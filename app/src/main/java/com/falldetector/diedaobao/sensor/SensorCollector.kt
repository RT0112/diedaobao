package com.falldetector.diedaobao.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.sqrt

data class SensorData(
    val accX: Float = 0f,
    val accY: Float = 0f,
    val accZ: Float = 0f,
    val accMagnitude: Float = 0f,
    val gyroX: Float = 0f,
    val gyroY: Float = 0f,
    val gyroZ: Float = 0f,
    val timestamp: Long = System.currentTimeMillis()
)

class SensorCollector(context: Context) {

    companion object {
        private const val TAG = "SensorCollector"
    }

    /** 直接回调接口：传感器数据直接喂给 FallDetector，绕过 StateFlow 轮询 */
    interface SensorCallback {
        fun onSensorData(accX: Float, accY: Float, accZ: Float,
                         gyroX: Float, gyroY: Float, gyroZ: Float,
                         timestamp: Long)
    }

    private var callback: SensorCallback? = null

    fun setCallback(cb: SensorCallback) {
        callback = cb
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    private val _sensorData = MutableStateFlow(SensorData())
    val sensorData: StateFlow<SensorData> = _sensorData

    private var lastGyroZ = 0f
    private var postureAngle = 0f
    
    private var hasData = false

    private val sensorEventListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    val ax = event.values[0]
                    val ay = event.values[1]
                    val az = event.values[2]
                    
                    // 转换为g (除以9.8)
                    val gravity = 9.8f
                    val accX = ax / gravity
                    val accY = ay / gravity
                    val accZ = az / gravity
                    val mag = sqrt(accX * accX + accY * accY + accZ * accZ)
                    
                    val sd = SensorData(
                        accX = accX, 
                        accY = accY, 
                        accZ = accZ,
                        accMagnitude = mag,
                        gyroX = _sensorData.value.gyroX,
                        gyroY = _sensorData.value.gyroY,
                        gyroZ = _sensorData.value.gyroZ,
                        timestamp = event.timestamp
                    )
                    _sensorData.value = sd
                    hasData = true

                    // ★ 直接回调（绕过 StateFlow 轮询，0 数据丢失）
                    callback?.onSensorData(accX, accY, accZ,
                        sd.gyroX, sd.gyroY, sd.gyroZ, event.timestamp)

                    // 打印调试信息
                    if (mag > 1.2f) {
                        Log.d(TAG, "加速度: x=$accX, y=$accY, z=$accZ, magnitude=$mag g")
                    }
                }
                Sensor.TYPE_GYROSCOPE -> {
                    val gx = event.values[0]
                    val gy = event.values[1]
                    val gz = event.values[2]
                    
                    // 简单积分计算姿态角变化
                    val dt = 0.02f // ~50Hz
                    postureAngle += kotlin.math.abs(gz - lastGyroZ) * dt
                    lastGyroZ = gz
                    
                    _sensorData.value = _sensorData.value.copy(
                        gyroX = gx, 
                        gyroY = gy, 
                        gyroZ = gz
                    )
                    
                    if (kotlin.math.abs(gz) > 0.5f) {
                        Log.d(TAG, "陀螺仪: x=$gx, y=$gy, z=$gz, postureAngle=$postureAngle")
                    }
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
            Log.i(TAG, "传感器精度变化: ${sensor?.name}, accuracy=$accuracy")
        }
    }

    fun start() {
        Log.i(TAG, "开始启动传感器...")
        
        if (accelerometer == null) {
            Log.e(TAG, "加速度计不可用!")
        } else {
            Log.i(TAG, "加速度计可用: ${accelerometer.name}")
        }
        
        if (gyroscope == null) {
            Log.w(TAG, "陀螺仪不可用!")
        } else {
            Log.i(TAG, "陀螺仪可用: ${gyroscope.name}")
        }

        // SENSOR_DELAY_GAME ≈ 20ms (~50Hz)
        accelerometer?.let {
            val registered = sensorManager.registerListener(sensorEventListener, it, SensorManager.SENSOR_DELAY_GAME)
            Log.i(TAG, "加速度计注册: ${if (registered) "成功" else "失败"}")
        }
        
        gyroscope?.let {
            val registered = sensorManager.registerListener(sensorEventListener, it, SensorManager.SENSOR_DELAY_GAME)
            Log.i(TAG, "陀螺仪注册: ${if (registered) "成功" else "失败"}")
        }
        
        hasData = false
    }

    fun stop() {
        sensorManager.unregisterListener(sensorEventListener)
        Log.i(TAG, "传感器已停止")
    }

    fun getPostureAngle(): Float = postureAngle

    fun resetPostureAngle() {
        postureAngle = 0f
    }
    
    fun hasSensorData(): Boolean = hasData
}