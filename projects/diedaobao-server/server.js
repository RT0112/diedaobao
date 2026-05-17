// 跌倒宝后端服务器 - 替代 CloudBase 所有云函数 + WebSocket 实时推送
// 运行在 K70 (Termux) 上，SQLite 数据库

const express = require('express')
const cors = require('cors')
const compression = require('compression')
const http = require('http')
const { getDb, genId } = require('./db')
const { initWS, getOnlineStats, isOnline, pushToRoom, sendToUser } = require('./ws')

const app = express()
const PORT = process.env.PORT || 3000

app.use(cors())
app.use(compression())
app.use(express.json({ limit: '10mb' }))
app.use(express.urlencoded({ extended: true, limit: '10mb' }))

// 日志中间件
app.use((req, res, next) => {
  const t = Date.now()
  res.on('finish', () => {
    const ms = Date.now() - t
    if (req.path !== '/health') {
      console.log(`[${new Date().toISOString()}] ${req.method} ${req.path} ${res.statusCode} ${ms}ms`)
    }
  })
  next()
})

// 健康检查
app.get('/health', (req, res) => {
  const db = getDb()
  try {
    db.prepare('SELECT 1').get()
    const wsStats = getOnlineStats()
    res.json({ status: 'ok', time: Date.now(), db: 'ok', ws: wsStats })
  } catch (e) {
    res.status(500).json({ status: 'error', db: e.message })
  }
})

// ========== user-register ==========
app.post('/user-register', (req, res) => {
  try {
    const body = req.body
    const { deviceId, name, phone, role } = body
    if (!deviceId) return res.json({ code: 400, message: 'Missing deviceId' })

    const db = getDb()
    const existing = db.prepare('SELECT * FROM users WHERE deviceId = ?').get(deviceId)
    if (existing) {
      db.prepare(`UPDATE users SET name=COALESCE(?,name), phone=COALESCE(?,phone), role=COALESCE(?,role), updatedAt=? WHERE deviceId=?`)
        .run(name || existing.name, phone !== undefined ? phone : existing.phone, role || existing.role, Date.now(), deviceId)
      const updated = db.prepare('SELECT * FROM users WHERE deviceId=?').get(deviceId)
      return res.json({ code: 200, message: 'User already exists (updated)', userId: updated.id, data: { id: updated.id, deviceId: updated.deviceId, name: updated.name, phone: updated.phone, role: updated.role } })
    }

    const id = genId()
    db.prepare(`INSERT INTO users (id, deviceId, name, phone, role, createdAt, updatedAt) VALUES (?,?,?,?,?,?,?)`)
      .run(id, deviceId, name || '老人', phone || '', role || 'elder', Date.now(), Date.now())
    return res.json({ code: 200, message: 'User registered successfully', userId: id, data: { id, deviceId, name: name || '老人', phone: phone || '', role: role || 'elder' } })
  } catch (err) {
    return res.status(500).json({ code: 500, message: err.message })
  }
})

