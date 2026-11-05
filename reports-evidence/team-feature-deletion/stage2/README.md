# 团队功能删除 · 阶段 2（摘挂载入口）证据

> 工作区 `/Users/kaiyu/Claude code/Stand/.nebflow/worktrees/delegate-nebflow-f97716ed`
> 分支 `delegate/delegate-nebflow-f97716ed` · 基线 sha `2af513702c81ff0522c952fb7ea7a43b59b578b2`（上游 stage-1 commit `63b1e0e00`）
> 依据件：`/Users/kaiyu/.stand/projects/stand/.stand/docs/flow-tree-actor-deletion-blocked-live-consumers__20261104_220921__delegate-nebflow-5ea65c9e.md` §2.1-2.3
> 本阶段 = **摘四条挂载入口**，独立编译 / 独立提交 / 独立可回滚。**零类型本体删除**（`FlowTreeActor`、`object TeamSessionRegistry`、`TreeCommand` 全在，阶段 3 才动）；**零 baseline 抬高**；**零 kill/signal**（生产实例 PID 78176 未被触碰）。

---

## 0. 一句话结论

四条入口按「活消费者是否只剩团队」逐条判：

| 项 | 判定 | 理由一句话 |
|---|---|---|
| **A. `Load` 工具 → `MountTeam`** | **保留工具 / 删 team 分支 / 改写描述** | `Load` 还有 `type:"flow"` 的非团队用途（只校验 DAG，不挂载）⇒ 按 brief 的第一分支处理 |
| **B. `GET /api/teams/mounted` + `awaitRestore` 腿** | **全删**（路由 + 响应体构造 + 恢复等待腿 6 成员） | 前端/CLI/BE 零调用点；数据源只由 FlowTreeActor 填充；等待信号的唯一生产者与唯一消费者同时消失 |
| **C. `POST /api/team/rules/:name` 写后热重载** | **只删热重载段 / 端点保留** | 端点写的是盘上真配置文件 `rules.md`（有 3 处非树读面 + 活的前端写调用方），只有那条 `ReloadDefinition` 腿是团队树专属 |
| **D. WS `cancelFlow` 帧** | **帧保留 / 删团队 pipeline 分支** | 同帧的 `instanceId` 分支取消的是 flow-map/DAG 实例（`RunningFlowRegistry`），另一套且仍活 |

---

## A. `Load` 工具：保留工具，摘 team 分支，改写描述

### 证据（改前）
- 分支：`src/main/scala/nebflow/core/tools/LoadTool.scala:63-66`：`case "team" => loadTeam(name, ctx)` / `case "flow" => loadFlow(name)`。
- 挂载腿：`LoadTool.scala:133-135` `treeRef <- FlowTreeRegistry.getOrCreate(ctx)` → `treeRef ! TreeCommand.MountTeam(team)`。
- 注册行：`src/main/scala/nebflow/core/tools/registry.scala:119` `"Load" -> LoadTool`（无条件注册）。
- **非团队用途确实存在**：`loadFlow :150-179` + `validateFlowDag :181-207` —— 读 `flows/<name>.json`，走 `FlowDagCompiler.validate`（E-0xx..E-3xx + agent 存在性），**只校验、不挂载任何东西**，与团队无关。

### 判定依据（"还有没有别的用途"）
1. `grep -rn '"Load"' src/` ⇒ 3 命中：`LoadTool.scala:15`（声明）、`registry.scala:119`（注册）、`AgentConvergenceSpec.scala:176`（该名在**被显式移除工具的禁漏集**里）。
2. `grep -rn '"Load"' /Users/kaiyu/.stand/agents/` ⇒ **0 命中**（没有任何 agent 声明 `Load`）。
3. 但"没人声明" ≠ "工具无用途"：flow 校验分支是独立能力（校验 DAG 定义、报悬空路由/缺 agent）。删整个工具会连带删掉一个非团队能力 ⇒ **按 brief「有 → 只摘 team 分支并改写工具描述」**。

