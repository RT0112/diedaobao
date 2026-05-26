# 任务总结：恢复三端到绑定问题修复前版本 (2026-05-25)

## 目标
用户要求恢复三端（子女端、老人端、后端）到"改绑定老人相关之前"的版本，因为今晚的绑定相关修改导致问题。

## 执行步骤

### 1. 分析时间线
通过 `git log` 查看三端今晚的提交记录，确定绑定相关改动的范围：
- **子女端**：`f238d25` 开始绑定相关修复，涉及 `CloudBaseClient.kt`, `LoginActivity.kt`, `MainActivity.kt`
- **后端**：`4d8cd00` 开始绑定相关修复，涉及 `server.js` 的解绑逻辑
- **老人端**：`014a023` WSClient修复 + `7634c3f` 登录后自动连接WS（间接支持绑定流程）

### 2. 恢复三端到绑定改动之前
```bash
# 子女端 - 恢复到 WS二进制帧接收版本
cd ~/.qclaw/workspace-x5kuz49xple53hhg/projects/family-guardian-app
git reset --hard 3efb7fe

# 后端 - 恢复到 添加RemoteAssistActivity日志版本
cd ~/.qclaw/workspace-x5kuz49xple53hhg/projects/diedaobao-server
git reset --hard 601beca

# 老人端 - 恢复到 二进制帧用okio.Buffer版本
cd ~/.qclaw/workspace-x5kuz49xple53hhg/projects/fall-detection-app
git reset --hard 6989a66
```

### 3. 编译子女端 APK
```bash
export JAVA_HOME="/Users/zhou/jdk-17/jdk-17.0.2.jdk/Contents/Home"
cd ~/.qclaw/workspace-x5kuz49xple53hhg/projects/family-guardian-app
./gradlew clean
./gradlew assembleDebug --no-daemon --max-workers=1
```
**结果**：✅ BUILD SUCCESSFUL in 4m 55s

### 4. 发送 APK 到微信
```bash
bash ~/.qclaw/workspace-x5kuz49xple53hhg/scripts/wechat_mac_send.sh \
  "/Users/zhou/.qclaw/workspace-x5kuz49xple53hhg/projects/family-guardian-app/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk"
```
**结果**：✅ 发送成功 (17M)

### 5. 重启后端（使用恢复后的代码）
```bash
# 停止旧进程
kill 66416

# 启动新进程
cd ~/.qclaw/workspace-x5kuz49xple53hhg/projects/diedaobao-server
nohup node server.js > server.log 2>&1 &
```
**结果**：✅ 新进程启动 (PID 67998)，健康检查通过

## 恢复目标版本详情

### 子女端 (`3efb7fe`)
**提交信息**：`feat: WS二进制帧接收 (handleBinaryFrame) + AssistFrame加jpegBytes字段`

**关键文件**：
- `CloudBaseClient.kt` - 未修改（保持原始绑定逻辑）
- `LoginActivity.kt` - 未修改
- `MainActivity.kt` - 未修改

### 后端 (`601beca`)
**提交信息**：`fix: 添加RemoteAssistActivity日志，诊断guardianId问题`

**关键文件**：
- `server.js` - 未包含解绑逻辑（保持简单的绑定关系管理）

### 老人端 (`6989a66`)
**提交信息**：`fix: 二进制帧用okio.Buffer替代ByteString.of(spread)防崩溃`

**关键文件**：
- `WSClient.kt` - 未修改（保持原始WS连接逻辑）
- `LoginActivity.kt` - 未修改

## 编译输出
- **APK路径**：`app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`
- **文件大小**：17M
- **编译时间**：4m 55s
- **版本信息**：versionCode 恢复到了绑定改动之前的版本

## 后端状态
- **旧进程**：PID 66416 (已停止)
- **新进程**：PID 67998 (运行中)
- **健康检查**：`{"status":"ok","time":1779714461006,"db":"ok","ws":{"total":0,"elders":0,"guardians":0}}`
- **端口**：3000

## 待验证
- [ ] 用户安装 APK 并测试
- [ ] 确认绑定功能是否正常
- [ ] 确认退出登录后重新登录是否还丢失绑定

## 经验教训
1. **绑定逻辑改动要谨慎**：涉及三端协同（老人端、子女端、后端），改动前要全链路分析
2. **恢复版本前要确认范围**：通过 `git log --since` 查看今晚所有提交，避免遗漏
3. **后端在Mac上运行**：重启后端时要确保使用正确的代码目录
4. **编译时间约5分钟**：`assembleDebug` 需要耐心等待，不要中途终止

## 相关文件
- 子女端：`~/.qclaw/workspace-x5kuz49xple53hhg/projects/family-guardian-app/`
- 后端：`~/.qclaw/workspace-x5kuz49xple53hhg/projects/diedaobao-server/`
- 老人端：`~/.qclaw/workspace-x5kuz49xple53hhg/projects/fall-detection-app/`
- 发送脚本：`~/.qclaw/workspace-x5kuz49xple53hhg/scripts/wechat_mac_send.sh`
