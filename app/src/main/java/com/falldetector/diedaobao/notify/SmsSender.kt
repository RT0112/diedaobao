package com.falldetector.diedaobao.notify

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object SmsSender {
    private const val TAG = "SmsSender"

    fun sendFallAlert(context: Context, phoneNumber: String, latitude: Double, longitude: Double) {
        // 先检查权限
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS)
            != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "没有 SEND_SMS 权限，无法发送短信")
            return
        }

        try {
            val smsManager = SmsManager.getDefault()
            val timeStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())
            val mapUrl = "https://uri.amap.com/marker?position=$longitude,$latitude"
            val message = "【跌倒宝】您的家人可能发生了跌倒！\n" +
                "位置：$mapUrl\n" +
                "时间：$timeStr\n" +
                "请尽快确认安全状况。"

            val parts = smsManager.divideMessage(message)
            smsManager.sendMultipartTextMessage(phoneNumber, null, parts, null, null)
            Log.i(TAG, "短信已发送到: $phoneNumber")
        } catch (e: SecurityException) {
            Log.e(TAG, "短信权限被拒绝: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "短信发送失败: ${e.message}")
        }
    }
}
