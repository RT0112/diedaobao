# Bug修复：子女端位置超时 + 定位精度差

## Bug 1: MapActivity不监听WS位置推送，导致总是超时

### 根因
MapActivity 完全不监听 WS 的 `location_update` 事件，只靠 HTTP 轮询（每2秒GET /get-status，最多45秒超时）。即使老人端5秒内上传了位置、后端WS推了 `location_update`，MapActivity 也收不到。

### 修复
- **添加WS监听**：参考 `HomeFragment:455` 的实现，在 `MapActivity` 中添加 `startWSLocationListener()`
- **实时推送优先**：WS推送到达后立即更新地图标记，同时取消轮询循环
- **超时缩短**：轮询兜底从45秒缩短到30秒
- **版本号**：family-guardian-app: 30 → 31 (0.8.6 → 0.8.7)

### 改动文件
- `family-guardian-app/.../ui/MapActivity.kt`：
  - 新增 import `WSClient`
  - 新增字段 `wsListenerJob`、`wsLocationReceived`
  - 新增方法 `startWSLocationListener()`
  - 修改 `loadElderData()`：WS监听优先，轮询兜底
  - `onDestroy()` 中清理 `wsListenerJob`

---

## Bug 2: uploadLocationNow只用NETWORK_PROVIDER，精度差

### 根因
老人端 `uploadLocationNow()` 只请求 `NETWORK_PROVIDER`（WiFi/基站，精度30-500m），没尝试 `GPS_PROVIDER`（精度5-30m）。

### 修复
- **双Provider同时请求**：同时调用 `requestSingleUpdate(GPS_PROVIDER)` 和 `requestSingleUpdate(NETWORK_PROVIDER)`，谁先返回用谁
- **超时延长**：从5秒改为10秒，给GPS冷启动更多时间
- **版本号**：fall-detection-app: 147 → 148 (0.45.5 → 0.45.6)

### 改动文件
- `fall-detection-app/.../cloud/CloudBaseClient.kt`：
  - `withTimeout(5000)` → `withTimeout(10_000)`
  - 新增 `GPS_PROVIDER` 的 `requestSingleUpdate` 调用

---

## Git提交
```bash
# 老人端
git commit -m "fix: uploadLocationNow同时请求GPS+NETWORK，提升定位精度"

# 子女端
git commit -m "fix: MapActivity监听WS location_update，位置获取超时从45秒缩短到30秒"
```
