# Test run — SM-S9420 · landscape-180-sw720

- **When:** 2026-10-05T17:40:48+00:00
- **Device:** SM-S9420 (Samsung SM-S9420) — Android 14
- **Config:** landscape, rotation 180°, smallest width 720dp
- **Package:** `net.slions.fulguris.full.agent.debug`
- **Options:** restart=False, keep_tabs=False, orientation=default, filter=all
- **Result:** 3/5 passed in 195.1s

| Test | Description | Result | Duration |
|---|---|---|---|
| `test_close_tab_entry_closes_the_current_tab` | The 'Close tab' entry is in the tab menu out of the box and closes the current tab (last tab leaves the zero-tab empty state) | ❌ fail | 44.1s |
| | _the Close tab entry should be in the tab menu out of the box (it used to hide behind the menu settings); menu items: ['', 'header', 'layoutMenuItems', 'layoutMenuItemsContainer', 'menuItemAddBookmark', 'menuItemConsole', 'menuItemDarkMode', 'menuItemDesktopMode', 'menuItemDomainSettings', 'menuItemFind', 'menuItemMainMenu', 'menuItemPageHistory', 'menuItemPageRequests', 'menuItemPrint', 'menuItemReaderMode', 'menuItemShare', 'menuItemStorage', 'menuItemTranslate', 'menuShortcutBack', 'menuShortcutForward', 'menuShortcutHome', 'menuShortcutRefresh', 'scrollViewItems']_ | | |
| `test_console_repl_evaluates_expression` | The console sheet evaluates '1+1' in the page and shows the result | ✅ pass | 40.3s |
| `test_storage_sheet_lists_local_storage` | The storage sheet lists the page's localStorage and sessionStorage items | ✅ pass | 28.9s |
| `test_requests_sheet_lists_requests_with_details` | The requests sheet lists the page request and its detail reports the method (network section) | ❌ fail | 51.3s |
| | _the request detail should report the GET method (nodes: ['', '', '', '', '', '', '', '', '', '', '', '', '', '', '', '', '', '请求详情', 'http', '', '', '', '', '打开', 'http://localhost:8899/advanced_target.html?cb=1791221969524', '', '', '状态', '', '', '', '', '已允许', '', '', 'URL', '', '', '', '', '复制', '59 个字符', '', '', 'URL', 'http://localhost:8899/advanced_target.html?cb=1791221969524', '', '', '域名', 'localhost', '', '', '路径', '/advanced_target.html', '', '', '参数', '', '', 'cb'])_ | | |
| `test_history_page_groups_dates_and_shows_icons` | The history page lists visited entries with a search box, a dated group header and a favicon per entry | ✅ pass | 25.6s |
