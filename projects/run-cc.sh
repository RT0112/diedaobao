#!/bin/bash
cd /Users/zhou/.qclaw/workspace-x5kuz49xple53hhg/projects
export ANTHROPIC_BASE_URL=http://127.0.0.1:20000
export ANTHROPIC_API_KEY=sk-ant-api03-placeholder
exec claude --add-dir . --dangerously-skip-permissions
