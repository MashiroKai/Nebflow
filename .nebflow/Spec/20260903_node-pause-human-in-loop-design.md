# Node 暂停 / 人在回路（Pause / Human-in-the-Loop）语义设计

- 日期：2026-09-03（设计批）
- 裁定来源：作者 2026-09-03 19:57 需求原话要点——「node 还缺一个语义：暂停或者仍在回路（human-in-the-loop）。任务分发器一次性建完整条长链自动推进，但真实开发中：①到某个阶段需要把客户端重启一下再继续；②某个节点做完需要用户补充信息才能往下走；③阶段性成果要 Nebula 跟用户沟通确认后再继续。作者设想：节点完成后把结果返回给 Nebula → Nebula 跟用户沟通 → 之后再让节点往下一步执行。」
- 代码基线：主仓 main `2552fb57`（工作区 clean；本文全部 file:line 锚点在此核对）
- 范围：**只设计不实施**。主仓零改动；实施在作者确认后按 §5 排期
- 文档关系：`20260831_project-node-architecture.md`（架构基线）、`20260902_flowmap-engine-evolution-design.md`（deps 边 / LoopNode 设计 / blocked 反馈协议协同）、`20260902_dispatcher-reentry-feedback-design.md`（blocked 重入，已进 main）、`20260903_flowmap-archive-panel-spec.md`（归档面板——「整链归档」交互相关）

---

## 0. 现状事实（设计基线，全部实核 @ 2552fb57）

### 0.1 与本设计直接相关的机制现状

| # | 事实 | 锚点 |
|---|---|---|
| 1 | 边模型两种：`out`（结果边，单权威 + 下游 in 双写镜像）、`deps`（完成信号边，下游单侧持有、不投结果）——**两者都是完成即自动推进**（out 投递→barrier 归零→startNode；deps 结算→startNode） | NodeTypes：NodeDef `out` :51 / `deps` :57（ProjectTypes.scala）；镜像 setOut（NodeTools.scala:100-132）；deps 闸门 startNode 内单权威（NodeEngine.scala:157） |
| 2 | 完成 = 结果持久保存 + 沿边自动投递：completeNode → BlockedReader 分流 → completed 落库 → deliverOut（:376）→ settleDeps（:382）。**「完成即推进」没有中间态**——要么 completed（全链自动续跑）要么 blocked（异常停住走重入） | NodeEngine.scala:354-385 |
| 3 | startNode 是全部启动路径的单权威入口（deliverOut / deliverOutTo / D1 补投递 / settleDeps / 入口启动），内含状态幂等跳过（:150-151）+ deps 闸门（:157）+ barrier 复核（:167） | NodeEngine.scala:144-177 |
| 4 | blocked 终态 + 反馈重入：BLOCKED 锚定 → blockedNode（status=Blocked、ttlExpireAt=**None 永不过期**、blockCount+1、不结算下游）→ FeedbackRouter 重入分发器 / 升级 Nebula。**「永不过期的待办语义」有先例** | NodeEngine.scala:413-439（TTL=None :425）；FeedbackRouter.scala:68-84 |
| 5 | TTL 只管显示：终态 +24h 移归档（sweepExpired 只扫 `Terminal` + `ttlExpireAt.exists`），归档结果可长期接线投递 | FlowMapStore.scala:92-105；NodeLifecycle.Terminal :27（ProjectTypes.scala） |
| 6 | Mail(→project) 是 Nebula 触发项目的唯一通道：→ ProjectActor.TriggerDispatcher → 分发器单例（活跃会话注入排队，无则 spawn） | MailTool.scala:182-192；ProjectActor.scala:137, 158-163, 314-344 |
| 7 | NodeEdit 已有布尔动作参数先例：`abandon=true`（终态/wiring/pending/死会话 running → cancelled + TTL + 审计）——「工具面不加工具、NodeEdit 加动作」是既有惯例 | NodeTools.scala:597-629；schema abandon :295 |
| 8 | AskUserQuestion 机械面对任意 agent 会话可用（AgentCommand.AskUser → InteractionHub 渲染卡片到 Nebula 窗口、requestId 路由回答），**但**：① 专用化护栏的 flow 节点引擎级剥离集含 `AskUserQuestion`（Guardrails.FlowWorkerStrippedTools）；② 卡片 pending 表是内存态；③ node 会话挂 AskUser 时 turn=Processing 且零活动 | InteractionHub.scala:44-70, 163-173；AskUserQuestionTool.scala:193-205；Guardrails.scala:34；AgentCore.scala:1594-1601 |
| 9 | TaskStuckWatcher 对 node 会话（kind=Flow）判卡死 = `Processing 且 lastActivityMs 超 10min` → 第 1 次即硬取消在飞 LLM，StopAttempts+2 轮仍卡 → 经 supervisorRef 发 Cancelled → engine cancelNode 全链清理。**挂 AskUser 等用户（无 LLM chunk / 无工具完成 → lastActivityMs 不刷新）的 node 会话必然命中** | TaskStuckWatcher.scala:20-27, 47-51 |
| 10 | LoopNode **未实施**（全仓无 `nodeType`/`loopSpec`/`LoopExecutor` 代码，仅设计文档）；deps **已实施** | 全仓 grep 实核；NodeDef.deps :57 / depsSatisfied（NodeEngine.scala:139-141）/ settleDeps（:395-403） |
| 11 | D1 补投递（已完成节点被改接 out → 立即向新目标投递，含归档）已实施——create（:537-547）/ edit（:871-879）两路 | NodeTools.scala |

