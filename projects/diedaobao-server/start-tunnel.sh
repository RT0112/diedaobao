#!/data/data/com.termux/files/usr/bin/bash
# diedaobao-cloudflared.sh — K70 Cloudflare Tunnel 启动脚本
# 用法: bash ~/diedaobao-server/start-tunnel.sh
# 
# 原理: proot 绑定 resolv.conf + SSL证书，解决 Go 程序在 Termux 下的 DNS/TLS 问题
# 穿透: cloudflared quick tunnel → 自动分配 trycloudflare.com 公网 URL

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
CF_BIN="$HOME/cloudflared"
CF_LOG="$SCRIPT_DIR/tunnel.log"
RESOLV="$PREFIX/etc/resolv.conf"
TLS_DIR="$PREFIX/etc/tls"

# 检查 cloudflared 是否已安装
if [ ! -x "$CF_BIN" ]; then
    echo "❌ cloudflared 未安装"
    echo "安装: curl -sL -o ~/cloudflared 'https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-arm64' && chmod +x ~/cloudflared"
    exit 1
fi

# 杀掉旧的 tunnel 进程
pkill -f "cloudflared tunnel" 2>/dev/null || true
sleep 1

echo "🚀 启动 Cloudflare Tunnel..."

# 启动 cloudflared，用 proot 绑定 DNS 和 SSL
SSL_CERT_FILE="$TLS_DIR/cert.pem" proot \
    -b "$RESOLV:/etc/resolv.conf" \
    -b "$TLS_DIR:/etc/ssl" \
    "$CF_BIN" tunnel \
    --url http://localhost:3000 \
    --protocol http2 \
    > "$CF_LOG" 2>&1 &

CF_PID=$!
echo "PID: $CF_PID"

# 等待 tunnel URL 出现
echo "⏳ 等待公网 URL 分配..."
for i in $(seq 1 30); do
    if grep -q "trycloudflare.com" "$CF_LOG" 2>/dev/null; then
        URL=$(grep -oP 'https://[a-z0-9-]+\.trycloudflare\.com' "$CF_LOG" | head -1)
        echo ""
        echo "✅ Tunnel 已建立！"
        echo "📡 公网地址: $URL"
        echo "🔌 WS地址: wss://$(echo $URL | sed 's|https://||')/ws"
        echo "📋 日志: $CF_LOG"
        echo ""
        echo "⚠️  注意: Quick Tunnel URL 每次重启会变"
        echo "⚠️  如需固定域名，请配置 Cloudflare 账号 + 自定义域名"
        
        # 保存当前URL到文件，供其他脚本读取
        echo "$URL" > "$SCRIPT_DIR/tunnel_url.txt"
        echo "wss://$(echo $URL | sed 's|https://||')/ws" > "$SCRIPT_DIR/tunnel_ws_url.txt"
        exit 0
    fi
    sleep 1
done

echo "❌ 30秒内未获取到 tunnel URL，请检查日志: $CF_LOG"
exit 1
