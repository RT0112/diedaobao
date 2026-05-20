# 跌倒宝(DieDaoBao)项目代码遍历总结

**时间**: 2026-05-16  
**任务**: 遍历项目代码结构，整理开发文档供后续快速上手  
**状态**: ✅ 已完成

---

## 项目概览

**项目路径**:
- Android App: `/Users/zhou/.qclaw/workspace-x5kuz49xple53hhg/projects/fall-detection-app/`
- 后端服务器: `/Users/zhou/.qclaw/workspace-x5kuz49xple53hhg/projects/diedaobao-server/`

**技术栈**:
- Android: Kotlin + Jetpack Compose/ViewBinding + Room + OkHttp + WebSocket + ONNX/TFLite
- 后端: Node.js + Express + SQLite + WebSocket

---

## 已遍历模块清单

### Android端 (40个.kt文件)

#### 1. Application初始化
- ✅ `FallDetectionApp.kt` - Application类，初始化全局依赖

#### 2. 传感器与检测 (detect + sensor)
- ✅ `SensorCollector.kt` - 传感器数据采集 (50Hz, 直接回调FallDetector)
- ✅ `DetectionConfig.kt` - 检测参数配置 (8级灵敏度可调)
- ✅ `FallDetector.kt` - 核心检测算法 (三路径决策树)
- ✅ `FallDetectorONNX.kt` - ONNX推理引擎 (50维特征, RF F1=0.8148)
- ✅ `FallDetectorML.kt` - TFLite推理引擎 (85维特征, 遗留代码)

#### 3. 前台服务 (service)
- ✅ `FallDetectionService.kt` - 跌倒检测前台服务 (保活+传感器+通知)
- ✅ `BootReceiver.kt` - 开机自启广播接收器
- ✅ `BootWorker.kt` - WorkManager保活 (15分钟心跳)

#### 4. 远程协助 (assist)
- ✅ `RemoteAssistService.kt` - 无障碍服务 (手势+截图)
- ✅ `RemoteAssistManager.kt` - 远程协助管理器 (WS+HTTP双模)
- ✅ `PermissionRecordManager.kt` - 权限操作录制与回放
- ✅ `ScreenCaptureService.kt` - 屏幕捕获服务 (MediaProjection)
- ✅ `TouchRecordOverlay.kt` - 触摸录制覆盖层

#### 5. 云端通信 (cloud)
- ✅ `CloudBaseClient.kt` - HTTP客户端 (OkHttp, 10s/30s超时)
- ✅ `WSClient.kt` - WebSocket客户端 (重连+心跳)

#### 6. 数据存储 (data)
- ✅ `AppDatabase.kt` - Room数据库 (version=2)
- ✅ `Contact.kt` - 联系人实体
- ✅ `FallEvent.kt` - 跌倒事件实体
- ✅ `ContactDao.kt` - 联系人DAO
- ✅ `FallEventDao.kt` - 跌倒事件DAO
- ✅ `Repository.kt` - 数据仓库

#### 7. 通知渠道 (notify)
- ✅ `EmergencyNotifier.kt` - 紧急通知调度器 (电话+短信+企微)
- ✅ `PhoneCaller.kt` - 电话拨打 (TelecomManager双卡兼容)
- ✅ `SmsSender.kt` - 短信发送 (带位置链接)
- ✅ `WeChatNotifier.kt` - 企业微信Webhook通知

#### 8. 用户界面 (ui)
- ✅ `MainActivity.kt` - 主活动 (底部导航)
- ✅ `HomeFragment.kt` - 主页 (开启/关闭守护)
- ✅ `ContactsFragment.kt` - 紧急联系人管理
- ✅ `HistoryFragment.kt` - 跌倒历史记录
- ✅ `SettingsFragment.kt` - 设置页面 (灵敏度+通知测试)
- ✅ `Test1Fragment.kt` - 测试页面 (传感器实时监控)
- ✅ `ConfirmActivity.kt` - 跌倒确认页面 (全屏倒计时)
- ✅ `PermissionActivity.kt` - 权限设置引导
- ✅ `RemoteAssistActivity.kt` - 远程协助页面

