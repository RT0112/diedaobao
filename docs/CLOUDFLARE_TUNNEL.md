# 外网访问部署指南 — Cloudflare Tunnel

> 目标：让子女端在外网也能访问K70后端
> 方案：Cloudflare Tunnel（免费、稳定、K70独立运行）

---

## 方案对比

| 方案 | 免费 | 稳定性 | K70独立 | 推荐度 |
|------|------|--------|---------|--------|
| **Cloudflare Tunnel** | ✅ | ★★★★★ | ✅ | ⭐ 首选 |
| Tailscale | ✅ | ★★★★☆ | ✅ | 备选（需子女装App） |
| serveo.net | ✅ | ★★☆☆☆ | ✅ | ❌ 经常被墙 |

---

## 前提条件

1. K70已安装Termux并能正常运行后端（`~/diedaobao-server/`）
2. K70能访问互联网
3. 有一个Cloudflare账号（免费注册：https://dash.cloudflare.com/sign-up）

---

## 步骤一：安装cloudflared

在K70 Termux中执行：

```bash
# 方法1：直接下载ARM64二进制（推荐）
cd ~/diedaobao-server
curl -L https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-arm64 -o cloudflared
chmod +x cloudflared

# 测试是否正常
./cloudflared --version
```

如果GitHub下载慢，用镜像：
```bash
curl -L https://ghproxy.com/https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-arm64 -o cloudflared
chmod +x cloudflared
```

---

## 步骤二：登录Cloudflare（一次性）

```bash
./cloudflared tunnel login
```

会输出一个URL，用K70浏览器或其他设备打开这个URL，登录Cloudflare并授权。

授权后，cloudflared会生成证书文件：
```
~/.cloudflared/cert.pem
```

---

## 步骤三：创建隧道（一次性）

```bash
# 创建名为"diedaobao"的隧道
./cloudflared tunnel create diedaobao
```

输出类似：
```
Tunnel credentials written to ~/.cloudflared/<隧道ID>.json
```

记下这个隧道ID（后面要用）。

---

## 步骤四：运行隧道

### 快速测试

```bash
# 前台运行，分配随机域名
./cloudflared tunnel run --url http://localhost:3000 diedaobao
```

输出类似：
```
Your quick Tunnel has been created! Visit it at:
https://xxxx-xxxx-xxxx.trycloudflare.com
```

记下这个 `*.trycloudflare.com` 域名，这就是你的外网地址。

### 后台运行

```bash
# 后台运行
nohup ./cloudflared tunnel run --url http://localhost:3000 diedaobao > tunnel.log 2>&1 &

# 查看日志确认成功
tail -f tunnel.log
```

---

## 步骤五：配置子女端

将子女端的 `ServerConfig.kt` 中的 `BASE_URL` 改为隧道域名：

```kotlin
// Before
val BASE_URL = "http://192.168.4.19:3000"

// After
val BASE_URL = "https://xxxx-xxxx-xxxx.trycloudflare.com"
```

重新编译安装子女端APK。

---

## 步骤六：开机自启（可选）

在K70 Termux中创建启动脚本：

```bash
cat > ~/diedaobao-server/start-tunnel.sh << 'EOF'
#!/data/data/com.termux/files/usr/bin/bash
cd ~/diedaobao-server

# 启动后端服务
node server.js > server.log 2>&1 &

# 等待服务启动
sleep 2

# 启动隧道
nohup ./cloudflared tunnel run --url http://localhost:3000 diedaobao > tunnel.log 2>&1 &

echo "服务已启动"
EOF

chmod +x ~/diedaobao-server/start-tunnel.sh
```

如果安装了Termux:Boot，可以把脚本放到 `~/.termux/boot/` 目录实现开机自启：

```bash
mkdir -p ~/.termux/boot
ln -s ~/diedaobao-server/start-tunnel.sh ~/.termux/boot/start-diedaobao.sh
```

---

## 获取隧道域名（随时查看）

如果忘记隧道域名，可以查看日志：

```bash
grep "trycloudflare.com" ~/diedaobao-server/tunnel.log
```

或重新运行一次前台模式查看。

---

## 固定域名（可选）

如果不想每次域名都变，可以配置固定域名：

```bash
# 创建配置文件
cat > ~/.cloudflared/config.yml << EOF
tunnel: <你的隧道ID>
credentials-file: ~/.cloudflared/<隧道ID>.json

ingress:
  - hostname: diedaobao.yourdomain.com
    service: http://localhost:3000
  - service: http_status:404
EOF
```

然后在Cloudflare Dashboard添加DNS记录：
- 类型：CNAME
- 名称：diedaobao
- 内容：`<隧道ID>.cfargotunnel.com`

这样就可以用 `diedaobao.yourdomain.com` 固定访问。

---

## 常见问题

### Q: 隧道连接不上？

```bash
# 检查cloudflared进程
ps aux | grep cloudflared

# 查看日志
tail -100 ~/diedaobao-server/tunnel.log

# 重启隧道
pkill -f cloudflared
~/diedaobao-server/start-tunnel.sh
```

### Q: 域名每次重启都变？

使用"固定域名"方案，或者每次重启后查看新域名并更新子女端。

### Q: Cloudflare被墙？

改用Tailscale（P2P直连，但子女手机也要装Tailscale）。

---

## 完整命令速查

```bash
# 一键启动（在K70上执行）
cd ~/diedaobao-server && ./start-tunnel.sh

# 查看隧道状态
ps aux | grep cloudflared

# 查看隧道域名
grep "trycloudflare.com" ~/diedaobao-server/tunnel.log

# 重启隧道
pkill -f cloudflared
cd ~/diedaobao-server && nohup ./cloudflared tunnel run --url http://localhost:3000 diedaobao > tunnel.log 2>&1 &

# 完全重启（后端+隧道）
pkill -f cloudflared; pkill -f node
cd ~/diedaobao-server && ./start-tunnel.sh
```

---

## 下一步

1. 先在K70上完成cloudflared安装和登录
2. 测试隧道能否正常工作
3. 确定隧道域名后更新子女端ServerConfig
4. 测试子女端在外网能否访问