// ========== fall-report ==========
app.post('/fall-report', (req, res) => {
  try {
    const { userId, timestamp, latitude, longitude, impactG, ffDuration, mlScore, physicalScore, accuracy } = req.body
    if (!userId) return res.json({ code: 400, message: 'Missing userId' })

    const db = getDb()
    const id = genId()
    const fallTs = timestamp ? (isNaN(timestamp) ? Date.now() : parseInt(timestamp)) : Date.now()

    db.prepare(`INSERT INTO fall_events (id, userId, timestamp, latitude, longitude, impactG, ffDuration, mlScore, physicalScore, status, createdAt)
      VALUES (?,?,?,?,?,?,?,?,?,?,?)`)
      .run(id, userId, fallTs, latitude || null, longitude || null, impactG || 0, ffDuration || 0, mlScore || 0, physicalScore || 0, 'pending', Date.now())

    // 写入 lastFallEvent 到 users 表
    const lastFallEvent = JSON.stringify({ eventId: id, timestamp: fallTs, impactG: impactG || 0, mlScore: mlScore || 0, latitude: latitude || null, longitude: longitude || null })
    db.prepare('UPDATE users SET lastFallEvent=?, updatedAt=? WHERE id=?').run(lastFallEvent, Date.now(), userId)

    // 获取绑定的家属数量
    const bindings = db.prepare("SELECT id, familyId FROM family_bindings WHERE elderId=? AND status='active'").all(userId)

    // v24: WS 实时推送跌倒事件给子女端
    const fallData = { eventId: id, timestamp: fallTs, impactG: impactG || 0, mlScore: mlScore || 0, latitude: latitude || null, longitude: longitude || null }
    // 优先用 pushToRoom（依赖rooms），同时直接查库推送（防止rooms未初始化）
    let wsPushed = pushToRoom(userId, { type: 'fall_event', data: fallData })
    // 如果 pushToRoom 推送失败（rooms为空），直接查库推送
    if (wsPushed === 0) {
      const bindings = db.prepare('SELECT familyId FROM family_bindings WHERE elderId=? AND status=?').all(userId, 'active')
      for (const b of bindings) {
        if (sendToUser(b.familyId, { type: 'fall_event', data: fallData })) wsPushed++
      }
      console.log(`[fall-report] pushToRoom=0, 直接推送: ${wsPushed}个家属`)
    }
    // 同时推送 location_update（位置同步）
    if (latitude && longitude) {
      pushToRoom(userId, { type: 'location_update', data: { latitude, longitude, accuracy: accuracy || 0, timestamp: fallTs } })
      // 如果 pushToRoom 失败，直接推送给家属
      const locData = { latitude: parseFloat(latitude), longitude: parseFloat(longitude), accuracy: accuracy || 0, timestamp: fallTs }
      const locBindings = db.prepare('SELECT familyId FROM family_bindings WHERE elderId=? AND status=?').all(userId, 'active')
      for (const b of locBindings) {
        sendToUser(b.familyId, { type: 'location_update', data: locData })
      }
    }
    console.log(`[fall-report] WS推送: ${wsPushed}个家属在线`)

    return res.json({ code: 200, message: 'Fall reported', eventId: id, notifiedFamily: bindings.length, wsPushed })
  } catch (err) {
    return res.status(500).json({ code: 500, message: err.message })
  }
})

// ========== location-sync ==========
app.post('/location-sync', (req, res) => {
  try {
    const body = req.body
    const { action, userId, latitude, longitude, accuracy, timestamp } = body
    const elderId = body.elderId || body.data?.elderId

    const db = getDb()

    if (action === 'poll_pull') {
      const targetId = elderId || userId
      const user = db.prepare('SELECT pullLocationRequest, pullLocationStatus FROM users WHERE id=?').get(targetId)
      if (!user) return res.json({ code: 404, hasPullRequest: false, message: 'User not found' })
      const hasPull = user.pullLocationRequest && user.pullLocationStatus === 'pending'
      return res.json({ code: 200, hasPullRequest: !!hasPull, pullRequestTime: user.pullLocationRequest || 0 })
    }

    // 默认：上传位置
    if (!userId || latitude === undefined || longitude === undefined) {
      return res.json({ code: 400, message: 'Missing userId/latitude/longitude' })
    }

    const locId = genId()
    const ts = timestamp ? parseInt(timestamp) : Date.now()
    db.prepare('INSERT INTO locations (id, userId, latitude, longitude, accuracy, timestamp, createdAt) VALUES (?,?,?,?,?,?,?)')
      .run(locId, userId, parseFloat(latitude), parseFloat(longitude), accuracy || 0, ts, Date.now())

    db.prepare('UPDATE users SET lastLocationLat=?, lastLocationLng=?, lastLocationAccuracy=?, lastLocationTime=?, pullLocationRequest=NULL, pullLocationStatus=?, updatedAt=? WHERE id=?')
      .run(parseFloat(latitude), parseFloat(longitude), accuracy || 0, ts, 'done', Date.now(), userId)

    // WS 推送位置更新给子女端
    const locData = { latitude: parseFloat(latitude), longitude: parseFloat(longitude), accuracy: accuracy || 0, timestamp: ts }
    const wsPushed = pushToRoom(userId, { type: 'location_update', data: locData })

    return res.json({ code: 200, success: true, message: 'Location uploaded', wsPushed })
  } catch (err) {
    return res.status(500).json({ code: 500, message: err.message })
  }
})

