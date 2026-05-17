package com.falldetector.diedaobao.ml

import android.content.Context
import android.util.Log
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.OnnxTensor
import java.nio.FloatBuffer

/**
 * ONNX Runtime 跌倒检测推理引擎 (v12 RF)
 *
 * v12 核心改进：
 * - 重力对齐坐标变换 → 方向特征旋转不变
 * - 去掉所有陀螺仪特征 → 消除合成/真实分布不匹配
 * - 训练/推理都用零交叉率估算频率
 * - 采样率 50Hz 统一（SisFall 200Hz→50Hz）
 *
 * 模型: fall_detection_model.onnx (RF v12)
 * 精度: RF CV F1=0.8148, FPR=2.8%, FNR=6.6%
 */
class FallDetectorONNX(context: Context) {

    companion object {
        private const val TAG = "FallDetectorONNX"
        private const val MODEL_PATH = "fall_detection_model.onnx"
        private const val N_FEATURES = 50
        // v12 最优阈值（从测试集得出，F1最优对应阈值）
        private const val FALL_THRESHOLD = 0.40f
        private const val SAMPLE_RATE = 50f

        // v12 StandardScaler 参数 (50维) - 修正scale系数后重新训练
        // 修正：SCALER_MEAN[22] (mag_mean) 现在是 0.99g（之前是 0.026g，偏小40倍）
        private val SCALER_MEAN = floatArrayOf(
            0.941973242f, 0.221122912f, 1.804473506f, 0.485313482f, 1.319160024f, 0.109878967f,
            0.233343228f, 0.557647577f, 5.123050874f, 128.854074566f, 0.0f,
            0.157047841f, 0.131625085f, 0.769360872f, 0.013895920f, 0.755464952f, 0.055522102f,
            0.129090076f, 1.582338096f, 4.381155479f, 12.365505785f, 0.0f,
            0.991294406f, 0.219408307f, 1.934691627f, 0.656637964f, 1.278053663f, 0.100542051f,
            0.219047854f, 0.930899021f, 5.822730928f, 141.432051830f, 0.0f,
            6.227892206f, 5.488214705f, 6.133321305f, 0.249115689f, 0.219528589f, 0.245332853f,
            0.642684878f, 14.624544569f, 0.292490891f, 2.869335651f, 8.522699349f, 1.697467816f,
            1.806121458f, 0.950476032f, 0.769360872f, 4.136216256f, 35.838073954f
        )

        private val SCALER_SCALE = floatArrayOf(
            0.045797861f, 0.304181232f, 1.492625483f, 0.622253388f, 2.014081986f, 0.202185209f,
            0.423876264f, 1.646271692f, 10.310304425f, 41.777653292f, 1.0f,
            0.172841470f, 0.164501322f, 1.171045368f, 0.018720586f, 1.162171661f, 0.063758646f,
            0.142200012f, 1.121447559f, 8.517098382f, 26.124518371f, 1.0f,
            0.108591869f, 0.298093302f, 1.752384947f, 0.289480459f, 1.956712676f, 0.175660836f,
            0.390023763f, 1.789679378f, 11.670769746f, 65.629843742f, 1.0f,
            3.799456548f, 3.440649130f, 3.914077855f, 0.151978262f, 0.137625966f, 0.156563114f,
            0.333624750f, 20.564156617f, 0.411283132f, 4.034687540f, 14.804648636f, 3.083334097f,
            1.497216285f, 0.056068653f, 1.171045368f, 6.113623880f, 69.205476827f
        )

        private const val GRAVITY = 9.81f
        // 低通滤波系数 ~0.3Hz 截止频率（与 Python step1_extract_v12.py 一致）
        private const val LOWPASS_ALPHA = 0.1f
    }

    // ====== 重力对齐状态（跨调用持久化）======
    // 初始重力方向：竖直向上 (x=0, y=0, z=1)，手机屏幕朝上时对应
    private val smoothedGravity = floatArrayOf(0f, 0f, 1f)
    private var isFirstCall = true

