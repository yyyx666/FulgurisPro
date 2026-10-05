"""Advanced feature tests (Batch 1): inspector panels, tab close entry, history page.

These cover the features added in the "advanced" batch:

* the **Close tab** entry, now exposed in the out-of-the-box tab menu (it used to
  be hidden behind the menu settings, and the per-tab long-press dialog had no
  caller at all);
* the **console** sheet's REPL, which evaluates an expression in the current page;
* the **storage** sheet, which dumps `localStorage`/`sessionStorage` of the page;
* the **requests** (network) sheet and its per-request detail;
* the **history** page, rendered with a search box, day headers and favicons.

The inspector panels are reached through the tab menu, and the pages under test
are served from the host over an `adb reverse` tunnel (Fulguris blocks `file://`
URLs), see cursor_tests.

The history page is an in-app `file://` WebView whose DOM uiautomator cannot see,
so instead of asserting on screen nodes we read back the page the app generated
(`files/history.html`, written when the page loads) — that is exactly the markup
our group headers, favicon `<img>` and search box live in.

    python scripts/tests/run.py --device SERIAL --group advanced
    python scripts/tests/run.py --device SERIAL --test advanced
"""
from __future__ import annotations

import re
import time

from cursor_tests import PORT, _ensure_reverse, _ensure_server
from empty_tab_tests import (
    _close_all_tabs,
    _empty_state_visible,
    _menu_item_ids,
    _node_texts,
    _open_webpage_menu,
    _tap_id,
    _wait_for_empty_state,
    _wait_for_webview,
)
from framework import keys

#: The page our advanced tests run against: it seeds both web storages and gives
#: itself a distinctive title, which Fulguris mirrors into the toolbar label.
TARGET_PAGE = "advanced_target.html"
TARGET_TITLE = "Advanced Target Page"
#: The storage items TARGET_PAGE seeds (read back by our storage inspector).
LOCAL_KEY, LOCAL_VALUE = "fulgurisKey", "fulgurisValue"
SESSION_KEY, SESSION_VALUE = "fulgurisSessionKey", "fulgurisSessionValue"

#: Console REPL expression + its expected result (1+1 evaluates to 2 in the page).
REPL_CODE, REPL_RESULT = "1+1", "2"


# ---------------------------------------------------------------------------
# Page + UI helpers
# ---------------------------------------------------------------------------


def _load_target(device, reset: bool = True) -> None:
    """Serve and open the advanced target page, waiting for it to finish loading.

    The page reflects its `document.title` into the toolbar label, so a matching
    label proves the page loaded and its scripts ran (see cursor_tests).
    """
    _ensure_server()
    _ensure_reverse(device)
    url = "http://localhost:%d/%s?cb=%d" % (PORT, TARGET_PAGE, int(time.time() * 1000))
    device.navigate(url, reset=reset)
    assert _wait_for_label(device, TARGET_TITLE, 30.0), (
        "the advanced target page did not load: toolbar label is "
        f"{device.field_text()!r} (expected {TARGET_TITLE!r})")


def _wait_for_label(device, label: str, timeout: float = 15.0) -> bool:
    """Poll until the toolbar label equals ``label`` (the page mirrored its title)."""
    deadline = time.time() + timeout
    while time.time() < deadline:
        if device.field_text() == label:
            return True
        time.sleep(0.5)
    return device.field_text() == label


def _wait_for_node(device, id_suffix: str, timeout: float = 15.0):
    """Poll until the node whose resource-id ends with ``id_suffix`` appears."""
    deadline = time.time() + timeout
    while time.time() < deadline:
        node = device.find_node(id_suffix)
        if node is not None:
            return node
        time.sleep(0.5)
    return device.find_node(id_suffix)


def _texts(device) -> list[str]:
    return [n.text or "" for n in device.nodes()]


def _has_text(device, needle: str) -> bool:
    return any(needle in text for text in _texts(device))


def _has_exact_text(device, text: str) -> bool:
    return any(text == node for node in _texts(device))


def _wait_for_text(device, needle: str, timeout: float = 15.0, exact: bool = False) -> bool:
    """Poll until a node's text equals (``exact``) or contains ``needle``."""
    match = _has_exact_text if exact else _has_text
    deadline = time.time() + timeout
    while time.time() < deadline:
        if match(device, needle):
            return True
        time.sleep(0.5)
    return match(device, needle)


def _open_sheet(device, item_id: str) -> None:
    """Open one of our inspector sheets from the tab menu.

    Asserts the entry is in the tab menu out of the box: these panels used to be
    hidden by default, which made the features unreachable without a trip to the
    menu settings.
    """
    _open_webpage_menu(device)
    assert item_id in _menu_item_ids(device), (
        f"{item_id} should be in the tab menu by default so the feature is "
        f"reachable (menu items: {sorted(_menu_item_ids(device))!r})")
    _tap_id(device, ":id/%s" % item_id)
    device.settle(timeout=20.0)