#### 9. 反馈系统 (feedback)
- ✅ `FeedbackActivity.kt` - 误报反馈页面
- ✅ `FeedbackSender.kt` - 反馈提交器 (HTTP POST)

#### 10. 工具类 (util)
- ✅ `AppLogger.kt` - 日志工具 (云端上传)
- ✅ `LogUploader.kt` - 崩溃日志上传

---

### 后端 (7个文件)

#### 1. 主服务器
- ✅ `server.js` (~587行) - Express服务器 + WebSocket
  - 端口: 3000 (HTTP + WebSocket统一)
  - 数据库: node:sqlite (WAL模式)
  - API端点:
    - `/health` - 健康检查
    - `/user-register` - 用户注册 (elder/family角色区分)
    - `/fall-report` - 跌倒报告 (WS实时推送)
    - `/location-sync` - 位置同步 (poll_pull + 上传)
    - `/get-status` - 获取用户状态
    - `/bind-family` - 家属绑定 (6位码, 5分钟有效)
    - `/fall-history` - 跌倒历史查询
    - `/geofence` - 围栏管理 (CRUD + Haversine检测)
    - `/remote-assist` - 远程协助 (request/respond/signal/frame, v23顶掉机制)
    - `/request-elder-location` - 请求老人位置
    - `/upload-log` - 日志上传
  - WebSocket事件:
    - `assist_request` - 协助请求
    - `assist_signal` - WebRTC信令转发
    - `assist_frame` - 屏幕帧推送
    - `location_request` - 位置请求
    - `fall_event` - 跌倒事件推送

---

## 关键设计决策

### 1. 双引擎检测
- **ONNX优先**: RandomForest模型, 50维特征, gravityAlign预处理
- **TFLite降级**: 85维特征, 遗留代码, 作为备用
- **三路径决策树**:
  - 路径1: ML高阈值 (≥0.75) → 直接报警
  - 路径2: ML中阈值 (≥0.50) + 加权评分 (物理36% + ML64%) ≥0.50 → 报警
  - 路径3: 物理覆盖 (physScore≥0.80 且冲击≥3.0g 且 FF≥200ms 且 ML≥0.25) → 报警

### 2. 传感器数据流
- **直接回调**: SensorCollector → FallDetector.feed() (绕过StateFlow, 降低延迟)
- **限流保护**: Mutex + 18ms限流 (防止ANR)
- **单位转换**: 加速度 m/s² → g, 陀螺仪积分 → postureAngle

### 3. 位置系统
- **双轨并行**:
  - 按需拉取轮询: 常态10秒/加急3秒
  - 本地GPS监听: requestLocationUpdates + 被动监听网络
- **精度分级上传**:
  - GPS ≤50m → 30秒内直接上传
  - 网络 ≤30m → 15秒内直接上传
  - 否则 requestSingleUpdate 10秒超时

### 4. 远程协助
- **双模架构**: WebSocket主 + HTTP轮询降级
- **WS健康检查**: 30秒 ping/pong, 死亡自动重启轮询
- **多监听器注册**: assist_request/location_request/assist_cancel/assist_end/assist_signal
- **坐标映射**: 视频流分辨率 → 屏幕分辨率 (比例缩放)

### 5. 屏幕共享
- **MediaProjection优先**: VirtualDisplay → ImageReader → YUV→RGB→Bitmap→JPEG→Base64
- **降级方案**: AccessibilityService.takeScreenshot() (0.5fps, 360p)
- **帧率控制**: 1fps, 最大上传延迟2秒 (丢帧策略)
- **R↔B通道swap**: ImageReader RGBA_8888需要手动交换R/B

