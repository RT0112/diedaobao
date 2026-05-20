# 跌倒宝开发技能

## 触发场景
当你需要：
- 阅读/修改跌倒宝源码
- 调试K70后端
- 修复bug
- 分析报错
- 部署到K70

## 🔍 代码阅读方法

### 拿到任务后

1. **定位文件** - 先找到对应的Kotlin/JS文件
2. **理解数据流** - 这个功能从哪里来，到哪里去
3. **分析关键函数** - 核心逻辑是什么
4. **再动手** - 理解后再修改

### 关键文件索引

```
跌倒检测：
  FallDetector.kt → 主检测器（ML+物理融合）
  FallDetectorML.kt → TensorFlow Lite
  SensorCollector.kt → 传感器采集

远程协助：
  RemoteAssistService.kt → 无障碍服务入口
  PermissionRecordManager.kt → 录制/回放（v17核心）
  TouchRecordOverlay.kt → 透明悬浮层
  ScreenCaptureService.kt → 屏幕捕获
  RemoteAssistManager.kt → 会话管理

通讯：
  CloudBaseClient.kt → HTTP调用K70
  WSClient.kt → WebSocket推送
  server.js → Express API路由
  ws.js → WebSocket服务端
  db.js → SQLite操作

UI：
  MainActivity.kt → 主入口
  HomeFragment.kt → 首页
  PermissionActivity.kt → 权限引导页
  RemoteAssistActivity.kt → 远程协助页
```

---

## 🐛 Bug修复流程

### 遇到报错时

```
1. 读错误信息 → 找到具体文件和行号
2. 读上下文 → 这个函数是做什么的
3. 追溯调用 → 谁调用的，参数是什么
4. 找到根因 → 为什么报错
5. 修复 + 验证 → 测试完整流程
```

### 常见bug模式

| Bug | 根因 | 修复 |
|-----|------|------|
| 录制为空 | `requestMediaProjection()`前没启动Overlay | 先调 `instance?.startMpRecording()` |
| 自动回放失效 | 没重置 `KEY_AUTO_DISABLED` | `saveRecording()` 时重置 |
| 编译坐标错 | 两个Gradle同时跑 | 杀Java再编译 |
| K70连接失败 | ADB命令不可靠 | 用SSH |

---

## 🔧 调试命令

```bash
# 查看源码
cat projects/fall-detection-app/assist/RemoteAssistService.kt

# 搜索关键词
grep -r "startMpRecording" projects/ --include="*.kt"

# K70连接
adb forward tcp:8022 tcp:8022 && ssh localhost -p 8022

# 查看后端日志
cd projects/diedaobao-server && node server.js  # 前台看日志

# 测试API
curl http://localhost:3000/health
```

---

## 🚀 部署流程

### K70后端部署

```bash
# 1. 连接K70
adb forward tcp:8022 tcp:8022
ssh localhost -p 8022

# 2. 上传代码（通过scp或直接复制文件内容）
# 3. 安装依赖
cd ~/diedaobao-server && npm install

# 4. 启动
node server.js &

# 5. 启动内网穿透
./start-tunnel.sh
```

### Android APK编译

```bash
cd projects/fall-detection-app

# 老人端
./gradlew assembleElderRelease
pkill -f gradle  # 立刻杀！

# 子女端
./gradlew assembleChildRelease
pkill -f gradle
```

---

## 📊 代码结构图

```
fall-detection-app/
├── detect/          ← 跌倒检测
│   ├── FallDetector.kt
│   └── DetectionConfig.kt
├── assist/          ← 远程协助核心
│   ├── RemoteAssistService.kt      ← 无障碍服务（自动点击）
│   ├── PermissionRecordManager.kt  ← 录制/回放
│   ├── TouchRecordOverlay.kt       ← 透明悬浮层
│   └── ScreenCaptureService.kt     ← 屏幕捕获
├── ui/              ← UI层
│   ├── MainActivity.kt
│   └── ...
├── cloud/           ← 通讯
│   ├── CloudBaseClient.kt          ← HTTP
│   └── WSClient.kt                 ← WebSocket
└── service/         ← 后台服务
    └── FallDetectionService.kt

diedaobao-server/
├── server.js        ← Express路由 + API
├── ws.js            ← WebSocket推送
└── db.js            ← SQLite
```

---

## 💡 快速参考

```kotlin
// RemoteAssistService单例
RemoteAssistService.instance?.xxx()

// 录制流程
instance?.startMpRecording()  // 先启动
requestMediaProjection()        // 再弹窗

// 权限弹窗检测
TYPE_WINDOW_STATE_CHANGED + com.android.systemui
```

---

*基于跌倒宝v0.3.3开发记忆*