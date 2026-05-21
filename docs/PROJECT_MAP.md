# 跌倒宝项目地图 v1.0

> 生成时间：2026-05-21  
> 覆盖范围：源代码全文扫描 + 文档分析  
> 状态：✅ 核心文件已读，待补充UI层细节

---

## 1. 项目概览

```
跌倒宝 = 老人端APK + 子女端APK + K70本地后端(Express+SQLite+WS)
```

| 组件 | 包名 | 技术 | 文件数 | versionCode |
|------|------|------|--------|-------------|
| 老人端APK | `com.falldetector.diedaobao` | Kotlin Android | 41个.kt | 136 |
| 子女端APK | `com.familyguardian.app` | Kotlin Android | 21个.kt | 28 |
| 后端服务 | K70 Termux | Node.js/Express | 3个.js | — |

---

## 2. 整体架构图

```
┌─────────────────────────────────────────────────────────────────┐
│                        K70 (Termux)                              │
│                      localhost:3000                              │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │                    Express Server                         │  │
│  │  POST /user-register   POST /fall-report                 │  │
│  │  POST /location-sync   POST /bind-family                  │  │
│  │  POST /geofence       GET  /fall-history                 │  │
│  │  POST /remote-assist   GET  /get-status                   │  │
│  │  POST /restart                                          │  │
│  │                                                          │  │
│  │  WebSocket /ws  ←→  WSClient (子女端)                   │  │
│  │                     WSClient (老人端)                   │  │
│  │                                                          │  │
│  │  SQLite: users / fall_events / locations                │  │
│  │           family_bindings / geofences / screen_frames    │  │
│  └──────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
         ↑ HTTP/WS                    ↑ HTTP/WS
         │                            │
┌────────┴────────┐         ┌─────────┴──────────┐
│   老人端APK      │         │   子女端APK         │
│  (K70本地运行)   │         │  (子女手机)          │
│                 │         │                      │
│ SensorCollector │         │ HomeFragment         │
│  ↓              │         │  ├─ 跌倒通知卡片     │
│ FallDetector    │         │  └─ 实时位置显示     │
│  ├─ ML (ONNX)  │         │                      │
│  ├─ 物理模型   │         │ HistoryFragment      │
│  └─ 三路径融合 │         │ GeofenceFragment     │
│  ↓              │         │ RemoteAssistFragment │
│ FallDetectionSvc │         │ MapActivity          │
│  ↓              │         │                      │
│ CloudBaseClient │         │ CloudBaseClient      │
│  └─ HTTP POST   │         │  └─ HTTP POST/GET    │
│  └─ WS推送     │         │  └─ WS接收           │
└────────────────┘         │  └─ 协助发起/接收    │
                          └──────────────────────┘

┌──────────────────────────────────────────────────────────────────┐
│              远程协助双向数据流                                    │
│  子女端 ──WS:assist_request──→ 服务器 ──WS:assist_request──→ 老人端 │
│  子女端 ←─WS:assist_response── 服务器 ←─WS:assist_response── 老人端 │
│                                                                  │
│  子女端 ←─WS:assist_frame─── 服务器 ←─WS:assist_frame─── 老人端  │
│  (屏幕画面)                    ScreenCaptureService              │
│                                                                  │
│  子女端 ──WS:assist_signal──→ 服务器 ──WS:assist_signal──→ 老人端│
│  (触控指令)                    RemoteAssistService               │
└──────────────────────────────────────────────────────────────────┘
```

---

## 3. 源文件总览

### 3.1 老人端 — 41个Kotlin源文件

路径：`projects/fall-detection-app/app/src/main/java/com/falldetector/diedaobao/`

