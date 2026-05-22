# 修复位置功能两个Bug — 已完成

## Date
2026-05-22 14:18

## Objective
修复子女端"查看位置"功能的两个bug:
1. 子女端点击位置按钮经常超时
2. 老人端APP在位置操作时崩溃

## Root Cause Analysis

### Bug 1: 子女端位置超时
- **根因**: WS连接可能还没建立完成就发送了`location_request`，消息没送达老人端
- WS连接是异步的，新订阅者可能错过消息

### Bug 2: 老人端崩溃  
- **根因**: `suspendCancellableCoroutine`中使用`cont.isActive`检查，在协程取消时可能导致竞态条件
- 位置监听器未被正确移除导致崩溃

## Fix Applied

### 老人端 (CloudBaseClient.kt v25):
1. 改用局部标志位+AtomicBoolean模式，避免`cont.isActive`竞态
2. 增加`try-catch`包裹WS推送，防止WS异常导致崩溃

### 子女端 (MapActivity.kt):
1. 等待WS连接建立后再发送位置请求(最多5秒)
2. 轮询间隔从2秒改为1秒，更快响应
3. 适配新的超时阈值

## Commit Summary
- fall-detection-app: `1620a24` - versionCode 150
- family-guardian-app: `54a30c6` - versionCode 40

## Result
✅ 两个APK已编译并发送到微信
- 老人端: app-arm64-v8a-debug.apk (39MB) v150
- 子女端: app-arm64-v8a-debug.apk (18MB) v40
