# CC用法优化 + 双端微信发送规则

## Objective
诊断CC（Claude Code）在跌倒宝项目中使用效率低的原因，并参考成熟团队用法进行改进。同时补充双端APK都需发微信的规则。

## Key Findings — CC用法问题诊断

### 问题根因（5个）
1. **没给CC项目地图（最大问题）** — CC有原生`CLAUDE.md`机制，启动时自动读取注入系统提示词，但我们从来没创建过，CC每次裸奔从零探索
2. **只用`--print`单发模式** — 这是单shot模式，不能迭代、探索、纠偏，大任务必须在一次回复里完成所有工作，容易超时
3. **cwd设在子目录** — CC只能看老人端/子女端/后端之一，无法做跨端全链路分析
4. **按文件拆分任务是反模式** — 丧失全局视角，遗漏跨文件依赖。研究明确说这是错误做法
5. **超时不是根本问题** — proxy.py 300s对单次请求够用，问题是单发模式要求300s内完成所有工作

### 正确做法（成熟团队CC用法）
1. **CLAUDE.md** → 已创建 `projects/CLAUDE.md`，包含项目架构、编译铁律、代码陷阱、API速查
2. **交互模式** → 大任务首选，CC可以多轮对话、读文件、迭代修改，不存在超时问题
3. **全项目cwd** → cwd设`projects/`根目录，CC能看全项目三端
4. **给完整问题** → 不按文件拆分，让CC自己做全链路分析（CC发现根因能力比我强）
5. **`--continue`续接** → 编译错误可续接上次会话让CC修

## Changes Made
1. **创建 `projects/CLAUDE.md`** — CC项目大脑，5.3KB，自动加载
2. **重写 `cc-agent.sh`** — 支持3种模式：`--interactive`(交互)、`--print`(单发)、`--continue`(续接)
3. **更新 qclaw-cc-integration skill** — 替换旧的拆分策略为正确用法
4. **更新 MEMORY.md** — CC集成规则从6条扩展到10条
5. **更新 diedaobao-project skill** — 编译后必做第4条：双端都改了→双端APK都发微信

## WeChat Rule
- 双端都修改时，两个APK都要通过 `wechat_mac_send.sh` 发到微信文件传输助手
- 不能只发一个端

## CC Usage Quick Reference
```bash
# 大任务（多文件bug修复、新功能）— 交互模式
./cc-agent.sh --interactive

# 小任务（单文件、编译错误）— 单发模式
./cc-agent.sh "修复XXX编译错误：[贴错误]"

# 续接上次会话
./cc-agent.sh --continue "继续修下一个文件"
```
