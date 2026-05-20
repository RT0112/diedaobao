# OPERATIONS.md — 可复用操作模式

> 遇到值得记住的操作经验就写下来，不用凑章节。
> 遇到新情况先来这里找有没有类似模式，没有就新建一个。
> 格式：`## 情境描述 → 做法`，避免写成"标准流程"。

---

## 📱 编译 Android APK

### 编译后发送 APK 到微信文件传输助手（Mac客户端版）

**✅ 稳定方案：微信Mac客户端 + AppleScript**
```bash
# 直接发送APK（支持任意路径）
./scripts/wechat_mac_send.sh ~/Desktop/跌倒宝-老人端-v137.apk
./scripts/wechat_mac_send.sh ~/Desktop/亲情守护-子女端-v20.apk

# 也可以直接发送编译产物（无需先复制到桌面）
./scripts/wechat_mac_send.sh projects/fall-detection-app/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
./scripts/wechat_mac_send.sh projects/family-guardian-app/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
```

**原理**：
1. 激活微信Mac客户端
2. 把APK文件放入剪贴板（Finder clipboard）
3. Command+V 粘贴到聊天窗口
4. 回车发送

**前提条件**：
- 微信Mac客户端已运行
- 文件传输助手聊天窗口已打开（或最近一次聊天窗口可见）
- 系统偏好设置 → 隐私与安全性 → 辅助功能 → 已允许终端/System Events控制电脑

**⚠️ 铁律：每次编译成功后，必须发送 APK 到微信文件传输助手备份！**
**⚠️ 不再复制到桌面，直接发送编译产物路径即可。**

### macOS 编译跌倒宝/子女端
```bash
export JAVA_HOME="/Users/zhou/jdk-17/jdk-17.0.2.jdk/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
cd ~/.qclaw/workspace-x5kuz49xple53hhg/projects/fall-detection-app  # 或 family-guardian-app
./gradlew assembleDebug --no-daemon --max-workers=1
pkill -f gradle   # 铁律：编译完立刻杀
```

**⚠️ Gradle task 名**：`assembleDebug`，不是 `assembleElderDebug` / `assembleChildDebug`（单模块项目无flavor）。

**⚠️ JDK 路径**：`/Users/zhou/jdk-17/jdk-17.0.2.jdk/Contents/Home`（不是 `~/jdk-17.0.2.jdk`，少一层目录）。

### 推送 APK 到 K70 并安装
```bash
# 方案A：adb install -r（推荐，绕过MIUI ICP备案弹窗）
adb install -r <apk-path>

# 方案B：am start（可能遇到MIUI ICP备案包名输入验证，不推荐）
adb push <apk-path> /sdcard/Download/app.apk
adb shell am start -a android.intent.action.VIEW \
  -d file:///sdcard/Download/app.apk \
  -t application/vnd.android.package-archive
```

**⚠️ 2026-05-19 发现**：MIUI ICP备案弹窗新增"请输入此应用的包名"验证步骤，`am start`触发安装器会卡住。用 `adb install -r` 可直接绕过，无需处理任何弹窗。

---

## 🔲 MIUI 安装弹窗处理

### ICP 备案弹窗
出现在安装境外应用时。点"继续安装"：
```
bounds: [131,2885][698,3068] → tap 415, 2976
```

### MIUI 安全审核弹窗（同版本更新）
出现在已有应用重新安装时。**两步**：先勾选复选框，再点"继续更新"：
```
复选框 bounds: [102,2321][1338,2416] → tap 720, 2368
继续更新 bounds: [102,2687][1338,2870] → tap 720, 2778
```

### ⚠️ MIUI安装铁律：每个按钮只点一次！
**第一次点击成功后，按钮位置会变**（比如"继续更新"变成"取消更新"在相同位置），第二次点击会点到"取消更新"导致安装失败！

正确流程：
1. ICP弹窗 → `tap 415, 2976`（只点1次）→ 等2秒
2. 安全审核弹窗 → `tap 720, 2368`（勾选，只点1次）→ 等1秒 → `tap 720, 2778`（继续更新，只点1次）→ 等3秒
3. 验证：`adb shell pm dump <pkg> | grep versionCode`

