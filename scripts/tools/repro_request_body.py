#!/usr/bin/env python3
"""Check that a POST made by the page shows up with its request body, status and response body.

A POST is where the inspector's own plumbing cannot help: WebView hands us no request body, and the
network engines hand a POST straight back to WebView rather than answering it themselves, so neither
the body sent nor the response received ever reaches us that way. What the page's own fetch and
XMLHttpRequest see is therefore the only source, and this script serves a page that makes exactly
those two POSTs and then reads the request rows back.

Serves the page from this machine over HTTP, reached through ``adb reverse`` rather than the
emulator's 10.0.2.2 host alias: that alias depends on the host's firewall letting the emulator in,
and on a stock Windows box it does not.

    python scripts/tools/repro_request_body.py
    python scripts/tools/repro_request_body.py --device SERIAL
"""
from __future__ import annotations

import argparse
import http.server
import json
import os
import re
import socket
import threading
import time

import adb

OUT_DIR = os.path.join(os.path.dirname(__file__), "out")
STRINGS_DIR = os.path.join(os.path.dirname(__file__), "..", "..", "app", "src", "main", "res")
REPORT: list[str] = []

# The two payloads the page sends, and a marker we look for to tell the response body apart from
# everything else on screen. Both are unlikely to appear by accident.
JSON_PAYLOAD = '{"greeting":"hello-from-fetch"}'
FORM_PAYLOAD = "alpha=1&beta=hello-from-xhr"

# What to match on inside the panel: a JSON body is pretty-printed before it is shown, so the
# payload as sent is not there to find - only the value inside it survives the reformatting.
JSON_MARKER = "hello-from-fetch"
FORM_MARKER = "hello-from-xhr"

# The pane's two sides, as the resource ids of the tab row that switches between them. One side is a
# long list on its own, so reading both means crossing back to that row, which does not stay in view.
TAB_REQUEST = ":id/request_tabs_request"
TAB_RESPONSE = ":id/request_tabs_response"

PAGE = """<!doctype html>
<html><head><meta charset=utf-8><title>POST probe</title></head>
<body>
<p>POST probe</p>
<script>
// Ask what the observer has managed to install, at the two moments that matter: while the page is
// still being parsed (when a page's own first request goes out) and once it has settled. A probe
// at parse time that finds nothing while the later one finds everything dates the injection.
function reportState(at) {
    var probe = new XMLHttpRequest();
    probe.open('POST', '/diag');
    probe.send(JSON.stringify({
        at: at,
        installed: window._fulgurisNetworkInstalled === true,
        wsInstalled: window._fulgurisWsInstalled === true,
        fetchPatched: String(window.fetch).indexOf('[native code]') < 0,
        xhrPatched: String(XMLHttpRequest.prototype.send).indexOf('[native code]') < 0
    }));
}
reportState('parse');
setTimeout(function () { reportState('late'); }, 1500);
</script>
<script>
fetch('/echo', {
    method: 'POST',
    headers: {'Content-Type': 'application/json'},
    body: '%(json)s'
}).then(function (r) { return r.text(); }).then(function (t) { console.log('fetch done ' + t); });

var xhr = new XMLHttpRequest();
xhr.open('POST', '/echo');
xhr.setRequestHeader('Content-Type', 'application/x-www-form-urlencoded');
xhr.send('%(form)s');
</script>
</body></html>
""" % {"json": JSON_PAYLOAD, "form": FORM_PAYLOAD}


def say(line: str) -> None:
    """Report through a file: the console here mangles anything that is not ASCII."""
    print(line)
    REPORT.append(line)


class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self) -> None:  # noqa: N802 - the name http.server calls
        body = PAGE.encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self) -> None:  # noqa: N802 - the name http.server calls
        length = int(self.headers.get("Content-Length") or 0)
        received = self.rfile.read(length).decode("utf-8", "replace")
        if self.path == "/diag":
            # The page telling us what the observer managed to install, rather than a POST to echo
            say(f"  page says        : {received}")
            self.send_response(204)
            self.end_headers()
            return
        body = json.dumps({"echo-marker": received}).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args) -> None:
        """Stay quiet: the page's requests are what we are here to look at, not ours."""


def start_server() -> tuple[http.server.ThreadingHTTPServer, int]:
    """Serve on a free loopback port, the one `adb reverse` will point the device at."""
    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    port = server.server_address[1]
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server, port


def reverse(serial: str, port: int, on: bool) -> None:
    """Point the device's own localhost at ours, or stop doing so.

    Both specs given even though they are the same port: the single-spec shorthand is not accepted
    by every adb, and the failure is quiet enough to look like the page itself misbehaving.
    """
    spec = "tcp:%d" % port
    adb._adb(serial, ["reverse", spec, spec] if on else ["reverse", "--remove", spec], timeout=30)


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
    path = os.path.join(OUT_DIR, f"{serial.replace(':', '_')}_request_body_{name}.png")
    adb.screenshot(serial, path)
    say(f"  shot {path}")


def node_by_title(serial: str, title: str):
    """The row whose label is [title]: a preference's title view, requests included."""
    for node in adb.nodes(serial):
        if node.resource_id.endswith(":id/title") and node.text == title:
            return node
    return None


