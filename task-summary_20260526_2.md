# Task Summary: 远程协助三问题代码深度分析

**时间**: 2026-05-26
**目标**: 分析远程协助3个可靠性问题的根因，给出解决方案，不修改代码

## 完成内容

1. **阅读了全部核心代码文件**：
   - RemoteAssistActivity.kt（老人端Activity，950行）
   - RemoteAssistManager.kt（老人端管理器，WS+HTTP轮询）
   - ScreenCaptureService.kt（屏幕推流，ImageReader+VirtualDisplay+HTTP上传）
   - RemoteAssistService.kt（AccessibilityService）
   - WSClient.kt（WebSocket客户端）
   - FallDetectionApp.kt（App级WS监听）
   - server.js（后端）

2. **三大问题根因定位**：

### 问题1（协助中突然退出+子女卡住）
- ScreenCaptureService HTTP断连逻辑过激：5次失败就stopSelf
- WS抖动时误触发AssistEnd → 无条件finish()
- MediaProjection被MIUI回收时静默失败 → 推流停但信号轮询还在跑 → 子女"卡住但能操作"

### 问题2（自动允许倒计时不动/闪退）
- 自动允许模式根本没启动倒计时Runnable，UI显示初始值30秒但不变
- 全局catch+restartApp()把真bug藏了，闪退=杀进程重启到首页
- 四条路径（FallDetectionApp WS + RemoteAssistManager WS + HomeFragment + HTTP轮询）并发触发startActivity → "弹两下"

### 问题3（倒计时跳快60→58→56）
- 多个onNewIntent叠加调用startAutoRejectCountdown()，多个Runnable同时递减同一个remainingSeconds
- 重复请求路径的else分支也调了startAutoRejectCountdown，没加防重入

3. **额外发现6个遗漏问题**（FallDetectionApp双重WS监听、子女端WS+HTTP双发、screen_ready用HTTP等）

4. **HTTP评估**：建议砍掉 poll_request/poll_signal/upload_frame 三个HTTP路径，保留respond/end/check_status/request作为兜底

## 输出文件
- `/Users/zhou/.qclaw/workspace-x5kuz49xple53hhg/remote-assist-analysis_20260526.md`
