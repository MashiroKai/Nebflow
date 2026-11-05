# 后台智能体面板身份术语审计（只读证据）

对象：聊天输入区头部指示器下拉（子智能体面板，合并载体 `#bg-dropdown`；渲染单点 `src/main/resources/web/js/main/bgAgents.js`）。
读数时间：2026-11-05 09:06–09:20（本地）。全程只读：零源码/样式/文案改动，未重启、未 kill 任何 java/sbt/nebflow 进程，未向 6138 写入任何东西。

## 0. 读数环境与被观察数据的边界

| 数据源（绝对路径） | 只读读数 | 备注 |
|---|---|---|
| `/Users/kaiyu/.stand/delegates/*.json` | 1198 份 DelegateRegistry.Record | 实例在飞并在写盘：同一次会话内两次计数为 1196 → 1198 |
| `/Users/kaiyu/.stand/subagent-tasks/*.json` | 111 份（1 份空数组），子代理条目 **1743 → 1746**（读数窗口内在飞漂移） | 文件名 = `parentSessionId` |
| `/Users/kaiyu/Claude code/Stand/.nebflow/flow-maps/*/flow-map.json` | 401 份，21 个非空域，130 个节点 | 委托作用域 flow 结构（节点真源） |
| `/Users/kaiyu/.stand/**/flow-map.json` | 78 份，**全部 `nodes:{}`** | ~/.stand 侧 flow map 已清空 |
| `/Users/kaiyu/.stand/sessions/*` | 22543 项；前缀计数见 ③ | 会话 id 前缀是面板 kind 判定的第一读源 |
| `GET http://localhost:6138/api/delegates`、`/api/chains` | **未取得**：`403 {"error":"Unauthorized"}` | 该面需浏览器会话 cookie / PAT；本地 `auth.json` 文件 token 对该面无效（`GatewayAuth.Rails` 分支，`src/main/scala/nebflow/gateway/GatewayAuth.scala:43-47`）。故未取实时 `activeAgents` 快照——面板行集的 live 面（`state.sessionBgAgents`）无落盘镜像，下文凡涉及 live 面的计数一律标注来源边界 |

---

## ① 术语对照表（穷举：bgAgents.js 全部字面量词 + `subagents.*` 键族）

判定链：`bgTaskRowHtml` 的 kind 表达式 = 徽标覆写 → `bgFlowKindWord`（前缀）→ `bgKindDisplayWord`（快照 kind）→ `bgAgentKindFromSession`（会话 id 前缀）——`src/main/resources/web/js/main/bgAgents.js:946-947`；顶层行再摘 Delegate/Subagent 残留词 `:952`。

### A. 组头（head level）徽标 —— 只有一种

| zh 显示 | en 显示 | 真实实体（引擎字段/前缀） | 决定点 file:line |
|---|---|---|---|
| `kernel`（逐字，无 locale 键） | `kernel` | `DelegateRegistry.Record.mode = "kernel"`（执行形态；值域注 `src/main/scala/nebflow/core/delegate/DelegateRegistry.scala:156-163`，读口 `modeFaceOf` `:714-718`） | `bgAgents.js:363-367`（`:365` 返回 `execMode` 原文） |
| `dispatcher`（逐字，无 locale 键） | `dispatcher` | `Record.mode = "dispatcher"`（同上） | `bgAgents.js:365` |
| （无徽标） | （无徽标） | `Record.mode = None`（880 条）/ 非 delegate 行 | `bgAgents.js:366` + 顶层摘词 `:952` |

### B. 子行（child level）kind 徽标

