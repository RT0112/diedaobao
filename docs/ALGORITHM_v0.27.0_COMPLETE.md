# 跌倒检测算法 v0.27.0 完整文档

> 生成时间: 2026-04-27 23:03
> 项目路径: `/Users/mac/.qclaw/workspace-agent-cc80c0cf/projects/fall-detection-app/`
> 核心文件: `app/src/main/java/com/falldetector/diedaobao/detect/FallDetector.kt` (681行)

---

## 一、顶层架构

### 1.1 状态机

```
MONITORING (监控状态)
    ↓ 冲击触发 (dynamicAcc > 0.6g 且 FF≥200ms 或 冲击≥5g)
IMPACT_DETECTED (确认状态)
    ↓ 1500ms确认期结束
决策链执行
    ↓
返回 MONITORING
```

### 1.2 核心流程

```
传感器数据输入
    ↓
冷却期检查 (2800ms)
    ↓
窗口维护 (125帧, 2.5秒)
    ↓
速度积分 (用于趋势检测)
    ↓
状态分支:
  - MONITORING: FF追踪 + 冲击检测
  - IMPACT_DETECTED: 确认期等待
    ↓
决策链 (三路径)
    ↓
结果输出
```

---

## 二、MONITORING 状态详细逻辑

### 2.1 冷却期检查

```kotlin
if (now - lastPeakTime < 2800ms) {
    freefallStart = 0  // 清零防止残留
    return  // 跳过所有处理
}
```

**作用**: 报警后2800ms内不处理任何传感器数据，防止重复触发。

---

### 2.2 窗口维护

```kotlin
// 传感器窗口
accWindow.addLast(floatArrayOf(accX, accY, accZ))
gyroWindow.addLast(floatArrayOf(gyroX, gyroY, gyroZ))
if (accWindow.size > 125) accWindow.removeFirst()

// 动态加速度缓冲
accBuffer.addLast(Pair(dynamicAcc, timestamp))
if (accBuffer.size > 125) accBuffer.removeFirst()
```

**参数**:
- 窗口大小: 125帧 (2.5秒 @ 50Hz)
- 用途: ML推理输入 + 方差计算

---

### 2.3 速度积分

```kotlin
val dt_s = 0.02f  // 50Hz采样
val accel = when {
    accMag < 0.7f -> 9.81f * (1.0f - accMag)        // 失重: 正向加速
    accMag > 1.3f -> -9.81f * (accMag - 1.0f) * 0.5f  // 超重: 负向加速(衰减)
    else -> 9.81f * (1.0f - accMag)                // 正常: 微调
}
signedVelocity += accel * dt_s
```

**作用**: 
- 追踪有符号速度，用于FF合并时的趋势判断
- 失重时速度增加，超重时速度减少
- 超重衰减系数0.5，避免过度敏感

---

### 2.4 FF (自由落体) 追踪

#### 2.4.1 FF段开始

**条件**: `accMag < 0.7g` 且 `freefallStart == 0`

```kotlin
freefallStart = now
freefallAccSum = accMag
freefallCount = 1
```

**FF合并判断** (v0.27核心变更):

```kotlin
if (pauseStart > 0L) {
    val pauseDuration = now - pauseStart
    if (pauseDuration <= 500ms) {
        // 暂停期在500ms内，用速度趋势判断
        val (isTrending, trendDesc) = detectVelocityTrend(velocityHistory)
        if (isTrending) {
            // 速度趋势递增 → FF合并
            freefallTotalAccum += freefallTimeMs
            Log.w("🔗【FF合并-趋势递增】")
        } else {
            // 速度震荡 → 硬切分
            freefallTotalAccum = 0L
            Log.w("🔄【FF硬切分-速度震荡】")
        }
        velocityHistory.clear()
    } else {
        // 暂停超时 → 不合并
        freefallTotalAccum = 0L
        velocityHistory.clear()
    }
} else {
    // 第一段FF
    velocityHistory.clear()
}
```

**关键点**:
- 暂停期 ≤ 500ms 才考虑合并
- 用速度趋势替代超重检测
- 趋势递增 → 合并 (真跌倒特征)
- 速度震荡 → 硬切分 (拿起放下特征)

