package com.falldetector.diedaobao.service

import android.Manifest
import com.falldetector.diedaobao.util.AppLogger
import com.falldetector.diedaobao.assist.RemoteAssistManager
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.falldetector.diedaobao.R
import com.falldetector.diedaobao.cloud.CloudBaseClient
import com.falldetector.diedaobao.detect.DetectionConfig
import com.falldetector.diedaobao.detect.FallDetector
import com.falldetector.diedaobao.notify.EmergencyNotifier
import com.falldetector.diedaobao.sensor.SensorCollector
import com.falldetector.diedaobao.ui.ConfirmActivity
import com.falldetector.diedaobao.ui.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

class FallDetectionService : Service() {

    companion object {
        const val CHANNEL_ID = "fall_detection_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "STOP_FALL_DETECTION"

        // v18.2: 区分用户主动停止 vs 系统杀进程
        @Volatile
        var userStopped = false
            private set

        const val ALERT_CHANNEL_ID = "fall_alert_channel"
        const val ALERT_NOTIFICATION_ID = 999

        @Volatile
        var isRunning = false
            private set

        private var _currentInstance: FallDetectionService? = null

        fun getDetectorStats(): String = "ML: " + FallDetector.mlInferCount + " 跌倒: " + FallDetector.mlFallCount
        fun getDiagnosticInfo(): com.falldetector.diedaobao.detect.DiagnosticInfo? =
            _currentInstance?.fallDetector?.diagnosticInfo?.value
        
        fun getDecisionLog(): String =
            _currentInstance?.fallDetector?.decisionLog?.value ?: ""
    }

    private val TAG = "FallDetectionService"
    private lateinit var sensorCollector: SensorCollector
    private lateinit var fallDetector: FallDetector
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var serviceJob: Job? = null
    private val feedMutex = kotlinx.coroutines.sync.Mutex()
    private var lastFeedTime = 0L  // 限流：避免 50Hz 全部涌入推理
    private var locationSyncJob: Job? = null  // 定期位置同步
    private var lastUploadedLat = 0.0  // 上次上传的纬度
    private var lastUploadedLng = 0.0  // 上次上传的经度
    private var lastUploadTime = 0L    // 上次上传时间
    @Volatile private var lastGoodLocation: Location? = null  // locationListener 收到的最新位置
    private val LOCATION_UPLOAD_DISTANCE = 50f  // 位置变化超过50米才上传（省云端资源）
    private val LOCATION_UPLOAD_INTERVAL = 5 * 60_000L  // 最短上传间隔5分钟（省云端资源）
    private val LOCATION_FORCE_INTERVAL = 15 * 60_000L  // 最长上传间隔15分钟（兜底）

    // 本地围栏缓存（用于本地Haversine检测，不调云函数）
    private var cachedFences: List<CloudBaseClient.GeofenceSimple> = emptyList()
    private var lastFenceRefreshTime = 0L
    private val FENCE_REFRESH_INTERVAL = 30 * 60_000L  // 30分钟刷新围栏数据
    