    private var ortSession: OrtSession? = null
    private var ortEnv: OrtEnvironment? = null
    private var isInitialized = false
    private var inferCount = 0
    private var fallCount = 0

    init {
        try {
            ortEnv = OrtEnvironment.getEnvironment()
            val modelBytes = context.assets.open(MODEL_PATH).use { it.readBytes() }
            val sessionOptions = OrtSession.SessionOptions()
            sessionOptions.setIntraOpNumThreads(2)
            ortSession = ortEnv?.createSession(modelBytes, sessionOptions)
            isInitialized = true
            Log.i(TAG, "✅ ONNX RF v12 模型加载成功 (50维)")
            Log.i(TAG, "   模型: fall_detection_model.onnx | 9MB")
            Log.i(TAG, "   特性: 重力对齐 | 无陀螺仪 | 零交叉率频率")
            Log.i(TAG, "   RF F1=0.8148 | FPR=2.8% | FNR=6.6%")
        } catch (e: Exception) {
            Log.e(TAG, "❌ ONNX模型加载失败: ${e.message}")
        }
    }

    /**
     * 重力对齐坐标变换（v0.15.1 修复）
     *
     * ⚠️ 关键：SensorCollector 已经把加速度从 m/s² 转成了 g 单位（ax/9.81）
     * 所以 Android 端的 gravityAlign 输入就是 g 单位，不需要再除以 GRAVITY！
     *
     * Python gravityAlign 的输入是 m/s²（需要除 GRAVITY 转 g）
     * Android 端必须对应，否则 accVert 偏小 10 倍 → ML 永远 ≈ 0
     *
     * 静止手机 acc=[0,0,1]g → accVert = 1.0g（静止时重力在垂直方向）
     */
    private fun gravityAlign(accX: FloatArray, accY: FloatArray, accZ: FloatArray): Triple<FloatArray, FloatArray, FloatArray> {
        val n = accX.size
        val accVert = FloatArray(n)
        val accHorizMag = FloatArray(n)
        val accMag = FloatArray(n)

        for (i in 0 until n) {
            // EMA 低通滤波更新重力方向（输入是 g 单位）
            if (isFirstCall && i == 0) {
                smoothedGravity[0] = accX[0]
                smoothedGravity[1] = accY[0]
                smoothedGravity[2] = accZ[0]
                isFirstCall = false
            }

            smoothedGravity[0] += LOWPASS_ALPHA * (accX[i] - smoothedGravity[0])
            smoothedGravity[1] += LOWPASS_ALPHA * (accY[i] - smoothedGravity[1])
            smoothedGravity[2] += LOWPASS_ALPHA * (accZ[i] - smoothedGravity[2])

            // 归一化重力方向 → 单位向量
            val gLen = kotlin.math.sqrt(
                smoothedGravity[0] * smoothedGravity[0] +
                smoothedGravity[1] * smoothedGravity[1] +
                smoothedGravity[2] * smoothedGravity[2]
            )
            val gravDirX = if (gLen > 0.001f) smoothedGravity[0] / gLen else 0f
            val gravDirY = if (gLen > 0.001f) smoothedGravity[1] / gLen else 0f
            val gravDirZ = if (gLen > 0.001f) smoothedGravity[2] / gLen else 1f

            // 更新平滑重力为归一化后的单位向量
            smoothedGravity[0] = gravDirX
            smoothedGravity[1] = gravDirY
            smoothedGravity[2] = gravDirZ

            // 垂直分量 = 投影到重力方向（g·g_dir，点积，单位已经是 g）
            val gDotA = accX[i] * gravDirX + accY[i] * gravDirY + accZ[i] * gravDirZ
            accVert[i] = gDotA  // 单位: g

            // 总加速度 magnitude（单位: g）
            val totalMag = kotlin.math.sqrt(accX[i] * accX[i] + accY[i] * accY[i] + accZ[i] * accZ[i])
            accMag[i] = totalMag  // 单位: g

            // 水平分量 = 总加速度 - 垂直分量（向量投影到水平面）
            val vertX = gravDirX * gDotA
            val vertY = gravDirY * gDotA
            val vertZ = gravDirZ * gDotA
            val horizX = accX[i] - vertX
            val horizY = accY[i] - vertY
            val horizZ = accZ[i] - vertZ
            accHorizMag[i] = kotlin.math.sqrt(horizX * horizX + horizY * horizY + horizZ * horizZ)  // 单位: g
        }

        return Triple(accVert, accHorizMag, accMag)
    }