| zh 显示 | en 显示 | 真实实体（引擎字段/前缀） | 决定点 file:line |
|---|---|---|---|
| `节点` | `Node` | 会话 id 前缀 `node-`（`NodeEngineContract.SessionPrefix = "node-"`，`src/main/scala/nebflow/core/project/NodeEngineContract.scala:238`；实建 `s"node-<uuid8>"` `src/main/scala/nebflow/core/project/NodeStarter.scala:359,396`）；引擎 `AgentKind.Flow`（`src/main/scala/nebflow/actor/AgentState.scala:14-15`） | `bgAgents.js:483-488`（`:486` → `t('subagents.kindNode')`）；`src/main/resources/web/js/locales/zh-CN.js:905`、`en.js:950` |
| `分发器` | `Dispatcher` | 会话 id 前缀 `dispatcher-`（`ProjectActor.DispatcherSessionPrefix = "dispatcher-"`，`src/main/scala/nebflow/core/project/ProjectActor.scala:553`）；`AgentKind.Flow` | `bgAgents.js:485`；`zh-CN.js:904`、`en.js:949` |
| `Delegate` | `Delegate` | 快照/帧协议 kind `AgentKind.SubTask`（值 `"SubTask"`）显示归一 | `bgAgents.js:471-475`（`:473`） |
| `Delegate` | `Delegate` | G3 store 子条目 `source = "subtask" \| "delegate"` | `bgAgents.js:460-464`（`:462`） |
| `Subagent` | `Subagent` | `source = "subagent"` / 会话前缀 `subagent-` | `bgAgents.js:461` / `:151` |
| `AgentFlow` | `AgentFlow` | 会话 id 前缀 `workflow-`（N1 复名批：Workflow 工具复名 AgentFlow，前缀保留） | `bgAgents.js:152`（※ 今日盘上 `workflow-*` 会话 **0 条**，此路零上屏） |
| `AgentFlow` | `AgentFlow` | `chain.nodes` 里 `kind:"flow-step"` 的 flow 节点（真源 = FlowMapState 节点，`src/main/scala/nebflow/core/project/ChainsReadFace.scala:491-514`，`:505` 写死 `"kind" -> "flow-step"`） | `bgAgents.js:1282`（**硬编码字面量**，不经 locale） |
| `Flow` | `Flow` | 快照 kind 值 `"Flow"`（`AgentKind.Flow`）且未命中上面两条前缀规则 | `bgAgents.js:947`（`bgKindDisplayWord` 原样透传，`zh-CN.js` 无该键） |
| `Flow` | `Flow` | 会话 id 前缀 `dag-` | `bgAgents.js:153` |
| `Ephemeral` | `Ephemeral` | 会话 id 前缀 `ephemeral-` | `bgAgents.js:154`（※ 盘上 0 条） |
| `Team` | `Team` | 会话 id 前缀 `team-` | `bgAgents.js:145`（※ 盘上 0 条） |
| `External` | `External` | 会话 id 前缀 `external-`（`ExternalSessionRunner.SessionPrefix = "external-"`，`src/main/scala/nebflow/core/executor/ExternalSessionRunner.scala:78`） | `bgAgents.js:160` |
| （`source` 原样） | 同 | store 子条目 `source` 为未知值时的透传 | `bgAgents.js:463`（`return String(source || '')`） |
| （无徽标，`Delegate` 被摘） | 同 | Pass 4 延续态合成组头 `info.kind = 'Delegate'` | 合成 `bgAgents.js:676`，摘除 `:952`（顶层恒不显示） |

### C. 来源/孤儿角标（同样承载身份词）

| zh 显示 | en 显示 | 真实实体 | 决定点 file:line |
|---|---|---|---|
| `来自 AgentFlow 节点` | `from an AgentFlow node` | 父会话 id 前缀 `node-`/`dag-`/`dispatcher-` 且名字链解析不出 | `bgAgents.js:888`；`zh-CN.js:895`、`en.js:937` |
| `来自上层子代理` | `from a parent sub-agent` | 父前缀 `subagent-`/`subtask-`/`workflow-` | `bgAgents.js:889`；`zh-CN.js:896`、`en.js:938` |
| `来自已结束的委托` | `from a finished delegate` | 其余（父名不可解析） | `bgAgents.js:892`；`zh-CN.js:894`、`en.js:936` |
| `上层名 · 父名` | 同 | `bgSourceChain` 名字链（`chainLabel`） | `bgAgents.js:882-887`；`src/main/resources/web/js/bgSourceChain.js:231-234` |

### D. 结构/统计词（身份相关，非 kind）

