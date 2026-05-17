# 跌倒宝（DieDaoBao）项目开发指南

> 本文档基于代码遍历生成，供后续开发快速上手使用。
> 最后更新：2026-05-16

---

## 一、项目概览

**跌倒宝** = 老人跌倒检测App（Android）+ K70本地后端（Node.js）+ 子女端监控

| 组件 | 技术 | 大小 | 部署位置 |
|------|------|------|----------|
| 老人端APK | Kotlin Android | ~38MB | 老人手机（K70） |
| 子女端APK | Kotlin Android | ~17MB | 子女手机 |
| 后端 | Node.js/Express/SQLite/WebSocket | - | K70 Termux |
| K70屏幕 | 1440×3200 | - | 小米机型 |

**后端地址**：`http://localhost:3000`

---

## 二、目录结构

```
projects/
├── fall-detection-app/          ← Android App（39个Kotlin文件）
│   ├── detect/                  ← 跌倒检测算法
│   ├── assist/                  ← 远程协助（RemoteAssistService, PermissionRecordManager）
│   ├── ui/                      ← UI层
│   ├── cloud/                   ← HTTP客户端（CloudBaseClient）+ WS客户端
│   ├── data/                    ← Room数据库
│   ├── sensor/                  ← 传感器采集
│   ├── notify/                  ← 紧急通知（电话/短信/企微）
│   ├── ml/                      ← ML模型（TFLite/ONNX）
│   ├── feedback/                ← 反馈系统
│   ├── util/                    ← 工具类
│   └── service/                 ← 前台服务（FallDetectionService）
├── diedaobao-server/            ← K70后端
│   ├── server.js                ← Express API + WebSocket
│   ├── ws.js                    ← WebSocket推送
│   └── db.js                    ← SQLite
```

---

## 三、核心模块详解

### 3.1 跌倒检测（detect/）

#### FallDetector.kt — 核心检测引擎

**职责**：传感器数据→跌倒判断（ML+物理融合）

**数据流**：
```
传感器50Hz → feed() → 窗口维护 → 状态机 → ML推理 → 三路径决策 → 报警
```

**状态机**：
- `MONITORING` — 监控中，等待冲击触发
- `IMPACT_DETECTED` — 冲击触发，进入确认期（2300ms）

**三路径决策**（v0.30+）：
1. **路径1（ML高阈值）**：`mlProb >= cfg.mlHigh` → 直接报警
2. **路径2（加权评分）**：`mlProb >= 0.25` 且 `weightedScore >= cfg.weightedScoreThresh` → 加权报警
3. **路径3（物理覆盖）**：`physScore >= cfg.physicsThresh` 且 冲击>=3g 且 FF>=cfg.ffTimeMs 且 mlProb>=0.25 → 物理覆盖报警

**关键参数**（DetectionConfig.kt）：
| 参数 | 默认值 | 说明 |
|------|--------|------|
| accThresholdG | 0.6f | 冲击触发门槛（dyn=\|accMag-1g\|） |
| mlHigh | 0.75f | 路径1 ML高阈值 |
| weightedScoreThresh | 0.50f | 路径2加权评分阈值 |
| physicsThresh | 0.80f | 路径3物理评分阈值 |
| ffTimeMs | 200 | 失重时间要求(ms) |
| ffQuality | 0.08f | 失重质量 |
| velocityMin | 0.8f | 最小速度(m/s) |

**8级灵敏度**（v0.30.0）：
- 等级1 = 最严格（ffTime=250ms）
- 等级4 = 默认推荐（ffTime=200ms）
- 等级8 = 最宽松（ffTime=150ms）

**500ms窗口FF检测**（v0.29）：
- 从500ms滑动窗口提取FF段
- 合并相邻FF段（间隔<500ms且速度趋势递增）
- 用signedVelocity方向判断替代速度趋势检测

**确认期运动检测**（v0.32.3）：
- 600ms回弹保护期（不检查运动）
- 三轴标准差综合判断：`stdX + stdY + stdZ > 0.5`
- 连续5帧超标才取消（避免噪声误触）

