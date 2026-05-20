#!/bin/bash
# ============================================================
# 微信Mac客户端发送文件
# 用法: ./scripts/wechat_mac_send.sh <apk-path>
#   例: ./scripts/wechat_mac_send.sh ~/Desktop/跌倒宝-老人端-v136.apk
#   例: ./scripts/wechat_mac_send.sh projects/fall-detection-app/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
#   例: ./scripts/wechat_mac_send.sh projects/family-guardian-app/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
#
# 前提条件:
#   1. 微信Mac客户端已运行
#   2. 文件传输助手聊天窗口已打开（或最近一次聊天窗口可见）
#   3. 系统偏好设置 → 隐私与安全性 → 辅助功能 → 已允许"终端"或微信控制电脑
# ============================================================

set -e

FILE="$1"
if [ -z "$FILE" ]; then
    echo "用法: $0 <文件路径>"
    echo "  例: $0 ~/Desktop/跌倒宝-老人端-v136.apk"
    exit 1
fi

if [ ! -f "$FILE" ]; then
    echo "❌ 文件不存在: $FILE"
    exit 1
fi

# 解析绝对路径
FILE_ABS=$(cd "$(dirname "$FILE")" && pwd)/$(basename "$FILE")
SIZE=$(du -h "$FILE_ABS" 2>/dev/null | cut -f1)
FILENAME=$(basename "$FILE_ABS")

echo "📤 发送文件: $FILE_ABS ($SIZE)"

# 激活微信
osascript -e 'tell application "WeChat" to activate' 2>/dev/null
sleep 1

# 复制文件到剪贴板（osascript）
osascript <<EOF 2>/dev/null
tell application "Finder"
    set the clipboard to (POSIX file "$FILE_ABS")
end tell
EOF

sleep 1

# Command+V 粘贴
osascript -e 'tell application "System Events" to keystroke "v" using command down' 2>/dev/null
sleep 1

# 回车发送
osascript -e 'tell application "System Events" to key code 36' 2>/dev/null

echo "✅ 发送命令已执行，请在微信中确认"