### 0.2 作者三场景 → 机制需求归纳

| 场景 | 本质要求 | 对机制的硬约束 |
|---|---|---|
| ① 到某阶段需重启客户端再继续 | 暂停状态**持久化**，跨重启可恢复、可放行 | 暂停必须落在 flow-map.json（数据态），不能是内存挂起的 turn / Deferred |
| ② 节点做完要用户补充信息才往下走 | 结果先给 Nebula（可见），补充信息能进入下游输入 | 完成与投递**可分离**；放行动作能携带文本并落到下游 task |
| ③ 阶段性成果要 Nebula 跟用户确认再继续 | 同 ②（确认后放行） | 暂停点对 Nebula **主动可见**（通知），放行有明确动作载体 |
| 作者设想 | 节点完成 → 结果返回 Nebula → Nebula 与用户沟通 → 再放行下一步 | 暂停表达在**完成边界**（节点做完 ≠ 链继续），不在节点内部 |

---

## 1. 候选方案对比

### 1.1 方案 A「门控边」——暂停表达在拓扑上

新增第三种边语义：下游已接线但不随上游完成自动启动，等显式放行才触发。两个子形态：

- A1 门控挂在**上游 out 边**上（`out` 附带 gate 标记——上游完成 → 结果保留不投递，放行 → 投递）；
- A2 门控挂在**下游节点**上（下游 `gated` 标记——输入到齐也不 startNode，放行 → 去标记 + startNode）。

```
A ────out（结果边）────▶ ║gate║ ────▶ B ────out────▶ C
  A 完成：结果保留在 A（A1）/ 已投递进 B 输入缓冲（A2）
  ↓放行前
  · A1：B 收不到结果，barrier 不结算；Nebula 看不到结果（需另加通知规则）
  · A2：B 输入已冻结缓冲但被 startNode 闸门拦住；Nebula 同样看不到（需在 deliverOut 加「目标被门控→抄送 Nebula」规则）
  ↓放行
  A1：向 B 补投递（复用 deliverOutTo）；A2：去 gate + startNode
```

**优点**：暂停在拓扑上可见（边/节点带标记）；节点语义零改动；A2 的放行 = startNode 前置检查，与 deps 闸门同构（NodeEngine.scala:157 先例）。

**缺点（相对方案 B 的决定性代价）**：
1. **投递去重语义被破坏**——`deliveredTo` 的不变量是「恰投一次」（dedup 防改接重投，NodeEngine.scala:127-128, 401-406）。A1 的门控投递要求边出现第三态（待放行/已投递），A2 则要求「已投递但未消费」成为可停留状态——两形态都要在**最热、不变量最密的投递路径**上开新口子，与 R1（改接竞态）防护面重叠，回归风险最高的位置。
2. **Nebula 可见性是外挂规则**——作者设想的核心是「结果返回给 Nebula」，门控方案里结果流向拓扑下游，通知 Nebula 必须在 deliverOut 里加「门控抄送」分支，给最敏感路径再加逻辑。
3. barrier 闸点别扭——多上游 barrier 的暂停天然是「下游整体门控」（A2），但作者三场景全是线性链的完成边界暂停，门控边是为非主要场景优化的。
4. 数据模型 / 环检测 / 前端边渲染（deps 边刚做完三重编码）三处各加一类边形态，改动面比方案 B 宽。

### 1.2 方案 B「节点 hold」——暂停表达在完成边界上（**推荐**）

节点参数 `hold=true`：该节点完成后**不**沿 out 边投递、不结算 deps，结果只持久保存 + 通知 Nebula；状态进入新的**非终态** `held`（已挂起，等待放行）。显式放行（NodeEdit `release=true`）→ held → completed → 走既有投递全链。**节点语义、边语义、投递机械全部不变——只是把「完成」分成两拍：产出（held）与放行（completed）。**

```
A(hold=true) ────out────▶ B ────out────▶ C(hold=true) ────out────▶ Nebula

A 完成 → status=held（result 持久保存，ttlExpireAt=None）
        + 通知 Nebula「[Node 'A' completed — held] + 结果」     ← 作者设想第一步
        · 不投递 B（deliverOut 不执行）· 不结算 deps（held ∉ completed，闸门天然拦）
用户 ←→ Nebula 沟通（场景②③）／用户重启客户端（场景①——held 是磁盘态，重启无损）
        ↓放行
NodeEdit(A, release=true, note="用户补充：…")                    ← 作者设想第二步
  → 单事务：A held→completed + note 追加进 B.task
  → deliverOut(A) → B 启动（输入 = task[含补充] + A 结果）→ B 自动推进 → C 完成 → held → …
```

