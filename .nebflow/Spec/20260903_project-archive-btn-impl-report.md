# Project 面板归档按钮——实施报告

> 节点：M1-迁移批-实施「Project 面板归档按钮」（worktree 独占施工）
> 日期：2026-09-03 ｜ 分支：`proj-archive-btn`（基线 main@bc95dec1，开工 merge main 零冲突）
> 设计依据：《20260903_stage2-migration-plan.md》v2 §6.1（新机制章①）
> 合并纪律：本批**未合并回 main**——由下游「合并」节点在 QA 通过后执行。

---

## 0. 上游文档 v2 要点（新机制章①一句话）

归档按钮四条语义：**仅显式人工触发**；归档后 project 从任务面板消失（目录注入同步下架）；workspace 文件零删除零移动；**系统无任何自动归档路径**。

---

## 1. 现状盘点（第零步，带代码行证据）

### 1.1 基线与合并

- worktree 基线确认：`git log -1` = `bc95dec1`（Compaction 分层压缩提示词），分支 `proj-archive-btn`，工作区干净。
- `git merge main`：**零冲突**，快进带入并行节点成果（结果投递五向量 `2b064487`、AskUser pending 修复 `7710190c`、turn-collapse `e50ec989`、worktree 参数归一化 `34a986bd`、send-btn 绿色强化 `127e7faf`、分层压缩 `01010883` 等 37 个 commit）。

### 1.2 前端 projectTab.js 通读结论

| 项 | 结论 | 证据 |
|---|---|---|
| 列表数据来源 | REST：`fetchProjects()` → `GET /api/projects`（需 auth）→ `{projects:[...]}` | nodeData.js:51-56（`API.projects` 集中定义 :19） |
| 条目 DOM | `.team-card.project-card[data-project=<name>]`，header（title+summary）+ fields（workspace/AGENTS.md/运行数/description） | projectTab.js:106-128 |
| 既有操作入口 | `data-open-flowmap`（title 进 Flow Map）、`data-open-workspace`（folder-open 按钮）、`data-open-agent`（查看/编辑），统一 `bindProjectClicks` 属性委托绑定 | projectTab.js:131-161 |
| 实时刷新机制 | WS nodeCreated/Updated/Completed/Removed → `rerenderProjectsListView` → `rerenderProjectsTab`（200ms 防抖 + renderSeq 防旧盖新） | projectTab.js:213-248 |
| i18n | `web/js/locales/en.js` + `zh-CN.js` 键值对，`t('project.xxx', {name})` 插值 | locales/en.js:22-40 |
| 确认交互既有范式 | `window.__showConfirm(title, msg, onConfirm)`（modal.js:129，复用 #delete-box，cancel 只 hideModals 不触发回调） | modal.js:129-143,264-265 |

### 1.3 后端数据模型与读写链

- **模型**：`ProjectDef(name, description, workspace, agentFile, feedbackMode, createdAt)`——project.json 磁盘格式经实读核实（4 个挂载项目均无 null 字段）。
- **写链**：`ProjectStore.create`（ProjectStore.scala:66-97）——全仓 project.json **唯一**写入点（:91 `AtomicJson.writeSync` 原子写）。
- **读链**：`ProjectStore.load/list`（:26-54）；挂载 `ProjectRuntimeRegistry.mount/mountAll`（ProjectActor.scala:57-123）；启动挂载 `startupMount`（GatewayMain.scala:424-445）。
- **列表 API 出口**：`GET /api/projects` → `ProjectStore.list()`（RestApiRoutes.scala:293-308）。
- **ProjectCreate 幂等挂载**：NodeTools.scala:1097-1106（已存在 → load → mountProject）。

### 1.4 持久化层选型：project.json 手术式原位插键（判定+理由）

**选型**：`ProjectStore.archive` 读原始 Json → `deepMerge` 写入 `archived:true` + `archivedAt` → AtomicJson 原子写回；解码侧 `ProjectDef` 增 `archived: Option[Boolean] = None` + `archivedAt: Option[Long] = None`（withDefaults，存量文件零迁移）。

**理由**：
1. circe derived codec 全量 re-encode 会把 `None` 字段写成 `null` 并重排布局——现存磁盘文件无 null 字段，风格不被接受；手术式插键保证存量字段**逐字节稳定**（spec 断言锁定）。
2. 条件序列化（对齐 NodeDef.deps 非默认值才带键的风格）天然成立：键只在归档后出现。
3. 单程语义 + 数据可逆：恢复 = 手工删除两键（本批无 UI）。

