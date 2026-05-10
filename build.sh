#!/bin/bash
set -e

JAR=/home/pejohnso/desktop.jar
SRC_DIR="$(cd "$(dirname "$0")/src" && pwd)"
OUT_DIR="$(cd "$(dirname "$0")/out" && pwd)"

if ! command -v javac &>/dev/null; then
    echo "ERROR: javac not found. Install with: sudo apt install default-jdk"
    exit 1
fi

echo "=== Compiling ==="
rm -rf "$OUT_DIR"
mkdir -p "$OUT_DIR"
javac -proc:none -cp "$JAR" -d "$OUT_DIR" \
    "$SRC_DIR/org/sarsoft/location/service/HamTracksParser.java" \
    "$SRC_DIR/org/sarsoft/location/service/APRSSerialThread.java" \
    "$SRC_DIR/org/sarsoft/location/service/YaesuSerialThread.java" \
    "$SRC_DIR/org/sarsoft/location/service/APRSLocalEngine.java" \
    "$SRC_DIR/org/sarsoft/location/service/LocalLocationsService.java"

echo "=== Backing up original JAR ==="
cp "$JAR" "${JAR}.bak"
echo "Backup: ${JAR}.bak"

echo "=== Patching JAR ==="
# Use Python to patch: fat JARs have duplicate entries that trip up Java 21's
# jar tool and the zip command; Python's zipfile module handles them cleanly.
python3 - "$JAR" "$OUT_DIR" <<'PYEOF'
import sys, zipfile
from pathlib import Path

jar_path = sys.argv[1]
out_dir  = Path(sys.argv[2])
tmp_path = jar_path + ".tmp"

patches = {
    "org/sarsoft/location/service/HamTracksParser.class",
    "org/sarsoft/location/service/APRSSerialThread.class",
    "org/sarsoft/location/service/APRSLocalEngine.class",
    "org/sarsoft/location/service/YaesuSerialThread.class",
    "org/sarsoft/location/service/LocalLocationsService.class",
}

with zipfile.ZipFile(jar_path, "r") as zin, \
     zipfile.ZipFile(tmp_path, "w", zipfile.ZIP_DEFLATED) as zout:
    seen = set()
    for item in zin.infolist():
        if item.filename in seen:
            continue        # drop duplicates that exist in the fat JAR
        seen.add(item.filename)
        if item.filename in patches:
            data = (out_dir / item.filename).read_bytes()
        else:
            data = zin.read(item.filename)
        zout.writestr(item, data)
    # Add YaesuSerialThread if it wasn't in the original JAR
    for name in patches - seen:
        zout.write(str(out_dir / name), name)

Path(tmp_path).replace(jar_path)
patched = patches & seen   # classes that replaced existing entries
added   = patches - seen   # classes that were new
print(f"  Replaced {len(patched)}, added {len(added)} class(es).")
PYEOF

echo ""
echo "=== Done ==="
echo ""
echo "Three changes are now in desktop.jar:"
echo "  1. Serial port scanning bug fixed — unconfigured ports are skipped."
echo "  2. New 'yaesu' mode for Yaesu FTM-series two-line TNC2 format."
echo "  3. HamTracks route packets ({{X\$HT) decoded and added to track."
echo ""
echo "To use Yaesu mode directly (no bridge script needed), set in topo.properties:"
echo "  sarsoft.location.serial.ttyUSB0=9600,8,1,0"
echo "  sarsoft.location.serial.ttyUSB0.yaesu=true"
echo ""
echo "Remove or comment out the ttyAPRS entries if you stop using the bridge script."
echo "Restart CalTopo to pick up the changes."
