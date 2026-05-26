package com.falldetector.diedaobao

import android.app.Application
import com.falldetector.diedaobao.util.AppLogger
import android.util.Log
import com.falldetector.diedaobao.cloud.CloudBaseClient
import com.falldetector.diedaobao.cloud.WSClient
import com.falldetector.diedaobao.data.AppDatabase
import com.falldetector.diedaobao.data.Repository
import com.falldetector.diedaobao.util.LogUploader
import com.falldetector.diedaobao.assist.RemoteAssistManager
import kotlinx.coroutines.*

/**
 * 全局 Application（单例）
 *
 * 职责：
 * 1. 初始化 CloudBaseClient
 * 2. 初始化 Room 数据库 + Repository
 * 3. 初始化 LogUploader（崩溃捕获 + 云端上传）
 * 4. 启动 WS 事件监听（跌倒、位置请求等始终在线）
 */
class FallDetectionApp : Application() {

    companion object {
        private const val TAG = "FallDetectionApp"
        lateinit var instance: FallDetectionApp
            private set
    }

    /** Room 数据库 */
    val database: AppDatabase by lazy {
        AppDatabase.getInstance(this)
    }

    /** 数据仓库（提供 DAO） */
    val repository: Repository by lazy {
        Repository(database)
    }

    /** 应用级协程作用域（WS事件始终在线） */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        instance = this

        // 1. 初始化 CloudBaseClient（加载 SharedPreferences）
        CloudBaseClient.init(this)
        Log.i(TAG, "CloudBaseClient 已初始化")

        // 2. 初始化日志上传器（崩溃捕获 + 云端上传）
        val userId = CloudBaseClient.getUserId(this) ?: "not_registered_yet"
        LogUploader.init(this, userId)
        AppLogger.init(this, userId)
        Log.i(TAG, "LogUploader + AppLogger 已初始化，userId=$userId")

        // 3. 自动连接 WebSocket（用于实时推送跌倒事件、位置更新、协助请求）
        WSClient.connect(this)
        Log.i(TAG, "WSClient.connect() 已调用")

        // 4. 全局监听 WS 事件（始终在线，不依赖 UI 或 Service 的生命周期）
        val appRef = this
        appScope.launch {
            WSClient.events.collect { event ->
                when (event) {
                    is WSClient.WSEvent.LocationRequest -> {
                        Log.i(TAG, "[WS] 收到家属位置请求，立即上传位置")
                        try {
                            CloudBaseClient.uploadLocationNow(appRef)
                        } catch (e: Exception) {
                            Log.e(TAG, "处理位置请求失败: ${e.message}")
                        }
                    }
                    is WSClient.WSEvent.AssistRequest -> {
                        // v28: 不再由 FallDetectionApp 直接启动 Activity
                        // RemoteAssistManager.startWSEventListener 已统一处理 WS 协助请求，
                        // 这里再处理会导致双重触发（倒计时跳快、弹两下的根因）
                        Log.i(TAG, "[WS] 收到协助请求(由RemoteAssistManager处理): from=${event.guardianName}")
                    }
                    else -> { /* 忽略其他事件 */ }
                }
            }
        }
    }
}