### 实际改动
- 删 `loadTeam`（原 :72-101）、`teamValidationNames`（原 :110-114）、`validateAndMount`（原 :116-144）——三者只服务 team 分支。
- `call` 的 match：`case "team"` 移除，未知类型提示改 `Use 'flow'.`。
- `description` / `inputSchema`：去掉 teams 段落，`enum` 由 `["team","flow"]` → `["flow"]`。
- 去掉随之失效的 import（`TeamDef`、`FlowTreeRegistry`、`TreeCommand`、`NebflowLogger`）；`registry.scala:119` **不动**（工具仍在）。
- 文件头加批注指向本节。

### 连带后果（如实记录）
- `EntityLoader.validateTeam` / `listTeamAgents` 本体不动（`listTeamAgents` 仍被 `ContextRefresher.scala:636` 使用，活的）；`validateTeam` 此后**失去唯一 main 调用方**，与团队实体一起留待阶段 3 判定。
- `TreeCommand.MountTeam` 此后**零生产者**（类型本体不动，阶段 3 随 `TreeCommand` 处置）。

---

## B. `GET /api/teams/mounted` + 恢复等待腿：全删

### 证据（改前）
- 路由：`src/main/scala/nebflow/gateway/PresenceRoutes.scala:591-601`：`awaitRestore(3000L)` → `buildMountedTeamsJson()` → `Ok(Json.obj("teams" -> …))`。
- 响应体：`buildMountedTeamsJson` 原 :2701-2745（`TeamSessionRegistry.listMountedTeams` + `listTeams` + `isBusy` + `FileTaskStore.list(teamScopeKey)`）。
- 等待腿：`FlowTreeRegistry.scala:21-69`：`restoreDone` / `restoreStarted` / `signalRestoreComplete`（生产者 = `FlowTreeActor.scala:313` setup）/ `markRestoreStarted`（生产者 = `FlowTreeRegistry.getOrCreate:101`）/ `awaitRestore` / `awaitRestoreUsing`。文件注释自述 "whose only producer is a FlowTreeActor"。

### 判定依据
1. **消费者零命中**：`grep -rn "teams/mounted" src/main/resources/web/` ⇒ 仅 `js/ws.js:1590` **一条历史注释**（无 `fetch(`）；CLI/BE 侧 `grep -rn "/api/teams" src/main/scala` 除该路由自身与注释外零调用（`flowViewers.js` 的三条是 `mail-queue` / `mailbox` / `def`，与本路由不同面）。
2. **数据已恒空**：`TeamSessionRegistry.listMountedTeams` 的唯一填充者是 `FlowTreeActor`（`handleMountTeam` / `restoreTeams`），阶段 1 断掉每会话无条件建树后没有新树 ⇒ 该表稳定 `{}` ⇒ 路由只剩「等一个永不到来的信号 → `{"teams":[]}`」的空壳（阶段 1 报告 §⑤ 已实测 `served=true` 但内容空）。
3. **等待腿双向归零**：唯一消费者 = 该路由（本次删掉），唯一生产者 = `FlowTreeActor.setup`（保留类型但不再需要这条握手）⇒ 按 brief「无人再调用就删」。
4. 删后**零编译悬空**：`grep -rn "awaitRestore\|markRestoreStarted\|signalRestoreComplete\|restoreStarted\|restoreDone" src/main/scala` 只剩注释文本；实测编译 exit 0（§F）。

### 实际改动
- 删路由分支（`PresenceRoutes.scala` 原位留墓碑注，说明退役理由与本批文件指针）。
- 删 `buildMountedTeamsJson`（原 :2701-2745，原位留墓碑注）。
- 删 `FlowTreeRegistry` 的 6 个成员 + 两处调用点（`FlowTreeActor.scala:313` 的 `signalRestoreComplete`、`getOrCreate` 的 `markRestoreStarted *> safetyModeIO` → `safetyModeIO`），并删 `FlowTreeRegistry.treesRef`（其唯一消费者是 C 项那条已删的热重载腿 ⇒ 零调用点），顺带删掉因此失去用途的 `scala.concurrent.duration.*` import。
- **不动**：`TeamSessionRegistry.listMountedTeams` / `isBusy` 本体（类型本体阶段 3）；`getOrCreate` / `register` / `unregister` / `get` / `clear` 本体（`removeRootAgent` 仍调 `unregister`）。

