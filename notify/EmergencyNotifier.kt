package com.falldetector.diedaobao.notify

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.falldetector.diedaobao.data.AppDatabase
import kotlinx.coroutines.*

object EmergencyNotifier {
    private const val TAG = "EmergencyNotifier"

    const val PREF_NOTIFY_BY_PHONE = "notify_by_phone"
    const val PREF_NOTIFY_BY_SMS = "notify_by_sms"
    const val PREF_NOTIFY_BY_WECHAT = "notify_by_wechat"
    const val PREF_WEBHOOK_URL = "webhook_url"
    const val PREF_COUNTDOWN_SECONDS = "countdown_seconds"

    /**
     * 从 SharedPreferences 获取通知设置
     */
    fun getNotificationSettings(context: Context): NotificationSettings {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        return NotificationSettings(
            notifyByPhone = prefs.getBoolean(PREF_NOTIFY_BY_PHONE, true),
            notifyBySms = prefs.getBoolean(PREF_NOTIFY_BY_SMS, true),
            notifyByWechat = prefs.getBoolean(PREF_NOTIFY_BY_WECHAT, true),
            webhookUrl = prefs.getString(PREF_WEBHOOK_URL, "") ?: "",
            countdownSeconds = prefs.getInt(PREF_COUNTDOWN_SECONDS, 30)
        )
    }

    /**
     * 触发紧急通知
     * v0.29.6: 新增详细信息参数
     * v0.30.7: 改为 suspend 函数，Room 读取在 IO 线程执行
     *          根因：runBlocking 不绕过 Room 的主线程检查，主线程调用直接抛异常
     */
    suspend fun triggerEmergency(
        context: Context,
        latitude: Double,
        longitude: Double,
        mlProbability: Float = 0f,
        impactG: Float = 0f,
        fallHeight: Float = 0f,
        physScore: Float = 0f
    ) {
        Log.i(TAG, "触发紧急通知，位置: $latitude, $longitude")

        val settings = getNotificationSettings(context)

        // v0.30.7: Room 读取移到 IO 线程，使用 suspend 方法
        val contacts = try {
            withContext(Dispatchers.IO) {
                val db = AppDatabase.getInstance(context)
                db.contactDao().getAllFirst()  // suspend 方法，Room 允许在 IO 线程调用
            }
        } catch (e: Exception) {
            Log.e(TAG, "读取联系人失败: ${e.message}")
            // 降级：从 SharedPreferences 读
            val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            val name = prefs.getString("contact_name", "") ?: ""
            val phone = prefs.getString("contact_phone", "") ?: ""
            if (phone.isNotEmpty()) {
                listOf(com.falldetector.diedaobao.data.Contact(name = name, phone = phone))
            } else emptyList()
        }

        // v0.32.5: 企业微信通知独立于联系人（群发，不需要联系人）
        if (settings.notifyByWechat && settings.webhookUrl.isNotEmpty()) {
            Log.i(TAG, ">>> 企业微信通知（群发）")
            val contactName = if (contacts.isNotEmpty()) contacts[0].name else "家人"
            CoroutineScope(Dispatchers.IO).launch {
                WeChatNotifier.sendFallAlert(
                    webhookUrl = settings.webhookUrl,
                    latitude = latitude,
                    longitude = longitude,
                    contactName = contactName,
                    mlProbability = mlProbability,
                    impactG = impactG,
                    fallHeight = fallHeight,
                    physScore = physScore
                )
            }
        }

        // 电话和短信需要联系人
        if (contacts.isEmpty()) {
            Log.w(TAG, "没有紧急联系人！跳过电话/短信通知（企业微信已单独处理）")
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            for (contact in contacts.sortedBy { it.priority }) {
                Log.i(TAG, "通知联系人: ${contact.name} (${contact.phone})")

                // 电话通知（检查权限）
                if (settings.notifyByPhone && hasPermission(context, Manifest.permission.CALL_PHONE)) {
                    Log.i(TAG, ">>> 电话通知: ${contact.phone}")
                    PhoneCaller.call(context, contact.phone)
                    delay(8000)
                } else if (settings.notifyByPhone) {
                    Log.w(TAG, "缺少 CALL_PHONE 权限，跳过电话通知")
                    // 降级：用 ACTION_DIAL
                    PhoneCaller.dial(context, contact.phone)
                }

                // 短信通知（检查权限）
                if (settings.notifyBySms && hasPermission(context, Manifest.permission.SEND_SMS)) {
                    Log.i(TAG, ">>> 短信通知: ${contact.phone}")
                    SmsSender.sendFallAlert(context, contact.phone, latitude, longitude)
                } else if (settings.notifyBySms) {
                    Log.w(TAG, "缺少 SEND_SMS 权限，跳过短信通知")
                }

                // 微信通知已在上方统一发送，此处不再重复

                // 如果还有下一个联系人，等2秒
                if (contacts.indexOf(contact) < contacts.size - 1) {
                    delay(2000)
                }
            }
            Log.i(TAG, ">>> 紧急通知全部发送完成")
        }
    }

    private fun hasPermission(context: Context, permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    data class NotificationSettings(
        val notifyByPhone: Boolean,
        val notifyBySms: Boolean,
        val notifyByWechat: Boolean,
        val webhookUrl: String,
        val countdownSeconds: Int
    )
}
