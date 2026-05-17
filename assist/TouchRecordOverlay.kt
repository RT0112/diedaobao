package com.falldetector.diedaobao.assist

import android.annotation.SuppressLint
import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.falldetector.diedaobao.util.AppLogger

/**
 * 透明触摸录制 Overlay
 *
 * 在权限弹窗出现时覆盖一层近乎透明的 View，拦截用户触摸坐标。
 * 捕获后隐藏 Overlay → dispatchGesture 重新点击 → 重新显示 Overlay。
 *
 * 为什么不用 AccessibilityEvent：
 * - AccessibilityEvent 没有 x/y 属性
 * - event.source 节点在系统弹窗上经常返回 null 文本
 * - 坐标方案最简单可靠
 */
class TouchRecordOverlay(private val service: RemoteAssistService) {

    companion object {
        private const val TAG = "TouchRecordOverlay"
        private const val RECLICK_DELAY_MS = 200L
        private const val RESHOW_DELAY_MS = 400L
    }

    private val windowManager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var overlayView: View? = null
    private var isShowing = false
    private var isRecording = false

    var onStepRecorded: ((x: Float, y: Float) -> Unit)? = null

    private fun createLayoutParams(): WindowManager.LayoutParams {
        // TYPE_ACCESSIBILITY_OVERLAY 是唯一能盖在系统权限弹窗上面的窗口类型
        // 只有 AccessibilityService 才能添加这种类型的窗口
        val type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
    }

    fun startRecording() {
        if (isRecording) return
        isRecording = true
        showOverlay()
        AppLogger.i(TAG, "开始触摸录制")
    }

    fun stopRecording() {
        if (!isRecording) return
        isRecording = false
        hideOverlay()
        AppLogger.i(TAG, "停止触摸录制")
    }

    fun isCurrentlyRecording(): Boolean = isRecording

    @SuppressLint("ClickableViewAccessibility")
    private fun showOverlay() {
        if (isShowing) return

        // 近乎透明的背景（完全透明可能收不到触摸事件）
        val view = View(service)
        view.setBackgroundColor(Color.argb(1, 0, 0, 0)) // alpha=1 几乎不可见

        AppLogger.i(TAG, "TouchRecordOverlay 创建完成，handler=$handler,wm=$windowManager")
        view.setOnTouchListener { _, event ->
            if (!isRecording) return@setOnTouchListener false

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val x = event.rawX
                    val y = event.rawY
                    AppLogger.i(TAG, "捕获触摸: ($x, $y)")

                    // 回调记录坐标
                    onStepRecorded?.invoke(x, y)

                    // 隐藏 overlay
                    hideOverlay()

                    // 延迟后重新点击（让弹窗真正收到点击）
                    handler.postDelayed({
                        redispatchClick(x, y)
                    }, RECLICK_DELAY_MS)

                    true
                }
                else -> true
            }
        }

        try {
            windowManager.addView(view, createLayoutParams())
            overlayView = view
            isShowing = true
        } catch (e: Exception) {
            AppLogger.e(TAG, "Overlay 显示失败: ${e.message}")
        }
    }

    private fun hideOverlay() {
        if (!isShowing) return
        try {
            overlayView?.let { windowManager.removeViewImmediate(it) }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Overlay 移除失败: ${e.message}")
        }
        overlayView = null
        isShowing = false
    }

    private fun redispatchClick(x: Float, y: Float) {
        // ★ 直接用 service 引用（构造时已传入），不依赖全局 instance
        val clicked = service.executeClick(x, y, 100)
        AppLogger.i(TAG, "重新点击 ($x, $y): $clicked")

        // 如果还在录制模式，重新显示 overlay
        if (isRecording) {
            handler.postDelayed({
                if (isRecording) showOverlay()
            }, RESHOW_DELAY_MS)
        }
    }

    fun destroy() {
        isRecording = false
        hideOverlay()
        handler.removeCallbacksAndMessages(null)
    }
}