// ========== get-status ==========
app.get('/get-status', (req, res) => {
  try {
    const elderId = req.query.elderId || req.body?.elderId
    if (!elderId) return res.json({ code: 400, message: 'Missing elderId' })

    const db = getDb()
    const user = db.prepare('SELECT id, name, lastLocationLat, lastLocationLng, lastLocationTime, pullLocationStatus, pullLocationRequest, lastFallEvent, updatedAt FROM users WHERE id=?').get(elderId)
    if (!user) return res.json({ code: 404, message: 'User not found' })

    const oneDayAgo = Date.now() - 86400000
    const falls = db.prepare('SELECT id, status FROM fall_events WHERE userId=? AND timestamp>=? ORDER BY timestamp DESC').all(elderId, oneDayAgo)
    let status = 'normal'
    if (falls.some(f => f.status === 'pending')) status = 'fallen'

    return res.json({
      code: 200, elderId, name: user.name || '老人',
      lastLocation: user.lastLocationLat ? { latitude: user.lastLocationLat, longitude: user.lastLocationLng, timestamp: user.lastLocationTime } : null,
      lastUpdate: user.updatedAt || Date.now(),
      status, pullLocationStatus: user.pullLocationStatus || 'idle',
      pullRequestTime: user.pullLocationRequest || null,
      lastFallEvent: user.lastFallEvent ? JSON.parse(user.lastFallEvent) : null
    })
  } catch (err) {
    return res.status(500).json({ code: 500, message: err.message })
  }
})

// ========== bind-family ==========
app.post('/bind-family', (req, res) => {
  try {
    const body = req.body
    const { action, bindCode, guardianId, elderId, familyId, relation } = body
    const db = getDb()

    if (action === 'generateCode') {
      const code = Math.random().toString().slice(2, 8)
      const targetElderId = elderId || (body.data && body.data.elderId) || (body.userId && body.userId)
      if (!targetElderId) return res.json({ code: 400, message: 'Missing elderId' })
      const codeId = genId()
      db.prepare('INSERT INTO bind_codes (id, code, elderId, used, expiresAt, createdAt) VALUES (?,?,?,?,?,?)')
        .run(codeId, code, targetElderId, 0, Date.now() + 300000, Date.now())
      return res.json({ code: 200, bindCode: code, expiresIn: 300 })
    }

    if (action === 'getBindings') {
      const uid = body.data?.userId || body.userId
      const role = body.data?.role || 'family'
      const field = role === 'elder' ? 'elderId' : 'familyId'
      const bindings = db.prepare(`SELECT * FROM family_bindings WHERE ${field}=? AND status='active'`).all(uid)
      return res.json({ code: 200, data: bindings })
    }

    // 默认：使用绑定码绑定
    const useCode = bindCode || body.code
    const useFamilyId = guardianId || familyId || (body.data && (body.data.guardianId || body.data.familyId))
    if (!useCode) return res.json({ code: 400, message: 'Missing bindCode' })
    if (!useFamilyId) return res.json({ code: 400, message: 'Missing guardianId/familyId' })

    const bindCodeRow = db.prepare("SELECT * FROM bind_codes WHERE code=? AND used=0 AND expiresAt>? AND elderId IS NOT NULL").get(useCode, Date.now())
    if (!bindCodeRow) return res.json({ code: 404, message: 'Invalid or expired bind code' })

    db.prepare('UPDATE bind_codes SET used=1 WHERE id=?').run(bindCodeRow.id)

    const elderRow = db.prepare('SELECT name FROM users WHERE id=?').get(bindCodeRow.elderId)
    const bindingId = genId()
    db.prepare('INSERT INTO family_bindings (id, elderId, familyId, relation, status, createdAt, updatedAt) VALUES (?,?,?,?,?,?,?)')
      .run(bindingId, bindCodeRow.elderId, useFamilyId, relation || '家属', 'active', Date.now(), Date.now())

    return res.json({ code: 200, message: 'Bound successfully', elderId: bindCodeRow.elderId, elderName: elderRow ? elderRow.name : '老人' })
  } catch (err) {
    return res.status(500).json({ code: 500, message: err.message })
  }
})

// ========== fall-history ==========
app.get('/fall-history', (req, res) => {
  try {
    const elderId = req.query.elderId || req.body?.elderId
    const limit = Math.min(parseInt(req.query.limit || 20), 100)
    if (!elderId) return res.json({ code: 400, message: 'Missing elderId' })

    const db = getDb()
    let rows
    if (elderId === '__ALL__') {
      rows = db.prepare('SELECT * FROM fall_events ORDER BY timestamp DESC LIMIT ?').all(limit)
    } else {
      rows = db.prepare('SELECT * FROM fall_events WHERE userId=? ORDER BY timestamp DESC LIMIT ?').all(elderId, limit)
    }

    const events = rows.map(e => ({
      eventId: e.id, timestamp: e.timestamp,
      latitude: e.latitude, longitude: e.longitude,
      impactG: e.impactG, mlScore: e.mlScore, status: e.status
    }))
    return res.json({ code: 200, events, debug: { elderId, totalInCollection: events.length, distinctUserIds: [...new Set(rows.map(r => r.userId))] } })
  } catch (err) {
    return res.status(500).json({ code: 500, message: err.message })
  }
})

