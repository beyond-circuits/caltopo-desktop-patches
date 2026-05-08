#!/usr/bin/env python3
"""
Bridges FTM-400XDR serial APRS output to CalTopo Desktop.

CalTopo initializes the TNC with a TNC2 command handshake (\r\n sequences
expecting 'cmd:' prompts, then a KISS mode command like 'INTFACE KISS').
This script emulates that handshake, then sends decoded APRS data as
KISS-framed AX.25 packets.
"""
import fcntl
import os
import pty
import re
import subprocess
import threading
import tty
import serial
import aprslib

TIOCEXCL = 0x540C  # prevent other processes from opening this port

SERIAL_PORT = '/dev/ttyUSB0'
BAUD_RATE = 9600
TOPO_PROPS = os.path.expanduser('~/topo.properties')
STABLE_LINK = '/dev/ttyAPRS'
STABLE_KEY = STABLE_LINK.removeprefix('/dev/')

HEADER_RE = re.compile(r'^(.+?) \[\d{2}/\d{2}/\d{2} \d{2}:\d{2}:\d{2}\] <UI[^>]*>:\s*$')

write_lock = threading.Lock()


# --- AX.25 / KISS encoding ---

def ax25_addr(callsign_str, last=False):
    parts = callsign_str.split('-')
    call = parts[0].upper().ljust(6)[:6]
    ssid = int(parts[1]) if len(parts) > 1 else 0
    addr = bytes(ord(c) << 1 for c in call)
    ssid_byte = 0x60 | ((ssid & 0x0F) << 1) | (0x01 if last else 0x00)
    return addr + bytes([ssid_byte])


def kiss_frame(src, dst, info):
    ax25 = (
        ax25_addr(dst, last=False) +
        ax25_addr(src, last=True) +
        bytes([0x03, 0xF0]) +
        info.encode('ascii', errors='replace')
    )
    stuffed = bytearray()
    for b in ax25:
        if b == 0xC0:
            stuffed += b'\xdb\xdc'
        elif b == 0xDB:
            stuffed += b'\xdb\xdd'
        else:
            stuffed.append(b)
    return bytes([0xC0, 0x00]) + bytes(stuffed) + bytes([0xC0])


# --- APRS decoding ---

def decode_to_position(header, info_field):
    try:
        parsed = aprslib.parse(f"{header}:{info_field}")
    except Exception as e:
        print(f"[warn] aprslib: {e}")
        return None, None
    lat = parsed.get('latitude')
    lon = parsed.get('longitude')
    if lat is None or lon is None:
        return None, None
    lat_deg = int(abs(lat))
    lat_min = (abs(lat) - lat_deg) * 60
    lon_deg = int(abs(lon))
    lon_min = (abs(lon) - lon_deg) * 60
    lat_str = f"{lat_deg:02d}{lat_min:05.2f}{'N' if lat >= 0 else 'S'}"
    lon_str = f"{lon_deg:03d}{lon_min:05.2f}{'E' if lon >= 0 else 'W'}"
    return header.split('>')[0], f"!{lat_str}/{lon_str}-"


# --- TNC2 command handshake emulation ---

def tnc_command_handler(master_fd):
    """
    Respond to CalTopo's TNC2 init sequence.
    CalTopo sends \r\n to get 'cmd:' prompts, then sends a KISS mode command.
    After that it expects KISS frames.
    """
    buf = b''
    kiss_mode = False
    while True:
        try:
            data = os.read(master_fd, 256)
        except OSError:
            break
        if not data:
            continue
        print(f"[caltopo->tnc] hex={data.hex()}  repr={repr(data)}")
        if kiss_mode:
            continue
        buf += data
        while b'\r' in buf:
            idx = buf.index(b'\r')
            line = buf[:idx].decode('ascii', errors='replace').strip()
            buf = buf[idx + 1:]
            if buf.startswith(b'\n'):
                buf = buf[1:]
            if line:
                print(f"[tnc_cmd] {repr(line)}")
                if any(k in line.upper() for k in ['KISS', 'INTFACE', 'RESTART']):
                    print("[tnc] Entering KISS mode — CalTopo should now accept KISS frames")
                    kiss_mode = True
                    break
            # Respond with TNC command prompt
            with write_lock:
                os.write(master_fd, b'\r\ncmd: ')


