#!/bin/bash
# CC交互模式 - 直接在终端里运行这段命令
# ==========================================

# 方法：在终端.app里新建窗口，复制运行下面这段

# cd /Users/zhou/.qclaw/workspace-x5kuz49xple53hhg/projects && \
#   ANTHROPIC_BASE_URL=http://127.0.0.1:20000 \
#   ANTHROPIC_API_KEY=sk-ant-api03-placeholder \
#   claude --add-dir . --dangerously-skip-permissions

# ==========================================
# 或者用 osascript 自动打开：
osascript -e '
tell application "Terminal"
  activate
  do script "cd /Users/zhou/.qclaw/workspace-x5kuz49xple53hhg/projects && ANTHROPIC_BASE_URL=http://127.0.0.1:20000 ANTHROPIC_API_KEY=sk-ant-api03-placeholder claude --add-dir . --dangerously-skip-permissions"
end tell
'