#### DetectionConfig.kt — 参数配置

- SharedPreferences持久化
- 支持实时调节（测试页面）
- 灵敏度等级自动调整参数

### 3.2 前台服务（service/）

#### FallDetectionService.kt — 守护服务

**职责**：
1. 启动传感器采集（SensorCollector）
2. 喂数据给FallDetector
3. 跌倒时触发：全屏通知 + 震动 + ConfirmActivity
4. 定期位置同步（GPS + 围栏检测）
5. 远程协助轮询（Service级别，后台也能响应）

**保活机制**：
- 前台服务（通知栏常驻）
- `onTaskRemoved` 自动重启
- `onDestroy` 非用户停止时AlarmManager重启
- SharedPreferences保存重启标志

**位置同步策略**：
- 按需拉取轮询（10秒间隔，无需权限）
- 本地GPS监听（需要权限，50米/5分钟去重）
- 围栏检测（本地Haversine，零云端成本）
- 15分钟强制兜底上传

**通知渠道**：
1. `fall_detection_channel` — 守护服务（低优先级）
2. `fall_alert_channel` — 紧急告警（最高优先级+全屏+锁屏）
3. `full_screen_channel` — 全屏告警（无声音，全屏弹出）
4. `remote_assist_channel` — 远程协助请求（来电式铃声）

### 3.3 远程协助（assist/）

#### RemoteAssistService.kt — 无障碍服务（v17极简版）

**职责**：
- 监听窗口状态变化（TYPE_WINDOW_STATE_CHANGED）
- 检测系统弹窗（只看com.android.systemui）
- 执行手势（点击/滑动/双击）
- 截图（API 30+）
- 导航键支持（Home/Back/Recents）

**关键API**：
```kotlin
// ✅ 正确（单例属性）
RemoteAssistService.instance

// ❌ 错误（不存在的方法）
RemoteAssistService.getInstance()
```

**手势执行**：
- `executeClick(x, y, durationMs)` — 点击（最少150ms，MIUI兼容）
- `executeSwipe(x1,y1,x2,y2,durationMs)` — 滑动
- `executeDoubleClick(x, y)` — 双击
- `executeGlobalAction(keyCode)` — 导航键

#### PermissionRecordManager.kt — 权限录制/回放（v18）

**核心原则**：录制和回放不会在同一个协助会话里同时发生

**首次流程**：允许协助 → 录制模式 → 用户操作弹窗 → 弹窗关闭 → 保存录制 → 结束
**再次流程**：允许协助 → 回放模式 → 检测弹窗 → 按录制自动点击 → 弹窗关闭 → 结束

**录制**：
- 启动后1秒内忽略触摸（跳过"好的，开始"按钮误录）
- 保存坐标+时间戳+设备信息
- SharedPreferences持久化（JSON格式）

**回放**：
- 只有expectingReplay=true时才触发
- 第一步延迟3秒（MIUI弹窗动画慢）
- 步骤间延迟至少800ms
- 每步最多重试2次，最后一步最多重试3次
- 总超时30秒

#### RemoteAssistManager.kt — 协助管理器（v3 WS+HTTP降级）

**职责**：
1. 接收WS推送的协助请求（降级HTTP poll_request轮询）
2. 接收WS推送的触控信令（降级HTTP poll_signal轮询）
3. 管理协助生命周期
4. 协调AccessibilityService + ScreenCaptureService

**轮询策略**：
- WS优先：连接成功后停掉HTTP轮询
- 健康检查：每30秒检测pollJob是否活着，死了自动重启
- 请求去重：同一个请求只通知一次，防止双弹窗

**信号类型**：
- `touch` — 点击/滑动/长按/双击
- `key` — 导航键（home/back/recents）
- `end_session` — 结束会话

#### ScreenCaptureService.kt — 屏幕共享服务