---

## C. `POST /api/team/rules/:name` 热重载：只删热重载段，端点保留

### 与 brief 表述的两处出入（如实记录）
1. 方法不是 **PUT** 而是 **POST**；路径不是 `/api/teams/:name/rules` 而是 **`/api/team/rules/:name`**（`PresenceRoutes.scala:2108-2133`）。读面对应 `GET /api/team/rules/:name`（:2100-2106）。
2. 热重载脚本文本与 brief 出入不大：`:2124 treesRef.get` → `:2126 ReloadDefinition(teamName)`，已按此定位。

### 判定依据（"端点是否只服务团队"）
- 端点确实**只处理团队规则**（`teams/<name>/rules.md`），但**它写的是盘上真配置文件，不是运行时树**：
  - 读面 3 处非树：`ContextRefresher.scala:638`（团队 agent 上下文刷新时注入 rules）、`MailTool.scala:2299`（发信时附团队规则）、`PresenceRoutes.scala:2103`（GET 读面本身）。
  - 写面有**活的前端调用方**：`src/main/resources/web/js/flowViewers.js:174`（POST 保存）+ `:158`（GET 读取）—— 删端点 = 前端已知破面，而本批硬约束是不碰前端。
- 因此按 brief 的第二分支：**只删「写后热重载」这一段**（`treesRef` 遍历 → `ReloadDefinition`），端点与 `rules.md` 落盘保留，原位留理由注。

### 实际改动
- `PresenceRoutes.scala:2120-2126` → 改为一段判定注 + 直接 `Ok(saved=true)`；import 面不动。
- **保留**：`GET /api/team/rules/:name`、`POST /api/team/rules/:name`、`EntityLoader.loadTeamRules`、`TreeCommand.ReloadDefinition` 类型本体（阶段 3）。

---

## D. WS `cancelFlow` 帧：帧保留，删团队 pipeline 分支

### 证据（改前）
`src/main/scala/nebflow/gateway/WsSystemHandlers.scala:303-328` `handleCancelFlow`：
- 分支①：`instanceId.nonEmpty` → `RunningFlowRegistry.cancel(instanceId)`，注释写明 "Cancel a DAG flow instance directly"。
- 分支②：`flowName.nonEmpty && cfSessionId.nonEmpty` → `FlowTreeRegistry.get(cfSessionId)` → `TreeCommand.CancelPipeline(flowName)`（注释 "Cancel a team pipeline flow via FlowTreeActor"）。

### 判定依据
1. **同帧两条语义**：分支①是 flow-map/DAG 取消（`FlowDagExecutor` + `RunningFlowRegistry`，另一套仍在用），分支②才是团队 pipeline。⇒ 帧不能删。
2. **前端发送方复核**：`grep -rn "cancelFlow" src/main/resources/web/` ⇒ **0 命中**（无前端发送方）。仓内唯一其他提及在 `AgentControlTool.scala:179` 的指引文案（"flow-level cancellation ... cancelFlow(instanceId) / RunningFlowRegistry"）—— 指向的正是分支①。⇒ 分支②零调用方。
3. 故删分支②、保留帧与分支①；分支②落到一条带原因 `warn`（不静默吞，便于下一批 grep 日志发现残留调用方）。

### 实际改动
- `WsSystemHandlers.scala:303-328`：删 `FlowTreeRegistry.get` + `CancelPipeline` 发送；改为判定注 + 具名 `warn`（含 flowName 与 sessionId）。
- **保留**：`"cancelFlow" -> handleCancelFlow` 注册（:45）、`TreeCommand.CancelPipeline` 类型本体与 `FlowTreeActor` 的 `receive` 分支（阶段 3）。

---

## E. 改动清单（file:line，均为改后行号）

