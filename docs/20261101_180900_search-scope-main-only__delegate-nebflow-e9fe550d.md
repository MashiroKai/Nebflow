---
type: batch
status: done
related: "[[index]]"
---

# 会话搜索范围收窄批：主 agent 聊天检索只搜主会话（2026-11-01）

> 作者 2026-11-01 17:29 裁定逐字：「我们的主agent的聊天记录搜索，是不搜索后台agent产生的消息的，只搜主会话。」四条硬要求：① 所有入口（搜索浮钮面板 + 全部入口）只出主会话消息，后台 agent（delegate / sub-agent / 后台任务）产生的消息一条不出；② 不许做成前端事后剔除——在现有搜索数据源/索引层收口（同一处能力，所有入口自动一致）；③ 主会话自身历史一条不少（更早批次 / 跨日 / 跨重启），空结果态文案照旧；④ 双引擎各跑两项实拍（后台专属词 → 零结果；主会话词 → 命中）。图谱：[[index]]。

## 0. 结论先行

- **收口点 = 会话来源（会话 id 族），单点判据 `nebflow.shared.SessionScope`**；落在**服务端三层数据面**，前端零过滤代码：① 索引入库（`ChatSearchAdapter`）、② 查询层 WHERE 兜底（`SearchIndexService`）、③ 搜索范围会话清单（`SessionStore.listSearchScopeSessions` + `GET /api/sessions?includeUnindexed=1`）（[[#2 收口点三层]]）。
- **为什么不是按消息 `source` 字段过滤**：后台 agent 的整条会话流都归它（不是主 agent 的历史）；而主会话里的注入行（节点结果 / mail / 后台任务通知）是**主会话自己的历史**，按消息来源过滤会把主会话历史误伤——违反要求③。会话来源才是正确切面（[[#1 切面]]）。
- **验收全绿**：新增 2 个 spec 共 14 条断言（判据/漂移门/入库/查询/数据源/真路由端到端/证据导出）全绿；邻域 6 个既有 spec 跑 57 条仅 1 条基线红（`SubAgentInboxMirrorSpec` 调用点计数，本批零触碰那 5 个源文件，见 [[#5 观察项]]）。
- **双引擎各两帧实拍**：`evidence/search-scope/` 4 张（索引引擎 / 旧全扫引擎 × 后台专属词零结果 / 主会话词命中），数据来源是本仓 `SearchScopeRoutesSpec` 从**真路由栈**导出的响应体（`evidence/search-scope/api/`），非手写夹具。

## 1. 切面：会话来源，不是消息来源

一条 `.ui.json` 的**会话 id 族**就是它的来源：

| 族 | 谁生成 | 会话索引登记 | 本批处置 |
|---|---|---|---|
| uuid 主会话 | 用户 / 主 agent（`SessionStore.createSession`，孤儿恢复也只认 uuid，见 `recoverOrphans`） | ✅ `_index.json` | 照旧全量可搜 |
| `delegate-*` `subtask-*` `subagent-*` `workflow-*` `node-*` `dispatcher-*` `external-*` | Delegate / SubTask / Subagent / Workflow / 项目节点 / 项目分发器 / 外部 CLI 执行器 | ❌ 从不登记 | **一律不出** |
| `dag-*` | FlowDagExecutor（AgentFlow 步骤） | ❌ 从不登记 | **一律不出**（本批新增收面） |

判据表本体 = `SessionScope.BackgroundAgentPrefixes`（8 项）。前 7 项与后端 `InjectedInboxMirror.SubAgentIdPrefixes`（= 前端 `utils.js#isBgAgentId`，UI 里由 bg-agent 弹窗承载的会话族）**逐值同源**；`dag-` 是 FlowDagExecutor 的后台步骤族，同族同处置。`team-` **不进表**——它是 MailTool 的**前端路由标记**（`team-<sessionId>` 剥前缀后指向真实会话，见 `WebSocketRoutes`），不是会话 id 族。

## 2. 收口点三层

| 层 | 落点 | 语义 |
|---|---|---|
| ① 索引入库 | `ChatSearchAdapter`（`excludedSidPrefixes` + `extractSession`/`sessionFingerprint`/`sessionMtime`/`monthKey` 对外族答 `None`） | 后台族**从不进** `search-index/chat.db`；指纹 None ⇒ `upToDate` 恒 false ⇒ 必走「删旧行」分支 ⇒ 本批之前入库的残留随首次同步清零 |
| ② 查询层 | `SearchIndexService.runQuery`：`adapter.excludedSidPrefixes` → WHERE `AND d.sid NOT LIKE '<prefix>%' ESCAPE '\'` | 旧库残留行在**任何查询形状**下（current 档 / all 档 / 各类型片 / 分页）都不出结果；`total` 用同一谓词计数不虚高（翻页不塌）；残留清零后该谓词恒真、成本可忽略 |
| ③ 搜索范围清单 | `SessionStore.listSearchScopeSessions` + `SessionRoutes`：`includeUnindexed=1` 只回主会话；旧全量形态改由 `includeBgAgents=1` 显式取 | 旧全扫引擎（`fetchSessionList` → 逐会话 history）与索引引擎**自动同一范围**；侧边栏（无参数 = `listSessions`）与 bg-agent/flow 弹窗（`includeBgAgents=1`）不受影响 |

配套：`SearchIndexAdapter` 新增 `excludedSidPrefixes: List[String] = Nil`（缺省不排除，其他数据面零影响）；`flowAgentPopup.js` 的 REST 兜底改取 `includeBgAgents=1`（它要看 dag- 族自己的会话）；`chatSearch.js` 只改注释（**零过滤代码**）。

## 3. 判据表（spec）

`src/test/scala/nebflow/core/search/SessionSearchScopeSpec.scala`（判据单点 / 漂移门 / 入库 / 查询 / 数据源）
`src/test/scala/nebflow/gateway/SearchScopeRoutesSpec.scala`（真路由栈端到端 + 证据导出）

| 组 | 断言 |
|---|---|
| ① | 8 族逐族 = 后台；uuid / `team-abc` / `ask-*` = 主会话；空 id 不入面 |
| ② | 后端弹窗族 ⊆ 排除表（逐值）；前端 `utils.js#isBgAgentId` 逐值 ⊆ 排除表（源码文本对账，先例 = `SubAgentInboxMirrorSpec` ③-1）；表本体 pin |
| ③ | 后台会话 `extractSession`/指纹/`mtime` = None、`monthKey` = ""；同关键词主会话照常抽取 |
| ④ | **直接灌库**的后台文档（模拟旧库残留）查询零命中、`total=0`（含 current 档按 sid 明指后台会话）；主会话照常命中 |
| ⑤ | 搜索范围清单只含主会话；`listSessionsIncludeUnindexed` / `listSessions` 行为不变 |
| A1-A3 | 路由层：后台专属词 `hits=[] total=0`；主会话词 `hits=1 total=1`；current 档同样收口 |
| B1-B4 | 路由层：搜索范围清单只有主会话；`includeBgAgents=1` 全量档 4 族齐在；侧边栏路径零后台族 |
| C1 | `fullRebuild` 后索引库残留行**物理清零**（`docCount` 读数 before > after），清零后主会话仍命中 |
| E1 | 真响应体落 `evidence/search-scope/api/`（实拍回放数据源） |

## 4. 验收与证据

- spec 结果：`testOnly SessionSearchScopeSpec SearchScopeRoutesSpec` ⇒ **Passed: Total 14, Failed 0**；邻域回归 `ChatSearchAdapterSpec` / `ChatSearchRoutesSpec` / `SessionStoreUnindexedSpec` / `SubAgentInboxMirrorSpec` / `SearchIndexServiceSpec` / `CjkBigramSpec` ⇒ **57 条，1 红（基线红，非本批）**。
- 实拍（`tests/search-scope-shots.mjs`，真 web 静态资源 + 真响应体；旧全扫引擎由 `/api/search/chat` 强制 503 触发前端既有降级腿）：
  - `engine-index-bg-zero.png` — 索引引擎 · 「后台专属词流星」· 范围=全部会话 ⇒ **0 条结果 / 无匹配结果**；
  - `engine-index-main-hit.png` — 索引引擎 · 「主会专属词灯塔」⇒ **1 条结果**（命中主会话兜底关键词行）；
  - `engine-legacy-bg-zero.png` / `engine-legacy-main-hit.png` — 旧全扫引擎同两帧，读数逐字一致。
- 实拍数据 = `SearchScopeRoutesSpec` E1 导出的真响应体：`search-bg.json = {"hits":[],"total":0}`；`search-main.json` 命中主会话 1 条；`sessions-search-scope.json` 只含 2 个 uuid 主会话，`sessions-full.json` 多出 `delegate-/subtask-/node-/subagent-` 四族。
- 成本副效应（顺带读数）：开发机 `~/.stand/sessions` 共 6896 个 `.ui.json`，其中后台族约 6600（node 4071 / delegate 920 / dispatcher 586 / subagent 319 / subtask 36 / dag 若干 / external 8）；旧全扫引擎此前逐会话拉 6800 次 history，收窄后 ~350 次；索引入库面同比例收缩。

## 5. 观察项（非本批判据）

1. **CJK 紧邻 ASCII 的既有装配边界**（本批实测撞到，非本批引入）：`CjkBigram.transform` 在 CJK 段 flush 后直接拼接后续 ASCII ⇒ 索引 token 会变成「末字+ASCII」粘连词（`主会专属词Stargate` → 末 token `属词Stargate`），导致「中文词紧跟英文」的查询两侧不同变换 ⇒ 索引引擎零命中（旧全扫仍命中）。本批夹具因此选纯 CJK 关键词。属 `CjkBigram` 两侧同变换原则的独立缺口，建议另批修（改 `flush()` 补分隔符即可，但要重跑索引侧全量）。
2. **基线红（非本批）**：`SubAgentInboxMirrorSpec` ③-2 的 `emitInjectedUserEvent(` 调用点计数钉 14、实测 16（`AgentFinishTurn` 3 / `AgentProcessing` 5）——本批 `git status` 对这 5 个源文件零改动，故该 red 在基线提交上同样成立（该 spec 注释自陈「每批都漂移、main 裸跑同红」）。留待既有红清欠批重钉。

## 6. 假设与边界（作者可复核）

- **主会话里的注入行进结果**：节点结果 / mail / 后台任务通知等注入行属**主会话自己的历史**（要求③「一条不少」），本批不剔除。若作者要的是「连注入气泡也不搜」，那是另一个切面（消息来源而非会话来源），需另令。
- **媒体 / 文件 / 链接三个分类 tab 同样收窄**：它们与消息 tab 共用同一检索面与同一会话范围（一刀切，避免「同一个面板两种范围」）。后台 agent 产出的 Pop 工件因此不再出现在主 agent 的检索面板里。
- **`external-*` 会话**：前端 `isBgAgentId` 一直把它们算后台族（UI 由 bg-agent 弹窗承载），本批据此同样排除；它们若曾登记进 `_index.json`（历史遗留 8 条），侧边栏仍可见，只是不进搜索面。
- **current 档（scope=当前会话）同样收口**：即便当前打开的是一条后台会话，检索面板也不出它的消息（与「一条不出」字面一致）。

## 7. 关联

- 消费面：`chatSearch.js`（搜索浮钮面板唯一实现，桌面/mobile 共用）、`/api/search/chat`（datalayer-spec 批 C2）、`popMediaIndex.js`（媒体面同源口径）。
- 前序批：[[20261031_223533_stand-dev-plugin-merge]] 无关；搜索家族相关 = datalayer-spec 批（2026-10-30 索引骨架 C1-C6）、聊天记录搜索优化批（2026-10-30 作者令① tool 片退索引）、[[20261101_171500_search-return-pill__delegate-nebflow-9fcc3f05]]（同面板跳转回位）。
