#!/usr/bin/env python3
"""Exercise the requests panel's filters: the URL field and the type row.

Opens a page that made one HTML and one JavaScript request, then checks that typing in the URL field
narrows the list, and that the type row's dialog does too.

    python scripts/tools/repro_filters.py
    python scripts/tools/repro_filters.py --device SERIAL --url example.com
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


def titles(serial: str) -> list[str]:
    """Every preference row title on screen, in dump order."""
    return [
        n.text
        for n in adb.nodes(serial)
        if n.text and n.resource_id.endswith(":id/title")
    ]


def node_by_title(serial: str, title: str):
    for node in adb.nodes(serial):
        if node.resource_id.endswith(":id/title") and node.text == title:
            return node
    return None


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
    path = os.path.join(OUT_DIR, f"{serial.replace(':', '_')}_filters_{name}.png")
    adb.screenshot(serial, path)
    say(f"  shot {path}")


def request_rows(serial: str) -> list[str]:
    """The rows that are requests, told apart from our own rows by their leading path."""
    return [t for t in titles(serial) if t.startswith("/")]


def find_search_field(serial: str):
    """The URL filter field.

    It has no id by the time it is on screen: the field is the root of its own row, and binding a
    row sets the root's id. It is found as the one bare field in there.
    """
    for node in adb.nodes(serial):
        if node.cls.endswith("EditText") and not node.resource_id:
            return node
    return None


def open_requests_panel(serial: str) -> None:
    tap_node(serial, adb.find_node(serial, ":id/button_more"), "button_more")
    if adb.find_node(serial, ":id/menuItemTabMenu") is not None:
        tap_node(serial, adb.find_node(serial, ":id/menuItemTabMenu"), "tab menu switcher")
    adb.wait_until(
        lambda: adb.find_node(serial, ":id/menuItemPageRequests") is not None, timeout=5
    )
    tap_node(serial, adb.find_node(serial, ":id/menuItemPageRequests"), "Requests", wait=1.5)


def run(serial: str, url: str) -> None:
    package = adb.detect_package(serial)
    say(f"=== {adb.device_label(serial)} [{package}] ===")

    adb.restart(serial, package)
    if not adb.settle(serial, package):
        say("  WARNING: not settled")
    adb.navigate(serial, package, url)
    open_requests_panel(serial)

    say("-- unfiltered")
    all_rows = request_rows(serial)
    say(f"  requests: {all_rows}")
    shot(serial, "list")
    if len(all_rows) < 2:
        say("  !! need at least two requests to tell filtering apart")
        return

    say("-- typing a URL fragment into the field")
    field = find_search_field(serial)
    if not tap_node(serial, field, "URL filter field", wait=1.0):
        shot(serial, "no_field")
        return
    adb.type_text(serial, "s.js", wait=1.5)
    say(f"  requests: {request_rows(serial)}")
    shot(serial, "url_filtered")

    # A fresh panel for the type row: the field keeps what was typed into it, and this check wants
    # the whole list back. Back puts the keyboard away first and only closes the panel after that.
    for _ in range(3):
        if adb.find_node(serial, ":id/button_more") is not None:
            break
        adb.key(serial, adb.KEY_BACK, wait=1.5)
    open_requests_panel(serial)

    say("-- the type row's dialog")
    rows = titles(serial)
    say(f"  rows: {rows}")
    # The back row, then ours, in the order RequestsFragment adds them: type first, method second
    if len(rows) < 3:
        say("  !! expected our two filter rows before the requests")
        shot(serial, "no_filter_rows")
        return
    if not tap_node(serial, node_by_title(serial, rows[1]), "type filter row", wait=1.5):
        return

    items = [n.text for n in adb.nodes(serial) if n.resource_id.endswith(":id/text1")]
    say(f"  dialog offers: {items}")
    shot(serial, "type_dialog")

    # JS reads the same in every locale, and one of our two requests is a script
    if not tap_node(serial, node_by_text(serial, "JS"), "JS", wait=1.5):
        return
    say(f"  requests: {request_rows(serial)}")
    shot(serial, "type_filtered")


def node_by_text(serial: str, text: str):
    for node in adb.nodes(serial):
        if node.text == text:
            return node
    return None


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
        report = os.path.join(OUT_DIR, f"{serial.replace(':', '_')}-filters.txt")
        with open(report, "w", encoding="utf-8") as file:
            file.write("\n".join(REPORT) + "\n")
        print(f"report: {report}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
