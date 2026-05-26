package com.falldetector.diedaobao.assist

import android.content.ComponentName
import android.content.Context
import com.falldetector.diedaobao.util.AppLogger
import android.content.Intent
import android.util.Log
import com.falldetector.diedaobao.cloud.CloudBaseClient
import com.falldetector.diedaobao.cloud.WSClient
import kotlinx.coroutines.*
import org.json.JSONObject

/**
 * 老人端远程协助管理器（v3 WebSocket + HTTP 降级）
 * 
 * 职责：
 * 1. 接收 WS 推送的协助请求（降级 HTTP poll_request 轮询）
 * 2. 接收 WS 推送的触控信令（降级 HTTP poll_signal 轮询）
 * 3. 管理协助生命周期
 * 4. 协调 AccessibilityService + ScreenCaptureService
 */
object RemoteAssistManager {

    private const val TAG = "RemoteAssistManager"
    private const val POLL_INTERVAL_MS = 5000L  // 保留常量（兼容性）
    private const val SIGNAL_POLL_INTERVAL_MS = 500L // 信号轮询频率，500ms 保证低延迟
    private const val HEALTH_CHECK_INTERVAL_MS = 30000L // v19.7.5: 健康检查间隔30s
    private const val DISCONNECT_VERIFY_DELAY_MS = 3000L // v28: 断连验证延迟

    private var isPolling = false
    private var pollJob: Job? = null
    private var healthCheckJob: Job? = null // v19.7.5: 健康检查协程
    private var isSignalPolling = false
    private var signalPollJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var appContext: Context? = null // v19.7.5: 保存context用于健康检查重启

    // 请求回调 — 支持多个监听者（HomeFragment + FallDetectionService 都可以注册）
    private val assistRequestListeners = mutableListOf<(AssistRequest) -> Unit>()

    // 请求去重：防止同一个请求被 HomeFragment + FallDetectionService 同时触发导致双弹窗
    private var lastNotifiedRequestId: String = ""

    /**
     * 注册协助请求监听器（可注册多个，不会被覆盖）
     */
    fun addAssistRequestListener(listener: (AssistRequest) -> Unit) {
        if (!assistRequestListeners.contains(listener)) {
            assistRequestListeners.add(listener)
        }
    }

    /**
     * 移除协助请求监听器
     */
    fun removeAssistRequestListener(listener: (AssistRequest) -> Unit) {
        assistRequestListeners.remove(listener)
    }

    // 兼容旧代码：onAssistRequest 仍可赋值，会同时添加到监听列表
    var onAssistRequest: ((AssistRequest) -> Unit)?
        get() = assistRequestListeners.firstOrNull()
        set(value) {
            value?.let { addAssistRequestListener(it) }
        }

    /**
     * 会话被对方结束的回调（子女端主动断开时触发）
     */
    var onSessionEnded: (() -> Unit)? = null

    /**
     * 协助请求数据
     */
    data class AssistRequest(
        val fromId: String,
        val fromName: String,
        val requestTime: Long,
        val remainingSeconds: Int
    )

    /**
     * 轮询响应数据
     */
    data class PollResponse(
        val hasRequest: Boolean,
        val request: AssistRequest? = null
    )

    /**
     * 开始轮询（老人端启动时调用）
     *
     * v19 修复：
     * - 检查 pollJob 是否真的活着（协程可能已取消但 isPolling 未重置）
     * - 通知所有注册的监听器，不只是一个
     */
    // ==================== WS 事件监听 ====================

    private var wsEventListenerJob: Job? = null

