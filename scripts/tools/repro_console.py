#!/usr/bin/env python3
"""Drive the console panel's REPL and report what shows up without a refresh.

Opens a page, opens the Console panel, evaluates `console.log(1)` from the input bar and prints our
rows before and after. The line the page prints has to show up on its own: it is what the panel only
ever showed on reopening.

The second command logs from a `setTimeout`, that is after the evaluation has returned, so a line
appearing from it cannot be our callback simply happening to run late.

    python scripts/tools/repro_console.py
    python scripts/tools/repro_console.py --device SERIAL --url example.com
"""
from __future__ import annotations

import argparse
import os
import time

import adb

OUT_DIR = os.path.join(os.path.dirname(__file__), "out")
REPORT: list[str] = []

IMMEDIATE = "console.log(1)"
DEFERRED = "setTimeout(function(){console.log(4242)},800)"


def say(line: str) -> None:
    """Report through a file: the console here mangles anything that is not ASCII."""
    print(line)
    REPORT.append(line)


def rows(serial: str) -> list[tuple[str, str]]:
    """(title, summary) of every row on screen, in dump order."""
    out: list[tuple[str, str]] = []
    title: str | None = None
    for node in adb.nodes(serial):
        if node.resource_id.endswith(":id/title"):
            title = node.text
        elif node.resource_id.endswith(":id/summary") and title is not None:
            out.append((title, node.text or ""))
            title = None
    return out


def summaries(serial: str) -> list[str]:
    return [summary for _, summary in rows(serial)]


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
    path = os.path.join(OUT_DIR, f"{serial.replace(':', '_')}_console_{name}.png")
    adb.screenshot(serial, path)
    say(f"  shot {path}")


def open_console_panel(serial: str) -> None:
    """The Console entry is in the tab menu, the main menu only switches to it."""
    tap_node(serial, adb.find_node(serial, ":id/button_more"), "button_more")
    if adb.find_node(serial, ":id/menuItemTabMenu") is not None:
        tap_node(serial, adb.find_node(serial, ":id/menuItemTabMenu"), "tab menu switcher")
    adb.wait_until(lambda: adb.find_node(serial, ":id/menuItemConsole") is not None, timeout=5)
    tap_node(serial, adb.find_node(serial, ":id/menuItemConsole"), "Console", wait=1.5)


def evaluate(serial: str, code: str) -> bool:
    """Type [code] into the input bar and run it."""
    field = adb.find_node(serial, ":id/consoleInput")
    if not tap_node(serial, field, "console input", wait=1.0):
        return False
    # The bar keeps what a previous run left in it
    adb.clear_field(serial)
    adb.type_text(serial, code, wait=1.0)
    if not tap_node(serial, adb.find_node(serial, ":id/consoleRun"), "run button", wait=1.5):
        return False
    return True


def run(serial: str, url: str) -> None:
    package = adb.detect_package(serial)
    say(f"=== {adb.device_label(serial)} [{package}] ===")

    adb.restart(serial, package)
    if not adb.settle(serial, package):
        say("  WARNING: not settled")
    adb.navigate(serial, package, url)
    open_console_panel(serial)

    say("-- before anything is run")
    before = rows(serial)
    for title, summary in before:
        say(f"  {title!r} | {summary!r}")
    shot(serial, "before")

    say(f"-- {IMMEDIATE}")
    if not evaluate(serial, IMMEDIATE):
        shot(serial, "no_input")
        return
    immediate = rows(serial)
    for title, summary in immediate:
        say(f"  {title!r} | {summary!r}")
    shot(serial, "immediate")
    say(f"  REPL row: {any(t == '> ' + IMMEDIATE for t, _ in immediate)}")
    say(f"  logged line without a refresh: {'1' in summaries(serial)}")

    say(f"-- {DEFERRED}")
    if not evaluate(serial, DEFERRED):
        return
    # The page logs this one well after our evaluation returned
    time.sleep(2.0)
    later = rows(serial)
    for title, summary in later:
        say(f"  {title!r} | {summary!r}")
    shot(serial, "deferred")
    say(f"  deferred line without a refresh: {'4242' in summaries(serial)}")
    say(f"  header summary: {later[0][1]!r}" if later else "  !! no rows")


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
        report = os.path.join(OUT_DIR, f"{serial.replace(':', '_')}-console.txt")
        with open(report, "w", encoding="utf-8") as file:
            file.write("\n".join(REPORT) + "\n")
        print(f"report: {report}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