// ========== geofence ==========
function calcDistance(lat1, lon1, lat2, lon2) {
  const R = 6371000, dLat = (lat2 - lat1) * Math.PI / 180, dLon = (lon2 - lon1) * Math.PI / 180
  const a = Math.sin(dLat / 2) ** 2 + Math.cos(lat1 * Math.PI / 180) * Math.cos(lat2 * Math.PI / 180) * Math.sin(dLon / 2) ** 2
  return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
}

app.post('/geofence', (req, res) => {
  try {
    const body = req.body
    const { action, elderId, creatorId, name, latitude, longitude, radius, fenceId, isActive } = body
    const db = getDb()

    if (action === 'create') {
      if (!elderId || !creatorId || !name || latitude === undefined || longitude === undefined || !radius) {
        return res.json({ success: false, message: '缺少必填参数' })
      }
      const id = genId()
      db.prepare('INSERT INTO geofences (id, elderId, creatorId, name, latitude, longitude, radius, isActive, createdAt, updatedAt) VALUES (?,?,?,?,?,?,?,?,?,?)')
        .run(id, elderId, creatorId, name.trim().slice(0, 20), parseFloat(latitude), parseFloat(longitude), parseInt(radius), 1, Date.now(), Date.now())
      return res.json({ success: true, fence: { id, elderId, name: name.trim().slice(0, 20), latitude: parseFloat(latitude), longitude: parseFloat(longitude), radius: parseInt(radius), isActive: true, isBreached: false } })
    }

    if (action === 'list') {
      if (!elderId) return res.json({ success: false, message: '缺少elderId', fences: [] })
      const fences = db.prepare('SELECT * FROM geofences WHERE elderId=? ORDER BY createdAt DESC LIMIT 20').all(elderId)
      return res.json({ success: true, fences: fences.map(f => ({ id: f.id, elderId: f.elderId, name: f.name, latitude: f.latitude, longitude: f.longitude, radius: f.radius, isActive: !!f.isActive, isBreached: !!f.isBreached })) })
    }

    if (action === 'update') {
      if (!fenceId) return res.json({ success: false, message: '缺少fenceId' })
      const existing = db.prepare('SELECT * FROM geofences WHERE id=?').get(fenceId)
      if (!existing) return res.json({ success: false, message: '围栏不存在' })
      if (!creatorId || (existing.creatorId !== creatorId)) return res.json({ success: false, message: '无权限修改此围栏' })
      const updates = { updatedAt: Date.now() }
      if (name !== undefined) updates.name = name.trim().slice(0, 20)
      if (latitude !== undefined) updates.latitude = parseFloat(latitude)
      if (longitude !== undefined) updates.longitude = parseFloat(longitude)
      if (radius !== undefined) { const r = parseInt(radius); if (r < 50 || r > 5000) return res.json({ success: false, message: '半径需在50-5000米之间' }); updates.radius = r }
      if (isActive !== undefined) updates.isActive = isActive ? 1 : 0
      const setClause = Object.keys(updates).map(k => `${k}=?`).join(', ')
      db.prepare(`UPDATE geofences SET ${setClause} WHERE id=?`).run(...Object.values(updates), fenceId)
      return res.json({ success: true })
    }

    if (action === 'delete') {
      if (!fenceId || !creatorId) return res.json({ success: false, message: '缺少fenceId或creatorId' })
      const existing = db.prepare('SELECT * FROM geofences WHERE id=?').get(fenceId)
      if (!existing) return res.json({ success: false, message: '围栏不存在' })
      if (existing.creatorId !== creatorId) return res.json({ success: false, message: '无权限删除此围栏' })
      db.prepare('DELETE FROM geofences WHERE id=?').run(fenceId)
      return res.json({ success: true })
    }

    if (action === 'check') {
      if (!elderId || latitude === undefined || longitude === undefined) return res.json({ success: false, message: '缺少elderId或坐标', breaches: [] })
      const fences = db.prepare('SELECT * FROM geofences WHERE elderId=? AND isActive=1').all(elderId)
      const lat = parseFloat(latitude), lng = parseFloat(longitude)
      const breaches = []
      for (const f of fences) {
        const dist = calcDistance(lat, lng, f.latitude, f.longitude)
        const breached = dist > f.radius
        if (breached) breaches.push(f.name)
        if (breached !== !!f.isBreached) db.prepare('UPDATE geofences SET isBreached=? WHERE id=?').run(breached ? 1 : 0, f.id)
      }
      return res.json({ success: true, breaches })
    }

    return res.json({ success: false, message: `未知操作: ${action}` })
  } catch (err) {
    return res.status(500).json({ success: false, message: err.message })
  }
})

