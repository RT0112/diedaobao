package com.falldetector.diedaobao.util

import com.falldetector.diedaobao.config.ServerConfig
import android.content.Context
import android.util.Log
import java.io.PrintWriter
import java.io.StringWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.net.ssl.HttpsURLConnection

/**
 * AppLogger — 自动云端上传的关键日志工具
 *
 * 使用方法：直接替代 android.util.Log
 *   Log.e(tag, msg) → AppLogger.e(tag, msg)
 *   Log.w(tag, msg) → AppLogger.w(tag, msg)
 *   Log.i(tag, msg) → AppLogger.i(tag, msg)
 *
 * 自动行为：
 * - ERROR / WARN：立即上传到云端
 * - INFO / DEBUG：仅本地 Logcat（不上传，省流量）
 * - 未初始化时：fallback 到普通 Log
 *
 * 云函数：upload-log（与 LogUploader 共用）
 * 集合：logs
 */
object AppLogger {

    private const val TAG = "AppLogger"
    // URL已迁移到ServerConfig
    private val UPLOAD_URL = ServerConfig.UPLOAD_LOG_URL

    // 哪些级别要上传云端
    private val UPLOAD_LEVELS = setOf("ERROR", "WARN", "CRASH")
    // 最大单条日志字符数（云端截断）
    private const val MAX_MESSAGE_LEN = 3000

    @Volatile
    private var initialized = false

    @Volatile
    private var userId: String = "not_initialized"

    private lateinit var appContext: Context

    // ─────────────────────────────────────────────
    // 公开 API（替代 android.util.Log）
    // ─────────────────────────────────────────────

    fun init(context: Context, userId: String) {
        this.appContext = context.applicationContext
        this.userId = userId
        this.initialized = true
        Log.i(TAG, "AppLogger 已初始化，userId=$userId")
    }

    fun setUserId(newUserId: String) {
        this.userId = newUserId
    }

    fun e(tag: String, message: String) = log("ERROR", tag, message)
    fun e(tag: String, message: String, tr: Throwable) = log("ERROR", tag, message, tr)
    fun w(tag: String, message: String) = log("WARN", tag, message)
    fun w(tag: String, message: String, tr: Throwable) = log("WARN", tag, message, tr)
    fun i(tag: String, message: String) = log("INFO", tag, message)
    fun d(tag: String, message: String) = log("DEBUG", tag, message)

    // ─────────────────────────────────────────────
    // 核心逻辑
    // ─────────────────────────────────────────────

    private fun log(level: String, tag: String, message: String, throwable: Throwable? = null) {
        // ① 写到 Logcat（总是）
        if (throwable != null) {
            Log.println(priorityOf(level), tag, "$message\n${getStackTraceString(throwable)}")
        } else {
            Log.println(priorityOf(level), tag, message)
        }

        // ② 上传云端（仅 ERROR/WARN）
        if (level in UPLOAD_LEVELS) {
            val stackTrace = throwable?.let { getStackTraceString(it) }
            uploadAsync(userId, level, tag, message, stackTrace)
        }
    }

    private fun uploadAsync(
        userId: String,
        level: String,
        tag: String,
        message: String,
        stackTrace: String?
    ) {
        Thread {
            try {
                val payload = buildJson(userId, level, tag, message, stackTrace)
                val result = sendRequest(payload)
                if (result != null) {
                    Log.d(TAG, "云端日志上传成功 [$level] $tag")
                }
            } catch (e: Exception) {
                // 上传失败不影响主流程，静默
            }
        }.start()
    }

    // ─────────────────────────────────────────────
    // 网络请求
    // ─────────────────────────────────────────────

    private fun buildJson(
        userId: String,
        level: String,
        tag: String,
        message: String,
        stackTrace: String?
    ): String {
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val msg = escapeJson(message.take(MAX_MESSAGE_LEN))
        val trace = stackTrace?.let { escapeJson(it.take(MAX_MESSAGE_LEN)) }

        return buildString {
            append("{")
            append("\"action\":\"upload\",")
            append("\"userId\":\"${escapeJson(userId)}\",")
            append("\"level\":\"${escapeJson(level)}\",")
            append("\"tag\":\"${escapeJson(tag)}\",")
            append("\"logMessage\":\"$msg\",")
            append("\"timestamp\":\"${escapeJson(ts)}\",")
            append("\"stackTrace\":")
            if (trace != null) {
                append("\"${trace}\"")
            } else {
                append("null")
            }
            append("}")
        }
    }

    private fun sendRequest(json: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(UPLOAD_URL).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            // 加 JWT token
            if (this::appContext.isInitialized) {
                val prefs = appContext.getSharedPreferences("cloudbase", Context.MODE_PRIVATE)
                val token = prefs.getString("jwt_token", null)
                if (!token.isNullOrEmpty()) {
                    conn.setRequestProperty("Authorization", "Bearer $token")
                }
            }
            conn.doOutput = true
            conn.outputStream.write(json.toByteArray(StandardCharsets.UTF_8))

            val code = conn.responseCode
            if (code == HttpsURLConnection.HTTP_OK || code == 200) {
                conn.inputStream.bufferedReader().readText()
            } else {
                conn.errorStream?.bufferedReader()?.readText()
            }
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun escapeJson(s: String): String {
        return s
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    private fun getStackTraceString(t: Throwable): String {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        return sw.toString()
    }

    private fun priorityOf(level: String): Int = when (level) {
        "ERROR" -> Log.ERROR
        "WARN"  -> Log.WARN
        "INFO"  -> Log.INFO
        "DEBUG" -> Log.DEBUG
        else -> Log.DEBUG
    }
}
