// 跌倒宝 WebSocket 长连接模块
// 替代 HTTP 轮询，实现实时推送：跌倒通知、协助请求、位置更新、远程协助信令/帧

const { WebSocketServer } = require('ws')
const { getDb } = require('./db')

// JWT 密钥（由 server.js 通过 initWS(server, jwtSecret) 传入）
let JWT_SECRET = null

// 连接管理：userId → WebSocket
const clients = new Map()

// 房间管理：elderId → Set<userId>（老人+所有绑定的家属）
const rooms = new Map()

let wss = null

/**
 * 初始化 WebSocket 服务器，挂载到已有 HTTP server 上
 * @param {object} server - HTTP server 实例
 * @param {string} jwtSecret - JWT 密钥（由 server.js 传入）
 */
function initWS(server, jwtSecret) {
  JWT_SECRET = jwtSecret
  wss = new WebSocketServer({ server, path: '/ws' })

  wss.on('connection', (ws, req) => {
    const ip = req.headers['x-forwarded-for'] || req.socket.remoteAddress
    console.log(`[WS] 新连接 from ${ip}`)

    ws.isAlive = true
    ws.userId = null
    ws.role = null // 'elder' | 'guardian'

    ws.on('pong', () => { ws.isAlive = true })

    ws.on('message', (raw) => {
      let msg
      try {
        msg = JSON.parse(raw)
      } catch {
        return wsSend(ws, { type: 'error', message: 'Invalid JSON' })
      }
      handleMessage(ws, msg)
    })

    ws.on('close', () => {
      if (ws.userId) {
        console.log(`[WS] 断开: ${ws.userId} (${ws.role})`)
        clients.delete(ws.userId)
        leaveRoom(ws)
      }
    })

    ws.on('error', (err) => {
      console.error(`[WS] 错误: ${err.message}`)
    })

    // 发送欢迎
    wsSend(ws, { type: 'connected', message: 'WebSocket 已连接，请认证' })
  })

  // 心跳检测：每30秒 ping，60秒无 pong 则断开
  const heartbeat = setInterval(() => {
    wss.clients.forEach((ws) => {
      if (!ws.isAlive) return ws.terminate()
      ws.isAlive = false
      ws.ping()
    })
  }, 30000)

  wss.on('close', () => clearInterval(heartbeat))

  console.log('[WS] WebSocket 服务器已启动，路径: /ws')
}

/**
 * 处理客户端消息
 */
function handleMessage(ws, msg) {
  const { type, data } = msg

  switch (type) {
    case 'auth':
      handleAuth(ws, msg)  // 传完整 msg，包含 token
      break

    case 'ping':
      wsSend(ws, { type: 'pong', ts: Date.now() })
      break

    // --- 跌倒通知（老人端→服务器→子女端）---
    case 'fall_event':
      broadcastToRoom(ws.userId, { type: 'fall_event', data }, ws.userId)
      break

    // --- 位置更新推送 ---
    case 'location_update':
      broadcastToRoom(ws.userId, { type: 'location_update', data }, ws.userId)
      break

    // --- 位置拉取请求（子女端→老人端）---
    case 'location_request': {
      const locElderId = data?.elderId
      if (!locElderId) break
      // 优先WS推送
      const locPushed = sendToUser(locElderId, { type: 'location_request', data: { guardianId: ws.userId, requestTime: Date.now() } })
      // WS不在线时，写DB标记让老人端HTTP轮询拉取（降级保障）
      if (!locPushed) {
        try {
          const db = getDb()
          db.prepare('UPDATE users SET pullLocationRequest=?, pullLocationStatus=? WHERE id=?')
            .run(Date.now(), 'pending', locElderId)
          console.log(`[WS] location_request: WS离线，已写DB拉取标记 elderId=${locElderId}`)
        } catch (e) { console.error('[WS] location_request DB降级失败:', e.message) }
      }
      break
    }

    // --- 远程协助请求（子女端→老人端）---
    case 'assist_request':
      sendToUser(data?.elderId, { type: 'assist_request', data: { guardianId: ws.userId, guardianName: data?.guardianName || '家属', requestTime: Date.now() } })
      break

    // --- 远程协助响应（老人端→子女端）---
    case 'assist_response':
      if (data?.accepted && data?.guardianId) {
        sendToUser(data.guardianId, { type: 'assist_response', data: { accepted: true, sessionId: data.sessionId, elderId: ws.userId } })
      } else if (data?.guardianId) {
        sendToUser(data.guardianId, { type: 'assist_response', data: { accepted: false } })
      }
      break

    // --- 远程协助结束 ---
    case 'assist_end':
      broadcastToRoom(ws.userId, { type: 'assist_end', data: { from: ws.userId, reason: data?.reason || 'ended' } }, ws.userId)
      break

    // --- 远程协助信令（触摸/按键，双向）---
    case 'assist_signal':
      if (data?.to) {
        sendToUser(data.to, { type: 'assist_signal', data: { ...data, from: ws.userId } })
      }
      break

    // --- 远程协助屏幕帧（老人端→子女端，二进制或base64）---
    case 'assist_frame':
      if (data?.to) {
        sendToUser(data.to, { type: 'assist_frame', data })
      }
      break

    // --- 协助请求取消 ---
    case 'assist_cancel':
      if (data?.elderId) {
        sendToUser(data.elderId, { type: 'assist_cancel', data: { guardianId: ws.userId } })
      }
      break

    // --- 围栏越界通知 ---
    case 'geofence_breach':
      broadcastToRoom(ws.userId, { type: 'geofence_breach', data }, ws.userId)
      break

    default:
      wsSend(ws, { type: 'error', message: `未知消息类型: ${type}` })
  }
}

