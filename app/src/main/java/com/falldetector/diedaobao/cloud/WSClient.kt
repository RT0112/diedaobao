package com.falldetector.diedaobao.cloud

import android.content.Context
import com.falldetector.diedaobao.config.ServerConfig
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import okhttp3.*
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import com.falldetector.diedaobao.util.AppLogger

/**
 * WebSocket 实时推送客户端（老人端）
 * 
 * 替代 HTTP 轮询，实现：
 * - 实时接收子女端的协助请求（assist_request）
 * - 实时接收位置拉取请求（location_request）
 * - 实时推送跌倒事件给子女端（fall_event）
 * - 实时推送位置更新给子女端（location_update）
 * - 实时接收协助结束/取消信号（assist_end/assist_cancel）
 * 
 * 降级策略：WS连接失败时自动退化为 HTTP 轮询
 */
object WSClient {
    private const val TAG = "WSClient"
    
    // WS_URL已迁移到ServerConfig
    private val WS_URL = ServerConfig.WS_URL
    
    // 重连配置：指数退避 + 无限重连
    private const val INITIAL_RECONNECT_DELAY_MS = 3000L
    private const val MAX_RECONNECT_DELAY_MS = 60000L   // 最长等60秒
    private const val HEARTBEAT_INTERVAL_MS = 25000L
    private const val WATCHDOG_INTERVAL_MS = 120000L    // 2分钟看门狗检查
    
    @Volatile private var webSocket: WebSocket? = null
    @Volatile private var isConnected = false
    private var reconnectAttempts = 0
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null
    private var watchdogJob: Job? = null
    private var appContext: Context? = null  // 保存context供看门狗重连
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // 事件流：供 UI/Service 订阅
    // replay=0 防止旧事件重放（和子女端保持一致）
    private val _events = MutableSharedFlow<WSEvent>(replay = 0, extraBufferCapacity = 20)
    val events: SharedFlow<WSEvent> = _events
    
    private var userId: String? = null
    private var role: String = "elder"
    
    // ========== 连接管理 ==========
    
