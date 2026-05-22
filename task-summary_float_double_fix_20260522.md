## Fix Crash: JSONObject.put String Float → Double

**Objective**: 将 fall-detection-app 中所有 JSONObject.put 的 Float 参数改为 Double，解决崩溃问题。

**Key Reasoning**:
- Android/Java的 `JSONObject.put(String, Float)` 方法不存在
- 只有 `put(String, Double)`, `put(String, Int)` 等签名
- Kotlin的Float不会自动装箱为Double，运行时抛出 `NoSuchMethodError`
- 该错误被try-catch吞掉，导致 reportFall() 静默失败

**File Changed**: 
- `/projects/fall-detection-app/cloud/CloudBaseClient.kt`

**Diff**:
```diff
- put("impactG", impactG)
+ put("impactG", impactG.toDouble())
- put("mlScore", mlScore)
+ put("mlScore", mlScore.toDouble())
- put("physicalScore", physicalScore)
+ put("physicalScore", physicalScore.toDouble())
- put("accuracy", accuracy)
+ put("accuracy", accuracy.toDouble())
```

**Commit**: `02239f8` — "fix: CloudBaseClient JSONObject.put Float→Double crash"

**Build Status**: 等待构建(缺少Java)

**Conclusion**: 代码修复已完成，需要有Java环境才能执行 `./gradlew assembleDebug`