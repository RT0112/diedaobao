# 围栏越界通知Bug修复报告

## 日期
2026-05-22

## 用户问题
子女端接收围栏通知，一次都没收到。

---

## 排查过程

### Step 1: 定位代码层面的Bug ✅

分析 `FallDetectionService.kt` 的 `notifyGuardianOfBreach()` 方法：

```kotlin
// 原代码问题：企微通知和WS推送在同一协程内
private fun notifyGuardianOfBreach(fenceName: String, distance: Double) {
    serviceScope.launch(Dispatchers.IO) {
        try {
            if (webhookUrl.isEmpty()) {
                AppLogger.w(TAG, "企微Webhook未配置，跳过围栏越界通知")
                return@launch  // ❌ BUG: 退出整个协程，后续WS推送不执行
            }
            // ... 企微发送 ...
        } catch (e: Exception) { }

        // WS 推送代码永远到不了这里！
        try {
            CloudBaseClient.reportGeofenceBreach(...)
        } catch (e: Exception) { }
    }
}
```

**Bug**: 当企微Webhook未配置时，`return@launch` 退出整个 `launch{}` 协程，导致 WS 推送代码永不执行。

**修复**: 拆成两个独立协程，互不干扰。

### Step 2: K70 验收时发现真正的根因 ⚠️

验收时才发现上面的 bug 修了，但**围栏通知还是收不到**，原因是：

```
FallDetectionService: 围栏缓存已刷新: 0个围栏
```

**真实根因**：
- 老人端登录的 userId: `mp9c6n7qoew5spu9l`
- 数据库围栏的 elderId: `mpfo2h30n6g3zg9j5`
- **两个用户不匹配** → 老人端 `action=list` 返回空 → `cachedFences` 为空 → `checkLocalGeofence` 从未触发

### Step 3: 验证修复

1. **补建围栏**: 为当前用户 `mp9c6n7qoew5spu9l` 创建围栏 `mpgpykr4hpvhp46bl`
2. **重启App**: 围栏加载成功 → `围栏缓存已刷新: 1个围栏` ✅
3. **后端推送测试**: 直接调 `breach_notify` 接口 → 子女端收到 `⚠️ 收到围栏越界告警` ✅
4. **待验证**: 实际走出围栏的完整链路（需要手动测试）

---

## 教训

1. **先验证再修代码**：这次直接假设 `return@launch` 是根因，没先清日志验证老人端有没有调用 `notifyGuardianOfBreach`
2. **先验收再发微信**：修完直接发了微信，用户提醒才回头做验收
3. **数据问题也是根因**：老人端登录用户和围栏 elderId 不匹配，这是数据问题不是代码 bug

---

## 修改的文件
- `fall-detection-app/app/src/main/java/com/falldetector/diedaobao/service/FallDetectionService.kt` (第847-907行)
- `fall-detection-app/app/build.gradle.kts` (versionCode 152→153, versionName 0.45.8→0.45.9)

## Git Commit
```
fix: 修复围栏越界通知收不到的bug (notifyGuardianOfBreach return@launch问题)
- 根因: 企微Webhook未配置时return@launch退出整个协程，导致WS推送代码不执行
- 修复: 将企微通知和WS推送拆成两个独立协程，互不干扰
- 版本: 0.45.8 → 0.45.9 (versionCode 152→153)
```

## 待手动验证
1. 带着 K70 走出围栏区域（距离 > 200米）
2. 观察子女端是否收到系统通知 + Toast
3. 命令: `adb -s a0c2910e logcat -d | grep -E "围栏越界|WS推送结果|收到围栏越界"`
