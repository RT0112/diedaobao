package com.falldetector.diedaobao.ui

import android.Manifest
import com.falldetector.diedaobao.util.AppLogger
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.*
import com.falldetector.diedaobao.R
import com.falldetector.diedaobao.data.FallEvent
import com.falldetector.diedaobao.service.FallDetectionService
import com.falldetector.diedaobao.notify.EmergencyNotifier
import com.falldetector.diedaobao.cloud.CloudBaseClient
import kotlinx.coroutines.*

class ConfirmActivity : AppCompatActivity() {

    private var countdownJob: Job? = null
    private var latitude = 0.0
    private var longitude = 0.0
    private var hasLocationFromService = false  // v0.46: Service传来的位置更可靠
    private var vibrator: Vibrator? = null
    private var mediaPlayer: MediaPlayer? = null

    private var currentEventId: Long = 0
    private var peakAcc = 0f
    private var postureAngle = 0f
    // v0.29.6: 新增详细信息（供微信通知）
    private var mlProbability = 0f
    private var physScore = 0f
    private var impactG = 0f
    private var fallHeight = 0f

    private var alarmSoundEnabled = true
    private var alarmVibrateEnabled = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ========== 锁屏弹窗：在 setContentView 之前设置！==========
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR
        )

        WindowCompat.setDecorFitsSystemWindows(window, true)
        // 不隐藏系统栏，让布局正常显示
        // WindowInsetsControllerCompat(window, window.decorView).let { controller ->
        //     controller.hide(WindowInsetsCompat.Type.systemBars())
        //     controller.systemBarsBehavior =
        //         WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        // }

        // ========== 加载告警设置 ==========
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        alarmSoundEnabled = prefs.getBoolean(SettingsFragment.PREF_ALARM_SOUND, true)
        alarmVibrateEnabled = prefs.getBoolean(SettingsFragment.PREF_ALARM_VIBRATE, true)

        peakAcc = intent.getFloatExtra("peak_acc", 0f)
        val postureFromIntent = intent.getFloatExtra("posture_angle", 0f)
        val confidence = intent.getFloatExtra("confidence", 0f)
        val detectionMethod = intent.getStringExtra("detection_method") ?: "unknown"
        mlProbability = intent.getFloatExtra("ml_probability", 0f)
        // v0.29.6: 新增详细信息
        physScore = intent.getFloatExtra("phys_score", 0f)
        impactG = intent.getFloatExtra("impact_g", 0f)
        fallHeight = intent.getFloatExtra("fall_height", 0f)
        postureAngle = if (postureFromIntent > 0f) postureFromIntent else estimatePostureFromPeak(peakAcc)

        // v0.46: 优先使用FallDetectionService传来的位置（比onCreate里取getLastKnownLocation更可靠）
        val latFromService = intent.getDoubleExtra("latitude", Double.NaN)
        val lngFromService = intent.getDoubleExtra("longitude", Double.NaN)
        if (!latFromService.isNaN() && !lngFromService.isNaN()) {
            latitude = latFromService
            longitude = lngFromService
            hasLocationFromService = true
            Log.i(TAG, "从Service获取位置: $latitude, $longitude")
        }

        AppLogger.w(TAG, "===== ConfirmActivity 启动 =====")
        AppLogger.w(TAG, "冲击=${"%.2f".format(peakAcc)}g | 姿势=${"%.0f".format(postureAngle)}° | " +
                "方式=$detectionMethod | ML概率=${"%.3f".format(mlProbability)}")
        Log.i(TAG, "sound=$alarmSoundEnabled, vibrate=$alarmVibrateEnabled")

        setContentView(R.layout.activity_confirm)

        val tvTitle = findViewById<TextView>(R.id.tv_confirm_title)
        val tvInfo = findViewById<TextView>(R.id.tv_confirm_info)
        val tvCountdown = findViewById<TextView>(R.id.tv_countdown)
        val btnOk = findViewById<Button>(R.id.btn_i_am_ok)
        val btnHelp = findViewById<Button>(R.id.btn_need_help)

        val methodLabel = when (detectionMethod) {
            "ml" -> "🤖 ML模型"
            "threshold" -> "📊 阈值法"
            "test" -> "🧪 测试"
            else -> detectionMethod
        }
        tvInfo.text = buildString {
            appendLine("冲击力: ${"%.1f".format(peakAcc)}g")
            appendLine("姿态变化: ${"%.0f".format(postureAngle)}°")
            appendLine("置信度: ${"%.0f".format(confidence * 100)}%")
            appendLine("检测方式: $methodLabel")
            if (mlProbability > 0f) appendLine("ML概率: ${"%.1f".format(mlProbability * 100)}%")
        }

        // ========== 立即启动告警 ==========
        startAlarmIfEnabled()

        recordFallEvent()
        getLocation()

        val countdownSeconds = prefs.getInt("countdown_seconds", 30)

        countdownJob = CoroutineScope(Dispatchers.Main).launch {
            var remaining = countdownSeconds
            while (remaining > 0) {
                tvCountdown.text = "⏱ ${remaining}秒后自动通知紧急联系人"
                btnOk.text = "✅ 我没事（${remaining}s）"
                delay(1000)
                remaining--
            }
            onCountdownFinished(tvTitle, tvCountdown, btnOk, btnHelp)
        }

        btnOk.setOnClickListener {
            countdownJob?.cancel()
            stopAlarm()
            Log.i(TAG, "用户确认安全")
            Toast.makeText(this, "警报已取消", Toast.LENGTH_SHORT).show()
            cancelAlertNotification()
            updateFallEvent(isFalsePositive = true, notificationSent = false)
            finish()
        }

        btnHelp.setOnClickListener {
            countdownJob?.cancel()
            stopAlarm()
            Log.i(TAG, "用户主动求助")
            Toast.makeText(this, "正在联系紧急联系人...", Toast.LENGTH_SHORT).show()
            onCountdownFinished(tvTitle, tvCountdown, btnOk, btnHelp)
        }
    }

    private fun estimatePostureFromPeak(peak: Float): Float {
        return when {
            peak < 0.3f -> 10f
            peak < 1.0f -> 25f
            peak < 1.5f -> 40f
            peak < 2.0f -> 55f
            peak < 2.5f -> 65f
            peak < 3.0f -> 75f
            peak < 4.0f -> 82f
            else -> 90f
        }
    }

    private fun recordFallEvent() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val app = com.falldetector.diedaobao.FallDetectionApp.instance
                val event = FallEvent(
                    peakAcceleration = peakAcc,
                    postureAngle = postureAngle,
                    isConfirmed = false,
                    isFalsePositive = false,
                    notificationSent = false
                )
                currentEventId = app.repository.fallEventDao.insert(event)
                Log.i(TAG, "跌倒事件已记录: id=$currentEventId")
            } catch (e: Exception) {
                AppLogger.e(TAG, "记录失败: ${e.message}")
            }
        }
    }

    private fun updateFallEvent(isFalsePositive: Boolean, notificationSent: Boolean) {
        if (currentEventId == 0L) return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val app = com.falldetector.diedaobao.FallDetectionApp.instance
                val event = app.repository.fallEventDao.getById(currentEventId)
                if (event != null) {
                    app.repository.fallEventDao.update(event.copy(
                        latitude = latitude,
                        longitude = longitude,
                        isConfirmed = true,
                        isFalsePositive = isFalsePositive,
                        notificationSent = notificationSent
                    ))
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "更新失败: ${e.message}")
            }
        }
    }

    private fun onCountdownFinished(
        tvTitle: TextView, tvCountdown: TextView,
        btnOk: Button, btnHelp: Button
    ) {
        tvTitle.text = "🆘 正在通知紧急联系人..."
        tvCountdown.text = "请稍候"
        btnOk.isEnabled = false
        btnHelp.isEnabled = false
        stopAlarm()

        val alreadyNotified = intent.getBooleanExtra("notification_sent", false)
        Log.w(TAG, "onCountdownFinished: alreadyNotified=$alreadyNotified, peakAcc=$peakAcc, impactG=$impactG, mlProbability=$mlProbability")
        
        if (!alreadyNotified) {
            // v0.30.7: triggerEmergency 改为 suspend，用协程在 IO 线程执行
            //   根因：Room 主线程检查无法被 runBlocking 绕过，必须切到 IO 线程
            CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
                Log.w(TAG, "onCountdownFinished: 开始执行EmergencyNotifier")
                EmergencyNotifier.triggerEmergency(
                    context = this@ConfirmActivity,
                    latitude = latitude,
                    longitude = longitude,
                    mlProbability = mlProbability,
                    impactG = impactG,
                    fallHeight = fallHeight,
                    physScore = physScore
                )
                
                // v0.33.0: 上报到 CloudBase
                Log.w(TAG, "onCountdownFinished: 开始执行CloudBaseClient.reportFall")
                val reported = CloudBaseClient.reportFall(
                    context = this@ConfirmActivity,
                    latitude = latitude,
                    longitude = longitude,
                    impactG = impactG,
                    ffDuration = 0L, // TODO: 从 FallDetector 传递
                    mlScore = mlProbability,
                    physicalScore = physScore
                )
                Log.w(TAG, "CloudBase上报结果: reported=$reported")
            }
            
            updateFallEvent(isFalsePositive = false, notificationSent = true)
            Toast.makeText(this, "紧急联系人已通知！", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "紧急联系人已于跌倒检测时通知", Toast.LENGTH_SHORT).show()
        }

        cancelAlertNotification()

        CoroutineScope(Dispatchers.Main).launch {
            delay(5000)
            finish()
        }
    }

    private fun startAlarmIfEnabled() {
        Log.i(TAG, "startAlarmIfEnabled: sound=$alarmSoundEnabled, vibrate=$alarmVibrateEnabled")
        if (alarmVibrateEnabled) {
            startVibration()
        } else {
            Log.i(TAG, "震动已被用户关闭")
        }
        if (alarmSoundEnabled) {
            startSound()
        } else {
            Log.i(TAG, "声音已被用户关闭")
        }
    }

    private fun startVibration() {
        Log.i(TAG, "startVibration() 开始")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.VIBRATE) != PackageManager.PERMISSION_GRANTED) {
                AppLogger.w(TAG, "VIBRATE权限未授予，跳过震动")
                return
            }
        }

        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        if (vibrator == null) {
            AppLogger.e(TAG, "Vibrator为null，跳过震动")
            return
        }

        val hasVibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            vibrator?.hasVibrator() == true
        } else {
            @Suppress("DEPRECATION")
            vibrator?.hasVibrator() == true
        }

        if (!hasVibrator) {
            AppLogger.w(TAG, "设备无震动功能")
            return
        }

        // 震动：强有力警报模式
        val pattern = longArrayOf(0, 1000, 500, 1000, 500, 1000)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
                Log.i(TAG, "震动已启动 VibrationEffect (API ${Build.VERSION.SDK_INT})")
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(pattern, -1)
                Log.i(TAG, "震动已启动 legacy (API ${Build.VERSION.SDK_INT})")
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "震动失败: ${e.message}")
        }
    }

    private fun startSound() {
        try {
            val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_NOTIFICATION)
            audioManager.setStreamVolume(AudioManager.STREAM_NOTIFICATION, maxVol, 0)

            val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

            Log.i(TAG, "startSound() 播放: $soundUri")

            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(this@ConfirmActivity, soundUri)
                isLooping = true
                setVolume(1.0f, 1.0f)
                prepare()
                start()
            }
            Log.i(TAG, "声音已开始")
        } catch (e: Exception) {
            AppLogger.e(TAG, "声音失败: ${e.message}")
        }
    }

    private fun stopAlarm() {
        try {
            vibrator?.cancel()
            mediaPlayer?.let {
                if (it.isPlaying) {
                    it.stop()
                    it.release()
                }
            }
            mediaPlayer = null
            Log.i(TAG, "告警已停止")
        } catch (e: Exception) {
            AppLogger.e(TAG, "停止告警失败: ${e.message}")
        }
    }

    private fun getLocation() {
        // v0.46: 如果FallDetectionService已传了位置，不再覆盖
        if (hasLocationFromService) {
            Log.i(TAG, "使用Service传来的位置: $latitude, $longitude")
            return
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) return

        try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
            val loc = lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
            loc?.let {
                latitude = it.latitude
                longitude = it.longitude
                Log.i(TAG, "位置: $latitude, $longitude")
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "获取位置失败: ${e.message}")
        }
    }

    private fun cancelAlertNotification() {
        try {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.cancel(FallDetectionService.ALERT_NOTIFICATION_ID)
        } catch (e: Exception) {
            AppLogger.e(TAG, "取消通知失败: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAlarm()
    }

    companion object {
        private const val TAG = "ConfirmActivity"
    }
}