**为什么 held 必须是新状态而不是「completed + 投递抑制」**：若 held 节点保持 status=completed，deps 闸门（`status==completed` 判定，NodeEngine.scala:139-141）、loop detect、终态判定、「全终态整链归档」判定、前端状态色全部拿到**说谎信号**——每个 completed 的消费方都要加 hold 例外检查。一个诚实的非终态值让**所有既有机械零修改地正确工作**：held ∉ completed → deps 不触发；deliverOut 没跑 → barrier 不结算；held ∉ Terminal → TTL 不扫、归档不收、整链归档判定天然排除。NodeLifecycle 加值的先例是 wiring（NodeTypes.scala:19）。

**优点**：
1. **与作者设想逐字对齐**——「节点完成后把结果返回给 Nebula → 沟通 → 再让节点往下一步」就是 held 通知 + release 的直译；
2. **改动面最小**——不碰边模型、不碰投递去重、不碰环检测；新增逻辑集中在 completeNode 的一个分支 + 一个 release 方法；
3. **三场景全覆盖且场景①免费获得**——held 是纯磁盘态（无会话、无 Deferred、无在飞 fiber），重启恢复 = 既有 mountAll 加载 flow-map.json，零新增机制；
4. 暂停上限策略可对齐 blocked 的「永不过期待办语义」先例（ttlExpireAt=None）。

**缺点**：NodeLifecycle 加一个值（前端状态色/徽标要跟进）；「完成即推进」的全局直觉多了一个例外（靠状态色 + 通知文案弥补）。

### 1.3 方案 C「节点内直接 AskUser」——否决

机制面：node 会话调 AskUserQuestionTool → AgentCommand.AskUser → InteractionHub 渲染卡片到 Nebula 窗口 → 用户答 → requestId 路回。**机械上今天就能通**（护栏关着时工具可达）。

**否决理由（三条硬伤）**：
1. **场景①致命**：AskUser 的等待是 turn 内挂起（Deferred + hub 内存 pending 表）——实例重启即全部丢失；死掉的 running 节点被 reapStaleRunning 收殓成 cancelled（NodeEngine.scala:80-95），暂停点消失、无法恢复。人在回路的等待时长是小时/天级，挂在一个 turn 上天然违背「持久态而非挂起 turn」的架构原则（节点等待一律不设超时、状态全落盘的既有裁定方向）。
2. **watcher 误杀**：挂 AskUser 的 node 会话 = Processing + lastActivityMs 停止刷新（无 LLM chunk / 无工具完成）→ TaskStuckWatcher 10min 判卡死 → 第 1 次即硬取消在飞 LLM → 2 轮后经 supervisorRef 发 Cancelled → cancelNode 全链清理（TaskStuckWatcher.scala:20-27, 47-51）。用户 10 分钟不回答，节点就被系统「救死」了。要修就得给 watcher 加 AskUser 豁免——为一个被否决的方案改卡死兜底，得不偿失。
3. **架构越界**：节点是 leaf、无 Mail 身份、无用户向工具（guardrails 剥离集含 AskUserQuestion，AgentCore.scala:1594-1601 引擎级剥离哲学）；作者设想明确走 Nebula 中转（节点 → Nebula → 用户），C 是节点直连用户，绕过唯一有记忆/验收职责的 Nebula，问答上下文也不沉淀。

**C 的正当遗产**：InteractionHub 的卡片渲染/回答路由机械，可在 v2 被「放行确认卡片」（§4 前端形态）复用——机制否决，UX 遗产保留。

### 1.4 方案 D「现状协议化（零代码基线）」——保留为子集

不写一行代码，用既有机制模拟：分发器建链时在闸点把 `out` 指向 Nebula（而非下游）→ 节点完成 → 结果进 Nebula（既有 out=Nebula 路径）→ 用户确认后再次 Mail(project) → 分发器重入会话 `NodeEdit(B, in=A)`（= 改接 A.out→B）→ D1 补投递（NodeTools.scala:871-879）→ B 启动。

```
触发1：分发器建 A(out=Nebula) → A 完成 → 结果进 Nebula（+ 24h TTL 后归档）
触发2：用户确认 → Nebula Mail(project) → 分发器 NodeEdit(B, in=A)
       → in 追加 = A.out 改指 B → D1 补投递 → B 启动
```

**作为零代码基线今天可用**，但有三个结构性缺口，正是方案 B 要补的：
1. **链无法一次性预建**——`in` 追加会立即改接上游 out 并对已完成上游触发补投递（setOut :100-132 + D1），所以下游 B 不能在建链时预先 in=A（等于提前放行）；B 只能在确认后的第二轮会话里建。「分发器一次性建完整条长链」在闸点断开。
2. **闸点无显式记录**——A.out=Nebula 在图上长得和「链到此结束」一模一样，重入分发器无法从拓扑区分「这里是人工闸点」还是「链终点」，只能靠 task 文本约定，易漂移。
3. **暂停点会从主图消失**——completed + 24h TTL → 归档；用户暂停数天，闸点卡片不在主图上（归档区可查但「暂停链主图保留」的可见性没了）。