**debug版APK更新不需要指纹验证！**

### 验证安装成功
```bash
adb shell pm dump com.falldetector.diedaobao | grep versionName  # 老人端
adb shell pm dump com.familyguardian.app | grep versionName       # 子女端
```

---

## 🐛 查 Bug / 看日志

### Android 日志（按包名过滤）
```bash
adb logcat | grep -i "fall\|cloud\|assist"   # 老人端
adb logcat | grep -i "family\|guardian\|cloud" # 子女端
adb logcat -d *:E | tail -50                   # 只看 Error
```

### 后端日志（SSH）
```bash
ssh -p 8022 -i ~/.ssh/k70_key u0_a280@localhost \
  "tail -50 ~/server.log"
```

### 实时 WS 连接状态
```bash
curl http://localhost:3000/health
# → {"ws":{"total":2,"elders":1,"guardians":1,...}}
```

---

## 🔍 真机 UI 调试（读屏幕）

### 截图 + 读 UI 层级
```bash
adb shell screencap -p /sdcard/ui.png
adb pull /sdcard/ui.png /tmp/ui.png
adb shell uiautomator dump /sdcard/ui.xml
adb shell cat /sdcard/ui.xml | grep -o 'text="[^"]*"' | grep -v 'text=""'
```

### 从 XML 提取坐标
```bash
adb shell "cat /sdcard/ui.xml" | grep -E "text.*关键词" | head -5
# 找 bounds="[x1,y1][x2,y2]"，center = ((x1+x2)/2, (y1+y2)/2)
```

### 触发点击
```bash
adb shell input touchscreen tap <x> <y>
```

---

## 🖥️ K70 远程操作

### K70 服务端重启（通过 SSH）

**⚠️ 前提条件：** sshd 必须在运行（见上方「K70 SSH 连接」章节）

**一行命令重启：**
```bash
export PATH="$PATH:$HOME/Library/Android/sdk/platform-tools"
adb forward tcp:8022 tcp:8022
ssh -o StrictHostKeyChecking=no -p 8022 -i ~/.ssh/k70_key u0_a280@localhost \
  "pkill -f 'node server' 2>/dev/null; sleep 2; cd ~/diedaobao-server && nohup node server.js > server.log 2>&1 &"
```

**验证：**
```bash
ssh -o StrictHostKeyChecking=no -p 8022 -i ~/.ssh/k70_key u0_a280@localhost "curl -s http://127.0.0.1:3000/health"
# → {"status":"ok","ws":{"total":...}}
```

**如果 sshd 没启动，先启动它：**
```bash
adb shell am start -n com.termux/.app.TermuxActivity
adb shell input text 'sshd' && sleep 1 && adb shell input keyevent 66
sleep 3
# 然后再执行重启命令
```

### 建立 SSH 连接（sshd 需通过 ADB input 启动）
```bash
# 1. 启动 Termux 前台
adb shell am start -n com.termux/.app.TermuxActivity
# 2. 输入 sshd + 回车
adb shell input text 'sshd' && sleep 1 && adb shell input keyevent 66
# 3. 端口转发 + 连接
adb forward tcp:8022 tcp:8022
ssh -o StrictHostKeyChecking=no -p 8022 -i ~/.ssh/k70_key u0_a280@localhost
```

**⚠️ 注意**：Termux 被 `am force-stop` 或应用管理器杀掉后，sshd 会断。
用 ADB input 重新输入 `sshd` 即可恢复。

### 查看 K70 网络信息
```bash
# WLAN IP
adb shell ip addr show wlan0 | grep inet
# 当前 192.168.4.19

# 后端健康检查
ssh -p 8022 ... "curl -s http://localhost:3000/health"
```

---

## 🌐 网络 URL 迁移模式

**情境**：K70 后端 IP/域名变了，需要改所有端点的 BASE_URL。

---

## 🔐 K70 内网穿透（serveo.net SSH 反向隧道）