| 文件 | 改动 |
|---|---|
| `src/main/scala/nebflow/core/tools/LoadTool.scala` | 删 team 分支三函数（原 :72-144）+ 改 `call` :62-70 / `description` :27-34 / `inputSchema` :36-52 / import :3-9；文件头批注 :11-20 |
| `src/main/scala/nebflow/gateway/PresenceRoutes.scala` | 删路由（原 :591-601 → 现 :591-600 墓碑注）；改 :602-607（live siblings 名单）；删热重载段（原 :2120-2126 → 现 :2124-2132 理由注）；删 `buildMountedTeamsJson`（原 :2701-2745 → 现 :2701-2706 墓碑注） |
| `src/main/scala/nebflow/core/flow/FlowTreeRegistry.scala` | 删恢复等待腿 6 成员 + `treesRef`（原 :21-69）；`getOrCreate` 去掉 `markRestoreStarted`（现 :51-53）；删 duration import；头注 :10-21 |
| `src/main/scala/nebflow/core/flow/FlowTreeActor.scala` | 删 `_ <- FlowTreeRegistry.signalRestoreComplete`（原 :313），原位注解说明（现 :311-316） |
| `src/main/scala/nebflow/gateway/WsSystemHandlers.scala` | `handleCancelFlow`（现 :302-328）删团队分支（原 :319-326），改为 :321-328 的具名 warn |
| `src/main/scala/nebflow/gateway/WebSocketRoutes.scala` | 仅批注订正（:490-494 阶段 2 补充、:1366、:3256-3258 指向已退役路由） |
| `src/main/scala/nebflow/agent/AgentFinishTurn.scala` | 仅批注订正（:818-824：busyMap 现由 `filterActiveAgents` 消费） |
| `src/test/scala/nebflow/gateway/DeviceFaceHardeningRoutesSpec.scala` | `/teams/mounted` 从 `stillServed` 移入 `retired`（:242 新增一行 + 理由注） |
| `src/test/scala/nebflow/gateway/ActiveAgentsFilterSpec.scala` | 仅类注释措辞（:18-20） |
| `src/test/scala/nebflow/shared/DataRootPlaceholderSpec.scala` | :103-107 断言由 team 路径改钉 `flows/<name>.json` |
| `src/test/scala/nebflow/core/flow/AwaitRestoreSpec.scala` | **整文件删除**（6 用例全部测被删的等待腿） |
| `src/test/scala/nebflow/core/tools/LoadToolTeamValidationSpec.scala` | **整文件删除**（6 用例全部测被删的 team 分支/`teamValidationNames`） |

`git diff --stat`（含本 README）：**13 文件 +309 / −451**；去掉本 README（+210）即代码/测试面 **12 文件 +99 / −451**。无任何 baseline 类文件（`tests/type-baseline.json`、`tests/visual/baseline/*`、`scripts/component-contract-baseline.json`、`process-artifacts-allowlist.txt` 全未出现在 diff 中）。

---

## F. 编译 / 测试读数（sbt 串行闸）

`ps aux | grep -c "[s]bt"` 改前读数 = **5**（生产实例 78176 在列，未触碰）。

一条命令跑到底（命令原文）：

```bash
cd "/Users/kaiyu/Claude code/Stand/.nebflow/worktrees/delegate-nebflow-f97716ed" && \
sbt -batch "compile" "testOnly nebflow.gateway.DeviceFaceHardeningRoutesSpec \
  nebflow.gateway.ActiveAgentsFilterSpec nebflow.core.flow.FlowTreeRegistrySpec \
  nebflow.shared.DataRootPlaceholderSpec"
```

读数（exit 0，总 26 s）：

```
[sbt-gate] gate acquired after 0s
[info] compiling 7 Scala sources … [info] done compiling        ← 主源，零 scalac warning（-Xfatal-warnings 生效）
[info] compiling 3 Scala sources to …/test-classes … [info] done compiling
[RTE-R0] POSITIVE CONTROL GET /agents ⇒ served=true
[RTE-R1] retired GET /api/teams/mounted ⇒ served=false           ← 本阶段核心新事实
[RTE-R2] live GET /api/team/rules/:name ⇒ served=true            ← C 项保留判定被实测钉住
[info] Passed: Total 28, Failed 0, Errors 0, Passed 28
```

