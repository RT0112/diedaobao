#!/bin/bash
# CC PTY runner — 实时捕获CC输出到日志文件
# 用法:
#   ./cc-run.sh "your prompt"          # 通过proxy跑CC，实时输出到 /tmp/cc_session.log
#   ./cc-run.sh                         # CC交互模式（需要本地已登录）
#   tail -f /tmp/cc_session.log         # 实时查看CC输出（另一个终端窗口）

python3 /tmp/run_cc_interactive.py "$@"