    /**
     * 连接 WebSocket（需要先 init 获取 userId）
     */
    fun connect(context: Context) {
        val prefs = context.getSharedPreferences("cloudbase", Context.MODE_PRIVATE)
        userId = prefs.getString("user_id", null) ?: return
        
        // 保存context供看门狗重连
        appContext = context.applicationContext
        
        if (isConnected && webSocket != null) {
            Log.i(TAG, "WebSocket 已连接，跳过")
            startWatchdog(context)
            return
        }
        
        Log.i(TAG, "连接 WebSocket: $WS_URL")
        startWatchdog(context)
        
        val client = OkHttpClient.Builder()
            .pingInterval(HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS)
            .build()
        
        val request = Request.Builder()
            .url(WS_URL)
            .build()
        
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket 已连接")
                isConnected = true
                reconnectAttempts = 0
                
                // 认证
                val auth = JSONObject().apply {
                    put("type", "auth")
                    put("data", JSONObject().apply {
                        put("userId", userId)
                        put("role", "elder")
                    })
                }
                webSocket.send(auth.toString())
            }
            
            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }
            
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.w(TAG, "WebSocket 关闭: $code $reason")
                if (this@WSClient.webSocket === webSocket) {
                    isConnected = false
                    this@WSClient.webSocket = null
                }
                webSocket.close(1000, null)
            }
            
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.w(TAG, "WebSocket 已关闭: $code $reason")
                isConnected = false
                scheduleReconnect(context)
            }
            
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket 失败: ${t.message}")
                isConnected = false
                webSocket.cancel()
                scheduleReconnect(context)
            }
        })
    }
    
    /**
     * 断开连接
     */
    fun disconnect() {
        reconnectJob?.cancel()
        heartbeatJob?.cancel()
        watchdogJob?.cancel()
        webSocket?.close(1000, "User disconnected")
        webSocket = null
        isConnected = false
        Log.i(TAG, "WebSocket 已断开")
    }
    
    // ========== 消息处理 ==========
    
    private fun handleMessage(text: String) {
        try {
            val json = JSONObject(text)
            val type = json.optString("type", "")
            val data = json.optJSONObject("data")
            
            when (type) {
                "auth_result" -> {
                    if (json.optBoolean("success", false)) {
                        Log.i(TAG, "WebSocket 认证成功")
                    } else {
                        Log.e(TAG, "WebSocket 认证失败: ${json.optString("message")}")
                    }
                }
                "pong" -> {
                    // 心跳响应，正常
                }
                "assist_request" -> {
                    // 子女端发来协助请求
                    val guardianId = data?.optString("guardianId", "")
                    val guardianName = data?.optString("guardianName", "家属")
                    Log.i(TAG, "收到协助请求: $guardianName($guardianId)")
                    
                    scope.launch {
                        _events.emit(WSEvent.AssistRequest(
                            fromId = guardianId ?: "",
                            guardianId = guardianId ?: "",
                            guardianName = guardianName ?: "家属",
                            requestTime = data?.optLong("requestTime", System.currentTimeMillis()) ?: System.currentTimeMillis()
                        ))
                    }
                }
                "location_request" -> {
                    // 子女端请求位置
                    Log.i(TAG, "收到位置拉取请求")
                    scope.launch {
                        _events.emit(WSEvent.LocationRequest(
                            requestTime = data?.optLong("requestTime", System.currentTimeMillis()) ?: System.currentTimeMillis()
                        ))
                    }
                }
                "assist_cancel" -> {
                    val guardianId = data?.optString("guardianId", "")
                    Log.i(TAG, "收到协助取消: $guardianId")
                    scope.launch {
                        _events.emit(WSEvent.AssistCancel(guardianId ?: ""))
                    }
                }
                "assist_end" -> {
                    Log.i(TAG, "收到协助结束")
                    scope.launch {
                        _events.emit(WSEvent.AssistEnd)
                    }
                }
                "assist_signal" -> {
                    // 子女端发来的触控/导航键信令
                    val signalType = data?.optString("type", "touch") ?: "touch"
                    val touchAction = data?.optString("touchAction", null)
                    val keyCode = data?.optString("keyCode", null)
                    val from = data?.optString("from", "")
                    val duration = if (data?.has("duration") == true) data.optLong("duration", 100L) else null
                    
                    val x = data?.optDouble("x", Double.NaN)?.let { if (it.isNaN()) null else it.toFloat() }
                    val y = data?.optDouble("y", 0.0)?.toFloat() ?: 0f
                    val x1 = data?.optDouble("x1", Double.NaN)?.let { if (it.isNaN()) null else it.toFloat() }
                    val y1 = data?.optDouble("y1", Double.NaN)?.let { if (it.isNaN()) null else it.toFloat() }
                    val x2 = data?.optDouble("x2", Double.NaN)?.let { if (it.isNaN()) null else it.toFloat() }
                    val y2 = data?.optDouble("y2", Double.NaN)?.let { if (it.isNaN()) null else it.toFloat() }
                    
                    Log.i(TAG, "收到信令: type=$signalType touchAction=$touchAction")
                    scope.launch {
                        _events.emit(WSEvent.AssistSignal(
                            signalType = signalType ?: "touch",
                            touchAction = touchAction,
                            keyCode = keyCode,
                            from = from ?: "",
                            x = x,
                            y = y,
                            x1 = x1, y1 = y1,
                            x2 = x2, y2 = y2,
                            duration = duration
                        ))
                    }
                }
                "error" -> {
                    Log.e(TAG, "WS错误: ${json.optString("message")}")
                }
                else -> {
                    Log.d(TAG, "收到未知消息: $type")
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "处理消息失败: ${e.message}")
        }
    }
    
    // ========== 发送消息 ==========
    
    private fun safeSend(json: JSONObject) {
        if (!isConnected || webSocket == null) {
            Log.w(TAG, "WebSocket 未连接，跳过发送: ${json.optString("type")}")
            return
        }
        try {
            webSocket?.send(json.toString())
        } catch (e: Exception) {
            Log.e(TAG, "发送失败: ${e.message}")
        }
    }

    /**
     * v28: 公开JSON发送接口，供 ScreenCaptureService 等发送 screen_ready 等消息
     */
    fun sendJson(json: JSONObject) = safeSend(json)
    
    /**
     * 推送跌倒事件给子女端
     */
    fun pushFallEvent(
        eventId: String,
        timestamp: Long,
        impactG: Float,
        mlScore: Float,
        latitude: Double?,
        longitude: Double?
    ) {
        val json = JSONObject().apply {
            put("type", "fall_event")
            put("data", JSONObject().apply {
                put("eventId", eventId)
                put("timestamp", timestamp)
                put("impactG", impactG)
                put("mlScore", mlScore)
                put("latitude", latitude)
                put("longitude", longitude)
            })
        }
        safeSend(json)
    }
    
    /**
     * 推送位置更新给子女端
     */
    fun pushLocationUpdate(latitude: Double, longitude: Double, accuracy: Float) {
        val json = JSONObject().apply {
            put("type", "location_update")
            put("data", JSONObject().apply {
                put("latitude", latitude)
                put("longitude", longitude)
                put("accuracy", accuracy.toDouble())  // Fix: Float -> Double
                put("timestamp", System.currentTimeMillis())
            })
        }
        safeSend(json)
    }
    
    /**
     * 发送协助响应（接受/拒绝）
     */
    fun pushAssistResponse(accepted: Boolean, guardianId: String?, sessionId: String?) {
        val json = JSONObject().apply {
            put("type", "assist_response")
            put("data", JSONObject().apply {
                put("accepted", accepted)
                if (guardianId != null) put("guardianId", guardianId)
                if (sessionId != null) put("sessionId", sessionId)
            })
        }
        safeSend(json)
    }
    
    /**
     * 发送协助结束信号
     */
    fun pushAssistEnd(reason: String = "ended") {
        val json = JSONObject().apply {
            put("type", "assist_end")
            put("data", JSONObject().apply {
                put("reason", reason)
            })
        }
        safeSend(json)
    }
    
    /**
     * 发送协助信令（触摸/按键）
     */
    fun pushAssistSignal(to: String, signalType: String, x: Int, y: Int, keyCode: String? = null) {
        val json = JSONObject().apply {
            put("type", "assist_signal")
            put("data", JSONObject().apply {
                put("to", to)
                put("type", signalType)
                put("x", x)
                put("y", y)
                if (keyCode != null) put("keyCode", keyCode)
            })
        }
        safeSend(json)
    }
    
    /**
     * 发送屏幕帧（老人端→子女端）- JSON方式（旧）
     */
    fun pushAssistFrame(to: String, frameData: String, width: Int, height: Int, frameNum: Int) {
        val json = JSONObject().apply {
            put("type", "assist_frame")
            put("data", JSONObject().apply {
                put("to", to)
                put("frameData", frameData)
                put("width", width)
                put("height", height)
                put("frameNum", frameNum)
            })
        }
        safeSend(json)
    }

    /**
     * 发送屏幕帧（老人端→子女端）- 二进制直传（无Base64膨胀）
     * 协议: 4字节大端 headerLen + JSON header + JPEG body
     * 服务端直接从二进制中提取 header.to 转发给 Guardian
     */
    fun pushAssistFrameBinary(to: String, jpegBytes: ByteArray, width: Int, height: Int, frameNum: Int) {
        if (!isConnected || webSocket == null) return
        try {
            val header = JSONObject().apply {
                put("to", to)
                put("w", width)
                put("h", height)
                put("fn", frameNum)
            }
            val headerBytes = header.toString().toByteArray(Charsets.UTF_8)
            val buf = Buffer()
            buf.writeInt(headerBytes.size)
            buf.write(headerBytes)
            buf.write(jpegBytes)
            webSocket?.send(buf.readByteString())
        } catch (e: Exception) {
            Log.e(TAG, "二进制帧发送失败: ${e.message}")
        }
    }
    
    /**
     * 发送围栏越界通知
     */
    fun pushGeofenceBreach(fenceName: String) {
        val json = JSONObject().apply {
            put("type", "geofence_breach")
            put("data", JSONObject().apply {
                put("name", fenceName)
                put("timestamp", System.currentTimeMillis())
            })
        }
        safeSend(json)
    }
    
    // ========== 重连机制 ==========
    
    /**
     * 指数退避重连：3s → 6s → 12s → 24s → 48s → 60s(封顶) → 60s → ...
     * 永不放弃，确保WS断线后最终能恢复
     */
    private fun scheduleReconnect(context: Context) {
        reconnectJob?.cancel()
        reconnectAttempts++
        
        // 指数退避计算延迟
        val delay = minOf(
            INITIAL_RECONNECT_DELAY_MS * (1L shl minOf(reconnectAttempts - 1, 4)),  // 3,6,12,24,48
            MAX_RECONNECT_DELAY_MS  // 封顶60s
        )
        
        Log.i(TAG, "尝试重连 WebSocket (第${reconnectAttempts}次, ${delay/1000}s后)")
        
        reconnectJob = scope.launch {
            delay(delay)
            connect(context)
        }
    }
    
    /**
     * 看门狗：定期检查WS连接状态，断线则触发重连
     * 解决长时间运行后WS断线不重连的问题
     */
    private fun startWatchdog(context: Context) {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            while (true) {
                delay(WATCHDOG_INTERVAL_MS)
                if (!isConnected) {
                    Log.w(TAG, "🔄 看门狗: WS断线，触发重连")
                    reconnectAttempts = 0  // 重置计数，用短间隔重新开始
                    connect(context)
                }
            }
        }
    }
    
    // ========== 状态查询 ==========
    
    fun isWSConnected(): Boolean = isConnected
    
    // ========== 事件类型 ==========
    
    sealed class WSEvent {
        data class AssistRequest(
            val fromId: String = "",
            val guardianId: String,
            val guardianName: String,
            val requestTime: Long
        ) : WSEvent()
        
        data class LocationRequest(
            val requestTime: Long
        ) : WSEvent()
        
        data class AssistCancel(
            val guardianId: String
        ) : WSEvent()
        
        object AssistEnd : WSEvent()
        
        /** 触控/导航键信令 */
        data class AssistSignal(
            val signalType: String,     // "touch", "key", "end_session"
            val touchAction: String? = null, // "click", "swipe", "longclick"
            val keyCode: String? = null,     // "home", "back", "recents"
            val from: String = "",
            val x: Float? = null, val y: Float = 0f,
            val x1: Float? = null, val y1: Float? = null,
            val x2: Float? = null, val y2: Float? = null,
            val duration: Long? = null
        ) : WSEvent()
    }
}