**改前读数**：同一棵树的上游 stage-1 收尾实测 `compile` exit 0（749 sources，见 `stage1/README.md` §2）；本阶段未另跑一次改前编译（基线 sha `63b1e0e00` 即改前状态，未改动即绿）。

### G. grep 读数（改前 = `git grep 63b1e0e`；改后 = 工作树；范围 `src/main/scala src/test/scala`）

| 判据 | 全行（改前→改后） | 代码行（去注释，改前→改后） |
|---|---|---|
| `/api/teams` | 16 → 13 | 6 → 6（余 6 条全是测试的退役/存活断言清单，非调用方） |
| `MountTeam` | 6 → 6 | **5 → 4**（生产腿 `LoadTool:135` 归零，余 4 为类型/接收/注释） |
| `CancelPipeline` | 3 → 3 | **3 → 2**（`WsSystemHandlers` 生产腿归零，余 2 为类型 + 接收分支） |
| `ReloadDefinition` | 3 → 3 | **3 → 2**（`PresenceRoutes` 生产腿归零，余 2 为类型 + 接收分支） |
| `awaitRestore` | 16 → 4 | **11 → 0**（全腿清零，余 4 为墓碑注文本） |
| `FlowTreeRegistry`（附参） | — | 48 → 36 |

> 全行数不降反"持平"的原因：本批按纪律给每处删除留了墓碑注，注释行计入全行数；代码行计数才是删面读数。

### 测试处置逐条说明（brief 点名 3 个 + 任务外新增 2 个）

1. **`DeviceFaceHardeningRoutesSpec`**（:227/228/236/250/251/252 清单）：`("GET /api/teams/mounted", get("/teams/mounted"))` 由 `stillServed` **移入 `retired`**（:242），依「已下线断言」处理，未删 spec（它还承载 11-route takedown 其余 6 条 + 5 条 A1/A2 用例）。实测 `RTE-R1 served=false` 且 `RTE-R2` 其余 live 路由零连带。
2. **`AwaitRestoreSpec`**：:11/:14/:15/:22/:86/:87 提到的 `/api/teams/mounted` 与被测核心 `awaitRestoreUsing` **两者都已不存在** ⇒ 6 个用例无一可留（它们全部 `FlowTreeRegistry.awaitRestoreUsing(...)`），**整文件删除**；不是"因难改而删"，是"被测对象已被本批移除，保留即测幽灵 API"。
3. **`FlowTreeRegistrySpec`**：**未改一行**。实读该文件（5 用例）只测 `register/get/unregister/clear`，与 `awaitRestore` 无关 ⇒ brief 预判的"会失效"不成立；实测 5/5 绿，注册表本体仍在（阶段 3 才动）。
4. **`ActiveAgentsFilterSpec:19`**：**功能不受影响**（busyMap 的活消费者是 `WebSocketRoutes.filterActiveAgents`，即 bg-agent 下拉，路由删除不影响它）；只把注释里"`/api/teams/mounted` uses"改成"the (stage-2 retired) … used"，避免文档指向已下线路由。实测 7/7 绿。
5. **【任务外新增】`LoadToolTeamValidationSpec`**：6 用例全部调用 `LoadTool.teamValidationNames` / `LoadTool.call(type="team")`，随 A 项一并失去被测对象 ⇒ **整文件删除**。底层 `EntityLoader.validateTeam` / `listTeamAgents` 的域语义本 spec 对象已不在，若阶段 3 保留团队实体，应另立 `EntityLoader` 面 spec（本批不代建）。
6. **【任务外新增】`DataRootPlaceholderSpec:104`**：断言 "Load 描述必须教 `teams/<name>/team.json`" 随 A 项描述改写失效 ⇒ 改钉保留的 `flows/<name>.json`（同样是运行期渲染值，仍是"desc 走 `dataRootRenderValue` 而非冻结 val"的等价证据）。实测该 spec 9/9 绿。

---

## H. 刻意保留清单（保守 = 不删）