#### 2.4.2 FF段继续

**条件**: `accMag < 0.7g` 且 `freefallStart > 0`

```kotlin
freefallAccSum += accMag
freefallCount++
velocityHistory.add(signedVelocity)  // 记录速度历史
```

#### 2.4.3 FF段结束

**条件**: `accMag >= 0.7g` 且 `freefallStart > 0`

```kotlin
freefallTimeMs = now - freefallStart
freefallQuality = 1.0f - freefallAccSum / freefallCount
peakVelocity = 9.81f * (freefallTimeMs / 1000f)
freefallEndTime = now

// 开始暂停期
pauseStart = now
freefallStart = 0L
```

**计算**:
- FF时长: 当前时间 - FF开始时间
- FF质量: 1 - 平均加速度 (越接近1g质量越高)
- 峰值速度: 9.81 × FF时长(秒)

---

### 2.5 暂停期追踪

**条件**: `pauseStart > 0` 且 `accMag >= 0.7g`

```kotlin
val pauseDuration = now - pauseStart
if (pauseDuration > 500ms) {
    // 暂停超时，不再合并
    pauseStart = 0L
    velocityHistory.clear()
    freefallTotalAccum = 0L
}
```

**作用**: 等待下一段FF，超过500ms则放弃合并。

---

### 2.6 冲击检测

**条件**: `dynamicAcc > 0.6g`

```kotlin
val step1 = dynamicAcc > cfg.accThresholdG  // 0.6g

if (step1) {
    // 冷却期二次检查
    val inCooldown = (now - lastPeakTime) < 2800ms
    if (inCooldown && lastPeakTime > 0) return
    
    // FF<200ms排除 (在进入确认期之前)
    val ffTimeNow = effectiveFFTime()
    val isSevereNow = accMag >= 5.0f
    if (ffTimeNow in 1L..199L && !isSevereNow) {
        Log.w("⚡【立即排除】FF=${ffTimeNow}ms<200ms 且 冲击<5g")
        return  // 不进确认期
    }
    
    // 进入确认期
    detectionState = IMPACT_DETECTED
    impactStartTime = now
    peakValue = accMag
    gravityAtImpactStart = normalizeGravity(accX, accY, accZ, accMag)
}
```

**关键点**:
- 冲击阈值: 0.6g (动态加速度)
- FF<200ms立即排除 (5g豁免)
- 5g严重跌倒跳过FF检查

---

## 三、IMPACT_DETECTED 状态详细逻辑

### 3.1 确认期等待

```kotlin
if (detectionState == IMPACT_DETECTED) {
    // 更新peakValue
    if (accMag > peakValue) peakValue = accMag
    
    val elapsed = now - impactStartTime
    val confirmDuration = 1500L
    
    if (elapsed < confirmDuration) {
        // 方差检查
        if (confirmationAccBuffer.size >= 10) {
            val recent = confirmationAccBuffer.takeLast(10)
            val mean = recent.map { it.first }.average()
            val variance = recent.map { (it.first - mean) * (it.first - mean) }.average()
            if (variance > 0.5f) {
                Log.w("🚶【确认期取消】方差>0.5 → 用户在移动")
                detectionState = MONITORING
                resetPhysicsState()
                return
            }
        }
        return  // 继续等待
    }
    
    // 确认期结束，运行决策链
    runMlDecisionTree(...)
}
```

**参数**:
- 确认期时长: 1500ms
- 方差取消阈值: 0.5
- 方差检查帧数: 10

---

## 四、决策链详细逻辑

### 4.1 ML推理

```kotlin
val step2 = accWindow.size >= 50

if (step2) {
    mlInferCount++
    val accXArr = accWindow.map { it[0] }.toFloatArray()
    val accYArr = accWindow.map { it[1] }.toFloatArray()
    val accZArr = accWindow.map { it[2] }.toFloatArray()
    
    if (onnxEngine != null) {
        mlRaw = onnxEngine!!.predict(accXArr, accYArr, accZArr)
        engine = "onnx"
    } else if (mlEngine != null) {
        mlRaw = mlEngine!!.predictFromSensorWindow(accList, gyroList)
        engine = "tflite"
    }
} else {
    mlRaw = 0f
    engine = "window_small(${accWindow.size})"
}
```