/**
 * 认证：绑定 userId 到 WebSocket 连接
 * @param {object} msg - 完整消息对象，包含 token/data
 */
function handleAuth(ws, msg) {
  // 支持两种认证方式：token（推荐）或 userId（兼容旧版）
  // token 可以在 msg.token（新版）或 msg.data.token（旧版兼容）中
  let userId = null
  const token = msg.token || (msg.data && msg.data.token)

  if (token) {
    try {
      const decoded = require('jsonwebtoken').verify(token, JWT_SECRET)
      // 优先使用 token 中的 userId，但如果为 null 则尝试使用 msg.data.userId（guardian app 会传正确的 userId）
      userId = decoded.userId || (msg.data && msg.data.userId) || decoded.accountId
    } catch (e) {
      return wsSend(ws, { type: 'auth_result', success: false, message: 'token 无效或已过期' })
    }
  } else if (msg.data && msg.data.userId) {
    userId = msg.data.userId  // 兼容旧版 APP（无 token）
  } else if (msg.userId) {
    userId = msg.userId  // 兼容更旧的格式
  } else {
    return wsSend(ws, { type: 'auth_result', success: false, message: '缺少 userId 或 token' })
  }

  // 如果该 userId 已有连接，踢掉旧的
  const existing = clients.get(userId)
  if (existing && existing !== ws) {
    console.log(`[WS] 踢掉旧连接: ${userId}`)
    try { existing.close(4001, 'New connection replaced') } catch {}
    clients.delete(userId)
  }

  ws.userId = userId
  ws.role = (msg.data && msg.data.role) || 'elder'
  clients.set(userId, ws)

  // 加入房间
  joinRoom(ws)

  console.log(`[WS] 认证成功: ${userId} (${ws.role})`)
  wsSend(ws, { type: 'auth_result', success: true, userId, role: ws.role })
}

/**
 * 加入房间：老人→自己的房间，家属→绑定老人的房间
 */
