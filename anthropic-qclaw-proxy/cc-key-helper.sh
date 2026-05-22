#!/bin/bash
# CC apiKeyHelper - outputs the API key from cc-agent.sh
# This avoids setting ANTHROPIC_API_KEY env var, preventing the confirmation dialog
KEY=$(grep "export ANTHROPIC_API_KEY=" ~/.qclaw/workspace-x5kuz49xple53hhg/anthropic-qclaw-proxy/cc-agent.sh | head -1 | sed 's/export ANTHROPIC_API_KEY=//' | tr -d '"' | tr -d "'")
echo -n "$KEY"
