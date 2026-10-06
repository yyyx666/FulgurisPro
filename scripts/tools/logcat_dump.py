#!/usr/bin/env python3
"""Dump the logcat of a device, optionally filtered, to scripts/tools/out/.

    python scripts/tools/logcat_dump.py                       # everything, to out/<serial>-logcat.txt
    python scripts/tools/logcat_dump.py --grep AndroidRuntime
    python scripts/tools/logcat_dump.py --grep Fulguris --clear
"""
from __future__ import annotations

import argparse
import os
import sys

sys.path.insert(0, os.path.dirname(__file__))
import adb

OUT_DIR = os.path.join(os.path.dirname(__file__), "out")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--device", help="Target a specific adb device serial")
    parser.add_argument("--all", action="store_true", help="Dump from every connected device")
    parser.add_argument("--grep", default="", help="Only keep lines matching this regex")
    parser.add_argument("--clear", action="store_true", help="Clear the buffer before dumping")
    parser.add_argument("--buffer", default="", help="Logcat buffer to read, e.g. crash, main (default: main)")
    parser.add_argument("--tail", type=int, default=0, help="Only keep the last N lines (0 = everything)")
    args = parser.parse_args()

    devices = adb.resolve_devices(args.device, args.all)

    # `adb logcat -d` comes back empty on some emulators, going through the shell always works
    call = ["shell", "logcat", "-d"]
    if args.buffer:
        call += ["-b", args.buffer]
    if args.tail:
        call += ["-t", str(args.tail)]

    for serial in devices:
        text = adb._adb(serial, call, timeout=60)
        if args.grep:
            text = "\n".join(line for line in text.splitlines() if args.grep in line)
        os.makedirs(OUT_DIR, exist_ok=True)
        name = f"{serial}-logcat{'-' + args.buffer if args.buffer else ''}.txt"
        path = os.path.join(OUT_DIR, name)
        with open(path, "w", encoding="utf-8") as f:
            f.write(text)
        print(f"{serial}: {len(text.splitlines())} lines -> {path}")

    return 0


if __name__ == "__main__":
    sys.exit(main())