    // 围栏越界冷却：同一围栏30分钟内只通知一次，避免通知轰炸
    private val fenceBreachCooldown = mutableMapOf<String, Long>()
    private val BREACH_COOLDOWN_MS = 30 * 60_000L  // 30分钟冷却

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            lastGoodLocation = location  // 追踪最新位置，供按需上传使用
            handleNewLocation(location)
        }
        @Deprecated("Deprecated in Java")
        override fun onLocationChanged(locations: MutableList<Location>) {
            locations.lastOrNull()?.let { handleNewLocation(it) }
        }
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {
            // GPS重新开启时立即获取一次位置
            Log.i(TAG, "定位提供者已启用: $provider")
            tryGetFreshAndUpload()
        }
        override fun onProviderDisabled(provider: String) {}
    }

    // 权限变化广播接收器：用户授权"始终允许"后，无需重启服务即可生效
    private val locationPermissionReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: android.content.Intent?) {
            if (intent?.action == "android.location.PROVIDERS_CHANGED" ||
                intent?.action == LocationManager.MODE_CHANGED_ACTION) {
                Log.i(TAG, "位置权限/模式变化，重新启动位置监听")
                stopLocationSync()
                startLocationSync()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        _currentInstance = this
        Log.i(TAG, "onCreate")
        DetectionConfig.init(this)  // 加载检测参数配置
        sensorCollector = SensorCollector(this)
        fallDetector = FallDetector(this)
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand: action=${intent?.action}")

        if (intent?.action == ACTION_STOP) {
            Log.i(TAG, "收到停止命令")
            userStopped = true  // v18.2: 标记用户主动停止
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildGuardNotification())
        isRunning = true
        userStopped = false  // v18.2: 启动时重置
        // v0.30.7: 服务启动成功，清除重启标志
        getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit().putBoolean("service_need_restart", false).apply()
        startDetection()
        
        // 启动远程协助轮询（Service级别，即使App在后台也能响应）
        startRemoteAssistPolling()
        
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        AppLogger.w(TAG, "onTaskRemoved: 用户划掉最近任务，重启服务保活")
        // 重新启动服务（HyperOS/MIUI 等国产系统会杀后台，必须重启）
        val restartIntent = Intent(this, FallDetectionService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(restartIntent)
        } else {
            startService(restartIntent)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        _currentInstance = null
        isRunning = false
        serviceJob?.cancel()
        stopLocationSync()
        stopRemoteAssistPolling()  // 停止远程协助轮询
        if (::sensorCollector.isInitialized) sensorCollector.stop()
        
        // v0.30.7: 非用户主动停止时，自动重启服务（防止系统杀后台导致守护中断）
        // 多层防御：AlarmManager + 保存重启标志（HomeFragment 轮询也会检测恢复）
        if (!userStopped) {
            AppLogger.w(TAG, "onDestroy: 非用户主动停止，尝试自动重启服务")
            // 保存需要重启的标志（SharedPreferences 跨进程存活）
            getSharedPreferences("settings", Context.MODE_PRIVATE)
                .edit().putBoolean("service_need_restart", true).apply()
            
            val restartIntent = Intent(this, FallDetectionService::class.java)
            val pendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                PendingIntent.getForegroundService(this, 0, restartIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            } else {
                PendingIntent.getService(this, 0, restartIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            }
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            // 优先尝试精确闹钟，失败则降级为不精确闹钟
            try {
                alarmManager.setExactAndAllowWhileIdle(
                    android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    android.os.SystemClock.elapsedRealtime() + 3000,
                    pendingIntent
                )
                AppLogger.i(TAG, "已设置 AlarmManager 精确重启（3秒后）")
            } catch (e: SecurityException) {
                // API 31+ 无 SCHEDULE_EXACT_ALARM 权限时降级
                alarmManager.setAndAllowWhileIdle(
                    android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    android.os.SystemClock.elapsedRealtime() + 5000,
                    pendingIntent
                )
                AppLogger.w(TAG, "精确闹钟失败，降级为不精确闹钟（5秒后）")
            }
        }
        
        Log.i(TAG, "服务已停止")
    }

    private fun startDetection() {
        Log.i(TAG, "开始检测...")
        sensorCollector.start()

        CoroutineScope(Dispatchers.Default).launch {
            delay(1000)
            Log.i(TAG, "传感器初始化完成")
        }

        // ★ 直接回调模式（v0.16.0）：传感器数据直接喂给 FallDetector，不再轮询 StateFlow
        // 旧方式：传感器50Hz -> StateFlow(只保留最新值) -> 服务20Hz轮询 -> 60%数据丢失
        // 新方式：传感器50Hz -> onSensorChanged -> 直接 feed() -> 0%数据丢失
        //
        // v0.16.1: onSensorChanged 在主线程运行，feed() 里的 ONNX 推理可能耗时
        // -> 用协程切换到 Default 线程池，避免阻塞主线程
        sensorCollector.setCallback(object : SensorCollector.SensorCallback {
            override fun onSensorData(accX: Float, accY: Float, accZ: Float,
                                     gyroX: Float, gyroY: Float, gyroZ: Float,
                                     timestamp: Long) {
                // 限流：20ms 间隔（50Hz），避免并发推理
                val now = System.currentTimeMillis()
                if (now - lastFeedTime < 18) return  // 限流
                lastFeedTime = now

                // 在 Default 线程池执行推理（避免主线程 ANR）
                // 用 Mutex 保证同一时间只有一个 feed() 在执行
                serviceScope.launch {
                    feedMutex.withLock {
                        fallDetector.feed(accX, accY, accZ, gyroX, gyroY, gyroZ, timestamp)
                    }

                    val result = fallDetector.result.value
                    if (result.isFallDetected) {
                        withContext(Dispatchers.Main) {
                            AppLogger.w(TAG, "★★★ 跌倒检测! peak=${result.peakAcceleration}g, method=${result.detectionMethod}")

                            // 全屏紧急通知
                            showFullScreenAlert(result)

                            // 震动
                            triggerVibration()

                            // 启动 ConfirmActivity
                            try {
                                val confirmIntent = Intent(this@FallDetectionService, ConfirmActivity::class.java).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                                    putExtra("peak_acc", result.peakAcceleration)
                                    putExtra("posture_angle", result.postureAngle)
                                    putExtra("confidence", result.confidence)
                                    putExtra("detection_method", result.detectionMethod)
                                    putExtra("ml_probability", result.mlProbability)
                                    // v0.29.6: 新增详细信息（供微信通知）
                                    putExtra("phys_score", result.physScore)
                                    putExtra("impact_g", result.impactG)
                                    putExtra("fall_height", result.fallHeight)
                                    // v0.46: 传入服务已知的最新位置，避免ConfirmActivity取不到GPS
                                    lastGoodLocation?.let { loc ->
                                        putExtra("latitude", loc.latitude)
                                        putExtra("longitude", loc.longitude)
                                    }
                                }
                                startActivity(confirmIntent)
                            } catch (e: Exception) {
                                AppLogger.e(TAG, "启动ConfirmActivity失败: ${e.message}")
                            }
                        }

                        fallDetector.reset()
                        sensorCollector.resetPostureAngle()
                    }
                }
            }
        })

        // 保留 serviceJob 用于生命周期管理，但不再轮询
        serviceJob = CoroutineScope(Dispatchers.Default).launch {
            // 只做日志打印
            while (isActive) {
                delay(5000)
                val diag = fallDetector.diagnosticInfo.value
                Log.i(TAG, "📊 状态: ${diag.detectionState} | feed=${diag.accWindowSize}样本 | ML推理=${FallDetector.mlInferCount}次")
            }
        }

        // 定期位置同步（每5分钟上传一次位置，让子女端可查看）
        startLocationSync()
    }

    /**
     * 核心修复：全屏紧急通知 — 保证锁屏也能弹出
     * 使用 fullScreenIntent，这是 Android 官方推荐的方式
     */
    private fun showFullScreenAlert(result: com.falldetector.diedaobao.detect.FallDetectionResult) {
        try {
            // fullScreenIntent: 点击通知或锁屏时直接打开 ConfirmActivity
            val fullScreenIntent = Intent(this, ConfirmActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("peak_acc", result.peakAcceleration)
                putExtra("posture_angle", result.postureAngle)
                putExtra("confidence", result.confidence)
                putExtra("detection_method", result.detectionMethod)
                putExtra("ml_probability", result.mlProbability)
            }
            val fullScreenPendingIntent = PendingIntent.getActivity(
                this, 0, fullScreenIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // 点击通知内容时的 Intent（跳转到 MainActivity）
            val contentIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val contentPendingIntent = PendingIntent.getActivity(
                this, 1, contentIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // 关键修复：使用 fullScreenChannel（专门用于锁屏弹窗）
            val notification = NotificationCompat.Builder(this, "full_screen_channel")
                .setContentTitle("⚠️ 检测到可能跌倒！")
                .setContentText("请确认是否安全，倒计时后将自动通知紧急联系人")
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setOngoing(true)
                .setAutoCancel(false)
                .setFullScreenIntent(fullScreenPendingIntent, true)
                .setContentIntent(contentPendingIntent)
                .setSound(android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION))
                .build()

            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(ALERT_NOTIFICATION_ID, notification)

            Log.i(TAG, "全屏告警通知已发出")
        } catch (e: Exception) {
            AppLogger.e(TAG, "全屏告警通知失败: ${e.message}")
        }
    }

    private fun triggerVibration() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager
                vm.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(VIBRATOR_SERVICE) as Vibrator
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(
                    VibrationEffect.createWaveform(
                        longArrayOf(0, 1000, 300, 1000, 300, 1000, 300, 1000),
                        -1
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(longArrayOf(0, 1000, 300, 1000, 300, 1000, 300, 1000), -1)
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "震动失败: ${e.message}")
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // 1. 守护服务通知（低优先级）
            val guardChannel = NotificationChannel(
                CHANNEL_ID, "跌倒守护服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "跌倒检测后台守护服务"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
            }

            // 2. 紧急告警通知（最高优先级 + 全屏 + 锁屏可见）
            val alertChannel = NotificationChannel(
                ALERT_CHANNEL_ID, "紧急告警",
                NotificationManager.IMPORTANCE_HIGH  // 高优先级，弹出横幅
            ).apply {
                description = "跌倒紧急告警"
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setShowBadge(true)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 1000, 300, 1000)
                // 关键：允许全屏意图
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // Android 10+ 无需额外设置，IMPORTANCE_HIGH 自动允许
                }
                // 设置告警声音
                setSound(
                    android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM),
                    android.app.Notification.AUDIO_ATTRIBUTES_DEFAULT
                )
                enableLights(true)
                lightColor = android.graphics.Color.RED
            }

            // 3. 全屏意图通道（最高优先级，无声音，全屏弹出）
            val fullScreenChannel = NotificationChannel(
                "full_screen_channel", "全屏告警",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "跌倒全屏告警"
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setShowBadge(true)
                enableVibration(true)
                setSound(null, null) // 声音由 ConfirmActivity 自己播放
            }

            // 4. 远程协助请求通道（全屏弹出 + 来电式铃声）
            val assistChannel = NotificationChannel(
                "remote_assist_channel", "远程协助请求",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "远程协助请求通知"
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setShowBadge(true)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 1000, 300, 1000, 300, 1000)
                setSound(
                    android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE),
                    android.app.Notification.AUDIO_ATTRIBUTES_DEFAULT
                )
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannels(listOf(guardChannel, alertChannel, fullScreenChannel, assistChannel))
        }
    }

    /**
     * 守护服务常驻通知（点击跳转APP）
     */
    private fun buildGuardNotification(): Notification {
        // 点击通知跳转到 MainActivity
        val contentIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🔒 跌倒宝守护中")
            .setContentText("点击返回跌倒宝")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)  // 关键修复：点击跳转APP
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    fun updateNotification(text: String) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildGuardNotification())
    }

    /**
     * 位置同步入口：分成两个独立部分
     * 1. 按需拉取轮询（不依赖权限，HTTP 调云函数，服务启动即运行）
     * 2. 本地位置监听 + 上传（需要位置权限）
     */
    private fun startLocationSync() {
        // ═══ 1. 按需拉取轮询 — 不需要位置权限，立即启动 ═══
        startPullPolling()

        // ═══ 2. 本地位置监听 — 需要位置权限 ═══
        startLocationListening()
    }

    /**
     * 按需拉取轮询：云端检查是否有子女端位置请求
     * 纯 HTTP 调用，不依赖 GPS 权限。即使没有位置权限也能运行。
     */
    private var isUrgentPull = false  // 加急拉取标志：子女请求位置后缩短轮询间隔
    private var urgentPullStartTime = 0L  // 加急模式开始时间

    private fun startPullPolling() {
        locationSyncJob?.cancel()
        locationSyncJob = serviceScope.launch {
            delay(2_000)  // 首次延迟2秒（让初始化完成）
            refreshFenceCache()
            Log.i(TAG, "📍 按需拉取轮询已启动（常态10秒，加急3秒）")
            while (isActive) {
                try {
                    // 1. 定期刷新围栏缓存（30分钟一次，感知子女端的增/删/改）
                    val sinceLastRefresh = System.currentTimeMillis() - lastFenceRefreshTime
                    if (sinceLastRefresh >= FENCE_REFRESH_INTERVAL) {
                        refreshFenceCache()
                    }

                    // 2. 按需位置拉取
                    val result = CloudBaseClient.pollPullRequest(this@FallDetectionService)
                    if (result.hasPullRequest) {
                        Log.i(TAG, "📍 检测到位置拉取请求(pullTime=${result.pullRequestTime})，立即上传")
                        isUrgentPull = true  // 上传后保持加急60秒，确保子女端轮询能及时拿到
                        urgentPullStartTime = System.currentTimeMillis()
                        tryGetFreshAndUpload()
                    } else if (isUrgentPull && System.currentTimeMillis() - urgentPullStartTime > 60_000L) {
                        // 加急模式超时60秒，恢复正常
                        Log.i(TAG, "📍 加急轮询超时60秒，恢复正常10秒间隔")
                        isUrgentPull = false
                    }
                } catch (e: Exception) {
                    AppLogger.e(TAG, "拉取轮询异常: ${e.message}")
                }
                // 动态间隔：加急模式5秒，常态30秒
                // 加急模式在子女请求位置后触发，最多持续60秒自动恢复
                val interval = if (isUrgentPull) 3_000L else 10_000L
                delay(interval)
            }
            AppLogger.w(TAG, "按需拉取轮询已停止")
        }
    }

    /**
     * 本地位置监听：GPS + 网络定位实时回调
     * 用于围栏检测 + 按需上传时的新鲜位置来源
     */
    private fun startLocationListening() {
        // 检查前台位置权限
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            AppLogger.w(TAG, "⚠️ 无位置权限，仅开启按需拉取轮询（无GPS监听）")
            registerLocationPermissionReceiver()
            return
        }

        // 检查后台位置权限（Android 10+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val hasBgLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (!hasBgLocation) {
                AppLogger.w(TAG, "⚠️ 缺少后台位置权限！请到设置->应用->跌倒宝->位置权限->始终允许")
                showBackgroundLocationNotification()
                registerLocationPermissionReceiver()
            }
        }

        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 1000L, 5f, locationListener
            )
        } catch (e: Exception) {
            AppLogger.w(TAG, "GPS位置监听注册失败: ${e.message}")
        }

        try {
            locationManager.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER, 1000L, 5f, locationListener
            )
        } catch (e: Exception) {
            AppLogger.w(TAG, "网络位置监听注册失败: ${e.message}")
        }

        Log.i(TAG, "📍 GPS监听已启动（本地围栏检测 + 按需新鲜位置）")
        registerLocationPermissionReceiver()
    }

    private fun stopLocationSync() {
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        locationManager?.removeUpdates(locationListener)
        locationSyncJob?.cancel()
        locationSyncJob = null
        // 取消权限监听
        try { unregisterReceiver(locationPermissionReceiver) } catch (_: Exception) {}
        Log.i(TAG, "位置同步已停止")
    }

    /**
     * 注册位置权限变化监听
     * 用户授权"始终允许"后自动重启位置监听，无需重启服务
     */
    private fun registerLocationPermissionReceiver() {
        try {
            val filter = android.content.IntentFilter().apply {
                addAction(LocationManager.PROVIDERS_CHANGED_ACTION)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    addAction(LocationManager.MODE_CHANGED_ACTION)
                }
            }
            registerReceiver(locationPermissionReceiver, filter)
            Log.d(TAG, "位置权限监听已注册")
        } catch (e: Exception) {
            AppLogger.w(TAG, "注册位置权限监听失败: ${e.message}")
        }
    }

    /**
     * 立即获取并上传一次位置
     */
    /**
     * 按需获取新鲜位置并上传。
     * 
     * 策略（v0.39.6->v0.39.7 精度分级）：
     *   lastGoodLocation 是 GPS 且 acc≤50m 且 age<30s -> 直接上传
     *   lastGoodLocation 是 网络 且 acc≤30m 且 age<15s -> 直接上传（WiFi定位）
     *   都不满足 -> requestSingleUpdate(GPS+网络并行)，分精度门槛(50m/30m)，10秒超时
     *   requestSingleUpdate 无结果 -> 用 lastGoodLocation 兜底（非 getLastKnownLocation）
     */
    private fun tryGetFreshAndUpload() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            AppLogger.w(TAG, "无位置权限，无法上传位置")
            return
        }

        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        Log.i(TAG, "📍 拉取请求触发")

        serviceScope.launch {
            try {
                // ═══ 先看 lastGoodLocation 是否达标 ═══
                val cached = lastGoodLocation
                val age = cached?.let { System.currentTimeMillis() - it.time } ?: Long.MAX_VALUE
                val isGps = cached?.provider == "gps"
                Log.i(TAG, "📍 lastGoodLocation: age=${age}ms, provider=${cached?.provider ?: "无"}, acc=${cached?.accuracy ?: "N/A"}m")

                val canUseCached = when {
                    cached == null -> false
                    isGps && age < 30_000 && cached.accuracy <= 50f -> {
                        Log.i(TAG, "✅ GPS缓存命中（age=${age}ms, acc=${cached.accuracy}m）")
                        true
                    }
                    !isGps && age < 15_000 && cached.accuracy <= 30f -> {
                        Log.i(TAG, "✅ WiFi缓存命中（age=${age}ms, acc=${cached.accuracy}m）")
                        true
                    }
                    else -> {
                        Log.i(TAG, "⏳ 缓存不达标（isGps=$isGps, acc=${cached.accuracy}m），触发主动定位")
                        false
                    }
                }

                if (canUseCached) {
                    uploadLocation(cached!!.latitude, cached!!.longitude)
                    return@launch
                }

                // ═══ 主动 requestSingleUpdate（GPS + 网络并行） ═══
                var gotLocation = false
                val lock = Object()
                val listener = object : LocationListener {
                    override fun onLocationChanged(loc: Location) {
                        val ok = when (loc.provider) {
                            "gps" -> loc.accuracy <= 50f
                            else -> loc.accuracy <= 30f
                        }
                        if (!gotLocation && ok) {
                            gotLocation = true
                            Log.i(TAG, "✅ requestSingleUpdate: ${loc.provider}, acc=${loc.accuracy}m")
                            uploadLocation(loc.latitude, loc.longitude)
                            synchronized(lock) { lock.notify() }
                        } else if (!gotLocation) {
                            Log.d(TAG, "  跳过(${loc.provider}, acc=${loc.accuracy}m) — 精度不达标")
                        }
                    }
                    override fun onProviderDisabled(p: String) {}
                    override fun onProviderEnabled(p: String) {}
                    override fun onStatusChanged(p: String?, s: Int, extras: Bundle?) {}
                }

                try {
                    locationManager.requestSingleUpdate(LocationManager.GPS_PROVIDER, listener, mainLooper)
                } catch (e: Exception) {
                    AppLogger.w(TAG, "GPS requestSingleUpdate失败: ${e.message}")
                }
                try {
                    locationManager.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, listener, mainLooper)
                } catch (e: Exception) {
                    AppLogger.w(TAG, "NETWORK requestSingleUpdate失败: ${e.message}")
                }

                synchronized(lock) {
                    try { lock.wait(10_000) } catch (_: InterruptedException) {}
                }

                if (!gotLocation) {
                    // ═══ 主动定位失败：用 lastGoodLocation 兜底 ═══
                    // 不用 getLastKnownLocation（Android 系统级缓存，可能几小时甚至几天前）
                    val fallback = lastGoodLocation
                    if (fallback != null) {
                        AppLogger.w(TAG, "⚠️ requestSingleUpdate无结果，lastGoodLocation兜底: ${fallback.provider}, acc=${fallback.accuracy}m, age=${System.currentTimeMillis() - fallback.time}ms")
                        uploadLocation(fallback.latitude, fallback.longitude)
                    } else {
                        AppLogger.e(TAG, "❌ 完全无位置（lastGoodLocation=null）")
                    }
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "获取位置异常: ${e.message}")
            }
        }
    }

    /**
     * 强制请求新鲜位置（GPS+网络），最多等待10秒
     * 返回第一个收到的位置，或超时返回null
     */
    private suspend fun getFreshLocation(locationManager: LocationManager): Location? = withContext(Dispatchers.Main) {
        var result: Location? = null
        var done = false
        val lock = Object()

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (!done && location.accuracy <= 100f) {
                    done = true
                    result = location
                    synchronized(lock) { lock.notify() }
                }
            }
            override fun onProviderDisabled(p: String) {}
            override fun onProviderEnabled(p: String) {}
            override fun onStatusChanged(p: String?, s: Int, extras: Bundle?) {}
        }

        try {
            // requestSingleUpdate = 让系统发起一次真实 GPS fix（不同于 getLastKnownLocation 读缓存）
            locationManager.requestSingleUpdate(LocationManager.GPS_PROVIDER, listener, mainLooper)
        } catch (e: Exception) {
            AppLogger.w(TAG, "GPS requestSingleUpdate失败: ${e.message}")
        }

        Log.d(TAG, "等待系统GPS fix（requestSingleUpdate）...")
        synchronized(lock) {
            try { lock.wait(20_000) } catch (_: InterruptedException) {}
        }
        done = true

        val loc = result
        Log.d(TAG, "getFreshLocation结果: ${loc?.let { "lat=${it.latitude},acc=${it.accuracy}m,provider=${it.provider}" } ?: "null"}")
        return@withContext loc
    }

    /**
     * 处理位置变化回调
     * 1. 本地围栏检测（每次位置变化都检查，零云端成本）
     * 2. 位置上传仅通过子女端拉取请求触发（poll_pull）
     */
    private fun handleNewLocation(location: Location) {
        // 本地围栏检测（每次都检查，纯本地计算，零云端成本）
        checkLocalGeofence(location.latitude, location.longitude)
    }

    /**
     * 强制上传当前位置（兜底机制，15分钟一次）
     */
    private suspend fun forceUploadLocation() {
        if (ContextCompat.checkSelfPermission(this@FallDetectionService, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return

        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val location = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)

        if (location != null) {
            uploadLocation(location.latitude, location.longitude)
        } else {
            AppLogger.w(TAG, "兜底上传：无法获取当前位置")
        }
    }

    /**
     * 本地围栏检测（Haversine公式，零云端成本）
     * 每次位置变化都检查，实时性高
     * 检测到越界->通过短信/企微通知子女（不依赖子女端轮询）
     */
    private fun checkLocalGeofence(lat: Double, lng: Double) {
        if (cachedFences.isEmpty()) return

        for (fence in cachedFences) {
            val distance = haversineDistance(lat, lng, fence.lat, fence.lng)
            if (distance > fence.radiusMeters * 1.1) {  // 10%余量防边缘抖动
                // 冷却检查：同一围栏30分钟内不重复通知
                val lastBreach = fenceBreachCooldown[fence.id] ?: 0L
                val now = System.currentTimeMillis()
                if (now - lastBreach < BREACH_COOLDOWN_MS) {
                    continue  // 冷却中，跳过
                }
                fenceBreachCooldown[fence.id] = now
                // 越界！发送通知
                AppLogger.w(TAG, "围栏越界: ${fence.name}, 距离=${distance.toInt()}m > 半径=${fence.radiusMeters.toInt()}m")
                sendGeofenceBreachNotification(listOf(fence.name))
                // 越界通知通过已有通道（短信/企微Webhook）发给子女
                notifyGuardianOfBreach(fence.name, distance)
            }
        }
    }

    /**
     * 通过已有企微Webhook通知子女围栏越界
     * 直接用OkHttp发text消息，无需额外依赖
     */
    private fun notifyGuardianOfBreach(fenceName: String, distance: Double) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
                val webhookUrl = prefs.getString("webhook_url", "") ?: ""
                if (webhookUrl.isEmpty()) {
                    AppLogger.w(TAG, "企微Webhook未配置，跳过围栏越界通知")
                    return@launch
                }

                val timeStr = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.CHINA)
                    .format(java.util.Date())
                val content = buildString {
                    append("⚠️【围栏越界告警】\n")
                    append("老人已离开「$fenceName」区域\n\n")
                    append("⏰ 时间：$timeStr\n")
                    append("📏 距离边界：${distance.toInt()}米\n\n")
                    append("请确认老人安全状况！")
                }

                val json = org.json.JSONObject().apply {
                    put("msgtype", "text")
                    put("text", org.json.JSONObject().apply {
                        put("content", content)
                    })
                }

                val body = json.toString()
                    .toRequestBody("application/json".toMediaType())
                val request = okhttp3.Request.Builder()
                    .url(webhookUrl)
                    .post(body)
                    .build()

                val client = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                val response = client.newCall(request).execute()
                Log.i(TAG, "围栏越界企微通知: ${response.code}")
            } catch (e: Exception) {
                AppLogger.e(TAG, "围栏越界通知发送失败: ${e.message}")
            }
        }
    }

    /**
     * Haversine公式计算两点间距离（米）
     */
    private fun haversineDistance(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6371000.0  // 地球半径（米）
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLng / 2) * Math.sin(dLng / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return r * c
    }

    /**
     * 刷新围栏缓存（从云端拉取围栏数据，30分钟一次）
     */
    private suspend fun refreshFenceCache() {
        try {
            val fences = CloudBaseClient.getCachedFenceData(this@FallDetectionService)
            // 始终更新缓存（包括空列表），这样删除围栏后能立即生效
            cachedFences = fences
            lastFenceRefreshTime = System.currentTimeMillis()
            // 清除已删除围栏的冷却记录
            val activeIds = fences.map { it.id }.toSet()
            fenceBreachCooldown.keys.retainAll(activeIds)
            Log.i(TAG, "围栏缓存已刷新: ${fences.size}个围栏")
        } catch (e: Exception) {
            AppLogger.e(TAG, "围栏缓存刷新失败: ${e.message}")
        }
    }

    /**
     * 上传位置到云端（仅上传，围栏检测由本地完成）
     */
    private fun uploadLocation(latitude: Double, longitude: Double) {
        val now = System.currentTimeMillis()
        // 去重：5分钟内不重复上传同一位置
        if (now - lastUploadTime < 60_000 &&
            lastUploadedLat != 0.0 && lastUploadedLng != 0.0) {
            val distance = FloatArray(1)
            Location.distanceBetween(lastUploadedLat, lastUploadedLng, latitude, longitude, distance)
            if (distance[0] < 30f) return  // 30米内不算变化
        }

        serviceScope.launch {
            try {
                val success = CloudBaseClient.syncLocation(
                    context = this@FallDetectionService,
                    latitude = latitude,
                    longitude = longitude
                )
                if (success) {
                    lastUploadedLat = latitude
                    lastUploadedLng = longitude
                    lastUploadTime = now
                    Log.i(TAG, "位置上传: lat=$latitude, lng=$longitude")
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "位置上传异常: ${e.message}")
            }
        }
    }

    /**
     * 发送围栏越界通知
     */
    private fun sendGeofenceBreachNotification(breaches: List<String>) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        // 确保通知渠道存在
        if (manager.getNotificationChannel("geofence_channel") == null) {
            val channel = NotificationChannel(
                "geofence_channel",
                "围栏告警",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "老人超出安全区域时通知"
                enableVibration(true)
            }
            manager.createNotificationChannel(channel)
        }

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 2001, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, "geofence_channel")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("⚠️ 围栏越界告警")
            .setContentText("老人已离开：${breaches.joinToString()}")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)  // 点击跳转到App
            .build()

        manager.notify(2001, notification)
    }

    /**
     * 显示后台位置权限缺失通知
     * 引导用户到设置页开启"始终允许"定位
     */
    private fun showBackgroundLocationNotification() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel("location_permission_channel") == null) {
            val channel = NotificationChannel(
                "location_permission_channel",
                "位置权限提醒",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "提醒开启始终允许定位"
                enableVibration(true)
            }
            manager.createNotificationChannel(channel)
        }

        val settingsIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:$packageName")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, settingsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, "location_permission_channel")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("📍 请开启\"始终允许\"定位")
            .setContentText("点击这里->位置权限->选择\"始终允许\"，子女才能看到你的实时位置")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        manager.notify(2002, notification)
    }

    // ==================== 远程协助 ====================

    /**
     * 启动远程协助轮询（Service级别，App在后台也能响应）
     */
    private fun startRemoteAssistPolling() {
        // v19: 用 addAssistRequestListener，不覆盖 HomeFragment 的监听器
        val assistListener: (RemoteAssistManager.AssistRequest) -> Unit = { request ->
            Log.i(TAG, "收到远程协助请求: from=${request.fromName}")
            
            // ========== 双重保障：fullScreenIntent + 直接 startActivity ==========
            // 1. fullScreenIntent：锁屏/息屏时自动弹出全屏界面（Android 官方推荐）
            // 2. startActivity：解锁/后台时直接启动 Activity
            //    MIUI/HyperOS 可能拦截后台 startActivity，但 fullScreenIntent 通知
            //    仍然可见，用户点击即可打开
            
            // 方案1：fullScreenIntent 通知（锁屏/息屏必弹，解锁时显示高优先级通知）
            val fullScreenIntent = Intent(this, com.falldetector.diedaobao.ui.RemoteAssistActivity::class.java).apply {
                putExtra("from_name", request.fromName)
                putExtra("from_id", request.fromId)
                putExtra("remaining_seconds", request.remainingSeconds)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            val fullScreenPendingIntent = PendingIntent.getActivity(
                this, 0, fullScreenIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_MUTABLE else 0)
            )
            val notification = NotificationCompat.Builder(this, "remote_assist_channel")
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("📞 远程协助请求")
                .setContentText("${request.fromName} 请求协助操作您的手机")
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setFullScreenIntent(fullScreenPendingIntent, true)
                .setContentIntent(fullScreenPendingIntent)  // 点击通知也打开
                .setAutoCancel(true)
                .setTimeoutAfter(request.remainingSeconds * 1000L)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build()
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(3001, notification)
            
            // 方案2：同时尝试直接 startActivity（解锁/后台场景）
            // 如果被 MIUI 拦截，fullScreenIntent 通知仍然可以点击打开
            try {
                val directIntent = Intent(this, com.falldetector.diedaobao.ui.RemoteAssistActivity::class.java).apply {
                    putExtra("from_name", request.fromName)
                    putExtra("from_id", request.fromId)
                    putExtra("remaining_seconds", request.remainingSeconds)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                startActivity(directIntent)
                Log.i(TAG, "直接 startActivity 成功")
            } catch (e: Exception) {
                // MIUI/HyperOS 可能拦截后台 startActivity，不报错
                // fullScreenIntent 通知已发出，用户点击即可
                Log.w(TAG, "直接 startActivity 被拦截（MIUI/后台限制），但通知已发出: ${e.message}")
            }
        }
        RemoteAssistManager.addAssistRequestListener(assistListener)
        RemoteAssistManager.ensurePolling(this) // v19.7.5: 用ensurePolling替代startPolling，防重复启动
        Log.i(TAG, "远程协助轮询已启动（Service级）")
    }

    /**
     * 停止远程协助轮询
     */
    private fun stopRemoteAssistPolling() {
        RemoteAssistManager.stopPolling()
        Log.i(TAG, "远程协助轮询已停止")
    }
}