// ========== remote-assist ==========
app.post('/remote-assist', (req, res) => {
  try {
    const body = req.body
    const { action, userId, elderId, guardianId, guardianName, accepted, signal, to, from } = body
    const db = getDb()
    const targetId = elderId || userId

    if (action === 'request') {
      if (!elderId || !guardianId) return res.json({ code: 400, message: '缺少 elderId 或 guardianId' })
      const user = db.prepare('SELECT id, remoteAssist FROM users WHERE id=?').get(elderId)
      if (!user) return res.json({ code: 404, message: '老人设备未注册' })
      const ra = user.remoteAssist ? JSON.parse(user.remoteAssist) : {}

      // v23: 顶掉机制 — 如果当前是其他家属在协助，单播通知旧家属并替换
      if (ra.status === 'active' && ra.requestFrom !== guardianId) {
        console.log(`[assist/request] 顶掉旧协助: 旧=${ra.requestFrom}, 新=${guardianId}`)
        // 单播通知旧家属被顶掉（避免广播给老人触发onSessionEnded闪退）
        sendToUser(ra.requestFrom, { type: 'assist_end', data: { from: guardianId, reason: 'kicked', timestamp: Date.now() } })
        // 清理旧帧
        try { db.prepare('DELETE FROM screen_frames WHERE id=?').run(`frame_${elderId}`) } catch {}
      }

      // 同一家属重复请求：直接重发WS推送（不报409）
      if (ra.status === 'requesting' && ra.requestFrom === guardianId && ra.requestTime && Date.now() - ra.requestTime < 60000) {
        const wsPushed = sendToUser(elderId, { type: 'assist_request', data: { guardianId, guardianName: guardianName || '家属', requestTime: ra.requestTime } })
        return res.json({ code: 200, message: '请求已重发', requestTime: ra.requestTime, wsPushed })
      }

      const newRA = { active: false, status: 'requesting', requestFrom: guardianId, requestFromName: guardianName || '家属', requestTime: Date.now(), sessionId: null }
      db.prepare('UPDATE users SET remoteAssist=?, updatedAt=? WHERE id=?').run(JSON.stringify(newRA), Date.now(), elderId)

      // v22: 新请求也需要WS推送（之前误删了sendToUser）
      const wsPushed = sendToUser(elderId, { type: 'assist_request', data: { guardianId, guardianName: guardianName || '家属', requestTime: newRA.requestTime } })
      console.log(`[assist/request] 请求已记录，WS推送=${wsPushed}`)

      return res.json({ code: 200, message: '请求已发送', requestTime: newRA.requestTime, wsPushed })
    }

    if (action === 'poll_request') {
      if (!userId) return res.json({ code: 400, message: '缺少 userId', hasRequest: false })
      const user = db.prepare('SELECT id, remoteAssist FROM users WHERE id=?').get(userId)
      if (!user) return res.json({ code: 404, message: '用户不存在', hasRequest: false })
      const ra = user.remoteAssist ? JSON.parse(user.remoteAssist) : {}
      if (!ra.status || ra.status === 'idle' || ra.status === 'cancelled') return res.json({ code: 200, hasRequest: false })
      if (ra.status === 'requesting') {
        const elapsed = Date.now() - (ra.requestTime || 0)
        if (elapsed > 60000) {
          const cleared = { active: false, status: 'idle', requestFrom: null }
          db.prepare('UPDATE users SET remoteAssist=?, updatedAt=? WHERE id=?').run(JSON.stringify(cleared), Date.now(), userId)
          return res.json({ code: 200, hasRequest: false, message: '请求超时' })
        }
        return res.json({ code: 200, hasRequest: true, requestFrom: ra.requestFrom, requestFromName: ra.requestFromName, requestTime: ra.requestTime, remainingSeconds: Math.ceil((60000 - elapsed) / 1000) })
      }
      return res.json({ code: 200, hasRequest: false })
    }

    if (action === 'respond') {
      if (!userId) return res.json({ code: 400, message: '缺少 userId' })
      const user = db.prepare('SELECT id, remoteAssist FROM users WHERE id=?').get(userId)
      if (!user) return res.json({ code: 404, message: '用户不存在' })
      const ra = user.remoteAssist ? JSON.parse(user.remoteAssist) : {}
      if (ra.status !== 'requesting') return res.json({ code: 400, message: '没有待处理的请求' })
      if (accepted) {
        const sessionId = 'ra_' + Date.now().toString(36) + '_' + Math.random().toString(36).substr(2, 5)
        const newRA = { ...ra, active: true, status: 'active', sessionId, startedAt: Date.now(), signals: [], frameBuffer: [] }
        db.prepare('UPDATE users SET remoteAssist=?, updatedAt=? WHERE id=?').run(JSON.stringify(newRA), Date.now(), userId)

        // WS 推送接受结果给子女端
        sendToUser(ra.requestFrom, { type: 'assist_response', data: { accepted: true, sessionId, elderId: userId } })

        return res.json({ code: 200, message: '已接受', sessionId, guardianId: ra.requestFrom })
      } else {
        const cleared = { active: false, status: 'idle', requestFrom: null }
        db.prepare('UPDATE users SET remoteAssist=?, updatedAt=? WHERE id=?').run(JSON.stringify(cleared), Date.now(), userId)

        // WS 推送拒绝结果给子女端
        sendToUser(ra.requestFrom, { type: 'assist_response', data: { accepted: false } })

        return res.json({ code: 200, message: '已拒绝' })
      }
    }

    if (action === 'check_status') {
      if (!targetId) return res.json({ code: 400, message: '缺少 userId 或 elderId' })
      const user = db.prepare('SELECT remoteAssist FROM users WHERE id=?').get(targetId)
      const ra = user?.remoteAssist ? JSON.parse(user.remoteAssist) : {}
      return res.json({ code: 200, status: ra.status || 'idle', active: !!ra.active, requestFrom: ra.requestFrom, requestFromName: ra.requestFromName, sessionId: ra.sessionId, startedAt: ra.startedAt, screenReady: !!ra.screenReady, screenWidth: ra.screenWidth || 720, screenHeight: ra.screenHeight || 1280 })
    }

    if (action === 'signal') {
      if (!from || !to || !signal || !signal.type) return res.json({ code: 400, message: '缺少必填参数' })
      const targetUser = db.prepare('SELECT id, remoteAssist FROM users WHERE id=?').get(to)
      if (!targetUser) return res.json({ code: 404, message: '目标用户不存在' })
      const ra = targetUser.remoteAssist ? JSON.parse(targetUser.remoteAssist) : {}
      const signals = ra.signals ? [...ra.signals.slice(-50), { type: signal.type, touchAction: signal.touchAction || null, keyCode: signal.keyCode || null, x: signal.x ?? null, y: signal.y ?? null, x1: signal.x1 ?? null, y1: signal.y1 ?? null, x2: signal.x2 ?? null, y2: signal.y2 ?? null, sdp: signal.sdp || null, candidate: signal.candidate || null, from, timestamp: Date.now() }] : [{ type: signal.type, touchAction: signal.touchAction || null, x: signal.x ?? null, y: signal.y ?? null, from, timestamp: Date.now() }]
      db.prepare('UPDATE users SET remoteAssist=?, updatedAt=? WHERE id=?').run(JSON.stringify({ ...ra, signals }), Date.now(), to)

      // WS 实时推送信令
      sendToUser(to, { type: 'assist_signal', data: { ...signal, from } })

      return res.json({ code: 200, message: '信令已发送', signalCount: signals.length })
    }

    if (action === 'poll_signal') {
      if (!userId) return res.json({ code: 400, message: '缺少 userId', signals: [] })
      const user = db.prepare('SELECT remoteAssist FROM users WHERE id=?').get(userId)
      const ra = user?.remoteAssist ? JSON.parse(user.remoteAssist) : {}
      const signals = (ra.signals || []).slice(-20)
      if (signals.length > 0) {
        db.prepare('UPDATE users SET remoteAssist=?, updatedAt=? WHERE id=?').run(JSON.stringify({ ...ra, signals: [] }), Date.now(), userId)
      }
      return res.json({ code: 200, signals })
    }

    if (action === 'upload_frame') {
      const { data: frameData, width, height, frameNum } = body
      if (!userId || !frameData) return res.json({ code: 400, message: '缺少参数' })
      const docId = `frame_${userId}`
      const existing = db.prepare('SELECT id FROM screen_frames WHERE id=?').get(docId)
      if (existing) {
        db.prepare('UPDATE screen_frames SET frameData=?, frameWidth=?, frameHeight=?, frameNum=?, frameTs=?, updatedAt=? WHERE id=?')
          .run(frameData, width || 0, height || 0, frameNum || 0, Date.now(), Date.now(), docId)
      } else {
        db.prepare('INSERT INTO screen_frames (id, elderId, frameData, frameWidth, frameHeight, frameNum, frameTs, updatedAt) VALUES (?,?,?,?,?,?,?,?)')
          .run(docId, userId, frameData, width || 0, height || 0, frameNum || 0, Date.now(), Date.now())
      }
      const ra = db.prepare('SELECT remoteAssist FROM users WHERE id=?').get(userId)
      const oldRA = ra?.remoteAssist ? JSON.parse(ra.remoteAssist) : {}
      db.prepare('UPDATE users SET remoteAssist=?, updatedAt=? WHERE id=?').run(JSON.stringify({ ...oldRA, lastFrameAt: Date.now(), screenReady: true }), Date.now(), userId)

      // WS 推送屏幕帧给子女端（实时）
      pushToRoom(userId, { type: 'assist_frame', data: { frameData, width, height, frameNum } })

      return res.json({ code: 200, frameNum, bufferSize: 1 })
    }

    if (action === 'poll_frame') {
      const target = elderId || userId
      if (!target) return res.json({ code: 400, hasNewFrame: false, message: '缺少参数' })
      const frameDoc = db.prepare('SELECT * FROM screen_frames WHERE id=?').get(`frame_${target}`)
      if (!frameDoc) return res.json({ code: 200, hasNewFrame: false, bufferSize: 0 })
      const lastFrameNum = parseInt(req.body.lastFrameNum || req.query.lastFrameNum || 0)
      if (frameDoc.frameNum <= lastFrameNum) return res.json({ code: 200, hasNewFrame: false, currentNum: frameDoc.frameNum })
      return res.json({ code: 200, hasNewFrame: true, frame: { data: frameDoc.frameData, width: frameDoc.frameWidth, height: frameDoc.frameHeight, num: frameDoc.frameNum, ts: frameDoc.frameTs }, frameNum: frameDoc.frameNum, bufferSize: 1 })
    }

    if (action === 'cancel') {
      if (!targetId) return res.json({ code: 400, message: '缺少 elderId' })
      const user = db.prepare('SELECT id, remoteAssist FROM users WHERE id=?').get(targetId)
      if (!user) return res.json({ code: 404, message: '用户不存在' })
      const ra = user.remoteAssist ? JSON.parse(user.remoteAssist) : {}
      if (ra.status === 'requesting' || ra.status === 'active') {
        // WS 推送取消信号
        sendToUser(targetId, { type: 'assist_cancel', data: { guardianId: from || guardianId } })
        db.prepare('UPDATE users SET remoteAssist=?, updatedAt=? WHERE id=?').run(JSON.stringify({ active: false, status: 'idle', requestFrom: null }), Date.now(), targetId)
      }
      return res.json({ code: 200, message: '已取消' })
    }

    if (action === 'screen_ready') {
      if (!targetId) return res.json({ code: 400, message: '缺少 userId' })
      const user = db.prepare('SELECT id, remoteAssist FROM users WHERE id=?').get(targetId)
      if (!user) return res.json({ code: 404, message: '用户不存在' })
      const ra = user.remoteAssist ? JSON.parse(user.remoteAssist) : {}
      const width = body.width || 720
      const height = body.height || 1280
      db.prepare('UPDATE users SET remoteAssist=?, updatedAt=? WHERE id=?').run(JSON.stringify({ ...ra, screenReady: true, screenWidth: width, screenHeight: height }), Date.now(), targetId)
      return res.json({ code: 200, message: 'Screen ready', width, height })
    }

    if (action === 'end') {
      if (!targetId) return res.json({ code: 400, message: '缺少 userId 或 elderId' })
      const user = db.prepare('SELECT id, remoteAssist FROM users WHERE id=?').get(targetId)
      if (!user) return res.json({ code: 404, message: '用户不存在' })
      const ra = user.remoteAssist ? JSON.parse(user.remoteAssist) : {}
      if (ra.status !== 'active') return res.json({ code: 400, message: '当前没有活跃的协助会话' })

      // v21: 只通知家属被顶掉，不广播给老人自己
      // pushToRoom 会发给老人→触发 onSessionEnded→finish()，导致协助页面闪退
      const raInfo = user.remoteAssist ? JSON.parse(user.remoteAssist) : {}
      if (raInfo.requestFrom) {
        sendToUser(raInfo.requestFrom, { type: 'assist_end', data: { from: from || userId, reason: 'ended', timestamp: Date.now() } })
      }

      // 清理
      db.prepare('DELETE FROM screen_frames WHERE id=?').run(`frame_${targetId}`)
      db.prepare('UPDATE users SET remoteAssist=? WHERE id=?').run(JSON.stringify({ active: false, status: 'idle', requestFrom: null, signals: [] }), targetId)

      return res.json({ code: 200, message: '协助已结束' })
    }

    return res.json({ code: 400, message: `未知操作: ${action}` })
  } catch (err) {
    return res.status(500).json({ code: 500, message: err.message })
  }
})

