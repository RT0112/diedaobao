package com.falldetector.diedaobao.assist

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.PointF
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import com.falldetector.diedaobao.util.AppLogger

/**
 * 远程协助 AccessibilityService v17 — 极简版
 *
 * 职责：
 * - 监听窗口状态变化 → 检测系统弹窗 → 触发自动回放
 * - 执行手势（点击、滑动，供远程触控使用）
 * - 截图（API 30+）
 *
 * v17 简化：
 * - onAccessibilityEvent 只监听 TYPE_WINDOW_STATE_CHANGED
 * - 只在非录制+非回放状态下检测弹窗
 * - 检测只看 com.android.systemui 窗口（根除误报）
 */
class RemoteAssistService : AccessibilityService() {

    companion object {
        private const val TAG = "RemoteAssistService"

        @Volatile
        var instance: RemoteAssistService? = null
            private set

        const val PREFS_CLOUD = "cloudbase"
        const val KEY_MP_WAITING = "mp_waiting"
        const val KEY_MP_GRANTED = "mp_granted"
        const val ACTION_AUTO_PERMISSION_GRANTED = "com.falldetector.diedaobao.ACTION_AUTO_PERMISSION_GRANTED"

        fun isAccessibilityEnabled(context: android.content.Context): Boolean {
            val pkg = context.packageName
            val enabledServices = android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false

            return enabledServices.contains("$pkg/.assist.RemoteAssistService")
                || enabledServices.contains("$pkg/$pkg.assist.RemoteAssistService")
                || enabledServices.contains("RemoteAssistService")
        }
    }

    private lateinit var permissionRecordManager: PermissionRecordManager
    private val handler by lazy { android.os.Handler(mainLooper) }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        permissionRecordManager = PermissionRecordManager(this)
        permissionRecordManager.onReplayComplete = { result ->
            AppLogger.i(TAG, "onReplayComplete: result=$result")
        }
        AppLogger.i(TAG, "远程协助辅助服务已连接 (v17 极简版)")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (!::permissionRecordManager.isInitialized) return

        val eventType = event.eventType

        // 录制期间打印诊断日志
        if (permissionRecordManager.isCurrentlyRecording()) {
            AppLogger.i(TAG, "[录制诊断] eventType=$eventType pkg=${event.packageName} class=${event.className}")
        }