```
├── FallDetectionApp.kt                    (1513B)  Application单例，初始化CloudBaseClient/WSClient
│
├── assist/                               ★★★ 远程协助核心 [5文件, 96KB]
│   ├── RemoteAssistService.kt             (9769B, 255行)  无障碍服务，单例.instance
│   │   ├── 手势执行: executeClick/executeSwipe/executeDoubleClick
│   │   ├── 全局操作: executeGlobalAction(home/back/recents等)
│   │   ├── 截图: takeScreenshot (API30+)
│   │   └── 权限录制: startMpRecording/stopMpRecording
│   │
│   ├── PermissionRecordManager.kt         (21598B, 567行)  v18权限录制/回放管理器 ★★★
│   │   ├── hasPermissionDialog()  — 只检查com.android.systemui窗口
│   │   ├── startRecording()       — 录制启动
│   │   ├── stopRecording()        — 录制停止并保存到SharedPreferences
│   │   ├── tryAutoHandle()        — 检测到弹窗后自动回放
│   │   ├── hasRecording()         — 检查是否有保存的录制
│   │   ├── clearRecording()       — 清除录制
│   │   ├── KEY_RECORDED_STEPS = "recorded_steps_v9"
│   │   └── KEY_AUTO_DISABLED 相关逻辑
│   │
│   ├── RemoteAssistManager.kt             (26640B)  远程协助会话管理
│   ├── ScreenCaptureService.kt             (33873B)  屏幕捕获 + 帧推送
│   └── TouchRecordOverlay.kt               (4962B)   透明悬浮窗录制层
│
├── cloud/                                ★★ 网络层 [2文件, 38KB]
│   ├── CloudBaseClient.kt                 (22113B, ~600行)  HTTP API客户端
│   │   ├── registerUser(deviceId, name, phone) → userId
│   │   ├── reportFall(latitude, longitude, impactG, ffDuration, mlScore, physicalScore)
│   │   │   ⚠️ impactG.toDouble() — Float必须转Double！
│   │   ├── syncLocation(latitude, longitude)
│   │   ├── uploadLocationNow()  — 立即获取GPS并上传
│   │   ├── requestLocationPull()  — 请求老人端上传位置
│   │   ├── bindFamily(bindCode)  — 绑定家庭码
│   │   ├── generateBindCode()  — 生成绑定码
│   │   ├── getStatus(elderId)  — 查询老人状态
│   │   ├── pullLocation()  — 拉取老人位置
│   │   ├── getFallHistory(elderId)
│   │   ├── sendFeedback()  — 反馈提交
│   │   ├── uploadLog()  — 日志上传
│   │   ├── registerForRemoteAssist()
│   │   └── REMOTE_ASSIST_URL = "http://192.168.4.19:3000/remote-assist"
│   │
│   └── WSClient.kt                        (14921B, ~380行)  WebSocket客户端
│       ├── WSEvent类型: AssistRequest/LocationRequest/AssistCancel/
│       │             AssistEnd/AssistSignal/FallEvent/LocationUpdate
│       ├── connect(context)  — 连接WS，自动认证(auth: elder)
│       ├── sendAssistRequest(guardianId, guardianName)
│       ├── sendAssistResponse(guardianId, accepted, sessionId)
│       ├── sendLocationUpdate(latitude, longitude)
│       ├── sendLocationRequest(elderId)
│       ├── sendAssistSignal(signalType, touchAction, x, y, ...)
│       ├── sendAssistFrame(frameData, width, height)
│       ├── sendAssistEnd()
│       ├── disconnect()
│       └── 自动重连: RECONNECT_DELAY_MS=3000, MAX=5次
│
├── detect/                               ★★★ 跌倒检测核心 [2文件, 49KB]
│   ├── FallDetector.kt                   (40251B, ~1100行)  核心检测算法
│   │   ├── 三路径检测:
│   │   │   路径1: ML分数 > mlHigh阈值 → 直接报警
│   │   │   路径2: 加权分数(ML×0.6+物理×0.4) > weightedScoreThresh → 报警
│   │   │   路径3: 物理分数 > physicsThresh → 报警
│   │   ├── 物理模型输入: impactG / ffDuration(失重时间) / velocity(速度)
│   │   ├── mlScoreFromSensor()  — ONNX推理
│   │   ├── onFreeFall() / onImpact() / onMotion()  — 状态机
│   │   └── addToLog()  — 诊断日志
│   │
│   └── DetectionConfig.kt                 (7918B, 159行)  灵敏度配置
│       ├── SharedPreferences持久化: "detection_config"
│       ├── 8级灵敏度: 1(最严) ~ 8(最宽松), 默认4
│       ├── SENSITIVITY_TABLE: ffTimeMs / mlHigh / weightedScore / physics
│       ├── 灵敏度等级 → 参数映射表
│       ├── 硬编码常量: ML_LOW_HARDCODED=0.50 / IMPACT_MIN_HARDCODED=3.0g
│       └── .summary()  — 诊断摘要字符串
│
├── ml/                                   ★ ML推理 [2文件, 37KB]
│   ├── FallDetectorONNX.kt                (18289B)  ONNX Runtime推理 ★使用中
│   └── FallDetectorML.kt                  (19200B)  TensorFlow Lite推理(备用)
│
├── sensor/                               传感器采集 [1文件, 5.6KB]
│   └── SensorCollector.kt                 (5650B, ~160行)
│       ├── 加速度计: TYPE_ACCELEROMETER → accX/accY/accZ/g值
│       ├── 陀螺仪: TYPE_GYROSCOPE → gyroX/gyroY/gyroZ
│       ├── SENSOR_DELAY_GAME (~50Hz)
│       ├── SensorCallback接口 → 直接回调FallDetector，0数据丢失
│       └── postureAngle 积分
│
├── service/                              ★★ 服务层 [3文件, 21KB]
│   ├── FallDetectionService.kt            (50846B, ~1400行)  ★★★ 核心ForegroundService
│   │   ├── onCreate: 初始化SensorCollector/WSClient
│   │   ├── onStartCommand: startDetection()
│   │   ├── startDetection(): 启动传感器 + 位置监听
│   │   ├── onSensorData(): 喂数据给FallDetector
│   │   ├── FallDetector.onFallDetected() → showFullScreenAlert()
│   │   ├── 确认流程: 用户确认 → CloudBaseClient.reportFall()
│   │   ├── 倒计时取消: 用户点"我没事" → 清除本次报警
│   │   ├── 位置监听: onLocationChanged → syncLocation
│   │   ├── WS事件监听: AssistRequest → 弹出协助请求
│   │   └── 启动方式: START_STICKY (系统强杀后自动重启)
│   │
│   ├── BootReceiver.kt                    (1956B)  开机广播接收 → 启动FallDetectionService
│   └── BootWorker.kt                      (3295B)  WorkManager定时任务
│
├── data/                                 数据层 [5文件, 2.6KB]
│   ├── AppDatabase.kt                     (863B)   Room数据库
│   ├── FallEvent.kt                       (661B)   @Entity(tableName="fall_events")
│   ├── FallEventDao.kt                    (562B)   DAO
│   ├── Contact.kt                         (312B)   联系人
│   └── ContactDao.kt                      (762B)
│
├── notify/                               通知模块 [4文件, 15KB]
│   ├── EmergencyNotifier.kt               (5775B)  紧急通知
│   ├── SmsSender.kt                       (1623B)  短信发送
│   ├── PhoneCaller.kt                     (3233B)  电话拨打
│   └── WeChatNotifier.kt                  (5010B)  微信通知
│
├── feedback/                             反馈模块 [2文件, 17KB]
│   ├── FeedbackActivity.kt                (12900B)  反馈界面
│   └── FeedbackSender.kt                   (4633B)  反馈提交到 /feedback
│
├── ui/                                   ★ UI层 [9文件, 149KB]
│   ├── MainActivity.kt                    (6859B)   主界面容器
│   ├── HomeFragment.kt                    (19840B)  主页: 跌倒检测状态/灵敏度/测试
│   ├── ContactsFragment.kt                (12491B)  联系人管理
│   ├── HistoryFragment.kt                  (7792B)  历史记录
│   ├── SettingsFragment.kt                (21038B)  设置: 灵敏度/通知/账号
│   ├── Test1Fragment.kt                   (11248B)  测试页面
│   ├── ConfirmActivity.kt                 (17138B)  跌倒确认弹窗
│   ├── PermissionActivity.kt              (38680B)  权限引导 ★重要
│   └── RemoteAssistActivity.kt            (44039B)  远程协助界面
│
├── config/
│   └── ServerConfig.kt                    (667B)   BASE_URL="http://192.168.4.19:3000"
│       ├── BASE_URL  ← 老人端直连K70
│       ├── WS_URL   = ws://${BASE_URL}:8080/ws  ⚠️ 硬编码了8080!
│       ├── FEEDBACK_URL = "$BASE_URL/feedback"
│       ├── UPLOAD_LOG_URL = "$BASE_URL/upload-log"
│       └── REMOTE_ASSIST_URL = "$BASE_URL/remote-assist"
│
└── util/
    ├── AppLogger.kt                       (6828B)   日志封装
    └── LogUploader.kt                     (11139B)   日志上传
```

