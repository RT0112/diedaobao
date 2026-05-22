# Bug修复验收报告 - 2026-05-22

## CC修复内容（来自CC输出）

1. **Bug 1 - 通知标题通用化**：跌倒警报 → 远程协助通知（适应跌倒+围栏等多种通知）
2. **Bug 2 - 位置请求卡住**：添加wsListenerJob取消 + 发送请求前等待WS连接 + 超时后正确隐藏loading
3. **Bug 3 - 强制弹窗不生效**：移除错误的canDrawOverlays检查（全屏Intent不需要悬浮窗权限，USE_FULL_SCREEN_INTENT已在Manifest声明）

---

## 验收环境

- **设备**：K70 (a0c2910e)
- **子女端版本**：v0.8.9（已安装）
- **老人端版本**：待确认
- **后端**：Mac本地 :3000
- **ngrok**：http://oscular-august-equanimously.ngrok-free.dev

---

## Bug 1 验收：通知标题

### 测试步骤
1. 触发老人端跌倒检测（或手动触发测试通知）
2. 观察子女端收到的通知标题

### 期望结果
- 通知标题显示"远程协助通知"（不是"跌倒通知"或"跌倒警报"）

### 当前状态
- ⏳ 待测试（需要先触发通知）

---

## Bug 2 验收：位置请求卡住

### 测试步骤
1. 打开子女端App
2. **等待5分钟**（模拟idle）
3. 点击"查看位置"按钮
4. 观察：是否还卡在"正在获取"

### 期望结果
- **修复前**：永远显示"正在获取"，不会超时
- **修复后**：30秒后超时，隐藏loading，显示"获取位置超时"或返回主页

### 当前状态
- ⚠️ **发现基础设施问题**：后端WS连接数 elders=0（老人端未连接WS）
- 需要先解决WS连接问题，才能正确测试位置请求

### 问题分析
从日志看到：
```
OkHttpClient: A connection to https://oscular-august-equanimously.ngrok-free.dev/ was leaked
```

说明老人端App尝试连接WS但失败了。可能原因：
1. 老人端App没正确启动WS连接
2. ngrok URL变化了（需要确认）
3. 老人端App版本太旧

### 下一步
1. 检查老人端App版本
2. 检查WS连接逻辑
3. 重新测试

---

## Bug 3 验收：强制弹窗不生效

### 测试步骤
1. 打开老人端App
2. 进入设置
3. **打开"强制弹窗"开关**
4. 从子女端发起远程协助（如"查看位置"或"拍照"）
5. 观察：老人端是否弹窗

### 期望结果
- 老人端弹出全屏通知窗口（即使App在后台）

### 当前状态
- ⏳ 待测试（需要双端配合）

---

## 发现的问题

### 问题1：后端WS连接数 elders=0
- **症状**：老人端App启动后，后端WS连接数 elders=0
- **影响**：位置请求无法下发到老人端，子女端显示"获取状态失败"
- **根因**：待排查（可能是老人端WS连接逻辑问题，或ngrok URL配置错误）

### 问题2：OkHttp连接泄漏警告
```
OkHttpClient: A connection to https://oscular-august-equanimously.ngrok-free.dev/ was leaked
```
- **症状**：日志大量打印连接泄漏警告
- **影响**：可能WS连接不稳定
- **根因**：OkHttp没有正确关闭response body

---

## 下一步行动

### 立即执行
1. ✅ **先修复WS连接问题**（这是阻塞其他测试的关键）
2. 确认老人端App版本
3. 检查老人端WS连接逻辑

### 验收顺序
1. 先修WS连接 → 确保基础设施正常
2. 再测Bug 2（位置卡住）
3. 再测Bug 3（强制弹窗）
4. 最后测Bug 1（通知标题）

---

## 验收结论（暂定）

- **Bug 1**（通知标题）：⏳ 待测试
- **Bug 2**（位置卡住）：⏳ 阻塞中（WS连接问题）
- **Bug 3**（强制弹窗）：⏳ 待测试

**建议**：先把WS连接问题修了，否则无法正确测试位置请求功能。

---

## 附录：测试命令速查

```bash
# 检查K70连接
export PATH="$HOME/Library/Android/sdk/platform-tools:$PATH"
adb devices

# 检查子女端版本
adb -s a0c2910e shell pm dump com.familyguardian.app | grep versionName

# 检查老人端版本
adb -s a0c2910e shell pm dump com.falldetector.diedaobao | grep versionName

# 检查后端健康状态
curl -s http://127.0.0.1:3000/health | python3 -m json.tool

# 启动后端
cd ~/.qclaw/workspace-x5kuz49xple53hhg/projects/diedaobao-server
node server.js &

# 启动ngrok
export PATH="$HOME/.local-node/bin:$PATH"
ngrok http 3000 &

# 清日志
adb -s a0c2910e logcat -c

# 抓WS相关日志
adb -s a0c2910e logcat -d | grep -E "WSClient|WebSocket|connect|auth" | grep falldetector | tail -30
```