        when (eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // v18: 只有 expectingReplay=true 时才触发自动回放
                // 录制完成后 isRecording=false 但 expectingReplay=false，不会误触
                if (permissionRecordManager.isExpectingReplay()
                    && !permissionRecordManager.isCurrentlyRecording()
                    && !permissionRecordManager.isReplayingActive
                    && permissionRecordManager.hasPermissionDialog()) {
                    AppLogger.i(TAG, "[v18] 检测到系统弹窗 + 期望回放，启动自动回放")
                    permissionRecordManager.tryAutoHandle()
                }
            }
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                // 录制期间不处理（TouchRecordOverlay 负责录制）
            }
        }
    }

    // ==================== 对外方法 ====================

    fun startMpRecording() {
        if (::permissionRecordManager.isInitialized) {
            permissionRecordManager.startRecording()
        }
    }

    fun stopMpRecording() {
        if (::permissionRecordManager.isInitialized) {
            permissionRecordManager.stopRecording()
        }
    }

    fun hasRecordedPermissionSteps(): Boolean {
        return if (::permissionRecordManager.isInitialized) {
            permissionRecordManager.hasRecording()
        } else false
    }

    fun isPermissionAutoDisabled(): Boolean = false  // v17: 无自动禁用

    fun resetPermissionAutoDisabled() { /* v17: 无操作 */ }

    fun clearPermissionRecording() {
        if (::permissionRecordManager.isInitialized) {
            permissionRecordManager.clearRecording()
        }
    }

    fun clearRecordedPermissionSteps() {
        if (::permissionRecordManager.isInitialized) {
            permissionRecordManager.clearRecording()
        }
    }

    fun hasPermissionDialog(): Boolean {
        return if (::permissionRecordManager.isInitialized) {
            permissionRecordManager.hasPermissionDialog()
        } else false
    }

    fun getPermissionRecordManager(): PermissionRecordManager? {
        return if (::permissionRecordManager.isInitialized) permissionRecordManager else null
    }

    override fun onInterrupt() {
        AppLogger.w(TAG, "辅助服务被中断")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        if (::permissionRecordManager.isInitialized) {
            permissionRecordManager.destroy()
        }
        AppLogger.w(TAG, "辅助服务已解绑")
        return super.onUnbind(intent)
    }

    // ==================== 手势执行 ====================

    fun executeClick(x: Float, y: Float, durationMs: Long = 100L): Boolean {
        AppLogger.i(TAG, "executeClick: x=$x, y=$y, durationMs=$durationMs")
        return try {
            val path = Path().apply { moveTo(x, y) }
            // v19.7.2: duration 至少 150ms（MIUI/HyperOS 对短点击可能不识别）
            val actualDuration = durationMs.coerceAtLeast(150L)
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, actualDuration))
                .build()
            val result = dispatchGesture(gesture, null, null)
            AppLogger.i(TAG, "executeClick dispatched: result=$result, actualDuration=$actualDuration")
            result
        } catch (e: Exception) {
            AppLogger.e(TAG, "executeClick 失败: ${e.message}")
            false
        }
    }

    fun executeSwipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 300L): Boolean {
        AppLogger.i(TAG, "executeSwipe: ($x1,$y1)→($x2,$y2) duration=$durationMs")
        return try {
            val path = Path().apply {
                moveTo(x1, y1)
                lineTo(x2, y2)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
                .build()
            val result = dispatchGesture(gesture, null, null)
            AppLogger.i(TAG, "executeSwipe dispatched: result=$result")
            result
        } catch (e: Exception) {
            AppLogger.e(TAG, "executeSwipe 失败: ${e.message}")
            false
        }
    }

    fun executeDoubleClick(x: Float, y: Float): Boolean {
        val first = executeClick(x, y, 80)
        if (!first) return false
        handler.postDelayed({ executeClick(x, y, 80) }, 100)
        return true
    }

    fun getScreenSize(): PointF {
        val metrics = resources.displayMetrics
        return PointF(metrics.widthPixels.toFloat(), metrics.heightPixels.toFloat())
    }

    // v19.7.3: 导航键支持 — 通过 AccessibilityService.performGlobalAction
    fun executeGlobalAction(keyCode: String): Boolean {
        val action = when (keyCode) {
            "home" -> GLOBAL_ACTION_HOME
            "back" -> GLOBAL_ACTION_BACK
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
            "power_dialog" -> GLOBAL_ACTION_POWER_DIALOG
            "lock_screen" -> GLOBAL_ACTION_LOCK_SCREEN
            "split_screen" -> GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN
            else -> {
                AppLogger.w(TAG, "未知的全局操作: $keyCode")
                return false
            }
        }
        val result = performGlobalAction(action)
        AppLogger.i(TAG, "executeGlobalAction($keyCode) = $result")
        return result
    }

    fun takeScreenshot(callback: (Bitmap?) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            AppLogger.w(TAG, "takeScreenshot requires API 30+")
            callback(null)
            return
        }
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    try {
                        val bitmap = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                        result.hardwareBuffer.close()
                        callback(bitmap)
                    } catch (e: Exception) {
                        AppLogger.e(TAG, "wrapHardwareBuffer failed: ${e.message}")
                        callback(null)
                    }
                }
                override fun onFailure(errorCode: Int) {
                    AppLogger.w(TAG, "takeScreenshot failed: $errorCode")
                    callback(null)
                }
            })
        } catch (e: Exception) {
            AppLogger.e(TAG, "takeScreenshot exception: ${e.message}")
            callback(null)
        }
    }
}
