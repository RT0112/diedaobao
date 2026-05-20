# 2026-05-18 22:50 — 三个Bug修复部署完成

## 目标
修复三个用户报告的Bug并部署到K70设备

## 关键步骤
1. Bug 1 (围栏缓存不清理): 修改FallDetectionService.kt中refreshFenceCache()，无论fences是否为空都更新cachedFences
2. Bug 2 (跌倒通知不跳转地图): 修改HomeFragment.kt通知contentIntent打开MapActivity(view_fall模式)，MapActivity.kt新增view_fall分支
3. Bug 3 (权限批量申请): 修改PermissionActivity.kt，将ACCESS_BACKGROUND_LOCATION从批量请求中剔除
4. 双端编译成功(老人端2m35s，子女端2m20s)
5. 推送到K70，处理MIUI ICP弹窗(415,2976)+安全审核弹窗(720,2368→720,2778)
6. 安装验证：老人端versionCode=135，子女端versionCode=18
7. 后端健康检查：elders:1, guardians:1

## 踩过的坑
- MIUI同版本更新弹窗(0.8.1→0.8.1)有checkbox+继续更新两步操作
- 每个按钮只点1次，否则误触"取消更新"

## 经验提炼
- 围栏缓存必须允许清空：`if (fences.isNotEmpty())` → 无条件更新
- 通知contentIntent必须指向具体功能页而非Launcher Activity
- Android 11+后台定位不能批量请求，必须单独处理

## 固化
- 围栏缓存清空规则 → FallDetectionService代码注释
- 通知跳转MapActivity模式 → HomeFragment代码
- 权限分批请求 → PermissionActivity代码

## 当前状态
- 双端已安装，后端正常(elders:1, guardians:1)
- 三个Bug修复待用户测试验证
