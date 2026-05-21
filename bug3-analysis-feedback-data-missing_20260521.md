# Bug 3 Analysis: Monitoring Data Missing in FeedbackActivity

## Objective
Trace the data flow from fall detection → history list → feedback form to identify why monitoring data (accelerometer, ML score, etc.) is not properly displayed or submitted in FeedbackActivity.

---

## Data Flow Overview

```
[Server /fall-history API]
       │
       ├──→ CloudBaseClient.getFallHistory() → FallEvent (simplified) → HistoryFragment
       │
       └──→ CloudBaseClient.getFallEvents()  → List<JSONObject> (raw)  → FeedbackActivity
```

**Key finding: HistoryFragment and FeedbackActivity use completely separate data sources and never pass data between them.**

---

## 1. FallNotification (Local Room DB — used by HistoryFragment)

File: `data/FallNotification.kt`

```kotlin
@Entity(tableName = "fall_notifications")
data class FallNotification(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val eventId: String = "",
    val elderName: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val latitude: Double? = null,
    val longitude: Double? = null,
    val impactG: Double = 0.0,
    val mlScore: Double = 0.0,
    val isRead: Boolean = false,
    val isHandled: Boolean = false
)
```

**Missing fields:** `ffTimeMs`, `physicsScore`, `weightedScore`, `decisionPath`, `sensorDataJson` — none of the extended monitoring data is stored locally.

---

## 2. HistoryFragment — Notification Click Handler

File: `ui/HistoryFragment.kt` (lines 81-121)

When a fall notification is clicked:
1. Marks it as read in local DB
2. Shows an **AlertDialog** with basic info (time, elderName, impactG, mlScore, location)
3. Only navigation option: `MapActivity` (to view location)
4. **No navigation to FeedbackActivity at all**

```kotlin
private fun onNotificationClick(notification: FallNotification) {
    // ... builds AlertDialog ...
    .setPositiveButton("📍 查看位置") { _, _ ->
        startActivity(Intent(requireContext(), MapActivity::class.java).apply { ... })
    }
    .setNegativeButton("知道了", null)
    .show()
}
```

**Bug: There is no way to navigate from HistoryFragment to FeedbackActivity.** The "误报反馈" (false positive report) entry point is disconnected from the history list.

---

## 3. CloudBaseClient — Two Competing API Methods

### getFallHistory() → `List<FallEvent>` (line 351)

```kotlin
data class FallEvent(
    val eventId: String,
    val timestamp: Long,
    val latitude: Double?,
    val longitude: Double?,
    val impactG: Double,      // Server field name: impactG
    val mlScore: Double       // Server field name: mlScore
)
```

Only 6 fields. No extended monitoring data.

### getFallEvents() → `List<JSONObject>` (line 656)

Same `/fall-history` API endpoint, but parses raw JSON. FeedbackActivity expects these fields:

| Field FeedbackActivity reads | Default | Likely server field |
|---|---|---|
| `ff_time_ms` | 0 | **NOT in server response** |
| `impact_strength` | 0.0 | Server has `impactG` |
| `ml_probability` | 0.0 | Server has `mlScore` |
| `physics_score` | 0.0 | **NOT in server response** |
| `weighted_score` | 0.0 | **NOT in server response** |
| `decision_path` | "" | **NOT in server response** |
| `sensor_data_json` | "[]" | **NOT in server response** |
| `id` | 0 | Server has `eventId` |

---

## 4. Root Cause Summary

### Root Cause #1: Field Name Mismatch (DATA LOSS)
FeedbackActivity reads `impact_strength` and `ml_probability`, but the server returns `impactG` and `mlScore`. Since `optDouble()` returns 0.0 for missing keys, **impact and ML data always shows as 0.0** in the feedback form.

### Root Cause #2: Extended Fields Don't Exist in Server Response (DATA NOT AVAILABLE)
Fields `ff_time_ms`, `physics_score`, `weighted_score`, `decision_path`, `sensor_data_json` are not part of the `/fall-history` API response at all. The server only returns the simplified `FallEvent` structure. These fields silently resolve to 0/empty via `optLong()`/`optDouble()`/`optString()` defaults.

### Root Cause #3: No Navigation Path from History to Feedback (BROKEN UX)
HistoryFragment's click handler only offers MapActivity navigation. There is no "report false positive" button or intent to launch FeedbackActivity with the selected fall record.

### Root Cause #4: Local Data Model Lacks Extended Fields (STRUCTURAL GAP)
`FallNotification` (Room entity) only stores `impactG` and `mlScore`. Even if navigation were added, the detailed monitoring data isn't available locally to pass via Intent extras.

---

## 5. Fix Recommendations

1. **Align field names:** In FeedbackActivity's `updateDetectionDataDisplay()` and `submitMisreport()`, read `impactG` instead of `impact_strength`, and `mlScore` instead of `ml_probability`.

2. **Server-side:** Extend the `/fall-history` API to return all monitoring fields (`ff_time_ms`, `physics_score`, `weighted_score`, `decision_path`, `sensor_data_json`) that the detector already captures.

3. **Add navigation:** Add a "误报反馈" button in HistoryFragment's AlertDialog that launches FeedbackActivity with the selected event's ID or data.

4. **Extend local model:** Add monitoring fields to `FallNotification` so data survives offline and can be passed between activities without re-fetching from the cloud.

5. **Unify data access:** Have FeedbackActivity accept an event ID (from either local DB or cloud) and load data from a single source of truth, rather than maintaining two parallel API methods.
