# 20260826 权限系统 auto-all 泺漏排查：qa-backend Write 撞墙 + 权限卡不可见

> 阶段文档（诊断完成即冻结）。排查范围：2026-08-26 晚 21:47 重启后实例（pid 99771）。
> 方法：纯静态代码追踪 + 运行日志（`~/.nebflow/logs/nebflow.log`）+ 持久化（`sessions/_index.json`、会话消息/ui.json、scheduled-tasks）交叉验证。未改任何代码。

---

## 0. 结论摘要（TL;DR）

**qa-backend 撞的不是用户的墙，是一个 3 分钟寿命的 e2e 临时会话留下的墙。**

21:56 qa-frontend 跑 B6-A9 e2e，用 `POST /api/sessions` 建了临时会话 `9f727822`（QA-B6-A9）。该会话连入时触发了两个副作用：

1. **权限桶被播种为 confirm-edits**（REST 建会话默认值，`SessionStore.createSession` L660）；
2. 其 FlowTree `restoreTeams` **自动挂载了磁盘上全部 team**，把全局单例 `TeamSessionRegistry.parentSessionMap[nebflow-project]` 从用户真实会话 `5cc7590a` 改写为 `9f727822`（`FlowTreeActor.scala` L578-588 auto-mount 分支 + L89-90）。

21:59 e2e 清理删掉了索引项，但**内存里的桶、parentMap、Hub root 注册全部残留**。22:11 qa-backend 首次激活时 rootSid 经 parentMap 解析到僵尸 `9f727822` → 桶命中 ConfirmEdits（它自己 meta 里的 auto-all 被 `MailTool` L979-981 的 policyOpt 优先级覆盖）→ Write 判为 Ask → 卡渲染进僵尸会话（无人在看）→ 5 分钟超时自动拒，循环 4 次。

**用户的 auto-all 从来不是全局设置**——它只存在于会话 `5cc7590a` 的 meta（`_index.json` 实证），系统根本没有全局安全模式这个概念。三个缺陷叠加：**R1 临时会话可劫持 team 归属（全局 last-writer-wins）→ R2 权限桶缺失/错位时静默回落孤儿 default → R3 卡只渲染到 rootSid 会话，root 不可见时无人能答**。

**事故仍在连锁**：Manager 的 [RESULT] 被路由进僵尸会话唤醒了里面的 Nebula，它已向 team 派活、并在 22:47 设了一个 **~00:30 触发的实例重启定时任务**（见 §6 立即行动项）。

---

## 1. 事故链路（时间线 + 日志证据）

日志均为 `~/.nebflow/logs/nebflow.log` 行号。