**结论**：D 保持为「开放式阶段拆分」的既有配方（架构文档 §2.8 协议要点 2 本来就规定结果依赖后续决策的阶段用多轮触发），方案 B 覆盖「预建长链 + 显式闸点」的需求，两者并存不重叠。

### 1.5 对比总表与推荐

| | A 门控边 | **B 节点 hold（推荐）** | C 节点 AskUser | D 协议化 |
|---|---|---|---|---|
| 场景① 跨重启 | ✓（数据态） | ✓（磁盘态，零成本） | ✗ 挂起 turn 重启即断 | ✓ |
| 场景②③ 结果进 Nebula | 外挂通知规则 | **原生**（held 通知） | ✓（但绕过 Nebula 职责） | ✓（out=Nebula 原生） |
| 预建完整长链 | ✓ | ✓ | ✓ | ✗（闸点必须二轮建） |
| 闸点显式可记录 | ✓ | ✓（held 状态 + 审计事件） | ✗ | ✗（拓扑不可辨） |
| 改动面 | 边模型+投递去重+环检测+前端边渲染 | 一个状态值+一个参数+completeNode 分支+release | 看似零改动实则要改 watcher+护栏豁免 | 零 |
| 对既有不变量的风险 | **高**（deliveredTo 恰投一次被破） | 低（全部新逻辑在既有分支旁路） | 高（watcher 误杀 / 重启断链） | 零 |
| 工具面 | 需定义门控边语法 | NodeEdit +hold/release/note 三参数（abandon 先例） | 零 | 零 |

**推荐：方案 B**。理由一句话：它是唯一同时满足「暂停落在完成边界（作者设想原话）+ 三场景全覆盖 + 跨重启免费 + 对投递去重/环检测/TTL/归档四条既有不变量零侵入」的形态，且放行动作可完全复用 NodeEdit 的布尔动作先例（abandon），工具面零膨胀。A 是次选（若作者坚持「暂停必须长在拓扑上」可选 A2），C 否决，D 保留为开放式阶段的既有配方。

---

## 2. 方案 B 详细设计

### 2.1 数据模型（ProjectTypes.scala，与 deps 同型的加性扩展）

```scala
// NodeLifecycle（:18-27）追加：
val Held = "held"
// 注意：Terminal 集合【不加】held——held 是非终态（等待放行），这是全部既有机械
// 零修改正确工作的关键（TTL 不扫、归档不收、deps 不触发、整链归档判定天然排除）。

// NodeDef（:41-71）在 hold 语义字段追加（建议置于 deps 之后）：
hold: Boolean = false,   // 人工闸点：完成后不投递不结算，status→held 等待 NodeEdit release
// withDefaults Codec（:74）→ 旧 flow-map.json 无此键解码为 false，零迁移。
```

**NodePayload**（:84-117）：`status` 字段原样携带 "held" 三源自动透传；`hold` 条件序列化（true 才带，与 deps 条件字段同构 :116），NodeEventPushSpec 字段集断言对无 hold 节点零影响。

### 2.2 完成路径（NodeEngine.scala，completeNode :354-385 加一个分支）

分支顺序（语义优先级）：**BlockedReader 分流（:357-358）→ hold 分支 → completed 原路径**。blocked 优先于 hold——节点申告「无法继续」是比「暂停等放行」更强的信号，BLOCKED 输出即使在 hold 节点上也走 blockedNode + FeedbackRouter（重入），不得被 held 吞掉。

```scala
// completeNode 内，BlockedReader.parse == None 之后：
if node.hold then heldNode(nodeId, resultText)
else /* 既有 completed 路径原样 */

// heldNode（与 blockedNode :413-439 同构的四动作序列）：
// ① 事务内现读 fresh（R2 纪律，拒写已消失/状态已变）：
//    status=Held, result=Some(text), completedAt=Some(now), ttlExpireAt=None
//    （永不过期 = blocked「待办语义」先例 :425；主图保留，整链归档判定天然不含）
// ② emitEvent nodeUpdated（NodePayload 同构载荷，不加新 WS 事件类型）
// ③ 不结算下游：deliverOut / settleDeps 都不调（与 blocked「传播停止」同款；
//    与 blocked 的差异：blocked 是异常申告走重入路由，held 是主动闸门等人放行）
// ④ deliverToNebula("[Node 'A' completed — held, awaiting release]\n<result>",
//                   nodeName, "held")    ← 作者设想「结果返回给 Nebula」
//    + FlowMapEventLog.append(..., "held", 摘要)
```

通知文本规格：**全文**（与 out=Nebula 完成投递先例一致，Nebula 需要全文才能与用户讨论）；气泡 eventType="held"，前端 label 映射加一项（v2 外观项，MVP 气泡正文已自解释）。

