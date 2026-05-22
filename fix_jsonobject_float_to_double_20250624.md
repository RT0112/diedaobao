# Task: Fix JSONObject.put Float→Double crash in CloudBaseClient.kt

## Objective
Fix `NoSuchMethodError` crash caused by passing `Float` values to `JSONObject.put()`, which doesn't have a `put(String, Float)` overload in Android's `org.json.JSONObject`.

## Key Reasoning
- Android's `JSONObject` only has `put(String, double)` — no `put(String, Float)` or `put(String, Number)` that accepts Float
- Kotlin `Float` does NOT auto-widen to `double` at the JVM call site when targeting a specific overload
- This causes a runtime `NoSuchMethodError` crash

## Findings
Audited ALL 30 `put()` calls in `CloudBaseClient.kt`. Found **6 total Float parameters** passed to JSONObject:
1. `impactG` (Float) — already had `.toDouble()` (committed previously)
2. `mlScore` (Float) — already had `.toDouble()` (committed previously)
3. `physicalScore` (Float) — already had `.toDouble()` (committed previously)
4. `weightedScore` (Float) — already had `.toDouble()` (committed previously)
5. `feedRate` (Float) — already had `.toDouble()` (committed previously)
6. **`accuracy` (Float)** — was missing `.toDouble()`, **fixed in this commit**

All other `put()` calls pass String, Long, Double, JSONObject, or JSONArray — no risk.

## Action Taken
- `accuracy` line 337: `put("accuracy", accuracy)` → `put("accuracy", accuracy.toDouble())`
- Committed as `53ccee3`: `fix: JSONObject.put Float->Double to prevent NoSuchMethodError`