### 3.2 子女端 — 21个Kotlin源文件

路径：`projects/family-guardian-app/app/src/main/java/com/familyguardian/app/`

```
├── FamilyGuardianApp.kt                   (3271B)   Application单例
├── MainActivity.kt                        (5623B)   主容器
│
├── cloud/                                ★★ 网络层 [3文件, 47KB]
│   ├── CloudBaseClient.kt                 (29079B, ~705行)  HTTP API客户端
│   │   ├── autoRegister() → deviceId="family_$androidId"
│   │   ├── bindElder(bindCode) → POST /bind-family → elderId
│   │   ├── syncBindingFromCloud()  — 同步过期elderId
│   │   ├── getStatus(elderId) → GET /get-status
│   │   ├── pullLocation(elderId) → POST /location-sync action=poll_pull
│   │   ├── getFallHistory(elderId) → GET /fall-history
│   │   ├── addGeofence(...) → POST /geofence action=create
│   │   ├── getGeofences(elderId) → POST /geofence action=list
│   │   ├── deleteGeofence(fenceId) → POST /geofence action=delete
│   │   ├── checkGeofenceBreach(...) → POST /geofence action=check
│   │   ├── requestRemoteAssist() → POST /remote-assist
│   │   ├── sendFeedback()
│   │   └── LOCAL GEOFENCES CACHE: SharedPreferences "cloudbase" "geofences_cache"
│   │
│   ├── WSClient.kt                        (15817B, ~448行)  WebSocket客户端
│   │   ├── role = "guardian"
│   │   ├── WSEvent类型:
│   │   │   FallEvent(eventId, timestamp, impactG, mlScore, lat, lng)
│   │   │   LocationUpdate(lat, lng, accuracy, timestamp)
│   │   │   AssistResponse(accepted, sessionId, elderId)
│   │   │   AssistFrame(frameData, width, height, frameNum) ★屏幕帧
│   │   │   AssistEnd / AssistCancel
│   │   │   GeofenceBreach(elderName, breaches, timestamp, elderId)
│   │   ├── sendAssistRequest(elderId, guardianName)
│   │   ├── sendAssistResponse(accepted, sessionId, elderId)
│   │   ├── sendAssistEnd()
│   │   ├── sendAssistSignal(signalType, x, y, elderId)
│   │   ├── sendLocationRequest(elderId)
│   │   ├── RECONNECT_DELAY_MS=5000, MAX=10次
│   │   └── ⚠️ WS URL硬编码在ServerConfig
│   │
│   └── RemoteAssistManager.kt              (24864B)  远程协助管理器(WebRTC)
│
├── config/
│   └── ServerConfig.kt                    (660B)   BASE_URL="http://192.168.4.19:3000"
│
├── data/                                 数据层 [3文件, 2.9KB]
│   ├── AppDatabase.kt                     (829B)   Room数据库
│   ├── FallNotification.kt                (825B)   跌倒通知实体
│   └── FallNotificationDao.kt             (1275B)
│
├── feedback/
│   ├── FeedbackActivity.kt                (13076B)  反馈界面
│   └── FeedbackSender.kt                   (4778B)
│
├── ui/                                   ★ UI层 [9文件, 90KB]
│   ├── HomeFragment.kt                   (19510B)  主页: 跌倒卡片/位置/WS状态
│   │   ├── WS监听 → 实时更新跌倒事件/位置
│   │   └── pullLocation() → 轮询get-status
│   ├── HistoryFragment.kt                 (7460B)  历史跌倒记录
│   ├── GeofenceFragment.kt                (7565B)  围栏列表
│   │   ├── 添加 → MapActivity(mode=add)
│   │   └── 点击 → MapActivity(mode=view_fence&fenceId=xxx)
│   ├── RemoteAssistFragment.kt            (12107B)  远程协助发起
│   │   ├── sendAssistRequest() → WS
│   │   └── WS监听AssistResponse/AssistFrame
│   ├── MapActivity.kt                    (25858B)  地图(Leaflet+WebView)
│   │   ├── mode=add      → 拖拽画圆+保存
│   │   ├── mode=view     → 只读查看
│   │   └── mode=view_fence → 查看单个围栏
│   ├── PermissionActivity.kt             (19516B)  权限引导
│   ├── SettingsFragment.kt                (4631B)  设置
│   ├── FallEventAdapter.kt                (2491B)  跌倒事件列表Adapter
│   └── GeofenceAdapter.kt                 (2568B)  围栏列表Adapter
│
└── util/
    └── AppLogger.kt                       (6820B)
```

