# OPERATIONS.md — 可复用操作模式

> 遇到值得记住的操作经验就写下来，不用凑章节。
> 遇到新情况先来这里找有没有类似模式，没有就新建一个。
> 格式：`## 情境描述 → 做法`，避免写成"标准流程"。

---

## 📱 编译 Android APK

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
adb push <apk-path> /sdcard/Download/app.apk
adb shell am start -a android.intent.action.VIEW \
  -d file:///sdcard/Download/app.apk \
  -t application/vnd.android.package-archive
# → 触发系统安装器，接下来处理弹窗
```

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

### 指纹验证
**无法远程完成**，需用户手动操作。提示用户即可。

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

## 🖥️ K70 SSH 连接

### 建立连接
```bash
adb forward tcp:8022 tcp:8022
ssh -p 8022 -i ~/.ssh/k70_key u0_a280@localhost
```

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

### 重启 server.js
```bash
ssh -p 8022 -i ~/.ssh/k70_key u0_a280@localhost \
  "pkill -f 'node server.js'; cd ~/diedaobao-server && node server.js > ~/server.log 2>&1 &"
sleep 2
ssh ... "curl -s http://localhost:3000/health"
```

### 推送本地修改到 K70
```bash
scp -P 8022 -i ~/.ssh/k70_key <local-file> \
  u0_a280@localhost:<remote-path>
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

### MIUI 同版本更新弹窗模式
和安全审核弹窗不同（同版本 0.8.1→0.8.1），有 checkbox + "继续更新" 按钮，需要两步操作。