**帧采集**：
1. MediaProjection → VirtualDisplay → ImageReader Surface
2. ImageReader.OnImageAvailableListener回调取帧
3. YUV→RGB→Bitmap→JPEG→Base64→上传

**替补模式**：
- ImageReader失败时启动AccessibilityService截图
- 0.5fps，缩放至360p
- JPEG质量25%

**上传**：
- WS优先发送（低延迟）
- HTTP降级（POST /remote-assist upload_frame）
- 连续5次上传失败主动停止

### 3.4 网络通信（cloud/）

#### CloudBaseClient.kt — HTTP API客户端

**直连K70本地服务器**：`http://localhost:3000`

**核心API**：
| 方法 | 端点 | 说明 |
|------|------|------|
| registerUser | POST /user-register | 注册用户 |
| reportFall | POST /fall-report | 上报跌倒事件 |
| syncLocation | POST /location-sync | 同步位置 |
| generateBindCode | POST /bind-family generateCode | 生成绑定码 |
| checkGeofenceBreaches | POST /geofence check | 检查围栏越界 |
| pollPullRequest | POST /location-sync poll_pull | 轮询位置拉取请求 |

#### WSClient.kt — WebSocket实时推送

**连接**：`ws://localhost:3000/ws`

**事件类型**（sealed class WSEvent）：
- `AssistRequest` — 协助请求
- `LocationRequest` — 位置请求
- `AssistCancel` — 协助取消
- `AssistEnd` — 协助结束
- `AssistSignal` — 触控/导航键信令

**降级策略**：WS连接失败时自动退化为HTTP轮询

### 3.5 紧急通知（notify/）

#### EmergencyNotifier.kt — 通知调度器

**通知渠道**（可配置）：
1. **电话** — CALL_PHONE权限，直接拨号
2. **短信** — SEND_SMS权限，发送跌倒位置
3. **企业微信** — Webhook群发，独立于联系人

**企微通知内容**（v0.29.6+）：
- 跌倒时间、位置（含地图链接）
- ML概率、冲击力、跌倒高度、物理评分
- 决策路径

### 3.6 传感器（sensor/）

#### SensorCollector.kt — 传感器采集

**配置**：
- 加速度计 + 陀螺仪
- SENSOR_DELAY_GAME ≈ 20ms (~50Hz)
- 直接回调模式（绕过StateFlow轮询，0数据丢失）

**数据转换**：
- 加速度转换为g（除以9.8）
- 陀螺仪积分计算姿态角变化

### 3.7 数据库（data/）

#### AppDatabase.kt — Room数据库

**实体**：
- `Contact` — 紧急联系人
- `FallEvent` — 跌倒事件（v0.30.9+）

**用途**：
- 联系人管理
- 跌倒历史记录
- 反馈系统数据

### 3.8 K70后端（diedaobao-server/）

#### server.js — Express API

**路由**：
| 端点 | 方法 | 说明 |
|------|------|------|
| /health | GET | 健康检查 |
| /user-register | POST | 注册用户 |
| /fall-report | POST | 跌倒上报 + WS推送 |
| /bind-family | POST | 家庭绑定（生成码/查询/绑定） |
| /location-sync | POST | 位置同步（含poll_pull） |
| /get-status | GET | 查询老人状态 |
| /fall-history | GET | 跌倒历史 |
| /geofence | POST | 围栏管理（CRUD+检查） |
| /remote-assist | POST | 远程协助（请求/响应/信令/帧） |
| /request-elder-location | POST | 请求老人位置 |
| /upload-log | POST | 日志上传/查询 |

#### ws.js — WebSocket推送

**连接管理**：
- userId → WebSocket映射
- 房间管理：elderId → Set<userId>

**消息类型**：
- `auth` — 认证
- `fall_event` — 跌倒通知
- `location_update` — 位置更新
- `location_request` — 位置请求
- `assist_request` — 协助请求
- `assist_response` — 协助响应
- `assist_signal` — 触控信令
- `assist_frame` — 屏幕帧
- `assist_end` — 协助结束
- `assist_cancel` — 协助取消
- `geofence_breach` — 围栏越界

