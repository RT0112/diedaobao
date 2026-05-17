package com.falldetector.diedaobao.detect

import android.content.Context
import com.falldetector.diedaobao.util.AppLogger
import android.util.Log
import com.falldetector.diedaobao.ml.FallDetectorML
import com.falldetector.diedaobao.ml.FallDetectorONNX
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.LinkedList
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.sqrt

data class FallDetectionResult(
    val isFallDetected: Boolean = false,
    val peakAcceleration: Float = 0f,
    val postureAngle: Float = 0f,
    val confidence: Float = 0f,
    val detectionMethod: String = "",
    val mlProbability: Float = 0f,
    val timestamp: Long = System.currentTimeMillis(),
    // v0.29.6: 新增详细信息（供微信通知）
    val physScore: Float = 0f,
    val impactG: Float = 0f,
    val fallHeight: Float = 0f
)

/** v0.29: 500ms窗口帧数据 */
data class WindowFrame(
    val accMag: Float,
    val velocity: Float,
    val timestamp: Long
)

/** v0.29: FF段数据 */
data class FFSection(
    val startTime: Long,
    val endTime: Long,
    val durationMs: Long,
    val velocities: List<Float>
)

/** 实时诊断信息(供测试页面显示每个决策节点) */
data class DiagnosticInfo(
    val accMag: Float = 0f,
    val dynamicAcc: Float = 0f,
    val isRecording: Boolean = false,
    val peakValue: Float = 0f,
    val variance: Float = 0f,
    val accWindowSize: Int = 0,
    val gyroWindowSize: Int = 0,
    val mlProbability: Float = 0f,
    val mlInferCount: Int = 0,
    val mlFallCount: Int = 0,
    val detectionState: String = "INIT",
    val freefallTimeMs: Long = 0L,
    val freefallQuality: Float = 0f,
    val peakVelocity: Float = 0f,
    val impactStrength: Float = 0f,
    val physicsPass: Boolean = false,

    // 决策树详情
    val step1_triggered: Boolean = false,
    val step2_windowOk: Boolean = false,
    val step3_mlRaw: Float = 0f,
    val step4_mlHigh: Boolean = false,
    val step5_mlLow: Boolean = false,
    val step6_physFf: Boolean = false,
    val step6_physQ: Boolean = false,
    val step6_physV: Boolean = false,
    val step6_physImp: Boolean = false,
    val step7_result: Boolean = false,
    // v0.25.16: 详细决策信息（供测试页面显示逻辑走向）
    val ffTimeMs: Long = 0L,
    val ffScore: Float = 0f,
    val impScore: Float = 0f,
    val velScore: Float = 0f,
    val physScore: Float = 0f,
    val weightedScore: Float = 0f,
    val decisionPath: String = "",
    val ffMerged: Boolean = false,
    val overweightDetected: Boolean = false,

    val decisionSummary: String = ""
)

enum class DetectionState {
    MONITORING,
    IMPACT_DETECTED
}

class FallDetector(private val context: Context? = null) {

    companion object {
        private const val TAG = "FallDetector"
        const val WINDOW_SIZE = 125       // 2.5秒 @ 50Hz
        private const val FREEFALL_THRESHOLD = 0.7f
        private const val CONTINUOUS_ML_INTERVAL = 50
        private const val COOLDOWN_MS = 2800L
        private const val FF_MERGE_GAP_MS = 500L
        private const val FF_RECENT_WINDOW_MS = 1000L  // 向上推防护
        private const val CONFIRMATION_MS = 2300L     // 确认期（2026-04-30: 1800→2300）
        private const val REBOUND_PROTECT_MS = 860L   // 回弹保护期（2026-04-30: 600→860）
        private const val FF_SKIP_THRESHOLD_MS = 200L  // FF<200ms跳过（5g豁免）
        // 确认期运动检测：三轴标准差综合判断（2026-04-30 v0.32.3）
        // 手机静止→各轴std≈0；任何方向移动→对应轴std跳变，三轴之和超阈值
        private const val MOTION_XYZ_STD_TH = 0.5f    // 三轴标准差之和阈值（m/s²），超=人在动
        private const val MOTION_FRAME_COUNT = 5        // 连续5帧超标才取消（避免噪声误触）

        var mlInferCount = 0
        var mlFallCount = 0
        const val DEBUG_LOG = true
    }

    private var mlEngine: FallDetectorML? = null
    private var onnxEngine: FallDetectorONNX? = null

    private val _result = MutableStateFlow(FallDetectionResult())
    val result: StateFlow<FallDetectionResult> = _result.asStateFlow()

    private val _diagnosticInfo = MutableStateFlow(DiagnosticInfo())
    val diagnosticInfo: StateFlow<DiagnosticInfo> = _diagnosticInfo.asStateFlow()

    private val _decisionLog = MutableStateFlow("")
    val decisionLog: StateFlow<String> = _decisionLog.asStateFlow()

