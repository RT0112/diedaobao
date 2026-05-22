#!/bin/bash
# Start CC interactive mode in screen session
export PATH="$HOME/.local-node/bin:$PATH"
export ANTHROPIC_BASE_URL=http://127.0.0.1:20000
export ANTHROPIC_API_KEY=$(grep 'ANTHROPIC_API_KEY=' ~/.qclaw/workspace-x5kuz49xple53hhg/anthropic-qclaw-proxy/cc-agent.sh | head -1 | sed 's/.*ANTHROPIC_API_KEY=//' | cut -d' ' -f1)
export SETTINGS="$HOME/.qclaw/workspace-x5kuz49xple53hhg/anthropic-qclaw-proxy/cc-settings.json"
cd "$HOME/.qclaw/workspace-x5kuz49xple53hhg/projects"
claude --dangerously-skip-permissions --bare --settings "$SETTINGS"