### 1.5 过滤层选型：`ProjectStore.list()` 源头过滤（判定+理由）

`list()` 全仓仅两个消费方——面板 API（RestApiRoutes.scala:295）与 startupMount（GatewayMain.scala:429），语义都要求跳过归档。在源头过滤让二者同源生效，且未来新消费方默认安全（归档项永不出现在任何列表路径——验收 4 最强保证）。`load()` 保持不过滤（agent.md 读写不受影响，归档项目定义仍可查）。

### 1.6 清扫机制全仓审计清单（验收 4 证据底稿）

| # | 机制 | 位置 | 作用域 | 触及 project 定义？ |
|---|---|---|---|---|
| 1 | 节点 TTL 扫描 `projectTtlScanner`（30s→TtlTick→`sweepExpired`） | GatewayMain.scala:450-451 → ProjectActor.scala:208-217 → FlowMapStore.scala:92-105 | **仅 flow-map 节点**：终态节点从活动区移入归档区（显示 TTL），`s.nodes` 域内操作 | 否 |
| 2 | 任务 TTL `taskTtlSweep` → `FileTaskStore.purgeAllExpired()` | GatewayMain.scala:392-397 | tasks/ 目录（session+team 任务记录） | 否 |
| 3 | 孤儿收殓 `subagentCrashSweep` → `SubAgentStartupRecovery.recoverOrphans` | GatewayMain.scala:404-411 | subAgentTaskStore + sessionStore | 否 |
| 4 | V8 重投扫描 `redeliverUnconsumedNebulaResults`（TtlTick 内） | ProjectActor.scala:209-214 | flow-map 节点 out=Nebula 结果补投 | 否 |
| 5 | 定时任务执行器 ScheduledTaskActor/Service | core/scheduler/ | cron 触发用户任务执行，无 project 生命周期钩子（全文件零 ProjectStore 引用） | 否 |
| 6 | 备份 NebflowBackup | service/NebflowBackup.scala:58 | `projects/` 是备份**包含**模式——只读复制进 backups/，源零删除零移动 | 否 |
| 7 | ContextRefresher 的 projects 路径 | agent/ContextRefresher.scala:113-151 | `projects/<folderName>/` 旧 folder 约定配置目录（NEBFLOW.md），只读+mkdir，与 `projects/<name>/project.json` 生命周期无关 | 否 |
| 8 | filewatch 快照扫描 | core/filewatch.scala | workspace 文件变更 stat（reminder 用），只读 | 否 |
| 9 | `ProjectStore.delete` | ProjectStore.scala:115-118 | **全仓 src/main 零调用方**（grep 证据：`ProjectStore.delete` 仅命中定义处）——不存在任何删除 project 的代码路径 | 否 |

**结论**：系统无 TTL/定时/清扫/重启路径会归档 project；归档唯一入口 = 本批新增 `POST /api/projects/<name>/archive`（显式人工动作）。

---

## 2. 改动清单（文件/函数级）

| 文件 | 改动 |
|---|---|
| `src/main/scala/nebflow/core/project/ProjectTypes.scala` | `ProjectDef` 增 `archived: Option[Boolean] = None`、`archivedAt: Option[Long] = None`（解码零迁移） |
| `src/main/scala/nebflow/core/project/ProjectStore.scala` | ① `list()` 尾部 `.map(_.filterNot(_.archived.contains(true)))` 源头过滤；② 新增 `archive(name): IO[Either[String, Long]]`——手术式插键+幂等（已归档不重写、返回既有 archivedAt）+ 名字合法性校验 |
| `src/main/scala/nebflow/gateway/RestApiRoutes.scala` | 新增 `POST /projects/<name>/archive` 路由（withAuth；Right→`{archived:true,archivedAt}`，Left→404）+ 契约注释更新 |
| `src/main/scala/nebflow/core/tools/NodeTools.scala` | `ProjectCreate.call` 幂等挂载路径增归档拒绝分支（`case Some(pd) if pd.archived.contains(true)` → ToolError 提示手工删键恢复），防「已挂载但面板不可见」僵尸态 |
| `src/main/resources/web/js/projectTab.js` | ① import 增 `authHeaders`/`API`；② `projectCardHtml` header 增归档按钮（`data-archive-project`）；③ `bindProjectClicks` 增归档绑定；④ 新增 `archiveProject(name)`——`window.__showConfirm` 确认 → POST → toast → `rerenderProjectsTab()` 同页重渲 |
| `src/main/resources/web/css/projectPanel.css` | 新增 `.project-archive-btn`（26×26 对齐 `.project-open-btn`，hover 用既有 `--color-error` 提示半破坏性） |
| `src/main/resources/web/js/locales/en.js` / `zh-CN.js` | 各增 5 key 成对：`project.archive` / `archiveTitle` / `archiveConfirm`（含「归档后不在任务面板显示，workspace 文件全部保留」语义）/ `archiveDone` / `archiveFail` |
| `src/test/scala/nebflow/core/project/ProjectArchiveSpec.scala` | **新文件**，6 用例（既有 spec 零改动） |
| `tests/project-panel-archive.spec.mjs` | **新文件**，Playwright 2 用例 |

