# Task Summary: WS连接不上原因分析

**时间**: 2026-05-26 21:36
**目标**: 分析用户报告的"WS连接不上"问题

## 根因

项目存在两套代码文件副本，所有v24 edit改到了错误的副本：

| 改动的文件(错误) | 编译用的文件(未改动) |
|---|---|
| `cloud/WSClient.kt` | `app/src/main/java/com/falldetector/diedaobao/cloud/WSClient.kt` |
| `assist/RemoteAssistManager.kt` | `app/src/main/java/com/falldetector/diedaobao/assist/RemoteAssistManager.kt` |
| `ui/RemoteAssistActivity.kt` | `app/src/main/java/com/falldetector/diedaobao/ui/RemoteAssistActivity.kt` |

v153 APK = 旧代码 + versionCode递增，不包含任何v24修复。

## WS连不上的真正原因

**不是我改代码导致的。** Mac后端进程19:41启动未热加载，ngrok正常运行，后端health正常。日志显示elder WS在线，guardian反复断开重连（ngrok免费版不稳定，p99延迟56秒）。

## 待办
- 将v24修复代码改到正确的路径（app/src/main/java/...）
- 重新编译部署
- 修复后端：Mac后端需要重启才能加载ws.js的sessionId改动