    private fun zeroCrossingRate(arr: FloatArray): Float {
        if (arr.size < 2) return 0f
        val mean = arr.sum() / arr.size
        var crossings = 0
        for (i in 0 until arr.size - 1) {
            val s1 = arr[i] - mean
            val s2 = arr[i + 1] - mean
            if ((s1 >= 0 && s2 < 0) || (s1 < 0 && s2 >= 0)) crossings++
        }
        return crossings.toFloat() / arr.size
    }

    private fun domFreqZCR(arr: FloatArray): Float {
        return zeroCrossingRate(arr) * SAMPLE_RATE / 2f
    }

    private fun timeFeatures(arr: FloatArray): FloatArray {
        val n = arr.size
        if (n < 10) return FloatArray(11) { 0f }

        var sum = 0f
        var sumSq = 0f
        var maxV = arr[0]
        var minV = arr[0]
        for (v in arr) {
            sum += v
            sumSq += v * v
            if (v > maxV) maxV = v
            if (v < minV) minV = v
        }
        val mean = sum / n
        val variance = sumSq / n - mean * mean
        val std = if (variance > 0) kotlin.math.sqrt(variance) else 0f

        // 中位数
        val sorted = arr.sorted()
        val median = if (n % 2 == 0) (sorted[n / 2 - 1] + sorted[n / 2]) / 2f else sorted[n / 2]

        // MAD
        var madSum = 0f
        for (v in arr) madSum += kotlin.math.abs(v - median)
        val mad = madSum / n

        // IQR
        val q1 = sorted[(n * 0.25).toInt().coerceIn(0, n - 1)]
        val q3 = sorted[(n * 0.75).toInt().coerceIn(0, n - 1)]
        val iqr = q3 - q1

        // 能量
        var energy = 0f
        for (v in arr) energy += v * v

        // 偏度和峰度（简化版）
        var skewSum = 0f
        for (v in arr) skewSum += (v - mean) * (v - mean) * (v - mean)
        val skew = if (std > 0 && n > 2) skewSum / (n * std * std * std) else 0f

        return floatArrayOf(mean, std, maxV, minV, maxV - minV, mad, iqr, skew, 0f, energy, 0f)
    }

    private fun physicsFeatures(vert: FloatArray, horiz: FloatArray, mag: FloatArray): FloatArray {
        val n = mag.size

        // 自由落体检测
        var ffCount = 0
        var ffSum = 0f
        for (v in mag) {
            if (v < 0.8f) {
                ffCount++
                ffSum += v
            }
        }
        val ffQuality = if (ffCount > 0) kotlin.math.max(0f, 1f - ffSum / ffCount) else 0f
        val ffDuration = ffCount / SAMPLE_RATE
        val peakVelocity = GRAVITY * ffDuration
        val impactPeak = mag.maxOrNull() ?: 0f
        val impactStrength = impactPeak * peakVelocity

        // 冲击前后能量比
        var impIdx = 0
        var impMax = 0f
        for (i in mag.indices) {
            if (mag[i] > impMax) {
                impMax = mag[i]
                impIdx = i
            }
        }
        val wh = minOf(10, impIdx, n - 1 - impIdx)
        var preE = 0f
        var postE = 0f
        if (wh > 2) {
            for (i in (impIdx - wh) until impIdx) preE += mag[i] * mag[i]
            for (i in impIdx until (impIdx + wh)) postE += mag[i] * mag[i]
            preE /= wh
            postE /= wh
        }
        val energyRatio = if (preE > 1e-10f) postE / preE else 1f

        // 垂直分量峰值
        val vertPeak = vert.map { kotlin.math.abs(it) }.maxOrNull() ?: 0f
        val vertMeanAbs = vert.map { kotlin.math.abs(it) }.sum() / n

        // 水平分量峰值
        val horizPeak = horiz.maxOrNull() ?: 0f

        // Jerk
        if (n < 2) return floatArrayOf(ffQuality, ffCount.toFloat(), ffDuration, peakVelocity,
            impactStrength, energyRatio, vertPeak, vertMeanAbs, horizPeak, 0f, 0f)

        var jerkSum = 0f
        var jerkMax = 0f
        for (i in 0 until n - 1) {
            val j = kotlin.math.abs((mag[i + 1] - mag[i]) * SAMPLE_RATE)
            jerkSum += j
            if (j > jerkMax) jerkMax = j
        }
        val jerkMean = jerkSum / (n - 1)

        return floatArrayOf(ffQuality, ffCount.toFloat(), ffDuration, peakVelocity,
            impactStrength, energyRatio, vertPeak, vertMeanAbs, horizPeak, jerkMean, jerkMax)
    }