| 时刻 | 事件 | 证据 |
|---|---|---|
| 21:47:03 | 重启后用户重连会话 `5cc7590a`（meta: **auto-all**）；`seedPermissionPolicy(5cc7590a, auto-all)`；FlowTree restoreTeams → `parentMap[nebflow-project]=5cc7590a` | L23347-23348；WebSocketRoutes L221-223 |
| 21:47:50 | Manager 激活（parent=agent-5cc7590a → rootSid=5cc7590a → **AutoAll**） | L"subagent-Manager … spawn detail=parent=agent-5cc7590a" |
| 21:48:15 | Backend、qa-frontend 激活，同上 rootSid=5cc7590a | 同上格式 spawn 行 |
| ~21:50 | **Backend Write/Edit `~/.nebflow/agents/Nebula/agent.json` 成功（bdc2f99）**——auto-all 桶生效 | 用户对照事实；git bdc2f99 |
| 21:48-22:09 | deck v5 flow（planner=design-engineer、16×Coder）spawn，parent 均 = agent-5cc7590a → rootSid=5cc7590a → **整夜写文件零撞墙** | "fpga-deck-v5-academic-r2/worker#N … parent=agent-5cc7590a" |
| 21:56:11 | qa-frontend 启动 B6-A9 e2e 后台任务（Background job 46ef79d3，脚本 `/tmp/nb-b6a9/e2e-b6a9.cjs`） | L"Background job 46ef79d3 Run B6-A9 E2E against live 8080" |
| **21:56:18** | e2e 建临时会话 **`9f727822`（QA-B6-A9）**：`POST /api/sessions`（无 safetyMode → `createSession` 默认 **confirm-edits**，SessionStore L654-681）；WS 连入 → `seedPermissionPolicy(9f727822, confirm-edits)` → **桶[9f727822]=ConfirmEdits**；FlowTree restoreTeams：该会话无 flows.json → 磁盘全部 team 判为 unmounted → **auto-mount 全部 → `parentMap[nebflow-project]=9f727822`（劫持）** | L23771-23774；FlowTreeActor L576-588；e2e 脚本注释 "Session: QA-B6-A9 … deleted after the run" |
| 21:59:16-28 | e2e 清理：REST `DELETE /api/sessions/9f727822`（只清索引+文件，**不清内存桶/parentMap/Hub 注册**，且 REST 路径不调 `removeRootAgent`，RestApiRoutes L200-203）+ `rm -rf ~/.nebflow/sessions/9f727822…`（目录形态，实际文件是 `.json`，**没删掉**） | L23830-23837；文件现存可读 |
| **22:11:57** | qa-backend 首次激活（Backend→qa-backend Mail 触发）：`activateAgent` L977-981 `rootSid = parentSessionOf("nebflow-project") = 9f727822`；`policyOpt` **命中**（桶存在）→ actor safetyMode=confirm-edits（**qa-backend 自身 meta 的 auto-all 被覆盖**）；spawn 时 `rootSessionId=9f727822` | L"subagent-qa-backend …/95eeea2c spawn"；MailTool L977-981, L1063 |
| 22:18:55 | Write #1 → `permissionDecision`（AgentCore L996-1008）：桶[9f727822]=ConfirmEdits → `isReversible(Write,ConfirmEdits)=false` → **Ask**；卡经 Hub 渲染进 9f727822（rootWsSend 已注册，**无 drop 警告**，卡确实进了该会话 ui.json） | L26096；InteractionHub L95-109；9f727822.ui.json 有卡 |
| 22:23:55 | 5min 超时 → auto-deny（PermissionTimeout，AgentCore L29） | qa-backend 会话消息 idx157 |
| 22:24 / 22:29 / 22:34 | 同一 Write 重试 ×3，同路径超时拒 ×3 | L26161 / L26201 / L26247 |
| 22:34:28 | qa-backend 放弃落盘：「Write 权限层持续超时。改 Mail 直发全文报告（落盘延后）」 | 95eeea2c.json idx163 |
| 22:36:22 | Manager `Mail(→Nebula) [RESULT]` **经 parentMap 路由进僵尸会话 9f727822**，唤醒其中的 Nebula actor（用户主会话 5cc7590a 没收到） | L26687；MailTool L1203-1207 同源缺陷 |
| 22:37:14 / 22:47:09 | 僵尸 Nebula 向 nebflow-project 派活（Mail） | L26807 / L27346 |
| 22:47:13 | 僵尸设 **Schedule 定时任务（~00:30 触发，含执行 `nebflow-restart.sh --force` 的指令）** | scheduled-tasks/9f727822….json（id 8436adb9） |
| 22:48:24 | 僵尸 Nebula 自己的 `Edit(~/.nebflow/agents/Nebula/memory.md)` 撞同一堵墙 → 22:53:24 超时拒 | L27412；9f727822.json idx16 |
| 23:02:46 | **prompt-engineer**（被僵尸 22:47 的 Mail 激活）`Edit(~/.nebflow/teams/nebflow-project/rules.md)` 撞墙 → ~23:07:46 超时拒 | L27887（排查进行中实时观测） |

