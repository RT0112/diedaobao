# 经验案例库

> 由 SOUL.md "成功经验总结机制" 指向。每次成功操作后必须总结并追加到此文件。
> 格式：案例编号 + 标题 + 日期 + 完整6步总结

---

## 案例#1 — reportFall()静默崩溃：子女端收不到跌倒通知

**日期**：2026-05-18

**1. 目标**：老人端按"需要帮助"后子女端无通知

**2. 关键步骤**：
- API模拟POST /fall-report → 成功(wsPushed:2) → 通知链路本身OK
- SSH查server.log → 无/fall-report请求 → 老人端根本没发
- logcat过滤 → 无reportFall日志 → 代码没执行到
- 系统日志确认ConfirmActivity启动过 → ActivityOK但reportFall()静默失败
- 读代码 → `JSONObject.put(String, Float)` Android不存在 → NoSuchMethodError被catch吞掉

**3. 踩过的坑**：
- 一开始怀疑URL配置问题（隧道vs localhost），浪费了时间
- 加了大量诊断日志但其实不需要——根因是类型不匹配，不是缺日志

**4. 经验提炼**：
- 分层排除法：先验证链路→缩小范围→最后读代码
- 后端日志是最可靠证据：后端没收到=客户端问题
- 静默失败最危险：catch只打Log不抛异常=毒药
- Kotlin Float≠Java Double：`put("key", floatValue)` 必须显式 `.toDouble()`
- API模拟≠真实场景：curl成功只证明链路通，不证明客户端代码对

**5. 固化**：
- AGENTS.md 铁律#6 Float≠Double陷阱
- AGENTS.md 铁律#7 静默失败危险
- SOUL.md Bug排查方法论

---

## 案例#2 — 加权<0.5却触发报警：运行时参数≠代码默认值

**日期**：2026-05-18

**1. 目标**：用户说"加权评分小于0.5还是触发了"

**2. 关键步骤**：
- logcat grep "决策链" → 找到实际触发：加权=0.44, 阈值=0.42
- 发现阈值不是0.50 → 说明不是默认等级4
- 查SharedPreferences → `sensitivity_level=7`
- 对照DetectionConfig灵敏度表 → 等级7的weightedScoreThresh=0.42

**3. 踩过的坑**：
- 之前解释算法时只说了默认等级4的参数，没确认K70实际运行等级
- 用户直觉"有点问题"是对的——等级7太宽松容易误报

**4. 经验提炼**：
- **排查阈值/算法问题时，第一步先查运行时实际参数**
- 查SharedPreferences：`adb shell run-as <pkg> cat shared_prefs/xxx.xml`
- 代码默认值≠运行时值（可能被测试页面/用户修改过）
- 解释算法参数时必须确认实际运行等级，不能假设默认

**5. 固化**：
- AGENTS.md 铁律#9 排查前先查运行时实际参数
- SOUL.md 案例#2
- K70灵敏度已调回等级4

---

## 案例#3 — 微信APK自动发送（Mac客户端AppleScript方案）

**日期**：2026-05-19（方案升级）

**1. 目标**：编译成功后自动发送APK到微信文件传输助手

**2. 关键步骤**：
- 脚本：`scripts/wechat_mac_send.sh <APK路径>`
- AppleScript激活微信Mac客户端
- Finder将APK文件放入剪贴板
- System Events模拟Command+V粘贴
- System Events模拟回车发送

**3. 踩过的坑**：
- 网页版(filehelper.weixin.qq.com)太不稳定：登录态丢失、filechooser超时、发送按钮禁用
- Playwright+CDP方案：setFiles大文件必超时、微信页面结构变化选择器失效
- 网页版reload=清登录态、close=清登录态，极其脆弱
- 最终方案：Mac客户端AppleScript，简单可靠

**4. 经验提炼**：
- 微信网页版不适合自动化——登录态脆弱、大文件上传不可靠、页面结构常变
- Mac客户端AppleScript方案更稳定：只需激活+粘贴+回车
- 超时≠失败（旧教训仍有效）：Playwright超时但文件可能已上传

**5. 固化**：
- OPERATIONS.md §微信传输
- scripts/wechat_mac_send.sh（稳定脚本）
- 旧网页版脚本已全部清理