| zh | en | 判据 | file:line |
|---|---|---|---|
| `链运行中` | `Chain running` | delegate 已终态 ∧ 作用域桶链仍在跑 | `bgAgents.js:930-932`；`zh-CN.js:906`、`en.js:951` |
| `子智能体 · {count}` | `Sub-agents · {count}` | `bgPanelModel().total` | `bgAgents.js:1318`；`zh-CN.js:849`、`en.js:896` |
| `展开子任务` / `收起子任务` | `Expand/Collapse subtasks` | 组头 caret | `bgAgents.js:1172`；`zh-CN.js:899-900`、`en.js:942-943` |

**不在本表**：状态词轴（进行中 / 空闲 / 卡死 / 失败 / 已完成 / 候合并 / 待命 / 已冻结，`bgAgents.js:932` → `subagents.status.*`）——它是状态不是身份，另轴治理。

---

## ② 混用举证

### 举证一（主证）：同一个成员节点，两条互斥通路各挂一个词

- **通路 A（live 会话行）**：会话 `node-*` 行 → `bgFlowKindWord` 命中前缀 → `t('subagents.kindNode')` = **「节点」/「Node」**。`bgAgents.js:483-488`（`:486`），`zh-CN.js:905`，`en.js:950`。
- **通路 B（结构投影行）**：`chain.nodes` 里 `kind:"flow-step"` 的节点 → `bgFlowChildHtml` 把徽标**硬编码**成 **`AgentFlow`**。`bgAgents.js:1263-1287`（`:1282`）。
- **两条通路在同一次组装里互斥**：组里已有 chain 子行就整组跳过 flow 结构投影——`bgAgents.js:648-655`（`if (!g.children.some((c) => c.type === 'chain'))`）。也就是说：**同一个成员集合，谁在场谁上屏，词跟着通路走**，不是两个东西。

可复算读数（2026-11-05 09:15）：

- 委托作用域 flow map：`/Users/kaiyu/Claude code/Stand/.nebflow/flow-maps/*/flow-map.json` = 401 份，**21 个非空域 / 130 个节点**（`pending 75 / completed 35 / wiring 7 / cancelled 6 / running 5 / failed 2`）。
- 活例：`flow-maps/delegate-nebflow-1ba3852b/flow-map.json` 6 个节点（`verify=cancelled`、`facts=completed`、`n-7372b36b=wiring`、`unify=running`、`n-c9855cff=wiring`、`report=cancelled`）；该委托的 G3 子条目文件 `/Users/kaiyu/.stand/subagent-tasks/delegate-nebflow-1ba3852b.json` **不存在**（0 条 children）⇒ 该组无 chain 子行的现实前提成立 ⇒ 这 6 行会走通路 B，徽标 = `AgentFlow`。而同一个取向若以 live `node-*` 会话出现在作用域桶里（`bgDelegateScopeChainRows`，`bgAgents.js:504-520`），同样的成员就变成「节点」。
- 反证：`node-*` 会话在盘上 13368 条，`workflow-*` 会话 0 条 ⇒ 今日面板上 `AgentFlow` 一词只可能来自通路 B（flow-step 行）或 `来自 AgentFlow 节点` 角标（`bgAgents.js:888`），不可能来自 `bgAgentKindFromSession:152`。

### 举证二：整体词「agentflow」在数据里存在，但在本面板**没有词位**

- 整体代称的编码：`DelegateRegistry.ModeAgentflow = "agentflow"`（值域顶，`DelegateRegistry.scala:459`；升级单写 `:470-484`，升级挂点 `src/main/scala/nebflow/core/tools/AgentFlowTool.scala:197`）；`DelegateRow.orchestrationMode` 投影 `src/main/resources/web/js/delegateChainStub.js:244`。
- 面板**不渲染整体词**：徽章归位批明确退役 `orchestrationMode` 兜底链（`bgAgents.js:350-362`）；全仓 web 侧 grep `orchestrationMode` 只剩文档注释与投影，**零渲染消费者**。
- 而同词在别的面确是整体：`delegatePanoramaView.js:697` 的 `agentflow ·${steps.length}步`；`taskList.js:125-126`「blocked 是给 agentflow 节点用的」。

### 举证三：`dispatcher` 一词两义（同词异实体）