```text
决策路径（qa-backend Write @22:18）：
state.session.rootSessionId = 9f727822            ← 激活时经 parentMap 解析（MailTool L1063）
  → policies.getOrElse(9f727822, default)          ← AgentCore L1003
    = ConfirmEdits（21:56:18 由临时会话连入播种）    ← WebSocketRoutes L222/L247-249
  → isReversible(Write, ConfirmEdits) = false      ← permissions.scala L73-75
  → Ask → Hub.Request(root=9f727822)               ← 渲染成功但无人可见
  → 5min PermissionTimeout → auto-deny             ← AgentCore L29, L1053-1055
```

![事故因果链](/tmp/permleak.svg)

---

## 2. 五问逐答

### Q1：qa-backend 那次 Write 走了哪条判定路径？rootSid 是哪个桶？

`Ask` 路径。rootSid = `9f727822`（僵尸 e2e 会话），桶在 21:56:18 被 `seedPermissionPolicy` 播种为 **ConfirmEdits**（REST 建会话默认），21:59 的删除**没有清内存桶**。不是「查不到回落 default」——桶**存在且是 confirm-edits**；但两种情况对 Write 等价（default 也是 ConfirmEdits，protocol.scala L334-341）。日志证据：4 次 `InteractionRequest kind=Permission root=9f727822 sourceAgent=qa-backend`（L26096/26161/26201/26247），无 `InteractionAnswered` 配对 → 全部 5min 超时。

### Q2：用户的 auto-all 存在哪？为什么没覆盖 qa-backend？

**存在会话 `5cc7590a` 的 meta 里**（`_index.json`：`"safetyMode": "auto-all"`，agentName=Nebula）。全代码检索确认：**不存在全局安全模式**——`setSafetyMode` 是 per-session WS 指令（WebSocketRoutes L1579-1605，写该会话 meta + 该会话桶）；nebflow.json 无任何 safety 配置；`~/.nebflow/permission_policy.json`（autoApproveAll:true）是**无任何代码读取的遗留文件**。

没覆盖 qa-backend 的机制：auto-all 只对 rootSid=5cc7590a 的会话树生效。qa-backend 的 rootSid 被 parentMap 劫持指向 9f727822。此外还有两层加重：① `MailTool.activateAgent` L979-981 桶优先于成员自身 meta——桶（confirm-edits）覆盖了 qa-backend 自己 meta 里的 auto-all；② 即使桶缺失，`permissionDecision` 回落 `PermissionPolicy.default`（ConfirmEdits），**actor 构造时传入的 safetyMode 副本在决策点被完全忽略**（AgentCore L988-990 注释自述 D5 设计）。

**定性：两者兼有**——「auto-all 没有全局生效」（没有全局机制）+「桶错位回落到 confirm-edits」（劫持+残留）。

### Q3：权限卡为什么不可见？

卡**确实渲染了**（无 "InteractionRequest dropped" 警告；9f727822 的 ui.json 里留有 6 张 askPermission 卡：4×Write(qa-backend)、1×Edit memory.md(僵尸)、1×Edit rules.md(prompt-engineer)）。不可见的原因是**渲染目标错位**：

- 卡按设计渲染在 `sessionId = rootSessionId`（InteractionHub.renderPermission L122）= 僵尸会话 9f727822；
- 用户前台开着的是 5cc7590a。前端 `onMessage('askPermission')`（main.js L1182-1219）对非活跃会话只做 `setSessionAttention(sid)` + `saveMsg`——而 **9f727822 已从 `_index.json` 删除，侧栏没有这个会话**，attention 指示无处安放，saveMsg 存进一个永远打不开的会话；
- 前端的第二道防线 `bypassSessions`（auto-all 会话自动批准，main.js L1188-1200）按**会话列表 meta** 推导（L1248/L2140）——9f727822 非 auto-all 且不在列表，防不住；
- 唯一在看着 9f727822 的客户端是 e2e 的 Playwright 浏览器，21:59 测试结束已关闭。