**ML引擎优先级**: ONNX > TFLite

---

### 4.2 物理评分计算

```kotlin
val ffTime = effectiveFFTime()  // max(freefallTimeMs, freefallTotalAccum)

// FF评分
val ffScore = minOf(ffTime / 400f, 1f)

// 冲击评分
val impScore = maxOf(minOf((peakValue - 1.5f) / 3.0f, 1f), 0f)

// 速度评分
val velScore = minOf(peakVelocity / 3.0f, 1f)

// 物理总分
val physScore = ffScore * 0.35f + impScore * 0.35f + velScore * 0.30f
```

**公式**:
- FF分 = min(FF时长/400ms, 1)
- 冲击分 = clamp((峰值-1.5g)/3g, 0, 1)
- 速度分 = min(速度/3m/s, 1)
- 物理总分 = FF×35% + 冲击×35% + 速度×30%

---

### 4.3 三路径决策

```kotlin
val isMlHigh = mlRaw >= 0.75f
val isMlLow = mlRaw >= 0.50f
val fallScore = physScore * 0.36f + mlRaw * 0.64f  // 加权评分
val physicsOverride = physScore >= 0.80f && peakValue >= 3g && ffTime >= 200 && mlRaw >= 0.25f

val isFall = isMlHigh || (isMlLow && fallScore >= 0.50f) || physicsOverride
```

**路径1 - ML高阈值**:
- 条件: `mlRaw >= 0.75`
- 结果: 直接报警

**路径2 - 加权评分**:
- 条件: `mlRaw >= 0.50` 且 `fallScore >= 0.50`
- 加权评分: `physScore×36% + mlRaw×64%`
- 结果: 加权报警

**路径3 - 物理覆盖**:
- 条件: `physScore >= 0.80` 且 `peakValue >= 3g` 且 `ffTime >= 200ms` 且 `mlRaw >= 0.25`
- 结果: 物理覆盖报警

---

### 4.4 结果输出

```kotlin
_result.value = FallDetectionResult(
    isFallDetected = isFall,
    peakAcceleration = peakValue,
    mlProbability = mlRaw,
    confidence = fallScore,
    detectionMethod = when {
        isMlHigh -> "ml_high"
        physicsOverride -> "phys_override"
        else -> "weighted_score"
    }
)

detectionState = MONITORING
resetPhysicsState()
```

---

## 五、速度趋势检测 (v0.27核心)

### 5.1 detectVelocityTrend 函数

**输入**: `velocityHistory` (FF期间的速度历史)

**输出**: `Pair<Boolean, String>` (是否趋势递增, 描述)

```kotlin
fun detectVelocityTrend(history: List<Float>): Pair<Boolean, String> {
    // 帧数不足特殊处理
    if (history.size < 4) {
        val lastFf = if (freefallTimeMs > 0) freefallTimeMs else 0L
        return if (lastFf < 100L) {
            Pair(false, "(帧数<4 且 FF<100ms)")
        } else {
            Pair(true, "(帧数<4 但 FF>=100ms)")
        }
    }
    
    // 指标1: Spearman秩相关
    val rho = calculateSpearman(history)
    
    // 指标2: 正差分比例
    val diffs = (1 until n).map { history[it] - history[it - 1] }
    val positiveRatio = diffs.count { it > 0 }.toFloat() / diffs.size
    
    // 指标3: MA比值
    val shortMa = history.takeLast(5).average()
    val longMa = history.average()
    val maRatio = shortMa / longMa
    
    // 指标4: 峰值位置
    val peakIdx = history.indices.maxByOrNull { history[it] } ?: 0
    val peakPos = peakIdx.toFloat() / n
    
    // 综合评分
    val trendScore = (
        maxOf(rho, 0f) * 0.25f +
        positiveRatio * 0.35f +
        maxOf(minOf(maRatio - 0.8f, 0.4f) * 0.5f, 0f) +
        peakPos * 0.15f
    )
    
    // 判断
    val isTrending = trendScore > 0.4f || positiveRatio > 0.55f || (rho > 0.3f && positiveRatio > 0.5f)
    
    return Pair(isTrending, "rho=${rho} pos=${positiveRatio} MA=${maRatio} peak=${peakPos}")
}
```

