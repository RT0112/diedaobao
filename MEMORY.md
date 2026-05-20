# MEMORY.md - 跌倒宝开发者长时记忆

> 本Agent的知识库，包含从原始备份中提取的全部精华。
> 所有重要规则、项目理解、开发经验都在这里。

---

## 项目概览

**跌倒宝** = 远程协助App + K70本地后端

| 组件 | 技术 | 大小 |
|------|------|------|
| 老人端APK | Kotlin Android | ~38MB |
| 子女端APK | Kotlin Android | ~17MB |
| 后端 | Node.js/Express/SQLite/WebSocket | K70 Termux |
| K70屏幕 | 1440×3200 | 小米机型 |

**K70后端地址**：`http://localhost:3000`

### macOS 编译 JDK 路径
```
JAVA_HOME=/Users/zhou/jdk-17/jdk-17.0.2.jdk/Contents/Home
```
系统PATH里无java，必须设置。编译命令：
```
JAVA_HOME=/Users/zhou/jdk-17/jdk-17.0.2.jdk/Contents/Home ./gradlew assembleDebug --no-daemon --max-workers=1
pkill -f gradle
```
**编译成功后自动推送到K70安装，不要等用户确认。**

---

## 🔥 核心铁律（绝对不能忘）

### 编译规则
**编译完一个端 → 立刻 `pkill -f gradle` → 再编译另一个**

原因：同时跑两个Gradle会导致坐标映射出错。这是导致大量bug的根因。

### JDK路径（macOS x64）
```
JAVA_HOME=/Users/zhou/jdk-17/jdk-17.0.2.jdk/Contents/Home
```
**每次编译必须设置**：`JAVA_HOME=/Users/zhou/jdk-17/jdk-17.0.2.jdk/Contents/Home ./gradlew assembleDebug --no-daemon --max-workers=1`
系统PATH里没有java，不设JAVA_HOME会报错。

### RemoteAssistService单例
```kotlin
// ❌ 错误
RemoteAssistService.getInstance()

// ✅ 正确
RemoteAssistService.instance
```

### ADB input text 空格替代符：`%s`（不是`%20`！`%20`会变成字面文本）

### K70调试
- **ADB input 可以启动 sshd**：`adb shell input text 'sshd'` + `adb shell input keyevent 66`
- **SSH是唯一可靠方案**：先 `adb forward tcp:8022 tcp:8022`，再 `ssh -p 8022 -i ~/.ssh/k70_key u0_a280@localhost`
- **sshd不会开机自启**：每次重启K70后需通过ADB input重新启动
- **后端路径**：`~/diedaobao-server/`（不是 `~/.qclaw/diedaobao-server/`）

### 录制流程关键点
1. 点"好的，开始"前必须先启动Overlay，否则录制为空
2. 保存新录制时必须重置 `KEY_AUTO_DISABLED` 和 `KEY_REPLAY_FAIL_COUNT`
3. 弹窗后要立即调 `showLoading()`，否则Overlay持续拦截导致手机卡

---

## 项目架构

### 数据流

```
跌倒检测流程：
传感器采集 → FallDetector(ML+物理) → FallDetectionService
                                              ↓
                                        CloudBaseClient
                                              ↓
                                    server.js (K70:3000)
                                              ↓
                                    WebSocket推送
                                              ↓
                                    子女端WSClient → 通知

远程协助流程：
子女请求 → 老人端RemoteAssistService
               ↓
          PermissionRecordManager
               ↓
          [首次]→ 录制引导 → TouchRecordOverlay拦截坐标
               ↓
          [回放]→ tryAutoHandle() → AccessibilityService点击
               ↓
          ScreenCaptureService → 屏幕帧 → K70 → WS推送子女
```

### 关键文件

| 路径 | 说明 |
|------|------|
| `projects/fall-detection-app/detect/FallDetector.kt` | 跌倒检测核心 |
| `projects/fall-detection-app/assist/RemoteAssistService.kt` | 无障碍服务 |
| `projects/fall-detection-app/assist/PermissionRecordManager.kt` | 录制/回放 (v17) |
| `projects/diedaobao-server/server.js` | Express API |

---

## 权限自动点击演进

| 版本 | 方案 | 结果 | 根因 |
|------|------|------|------|
| v1~v4 | TouchRecordOverlay悬浮窗 | ❌ 失败 | z-order被系统弹窗遮挡 |
| v5 | AccessibilityService节点树 | ✅ 成功 | 绕过z-order |
| v17 | 极简重写（493行→200行） | ✅ 成功 | 修复三大问题 |

### v17核心修复（来自2026-05-13记忆）
1. 只检查 `com.android.systemui` 窗口，避免误报
2. `dispatchGestureClick` 改为纯异步
3. 保存录制时重置禁用标志

### HyperOS两步弹窗结构
1. 第一步：权限说明弹窗（"此应用将捕获您屏幕上的所有内容..."）
2. 第二步：实际权限弹窗（允许/拒绝）
→ 录制时两个都要拦截坐标

---

## API路由（K70后端）

| 端点 | 方法 | 说明 |
|------|------|------|
| `/health` | GET | 健康检查 |
| `/user-register` | POST | 注册用户 |
| `/fall-report` | POST | 跌倒上报 + WS推送 |
| `/bind-family` | POST | 家庭绑定 |
| `/location-sync` | POST | 位置同步 |
| `/remote-assist` | POST | 远程协助帧上传 |
| `/fall-history` | POST | 历史查询 |
| `/get-status` | POST | 状态查询 |
| `/pull-location` | POST | 拉取位置 |
| `/request-elder-location` | POST | 请求老人位置 |