# --- Setup helpers ---

def check_port_in_use():
    result = subprocess.run(['fuser', SERIAL_PORT], capture_output=True, text=True)
    if result.stdout.strip():
        print(f"WARNING: {SERIAL_PORT} is held by PID(s) {result.stdout.strip()}.")
        print("Stop CalTopo before running this script.\n")


def create_stable_link(slave_name):
    subprocess.run(['sudo', 'rm', '-f', STABLE_LINK])
    subprocess.run(['sudo', 'ln', '-s', slave_name, STABLE_LINK], check=True)
    print(f"Created {STABLE_LINK} -> {slave_name}")


def remove_stable_link():
    subprocess.run(['sudo', 'rm', '-f', STABLE_LINK])
    print(f"Removed {STABLE_LINK}")


def update_topo_properties():
    lines = []
    if os.path.exists(TOPO_PROPS):
        with open(TOPO_PROPS) as f:
            lines = f.readlines()
    lines = [l for l in lines if not re.match(r'sarsoft\.location\.serial\.', l)]
    if not any('sarsoft.location.aprs.local.enabled' in l for l in lines):
        lines.append('sarsoft.location.aprs.local.enabled=true\n')
    lines.append(f'sarsoft.location.serial.{STABLE_KEY}={BAUD_RATE},8,1,0\n')
    lines.append(f'sarsoft.location.serial.{STABLE_KEY}.kiss=true\n')
    with open(TOPO_PROPS, 'w') as f:
        f.writelines(lines)
    print(f"Updated {TOPO_PROPS}:")
    with open(TOPO_PROPS) as f:
        print(f.read())
    print("Start CalTopo now, then send a beacon.\n")


# --- Main ---

def main():
    check_port_in_use()

    master_fd, slave_fd = pty.openpty()
    tty.setraw(slave_fd)
    slave_name = os.ttyname(slave_fd)
    print(f"Virtual port: {slave_name}")

    create_stable_link(slave_name)
    update_topo_properties()

    try:
        ser = serial.Serial(SERIAL_PORT, BAUD_RATE, bytesize=8, stopbits=1,
                            parity='N', timeout=5)
        fcntl.ioctl(ser.fd, TIOCEXCL)
        print(f"Locked {SERIAL_PORT} exclusively.")
    except serial.SerialException as e:
        print(f"ERROR opening {SERIAL_PORT}: {e}")
        remove_stable_link()
        return

    threading.Thread(target=tnc_command_handler, args=(master_fd,), daemon=True).start()

    print(f"Listening on /dev/ttyUSB0 at {BAUD_RATE} baud... (Ctrl-C to stop)")

    pending_header = None

    try:
        while True:
            raw = ser.readline()
            if not raw:
                continue
            line = raw.decode('ascii', errors='replace').rstrip('\r\n')
            if not line:
                continue

            print(f"[raw] {repr(line)}")

            m = HEADER_RE.match(line)
            if m:
                pending_header = m.group(1)
                continue

            if pending_header is not None:
                callsign, aprs_info = decode_to_position(pending_header, line)
                if callsign and aprs_info:
                    frame = kiss_frame(callsign, 'APRS', aprs_info)
                    with write_lock:
                        os.write(master_fd, frame)
                    print(f"[kiss] {callsign}>APRS:{aprs_info}  ({len(frame)} bytes)")
                pending_header = None
                continue

            pending_header = None

    except KeyboardInterrupt:
        print("\nStopping.")
    finally:
        ser.close()
        os.close(master_fd)
        os.close(slave_fd)
        remove_stable_link()


if __name__ == '__main__':
    main()
