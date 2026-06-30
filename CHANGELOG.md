# Changelog

## v2.4.0 — Search Suggestions + Android TV / API 22 Support

### New feature: Search Suggestions

Typing in the address bar or the home page search bar now shows a live suggestions dropdown, the same way Chrome does.

- Full-screen overlay slides up when you tap the search bar — a clean dedicated input UI rather than an inline autocomplete
- Suggestions update as you type with a 300 ms debounce (no network hammering on every keystroke)
- Each suggestion row has a **↗ fill** button: autofills the bar without navigating, so you can refine the query before committing
- Supports all built-in search engines with their native suggestion APIs: Google (OpenSearch), DuckDuckGo (`type=list`), Bing (`osjson.aspx`), Brave
- Stale-response guard: if you type faster than the network responds, outdated results are silently discarded
- Back button or the ← arrow dismisses the overlay
- Can be disabled in Settings → Browser → Search Suggestions

### Android TV & API 22 compatibility

- **`minSdk` lowered from 24 to 22** — the app now installs on Android 5.1.1 devices (including older Android TVs) that were previously rejected with a parse error
- Explicit V1 (JAR) signing enabled for release builds — required for devices running Android < 7.0
- Fixed `getSystemService(Class<T>)` call in `DownloadService` (API 23+, crashed silently on API 22)
- Search overlay fully navigable with D-pad / TV remote:
  - D-pad DOWN from the search bar moves focus to the first suggestion
  - D-pad UP from the first suggestion row returns focus to the search bar
  - D-pad CENTER / ENTER on a suggestion navigates immediately
  - Back dismisses the overlay
  - `InputController` is correctly bypassed while the overlay is visible

### Bug fixes

- Fixed animation race condition where rapidly opening/closing the search overlay could leave it permanently invisible
- Fixed `UninitializedPropertyAccessException` crash on cold start when `hideSearchOverlay()` was called before `initViews()` finished
- New `ic_search` icon (clean Material Design magnifying glass) replaces the zoom icon on suggestion rows

## v2.3.0 — Stability Release

A full behavioral audit across the entire codebase. 29 real bugs found and fixed (several critical), plus dead code removal. No new features — this release exists purely to make every existing feature actually work correctly.

### Critical fixes

- Fixed camera and microphone permissions for websites (`getUserMedia`) never resolving — the Android permission flow was registered but never actually triggered, so any site requesting camera/mic (video calls, WebRTC, voice input) would hang forever with no dialog, no grant, no error.
- Fixed `<input type="file">` doing nothing on any website — no file picker was ever implemented; `onShowFileChooser` now launches the system picker and supports multi-file selection.
- Fixed the JavaScript bridge silently breaking in release builds: ProGuard/R8 had no keep rule for the real `JsBridge` class (only for an unused dead-code stand-in), so `minifyEnabled` release builds — including F-Droid builds — could strip or rename its methods while debug builds worked fine, hiding the bug from normal local testing.
- Fixed the back button (and any navigation back to the home page) leaving a blank white screen instead of showing the home overlay.

### Tab & browsing behavior fixes

- Fixed several places where a **background tab** could corrupt the **active tab's** UI: finishing a page load could hide the active tab's progress bar/refresh spinner; starting a new load could overwrite the visible address bar text, bookmark icon, and toolbar visibility; a load error could cover the active tab with a full-screen error message; an SSL warning dialog could pop up for a tab the user wasn't even looking at.
- Fixed the Home button, the gamepad/TV-remote Home shortcut, and the error screen's Home button only showing the home overlay UI without actually navigating the WebView home — pressing Back would land you right back on the old (or failed) page as if nothing happened.
- Fixed deleting the active tab group leaving a destroyed ("zombie") WebView on screen, which crashed on the next interaction.
- Fixed a corrupted/stale saved session (zero-tab active group) leaving the app with no WebView at all on cold start or after the OS killed and restored the process.
- Fixed tab WebView eviction using insertion order instead of true least-recently-used order — an old tab you kept revisiting was the first to be evicted, while a freshly opened, never-revisited tab could live forever.
- Fixed a rare race where a tab thumbnail or favicon update could be applied to the wrong tab after closing/reordering tabs.
- Fixed "Clear Browsing Data" (Settings) not clearing the live WebView cache — it only cleared on-disk data, requiring an app restart to fully take effect.
- Fixed stale in-memory tab thumbnails not refreshing after a bookmark/search-engine change.

### Input fixes (gamepad / TV remote / keyboard)

- Fixed gamepad/TV-remote input being unresponsive after a cold start (missing focus request on the restored tab's WebView).
- Fixed the virtual cursor losing correct screen bounds after rotating the device.
- Fixed `DPAD_CENTER` ignoring the visible cursor position on gamepads that report their confirm button this way (common with PS-style pads over generic Bluetooth adapters) — it now clicks where the cursor actually is.
- Fixed "Find in Page" from the menu not focusing the input or opening the keyboard, unlike the keyboard shortcut for the same action.

### Download manager fixes

- Fixed an OkHttp connection leak on HTTP failure or a mid-download error (response/stream were never closed).
- Fixed a race condition where a new download starting could be stopped immediately by another download finishing at the same time.
- Fixed a Handler callback leak in the downloads screen if it was closed before a pending UI reset fired.

### Other fixes

- Fixed Eruda console / custom JS injection silently failing on pages with `<head lang="...">` or any other attribute on the `<head>` tag.
- Fixed custom JS containing a literal `</script>` breaking its own injection and leaking raw text into the page.
- Fixed a race condition where two background tabs finishing at the same time could silently lose a history entry.
- Fixed "Desktop Mode" toggled from Settings having no effect on already-open tabs until the app was restarted.

### Removed

- Removed dead code with zero usages anywhere in the project: `BrowserScreenController`, `TabManager`, `MainActivity.SearchBridge`, and `normalizeNavigationInput()`.

## v2.2.8

### What is new

- Added a **new dedicated download management system** with its own page.
- Added **full support** for controllers, keyboard, mouse, and TV remotes.
- Fixed some issues and made other improvements.
- New app logo