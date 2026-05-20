# 微信文件传输助手 APK 自动上传 — 成功记录

## 时间
2026-05-18 11:34 (Asia/Shanghai)

## 目标
编译成功后自动将两个 APK 发送到微信文件传输助手

## 最终成功方案
Playwright 连接 OpenClaw browser (profile=openclaw) 的 CDP 端口，用 `page.click('[title*="文件"]')` + `waitForEvent('filechooser')` 触发原生文件选择器上传

## 关键参数
- CDP URL: `http://127.0.0.1:28800`（必须用 127.0.0.1，不能用 localhost）
- 文件按钮选择器: `[title*="文件"]`
- 上传方法: `Promise.all([waitForEvent('filechooser'), click()])` → `fc.setFiles([path])`
- 发送按钮: `text=发送`

## 已发送文件
1. /Users/zhou/Desktop/跌倒宝-老人端-v134.apk (38.43MB→38.43MB)
2. /Users/zhou/Desktop/亲情守护-子女端-v17.apk (17.5MB→16.65MB)

## 注意事项
- OpenClaw openclaw profile 是独立 Chrome profile，首次需扫码登录
- 登录后 cookie 保存在 profile 中，后续自动保持
- **绝对不要 reload 微信页面**，否则登录态丢失
- 大文件上传时 CDP 可能暂时阻塞，等几秒重试即可
- APK 版本号需要根据实际 versionCode 更新

## 脚本位置
- 一键脚本: `scripts/wechat_send_apk.js`
- 操作文档: `OPERATIONS.md` §微信文件传输助手自动发送APK
