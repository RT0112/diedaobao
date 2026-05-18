package com.falldetector.diedaobao.detect

import android.content.Context
import android.content.SharedPreferences
import kotlin.math.abs

/**
 * 跌倒检测参数配置（SharedPreferences 持久化）
 * 用户可在测试页面实时调节，FallDetector 每次感知事件时读取最新值
 */
object DetectionConfig {
    private const val PREFS_NAME = "detection_config"
    private const val KEY_ACC_THRESHOLD      = "acc_threshold"
    private const val KEY_ML_HIGH            = "ml_high"
    private const val KEY_ML_LOW             = "ml_low"
    private const val KEY_FF_TIME            = "ff_time"
    private const val KEY_FF_QUALITY        = "ff_quality"
    private const val KEY_VELOCITY_MIN       = "velocity_min"
    private const val KEY_IMPACT_MIN         = "impact_min"
    private const val KEY_WAIT_MS            = "wait_ms"

    // 默认值（v0.30.3：三路径阈值可配置）
    const val DEFAULT_ACC_THRESHOLD  = 0.6f   // g — 冲击触发门槛（dyn=|accMag-1g|）
    const val DEFAULT_ML_HIGH        = 0.75f  // 路径1：ML高阈值（直接报警）
    const val DEFAULT_WEIGHTED_SCORE = 0.50f  // 路径2：加权评分阈值
    const val DEFAULT_PHYSICS        = 0.80f  // 路径3：物理评分阈值
    const val DEFAULT_FF_TIME        = 200    // ms — 失重时间要求
    const val DEFAULT_FF_QUALITY     = 0.08f  // 失重质量
    const val DEFAULT_VELOCITY_MIN    = 0.8f   // m/s
    const val DEFAULT_IMPACT_MIN     = 3.0f   // g — 冲击峰值
    const val DEFAULT_WAIT_MS        = 300    // ms
    
    // 硬编码常量（不受灵敏度影响）
    const val ML_LOW_HARDCODED       = 0.50f  // ML低阈值（进入路径2的门槛）
    const val IMPACT_MIN_HARDCODED   = 3.0f   // g — 冲击最小值

    @Volatile private var prefs: SharedPreferences? = null