**心跳**：30秒ping，60秒无pong断开

#### db.js — SQLite数据库

**表结构**：
- `users` — 用户（老人/家属）
- `fall_events` — 跌倒事件
- `locations` — 位置历史
- `bind_codes` — 绑定码
- `family_bindings` — 家庭绑定关系
- `geofences` — 围栏
- `screen_frames` — 屏幕帧缓存
- `logs` — 日志

---

## 四、关键开发铁律

### 4.1 编译规则
> **编译完一个端 → 立刻 `pkill -f gradle` → 再编译另一个**

同时跑两个Gradle会导致坐标映射出错。

### 4.2 RemoteAssistService API
```kotlin
// ✅ 正确
RemoteAssistService.instance

// ❌ 错误（不存在）
RemoteAssistService.getInstance()
```

### 4.3 K70调试
- **ADB不可靠**：shell/RUN_COMMAND/input text 全部失败
- **SSH唯一方案**：`adb forward tcp:8022 tcp:8022` → `ssh localhost -p 8022`
- **K70屏幕分辨率**：1440×3200，触控坐标要按这个比例映射

### 4.4 权限录制流程
1. 点"允许" → 录制引导弹窗
2. 点"好的，开始" → **先启动Overlay** → 再弹出系统弹窗
3. 用户操作弹窗 → Overlay拦截坐标 → 录进recordedSteps

### 4.5 坐标映射（v19.7.2）
子女端发的是视频流坐标（如360×765），需要映射到老人端屏幕坐标（1440×3062）：
```kotlin
val scaleX = screenSize.x / streamWidth
val scaleY = screenSize.y / streamHeight
val mappedX = x * scaleX
val mappedY = y * scaleY
```

---

## 五、常用命令

```bash
# 编译老人端
cd projects/fall-detection-app
./gradlew assembleElderRelease
pkill -f gradle

# 编译子女端
./gradlew assembleChildRelease
pkill -f gradle

# K70连接测试
adb devices
adb forward tcp:8022 tcp:8022
ssh localhost -p 8022

# 后端部署
cd projects/diedaobao-server
npm install
node server.js

# 查看日志
tail -f ~/.qclaw/workspace-x5kuz49xple53hhg/projects/diedaobao-server/diedaobao.db
```

---

## 六、已知问题/待办

| 问题 | 状态 | 说明 |
|------|------|------|
| v17真机测试 | ⏳ 待测试 | 2026-05-13编译成功，待K70实机验证 |
| K70设备连接 | ⏳ 待确认 | 需验证WS连接 |
| 双端APK联调 | ⏳ 待测试 | 五项联调修复后的完整流程 |

---

## 七、版本演进

### 权限自动点击演进
| 版本 | 方案 | 结果 | 根因 |
|------|------|------|------|
| v1~v4 | TouchRecordOverlay悬浮窗 | ❌ 失败 | z-order被系统弹窗遮挡 |
| v5 | AccessibilityService节点树 | ✅ 成功 | 绕过z-order |
| v17 | 极简重写（493行→200行） | ✅ 成功 | 修复三大问题 |

### v17核心修复
1. 只检查 `com.android.systemui` 窗口，避免误报
2. `dispatchGestureClick` 改为纯异步
3. 保存录制时重置禁用标志

### HyperOS两步弹窗结构
1. 第一步：权限说明弹窗（"此应用将捕获您屏幕上的所有内容..."）
2. 第二步：实际权限弹窗（允许/拒绝）
→ 录制时两个都要拦截坐标

---

## 八、开发原则

1. **先读代码，再给建议** — 不要猜测，先理解完整数据流
2. **根因分析 > 试错修复** — 找到根因再动手
3. **完整流程验证** — 修复后测试完整路径
4. **重要规则写进记忆** — 学到的教训要记录

---

*本文档由AI基于代码遍历自动生成，如有遗漏请补充。*