### 方案选择
| 工具 | 状态 | 原因 |
|------|------|------|
| ngrok / cloudflared | ❌ 不可用 | Go 静态二进制在 Termux 沙箱内 DNS 解析失败（`[::1]:53` 不可访问） |
| serveo.net (SSH 隧道) | ✅ 可用 | 基于 SSH，不受 Go DNS 问题影响 |
| VPS + frp | ✅ 推荐（长期） | 稳定、可自定义域名、约 ¥10-30/月 |

### 快速启动隧道
```bash
export PATH="$PATH:$HOME/Library/Android/sdk/platform-tools"

# 1. 确保 K70 SSH 可用（sshd 运行中）
adb forward tcp:8022 tcp:8022
ssh -o StrictHostKeyChecking=no -p 8022 -i ~/.ssh/k70_key u0_a280@localhost "ps aux | grep -v grep | grep sshd" || {
  echo "sshd not running, starting..."
  adb shell am start -n com.termux/.app.TermuxActivity
  adb shell input text 'sshd' && sleep 1 && adb shell input keyevent 66
  sleep 3
}

# 2. 启动隧道（一次性）
ssh -o StrictHostKeyChecking=no -p 8022 -i ~/.ssh/k70_key u0_a280@localhost \
  "ssh -o StrictHostKeyChecking=no -o ServerAliveInterval=60 -R 80:localhost:3000 serveo.net 2>&1 | head -5"
# 输出示例：Forwarding HTTP traffic from https://xxxxx.serveousercontent.com

# 3. 启动隧道（后台 + 自动重连）
ssh -o StrictHostKeyChecking=no -p 8022 -i ~/.ssh/k70_key u0_a280@localhost \
  "nohup ~/start_tunnel.sh > /dev/null 2>&1 &"

# 4. 获取当前隧道 URL
curl -s https://36a116cfc0e2e056-120-84-10-228.serveousercontent.com/health
# 或 SSH 进 K70 查看：cat ~/tunnel_url.txt
```

### 自动重连脚本（已在 K70 上）
**路径**：`~/start_tunnel.sh`
```bash
#!/bin/bash
while true; do
  echo "[$(date)] Starting tunnel..." >> ~/tunnel.log
  ssh -o StrictHostKeyChecking=no -o ServerAliveInterval=60 \
    -o ExitOnForwardFailure=yes \
    -R 80:localhost:3000 serveo.net >> ~/tunnel.log 2>&1
  echo "[$(date)] Tunnel disconnected, reconnecting in 5s..." >> ~/tunnel.log
  sleep 5
done
```

### 验证隧道正常工作
```bash
# 从 Mac 测试（替换实际 URL）
TUNNEL_URL="https://36a116cfc0e2e056-120-84-10-228.serveousercontent.com"
curl -s "${TUNNEL_URL}/health"
# 应返回：{"status":"ok","time":...}

# 查看 K70 上隧道进程
adb forward tcp:8022 tcp:8022
ssh -p 8022 -i ~/.ssh/k70_key u0_a280@localhost "ps aux | grep serveo"
```

### 更新子女端 BASE_URL
编译前修改 `projects/family-guardian-app/app/src/main/java/com/familyguardian/app/network/CloudBaseClient.kt`：
```kotlin
// 修改前
private const val BASE_URL = "http://192.168.4.19:3000"

// 修改后
private const val BASE_URL = "https://36a116cfc0e2e056-120-84-10-228.serveousercontent.com"
```

### ⚠️ 注意事项
1. **serveo.net 免费服务**：URL 每次连接可能变化（除非注册账号）
2. **SSH 密钥信任**：首次连接 serveo.net 需手动确认（已自动处理）
3. **隧道稳定性**：网络变化可能导致隧道断开，脚本会自动重连
4. **速度限制**：免费服务有速率限制，生产环境建议用 VPS + frp

### 当前隧道信息（2026-05-20）
- **URL**：`https://36a116cfc0e2e056-120-84-10-228.serveousercontent.com`
- **状态**：✅ 运行中
- **K70 进程**：`start_tunnel.sh`（自动重连）
- **日志**：`~/tunnel.log`