    // ── 快速读取（无 GC，每次 sensor 事件调用，用字段缓存）─────────────
    @Volatile var accThresholdG       = DEFAULT_ACC_THRESHOLD
    @Volatile var mlHigh              = DEFAULT_ML_HIGH          // 路径1阈值
    @Volatile var weightedScoreThresh = DEFAULT_WEIGHTED_SCORE    // 路径2阈值
    @Volatile var physicsThresh       = DEFAULT_PHYSICS           // 路径3阈值
    @Volatile var ffTimeMs            = DEFAULT_FF_TIME
    @Volatile var ffQuality           = DEFAULT_FF_QUALITY
    @Volatile var velocityMin         = DEFAULT_VELOCITY_MIN
    @Volatile var waitMs              = DEFAULT_WAIT_MS

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        reload()
    }

    fun reload() {
        prefs?.let { p ->
            // v0.30.0: 优先加载灵敏度等级（会覆盖参数）
            val savedLevel = p.getInt(KEY_SENSITIVITY_LEVEL, -1)
            if (savedLevel in 1..8) {
                applySensitivityLevel(savedLevel)
            } else {
                // 首次启动，使用默认等级
                applySensitivityLevel(DEFAULT_SENSITIVITY_LEVEL)
            }

            // 其他参数（不受灵敏度影响）
            accThresholdG  = p.getFloat(KEY_ACC_THRESHOLD, DEFAULT_ACC_THRESHOLD)
            ffQuality      = p.getFloat(KEY_FF_QUALITY,    DEFAULT_FF_QUALITY)
            velocityMin    = p.getFloat(KEY_VELOCITY_MIN,  DEFAULT_VELOCITY_MIN)
            waitMs         = p.getInt(KEY_WAIT_MS,          DEFAULT_WAIT_MS)
        }
    }

    // ── 保存单个参数 ────────────────────────────────────────────────
    fun saveAccThreshold(v: Float)     = save(KEY_ACC_THRESHOLD, v) { accThresholdG = v }
    fun saveMlHigh(v: Float)           = save(KEY_ML_HIGH, v) { mlHigh = v }
    fun saveWeightedScore(v: Float)    = save("weighted_score", v) { weightedScoreThresh = v }
    fun savePhysics(v: Float)          = save("physics", v) { physicsThresh = v }
    fun saveFfTime(v: Int)            = saveInt(KEY_FF_TIME, v) { ffTimeMs = v }
    fun saveFfQuality(v: Float)        = save(KEY_FF_QUALITY, v) { ffQuality = v }
    fun saveVelocityMin(v: Float)      = save(KEY_VELOCITY_MIN, v) { velocityMin = v }
    fun saveWaitMs(v: Int)            = saveInt(KEY_WAIT_MS, v) { waitMs = v }

    fun resetAll() {
        prefs?.edit()?.clear()?.apply()
        reload()
    }

    fun getAllValues(): Map<String, Any> = mapOf(
        "accThresholdG" to accThresholdG,
        "mlHigh"              to mlHigh,
        "weightedScoreThresh" to weightedScoreThresh,
        "physicsThresh"       to physicsThresh,
        "ffTimeMs"            to ffTimeMs,
        "ffQuality"           to ffQuality,
        "velocityMin"         to velocityMin,
        "waitMs"              to waitMs,
        // 硬编码常量（只读）
        "mlLowHardcoded"      to ML_LOW_HARDCODED,
        "impactMinHardcoded"  to IMPACT_MIN_HARDCODED
    )

    // ── 诊断摘要（拼成一行供日志用）──────────────────────────────────
    fun summary(): String {
        return "dyn>${"%.1f".format(accThresholdG)}g | " +
                "ML高=${"%.2f".format(mlHigh)} 路径2=${"%.2f".format(weightedScoreThresh)} 路径3=${"%.2f".format(physicsThresh)} | " +
                "ff>${ffTimeMs}ms q>${"%.2f".format(ffQuality)} v>${"%.1f".format(velocityMin)} | " +
                "wait=${waitMs}ms [硬编码:ML低=${ML_LOW_HARDCODED}, 冲击=${IMPACT_MIN_HARDCODED}g]"
    }

    // ── 8 级灵敏度调整（v0.30.0）────────────────────────────────────
    // 等级 1 = 最严格（ffTime=250ms, 阈值最严）
    // 等级 4 = 默认推荐（ffTime=200ms）
    // 等级 8 = 最宽松（ffTime=150ms, 阈值最宽）
    private const val KEY_SENSITIVITY_LEVEL = "sensitivity_level"
    const val DEFAULT_SENSITIVITY_LEVEL = 4  // 默认推荐等级

    @Volatile var sensitivityLevel = DEFAULT_SENSITIVITY_LEVEL

    // 灵敏度等级对应的参数表（索引0不使用，1-8有效）
    private val SENSITIVITY_TABLE = mapOf(
        "ffTimeMs" to listOf(0, 250, 235, 220, 200, 185, 170, 160, 150),
        "mlHigh" to listOf(0f, 0.85f, 0.80f, 0.78f, 0.75f, 0.72f, 0.70f, 0.68f, 0.65f),
        "weightedScore" to listOf(0f, 0.60f, 0.55f, 0.52f, 0.50f, 0.48f, 0.45f, 0.42f, 0.40f),
        "physics" to listOf(0f, 0.90f, 0.85f, 0.82f, 0.80f, 0.78f, 0.75f, 0.72f, 0.70f)
    )

    /**
     * 应用灵敏度等级（1-8）
     * 会自动调整 ffTimeMs、mlHigh、weightedScoreThresh、physicsThresh
     */
    fun applySensitivityLevel(level: Int) {
        val lvl = level.coerceIn(1, 8)
        sensitivityLevel = lvl

        // 从表中取出对应参数（需类型转换）
        ffTimeMs = SENSITIVITY_TABLE["ffTimeMs"]!![lvl] as Int
        mlHigh = SENSITIVITY_TABLE["mlHigh"]!![lvl] as Float
        weightedScoreThresh = SENSITIVITY_TABLE["weightedScore"]!![lvl] as Float
        physicsThresh = SENSITIVITY_TABLE["physics"]!![lvl] as Float

        // 持久化
        prefs?.edit()?.putInt(KEY_SENSITIVITY_LEVEL, lvl)?.apply()
    }

    fun loadSensitivityLevel() {
        val lvl = prefs?.getInt(KEY_SENSITIVITY_LEVEL, DEFAULT_SENSITIVITY_LEVEL) ?: DEFAULT_SENSITIVITY_LEVEL
        applySensitivityLevel(lvl)
    }

    // ── 私有 ────────────────────────────────────────────────────────
    private inline fun save(key: String, value: Float, then: () -> Unit) {
        prefs?.edit()?.putFloat(key, value)?.apply()
        then()
    }
    private inline fun saveInt(key: String, value: Int, then: () -> Unit) {
        prefs?.edit()?.putInt(key, value)?.apply()
        then()
    }
}
