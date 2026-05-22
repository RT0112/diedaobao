# 子女端查看位置超时+偏差问题分析

## 两个问题

### 问题1: 查看位置老是超时

### 问题2: 位置和实际有几十米偏差

---

## 完整链路

```
子女端 MapActivity → HTTP POST /request-elder-location
  → 后端设 pullLocationStatus=pending + WS推送 location_request 给老人
    → 老人端两条通道并行响应：
       通道A: WS实时 → uploadLocationNow() (5s超时, 仅NETWORK_PROVIDER)
       通道B: HTTP poll_pull 轮询 (10s间隔) → tryGetFreshAndUpload() (10s超时, GPS+NET)
    → 老人上传位置到 /location-sync → 后端WS推送 location_update
      → 子女端：MapActivity 用 HTTP 轮询 (每2秒, 45秒超时) 等新位置
```

---

## 超时根因：3个问题叠加

### 根因1: MapActivity 不监听 WS location_update 事件（最大问题！）

- `HomeFragment` 监听了 WS 的 `LocationUpdate` 事件 ✅
- `MapActivity` **完全没监听** WS 事件（grep 确认0个WS引用）❌
- 即使老人端5秒内上传了位置、后端通过WS推送了 location_update，MapActivity 也收不到
- MapActivity 只靠 HTTP 轮询（每2秒 GET /get-status），最多要等45秒才超时

**WS长连接确实没有超时**——问题不是WS超时，而是MapActivity根本没听WS。

### 根因2: uploadLocationNow() 只用 NETWORK_PROVIDER（5秒超时）

```kotlin
// CloudBaseClient.kt L284-285
locationManager.requestSingleUpdate(
    android.location.LocationManager.NETWORK_PROVIDER,  // ← 只有WiFi/基站！
    listener,
    android.os.Looper.getMainLooper()
)
```

WiFi/基站定位精度 30-500m，室内经常5秒内拿不到位置，超时后 fallback 到 lastKnown。

### 根因3: fallback 链太长、位置可能很旧

```
freshLocation (NETWORK_PROVIDER, 5s超时)
  → lastKnown GPS_PROVIDER（可能几分钟前，可能null）
    → lastKnown NETWORK_PROVIDER（可能更旧）
      → SharedPreferences缓存（可能几小时前）
```

---

## 位置偏差根因

### 不是坐标系问题
- MapActivity JS层有完整的 WGS84→GCJ02 转换（wgs2gcj函数）✅
- 高德瓦片是 GCJ-02 坐标系，转换正确

### 真正原因：NETWORK_PROVIDER 精度差
- WiFi/基站定位精度 30-500m，室内经常 50-200m
- GPS 精度 5-30m，但 uploadLocationNow() 没尝试 GPS
- fallback 到 lastKnown 或 SP缓存的位置可能是几分钟前的，人已经走了几十米

---

## 修复方案

### Fix 1: MapActivity 监听 WS location_update 事件（解决超时）
- MapActivity 添加 WSClient 事件监听
- 收到 location_update 时立即更新地图标记，不用轮询

### Fix 2: uploadLocationNow() 同时请求 GPS + NETWORK（解决偏差）
- 用 `requestSingleUpdate` 同时注册 GPS_PROVIDER 和 NETWORK_PROVIDER
- 谁先返回用谁（GPS慢但准，NETWORK快但偏）
- 超时从5秒提到10秒给GPS更多时间

### Fix 3: 保留轮询作为兜底但缩短超时
- WS监听作为主通道（<5秒响应）
- 轮询作为WS断开时的兜底（每2秒，最多30秒）