- `dispatcher-*` **会话种类** → `t('subagents.kindDispatcher')` = 「分发器」/「Dispatcher」（`bgAgents.js:485`，`zh-CN.js:904`）。
- `Record.mode = "dispatcher"` **执行形态** → 面板逐字小写 `dispatcher`（`bgAgents.js:365`，不经 locale）；同一字段在任务列表里却是「分发器」/「Dispatcher」（`src/main/resources/web/js/taskList.js:594-601` → `zh-CN.js:2198-2199` / `en.js:2274-2275`）。

**一句话结论**：「节点」与「AgentFlow」**都是成员词**——同一个 flow 节点的两条互斥画法（live `node-*` 会话行 vs `chain.nodes` flow-step 结构行），不是两个实体；真正被混用的是「agentflow」：它既是整体代称的引擎字段值（`orchestrationMode="agentflow"` / `AgentFlowTool`）又被写死成单个成员行的徽标（`bgAgents.js:1282`），而整体层在后台面板里根本没有词位，于是看起来「有的写节点、有的写 agentflow」。

---

## ③ 真实实例读数（只读，≥2 例；字段逐字）

会话 id 前缀普查（`/Users/kaiyu/.stand/sessions`，22543 项）：`node-` 13368 · `delegate-` 4120 · `dispatcher-` 2827 · `subagent-` 698 · `subtask-` 72 · `dag-` 62 · `external-` 10 · `team-` 0 · `ephemeral-` 0 · `workflow-` 0。

| # | 真实记录（逐字字段） | 面板会打成哪个标签 | 依据 |
|---|---|---|---|
| 1 | `/Users/kaiyu/.stand/delegates/delegate-nebflow-1ba3852b.json`：`id="delegate-nebflow-1ba3852b"`、`name="中途注入时机"`、`taskId="1570"`、`mode="dispatcher"`、`orchestrationMode="single"`、`archivedAt` 缺键（在册）、`reportStatus="standby"` | 顶层组头徽标 = **`dispatcher`**（逐字小写，非「分发器」）；无任何整体词 | `bgDelegateBadgeWord` `bgAgents.js:363-367`，覆写入参 `:1361` |
| 2 | `delegate-nebflow-64a96a05`：`name="输出风格入提示词"`、`taskId="1577"`、`mode="kernel"`、`orchestrationMode="single"`、在册 | 顶层组头徽标 = **`kernel`** | `bgAgents.js:365` |
| 3 | `delegate-nebflow-79bb6698`：`mode="dispatcher"`、`orchestrationMode="single"`、`archivedAt=1793840138340`、`taskId="1491"`；`workReport` 逐字自述「链 `jev-imagegen-align-llm-v3` 一次性注册并开始跑 … 共 8 节点」 | 组头徽标 = **`dispatcher`**；它**确实是**一次 agentflow 的组织者，但整条记录里没有词位显示「agentflow」——且 `orchestrationMode` 仍是 `single`（未被升级到 `agentflow`） | `bgAgents.js:363-367`；`DelegateRegistry.scala:459,470` |
| 4 | `node-d01b7689`（**本腿自己的节点会话**）：`/Users/kaiyu/.stand/sessions/node-d01b7689.json`（364219 B，mtime 11-05 09:05）、`node-d01b7689.ui.json`（376852 B） | 子行徽标 = **「节点」/「Node」** | 前缀 `node-` → `bgAgents.js:486`；`NodeEngineContract.scala:238` |
| 5 | `dispatcher-000bda27`（`/Users/kaiyu/.stand/sessions/dispatcher-000bda27.json` 存在；同类 2827 条） | 子行徽标 = **「分发器」/「Dispatcher」** | 前缀 `dispatcher-` → `bgAgents.js:485`；`ProjectActor.scala:553` |
| 6 | store 子条目（`subagent-tasks` 内逐字）：`{"taskId":"subagent-4bf18f29","parentSessionId":"node-d9c69465","agentName":"subagent","status":"completed","source":"subagent","spawnedAt":1793761358226,"completedAt":1793761465616}` | 徽标 = **`Subagent`**；父是 `node-*` 会话（永不成组头）⇒ 四源全空 ⇒ 顶层野行 + 角标**「来自 AgentFlow 节点」** | `bgChildKindWord:461`；野行判据 `:603-611`；角标 `:888` |
| 7 | `/Users/kaiyu/.stand/delegates/dd-16e1a8d9.json`：`id="dd-16e1a8d9"`、`executor="device:KAI"`、`project="stand-kai"`、`taskId="923"`、`mode=null`、`orchestrationMode=null`、`archivedAt=1793114466194` | 前缀 `dd-` 既不匹配 `delegate-`/`external-`（不能当组头），也无 children 认领 ⇒ 一旦进 live 面即**顶层野行、零徽标** | 组头判据 `:603`；`:952` 摘词；`:366` 空徽标 |

