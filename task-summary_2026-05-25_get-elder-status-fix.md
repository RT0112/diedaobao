# 任务总结：修复子女端获取老人状态失败 (2026-05-25)

## 目标
修复子女端 App 绑定后无法获取老人状态和位置的问题。

## 根因分析
**JSON 解析错误**：后端返回格式是 `{code:200, data:{...}}`，但 App 的 `CloudBaseClient.kt` 中多个方法把整个 `json` 对象当成目标类型反序列化，导致所有字段都是 `null`。

### 受影响方法
1. **`getElderStatus()`**：`gson.fromJson(json, ElderStatus::class.java)` → 应提取 `json.get("data")`
2. **`getFallHistory()`**：`json.getAsJsonArray("events")` → 应提取 `json.get("data").events`

## 修复步骤
1. 读取 `CloudBaseClient.kt` 第 523-557 行 (`getElderStatus()`)
2. 修复：提取 `json.get("data")?.asJsonObject` 再反序列化
3. 读取第 563-602 行 (`getFallHistory()`)
4. 修复：提取 `json.get("data")?.asJsonObject` 再获取 `events` 数组
5. Git commit: `e53e9a2` ("fix: 修复 getElderStatus 和 getFallHistory JSON 解析（提取 data 字段）")
6. 编译：`export JAVA_HOME=... && ./gradlew assembleDebug --no-daemon --max-workers=1`
7. 发送 APK 到微信文件传输助手（`wechat_mac_send.sh`）

## 编译结果
✅ BUILD SUCCESSFUL in 2m 16s  
✅ APK: `app-arm64-v8a-debug.apk` (18M)  
✅ 已发送到微信

## 待验证
- [ ] 用户安装 APK 并测试
- [ ] 确认首页能显示老人状态
- [ ] 确认跌倒历史能正常加载

## 经验教训
1. **后端返回格式要文档化**：所有 API 端点返回 `{code, message, data}` 格式，App 端必须提取 `data` 字段
2. **JSON 反序列化要小心**：`gson.fromJson(json, Class)` 会把整个 `json` 对象尝试映射到 `Class` 字段，如果字段不匹配会返回 `null` 或默认值
3. **日志是关键**：`Log.i(TAG, "getElderStatus response: $responseBody")` 帮助确认后端返回格式

## 相关文件
- `/Users/zhou/.qclaw/workspace-x5kuz49xple53hhg/projects/family-guardian-app/app/src/main/java/com/familyguardian/app/cloud/CloudBaseClient.kt` (修改)
- `server.js` (`/get-status`, `/fall-history` 端点返回 `{code,data}` 格式)