**startNode 幂等跳过列表**（:150-151）追加 `Held`——防御性一行：held 不应有任何启动路径可达（自身完成已发生过、上游投递因 deliverOut 未跑而永不满足 barrier、deps 闸门因 held ∉ completed 而它自身的 deps 在运行前已全满足且 completed 不可逆），但闸门单点多挡一道是既有纵深防御风格（:172 wiring 空节点守卫同款）。

### 2.3 放行（NodeTools.scala NodeEdit + NodeEngine.releaseNode）

**放行动作 = `NodeEdit(nodename, release=true, note=?)`**——不新增工具。理由：NodeEdit 已是「变更节点生命周期」的载体（abandon=true 先例 :597-629）；分发器是唯一 Node 工具使用者（架构裁定），放行的发起方（Nebula→分发器重入）恰在 NodeEdit 的既有调用面上；工具预算极简主义（本任务约束）下新工具（NodeRelease）无强理由。

```scala
// NodeEdit schema（:270-299）追加两参数：
"release": Bool —— 放行一个 held 节点（唯一离开 held 的动作）
"note": String —— 可选，仅与 release 同用：追加进 out 目标节点的 task（用户补充信息）

// call 路径（:308-359）：
//   release=true → 走 releaseNode 分支（与 abandon 同级的动作分支）
//   release 与 task/agent/in/deps/out/abandon 任一同时出现 → 拒绝（放行是独立动作，
//     不与改接/重激活混事务，避免半放行半改线的歧义；改线需求 = 先 release 后再 NodeEdit 改接）
```

**releaseNode 执行序列（引擎单点，NodeEngine.scala 新方法）**：

```
1. fresh-read 守卫：节点存在且 status==Held，否则报错
   "Node 'X' is not held (status=…) — release only applies to held nodes"
2. 单事务 mutate（同一 FlowMapState 同时改两节点，原子）：
   a. 本节点：status=Held→Completed，ttlExpireAt=now+TtlDisplayMs
      （completedAt 保留 held 时刻——那是工作完成的时刻；显示倒计时从放行起算）
   b. note 注入（note 非空时）：out 目标（必为节点 id，见 2.5 校验）且
      status ∈ {wiring, pending} → target.task += "\n\n== 用户补充（放行时注入） ==\n<note>"
      （目标不可能已运行：它 in 含本节点而本节点从未投递 → barrier 永不归零；
        守卫仅纵深防御，若目标异常运行则 note 不注入并在返回文本说明）
3. 事务后（既有完成路径复用，同一 detached fiber 顺序推进）：
   emitUpdated → deliverOut(completed) → settleDeps(completed)
   ——放行后下游启动前的全部闸门（目标自身 deps、barrier 复核）由 startNode 原样把关
4. FlowMapEventLog.append(..., "released", note 摘要)
```

放行后节点成为普通 completed：TTL 显示倒计时、可被改接补投递、可 abandon——全部既有终态语义自动适用。

### 2.4 补充信息进链（三路径，MVP 取 ①）

| 路径 | 流程 | 评估 |
|---|---|---|
| ① **release note 注入（MVP 推荐）** | Nebula 与用户沟通得到补充 → Mail(project) 文本含补充 → 分发器重入 `NodeEdit(A, release=true, note=…)` → note 原子追加进 B.task → B 启动时 buildInput 天然携带（NodeEngine.scala:182-192 task 即输入首段） | 一次动作完成放行+注入；单事务无窗口；信息落在输入构造的唯一数据源（task） |
| ② 分发器重入分两步 | Mail 文本含补充 → 分发器 `NodeEdit(B, task=B.task+补充)`（pending 节点编辑不受冻结限制）→ `NodeEdit(A, release=true)` | 两步、两次校验，语义等价；作为 ① 不可用时的等价路径（如补充要改的是 B 的 agent 而非 task） |
| ③ 前端放行对话框带 note 输入 | Flow Map held 卡片「放行」按钮 + 可选 note 输入框 → REST → releaseNode 同一引擎方法 | v2 UX（见 §4）；引擎路径与 ① 完全同一条 |

否决形态：向「运行中节点」注入补充——节点输入在启动时冻结是架构基线（§2.3 竞态一致性），且放行场景下游必然未启动，无需开运行中注入的口子。

### 2.5 NodeEdit 校验清单（0 spawn，全部工具层）