### 3.3 后端 — 4个Node.js文件

路径：`projects/diedaobao-server/`

```
server.js  (≈950行)  Express主服务器
  ├── 中间件: cors / compression / express.json
  ├── 日志中间件 (跳过/health)
  ├── GET  /health  → {status, time, db, ws{total, elders, guardians}}
  ├── POST /user-register  → users表
  ├── POST /fall-report  → fall_events + WS推送家属
  ├── POST /location-sync  → locations + WS推送
  ├── GET  /get-status  → 老人状态+最新跌倒
  ├── POST /bind-family  → family_bindings + bind_codes
  ├── GET  /fall-history  → 跌倒历史
  ├── POST /geofence  → action: create/list/delete/check
  ├── POST /remote-assist  → assist请求/响应
  ├── POST /restart  → 服务自我重启 (nohup detached)
  └── POST /feedback  → feedback表

ws.js      (≈250行)  WebSocket服务器
  ├── initWS(server)  — 挂载到 /ws 路径
  ├── clients: Map<userId, WebSocket>
  ├── rooms: Map<elderId, Set<userId>>  — 老人+家属房间
  ├── 心跳: 每30s ping, 60s无pong断开
  ├── handleMessage():
  │   ├── auth  → 认证，绑定userId/role，加入房间
  │   ├── fall_event / location_update → broadcastToRoom
  │   ├── assist_request  → sendToUser(老人)
  │   ├── assist_response → sendToUser(家属)
  │   ├── assist_end / assist_cancel
  │   ├── assist_signal / assist_frame  → sendToUser(指定用户)
  │   ├── location_request  → sendToUser(老人)
  │   └── geofence_breach → broadcastToRoom
  └── getOnlineStats() → WS在线状态

db.js      (≈180行)  SQLite数据库
  ├── DatabaseSync (Node.js 22+内置node:sqlite)
  ├── PRAGMA: journal_mode=WAL, foreign_keys=ON, busy_timeout=5000
  └── 表:
      users          — id/deviceId/name/phone/role/lastLocationLat/...
      fall_events    — id/userId/timestamp/lat/lng/impactG/ffDuration/mlScore/physicalScore/status
      locations      — id/userId/lat/lng/accuracy/timestamp
      bind_codes     — id/code/elderId/used/expiresAt
      family_bindings — id/elderId/familyId/relation/status
      geofences      — id/elderId/creatorId/name/lat/lng/radius/isActive/isBreached
      screen_frames  — id/elderId/frameData/frameWidth/frameHeight/frameNum
      logs           — id/userId/level/tag/message/stackTrace/metadata/timestamp
      feedback       — id/type/contact/content/device_model/android_version/app_version
```

