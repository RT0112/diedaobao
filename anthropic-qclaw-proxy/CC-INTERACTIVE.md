#!/bin/bash
# CC交互模式 - 在你的终端里直接运行
# ⚠️ 注意：CC交互模式不走ANTHROPIC_BASE_URL代理，直连api.anthropic.com
# 需要你的claude已登录或有有效auth

cd /Users/zhou/.qclaw/workspace-x5kuz49xple53hhg/projects
claude --add-dir . --dangerously-skip-permissions