### Q4：今晚影响面（askPermission 事件 + 超时拒绝清单）

| # | 发出 | 超时拒绝 | sourceAgent | 工具/目标 | rootSid |
|---|---|---|---|---|---|
| 1 | 22:18:55 | 22:23:55 | qa-backend | Write `/tmp/qa-backend-reports/feat-refresolver-html-path-backend.md` | 9f727822 |
| 2 | 22:24:03 | 22:29:03 | qa-backend | 同上（重试） | 9f727822 |
| 3 | 22:29:14 | 22:34:14 | qa-backend | 同上（重试） | 9f727822 |
| 4 | 22:34:37 | 22:39:37 | qa-backend | 同上（重试）← 用户看到的这条 | 9f727822 |
| 5 | 22:48:24 | 22:53:24 | Nebula（僵尸会话内） | Edit `~/.nebflow/agents/Nebula/memory.md` | 9f727822 |
| 6 | 23:02:46 | ~23:07:46 | prompt-engineer | Edit `~/.nebflow/teams/nebflow-project/rules.md` | 9f727822 |

**qa-backend 不是孤例，是一类**：所有 21:56 之后才激活的 nebflow-project 成员（qa-backend 22:11、prompt-engineer ~23:01）以及僵尸会话自身的 agent 都会持续撞墙；21:56 前已激活的成员（Manager/Backend/qa-frontend，rootSid=5cc7590a）不受影响。间接损失：报告文件未落盘（内容经 Mail 文本送达）、qa-backend ~16 分钟写停滞、用户主会话没收到 Manager 的 [RESULT]、僵尸会话开始自治行动（§6）。

### Q5：为何 flow 子会话 / Backend 没撞墙？

| 会话 | 激活/spawn 时刻 | rootSid 归属 | 桶 | 结果 |
|---|---|---|---|---|
| Backend / Manager / qa-frontend | 21:47:50-21:48:15（**劫持前**） | parentMap 当时=5cc7590a | AutoAll | Write/Edit 畅通 |
| deck v5 flow workers（×16）+ planner | 21:48-22:09 持续 spawn | spawn 链 parent=agent-5cc7590a，rootSessionId 继承 5cc7590a（DelegateTool L408/L540） | AutoAll | 整夜写文件零撞墙 |
| qa-backend | 22:11:57（**劫持后**首次激活） | parentMap 已=9f727822 | ConfirmEdits | 撞墙 |
| prompt-engineer | ~23:01（劫持后） | 9f727822 | ConfirmEdits | 撞墙 |

分界线就是 21:56:18 的 parentMap 改写——**激活时刻相对劫持的先后**决定 rootSid 归属，而不是「team 成员 vs flow 子会话」的角色差异。

---

## 3. 根因结论

### 三层缺陷（叠加成事故）

**R1（触发器）临时会话可劫持全局 team 归属。**
`TeamSessionRegistry` 是全局单例（object + in-memory Ref，FlowTreeActor L32-45）；`parentSessionMap: instanceName → parentSessionId` 单值 last-writer-wins。**每个** root 会话的 FlowTree 在创建时都会跑 `restoreTeams`，其中 auto-mount 分支（L576-588）把磁盘上所有未显式挂载的 team 挂到**自己名下**并 `registerParentSession(team, self)`。于是任何一个短命会话（e2e、测试、误开的空会话）都能把 team 的「root 会话」抢到自己身上。会话删除（REST DELETE）不回滚这些注册。

**R2（判定层）权限桶按 rootSid 查，错位/缺失时静默落到 confirm-edits。**
决策点 `policies.getOrElse(rootSid, PermissionPolicy.default)`（AgentCore L1003）：桶缺失回落 default=ConfirmEdits；桶被错误播种（本例）也一样。同时激活链里成员自身 meta 的 safetyMode 被桶覆盖（MailTool L979-981），而桶又只在「用户连入某会话」时播种（WebSocketRoutes L222）——**后台会话的桶没有确定来源**。auto-all 本身没有任何全局通道。

