# Bug修复报告 - 2026-05-16

## Bug 1: 子女端收不到跌倒通知

### 根因
`server.js` 的 `/fall-report` 路由调用 `pushToRoom(userId, ...)` 推送跌倒事件，但 `pushToRoom()` 依赖 `rooms` Map（WebSocket 连接时才填充）。

如果子女端还没建立 WebSocket 连接（`joinRoom()` 未执行），`rooms.get(elderId)` 返回 `undefined`，`pushToRoom` 返回 `0`，**子女端完全收不到通知**。

### 修复内容 (`server.js`)
1. **增加备用推送路径**：`pushToRoom` 返回 `0` 时，直接查 `family_bindings` 表，逐个 `sendToUser()` 推送。
2. **修复 `accuracy` 未解构**：`/fall-report` 路由漏了 `accuracy` 字段的解构。
3. **修复 SQL 语法错误**：`/location-sync` 路由中 `UPDATE ... SET pullLocationRequest=NULL, pullLocationStatus=?` 缺少逗号（已修复）。
4. **`joinRoom()` 增加日志**：方便调试房间成员情况。

---

## Bug 2: 老人端收到协助请求后闪退

### 根因分析
两个独立问题叠加：

#### 问题 A：`HomeFragment.handleAssistRequest()` 中 `requireContext()` 崩溃
- `assistRequestListener` 在 `isAdded && activity != null` 检查通过后、`requireContext()` 调用前，Fragment 可能已 detach
- `requireContext()` 在 Fragment 已 detach 时抛 `IllegalStateException` → 闪退

#### 问题 B：`FallDetectionService.startRemoteAssistPolling()` 多次注册 listener
- `assistListener` 是匿名 lambda，每次 `startRemoteAssistPolling()` 调用都会创建新实例
- 如果 `startRemoteAssistPolling()` 被调用 N 次，就有 N 个监听器同时触发
- 导致 `RemoteAssistActivity` 被启动 N 次 → 多个全屏通知/Activity → 崩溃

### 修复内容

#### `HomeFragment.kt`
- 在 `handleAssistRequest()` 开头先拿 `val ctx = context`（可为 null），后续所有 `requireContext()` 替换为 `ctx`
- 加 `if (ctx == null) return` 防护

#### `FallDetectionService.kt`
- 增加 `assistListenerRegistered` 标志位（默认 `false`）
- `startRemoteAssistPolling()` 开头检查，已注册则跳过
- `stopRemoteAssistPolling()` 里重置 `assistListenerRegistered = false`

#### `server.js`（辅助修复）
- `joinRoom()` 里增加 `console.log` 输出房间成员，方便调试
- 老人端 `joinRoom()` 时，同时把**已在线**的家属加入房间（双向绑定修复）

---

## 修复文件清单

| 文件 | 修改内容 |
|------|---------|
| `projects/diedaobao-server/server.js` | fall-report 备用推送、accuracy 解构、SQL 逗号修复 |
| `projects/diedaobao-server/ws.js` | joinRoom() 增加日志、双向绑定修复 |
| `projects/fall-detection-app/ui/HomeFragment.kt` | requireContext() → ctx，防 detach 崩溃 |
| `projects/fall-detection-app/service/FallDetectionService.kt` | assistListenerRegistered 防重复注册 |

---

## 验证步骤

1. 编译老人端 APK：`./gradlew assembleElderRelease`
2. 安装到 K70，确认：
   - 跌倒后子女端能收到通知（即使子女端后连接 WS）
   - 收到协助请求时不闪退
   - `HomeFragment` 在后台/前台都能正常处理协助请求
3. 检查 K70 服务端日志：`[fall-report] pushToRoom=0, 直接推送: N个家属`

---

*修复时间: 2026-05-16 20:30 GMT+8*