| # | 校验 | 文案要点 |
|---|---|---|
| 1 | `hold=true` 要求 out 为**节点 id**（out=Nebula 拒绝）——闸点是「扣住给下游的投递」，终点节点结果本来就投 Nebula，hold 无意义 | `hold=true requires a node-target out edge — out="Nebula" nodes deliver to Nebula directly, nothing to hold` |
| 2 | `hold` 只能设在 wiring/pending/running（完成前）；completed/终态/held 上设置或撤销 → 拒绝（结果已投递/已挂起，回退语义属于 release/abandon/重激活各司其职） | 对齐 D2「已消费拒改接」的口径 |
| 3 | `release=true` 仅接受 held 节点；与其他编辑参数同传 → 拒绝（§2.3） | 可行动错误文案（指引先 release 再改接） |
| 4 | `note` 仅与 release 同用；单独出现 → 拒绝 | — |
| 5 | `hold` 编辑 running 节点**合法**（hold 是完成时行为开关，不属输入冻结域——与 deps 冻结规则 :645-647 的边界注记对称：改下游冻结域参数拒绝，改自身完成行为参数放行） | — |
| 6 | findDuplicateDispatch 状态集（NodeTools.scala:147-157）**追加 Held**——held 节点任务未完，同 agent+task 重派应继续报「疑似重复派发」 | 一行改动 |
| 7 | abandon 接受域（:597-629）**追加 held**——放弃一条暂停链的节点是合法清场（held 无在飞会话，无中断副作用；处置同款 cancelled + TTL + 审计） | abandonNode 分支条件加 held |
| 8 | NodeCancel 对 held 不适用（status ≠ running → 既有 no-op 分支 :1021-1022 天然覆盖，零改动） | — |

### 2.6 状态机与终态关系

```
                    ┌────────────────────────────────────────────┐
                    │                                            │
wiring/pending ──▶ running ──▶ completeNode 分流：                  │
                    │            ├─ BLOCKED 锚定 ──▶ blocked（终态，重入路由）      │
                    │            ├─ hold=true ──▶ 【held】（非终态）              │
                    │            │                   │ NodeEdit(release=true)  │
                    │            │                   │  ├→ completed（走投递全链）│
                    │            │                   └─├→ abandon → cancelled    │
                    │            └─ 否则 ──▶ completed（原路径）──┘                  │
                    │                        ├─ 执行失败 ──▶ failed                │
                    └── NodeCancel ──▶ cancelled ◄─────────────────────────────┘

held 与相邻概念划界：
· held vs blocked：都是「停住等人/等系统」，但 blocked = 节点申告异常（终态，
  唯一出口 NodeEdit 重激活，走 FeedbackRouter）；held = 主动闸门（非终态，
  唯一出口 release，不走 FeedbackRouter）。blockCount 与 hold 互不计数。
· held vs pending：pending 是「还没轮到」（输入未到齐），held 是「做完了但被扣住」
  （result 已产出）。NodeList 里两者都可见，语义不同。
· held 与「整链归档」（归档面板设计）：全链终态判定基于 Terminal 集——held ∉ Terminal
  → 含 held 节点的链永远不算全终态，主图保留、不进归档面板，正是「暂停链不算终态、
  主图保留」的要求；零新规则（sweepExpired :92-105 只扫 Terminal ∩ ttlExpireAt.isDefined）。
```

状态命名：**`held`**（推荐）。备选 `paused`（更直白但易误读为「运行中暂停」——held 明确表达「完成产出已被扣住」）。本设计按 held 书写，作者可一键改名（值只出现在 NodeLifecycle 常量 + 前端映射）。

### 2.7 可视化（一句话建议，UX 细节归 design-engineer）

held 卡片用琥珀色 + ⏸ 徽标（区别于 blocked 红 / completed 绿），其 out 边以「待投递」虚线态渲染（复用 deps 边的视觉语法与图例位）。

### 2.8 健壮性

| 项 | 分析 |
|---|---|
| **重启恢复（场景①）** | held 全量落盘（status/result/ttlExpireAt=None 进 flow-map.json，write-through）→ mountAll → FlowMapStore.open 加载即恢复（FlowMapStore.scala:123-132）；held 无会话/无 Deferred/无在飞 fiber，重启零孤儿。放行在重启后照常（NodeEdit release 走活动区；held 永不被 TTL 归档，不存在「归档节点不能 release」问题）。重启前发出的 held 通知已在 Nebula 会话记录里，重启后 Nebula 仍可经 NodeList 看到全量 held 清单。可选 P2：mount 后对 held 节点补发一条汇总提示 |
| **TTL / 归档** | held ∉ Terminal 且 ttlExpireAt=None → sweepExpired 双重不命中（FlowMapStore.scala:95-97）；放行时才起算显示 TTL。暂停多久都不丢、不消失 |
| **blocked 反馈协议** | completeNode 分流顺序保证 BLOCKED 优先（§2.2）；blockCount 只由 blockedNode 递增，hold 不计数；FeedbackRouter 决策链（cooldown/escalate/重入）与 held 零交集；上游 blocked 对 held 节点无影响（held 的输入在自身运行前已收齐）。反方向：held 下游对上游无要求（上游早已完成） |
| **deps 边语义** | held ∉ completed → depsSatisfied（NodeEngine.scala:139-141）不满足 → 依赖 held 节点完成信号的下游保持 pending 可见；release 落 completed 后 settleDeps（:395-403）正常结算。与 deps 设计裁定③「failed 不触发」的语义 family 一致：**held 也不触发** |
| **LoopNode** | 未实施（§0 #10）；按 20260902 设计，Loop 终态走同一 completeNode（verify PASS → completeNode）→ hold 天然组合（Loop 节点可设闸点：PASS 产出 → held → 人工放行才投递）。无需专门处理，实施 Loop 时不必回头改本机制 |
| **防滥用 / 超长暂停** | **不设暂停上限**——与「Node 不设超时」「blocked 永不过期待办语义」同一哲学：等待是状态不是故障。held 节点常驻主图 + NodeList 可见（分发器每次重入开场必读）。可选 P2（~15 行）：TtlTick 顺带扫描 held 超 7 天的节点重发一次 Nebula 提醒（节点记 heldNotifiedAt 去重），默认不做 |
| **watcher** | held 无 AgentRecord（会话已随完成销毁）→ TaskStuckWatcher 扫描集天然不含，零交互 |

