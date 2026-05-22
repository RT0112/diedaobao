# 跌倒宝（DieDaoBao）项目 — Claude Code 开发指南

## 项目架构

三端联动的老人跌倒检测系统：

```
老人端 App (Kotlin) → 后端 (Node.js/Express/SQLite/WS) → 子女端 App (Kotlin)
   fall-detection-app/      diedaobao-server/            family-guardian-app/
```

- **外网域名**: `https://oscular-august-equanimously.ngrok-free.dev`（Mac中继ngrok）
- **后端跑在Mac上**，ngrok指向Mac:3000，K70只跑老人端App
- **老人端**：41个Kotlin文件，~38MB APK
- **子女端**：21个Kotlin文件，~17MB APK
- **后端**：3个JS文件（server.js, ws.js, db.js）

## 目录结构

```
projects/
├── fall-detection-app/          ← 老人端
│   ├── detect/                  ← 跌倒检测（FallDetector, DetectionConfig）
│   ├── service/                 ← 前台服务（FallDetectionService, BootReceiver, BootWorker）
│   ├── assist/                  ← 远程协助（RemoteAssistService, ScreenCaptureService, PermissionRecordManager）
│   ├── ui/                      ← UI（MainActivity, HomeFragment, ConfirmActivity, SettingsFragment...）
│   ├── cloud/                   ← HTTP（CloudBaseClient）+ WS（WSClient）
│   ├── data/                    ← Room（AppDatabase, Contact, FallEvent, Repository）
│   ├── sensor/                  ← 传感器采集（SensorCollector）
│   ├── notify/                  ← 紧急通知（EmergencyNotifier, PhoneCaller, SmsSender）
│   ├── ml/                      ← ML推理（FallDetectorML, FallDetectorONNX）
│   ├── feedback/                ← 反馈（FeedbackActivity, FeedbackSender）
│   ├── config/                  ← ServerConfig
│   └── FallDetectionApp.kt      ← Application入口
│
├── family-guardian-app/         ← 子女端
│   ├── ui/                      ← UI（MainActivity, HomeFragment, GeofenceFragment, MapActivity...）
│   ├── cloud/                   ← HTTP（CloudBaseClient）+ WS（WSClient）
│   ├── data/                    ← Room（AppDatabase, FallNotification）
│   ├── feedback/                ← 反馈（FeedbackActivity, FeedbackSender）
│   ├── assist/                  ← RemoteAssistManager
│   ├── config/                  ← ServerConfig
│   └── FamilyGuardianApp.kt     ← Application入口
│
└── diedaobao-server/            ← 后端
    ├── server.js                ← Express API + WebSocket
    ├── ws.js                    ← WebSocket推送
    └── db.js                    ← SQLite
```

## 核心数据流

```
传感器50Hz → SensorCollector → FallDetector(ML+物理) → FallDetectionService
    → ConfirmActivity（倒计时确认）
    → CloudBaseClient.reportFall() → POST /fall-report
    → server.js → ws.js推送fall_event
    → 子女端WSClient → HomeFragment弹窗+通知
```

远程协助流：子女请求 → WS推送 → 老人端RemoteAssistService → 无障碍点击 → 截屏回传

## 🔥 编译铁律

```bash
# JDK路径（必须设置！系统PATH无java）
JAVA_HOME=/Users/zhou/jdk-17/jdk-17.0.2.jdk/Contents/Home

# 编译（--no-daemon --max-workers=1，8GB内存Mac只能一个Gradle）
JAVA_HOME=/Users/zhou/jdk-17/jdk-17.0.2.jdk/Contents/Home \
  ./gradlew assembleDebug --no-daemon --max-workers=1

# 编译完立刻杀Java进程（两Gradle同时跑坐标映射出错！）
pkill -f gradle
```

**每次编译前递增versionCode**，否则Android不更新。

## 代码陷阱

### 1. RemoteAssistService 单例
```kotlin
// ❌ 错误（不存在）
RemoteAssistService.getInstance()
// ✅ 正确
RemoteAssistService.instance
```

### 2. JSONObject.put() Kotlin类型陷阱
```kotlin
// ❌ Android JSONObject无 put(String, Float)签名，会NoSuchMethodError
json.put("impactG", impactG)
// ✅ 必须显式转Double
json.put("impactG", impactG.toDouble())
```

### 3. Kotlin thread {} 必须try-catch
未捕获异常会崩溃整个进程。

### 4. JavaScript || vs ??
`||` 把0和空字符串视为falsy，数值字段用 `??`。

### 5. 双路径Intent数据一致性
当同一Activity有两条启动路径时（如前台startActivity vs 锁屏PendingIntent），必须检查两条路径的Intent extras是否一致。

## 后端API速查

| 端点 | 方法 | 说明 |
|------|------|------|
| /health | GET | 健康检查 |
| /user-register | POST | 注册用户 |
| /fall-report | POST | 跌倒上报+WS推送 |
| /bind-family | POST | 家庭绑定 |
| /location-sync | POST | 位置同步 |
| /geofence | POST | 围栏CRUD+检查 |
| /remote-assist | POST | 协助请求/响应/信令/帧 |
| /request-elder-location | POST | 请求老人位置 |

WS消息：auth, fall_event, location_update, assist_request/response/signal/frame/end, geofence_breach

## 工作规范

1. **不要用write工具写代码文件** — 用patch/精确替换
2. **改完代码必须git commit** — 不要只存记忆
3. **绝不让用户排查日志** — 自己用ADB/SSH查
4. **必须K70验收通过才发微信** — 编译→安装→K70实测→确认PASS→发微信。禁止未经验证就发APK。验收不过就继续修，不发。
5. **双端都改了→双端APK都发微信** — wechat_mac_send.sh
6. **network_security_config.xml不支持CIDR** — 写具体IP
7. **K70 SSH端口8222**（不是8022）
8. **全部任务完成后→通知qclaw** — 发送完成消息+修改摘要（见下方脚本）

## 通知qclaw（任务完成后最后一步）

**消息格式**：不只发"我跑完了"，还要附上改了什么bug，方便qclaw验证：

```
我跑完了 ✅

修复内容：
1. xxx
2. xxx
```

**用法**：把修改摘要赋值给变量，脚本自动粘贴发送：

```bash
# 1. 设置消息内容（根据实际修改填写）
NOTIFY_MSG="我跑完了 ✅

修复内容：
1. 老人端uploadLocationNow崩溃 - 改用标志位避免协程竞态 + WS推送加try-catch
2. 子女端位置超时 - 等待WS连接后再发请求 + 轮询从2s改1s"

# 2. 激活qclaw + 粘贴 + 回车
osascript -e 'tell application "QClaw" to activate' 2>/dev/null
sleep 1
echo "$NOTIFY_MSG" | pbcopy
sleep 0.5
osascript -e 'tell application "System Events" to keystroke "v" using command down' 2>/dev/null
sleep 0.5
osascript -e 'tell application "System Events" to keystroke return' 2>/dev/null
echo "✅ 已通知qclaw"
```