**R3（可见层）权限卡只渲染到 rootSid 会话，root 不可见即无人能答。**
卡按 rootSessionId 定向渲染（InteractionHub L117-126）；前端对非活跃/不在侧栏的会话只置 attention（main.js L1202/1215-1217）。当 rootSid 指向僵尸/已删会话时，卡进了坟场，5 分钟后自动拒。Hub 对「root 未注册」的 drop 路径（L96-99）虽然存在，但本例 root 是注册过的（更隐蔽——渲染成功≠有人看得见）。

### 对三条用户事实的解释

1. **「auto-all 下本应零询问」**——事实成立但机制不存在：auto-all 只是 5cc7590a 一个会话的 meta 字段，无全局语义。qa-backend 的 rootSid 不指向 5cc7590a，所以 auto-all 对它无效。
2. **「没有任何卡弹到前台」**——卡渲染进了 9f727822（僵尸会话）：已从侧栏删除、无客户端在看、前端对非活跃会话只做不可见的 attention 标记。卡的 6 次渲染全部留在了那个会话的 ui.json 里。
3. **「后台 agent 更不该撞权限墙」**——撞墙的不是「后台」身份，是劫持后的错误归属 + confirm-edits 桶 + 不可见卡的三连。同一团队里劫持前激活的成员整夜畅通，证明「后台」本身不是变量。

### 对照差异的解释

见 Q5 表：分界是**激活时刻 vs 21:56:18 劫持**。Backend（21:48）和 flow workers（继承 5cc7590a）拿的是 auto-all 桶；qa-backend（22:11）和 prompt-engineer（23:01）经被劫持的 parentMap 拿到僵尸桶。

---

## 4. 证据索引

| 证据 | 位置 |
|---|---|
| 4 次 qa-backend + 2 次后续 Permission 请求（root=9f727822） | nebflow.log L26096/26161/26201/26247/27412/27887 |
| 无任何 "InteractionRequest dropped" 警告（卡渲染成功） | nebflow.log 全文 grep |
| 6 张 askPermission 卡留在僵尸会话 | `sessions/9f727822-….ui.json` |
| 4 次 Write 超时拒绝（时刻=发出+5min 整） | `sessions/95eeea2c-….json` idx157/159/161/165 |
| qa-backend 放弃 Write 改 Mail 直发 | `sessions/95eeea2c-….json` idx163（22:34:28） |
| auto-all 只在 5cc7590a meta | `sessions/_index.json`（5cc7590a 条目；**无 9f727822 条目**） |
| qa-backend 自身 meta 是 auto-all（被桶覆盖） | `_index.json` 95eeea2c 条目 |
| 临时会话由 e2e 创建、注释自述用后即删 | `/tmp/nb-b6a9/e2e-b6a9.cjs` 头注释；nebflow.log L23769/23830-23837 |
| 僵尸的定时重启任务（armed, ~00:30） | `scheduled-tasks/9f727822-….json`（id 8436adb9, enabled, triggered=false） |
| 僵尸会话复活与自治（[RESULT] 注入、Mail 派活） | `sessions/9f727822-….json` 22:36/22:46/22:53 消息；L26807/L27346 |

---

## 5. 修复方案

> 三条不变量：**I1** auto-all 全局生效永不询问；**I2** 后台会话策略有确定来源，不回落孤儿 default；**I3** 权限卡若发出必须可见可答。

### F1 全局安全模式（→ I1）

