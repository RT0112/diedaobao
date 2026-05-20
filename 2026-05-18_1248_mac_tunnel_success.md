# Mac 中转隧道成功 (12:48)

## 方案
Mac 运行 cloudflared，转发到 K70 的 192.168.4.19:3000

## 新外网地址
https://clerk-anything-adopt-lately.trycloudflare.com

## 验证
{"status":"ok","time":1779079716027,"db":"ok","ws":{"total":2,"elders":1,"guardians":1}}

## 进程
Mac PID 33123，需要保持运行

## 待确认
- 另一台手机是否和 K70 同一 WiFi？（是则不需要隧道）
- 是否需要更新双端代码用 tunnel URL？
