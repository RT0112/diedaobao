# 远程协助三问题代码深度分析 + HTTP 评估

## 用户报告的问题

1. **关闭自动允许**：接受协助后进入协助画面，过一会突然退出，子女画面卡住但能操作
2. **开启自动允许**：倒计时30秒不动，页面有时闪退到首页（好像弹两下）
3. **关闭自动允许**：倒计时跳很快，60→58→56

---

## 一、问题1：协助中突然退出 + 子女画面卡住

### 根因分析

#### 1.1 ScreenCaptureService 断连逻辑过于激进

**代码位置**: `ScreenCaptureService.kt` uploadFrameAsync()

- HTTP上传成功后检查云端status，非active就主动停掉 → 但子女端还没连上时status可能是idle
- 连续5次上传失败就stopSelf → 网络抖动就可能触发（HTTP超时10-15s×5=50-75s）
- **最关键**：WS模式下根本不走HTTP，但一旦WS断开瞬间切HTTP，帧数据巨大（Base64 JPEG 360p约30-50KB）+ HTTP延迟高 → 几乎必然连续失败 → 立即断开

#### 1.2 WS 抖动误触发 AssistEnd 信号

**代码位置**: `RemoteAssistManager.kt` 第164-168行

- 收到 `AssistEnd` 就直接调 `onSessionEnded?.invoke()` → Activity `finish()` 退出
- 没有验证是否真的结束，WS重连时可能收到旧消息

#### 1.3 MediaProjection 被系统停止时静默失败

**代码位置**: `ScreenCaptureService.kt` projectionCallback.onStop()

- MIUI/HyperOS 内存回收会停止 MediaProjection
- `stopScreencapMode()` 只停了推流，不通知 Activity
- 推流停了但信号轮询还在跑 → 子女能操作但看不到画面（"卡住但能操作"的根因！）

#### 1.4 onSessionEnded / onGuardianDisconnected 回调无条件 finish

**代码位置**: `RemoteAssistActivity.kt` 第878-893行

```kotlin
RemoteAssistManager.onSessionEnded = {
    runOnUiThread {
        cleanupAssist()
        finish()  // ← 无条件退出
    }
}
ScreenCaptureService.instance?.onGuardianDisconnected = {
    runOnUiThread {
        cleanupAssist()
        finish()  // ← 无条件退出
    }
}
```

**问题**: 收到任何断连信号就直接退出，没有延迟验证、没有重连尝试。

### 修复方向

1. **断连验证**：收到 AssistEnd/onGuardianDisconnected 后，延迟2-3秒用 `check_status` 查一次云端，确认真的结束了再退出
2. **MediaProjection 停止时通知 Activity**：projectionCallback.onStop() → 通知用户"屏幕共享被系统中断"，而非静默失败
3. **降低 ScreenCaptureService 的断连敏感度**：
   - HTTP 5次失败 → 改为10次或更多
   - HTTP status检查逻辑：WS模式下完全不走HTTP status检查
   - 或者直接砍掉HTTP（见下方评估）

---

## 二、问题2：自动允许模式倒计时不动/闪退

### 根因分析

#### 2.1 自动允许模式根本不走倒计时

**代码位置**: `RemoteAssistActivity.kt` onCreate() 第170行

```kotlin
if (autoAllow) {
    containerRequest.visibility = View.VISIBLE
    onAllowClicked()  // ← 直接走允许，没有倒计时
} else {
    showRequestDialog()
    startAutoRejectCountdown()  // ← 只有手动模式才启动
}
```

用户看到的"30秒不动"：`remainingSeconds` 初始值=30，UI显示了 `${remainingSeconds}秒后自动拒绝` 但没有启动 Runnable → 数字永远不变。

#### 2.2 全局 catch + restartApp() 导致闪退到首页

**代码位置**: `RemoteAssistActivity.kt` onCreate() 第100行

```kotlin
} catch (e: Exception) {
    restartApp("onCreate崩溃")  // ← 杀进程重启到首页
}
```

什么会触发异常？
- `onAllowClicked()` → `respondToRequest()` → 网络请求失败
- `initViews()` 找不到 View
- 重复请求触发竞态条件

**这个全局 catch 把所有真正的 bug 都藏起来了！** 用户只看到闪退，开发者连日志都看不到。

#### 2.3 "弹两下"——四条路径同时触发 Activity

**触发路径**（全部会 startActivity）：
1. `FallDetectionApp.kt` 第75行 → WS事件监听 → startActivity
2. `FallDetectionService.kt` 第1108行 → Service级监听 → startActivity
3. `HomeFragment.kt` 第293行 → Fragment级监听 → startActivity
4. 通知的 `fullScreenIntent` → PendingIntent → startActivity

虽然 `RemoteAssistManager` 有 `lastNotifiedRequestId` 去重，但四条路径各自独立触发，在去重逻辑执行前就可能已创建多个 Intent。

**实测影响**：WS推送一次 `assist_request` → 上述4条路径中至少2-3条同时触发 → `onNewIntent` 被快速调用2-3次 → 每次 `handleNewRequest` 都判断为"重复请求"→ 但 else 分支又调了 `onAllowClicked()` → 自动允许模式下每次都触发 `respondToRequest`。