    // 传感器窗口
    private val accWindow = LinkedList<FloatArray>()
    private val gyroWindow = LinkedList<FloatArray>()
    private val accBuffer = LinkedList<Pair<Float, Long>>()

    // v0.25.5: 滚动窗口追踪最近500ms内的accMag
    private val standingAccMagWindow = LinkedList<Pair<Float, Long>>()

    // 状态机
    private var detectionState = DetectionState.MONITORING
    private var impactStartTime = 0L
    private var lastDiagnosticPushTime = 0L
    private val confirmationXyzBuffer = LinkedList<FloatArray>()  // 存XYZ三轴加速度，供确认期运动检测（2026-04-30 v0.32.3）
    private var peakValue = 0f
    private var lastPeakTime = 0L

    // 物理追踪
    private var freefallStart = 0L
    private var freefallAccSum = 0f
    private var freefallCount = 0
    private var freefallTimeMs = 0L
    private var freefallQuality = 0f
    private var peakVelocity = 0f
    private var impactStrength = 0f

    // 已删除：freefallTotalAccum/pauseStart/velocityHistory等（v0.29改用500ms窗口）

    // v0.29: 500ms窗口buffer（替代实时FF追踪）
    private val windowBuffer = ArrayDeque<WindowFrame>(30)  // 500ms @ 50Hz ≈ 25帧
    private var signedVelocity = 0f  // 有符号速度（用于趋势检测）

    // 重力方向追踪
    private var gravityAtFreefallStart: FloatArray? = null
    private var gravityAtImpactStart: FloatArray? = null
    private var angleChangeDeg = 0f

    // ML
    private var latestMlProb = 0f
    private var latestMlEngine = "none"

    init {
        if (context != null) {
            try {
                onnxEngine = FallDetectorONNX(context)
                Log.i(TAG, "✅ ONNX RF引擎初始化成功")
            } catch (e: Exception) {
                AppLogger.e(TAG, "❌ ONNX引擎初始化失败: ${e.message}")
            }
        }
    }

    private var feedCount = 0L
    private var firstFeedTime = 0L
    private var lastDiagTime = 0L