| 保留项 | 锚点 | 为什么不动 |
|---|---|---|
| `FlowTreeActor` 类型本体 + `receive` 三命令分支 | `FlowTreeActor.scala:280+`、`:346/:349/:352` | brief 明令「不动，阶段 3 才动」 |
| `object TeamSessionRegistry`（含 `listMountedTeams`/`isBusy`） | `FlowTreeActor.scala:36-278` | 同上；`src/main` 91 处引用（Mail 寻址底座） |
| `FlowTreeRegistry.getOrCreate` / `register` / `get` / `unregister` / `clear` | `FlowTreeRegistry.scala:20-64` | `unregister` 仍被 `WebSocketRoutes.removeRootAgent` 调用；`getOrCreate` 随类型阶段 3 处置 |
| `TreeCommand`（`MountTeam`/`ReloadDefinition`/`CancelPipeline`） | `FlowTreeTypes.scala:22-29` | 类型本体，阶段 3 |
| `POST/GET /api/team/rules/:name` | `PresenceRoutes.scala:2100-2133` | §C：写盘上真配置 + 活前端调用方 |
| `cancelFlow` 帧 + `instanceId` 分支 | `WsSystemHandlers.scala:45`、`:303-317` | §D：DAG/flow-map 取消仍活 |
| `EntityLoader.validateTeam` / `listTeamAgents` / `loadTeamRules` | `EntityLoader.scala:61/239/…` | 实体层，非挂载面；`listTeamAgents`/`loadTeamRules` 仍有活消费者 |
| 前端 `src/main/resources/web/` | 全目录 | 硬约束（不碰视觉面）；`ws.js:1590` 那条 `/api/teams/mounted` 历史注释**未改**（避免触发资源版本/视觉回归面），已由本 README 记账 |
| `MountedFlowStore` / 盘上 `sessions/*/flows.json` / `teams/*` | — | 数据面，非本批对象 |

## I. 阶段 3 接力项（本批留下的残余入口）

1. `TreeCommand.MountTeam` / `CancelPipeline` / `ReloadDefinition` **零生产者**（三处生产腿已全摘），接收分支与类型留待阶段 3 随 `FlowTreeActor` 一并处置。
2. `FlowTreeRegistry.getOrCreate` **零调用方**（`LoadTool` 腿已断）—— 阶段 1 报告的"惰性建树残余入口"至此**已断**：本批之后**没有任何 main 代码能再建一棵团队树**（这是本阶段最重要的事实读数）。
3. `TeamSessionRegistry.listMountedTeams` / `isBusy`：`listMountedTeams` 失去唯一消费者；`isBusy` 仍被 `filterActiveAgents`（agent 下拉）使用 ⇒ 阶段 3 删该类型时须保留 busy 语义或迁址。
4. `EntityLoader.validateTeam`：失去唯一 main 调用方。
5. `FlowTreeActor.setup` 的 `restoreTeams` 腿仍在（惰性建树已无入口，实际不会跑），阶段 3 随类删除。

---

## J. 合规自检

- [x] 单 commit、单分支（`delegate/delegate-nebflow-f97716ed`），main tip 仍 `2af513702`，无 main 提交。
- [x] `git diff --stat` 无 `tests/type-baseline.json`、`tests/visual/baseline/*`、`scripts/component-contract-baseline.json`、`process-artifacts-allowlist.txt` —— **未抬任何 baseline**。
- [x] 无 `git reset --hard` / `checkout .` / `clean -f`；文件删除走 `git rm`（可回滚）。
- [x] 零 `kill`/`signal`；仅只读 `ps` 记录读数（PID 78176 生产实例未被触碰）。
- [x] 未碰前端视觉面（`src/main/resources/web/` 零改动）。
- [x] 未动 `FlowTreeActor` / `object TeamSessionRegistry` 类型本体、`FlowDagExecutor` / flow-map / chain / `ChainStore` / `MergeQueue` / `MountedFlowStore`。
- [x] 编译 exit 0（`-Xfatal-warnings` 生效 ⇒ 零 scalac warning）；受影响 spec 28/28 绿。