### 5.2 四层指标详解

**指标1 - Spearman秩相关** (单调性):
- 计算速度序列的秩相关系数
- 真跌倒: 速度单调递增 → ρ接近1
- 拿起放下: 速度震荡 → ρ接近0或负

**指标2 - 正差分比例** (递增帧占比):
- 计算相邻帧差分中正值的比例
- 真跌倒: 大部分差分>0 → ratio接近1
- 拿起放下: 正负交替 → ratio接近0.5

**指标3 - MA比值** (短期/长期动量):
- 短期MA = 最近5帧平均
- 长期MA = 全部帧平均
- 真跌倒: 短期MA > 长期MA → ratio > 1
- 拿起放下: ratio ≈ 1

**指标4 - 峰值位置** (峰值在序列中的位置):
- peakPos = 峰值索引 / 总帧数
- 真跌倒: 峰值在末尾 → peakPos接近1
- 拿起放下: 峰值在中间 → peakPos ≈ 0.5

### 5.3 综合评分权重

```
trendScore = ρ×25% + positiveRatio×35% + MA项×15% + peakPos×15%
```

### 5.4 判断逻辑 (三层OR)

```
isTrending = 
    trendScore > 0.4  或
    positiveRatio > 0.55  或
    (rho > 0.3 且 positiveRatio > 0.5)
```

**设计思路**: 任何一个指标明显符合趋势特征就判定为趋势递增，避免单一指标误判。

---

## 六、关键参数总表

| 分类 | 参数 | 值 | 说明 |
|------|------|-----|------|
| **FF检测** | FF阈值 | 0.7g | accMag < 0.7g 判定为失重 |
| | FF合并间隔 | 500ms | 暂停期超过500ms不合并 |
| | FF<200ms排除 | 200ms | 短FF排除（5g豁免） |
| **冲击检测** | 冲击阈值 | 0.6g | dynamicAcc > 0.6g 触发 |
| | 5g豁免 | 5.0g | 冲击≥5g跳过FF<200ms检查 |
| | 冲击评分起点 | 1.5g | 冲击分=(peak-1.5g)/3g |
| | 物理覆盖冲击 | 3g | 路径3要求冲击≥3g |
| **时间参数** | 确认期 | 1500ms | 冲击后等待确认 |
| | 冷却期 | 2800ms | 报警后冷却 |
| | 采样率 | 50Hz | 20ms/帧 |
| | 窗口大小 | 125帧 | 2.5秒历史 |
| | ML窗口要求 | 50帧 | 窗口≥50才推理 |
| **ML参数** | ML高阈值 | 0.75 | 路径1直接报警 |
| | ML低阈值 | 0.50 | 路径2加权评分 |
| | 物理覆盖ML | 0.25 | 路径3最低ML要求 |
| **评分权重** | FF权重 | 35% | 物理评分内 |
| | 冲击权重 | 35% | 物理评分内 |
| | 速度权重 | 30% | 物理评分内 |
| | 物理权重 | 36% | 加权评分内 |
| | ML权重 | 64% | 加权评分内 |
| **速度趋势** | 最小帧数 | 4 | 帧数不足特殊处理 |
| | 短FF阈值 | 100ms | 帧数不足时根据FF判断 |
| | 趋势评分阈值 | 0.4 | trendScore > 0.4 |
| | 正差分阈值 | 0.55 | positiveRatio > 0.55 |
| | Spearman阈值 | 0.3 | ρ > 0.3 |
| | Spearman权重 | 25% | 综合评分内 |
| | 正差分权重 | 35% | 综合评分内 |
| | MA权重 | 15% | 综合评分内 |
| | 峰值位置权重 | 15% | 综合评分内 |
| **确认期取消** | 方差阈值 | 0.5 | 方差 > 0.5 取消 |
| | 检查帧数 | 10 | 最近10帧 |

---

## 七、数据结构

### 7.1 传感器窗口

