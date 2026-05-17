package com.falldetector.diedaobao.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "fall_events")
data class FallEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val peakAcceleration: Float = 0f,
    val postureAngle: Float = 0f,
    val isConfirmed: Boolean = false,      // 是否已处理（倒计时结束或用户响应）
    val isFalsePositive: Boolean = false,  // 是否误报（用户点击"我没事"）
    val notificationSent: Boolean = false  // 是否已发送通知
)
