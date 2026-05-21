# Claude Code + qclaw 免费额度集成方案

## 架构总览

```
Claude Code CLI
  │ (Anthropic API 格式: POST /v1/messages)
  ▼
翻译代理 (Python, localhost:20000)
  │ (OpenAI 兼容格式: POST /chat/completions)
  ▼
qclaw 网关 (localhost:19000)
  │ (走 qclaw 免费额度)
  ▼
Claude 模型 (modelroute)
```

## 已验证的事实

| 项目 | 状态 | 详情 |
|------|------|------|
| qclaw 网关 | ✅ 可用 | `http://127.0.0.1:19000/proxy/llm/chat/completions` |
| 认证 | ✅ 可用 | `Bearer __QCLAW_AUTH_GATEWAY_MANAGED__` |
| 非流式请求 | ✅ 验证通过 | 标准 OpenAI 格式响应 |
| 流式请求 (SSE) | ✅ 验证通过 | `stream: true` → SSE `data: {...}\n\n` + `data: [DONE]` |
| reasoning_content | ✅ 支持 | 流式 chunk 中有 `reasoning_content` 字段 |
| tool_calls | ✅ 支持 | chunk 中有 `tool_calls` 字段 |
| Node.js | ❌ 未安装 | 需要安装才能跑 CC CLI |
| npm | ❌ 未安装 | 需要安装才能装 CC CLI |
| Homebrew | ❌ 未安装 | 不能 `brew install node` |

## 实施步骤

### 第一步：安装 Node.js

**推荐方案：直接下载 Node.js 二进制包（无需 brew）**

```bash
# 下载 Node.js (macOS x86_64)
curl -L --connect-timeout 30 --max-time 600 \
  https://nodejs.org/dist/v22.16.0/node-v22.16.0-darwin-x64.tar.gz \
  -o /tmp/node.tar.gz

# 解压到 ~/.local-node
mkdir -p ~/node-install
tar -xzf /tmp/node.tar.gz -C ~/node-install --strip-components=1
mv ~/node-install ~/.local-node

# 加入 PATH（每次 shell 启动时自动生效）
echo 'export PATH="$HOME/.local-node/bin:$PATH"' >> ~/.zshrc

# 验证
source ~/.zshrc
node --version  # v22.16.0
npm --version   # 10.9.2
```

### 第二步：安装 Claude Code CLI

```bash
npm install -g @anthropic-ai/claude-code
claude --version  # 2.1.146
```

### 第三步：运行翻译代理

代理已实现，支持完整流式 + tool_calls。

```bash
# 安装 aiohttp 依赖
pip3 install aiohttp

# 启动代理
python3 ~/.qclaw/workspace-x5kuz49xple53hhg/anthropic-qclaw-proxy/proxy.py

# 或后台运行
nohup python3 ~/.qclaw/workspace-x5kuz49xple53hhg/anthropic-qclaw-proxy/proxy.py > /tmp/cc-proxy.log 2>&1 &
```

### 第四步：使用 CC

```bash
export ANTHROPIC_BASE_URL=http://127.0.0.1:20000
export ANTHROPIC_API_KEY=qclaw-proxy

# 非交互模式（适合自动化）
claude --print --dangerously-skip-permissions "你的编程任务"

# 交互模式（需要手动确认文件写入等）
claude
```

### 第五步：集成到 Hermes

**重要发现：CC CLI 不支持 ACP 协议**，所以无法通过 `delegate_task(acp_command='claude')` 集成。

**替代方案：直接通过 terminal() 调用 CC**

```python
import subprocess, os

env = os.environ.copy()
env["PATH"] = os.path.expanduser("~/.local-node/bin") + ":" + env.get("PATH", "")
env["ANTHROPIC_BASE_URL"] = "http://127.0.0.1:20000"
env["ANTHROPIC_API_KEY"] = "qclaw-proxy"

result = subprocess.run(
    ["claude", "--print", "--dangerously-skip-permissions", "你的编程任务"],
    capture_output=True, text=True, timeout=120, env=env
)
print(result.stdout)
```

