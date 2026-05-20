# 2026-05-19 工作总结：工作流整合

## 目标
1. 找到可靠的 K70 Termux 远程操作方法 → 固化文档
2. APK 发微信不再复制到桌面，直接发
3. 整合成永久可复用经验

---

## 核心成果

### 1. wechat_mac_send.sh 更新 ✅
**不再复制到桌面**，直接传 APK 路径：
```bash
./scripts/wechat_mac_send.sh projects/family-guardian-app/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
```

原理：Finder clipboard → Command+V → 回车。微信 Mac 客户端需已打开，文件传输助手窗口需可见。

### 2. K70 Termux 操作研究结果 ✅

**结论：必须安装 Termux:API（一次性手动操作）**

研究过程：
- `adb shell` 无法访问 Termux 私有目录 → Permission denied
- `run-as com.termux` → package not debuggable（Termux 是 release build）
- SSH via adb forward → sshd 不自动启动，Termux 被杀后 SSH 断
- `adb shell input text` → 对长命令不可靠（空格/newline 处理问题）
- `/data/local/tmp/` shell 可写，但无法触发 Termux 执行

唯一可靠方案：**Termux:API**
安装后可用 `am startservice` 直接在 Termux 里执行命令，不依赖 sshd。

### 3. 文档更新 ✅
**OPERATIONS.md**：
- 新增「K70 远程操作」章节，完整记录 Termux:API 安装步骤和重启命令
- 更新「推送本地修改到 K70」用 `/sdcard/Download/` 中转方案
- 编译章节去除"复制桌面"步骤

**MEMORY.md**：
- 微信发送：wechat_mac_send.sh，直接传路径不发桌面
- K70 操作：必须装 Termux:API 才能可靠重启服务端

**AGENTS.md**：
- 铁律 #12：微信发送直接传 APK 路径不发桌面
- 铁律 #13：K70 操作必须先装 Termux:API

---

## 待用户操作（一次性）

**在 K70 上安装 Termux:API**：
- Google Play 或 F-Droid 搜索 "Termux API"
- 安装后给权限
- 之后我就能远程可靠重启服务端了

---

## 文件改动清单

| 文件 | 改动 |
|------|------|
| `scripts/wechat_mac_send.sh` | 重写，移除 cp 命令，支持任意路径 |
| `OPERATIONS.md` | 新增 K70 远程操作章节，更新编译推送章节 |
| `MEMORY.md` | 更新微信发送和 K70 操作规则 |
| `AGENTS.md` | 铁律 #12/#13 |

---

## 关键经验
- Termux 没有公开的命令执行 API（无 Termux:API 时无法远程自动化）
- SSH 到 Termux 需要 sshd 已在运行（不自动启动）
- `adb shell input text` 对复杂命令不可靠
- `adb shell` 无法访问 Termux 私有目录（权限隔离）
