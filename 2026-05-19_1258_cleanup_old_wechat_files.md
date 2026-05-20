# 清理旧微信网页版文件和文档

## 目标
用户确认微信APK发送成功后，清理所有旧的网页版（Playwright+CDP）相关文件和文档，全面切换到Mac客户端AppleScript方案。

## 关键操作
1. 清理 /tmp 下 50+ 个临时文件（pw_*.js、sc*.js、send_*.js、wechat_*.js/png）
2. 清理 /tmp/pw/ Playwright临时安装目录
3. 清理 wechat_session/ 网页版登录态（cookies.json、localStorage.json）
4. 更新 MEMORY.md：微信方案改为Mac客户端，版本号v136/v18
5. 更新 AGENTS.md：新增铁律#11（不用write写代码文件）和#12（微信用Mac客户端）
6. 更新 SOUL.md：案例3方案更新
7. 更新 OPERATIONS.md：微信传输章节替换为Mac客户端方案
8. 更新 docs/EXPERIENCE_CASES.md：案例3完整重写

## 结论
所有旧网页版相关文件和文档已清理完毕。scripts/ 只保留 wechat_mac_send.sh 稳定脚本。文档体系已全面切换到Mac客户端方案。
