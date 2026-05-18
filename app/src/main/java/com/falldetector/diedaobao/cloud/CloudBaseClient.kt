package com.falldetector.diedaobao.cloud

import android.content.Context
import com.falldetector.diedaobao.util.AppLogger
import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.TimeoutCancellationException

/**
 * v0.33.0: CloudBase HTTP API 客户端
 * 
 * 直接通过 HTTP 调用云函数，无需 SDK。
 * 环境ID: diedaobao-cdn-d4g496tvv296f0ac2
 */
object CloudBaseClient {
    private const val TAG = "CloudBaseClient"
    
    // 外网地址（双端均外网使用）
    private const val BASE_URL = "https://clerk-anything-adopt-lately.trycloudflare.com"
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    
    private var isInitialized = false
    private var cachedUserId: String? = null
    
    /**
     * 初始化（从 SharedPreferences 加载 userId）
     */
    fun init(context: Context) {
        val prefs = context.getSharedPreferences("cloudbase", Context.MODE_PRIVATE)
        cachedUserId = prefs.getString("user_id", null)
        
        // 回填 elder_id（v0.41.0 之前未保存）
        if (cachedUserId != null && prefs.getString("elder_id", null) == null) {
            prefs.edit().putString("elder_id", cachedUserId).apply()
            Log.i(TAG, "回填 elder_id: $cachedUserId")
        }
        
        isInitialized = true
        Log.i(TAG, "初始化完成，userId: $cachedUserId")
    }
    
    /**
     * 检查是否已注册
     */
    fun isRegistered(context: Context): Boolean {
        val prefs = context.getSharedPreferences("cloudbase", Context.MODE_PRIVATE)
        return prefs.getString("user_id", null) != null
    }
    
    /**
     * 保存 userId 到 SharedPreferences
     */
    fun saveUserIdToPrefs(context: Context, userId: String) {
        val prefs = context.getSharedPreferences("cloudbase", Context.MODE_PRIVATE)
        prefs.edit().putString("user_id", userId).apply()
        cachedUserId = userId
        Log.i(TAG, "userId 已保存: $userId")
    }

    /**
     * 保存老人自己的 userId（用于远程协助帧上传到正确文档）
     * 老人端永远用 ownerUserId 作为 elder_id，不受 family binding 覆盖 user_id 的影响
     */
    fun saveElderIdToPrefs(context: Context, elderId: String) {
        val prefs = context.getSharedPreferences("cloudbase", Context.MODE_PRIVATE)
        prefs.edit().putString("elder_id", elderId).apply()
        Log.i(TAG, "elder_id 已保存: $elderId")
    }

    /**
     * 强制重置注册状态，清除本地userId，下次启动会重新注册（带正确deviceId前缀）
     * 用途：解决SharedPreferences残留旧userId导致无法重新注册的问题
     */
    fun resetRegistration(context: Context) {
        cachedUserId = null
        val prefs = context.getSharedPreferences("cloudbase", Context.MODE_PRIVATE)
        prefs.edit()
            .remove("user_id")
            .remove("elder_id")
            .apply()
        Log.i(TAG, "Registration reset, will re-register on next launch")
    }
    
    /**
     * 注册用户
     * 
     * @param deviceId 设备ID（Android ID）
     * @param name 用户姓名
     * @param phone 手机号
     * @return userId 或 null（失败）
     */
    suspend fun registerUser(
        context: Context,
        deviceId: String,
        name: String,
        phone: String
    ): String? {
        val body = JSONObject().apply {
            put("deviceId", deviceId)
            put("name", name)
            put("phone", phone)
            put("role", "elder")
        }
        
        return try {
            val response = callFunction("user-register", body)
            if (response != null) {
                val userId = response.optString("userId")
                if (userId.isNotEmpty()) {
                    saveUserIdToPrefs(context, userId)
                    saveElderIdToPrefs(context, userId)  // 老人端：自己的 ID 就是 elder_id
                    userId
                } else {
                    AppLogger.e(TAG, "注册失败: ${response.optString("error", "unknown")}")
                    null
                }
            } else {
                AppLogger.e(TAG, "注册失败: response is null")
                null
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "注册异常: ${e.message}")
            null
        }
    }
    
