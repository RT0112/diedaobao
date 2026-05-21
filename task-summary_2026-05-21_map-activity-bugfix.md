# Bug Fix: MapActivity.kt

**Date:** 2026-05-21
**File:** `app/src/main/java/com/familyguardian/app/ui/MapActivity.kt`

## Objective
Fix two bugs in MapActivity.kt that cause map tiles not to display and loading failures to go unreported.

## Bug 1 — Map tiles blocked by mixed content policy
- **Root cause:** Tile URL uses HTTP (`http://webrd0{s}.is.autonavi.com`) while `loadDataWithBaseURL` sets the base URL to `https://localhost`. Android WebView blocks mixed content (HTTPS page loading HTTP resources) by default.
- **Fix:** Added `settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE` to the WebView settings in `onCreate`, allowing the WebView to load HTTP resources from an HTTPS context.

## Bug 2 — Loading failures not reported on Android 6.0+
- **Root cause:** The old `onReceivedError(view, code, desc, url)` signature is deprecated since API 23. Android 6.0+ devices call the new overload `onReceivedError(view, request, error)` instead, which was not overridden — so errors were silently ignored.
- **Fix:** Added the new `onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?)` override that checks `request.isForMainFrame` before showing the error page, matching the behavior of the legacy callback.

## Changes
1. Line ~91: Added `settings.mixedContentMode = MIXED_CONTENT_COMPATIBILITY_MODE`
2. Lines ~100-107: Added new `onReceivedError` overload for API 23+