---

## 📡 网络 URL 迁移模式（旧方案，已废弃）

**情境**：K70 后端 IP/域名变了，需要改所有端点的 BASE_URL。

### 操作步骤
1. 确认新 URL 可达：`curl http://<new-ip>:3000/health`
2. 在项目里搜所有旧 URL：`grep -rn "旧域名\|旧IP" projects/ --include="*.kt"`
3. 逐个替换（注意 HTTPS → HTTP 区别）
4. 检查 `network_security_config.xml` 是否包含新 IP（明文许可）
5. 编译、推送、验证 WS 连接数 ↑

### 跌倒宝当前配置（2026-05-17）
- K70 IP：`192.168.4.19`
- 后端端口：3000
- 老人端 BASE_URL：`http://localhost:3000`（跑在 K70 本地）
- 子女端 BASE_URL：`http://192.168.4.19:3000`（同一局域网）
- WS_URL 同理

---

## 🔑 K70 SSH 连接（核心操作，2026-05-19 验证成功）

### 前置条件
- K70 通过 USB 连接 Mac
- Termux 已安装（`com.termux`）
- Termux 内已安装 openssh：`pkg install openssh`
- Mac 上有 SSH 密钥：`~/.ssh/k70_key`

### Step 1：启动 sshd（ADB input 方式）
```bash
export PATH="$PATH:$HOME/Library/Android/sdk/platform-tools"

# 确保 Termux 在前台
adb shell am start -n com.termux/.app.TermuxActivity

# 输入 sshd 并回车
adb shell input text 'sshd'
sleep 1
adb shell input keyevent 66

# 等待 sshd 启动
sleep 3

# 验证 sshd 进程
adb shell "ps -A | grep sshd"
# 应该看到 sshd 进程
```

### Step 2：建立 SSH 连接
```bash
# 设置端口转发
adb forward tcp:8022 tcp:8022

# 测试连接
ssh -o StrictHostKeyChecking=no -p 8022 -i ~/.ssh/k70_key u0_a280@localhost "echo SSH连接成功 && whoami"
# 应该输出：SSH连接成功 + u0_a280
```

### Step 3：常用远程操作
```bash
# 定义快捷函数（加到 shell 里用）
K70SSH() {
  ssh -o StrictHostKeyChecking=no -p 8022 -i ~/.ssh/k70_key u0_a280@localhost "$@"
}

# 查看后端健康状态
K70SSH "curl -s http://127.0.0.1:3000/health"

# 查看后端日志（最后20行）
K70SSH "tail -20 ~/diedaobao-server/server.log"

# 查看磁盘空间
K70SSH "df -h /data"

# 查看 Node 进程
K70SSH "pgrep -af node"

# 重启后端
K70SSH "pkill -f 'node server' 2>/dev/null; sleep 2; cd ~/diedaobao-server && nohup node server.js > server.log 2>&1 &"

# 推送文件到 K70（先推到 /sdcard/Download，再通过 SSH 移动）
adb push <local-file> /sdcard/Download/<name>
K70SSH "cp /sdcard/Download/<name> ~/diedaobao-server/<name>"

# 查看 SSH 密钥权限
K70SSH "ls -la ~/.ssh/"
```

### ⚠️ 注意事项
1. **sshd 不会开机自启**：每次 K70 重启后需要重新执行 Step 1
2. **input text 空格用 `%s`**：不是 `%20`
3. **input text 过长会崩溃**：报 `FAILED_TRANSACTION` 时等几分钟恢复
4. **SSH 端口**：Termux sshd 默认监听 8022（非 root 无法绑定 22）
5. **如果 sshd 已运行**：跳过 Step 1，直接 Step 2

### 设置 sshd 开机自启（一次性配置）
```bash
K70SSH "mkdir -p ~/.termux/boot"
K70SSH "echo '#!/bin/bash' > ~/.termux/boot/start_sshd.sh"
K70SSH "echo 'sshd' >> ~/.termux/boot/start_sshd.sh"
K70SSH "chmod +x ~/.termux/boot/start_sshd.sh"
# 还需要在 K70 系统设置中允许 Termux 自启动
# 设置 → 应用 → Termux → 电池优化 → 不限制
```