    /**
     * v12 重力对齐 50维特征提取
     * 与 Python step1_extract_v12.py 的 extract_features_v12 完全一致
     */
    fun extractFeatures50(accX: FloatArray, accY: FloatArray, accZ: FloatArray): FloatArray {
        if (accX.size != accY.size || accX.size != accZ.size) {
            Log.e(TAG, "加速度数组长度不一致!")
            return FloatArray(N_FEATURES) { 0f }
        }

        // 重力对齐
        val (accVert, accHoriz, accMag) = gravityAlign(accX, accY, accZ)

        // 时域特征
        val fVert = timeFeatures(accVert)   // [0-10]
        val fHoriz = timeFeatures(accHoriz) // [11-21]
        val fMag = timeFeatures(accMag)     // [22-32]

        // 频域特征（零交叉率）
        val fFreq = floatArrayOf(
            domFreqZCR(accVert),  // [33]
            domFreqZCR(accHoriz), // [34]
            domFreqZCR(accMag),   // [35]
            zeroCrossingRate(accVert),  // [36]
            zeroCrossingRate(accHoriz), // [37]
            zeroCrossingRate(accMag)    // [38]
        )

        // 物理增强
        val fPhysics = physicsFeatures(accVert, accHoriz, accMag) // [39-49]

        // 合并
        val features = FloatArray(N_FEATURES)
        var idx = 0
        for (f in fVert) features[idx++] = f
        for (f in fHoriz) features[idx++] = f
        for (f in fMag) features[idx++] = f
        for (f in fFreq) features[idx++] = f
        for (f in fPhysics) features[idx++] = f

        // 处理 NaN/Inf
        for (i in features.indices) {
            val v = features[i]
            features[i] = if (v.isNaN() || v.isInfinite()) 0f else v
        }

        return features
    }