---

## 4. 数据流图

### 4.1 跌倒检测流程

```
传感器(50Hz)
  SensorCollector
  ↓ (直接回调，无数据丢失)
FallDetector.onSensorData(accX/g, accY/g, accZ/g, gyro)
  ├─ FreeFall检测: |accMag - 1g| < accThresholdG(0.6g) × 持续时间 > ffTimeMs(200ms)
  ├─ Impact检测: accMag > IMPACT_MIN_HARDCODED(3.0g)
  └─ 三路径评估:
      路径1: ML推理分数 > mlHigh(0.75) → FALL
      路径2: ML×0.6 + 物理×0.4 > weightedScore(0.50) → FALL
      路径3: 物理分数 > physics(0.80) → FALL

FallDetector.onFallDetected()
  ↓
FallDetectionService.showFullScreenAlert()
  ↓ [老人30秒内无响应]
  CloudBaseClient.reportFall(
    lat, lng,
    impactG: Float → .toDouble(),     ⚠️ 必须转Double!
    ffDuration, 
    mlScore: Float → .toDouble(),    ⚠️ 必须转Double!
    physicalScore: Float → .toDouble()  ⚠️ 必须转Double!
  )
  ↓ HTTP POST /fall-report
  server.js
    → 写入 fall_events 表
    → 写入 users.lastFallEvent
    → WS pushToRoom(elderId, fall_event)
  ↓
  子女端WSClient收到 fall_event → 通知推送
```

