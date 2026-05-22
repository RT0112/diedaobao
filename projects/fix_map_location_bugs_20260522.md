# 修复子女端"查看位置"两个Bug — 已完成确认

## 时间
2026-05-22

## 目标
修复子女端"查看位置"功能的两个bug

## 结论：两个bug已在之前修复并提交

### Bug1: 查看位置老是超时
- **根因**: MapActivity完全不监听WS location_update事件，只靠HTTP轮询（每2秒，最多45秒）
- **修复**: MapActivity新增WS location_update监听（参考HomeFragment写法），轮询缩短到30秒作兜底
- **提交**: family-guardian-app `cacab1e` — MapActivity.kt + WSClient.kt + build.gradle.kts

### Bug2: 位置和实际有几十米偏差
- **根因**: 老人端uploadLocationNow()只用NETWORK_PROVIDER（精度30-500m），不用GPS（精度5-30m）
- **修复**: 同时请求GPS_PROVIDER和NETWORK_PROVIDER，谁先返回用谁，超时从5秒提到10秒
- **提交**: fall-detection-app `c1288a7` — CloudBaseClient.kt

## 文件清单
- `family-guardian-app/.../ui/MapActivity.kt` — 新增startWSLocationListener()，onDestroy清理
- `family-guardian-app/.../cloud/WSClient.kt` — 可能修改了WSClient（确保LocationUpdate事件正确解析）
- `fall-detection-app/.../cloud/CloudBaseClient.kt` — uploadLocationNow() GPS+NETWORK双请求
