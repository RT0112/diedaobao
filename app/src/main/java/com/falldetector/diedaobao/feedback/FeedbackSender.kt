package com.falldetector.diedaobao.feedback

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 反馈提交器 v0.30.9
 * 支持完整检测数据 + 传感器数据提交
 */
object FeedbackSender {
    private const val TAG = "FeedbackSender"
    
    // ngrok 公网地址（写死在App里，用户装上就能用）
    private const val API_URL = "https://oscular-august-equanimously.ngrok-free.dev/api/feedback"
    private const val API_KEY = "fall_detection_secret_key_2024"
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    
    private val JSON = "application/json; charset=utf-8".toMediaType()
    
    /**
     * 提交误报反馈（完整版）
     */
    suspend fun submitMisreport(
        context: Context,
        fallEventId: Long,
        sceneCategory: String,
        sceneDescription: String,
        ffTimeMs: Long,
        impactStrength: Float,
        mlProbability: Float,
        physicsScore: Float,
        weightedScore: Float,
        decisionPath: String,
        sensorDataJson: String,
        deviceModel: String,
        androidVersion: String,
        appVersion: String
    ): Result<String> {
        val payload = JSONObject().apply {
            put("type", "misreport")
            put("fall_event_id", fallEventId)
            put("scene_category", sceneCategory)
            put("scene_description", sceneDescription)
            // 检测数据
            put("ff_time_ms", ffTimeMs)
            put("impact_strength", impactStrength)
            put("ml_probability", mlProbability)
            put("physics_score", physicsScore)
            put("weighted_score", weightedScore)
            put("decision_path", decisionPath)
            // 传感器数据（JSON字符串→JSONArray）
            try {
                put("sensor_data", JSONArray(sensorDataJson))
            } catch (_: Exception) {
                put("sensor_data", JSONArray())
            }
            // 设备信息
            put("device_model", deviceModel)
            put("android_version", androidVersion)
            put("app_version", appVersion)
            put("version_code", try {
                if (android.os.Build.VERSION.SDK_INT >= 28)
                    context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toInt()
                else
                    @Suppress("DEPRECATION") context.packageManager.getPackageInfo(context.packageName, 0).versionCode
            } catch (_: Exception) { 0 })
            put("sensitivity_level", com.falldetector.diedaobao.detect.DetectionConfig.sensitivityLevel)
        }
        return postToServer(payload)
    }
    
    /**
     * 提交建议反馈
     */
    suspend fun submitSuggestion(
        context: Context,
        content: String,
        deviceModel: String,
        androidVersion: String,
        appVersion: String
    ): Result<String> {
        val payload = JSONObject().apply {
            put("type", "suggestion")
            put("content", content)
            put("device_model", deviceModel)
            put("android_version", androidVersion)
            put("app_version", appVersion)
        }
        return postToServer(payload)
    }
    
    /**
     * 发送POST请求到服务器
     */
    private suspend fun postToServer(payload: JSONObject): Result<String> = withContext(Dispatchers.IO) {
        return@withContext try {
            val body = payload.toString().toRequestBody(JSON)
            val request = Request.Builder()
                .url(API_URL)
                .addHeader("X-API-Key", API_KEY)
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build()
            
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()
            
            if (response.isSuccessful) {
                Log.d(TAG, "✅ 反馈提交成功: $responseBody")
                Result.success(responseBody ?: "提交成功")
            } else {
                val error = "服务器返回 ${response.code}: $responseBody"
                Log.e(TAG, "❌ $error")
                Result.failure(Exception(error))
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ 提交失败: ${e.message}")
            Result.failure(e)
        }
    }
}
