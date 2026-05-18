package com.falldetector.diedaobao.util

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
 * 错误日志上传器
 * 
 * 功能：
 * 1. 全局崩溃捕获（实现 UncaughtExceptionHandler）
 * 2. 手动记录日志（log() 方法）
 * 3. 崩溃时自动上传到云端 + 本地缓存
 * 4. 应用启动时检查本地缓存并重试上传
 * 
 * 云函数：upload-log
 * 集合：logs
 */
object LogUploader : Thread.UncaughtExceptionHandler {

    private const val TAG = "LogUploader"
    private const val UPLOAD_URL = "https://clerk-anything-adopt-lately.trycloudflare.com/upload-log"
    
    // 本地缓存文件名（崩溃时先写本地，异步上传）
    private const val CACHE_FILE = "error_log_cache.txt"
    
    // 最大缓存条数
    private const val MAX_CACHED = 50
    
    private var originalHandler: Thread.UncaughtExceptionHandler? = null
    private lateinit var appContext: Context
    
    // 上报用户 ID（由 App 初始化时注入）
    @Volatile
    var userId: String = "unknown"
        private set
    
    /**
     * 初始化，必须在 Application.onCreate() 中调用
     */
    fun init(context: Context, userId: String) {
        this.appContext = context.applicationContext
        this.userId = userId
        this.originalHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(this)
        Log.i(TAG, "LogUploader 已初始化，userId=$userId")
        
        // 启动时尝试上传本地缓存的日志
        uploadCachedLogs()
    }
    
    /**
     * 更新 userId（登录后调用）
     */
    fun setUserId(newUserId: String) {
        this.userId = newUserId
    }

    /**
     * 手动记录一条日志（不会崩溃上传，仅上传到云端）
     */
    fun log(tag: String, message: String, level: String = "INFO") {
        uploadLogSync(userId, level, tag, message, null)
    }
    
    /**
     * 记录带异常信息的日志
     */
    fun log(tag: String, message: String, throwable: Throwable?, level: String = "ERROR") {
        val stackTrace = throwable?.let { getStackTraceString(it) }
        uploadLogSync(userId, level, tag, message, stackTrace)
    }

    /**
     * Thread.UncaughtExceptionHandler 实现
     * 崩溃时：①写本地缓存 ②尝试异步上传 ③调用原始 handler
     */
    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val stackTrace = sw.toString()
        
        val message = throwable.javaClass.simpleName + ": " + throwable.message
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        
        Log.e(TAG, "===== 应用崩溃 =====")
        Log.e(TAG, "时间: $timestamp")
        Log.e(TAG, "线程: ${thread.name}")
        Log.e(TAG, "异常: $message")
        Log.e(TAG, "堆栈: $stackTrace")
        
        // ① 先写本地缓存（保证不丢失）
        cacheLog(userId, "CRASH", "App", message, stackTrace, timestamp)
        
        // ② 异步上传到云端
        Thread {
            uploadLogAsync(userId, "CRASH", "App", message, stackTrace)
        }.start()
        
        // ③ 等待一小段时间让上传有机会发出（1秒）
        try {
            Thread.sleep(1000)
        } catch (e: InterruptedException) { }
        