    fun feed(accX: Float, accY: Float, accZ: Float,
             gyroX: Float, gyroY: Float, gyroZ: Float,
             timestamp: Long) {
        val cfg = DetectionConfig
        val now = System.currentTimeMillis()
        if (firstFeedTime == 0L) firstFeedTime = now
        feedCount++

        if (now - firstFeedTime > 5000 && (now - firstFeedTime) % 5000 < 60) {
            val elapsed = (now - firstFeedTime) / 1000f
            val hz = feedCount / elapsed
            Log.i(TAG, "📊 feed统计: ${feedCount}次 / ${"%.1f".format(elapsed)}s = ${"%.1f".format(hz)}Hz")
        }

        val accMag = sqrt(accX * accX + accY * accY + accZ * accZ)
        val dynamicAcc = abs(accMag - 1f)

        // 窗口维护
        accWindow.addLast(floatArrayOf(accX, accY, accZ))
        gyroWindow.addLast(floatArrayOf(gyroX, gyroY, gyroZ))
        if (accWindow.size > WINDOW_SIZE) accWindow.removeFirst()
        if (gyroWindow.size > WINDOW_SIZE) gyroWindow.removeFirst()

        accBuffer.addLast(Pair(dynamicAcc, timestamp))
        if (accBuffer.size > WINDOW_SIZE) accBuffer.removeFirst()

        // v0.27: 速度更新（用于趋势检测，每帧执行）
        val dt_s = 0.02f // 50Hz
        val accel = when {
            accMag < 0.7f -> 9.81f * (1.0f - accMag)
            accMag > 1.3f -> -9.81f * (accMag - 1.0f) * 0.5f
            else -> 9.81f * (1.0f - accMag)
        }
        signedVelocity += accel * dt_s

        // v0.29: 500ms窗口buffer更新（每帧执行）
        windowBuffer.addLast(WindowFrame(accMag, signedVelocity, now))
        while (windowBuffer.isNotEmpty() && now - windowBuffer.first().timestamp > 500L) {
            windowBuffer.removeFirst()
        }

        // ── 冷却期 ──────────────────────────────────────────
        if (now - lastPeakTime < COOLDOWN_MS) {
            pushDiagnostic(accMag, dynamicAcc, peakValue, "冷却期")
            return
        }

        // ── 定期诊断更新（每5秒）──────────────────────────
        if (now - lastDiagTime >= 5000L) {
            lastDiagTime = now
            pushDiagnostic(accMag, dynamicAcc, peakValue, "监控中")
        }

        // ══════════════════════════════════════════════════════
        // 状态机: MONITORING
        // ══════════════════════════════════════════════════════
        if (detectionState == DetectionState.MONITORING) {

            val step1 = dynamicAcc > cfg.accThresholdG

            // ── 主链冲击检测 ─────────────────────────────────
            if (step1) {
                // v0.29: 5g豁免检查
                val isSevereNow = accMag >= 5.0f

                // v0.29: 从500ms窗口提取FF段
                val sections = process500msWindow()
                val ffTimeNow = if (sections.isEmpty()) 0L else sections.maxOf { it.durationMs }

                // FF<cfg.ffTimeMs且冲击<5g → 排除
                val ffThreshold = cfg.ffTimeMs.toLong()
                if (ffTimeNow in 1L until ffThreshold && !isSevereNow) {
                    AppLogger.w(TAG, "⚡【立即排除】FF=${ffTimeNow}ms<${ffThreshold}ms 且 冲击=${String.format("%.1f", accMag)}g<5g → 冲击能量不足")
                    _decisionLog.value = "FF<200ms排除: FF=${ffTimeNow}ms"
                    return  // 不进确认期
                }

                // 无FF段且非5g → 排除
                if (sections.isEmpty() && !isSevereNow) {
                    AppLogger.w(TAG, "⚡【立即排除】500ms窗口内无FF段 且 冲击=${String.format("%.1f", accMag)}g<5g")
                    _decisionLog.value = "无FF段排除"
                    return
                }

                // 进入确认期
                detectionState = DetectionState.IMPACT_DETECTED
                impactStartTime = now
                peakValue = accMag
                gravityAtImpactStart = normalizeGravity(accX, accY, accZ, accMag)

                // 记录FF时间（供决策链使用）
                freefallTimeMs = ffTimeNow
                peakVelocity = 9.81f * (ffTimeNow / 1000f)

                AppLogger.w(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                AppLogger.w(TAG, "⚡【冲击触发】dyn=${String.format("%.2f", dynamicAcc)}g accMag=${String.format("%.2f", accMag)}g FF=${ffTimeNow}ms")
                AppLogger.w(TAG, "-> 进入确认期1500ms...")
            }

            if (step1) {
                pushDiagnostic(accMag, dynamicAcc, peakValue,
                    "IMPACT_TRIGGER",
                    step1Triggered = true,
                    step2WindowOk = accWindow.size >= 50,
                    step3MlRaw = latestMlProb
                )
            }
            return
        }

        // ══════════════════════════════════════════════════════
        // 状态机: IMPACT_DETECTED
        // ══════════════════════════════════════════════════════
        if (detectionState == DetectionState.IMPACT_DETECTED) {
            // 2026-04-30: 填充确认期缓冲，供方差检查用
            confirmationXyzBuffer.addLast(floatArrayOf(accX, accY, accZ))
            if (confirmationXyzBuffer.size > 200) {
                confirmationXyzBuffer.removeFirst()
            }
            if (accMag > peakValue) peakValue = accMag

            val elapsed = now - impactStartTime

            // ── 600ms 回弹保护期：不检查运动 ──────────────────────
            if (elapsed < REBOUND_PROTECT_MS) {
                if (now - lastDiagnosticPushTime > 500) {
                    val remaining = CONFIRMATION_MS - elapsed
                    pushDiagnostic(accMag, dynamicAcc, peakValue, "🔵回弹保护(${remaining}ms)...", step1Triggered = true)
                    lastDiagnosticPushTime = now
                }
                return
            }

            // ── 保护期刚结束：清空buffer，避免保护期内的反弹数据污染运动检测 ──────────────────────
            if (elapsed >= REBOUND_PROTECT_MS && elapsed < REBOUND_PROTECT_MS + 100 && confirmationXyzBuffer.size > 20) {
                Log.d(TAG, "🔄【保护期结束】清空buffer(${confirmationXyzBuffer.size}帧)，重新采集")
                confirmationXyzBuffer.clear()
            }

            // ── 2300ms 确认期：三轴综合运动检测 ──────────────────────────
            if (elapsed < CONFIRMATION_MS) {
                if (confirmationXyzBuffer.size >= 10) {
                    // 取最近10帧XYZ
                    val recent = confirmationXyzBuffer.takeLast(10)
                    // 计算三轴各自的标准差
                    val stdX = calcStd(recent.map { it[0] })
                    val stdY = calcStd(recent.map { it[1] })
                    val stdZ = calcStd(recent.map { it[2] })
                    val totalStd = stdX + stdY + stdZ
                    val motionFrames = recent.count {
                        val dx = it[0] - recent.first()[0]
                        val dy = it[1] - recent.first()[1]
                        val dz = it[2] - recent.first()[2]
                        sqrt(dx*dx + dy*dy + dz*dz) > MOTION_XYZ_STD_TH  // 帧间位移>0.5视为运动
                    }
                    // 三轴标准差之和超过阈值，或帧间变化明显 → 人在动
                    if (totalStd > MOTION_XYZ_STD_TH * 3 || motionFrames >= MOTION_FRAME_COUNT) {
                        AppLogger.w(TAG, "🚶【确认期取消】stdX=${String.format("%.2f", stdX)} stdY=${String.format("%.2f", stdY)} stdZ=${String.format("%.2f", stdZ)} 总和=${String.format("%.2f", totalStd)} > ${MOTION_XYZ_STD_TH * 3} → 手机在移动，非跌倒")
                        detectionState = DetectionState.MONITORING
                        val savedPeak = peakValue
                        resetPhysicsState()
                        peakValue = savedPeak
                        confirmationXyzBuffer.clear()
                        return
                    }
                }
                if (now - lastDiagnosticPushTime > 500) {
                    val remaining = CONFIRMATION_MS - elapsed
                    pushDiagnostic(accMag, dynamicAcc, peakValue, "⏳等确认(${remaining}ms)...", step1Triggered = true)
                    lastDiagnosticPushTime = now
                }
                return
            }

            runMlDecisionTree(accX, accY, accZ, accMag, dynamicAcc, now, cfg)
        }
    }

    private fun runMlDecisionTree(
        accX: Float, accY: Float, accZ: Float,
        accMag: Float, dynamicAcc: Float,
        now: Long, cfg: DetectionConfig
    ): Boolean {
        val step2 = accWindow.size >= 50
        var mlRaw = 0f
        var engine = "none"

        if (step2) {
            mlInferCount++
            try {
                val accXArr = accWindow.map { it[0] }.toFloatArray()
                val accYArr = accWindow.map { it[1] }.toFloatArray()
                val accZArr = accWindow.map { it[2] }.toFloatArray()

                if (onnxEngine != null) {
                    mlRaw = onnxEngine!!.predict(accXArr, accYArr, accZArr)
                    engine = "onnx"
                } else if (mlEngine != null) {
                    val accList = accWindow.toList().map { Triple(it[0], it[1], it[2]) }
                    val gyroList = gyroWindow.toList().map { Triple(it[0], it[1], it[2]) }
                    mlRaw = mlEngine!!.predictFromSensorWindow(accList, gyroList)
                    engine = "tflite"
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "❌ ML推理异常: ${e.message}")
                engine = "error"
            }
        } else {
            mlRaw = 0f
            engine = "window_small(${accWindow.size})"
        }

        latestMlProb = mlRaw
        latestMlEngine = engine

        val currentGravity = normalizeGravity(accX, accY, accZ, accMag)
        val startGravity = gravityAtImpactStart ?: gravityAtFreefallStart
        if (startGravity != null && angleChangeDeg < 0.1f) {
            angleChangeDeg = angleBetweenGravity(startGravity, currentGravity)
        }

        val ffTime = freefallTimeMs  // v0.29: 直接使用freefallTimeMs（已在冲击触发时从500ms窗口计算）

        val isMlHigh = mlRaw >= cfg.mlHigh
        val isMlLow = mlRaw >= 0.25f

        // 物理评分计算
        val ffScore = minOf(ffTime / 400f, 1f)
        val impScore = maxOf(minOf((peakValue - 1.5f) / 3.0f, 1f), 0f)
        val velScore = minOf(peakVelocity / 3.0f, 1f)
        var physScore = ffScore * 0.35f + impScore * 0.35f + velScore * 0.30f

        AppLogger.w(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        AppLogger.w(TAG, "📊【决策链开始】")
        AppLogger.w(TAG, "  ML引擎=$engine 窗口=${accWindow.size}")
        AppLogger.w(TAG, "  FF=${ffTime}ms 冲击=${String.format("%.1f", peakValue)}g 速度=${String.format("%.2f", peakVelocity)}m/s  ML=${String.format("%.2f", mlRaw)}")

        AppLogger.w(TAG, "  【物理评分】")
        AppLogger.w(TAG, "    FF分=${String.format("%.2f", ffScore)} (FF${ffTime}ms/400ms)")
        AppLogger.w(TAG, "    冲击分=${String.format("%.2f", impScore)} ((冲击${String.format("%.1f", peakValue)}g-1.5g)/3g)")
        AppLogger.w(TAG, "    速度分=${String.format("%.2f", velScore)} (速度${String.format("%.2f", peakVelocity)}/3m/s)")
        AppLogger.w(TAG, "    物理总分=${String.format("%.2f", physScore)} (FF×35%+冲击×35%+速度×30%)")

        // 三路径决策
        val physicsOverride = physScore >= cfg.physicsThresh && peakValue >= DetectionConfig.IMPACT_MIN_HARDCODED && ffTime >= cfg.ffTimeMs && mlRaw >= 0.25f
        val fallScore = physScore * 0.36f + mlRaw * 0.64f  // 加权评分

        AppLogger.w(TAG, "  【三层决策】")
        AppLogger.w(TAG, "    路径1: ML=${String.format("%.2f", mlRaw)} >= ${cfg.mlHigh} → " + if(isMlHigh)"✅ 直接报警" else "❌")
        AppLogger.w(TAG, "    路径2: ML=${String.format("%.2f", mlRaw)} >= ${DetectionConfig.ML_LOW_HARDCODED} 且 加权=${String.format("%.2f", fallScore)} >= ${cfg.weightedScoreThresh} → " + if(isMlLow && fallScore >= cfg.weightedScoreThresh)"✅ 加权报警" else "❌")
        AppLogger.w(TAG, "      加权公式: 物理${String.format("%.0f", physScore*100)}%×36% + ML${String.format("%.0f", mlRaw*100)}%×64% = ${String.format("%.2f", fallScore)}")
        AppLogger.w(TAG, "    路径3: 物理=${String.format("%.2f", physScore)}>=${cfg.physicsThresh} 且 冲击=${String.format("%.1f", peakValue)}g>=${DetectionConfig.IMPACT_MIN_HARDCODED}g 且 FF=${ffTime}ms>=${cfg.ffTimeMs} 且 ML=${String.format("%.2f", mlRaw)}>=0.25 → " + if(physicsOverride)"✅ 物理覆盖报警" else "❌")

        val step4 = isMlHigh
        val step5 = isMlLow
        val step6Ff = ffTime >= cfg.ffTimeMs
        val step6Q = freefallQuality >= cfg.ffQuality
        val step6V = peakVelocity >= cfg.velocityMin
        val step6Imp = peakValue >= DetectionConfig.IMPACT_MIN_HARDCODED

        val isFall = isMlHigh || (isMlLow && fallScore >= cfg.weightedScoreThresh) || physicsOverride

        val reason = when {
            isMlHigh -> "路径1:ML高阈值"
            physicsOverride -> "路径3:物理覆盖"
            isMlLow && fallScore >= cfg.weightedScoreThresh -> "路径2:加权评分=${String.format("%.2f", fallScore)}"
            !step2 -> "窗口不足"
            mlRaw < DetectionConfig.ML_LOW_HARDCODED -> "ML过低"
            else -> "加权评分=${String.format("%.2f", fallScore)}<${cfg.weightedScoreThresh}"
        }

        AppLogger.w(TAG, "  【最终结果】")
        if (isFall) {
            AppLogger.e(TAG, "  🚨🚨🚨 跌倒报警！原因: $reason")
        } else {
            AppLogger.w(TAG, "  ✅ 非跌倒，原因: $reason")
        }
        AppLogger.w(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

        val decisionPath = when {
            isMlHigh -> "路径1"
            isMlLow && fallScore >= 0.50f -> "路径2"
            physicsOverride -> "路径3"
            else -> "排除"
        }

        AppLogger.w(TAG, "  【最终结果】")
        if (isFall) {
            AppLogger.e(TAG, "  🚨🚨🚨 跌倒报警！原因: $reason")
        } else {
            AppLogger.w(TAG, "  ✅ 非跌倒，原因: $reason")
        }
        AppLogger.w(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

        val fallResult = if(isFall) "报警" else "排除"
        val summary = "[决策链] FF=${ffTime}ms ML=${String.format("%.2f", mlRaw)} 物理=${String.format("%.2f", physScore)} 加权=${String.format("%.2f", fallScore)} -> $fallResult($reason) | $decisionPath"

        _decisionLog.value = summary

        pushDiagnostic(
            accMag, dynamicAcc, calculateRecentMotionVariance(),
            if (isFall) "🚨跌倒!" else reason,
            step1Triggered = true,
            step2WindowOk = step2,
            step3MlRaw = mlRaw,
            step4MlHigh = step4,
            step5MlLow = step5,
            step6PhysFf = step6Ff,
            step6PhysQ = step6Q,
            step6PhysV = step6V,
            step6PhysImp = step6Imp,
            step7Result = isFall,
            decisionSummary = summary,
            // v0.25.16: 传递详细决策信息
            ffTimeMs = ffTime,
            ffScore = ffScore,
            impScore = impScore,
            velScore = velScore,
            physScore = physScore,
            weightedScore = fallScore,
            decisionPath = decisionPath,
            ffMerged = false,  // v0.29: FF合并信息已在process500msWindow中处理
            overweightDetected = false  // v0.27: 超重检测已废弃
        )

        _result.value = FallDetectionResult(
            isFallDetected = isFall,
            peakAcceleration = peakValue,
            mlProbability = mlRaw,
            confidence = fallScore,
            detectionMethod = if (isMlHigh) "ml_high" else if (physicsOverride) "phys_override" else "weighted_score",
            // v0.29.6: 新增详细信息
            physScore = physScore,
            impactG = peakValue,
            fallHeight = peakVelocity.let { v -> v * v / (2f * 9.81f) }  // h = v²/2g
        )
        
        // v0.30.9: 跌倒触发时保存事件到Room数据库（供反馈系统使用）
        if (isFall) {
            saveFallEvent(ffTime, peakValue, mlRaw, physScore, fallScore, decisionPath)
        }
        
        detectionState = DetectionState.MONITORING
        resetPhysicsState()
        return isFall
    }

    private fun pushDiagnostic(
        accMag: Float, dynamicAcc: Float, variance: Float,
        state: String,
        step1Triggered: Boolean = false,
        step2WindowOk: Boolean = false,
        step3MlRaw: Float = 0f,
        step4MlHigh: Boolean = false,
        step5MlLow: Boolean = false,
        step6PhysFf: Boolean = false,
        step6PhysQ: Boolean = false,
        step6PhysV: Boolean = false,
        step6PhysImp: Boolean = false,
        step7Result: Boolean = false,
        decisionSummary: String = "",
        // v0.25.16: 新增详细决策信息
        ffTimeMs: Long = 0L,
        ffScore: Float = 0f,
        impScore: Float = 0f,
        velScore: Float = 0f,
        physScore: Float = 0f,
        weightedScore: Float = 0f,
        decisionPath: String = "",
        ffMerged: Boolean = false,
        overweightDetected: Boolean = false
    ) {
        val physicsPass = freefallTimeMs >= DetectionConfig.ffTimeMs &&
                freefallQuality >= DetectionConfig.ffQuality &&
                peakVelocity >= DetectionConfig.velocityMin

        _diagnosticInfo.value = DiagnosticInfo(
            accMag = accMag, dynamicAcc = dynamicAcc,
            isRecording = false, peakValue = peakValue, variance = variance,
            accWindowSize = accWindow.size, gyroWindowSize = gyroWindow.size,
            mlProbability = latestMlProb,
            mlInferCount = mlInferCount, mlFallCount = mlFallCount,
            detectionState = state,
            freefallTimeMs = freefallTimeMs,
            freefallQuality = freefallQuality,
            peakVelocity = peakVelocity,
            impactStrength = impactStrength,
            physicsPass = physicsPass,
            step1_triggered = step1Triggered,
            step2_windowOk = step2WindowOk,
            step3_mlRaw = step3MlRaw,
            step4_mlHigh = step4MlHigh,
            step5_mlLow = step5MlLow,
            step6_physFf = step6PhysFf,
            step6_physQ = step6PhysQ,
            step6_physV = step6PhysV,
            step6_physImp = step6PhysImp,
            step7_result = step7Result,
            decisionSummary = decisionSummary,
            // v0.25.16: 新增详细决策信息
            ffTimeMs = ffTimeMs,
            ffScore = ffScore,
            impScore = impScore,
            velScore = velScore,
            physScore = physScore,
            weightedScore = weightedScore,
            decisionPath = decisionPath,
            ffMerged = ffMerged,
            overweightDetected = overweightDetected
        )
    }

    /**
     * v0.29: 从500ms窗口提取FF段并合并
     * 返回: 合并后的FF段列表
     */
    private fun process500msWindow(): List<FFSection> {
        if (windowBuffer.size < 4) return emptyList()

        val frames = windowBuffer.toList()
        val sections = mutableListOf<FFSection>()
        var i = 0

        // 1. 自然分段（accMag<0.7g连续区域=FF段）
        while (i < frames.size) {
            if (frames[i].accMag < FREEFALL_THRESHOLD) {
                val startIdx = i
                val startTime = frames[i].timestamp
                val velocities = mutableListOf<Float>()

                while (i < frames.size && frames[i].accMag < FREEFALL_THRESHOLD) {
                    velocities.add(frames[i].velocity)
                    i++
                }

                val endTime = frames[i - 1].timestamp
                val durationMs = endTime - startTime

                if (durationMs >= 20L) {  // 忽略<20ms的噪声
                    sections.add(FFSection(startTime, endTime, durationMs, velocities.toList()))
                }
            } else {
                i++
            }
        }

        if (sections.isEmpty()) return emptyList()
        if (sections.size == 1) return sections

        // 2. 合并相邻FF段（间隔<500ms且速度趋势递增）
        val merged = mutableListOf<FFSection>()
        var current = sections[0]

        for (j in 1 until sections.size) {
            val next = sections[j]
            val gap = next.startTime - current.endTime

            if (gap <= FF_MERGE_GAP_MS) {
                // 检查间隔期是否有超重（accMag>1.5g）
                val gapFrames = frames.filter { it.timestamp in current.endTime..next.startTime }
                val hasOverweight = gapFrames.any { it.accMag > 1.5f }

                if (hasOverweight) {
                    // 超重→不合并，硬切分
                    merged.add(current)
                    current = next
                    AppLogger.w(TAG, "✂️【硬切分-超重】间隔${gap}ms有超重")
                } else {
                    // v0.30.4: 用 signedVelocity 方向判断（替代速度趋势检测）
                    val gapVelocities = gapFrames.map { it.velocity }

                    when {
                        gapVelocities.size < 2 -> {
                            // 间隔期帧数不足，保守策略：不合并
                            merged.add(current)
                            current = next
                            AppLogger.w(TAG, "✂️【硬切分-间隔帧不足】${gapVelocities.size}帧 < 2")
                        }
                        else -> {
                            val startVel = gapVelocities.first()
                            val endVel = gapVelocities.last()
                            val delta = endVel - startVel

                            when {
                                delta > 0.05f -> {
                                    // 向下加速=跌倒趋势→合并
                                    val allVelocities = current.velocities + next.velocities
                                    current = FFSection(
                                        current.startTime,
                                        next.endTime,
                                        current.durationMs + next.durationMs,
                                        allVelocities
                                    )
                                    AppLogger.w(TAG, "🔗【FF合并-向下加速】delta=${String.format("%.2f", delta)}, gap=${gap}ms")
                                }
                                delta < -0.05f -> {
                                    // 向上加速=推手机→不合并
                                    merged.add(current)
                                    current = next
                                    AppLogger.w(TAG, "✂️【硬切分-向上推】delta=${String.format("%.2f", delta)}, gap=${gap}ms")
                                }
                                else -> {
                                    // |delta| < 0.05，看绝对速度
                                    val avgVel = gapVelocities.average().toFloat()
                                    if (avgVel > 0.5f) {
                                        // 速度>0.5→合并
                                        val allVelocities = current.velocities + next.velocities
                                        current = FFSection(
                                            current.startTime,
                                            next.endTime,
                                            current.durationMs + next.durationMs,
                                            allVelocities
                                        )
                                        AppLogger.w(TAG, "🔗【FF合并-低速】avgVel=${String.format("%.2f", avgVel)}, delta=${String.format("%.2f", delta)}")
                                    } else {
                                        // 速度<0.5→不合并（静止）
                                        merged.add(current)
                                        current = next
                                        AppLogger.w(TAG, "✂️【硬切分-静止】avgVel=${String.format("%.2f", avgVel)}, delta=${String.format("%.2f", delta)}")
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                // 间隔>500ms，不合并
                merged.add(current)
                current = next
                AppLogger.w(TAG, "✂️【硬切分-间隔超时】${gap}ms>500ms")
            }
        }

        merged.add(current)
        return merged
    }

    // effectiveFFTime已删除（v0.29改用freefallTimeMs直接传递）

    private fun resetPhysicsState() {
        freefallStart = 0L; freefallAccSum = 0f; freefallCount = 0
        freefallTimeMs = 0L
        freefallQuality = 0f; peakVelocity = 0f
        impactStrength = 0f; peakValue = 0f
        gravityAtFreefallStart = null; gravityAtImpactStart = null; angleChangeDeg = 0f
        signedVelocity = 0f
        lastDiagnosticPushTime = 0L
        // v0.29: 窗口buffer在reset时不清空，保留历史数据供下次冲击使用
    }

    private fun angleBetweenGravity(g1: FloatArray, g2: FloatArray): Float {
        val dot = g1[0]*g2[0] + g1[1]*g2[1] + g1[2]*g2[2]
        val clamped = dot.coerceIn(-1f, 1f)
        return Math.toDegrees(acos(clamped.toDouble())).toFloat()
    }

    private fun normalizeGravity(accX: Float, accY: Float, accZ: Float, mag: Float): FloatArray {
        if (mag < 0.01f) return floatArrayOf(0f, 0f, -1f)
        return floatArrayOf(accX / mag, accY / mag, accZ / mag)
    }

    private fun calculateRecentMotionVariance(): Float {
        if (accBuffer.size < 5) return 0f
        val recent = accBuffer.toList().takeLast(10)
        val mean = recent.map { it.first }.average().toFloat()
        return recent.map { (it.first - mean) * (it.first - mean) }.average().toFloat()
    }

    // 计算一维数组的标准差（用于三轴综合运动检测，2026-04-30 v0.32.3）
    private fun calcStd(values: List<Float>): Float {
        if (values.size < 2) return 0f
        val mean = values.average().toFloat()
        val variance = values.map { (it - mean) * (it - mean) }.average().toFloat()
        return sqrt(variance.toDouble()).toFloat()
    }

    // v0.27: 速度趋势检测（用于FF合并判断）
    // 核心思路：真跌倒速度从小到大递增，拿起放下速度震荡
    // 返回: Pair(isTrending, description)
    private fun detectVelocityTrend(history: List<Float>): Pair<Boolean, String> {
        if (history.size < 4) {
            // 帧数不足时，根据FF时长判断：短FF(<100ms)不合并
            val lastFf = if (freefallTimeMs > 0) freefallTimeMs else 0L
            return if (lastFf < 100L) {
                Pair(false, "(帧数${history.size}<4 且 FF=${lastFf}ms<100ms)")
            } else {
                Pair(true, "(帧数${history.size}<4 但 FF=${lastFf}ms>=100ms)")
            }
        }

        // 指标1: Spearman秩相关（单调性）
        val n = history.size
        val indexed = history.mapIndexed { idx, v -> idx to v }.sortedBy { it.second }
        val ranks = FloatArray(n)
        for (i in indexed.indices) {
            ranks[indexed[i].first] = (i + 1).toFloat()
        }
        val meanRank = (n + 1) / 2f
        var num = 0f
        var den1 = 0f
        var den2 = 0f
        for (i in 0 until n) {
            num += (ranks[i] - meanRank) * (i + 1 - meanRank)
            den1 += (ranks[i] - meanRank) * (ranks[i] - meanRank)
            den2 += (i + 1 - meanRank) * (i + 1 - meanRank)
        }
        val rho = if (den1 > 0 && den2 > 0) num / kotlin.math.sqrt(den1 * den2) else 0f

        // 指标2: 正差分比例
        val diffs = (1 until n).map { history[it] - history[it - 1] }
        val positiveRatio = diffs.count { it > 0 }.toFloat() / diffs.size

        // 指标3: 短期MA vs 长期MA
        val shortW = minOf(5, n)
        val shortMa = history.takeLast(shortW).average().toFloat()
        val longMa = history.average().toFloat()
        val maRatio = if (longMa > 0) shortMa / longMa else 1f

        // 指标4: 峰值位置（真跌倒峰值在末尾）
        val peakIdx = history.indices.maxByOrNull { history[it] } ?: 0
        val peakPos = peakIdx.toFloat() / n

        // 综合评分
        val trendScore = (
            maxOf(rho, 0f) * 0.25f +
            positiveRatio * 0.35f +
            maxOf(minOf(maRatio - 0.8f, 0.4f) * 0.5f, 0f) +
            peakPos * 0.15f
        ).coerceIn(0f, 1f)

        // 判断
        val isTrending = trendScore > 0.4f || positiveRatio > 0.55f || (rho > 0.3f && positiveRatio > 0.5f)
        val desc = "rho=${String.format("%.2f", rho)} pos=${String.format("%.2f", positiveRatio)} MA=${String.format("%.2f", maRatio)} peak=${String.format("%.1f", peakPos)}"

        return Pair(isTrending, desc)
    }

    fun triggerTestFall() {
        detectionState = DetectionState.IMPACT_DETECTED
        impactStartTime = System.currentTimeMillis()
        peakValue = 2.8f
        freefallTimeMs = 200L
        freefallQuality = 0.5f
        peakVelocity = 2.5f
        impactStrength = 7.0f
        latestMlProb = 0.85f
        latestMlEngine = "simulate"
    }

    fun resetStats() {
        mlInferCount = 0; mlFallCount = 0
    }

    // ============ v0.30.9: 反馈系统支持 ============
    
    /** 获取当前accWindow传感器数据（供反馈系统读取）*/
    fun getSensorSnapshotJson(): String {
        val frames = accWindow.toList()
        if (frames.isEmpty()) return "[]"
        val sb = StringBuilder("[")
        frames.forEachIndexed { i, f ->
            if (i > 0) sb.append(",")
            sb.append("{\"x\":${f[0]},\"y\":${f[1]},\"z\":${f[2]}}")
        }
        sb.append("]")
        return sb.toString()
    }
    
    /** 保存跌倒事件到Room数据库 */
    private fun saveFallEvent(
        ffTimeMs: Long, impactG: Float, mlProb: Float,
        physScore: Float, weightedScore: Float, decisionPath: String
    ) {
        val ctx = context ?: return
        val sensorJson = getSensorSnapshotJson()
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = com.falldetector.diedaobao.db.AppDatabase.getInstance(ctx)
                val event = com.falldetector.diedaobao.db.FallEvent(
                    timestamp = System.currentTimeMillis(),
                    ffTimeMs = ffTimeMs,
                    impactStrength = impactG,
                    mlProbability = mlProb,
                    physicsScore = physScore,
                    weightedScore = weightedScore,
                    decisionPath = decisionPath,
                    sensorDataJson = sensorJson,
                    appVersion = try {
                        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
                    } catch (_: Exception) { "" },
                    versionCode = try {
                        if (android.os.Build.VERSION.SDK_INT >= 28)
                            ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode.toInt()
                        else
                            @Suppress("DEPRECATION") ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionCode
                    } catch (_: Exception) { 0 },
                    sensitivityLevel = DetectionConfig.sensitivityLevel
                )
                val id = db.fallEventDao().insert(event)
                Log.i(TAG, "💾 跌倒事件已保存到Room: id=$id")
            } catch (e: Exception) {
                AppLogger.e(TAG, "❌ 保存跌倒事件失败: ${e.message}")
            }
        }
    }
    
    fun reset() {
        detectionState = DetectionState.MONITORING
        accWindow.clear()
        gyroWindow.clear()
        accBuffer.clear()
        resetPhysicsState()
        latestMlProb = 0f
        _result.value = FallDetectionResult()
    }
}