### WebSocket消息类型
- `fall_event` - 跌倒事件（推送给子女）
- `location_update` - 位置更新
- `remote_frame` - 远程协助帧
- `assist_status` - 协助状态

### Kotlin/Android类型陷阱
```kotlin
// ❌ 错误 — JSONObject.put(String, Float)不存在
json.put("impactG", impactG)  // NoSuchMethodError!

// ✅ 正确 — 必须显式转Double
json.put("impactG", impactG.toDouble())
json.put("mlScore", mlScore.toDouble())
json.put("physicalScore", physicalScore.toDouble())
```
原因：Android的JSONObject是Java类，只有put(String, Double)签名，没有put(String, Float)。Kotlin的Float不会自动装箱为Double。

### 代码改动后必须git commit
用户多次指出改代码不提交的问题。每次代码修改后必须commit，不要只存记忆文件。

---

## 已知问题/待办

| 问题 | 状态 | 说明 |
|------|------|------|
| v17真机测试 | ⏳ 待测试 | 2026-05-13编译成功，待K70实机验证 |
| K70设备连接 | ⏳ 待确认 | 需验证WS连接 |
| 双端APK联调 | ⏳ 待测试 | 五项联调修复后的完整流程 |

### MIUI APK安装流程（遇到直接用，不要摸索）
1. ICP弹窗 → 点"继续安装"
2. MIUI安全检测弹窗 → **先勾选checkbox** → 点"继续安装"
3. 指纹验证 → 通知用户手动按（无法远程完成）

### Cloudflared在K70上DNS失败
Go静态编译走`[::1]:53`，Termux沙箱内无法访问。可用ngrok（K70已有ngrok2.zip）或升级cloudflared。

---
- MIUI APK安装流程：adb install -r 可直接绕过所有弹窗（推荐），推送APK到K70后直接adb install即可
- K70 Termux自动化：必须装Termux:API才能am startservice执行命令，SSH需Termux内手动运行sshd（不稳定）

## 用户特征

1. **全栈独立交付**：从像素级渲染到云端部署全部自己搞定
2. **极强韧性**：v1~v4四轮失败仍持续迭代，不妥协
3. **重视根因**：遇到bug先分析再修复，不试错
4. **开发习惯**：
   - 老人端操作极简优先
   - 画质240p→360p接受更高带宽
   - 重视云函数field projection优化

---

## 参考资源

- **精华记忆**：`memory/scene_blocks/远程协助App开发-跌倒宝.md`
- **每日日志**：`memory/daily/`（按日期查）
- **完整对话**：`memory/lcm/file_*.txt`（深度上下文）
- **项目文档**：`docs/SPEC.md`
- **算法文档**：`docs/ALGORITHM_v0.27.0_COMPLETE.md`

---

## 开发原则

1. **先读代码，再给建议** - 不要猜测，先理解完整数据流
2. **根因分析 > 试错修复** - 找到根因再动手
3. **完整流程验证** - 修复后测试完整路径
4. **重要规则写进记忆** - 学到的教训要记录

---

*最后更新：2026-05-16*

## 用户身份与偏好

- 不要命令行命令，要可点击使用的桌面App
- 方案用1、2、3、4方式命名快捷方式
- 测试时不要搞崩当前运行的QClaw进程
- APK编译好后自动发送到微信文件传输助手（用 wechat_mac_send.sh，直接传APK路径，不复制桌面）
- 微信发送用Mac客户端（AppleScript），不用网页版（太不稳定）
- K70远程操作：必须先装Termux:API，之后才能可靠重启服务端

## 技术规范偏好

- 每次编译前必须递增versionCode，否则Android不会真正更新
- MIUI安装弹窗：每个按钮只点1次，成功后位置变成取消更新，多点必误触
- network_security_config.xml不支持CIDR网段，必须写具体IP
- Kotlin thread {}内必须try-catch，未捕获异常会崩溃整个进程
- JavaScript中 || 会把0和空字符串视为falsy，数值字段要用 ?? 避免误转换
- MIUI抑制新安装App的浮动通知，setFullScreenIntent可绑过抑制（类似来电）
- ADB input text空格用%s不是%20，input touchscreen tap比keyevent更可靠
- 双端App都是外网使用，绝不能改老人端为localhost
- 不要改安装包名字，编译完只改版本号就行
- K70是独立服务器，不依赖Mac做任何中转（Mac只用于编译和传文件）
- 微信发送大文件timeout≠失败，不要因超时杀进程，要验证实际结果

## 当前项目与关注

- 跌倒宝项目当前阻塞：ML推理从未触发（灵敏度阈值问题），通知链路已修复
- 老人端versionCode=136，子女端versionCode=18

## 经验与决策

- MIUI APK安装：adb install -r 可绕过MIUI弹窗（推荐优先用），推送APK到K70后直接adb install即可
- MIUI首次安装需3步（ICP→安全checkbox→指纹），更新覆盖只需2步（ICP→安全checkbox）
- 请求去重ID必须用请求本身的唯一标识（requestTime），绝不能用当前时间戳
- ScreenCaptureService生命周期应独立于Activity，Activity关闭不等于用户想结束协助