function joinRoom(ws) {
  const db = getDb()

  if (ws.role === 'elder') {
    // 老人加入自己的房间
    if (!rooms.has(ws.userId)) rooms.set(ws.userId, new Set())
    rooms.get(ws.userId).add(ws.userId)
    
    // v24: 老人加入房间后，同时把【已在线的】绑定家属也加入房间
    const guardians = db.prepare("SELECT familyId FROM family_bindings WHERE elderId=? AND status='active'").all(ws.userId)
    for (const g of guardians) {
      if (!rooms.has(ws.userId)) rooms.set(ws.userId, new Set())
      rooms.get(ws.userId).add(g.familyId)
    }
    console.log(`[WS] 老人 ${ws.userId} 加入房间，成员: ${[...rooms.get(ws.userId)].join(',')}`)
  } else {
    // 家属加入绑定的老人房间
    const bindings = db.prepare("SELECT elderId FROM family_bindings WHERE familyId=? AND status='active'").all(ws.userId)
    for (const b of bindings) {
      if (!rooms.has(b.elderId)) rooms.set(b.elderId, new Set())
      rooms.get(b.elderId).add(ws.userId)
      // v24: 同时把老人也加入房间（如果已在线）
      const eWs = clients.get(b.elderId)
      if (eWs) rooms.get(b.elderId).add(b.elderId)
    }
    console.log(`[WS] 家属 ${ws.userId} 加入房间，绑定老人: ${bindings.map(b => b.elderId).join(',')}`)
  }
}

/**
 * 离开房间
 */
function leaveRoom(ws) {
  for (const [roomId, members] of rooms) {
    members.delete(ws.userId)
    if (members.size === 0) rooms.delete(roomId)
  }
}

/**
 * 向房间内所有人广播（排除发送者）
 */
function broadcastToRoom(senderId, message, excludeUserId) {
  if (!senderId) return

  const db = getDb()
  // 找到该用户所在的所有房间
  const elderIds = new Set()

  // 如果发送者是老人，房间就是自己
  const sender = clients.get(senderId)
  if (sender?.role === 'elder') {
    elderIds.add(senderId)
  }

  // 查找发送者绑定的老人（sender是家属时）
  const bindings = db.prepare("SELECT elderId FROM family_bindings WHERE familyId=? AND status='active'").all(senderId)
  for (const b of bindings) elderIds.add(b.elderId)

  // 查找绑定到发送者（老人）的家属 — 这里需要找到老人自己的房间
  const guardians = db.prepare("SELECT familyId FROM family_bindings WHERE elderId=? AND status='active'").all(senderId)
  for (const g of guardians) {
    // sender是老人，g.familyId是家属，老人自己的房间就是senderId
    elderIds.add(senderId)
  }

  // 对于每个老人房间，向所有成员广播（已包含老人自己 + 所有绑定家属，去重）
  for (const elderId of elderIds) {
    const roomMembers = rooms.get(elderId)
    if (!roomMembers) continue

    for (const memberId of roomMembers) {
      if (memberId === excludeUserId) continue
      sendToUser(memberId, message)
    }
  }
  // ⚠️ 注意：下面这段是重复逻辑，已删除（第260-267行）
  // 重复原因：elderIds 已经包含 senderId（老人），roomMembers 已经包含家属
  // 如果保留下面这段，家属会收到**两次**同一条消息
}

/**
 * 向指定用户发送消息
 */
function sendToUser(userId, message) {
  const ws = clients.get(userId)
  if (!ws || ws.readyState !== 1) return false
  return wsSend(ws, message)
}

/**
 * 安全发送 WebSocket 消息
 */
function wsSend(ws, data) {
  if (ws.readyState !== 1) return false
  try {
    ws.send(JSON.stringify(data))
    return true
  } catch (e) {
    console.error(`[WS] 发送失败: ${e.message}`)
    return false
  }
}

/**
 * 获取在线状态
 */
function getOnlineStats() {
  const elders = []
  const guardians = []
  for (const [userId, ws] of clients) {
    if (ws.role === 'elder') elders.push(userId)
    else guardians.push(userId)
  }
  return { total: clients.size, elders: elders.length, guardians: guardians.length, elderIds: elders, guardianIds: guardians }
}

/**
 * 检查用户是否在线
 */
function isOnline(userId) {
  const ws = clients.get(userId)
  return ws != null && ws.readyState === 1
}

/**
 * 服务器主动推送（供 HTTP 路由调用）
 * 例如：fall-report 路由处理后推送给子女端
 */
function pushToRoom(elderId, message) {
  const roomMembers = rooms.get(elderId)
  if (!roomMembers) return 0
  let sent = 0
  for (const memberId of roomMembers) {
    if (sendToUser(memberId, message)) sent++
  }
  return sent
}

module.exports = { initWS, getOnlineStats, isOnline, pushToRoom, sendToUser, broadcastToRoom }
