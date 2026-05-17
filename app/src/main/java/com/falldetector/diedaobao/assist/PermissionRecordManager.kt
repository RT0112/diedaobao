package com.falldetector.diedaobao.assist

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityWindowInfo
import com.falldetector.diedaobao.util.AppLogger
import org.json.JSONArray
import org.json.JSONObject

/**
 * 权限弹窗自动处理管理器 v18 — 逻辑理清
 *
 * 核心原则：录制和回放不会在同一个协助会话里同时发生
 *
 * 流程：
 *   首次：允许协助 → 录制模式 → 用户操作弹窗 → 弹窗关闭 → 保存录制 → 结束
 *   再次：允许协助 → 回放模式 → 检测弹窗 → 按录制自动点击 → 弹窗关闭 → 结束
 *
 * v18 vs v17 关键改动：
 * 1. 加 expectingReplay 标志：只有 Activity 主动请求回放时才触发，录制完不会误触
 * 2. RecordedStep 加 timestamp：回放按真实间隔执行
 * 3. 录制完成 → 停止，不触发回放
 */
class PermissionRecordManager(private val service: RemoteAssistService) {

    companion object {
        private const val TAG = "PermissionRecord_v18"
        private const val PREFS_NAME = "permission_record"
        private const val KEY_RECORDED_STEPS = "recorded_steps_v9"
        private const val KEY_RECORDING_META = "recording_meta_v9"
        private const val KEY_RECORDING_VERSION = "recording_version"
        private const val CURRENT_RECORDING_VERSION = 6  // v18: 加 timestamp
        private const val MAX_REPLAY_STEPS = 10
        private const val SYSTEM_UI_PKG = "com.android.systemui"
    }

    // ==================== Data Classes ====================

    data class RecordedStep(
        val centerX: Int,
        val centerY: Int,
        val stepIndex: Int,
        val timestamp: Long = 0,  // v18: 相对于录制开始的时间(ms)
        val recordingVersion: Int = CURRENT_RECORDING_VERSION
    )

    data class Recording(
        val steps: List<RecordedStep>,
        val deviceModel: String,
        val androidVersion: String,
        val recordedAt: Long,
        val recordingVersion: Int = CURRENT_RECORDING_VERSION
    )

    enum class HandleResult {
        NOT_HANDLED,
        REPLAY_SUCCESS,
        REPLAY_FAILED
    }

    // ==================== State ====================