### 修复方向

1. **自动允许模式专用UI**：显示"正在自动连接..."的Loading界面，不显示倒计时
2. **去掉全局 catch + restartApp()**：改为只 catch 具体已知异常，未预期的异常让 crash report 收集
3. **全局"请求处理中"锁**：在 `RemoteAssistManager` 加 `isRequestBeingHandled` 标志，防止多路径并发触发

---

## 三、问题3：倒计时跳快（60→58→56）

### 根因分析

#### 3.1 多个 Runnable 叠加递减同一个 remainingSeconds

**代码位置**: `RemoteAssistActivity.kt` startAutoRejectCountdown()

```kotlin
private fun startAutoRejectCountdown() {
    tvRemaining.text = "${remainingSeconds}秒后自动拒绝"
    rejectRunnable = object : Runnable {
        override fun run() {
            remainingSeconds--  // ← 共享变量，多个Runnable同时递减
            if (remainingSeconds <= 0) { ... }
            else {
                tvRemaining.text = "${remainingSeconds}秒后自动拒绝"
                handler.postDelayed(this, 1000)
            }
        }
    }
    handler.postDelayed(rejectRunnable!!, 1000)
}
```

**时序问题**：
1. WS推送 `assist_request` → `onNewIntent` 触发 → `startAutoRejectCountdown()` → Runnable#1 开始跑
2. 0.5秒后，HTTP轮询/FallDetectionApp 也触发 → `onNewIntent` 再次 → `handleNewRequest` 判断重复 → else分支又调 `startAutoRejectCountdown()` → Runnable#2 开始跑
3. 虽然 `rejectRunnable?.let { handler.removeCallbacks(it) }` 试图取消旧的，但新 Runnable 在 `postDelayed` 前被赋值给 `rejectRunnable`，**removeCallbacks 只取消了刚创建的新 Runnable**（还没 post），旧 Runnable 仍在跑

**结果**：2-3个 Runnable 同时每秒递减 `remainingSeconds` → 60→57→54（跳3格）

#### 3.2 remainingSeconds 在重复请求路径未重置

当 `handleNewRequest` 返回 false（重复请求）时，`remainingSeconds` 不被重置，直接用当前值（可能已被之前的 Runnable 递减过了）继续显示。

### 修复方向

1. **加 `isCountdownRunning` 防重入**：倒计时已在跑就不重新启动
2. **重复请求路径不启动倒计时**：`isNewRequest=false` 时直接 return，不调 `startAutoRejectCountdown()`
3. **remainingSeconds 只在 isNewRequest=true 时重置**

---

## 四、额外发现的遗漏问题

### 4.1 FallDetectionApp 和 RemoteAssistManager 双重 WS 监听 → 重复触发

**代码位置**：
- `FallDetectionApp.kt` 第75行 → 自己也监听 WS `AssistRequest` → startActivity
- `RemoteAssistManager.kt` 第106行 → 也监听 WS `AssistRequest` → 通知 HomeFragment + FallDetectionService → 也 startActivity

**同一条 WS 消息被两个地方各消费一次**，触发两次 Activity 启动。

### 4.2 子女端 requestAssist 同时发 WS + HTTP → 双重触发

**代码位置**: 子女端 `RemoteAssistManager.kt` requestAssist()

```kotlin
// WS 发送协助请求
WSClient.sendAssistRequest(eid, guardianName)

// 同时 HTTP 降级保障也发
CoroutineScope(Dispatchers.IO).launch {
    // HTTP POST /remote-assist action=request
}
```

服务端 `action=request` 会 WS 推送给老人端，但 WS 的 `sendAssistRequest` 也会推送给老人端。如果WS和HTTP都成功，老人端收到两次推送。

### 4.3 ScreenCaptureService 的 notifyScreenReady 用 HTTP 而非 WS

**代码位置**: `ScreenCaptureService.kt` notifyScreenReady()

```kotlin
thread(name = "NotifyReady") {
    // HTTP POST /remote-assist action=screen_ready
}
```

WS 连接正常时还用 HTTP 通知 screen_ready，浪费且延迟高。

### 4.4 cleanupAssist() 的 stopService 可能打断正在运行的推流

**代码位置**: `RemoteAssistActivity.kt` cleanupAssist()

```kotlin
// 停止 ScreenCaptureService
val intent = Intent(this, ScreenCaptureService::class.java)
stopService(intent)
// 还要强制停
ScreenCaptureService.instance?.stopSelf()
```

如果只是 WS 短暂断连（会自动重连），`cleanupAssist` 直接把推流 Service 杀了，WS 重连后也无法恢复。

### 4.5 老人端 WSClient 的 `pushAssistFrameBinary` 用 guardianId 而非 to

**代码位置**: `ScreenCaptureService.kt` uploadFrameAsync()

```kotlin
val gid = guardianId ?: elderId ?: ""
WSClient.pushAssistFrameBinary(gid, jpegBytes, w, h, frameNum)
```

