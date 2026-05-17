package com.falldetector.diedaobao

import android.app.Application
import com.falldetector.diedaobao.util.AppLogger
import android.util.Log
import com.falldetector.diedaobao.cloud.CloudBaseClient
import com.falldetector.diedaobao.data.AppDatabase
import com.falldetector.diedaobao.data.Repository
import com.falldetector.diedaobao.util.LogUploader

/**
 * 全局 Application（单例）
 *
 * 职责：
 * 1. 初始化 CloudBaseClient
 * 2. 初始化 Room 数据库 + Repository
 * 3. 初始化 LogUploader（崩溃捕获 + 云端上传）
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
    }
}
