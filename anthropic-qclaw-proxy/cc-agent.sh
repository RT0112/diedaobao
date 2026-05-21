#!/bin/bash
# Wrapper to run Claude Code as a non-interactive agent
# Usage: cc-agent.sh "your prompt here"

export PATH="$HOME/.local-node/bin:$PATH"
export ANTHROPIC_BASE_URL=http://127.0.0.1:20000
export ANTHROPIC_API_KEY=qclaw-proxy

PROMPT="$1"
WORKDIR="${2:-$(pwd)}"

cd "$WORKDIR" || exit 1

claude --print --dangerously-skip-permissions "$PROMPT"