    private fun startWSEventListener(context: Context) {
        wsEventListenerJob?.cancel()
        wsEventListenerJob = scope.launch {
            WSClient.events.collect { event ->
                when (event) {
                    is WSClient.WSEvent.AssistRequest -> {
                        // WS 推送协助请求（替代 poll_request 轮询）
                        // v26: 用 guardianId+requestTime 去重，不要用 currentTimeMillis（每秒不同永远匹配不上）
                        val requestId = "${event.guardianId}_${event.requestTime}"
                        if (requestId == lastNotifiedRequestId) return@collect
                        lastNotifiedRequestId = requestId
                        Log.i(TAG, "[WS] 收到协助请求: from=${event.guardianName}")
                        withContext(Dispatchers.Main) {
                            for (listener in assistRequestListeners.toList()) {
                                try {
                                    listener.invoke(AssistRequest(
                                        fromId = event.guardianId,
                                        fromName = event.guardianName,
                                        requestTime = event.requestTime,
                                        remainingSeconds = 60
                                    ))
                                } catch (e: Exception) {
                                    AppLogger.e(TAG, "WS监听器异常: ${e.message}")
                                }
                            }
                        }
                    }

                    is WSClient.WSEvent.LocationRequest -> {
                        // WS 推送位置请求 → 触发位置上传
                        Log.i(TAG, "[WS] 收到位置请求推送，立即上传位置")
                        withContext(Dispatchers.IO) {
                            try {
                                val ctx = appContext ?: return@withContext
                                // 通知 HomeFragment 上传位置
                                for (listener in assistRequestListeners.toList()) {
                                    // 位置请求复用监听器不太好，直接上传
                                }
                                // 直接通过 CloudBaseClient 上传位置
                                uploadLocationOnRequest(ctx)
                            } catch (e: Exception) {
                                AppLogger.e(TAG, "WS位置请求上传失败: ${e.message}")
                            }
                        }
                    }

                    is WSClient.WSEvent.AssistSignal -> {
                        // WS 推送触控信令（替代 poll_signal 轮询）
                        dispatchSignal(TouchSignal(
                            type = event.signalType,
                            touchAction = event.touchAction,
                            keyCode = event.keyCode,
                            x = event.x,
                            y = event.y,
                            x1 = event.x1, y1 = event.y1,
                            x2 = event.x2, y2 = event.y2,
                            duration = event.duration,
                            from = event.from,
                            timestamp = System.currentTimeMillis()
                        ))
                    }

                    is WSClient.WSEvent.AssistEnd -> {
                        Log.i(TAG, "[WS] 收到协助结束信号")
                        stopSignalPolling()
                        // v28: 延迟验证后再回调，防止 WS 抖动误触发
                        withContext(Dispatchers.IO) {
                            delay(DISCONNECT_VERIFY_DELAY_MS)
                            val status = checkAssistStatus(appContext ?: return@withContext)
                            Log.i(TAG, "[WS] 断连验证: status=$status")
                            if (status == "active" || status == "assisting") {
                                // 仍在协助中，WS 抖动误触发，忽略
                                Log.w(TAG, "[WS] AssistEnd信号但云端状态仍为active，忽略（WS抖动）")
                                return@withContext
                            }
                            withContext(Dispatchers.Main) {
                                onSessionEnded?.invoke()
                                onSessionEnded = null
                            }
                        }
                    }

                    is WSClient.WSEvent.AssistCancel -> {
                        Log.i(TAG, "[WS] 收到协助取消信号")
                        stopSignalPolling()
                        // v29: 通知Activity关闭请求页面
                        withContext(Dispatchers.Main) {
                            onSessionEnded?.invoke()
                        }
                    }
                }
            }
        }
    }

    /**
     * v28: 启动轮询（WS-only，砍掉HTTP poll_request）
     * WS 已在 FallDetectionApp.onCreate() 中连接
     * 协助请求通过 WS 推送接收，不再 HTTP 轮询
     */
    fun startPolling(context: Context) {
        val jobAlive = pollJob?.isActive == true
        if (isPolling && jobAlive) return
        if (isPolling && !jobAlive) {
            Log.w(TAG, "轮询标志为true但Job已死，强制重启")
            isPolling = false
        }

        startWSEventListener(context)

        isPolling = true
        appContext = context.applicationContext
        AppLogger.i(TAG, "远程协助轮询已启动（WS-only模式）")

        // v28: 不再启动 HTTP poll_request 协程，WS 推送已完全替代
        pollJob = scope.launch {
            delay(Long.MAX_VALUE) // 保持协程活跃以便健康检查判定
        }

        startHealthCheck()
    }

