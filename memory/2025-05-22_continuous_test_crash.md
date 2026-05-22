# 2025-05-22 连续测试发现CRASH + Skill更新 + CC修复

## 事件
用户要求连续几次测试"查看位置"功能，发现老人端每次都CRASH。

## CRASH详情
- 错误: `java.lang.NoSuchMethodError: No virtual method put(Ljava/lang/String;F)Lorg/json/JSONObject;`
- 位置: `CloudBaseClient.kt:337` in `uploadLocationNow()`
- 触发: 子女端每次点"查看位置" → 后端WS推location_request给老人端 → 老人端执行uploadLocationNow() → CRASH
- 根因: CC上次改代码时用了 `JSONObject.put(String, Float)`，违反铁律6（Float必须.toDouble()）
- 影响: pullLocationStatus永远pending，子女端看到的位置是缓存旧数据

## 连续测试结果
| 次数 | 子女端 | 老人端 |
|------|--------|--------|
| 第1次 | 地图正常，显示缓存位置 | CRASH ❌ |
| 第2次 | 同上 | CRASH ❌ (系统已标记"Too noisy") |
| 第3次 | 同上 | CRASH ❌ |

## Skill更新 (diedaobao-k70-debug)
新增内容:
- Step 10: 连续测试流程（BACK→tap→抓日志循环）
- 已知按钮快速参考表
- CRASH日志识别关键词
- 验收指标pass/fail判定表
- 铁律: 连续3次通过才算PASS

## CC修复
- CC已派出 (proc_bd4c98545b1c)，修复 CloudBaseClient.kt:337 的 Float→Double 问题
- 修复后编译+安装K70+发微信
