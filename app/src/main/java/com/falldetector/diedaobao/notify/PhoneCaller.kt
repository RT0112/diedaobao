package com.falldetector.diedaobao.notify

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.util.Log

object PhoneCaller {
    private const val TAG = "PhoneCaller"

    /**
     * 直接拨打电话 — 使用 TelecomManager.placeCall 指定卡1
     * 关键修复：MIUI 双卡手机必须用 TelecomManager 才能绕过选卡界面
     */
    fun call(context: Context, phoneNumber: String) {
        try {
            // 优先使用 TelecomManager.placeCall（Android 5.1+，MIUI 兼容）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                if (telecomManager != null) {
                    val phoneAccounts = telecomManager.callCapablePhoneAccounts
                    if (!phoneAccounts.isNullOrEmpty()) {
                        // 取第一个 PhoneAccountHandle（通常是卡1）
                        val accountHandle = phoneAccounts[0]
                        Log.i(TAG, "使用 TelecomManager.placeCall, account=${accountHandle.id}")
                        
                        val uri = Uri.parse("tel:$phoneNumber")
                        val extras = android.os.Bundle()
                        extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, accountHandle)
                        
                        telecomManager.placeCall(uri, extras)
                        Log.i(TAG, "拨打电话: $phoneNumber (卡1 via TelecomManager)")
                        return
                    }
                }
            }

            // 降级方案：ACTION_CALL + slot extras（部分机型有效）
            val intent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:$phoneNumber")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                putExtra("com.android.phone.forceSlot", true)
                putExtra("com.android.phone.extra.slot", 0)
                putExtra("slotId", 0)
                putExtra("simSlot", 0)
                putExtra("com.android.phone.DialerApplication.slot", 0)
            }
            context.startActivity(intent)
            Log.i(TAG, "拨打电话: $phoneNumber (ACTION_CALL + slot=0)")
        } catch (e: SecurityException) {
            Log.e(TAG, "电话权限被拒绝: ${e.message}")
            dial(context, phoneNumber)
        } catch (e: Exception) {
            Log.e(TAG, "拨打电话失败: ${e.message}")
            dial(context, phoneNumber)
        }
    }

    /**
     * 降级：打开拨号界面（不需要权限）
     */
    fun dial(context: Context, phoneNumber: String) {
        try {
            val intent = Intent(Intent.ACTION_DIAL).apply {
                data = Uri.parse("tel:$phoneNumber")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            Log.i(TAG, "打开拨号界面: $phoneNumber")
        } catch (e: Exception) {
            Log.e(TAG, "拨号界面也失败: ${e.message}")
        }
    }
}