---

## 3. 四条验收口径逐条自证

### ① 归档按钮可见 ✅

- 每张 project 卡片 header 渲染 `.project-archive-btn`（`data-archive-project=<name>`，lucide `archive` 图标，26×26 对齐既有 `.project-open-btn` 形态，位于摘要右侧）——projectTab.js `projectCardHtml`。
- Playwright 断言：`btnKeep/btnGone` toBeVisible + svg 图标存在（createIconsIn 已渲染）——passed。
- 双主题截图可见（见 §5 路径）：暗/亮色下按钮均与面板既有交互风格一致。

### ② 归档后任务面板不再显示（带确认，无需刷新）✅

- 确认：点击 → `window.__showConfirm` 弹层（`#delete-box`），标题/正文含项目名与「不在任务面板显示 / workspace 文件保留」语义；取消 → 零请求、卡片保留（Playwright 断言 `archivePosts.length===0`）。
- 确认后：POST archive → 后端 `ProjectStore.list()` 已过滤 → 前端 `rerenderProjectsTab()` 同页 refetch 重渲 → 卡片消失（Playwright：`demo-gone` toHaveCount(0)，`demo-keep` 仍可见，`window.__archiveNoReload` 标记存活证明**零页面刷新**，全程零 pageerror）。
- 后端侧同步下架：列表 API 出口过滤（RestApiRoutes.scala:295 消费已过滤的 list）。

### ③ workspace 本地文件全部保留（零删除零移动）✅

- 实现面：`ProjectStore.archive` 唯一写动作 = project.json 一个文件的原位重写（AtomicJson tmp+ATOMIC_MOVE）；无任何 os.remove/os.move 调用。
- spec 断言（ProjectArchiveSpec 用例 2）：归档前后 `os.walk(ws)` 全文件「相对路径→内容」Map **逐字节相等**；`projects/<name>/` 定义目录与 project.json 仍存在（打标记而非删除）。
- spec 断言（用例 1）：归档后 project.json 既有五字段（name/description/workspace/agentFile/createdAt）逐值不变，键集恰好多出 `archived`+`archivedAt` 两键。

### ④ 全仓无自动归档路径 ✅

- 审计清单见 §1.6（9 项机制逐一排除，含节点 TTL `sweepExpired` 只动 flow-map 节点域——FlowMapStore.scala:92-105 的 `s.nodes` 操作，与 project.json 零交集）。
- 归档唯一触发点 = `POST /api/projects/<name>/archive`（显式人工动作）；无定时器、无生命周期钩子、无重启路径调用 `ProjectStore.archive`。
- `ProjectStore.delete` 全仓零调用方——连删除路径都不存在。

### 附：挂载中 project 被归档的行为（任务书要求定义并实施）

**语义**：归档仅改标记+列表隐藏，**不强制拆除**运行中 ProjectActor/会话——`ProjectRuntimeRegistry`/Mail 路由不受影响，运行中任务自然终态。重启后 `startupMount` 经 `ProjectStore.list()`（GatewayMain.scala:429）同源过滤**跳过归档项**（spec 用例 4 断言 rtGone=None）；`ProjectCreate` 幂等挂载路径对归档项目**拒绝**（NodeTools.scala），防「已挂载但面板不可见」僵尸态。

---

## 4. 测试结果（全部真实执行）

### 4.1 后端 ProjectArchiveSpec（新 spec，既有 spec 只读未动）——6/6 绿

| 用例 | 结果 |
|---|---|
| 归档标记持久化（archived/archivedAt 入盘、既有字段逐值稳定、键集恰增两键、load 可解码） | ✅ |
| 零删除零移动（workspace 全文件快照逐字节相等 + 定义目录/project.json 存活） | ✅ |
| 列表出口过滤（arch-gone 从 list 消失、arch-keep 保留） | ✅ |
| startupMount 跳过归档项（list→mountAll 链：归档项不在挂载输入、registry 无其 runtime、活跃项正常挂载） | ✅ |
| 归档幂等（二次归档返回同一 archivedAt、文件逐字节未重写） | ✅ |
| 不存在/非法名 → Left | ✅ |