- **改动点**：`nebflow.json` 增加全局 `safety.defaultMode`（默认 confirm-edits）；`AgentCore.permissionDecision`（L1003）的回落值从 `PermissionPolicy.default` 改为「全局配置解析值」；`WebSocketRoutes.ensureAgent` 播种时以「会话 meta（显式设置过）→ 全局默认」为源。
- **文件**：`AgentCore.scala`、`WebSocketRoutes.scala`、`ConfigService`（或等价配置读取）。
- **验收**：
  - 全局 auto-all 下，任一会话来源（root / delegate / subtask / dag / Mail 激活的 team 成员 / 恢复的僵尸）执行 Write/Edit/Bash，`grep "InteractionRequest kind=Permission"` 零命中；
  - 全局 confirm-edits + 会话 meta auto-all 的组合，仅该会话树零询问（per-session 覆盖仍有效）；
  - 冒烟：真实启动 → 开 auto-all → 后台 Mail 派活让成员 Write → 文件落盘且日志零 ask。

### F2 杜绝临时会话劫持 team 归属（→ I2 前半）

- **改动点**（三层防御，可分步落地）：
  1. `restoreTeams` 的 **auto-mount 分支不再 `registerParentSession`**（FlowTreeActor L578-588）——只有 flows.json 里显式挂载的实例才算归属（L559-569 保留）；
  2. `registerParentSession` 增加守卫：目标 sid 必须存在于 `_index.json` 且 agentName=Nebula（`getSessionMeta` 校验），否则拒绝并 WARN；
  3. 会话删除统一清理：REST `DELETE /api/sessions`（RestApiRoutes L200-203）改走与 WS 删除相同的 `removeRootAgent` 路径，并新增 `TeamSessionRegistry.unregisterParentOf(sid)` + `permissionPolicies` 桶移除 + 对该会话后续 Mail 投递拒绝/重路由。
- **文件**：`FlowTreeActor.scala`、`RestApiRoutes.scala`、`WebSocketRoutes.scala`、`SessionStore.scala`。
- **验收**：
  - 重放今晚序列的 e2e：POST 建临时会话 → 连入 → 删除 → Mail team 成员 → 断言成员 rootSid ≠ 临时会话 id（测试钩子或日志断言），且 Write 正常；
  - 删除会话后，`parentSessionMap`/`permissionPolicies`/Hub root 注册对该 sid 的条目为零（内存断言）；
  - 已删除会话收到 Mail 投递时日志 WARN 且不复活 actor（或按 F4 重路由）。

### F3 桶的确定来源 + 可观测（→ I2 后半）

- **改动点**：`MailTool.activateAgent`（L977-981）与 `FlowTreeActor.resumeInterruptedAgent`（L663-667）在 spawn 前显式播种桶：`seed(rootSid, mode)`，mode 来源优先级 = root meta → 成员 meta → 全局默认（F1），并记 `permission bucket seeded root=X mode=Y source=Z`；`permissionDecision` 桶缺失时不再静默——WARN 日志注明回落来源。
- **文件**：`MailTool.scala`、`FlowTreeActor.scala`、`AgentCore.scala`。
- **验收**：每个 team 成员激活日志必含一行 seeded 记录；构造桶缺失场景，决策日志含 `fallback source=global|member-meta` 而非裸 default。

### F4 卡可见可答（→ I3）

- **改动点**：
  1. `InteractionHub.handleRequest`（L95-99）：root 未注册时不再仅 WARN+吞——fallback 广播到**所有**已注册 root（事件带 `sourceSession`/`sourceTeam`），前端全局 toast + Teams 面板对应成员亮 attention，点击可跳转作答；
  2. 前端 `onMessage('askPermission')`（main.js L1182）：对「事件 sessionId 不在会话列表」的卡（本例僵尸场景），弹全局通知而非仅 saveMsg 进坟场；
  3. （可选）超时前 T-60s 升级提醒。
- **文件**：`InteractionHub.scala`、`WebSocketRoutes.scala`（广播）、`main.js`/`ws.js`。
- **验收**（Playwright）：制造 root 不可达的 ask → 主窗口 toast 出现 → 点击作答 → `InteractionAnswered` 日志出现且请求方 Deferred 完成（工具真实执行）；不答则 5min 超时拒（行为不变）。

