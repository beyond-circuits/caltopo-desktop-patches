# CalTopo Desktop Patches

Patches to `desktop.jar` developed against **CalTopo Desktop version 4254 (2026-03-02)**.

## What's patched

### 1. Serial port scanning bug fix (`APRSLocalEngine.java`)

**Problem:** CalTopo opens every serial port on the system at 9600,8,1,0 defaults when
no explicit configuration exists for that port. This causes CalTopo to attempt to read
from unrelated devices (GPS receivers, USB adapters, radios, etc.), consuming data that
other processes need.

**Fix:** Unconfigured ports are now skipped. Only ports with an explicit
`sarsoft.location.serial.<name>=...` entry in `topo.properties` are opened.
To explicitly disable a port: `sarsoft.location.serial.<name>=0,0,0,0`.

**Root cause in original code (`portDiscovery()`):**
```java
// Original — bug: default-opens every port
if (config == null || config.length() == 0) {
    config = "9600,8,1,0";
}
// Fixed — skip instead
if (config == null || config.length() == 0) {
    this.logger.d("Port " + shortPortName + " not configured; skipping.");
    continue;
}
```

---

### 2. Yaesu FTM-series TNC2 support (`YaesuSerialThread.java` + `APRSLocalEngine.java`)

**Problem:** Yaesu FTM-series radios (FTM-400XDR, etc.) output a non-standard two-line
TNC2 monitor format. The header and APRS info field are split across two lines:

```
KN6TYR-7>SVUWWX,WIDE1-1,WIDE2-1 [05/07/26 10:57:55] <UI R>:
`2[(l [/`_3
```

CalTopo's `APRSSerialThread` expects standard single-line TNC2 format
(`CALLSIGN>PATH:infofield`) and silently drops both lines.

**Fix:** New `YaesuSerialThread` class extends `APRSSerialThread` and overrides
`processLine()` to buffer the header line (matched by regex) and combine it with the
following info-field line before passing to the existing parser. Mic-E encoding
(backtick prefix, used by Yaesu HTs like the FT5D) is already handled by
`LocationParser` — no conversion needed.

**Configuration** (`topo.properties`):
```properties
sarsoft.location.serial.ttyUSB0=9600,8,1,0
sarsoft.location.serial.ttyUSB0.yaesu=true
```

This replaces any need for a separate bridge script.

---

### 3. Location report logging at INFO level (`LocalLocationsService.java`)

**Problem:** The "Local location report" log line (confirming a beacon was parsed and
dispatched to the map) was at DEBUG level, buried in verbose output.

**Fix:** Promoted to INFO. Combined with setting `log4j.logger.aprs.level=INFO` in
`topo.properties`, the log now shows exactly two lines per received beacon:

```
APRSLocalEngine  [INFO] Serial data: KN6TYR-7>SVUWWX,...:`2[(l [/`_3
LocalLocationsService [INFO] Local location report LOCAL:KN6TYR-7 @ 36.963,-122.052
```

---

## Files

| File | Status | Purpose |
|------|--------|---------|
| `src/.../APRSLocalEngine.java` | Modified | Serial port scan fix + Yaesu mode |
| `src/.../YaesuSerialThread.java` | New | Yaesu two-line TNC2 parser |
| `src/.../LocalLocationsService.java` | Modified | INFO-level location logging |
| `src/.../LiveTracksService.java` | Experimental / not built | Auto-title from callsign (ineffective — UI sends empty string, not null; kept for reference) |

---

## How to apply

### Prerequisites

```bash
sudo apt install default-jdk    # needs javac 11+
```

### Apply to a new desktop.jar

```bash
# Place the target desktop.jar at ~/desktop.jar (or edit JAR= in build.sh)
./build.sh
```

The script:
1. Compiles the patched sources against `desktop.jar`
2. Backs up the original to `desktop.jar.bak`
3. Writes patched `.class` files into the JAR using Python's zipfile (needed because
   Java 21's `jar` tool rejects the duplicate entries present in CalTopo's fat JAR)

### Porting to a new CalTopo version

1. Decompile the new JAR: `jadx -d decompiled desktop.jar`
2. Diff the new decompiled sources against the files in `src/` to check if CalTopo
   changed any of the patched classes
3. Update the source files as needed, re-run `build.sh`

---

## topo.properties (reference configuration)

```properties
sarsoft.location.aprs.local.enabled=true
sarsoft.location.serial.ttyUSB0=9600,8,1,0
sarsoft.location.serial.ttyUSB0.yaesu=true
log4j.logger.aprs.name=org.sarsoft.location
log4j.logger.aprs.level=INFO
```

---

## Bug report filed with CalTopo

See `../caltopo_serial_bug_report.txt` for the formal write-up of the serial port
scanning bug, suitable for sending to the CalTopo team.
