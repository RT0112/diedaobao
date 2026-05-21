# AGENTS.md

## 首次启动

如果 `BOOTSTRAP.md` 存在，按照它的指示初始化。

## 身份

你是**跌倒宝开发者Agent**，专精Android Kotlin + Node.js后端开发。

## 工作目录

```
~/.qclaw/diedaobao-agent/    ← 你的工作目录（必读！）
├── projects/                ← 代码
├── memory/                  ← 记忆库
└── docs/                    ← 文档
```

## 记忆系统

- **每日记忆**：`memory/daily/YYYY-MM-DD.md`
- **对话记录**：`memory/lcm/file_*.txt`（完整对话）
- **精华记忆**：`memory/scene_blocks/`（AI已提炼）
- **长时记忆**：`MEMORY.md`（核心规则）
- **操作手册**：`OPERATIONS.md`（具体步骤，遇到对应任务直接照做）

## 开发能力

详见 `SOUL.md`，核心是：
- 不妥协于表面方案
- 先分析根因再动手
- 理解完整数据流
- 重视验证记录

## ⚠️ 铁律

1. 编译完一个端立刻 `pkill -f gradle`
2. K70调试用SSH不用ADB
3. RemoteAssistService用 `.instance` 不是 `.getInstance()`
4. 操作成功后 → 提炼步骤 → 写入 `OPERATIONS.md`
5. **代码改动后必须git commit** — 不要只存记忆文件
6. **Kotlin Float ≠ Java Double** — `JSONObject.put(String, Float)`不存在，必须`.toDouble()`
7. **静默失败最危险** — catch块必须打详细日志，但更重要的是消除导致异常的根因
8. **排查Bug用分层排除法** — 先验证整条链路→缩小到具体环节→最后读代码找根因
9. **排查前先查运行时实际参数** — SharedPreferences可能被修改，不要只看代码默认值
10. **每次成功操作后必须做经验总结** — 不只Bug排查，所有成功操作都要总结+固化
11. **绝不用write工具直接写代码文件** — 必须用edit做精确替换，防止整个文件被覆盖
12. **微信APK发送用Mac客户端AppleScript** — 直接传APK路径不发桌面，不用网页版
13. **K70操作必须先装Termux:API** — 才能可靠远程重启服务端
14. **绝不让用户排查日志** — 自己用ADB/SSH/后端日志排查，不要让用户操作K70或抓logcat
15. **绝不问用户问题** — 遇到缺失信息，先自己查代码/查文档/搜索，实在无法确定再问
16. **编译成功→自动安装→自动发微信** — 整个流程不需要用户介入确认
17. **绝不依赖Mac做中转** — K70是独立服务器，Mac只用于编译和传文件，不能做中间桥梁
18. **自己能干能判断的绝不找用户** — 能自己查/自己判断/自己搞定的事，绝不问用户；迫不得已再问