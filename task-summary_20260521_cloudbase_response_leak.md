# Bug Fix: Location Request Timeout on Subsequent Clicks

## Objective
Fix the bug where clicking "view location" in HomeFragment works the first time but times out on subsequent clicks after returning from MapActivity.

## Root Cause Analysis

**Bug:** OkHttp `Response` objects must be closed (or their body fully read) before the underlying connection can be returned to the connection pool.

In `CloudBaseClient.kt`, several API methods used early `return` statements (for error cases like `!response.isSuccessful`) **before** the response body was read or the response was closed. This leaked the connection.

**Flow of failure:**
1. First click "view location" → `getElderStatus()` → `getFallHistory()` → `getGeofences()` → connections borrowed from pool
2. Error path returns early WITHOUT closing response → connections remain occupied
3. Second click → `getElderStatus()` → tries to reuse the same connection → **connection is still in use → timeout (30s readTimeout exceeded)**

**Why the first click worked:** The first request to each endpoint succeeded, so the `response.body?.string()` was read and the response auto-closed. However, the connection pool had only 1 idle connection per host by default.

**Why subsequent clicks failed:** When `getElderStatus()` was called again on the second visit, if it hit the error path (e.g., server returned non-200 or code != 200), it returned without closing the response — leaking the connection. The subsequent call then had no available connections and timed out.

## Fix Applied

Added `response.use { ... }` blocks to ensure all OkHttp Response objects are properly closed regardless of success/failure/exception:

1. **`getElderStatus()`** — wrapped entire response handling in `response.use { }`
2. **`getFallHistory()`** — wrapped entire response handling in `response.use { }`
3. **`getGeofences()`** — wrapped entire response handling in `response.use { }`
4. **`getFallEvents()`** — wrapped entire response handling in `response.use { }`

Note: `requestElderLocation()` was already correctly using `response.use { }` (as noted in its existing comment at line 286-289), so it was unaffected.

## Files Modified
- `app/src/main/java/com/familyguardian/app/cloud/CloudBaseClient.kt`

## Requirements Met
- ✅ Only modified files in children app (family-guardian-app directory)
- ✅ Added code comments explaining root cause in each fixed method
- ✅ Did not modify unrelated code
- ✅ Did not modify backend
