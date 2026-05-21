# CC+qclaw 集成 + 子女端 MapActivity Bug 修复

## 目标
1. 将 CC (Claude Code CLI) + qclaw 免费额度的集成方法记录到 skill，确保新会话也能用
2. 修复子女端 MapActivity 的两个位置/WebView bug，用 CC 来修

## 关键推理

### Bug 分析
- **Bug 1（webresource/瓦片不显示）**: `loadDataWithBaseURL("https://localhost")` 设了 HTTPS baseURL，但高德瓦片 URL 是 `http://webrd0{s}.is.autonavi.com/`，WebView 混合内容策略阻止了瓦片加载 → 地图空白
- **Bug 2（加载失败无提示）**: `onReceivedError(view, code, desc, url)` 是废弃签名，Android 6.0+ 不回调这个重载，改为回调 `onReceivedError(view, request, error)`，导致地图加载失败时用户看不到任何错误提示

### 修复方案
- Bug 1: 添加 `settings.mixedContentMode = MIXED_CONTENT_COMPATIBILITY_MODE` + 将瓦片URL从 `http://` 升级为 `https://`
- Bug 2: 新增 `onReceivedError(WebView?, WebResourceRequest?, WebResourceError?)` 重载，只处理主帧错误

### CC 使用
- 用 Python subprocess 直接调用 CC，避免 shell 转义问题
- CC 修复了两个 bug，但引入了 `isActive` 缺少 import 的编译错误
- 手动添加 `import kotlinx.coroutines.isActive` 后编译通过

## 结论
- ✅ 编译通过，APK 安装到 K70 成功
- ✅ 微信发送 APK 成功
- ✅ Git commit: `7ff1e10 fix: 修复子女端地图WebView混合内容阻止瓦片加载 + onReceivedError废弃API导致错误无提示 (via CC)`
- ✅ Skill `qclaw-cc-integration` 更新到 v1.0.0，包含实战注意事项
- ⚠️ CC 可能引入编译错误，必须编译验证后再安装
