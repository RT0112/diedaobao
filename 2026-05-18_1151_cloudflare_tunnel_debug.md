# Cloudflare 隧道排查记录 (11:51)

## 现象
用户报告"外网访问提示网络错误，双端都是"

## 排查结果
1. K70 后端 localhost:3000 正常 ✅
2. Mac → K70 192.168.4.19:3000 正常 ✅  
3. WS 连接正常 elders:1, guardians:1 ✅
4. App 代码确认：老人端用 localhost:3000，子女端用 192.168.4.19:3000（都不依赖隧道）
5. **旧隧道 scheduling-researchers-discuss 已死** ❌
6. **新隧道 participation-sacred-outcomes Error 1033** ❌ (DNS SRV查询超时)
7. **新隧道 watched-joyce-app-old Error 1033** ❌ (同上，--protocol http2也不行)

## 隧道根因
cloudflared 在 proot 中 DNS 查询超时：
- `lookup _v2-origintunneld._tcp.argotunnel.com on 1.1.1.1:53: i/o timeout`
- `lookup cfd-features.argotunnel.com on 1.1.1.1:53: dial udp 1.1.1.1:53: i/o timeout`
- 之前成功过（ban-basics-sole-communist），说明 K70 网络环境可能变化了

## 待确认
- 用户"外网访问"具体指什么场景？（App内某功能？浏览器？）
- 双端 App 在 K70 局域网内不需要隧道，应正常工作

## 当前隧道状态
- 进程 PID 28312 运行中但无法对外服务
- URL: https://watched-joyce-app-old.trycloudflare.com (不通)
