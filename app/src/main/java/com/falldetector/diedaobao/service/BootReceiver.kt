package com.falldetector.diedaobao.service

import android.content.BroadcastReceiver
import com.falldetector.diedaobao.util.AppLogger
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.i("BootReceiver", "广播收到: action=${intent.action}")
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON" ||
            intent.action == "com.huawei.android.launcher.action.READY") {
            val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            val autoStart = prefs.getBoolean("auto_start", true)
            val guardEverStarted = prefs.getBoolean("guard_ever_started", false)
            // v0.30.7: 两个条件都检查，guard_ever_started 更可靠
            // guard_ever_started: 用户曾经开启过守护（即使未开 auto_start 也应恢复）
            val shouldStart = autoStart || guardEverStarted
            Log.i("BootReceiver", "开机启动检查: auto_start=$autoStart, guard_ever_started=$guardEverStarted, shouldStart=$shouldStart")
            if (shouldStart) {
                val serviceIntent = Intent(context, FallDetectionService::class.java)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    Log.i("BootReceiver", "启动前台服务 (Android O+)")
                    context.startForegroundService(serviceIntent)
                } else {
                    Log.i("BootReceiver", "启动普通服务")
                    context.startService(serviceIntent)
                }
            } else {
                Log.i("BootReceiver", "无需自动启动守护服务")
            }
        } else {
            AppLogger.w("BootReceiver", "未知广播: ${intent.action}")
        }
    }
}