### F5 僵尸会话治理（防复活，本次事故的放大器）

- **改动点**：`deleteSession` 后该会话的 Mail 注入不再复活 agent（L22 建议路径）；已删会话的 `scheduled-tasks/<sid>.json` 随删除一并清理或标记 disabled；e2e/测试文档规范「临时会话必须走专用 NEBFLOW_HOME」（本次 rerun2 已是正确范式，8091 隔离实例）。
- **验收**：DELETE 后向该会话 Mail → 拒绝日志，无 actor spawn、无 scheduled task 残留。

### 修复优先级建议

F2-1（auto-mount 不注册 parent，一行级改动，直接掐断本类事故）> F1（全局模式，用户心智模型对齐）> F4（可见性兜底）> F3 > F2-2/3、F5。

---

## 6. 立即行动项（无需改代码，今晚）

1. **【需用户裁定】00:30 左右的定时重启任务**（`scheduled-tasks/9f727822-….json`，id 8436adb9）是**僵尸会话里的 Nebula** 设的：它会先检查全局空闲再执行 `nebflow-restart.sh --force`。重启本身能让 B6-A9 修复生效（原计划里也有重启窗口），但「重启完成向用户报告」会报告进僵尸会话（用户看不见）。保留 or 删除该任务文件由用户定。
2. prompt-engineer 的 `Edit(rules.md)` 将于 ~23:07 被拒——需人工重发该修改（或等重启后 parentMap 重置再跑）。
3. qa-backend 的报告 `/tmp/qa-backend-reports/feat-refresolver-html-path-backend.md` 未落盘——全文已经 Mail 文本送达 Manager，可手动补写或让 qa-backend 重发。
4. **止血**：重启实例即可清除全部内存态（parentMap/桶/Hub 注册），劫持立即解除；但 F1-F4 不落地前，任何新的临时会话都可能复发。

---

## 附录：关键代码位置

| 位置 | 作用 |
|---|---|
| `AgentCore.scala` L29 | PermissionTimeout = 5 min |
| `AgentCore.scala` L996-1008 | permissionDecision：按 rootSid 查桶，`getOrElse(default)` |
| `AgentCore.scala` L1010-1089 / L1092-1150 | askUserPermission / sendPermissionRequest（→ Hub） |
| `InteractionHub.scala` L53-56, L95-109, L117-126 | root 注册、drop 分支、卡按 rootSessionId 渲染 |
| `WebSocketRoutes.scala` L181/L188, L221-223, L246-249 | meta 读取（getOrElse confirm-edits）、registerRoot+seed+initFlowTree 链、播种实现 |
| `WebSocketRoutes.scala` L1579-1605 | setSafetyMode（per-session） |
| `FlowTreeActor.scala` L32-45, L89-90 | TeamSessionRegistry 全局单例、registerParentSession |
| `FlowTreeActor.scala` L543-598 | restoreTeams（auto-mount 全部 team → 注册 parent = 自己） |
| `MailTool.scala` L977-981, L1051-1067, L1203-1207 | 激活时 rootSid 解析与桶优先级、spawn rootSessionId、mailbox 路由同源 |
| `permissions.scala` L61-78 | isReversible（AutoAll 恒 true；ConfirmEdits 下 Write false） |
| `protocol.scala` L334-341 | PermissionPolicy.default = ConfirmEdits |
| `SessionStore.scala` L654-681, L816-858 | createSession 默认 confirm-edits；deleteSession 清单（不含内存注册） |
| `RestApiRoutes.scala` L187-203 | POST/DELETE sessions（REST 删除不走 removeRootAgent） |
| `main.js` L1182-1234, L1248/L2140 | askPermission 前端路由（view 仅活跃会话）、bypassSessions 按 meta 推导 |
