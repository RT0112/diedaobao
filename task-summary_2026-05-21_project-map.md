# Task Summary: 项目地图生成 + 外网访问方案

**时间**: 2026-05-21 10:45
**任务**: 
1. ✅ 为跌倒宝项目创建完整项目地图
2. ✅ 标注ML禁区（绝对不能动）
3. ✅ 提供免费稳定的外网访问方案

---

## 完成内容

### 1. 项目地图 `docs/PROJECT_MAP.md` (32KB)

**包含**:
- 整体架构图（ASCII）
- 62个Kotlin源文件清单 + 功能标注
- 3个Node.js文件解析
- 3大数据流图：跌倒检测/位置同步/远程协助
- 12个HTTP端点 + 11种WS消息类型
- 关键配置速查表
- **新增：禁区清单（ML推理、ONNX模型、物理系数、采样率）**

### 2. 外网访问方案

**推荐：Cloudflare Tunnel**
- ✅ 免费、无带宽限制
- ✅ 稳定（Cloudflare大厂背书）
- ✅ K70独立运行，不依赖Mac
- ✅ Termux可直接安装cloudflared

**部署步骤**（详见 `docs/CLOUDFLARE_TUNNEL.md`）:
```bash
# K70上执行
cd ~/diedaobao-server
curl -L https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-arm64 -o cloudflared
chmod +x cloudflared
./cloudflared tunnel login      # 首次登录
./cloudflared tunnel create diedaobao  # 创建隧道
nohup ./cloudflared tunnel run --url http://localhost:3000 diedaobao > tunnel.log 2>&1 &
```

**备选方案**：
- Tailscale（P2P直连，速度快但子女也要装App）
- serveo.net（之前用过，不稳定）

---

## 关键约束

### ⛔ 禁区 — 绝对不能修改

| 禁区 | 文件 | 原因 |
|------|------|------|
| ML推理 | `ml/FallDetectorML.kt`, `ml/FallDetectorONNX.kt` | 算法已训练调优 |
| ONNX模型 | `assets/fall_model.onnx` | 模型文件不可修改 |
| 物理系数 | `FallDetector.kt`物理分数计算 | 经验参数 |
| 采样率 | `SensorCollector.kt` SENSOR_DELAY_GAME | 影响检测时序 |

**调整灵敏度只能改**: `DetectionConfig.kt` 中的阈值（已提供8级可调）

---

## 后续操作

用户需要：
1. 在K70上执行Cloudflare Tunnel部署
2. 获取外网域名后更新子女端ServerConfig
3. 重新编译子女端APK

**所有文档已就位**：
- `docs/PROJECT_MAP.md` — 项目地图
- `docs/CLOUDFLARE_TUNNEL.md` — 外网部署指南
- `OPERATIONS.md` — 已追加外网访问配置流程
