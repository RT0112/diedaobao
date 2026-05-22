#!/bin/bash
# CC Watch — 实时录屏CC输出
# Usage: cc-watch.sh "你的prompt" [workdir]
#
# 另开终端看进度:
#   tail -f /tmp/cc-watch-$(date +%s).log
#
# 录屏整个会话（更完整）:
#   script -q /tmp/cc-session-$(date +%s).txt  ~/.qclaw/workspace-x5kuz49xple53hhg/projects/run-cc.sh

set -e
PROMPT="$1"
WORKDIR="${2:-$HOME/.qclaw/workspace-x5kuz49xple53hhg/projects}"
LOGFILE="/tmp/cc-watch-$(date +%s).log"

echo "🚀 CC Watch mode"
echo "   Log: $LOGFILE"
echo "   Watch: tail -f $LOGFILE"
echo ""

cd "$WORKDIR" || exit 1
export ANTHROPIC_BASE_URL=http://127.0.0.1:20000
export ANTHROPIC_API_KEY=sk-ant-api03-placeholder

# Prompt通过stdin传递（--print模式的正确用法）
echo "$PROMPT" | claude --print --dangerously-skip-permissions --add-dir . 2>&1 | tee "$LOGFILE"
EXIT=$?

if [ $EXIT -eq 0 ]; then
    echo ""
    echo "✅ CC done. Log: $LOGFILE"
else
    echo ""
    echo "❌ CC failed (exit $EXIT). Log: $LOGFILE"
fi