### 2.9 与既有裁定相容性自检

| 既有裁定 | 相容性 |
|---|---|
| 结果持久化（完成即保存） | ✓ held 落 result 与 completed 同域；「显示消失 ≠ 结果丢弃」扩展为「未放行也不丢」 |
| 悬空补投递（D1） | ✓ 正交——D1 管「接线时补投」，hold 管「完成时不投」；held 节点改接 out 后 release → deliverOut 向新目标投递（改接一等语义兼容） |
| blocked 反馈协议 | ✓ 分流顺序 + 计数域隔离（§2.8） |
| deps 边语义（完成信号） | ✓ held 不满足完成信号——与 failed 不触发同族语义，行为可预期 |
| 整链归档 / TTL 显示语义 | ✓ held 链永不全终态、主图保留（§2.6） |
| 1 对多拒绝 / out 单值 | ✓ 雙 100% 不触碰边模型 |
| 分发器唯一 Node 工具使用者 / 无回报 | ✓ 放行走「Nebula Mail(project) → 分发器重入 → NodeEdit release」，触发链与既有重入同构 |
| Node 不设超时 / TaskStuckWatcher 兜底 | ✓ held 无会话；watcher 零改动 |
| 工具面极简（NodeEdit/NodeList/NodeCancel） | ✓ 零新工具；NodeEdit +3 参数（hold/release/note） |

---

## 3. 预期行为变化（行为对照）

| 时点 | 现在 | 改后 |
|---|---|---|
| 分发器建长链 | 只能建到下一个「需要人看」的点为止（再远的结果不知道该不该等确认） | 可一次性预建整条链，闸点节点带 hold=true——链自动推进到闸点自动停 |
| 闸点节点完成 | 结果沿边投下游，链继续跑（或只能 out=Nebula 断链，二轮重接线） | status=held，结果全文通知 Nebula，下游安静等待；节点卡片琥珀色停在主图上，多久都在 |
| 用户补充信息/确认后 | Nebula 再次 Mail(project)，分发器重接拓扑（out=Nebula 方案还要改线触发补投递） | 同样 Mail(project)，分发器一条 `NodeEdit(release=true, note=…)`——放行与补充一次完成，下游带着补充信息自动续跑 |
| 客户端重启 | 运行中节点成死会话被收殓；「等确认」这件事没有任何持久载体 | held 节点从 flow-map.json 原样恢复，重启后照常放行 |

---

## 4. 放行入口的三个形态（演进关系）

| 形态 | 批次 | 说明 |
|---|---|---|
| ① NodeEdit release（MVP，唯一放行通道） | 第 1 批 | Nebula ↔ 用户沟通后 Mail(project)，分发器重入执行。零前端工作 |
| ② 前端 held 卡片「放行」按钮 + note 输入 | 第 2 批 | REST 端点调**同一个** engine.releaseNode（用户直放，绕过分发器轮次；note 同事务注入）。卡片/交互归 design-engineer；可选复用 InteractionHub 卡片机械做「放行确认卡」 |
| ③ 7 天未放行提醒（TtlTick 顺带） | P2 可选 | 默认不做 |

---

## 5. 实施评估

### 5.1 改动面（文件级）

| 文件 | 改动 | 量级 |
|---|---|---|
| ProjectTypes.scala | NodeLifecycle.Held + NodeDef.hold + NodePayload hold 条件字段 | ~10 行 |
| NodeEngine.scala | completeNode hold 分支 + heldNode 方法（~30 行）+ releaseNode 方法（~50 行）+ startNode 幂等列表 +held | ~90 行 |
| NodeTools.scala | NodeEdit schema/call +hold/release/note 参数 + 校验 8 条（§2.5）+ abandon 域 + dup-check 集 | ~120 行 |
| 新 NodeHoldSpec.scala | §5.3 用例表（复用 NodeBarrierDeliverySpec 基建：CaptureLlm / 临时 dataRoot） | ~300 行测试 |
| 前端（第 2 批） | held 状态色/徽标、气泡 label "held"、（可选）放行按钮+REST 端点 | 另计 |