---

## 🔌 ADB 命令技巧

### `input text` 空格问题
- **正确**：`%s`（代替空格）
- **错误**：`%20`（变成字面文本）

### `input text` 过长崩溃
报错 `FAILED_TRANSACTION`。解决：等 5 分钟恢复，或 `adb kill-server`。

### `input touchscreen tap` 更可靠
对于点击按钮/复选框，用 `input touchscreen tap x y` 比 `input text` + keyevent 更稳。

### 查看 App 包名/Activity
```bash
adb shell dumpsys window | grep mCurrentFocus
adb shell "dumpsys package <pkg> | grep Activity"
```

### 启动 App
```bash
# 老人端
adb shell am start -n com.falldetector.diedaobao/.ui.MainActivity
# 子女端
adb shell am start -n com.familyguardian.app/.MainActivity
```

---

## ⚙️ 后端部署

### 重启 server.js（需要 Termux:API，见上方）
```bash
adb forward tcp:8022 tcp:8022
ssh -p 8022 -i ~/.ssh/k70_key u0_a280@localhost \
  "pkill -f 'node server' 2>/dev/null; sleep 2; cd ~/.qclaw/diedaobao-server && nohup node server.js > server.log 2>&1 &"
# 验证
curl -s http://localhost:3000/health
```

### 推送本地修改到 K70
```bash
# 先推送到 /sdcard/Download（shell可写）
adb push <local-file> /sdcard/Download/<name>
# 然后 SSH 进 Termux 移动到目标位置
adb forward tcp:8022 tcp:8022
ssh -p 8022 -i ~/.ssh/k70_key u0_a280@localhost \
  "cp /sdcard/Download/<name> ~/.qclaw/diedaobao-server/<name>"
```

---

## 🧠 Kotlin 协程注意

- `cont.resume(T)` 在 Kotlin 1.9.22 有歧义 → 用 `cont.resumeWith(Result.success(...))`
- `lifecycleScope.launch` 需确保 Activity/Fragment 已 attached
- `GlobalScope` 慎用

---

## 📡 WS/WebSocket 调试

### 测试 WS 握手
```bash
curl -i -N \
  --header "Upgrade: websocket" \
  --header "Connection: Upgrade" \
  --header "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==" \
  --header "Sec-WebSocket-Version: 13" \
  http://localhost:3000/ws
```

### WS 消息类型
- `fall_event` — 跌倒事件
- `location_update` — 位置更新
- `assist_request` — 远程协助请求
- `assist_response` — 协助响应

---

## 📋 本次新增模式（2026-05-17）

### URL 迁移模式
发现：所有 Kotlin 文件里的 BASE_URL/WS_URL/SIGNAL_URL 都要一起改，缺一不可。grep 要加 `--include="*.kt"` 并排除 build 目录。

### MIUI 同版本更新弹窗模式
和安全审核弹窗不同（同版本 0.8.1→0.8.1），有 checkbox + "继续更新" 按钮，需要两步操作。

---

## 🔍 远程协助调试流程

**情境**：老人端点击"允许"无响应，或协助请求未触发UI。

### 步骤1：检查权限状态
```bash
# 无障碍服务是否启用
export PATH="$PATH:$HOME/Library/Android/sdk/platform-tools"
export PATH="$PATH:$HOME/Library/Android/sdk/platform-tools"
```

### 步骤2：检查 WS 连接状态
```bash
# 查看 WS 连接数
curl -s http://localhost:3000/health
# 应该看到 "elders":1, "guardians":1
```

### 步骤3：审查代码流程
关键文件：
1. `RemoteAssistManager.kt` - 管理协助请求状态
2. `WSClient.kt` - 接收/发送 WS 消息
3. `HomeFragment.kt` - 处理协助请求，发通知
4. `FallDetectionService.kt` - 可能在运行中，负责 startActivity
5. `RemoteAssistActivity.kt` - 显示协助 UI