### 6. 权限录制回放
- **首次录制**: 手动操作, 捕获触摸坐标 (忽略启动后1秒)
- **后续回放**: 按真实间隔 dispatchGesture
- **第一步延迟**: 3秒 (等待Activity加载)
- **步骤间延迟**: 最低800ms (防止过快)
- **失败重试**: dispatchGesture失败重试2次, 最后一步权限未授权重试3次
- **总超时**: 30秒

### 7. 崩溃恢复
- **writeCrashLog**: 捕获全局异常, 写入本地文件
- **restartApp**: 崩溃后自动重启Application
- **CorruptedSharedPreferences修复**: 捕获异常, 删除损坏文件

### 8. 通知系统
- **三渠道**: 电话 (TelecomManager) + 短信 (SmsManager) + 企业微信 (Webhook)
- **优先级排序**: 按priority字段升序
- **电话间隔**: 8秒 (防止占线)
- **联系人间隔**: 2秒
- **企微群发**: 独立线程, 遍历所有Webhook URL

---

## 文件状态

### ✅ 已创建
- `/docs/PROJECT_GUIDE.md` (8986字节) - 项目开发指南
  - 项目结构图
  - 老人端/子女端APK说明
  - 核心模块说明
  - 开发规范
  - 常用命令
  - 已知问题

### ✅ 已完成
- 项目迁移: `~/.qclaw/diedaobao-agent/` → `~/.qclaw/workspace-x5kuz49xple53hhg/`
- 原目录删除
- 所有核心模块遍历 (40个.kt文件 + 7个后端文件)

---

## 待完成事项

### 1. Android端
- [ ] 读取布局文件 (XML)
- [ ] 读取 build.gradle (依赖版本确认)
- [ ] 读取 proguard-rules.pro (混淆配置)
- [ ] 测试代码覆盖率

### 2. 后端
- [ ] K70 Termux正式部署步骤文档
- [ ] 自动化启动脚本 (termux-boot)
- [ ] 日志轮转配置

### 3. 文档
- [ ] 更新 PROJECT_GUIDE.md 添加新遍历的模块说明
- [ ] 创建 API.md (后端API完整文档)
- [ ] 创建 ARCHITECTURE.md (架构设计文档)
- [ ] 创建 DEPLOYMENT.md (部署手册)

---

## 关键发现

### 1. 代码质量
- ✅ 生产级代码, 注释清晰
- ✅ 边界情况考虑周全 (权限/版本兼容)
- ✅ 错误处理完善 (try-catch + 降级)
- ⚠️ 部分遗留代码未清理 (FallDetectorML TFLite)

### 2. 性能优化
- ✅ 传感器直接回调 (低延迟)
- ✅ Mutex限流 (防ANR)
- ✅ Room数据库 (异步查询)
- ✅ OkHttp连接池 (HTTP复用)
- ⚠️ 屏幕帧Base64编码 (体积大, 建议改Binary)

### 3. 兼容性
- ✅ 厂商适配: 小米/华为/OPPO/vivo/三星/一加/魅族
- ✅ Android版本适配: 5.1+ (API 22+)
- ✅ 双卡适配: TelecomManager (MIUI兼容)
- ⚠️ Android 15+ 受限设置页面 (需引导用户手动)

---

## 下一步建议

1. **补充文档**: 创建 API.md 和 ARCHITECTURE.md
2. **清理代码**: 删除 FallDetectorML (TFLite遗留)
3. **性能优化**: 屏幕帧改Binary传输 (减少Base64开销)
4. **测试**: 添加单元测试 (核心算法+DAO)
5. **部署**: 编写K70 Termux部署脚本

---

**遍历完成时间**: 2026-05-16 20:30  
**总文件数**: 47个 (40个.kt + 7个后端)  
**总代码行数**: ~8000行 (估算)  
**文档状态**: ✅ PROJECT_GUIDE.md 已创建