// ========== request-elder-location ==========
app.post('/request-elder-location', (req, res) => {
  try {
    const elderId = req.body.elderId || req.body.data?.elderId
    if (!elderId) return res.json({ code: 400, success: false, message: 'Missing elderId' })
    const db = getDb()
    const user = db.prepare('SELECT id FROM users WHERE id=?').get(elderId)
    if (!user) return res.json({ code: 404, success: false, message: 'Elder not found' })
    db.prepare('UPDATE users SET pullLocationRequest=?, pullLocationStatus=? WHERE id=?').run(Date.now(), 'pending', elderId)

    // WS 实时推送位置请求给老人端
    const wsPushed = sendToUser(elderId, { type: 'location_request', data: { requestTime: Date.now() } })

    return res.json({ code: 200, success: true, message: 'Location request sent', requestTime: Date.now(), wsPushed })
  } catch (err) {
    return res.status(500).json({ code: 500, success: false, message: err.message })
  }
})

// ========== upload-log ==========
app.post('/upload-log', (req, res) => {
  try {
    const { action, userId, level, tag, logMessage, message, stackTrace, metadata, limit, since } = req.body
    const db = getDb()

    if (action === 'list') {
      if (!userId) return res.json({ code: 400, message: '缺少 userId' })
      let rows
      if (since) {
        rows = db.prepare('SELECT * FROM logs WHERE userId=? AND timestamp>=? ORDER BY timestamp DESC LIMIT ?').all(userId, parseInt(since), Math.min(limit || 50, 200))
      } else {
        rows = db.prepare('SELECT * FROM logs WHERE userId=? ORDER BY timestamp DESC LIMIT ?').all(userId, Math.min(limit || 50, 200))
      }
      return res.json({ code: 200, logs: rows.map(l => ({ id: l.id, level: l.level, tag: l.tag, message: l.message, timestamp: l.timestamp, stackTrace: l.stackTrace })), total: rows.length })
    }

    // 默认: upload
    const msg = logMessage || message
    if (!userId || !msg) return res.json({ code: 400, message: '缺少 userId 或 logMessage' })
    const id = genId()
    db.prepare('INSERT INTO logs (id, userId, level, tag, message, stackTrace, metadata, timestamp, date) VALUES (?,?,?,?,?,?,?,?,?)')
      .run(id, userId, level || 'ERROR', tag || 'App', String(msg).substring(0, 10000), stackTrace ? String(stackTrace).substring(0, 20000) : null, metadata ? JSON.stringify(metadata) : null, Date.now(), new Date().toISOString().split('T')[0])
    return res.json({ code: 200, logId: id, message: '日志已上传' })
  } catch (err) {
    return res.status(500).json({ code: 500, message: err.message })
  }
})

// 404 fallback
app.use((req, res) => {
  res.status(404).json({ code: 404, message: `Route not found: ${req.method} ${req.path}` })
})

// 创建 HTTP server 并挂载 WebSocket
const server = http.createServer(app)

// 初始化 WebSocket（挂载在同一个 HTTP server 上）
initWS(server)

server.listen(PORT, '0.0.0.0', () => {
  console.log(`\n🎉 跌倒宝服务器已启动`)
  console.log(`📡 HTTP端口: ${PORT}`)
  console.log(`🔌 WebSocket: ws://localhost:${PORT}/ws`)
  console.log(`🗄️  数据库: ${__dirname}/diedaobao.db`)
  console.log(`🌐 访问: http://localhost:${PORT}`)
  console.log(`💡 健康检查: GET /health\n`)
})

module.exports = app