### 4.2 位置同步流程

```
老人端:
  FallDetectionService.onLocationChanged(lat, lng)
    → CloudBaseClient.syncLocation(lat, lng)
      → HTTP POST /location-sync
        → 写入 locations 表
        → 更新 users.lastLocationLat/lng
        → WS pushToRoom → 子女端

子女端请求老人位置:
  CloudBaseClient.pullLocation(elderId)
    → HTTP POST /location-sync {action: "poll_pull"}
      → server: 设置 users.pullLocationRequest=时间戳
    老人端WSClient收到 location_request
      → CloudBaseClient.uploadLocationNow()
        → 强制GPS刷新 → HTTP POST /location-sync
          → WS推送给子女端
```

### 4.3 远程协助流程 (v18)

```
子女端发起:
  RemoteAssistFragment → WSClient.sendAssistRequest(elderId, guardianName)
    → WS → server → sendToUser(elderId)

老人端收到:
  WSClient → WSEvent.AssistRequest → RemoteAssistActivity弹窗
    → 老人点"允许" → RemoteAssistService.startMpRecording()
      → 启动Overlay
      → PermissionRecordManager.startRecording()
      → 老人操作权限弹窗 → Overlay录制坐标 → 保存recordedSteps
      → 老人点"好的，开始" → PermissionRecordManager.stopRecording()

自动回放(下次协助):
  WS收到 assist_request → RemoteAssistActivity弹窗
    → 老人点"允许" → PermissionRecordManager.tryAutoHandle()
      → 检测到权限弹窗 → 按recordedSteps顺序回放
      → 执行RemoteAssistService.executeClick(x, y)
```

---

## 5. API/WS消息参考

### 5.1 HTTP端点

| 端点 | 方法 | 请求体关键字段 | 返回关键字段 |
|------|------|----------------|-------------|
| `/user-register` | POST | `deviceId`, `name`, `phone`, `role` | `userId` |
| `/fall-report` | POST | `userId`, `timestamp`, `latitude`, `longitude`, `impactG(Double)`, `ffDuration`, `mlScore(Double)`, `physicalScore(Double)` | `eventId`, `notifiedFamily`, `wsPushed` |
| `/location-sync` | POST | `userId`, `latitude`, `longitude`, `accuracy`, `action` | `success`, `wsPushed` |
| `/location-sync` | POST | `action: "poll_pull"` | `hasPullRequest` |
| `/get-status` | GET | `elderId` | `lastFallEvent`, `lastLocationLat/Lng`, `pullLocationStatus` |
| `/bind-family` | POST | `bindCode`, `guardianId` | `code=200`, `elderId`, `elderName` |
| `/fall-history` | GET | `elderId` | `falls[]` |
| `/geofence` | POST | `action: "create"`, `elderId`, `name`, `latitude`, `longitude`, `radius` | `code=200` |
| `/geofence` | POST | `action: "list"`, `elderId` | `fences[]` |
| `/geofence` | POST | `action: "delete"`, `fenceId` | `code=200` |
| `/geofence` | POST | `action: "check"`, `userId`, `latitude`, `longitude` | `breaches[]` |
| `/remote-assist` | POST | `action`, `guardianId`, `elderId` | `code=200` |
| `/restart` | POST | — | `code=200, message: "服务端正在重启..."` |