后端合计 **~220 行 + 测试**，单一语义批（一个 commit 序列可整批 revert；数据模型加性字段，回滚后旧代码读含 hold 键的 flow-map.json 天然忽略，与 deps 批的回滚安全前提同款）。

### 5.2 分批与重启

- **第 1 批（MVP）**：后端全量——覆盖三场景（放行走分发器路径）。落地后需**宿主重建**才生效（现状惯例：运行宿主不含未重建代码，行为验证在隔离实例/重建后做）。
- **第 2 批**：前端 held 徽标/状态色 + 气泡 label（可与在途前端批次合并）；可选放行按钮 + REST。
- 无数据迁移（withDefaults 零迁移）；无行为开关需求（hold 缺省 false = 旧行为）。

### 5.3 测试用例表（NodeHoldSpec）

| # | 用例 | 关键断言 |
|---|---|---|
| T1 | hold 节点完成 → held | status=held；result 全文落库；ttlExpireAt=None；**无投递**（下游 deliveredTo 空、CaptureLlm 断言下游未启动）；deps 依赖者未触发；Nebula 收到 held 通知（全文） |
| T2 | release → 投递链 | held→completed；下游启动且输入含 `=== Node A ===` 段；barrier 多 in 时仍等齐其他上游 |
| T3 | release+note | 下游输入含 note 追加段（`== 用户补充（放行时注入） ==`）；单事务（note 注入失败则整体不落） |
| T4 | BLOCKED 优先 | hold 节点输出 BLOCKED 锚定 → blocked（非 held）；blockCount+1；FeedbackRouter 路由被调 |
| T5 | 重启恢复 | hold 节点 held 后重建 store（open 重载）→ status=held 原样；release 照常成功 |
| T6 | 校验负向 | hold=true + out=Nebula → 拒；completed 节点设 hold → 拒；release 非 held → 拒；release+task 同传 → 拒；note 单独 → 拒 |
| T7 | dup-dispatch | held 节点同 agent+task 重派 → 拒「疑似重复派发」 |
| T8 | abandon held | held → cancelled + TTL + 审计事件；NodeCancel 对 held no-op |
| T9 | held 改接后 release | held 节点 NodeEdit out→新目标 → release → 结果投新目标（改接一等语义兼容） |
| T10 | held 链归档排除 | 含 held 节点的项目 sweepExpired 不动它；全终态判定（Terminal）不含 held |

---

## 6. 待作者拍板清单

| # | 决策点 | 选项 | 建议 |
|---|---|---|---|
| 1 | 状态命名 | a) `held`；b) `paused` | **a**（「产出被扣住」，避免「运行中暂停」误读；改名成本≈0，随时可换） |
| 2 | held 通知内容 | a) 结果全文（对齐 out=Nebula 先例）；b) 摘要 + 指引看详情窗 | **a**（Nebula 需全文才能与用户讨论；超长结果已有工具结果保护机制兜底） |
| 3 | MVP 放行通道 | a) 仅 NodeEdit release（分发器路径）；b) 同时做前端放行按钮+REST | **a**（第 2 批再做前端，引擎方法同一条） |
| 4 | release 与改接同事务？ | a) 拒绝混用（先 release 再改接）；b) 允许 release+out 同调用 | **a**（动作正交，避免半放行半改线歧义） |
| 5 | 超长暂停提醒 | a) v1 不做（对齐 blocked 永不过期）；b) TtlTick 7 天重提醒（heldNotifiedAt 去重） | **a**，b 列 P2 可选 |
| 6 | 方案取舍确认 | a) 方案 B（hold+held+release，本设计）；b) 方案 A2（下游 gated，门控长在拓扑上） | **a**（理由见 §1.5：对投递去重不变量零侵入、Nebula 可见性原生、barrier 外场景全优） |
| 7 | LoopNode × hold | a) 天然组合不专门处理；b) Loop 节点禁用 hold | **a**（Loop 未实施，实施时同走 completeNode 即自动获得） |

---

## 附：自检核对

- [x] 作者三场景逐条覆盖：①跨重启=磁盘态零成本（§2.8）②补充信息=release note 单事务注入（§2.4）③Nebula 确认=held 通知+release（§2.2/§2.3）
- [x] 四形态对比（A/B/C/D）各带文字拓扑图与否定/保留理由（§1）
- [x] 放行动作三形态评估并给出推荐（§4）；工具面零膨胀（NodeEdit +3 参数，abandon 先例）
- [x] 状态机命名、终态关系、整链归档交互（held ∉ Terminal → 主图保留）逐条落点（§2.6）
- [x] 健壮性六项（重启/TTL/blocked/deps/LoopNode/防滥用）+ watcher 零交互（§2.8）
- [x] 与既有裁定相容性自检表九条全过（§2.9）
- [x] 实施评估：文件级改动面 + 行数 + 分批 + 重启注意事项 + 10 用例测试表（§5）
- [x] 代码锚点全部实核 @ 主仓 2552fb57；LoopNode 未实施以代码 grep 为准（§0 #10）
- [x] 主仓零写入；唯一产出本文件