def rows_by_title(serial: str, title: str) -> list:
    """Every row with that label: our page posts to /echo twice, so there are two of them."""
    return [
        node for node in adb.nodes(serial)
        if node.resource_id.endswith(":id/title") and node.text == title
    ]


def node_by_text(serial: str, text: str):
    """Any node labelled [text], be it a preference row or a dialog's own contents."""
    for node in adb.nodes(serial):
        if node.text == text:
            return node
    return None


def string(serial: str, name: str) -> str:
    """One of the app's own <string> values, in the device's language.

    The panel is labelled straight from these resources, so reading them here lets the script drive
    a device of any locale instead of pinning our zh-CN emulator into it.
    """
    locale = adb._adb(serial, ["shell", "getprop", "persist.sys.locale"]).strip()
    language, _, region = locale.partition("-")
    folders = [f"values-{language}-r{region}", f"values-{language}", "values"]
    for folder in folders:
        path = os.path.join(STRINGS_DIR, folder, "strings.xml")
        if not os.path.isfile(path):
            continue
        with open(path, encoding="utf-8") as file:
            found = re.search(rf'<string name="{name}">(.*?)</string>', file.read(), re.S)
        if found:
            return found.group(1)
    say(f"  !! no string resource named {name}")
    return ""


def read_body(serial: str, row_label: str, tab: str, what: str) -> str:
    """Open the body sitting behind its own row, and hand back what the dialog shows.

    Neither body is printed on the pane: each has a row that opens it in a dialog, so the script has
    to do what a reader does.
    """
    if not row_label:
        say(f"  !! could not resolve the label of the {what} row")
        return ""
    row = find_row(serial, row_label, tab)
    if not tap_node(serial, row, f"{what} row", wait=1.5):
        say(f"  !! no {what} row labelled {row_label!r}")
        return ""
    shown = "\n".join(texts(serial))
    adb.key(serial, adb.KEY_BACK, wait=1.0)
    return shown


def request_rows(serial: str) -> list[str]:
    """The rows that are requests, told apart from our own rows by their leading path."""
    return [t for t in (n.text for n in adb.nodes(serial) if n.resource_id.endswith(":id/title")) if t.startswith("/")]


def open_requests_panel(serial: str) -> bool:
    adb.wait_until(lambda: adb.find_node(serial, ":id/button_more") is not None, timeout=10)
    tap_node(serial, adb.find_node(serial, ":id/button_more"), "button_more")
    # The popup opens on whichever menu was last used, and Requests lives in the tab menu
    if adb.find_node(serial, ":id/menuItemTabMenu") is not None:
        tap_node(serial, adb.find_node(serial, ":id/menuItemTabMenu"), "tab menu switcher")
    adb.wait_until(lambda: adb.find_node(serial, ":id/menuItemPageRequests") is not None, timeout=5)
    return tap_node(serial, adb.find_node(serial, ":id/menuItemPageRequests"), "Requests", wait=1.5)


def texts(serial: str) -> list[str]:
    return [n.text for n in adb.nodes(serial) if n.text]


