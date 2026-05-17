package com.falldetector.diedaobao.notify

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

object WeChatNotifier {
    private const val TAG = "WeChatNotifier"
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    data class TestResult(val success: Boolean, val message: String)

    suspend fun sendTestAlert(webhookUrl: String): TestResult = withContext(Dispatchers.IO) {
        if (webhookUrl.isBlank()) {
            return@withContext TestResult(false, "Webhook地址为空，请先填写")
        }
        if (!webhookUrl.startsWith("https://qyapi.weixin.qq.com")) {
            return@withContext TestResult(false, "URL格式不对！\n\n需要的是Webhook推送地址，格式：\nhttps://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=xxx\n\n你可能填了「机器人添加链接」，那是用来加机器人进群的，不是用来发消息的。\n\n获取方法：在企微群聊中点击机器人 → 查看详情 → 复制Webhook地址")
        }
        try {
            val json = JSONObject().apply {
                put("msgtype", "text")
                put("text", JSONObject().apply {
                    put("content", "【跌倒宝测试】这是一条测试消息，如果你看到了说明企业微信通知配置成功！✅")
                })
            }
            val body = json.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(webhookUrl)
                .post(body)
                .build()
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            if (response.isSuccessful) {
                TestResult(true, "发送成功！请检查企微群是否收到消息")
            } else {
                TestResult(false, "发送失败(${response.code})：$responseBody")
            }
        } catch (e: Exception) {
            TestResult(false, "网络错误：${e.message}")
        }
    }

    // v0.29.6: 跌倒通知，支持详细信息
    suspend fun sendFallAlert(
        webhookUrl: String,
        latitude: Double,
        longitude: Double,
        contactName: String,
        mlProbability: Float = 0f,
        impactG: Float = 0f,
        fallHeight: Float = 0f,
        physScore: Float = 0f
    ) = withContext(Dispatchers.IO) {
        if (webhookUrl.isBlank()) {
            Log.w(TAG, "Webhook URL 为空，跳过微信通知")
            return@withContext
        }
        try {
            val timeStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())
            val mapUrl = "https://uri.amap.com/marker?position=$longitude,$latitude"

            // v0.29.6: 格式化详细信息
            val mlPercent = (mlProbability * 100).toInt().coerceIn(0, 100)
            val physPercent = (physScore * 100).toInt().coerceIn(0, 100)
            val severity = when {
                impactG >= 5f -> "严重"
                impactG >= 3f -> "中等"
                else -> "轻微"
            }

            val content = buildString {
                append("🚨【跌倒预警】\n")
                append("家人 $contactName 可能发生了跌倒\n\n")
                append("⏰ 时间：$timeStr\n")
                append("🤖 ML跌倒概率：$mlPercent%\n")
                append("💥 冲击能量：${String.format("%.1f", impactG)}g（$severity）\n")
                append("📊 物理评分：${physPercent}%\n")
                if (fallHeight > 0f) {
                    append("📏 估算跌倒高度：${String.format("%.1f", fallHeight)}米\n")
                }
                append("📍 位置：$mapUrl\n\n")
                append("请尽快确认家人安全状况！")
            }

            val json = JSONObject().apply {
                put("msgtype", "text")
                put("text", JSONObject().apply {
                    put("content", content)
                })
            }

            val body = json.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(webhookUrl)
                .post(body)
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            Log.i(TAG, "微信通知发送结果: ${response.code} $responseBody")
        } catch (e: Exception) {
            Log.e(TAG, "微信通知发送失败: ${e.message}")
        }
    }
}
