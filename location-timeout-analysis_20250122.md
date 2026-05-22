# 跌倒宝"查看位置"功能 — 超时问题与位置偏差分析

## 分析时间
2025-01-XX

## 一、完整数据流梳理

### 位置请求链路（子女端"查看位置"按钮 → 老人端响应）

```
1. 子女端 HomeFragment.btnViewLocation.onClick → MapActivity (mode=view)
2. MapActivity.loadElderData() → CloudBaseClient.requestElderLocation() → HTTP POST /request-elder-location
3. 后端 server.js: 设置 pullLocationRequest + pullLocationStatus=pending, 同时 sendToUser(elderId, {type:'location_request'})
4. 老人端 WSClient 收到 location_request → emit WSEvent.LocationRequest
5. 老人端 RemoteAssistManager 监听 → uploadLocationOnRequest() → CloudBaseClient.uploadLocationNow()
6. uploadLocationNow: requestSingleUpdate(5秒超时) → HTTP POST /location-sync → WS pushLocationUpdate
7. 后端 location-sync: 写DB + pushToRoom(location_update)
8. 子女端 MapActivity: 每2秒 HTTP GET /get-status 轮询，用 loc.timestamp > requestTime 判断新位置
```

### 双通道设计

| 通道 | 请求路径 | 响应路径 |
|------|---------|---------|
| **HTTP轮询**（主） | POST /request-elder-location（设置pull flag） | 老人端每10秒 poll_pull 检测 → POST /location-sync → 子女端每2秒 GET /get-status |
| **WS实时**（辅） | 后端 sendToUser(elderId, location_request) | 老人端WSClient→RemoteAssistManager→uploadLocationNow→WS pushLocationUpdate→子女端WSClient.LocationUpdate |

**关键发现：两条通道同时运行但互不感知！**

---

## 二、超时问题分析

### 2.1 子女端 MapActivity 超时机制

**位置**: `MapActivity.kt` L395-431

```kotlin
// 轮询等待老人上传新位置（最多45秒）
val startWait = System.currentTimeMillis()
while (System.currentTimeMillis() - startWait < 45_000) {
    if (!isActive) return@launch
    delay(2000)  // 2秒轮询
    val fresh = CloudBaseClient.getElderStatus()
    if (fresh?.lastLocation != null && loc.timestamp > requestTime) {
        // ✅ 成功获取
        return@launch
    }
    // 兜底：轮询超过8次（16秒）且 pullLocationStatus=done
    if (pollCount > 8 && fresh.pullLocationStatus == "done" && elapsed > 16_000) {
        // ✅ 时钟偏移兜底
        return@launch
    }
}
// ❌ 超时45秒
```

**超时时间：45秒**

### 2.2 老人端位置获取超时

#### 通道1: HTTP poll_pull（FallDetectionService.startPullPolling）

| 参数 | 值 | 位置 |
|------|-----|------|
| 常态轮询间隔 | **10秒** | L558 |
| 加急轮询间隔 | **3秒** | L558 |
| 加急超时 | **60秒** | L548 |
| requestSingleUpdate超时 | **10秒** | L727 |
| GPS缓存命中 | age<30s, acc≤50m | L673 |
| WiFi缓存命中 | age<15s, acc≤30m | L677 |

**问题：常态10秒轮询，子女端发出请求后，老人端最多需10秒才能检测到！**

#### 通道2: WS 实时推送（RemoteAssistManager）

| 参数 | 值 | 位置 |
|------|-----|------|
| requestSingleUpdate超时 | **5秒** | CloudBaseClient L270 |
| fallback | lastKnownLocation → SP缓存 | L300-318 |

**WS通道理论延迟：WS推送(~100ms) + requestSingleUpdate(5s) + HTTP上传(~1s) ≈ 6秒**

### 2.3 超时根因分析

**问题1：WS通道和HTTP轮询通道并存但子女端只看HTTP**

子女端 MapActivity.loadElderData() 的轮询逻辑：
- 只通过 HTTP GET /get-status 检查位置是否更新
- 虽然子女端 WSClient 能收到 location_update 事件，但 **MapActivity 没有监听 WS 事件！**
- HomeFragment 监听了 LocationUpdate（L455-461），但只是刷新 HomeFragment 的UI，MapActivity 完全不感知

**这意味着即使WS在5秒内推送了新位置到服务端，子女端还要等2秒×N次轮询才能拿到**

**问题2：老人端两条上传通道可能冲突**

- HTTP poll_pull 通道：FallDetectionService.tryGetFreshAndUpload()，有严格的精度门槛(GPS 50m/WiFi 30m)，10秒超时
- WS 通道：CloudBaseClient.uploadLocationNow()，5秒超时，精度要求宽松

两条通道可能同时上传，造成：
1. 重复上传浪费流量
2. 第二次上传可能覆盖第一次的更新位置时间戳，导致子女端判断混乱

**问题3：pullLocationStatus 状态管理缺陷**

- server.js L156-157: 位置上传时设置 `pullLocationRequest=NULL, pullLocationStatus='done'`
- 但WS通道的上传（CloudBaseClient.uploadLocationNow → syncLocation → location-sync）**也会清除 pull flag**
- 如果WS通道先上传（快），poll_pull 通道再查时就看不到 pending 状态了
- 两条通道互相干扰

