#!/bin/bash
#=============================================
# 跌倒宝服务器 - K70 (Termux) 一键部署
#=============================================

set -e

echo "🔧 跌倒宝服务器部署 - K70 (Termux)"
echo "===================================="

# 1. 更新包管理器
echo ""
echo "[1/5] 更新包管理器..."
pkg update -y && pkg upgrade -y

# 2. 安装 Node.js
echo ""
echo "[2/5] 安装 Node.js..."
pkg install nodejs -y

# 3. 安装项目依赖
echo ""
echo "[3/5] 安装项目依赖..."
cd "$(dirname "$0")"
npm install

# 4. 初始化数据库
echo ""
echo "[4/5] 初始化数据库..."
node -e "const {getDb} = require('./db'); getDb(); console.log('✅ 数据库初始化完成')"

# 5. 创建自启动脚本
echo ""
echo "[5/5] 创建自启动脚本..."

cat > ~/diedaobao-start.sh << 'EOF'
#!/bin/bash
# 跌倒宝服务器启动脚本
cd "$(dirname "$0")/diedaobao-server" 2>/dev/null || cd /data/data/com.termux/files/home/diedaobao-server
node server.js
EOF
chmod +x ~/diedaobao-start.sh

# Termux Boot 自启动（可选，需要安装 Termux:Boot）
mkdir -p ~/.termux/boot/
cat > ~/.termux/boot/diedaobao-server.sh << 'EOF'
#!/bin/bash
sleep 10
cd /data/data/com.termux/files/home/diedaobao-server
node server.js &
EOF
chmod +x ~/.termux/boot/diedaobao-server.sh

echo ""
echo "✅ 部署完成！"
echo ""
echo "启动方式："
echo "  手动: ~/diedaobao-start.sh"
echo "  后台: nohup node server.js &"
echo ""
echo "内网穿透（选择一种）："
echo "  1. SakuraFrp: https://open-frp.icu/"
echo "  2. NatFrp:    https://www.natfrp.com/"
echo ""
echo "测试: curl http://localhost:3000/health"
