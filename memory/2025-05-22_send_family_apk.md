# 2025-05-22 发送子女端APK

## 事件
用户要求发送子女端APK到微信。

## 操作
- 确认 APK 存在: `family-guardian-app/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk` (17MB, v0.8.7)
- 执行 `wechat_mac_send.sh` 发送成功 ✅

## 背景
CC修复了两个bug后编译了双端新版本：
- 老人端 v0.45.6（位置偏差修复）
- 子女端 v0.8.7（位置超时修复 + WS监听）

之前第一次运行CC时已发过一次，用户可能没收到或需要重新发送。
