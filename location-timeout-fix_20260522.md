# Fix: 子女端查看位置超时 + 位置偏差几十米

## Objective
修复跌倒宝两个关联的位置Bug：子女端MapActivity查看位置总是超时（45秒），以及老人端定位精度差（偏差几十米）。

## Bug 1 — MapActivity不监听WS，位置总是超时

**根因**：`MapActivity` 完全不监听WS的 `location_update` 事件（grep确认0个WS引用），只靠HTTP轮询（每2秒GET /get-status，最多等45秒）。即使老人端5秒内上传了位置、后端WS推了推送，`MapActivity` 也收不到。

**修复**：
- 添加 `startWSLocationListener()` 方法，参考 `HomeFragment:455` 的WS监听实现
- WS `location_update` 到达时立即调用 `setElderLocation()` 更新地图标记，并设置 `wsLocationReceived=true` 让轮询循环退出
- 轮询超时从45秒缩短到30秒（原来就只是兜底）

**改动文件**：`family-guardian-app/.../ui/MapActivity.kt`
- 新增 `import WSClient`
- 新增字段 `wsListenerJob`、`wsLocationReceived`
- 新增方法 `startWSLocationListener()`
- 修改 `loadElderData()`：先启动WS监听，轮询中发现 `wsLocationReceived` 立即退出
- `onDestroy()` 中清理 `wsListenerJob`

**版本号**：30 → 31 (0.8.6 → 0.8.7)

---

## Bug 2 — 老人端只用NETWORK_PROVIDER，精度差

**根因**：`uploadLocationNow()` 只调用 `requestSingleUpdate(NETWORK_PROVIDER)`（WiFi/基站，精度30-500m），完全没尝试 `GPS_PROVIDER`（精度5-30m）。

**修复**：
- 同时调用 `requestSingleUpdate(GPS_PROVIDER)` 和 `requestSingleUpdate(NETWORK_PROVIDER)`（在同一个 `suspendCancellableCoroutine` 里），谁先返回用谁
- 超时从5秒改为10秒，给GPS冷启动更多时间
- `lastKnownLocation` 的 fallback 顺序也改为 GPS 优先

**改动文件**：`fall-detection-app/.../cloud/CloudBaseClient.kt`
- `withTimeout(5000)` → `withTimeout(10_000)`
- 新增 `GPS_PROVIDER` 的 `requestSingleUpdate` 调用（在NETWORK之前）
- 日志也记录用的是哪个provider

**版本号**：147 → 148 (0.45.5 → 0.45.6)

---

## Build & Deploy
- ✅ 老人端编译成功 (38MB APK)
- ✅ 子女端编译成功 (17MB APK)
- ✅ Git commit 完成（双端分别提交）
- ✅ 双端APK已通过微信发送
- ⚠️ K70 SSH超时（设备不在线），无法自动安装，需手动安装

## Testing
- MapActivity现在应该能在WS推送到达时秒级更新位置（不再等轮询）
- 老人端定位精度应该明显提升（GPS优先）
- 如果WS断开，30秒轮询兜底仍然有效
