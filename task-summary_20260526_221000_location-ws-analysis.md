# Task Summary: 子女端查看位置不走WS的原因分析

**时间**: 2026-05-26 21:57~22:10
**目标**: 分析子女端查看位置为什么一直走HTTP而非WS

## 完整数据流

### 当前实现（MapActivity.loadElderData）

```
子女端打开地图
  │
  ├─ 1. HTTP GET /get-status → 展示缓存位置（立即）
  │
  ├─ 2. 等WS连接（最多5秒）
  │     └─ WS未连上？→ 显示缓存位置，return
  │
  ├─ 3. WS发送 location_request → 服务器转发给老人端
  │
  ├─ 4. 同时 HTTP POST /request-elder-location（"降级保障"）
  │
  ├─ 5. 等30秒WS推送 location_update
  │     └─ 收到？→ 更新地图，return ✅
  │
  └─ 6. 超时 → HTTP GET /get-status 降级
```

### 老人端响应链路

```
老人端收到location_request（WS或HTTP触发）
  │
  └─ uploadLocationNow()
       ├─ WS推送 location_update（pushLocationUpdate）→ broadcastToRoom → 子女端
       └─ HTTP POST /location-sync → 服务器也 pushToRoom → 子女端
```

## 根因分析：为什么"一直走的不是WS"

**问题1：老人端10秒定时HTTP上传 + location-sync路由里也有pushToRoom**

后端 `location-sync` 路由在老人端HTTP上传位置后，也调了 `pushToRoom(elderId, {type: 'location_update'})` 推给子女端。这意味着：
- 老人端每10秒HTTP上传一次位置
- 每次 upload 都会 WS 推 location_update 给子女端
- 所以子女端 **WS其实一直在收位置更新**，但MapActivity可能没在监听（因为MapActivity可能没打开）

**问题2：MapActivity每次打开都先调HTTP getElderStatus**

```kotlin
// 步骤1: 立刻HTTP查缓存位置
val status = CloudBaseClient.getElderStatus()  // ← 永远先走HTTP
```

这导致用户看到的体验是"每次查位置都是HTTP返回的缓存位置"，不是WS实时推送。

**问题3：WS location_request + HTTP request-elder-location 同时发**

```kotlin
// 步骤3+4: 同时发WS和HTTP
WSClient.sendLocationRequest(eid)  // WS
lifecycleScope.launch { CloudBaseClient.requestElderLocation() }  // HTTP
```

两个同时发，老人端可能收到两次location_request（WS推一次+HTTP触发一次），导致位置上传两次。

**问题4：老人端uploadLocationNow()也双推**

```kotlin
// 先WS推
WSClient.pushLocationUpdate(wsLat, wsLng, accuracy)
// 再HTTP上传（HTTP上传后服务器又WS推一次）
val response = callFunction("location-sync", body)
```

每次位置更新被WS推了 **两次**：老人端自己WS推一次 + HTTP上传后服务器又推一次。

**问题5（核心）：用户感受"走的不是WS"的真正原因**

从后端日志看：
- `POST /location-sync` 每10秒一次（老人端定时上传）
- `GET /get-status` 偶尔出现（子女端HTTP查状态）
- `POST /request-elder-location` 每次查看位置触发
- **没有WS的location_request日志**（后端没打印WS消息转发日志）

用户体验：点"查看位置" → 看到的位置是HTTP缓存 → 感觉"不是WS的"。

实际上WS可能确实在工作（老人端定时上传+pushToRoom），但MapActivity的流程设计让它**总是先展示HTTP缓存**，WS位置更新只是"锦上添花"的更新刷新。

## 解决方案

### 方案1（推荐）：WS优先展示 + HTTP降级

改 `loadElderData()` 流程：
1. 先展示缓存位置（HTTP，保留，保证不白屏）
2. **不调HTTP request-elder-location**（去掉降级保障，WS足够）
3. WS发送 location_request
4. 等5秒WS推送（缩短到5秒，30秒太长）
5. 超时 → HTTP request-elder-location降级

**核心改动：去掉步骤4的HTTP requestElderLocation()同时调用**，只在WS超时后才HTTP降级。

### 方案2：去掉老人端定时HTTP上传的pushToRoom

`location-sync` 路由里，只有主动位置请求触发的上传才 pushToRoom，定时上传不推。
避免子女端不断收到重复的 location_update。

### 方案3：统一位置更新入口

老人端只在 uploadLocationNow() 里走WS推送，不走HTTP location-sync（或者HTTP上传后不再pushToRoom）。
这样位置更新只有一条路径，不会重复推送。

## 建议：方案1最简单，效果最明显

改 MapActivity.loadElderData()：
- 删掉 `CloudBaseClient.requestElderLocation()` 的同时调用
- 缩短WS等待超时到5-10秒
- 超时后才走HTTP降级
