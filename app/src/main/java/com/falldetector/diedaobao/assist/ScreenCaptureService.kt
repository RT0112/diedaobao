package com.falldetector.diedaobao.assist

import android.app.*
import com.falldetector.diedaobao.util.AppLogger
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.falldetector.diedaobao.R
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * 远程协助屏幕共享服务（老人端前台服务）
 * 
 * v3: ImageReader + VirtualDisplay 直接取帧（抛弃 screencap 命令行）
 * 
 * 流程：
 * 1. MediaProjection → VirtualDisplay → ImageReader Surface
 * 2. ImageReader.OnImageAvailableListener 回调取帧
 * 3. YUV→RGB→Bitmap→JPEG→Base64→HTTP POST 上传
 * 4. 子女端 HTTP poll_frame 拉帧
 */
class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "ScreenCaptureService"
        const val NOTIFICATION_ID = 3001
        const val CHANNEL_ID = "remote_assist_channel"

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        const val EXTRA_ELDER_ID = "elder_id"
        const val EXTRA_GUARDIAN_ID = "guardian_id"

        private const val BASE_URL = "http://192.168.4.19:3000"

        // MIUI/HyperOS 兼容：用绑定方式而非前台服务
        var isRunning = false
            private set

        // v19.7.2: 暴露视频流帧尺寸，供坐标映射使用
        var streamWidth: Int = 0
            private set
        var streamHeight: Int = 0
            private set
        private const val SIGNAL_URL = "$BASE_URL/remote-assist"

        // v26: 帧率提升至3fps，改善子女端延迟体验
        private const val TARGET_FPS = 3  // 3fps，平衡流畅度和网络压力
        private const val FRAME_INTERVAL_MS = (1000L / TARGET_FPS) // 333ms

        // 最大上传延迟（超过就丢帧）
        private const val MAX_UPLOAD_TIME_MS = 2000L

        @Volatile
        var instance: ScreenCaptureService? = null
            private set
    }

    enum class State {
        INIT, CONNECTING, STREAMING, DISCONNECTED, ERROR
    }

    var onStateChange: ((State, String?) -> Unit)? = null
    var onGuardianDisconnected: (() -> Unit)? = null

    // MediaProjection
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var screenWidth: Int = 720
    private var screenHeight: Int = 1280
    private var screenDpi: Int = 240

    // ImageReader 帧采集
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var frameCount = 0
    private var uploadFailCount = 0
    private var lastUploadTime = 0L
    private val isDisposed = AtomicBoolean(false)

    // 会话
    private var elderId: String? = null
    private var guardianId: String? = null
    private var currentState = State.INIT

    // screencap 兜底模式相关
    private var screencapExecutor: ScheduledExecutorService? = null
    private var screencapFuture: ScheduledFuture<*>? = null
    private var isScreencapMode = false
    private val screencapFrameFile: File by lazy { File(cacheDir, "ra_frame.png") }
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.i(TAG, "MediaProjection 被系统停止")
            updateNotification("屏幕共享已停止")
            stopScreencapMode()
        }
    }

    // JPEG 压缩配置（复用避免每次分配）
    private val jpegBos = ByteArrayOutputStream(256 * 1024) // 256KB buffer
    private val yuvToRgb = YuvImageToRgb()

    // 上传线程池（替代每帧创建 HandlerThread，避免线程泄漏）
    private val uploadExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "FrameUpload").apply { isDaemon = true }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        isRunning = true
        createNotificationChannel()
        // Android Q+ (API 29+): MediaProjection 要求 Service 必须是前台服务
        // startForeground() 必须在 onCreate 后 5 秒内调用（Android 14+）
        startForeground(NOTIFICATION_ID, buildNotification("等待连接..."))
        Log.i(TAG, "ScreenCaptureService created (v4 foreground)")
    }

    // Binder for Activity binding
    inner class LocalBinder : android.os.Binder() {
        fun getService(): ScreenCaptureService = this@ScreenCaptureService
    }
    private val binder = LocalBinder()

    override fun onBind(intent: Intent?): IBinder? {
        Log.i(TAG, "onBind called")
        isRunning = true
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand called")

        if (intent == null) {
            AppLogger.w(TAG, "intent is null, stopping")
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_DATA)
        }
        elderId = intent.getStringExtra(EXTRA_ELDER_ID)
        guardianId = intent.getStringExtra(EXTRA_GUARDIAN_ID)

        Log.i(TAG, "onStartCommand: elderId=$elderId, guardianId=$guardianId, resultCode=$resultCode")

        if (resultCode == -1 || data == null || elderId.isNullOrBlank()) {
            AppLogger.e(TAG, "缺少必要参数! elderId=${elderId}, resultCode=$resultCode, data=${data != null}")
            stopSelf()
            return START_NOT_STICKY
        }

        // 显示通知
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification("正在初始化屏幕采集..."))

        startCapture(resultCode, data)

        return START_STICKY
    }

    /** Activity 绑定后调用，确保参数已设置 */
    fun startAfterBind(resultCode: Int, data: Intent, eId: String, gId: String?) {
        elderId = eId
        guardianId = gId
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification("正在初始化屏幕采集..."))
        startCapture(resultCode, data)
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy: frames=$frameCount, uploadFails=$uploadFailCount, screencap=$isScreencapMode")
        isDisposed.set(true)
        isRunning = false
        stopScreencapMode()
        stopCapture()
        uploadExecutor.shutdownNow()
        captureThread?.quitSafely()
        // 取消通知
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIFICATION_ID)
        instance = null
        super.onDestroy()
    }

    // ==================== 帧采集（ImageReader）====================

    private fun startCapture(resultCode: Int, data: Intent) {
        try {
            updateNotification("获取MediaProjection...")
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = manager.getMediaProjection(resultCode, data)
            // v0.43.2: 注册回调，监控 MIUI 是否中途停止 MediaProjection
            mediaProjection?.registerCallback(projectionCallback, captureHandler)
            Log.i(TAG, "MediaProjection 已获取")

            val metrics = resources.displayMetrics
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
            screenDpi = metrics.densityDpi
            Log.i(TAG, "屏幕: ${screenWidth}x${screenHeight}, dpi=$screenDpi")

            // 创建 ImageReader（最多缓存2帧，避免内存堆积）
            // v0.43.0: 增加安全检查
            updateNotification("创建ImageReader ${screenWidth}x${screenHeight}...")
            
            if (screenWidth <= 0 || screenHeight <= 0) {
                screenWidth = 720
                screenHeight = 1280
            }
            
            try {
                imageReader = ImageReader.newInstance(
                    screenWidth, screenHeight,
                    PixelFormat.RGBA_8888, 3
                )
            } catch (e: Exception) {
                AppLogger.e(TAG, "ImageReader失败: ${e.message}", e)
                updateNotification("ImageReader失败")
                return
            }
            Log.i(TAG, "ImageReader ok")

            // 创建后台线程处理帧
            captureThread = HandlerThread("FrameProcessor").apply { start() }
            captureHandler = Handler(captureThread!!.looper)

            imageReader?.setOnImageAvailableListener({ reader ->
                if (isDisposed.get() || reader == null) return@setOnImageAvailableListener
                try { processFrame(reader) }
                catch (e: Exception) { AppLogger.e(TAG, "processFrame异常: ${e.message}") }
            }, captureHandler)
            Log.i(TAG, "OnImageAvailableListener 已设置")

            // vFix: 必须先检查 MediaProjection
            if (mediaProjection == null) {
                AppLogger.e(TAG, "MediaProjection为null!")
                updateNotification("MediaProjection获取失败")
                startFallbackScreenshotMode()
                return
            }
            
            // 创建 VirtualDisplay
            // vFix: AUTO_MIRROR 优先（OWN_CONTENT_ONLY/PUBLIC 只会创建空白VD，无画面！）
            // 用原生分辨率（MIUI 对降分辨率的 VD 不兼容）
            val vdWidth = screenWidth
            val vdHeight = screenHeight
            updateNotification("创建VirtualDisplay ${vdWidth}x${vdHeight}...")
            
            val flagSets = listOf(
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,  // 首选：镜像屏幕
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
            )
            var vdCreated = false
            for ((idx, flags) in flagSets.withIndex()) {
                try {
                    Log.i(TAG, "尝试VD flag组合${idx+1}: 0x${flags.toString(16)}")
                    val vd = mediaProjection?.createVirtualDisplay(
                        "RemoteAssist",
                        vdWidth, vdHeight, screenDpi,
                        flags,
                        imageReader?.surface,
                        null, null
                    )
                    if (vd != null) {
                        virtualDisplay = vd
                        vdCreated = true
                        Log.i(TAG, "VD创建成功: flag组合${idx+1}, 0x${flags.toString(16)}")
                        break
                    } else {
                        AppLogger.w(TAG, "VD返回null: flag组合${idx+1}")
                    }
                } catch (e: Exception) {
                    AppLogger.w(TAG, "VD异常: flag组合${idx+1}: ${e.message}")
                }
            }

            if (!vdCreated) {
                AppLogger.e(TAG, "VD所有flag组合均失败: MIUI/HyperOS系统限制，启动AccessibilityService截图替补")
                startFallbackScreenshotMode()
                return
            }
            
            Log.i(TAG, "VD ok")

            // 通知云端屏幕就绪
            notifyScreenReady()

            updateState(State.STREAMING, null)
            updateNotification("✅ 推流已启动, 等待首帧...")
            Log.i(TAG, "帧采集已启动 (ImageReader + VirtualDisplay)")

        } catch (e: Exception) {
            val msg = "❌ 采集失败: ${e.message}"
            AppLogger.e(TAG, "启动采集失败: ${e.message}", e)
            updateNotification(msg)
            updateState(State.ERROR, "采集失败: ${e.message}")
        }
    }

    // ==================== 截图替补模式 ====================

    /**
     * 截图替补模式：使用 AccessibilityService.takeScreenshot()
     * 
     * 比 screencap 更可靠：
     * - 不依赖 root 权限
     * - Android 11+ 原生支持
     * - 直接拿到 Bitmap，省略文件读写
     */
    private fun startFallbackScreenshotMode() {
        isScreencapMode = true  // 复用标志位
        Log.i(TAG, "启动 AccessibilityService 截图替补模式 (0.5fps)")
        updateNotification("📷 截图替补模式")
        notifyScreenReady()
        updateState(State.STREAMING, null)

        screencapExecutor = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "AssistScreenshot").apply { isDaemon = true }
        }
        screencapFuture = screencapExecutor?.scheduleAtFixedRate({
            if (isDisposed.get()) return@scheduleAtFixedRate
            captureFrameViaAccessibility()
        }, 500, 2000, TimeUnit.MILLISECONDS)
    }

    private fun stopScreencapMode() {
        screencapFuture?.cancel(false)
        screencapExecutor?.shutdown()
        screencapExecutor = null
        screencapFuture = null
        isScreencapMode = false
        Log.i(TAG, "截图替补模式已停止")
    }

    /**
     * AccessibilityService 截图 → 缩放 → JPEG → Base64 → 上传
     */
    private fun captureFrameViaAccessibility() {
        try {
            val assistService = RemoteAssistService.instance
            if (assistService == null) {
                if (frameCount == 0) {
                    AppLogger.w(TAG, "⚠️ AccessibilityService未运行!")
                    updateNotification("⚠️ 请开启无障碍服务（设置→无障碍→跌倒宝）")
                }
                return
            }

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                AppLogger.e(TAG, "⚠️ 系统版本低于Android 11，不支持无障碍截图")
                updateNotification("⚠️ 此功能需要 Android 11+")
                return
            }

            assistService.takeScreenshot { bitmap ->
                if (bitmap == null) {
                    if (frameCount == 0) {
                        AppLogger.w(TAG, "无障碍截图返回null")
                    }
                    return@takeScreenshot
                }

                try {
                    // 缩放至 360p（平衡画质与传输量）
                    val targetW = 360
                    val targetH = maxOf(1, (bitmap.height * targetW.toFloat() / bitmap.width).toInt())
                    streamWidth = targetW
                    streamHeight = targetH
                    val scaled = Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
                    bitmap.recycle()

                    jpegBos.reset()
                    scaled.compress(Bitmap.CompressFormat.JPEG, 25, jpegBos)
                    scaled.recycle()

                    val jpegBytes = jpegBos.toByteArray()
                    if (jpegBytes.isEmpty()) return@takeScreenshot

                    val b64 = android.util.Base64.encodeToString(jpegBytes, android.util.Base64.NO_WRAP)

                    frameCount++
                    if (frameCount <= 3) {
                        val msg = "📸 截图帧${frameCount}: ${jpegBytes.size / 1024}KB"
                        Log.i(TAG, msg)
                        AppLogger.i(TAG, "[截图上传] #${frameCount} ${jpegBytes.size / 1024}KB")
                        updateNotification(msg)
                    } else if (frameCount % 5 == 0) {
                        updateNotification("已截图${frameCount}帧")
                    }

                    uploadFrameAsync(b64, frameCount, targetW, targetH)
                } catch (e: Exception) {
                    AppLogger.e(TAG, "截图处理异常: ${e.message}")
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "截图替补异常: ${e.message}", e)
        }
    }

    private fun stopCapture() {
        try {
            imageReader?.close()
            imageReader = null
            virtualDisplay?.release()
            virtualDisplay = null
            mediaProjection?.unregisterCallback(projectionCallback)
            mediaProjection?.stop()
            mediaProjection = null
            Log.i(TAG, "采集已停止: frames=$frameCount")
        } catch (e: Exception) {
            AppLogger.e(TAG, "stopCapture异常: ${e.message}")
        }
    }

    /**
     * ImageReader 帧回调
     */
    private fun processFrame(reader: ImageReader) {
        val image: Image
        try {
            image = reader.acquireLatestImage()
        } catch (e: Exception) {
            AppLogger.w(TAG, "acquireLatestImage失败: ${e.message}")
            return
        }

        if (image == null) {
            if (frameCount == 0) {
                AppLogger.w(TAG, "acquireLatestImage返回null (ImageReader尚未有帧)")
                updateNotification("等待首帧...")
            }
            return
        }

        try {
            // 帧率控制：距上一帧不到间隔就丢
            val now = System.currentTimeMillis()
            if (now - lastUploadTime < FRAME_INTERVAL_MS) {
                image.close()
                return
            }

            val planes = image.planes
            if (planes.isEmpty()) {
                AppLogger.e(TAG, "❌ image.planes为空!")
                updateNotification("❌ 图像格式错误: planes为空")
                image.close()
                return
            }

            val bitmap = imageToBitmap(image, planes)

            if (bitmap != null) {
                // 缩放至 360p（平衡画质与传输量）
                val targetW = 360
                val targetH = maxOf(1, (bitmap.height * targetW.toFloat() / bitmap.width).toInt())
                streamWidth = targetW
                streamHeight = targetH
                val scaled = Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
                bitmap.recycle()

                // JPEG 压缩并上传（25% 质量，平衡画质与传输）
                jpegBos.reset()
                val compressed = scaled.compress(Bitmap.CompressFormat.JPEG, 25, jpegBos)
                scaled.recycle()
                if (!compressed) {
                    AppLogger.e(TAG, "❌ JPEG压缩失败!")
                    updateNotification("❌ JPEG压缩失败")
                    image.close()
                    return
                }

                val jpegBytes = jpegBos.toByteArray()
                if (jpegBytes.isEmpty()) {
                    AppLogger.e(TAG, "❌ JPEG数据为空!")
                    updateNotification("❌ JPEG数据为空")
                    bitmap.recycle()
                    image.close()
                    return
                }

                val b64 = android.util.Base64.encodeToString(jpegBytes, android.util.Base64.NO_WRAP)
                bitmap.recycle()

                // 前5帧详细日志+通知
                if (frameCount < 5) {
                    val msg = "📸 帧${frameCount}: ${jpegBytes.size/1024}KB, plane=${planes.size}, stride=${planes[0].rowStride}"
                    Log.i(TAG, msg)
                    AppLogger.i(TAG, "[帧上传] #${frameCount} ${jpegBytes.size/1024}KB, elderId=$elderId")
                    updateNotification(msg)
                }
                
                uploadFrameAsync(b64, frameCount, targetW, targetH)
                lastUploadTime = now
                frameCount++

                if (frameCount % 15 == 0) {
                    val msg = "已上传${frameCount}帧, 失败${uploadFailCount}"
                    Log.i(TAG, msg)
                    updateNotification(msg)
                }
            } else {
                AppLogger.e(TAG, "❌ imageToBitmap返回null! planeCount=${planes.size}, pixelStride=${planes[0].pixelStride}, rowStride=${planes[0].rowStride}")
                updateNotification("❌ 图像转换失败")
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "❌ processFrame异常[${frameCount}]: ${e.message}", e)
            updateNotification("❌ 帧处理异常: ${e.javaClass.simpleName}")
        } finally {
            image.close()
        }
    }

    /**
     * Image → Bitmap
     * ImageReader Plane[0] 是 RGBA_8888 格式的单平面
     * 
     * ⚠️ 关键字节序差异：
     * ImageReader RGBA_8888 buffer 字节序: R, G, B, A（每个分量1字节）
     * Bitmap ARGB_8888 内存字节序（little-endian CPU）: B, G, R, A
     * 
     * 直接 copyPixelsFromBuffer 或 getInt 会导致 R↔B 互换（红蓝反转），
     * 必须 swap R 和 B 通道。
     */
    private fun imageToBitmap(image: Image, planes: Array<Image.Plane>): Bitmap? {
        if (planes.isEmpty()) return null

        val plane = planes[0]
        val buffer: ByteBuffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * screenWidth

        buffer.rewind()

        val bitmap = Bitmap.createBitmap(screenWidth, screenHeight, Bitmap.Config.ARGB_8888)

        if (pixelStride == 4 && rowPadding == 0) {
            // 快速路径：无 padding，copyPixelsFromBuffer 后 swap R↔B
            bitmap.copyPixelsFromBuffer(buffer)
            val pixels = IntArray(screenWidth * screenHeight)
            bitmap.getPixels(pixels, 0, screenWidth, 0, 0, screenWidth, screenHeight)
            for (i in pixels.indices) {
                val p = pixels[i]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                val a = (p shr 24) and 0xFF
                pixels[i] = (a shl 24) or (b shl 16) or (g shl 8) or r  // swap R↔B
            }
            bitmap.setPixels(pixels, 0, screenWidth, 0, 0, screenWidth, screenHeight)
        } else {
            // 有 padding，逐行拷贝 + swap R↔B
            val pixels = IntArray(screenWidth * screenHeight)
            for (y in 0 until screenHeight) {
                for (x in 0 until screenWidth) {
                    val offset = y * rowStride + x * pixelStride
                    // buffer 按字节读: R, G, B, A
                    val r = buffer.get(offset).toInt() and 0xFF
                    val g = buffer.get(offset + 1).toInt() and 0xFF
                    val b = buffer.get(offset + 2).toInt() and 0xFF
                    val a = buffer.get(offset + 3).toInt() and 0xFF
                    // Bitmap ARGB_8888: 0xAARRGGBB
                    pixels[y * screenWidth + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
            bitmap.setPixels(pixels, 0, screenWidth, 0, 0, screenWidth, screenHeight)
        }

        return bitmap
    }

    /**
     * 异步上传帧（使用单线程线程池，避免每帧创建 HandlerThread 导致线程泄漏）
     */
    private fun uploadFrameAsync(b64: String, frameNum: Int, w: Int, h: Int) {
        // WS 优先发送帧（低延迟、无HTTP开销）
        if (com.falldetector.diedaobao.cloud.WSClient.isWSConnected()) {
            val gid = guardianId ?: elderId ?: ""
            com.falldetector.diedaobao.cloud.WSClient.pushAssistFrame(gid, b64, w, h, frameNum)
            uploadFailCount = 0
            if (frameNum < 5) {
                Log.i(TAG, "✅ 帧${frameNum}WS发送成功")
            }
            return
        }
        
        // HTTP 降级
        uploadExecutor.execute {
            try {
                val url = URL(SIGNAL_URL)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.connectTimeout = 10000  // 10s 连接超时
                conn.readTimeout = 15000      // 15s 读取超时

                val body = org.json.JSONObject().apply {
                    put("action", "upload_frame")
                    put("userId", elderId)
                    put("frameNum", frameNum)
                    put("width", w)
                    put("height", h)
                    put("data", b64)
                }

                val bodyBytes = body.toString().toByteArray(Charsets.UTF_8)
                conn.outputStream.write(bodyBytes)
                conn.outputStream.flush()
                conn.outputStream.close()

                val code = conn.responseCode
                val responseBody = try {
                    conn.inputStream.bufferedReader().readText()
                } catch (_: Exception) {
                    conn.errorStream?.bufferedReader()?.readText() ?: ""
                }
                
                if (code == 200) {
                    uploadFailCount = 0
                    val msg = "✅ 帧${frameNum}上传成功 (${bodyBytes.size/1024}KB)"
                    Log.i(TAG, msg)
                    AppLogger.i(TAG, "[帧上传成功] #${frameNum} ${bodyBytes.size/1024}KB")
                    if (frameNum < 5) updateNotification(msg)
                    
                    // 检查云端返回的 status，如果不再是 active 则主动停掉
                    try {
                        val respJson = org.json.JSONObject(responseBody)
                        val respData = respJson.optJSONObject("data") ?: respJson
                        val status = respData.optString("status", "")
                        if (status == "normal" || status == "idle" || status == "ended") {
                            AppLogger.w(TAG, "云端状态已变: $status，主动停止屏幕共享")
                            Handler(mainLooper).post {
                                onGuardianDisconnected?.invoke()
                                stopSelf()
                            }
                        }
                    } catch (_: Exception) {}
                } else {
                    uploadFailCount++
                    val msg = "❌ 帧${frameNum}上传失败: HTTP $code"
                    AppLogger.w(TAG, "$msg resp=$responseBody")
                    updateNotification(msg)
                    // 连续失败5次，可能是云端已断开，主动停掉
                    if (uploadFailCount >= 5) {
                        AppLogger.e(TAG, "连续${uploadFailCount}次上传失败，主动停止屏幕共享")
                        Handler(mainLooper).post {
                            onGuardianDisconnected?.invoke()
                            stopSelf()
                        }
                    }
                }
            } catch (e: Exception) {
                uploadFailCount++
                val msg = "❌ 帧${frameNum}上传异常: ${e.javaClass.simpleName}"
                AppLogger.e(TAG, msg)
                updateNotification(msg)
            }
        }
    }

    // ==================== 通知 ====================

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "远程协助",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "远程协助服务运行中"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String = "家属正在帮您操作手机"): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, com.falldetector.diedaobao.ui.MainActivity::class.java),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("远程协助中")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    private fun updateNotification(text: String) {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, com.falldetector.diedaobao.ui.MainActivity::class.java),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("远程协助中")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setOngoing(true)
            .setContentIntent(pi)
            .setOnlyAlertOnce(true)
            .build()
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, notification)
    }

    private fun notifyScreenReady() {
        try {
            thread(name = "NotifyReady") {
                try {
                val url = URL(SIGNAL_URL)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.connectTimeout = 5000
                conn.readTimeout = 5000

                val body = org.json.JSONObject().apply {
                    put("action", "screen_ready")
                    put("userId", elderId)
                    put("width", screenWidth)
                    put("height", screenHeight)
                }
                conn.outputStream.write(body.toString().toByteArray(Charsets.UTF_8))
                    val resp = conn.inputStream.bufferedReader().readText()
                    Log.i(TAG, "screen_ready: resp=$resp")
                } catch (e: Exception) {
                    Log.e(TAG, "screen_ready网络请求异常: ${e.message}")
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "screen_ready异常: ${e.message}")
        }
    }

    private fun updateState(state: State, message: String?) {
        currentState = state
        Log.i(TAG, "状态: $state, msg=$message")
        Handler(mainLooper).post {
            onStateChange?.invoke(state, message)
        }
    }
}

/**
 * YUV420 → RGB 转换器（如果设备返回 YUV 格式时使用）
 * 
 * 实际上 ImageReader 配置了 RGBA_8888，不需要这个。
 * 保留作为兜底。
 */
class YuvImageToRgb {
    fun yuv420ToArgb8888(yData: ByteArray, uData: ByteArray, vData: ByteArray,
                         yRowStride: Int, uvRowStride: Int, uvPixelStride: Int,
                         width: Int, height: Int): IntArray {
        val out = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val yIdx = y * yRowStride + x
                val uvIdx = (y / 2) * uvRowStride + (x / 2) * uvPixelStride

                val yVal = yData[yIdx].toInt() and 0xFF
                val uVal = uData[uvIdx].toInt() and 0xFF - 128
                val vVal = vData[uvIdx].toInt() and 0xFF - 128

                var r = (yVal + (1.402 * vVal)).toInt()
                var g = (yVal - (0.344 * uVal) - (0.714 * vVal)).toInt()
                var b = (yVal + (1.772 * uVal)).toInt()

                r = r.coerceIn(0, 255)
                g = g.coerceIn(0, 255)
                b = b.coerceIn(0, 255)

                out[y * width + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return out
    }
}