### 5.2 WebSocket消息

| 消息类型 | 方向 | data字段 |
|---------|------|---------|
| `auth` | C→S | `userId`, `role` ("elder"/"guardian") |
| `fall_event` | S→G | `eventId`, `timestamp`, `impactG`, `mlScore`, `latitude`, `longitude` |
| `location_update` | S→G | `latitude`, `longitude`, `accuracy`, `timestamp` |
| `assist_request` | G→S→E | `guardianId`, `guardianName`, `requestTime` |
| `assist_response` | E→S→G | `accepted`, `sessionId`, `elderId` |
| `assist_end` | E↔G↔S | `from`, `reason` |
| `assist_cancel` | G→S→E | `guardianId` |
| `assist_signal` | E↔G↔S | `type` ("touch"/"swipe"/"key"), `x`, `y`, `x1/y1/x2/y2`, `to`, `from` |
| `assist_frame` | E→S→G | `frameData`(base64), `width`, `height`, `frameNum`, `to` |
| `location_request` | G→S→E | `guardianId`, `requestTime` |
| `geofence_breach` | S→G | `elderName`, `breaches[]`, `timestamp`, `elderId` |

---

## 6. 关键配置速查

### 6.1 ServerConfig (URL配置)

```
老人端 ServerConfig:
  BASE_URL = "http://192.168.4.19:3000"
  WS_URL   = "ws://192.168.4.19:3000/ws"  ⚠️ 不含8080!

子女端 ServerConfig:
  BASE_URL = "http://192.168.4.19:3000"
  WS_URL   = "ws://192.168.4.19:3000/ws"
```

### 6.2 检测灵敏度表 (DetectionConfig)

| 等级 | ffTimeMs | mlHigh | weightedScore | physics |
|------|---------|--------|--------------|---------|
| 1(最严) | 250 | 0.85 | 0.60 | 0.90 |
| 4(默认) | 200 | 0.75 | 0.50 | 0.80 |
| 8(最松) | 150 | 0.65 | 0.40 | 0.70 |

### 6.3 SharedPreferences键名

| 文件 | SP名称 | 关键键 |
|------|--------|--------|
| CloudBaseClient | `cloudbase` | `user_id`, `elder_id` |
| DetectionConfig | `detection_config` | `sensitivity_level`, `acc_threshold`, `ml_high`... |
| PermissionRecordManager | `permission_record` | `recorded_steps_v9`, `recording_meta_v9`, `recording_version` |
| RemoteAssistService | `cloudbase` | `mp_waiting`, `mp_granted` |

### 6.4 AndroidManifest权限声明 (老人端)

```
FOREGROUND_SERVICE, FOREGROUND_SERVICE_LOCATION, FOREGROUND_SERVICE_SPECIAL_USE
POST_NOTIFICATIONS
RECORD_AUDIO, CAMERA
READ_CONTACTS, READ_PHONE_STATE, CALL_PHONE
SEND_SMS, RECEIVE_SMS, READ_SMS
ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION, ACCESS_BACKGROUND_LOCATION
WAKE_LOCK, RECEIVE_BOOT_COMPLETED
BIND_ACCESSIBILITY_SERVICE → RemoteAssistService
BIND_NOTIFICATION_LISTENER_SERVICE
SYSTEM_ALERT_WINDOW
```

### 6.5 关键包名/Activity名

```
老人端:
  包名: com.falldetector.diedaobao
  MainActivity: .ui.MainActivity
  FallDetectionService: 全限定名
  RemoteAssistService: 全限定名

子女端:
  包名: com.familyguardian.app
  MainActivity: .MainActivity
  MapActivity: 全限定名

K70后端:
  路径: ~/diedaobao-server/ (不是 ~/.qclaw/)
  端口: 3000
  DB: ~/diedaobao-server/diedaobao.db
```

---

## 7. 常见陷阱速查表

