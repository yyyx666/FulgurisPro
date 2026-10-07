#!/usr/bin/env python3
"""Exercise the View source dialog: the server's own HTML first, the rendered DOM a switch away.

Opens a page, taps the View source entry in the tab overflow menu, then reads the dialog back:
the two views, the note explaining where the original came from, and that the switch swaps them.

With the default WebView engine the inspector captures no response of its own, so the original
comes from a re-fetch and the note says so - that is the path this checks. Point the network engine
at OkHttp to see the captured-response path instead, where the note is absent.

    python scripts/tools/repro_view_source.py
    python scripts/tools/repro_view_source.py --device SERIAL --url example.com
    python scripts/tools/repro_view_source.py --url fulguris://bookmarks   # the no-switch case
"""
from __future__ import annotations

import argparse
import os
import time

import adb

OUT_DIR = os.path.join(os.path.dirname(__file__), "out")
REPORT: list[str] = []


def say(line: str) -> None:
    """Report through a file: the console here mangles anything that is not ASCII."""
    print(line)
    REPORT.append(line)


def node_by_id(serial: str, id_suffix: str):
    return adb.find_node(serial, id_suffix)


def tap_node(serial: str, node, what: str, wait: float = 1.0) -> bool:
    if node is None or node.bounds is None:
        say(f"  !! no node for {what}")
        return False
    x1, y1, x2, y2 = node.bounds
    x, y = (x1 + x2) // 2, (y1 + y2) // 2
    say(f"  tap {what} @({x},{y})")
    adb.tap(serial, x, y, wait=wait)
    return True


def shot(serial: str, name: str) -> None:
    path = os.path.join(OUT_DIR, f"{serial.replace(':', '_')}_view_source_{name}.png")
    adb.screenshot(serial, path)
    say(f"  shot {path}")


def code_text(serial: str) -> str:
    """The dialog's code view contents, as much of it as uiautomator reports."""
    node = node_by_id(serial, ":id/dialog_multi_line")
    return node.text if node else ""


def open_view_source(serial: str) -> bool:
    adb.wait_until(lambda: node_by_id(serial, ":id/button_more") is not None, timeout=10)
    tap_node(serial, node_by_id(serial, ":id/button_more"), "button_more")
    if node_by_id(serial, ":id/menuItemTabMenu") is not None:
        tap_node(serial, node_by_id(serial, ":id/menuItemTabMenu"), "tab menu switcher")
    adb.wait_until(lambda: node_by_id(serial, ":id/menuItemViewSource") is not None, timeout=5)
    return tap_node(serial, node_by_id(serial, ":id/menuItemViewSource"), "View source", wait=2.0)


def run(serial: str, url: str) -> None:
    package = adb.detect_package(serial)
    say(f"=== {adb.device_label(serial)} [{package}] ===")

    adb.restart(serial, package)
    if not adb.settle(serial, package):
        say("  WARNING: not settled")
    adb.navigate(serial, package, url)

    if not open_view_source(serial):
        shot(serial, "no_menu_item")
        return

    say("-- the dialog")
    switch = node_by_id(serial, ":id/view_source_switch")
    note = node_by_id(serial, ":id/view_source_note")
    original = node_by_id(serial, ":id/view_source_original")
    rendered = node_by_id(serial, ":id/view_source_rendered")
    say(f"  switch present   : {switch is not None}")
    say(f"  original label   : {original.text if original else '<none>'}")
    say(f"  rendered label   : {rendered.text if rendered else '<none>'}")
    say(f"  note             : {note.text if note and note.text else '<none>'}")
    shot(serial, "source")

    shown = code_text(serial)
    say(f"  shown source is {len(shown)} chars, starts: {shown[:60]!r}")
    if not shown.strip():
        say("  !! the dialog showed no source at all")
        return

    if switch is None:
        # An internal page: one view, and no switch to fake a second with
        say("  no switch, as an internal page has only its rendered DOM")
        return

    say("-- switching to the rendered DOM")
    if not tap_node(serial, rendered, "rendered radio", wait=1.5):
        return
    rendered_text = code_text(serial)
    say(f"  rendered view has {len(rendered_text)} chars, starts: {rendered_text[:60]!r}")
    shot(serial, "rendered")

    if rendered_text == shown:
        say("  !! the switch did not change what is shown")
    else:
        say("  the two views differ, as the server's HTML and the live DOM should")

    say("-- back to the original")
    tap_node(serial, original, "original radio", wait=1.5)
    if code_text(serial) != shown:
        say("  !! switching back lost the original view")
    else:
        say("  switching back shows the original again")


def main() -> int:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("--device", help="Target a specific adb device serial")
    parser.add_argument("--all", action="store_true", help="Act on all connected devices")
    parser.add_argument("--url", default="example.com", help="Page to open first")
    args = parser.parse_args()

    os.makedirs(OUT_DIR, exist_ok=True)
    for serial in adb.resolve_devices(args.device, args.all):
        run(serial, args.url)
        time.sleep(0.5)
        report = os.path.join(OUT_DIR, f"{serial.replace(':', '_')}-view-source.txt")
        with open(report, "w", encoding="utf-8") as file:
            file.write("\n".join(REPORT) + "\n")
        print(f"report: {report}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
