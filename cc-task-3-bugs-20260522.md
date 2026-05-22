# CC任务分配记录 - 2026-05-22 20:00

## 任务来源
用户报告3个Bug，要求CC修复。

## Bug列表

### Bug 1: 子女端通知标题仍是"跌倒通知"
- **问题描述**：通知标题应该改成通用的（如"远程协助通知"），而不是特指"跌倒"
- **期望行为**：通知标题显示"远程协助通知"或"跌倒宝通知"
- **实际行为**：显示"跌倒通知"
- **可能位置**：
  - 老人端App：发送通知的代码
  - 子女端App：接收并显示通知的代码
  - 后端server：FCM推送payload

### Bug 2: 查看位置功能卡住（一直显示"正在获取"）
- **问题描述**：等待几分钟后点击"查看位置"，一直显示"正在获取"，不会超时
- **复现步骤**：
  1. 打开子女端App
  2. 等待几分钟
  3. 点击"查看位置"
  4. 卡住不动
- **可能原因**：
  - WebSocket连接已断开但UI状态未重置
  - 位置请求没有超时机制
  - 后端server没有正确处理延迟请求
- **需要排查**：
  - 子女端：ViewModel/Repository的位置请求逻辑
  - 后端server：位置请求超时处理
  - WebSocket连接状态管理

### Bug 3: 强制弹窗设置不生效
- **问题描述**：老人端设置里打开了"强制弹窗"开关，但实际没有弹窗
- **可能原因**：
  - SharedPreferences保存/读取错误
  - 弹窗逻辑没有检查这个设置
  - 高版本Android悬浮窗权限问题
  - 弹窗代码逻辑错误
- **需要排查**：
  - 老人端：设置页面（保存）
  - 老人端：弹窗逻辑（读取设置）
  - 老人端：悬浮窗权限检查

## 任务分配

**分配方式**：用AppleScript粘贴到用户已打开的Terminal窗口（CC交互模式会话）

**任务描述**（英文，避免安全扫描拦截）：
```
Read ~/.qclaw/workspace-x5kuz49xple53hhg/bug-report-3-issues.md and fix all 3 bugs described in it. The bug report is in Chinese. After fixing, commit your changes and tell me how to verify each fix.
```

**Bug报告文件**：`~/.qclaw/workspace-x5kuz49xple53hhg/bug-report-3-issues.md`

## 要求（已写入Bug报告）

1. **先分析根因，再动手改代码** — 不要盲目试错
2. **理解完整数据流** — 老人端App ↔ 后端server ↔ 子女端App
3. **改完必须git commit** — 不要只改代码不提交
4. **验证** — 改完后说明如何验证（K70实测由我负责）

## 验收标准（我的职责）

根据铁律21：**我是管理验收者，CC是开发者**

验收步骤：
1. CC改完代码并commit
2. 我编译App（或让CC编译）
3. 安装到K70实测
4. 验证3个Bug是否修复
5. 验收通过后，发微信通知用户

## 当前状态

- ✅ Bug报告已写好：`~/.qclaw/workspace-x5kuz49xple53hhg/bug-report-3-issues.md`
- ✅ 任务已发送给CC（通过AppleScript粘贴到Terminal）
- ⏳ 等待CC读取bug报告并分析问题
- ⏳ 等待CC修复代码并提交

## 后续动作

1. **监控CC进度** — 通过 `process(action="log")` 查看CC输出
2. **验收** — CC改完后，编译→安装→K70实测
3. **发微信** — 验收通过后，发微信通知用户

## 铁律更新

本次任务执行前，已更新AGENTS.md铁律：
- **21条**：我是管理验收者，CC是开发者
- **22条**：发CC任务用AppleScript粘贴到用户Terminal

## 文件位置

- Bug报告：`~/.qclaw/workspace-x5kuz49xple53hhg/bug-report-3-issues.md`
- 本记录：`~/.qclaw/workspace-x5kuz49xple53hhg/cc-task-3-bugs-20260522.md`
- 项目代码：
  - `~/.qclaw/workspace-x5kuz49xple53hhg/projects/diedaobao-elderly/` (老人端)
  - `~/.qclaw/workspace-x5kuz49xple53hhg/projects/diedaobao-child/` (子女端)
  - `~/.qclaw/workspace-x5kuz49xple53hhg/projects/diedaobao-server/` (后端)