未取得项（如实记）：`GET /api/delegates`、`GET /api/chains` 均 `403 Unauthorized`（见 §0），故「面板此刻 live 行集的逐行 dump」无读数；上表全部依据落盘真源与代码判定点。

---

## ④ 野节点现状普查（按现有面板判定规则）

### 判定式（逐字对位代码）

行 `r` 是**野节点**⇔

1. 它不成组头：`¬( sid.startsWith('delegate-') ∨ sid.startsWith('external-') )` 或 `ownerOf(sid) ≠ ''` —— `bgAgents.js:603`；
2. 且 `ownerOf(sid) === ''` —— `ownerOf = childOwner.get(sid) || liveParentOf.get(sid) || ''`，`bgAgents.js:596`；
   其中 `childOwner` = G3 store 子条目映射（`:581-588`），`liveParentOf` = live/快照行 `parentSessionId`（`:590-595`，父 = 自指或桶根会话则豁免）；
3. ⇒ 进 `leftovers`（`:610`），Pass 2 里 `ownerOf` 为空者原地留下（`:619-620`），最终由 `bgRowPlan` 以 `level:'top'` 收集（`:791-796`）、按 `[终态沉底, 起点戳升序, 会话 id]` 定序（`:818-819`），渲染为无缩进顶层行（`:1389-1391`）。
4. 次生野类（父可解析但父组头不可见）：`ownerOf ≠ ''` 而 `groupByDelegate` 无该父 ⇒ 落 `orphanBy`，出「来自 …」角标（`bgAgents.js:624-627`）。

### 逐类计数（含分母；读数窗口 2026-11-05 09:10-09:20）

分母：G3 store 子条目 **N = 1746**（111 份文件，1 份为空数组）。**读数窗口内 N 由 1743 漂到 1746**（实例在飞并在写盘），下面按最后一读 1746 复算，四类互斥且穷尽（249 + 1388 + 109 = 1746）。

| 类 | 判定（两句合取） | 条数 | 占 N | 面板后果 |
|---|---|---|---|---|
| **C1 正常子行** | 父 ∈ 在册 delegate ∧ 子自身非组头前缀 | **249**（78 个父，全 `source="subagent"`） | 14.3% | 挂到父组头下缩进一行（`bgAgents.js:633-641`） |
| **C2 自成组头（非野）** | 父 ∉ 在册 ∧ 子前缀 ∈ {`delegate-`,`external-`} | **1388** | 79.5% | 自己当组头（`:603`），父是根会话时不认领（root-parent 豁免 `:593-594`）：`d7c3cb54…`(Nebula) 1073 + `5cc7590a…` 313 + `delegate-Nebula-*` 2 |
| **C3 野节点（父不可解析）** | 父 ∉ 在册 ∧ 子前缀 ∉ {`delegate-`,`external-`} | **109** | **6.2%** | 无人认领 ⇒ `leftovers`（`:610`）⇒ `level:'top'` 顶层行（`:791-796, 1389-1391`）+「来自 …」角标（`:624-627, 888-893`） |
| └ 父 = `node-*` 会话 | 81 条 / **22 个不同 node 父**（`node-4e5b22e3` 10、`node-a4023eb1` 8、其余 3-5 条/父） | | | 子全为 `subagent-` |
| └ 父 = 其它 uuid 会话（非 delegate、非 node） | 28 条 / 6 个父（`52ad5890…` 21、`73527280…` 2、`676780c5…` 2、`aa15dbcc…` 1、`f4a7e151…` 1、`aac1c33a…` 1） | | | 子为 `subagent-`/`subtask-` |