    /**
     * 停止轮询（同时停止信号轮询和健康检查）
     */
    fun stopPolling() {
        isPolling = false
        pollJob?.cancel()
        pollJob = null
        healthCheckJob?.cancel()
        healthCheckJob = null
        stopSignalPolling()
        Log.i(TAG, "远程协助轮询已停止")
    }

    /**
     * v19.7.5: 健康检查 — 每30s检测 pollJob 是否活着，死了自动重启
     *
     * 根因：APK更新/系统回收后旧进程的轮询协程可能已死，
     * 但 FallDetectionService 可能仍在运行（onDestroy未调用），
     * 导致 isPolling=false 但无人重启轮询。
     */
    private fun startHealthCheck() {
        healthCheckJob?.cancel()
        healthCheckJob = scope.launch {
            while (isActive) {
                delay(HEALTH_CHECK_INTERVAL_MS)
                val jobAlive = pollJob?.isActive == true
                if (!jobAlive && isPolling) {
                    // isPolling=true 但 Job 死了 → 异常状态，重启
                    AppLogger.w(TAG, "健康检查：轮询Job已死但isPolling=true，重启轮询")
                    isPolling = false
                    val ctx = appContext ?: break
                    startPolling(ctx)
                } else if (!jobAlive && !isPolling) {
                    // 轮询完全停了，但可能是用户主动停的，检查是否有监听器
                    // 如果有监听器说明应该还在工作，重启
                    if (assistRequestListeners.isNotEmpty()) {
                        AppLogger.w(TAG, "健康检查：轮询已停但有${assistRequestListeners.size}个监听器，重启轮询")
                        val ctx = appContext ?: break
                        startPolling(ctx)
                    }
                }
            }
        }
    }

    /**
     * v19.7.5: 确保（ensure）轮询在运行 — 安全的启动方法，可随时调用
     * 适用场景：App回到前台、服务重启后、AlarmManager触发
     */
    fun ensurePolling(context: Context) {
        val jobAlive = pollJob?.isActive == true
        if (isPolling && jobAlive) return
        AppLogger.i(TAG, "ensurePolling：轮询未运行，启动（isPolling=$isPolling, jobAlive=$jobAlive）")
        startPolling(context)
    }

    /**
     * v28: 信号轮询（WS-only，砍掉HTTP poll_signal）
     * 触控信号通过 WS AssistSignal 事件接收，在 startWSEventListener 中分发。
     * 此方法保留以兼容现有调用方，但不再启动 HTTP 轮询。
     */
    fun startSignalPolling(context: Context) {
        if (isSignalPolling) return
        isSignalPolling = true
        Log.i(TAG, "信号轮询已启动（WS-only模式，无HTTP轮询）")

        // v28: 不再启动 HTTP poll_signal 协程，WS 已完全替代
        signalPollJob = scope.launch {
            delay(Long.MAX_VALUE) // 保持协程活跃
        }
    }

    /**
     * 停止信号轮询
     */
    fun stopSignalPolling() {
        isSignalPolling = false
        signalPollJob?.cancel()
        signalPollJob = null
        Log.i(TAG, "信号轮询已停止")
    }

    /**
     * v28: 检查云端协助状态（公开，供断连验证使用）
     */
    suspend fun checkAssistStatus(context: Context): String {
        val userId = getUserId(context) ?: return "unknown"
        val body = JSONObject().apply {
            put("action", "check_status")
            put("elderId", userId)
        }
        val response = CloudBaseClient.callFunctionRaw("remote-assist", body)
            ?: return "unknown"
        return response.optString("status", "unknown")
    }

