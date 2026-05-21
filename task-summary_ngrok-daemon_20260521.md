# Ngrok 守护进程 + 双机制落地

## 目标
1. 把 Mac 上的 ngrok 做成守护进程（防断线）
2. 桌面放开关（一键启停）
3. 落实两个机制：防重现 + 高复用归类

## 关键推理

### 为什么 K70 自己跑隧道不可行
- adb root ❌（生产构建）
- su ❌（无 root）
- setprop net.dns1 ❌（read-only）
- /etc/hosts ❌（需 root）
- Go 程序 DNS IPv6 崩溃 ❌（无解）
- **结论**：必须用 Mac 中继

### 守护进程内存优化
- `Nice=10` → CPU 低优先级
- `LowPriorityIO=true` → 磁盘 IO 低优先级
- ngrok 常驻约 30-50MB（可接受）

## 结论

### ✅ 已完成
1. **launchd 守护进程** — `~/Library/LaunchAgents/com.user.ngrok-daemon.plist`
   - KeepAlive → 崩溃自动重启
   - RunAtLoad → Mac 重启自启
   - Nice=10 + LowPriorityIO → 低内存占用
2. **桌面开关** — `~/Desktop/ngrok-control.command`
   - 显示状态 + 公网地址
   - 菜单：启动/停止/重启/查看日志
3. **机制 1 落地** — 防重现措施写入 skill
   - `diedaobao-project` skill 更新（launchd 配置）
   - `k70-tunnel` skill 新建（隧道管理全流程）
4. **机制 2 落地** — 高复用操作归类成 skill
   - 新建 `k70-tunnel` skill（mobile-dev 分类）
   - 包含：守护进程配置、故障排查、cloudflared 备用、快速诊断脚本

### 📁 生成的文件
```
~/Library/LaunchAgents/com.user.ngrok-daemon.plist  — launchd 守护配置
~/Desktop/ngrok-control.command                        — 桌面开关
~/.qclaw-hermes/skills/mobile-dev/k70-tunnel/         — 新建 skill
~/.qclaw-hermes/skills/mobile-dev/diedaobao-project/  — 更新（隧道管理章节）
```

### 🔮 待验证
- [ ] 双击 `ngrok-control.command` 是否能正常显示菜单
- [ ] Mac 重启后 ngrok 是否自动启动
- [ ] 子女端能否通过固定域名连上 K70

## 附：launchd plist 内容
```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>Label</key>
	<string>com.user.ngrok-daemon</string>
	<key>ProgramArguments</key>
	<array>
		<string>/Users/zhou/.local-node/bin/ngrok</string>
		<string>http</string>
		<string>192.168.4.19:3000</string>
		<string>--log=stdout</string>
		<string>--region=us</string>
	</array>
	<key>RunAtLoad</key>
	<true/>
	<key>KeepAlive</key>
	<true/>
	<key>LowPriorityIO</key>
	<true/>
	<key>Nice</key>
	<Integer>10</Integer>
	<key>StandardOutPath</key>
	<string>/tmp/ngrok-daemon.log</string>
	<key>StandardErrorPath</key>
	<string>/tmp/ngrok-daemon.log</string>
	<key>ThrottleInterval</key>
	<Integer>10</Integer>
	<key>ExitTimeOut</key>
	<Integer>5</Integer>
</dict>
</plist>
```
