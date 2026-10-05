# Test run — SM-S9420 · portrait-270-sw720

- **When:** 2026-10-05T16:51:51+00:00
- **Device:** SM-S9420 (Samsung SM-S9420) — Android 14
- **Config:** portrait, rotation 270°, smallest width 720dp
- **Package:** `net.slions.fulguris.full.agent.debug`
- **Options:** restart=False, keep_tabs=False, orientation=default, filter=all
- **Result:** 9/10 passed in 49.2s (5 ran, 5 carried forward)

| Test | Description | Result | Duration |
|---|---|---|---|
| `test_close_tab_entry_closes_the_current_tab` | The 'Close tab' entry is in the tab menu out of the box and closes the current tab (last tab leaves the zero-tab empty state) | ✅ pass ⏸ | 33.4s |
| `test_console_repl_evaluates_expression` | The console sheet evaluates '1+1' in the page and shows the result | ✅ pass ⏸ | 35.0s |
| `test_storage_sheet_lists_local_storage` | The storage sheet lists the page's localStorage and sessionStorage items | ✅ pass ⏸ | 28.6s |
| `test_requests_sheet_lists_requests_with_details` | The requests sheet lists the page request and its detail reports the method (network section) | ✅ pass ⏸ | 37.7s |
| `test_history_page_groups_dates_and_shows_icons` | The history page lists visited entries with a search box, a dated group header and a favicon per entry | ✅ pass ⏸ | 20.4s |
| `test_smoke_launch` | The app launches and reaches the main browser UI in the foreground | ✅ pass | 2.3s |
| `test_smoke_open_website` | Navigating to a web site loads and the address bar shows its label | ✅ pass | 10.1s |
| `test_smoke_open_settings` | The settings activity opens via its component and renders its content | ❌ fail | 24.6s |
| | _settings screen did not render (nodes: ['下载', '书签', '会话  默认', '历史记录', '标签菜单', '设置', '选项', '隐身'])_ | | |
| `test_smoke_background_app_switch` | KEYCODE_APP_SWITCH backgrounds the app; launching brings it back to the front | ✅ pass | 5.6s |
| `test_smoke_background_home` | KEYCODE_HOME backgrounds the app; the activity intent brings it back to the front | ✅ pass | 5.6s |