    /**
     * 响应协助请求（同意/拒绝）
     */
    suspend fun respondToRequest(context: Context, accepted: Boolean): RespondResult {
        val userId = getUserId(context) ?: return RespondResult(false, "未注册")
        
        return try {
            val body = JSONObject().apply {
                put("action", "respond")
                put("userId", userId)
                put("accepted", accepted)
            }
            
            val response = CloudBaseClient.callFunctionRaw("remote-assist", body)
            if (response != null) {
                val code = response.optInt("code", 0)
                if (code == 200) {
                    RespondResult(
                        success = true,
                        message = response.optString("message"),
                        sessionId = response.optString("sessionId", null),
                        guardianId = response.optString("guardianId", null)
                    )
                } else {
                    RespondResult(false, response.optString("message", "响应失败"))
                }
            } else {
                RespondResult(false, "网络错误")
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "响应请求异常: ${e.message}")
            RespondResult(false, "响应异常: ${e.message}")
        }
    }

    /**
     * 结束协助
     */
    suspend fun endAssist(context: Context): Boolean {
        val userId = getUserId(context) ?: return false
        
        return try {
            val body = JSONObject().apply {
                put("action", "end")
                put("userId", userId)
            }
            val response = CloudBaseClient.callFunctionRaw("remote-assist", body)
            response != null && response.optInt("code", 0) == 200
        } catch (e: Exception) {
            AppLogger.e(TAG, "结束协助异常: ${e.message}")
            false
        }
    }

    /**
     * 检查 AccessibilityService 是否已启用
     * 只检查系统设置，不依赖 instance（instance 由系统异步绑定，可能滞后）
     */
    fun isAccessibilityEnabled(context: Context): Boolean {
        return RemoteAssistService.isAccessibilityEnabled(context)
    }

