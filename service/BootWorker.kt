package com.falldetector.diedaobao.service

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.*
import java.util.concurrent.TimeUnit

/**
 * BootWorker — WorkManager 定时心跳
 *
 * 解决 MIUI/华为 等厂商强杀 APP 后服务不恢复的问题：
 * - 每次执行都把 FallDetectionService 拉起来
 * - 每 15 分钟执行一次（保活 + 开机恢复）
 * - 如果服务已在跑，startForegroundService 是安全操作（系统会忽略重复启动）
 */
class BootWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : Worker(appContext, workerParams) {

    override fun doWork(): Result {
        Log.i(TAG, "BootWorker: 开始执行，尝试启动检测服务")

        val prefs = applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val autoStart = prefs.getBoolean("auto_start", true)

        if (!autoStart) {
            Log.i(TAG, "BootWorker: auto_start=false，跳过")
            return Result.success()
        }

        // 启动前台服务（即使服务已在运行，重复调用无副作用）
        val serviceIntent = Intent(applicationContext, FallDetectionService::class.java)
        try {
            applicationContext.startForegroundService(serviceIntent)
            Log.i(TAG, "BootWorker: 前台服务已启动")
        } catch (e: Exception) {
            Log.e(TAG, "BootWorker: 启动失败 $e")
            return Result.retry()
        }

        // 安排下一次心跳（15分钟后）
        scheduleNext(applicationContext)
        return Result.success()
    }

    companion object {
        const val TAG = "BootWorker"
        const val WORK_NAME = "FallDetectionService-BootWorker"

        /** 安排 BootWorker 立即执行并周期性心跳 */
        fun schedule(context: Context) {
            Log.i(TAG, "schedule: 安排 BootWorker")

            val work = PeriodicWorkRequestBuilder<BootWorker>(
                15, TimeUnit.MINUTES,
                5, TimeUnit.MINUTES  // flexInterval
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(false)
                        .build()
                )
                .setInitialDelay(30, TimeUnit.SECONDS)
                .addTag(WORK_NAME)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE,
                    work
                )
            Log.i(TAG, "schedule: 完成")
        }

        /** 只安排下一次执行（不重复） */
        fun scheduleOneShot(context: Context) {
            val work = OneTimeWorkRequestBuilder<BootWorker>()
                .setInitialDelay(10, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueue(work)
        }

        private fun scheduleNext(context: Context) {
            schedule(context)
        }

        /** 取消所有 BootWorker */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.i(TAG, "cancel: 已取消")
        }
    }
}