    /**
     * 预测跌倒概率（使用重力对齐特征）
     */
    fun predictFromAlignedFeatures(features: FloatArray): Float {
        if (!isInitialized || ortSession == null) return 0f
        if (features.size != N_FEATURES) {
            Log.e(TAG, "特征维度不匹配: ${features.size} != $N_FEATURES")
            return 0f
        }

        return try {
            // 打印特征统计（诊断）
            var minF = Float.MAX_VALUE
            var maxF = Float.MIN_VALUE
            var sumF = 0f
            for (v in features) {
                if (v < minF) minF = v
                if (v > maxF) maxF = v
                sumF += v
            }
            Log.d(TAG, "📥 特征: min=${String.format("%.3f", minF)}, max=${String.format("%.3f", maxF)}, mean=${String.format("%.3f", sumF/features.size)}")
            
            val normalized = normalize(features)
            
            // 打印归一化后统计
            var minN = Float.MAX_VALUE
            var maxN = Float.MIN_VALUE
            var sumN = 0f
            for (v in normalized) {
                if (v < minN) minN = v
                if (v > maxN) maxN = v
                sumN += v
            }
            Log.d(TAG, "📤 归一化后: min=${String.format("%.3f", minN)}, max=${String.format("%.3f", maxN)}, mean=${String.format("%.3f", sumN/normalized.size)}")

            val inputBuffer = FloatBuffer.wrap(normalized)
            val inputTensor = OnnxTensor.createTensor(ortEnv, inputBuffer, longArrayOf(1, N_FEATURES.toLong()))

            val inputName = ortSession?.inputNames?.iterator()?.next() ?: "float_input"
            val results = ortSession?.run(mapOf(inputName to inputTensor))

            // 打印所有输出名称和值（诊断）
            val outputNames = ortSession?.outputNames?.toList() ?: emptyList()
            Log.d(TAG, "📤 ONNX输出: ${outputNames}")
            
            // RF输出格式取决于 ONNX opset 版本:
            // opset 9: output_probability 是 sequence<map<int64,float>> (zipmap)
            // opset 15 + zipmap=False: probabilities 是 tensor(float) [1, 2]
            // 必须兼容两种格式！
            val outputValue = results?.get(1)?.value
            val fallProbability = when (outputValue) {
                is Array<*> -> {
                    // opset 15: Array<FloatArray> shape=[1,2]
                    val probs = outputValue as? Array<FloatArray>
                    val p = probs?.get(0)?.get(1) ?: 0f
                    Log.i(TAG, "🎯 ONNX推理(tensor): ADL=${probs?.get(0)?.get(0)}, FALL=$p")
                    p
                }
                is List<*> -> {
                    // opset 9: List<Map<Long, Float>> (zipmap格式)
                    try {
                        @Suppress("UNCHECKED_CAST")
                        val probMap = (outputValue as? List<Map<Long, Float>>)?.get(0)
                        val p = probMap?.get(1L) ?: 0f
                        Log.i(TAG, "🎯 ONNX推理(zipmap): ADL=${probMap?.get(0L)}, FALL=$p")
                        p
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ zipmap解析失败: ${e.message}")
                        0f
                    }
                }
                else -> {
                    Log.e(TAG, "❌ 未知ONNX输出类型: ${outputValue?.javaClass?.name}")
                    0f
                }
            }

            inputTensor.close()
            results?.close()

            inferCount++
            if (fallProbability >= FALL_THRESHOLD) {
                fallCount++
                Log.i(TAG, "🔥 RF预测: P(FALL)=${String.format("%.3f", fallProbability)} [推理#$inferCount 跌倒#$fallCount]")
            }

            fallProbability
        } catch (e: Exception) {
            Log.e(TAG, "❌ ONNX推理失败: ${e.message}")
            e.printStackTrace()
            0f
        }
    }

    /**
     * 便捷方法：直接从传感器数据预测
     */
    fun predict(accX: FloatArray, accY: FloatArray, accZ: FloatArray): Float {
        val features = extractFeatures50(accX, accY, accZ)
        return predictFromAlignedFeatures(features)
    }

    private fun normalize(features: FloatArray): FloatArray {
        val normalized = FloatArray(N_FEATURES)
        for (i in 0 until N_FEATURES) {
            normalized[i] = (features[i] - SCALER_MEAN[i]) / SCALER_SCALE[i]
        }
        return normalized
    }

    fun isInferenceReady(): Boolean = isInitialized

    fun getThreshold(): Float = FALL_THRESHOLD

    fun getInferenceCount(): Int = inferCount

    fun getFallCount(): Int = fallCount

    fun resetCounts() {
        inferCount = 0
        fallCount = 0
    }

    /** 重置重力对齐状态（服务重启时调用） */
    fun resetGravity() {
        smoothedGravity[0] = 0f
        smoothedGravity[1] = 0f
        smoothedGravity[2] = 1f
        isFirstCall = true
    }

    fun close() {
        try {
            ortSession?.close()
            ortEnv?.close()
        } catch (e: Exception) {
            Log.e(TAG, "释放资源失败: ${e.message}")
        }
    }
}