### 4.2 变异验红（红绿证据）

- **变异**：`ProjectStore.list` 过滤临时替换为 `.map(identity)`（注释标记 MUTATION，未 commit）。
- **红**：`Failed: Total 6, Failed 2, Passed 4`——精确红在两条过滤依赖用例：`list() filters archived projects`（:141 archived project must vanish from list）+ `startupMount skips archived project`（:165 archived project must be absent from the startupMount input）。
- **恢复**：还原过滤行 → `Passed: Total 6, Failed 0, Errors 0, Passed 6`。闭环成立。

### 4.3 前端 Playwright（真实执行，route 拦截 harness，无 8080 端口涉足）——2 passed (3.8s)

- 用例 1：按钮可见（双卡片+svg 图标）→ 取消路径零请求 → 确认路径 POST 载荷正确（URL `/api/projects/demo-gone/archive`、无 body）→ 卡片同页移除（零 reload 标记存活、零 pageerror）。
- 用例 2：亮暗双主题截图（emulateMedia colorScheme）。

### 4.4 全量 sbt test（worktree 内前台真实跑）

| 轮次 | 结果 | 耗时 |
|---|---|---|
| 第 1 轮 | Total **2187**, Failed **1**, Errors 0, Passed 2186, Ignored 7——唯一失败 `nebflow.agent.InteractionHubSpec` | 502s |
| 第 2 轮 | Total **2187**, **Failed 0**, Errors 0, **Passed 2187**, Ignored 7——**全绿** ✅ | 502s |

第 1 轮单失败判定：**跨 suite 抖动**，非本批回归——证据：① 该 spec 单跑 `testOnly` 15/15 全绿；② 全量重跑全绿；③ 本批改动文件与 agent/InteractionHub 域零交集。两轮数字如实记录。

---

## 5. 截图（绝对路径，已目检）

- `/Users/dev/.nebflow/docs/Nebflow/20260903_project-archive-btn-dark.png`（暗色，双卡片归档按钮可见）
- `/Users/dev/.nebflow/docs/Nebflow/20260903_project-archive-btn-light.png`（亮色，同状态）

~/.nebflow commit：`b6bdcc0`（Project 面板归档按钮验证截图）。

---

## 6. 分支 commit 列表（proj-archive-btn）

| hash | 内容 |
|---|---|
| `2b064487` | merge main（开工零冲突，带入并行节点成果） |
| `79f4fd0c` | 后端：手术式归档标记 + list 源头过滤 + startupMount 同源跳过 + REST + ProjectCreate 拒绝 + spec |
| `5654aca9` | 前端：归档按钮 + 确认弹层 + 同页实时移除 + i18n + Playwright spec |

~/.nebflow commit：`b6bdcc0`（截图）+ 报告 commit（见文末）。

---

## 7. 生效说明

本批改动需**重新构建并重启 Nebflow**（sbt 重新打包 + 宿主进程重启）后生效；本次不做重建重启（合并由下游节点执行后统一进行）。重启前旧进程行为不变（归档 API 不存在、面板无按钮）。

## 8. 遗留 / 后续项

1. **取消归档（恢复显示）UI**：本批单程语义——恢复 = 手工删除 `~/.nebflow/projects/<name>/project.json` 中 `archived`/`archivedAt` 两键后重启（或经 ProjectCreate 前手工恢复）；后续可按需加「取消归档」入口（数据层已可逆）。
2. **归档面板视图**：归档项目当前「隐形」，无入口查看已归档列表；后续可加「显示已归档」开关（需 list API 暴露归档项的查询面——本批刻意不暴露）。
3. **运行中分发器会话**：归档不拆除运行中会话（本批语义）；若需「归档即停」语义，需后续定义收尾协议。
4. 第 1 轮全量的 InteractionHubSpec 抖动（单跑/重跑均绿）属既有并行 suite 干扰，与本批无关，未处理。

---

## 9. 报告与提交物

- 本报告：`/Users/dev/.nebflow/docs/Nebflow/20260903_project-archive-btn-impl-report.md`
- 分支 `proj-archive-btn`：`79f4fd0c` + `5654aca9`（工作区干净，未合并 main）
- 截图 ×2 + 报告均已在 ~/.nebflow commit（`b6bdcc0` + 报告 commit）