### 步骤4：日志过滤
```bash
# 清除日志
export PATH="$PATH:$HOME/Library/Android/sdk/platform-tools"
adb logcat -c

# 过滤相关日志
adb logcat | grep -iE "RemoteAssist|assist_request|HomeFragment|FallDetection"
```

### 步骤5：模拟请求测试
```bash
# 通过 HTTP 模拟子女端发起协助请求
curl -s -X POST http://192.168.4.19:3000/remote-assist \
  -H "Content-Type: application/json" \
  -d '{"action":"request","elderId":"<老人ID>","guardianId":"<子女ID>","guardianName":"测试子女"}'

# 观察老人端日志，看是否收到请求
```

### 步骤6：常见问题定位
- **问题1**：`HomeFragment` 发了通知，但 `RemoteAssistActivity` 没启动 → 检查 `FallDetectionService` 是否在运行，是否调用了 `startActivity`
- **问题2**：`RemoteAssistActivity` 启动了，但点击"允许"无反应 → 检查 `onAllowClicked()` 方法，检查无障碍权限和悬浮窗权限
- **问题3**：WS 消息没收到 → 检查 `WSClient.kt` 中的 `WS_URL` 是否正确，WS 连接是否建立

### 步骤7：修复后验证
1. 重新编译并安装 APK
2. 重复步骤5的测试
3. 确认老人端收到请求并弹出 UI
4. 确认点击"允许"后，后端收到 `action=respond` 请求

### 测试步骤（2026-05-17 16:05 添加）
1. **编译安装**：编译老人端 APK 并安装到 K70
2. **发送请求**：从子女端发送远程协助请求
3. **检查 UI**：老人端是否显示请求对话框（container_request 可见）
4. **点击允许**：点击"允许"按钮，观察是否调用 `onAllowClicked()`
5. **检查日志**：`adb logcat | grep RemoteAssistActivity` 查看 `onAllowClicked` 是否执行
6. **检查权限流程**：`onAllowClicked()` 应检查无障碍权限 → 调用 `respondToRequest()` → 启动 `startPermissionFlow()`
7. **验证后端**：K70 后端是否收到 `action=respond` 请求，并返回 `success=true`
8. **测试重复请求**：在30秒内再次发送请求，应显示"重复请求，显示现有状态"，不重复初始化

### 修复内容（2026-05-17 16:05）
- **问题**：`handleNewRequest()` 返回 `false`（重复请求），导致 `initViews()` 未被调用，按钮无响应
- **修复1**：`onCreate()` 中即使 `isNewRequest=false`，也调用 `initViews()` 并根据状态显示 UI
- **修复2**：`onNewIntent()` 中同样处理，确保重复请求时 UI 已初始化
- **修复3**：`auto_allow_assist=true` 时，确保 `containerRequest.visibility = View.VISIBLE`

---

## 📋 本次新增（2026-05-17 16:05）

### 远程协助调试步骤
已添加到上方"🔍 远程协助调试流程"章节。

### Agent 文件架构反思
当前架构：
- `SOUL.md`：角色指令 + 记忆触发规则
- `OPERATIONS.md`：具体操作步骤（编译、安装、调试等）
- `MEMORY.md`：长期记忆（项目架构、关键决策）
- `memory/YYYY-MM-DD.md`：每日日志

**缺陷**：
1. "操作成功→写入OPERATIONS.md"这条铁律，没有涵盖"调试步骤"这种非成功操作。
2. 调试过程中发现的问题和解决方案，可能没有被记录到OPERATIONS.md。

**改进建议**：
在 `SOUL.md` 中增加一条："每次调试会话结束后，将关键调试步骤记录到OPERATIONS.md对应章节"。

---

### 微信APK发送（编译后必做）

**✅ 稳定方案：微信Mac客户端 + AppleScript**

```bash
# 一键发送
./scripts/wechat_mac_send.sh ~/Desktop/跌倒宝-老人端-v136.apk
./scripts/wechat_mac_send.sh ~/Desktop/亲情守护-子女端-v18.apk
```