        // ④ 调用原始 handler（通常会触发 ANR 对话框或结束进程）
        originalHandler?.uncaughtException(thread, throwable)
    }
    
    // ─────────────────────────────────────────────
    // 私有方法
    // ─────────────────────────────────────────────
    
    /**
     * 同步上传（用于手动 log() 和崩溃时的备份）
     * 超时 8 秒，失败不影响主流程
     */
    private fun uploadLogSync(
        userId: String,
        level: String,
        tag: String,
        message: String,
        stackTrace: String?
    ) {
        val payload = buildJson(userId, level, tag, message, stackTrace)
        sendRequest(payload)
    }
    
    /**
     * 异步上传（崩溃时调用，不阻塞主线程）
     */
    private fun uploadLogAsync(
        userId: String,
        level: String,
        tag: String,
        message: String,
        stackTrace: String?
    ) {
        val payload = buildJson(userId, level, tag, message, stackTrace)
        Thread {
            try {
                val result = sendRequest(payload)
                Log.i(TAG, "崩溃日志上传结果: $result")
            } catch (e: Exception) {
                Log.e(TAG, "崩溃日志上传失败: ${e.message}")
            }
        }.start()
    }
    
    /**
     * 构建 JSON 请求体
     */
    private fun buildJson(
        userId: String,
        level: String,
        tag: String,
        message: String,
        stackTrace: String?
    ): String {
        return buildString {
            append("{")
            append("\"action\":\"upload\",")
            append("\"userId\":\"${escapeJson(userId)}\",")
            append("\"level\":\"${escapeJson(level)}\",")
            append("\"tag\":\"${escapeJson(tag)}\",")
            append("\"logMessage\":\"${escapeJson(message)}\",")
            if (stackTrace != null) {
                append("\"stackTrace\":\"${escapeJson(stackTrace.take(5000))}\"")
            } else {
                append("\"stackTrace\":null")
            }
            append("}")
        }
    }
    
    /**
     * 发送 HTTP POST 请求
     */
    private fun sendRequest(json: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(UPLOAD_URL).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.doOutput = true
            conn.outputStream.write(json.toByteArray(StandardCharsets.UTF_8))
            
            val responseCode = conn.responseCode
            if (responseCode == HttpsURLConnection.HTTP_OK || responseCode == 200) {
                conn.inputStream.bufferedReader().readText()
            } else {
                conn.errorStream?.bufferedReader()?.readText()
            }
        } catch (e: Exception) {
            Log.e(TAG, "上传失败: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }
    
    /**
     * 简单 JSON 字符串转义
     */
    private fun escapeJson(s: String): String {
        return s
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }
    
    /**
     * 获取堆栈跟踪字符串
     */
    private fun getStackTraceString(t: Throwable): String {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        return sw.toString()
    }
    
    // ─────────────────────────────────────────────
    // 本地缓存（崩溃时备用）
    // ─────────────────────────────────────────────
    
    /**
     * 写本地缓存
     */
    private fun cacheLog(
        userId: String,
        level: String,
        tag: String,
        message: String,
        stackTrace: String?,
        timestamp: String
    ) {
        try {
            val cacheFile = java.io.File(appContext.filesDir, CACHE_FILE)
            val entry = "$timestamp|$userId|$level|$tag|${message.replace("|", "&#124;")}|${stackTrace?.replace("|", "&#124;") ?: ""}\n"
            
            // 读取现有内容
            val existing = if (cacheFile.exists()) {
                cacheFile.readText()
            } else ""
            
            // 限制最大条数
            val lines = existing.lines().filter { it.isNotBlank() }
            val trimmed = if (lines.size >= MAX_CACHED) {
                lines.takeLast(MAX_CACHED - 1)
            } else {
                lines
            }
            
            (trimmed + entry).joinToString("\n").let {
                cacheFile.writeText(it)
            }
            Log.i(TAG, "日志已缓存: $timestamp $level $tag")
        } catch (e: Exception) {
            Log.e(TAG, "缓存日志失败: ${e.message}")
        }
    }
    
    /**
     * 应用启动时上传本地缓存的日志
     */
    private fun uploadCachedLogs() {
        val cacheFile = java.io.File(appContext.filesDir, CACHE_FILE)
        if (!cacheFile.exists()) return
        
        try {
            val lines = cacheFile.readLines().filter { it.isNotBlank() }
            if (lines.isEmpty()) return
            
            Log.i(TAG, "发现 ${lines.size} 条本地缓存日志，开始上传...")
            val successLines = mutableListOf<String>()
            
            for (line in lines) {
                val parts = line.split("|")
                if (parts.size < 5) {
                    successLines.add(line) // 格式错误，删除
                    continue
                }
                val timestamp = parts[0]
                val uid = parts[1]
                val level = parts[2]
                val tag = parts[3]
                val message = parts[4].replace("&#124;", "|")
                val stackTrace = if (parts.size > 5) parts[5].replace("&#124;", "|").takeIf { it.isNotBlank() } else null
                
                try {
                    val result = sendRequest(buildJson(uid, level, tag, message, stackTrace))
                    if (result != null && !result.contains("\"code\":5")) {
                        Log.i(TAG, "缓存日志上传成功: $timestamp")
                        continue // 上传成功，不加入成功列表（会被删除）
                    }
                } catch (_: Exception) { }
                // 上传失败，保留
                successLines.add(line)
            }
            
            // 更新缓存文件
            if (successLines.isEmpty()) {
                cacheFile.delete()
            } else {
                cacheFile.writeText(successLines.joinToString("\n"))
            }
            Log.i(TAG, "本地缓存处理完成，剩余 ${successLines.size} 条")
        } catch (e: Exception) {
            Log.e(TAG, "处理本地缓存失败: ${e.message}")
        }
    }
}