109 条野节点的状态分布：`completed 101` / `failed 6` / `cancelled 2` ⇒ **全部终态，无在跑**（野行不掩盖活工作，但会长期占屏）。

补充分母（另一面，在册记录）：

| 读数 | 值 |
|---|---|
| delegate 记录总数 | **1198**（`delegate-*` 1166 · `external-*` 8 · `dd-*` 23 · `probe-roundtrip-id` 1） |
| 其中 id 前缀**永不成组头**（`dd-*`/`probe-*`） | **24** 条（23 条已归档 + 1 条探测残留） ⇒ 一旦进 live 面即顶层野行 |
| `parentSessionId` 非空 | **1198 / 1198**（100%） |
| `parentSessionId` 指向 delegate 在册 id | **0** 条（父分布：根会话 `d7c3cb54…` 1089、`c444ce38…` 105、`probe` 1、`bgidle-parent-*` 3） ⇒ 委托的父恒为非 delegate 会话 |
| `mode` 分布 | `None` 880 · `kernel` 257 · `dispatcher` 61 |
| `orchestrationMode` 分布 | `single` 1073 · `subagent-star` 79 · `None` 27 · **`agentflow` 19** |
| 在册（未归档） | 25 条（`mode`: None 11 / kernel 8 / dispatcher 6） |
| 委托作用域 flow 节点 | 130 个（21 个非空域）；~/.stand 侧 78 份 flow map **全空** |

### 能不能回收/关闭（判据）

- **可安全不展示（可折叠、可灰化）的判据**（三条同时成立）：① `parentSessionId` 不在任何在册 delegate（`ownerOf===''`）；② 子条目 `status ∈ {completed, failed, cancelled}`（终态）；③ 该 `taskId` 在 `/Users/kaiyu/.stand/sessions/` 下无对应会话文件或会话已成历史窗（无 live 帧）。今日 109 条**全部满足 ①②**。
- **不可判定也要说的**：③ 的 live 面读数未取得（`activeAgents` 快照需 WS/鉴权，§0）。因此本腿**只给判据，不做回收动作**——面板侧结论：**只展示、不自动处置**（禁止自动删记录/自动归档：控制面归引擎 `DelegateRegistry.archive`，且「零删除纪律」见 `DelegateRegistry.scala:121-127`）。

---

## ⑤ 统一口径提案（≤1 页，供后三腿照做）

**语义基线（作者已定）**：一次编排 = 一个 **agentflow**；agentflow 的成员 = **节点**，节点分层、下一层是上一层的子。**kernel / dispatcher 逐字保留，不改写、不翻译。**

1. **整体行 = agentflow 行 = 现有 delegate 组头行**（`head` level，`data-bg-row-level="head"`，`bgAgents.js:1164-1166`）。
   - 词：**一个整体词位，只写 `agentflow`**（如需计数：`agentflow · N 节点`）。**不再把 `kernel`/`dispatcher` 放在身份徽标位**——它们是执行形态属性，不是身份。
2. **成员行 = 节点行**（`child` level）。词：**统一用「节点」/「Node」**；`bgAgents.js:1282` 的硬编码 `AgentFlow` 必须退役为「节点」，即通路 A 与通路 B 共用一个词。
   - 「AgentFlow」此后**只作整体词**（且与作者口径 `agentflow` 同一概念，大小写按现有两套惯例：引擎字段值小写 `agentflow`、UI 词首字母 `AgentFlow` 需二选一，提案选**与作者原话一致的小写 `agentflow`**，避免再造第三个词面）。
3. **归属靠结构表达，不靠解释句**：
   - 视觉：缩进 × 深度。现状只有一档子行缩进（`.bg-task-row.bg-task-row-child { padding-left: var(--ds-space-4); border-left: 2px solid var(--color-frame-border) }`，`src/main/resources/web/css/chat.css:355-358`）+ 三档行级（`BG_ROW_LEVEL_RANK = { head:0, child:1, top:2 }`，`bgAgents.js:723`）——**多级缩进当前不存在**，需按深度扩展（depth 1..n，缩进量 = 单档 × depth，竖线续接）。
   - 机器可读锚（禁解释句）：沿用并加宽既有属性——`data-bg-row-level`（head/child/top，`:1165-1166`）+ 新增 `data-bg-depth="n"` 与 `data-bg-parent="<父会话 id>"`（现有 `data-bg-caret` / `data-node-session-id` 已带会话 id，父 id 是唯一缺件）。