但 `WSClient.pushAssistFrameBinary` 的 header 字段是 `to`，这个 `to` 应该是子女端的 userId（guardianId），如果 guardianId 为空就退化为 elderId → 服务端找不到目标。

### 4.6 子女端 RemoteAssistFragment 的 manager 每次 onViewCreated 重建

**代码位置**: 子女端 `RemoteAssistFragment.kt`

```kotlin
private val manager: RemoteAssistManager by lazy {
    RemoteAssistManager(requireContext())
}
```

`by lazy` 跟 Fragment 生命周期绑定，但 `onDestroyView` 里 `manager.onFrameReceived = null`，下次 `onViewCreated` 时 manager 还是同一个实例但回调被清空了 → 切 tab 回来后收不到帧。

---

## 五、HTTP 评估：应该砍掉

### 当前 HTTP 路径清单

| 路径 | 用途 | WS 替代 | 砍掉影响 |
|------|------|---------|---------|
| `poll_request` | 老人端轮询协助请求 | ✅ WS `assist_request` | 无 |
| `poll_signal` | 老人端轮询触控信号 | ✅ WS `assist_signal` | 无 |
| `upload_frame` | 老人端上传屏幕帧 | ✅ WS binary `pushAssistFrameBinary` | 无 |
| `screen_ready` | 通知云端屏幕就绪 | ❌ 无 WS 替代 | 需新增 WS 消息 |
| `check_status` | 检查云端状态 | 部分替代 | 断连验证仍需 |
| `respond` | 响应请求(接受/拒绝) | 部分替代 | WS可能断，需保留 |
| `end` | 结束协助 | 部分替代 | 同上 |
| `signal` | 子女端发触控信号 | ✅ WS `assist_signal` | 无 |

### 砍掉 HTTP 的理由

1. **HTTP帧上传是卡顿根因**：360p JPEG Base64 约 30-50KB，HTTP POST 每帧一次，延迟高（200-500ms/帧），而 WS binary 直传延迟仅 20-50ms
2. **HTTP轮询和WS推送并存导致双重触发**：`poll_request` + WS `assist_request` 两条路径同时跑，是倒计时跳快和弹两下的根因
3. **HTTP降级不可靠**：WS断开时HTTP也不一定通（同一网络），且 HTTP 信号轮询 500ms 间隔对触控来说延迟太大
4. **HTTP帧上传的 Base64 膨胀**：30KB JPEG → Base64后约 40KB，带宽浪费 33%
5. **简化代码**：砍掉 HTTP 后 `ScreenCaptureService` 的 uploadFrameAsync 从 80 行砍到 10 行

### 保留的 HTTP（必要最小集）

- `respond`（接受/拒绝）：WS 断时仍需能响应
- `end`：WS 断时仍需能结束
- `check_status`：断连验证
- `request`（子女端发请求）：作为 WS 的兜底

### 砍掉后的影响

- **WS断开时完全无法推流**：这是对的，因为HTTP推流本身就卡得不可用
- **需要新增 WS 消息 `screen_ready`**：替代 HTTP 的 `screen_ready`
- **poll_request / poll_signal / upload_frame 全部移除**：代码大幅简化

---

## 六、汇总修复方案

### 优先级 P0（必须修）

| # | 问题 | 修复 |
|---|------|------|
| 1 | 断连信号无条件finish | 加延迟验证（2-3秒后查check_status确认） |
| 2 | MediaProjection停止静默失败 | projectionCallback → 通知Activity显示提示 |
| 3 | 多Runnable叠加递减 | 加 `isCountdownRunning` 防重入 |
| 4 | 全局catch+restartApp藏bug | 去掉粗暴restartApp，改为具体异常处理 |
| 5 | 四条路径并发触发Activity | FallDetectionApp删掉自己的WS监听，只走RemoteAssistManager统一分发 |

### 优先级 P1（建议修）

| # | 问题 | 修复 |
|---|------|------|
| 6 | 自动允许模式UI错乱 | 专用Loading界面替代倒计时 |
| 7 | HTTP帧上传卡顿 | 砍掉HTTP帧上传路径，WS-only |
| 8 | HTTP轮询双重触发 | 砍掉 poll_request / poll_signal |
| 9 | screen_ready 用 HTTP | 新增 WS 消息替代 |
| 10 | 子女端同时发WS+HTTP | HTTP降级改为：只在WS发送失败时才发 |

### 优先级 P2（后续优化）

| # | 问题 | 修复 |
|---|------|------|
| 11 | cleanupAssist在WS短暂断连时杀推流 | 断连时先保留Service，延迟5秒确认再杀 |
| 12 | pushAssistFrameBinary用错to字段 | 确保guardianId非空 |
| 13 | 子女端Fragment回调被清空 | manager回调在onDestroyView时保留onFrameReceived |

### 关于砍HTTP的结论

**建议砍掉**：`poll_request`、`poll_signal`、`upload_frame` 三个HTTP路径。它们是卡顿+双重触发的根因，且WS已完全覆盖。保留 `respond`、`end`、`check_status`、`request` 作为WS断连时的兜底。
