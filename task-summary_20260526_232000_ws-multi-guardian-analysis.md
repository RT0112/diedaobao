# WS多子女端连接问题分析 + HTTP统一WS方案

## 时间
2026-05-26 23:20

## 目标
分析2个子女端(330+333)绑定1个老人(331)时WS连接问题，以及能否删除HTTP统一走WS

## 核心发现

### WS断线重连根因
- 同一userId多处调用`WSClient.connect()`（子女端4处，老人端5处）
- 服务端`handleAuth()`发现同userId已有连接 → 踢掉旧的 → 断线重连循环
- **多子女端之间不会互踢**（不同userId各自独立连接）

### 2个子女绑定1个老人 → WS正常
- `clients = Map<userId, WebSocket>` 是按userId存的
- 330和333是不同userId，各占一条WS连接
- `rooms` 和 `broadcastToRoom` 正确处理多子女广播

### 扩展到N个子女也没问题
- 每个子女独立WS连接
- 广播走rooms Set，天然支持多成员
- 唯一瓶颈：同一userId被自己的多处connect()调用踢

## HTTP统一WS方案

### 可删（客户端不再调用，后端保留兼容）
- `/remote-assist?action=poll_request` → WS assist_request
- `/remote-assist?action=check_status` → WS assist_response  
- `/remote-assist?action=poll_signal` → WS assist_signal
- `/remote-assist?action=poll_frame` → WS assist_frame/二进制
- `/request-elder-location` → WS location_request
- `/get-status`轮询 → WS location_update + fall_event

### 不可删
- 注册/登录（WS连接前需要）
- 绑定/历史查询（一次性请求-响应）
- fall-report/location-sync（HTTP写入更可靠，同时触发WS推送）

## 推荐修复步骤
1. **最优先**：统一WS连接入口，只在Application.onCreate调用connect()
2. 删客户端HTTP轮询代码
3. （可选）把写操作也迁到WS — 暂缓，HTTP写入更可靠

## 关键代码位置
- 服务端踢连接：ws.js handleAuth() → `existing.close(4001, 'New connection replaced')`
- 子女端多调用：FamilyGuardianApp:89, MainActivity:83, LoginActivity:148/239, HomeFragment:159
- 老人端多调用：FallDetectionApp:59, MainActivity:64, LoginActivity:164/256/303
