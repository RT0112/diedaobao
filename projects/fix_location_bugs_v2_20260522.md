# 修复位置功能两个Bug — v2

## 日期
2026-05-22

## Bug 1: 子女端点击位置按钮经常超时

### 根因
1. `MapActivity.loadElderData()` 在启动 WS 监听后立即发送 `requestElderLocation()`
2. 但 WS 连接可能还没建立完成，`location_request` 可能没送达老人端
3. `WSClient.events` 是 SharedFlow，如果订阅晚于消息到达会丢失

### 修复方案
1. **确保 WS 连接建立后再发请求**：增加等待 WS 连接的就绪检测
2. **增加轮询频率**：从 2 秒改为 1 秒，更快检测到位置更新
3. **使用 MutableSharedFlow replay=1**：确保新订阅者能收到最近的消息

## Bug 2: 老人端 APP 在位置操作时崩溃

### 根因
1. `uploadLocationNow` 中的 `suspendCancellableCoroutine` 取消处理可能有问题
2. `LocationListener` 在协程被取消时可能没被正确移除
3. 缺少健壮的异常处理

### 修复方案
1. **改进协程取消处理**：确保 `LocationListener` 总是被移除
2. **添加更多的 try-catch**：覆盖所有可能的异常
3. **使用单独的标志位**：避免在协程外访问协程状态

## 修改文件
1. `family-guardian-app/.../cloud/WSClient.kt` — 改 SharedFlow replay=1
2. `family-guardian-app/.../ui/MapActivity.kt` — 增加 WS 就绪等待和轮询频率
3. `fall-detection-app/.../cloud/CloudBaseClient.kt` — 改进 uploadLocationNow 异常处理
