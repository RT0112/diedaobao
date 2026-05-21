# Task: 跌倒宝项目地图生成

**时间**: 2026-05-21 10:30 GMT+8
**任务**: 读取源代码 + 项目文档，生成完整项目地图

---

## 执行过程

### 1. 确认文件位置
- 工作目录: `~/.qclaw/workspace-x5kuz49xple53hhg/projects/`
- 源文件: 62个Kotlin文件 + 3个Node.js文件
- 发现根目录存在重复副本(inodes不同)，实际编译用的是 `app/src/main/java/` 下文件

### 2. 读取的文件
- **后端**: server.js(950行)/ws.js(250行)/db.js(180行)/package.json
- **老人端核心**: CloudBaseClient(22KB)/WSClient(15KB)/FallDetector(40KB)/DetectionConfig(8KB)/SensorCollector(5.6KB)/RemoteAssistService(10KB)/PermissionRecordManager(22KB)/FallDetectionService(50KB)/ServerConfig(667B)
- **子女端核心**: CloudBaseClient(29KB)/WSClient(16KB)/ServerConfig(660B)
- **配置**: AndroidManifest×2 / build.gradle.kts×2 / REQUIREMENT文档×3
- **文档**: MEMORY.md / OPERATIONS.md / SOUL.md / AGENTS.md / docs/

### 3. 生成地图

输出文件: `docs/PROJECT_MAP.md` (30KB)

包含:
- 项目概览
- 整体架构图（ASCII）
- 62个源文件清单 + 功能说明
- 3大数据流图（跌倒检测/位置同步/远程协助）
- API端点+WS消息类型完整参考表
- 关键配置速查（URL/灵敏度表/SharedPreferences键名/权限声明）
- 10条常见陷阱速查表
- 文件路径索引（按功能检索）

---

## 关键发现

1. **WS_URL有潜在bug**: 老人端ServerConfig中WS_URL应为`ws://192.168.4.19:3000/ws`，但代码中硬编码了8080端口
2. **后端新增/restart端点**: 2026-05-21刚添加，待测试
3. **ML从未触发**: 灵敏度阈值问题，v17待真机测试
4. **子女端versionCode=28，老人端=136**: 双端版本号未同步
5. **子女端BASE_URL=192.168.4.19**: 本地IP，prod需改为serveo.net隧道URL
