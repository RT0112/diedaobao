# Claude Code + qclaw 免费额度集成 — 完成报告

## 目标
让 Claude Code CLI 能通过 qclaw 免费额度（而非 Anthropic 付费订阅）进行编程辅助。

## 核心方案
**翻译代理架构** — CC 用 Anthropic 格式，qclaw 用 OpenAI 格式，写一个 Python 代理做双向翻译：

```
Claude Code CLI → 翻译代理(:20000) → qclaw网关(:19000) → Claude模型
```

## 完成的工作

### 1. 安装 Node.js + Claude Code CLI ✅
- 下载 Node.js v22.16.0 二进制包（brew 不可用，直接下 tar.gz）
- 解压到 `~/.local-node`
- `npm install -g @anthropic-ai/claude-code` → CC 2.1.146

### 2. 翻译代理 `proxy.py` ✅
- 监听 `localhost:20000`，接受 CC 的 Anthropic 格式请求
- 翻译为 OpenAI 格式转发给 qclaw（`model: modelroute`）
- **支持流式 SSE**：状态机将 OpenAI chunks 翻译为 Anthropic 事件序列
- **支持 tool_calls**：完整翻译 tool_use / tool_result / function.arguments
- 端口 `--port`，qclaw URL `--qclaw-url`，日志级别 `--debug`

### 3. 验证结果 ✅
| 测试 | 结果 |
|------|------|
| CC 纯文本对话 | ✅ "Hi!" |
| CC Read 工具 | ✅ 正确调用并返回 |
| CC Write+Bash 工具 | ✅ 完整执行（写文件+运行+返回结果） |
| 流式 SSE | ✅ Anthropic 事件序列正确 |
| tool_calls | ✅ 工具名/参数正确映射 |

### 4. Hermes 集成限制 ⚠️
- **CC CLI 不支持 ACP 协议**，无法用 `delegate_task(acp_command='claude')` 直接集成
- 替代方案：通过 `terminal()` 或封装脚本调用 CC

## 文件清单
```
~/.qclaw/workspace-x5kuz49xple53hhg/anthropic-qclaw-proxy/
├── proxy.py     # 翻译代理（主程序，≈640行）
├── cc-agent.sh  # shell 封装脚本
```

## 使用方法

### 启动代理
```bash
pip3 install aiohttp
python3 ~/.qclaw/workspace-x5kuz49xple53hhg/anthropic-qclaw-proxy/proxy.py
```

### 调用 CC
```bash
export PATH="$HOME/.local-node/bin:$PATH"
export ANTHROPIC_BASE_URL=http://127.0.0.1:20000
export ANTHROPIC_API_KEY=qclaw-proxy
claude --print --dangerously-skip-permissions "写一个Python函数..."
```

## 方案状态
✅ **已实现并验证可用** — CC 写代码能力完全可用，通过 qclaw 免费额度驱动。