**前置条件**：微信Mac客户端已运行，文件传输助手窗口可见

**原理**：AppleScript → 激活微信 → Finder将APK放入剪贴板 → Cmd+V粘贴 → 回车发送

**⚠️ 铁律：每次编译成功后，必须发送 APK 到微信文件传输助手备份！**

**完整流程**：编译成功 → 安装K70 → 直接发微信（不发桌面）

---

### MIUI 同版本更新弹窗模式
和安全审核弹窗不同（同版本 0.8.1→0.8.1），有 checkbox + "继续更新" 按钮，需要两步操作。

---

## 🐛 子女端意见反馈闪退修复（2026-05-20）

### 问题现象
子女端 APP 点击"意见反馈"按钮后立即闪退。

### 根因排查
1. **Theme 冲突**：`FeedbackActivity` 使用 `Theme.MaterialComponents.DayNight.DarkActionBar`，但内部调用 `setSupportActionBar()`，导致 ActionBar 冲突
2. **`android:exported` 缺失**：Android 12+ 要求显式声明 `exported`（虽非直接闪退原因，但编译警告）
3. **`getPackageInfo()` flag 问题**：`PackageManager.GET_META_DATA` 在 Android 13+ 需要正确的 flag

### 修复步骤
1. **`themes.xml` 新增 `Theme.FamilyGuardian.NoActionBar`**：
   ```xml
   <style name="Theme.FamilyGuardian.NoActionBar" parent="Theme.MaterialComponents.DayNight.NoActionBar">
       <item name="colorPrimary">@color/primary</item>
       <item name="colorPrimaryDark">@color/primary_dark</item>
       <item name="colorAccent">@color/accent</item>
   </style>
   ```

2. **`AndroidManifest.xml` 为 `FeedbackActivity` 添加 `android:exported="false"`**：
   ```xml
   <activity
       android:name=".feedback.FeedbackActivity"
       android:exported="false"
       android:label="意见反馈"
       android:theme="@style/Theme.FamilyGuardian.NoActionBar" />
   ```

3. **`FeedbackActivity.kt` 修复 `getPackageInfo()` flag**：
   ```kotlin
   val appVersion = try {
       @Suppress("DEPRECATION")
       packageManager.getPackageInfo(packageName, PackageManager.GET_META_DATA).versionName ?: ""
   } catch (_: Exception) { "" }
   ```

4. **编译、安装到 K70**：
   ```bash
   cd ~/.qclaw/workspace-x5kuz49xple53hhg/projects/family-guardian-app
   JAVA_HOME=/Users/zhou/jdk-17/jdk-17.0.2.jdk/Contents/Home ./gradlew assembleDebug --no-daemon --max-workers=1
   pkill -f gradle
   adb -s 192.168.4.19:5555 install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
   ```

5. **验证**：通过 ADB 启动 `FeedbackActivity`（`exported` 临时改为 `true`），用 `dumpsys activity activities` 确认 `topResumedActivity` 为 `FeedbackActivity`，无崩溃日志。

### 关键经验
- **Theme 冲突是常见闪退原因**：Activity 使用 `DarkActionBar` 主题但调用 `setSupportActionBar()`，会导致 `IllegalStateException`
- **`exported` 在 Android 12+ 必须声明**：虽非直接闪退原因，但会导致编译警告和潜在安全问题
- **验证时用 `dumpsys activity activities`**：比 logcat 更可靠，能直接看到当前前台 Activity

### 固化到文档
- ✅ `OPERATIONS.md` 新增本章节
- ✅ `themes.xml` 已包含 `Theme.FamilyGuardian.NoActionBar`
- ✅ `AndroidManifest.xml` 已包含 `android:exported="false"`
- ✅ `FeedbackActivity.kt` 已修复 `getPackageInfo()` flag

## 📝 子女端意见反馈功能实现 (v0.31.0)

### 目标
为子女端APP添加意见反馈功能（Bug反馈/改进建议/其他），数据提交到K70后端存储。

### 完整步骤

