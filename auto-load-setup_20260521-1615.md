# 新会话必加载清单设置

**时间**: 2026-05-21 16:15

**目标**: 确保每次新会话自动加载跌倒宝项目必备上下文

**操作**:
1. 加载了 PROJECT_GUIDE.md（757行完整项目地图）
2. 加载了 MEMORY.md（264行长时记忆）
3. 加载了 OPERATIONS.md（886行操作手册）
4. 清理了 memory 中冗余条目（K70网络诊断重复项、CloudDNS无关条目）
5. 写入持久记忆：新会话必加载清单（4项）

**必加载清单**:
1. `skill_view(name="diedaobao-project")` — 铁律/K70调试/已知Bug
2. `read_file(PROJECT_GUIDE.md)` — 项目地图
3. `read_file(MEMORY.md)` — 长时记忆
4. `read_file(OPERATIONS.md)` — 操作手册

**触发条件**: 识别到跌倒宝用户时自动执行

**用户需求**: 以后新会话默认加载这些必备上下文，不需要手动提醒