或使用封装的 shell 脚本：
```bash
~/.qclaw/workspace-x5kuz49xple53hhg/anthropic-qclaw-proxy/cc-agent.sh "写一个Python函数" "/path/to/workdir"
```

## 实现细节

### 翻译代理 (proxy.py) 架构

```
Anthropic SSE 事件序列                         OpenAI SSE chunks
─────────────────────────────────────────      ─────────────────
event: message_start         ← 新建           第一个 chunk (有 delta.role)
event: content_block_start   ← 新建           (自动)
event: content_block_delta   ← text_delta      delta.content
event: content_block_delta   ← input_json_delta delta.tool_calls[0].function.arguments
event: content_block_stop    ← 新建           delta.tool_calls 有 id 时触发
event: message_delta         ← stop_reason     delta.finish_reason 非空
event: message_stop          ← 新建            最后
event: done                  ← 自定义          [DONE]
```

### 关键翻译映射

**请求翻译 (Anthropic → OpenAI):**
- `POST /v1/messages` → `POST /proxy/llm/chat/completions`
- `x-api-key` header → `Authorization: Bearer __QCLAW_AUTH_GATEWAY_MANAGED__`
- `model: "claude-sonnet-4-..."` → `model: "modelroute"` (强制覆盖)
- `system` 顶层字段 → `messages[0]` with `role: "system"`
- `content: [{type:"text", text}]` → `content: "..."`
- `content: [{type:"tool_use"}]` → `tool_calls: [{id, function:{name, arguments}}]`
- `role: "tool"` → 保留为 `role: "tool"` (OpenAI 原生支持)
- `tools[]` → `tools[]` (Anthropic → OpenAI function schema)

**响应翻译 (OpenAI → Anthropic):**
- `content: "text"` → `content: [{type:"text", text}]`
- `tool_calls[]` → `content: [{type:"tool_use", id, name, input}]`
- `finish_reason: "stop"` → `stop_reason: "end_turn"`
- `finish_reason: "tool_calls"` → `stop_reason: "tool_use"`
- `usage.prompt_tokens` → `usage.input_tokens`
- `usage.completion_tokens` → `usage.output_tokens`

## 验证结果 ✅

| 测试 | 结果 | 详情 |
|------|------|------|
| Node.js 安装 | ✅ | v22.16.0 |
| CC CLI 安装 | ✅ | 2.1.146 |
| 代理健康检查 | ✅ | localhost:20000/health |
| 非流式请求 | ✅ | 返回 Anthropic 格式消息 |
| 流式请求 | ✅ | SSE 正确转换 |
| tool_calls 翻译 | ✅ | 工具名/参数正确映射 |
| CC 纯文本对话 | ✅ | `claude --print "Say hi"` → "Hi!" |
| CC 读文件 | ✅ | Read tool 调用成功 |
| CC 写文件+运行 | ✅ | Write tool + Bash tool 完整执行 |
| Hermes delegate_task | ❌ | CC 不支持 ACP 协议 |

## 文件清单

```
~/.qclaw/workspace-x5kuz49xple53hhg/anthropic-qclaw-proxy/
├── proxy.py          # 翻译代理（主程序）
├── cc-agent.sh       # shell 封装脚本
└── README.md         # 使用说明（见下方）
```

## 使用说明

### 启动代理（每次使用前）
```bash
python3 ~/.qclaw/workspace-x5kuz49xple53hhg/anthropic-qclaw-proxy/proxy.py
```

### 在 Hermes 中调用 CC
由于 CC 不支持 ACP，无法用 `delegate_task` 集成。**推荐用法**：
通过 `terminal()` 调用：
```python
result = terminal(
    '~/.qclaw/workspace-x5kuz49xple53hhg/anthropic-qclaw-proxy/cc-agent.sh "任务描述" "/工作目录"'
)
```

或在命令行直接用：
```bash
export PATH="$HOME/.local-node/bin:$PATH"
export ANTHROPIC_BASE_URL=http://127.0.0.1:20000
export ANTHROPIC_API_KEY=qclaw-proxy
claude --print --dangerously-skip-permissions "写一个Python函数..."