    /**
     * 上报跌倒事件
     * 
     * @param latitude 纬度
     * @param longitude 经度
     * @param impactG 冲击力（g）
     * @param ffDuration FF持续时间（ms）
     * @param mlScore ML 分数
     * @param physicalScore 物理分数
     * @return 是否成功
     */
    suspend fun reportFall(
        context: Context,
        latitude: Double,
        longitude: Double,
        impactG: Float,
        ffDuration: Long,
        mlScore: Float,
        physicalScore: Float
    ): Boolean {
        val userId = cachedUserId ?: run {
            val prefs = context.getSharedPreferences("cloudbase", Context.MODE_PRIVATE)
            prefs.getString("user_id", null)
        }
        
        if (userId == null) {
            Log.e(TAG, "❌ reportFall: userId为null！cachedUserId=$cachedUserId, 未注册，跳过上报")
            return false
        }
        Log.w(TAG, "📡 reportFall: userId=$userId, lat=$latitude, lng=$longitude, impactG=$impactG, mlScore=$mlScore")
        
        val body = JSONObject().apply {
            put("userId", userId)
            // v0.46: 0.0 表示未获取到位置，不传或传null，避免子女端显示"0.0000, 0.0000"
            if (latitude != 0.0 && longitude != 0.0) {
                put("latitude", latitude)
                put("longitude", longitude)
            }
            put("impactG", impactG.toDouble())
            put("ffDuration", ffDuration)
            put("mlScore", mlScore.toDouble())
            put("physicalScore", physicalScore.toDouble())
            put("timestamp", System.currentTimeMillis())
        }
        
        Log.w(TAG, "📡 reportFall: 请求体=${body.toString().take(200)}")
        return try {
            val response = callFunction("fall-report", body)
            Log.w(TAG, "📡 reportFall: 响应=${response?.toString()?.take(200) ?: "null"}")
            val success = response != null && response.optString("eventId").isNotEmpty()
            Log.w(TAG, "📡 reportFall: 最终结果=$success")
            success
        } catch (e: Exception) {
            Log.e(TAG, "❌ reportFall异常: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }
    
    /**
     * 同步位置
     */
    suspend fun syncLocation(
        context: Context,
        latitude: Double,
        longitude: Double
    ): Boolean {
        val userId = cachedUserId ?: run {
            val prefs = context.getSharedPreferences("cloudbase", Context.MODE_PRIVATE)
            prefs.getString("user_id", null)
        }
        
        if (userId == null) {
            AppLogger.w(TAG, "未注册，跳过位置同步")
            return false
        }
        
        val body = JSONObject().apply {
            put("userId", userId)
            put("latitude", latitude)
            put("longitude", longitude)
            put("timestamp", System.currentTimeMillis())
        }
        
        return try {
            val response = callFunction("location-sync", body)
            val success = response != null && response.optBoolean("success", false)
            Log.i(TAG, "位置同步: $success")
            success
        } catch (e: Exception) {
            AppLogger.e(TAG, "位置同步异常: ${e.message}")
            false
        }
    }

    /**
     * v21: 立即上传当前位置（WS收到位置请求时调用）
     * 获取最新GPS位置并上传到服务器 + WS推送给子女端
     */
    suspend fun uploadLocationNow(context: Context): Boolean {
        val userId = cachedUserId ?: run {
            val prefs = context.getSharedPreferences("cloudbase", Context.MODE_PRIVATE)
            prefs.getString("user_id", null)
        } ?: return false

        return try {
            // v23: 强制刷新位置 — requestSingleUpdate + 5秒超时，避免lastKnown返回null或旧位置
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager
            var lat = 0.0
            var lng = 0.0
            var accuracy = 0f

            if (locationManager != null) {
                // 先尝试强制刷新（5秒超时）
                val freshLocation = try {
                    withTimeout(5000) {
                        suspendCancellableCoroutine { cont: kotlinx.coroutines.CancellableContinuation<android.location.Location?> ->
                            val listener = object : android.location.LocationListener {
                                override fun onLocationChanged(loc: android.location.Location) {
                                    locationManager.removeUpdates(this)
                                    if (cont.isActive) cont.resumeWith(Result.success(loc))
                                }
                                override fun onProviderEnabled(provider: String) {}
                                override fun onProviderDisabled(provider: String) {}
                            }
                            cont.invokeOnCancellation {
                                try { locationManager.removeUpdates(listener) } catch (_: Exception) {}
                            }
                            try {
                                locationManager.requestSingleUpdate(
                                    android.location.LocationManager.NETWORK_PROVIDER,
                                    listener,
                                    android.os.Looper.getMainLooper()
                                )
                            } catch (e: Exception) {
                                if (cont.isActive) cont.resumeWith(Result.success(null))
                            }
                        }
                    }
                } catch (_: TimeoutCancellationException) {
                    AppLogger.w(TAG, "uploadLocationNow: requestSingleUpdate超时，fallback到lastKnown")
                    null
                }

                val location = freshLocation
                    ?: locationManager.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
                    ?: locationManager.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)

                if (location != null) {
                    lat = location.latitude
                    lng = location.longitude
                    accuracy = location.accuracy
                    AppLogger.i(TAG, "uploadLocationNow: 位置来源=${if (freshLocation != null) "fresh" else "lastKnown"}, lat=$lat, lng=$lng")
                }
            }

            // 从 SharedPreferences 获取上次保存的位置作为 fallback
            if (lat == 0.0 && lng == 0.0) {
                val prefs = context.getSharedPreferences("cloudbase", Context.MODE_PRIVATE)
                lat = prefs.getString("last_lat", null)?.toDoubleOrNull() ?: 0.0
                lng = prefs.getString("last_lng", null)?.toDoubleOrNull() ?: 0.0
                if (lat != 0.0 || lng != 0.0) {
                    AppLogger.w(TAG, "uploadLocationNow: 使用SP缓存位置 fallback")
                }
            }

            if (lat == 0.0 && lng == 0.0) {
                AppLogger.w(TAG, "uploadLocationNow: 无可用位置")
                return false
            }

            // 上传到服务器
            val body = JSONObject().apply {
                put("userId", userId)
                put("latitude", lat)
                put("longitude", lng)
                put("accuracy", accuracy)
                put("timestamp", System.currentTimeMillis())
            }
            val response = callFunction("location-sync", body)
            val httpOk = response != null && response.optBoolean("success", false)

            // WS推送位置更新给子女端
            com.falldetector.diedaobao.cloud.WSClient.pushLocationUpdate(lat, lng, accuracy)

            // 缓存位置
            val prefs = context.getSharedPreferences("cloudbase", Context.MODE_PRIVATE)
            prefs.edit()
                .putString("last_lat", lat.toString())
                .putString("last_lng", lng.toString())
                .apply()

            Log.i(TAG, "uploadLocationNow: lat=$lat, lng=$lng, httpOk=$httpOk")
            httpOk
        } catch (e: Exception) {
            AppLogger.e(TAG, "uploadLocationNow异常: ${e.message}")
            false
        }
    }
    
    /**
     * 生成绑定码（供家属绑定）
     * 
     * @return 绑定码（6位数字）或 null（失败）
     */
    suspend fun generateBindCode(context: Context): String? {
        val userId = getUserId(context) ?: return null
        
        val body = JSONObject().apply {
            put("action", "generateCode")
            put("data", JSONObject().apply {
                put("elderId", userId)
            })
        }
        
        return try {
            val response = callFunction("bind-family", body)
            if (response != null) {
                val bindCode = response.optString("bindCode")
                if (bindCode.isNotEmpty()) {
                    // 保存绑定码供显示
                    val prefs = context.getSharedPreferences("cloudbase", android.content.Context.MODE_PRIVATE)
                    prefs.edit().putString("bind_code", bindCode).apply()
                    Log.i(TAG, "绑定码生成: $bindCode")
                    bindCode
                } else {
                    AppLogger.e(TAG, "生成绑定码失败: ${response.optString("message", "unknown")}")
                    null
                }
            } else {
                null
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "生成绑定码异常: ${e.message}")
            null
        }
    }
    
    /**
     * 获取已缓存的绑定码
     */
    fun getCachedBindCode(context: Context): String? {
        val prefs = context.getSharedPreferences("cloudbase", android.content.Context.MODE_PRIVATE)
        return prefs.getString("bind_code", null)
    }
    
    /**
     * 检查围栏越界
     * 
     * @return 越界的围栏名称列表（空列表表示未越界）
     */
    suspend fun checkGeofenceBreaches(
        context: Context,
        latitude: Double,
        longitude: Double
    ): List<String> {
        val userId = getUserId(context) ?: return emptyList()
        
        val body = JSONObject().apply {
            put("action", "check")
            put("elderId", userId)
            put("latitude", latitude)
            put("longitude", longitude)
        }
        
        return try {
            val response = callFunction("geofence", body)
            if (response != null && response.optBoolean("success", false)) {
                val breaches = response.optJSONArray("breaches")
                if (breaches != null) {
                    (0 until breaches.length()).map { breaches.getString(it) }
                } else {
                    emptyList()
                }
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "围栏检查异常: ${e.message}")
            emptyList()
        }
    }

    /**
     * 获取围栏数据缓存（供本地Haversine检测用，不每次调云函数check）
     * 从云端拉取围栏列表，解析为本地可用的数据结构
     */
    suspend fun getCachedFenceData(context: Context): List<GeofenceSimple> {
        val userId = getUserId(context) ?: return emptyList()

        val body = JSONObject().apply {
            put("action", "list")
            put("elderId", userId)
        }

        return try {
            val response = callFunction("geofence", body)
            if (response != null && response.optBoolean("success", false)) {
                val fences = response.optJSONArray("fences")
                if (fences != null) {
                    (0 until fences.length()).mapNotNull { i ->
                        val obj = fences.getJSONObject(i)
                        val id = obj.optString("id", "")
                        val name = obj.optString("name", "")
                        val lat = obj.optDouble("latitude", 0.0)
                        val lng = obj.optDouble("longitude", 0.0)
                        val radius = obj.optDouble("radius", 0.0)
                        if (id.isNotEmpty() && name.isNotEmpty() && lat != 0.0 && lng != 0.0 && radius > 0) {
                            GeofenceSimple(id, name, lat, lng, radius)
                        } else null
                    }
                } else emptyList()
            } else emptyList()
        } catch (e: Exception) {
            AppLogger.e(TAG, "获取围栏数据失败: ${e.message}")
            emptyList()
        }
    }

    data class GeofenceSimple(
        val id: String,
        val name: String,
        val lat: Double,
        val lng: Double,
        val radiusMeters: Double
    )

    /**
     * 轮询位置拉取请求（老人端每10秒调用）
     * 检查子女端是否请求了实时位置
     * @return PullResult 包含 hasPullRequest 和 pullRequestTime
     */
    suspend fun pollPullRequest(context: Context): PullResult {
        val userId = getUserId(context) ?: return PullResult(false, 0L)
        
        val body = JSONObject().apply {
            put("action", "poll_pull")
            put("data", JSONObject().apply {
                put("elderId", userId)
            })
        }
        
        return try {
            val response = callFunction("location-sync", body)
            if (response != null && response.optInt("code", 0) == 200) {
                // hasPullRequest/pullRequestTime 在顶层，不在 data 里
                PullResult(
                    hasPullRequest = response.optBoolean("hasPullRequest", false),
                    pullRequestTime = response.optLong("pullRequestTime", 0L)
                )
            } else {
                PullResult(false, 0L)
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "pollPullRequest异常: ${e.message}")
            PullResult(false, 0L)
        }
    }
    
    data class PullResult(
        val hasPullRequest: Boolean,
        val pullRequestTime: Long  // 请求时间戳，0=无请求
    )

    /**
     * 获取 userId（内部方法）
     */
    fun getUserId(context: Context): String? {
        return cachedUserId ?: run {
            val prefs = context.getSharedPreferences("cloudbase", android.content.Context.MODE_PRIVATE)
            prefs.getString("user_id", null)
        }
    }
    
    /**
     * 调用云函数（公开方法，供 RemoteAssistManager 等外部调用）
     * 
     * @param functionName 函数名
     * @param body 请求体
     * @return 响应 JSON 或 null
     */
    suspend fun callFunctionRaw(functionName: String, body: JSONObject): JSONObject? {
        return callFunction(functionName, body)
    }

    /**
     * 调用云函数
     * 
     * @param functionName 函数名
     * @param body 请求体
     * @return 响应 JSON 或 null
     */
    private suspend fun callFunction(functionName: String, body: JSONObject): JSONObject? {
        val url = "$BASE_URL/$functionName"
        Log.d(TAG, "🌐 callFunction: POST $url")
        val request = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody(jsonMediaType))
            .build()
        
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val response = client.newCall(request).execute()
                Log.d(TAG, "🌐 callFunction: $functionName → HTTP ${response.code}")
                if (response.isSuccessful) {
                    val responseBody = response.body?.string()
                    if (responseBody != null) {
                        JSONObject(responseBody)
                    } else {
                        Log.e(TAG, "🌐 callFunction: 响应体为空")
                        null
                    }
                } else {
                    Log.e(TAG, "🌐 callFunction: $functionName → ${response.code} ${response.message}")
                    null
                }
            } catch (e: IOException) {
                Log.e(TAG, "🌐 callFunction: 网络异常 $functionName → ${e.javaClass.simpleName}: ${e.message}")
                null
            }
        }
    }
}