# ---------------------------------------------------------------------------
# Feature: closing an individual tab
# ---------------------------------------------------------------------------


def test_close_tab_entry_closes_the_current_tab(device, ctx: dict) -> None:
    """The tab menu's Close tab entry closes the current tab.

    Covers both halves of the feature: the entry is visible out of the box, and
    using it actually closes the tab (last tab -> the zero-tab empty state).
    """
    device.settle()
    device.key(keys.BACK, 1.0)  # drop any leftover sheet/keyboard state

    # Start from a single tab so "closed the tab" is unambiguous
    _close_all_tabs(device)
    _load_target(device, reset=False)  # from the empty state this opens exactly one tab
    assert _wait_for_webview(device), "precondition: one tab should be open"
    assert not _empty_state_visible(device), "precondition: the empty state should be gone"

    _open_webpage_menu(device)
    assert "menuItemCloseTab" in _menu_item_ids(device), (
        "the Close tab entry should be in the tab menu out of the box (it used "
        f"to hide behind the menu settings); menu items: {sorted(_menu_item_ids(device))!r}")
    _tap_id(device, ":id/menuItemCloseTab")

    assert _wait_for_empty_state(device, timeout=30.0), (
        "closing the last tab from the menu did not reach the zero-tab empty "
        f"state (nodes: {sorted(_node_texts(device))[:40]!r})")
    assert device.foreground_package() == device.package, (
        f"closing the last tab closed the app instead of showing the empty "
        f"state: foreground is {device.foreground_package()!r}")


# ---------------------------------------------------------------------------
# Feature: developer console REPL
# ---------------------------------------------------------------------------


def test_console_repl_evaluates_expression(device, ctx: dict) -> None:
    """The console sheet evaluates a JavaScript expression in the current page.

    Types ``1+1`` in the REPL bar and runs it: the sheet must show the entered
    expression and its result, which proves the input reaches the page's
    `evaluateJavascript` and comes back.
    """
    _load_target(device)
    _open_sheet(device, "menuItemConsole")

    field = _wait_for_node(device, ":id/consoleInput")
    assert field is not None and field.bounds, (
        f"the console REPL input should be in the sheet (nodes: {_texts(device)[:40]!r})")

    _tap_id(device, ":id/consoleInput")
    device.type_text(REPL_CODE, 0.6)
    _tap_id(device, ":id/consoleRun")

    assert _wait_for_text(device, "> %s" % REPL_CODE, 15.0), (
        f"the console should echo the expression (nodes: {_texts(device)[:40]!r})")
    assert _wait_for_text(device, REPL_RESULT, 15.0, exact=True), (
        f"evaluating {REPL_CODE!r} should display {REPL_RESULT!r} "
        f"(nodes: {_texts(device)[:40]!r})")


# ---------------------------------------------------------------------------
# Feature: storage inspector
# ---------------------------------------------------------------------------


def test_storage_sheet_lists_local_storage(device, ctx: dict) -> None:
    """The storage sheet dumps the web storage of the current page.

    The target page seeds one `localStorage` and one `sessionStorage` item; both
    keys and values must show up in the sheet.
    """
    _load_target(device)
    _open_sheet(device, "menuItemStorage")

    assert _wait_for_text(device, LOCAL_KEY, 20.0), (
        f"the storage sheet should list the page's localStorage key {LOCAL_KEY!r} "
        f"(nodes: {_texts(device)[:60]!r})")
    assert _has_text(device, LOCAL_VALUE), (
        f"the storage sheet should show the value {LOCAL_VALUE!r} "
        f"(nodes: {_texts(device)[:60]!r})")

    assert _wait_for_text(device, SESSION_KEY, 20.0), (
        f"the storage sheet should also list sessionStorage ({SESSION_KEY!r}) "
        f"(nodes: {_texts(device)[:60]!r})")


# ---------------------------------------------------------------------------
# Feature: network (requests) inspector
# ---------------------------------------------------------------------------


def test_requests_sheet_lists_requests_with_details(device, ctx: dict) -> None:
    """The requests sheet lists the page document and shows its details.

    Opens the requests sheet after loading a page and checks the page *document*
    request is listed — it used to be dropped, see WebPageClient.clearPageRequests —
    then opens its detail and checks the network section reports the method.
    We assert on values that do not depend on the UI locale (the HTTP method and
    the `document` fetch type), since the device may be localized.
    """
    _load_target(device)
    _open_sheet(device, "menuItemPageRequests")

    assert _wait_for_text(device, "/%s" % TARGET_PAGE, 20.0), (
        f"the requests sheet should list our page document request "
        f"({TARGET_PAGE!r}, shown as a path) (nodes: {_texts(device)[:60]!r})")
    # Its summary pairs the method with the fetch type: the main frame is a 'document'
    assert _wait_for_text(device, "GET", 15.0), (
        f"the request should report its HTTP method (nodes: {_texts(device)[:60]!r})")
    assert _has_text(device, "document"), (
        "our page request is a main frame navigation, i.e. a 'document' fetch "
        f"(nodes: {_texts(device)[:60]!r})")

    # Open the request detail from the list
    assert device.tap_text("/%s" % TARGET_PAGE, timeout=10.0), (
        f"could not tap the {TARGET_PAGE!r} request row (nodes: {_texts(device)[:60]!r})")
    device.settle(timeout=20.0)

    assert _wait_for_text(device, "GET", 15.0, exact=True), (
        "the request detail should report the GET method "
        f"(nodes: {_texts(device)[:60]!r})")
    assert _has_exact_text(device, "/%s" % TARGET_PAGE), (
        f"the request detail should report the URL path (nodes: {_texts(device)[:60]!r})")
    assert _has_exact_text(device, "localhost"), (
        f"the request detail should report the URL domain (nodes: {_texts(device)[:60]!r})")