    private val prefs: SharedPreferences = service.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())

    private val touchOverlay by lazy {
        TouchRecordOverlay(service).apply {
            onStepRecorded = { x, y ->
                recordTouchCoord(x.toInt(), y.toInt())
            }
        }
    }

    // Recording state
    private var isRecording = false
    private var recordedSteps = mutableListOf<RecordedStep>()
    private var recordingStartTime = 0L

    // Replay state
    private var isReplaying = false
    private var currentReplayStep = 0
    private var loadedRecording: Recording? = null

    // v18: 回放必须由 Activity 主动请求，不能被 onAccessibilityEvent 自动触发
    // 录制完成后 isRecording 变 false，但 recordingJustFinished 为 true，
    // 防止 onAccessibilityEvent 在弹窗还没完全消失时误触回放
    private var expectingReplay = false

    // v17.2: 回放冷却期 — 防止回放失败后立即重新触发（死循环）
    // v19.7.1: 30s→5s，30秒太长导致第二次请求被冷却期挡住
    private var lastReplayFinishTime = 0L
    private val REPLAY_COOLDOWN_MS = 5_000L

    // Callbacks
    var onRecordingComplete: ((Int) -> Unit)? = null
    var onReplayComplete: ((HandleResult) -> Unit)? = null

    // ==================== Dialog Detection ====================

    /**
     * 检测是否有系统屏幕共享弹窗
     * 只看 com.android.systemui 的窗口，绝不误判 App 自身
     */
    @Suppress("DEPRECATION")
    fun hasPermissionDialog(): Boolean {
        for (window in service.windows) {
            val root = window.root ?: continue
            val wPkg = root.packageName?.toString()
            if (wPkg != SYSTEM_UI_PKG) continue
            if (root.childCount > 0) {
                return true
            }
        }
        return false
    }

    // ==================== Recording ====================

    fun startRecording() {
        recordedSteps.clear()
        isRecording = true
        recordingStartTime = System.currentTimeMillis()
        expectingReplay = false  // 录制模式，不回放
        touchOverlay.startRecording()
        AppLogger.i(TAG, "🎬 录制启动")
        handler.post {
            try {
                android.widget.Toast.makeText(
                    service, "🎬 录制中，请操作弹窗", android.widget.Toast.LENGTH_LONG
                ).show()
            } catch (_: Exception) {}
        }
    }

    fun stopRecording() {
        if (!isRecording) {
            AppLogger.w(TAG, "stopRecording: 未在录制状态")
            return
        }
        isRecording = false
        touchOverlay.stopRecording()
        val count = recordedSteps.size
        AppLogger.i(TAG, "🎬 stopRecording: 捕获 $count 步")

        if (count > 0) {
            saveRecording()
            // 验证保存
            val saved = prefs.getString(KEY_RECORDED_STEPS, null)
            AppLogger.i(TAG, "🎬 保存验证: SP中数据=${saved != null}, 长度=${saved?.length ?: 0}")
            if (saved == null) {
                AppLogger.e(TAG, "🎬 ❌ 保存失败！SP中无数据！")
            }
            handler.post {
                try {
                    android.widget.Toast.makeText(
                        service, "✅ 录制完成！共 $count 步，下次自动完成",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                } catch (_: Exception) {}
            }
        } else {
            AppLogger.w(TAG, "🎬 录制结束，未捕获操作")
            handler.post {
                try {
                    android.widget.Toast.makeText(
                        service, "⚠️ 未录制到操作，请重试",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                } catch (_: Exception) {}
            }
        }
        onRecordingComplete?.invoke(count)
    }

    fun isCurrentlyRecording(): Boolean = isRecording

    /** 录制开始后前 1 秒忽略触摸（跳过"好的，开始"按钮误录） */
    private val RECORDING_IGNORE_MS = 1000L

    private fun recordTouchCoord(x: Int, y: Int) {
        val elapsed = System.currentTimeMillis() - recordingStartTime
        if (elapsed < RECORDING_IGNORE_MS) {
            AppLogger.i(TAG, "🎬 忽略触摸(启动后 ${elapsed}ms): ($x, $y)")
            return
        }

        val step = RecordedStep(
            centerX = x,
            centerY = y,
            stepIndex = recordedSteps.size,
            timestamp = elapsed,  // v18: 记录相对时间
            recordingVersion = CURRENT_RECORDING_VERSION
        )
        recordedSteps.add(step)
        AppLogger.i(TAG, "🎬 录制坐标 #${step.stepIndex}: ($x, $y) t=${elapsed}ms")
    }

    // ==================== Replay ====================

    /**
     * v18: 设置回放期望标志
     *
     * 只有 Activity 在 startPermissionFlow() 中发现有录制数据时才调用。
     * 录制完成后不会设此标志，所以 onAccessibilityEvent 不会误触回放。
     */
    fun setExpectingReplay(expect: Boolean) {
        expectingReplay = expect
        // v19.7.1: 新会话请求回放时重置冷却期，否则上次回放的冷却会挡住本次
        if (expect) {
            lastReplayFinishTime = 0L
        }
        AppLogger.i(TAG, "setExpectingReplay: $expect")
    }

    fun isExpectingReplay(): Boolean = expectingReplay

    /**
     * 自动处理权限弹窗
     *
     * v18: 只有 expectingReplay=true 时才触发
     */
    fun tryAutoHandle() {
        if (!expectingReplay) {
            AppLogger.i(TAG, "tryAutoHandle: 非回放模式(expectingReplay=false)，跳过")
            return
        }
        if (isRecording) {
            AppLogger.i(TAG, "tryAutoHandle: 录制中，跳过")
            return
        }
        if (isReplaying) {
            AppLogger.i(TAG, "tryAutoHandle: 正在回放，跳过")
            return
        }

        // 冷却期
        val elapsed = System.currentTimeMillis() - lastReplayFinishTime
        if (lastReplayFinishTime > 0 && elapsed < REPLAY_COOLDOWN_MS) {
            AppLogger.i(TAG, "tryAutoHandle: 冷却中（${elapsed}ms/${REPLAY_COOLDOWN_MS}ms），跳过")
            return
        }

        if (!hasRecording()) {
            AppLogger.i(TAG, "tryAutoHandle: 无录制数据")
            return
        }

        AppLogger.i(TAG, "tryAutoHandle: 启动回放")
        startReplay()
    }

    private var replayRetryCount = 0  // 当前步骤重试次数
    private val MAX_REPLAY_RETRIES = 2   // 每步最多重试2次（总共3次）
    private var lastStepRetryCount = 0   // v19.7.3: 回放完成后重试最后一步的次数（独立计数，不会被步骤推进重置）
    private val MAX_LAST_STEP_RETRIES = 3  // 最多重试3次
    private var replayStartTime = 0L     // v19.7.3: 回放开始时间，用于总超时
    private val REPLAY_TOTAL_TIMEOUT = 30_000L  // 回放总超时30秒

    private fun startReplay() {
        loadedRecording = loadRecording()
        if (loadedRecording == null || loadedRecording!!.steps.isEmpty()) {
            AppLogger.w(TAG, "录制数据为空")
            onReplayFailed("录制数据为空")
            return
        }

        isReplaying = true
        currentReplayStep = 0
        replayRetryCount = 0
        lastStepRetryCount = 0  // v19.7.3
        replayStartTime = System.currentTimeMillis()  // v19.7.3

        // 诊断日志
        val metrics = service.resources.displayMetrics
        AppLogger.i(TAG, "▶️ 开始回放，共 ${loadedRecording!!.steps.size} 步")
        AppLogger.i(TAG, "📱 屏幕分辨率: ${metrics.widthPixels}x${metrics.heightPixels}, density=${metrics.density}")
        loadedRecording!!.steps.forEachIndexed { i, s ->
            AppLogger.i(TAG, "  步骤#$i: (${s.centerX}, ${s.centerY}) t=${s.timestamp}ms")
        }

        handler.post {
            try {
                android.widget.Toast.makeText(
                    service, "▶️ 自动授权中 ${loadedRecording!!.steps.size} 步...",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            } catch (_: Exception) {}
        }

        // v19.7.1: 第一步延迟增加到 3 秒（MIUI/HyperOS 弹窗动画较慢，1.5s不够）
        handler.postDelayed({ replayNextStep() }, 3000L)
    }

    private fun replayNextStep() {
        if (!isReplaying) return

        val recording = loadedRecording ?: run {
            onReplayFailed("录制数据丢失")
            return
        }

        // v19.7.3: 总超时检查
        if (System.currentTimeMillis() - replayStartTime > REPLAY_TOTAL_TIMEOUT) {
            onReplayFailed("回放超时(${REPLAY_TOTAL_TIMEOUT/1000}s)")
            return
        }

        // 所有步骤已回放 → 等权限授权完成
        if (currentReplayStep >= recording.steps.size) {
            handler.postDelayed({
                if (!isReplaying) return@postDelayed
                // v19.7.3: 总超时二次检查
                if (System.currentTimeMillis() - replayStartTime > REPLAY_TOTAL_TIMEOUT) {
                    onReplayFailed("回放超时")
                    return@postDelayed
                }
                val cloudPrefs = service.getSharedPreferences("cloudbase", Context.MODE_PRIVATE)
                val mpGranted = cloudPrefs.getBoolean("mp_granted", false)
                if (mpGranted) {
                    onReplaySuccess()
                } else {
                    // v19.7.3: 用 lastStepRetryCount 替代 replayRetryCount（不会被步骤推进重置）
                    if (lastStepRetryCount < MAX_LAST_STEP_RETRIES) {
                        lastStepRetryCount++
                        currentReplayStep = recording.steps.size - 1  // 回到最后一步
                        AppLogger.w(TAG, "回放完成但权限未授权，重试最后一步 $lastStepRetryCount/$MAX_LAST_STEP_RETRIES")
                        handler.postDelayed({ replayNextStep() }, 1500L)
                    } else {
                        onReplayFailed("回放完成但权限未授权(已重试${MAX_LAST_STEP_RETRIES}次)")
                    }
                }
            }, 2000L)
            return
        }

        // 权限已拿到 → 成功（不需要继续回放剩余步骤）
        val cloudPrefs = service.getSharedPreferences("cloudbase", Context.MODE_PRIVATE)
        if (cloudPrefs.getBoolean("mp_granted", false)) {
            onReplaySuccess()
            return
        }

        val step = recording.steps[currentReplayStep]
        AppLogger.i(TAG, "▶️ 回放步骤 #${step.stepIndex}: (${step.centerX}, ${step.centerY}) 重试=$replayRetryCount")

        if (step.centerX <= 0 || step.centerY <= 0) {
            onReplayFailed("第${step.stepIndex}步坐标无效")
            return
        }

        val clicked = dispatchGestureClick(step.centerX, step.centerY)
        if (!clicked) {
            // dispatchGesture 本身失败，重试
            if (replayRetryCount < MAX_REPLAY_RETRIES) {
                replayRetryCount++
                AppLogger.w(TAG, "第${step.stepIndex}步 dispatchGesture 失败，重试 $replayRetryCount/$MAX_REPLAY_RETRIES")
                handler.postDelayed({ replayNextStep() }, 1500L)
                return
            }
            onReplayFailed("第${step.stepIndex}步 dispatchGesture 失败")
            return
        }

        currentReplayStep++
        replayRetryCount = 0  // 成功进入下一步，重置重试计数

        // v19.7.1: 步骤间延迟增加 — 最低 800ms（原 500ms 太快，弹窗动画没完就点下一步）
        val delayMs = if (currentReplayStep < recording.steps.size) {
            val nextTimestamp = recording.steps[currentReplayStep].timestamp
            val curTimestamp = step.timestamp
            val interval = nextTimestamp - curTimestamp
            // 至少 800ms，最多 5 秒
            interval.coerceIn(800L, 5000L)
        } else {
            2000L  // 最后一步后等 2 秒
        }
        AppLogger.i(TAG, "▶️ 下一步延迟 ${delayMs}ms")
        handler.postDelayed({ replayNextStep() }, delayMs)
    }

    private fun onReplaySuccess() {
        cleanupReplayState()
        lastReplayFinishTime = System.currentTimeMillis()
        AppLogger.i(TAG, "✅ 自动授权成功")
        handler.post {
            try {
                android.widget.Toast.makeText(
                    service, "✅ 屏幕共享权限已自动授权",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            } catch (_: Exception) {}
        }
        sendGrantedBroadcast()
        onReplayComplete?.invoke(HandleResult.REPLAY_SUCCESS)
    }

    private fun onReplayFailed(reason: String) {
        cleanupReplayState()
        lastReplayFinishTime = System.currentTimeMillis()
        AppLogger.e(TAG, "❌ 回放失败: $reason")
        handler.post {
            try {
                android.widget.Toast.makeText(
                    service, "⚠️ 自动授权失败: $reason",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            } catch (_: Exception) {}
        }
        onReplayComplete?.invoke(HandleResult.REPLAY_FAILED)
    }

    private fun cleanupReplayState() {
        isReplaying = false
        expectingReplay = false  // v18: 回放结束，重置标志
        handler.removeCallbacksAndMessages(null)
    }

    private fun sendGrantedBroadcast() {
        try {
            val intent = android.content.Intent(RemoteAssistService.ACTION_AUTO_PERMISSION_GRANTED)
            intent.setPackage(service.packageName)
            intent.addFlags(android.content.Intent.FLAG_RECEIVER_REPLACE_PENDING)
            service.sendBroadcast(intent)
        } catch (e: Exception) {
            AppLogger.e(TAG, "广播失败: ${e.message}")
        }
    }

    // ==================== Click Execution ====================

    private fun dispatchGestureClick(x: Int, y: Int): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            AppLogger.w(TAG, "dispatchGesture 需要 API 26+")
            return false
        }

        return try {
            val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
                .build()

            val dispatched = service.dispatchGesture(gesture, null, null)
            AppLogger.i(TAG, "dispatchGesture x=$x y=$y dispatched=$dispatched")
            dispatched
        } catch (e: Exception) {
            AppLogger.e(TAG, "dispatchGesture 异常: ${e.message}")
            false
        }
    }

    // ==================== Persistence ====================

    private fun saveRecording() {
        try {
            val jsonArr = JSONArray()
            for (step in recordedSteps) {
                jsonArr.put(JSONObject().apply {
                    put("centerX", step.centerX)
                    put("centerY", step.centerY)
                    put("stepIndex", step.stepIndex)
                    put("timestamp", step.timestamp)  // v18: 保存时间戳
                    put("recordingVersion", step.recordingVersion)
                })
            }

            val meta = JSONObject().apply {
                put("deviceModel", Build.MODEL)
                put("androidVersion", Build.VERSION.RELEASE)
                put("recordedAt", System.currentTimeMillis())
                put("recordingVersion", CURRENT_RECORDING_VERSION)
                put("stepCount", recordedSteps.size)
            }

            prefs.edit()
                .putString(KEY_RECORDED_STEPS, jsonArr.toString())
                .putString(KEY_RECORDING_META, meta.toString())
                .putInt(KEY_RECORDING_VERSION, CURRENT_RECORDING_VERSION)
                .apply()

            AppLogger.i(TAG, "💾 录制已保存: ${recordedSteps.size} 步, 设备=${Build.MODEL}")
            // 打印每步详情
            recordedSteps.forEach { s ->
                AppLogger.i(TAG, "💾 步骤#${s.stepIndex}: (${s.centerX}, ${s.centerY}) t=${s.timestamp}ms")
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "💾 保存失败: ${e.message}")
        }
    }

    private fun loadRecording(): Recording? {
        try {
            val raw = prefs.getString(KEY_RECORDED_STEPS, null) ?: return null
            val jsonArr = JSONArray(raw)
            if (jsonArr.length() == 0) return null

            val steps = mutableListOf<RecordedStep>()
            for (i in 0 until jsonArr.length()) {
                val obj = jsonArr.getJSONObject(i)
                steps.add(RecordedStep(
                    centerX = obj.optInt("centerX", -1),
                    centerY = obj.optInt("centerY", -1),
                    stepIndex = obj.optInt("stepIndex", i),
                    timestamp = obj.optLong("timestamp", 0L),  // v18: 读取时间戳
                    recordingVersion = obj.optInt("recordingVersion", 1)
                ))
            }

            val metaRaw = prefs.getString(KEY_RECORDING_META, null)
            val meta = if (metaRaw != null) JSONObject(metaRaw) else null

            return Recording(
                steps = steps,
                deviceModel = meta?.optString("deviceModel", "") ?: "",
                androidVersion = meta?.optString("androidVersion", "") ?: "",
                recordedAt = meta?.optLong("recordedAt", 0L) ?: 0L,
                recordingVersion = meta?.optInt("recordingVersion", 1) ?: 1
            )
        } catch (e: Exception) {
            AppLogger.e(TAG, "💾 加载失败: ${e.message}")
            return null
        }
    }

    // ==================== Public API ====================

    fun hasRecording(): Boolean {
        return loadRecording()?.let { it.steps.isNotEmpty() } ?: false
    }

    fun getRecordingInfo(): String {
        val r = loadRecording()
        return if (r != null && r.steps.isNotEmpty()) {
            val intervals = r.steps.zipWithNext { a, b -> b.timestamp - a.timestamp }
            "${r.steps.size}步, 间隔=${intervals}ms, 设备=${r.deviceModel}"
        } else "无录制"
    }

    fun clearRecording() {
        prefs.edit()
            .remove(KEY_RECORDED_STEPS)
            .remove(KEY_RECORDING_META)
            .remove(KEY_RECORDING_VERSION)
            .apply()
        AppLogger.i(TAG, "录制数据已清除")
    }

    fun isAutoDisabled(): Boolean = false
    fun resetAutoDisabled() { /* v18: 无自动禁用 */ }

    fun destroy() {
        isRecording = false
        isReplaying = false
        expectingReplay = false
        handler.removeCallbacksAndMessages(null)
    }

    var isReplayingActive: Boolean
        get() = isReplaying
        private set(_) {}
}
