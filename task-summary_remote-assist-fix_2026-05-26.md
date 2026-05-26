# 远程协助Bug修复 - 2026-05-26

## 问题描述

用户反馈两个问题：
1. **倒计时跳太快**：显示 `60 → 57 → 53`，每秒跳3-4秒，越来越快
2. **协助中Activity突然退出**：老人端接受协助后，进入"正在协助"画面，过一会突然退出，导致子女端画面卡住

---

## 根因分析

### Bug 1：倒计时叠加加速

**文件**：`RemoteAssistActivity.kt` → `startAutoRejectCountdown()`

**根因**：`startAutoRejectCountdown()` 没有在调用前取消上一个 `rejectRunnable`。
- 如果 `showRequestDialog()` 被调用多次（如重复的WS推送触发 `onNewIntent`），多个 `Runnable` 同时跑
- 每个 `Runnable` 每秒减1，N个同时跑就每秒减N
- 用户看到：`60 → 57 → 53 → ...` （3-4个Runnable叠加）

**修复**：在 `startAutoRejectCountdown()` 开头加 `rejectRunnable?.let { handler.removeCallbacks(it) }`

---

### Bug 2：协助中Activity销毁不通知子女端

**文件**：`RemoteAssistActivity.kt` → `onDestroy()`

**根因**：`onDestroy()` 里当 `isAssisting=true` 时：
1. 只解绑Service（`unbindService`），不停止Service
2. **不调用 `cleanupAssist()`** → 不通知子女端会话结束
3. 子女端不知道会话已结束，画面卡死

另外：`onBackPressed()` 没有重写，用户按返回键直接退出协助，没有确认对话框。

**修复**：
1. `onDestroy()` 里 `isAssisting=true` 时也调用 `cleanupAssist()`，正确通知子女端
2. 重写 `onBackPressed()`，协助中按返回键弹确认对话框

---

## 修改内容

### 修改1：`startAutoRejectCountdown()` — 防止Runnable叠加

```kotlin
// ⚠️ 先取消上一个，防止多个Runnable叠加导致倒计时加速
rejectRunnable?.let { handler.removeCallbacks(it) }
rejectRunnable = null
```

### 修改2：新增 `onBackPressed()` — 防止误触返回键退出协助

```kotlin
override fun onBackPressed() {
    if (isAssisting) {
        AlertDialog.Builder(this)
            .setTitle("结束远程协助？")
            .setMessage("确定要结束远程协助吗？子女端会收到通知。")
            .setPositiveButton("结束") { _, _ ->
                cleanupAssist()
                finish()
            }
            .setNegativeButton("取消", null)
            .show()
    } else {
        super.onBackPressed()
    }
}
```

### 修改3：`onDestroy()` — 协助中销毁也必须清理并通知子女端

```kotlin
// ⚠️ 修复：协助中销毁Activity也要正确清理，否则子女端收不到结束通知
if (isAssisting) {
    Log.w(TAG, "onDestroy: 协助中Activity被销毁，执行cleanupAssist通知子女端")
    cleanupAssist()
} else {
    cleanupAssist()
}
```

---

## 验证计划

- [ ] 编译通过
- [ ] 安装到K70真机测试
- [ ] 测试1：子女端发起远程协助 → 老人端收到请求 → 倒计时每秒减1（不加速）
- [ ] 测试2：协助中按返回键 → 弹确认对话框 → 确认后结束并通知子女端
- [ ] 测试3：协助中Activity被系统销毁（模拟低内存）→ 子女端收到结束通知（不卡死）
- [ ] 测试4：正常结束协助 → 子女端正常收到通知

---

## 文件清单

| 文件 | 修改类型 |
|------|-----------|
| `RemoteAssistActivity.kt` | 修改（`startAutoRejectCountdown`、`onBackPressed`、`onDestroy`） |

---

*创建时间：2026-05-26 10:15*
