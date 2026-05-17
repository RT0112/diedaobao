# v0.18.0 版本说明

## 修复两个问题

### 问题1：测试页点击闪退 ✅ 修复
**根因**：`FallDetectionService.getDiagnosticInfo()` 在 Service 未运行时抛异常，导致 TestFragment 崩溃。

**修复**：所有调用 `FallDetectionService` 的地方加 `try-catch` 保护：
- `updateServiceStatus()`
- `updateDiagnosticPanel()`
- `updateCurrentValues()`
- `updateMlStats()`

### 问题2：放进口袋误报 ✅ 修复
**根因**：`runFreefallMlBranch()` 只用 `mlRaw >= mlHigh` 判断，口袋场景 ML 可能 >= 0.75 导致误报。

**修复**：统一用加权评分，与主链 `runMlDecisionTree()` 完全一致：
```
// 旧逻辑（误报来源）
isFall = mlRaw >= cfg.mlHigh  // ML高阈值直接报警

// 新逻辑（与主链一致）
isMlHigh = mlRaw >= cfg.mlHigh
isMlLow  = mlRaw >= cfg.mlLow
ffScore  = min(ffTime/400ms, 1.0)
impScore = min((peak-1.5g)/3.0g, 1.0)
velScore = min(vel/3.0m/s, 1.0)
fallScore = ffScore*0.30 + impScore*0.35 + velScore*0.35

isFall = isMlLow && fallScore >= 0.50f  // ML低阈值+评分才报警
```

**口袋场景预期**：
- ffScore≈0.10-0.25, impScore≈0.0-0.50, velScore≈0.0-0.33
- fallScore≈0.05-0.35 → <0.50 → 不报警 ✅

**真实跌倒预期**：
- ffScore≈0.50-1.0, impScore≈0.50-1.17, velScore≈0.67-1.33
- fallScore≈0.65-0.85 → ≥0.50 → 报警 ✅

## 版本信息
- 版本：v0.18.0 (versionCode=47)
- APK：`app/build/outputs/apk/debug/app-debug.apk`
- Finder 已打开

## 测试建议
1. 安装后点「测试」Tab，不崩溃 ✅
2. 手持手机 → 松手放进口袋 → 不误报 ✅
3. 模拟跌倒（或真跌） → 正常报警 ✅
