#!/usr/bin/env python3
"""Drive a request's detail screen and report what tapping Response does to it.

Navigates, opens the Requests panel, opens the first request, then taps the Response half of the
tab bar and prints the rows before and after, with a screenshot of each.

    python scripts/tools/repro_tabs.py
    python scripts/tools/repro_tabs.py --device SERIAL --url example.com
"""
from __future__ import annotations

import argparse
import os
import time

import adb

OUT_DIR = os.path.join(os.path.dirname(__file__), "out")


def titles(serial: str) -> list[str]:
    """Every preference row title on screen, in dump order."""
    return [
        n.text
        for n in adb.nodes(serial)
        if n.text and n.resource_id.endswith(":id/title")
    ]


def ids_and_texts(serial: str) -> list[str]:
    return [
        f"{n.resource_id.rsplit('/', 1)[-1]}={n.text!r}"
        for n in adb.nodes(serial)
        if n.text or n.resource_id
    ]


def tap_node(serial: str, node, what: str, wait: float = 1.0) -> bool:
    if node is None or node.bounds is None:
        print(f"  !! no node for {what}")
        return False
    x1, y1, x2, y2 = node.bounds
    x, y = (x1 + x2) // 2, (y1 + y2) // 2
    print(f"  tap {what} @({x},{y})")
    adb.tap(serial, x, y, wait=wait)
    return True


def shot(serial: str, name: str) -> None:
    path = os.path.join(OUT_DIR, f"{serial.replace(':', '_')}_tabs_{name}.png")
    adb.screenshot(serial, path)
    print(f"  shot {path}")


def run(serial: str, url: str) -> None:
    package = adb.detect_package(serial)
    print(f"=== {adb.device_label(serial)} [{package}] ===")
    adb.logcat(serial, "RTABS", clear=True)

    # A fresh process, so that what we tap is what was last installed
    adb.restart(serial, package)
    if not adb.settle(serial, package):
        print("  WARNING: not settled")
    adb.navigate(serial, package, url)

    print("-- open the menu")
    tap_node(serial, adb.find_node(serial, ":id/button_more"), "button_more")

    # The Requests entry is in the tab menu; the main menu only switches to it
    if adb.find_node(serial, ":id/menuItemTabMenu") is not None:
        tap_node(serial, adb.find_node(serial, ":id/menuItemTabMenu"), "tab menu switcher")

    if not adb.wait_until(
        lambda: adb.find_node(serial, ":id/menuItemPageRequests") is not None, timeout=5
    ):
        print("  !! the Requests entry never showed up")
        print("  " + "\n  ".join(ids_and_texts(serial)))
        shot(serial, "menu")
        return
    tap_node(serial, adb.find_node(serial, ":id/menuItemPageRequests"), "Requests", wait=1.5)

    print("-- requests panel")
    rows = titles(serial)
    print(f"  rows: {rows}")
    shot(serial, "list")

    # Rows come as: back row, then our headers, then the requests themselves. A path is a request.
    request = None
    for n in adb.nodes(serial):
        if n.text and n.resource_id.endswith(":id/title") and "/" in n.text:
            request = n
            break
    if not tap_node(serial, request, "first request row", wait=1.5):
        print("  " + "\n  ".join(ids_and_texts(serial)))
        shot(serial, "no_request_row")
        return

    print("-- request details, request pane")
    before = titles(serial)
    print(f"  rows: {before}")
    shot(serial, "detail_request")

    response = adb.find_node(serial, ":id/request_tabs_response")
    if not tap_node(serial, response, "Response half", wait=2.0):
        print("  " + "\n  ".join(ids_and_texts(serial)))
        shot(serial, "no_tabs")
        return

    print("-- after tapping Response")
    after = titles(serial)
    print(f"  rows: {after}")
    shot(serial, "detail_response")
    print(f"  CHANGED: {before != after}")

    request_tab = adb.find_node(serial, ":id/request_tabs_request")
    if not tap_node(serial, request_tab, "Request half", wait=2.0):
        return

    print("-- after tapping Request again")
    back = titles(serial)
    print(f"  rows: {back}")
    shot(serial, "detail_back_to_request")
    print(f"  BACK: {back == before}")


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
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