# ---------------------------------------------------------------------------
# Feature: history page (favicons, day grouping, search)
# ---------------------------------------------------------------------------


def _read_history_page(device, timeout: float = 20.0) -> str:
    """Read back the history page the app generated, from its private storage.

    The history page is a WebView whose DOM uiautomator cannot see, so we assert
    on the markup it wrote to `files/history.html` instead. Polls until the page
    holds our freshly visited entry, since the file is written asynchronously
    when the page loads.
    """
    def read() -> str:
        return device.transport.shell(
            ["shell", "run-as", device.package, "cat", "files/history.html"], timeout=20)

    deadline = time.time() + timeout
    html = read()
    while time.time() < deadline and TARGET_TITLE not in html:
        time.sleep(1.0)
        html = read()
    return html


def test_history_page_groups_dates_and_shows_icons(device, ctx: dict) -> None:
    """The history page is rendered with a search box, day headers and favicons.

    Visits a page (so it is in the history), opens History from the main menu and
    checks the generated page: the visited entry, a non-empty day header, a
    favicon `<img>` and the search box.
    """
    _load_target(device)
    device.key(keys.BACK, 0.8)  # drop any leftover sheet/keyboard state

    # History lives in the main menu
    _tap_id(device, ":id/button_more")
    device.settle(timeout=20.0)
    assert "menuItemHistory" in _menu_item_ids(device), (
        f"the History entry should be in the main menu (nodes: {_texts(device)[:40]!r})")
    _tap_id(device, ":id/menuItemHistory")
    device.settle(timeout=30.0)

    assert device.foreground_package() == device.package, (
        f"opening History left the app: foreground is {device.foreground_package()!r}")

    html = _read_history_page(device)
    assert html, (
        "could not read the generated history page (files/history.html is empty "
        "or unreadable — is this a debuggable build?)")
    assert TARGET_TITLE in html, (
        "the page we visited should be listed in the history page "
        f"(generated page: {html[:400]!r})")

    # Day grouping: entries are grouped under day headers. We only check the
    # headers are present and non-empty: their label is localized ("Today",
    # "昨天", …) so asserting on its text would tie us to the device locale.
    headers = re.findall(r'id="groupTitle"[^>]*>([^<]*)<', html)
    assert any(label.strip() for label in headers), (
        f"the history page should group entries under dated headers (got {headers!r})")

    # Favicons: each entry carries an <img> with a usable src
    icons = re.findall(r"<img[^>]*entryIcon[^>]*>", html)
    assert icons, f"history entries should carry a favicon <img> (page: {html[:600]!r})"
    assert any(re.search(r'src="[^"]+"', icon) for icon in icons), (
        f"the favicon <img> should have a src (got {icons[:3]!r})")

    # Search box
    assert 'id="searchInput"' in html, (
        f"the history page should have a search box (page: {html[:600]!r})")


FEATURE_GROUPS = {
    "advanced": [
        test_close_tab_entry_closes_the_current_tab,
        test_console_repl_evaluates_expression,
        test_storage_sheet_lists_local_storage,
        test_requests_sheet_lists_requests_with_details,
        test_history_page_groups_dates_and_shows_icons,
    ],
}

ALL_TESTS = [t for group in FEATURE_GROUPS.values() for t in group]

TEST_DESCRIPTIONS = {
    "test_close_tab_entry_closes_the_current_tab": (
        "The 'Close tab' entry is in the tab menu out of the box and closes the "
        "current tab (last tab leaves the zero-tab empty state)"),
    "test_console_repl_evaluates_expression": (
        "The console sheet evaluates '1+1' in the page and shows the result"),
    "test_storage_sheet_lists_local_storage": (
        "The storage sheet lists the page's localStorage and sessionStorage items"),
    "test_requests_sheet_lists_requests_with_details": (
        "The requests sheet lists the page request and its detail reports the "
        "method (network section)"),
    "test_history_page_groups_dates_and_shows_icons": (
        "The history page lists visited entries with a search box, a dated "
        "group header and a favicon per entry"),
}