    /**
     * 跳转到无障碍设置页
     * HyperOS/MIUI 部分版本对 ACCESSIBILITY_DETAILS_SETTINGS 支持不完整，
     * 直接打开通用列表更稳定，用户手动找到跌倒宝即可。
     */
    fun openAccessibilitySettings(context: Context): Intent {
        Log.i(TAG, "openAccessibilitySettings: 打开通用无障碍列表")
        return android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    // ==================== 内部方法 ====================

    // v28: pollForRequest 已砍掉（HTTP poll_request 被 WS AssistRequest 完全替代）

    // ==================== 信号轮询 ====================

    /**
     * 信号数据
     */
    data class TouchSignal(
        val type: String,       // "touch", "key", or "end_session"
        val touchAction: String?, // "click", "swipe", "longclick"
        val keyCode: String?,     // v19.7.3: "home", "back", "recents"
        val x: Float?,
        val y: Float,
        val x1: Float?, val y1: Float?,
        val x2: Float?, val y2: Float?,
        val duration: Long?,
        val from: String?,
        val timestamp: Long
    )

    // v28: pollForSignals 已砍掉（HTTP poll_signal 被 WS AssistSignal 完全替代）

    /**
     * 分发信号到 RemoteAssistService 执行
     */
    private fun dispatchSignal(signal: TouchSignal) {
        val service = RemoteAssistService.instance
        if (service == null) {
            AppLogger.w(TAG, "信号到达但 AccessibilityService 未连接，丢弃: ${signal.type}")
            return
        }

        when {
            signal.type == "end_session" -> {
                AppLogger.i(TAG, "收到 end_session 信号，停止信号轮询")
                stopSignalPolling()
                // 不调 endAssist（子女端已调过，云端已清理）
                // 通知 UI 层关闭协助界面
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    onSessionEnded?.invoke()
                }
                return
            }
            signal.type == "touch" -> {
                val screenSize = service.getScreenSize()
                // v19.7.2: 坐标映射 — 子女端发的是视频流坐标（如360x765），
                // 需要映射到老人端屏幕坐标（如1440x3062）
                val streamW = ScreenCaptureService.streamWidth
                val streamH = ScreenCaptureService.streamHeight
                val scaleX = if (streamW > 0) screenSize.x / streamW else 1f
                val scaleY = if (streamH > 0) screenSize.y / streamH else 1f
                AppLogger.i(TAG, "坐标映射: stream=${streamW}x${streamH} screen=${screenSize.x}x${screenSize.y} scale=${scaleX}x${scaleY}")
                when (signal.touchAction) {
                    "click" -> {
                        val duration = signal.duration ?: 100L
                        val mappedX = (signal.x ?: 0f) * scaleX
                        val mappedY = (signal.y ?: 0f) * scaleY
                        if (duration >= 500) {
                            AppLogger.i(TAG, "执行长按: (${signal.x},${signal.y})→(${mappedX},${mappedY}) duration=${duration}ms")
                        } else {
                            AppLogger.i(TAG, "执行点击: (${signal.x},${signal.y})→(${mappedX},${mappedY})")
                        }
                        val result = service.executeClick(mappedX, mappedY, duration)
                        if (!result) AppLogger.e(TAG, "executeClick 返回 false")
                    }
                    "swipe" -> {
                        val mappedX1 = (signal.x1 ?: 0f) * scaleX
                        val mappedY1 = (signal.y1 ?: 0f) * scaleY
                        val mappedX2 = (signal.x2 ?: 0f) * scaleX
                        val mappedY2 = (signal.y2 ?: 0f) * scaleY
                        AppLogger.i(TAG, "执行滑动: (${signal.x1},${signal.y1})→(${signal.x2},${signal.y2}) 映射→(${mappedX1},${mappedY1})→(${mappedX2},${mappedY2})")
                        val result = service.executeSwipe(
                            mappedX1, mappedY1,
                            mappedX2, mappedY2,
                            signal.duration ?: 300L
                        )
                        if (!result) AppLogger.e(TAG, "executeSwipe 返回 false")
                    }
                    "doubleclick" -> {
                        val mappedX = (signal.x ?: 0f) * scaleX
                        val mappedY = (signal.y ?: 0f) * scaleY
                        AppLogger.i(TAG, "执行双击: (${signal.x},${signal.y})→(${mappedX},${mappedY})")
                        val result = service.executeDoubleClick(mappedX, mappedY)
                        if (!result) AppLogger.e(TAG, "executeDoubleClick 返回 false")
                    }
                    else -> AppLogger.w(TAG, "未知 touchAction: ${signal.touchAction}")
                }
            }
            signal.type == "key" -> {
                // v19.7.3: 导航键支持（Home/Back/Recents）
                val keyCode = signal.keyCode ?: ""
                AppLogger.i(TAG, "执行导航键: $keyCode")
                val result = service.executeGlobalAction(keyCode)
                if (!result) AppLogger.e(TAG, "executeGlobalAction($keyCode) 返回 false")
            }
            else -> AppLogger.w(TAG, "未知信号类型: ${signal.type}")
        }
    }

    private fun getUserId(context: Context): String? {
        val prefs = context.getSharedPreferences("cloudbase", Context.MODE_PRIVATE)
        return prefs.getString("user_id", null)
    }

    /**
     * v21: WS收到位置请求后立即上传位置
     * 通过 FusedLocationProvider 获取当前位置并上传到服务器
     * 同时通过 WS 推送位置更新给子女端
     */
    private suspend fun uploadLocationOnRequest(context: Context) {
        try {
            // 通过 CloudBaseClient 上传位置（复用已有逻辑）
            val result = CloudBaseClient.uploadLocationNow(context)
            if (result) {
                Log.i(TAG, "WS位置请求：位置已上传")
            } else {
                Log.w(TAG, "WS位置请求：位置上传失败")
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "uploadLocationOnRequest异常: ${e.message}")
        }
    }

    data class RespondResult(
        val success: Boolean,
        val message: String,
        val sessionId: String? = null,
        val guardianId: String? = null
    )
}