def scroll_down(serial: str) -> None:
    """Drag the pane up by about a screenful: a controlled drag, not a fling past what we want."""
    width, height = adb.screen_size(serial)
    x = width // 2
    adb._adb(serial, ["shell", "input", "swipe", str(x), str(height * 3 // 4), str(x), str(height // 3), "400"])
    time.sleep(0.8)


def back_to_tabs(serial: str, tab: str):
    """The pane's own tab row, brought back into view if it has been scrolled away.

    One side of a request is one long list, its tab row sitting at the very top and scrolling off
    with the rest, and we come back to it every time we cross to the other side. Coming back up is a
    downward drag though, which is also how a bottom sheet is dismissed: once the list is at its top
    a further drag closes the whole pane, so we check after every swipe and stop the moment the row
    is on screen.
    """
    node = adb.find_node(serial, tab)
    if node is not None:
        return node

    width, height = adb.screen_size(serial)
    x = width // 2
    for _ in range(6):
        adb._adb(serial, ["shell", "input", "swipe", str(x), str(height // 4), str(x), str(height // 2), "300"])
        time.sleep(0.6)
        node = adb.find_node(serial, tab)
        if node is not None:
            return node
        if adb.find_node(serial, ":id/design_bottom_sheet") is None:
            say("  !! the pane closed on us while scrolling back up")
            return None
    say(f"  !! never reached the tab row {tab}")
    return None


def open_tab(serial: str, tab: str, what: str) -> bool:
    """Show one of the two sides of the pane."""
    return tap_node(serial, back_to_tabs(serial, tab), what, wait=1.0)


def pane_texts(serial: str, tab: str, wanted: list[str], pages: int = 8) -> list[str]:
    """What one side of the pane says, scrolling until the rows we came for have turned up.

    A preference pane only builds the rows it shows, so a row is not in the tree at all until
    scrolled into view.
    """
    if not open_tab(serial, tab, "tab"):
        return []
    seen: list[str] = []
    for _ in range(pages):
        fresh = [t for t in texts(serial) if t not in seen]
        seen.extend(fresh)
        if wanted and all(any(w in t for t in seen) for w in wanted):
            break
        scroll_down(serial)
    return seen


def find_row(serial: str, label: str, tab: str):
    """The row labelled [label] on the [tab] side, wherever it sits in that list.

    Reach that side first - a row of the hidden one is not in the tree at all - then walk down from
    the tab row: the row may be below the fold, and it may equally have been scrolled past already.
    """
    if not open_tab(serial, tab, "tab"):
        return None
    for _ in range(10):
        node = node_by_text(serial, label)
        if node is not None:
            return node
        scroll_down(serial)
    return None


def run(serial: str) -> None:
    package = adb.detect_package(serial)
    server, port = start_server()
    reverse(serial, port, on=True)
    say(f"=== {adb.device_label(serial)} [{package}] localhost:{port} ===")

    try:
        adb.restart(serial, package)
        if not adb.settle(serial, package):
            say("  WARNING: not settled")
        adb.navigate(serial, package, f"http://localhost:{port}/")
        # The page makes both POSTs from its own inline script, so they land as it loads
        time.sleep(3)

        if not open_requests_panel(serial):
            shot(serial, "no_panel")
            return

        rows = request_rows(serial)
        say(f"  requests: {rows}")
        shot(serial, "list")
        if not rows:
            say("  !! no request rows at all")
            return

        labels = {
            name: string(serial, name) for name in (
                "request_payload_view",   # the row that opens the request body
                "request_body_view",      # and the one that opens the response body
                "request_status",         # where the response's status code is shown
                "request_duration",       # and its timing
                "body_view_text",         # the readings the body dialog offers
                "body_view_json",
                "request_body_raw",
            )
        }

        # The page posts to /echo twice, once through fetch and once through XHR: two wrappers, so
        # check them both rather than trusting one to speak for the other
        targets = rows_by_title(serial, "/echo") or rows_by_title(serial, rows[-1])
        if not targets:
            say("  !! no request row to open")
            shot(serial, "no_row")
            return

        found: set[str] = set()
        for index, row in enumerate(targets):
            say(f"-- /echo row {index + 1} of {len(targets)}")
            if not tap_node(serial, row, f"row {index + 1}", wait=1.5):
                shot(serial, f"no_row_{index + 1}")
                return

            # The status, type and timing all live on the request side, below the URL details
            say("   request side")
            pane = "\n".join(pane_texts(serial, TAB_REQUEST, [labels["request_status"], labels["request_duration"]]))
            for name in ("request_status", "request_duration"):
                say(f"    {name:16}: {'shown' if labels[name] and labels[name] in pane else 'MISSING'}")
            if labels["request_status"] in pane and "200" in pane:
                found.add("status")

            request = read_body(serial, labels["request_payload_view"], TAB_REQUEST, "request body")
            shot(serial, f"detail_{index + 1}_request")
            for marker in (JSON_MARKER, FORM_MARKER):
                if marker in request:
                    found.add(marker)
                    say(f"    body shown: {marker!r}")

            say("   response side")
            response = read_body(serial, labels["request_body_view"], TAB_RESPONSE, "response body")
            shot(serial, f"detail_{index + 1}_response")

            # The dialog does not commit to one reading of the body: it offers the readings this body
            # supports, opening on the one the Content-Type called for
            readings = [labels[name] for name in ("body_view_text", "body_view_json", "request_body_raw")]
            say(f"    readings offered: {[name for name in readings if name and name in response]}")
            if all(labels[name] and labels[name] in response for name in ("body_view_text", "body_view_json")):
                found.add("switch")

            # Our server echoes the request body, so the response proves the other direction
            if "echo-marker" in response:
                echoed = next(t for t in response.splitlines() if "echo-marker" in t)
                say(f"    body shown: {echoed[:100]!r}")
                if any(marker in response for marker in (JSON_MARKER, FORM_MARKER)):
                    found.add("response")

            adb.key(serial, adb.KEY_BACK, wait=1.0)

        say("-- what the inspector showed")
        for what, key in (
            ("the JSON body the fetch sent", JSON_MARKER),
            ("the form body the XHR sent", FORM_MARKER),
            ("the echoed response body", "response"),
            ("the response status", "status"),
            ("the body read switch", "switch"),
        ):
            say(f"  {what:28}: {'shown' if key in found else 'MISSING'}")
    finally:
        reverse(serial, port, on=False)
        server.shutdown()


def main() -> int:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("--device", help="Target a specific adb device serial")
    parser.add_argument("--all", action="store_true", help="Act on all connected devices")
    args = parser.parse_args()

    os.makedirs(OUT_DIR, exist_ok=True)
    for serial in adb.resolve_devices(args.device, args.all):
        try:
            run(serial)
        except socket.error as error:  # a port we could not open is worth saying out loud
            say(f"  !! could not serve the probe page: {error}")
        time.sleep(0.5)
        report = os.path.join(OUT_DIR, f"{serial.replace(':', '_')}-request-body.txt")
        with open(report, "w", encoding="utf-8") as file:
            file.write("\n".join(REPORT) + "\n")
        print(f"report: {report}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