#### 1. 后端：创建 `feedback` 表
K70上sqlite3 CLI不可用，通过Node.js操作：
```javascript
// 在K70上创建脚本 create_feedback_table_v2.js
const { getDb } = require('/data/data/com.termux/files/home/diedaobao-server/db');
const db = getDb();
db.exec(`
  CREATE TABLE IF NOT EXISTS feedback (
    id TEXT PRIMARY KEY,
    type TEXT NOT NULL,
    contact TEXT,
    content TEXT NOT NULL,
    device_model TEXT,
    android_version TEXT,
    app_version TEXT,
    platform TEXT,
    timestamp INTEGER NOT NULL,
    date TEXT
  )
`);
```
通过SSH执行：`ssh -p 8022 -i ~/.ssh/k70_key u0_a280@localhost "cd ~/diedaobao-server && node /path/to/create_feedback_table_v2.js"`

#### 2. 后端：添加 `/feedback` 路由
编辑 `server.js`，在404 fallback之前添加：
```javascript
// ========== feedback ==========
app.post('/feedback', (req, res) => {
  try {
    const { type, contact, content, device_model, android_version, app_version, platform } = req.body
    if (!type || !content) return res.json({ code: 400, message: "缺少 type 或 content" })
    const db = getDb()
    const id = genId()
    db.prepare('INSERT INTO feedback (id, type, contact, content, device_model, android_version, app_version, platform, timestamp, date) VALUES (?,?,?,?,?,?,?,?,?,?)')
      .run(id, String(type).substring(0,100), contact ? String(contact).substring(0,200) : null, String(content).substring(0,10000), device_model || null, android_version || null, app_version || null, platform || "unknown", Date.now(), new Date().toISOString().split('T')[0])
    return res.json({ code: 200, feedbackId: id, message: "反馈已收到" })
  } catch (err) {
    return res.status(500).json({ code: 500, message: err.message })
  }
})
```

#### 3. 子女端：创建 `FeedbackActivity.kt`
从老人端复制并修改：
- package名改为 `com.familyguardian.app.feedback`
- 跌倒记录数据源改为 `CloudBaseClient.getFallEvents()`

#### 4. 子女端：创建 `FeedbackSender.kt`
```kotlin
object FeedbackSender {
    private const val BASE_URL = CloudBaseClient.BASE_URL  // 复用后端URL
    private const val API_URL = "$BASE_URL/feedback"
    
    fun submitFeedback(context: Context, type: String, contact: String?, content: String, callback: (Boolean, String?) -> Unit) {
        // 使用OkHttp POST JSON到 API_URL
    }
}
```

#### 5. 子女端：复制布局 + 注册Manifest
```bash
cp fall-detection-app/app/src/main/res/layout/activity_feedback.xml \
   family-guardian-app/app/src/main/res/layout/
```
Manifest添加：
```xml
<activity android:name=".feedback.FeedbackActivity"
    android:exported="false"
    android:label="意见反馈"
    android:theme="@style/Theme.FamilyGuardian.NoActionBar" />
```

#### 6. 编译安装
```bash
cd projects/family-guardian-app
JAVA_HOME=/Users/zhou/jdk-17/jdk-17.0.2.jdk/Contents/Home ./gradlew assembleDebug --no-daemon --max-workers=1
pkill -f gradle
adb -s a0c2910e install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
```

### 关键经验
1. **K70数据库操作必须用Node.js**：sqlite3 CLI不可用，better-sqlite3模块未安装，只能通过server.js的Node.js环境操作
2. **SQL字符串字面量用单引号**：SQLite中双引号是列名，单引号才是字符串（`unixepoch`修饰符错误）
3. **后端路由404先grep确认**：不要假设路由存在，先 `grep -n 'feedback' server.js` 确认
4. **URL配置分测试/生产**：测试用本地IP `http://192.168.4.19:3000`，生产需改隧道URL

### 验证方法
```bash
# 测试POST
curl -X POST http://192.168.4.19:3000/feedback \
  -H "Content-Type: application/json" \
  -d '{"type":"suggestion","content":"测试"}'

# 测试查询
curl http://192.168.4.19:3000/feedback
```