4. **野节点（父不可解析）**：不缩进、置顶、保留现有「来自 …」角标词（`bgAgents.js:888-893`），不参与 agentflow 的计数分子；**只展示、不自动处置**（判据见 ④）。
5. **kernel / dispatcher 的落位**：仍是 `DelegateRegistry.Record.mode` 的两个值，**逐字上屏**（`kernel`、`dispatcher`，不经 locale，保持 `bgAgents.js:365` 现状）；若要与任务列表的「内核/分发器」统一，只能二选一且**不得改写这两个词本身**——建议面板与任务列表都落回逐字 `kernel`/`dispatcher`，或两处都用同一套 locale 键，不新增第三套。`dispatcher` 作为**会话种类词**（`dispatcher-*` → 「分发器」）与本形态词在 UI 上必须靠位置区分（种类在身份徽标位、形态在属性位），不得同槽并列。
6. **不在本提案内**：状态词轴（进行中/空闲/待命/已冻结/候合并…）、`Flow`/`Team`/`Ephemeral`/`External` 这些历史类型残留词的存废——另立条款。

---

## 附：本文件涉及的判定点索引

| file:line | 作用 |
|---|---|
| `web/js/main/bgAgents.js:143-162` | `bgAgentKindFromSession`（会话前缀 → kind 词） |
| `web/js/main/bgAgents.js:363-367` | `bgDelegateBadgeWord`（顶层只认 `row.mode`） |
| `web/js/main/bgAgents.js:460-464` | `bgChildKindWord`（store `source` → 词） |
| `web/js/main/bgAgents.js:471-475` | `bgKindDisplayWord`（`SubTask` → `Delegate`） |
| `web/js/main/bgAgents.js:483-488` | `bgFlowKindWord`（`dispatcher-`→分发器 / `node-`→节点） |
| `web/js/main/bgAgents.js:577-695` | `bgDelegateGroups`（四源认领 + leftover + orphanBy + 合成组头） |
| `web/js/main/bgAgents.js:648-655` | 通路互斥点（chain 子行在场 ⇒ 跳过 flow 结构投影） |
| `web/js/main/bgAgents.js:723,775-832` | 行级三档 / `bgRowPlan` 收敛与定序 |
| `web/js/main/bgAgents.js:946-953` | 行徽标判定链 + 顶层摘词 |
| `web/js/main/bgAgents.js:1263-1287` | `bgFlowChildHtml`（`:1282` 硬编码 `AgentFlow`） |
| `web/js/main/bgAgents.js:882-897` | 孤儿来源角标（含「来自 AgentFlow 节点」） |
| `web/js/locales/zh-CN.js:895,904-905` / `en.js:937,949-950` | kindNode / kindDispatcher / fromFlowNode |
| `web/js/taskList.js:594-601` + `zh-CN.js:2198-2199` / `en.js:2274-2275` | 同一 `mode` 字段的另一套显示词（内核/分发器） |
| `web/css/chat.css:348-359` | 组头/子行样式（子行单档缩进） |
| `scala/nebflow/actor/AgentState.scala:14-15` | `AgentKind` 值域 |
| `scala/nebflow/core/delegate/DelegateRegistry.scala:156-163,453-484,714-718` | `mode`（kernel/dispatcher）/ `orchestrationMode` 值域与升级 / `modeFaceOf` |
| `scala/nebflow/core/project/ChainsReadFace.scala:491-514` | `chain.nodes` 的 `kind:"flow-step"` 真源 |
| `scala/nebflow/core/project/NodeEngineContract.scala:238` | `node-` 前缀权威 |
| `scala/nebflow/core/project/ProjectActor.scala:553` | `dispatcher-` 前缀权威 |
| `scala/nebflow/core/executor/ExternalSessionRunner.scala:78` | `external-` 前缀权威 |
| `scala/nebflow/core/tools/AgentFlowTool.scala:197` | `orchestrationMode → agentflow` 升级挂点 |
