package com.falldetector.diedaobao.ui

import android.app.Activity
import android.view.WindowManager
import com.falldetector.diedaobao.util.AppLogger
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.IntentFilter
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.app.NotificationManager
import android.os.Bundle

import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.falldetector.diedaobao.R
import com.falldetector.diedaobao.assist.RemoteAssistManager
import com.falldetector.diedaobao.assist.RemoteAssistService
import com.falldetector.diedaobao.assist.ScreenCaptureService
import android.util.Log
import kotlinx.coroutines.*

/**
 * 远程协助授权 + 协助中界面（老人端）
 * 
 * 流程：
 * 1. 显示协助请求，老人点[允许]/[拒绝]
 * 2. 检查 AccessibilityService 是否启用 → 未启用则引导
 * 3. 申请 MediaProjection 屏幕录制权限
 * 4. 启动 ScreenCaptureService 开始推流
 * 5. 协助中显示红色指示条 + 结束按钮
 * 6. 倒计时处理（30秒超时）
 */
class RemoteAssistActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "RemoteAssistActivity"
        private const val REQUEST_MEDIA_PROJECTION = 1001
        private const val REQUEST_ACCESSIBILITY = 1002
        private const val AUTO_REJECT_MS = 60_000L
    }

    // UI 组件
    private lateinit var containerRequest: View
    private lateinit var containerAssisting: View
    private lateinit var containerLoading: View
    private lateinit var tvRequestFrom: TextView
    private lateinit var tvRemaining: TextView
    private lateinit var btnAllow: Button
    private lateinit var btnReject: Button
    private lateinit var btnEndAssist: Button
    private lateinit var btnFinishRecording: Button
    private lateinit var btnCancelRecording: Button
    private lateinit var tvAssistStatus: TextView

    // 请求数据
    private var requestFromName: String = ""
    private var requestFromId: String = ""
    private var guardianId: String? = null
    
    // v23: 重复请求检测 — 用SP持久化，Activity重建后不丢失
    private fun getLastHandledFromId(): String {
        return getSharedPreferences("assist_dedup", MODE_PRIVATE).getString("last_handled_from_id", "") ?: ""
    }
    private fun setLastHandledFromId(id: String) {
        getSharedPreferences("assist_dedup", MODE_PRIVATE).edit().putString("last_handled_from_id", id).apply()
    }

    // 状态
    private var isAssisting = false
    private var waitingForAccessibility = false  // 标记：刚从设置页返回
    private var waitingForOverlay = false  // 标记：刚从悬浮窗设置页返回
    private val handler = Handler(Looper.getMainLooper())
    private var rejectRunnable: Runnable? = null
    private var remainingSeconds = 30

    // 权限自动处理
    private var permissionCheckRunnable: Runnable? = null
    private var isWaitingForPermission = false
    // v15: 防止广播重复触发 startScreenCapture
    private var permissionHandled = false

    // Service binding
    private var captureService: ScreenCaptureService? = null
    private var serviceBound = false
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.i(TAG, "onServiceConnected 被调用")
            val binder = service as ScreenCaptureService.LocalBinder
            captureService = binder.getService()
            serviceBound = true
            Log.i(TAG, "Service bound successfully, pendingResultCode=$pendingResultCode")
            // 绑定成功后启动采集
            startCaptureAfterBind()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            captureService = null
            serviceBound = false
            Log.i(TAG, "Service disconnected")
        }
    }

    private var pendingResultCode: Int = Int.MIN_VALUE
    private var pendingData: Intent? = null
    private var pendingElderId: String = ""
    private var pendingGuardianId: String? = null
    private var autoPermissionReceiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "onCreate 开始, intent=$intent")

        // v19.7.6: 全局异常保护，防止闪退
        try {
            // 确保锁屏/息屏场景下也能显示在最前（兼容 HyperOS/MIUI）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                setShowWhenLocked(true)
                setTurnScreenOn(true)
            }
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                    or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )

            setContentView(R.layout.activity_remote_assist)

            val isNewRequest = handleNewRequest(intent)
            if (!isNewRequest) {
                // 重复请求，不重新走授权流程
                Log.i(TAG, "onCreate: 重复请求，跳过")
                return
            }
            initViews()

            // v18.2: 检查是否自动允许协助
            val prefs = getSharedPreferences("settings", MODE_PRIVATE)
            val autoAllow = prefs.getBoolean("auto_allow_assist", true)
            if (autoAllow) {
                // 自动允许 → 直接走允许流程，不弹请求对话框
                tvRequestFrom.text = "$requestFromName 请求协助操作您的手机"
                onAllowClicked()
            } else {
                showRequestDialog()
                startAutoRejectCountdown()
            }

            // v14: 注册自动授权成功广播
            registerAutoPermissionReceiver()
        } catch (e: Exception) {
            // v19.7.6: 用 Log.e 代替 AppLogger（AppLogger 可能未初始化）
            Log.e(TAG, "onCreate 异常!", e)
            // 写入本地文件（不依赖任何工具类）
            writeCrashLog("onCreate", e)
            // v20: 自动重启 App，老人不需要手动操作
            restartApp("onCreate崩溃")
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        Log.i(TAG, "onNewIntent: intent=$intent")
        try {
            setIntent(intent)
            val isNewRequest = handleNewRequest(intent)
            if (!isNewRequest) {
                Log.i(TAG, "onNewIntent: 重复请求，跳过")
                return
            }

            val prefs = getSharedPreferences("settings", MODE_PRIVATE)
            val autoAllow = prefs.getBoolean("auto_allow_assist", true)
            if (autoAllow) {
                tvRequestFrom.text = "$requestFromName 请求协助操作您的手机"
                onAllowClicked()
            } else {
                showRequestDialog()
                startAutoRejectCountdown()
            }
        } catch (e: Exception) {
            Log.e(TAG, "onNewIntent 异常!", e)
            writeCrashLog("onNewIntent", e)
            // v20: 自动重启 App
            restartApp("onNewIntent崩溃")
        }
    }

    /**
     * 处理新的远程协助请求（onCreate 和 onNewIntent 共用）
     */
    /**
     * 处理新的远程协助请求
     * @return true=新请求，需要继续处理; false=重复请求，调用方应跳过
     */
    private fun handleNewRequest(intent: Intent?): Boolean {
        val newFromId = intent?.extras?.getString("from_id", "") ?: ""
        val hadActiveSession = isAssisting
        val previousGuardianId = guardianId
        val lastHandled = getLastHandledFromId()
        
        // v23: 先检测重复请求，再cleanup — 避免cleanup重置状态后误判
        // 场景1: 有活跃会话 + 同一guardian重复请求（WS+HTTP双推）
        if (hadActiveSession && previousGuardianId == newFromId && newFromId.isNotEmpty()) {
            Log.i(TAG, "handleNewRequest: 同一监护人重复请求(活跃会话)，忽略")
            return false
        }
        // 场景2: 无活跃会话 + from_id与上次相同（WS+HTTP双推或Activity重建）
        if (!hadActiveSession && newFromId.isNotEmpty() && newFromId == lastHandled) {
            Log.i(TAG, "handleNewRequest: 重复请求(from_id=$newFromId, lastHandled=$lastHandled)，忽略")
            return false
        }
        
        // 通过重复检测 → 清理旧会话
        cleanupAssist()
        
        // v21: 只有不同家属的请求才调 endAssist
        if (hadActiveSession && previousGuardianId != newFromId && newFromId.isNotEmpty()) {
            scope.launch {
                RemoteAssistManager.endAssist(this@RemoteAssistActivity)
            }
        }
        
        // 记录已处理的from_id（持久化到SP）
        setLastHandledFromId(newFromId)

        // 重置所有状态
        isAssisting = false
        guardianId = null
        isWaitingForPermission = false
        permissionHandled = false
        waitingForAccessibility = false
        waitingForOverlay = false
        rejectRunnable?.let { handler.removeCallbacks(it) }
        permissionCheckRunnable?.let { handler.removeCallbacks(it) }

        // 解析新请求数据
        val extras = intent?.extras
        requestFromName = extras?.getString("from_name", "家属") ?: "家属"
        requestFromId = extras?.getString("from_id", "") ?: ""
        remainingSeconds = extras?.getInt("remaining_seconds", 60) ?: 60
        return true
    }

    override fun onDestroy() {
        rejectRunnable?.let { handler.removeCallbacks(it) }
        permissionCheckRunnable?.let { handler.removeCallbacks(it) }
        isWaitingForPermission = false
        // v19.7.3: 确保 cleanupAssist 被调，防止闪退后脏状态
        if (isAssisting) {
            cleanupAssist()
        }
        // 注销自动授权广播接收器
        autoPermissionReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
            autoPermissionReceiver = null
        }
        // 停止信号轮询
        RemoteAssistManager.stopSignalPolling()
        if (serviceBound) {
            try { unbindService(serviceConnection) } catch (_: Exception) {}
            serviceBound = false
        }
        // 注意：不再在 onDestroy 中发送 respondToRequest(false)
        // 之前这里会在 !isAssisting 时发拒绝，但协程异步执行时可能
        // 恰好拦截到下一个请求并错误拒绝它
        // 改由云端超时机制处理：请求超过 remainingSeconds 自动过期
        super.onDestroy()
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ==================== UI ====================

    private fun initViews() {
        containerRequest = findViewById(R.id.container_request)
        containerAssisting = findViewById(R.id.container_assisting)
        containerLoading = findViewById(R.id.container_loading)
        tvRequestFrom = findViewById(R.id.tv_request_from)
        tvRemaining = findViewById(R.id.tv_remaining)
        btnAllow = findViewById(R.id.btn_allow)
        btnReject = findViewById(R.id.btn_reject)
        btnEndAssist = findViewById(R.id.btn_end_assist)
        btnFinishRecording = findViewById(R.id.btn_finish_recording)
        btnCancelRecording = findViewById(R.id.btn_cancel_recording)
        tvAssistStatus = findViewById(R.id.tv_assist_status)
    }

    private fun showRequestDialog() {
        containerRequest.visibility = View.VISIBLE
        containerAssisting.visibility = View.GONE
        containerLoading.visibility = View.GONE

        tvRequestFrom.text = "$requestFromName 请求协助操作您的手机"

        btnAllow.setOnClickListener { onAllowClicked() }
        btnReject.setOnClickListener { onRejectClicked() }
    }

    private fun showLoading(message: String) {
        containerRequest.visibility = View.GONE
        containerAssisting.visibility = View.GONE
        containerLoading.visibility = View.VISIBLE

        btnFinishRecording.setOnClickListener {
            // 完成录制
            RemoteAssistService.instance?.stopMpRecording()
            // 重置 KEY_MP_GRANTED，触发下一轮轮询
            val prefs = getSharedPreferences(RemoteAssistService.PREFS_CLOUD, MODE_PRIVATE)
            prefs.edit().putBoolean(RemoteAssistService.KEY_MP_GRANTED, true).apply()
            Toast.makeText(this, "录制完成", Toast.LENGTH_SHORT).show()
        }
        btnCancelRecording.setOnClickListener {
            // 取消录制
            RemoteAssistService.instance?.stopMpRecording()
            scope.launch { RemoteAssistManager.endAssist(this@RemoteAssistActivity) }
            finish()
        }
    }

    private fun showAssisting() {
        containerRequest.visibility = View.GONE
        containerAssisting.visibility = View.VISIBLE
        containerLoading.visibility = View.GONE

        tvAssistStatus.text = "$requestFromName 正在协助..."
        btnEndAssist.setOnClickListener { onEndClicked() }
    }

    // ==================== 操作 ====================

    private fun onAllowClicked() {
        // 取消自动拒绝
        rejectRunnable?.let { handler.removeCallbacks(it) }

        // 先检查 AccessibilityService
        if (!RemoteAssistManager.isAccessibilityEnabled(this)) {
            showAccessibilityGuide()
            return
        }

        // v0.43.2: 检查悬浮窗权限（MIUI/HyperOS 要求悬浮窗权限才能创建 VirtualDisplay）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            showOverlayGuide()
            return
        }

        // 同意请求
        scope.launch {
            val result = RemoteAssistManager.respondToRequest(this@RemoteAssistActivity, true)
            runOnUiThread {
                if (result.success) {
                    guardianId = result.guardianId
                    // 用 intent 传来的 requestFromId（老人自己的 userId）
                    // ⚠️ 不能用 getUserId()——family binding 会把 guardian 的 ID 写入 SharedPreferences，
                    //    导致 ScreenCaptureService 上传帧到错误的文档，子女端永远拉不到帧
                    startPermissionFlow()
                } else {
                    Toast.makeText(this@RemoteAssistActivity, result.message, Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
    }

    /**
     * 启动权限获取流程（v10: TouchRecordOverlay录制 + 通用回放）
     *
     * 录制模式（首次）：
     * 1. 启动 RemoteAssistService.startMpRecording() → TouchRecordOverlay 激活
     * 2. requestMediaProjection() → 弹窗出现
     * 3. showLoading() → 显示「完成录制」按钮
     * 4. 用户手动操作弹窗 → Overlay 拦截每个触摸坐标，录进 recordedSteps
     * 5. 用户点「完成录制」 → stopMpRecording() → 保存坐标
     *
     * 回放模式（有录制）：
     * - tryAutoHandle() → 检测到弹窗 → dispatchGesture 点击录制坐标
     *
     * 自动处理（有录制 + 自动模式未禁用）：
     * - showLoading() → requestMediaProjection() 后立即 tryAutoHandle()
     */
    private fun startPermissionFlow() {
        // v18.1: 开始前重置 mp_granted，防止上次残留 true 导致误判
        getSharedPreferences("cloudbase", MODE_PRIVATE)
            .edit().putBoolean("mp_granted", false).apply()

        val service = RemoteAssistService.instance
        // v17.2: 双重检查 — 先问 Service，再直接读 SharedPreferences
        // 解决 Service 被系统杀后 instance=null 导致 hasRecording 误判的问题
        var hasRecording = service?.hasRecordedPermissionSteps() == true
        if (!hasRecording) {
            val prefs = getSharedPreferences("permission_record", MODE_PRIVATE)
            val stepsJson = prefs.getString("recorded_steps_v9", null)
            hasRecording = stepsJson != null && stepsJson.isNotEmpty() && stepsJson != "[]"
            if (hasRecording) {
                AppLogger.i(TAG, "startPermissionFlow: Service报无录制但SharedPreferences有数据，以SP为准")
            }
        }
        Log.i(TAG, "启动权限流程: hasRecording=$hasRecording")

        if (hasRecording) {
            // 有录制 → 回放模式：设置标志 + 弹系统弹窗
            RemoteAssistService.instance?.getPermissionRecordManager()?.setExpectingReplay(true)
            Toast.makeText(this, "🚀 自动授权中...", Toast.LENGTH_SHORT).show()
            requestMediaProjection()
        } else {
            // 首次使用 → 录制引导
            showRecordingGuide()
        }
    }

    /**
     * 首次录制引导对话框
     */
    private fun showRecordingGuide() {
        AlertDialog.Builder(this)
            .setTitle("屏幕共享授权（首次设置）")
            .setMessage(
                "接下来会弹出系统权限弹窗。\n\n" +
                "📱 请手动点击弹窗上的按钮完成授权。\n\n" +
                "🎬 App 会记录您的点击步骤，下次自动完成。"
            )
            .setPositiveButton("好的，开始") { _, _ ->
                // 1. 先启动录制（激活 TouchRecordOverlay 拦截触摸坐标）
                RemoteAssistService.instance?.startMpRecording()
                Log.i(TAG, "录制已启动，等待弹窗操作...")
                // 2. 弹出 MediaProjection 系统弹窗 → 用户手动操作 → Overlay 录制坐标
                requestMediaProjection()
                // 3. 立即显示 Loading 界面（含「完成录制」按钮），让用户在操作弹窗后能手动结束录制
                showLoading("请操作系统弹窗完成授权，然后点击「完成录制」")
            }
            .setNegativeButton("取消") { _, _ ->
                scope.launch {
                    RemoteAssistManager.endAssist(this@RemoteAssistActivity)
                }
                finish()
            }
            .setCancelable(false)
            .show()
    }

    // showAutoDisabledGuide 已删除（2026-05-09，自动点击功能移除）

    // startPermissionPolling 已删除（2026-05-09，依赖自动点击检测）

    // handlePermissionTimeout 已删除

    private fun onRejectClicked() {
        rejectRunnable?.let { handler.removeCallbacks(it) }
        scope.launch {
            RemoteAssistManager.respondToRequest(this@RemoteAssistActivity, false)
            runOnUiThread { finish() }
        }
    }

    private fun onEndClicked() {
        // 停止信号轮询
        RemoteAssistManager.stopSignalPolling()

        cleanupAssist()

        // 通知云端结束
        scope.launch {
            RemoteAssistManager.endAssist(this@RemoteAssistActivity)
        }

        Toast.makeText(this, "协助已结束", Toast.LENGTH_SHORT).show()
        finish()
    }

    /**
     * 清理协助相关资源（停止 ScreenCaptureService + 解绑 + 取消通知）
     * 
     * ⚠️ 不用 isAssisting 守卫，确保多次调用都安全（幂等）。
     * 之前用 if (!isAssisting) return 导致子女断开→onSessionEnded 时
     * isAssisting 已被其他路径设为 false，清理被跳过，Service 继续运行。
     */
    private fun cleanupAssist() {
        Log.i(TAG, "cleanupAssist: isAssisting=$isAssisting, serviceBound=$serviceBound")
        isAssisting = false

        // v19: 重置 mp_granted，防止下次误判（上次残留 true 会导致回放逻辑认为权限已授权）
        getSharedPreferences("cloudbase", MODE_PRIVATE)
            .edit().putBoolean("mp_granted", false).apply()
        Log.i(TAG, "cleanupAssist: mp_granted 已重置为 false")

        // 停止信号轮询
        RemoteAssistManager.stopSignalPolling()

        // 解绑 Service
        try {
            if (serviceBound) {
                unbindService(serviceConnection)
                serviceBound = false
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "解绑Service异常: ${e.message}")
        }

        // 停止 ScreenCaptureService
        try {
            val intent = Intent(this, ScreenCaptureService::class.java)
            stopService(intent)
        } catch (e: Exception) {
            AppLogger.w(TAG, "停止Service异常: ${e.message}")
        }

        // 强制停掉 ScreenCaptureService（即使 bindService 启动的也需要显式停）
        ScreenCaptureService.instance?.let { svc ->
            try {
                svc.stopSelf()
            } catch (e: Exception) {
                AppLogger.w(TAG, "stopSelf异常: ${e.message}")
            }
        }

        // 取消远程协助通知
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(3001)
        } catch (e: Exception) {
            AppLogger.w(TAG, "取消通知异常: ${e.message}")
        }
    }

    // ==================== 无障碍引导 ====================

    private fun showAccessibilityGuide() {
        waitingForAccessibility = true
        AlertDialog.Builder(this)
            .setTitle("开启远程协助（只需一次）")
            .setMessage(
                "为了让家人能帮您操作手机，需要开启一个开关：\n\n" +
                "① 点击下方「前往设置」按钮\n" +
                "② 找到「跌倒宝」→ 打开开关\n" +
                "③ 返回此页面，点击「允许」\n\n" +
                "⚠️ 开启后永久有效，下次不用再设置"
            )
            .setPositiveButton("前往设置") { _, _ ->
                val intent = RemoteAssistManager.openAccessibilitySettings(this)
                // 加 resolveActivity 检查兜底——某些 MIUI 精简版系统
                // 根本没有无障碍设置页，startActivity 会抛 ActivityNotFoundException
                if (intent.resolveActivity(packageManager) != null) {
                    try {
                        startActivity(intent)
                    } catch (e: Exception) {
                        Toast.makeText(this@RemoteAssistActivity,
                            "无法打开设置，请手动在系统设置中开启", Toast.LENGTH_LONG).show()
                    }
                } else {
                    Toast.makeText(this@RemoteAssistActivity,
                        "系统不支持此功能，请联系家人协助", Toast.LENGTH_LONG).show()
                }
                Toast.makeText(this@RemoteAssistActivity,
                    "找到「跌倒宝」→ 打开开关 → 返回此页面", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("拒绝协助") { _, _ ->
                waitingForAccessibility = false
                scope.launch {
                    RemoteAssistManager.respondToRequest(this@RemoteAssistActivity, false)
                }
                finish()
            }
            .setCancelable(false)
            .show()
    }

    /**
     * v0.43.2: MIUI/HyperOS 要求悬浮窗权限才能创建 VirtualDisplay
     * 引导用户开启悬浮窗权限
     */
    private fun showOverlayGuide() {
        waitingForOverlay = true
        AlertDialog.Builder(this)
            .setTitle("开启悬浮窗权限")
            .setMessage(
                "为了能让家人看到您的手机屏幕，需要允许显示悬浮窗：\n\n" +
                "① 点击下方「前往设置」按钮\n" +
                "② 找到「跌倒宝」→ 打开开关\n" +
                "③ 返回此页面，自动继续\n\n" +
                "⚠️ 这是手机安全要求，开启后远程协助才能正常工作"
            )
            .setPositiveButton("前往设置") { _, _ ->
                try {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(this,
                        "无法打开设置，请手动在应用信息中开启悬浮窗", Toast.LENGTH_LONG).show()
                }
                Toast.makeText(this,
                    "打开「跌倒宝」的悬浮窗开关 → 返回此页面", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("取消") { _, _ ->
                waitingForOverlay = false
            }
            .setCancelable(false)
            .show()
    }

    override fun onResume() {
        super.onResume()
        Log.i(TAG, "onResume: waitingForAccessibility=$waitingForAccessibility, waitingForOverlay=$waitingForOverlay")
        try {
            // 从无障碍设置页返回 → 自动检测是否已开启并继续流程
            if (waitingForAccessibility && RemoteAssistManager.isAccessibilityEnabled(this)) {
                waitingForAccessibility = false
                checkOverlayThenProceed()
            }
            // 从悬浮窗设置页返回 → 自动检测是否已开启并继续流程
            if (waitingForOverlay) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
                    waitingForOverlay = false
                    checkOverlayThenProceed()
                } else {
                    // 用户可能没授予，再问一次
                    waitingForOverlay = false
                    Toast.makeText(this, "请允许悬浮窗权限以使用远程协助", Toast.LENGTH_LONG).show()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "onResume 异常!", e)
            writeCrashLog("onResume", e)
            // v20: 自动重启 App
            restartApp("onResume崩溃")
        }
    }

    /**
     * 悬浮窗权限检查通过后，继续请求流程
     */
    private fun checkOverlayThenProceed() {
        scope.launch {
            val result = RemoteAssistManager.respondToRequest(this@RemoteAssistActivity, true)
            runOnUiThread {
                if (result.success) {
                    guardianId = result.guardianId
                    requestMediaProjection()
                } else {
                    Toast.makeText(this@RemoteAssistActivity, result.message, Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
    }

    // ==================== MediaProjection ====================

    private fun requestMediaProjection() {
        try {
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(
                manager.createScreenCaptureIntent(),
                REQUEST_MEDIA_PROJECTION
            )
        } catch (e: Exception) {
            AppLogger.e(TAG, "requestMediaProjection 异常: ${e.message}")
            // Activity 可能已销毁或不在前台
            scope.launch { RemoteAssistManager.endAssist(this@RemoteAssistActivity) }
            finish()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {
            REQUEST_MEDIA_PROJECTION -> {
                if (resultCode == Activity.RESULT_OK && data != null) {
                    // v17: 延迟停止录制，给 TouchRecordOverlay 时间完成回调
                    // 之前立即 stopMpRecording() 会导致 overlay 回调还没执行完就被停止
                    Handler(Looper.getMainLooper()).postDelayed({
                        RemoteAssistService.instance?.stopMpRecording()
                    }, 1500L)
                    // v15: 防重复 — onActivityResult 和广播都可能触发，只处理一次
                    if (permissionHandled) {
                        Log.w(TAG, "[v15] 权限已通过广播处理，跳过 onActivityResult")
                        return
                    }
                    permissionHandled = true

                    // 权限获取成功，设置标志
                    val prefs = getSharedPreferences(RemoteAssistService.PREFS_CLOUD, MODE_PRIVATE)
                    prefs.edit().putBoolean(RemoteAssistService.KEY_MP_GRANTED, true).apply()
                    Log.i(TAG, "MediaProjection 授权成功，已设置 mp_granted=true")

                    Toast.makeText(this, "✅ 屏幕共享已启动", Toast.LENGTH_SHORT).show()

                    startScreenCapture(resultCode, data)
                } else {
                    // 用户拒绝屏幕录制权限
                    // 停止录制
                    RemoteAssistService.instance?.stopMpRecording()
                    isWaitingForPermission = false
                    handler.removeCallbacks(permissionCheckRunnable ?: return)

                    Toast.makeText(this, "需要屏幕共享权限才能使用远程协助", Toast.LENGTH_LONG).show()
                    scope.launch {
                        RemoteAssistManager.endAssist(this@RemoteAssistActivity)
                    }
                    finish()
                }
            }
        }
    }

    private fun startScreenCapture(resultCode: Int, data: Intent) {
        isAssisting = true
        showAssisting()

        // ⚠️ elderId 必须从 SharedPreferences 读"绑定的老人ID"（cloudbase 中的 elder_id）
        // 不能用 getUserId()——family binding 会把 guardian 的 ID 写入 user_id 字段
        // ScreenCaptureService 上传帧要写到「老人文档」，不能用 guardian 的 ID
        val elderId = getBoundElderId() ?: getUserId() ?: ""
        Log.i(TAG, "startScreenCapture: elderId=$elderId, guardianId=$guardianId")
        AppLogger.i(TAG, "[诊断] elderId=$elderId, guardianId=$guardianId")
        // 诊断：通知用户实际用的 elderId（方便排查问题）
        Toast.makeText(this, "[诊断] elderId=$elderId\nguardianId=$guardianId", Toast.LENGTH_LONG).show()

        if (elderId.isBlank()) {
            AppLogger.e(TAG, "elderId 为空，无法启动屏幕共享")
            Toast.makeText(this, "用户ID异常，请重新注册后重试", Toast.LENGTH_LONG).show()
            isAssisting = false
            finish()
            return
        }

        val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
            putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenCaptureService.EXTRA_DATA, data)
            putExtra(ScreenCaptureService.EXTRA_ELDER_ID, elderId)
            putExtra(ScreenCaptureService.EXTRA_GUARDIAN_ID, guardianId)
        }

        // 保存参数，等 onServiceConnected 后再启动
        pendingResultCode = resultCode
        pendingData = data
        pendingElderId = elderId
        pendingGuardianId = guardianId
        Log.i(TAG, "参数已保存: resultCode=$pendingResultCode, data=${pendingData != null}, elderId=$pendingElderId")

        try {
            // MIUI/HyperOS 兼容：只用 bindService，不调用 startService
            // startService 会被系统拦截，bindService 在用户交互场景可以正常启动
            bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
            Log.i(TAG, "ScreenCaptureService bind 请求已发送")
        } catch (e: Exception) {
            AppLogger.e(TAG, "启动 Service 异常: ${e.message}")
            Toast.makeText(this, "启动失败: ${e.message}", Toast.LENGTH_LONG).show()
            isAssisting = false
            finish()
            return
        }
    }

    private fun startCaptureAfterBind() {
        Log.i(TAG, "startCaptureAfterBind 被调用")
        Log.i(TAG, "pendingResultCode=$pendingResultCode, pendingData=${pendingData != null}, pendingElderId='$pendingElderId', captureService=$captureService")
        if (pendingResultCode == Int.MIN_VALUE) {
            AppLogger.e(TAG, "pendingResultCode 未设置 (Int.MIN_VALUE)")
            Toast.makeText(this, "启动参数异常: resultCode", Toast.LENGTH_LONG).show()
            isAssisting = false
            finish()
            return
        }
        if (pendingData == null) {
            AppLogger.e(TAG, "pendingData == null")
            Toast.makeText(this, "启动参数异常: data", Toast.LENGTH_LONG).show()
            isAssisting = false
            finish()
            return
        }
        if (pendingElderId.isBlank()) {
            AppLogger.e(TAG, "pendingElderId.isBlank()")
            Toast.makeText(this, "启动参数异常: elderId", Toast.LENGTH_LONG).show()
            isAssisting = false
            finish()
            return
        }
        captureService?.startAfterBind(pendingResultCode, pendingData!!, pendingElderId, pendingGuardianId)
        Log.i(TAG, "屏幕采集已启动")

        // 启动信号轮询：从云端拉取子女端的触控指令，分发给 RemoteAssistService 执行
        RemoteAssistManager.startSignalPolling(this)
        Log.i(TAG, "信号轮询已启动")

        // 监听子女端主动断开（end_session 信号）
        RemoteAssistManager.onSessionEnded = {
            runOnUiThread {
                cleanupAssist()
                Toast.makeText(this, "子女已断开，协助结束", Toast.LENGTH_LONG).show()
                finish()
            }
        }

        // 监听服务断连
        ScreenCaptureService.instance?.onGuardianDisconnected = {
            runOnUiThread {
                cleanupAssist()
                Toast.makeText(this, "连接已断开，协助结束", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    // ==================== 自动拒绝倒计时 ====================

    private fun startAutoRejectCountdown() {
        tvRemaining.text = "${remainingSeconds}秒后自动拒绝"

        rejectRunnable = object : Runnable {
            override fun run() {
                remainingSeconds--
                if (remainingSeconds <= 0) {
                    // 超时自动拒绝
                    scope.launch {
                        RemoteAssistManager.respondToRequest(this@RemoteAssistActivity, false)
                    }
                    finish()
                } else {
                    tvRemaining.text = "${remainingSeconds}秒后自动拒绝"
                    handler.postDelayed(this, 1000)
                }
            }
        }
        handler.postDelayed(rejectRunnable!!, 1000)
    }

    // v15: 注册自动授权成功广播接收器，收到后取消倒计时并启动屏幕共享
    private fun registerAutoPermissionReceiver() {
        autoPermissionReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                Log.i(TAG, "[v15] 收到自动授权成功广播")
                // v15: 防重复触发
                if (permissionHandled) {
                    Log.w(TAG, "[v15] 权限已处理过，忽略")
                    return
                }
                permissionHandled = true
                // 取消倒计时
                rejectRunnable?.let { handler.removeCallbacks(it) }
                rejectRunnable = null
                // 检查是否已有 MediaProjection 权限
                val prefs = getSharedPreferences(RemoteAssistService.PREFS_CLOUD, MODE_PRIVATE)
                val mpGranted = prefs.getBoolean(RemoteAssistService.KEY_MP_GRANTED, false)
                Log.i(TAG, "[v15] mp_granted=$mpGranted, pendingResultCode=${pendingResultCode != Int.MIN_VALUE}")
                if (mpGranted && pendingResultCode != Int.MIN_VALUE && pendingData != null) {
                    // 权限已获取 → 直接启动屏幕共享
                    Log.i(TAG, "[v15] 权限已获取，直接启动屏幕共享")
                    startScreenCapture(pendingResultCode, pendingData!!)
                } else if (!mpGranted) {
                    // 还没弹系统权限弹窗 → 等待 onActivityResult
                    Log.i(TAG, "[v15] 权限未获取，等待 onActivityResult")
                }
            }
        }
        val filter = android.content.IntentFilter(RemoteAssistService.ACTION_AUTO_PERMISSION_GRANTED)
        registerReceiver(autoPermissionReceiver, filter, RECEIVER_NOT_EXPORTED)
    }

    /**
     * v20: 自动重启 App（闪退恢复）
     * 老人不会手动重启，远程协助窗口崩溃后必须自动恢复
     */
    private fun restartApp(reason: String) {
        try {
            Log.i(TAG, "重启App: $reason")
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            intent?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "重启App失败", e)
        } finally {
            finish()
            // 杀进程确保干净重启，避免脏状态
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

    /**
     * v19.7.6: 将闪退日志写入本地文件（不依赖 AppLogger 等工具类）
     * 下次启动时可通过 ADB 读取: adb shell cat /data/data/com.falldetector.diedaobao/files/assist_crash_log.txt
     */
    private fun writeCrashLog(location: String, e: Exception) {
        try {
            val sw = java.io.StringWriter()
            e.printStackTrace(java.io.PrintWriter(sw))
            val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())
            val logLine = "$timestamp | $location | ${e.javaClass.simpleName}: ${e.message}\n${sw.toString()}\n\n"
            val file = java.io.File(filesDir, "assist_crash_log.txt")
            file.appendText(logLine)
        } catch (_: Exception) {
            // 最后的防线，不能再抛异常
        }
    }

    private fun getUserId(): String? {
        val prefs = getSharedPreferences("cloudbase", MODE_PRIVATE)
        return prefs.getString("user_id", null)
    }

    /**
     * 读取绑定的老人ID（来自 family binding）
     * 用于 ScreenCaptureService 上传帧到正确的老人文档
     */
    private fun getBoundElderId(): String? {
        val prefs = getSharedPreferences("cloudbase", MODE_PRIVATE)
        // 绑定时存的是绑定的老人 userId
        return prefs.getString("elder_id", null)
    }
}