```kotlin
private val accWindow = LinkedList<FloatArray>()  // 加速度窗口
private val gyroWindow = LinkedList<FloatArray>()  // 陀螺仪窗口
private val accBuffer = LinkedList<Pair<Float, Long>>()  // 动态加速度缓冲
```

### 7.2 FF追踪变量

```kotlin
private var freefallStart = 0L         // 当前FF段开始时间
private var freefallAccSum = 0f        // FF段加速度累加
private var freefallCount = 0          // FF段帧数
private var freefallTimeMs = 0L        // 当前FF段时长
private var freefallQuality = 0f       // FF质量 (1-avgAcc)
private var peakVelocity = 0f          // 峰值速度
private var freefallEndTime = 0L       // FF结束时间
```

### 7.3 FF合并变量

```kotlin
private var freefallTotalAccum = 0L    // 累计FF时长 (合并后)
private var pauseStart = 0L            // 暂停期开始时间
private val velocityHistory = mutableListOf<Float>()  // 速度历史
private var signedVelocity = 0f        // 有符号速度
```

### 7.4 状态变量

```kotlin
private var detectionState = DetectionState.MONITORING
private var impactStartTime = 0L       // 冲击触发时间
private var peakValue = 0f             // 峰值加速度
private var lastPeakTime = 0L          // 上次峰值时间 (冷却期)
```

---

## 八、v0.27 核心变更

### 8.1 删除超重检测逻辑

**删除内容**:
- `standingAccMagWindow` (站立期滚动窗口)
- `pauseHadOverweight` (暂停期超重标志)
- `overweightStartTime` (超重开始时间)
- `standingOverweightStartTime` (站立期超重开始)
- `preFfOverweight` (前置超重标志)
- `shouldMergeFF()` 函数 (超重合并判断)

### 8.2 新增速度趋势检测

**新增内容**:
- `velocityHistory` (FF期间速度历史)
- `signedVelocity` (有符号速度)
- `detectVelocityTrend()` 函数 (四层指标综合判断)

### 8.3 核心思路转变

**之前 (v0.25)**:
- 用超重检测判断FF合并
- 超重 → 硬切分 (推手机)
- 无超重 → 合并 (真跌倒)

**现在 (v0.27)**:
- 用速度趋势判断FF合并
- 速度递增 → 合并 (真跌倒)
- 速度震荡 → 硬切分 (拿起放下)

**原理**:
- 真跌倒: 速度从小到大单调递增 (单峰)
- 拿起放下: 速度反复震荡 (多峰)

---

## 九、日志关键词速查

| 关键词 | 含义 |
|--------|------|
| `🔗【FF合并-趋势递增】` | 速度趋势判断为递增，FF合并 |
| `🔄【FF硬切分-速度震荡】` | 速度震荡，从当前FF重新开始 |
| `⏰【暂停超时】` | 暂停期>500ms，不合并 |
| `📏【FF段结束】` | FF段结束，计算时长/质量 |
| `⚡【冲击触发】` | dynamicAcc>0.6g，进入确认期 |
| `⚡【立即排除】` | FF<200ms且冲击<5g，不进确认期 |
| `🚶【确认期取消】` | 方差>0.5，用户在移动 |
| `🚨🚨🚨 跌倒报警` | 决策链判定为跌倒 |
| `✅ 非跌倒` | 决策链判定为非跌倒 |

---

## 十、版本历史

| 版本 | 关键变更 |
|------|----------|
| v0.25.5 | 原始完整版 (~940行) |
| v0.25.6 | git checkout误删，重建简化版 |
| v0.25.7 | FF<200ms排除移到冲击触发时 |
| v0.25.8 | 冲击阈值修正 1.5g→0.6g |
| v0.25.9 | 删除废弃自由落体分支 |
| v0.25.14 | 超重硬切分修复 |
| v0.25.28 | 站立期超重检测修复 |
| **v0.27.0** | **速度趋势FF合并 (删除超重逻辑)** |

---

**文档完成时间**: 2026-04-27 23:03
**代码行数**: 681行
**编译状态**: BUILD SUCCESSFUL
**APK路径**: `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`