| # | 陷阱 | 位置 | 正确做法 |
|---|------|------|---------|
| 1 | `JSONObject.put("key", Float)` 崩溃 | CloudBaseClient.reportFall | 必须 `.toDouble()` |
| 2 | WS URL硬编码含8080端口 | 老人端ServerConfig | 应从BASE_URL推导 |
| 3 | `RemoteAssistService.getInstance()` | 所有调用处 | 用 `.instance` |
| 4 | ADB input空格用`%20` | K70调试 | 用 `%s` |
| 5 | 双Gradle同时跑 | 编译时 | 编译完一个立即`pkill -f gradle` |
| 6 | JAVA_HOME未设置 | macOS编译 | 设为 `/Users/zhou/jdk-17/jdk-17.0.2.jdk/Contents/Home` |
| 7 | versionCode未递增 | build.gradle.kts | 每次编译前+1 |
| 8 | git commit忘记 | 代码修改后 | 改完必须commit |
| 9 | 老人端BASE_URL用localhost | 配置 | 老人端在K70上跑，填K70的IP |
| 10 | 子女端BASE_URL填localhost | 配置 | 子女端在手机/模拟器，填K70的IP |

---

## 8. 文件路径索引

### 按功能检索

| 关键词 | 相关文件 |
|--------|---------|
| 跌倒检测算法 | `detect/FallDetector.kt`, `detect/DetectionConfig.kt` |
| ML推理 | `ml/FallDetectorML.kt`, `ml/FallDetectorONNX.kt` |
| 传感器采集 | `sensor/SensorCollector.kt` |
| 权限录制回放 | `assist/PermissionRecordManager.kt`, `assist/RemoteAssistService.kt` |
| 屏幕捕获 | `assist/ScreenCaptureService.kt` |
| WebSocket推送 | `cloud/WSClient.kt` (老人端/子女端) |
| HTTP API | `cloud/CloudBaseClient.kt` (老人端/子女端) |
| 位置同步 | `service/FallDetectionService.kt`, `cloud/CloudBaseClient.kt` |
| 数据库 | `db.js`, `data/AppDatabase.kt` |
| 围栏 | `ui/GeofenceFragment.kt`, `ui/MapActivity.kt`, `CloudBaseClient.kt` |
| 反馈 | `feedback/FeedbackActivity.kt`, `feedback/FeedbackSender.kt` |
| 开机启动 | `service/BootReceiver.kt` |
| 灵敏度配置 | `detect/DetectionConfig.kt` |
| URL配置 | `config/ServerConfig.kt` (老人端/子女端) |
| APK构建 | `app/build.gradle.kts` (老人端/子女端) |

---

## 9. ⛔ 禁区 — 绝对不能修改

> 以下模块已稳定运行，任何修改都可能导致检测失效，**除非明确要求，否则绝对不能动**

| 禁区 | 文件 | 原因 |
|------|------|------|
| ⛔ **ML推理** | `ml/FallDetectorML.kt`, `ml/FallDetectorONNX.kt` | 算法已训练调优，动则误报率飙升 |
| ⛔ **ONNX模型** | `assets/fall_model.onnx` | ML模型文件，不可修改 |
| ⛔ **物理模型系数** | `FallDetector.kt`中的物理分数计算公式 | 经验参数，基于大量测试调优 |
| ⛔ **传感器采样率** | `SensorCollector.kt`的SENSOR_DELAY_GAME(50Hz) | 改频率会影响检测时序 |

**如需调整灵敏度**：只能改 `DetectionConfig.kt` 中的阈值参数（已提供8级可调）

---

## 10. 待办/已知问题

- [ ] 老人端WS_URL硬编码含8080端口，应从BASE_URL推导
- [ ] ML推理从未触发（灵敏度阈值问题，v17待真机测试）
- [ ] POST /restart端点刚添加，待K70真机测试
- [ ] 子女端versionCode=28，老人端versionCode=136
- [ ] 子女端BASE_URL仍为192.168.4.19，待更新为serveo.net隧道URL

---

*地图由AI基于源代码全文扫描自动生成，如有出入以源代码为准。*
