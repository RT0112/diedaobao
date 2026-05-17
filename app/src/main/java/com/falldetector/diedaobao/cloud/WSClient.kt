package com.falldetector.diedaobao.cloud

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import okhttp3.*
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
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
    
    // WebSocket 服务器地址（K70 本地）
    // 注意：老人端跑在 K70 上，连 localhost:3000
    private const val WS_URL = "ws://localhost:3000/ws"
    
    // 重连配置
    private const val RECONNECT_DELAY_MS = 3000L
    private const val MAX_RECONNECT_ATTEMPTS = 5
    private const val HEARTBEAT_INTERVAL_MS = 25000L
    
    private var webSocket: WebSocket? = null
    private var isConnected = false
    private var reconnectAttempts = 0
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // 事件流：供 UI/Service 订阅
    private val _events = MutableSharedFlow<WSEvent>(replay = 1, extraBufferCapacity = 20)
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
        
        if (isConnected && webSocket != null) {
            Log.i(TAG, "WebSocket 已连接，跳过")
            return
        }
        
        Log.i(TAG, "连接 WebSocket: $WS_URL")
        
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
                isConnected = false
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
                put("accuracy", accuracy)
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
     * 发送屏幕帧（老人端→子女端）
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
    
    private fun scheduleReconnect(context: Context) {
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            Log.w(TAG, "达到最大重连次数($MAX_RECONNECT_ATTEMPTS)，停止重连，切换HTTP降级")
            return
        }
        
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(RECONNECT_DELAY_MS)
            reconnectAttempts++
            Log.i(TAG, "尝试重连 WebSocket ($reconnectAttempts/$MAX_RECONNECT_ATTEMPTS)")
            connect(context)
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