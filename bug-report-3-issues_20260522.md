# Bug报告（CC处理）

## Bug 1：子女端通知标题仍显示"跌倒警报"

**文件**：`family-guardian-app/app/src/main/java/com/familyguardian/app/ui/HomeFragment.kt`  
**行号**：338 和 355  
**当前代码**：
```kotlin
.setContentTitle("🚨 ${notification.elderName}跌倒警报！")
```
**问题**：之前说好改成通用描述"请及时确认老人状况"，但代码里还是"跌倒警报"。两个通知构建器（全屏通知和普通通知）都是这个标题。  
**期望**：标题改为通用，不暴露跌倒细节。

---

## Bug 2：MapActivity"查看位置"卡住，一直显示"正在获取"

**文件**：`family-guardian-app/app/src/main/java/com/familyguardian/app/ui/MapActivity.kt`  
**根因**：`wsLocationReceived` 标志位在**开始新一次位置请求时没有重置为`false`**。  
**复现步骤**：
1. 打开MapActivity → 点击"查看位置"
2. 成功获取到位置（此时 `wsLocationReceived = true`）
3. 退出MapActivity
4. 再次打开MapActivity → 点击"查看位置"
5. **卡住**，一直显示"正在获取"

**原因**：`loadElderLocation()` 里没有 `wsLocationReceived = false` 这一行。第二次打开时，`wsLocationReceived` 还是 `true`，轮询循环刚进来就 `if (wsLocationReceived) return@launch` 直接退出了，地图根本没更新。  
**修复**：在 `loadElderLocation()` 开头加 `wsLocationReceived = false`。

---

## Bug 3："强制弹窗"开关打开后不生效

**文件**：`family-guardian-app/app/src/main/java/com/familyguardian/app/ui/SettingsFragment.kt`  
**布局**：`res/layout/fragment_settings.xml`（SwitchCompat `switch_force_popup`）  
**问题**：开关能打开，但跌倒警报来时并没有强制弹窗（全屏通知）。需要查：
1. `SettingsFragment.kt` 里 `switch_force_popup` 的 checkedChange 监听器有没有写
2. 开关状态有没有存进 `SharedPreferences`
3. `HomeFragment.kt` 里读这个开关状态的逻辑对不对（`force_popup` key）
4. MIUI系统层面有没有拦截全屏通知（需要"显示在其他应用上层"权限）

---

**请CC按以上顺序修复，每修完一个验证一个。**