**问题4：时钟偏移导致 requestTime 判断失败**

- 子女端用 `loc.timestamp > requestTime` 判断新位置
- requestTime 是服务端 `Date.now()` 生成的（server.js L581）
- loc.timestamp 是老人端 `System.currentTimeMillis()` 生成的（CloudBaseClient.kt L332）
- **两端时钟可能有数秒差异**，导致新位置被误判为旧位置
- 兜底逻辑是 16秒+8次轮询，这太慢了

---

## 三、位置偏差问题分析

### 3.1 坐标系转换现状

| 位置 | 坐标系 | 转换 |
|------|--------|------|
| Android GPS 原始输出 | **WGS-84** | 无转换直接上传 |
| 老人端上传到服务端 | **WGS-84** | 无转换（raw GPS） |
| 服务端存储 | **WGS-84** | 无转换 |
| 子女端 MapActivity JS | **GCJ-02** | ✅ JS层 wgs2gcj() 转换后显示 |
| 高德瓦片地图 | **GCJ-02** | 匹配 |

**结论：坐标系转换在子女端 MapActivity 的 JS 层完成（wgs2gcj函数），Kotlin 代码没有做任何转换。这个设计是正确的。**

但需注意：
- `setupAddMode()` 和 `setupEditMode()` 中调用 `setupEditableFence($lat,$lng,...)` 传入的是 **WGS-84 坐标**，JS 的 `setupEditableFence()` 函数内部有 `wgs2gcj()` 转换（L301），所以显示正确
- `saveFence()` 用 `gcj2wgs()` 反转回 WGS-84 存储到服务端，也正确

**坐标转换链路没有问题。**

### 3.2 实际位置偏差来源

如果用户反馈位置偏差大，根因不在坐标系，而在于：

1. **requestSingleUpdate 只用 NETWORK_PROVIDER**（CloudBaseClient.uploadLocationNow L284-288）
   - 只尝试了网络定位，没有尝试 GPS
   - WiFi/基站定位精度通常 30-500m，比 GPS（5-30m）差很多
   
2. **FallDetectionService.tryGetFreshAndUpload 的精度门槛太严**
   - GPS 要求 acc≤50m 且 age<30s
   - WiFi 要求 acc≤30m 且 age<15s
   - 室内 GPS 经常 >50m，导致回退到 lastKnownLocation

3. **lastKnownLocation 可能很旧**
   - Android 的 `getLastKnownLocation()` 返回的可能是几分钟甚至几小时前的位置
   - 虽然有 SP 缓存兜底，但那也是历史位置

4. **uploadLocationNow 的 fallback 链过长**
   ```
   requestSingleUpdate(NETWORK, 5s超时) 
   → lastKnownLocation(GPS) 
   → lastKnownLocation(NETWORK)
   → SP缓存
   ```
   越往后越旧，偏差越大

---

## 四、问题汇总

| # | 问题 | 严重度 | 影响 |
|---|------|--------|------|
| 1 | MapActivity 不监听 WS location_update 事件 | 🔴高 | 即使5秒内拿到新位置，也要等2秒×N轮询 |
| 2 | uploadLocationNow 只用 NETWORK_PROVIDER | 🔴高 | 位置精度差（30-500m vs GPS 5-30m） |
| 3 | 两条上传通道（WS+HTTP poll_pull）互相干扰 | 🟡中 | pullLocationStatus 被抢先清除，状态混乱 |
| 4 | 时钟偏移导致 requestTime 判断失败 | 🟡中 | 新位置被误判为旧位置，需等16秒兜底 |
| 5 | 子女端45秒超时无渐进提示 | 🟡中 | 用户体验差，不知道是"正在定位"还是"已失败" |
| 6 | 加急轮询60秒超时过长 | 🟢低 | 资源浪费，但影响有限 |

---

## 五、修复建议

### 修复1: MapActivity 监听 WS location_update 事件（最高优先级）

```kotlin
// MapActivity.loadElderData() 中加入 WS 监听
val wsJob = lifecycleScope.launch {
    WSClient.events.collect { event ->
        if (event is WSClient.WSEvent.LocationUpdate) {
            // 直接拿到新位置，无需轮询
            evalJs("setElderLocation(${event.latitude},${event.longitude},...)")
            return@collect
        }
    }
}
// 超时或退出时取消
locationJob?.invokeOnCompletion { wsJob.cancel() }
```

### 修复2: uploadLocationNow 同时使用 GPS + NETWORK

```kotlin
// 先尝试 GPS（精度高），并行 NETWORK 做兜底
locationManager.requestSingleUpdate(GPS_PROVIDER, listener, mainLooper)
locationManager.requestSingleUpdate(NETWORK_PROVIDER, listener, mainLooper)
```

### 修复3: 去掉 HTTP poll_pull 通道（WS 已覆盖）

当 WS 在线时，不需要 poll_pull 轮询。WS 断开时才降级到 poll_pull。

### 修复4: 用服务端时间戳代替 requestTime 比较

后端 location-sync 返回时带上服务端时间，子女端比较服务端时间而非客户端时钟。

### 修复5: 超时提示优化

5秒时提示"正在获取GPS定位..."，15秒时提示"GPS信号弱，尝试网络定位..."，30秒时提示"定位困难，将显示最近位置"。
