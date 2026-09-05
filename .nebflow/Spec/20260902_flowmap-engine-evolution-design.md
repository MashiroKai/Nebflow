# Flow Map 引擎演进统一设计：deps 依赖连接 + LoopNode + 反馈协议协同

- 日期：2026-09-03 00:30–01:00（统一设计批）
- 裁定来源：作者 2026-09-02 23:51（deps 依赖连接五点）+ 2026-09-02 23:58（LoopNode 语义与七决策点）——两裁定并入本统一设计批
- 补录裁定（**变更记录**）：作者 2026-09-03 00:36 消息两裁定，**直接生效、独立于 §6 待确认点**——①**不允许节点空连接**（创建/改接校验禁止零连接；清场退役走 abandon，§1.6）；②**节点连接改接为一等操作语义**（分发器与作者均可随任务演进调整 in/deps/out，§1.2）。落点：§0.2/§1.0/§1.2/§1.3/§1.4/§1.6（新建）/§2.4/§2.6/§2.7/§3.3/§4；与 §6 十三条已逐条核对，无直接冲突（见附自检）
- 补录裁定二（**变更记录**）：作者 2026-09-03 00:59 拍板，**直接生效**——**裁定 A**：verify 侧 agent 选型 = **通用 agent + plugins 动态分配**（与阶段 2 裁定⑧「专业化=通用 agent+Plugins」一致；执行期按验证域经 plugins 体系分配；**verifyPreset 单列能力保留**；§6 #5 原「按域选 qa-*/Coder/Explorer」建议废弃）；**裁定 B**：LoopNode 内部 worker/verify **双会话贯穿整个存续期**（首轮 spawn、跨轮同会话注入续跑；worker 跨轮保留全部上下文——反复打磨正是 /goal 模式价值），**LoopNode 终态（PASS 投递 / cancelled / failed）才双会话销毁**——不是每轮新会话。§6 其余 11 条按作者「其他按建议来」确认（范围声明，逐条标注见 §6 表）。落点：§0.2/§2.1/§2.2/§2.4/§2.5/§2.6/§2.7/§3.2/§3.3/§4.2/§6/附自检
- 代码基线：主仓 main `2f32d0a6`（本文全部 file:line 锚点在此核对）；本文写作期间分发器单例化落地为 `76f3808c`（仅 ProjectActor.scala + 新 spec，不动本文其余锚点；§3 按终态后状态写）
- 文档关系：`20260902_deps-dependency-connection-design.md`（24efacf）**已并入本文作 §1**，原文件头部已加注记（以主文档为准）；`20260902_dispatcher-reentry-feedback-design.md`（反馈协议，后端已进 main 54c22d5b）作为协同前提引用，不重述其全文
- 范围：**只设计不实施**。主仓零改动；实施在作者确认后、反馈协议热文件窗口结束后按 §3 排期

---

## 0. 现状事实与代码锚点（设计基线，实施节点须知）

### 0.1 环境事实

| # | 事实 | 证据 |
|---|---|---|
| 1 | 设计锚点基线 `2f32d0a6`；写作期间 main 前进到 `76f3808c`（分发器单例化，208 行改 ProjectActor.scala + 309 行新 spec） | `git log --oneline -3` |
| 2 | 反馈协议后端已进 main（`54c22d5b`）：NodeLifecycle.Blocked / BlockedFeedback / BlockedReader / FeedbackRouter / blockedNode 全链可用 | ProjectTypes.scala:18-38、NodeEngine.scala:314-340、FeedbackRouter.scala 全文 |
| 3 | 运行宿主 v1.4.1-beta.54 未重建（PID 17392）——宿主进程不含 2f32d0a6 及以后；本文设计的语义随下次重建生效，重建前不要在宿主上做行为验证 | 环境信息 vs git log |
| 4 | 2a 沙箱：分支 `sandbox-2a` @ `efa9f86e` 已提交（21 文件 +750/-143：agent/AgentActor+AgentCore+SharedResources+protocol、NodeRunner、NodeEngine、ProjectActor、core/sandbox/* 三新文件、tools 五工具+shell+types、GatewayMain、llm/config），验证节点 n-cbe91f09（wiring） | `git diff --stat main...sandbox-2a`；主仓磁盘 flow-map.json |
| 5 | sandbox-2a 对 NodeEngine.scala 的全部改动 = SpawnParams 追加 `sandboxEnabled = true` 一处 hunk（约 :220 区域）；对 ProjectActor = 同款一处；对 NodeRunner = ToolContext 传递链 6 行 | `git diff main...sandbox-2a -- <file>` |
| 6 | 反馈协议前端 blocked 徽标在途（节点 n-422b75ab，wiring）——主仓无前端未提交改动，前端锚点按当前 HEAD 有效 | `git status`；磁盘 flow-map.json |

### 0.2 核心代码锚点（全部实核）

**数据模型（`src/main/scala/nebflow/core/project/ProjectTypes.scala`）**
- `NodeLifecycle` :18-27（wiring/pending/running/completed/failed/cancelled/blocked，Terminal 含后四者）
- `BlockedFeedback` :30-38；`NodeDef` :41-65（`in` :50 / `out` :51 / `deliveredTo` :52 / `blockCount` :58 / `blockedFeedback` :60）；Codec `withDefaults` :68（缺省字段反序列化为缺省值——**旧数据无新键可加载，向后兼容的机制基础**）
- `NodePayload.buildNodeJson` :77-106（NodeList / REST / WS 三源共用单一序列化点；baseFields :79-98、blockedFeedback 条件字段 :99-105）

**执行内核（`core/project/NodeEngine.scala`）**
- `startNode` :113-128（幂等跳过 running/终态 :119-120；wiring 空节点守卫 :123）——**deps 闸门与 LoopNode 启动的单点入口**
- `buildInput` :133-143（task + in 上游 result 带 `=== Node <名> ===` 头 + BLOCKED 协议脚注）
- `runWithAgent` :156-267（running 迁移事务 :168-181；bridge :187-201；spawn :203-222；registry 注册 :230-242；`UserInput` :243；`IO.race` 等终态 :245；终态分流 :260-266）
- `completeNode` :279-304（BlockedReader 分流 :282-283；事务内现读 fresh 写终态 :287-296；`deliverOut` :301）
- `blockedNode` :314-340（fresh 拒写 :327；FlowMapEventLog :334；`feedbackRouter.route` :335）
- `failNode` :342-363 / `cancelNode` :365-383（无投递）
- `deliverOut` :388-413（deliveredTo 增量 :401-406，barrier 判定 `in.forall(deliveredTo.contains)` :409-410）；`deliverFailed` :415-439（collect 结算）；`deliverToNebula` :447-459
- 常量：`TtlDisplayMs` :473 / `MaxBlockRoundsPerNode` :480 / `ProtocolFootnote` :483-489；`BlockedReader` :497-542（`parse` :505-519 / `anchored` :526-530 / `jsonBody` :533-536 / `fallback` :539-542）

**工具层（`core/tools/NodeTools.scala`）**
- `parseOut` :54-66 / `parseIn` :74-90（三形态宽容解析）/ `setOut` :93-106（out↔in 双写镜像单事务）
- `findDuplicateDispatch` :121-131（同 agent+归一化 task 重复派发拒；Terminal 含 blocked）
- `runDetached` :156-164（节点运行后台化——工具 fiber 不阻塞）
- `NodeEditTool` schema :216-241 / `call` :250-276 / `createNode` :280-316 / `proceed` :318-440（环检 :334-341；loop detect :343-345；NodeDef 构造 :363-378；单事务建边 :380-400；D1 补投递 :415-425；入口节点启动 :430-432）
- `abandonNode` :447-469（**仅接受终态**，:448-450 守卫——裁定①清场扩展见 §1.6）/ `editNode` :471-683（D2 已消费拒改接 :492-503；guard :505-511；blocked 重激活 :530-537 判定、:584-604 写回、:613-631 补投递链；edit 路径 D1 :654-671）
- `NodeCancelTool` :723-762（**仅接受 running** :757-758）
- `buildNodeListPayload` :168-187（REST flow-map 端点数据源）

**存储（`core/project/FlowMapStore.scala`）**
- `findNode` :38-42（活动区优先归档区兜底）/ `mutate` :45-52 / `wouldCreateCycle` :66-78（**只沿 out 单向 DFS** :74-76——deps 环检测扩展点）/ `sweepExpired` :82-95 / `normalizeOut` :110-111

**反馈路由（`core/project/FeedbackRouter.scala`）**
- 决策链 `decide` :68-84（cooldown → escalate-only → blockCount>2 → 窗口≥5 → Reenter）；`route` :87-96；`reenter` :99-111（→ ProjectActor.ReenterDispatcher）；窗口/阈值 :149-153

**循环防护（`core/processor/LoopGuard.scala`）**
- R-call / R-text 精确重复检测：`identicalCallHard`/`identicalTextHard` ×10 :53-54；**连续性语义**（不同调用/文本即刷新归零、成功不清零）:23-27 与 :199-235；计数器为 **AgentState 会话级字段** :84-88（**per-session、turn 边界清零 S1/R** :190-196）；L0/L1/L2 阶梯 :121-129；S3 跨轮冻结 :28、:238-242
- **对 LoopNode 的关键推论（2026-09-03 00:59 裁定 B 改写）**：LoopGuard 计数器是会话级字段，裁定 B 下 worker/verify 双会话**贯穿 LoopNode 存续期**（首轮 spawn、跨轮注入续跑）→ LoopGuard 的会话级计数（R-text 精确重复、连续性语义）**对 Loop 轮次可见**：worker 连续产出逐字相同会在同一会话内累计 R-text（同 sessionId 跨轮不清零）。LoopNode 层 K=5 兜底保留且更关键（两层检测划界见 §2.5 详析）

**卡死兜底（`core/processor/TaskStuckWatcher.scala`）**
- Flow 会话分支 :177-213（kind=Flow + supervisorRef：Processing 零活动 10min → 硬取消在飞 LLM → StopAttempts+2 仍卡 → 桥 Cancelled 全链清理）

**分发器（`core/project/ProjectActor.scala`，锚点 @ 76f3808c）**
- 命令 :140-143（TriggerDispatcher / ReenterDispatcher / CancelNode / TtlTick）；单例化 `ActiveDispatcher` :158、并发注入排队 :188-201；`dispatchTask` :303 / `dispatchReentry` :335 / `spawnDispatcher` :373

---

## 1. deps 依赖连接（作者 23:51 五点裁定，吸收自 24efacf 原文档）

> 本章为统一主文档的 deps 权威版本（五点裁定逐条落点：①→§1.1，②→§1.2，③→§1.3，④→§1.4，⑤→§1.5/§4）。原文档 `20260902_deps-dependency-connection-design.md` 保留作细节参考（其 file:line 实现级注释最全），冲突处以本章为准。

### 1.0 语义定义：三种连接对照

| | out（输出连接，现有） | in（barrier 入边，现有） | **deps（依赖连接，新增）** |
|---|---|---|---|
| 权威存储 | 上游 `out: Option[String]`（单值） | 下游 `in: List[String]`（与上游 out 双写镜像，setOut :93-106 维持一致） | **下游 `deps: List[String]`（下游单侧持有，不回写上游）** |
| 等什么 | — | 上游「结果投递」（deliveredTo 累计） | 上游「完成信号」（`status == completed`） |
| 投递什么 | 上游 result 注入下游输入 | 同左 | **什么都不投递。下游输入 = 自身 task（自足），绝不注入上游 result** |
| 上游 failed | out=Nebula 通知 / out=节点 deliverFailed 占位结算 | 计入 barrier 照常触发（collect 语义） | **不触发**，下游保持 pending/wiring 可见 |
| 上游 cancelled | 无投递 | 无投递 → barrier 永久悬挂（现状缺陷，仅记录） | 不触发，下游 pending 可见（比 in 的悬挂更健康可诊断） |
| 上游 blocked | 不结算（传播停止是特性） | 不结算 | 不触发（blocked ∉ completed） |
| 典型用途 | 结果流水线 | 结果汇合（merge） | 「先跑完 A 再跑 B，但 B 不需要 A 的产出」（先建 worktree 再开工；先出设计稿再动工） |

**in + deps 并存**：同节点可兼有。同一上游既 in 又 deps → 结果投递路径优先承载触发，deps 判定被同一完成事件幂等满足，二者汇合同一个 `startNode` 入口（幂等跳过，:119-120），冗余无害不重复 spawn。NodeEdit 不拒绝重叠；工具文档注明「需要结果用 in，只需完成信号用 deps，都要就都写」。

**连接存在性下限（作者 2026-09-03 00:36 裁定①）**：任一节点在任意交互终态必须至少持有一种连接（in / deps / out 至少一项）。三种连接地位平等地计入下限（out="Nebula" 同样是合法的连接声明）。「零连接」从合法可停留状态改为**被校验禁止的瞬态**——完整语义、校验与清场处置见 §1.6。

### 1.1 数据模型（裁定①：NodeDef 增依赖字段）

`ProjectTypes.scala` NodeDef（:41-65）在 `out`（:51）后插入：

```scala
out: Option[String] = None,
deps: List[String] = Nil,     // 新增：依赖连接（下游持有；只等完成信号，不投结果）
deliveredTo: List[String] = Nil,
```

- 序列化形态：`"deps": ["n-aaaaaaaa"]`（字符串数组，与 in 同构）。旧 flow-map.json 无 `deps` 键 → `withDefaults` 解码为 Nil，**加载零迁移**。
- 全部引用链：NodePayload 单点（:76-106）加 `"deps"` 一行 → NodeList 工具 / REST flow-map / 四类 WS 事件三源全通；FlowMapState/Archive 内嵌 NodeDef 自动携带；磁盘持久化（FlowMapStore :99-105）自动携带。**唯一必改的存量测试**：NodeEventPushSpec.scala:104-108 payload 字段集断言加 `"deps"`。
- 存储权威性差异（关键约束）：in 是双写镜像 → 环检测沿 out DFS 即可覆盖；**deps 下游单侧持有**（上游不知道自己被依赖——「不用其输出」语义的结构体现）→ 环检测须显式扩展（§1.2）、完成触发靠反向扫描（§1.3）。

### 1.2 NodeEdit（裁定②：声明 deps 的参数与校验）

- **schema**（NodeTools.scala:216-241）在 `in` 后加 `deps`，形态与 in 一致（`oneOf: string | array<string>`），description 注明 completion-only 语义与「提供即整体替换」。
- **解析**：不新写解析器，复用 `NodeTools.parseIn`（:74-90 三形态宽容解析）。
- **语义裁定建议：replace-on-provide**——未传不改；传了（任何形态）整体替换；空数组/null 清空（清空是合法改接动作，但清空后节点仍须剩至少一种连接——裁定①零连接下限，校验六；已终态节点的断开保留语义不受影响：result 不因断开清除）。理由：in 的删除有上游侧句柄（setOut 改投即从旧目标 in 移除）；deps 上游侧无句柄，append-only 则错接线永远无法删。replace 是下游自持列表唯一完整 CRUD 原语，对分发器幂等。
- **改接一等操作语义（作者 2026-09-03 00:36 裁定②，落账确认）**：in/deps/out 的修改与调整（改接/断开/重投）确认为**一等操作**——分发器（重入会话）与作者均可随任务演进调整连接，不属例外路径。机制面本文已有：D1 补投递（create :415-425 / edit :654-671）、edit barrier 复查（:667-670）、D2 已消费拒改接（:492-503）、混合图环检测（下校验二）、replace-on-provide（上条）。裁定②对本文的唯一新增约束 = 裁定①零连接下限：任何改接的交互终态仍须 ≥1 连接（下校验五/六）。
- **校验六条（1-4 为 deps 原设计；5、6 为 2026-09-03 00:36 裁定①新增待实施校验）**：
  1. 目标存在：`ensureNodeExists`（:36-40，findNode 归档兜底——deps 引用归档节点天然合法）；
  2. **无环检测（in+deps 混合图一起查）**：`FlowMapStore.wouldCreateCycle`（:66-78）的 DFS 后继集合从「out 目标（跳过 Nebula）」扩展为「out 目标 ∪ { m | m.deps.contains(起点) }」——单点改，create（:335,340）与 edit（:517,568）全部既有调用点自动获得混合图覆盖；
  3. 目标 running 冻结：与 in 的「输入已冻结」对齐（`ensureTargetNotRunning` :43-48），编辑 running 下游传 deps → 同款拒绝（:504-511 guard 段加 deps 分支）；给下游加 deps 的上游是 running 则合法（等它完成正是 deps 语义）；
  4. blocked 重激活联动：editNode `actualChange`（:536）加入 `depsChanged`；重激活补投递链（:613-631）不改——deps 闸门（§1.3）在 startNode 内自动把关。
  5. **零连接禁止——创建侧（裁定①）**：新建节点 in/deps/out 三者全缺 → 拒绝创建。校验错误码 **`EMPTY_NODE_CONNECTION`**（工具层 ToolError，文案风格对齐现状 abandon 守卫 :448-450 的 `Node '<名>' is <status> — <规则说明>` 句式）：创建侧消息 `Node '<名>' must declare at least one connection — provide in, deps, or out.`；改接侧同码，后缀 ` Rewiring would leave it disconnected.`。落点：**主仓 NodeTools/NodeEdit 校验层**——createNode（:280-316）schema 组装后、入库前单点拦截；与 deps 校验同批落地（§3.3 第 1 批）。
  6. **零连接禁止——改接侧（裁定①）**：编辑允许改接/断开单边（含 out=null 断开、in/deps 清空——断开本身是合法操作），但**交互终态**连接集（in ∪ deps ∪ out）为空 → 同款拒绝（同码，改接侧文案）。终态节点与非终态同规则；「out=null 断开、result 保留」既有语义不受影响（result 不因断开清除——裁定①边界注记）。
- **D1 悬空补接线对 deps 同样适用（裁定③内明确要求）**：create（:415-425）/ edit（:654-671）路径的等价 deps 动作 = 接线后若「deps 全满足 && in barrier 已归零 && 自身 wiring/pending」→ `runDetached(startNode(nodeId))`——上游已 completed 时接线立即触发（含归档上游，findNode 兜底命中；这是存量断链教训的直接应用）。**裁定①注记：D1 机制保留**——它服务「改接时接上已完成节点」的合法路径（与裁定②衔接）；「悬空」语义随之收窄：仅指「上游已完成而边后建」的补投递窗口（瞬态），不再是「节点无边、可停留待补」的合法驻留态（后者已被校验五/六禁止）。
- 创建即运行判定（:374, :430-432）不改：deps 不产生输入，不构成「可立即运行」；即使误判也被 startNode 内闸门拦下。

### 1.3 NodeEngine barrier 结算扩展（裁定③：in 等结果投递，deps 只等完成信号）

**核心改法：deps 满足判定 = startNode 内部前置闸门（单权威），不在各投递路径散落。**

```scala
// startNode（:113-128）status 幂等检查之后：
depsOk <- node.deps.traverse(id => store.findNode(id))
            .map(_.forall(_.exists(_.status == NodeLifecycle.Completed)))
// wiring 空节点守卫（:123）扩展为：task.isEmpty && in.isEmpty && deps.isEmpty 才跳过
// （裁定①后该守卫降级为纵深防御：NodeEdit 校验五/六已禁止零连接创建与改接，
//   守卫只兜历史遗留数据——校验生效前落盘的零连接旧节点——与校验层外瞬态）
```

- 用 `findNode`（归档兜底）——TTL 归档不影响判定。
- **裁定①衔接**：闸门与守卫均不承担「零连接禁止」主责——主责在 NodeEdit 校验（§1.2 校验五/六，分发器与作者共用同一工具层、无旁路）；startNode 内两者只做引擎侧兜底（幂等、纵深防御），历史遗留零连接节点（旧数据）在此被静默跳过，不空跑、无需数据迁移。
- 满足判定是**状态查询**（声明式、幂等），非 deliveredTo 式事件累计——**不加任何记账字段**，重复判定零成本。
- 单闸门自动保护全部启动入口：deliverOut（:410）、deliverOutTo（:109）、D1 补投递、重激活链、入口启动——任何绕过 deps 的启动都被拦在最后一关。
- **buildInput（:133-143）零改动**——deps 上游 result 不进输入，这是「不使用上游输出」的代码落点。

**完成信号 → 触发下游**：`completeNode`（:279-304）在 `deliverOut`（:301）之后追加 deps 反向结算：

```scala
settleDeps(completed):  // 反向扫描活动区（pending 依赖者必在活动区）
  store.snapshot.map(_.nodes.values.filter(_.deps.contains(completed.id)))
  >> 对每个依赖者 d（d 非 running/终态）：startNode(d.id)
  // 闸门在 startNode 内：deps 未全满足 / in 未归零都会静默返回
```

- 与 deliverOut 同一完成 fiber 顺序推进（现状 :410 同款语义）；同一上游既 in 又 deps 时第二次调用被幂等跳过。

**failed/cancelled 不触发 deps 下游（裁定③差异说明，须写进实施者认知）**：
- deps 边：failed/cancelled/blocked 均不满足闸门 → 下游保持 wiring/pending，NodeList/Flow Map 可见，分发器（重入会话）自行处置。
- in 边现状差异：failed 走 deliverFailed **计入 barrier 照常触发**（collect，错误串作为 result 注入——「带着错误继续」）；cancelled 无投递 → barrier 永久悬挂（现状缺陷，不在本设计范围仅记录）。**in 的 failed = 带错继续；deps 的 failed = 停住可见。**

**终态清理 / TTL / 归档不影响触发判定（裁定建议：归档上游可触发）**：sweepExpired 整节点搬归档区 status 原样保留 → findNode 查得。理由：① deps 等的是「完成」事实，TTL 只管显示（FlowMapStore.scala:15 头注释）；② 与 D1「归档 completed 上游是唯一投递入口」先例一致（NodeTools :411-413 注释原文）；③ 若归档不能触发，下游将永远 pending（不存在未来完成事件），重演存量断链。归档 failed/cancelled/blocked 同样不触发（与活动图一致）。

**与其他在途工作交集（详证见 deps 原文档 §C.6）**：deps 触发全程在 NodeEngine 内部 fiber（完成→桥→completeNode→settleDeps→startNode），与 TaskTool/MailTool→ProjectActor 分发器触发链**零调用关系**（分发器单例化 76f3808c 不触及）；反馈协议 blocked 分流在 completeNode 入口即与 completed 分叉，deps 结算只挂 completed 分支尾部，与 FeedbackRouter 零交集。

### 1.4 前端 Flow Map（裁定④：两种边视觉区分 + 实时增量兼容）

- **deps 边渲染**：collectEdges（flowMapTab.js:173-187）现只按 `n.out` 画边；deps 边**必须按下游 `n.deps` 反向渲染**（上游→下游画箭头），边 id 用 `~>` 与输出边 `=>` 区分，DOM class 加 `fm-edge-deps` + 状态档。
- **视觉三重编码**（不依赖单一色觉通道，亮暗主题 CSS 变量自适应）：虚线 `stroke-dasharray: 3 4`（类型）+ 绿/橙/灰三档（满足/等待/未满足，`--color-success/--color-warning/--color-border`）+ 空心小箭头（第二类型编码）。
- **图例**：renderFlowMap 全量模板（:597-612）底部加图例条（6 档：输出已投递/等待/未投递 × 依赖已满足/等待/未满足），i18n 键 `flowmap.legend.*` 落 locales en+zh-CN 双文件。
- **增量兼容逐点**：NodePayload 单点加 deps 后四类 WS 事件自动携带，handleNodeWsEvent（:846-870）零改动；layoutNodes（:78-118）childrenMap 增 deps 边 + 有 deps 的节点不作根（:97-100），否则纯 deps 下游被当根放第 0 层边画成逆向；nodeContentKey（:388-402）加 deps 段（pending 且有 deps 显示数量，与 in barrier 提示同款）；applyEdgeDiff（:486,492）class 拼接带 kind；taskList.js 零改动（整节点对象缓存）。
- **「wiring 节点等待谁」脚注**：pending 且 in/deps 非空的卡片显示 `等待: 设计稿(n-abc) · 审定(n-def✦)`；上游已归档名字不可得 → 裸 id + ✦ 诚实降级。这是断链诊断教训的低成本可见性方案。
- **零连接节点无前端空态（裁定①）**：NodeEdit 校验后活动图不存在零连接节点，前端无需为其设计空态展示；历史遗留零连接节点（旧数据）按现状孤立卡片渲染即可，不做专门处理。

### 1.5 测试（裁定⑤；完整用例表见 §4.1）

核心五用例：T1 deps 触发且不投递 result（CaptureLlm 断言 B 输入无 `=== Node A ===` 段）；T2 in+deps 混合 barrier；T3 混合图环检测（store 层 + NodeEdit e2e 双层）；T4 D1-deps 补触发（含归档变体——「归档可触发」裁定的回归锚）；T5 failed 不触发（对照断言锁定与 in 边 collect 的行为差异）。

### 1.6 连接存在性下限与清场 abandon（作者 2026-09-03 00:36 裁定①补录，新建小节）

**下限语义**：任一节点在任意交互终态必须至少持有一种连接（in / deps / out 至少一项，§1.0 下限注记）。「零连接」从合法可停留状态（「先建空节点、接线后补」式建图法）改为**被校验禁止的瞬态**：
- 创建侧：零连接节点拒绝创建（§1.2 校验五，错误码 `EMPTY_NODE_CONNECTION`）；
- 改接侧：改接/断开单边合法，但交互终态零连接拒绝（§1.2 校验六）；
- 引擎侧：startNode wiring 空节点守卫（:123，§1.3）降级为纵深防御，只兜历史遗留数据；
- D1 悬空补投递**保留**（§1.2 注记）——「悬空」收窄为「上游已完成而边后建」的补投递窗口，不再是节点可驻留状态。

**分发器建图职责收紧（同一裁定）**：建图即保证连通——分发器规划的每个新节点在创建调用内即声明连接，不存在「先放着以后再说」的空节点。分发器与作者共用 NodeEdit 同一校验层，无旁路。

**清场场景与 abandon（最小定义 + 待实施语义）**：
- 现状代码：`abandonNode` **已存在**（NodeTools.scala:447-469，blocked 反馈重入设计 §7.7）——终态节点 → status=cancelled + display TTL + `abandoned` 审计事件，result 保留，TTL 后 sweepExpired 归档；**但仅接受终态节点**（:448-450 守卫；错误文案指向 NodeCancel，而 NodeCancel 又仅接受 running :757-758）。
- 缺口：拓扑调整清场时，退役节点往往是 **wiring/pending**（活的、未终态）——现状 abandon 拒收、NodeCancel 也拒收，无处置出口，正是裁定①要消灭的「悬空活节点」。
- **待实施语义（本裁定新增，列入第 1 批实施范围）**：abandon 接受域从「终态」扩展为「终态 ∪ wiring/pending」（running 仍排除——运行中处置走 NodeCancel）。wiring/pending 的 abandon 与终态同款处置（cancelled + display TTL + `abandoned` 审计事件）；退役节点自此不再是可运行的悬空活节点，TTL 后自然归档。落点：NodeTools.scala abandonNode 守卫（:448-450）+ 错误文案同步改写。安全性：wiring/pending 节点无在飞会话，abandon 无中断副作用；被退役节点的上游其后完成时 deliverOut → startNode 幂等跳过（:119-120，cancelled ∈ Terminal），与终态 abandon 同构。

---

## 2. LoopNode 设计（作者 23:58 语义 + 七决策点）

> 语义原文：复合节点，内部由 worker / verify 两个节点组成；外部完全透明——对其他节点和连接而言就是一个普通 node（单 in 单 out）。worker 处理 task → 产出交 verify 验证 → 通过则产出传给 out；不通过则打回 worker 再循环。不设循环次数上限（与 08-29 LoopGuard 简化裁定哲学一致：靠精确重复检测兜底异常；TaskStuckWatcher 零活动兜底照常）。适用：需反复验证修改的场景（类似 codex 等 agent 的 /goal 模式）。

### 2.1 决策点 1：数据模型——LoopNode 复合表示

**两个候选**：

| 方案 | 形态 | 评估 |
|---|---|---|
| (a) **单 NodeDef + loopSpec 内嵌** | LoopNode 就是一个 NodeDef（nodeType="loop"），worker/verify 是**执行期**由 LoopExecutor 管理的两个真实 agent 会话——**裁定 B（2026-09-03 00:59）：双会话贯穿 LoopNode 存续期（首轮 spawn、跨轮同会话注入续跑），LoopNode 终态销毁**，不进 Flow Map 存储 | 现有引擎全部不变量（barrier/投递/D1/重激活/TTL/归档/NodeList/WS/deps 闸门）以「节点=NodeDef」为前提，(a) 下 LoopNode 天然是普通节点——外部透明性（作者裁定）零成本成立 |
| (b) 两个真实内部 node 实例 + 编组层 | create 时展开 worker-<id>/verify-<id> 两个真实 NodeDef + 组标记 | 需为 TTL 归档（组内单节点先到期？）、NodeCancel（单内部节点 vs 整组）、blocked 重激活（作用在内部还是组）、deps/in 指向组还是内部成员、findDuplicateDispatch 是否看到内部节点、前端 visibleNodes 折叠……每一项都是现存不变量的例外，编组层复杂度高 |

**裁定建议：(a) 单 NodeDef + loopSpec 内嵌，worker/verify 是 LoopExecutor 执行期会话。** 理由：① 外部透明性在 (a) 下是结构天然的（LoopNode 就是一个 NodeDef，单 in 单 out，deps 照挂，completeNode/blockedNode/failNode 照走）；② 复用度最大——会话生命周期管理（首轮 spawn / 跨轮注入续跑 / 终态销毁，裁定 B）与普通节点同源：spawn 复用 `NodeRunner.spawnAgentActor` + 观察桥 + registry 注册路径（NodeEngine :187-243，建议抽取为 `runSingleSession` 复用函数）；跨轮注入续跑有引擎先例可引——`runWithAgent` 已有 `UserInput` 注入通道（:243），分发器单例化已落地并发注入排队（ProjectActor :188-201，锚点 @ 76f3808c），持久会话注入续跑是同一注入面的 Loop 用法，非新造机制；③ worker/verify 仍是**真实 agent 会话**（可观测、受 LoopGuard/TaskStuckWatcher 覆盖），只是不占 Flow Map 存储位；④（裁定 B）双会话贯穿存续期使 worker 跨轮保留全部上下文（自己做过什么、verify 提过什么），反复打磨正是 /goal 模式的价值。

**字段设计（与 deps 字段同文件演进，同一兼容机制）**：

```scala
// ProjectTypes.scala，NodeDef 追加（deps 之后的第二批同型扩展）：
nodeType: String = "standard",        // standard | loop（v1 仅 loop 一个复合类型）
loopSpec: Option[LoopSpec] = None,

case class LoopSpec(
  verifyAgent: String,                // 验证侧 agent：双轨期（阶段 2 前）接受显式 agent 名；阶段 2 落地后 verify 专业化 = 通用 agent + plugins，按验证域分配（裁定 A 2026-09-03 00:59）
  verifyTask: String,                 // 验证清单模板（含验收基准说明）
  verifyPreset: Option[String] = None // verify 侧 preset（可选；node.preset 归 worker 侧）——单列能力保留（裁定 A）
)
```

- **worker 侧字段复用 NodeDef 现有字段**：`agent` = workerAgent、`task` = worker 任务模板、`preset`/`skill`/`mcp`/`worktree` 归 worker。理由：① `agent`/`task` 是 findDuplicateDispatch（:121-131）的判定维度——loop 节点存 workerAgent+workerTask，重复派发检测零改动生效；② NodeList/前端/详情窗现有逻辑对 agent/task 的消费零改动；③ 「LoopNode 的 task」在语义上就是「worker 要做什么」，不引入双字段漂移。
- **外部 in/out 映射**：in 边照常 barrier（等结果投递）→ startNode 启动 LoopNode → 外部输入进入 worker 首轮输入（§2.2 模板一）；verify PASS 后 `completeNode(nodeId, worker 最终产出)` → deliverOut 沿 out 投递 worker 产出原文。**外部不感知内部轮次**。
- **deps 兼容**：deps 闸门在 startNode 内（§1.3）——LoopNode 启动前 deps 闸门照常生效；其他节点 `deps=[LoopNode]` 等 LoopNode `status==completed`（verify PASS 才会到达），普通节点语义天然覆盖。
- 序列化：`withDefaults` Codec（:68）自动向后兼容（旧数据无 nodeType/loopSpec 键 → standard/None）；NodePayload 单点追加 `nodeType`（恒带）与 loop 运行态字段（§2.6）。
- **内部执行状态不持久化**：轮次/阶段存 LoopExecutor 内存 Ref（`loopStates: Map[nodeId, LoopRuntimeState]`）。重启时 running 的 LoopNode 与现状 running 普通节点同样成为孤儿（现状既有行为，正交不新增负担）；每轮追加 FlowMapEventLog 审计事件（`loop_round`），轮次历史经事件日志可追溯。

### 2.2 决策点 2：执行语义——循环状态机与输入模板

**状态机**（每个 LoopNode 实例一轮推进，全部状态迁移 emit nodeUpdated 复用现有 WS 类型；裁定 B 2026-09-03 00:59：worker/verify 双会话贯穿 LoopNode 存续期——首轮 spawn，此后每轮同会话注入续跑，终态才销毁）：

```
pending/wiring（等 in barrier 归零 + deps 闸门，与普通节点无差别）
  → startNode 触发 → LoopExecutor 接管（spawn worker + verify 双会话，此后贯穿存续期）：
  ┌→ WORKER(轮 N)：worker 会话（首轮 spawn，输入=buildInput 产物；轮 N≥2 同会话注入返工模板）
  │     worker 输出 BLOCKED 锚定 → blockedNode（Loop 级 blocked，§2.7）
  │     worker 执行失败(Left) → failNode（§2.4）
  │     否则 → VERIFY(轮 N)：verify 会话（首轮 spawn；此后同会话注入续跑）
  │         verify 输出 BLOCKED 锚定 → blockedNode（Loop 级）
  │         verify 执行失败 → failNode（§2.4）
  │         VerdictReader 解析（§2.3）：
  │           PASS（含畸形降级 PASS）→ completeNode(nodeId, worker 第 N 轮产出原文) → deliverOut（现路径）
  │           FAIL → 轮 N+1，打回 WORKER（同会话注入意见）──→（无轮次上限）
  └──────────────────────────────────────────────────────────┘
  终态（PASS 投递 / cancelled / failed）：双会话一并销毁（裁定 B）
  跨轮兜底：连续 K 轮 worker 产出逐字相同 → failNode（Loop 级内容死循环，§2.5）
```

**输入模板（精确给定）**：

模板一 · worker 首轮（= 现有 `buildInput(node)` 产物原样复用，零新逻辑）：

```
<node.task>

=== Node <上游名> ===
<上游 result>          （in 边投递的段；无 in 则无此段）

── 节点协议 ──          （BlockedReader 协议脚注，ProtocolFootnote :483-489 原样）
```

模板二 · worker 返工轮（第 N≥2 轮；**裁定 B：同会话注入**——原始任务、上游段、历轮产出与历轮意见均已在会话上下文中，不重复注入）：

```
【LoopNode 返工 · 第 N 轮】
== 验证意见 ==
<VerdictReader 解析出的 issues 逐条 + requirements>
── 节点协议 ──
```

产出全文不再随轮次重复注入，返工输入恒定、不随轮次线性膨胀——持久会话模型的附带收益。

模板三 · verify（第 N 轮）：

```
【LoopNode 验证 · 第 N 轮】
== 原始任务（验收基准） ==
<node.task>
（=== Node 上游 === 段，同首轮）
== 待验证产出（worker 第 N 轮） ==
<worker 产出全文>
== 验证清单 ==
<loopSpec.verifyTask>
── 验证协议 ──          （VerdictReader 文法脚注，§2.3，NodeEngine 常量单点注入）
```

（裁定 B：模板三全文仅首轮 spawn 使用；轮 N≥2 verify 会话已持有原始任务/上游段/验证清单与历轮产出，只注入「【LoopNode 验证 · 第 N 轮】+ worker 第 N 轮产出」新段——verify 跨轮记得此前意见，可核对问题是否被针对性修复。）

- **（2026-09-03 00:59 裁定 B 改写）持久会话保留全部轮次上下文**：worker/verify 跨轮不换会话——worker 记得自己历轮产出、历轮 verify 意见（作者裁定：反复打磨正是 /goal 模式的价值所在）。模板二因此只注入新意见，产出全文与历史不重复注入，返工输入恒定、不随轮次线性膨胀（持久会话模型的附带收益）；更早轮次上下文在会话内自然可得，无需模板搬运。
- **verify PASS 后 out 投递内容——裁定建议：worker 最终产出原文**（非 verify 意见、非「产出+验证附注」）。理由：① 外部消费者语义 =「LoopNode 的产出」，与普通节点 result=最终输出文本的架构裁定同构；② `completeNode(nodeId, workerFinalOutput)` 直接复用现终态路径（含 BlockedReader 分流——此时不会命中，因为 worker 产出若 BLOCKED 锚定早在轮内被拦截）；③ verify 的 PASS 记录与轮次历史保存在 loopState + FlowMapEventLog（可观测），不污染投递流。下游收到的 `=== Node <LoopNode> ===` 段 = worker 产出原文。

### 2.3 决策点 3：verify 判定文法（解析器级定义）

**VerdictReader**（新 object，与 BlockedReader :497-542 同构、同文件风格，便于单测）：

- **锚定行**：verify 最终输出（extractLastAssistantText 同款提取）trim 后**首个非空行**必须整体匹配 `^VERDICT:\s*(PASS|FAIL)$`（大小写不敏感，归一化为大写；行内除 PASS/FAIL 外不得有其他内容——防「VERDICT: PASS (mostly)」这类模糊判定）。
- **FAIL JSON 体**：首个 `{` 到末个 `}`（jsonBody 同款 :533-536），circe 解析：

```json
{ "issues": ["<具体问题 1>", "<具体问题 2>"], "requirements": "<通过标准，可空>" }
```

  `issues` 必须为非空字符串数组；`requirements` 可选字符串。
- **四态判定表**：

| verify 输出形态 | 判定 | 动作 |
|---|---|---|
| 锚定 `VERDICT: PASS` | Pass | completeNode（§2.2） |
| 锚定 `VERDICT: FAIL` + JSON 合法（issues 非空） | Fail(issues, requirements) | 打回 worker（模板二） |
| 锚定 `VERDICT: FAIL` + JSON 缺失/畸形/issues 空 | Fail(List("verify 未给出结构化意见（文法畸形）"), "") | 打回 worker（**FAIL 锚定保留**——显式锚定信号必被尊重，同 BlockedReader「锚定命中但 JSON 畸形仍 blocked、fallback 截断」:539-542 的对称设计） |
| 无锚定行（文法畸形） | **降级 = PASS 直通** | completeNode |

- **解析失败降级语义（裁定建议：无锚定 → PASS 直通）与理由**：与 BLOCKED 方案③的无损降级**同构对称**——BLOCKED 的降级是「非 BLOCKED 开头 → completed 原路径」（不惩罚正常输出，误报率≈0）；verify 的降级是「未按文法输出 → PASS」（不惩罚文法不完美）。共同哲学：**文法只用于收紧特殊语义，畸形输出永远回落到默认正常路径，绝不因格式问题硬失败**。① verify agent 是通用全局 agent，文法遵循度不完美，「验证员不会写 JSON」不应摧毁整个 Loop；② LoopNode 无轮次上限，畸形即 FAIL 会制造「verify 与文法搏斗」的新循环面；③ 产出质量最终可被下游消费者与人工复核（result 是产出原文，透明）。
- **风险分析（降级 PASS 的代价）**：verify 意图 FAIL 但忘写锚定行 → 坏产出被放行。缓解：① verifyTask 模板末尾单点注入文法脚注（与 ProtocolFootnote 同机制，覆盖所有 verify 会话）；② 放行的坏产出在下游节点/详情窗可见（透明可诊断）；③ 备选方案（畸形 → 强制 FAIL 重试 verify 一次）被 v1 拒绝：引入 verify 内层重试路径与「verify 反复畸形」的新循环面，收益（挽回个别漏杀）不抵复杂度——列入 §6 待确认 #1 供作者改判。
- **文法脚注文本**（NodeEngine 常量 `VerifyVerdictFootnote`，模板三末尾注入）：

```
── 验证协议 ──
你的最终输出第一行必须是且只能是：VERDICT: PASS 或 VERDICT: FAIL（大写，冒号后半角）。
判 FAIL 时随后给出 JSON：{"issues":["问题1","问题2"],"requirements":"通过标准"}
issues 必须具体到修改点；可 PASS 时第一行写 VERDICT: PASS，不要附加其他内容。
```

- **worker 输出与 verify 输出的解析分工**：worker 产出先经 BlockedReader（BLOCKED 锚定 → Loop 级 blocked）；verify 输出先经锚定行检查，若首行是 BLOCKED 锚定则同样走 blockedNode（验证员判定任务本身无法验证），否则经 VerdictReader。两个 Reader 都只查锚定开头，正文中出现关键词不误判。

### 2.4 决策点 4：异常路径（全覆盖）

| 异常 | 判定 | 处置（裁定建议） | 理由 |
|---|---|---|---|
| **worker 执行层 failed**（LLM 错误 / agent 不存在 / LoopGuard L1 终止该 turn） | runSingleSession 返回 Left(FailOutcome) 且非 cancelled | **整 Loop 对外 failed**：`failNode(nodeId, "worker round N: <err>")`，loopState 清理 | ① 与普通节点 failed=一次性终态现状对齐（maxRetries 声明未实现——反馈设计 §0.3 现实核查，本设计不依赖不实现）；② worker 是产能核心，其执行故障重试同参数意义不确定；③ 处置出口 = 分发器/Nebula 对 failed LoopNode 做 NodeEdit 重激活（改 task/loopSpec → 从第 1 轮重跑）或 abandon |
| **verify 执行层 failed**（同上） | 同款 Left | **整 Loop 对外 failed**：`failNode(nodeId, "verify round N: <err>")` | 备选「verify 缺席视为通过（跳过验证直投）」被拒绝：违背 LoopNode 存在意义（要验证才用 Loop）；verify failed 常是配置错误（agent 不存在），静默放行会投递未验证产出。result 写明 verify 失败，分发器可换 verifyAgent 重激活（重激活可改配置；裁定 A 2026-09-03 00:59：plugins 体系落地后按验证域重新分配） |
| **watcher 硬取消内部会话**（TaskStuckWatcher :177-213 桥 Cancelled） | Left 含 "cancelled" | 桥语义 → `cancelNode(nodeId)`（与普通节点被 watcher 释放同路径），整 Loop **cancelled**；**终态时双会话一并销毁（裁定 B）**——被硬取消一侧停转，另一侧（在飞或空闲）随终态清理 | 复用 runWithAgent 现有 cancelled 分流（:265）；cancelled 无投递，与普通节点一致 |
| **NodeCancel（人工/分发器）** | engine.cancelNodeById | **整 Loop cancelled**：LoopNode id 注册在 engine.running（:61）的 Deferred 收信号 → 在飞内部会话停（`AgentCommand.Stop`）；**终态时双会话一并销毁（裁定 B）**——非在飞一侧的空闲会话同样随终态清理 | 语义裁定：NodeCancel 对 LoopNode = 停止整个循环，已耗轮次不补偿、不续跑；重启用 NodeEdit 重激活。NodeCancelTool「仅接受 running」（:757-758）天然成立（LoopNode 运行中内部必有会话在跑） |
| **worker 输出 BLOCKED 锚定** | BlockedReader 命中 | Loop 级 `blockedNode`（§2.7：反馈透传 + 重入路由） | blocked 语义 = 任务/拓扑无法继续，对整个 Loop 成立 |
| **verify 输出 BLOCKED 锚定** | 同上 | Loop 级 blockedNode | 验证员判定任务无法验证 = 任务问题 |
| **重激活语义** | NodeEdit 编辑 failed/cancelled/blocked 的 LoopNode 且 task/agent/loopSpec/in/out/deps 实际变更（deps 纳入——改接一等语义，裁定②） | status 回 wiring/pending → 重跑**从第 1 轮开始**，轮次历史清零；**裁定 B：重激活 = 新会话重新开始**（旧 worker/verify 会话已随前次终态销毁，历史不可续）；blocked 场景 blockCount 保留（沿用反馈设计 §3.1） | 复用 editNode 重激活链（:584-631），loopSpec 变更纳入 actualChange 判定 |
| **编排器自身异常**（LoopExecutor bug 抛错） | executor fiber 兜底 | failNode(nodeId, "loop executor: <err>")（runDetached 同款兜底日志模式 :156-164） | 编排器不许静默挂死 |

### 2.5 决策点 5：watcher 交互（精确重复检测 × 循环轮次）

**关键机制事实（2026-09-03 00:59 裁定 B 后重写）**：LoopGuard 计数器是 AgentState 会话级字段（LoopGuard.scala:84-88），S1/R 在 turn 边界清零（:190-196）——不跨会话累计。裁定 B 下 worker/verify 双会话**贯穿 LoopNode 存续期**（首轮 spawn、跨轮同会话注入续跑）→ LoopGuard 的会话级计数**对 Loop 轮次可见**：worker 连续产出逐字相同会在同一会话内累计 R-text（同 sessionId 跨轮不清零，连续性语义照常），既有 L0/L1/L2 阶梯对跨轮循环可达。

逐形态分析：

| 形态 | 是否触发既有防护 | 分析 |
|---|---|---|
| worker 单轮内部：同参同败 ×8（S1）/ 同参成功 ×10（R-call）/ 逐字同文 ×10（R-text） | **触发** → 该 worker turn 终止 → §2.4 worker failed → Loop failed | 合理兜底：worker 在单轮内空转烧 token，L1 终止正确 |
| verify 意见逐轮变化 → worker 输入不同 → 产出不同 | 不触发 | 正常「修改-验证」迭代，本就不该触发 |
| worker 连续多轮产出完全相同（verify 意见不变 → 输入不变 → 产出不变） | **触发**（裁定 B：持久会话内 R-text 跨轮累计，L0/L1/L2 阶梯可达） | 裁定 B 前的「新盲区」论证作废——既有防线对此形态可见；但其阈值（×10）与处置语义是会话内 turn 级，Loop 层 K=5 仍保留（轮级专属防线，划界见下） |
| verify 长思考 10min 零活动 | TaskStuckWatcher 可能硬取消 | 与普通长思考节点暴露面完全一致（watcher 判据是会话级 Processing+零活动）；误取消 → verify failed → Loop failed 可重激活，**不因 LoopNode 恶化** |

**LoopNode 级跨轮兜底 K=5（2026-09-03 00:59 裁定 B 确认：保留且更关键——作者「靠精确重复检测兜底异常」哲学在 Loop 轮级的落地）**：

- LoopExecutor 维护「上一轮 worker 产出 hash」（sha256 同款指纹，LoopGuard.fingerprint :134-137 复用规范），连续 **K=5** 轮 worker 产出逐字相同 → `failNode(nodeId, "loop content loop: identical worker output for K consecutive rounds — verify feedback is not changing the outcome")`。
- **为何 LoopGuard 已可见跨轮循环，K=5 仍保留且更关键（两层防线划界，裁定 B 明示）**：① 持久上下文也可能原地打转——同会话不等于不重复；② LoopGuard 阶梯语义（S1/R 清零、L0/L1/L2 处置）面向单会话 turn 循环，与 Loop 层「轮」的语义粒度不同——Loop 层 K=5（连续 5 轮逐字相同 → failNode）是 Loop 语义下的确定性死锁兜底；③ 划界：LoopGuard = 会话内 turn 级既有防线，Loop K=5 = 轮级 Loop 专属防线，二者独立触发、不互相替代。
- K=5 依据：verify 意见完全不变 + 产出完全不变连续 5 轮 = 确定性死锁；「产出逐字相同才算、微调即刷新连续计数」给了合理余量（连续性语义与 LoopGuard R-text 作者裁定⑤同构）；5 而非 10——Loop 轮的成本远高于单 turn 一轮（每轮两次完整 LLM 往返；会话复用、无重建开销，但两次完整往返本身不便宜），阈值应更紧。
- 辅助信号（可选，不作为 v1 主判据）：verify FAIL 意见逐字相同连续 K 轮（worker 产出 hash 是更直接的「无进展」证据，主判据只用它，避免双计数器）。
- 与「不设 loop 次数上限」裁定的一致性：该检测不是轮数帽——它不数轮数，只抓「连续 K 轮内容逐字不变」这一病态信号；内容在变的第 100 轮也放行，内容不变的第 5 轮就停。这正是 08-29 裁定「删除模糊的进展判定、保留精确重复检测」的精确延伸。

**编排器残留风险**：LoopExecutor 编排 fiber 不是会话、不注册 agentRegistry → watcher 不覆盖它。若 bug 挂死（不注入续跑也不终态）→ 节点永远 running。缓解：每轮状态迁移都有 logger + WS 事件，「轮次徽标不涨」在 UI 可诊断；v1 接受此残余风险（§6 待确认 #12）。

### 2.6 决策点 6：UI（Flow Map 呈现 + NodeEdit 创建语法）

**Flow Map 呈现（对外单节点卡片原则）**：

| 位置 | 改动 |
|---|---|
| nodeHtml（flowMapTab.js:121-149） | nodeType=loop 的卡片加角标 `⟳`（区别于普通节点）；running 的 loop 节点状态行追加 `轮 N · worker` / `轮 N · verify`（实时阶段） |
| NodePayload（ProjectTypes.scala:77-106） | 追加：`nodeType`（恒带）；`loopRound`（loop 节点才有：running=当前轮，终态=总轮数）；`loopPhase`（running 才有：worker/verify）；`loopLastVerdict`（最近一次 FAIL 摘要 ≤200 字符，运行态才有）——单点序列化三源全通 |
| nodeContentKey（:388-402） | 加 loopPhase/loopRound 段——每轮状态迁移触发卡片重建（实时性） |
| 详情窗 openNodeResultViewer（flowViewers.js:79） | loop 节点加专属段：轮次历史（数据源 = FlowMapEventLog loop_round 事件按 nodeId 过滤）、每轮 verdict、最终 result（= worker 产出原文） |
| WS 事件 | 零新事件类型：每轮迁移 emit nodeUpdated（NodePayload 同构载荷，blockedNode :332 同款做法） |
| v1 不做 | 内部拓扑展开态（worker/verify 不是存储节点，无可展开拓扑；若未来 loopSpec 泛化为内部多节点图再议） |

裁定 B 注记（2026-09-03 00:59）：轮次信息呈现不变——会话数恒 2（worker/verify 各一，贯穿存续期），轮次推进以 loopState 为准；持久会话模型对 UI 零新增呈现负担。

**NodeEdit 创建/编辑语法**（schema :216-241 追加，与 deps 参数同文件分批落）：

```
NodeEdit(
  project, nodename="实现-X",
  nodeType="loop",
  agent="Coder",                    // worker 侧（复用现有字段）
  task="<worker 任务模板>",          // worker 侧（复用现有字段）
  verifyAgent="qa-frontend",        // 新参数：verify 侧 agent——双轨期示例（显式 agent 名）；阶段 2 后 verify 专业化 = 通用 agent + plugins，按验证域分配（裁定 A 2026-09-03 00:59）
  verifyTask="<验收清单模板>",        // 新参数：verify 侧任务
  verifyPreset="p-...",             // 新参数（可选）：verify 侧 preset
  in=[...], out="n-yyy"|"Nebula"|null, deps=[...]   // 与普通节点完全一致
)
```

**校验规则（0 spawn 拦截，全部工具层）**：
1. `nodeType="loop"` 时 agent/task/verifyAgent/verifyTask 全部必填，缺失报错**逐一列出**缺哪几个；
2. 两个 agent 各自 `EntityLoader.loadAgent` 存在性校验（同 createNode :305 先例）；
3. `nodeType` 缺省/`standard` 时传了 verifyAgent/verifyTask/verifyPreset → 拒绝（防拼错 nodeType 后 spec 静默丢失）；
4. loop 节点传 `maxRetries` → 拒绝（无轮帽哲学下无意义；执行层重试未来落地时另议，§6 #10）；
5. loop detect 沿用 findDuplicateDispatch（agent=workerAgent、task=workerTask 归一化比对，零改动）；
6. in/out/deps/skill/mcp/worktree/preset 语义与标准节点完全一致（含 D1、环检测、D2、重激活、**零连接禁止**——§1.2/§1.3 机制对 loop 节点无差别生效；裁定①：loop 节点创建同样必须至少声明 in/deps/out 一项，改接终态同样不得零连接，同码 `EMPTY_NODE_CONNECTION`）；
7. loop 节点编辑：verifyAgent/verifyTask/verifyPreset 变更纳入重激活 actualChange 判定。

（裁定 A 注记 2026-09-03 00:59：校验规则 1-7 本身不变；verifyAgent 双轨期接受显式 agent 名，阶段 2 落地后 verify 专业化 = 通用 agent + plugins 按验证域分配——届时校验语义随 plugins 体系演进，不在本批范围。）

### 2.7 决策点 7：三者协同（deps × LoopNode × 反馈协议 blocked）

**deps × LoopNode**：

| 组合 | 语义 | 落点 |
|---|---|---|
| 下游节点 `deps=[LoopNode]` | 等 LoopNode `status==completed`——只有 verify PASS 才到达；Loop running/blocked/failed 期间下游 pending 可见 | deps 闸门（§1.3）对 loop 节点无差别生效 |
| LoopNode `deps=[其他节点]` | LoopNode 启动前 deps 闸门照常；未满足保持 pending | 闸门在 startNode 内，LoopExecutor 由 startNode 触发，无法绕过 |
| deps/in 指向内部 worker/verify | **不可能**——内部会话不是存储节点，ensureNodeExists 看不到它们，校验天然拒绝 | (a) 方案的结构收益 |
| LoopNode completed 后下游 deps 触发 + 另一节点 in 接同一 LoopNode | deps 下游拿到触发（无输入），in 下游拿到 worker 产出原文投递——两路互不干扰 | §1.0「结果投递优先、完成信号冗余无害」同款 |

**反馈协议 blocked × LoopNode**：

- **边界划分（核心裁定建议）**：**FAIL = 产出质量问题（内部迭代消化，不外溢）；BLOCKED = 任务/拓扑问题（传播停止 + 重入路由）**。verify 每轮 FAIL 打回是 Loop 的内循环本职；worker/verify 任一会话输出 BLOCKED 锚定 = 节点声明「继续无意义」，必须升级为 Loop 级 blocked 走重入。
- **内部 BLOCKED → Loop 级 blockedNode**：worker 轮产出命中 BlockedReader → `blockedNode(nodeId, feedback)`（blockedFeedback 透传、blockCount+1、不结算下游、FeedbackRouter 重入/升级——NodeEngine :314-340 全链复用，LoopExecutor 只是调用方不同）。verify 轮输出首行 BLOCKED 锚定同款。
- **BLOCKED 反馈能否打回 LoopNode 内部 worker？——不能，且不需要**：blocked 是终态，重入分发器的处置动作是 NodeEdit 编辑该 LoopNode（改 task/loopSpec/in/out/deps——改接一等语义，裁定②）→ 重激活 → **新会话从第 1 轮重跑**（裁定 B：旧 worker/verify 会话已随 blocked 终态销毁，与 §2.4 重激活语义一致）。不存在「运行中 Loop 被外部反馈注入下一轮」的通道——**verify FAIL 打回（内部、运行中、无人参与）与 blocked 重入（外部、终态后、分发器参与）是两个不相交的层面**，混设计会同时破坏两者的可推理性。
- **LoopNode 外部被 BLOCKED 时**：上游节点 blocked → in 边不结算 barrier（反馈设计 §2.1③）→ LoopNode 保持 pending 可见（重入分发器 NodeList 可见并处置）✓；deps 上游 blocked → 闸门不满足 → pending ✓。两个方向都自然成立，零新增逻辑。
- **blocked 重入上限沿用**：MaxBlockRoundsPerNode=2 与项目级频率保护（FeedbackRouter :68-84）对 Loop 级 blocked 照常生效——内部 FAIL 循环**不消耗** blockCount（FAIL 不是 blocked），两个计数域完全独立。

**同文件域协同**：deps（NodeDef.deps）与 LoopNode（NodeDef.nodeType/loopSpec）同为 ProjectTypes.scala 加性字段、同一 withDefaults Codec、同一 NodePayload 单点——数据模型一次定型后两批实施互不返工；NodeEdit schema 一处文件、分批加参数。

---

## 3. 冲突窗口与统一实施顺序（专节）

### 3.1 在途工作清单与终态判据（截至本文完稿 2026-09-03 00:50 观测）

| 在途/近期工作 | 载体状态 | 文件域 | 窗口状态与终态判据 |
|---|---|---|---|
| 反馈协议后端 | `54c22d5b` 已进 main | ProjectTypes / NodeEngine / NodeTools(部分) / FeedbackRouter / ProjectActor / FlowMapEventLog + 测试 | **已终态，无窗口**（本文锚点即含它） |
| 分发器单例化 | `76f3808c` 已进 main（**本文写作期间 00:4x 落地**——设计批启动时还是未提交工作区改动） | ProjectActor.scala（+208/-42）+ ProjectDispatcherSingletonSpec.scala（新） | **已终态，无窗口**（写作期间关闭；本文 §0.2 ProjectActor 锚点按 76f3808c） |
| 反馈协议前端 blocked 徽标 | 节点 n-422b75ab（wiring）；主仓无前端改动 | nodeData.js / flowMapTab.js（多 hunk）/ flowCss.js + flowMap.css / flowViewers.js / locales en+zh / chat.js | **开放**。终态判据：n-422b75ab completed + 分支合并 main |
| 2a 沙箱 | 分支 `sandbox-2a` @ `efa9f86e` 已提交（21 文件 +750）；验证节点 n-cbe91f09（wiring） | agent/×4、NodeRunner（+6 ToolContext 链）、NodeEngine（SpawnParams 一处 hunk ~:220）、ProjectActor（同款一处）、core/sandbox/*（三新文件）、tools 五工具+shell+types、GatewayMain、llm/config | **开放**。终态判据：n-cbe91f09 验证通过 + merge main（合并前 rebase 最新 main——ProjectActor 已被 76f3808c 改过，2a 的 +6 行可能需手工对齐） |

> 注：deps 原文档 §0 #6「sandbox-2a 18 文件未提交」已过时——现为 efa9f86e 已提交分支（21 文件）。以本节为准。

### 3.2 文件级冲突矩阵（本设计两实施批 × 开放窗口）

| 本设计批 | 目标文件 | 与前端徽标（开放） | 与 2a（开放） |
|---|---|---|---|
| **deps 后端** | ProjectTypes.scala / FlowMapStore.scala / NodeEngine.scala / NodeTools.scala / 新 NodeDepsSpec.scala | **零交集**（纯后端） | NodeEngine.scala：2a 只动 SpawnParams hunk（~:220），deps 动 startNode（:113-128）/completeNode 尾部/新增 settleDeps——**同文件不同 hunk，git 自动收敛**；其余零交集 |
| **deps 前端** | flowMapTab.js（layout/collectEdges/nodeContentKey/nodeHtml/图例模板）/ flowMap.css / locales en+zh / taskList.js（零改动确认）/ 新 verify-flowmap-deps.cjs | **同文件多 hunk 交叠**（flowMapTab.js 两批都改 layout/render 区；locales 同段加键；flowViewers.js 都可能碰）→ **硬冲突窗口** | 零交集 |
| **LoopNode 后端** | ProjectTypes.scala / NodeEngine.scala / NodeTools.scala / 新 LoopExecutor.scala + VerdictReader / 新测试；**裁定 B 增项：会话生命周期管理（保活 / 注入续跑 / 终态双会话销毁）** | 零交集 | NodeEngine.scala 同上（不同 hunk）；NodeRunner.scala **零改动断言复核后仍成立**（裁定 B）：首轮 spawn 复用现 SpawnParams、不碰 ToolContext 链；跨轮注入走 runWithAgent 的 `UserInput` 通道（:243）与观察桥——均属 NodeEngine.scala 域（已在本批目标文件内），不触及 NodeRunner。影响面：runSingleSession 抽取需含「保活 + 逐轮注入 + 逐轮取产出」变体，Loop 后端工作量略增（§3.3）；对前端徽标批仍零冲突 |
| **LoopNode 前端** | flowMapTab.js（角标/轮次徽标/contentKey）/ flowViewers.js（loop 详情段）/ locales / 新 verify-flowmap-loop.cjs | **同文件交叠**（同 deps 前端批） | 零交集 |

### 3.3 统一实施顺序（含 n-219106db 纳入）

```
第 0 步（前置等待，非本设计工作）
  ├─ 前端徽标节点 n-422b75ab 终态 + 合并 main   ← deps/LoopNode 前端批的硬前置
  └─ （2a merge 独立轨道，随时可合，不阻塞本设计——hunk 不相交）

第 1 批 deps 后端（= 既有节点 n-219106db「实施-deps依赖连接」的前半，task 自带冲突窗口预检协议）
  基线 ≥ 76f3808c 且工作区干净。顺序：ProjectTypes(deps) → FlowMapStore(混合 DFS) →
  NodeEngine(startNode 闸门 + settleDeps) → NodeTools(schema/解析/校验/D1-deps，
  校验含裁定①零连接禁止与 abandon 接受域扩展——§1.2 校验五/六、§1.6) → NodeDepsSpec 用例（T1-T8，§4.1）。
  文件冲突：与 2a 的 NodeEngine hunk 不相交，可与 2a merge 并行（合并顺序先后皆可）。

第 2 批 deps 前端（= n-219106db 的后半）
  硬等待：第 1 批终态 + n-422b75ab 终态合并（flowMapTab.js/locales/flowViewers 交叠）。
  顺序：collectEdges/layout/nodeContentKey → 脚注 → 图例/CSS → i18n → verify-flowmap-deps.cjs。

第 3 批 LoopNode 后端（建议新建节点「实施-LoopNode」，排在 n-219106db 终态之后触发）
  硬等待：第 1 批终态——①文件域 60% 重叠（ProjectTypes/NodeEngine/NodeTools）；
  ②语义依赖：LoopExecutor 的启动走 startNode deps 闸门、D1 补触发与 runDetached 语义，
  先落闸门再落 Loop，避免 Loop 绕过未完成的 deps 语义。
  顺序：ProjectTypes(nodeType/loopSpec) → VerdictReader+LoopExecutor（新文件，含裁定 B
  会话生命周期管理：首轮 spawn / 跨轮同会话注入续跑 / 终态双会话销毁）→
  NodeEngine(startNode loop 分流 + runSingleSession 抽取为「保活 + 逐轮注入」变体) →
  NodeTools(nodeType/verify* 参数) → LoopNode 后端测试（§4.2）。
  工作量注记（裁定 B 2026-09-03 00:59）：Loop 后端较原估略增——会话生命周期管理为新增
  代码面（保活/注入/销毁）；但注入续跑复用 runWithAgent 的 UserInput 通道（:243）与分发器
  注入排队先例（ProjectActor :188-201），非从零造机制，增量可控。

第 4 批 LoopNode 前端 + E2E
  硬等待：第 3 批终态 + n-422b75ab 终态合并。
```

**deps 实施与 LoopNode 实施的先后关系（明确裁定建议）**：**deps 先、LoopNode 后，串行不并行**。文件域大面积重叠使并行必然产生手工冲突解决；语义依赖（Loop 启动依赖闸门）使顺序天然单向；两批各自独立可验收（deps 五用例 / Loop 九用例），串行回滚边界干净。

**n-219106db 纳入说明**：该节点（「实施-deps依赖连接」，wiring，等 deps 设计结果触发）对应本文第 1+2 批。其 task 自带冲突窗口预检协议——触发时应核对：① 基线 ≥ 76f3808c；② n-422b75ab 状态（决定第 2 批能否随即做，若未终态则第 1 批先行、第 2 批挂起）；③ sandbox-2a 是否已合（若未合，NodeEngine.scala 改动后其 merge 需重跑冲突检查——hunk 不相交预期自动收敛）；④ **补录裁定①语义属本节点实施范围**——零连接校验（§1.2 校验五/六）、abandon 接受域扩展（§1.6）、负向用例 T6-T8（§4.1）、存量零连接创建用例排查（§4.4）。LoopNode 实施节点建议等 n-219106db completed 后再建再派。

### 3.4 回滚单元划分

- **回滚粒度 = 批**：每批一组 commit，`git revert` 按 commit 序列整批回。批内 commit 按 §3.3 顺序逐个可 revert（数据模型 → 存储 → 引擎 → 工具 → 测试的依赖序）。
- **依赖方向**：LoopNode 批依赖 deps 批代码（闸门/parseIn 复用）。回滚顺序约束：**回 deps 批必须先回 LoopNode 批**（正向实施 deps→Loop，反向回滚 Loop→deps）。
- **feature flag 评估：不需要**。deps/loopSpec/verify* 全部是加性可选字段，缺省 = 旧行为（withDefaults 兜底）；前端改动是纯展示（边样式/徽标/图例）。回滚即回退，无行为开关需求。
- **回滚安全性前提（实施批须验证）**：旧代码读含 `deps`/`loopSpec` 新键的 flow-map.json 必须不报错——circe Scala 3 derived decoder 只查找已知字段、忽略未知键；**建议每批加一条「回滚安全」单测**（旧版 Codec 样本含新键解码成功），锁定该前提。
- **数据回滚**：已落盘的 deps/loopSpec 键在回滚后保留在 flow-map.json 中无害（被忽略）；再次升级时数据仍在，无迁移负担。

---

## 4. 统一测试方案

### 4.1 deps 批（NodeDepsSpec，复用 NodeBarrierDeliverySpec 基建 :54-66 CaptureLlm / 临时 dataRoot / 真实 spawn 轮次）

| # | 用例 | 关键断言 |
|---|---|---|
| T1 | deps 触发且不投递 result | B 启动并完成；**B 输入不含 `=== Node A ===` 段**；A.result 不变 |
| T2 | in+deps 混合 barrier | C 仅在 A、B 都终态后启动；C 输入**含** in 上游段；B 完成前 C 保持 pending |
| T3 | 混合图环检测 | store 层 wouldCreateCycle 混合链为 true；NodeEdit 返回 cycle 错误，store 无新边 |
| T4 | D1-deps 补触发（含归档变体） | 活动区接线、归档后接线两条路径 B 都立即启动；B 输入无 A result |
| T5 | failed/cancelled 不触发 | A failed 后 B 保持 pending；对照：同拓扑 in 边时 B 被 collect 结算触发（锁定 §1.3 差异） |
| T6 | 零连接创建被拒（裁定①负向，§1.2 校验五） | NodeEdit create 无 in 无 deps 无 out → ToolError `EMPTY_NODE_CONNECTION`（消息列三参数），store 无新节点；对照：仅 `out="Nebula"` 创建成功（Nebula 计入连接下限） |
| T7 | 改接至零连接被拒（裁定①负向，§1.2 校验六） | 仅持 in 边的 wiring 节点编辑清空 in → 拒绝（同码，改接侧文案）；对照：清空 in 保留 out → 成功（断开合法、result 保留语义不变） |
| T8 | 清场 abandon 接受域扩展（裁定①待实施语义，§1.6） | wiring 节点 abandon → cancelled + display TTL + `abandoned` 审计事件，TTL 后归档；running 节点 abandon 仍拒绝（走 NodeCancel）；被退役节点上游其后完成 → startNode 幂等跳过 |

### 4.2 LoopNode 批（LoopNodeSpec，同基建）

| # | 用例 | 关键断言 |
|---|---|---|
| L1 | 首轮 worker 输入构成 | CaptureLlm：worker 输入 = task + in 上游段（若有）+ BLOCKED 脚注 |
| L2 | verify PASS → 投递 | out 投递内容 = worker 最终产出原文；result 同；下游输入 `=== Node <LoopNode> ===` 段 = 产出原文 |
| L3 | verify FAIL → 返工轮注入（裁定 B） | 第 2 轮 worker 为**同会话注入续跑**：sessionId 与首轮相同；注入内容 = 【LoopNode 返工 · 第 2 轮】标头 + issues 逐条 + requirements + 协议脚注，**不含**上轮产出全文（会话上下文已持有，模板二简化） |
| L4 | FAIL 后 PASS | completed；总轮数进 loopRound |
| L5 | verify 文法畸形降级 | 无锚定行 → PASS 直通（completed）；锚定 FAIL + JSON 畸形 → 打回且意见为占位串 |
| L6 | worker/verify 输出 BLOCKED | Loop 级 blocked；blockedFeedback 透传；FeedbackRouter 路由被调（tiny 窗口注入） |
| L7 | 执行层 failed | worker LLM 抛错 → Loop failed（result 含轮次+错误）；verify 抛错 → Loop failed；watcher 桥 Cancelled → cancelled |
| L8 | 跨轮内容死循环 | mock verify 恒 FAIL + worker 恒产出相同 → 第 K 轮 failed，result 含 loop content loop 说明 |
| L9 | NodeCancel | running LoopNode 收 cancel 信号 → 在飞会话停 → cancelled、无投递；**双会话一并销毁、无滞留会话（裁定 B）** |
| L10 | 创建校验 | nodeType=loop 缺 verifyAgent → 报错列缺失项；standard 传 verify* → 拒绝；loop detect 对 workerAgent+task 生效；loop 节点 in/deps/out 全缺 → 同款零连接拒绝（裁定①，与 standard 同码同批，§2.6 校验 6） |
| L11 | 持久上下文（裁定 B） | 第 2 轮注入续跑的请求消息历史含首轮 task/上游段与第 1 轮 verify 意见（同会话累积，非模板重注入）——worker「记得自己做过什么、verify 提过什么」 |
| L12 | 终态会话销毁（裁定 B） | PASS 投递 / cancelled / failed 三终态各验一条：终态后 worker/verify 双会话均注销（agentRegistry 无滞留注册，反向断言 :230-242 注册链） |
| L13 | 两层防线独立可达（裁定 B） | a) worker 同会话连续逐字相同产出 → LoopGuard R-text 阶梯可达（会话内计数累计）；b) 连续 K=5 轮逐字相同 → Loop 层 failNode。两条判据独立构造、独立断言，互不依赖 |

### 4.3 协同用例

| # | 用例 | 断言 |
|---|---|---|
| C1 | deps=[LoopNode] 下游触发 | Loop completed（verify PASS）后下游启动；下游无 deps 输入注入 |
| C2 | LoopNode deps 未满足 | Loop 不启动，pending 可见 |
| C3 | LoopNode blocked → deps 下游 | 下游保持 pending（blocked ∉ completed） |

### 4.4 前端 harness 与全量回归

- `verify-flowmap-deps.cjs`：deps 边虚线/空心箭头断言、图例 6 档、增量事件边状态迁移、等待脚注、归档上游裸 id ✦、亮暗双主题截图（打桩 + MockWebSocket 模式，不起端口不碰 8080）。
- `verify-flowmap-loop.cjs`：loop 角标、轮次徽标随 nodeUpdated 迁移（worker→verify→轮+1）、详情窗轮次历史、双主题截图。
- 全量回归：后端 `nebflow.core.project.* / nebflow.core.tools.* / nebflow.core.processor.*`（LoopGuard 回归——LoopNode 不改它但共享哲学）；NodeEventPushSpec 字段集断言两批各加一次（`deps`；loop 四字段）；`check-js-types.mjs` + `verify-i18n-sweep.cjs` + 既有 verify-* 不回归；回滚安全单测（§3.4）。
- 存量用例排查（裁定①回归项，第 1 批）：主仓既有测试如存在「仅 task、零连接创建节点」的用例（校验生效前的合法形态），实施时同步补连接参数或改写为 T6 式负向断言——列入第 1 批回归清单，避免校验上线即打破存量套件。

---

## 5. 观察记录（仅记录，不处理）

1. **拓扑分歧（宿主内存 vs 磁盘）**：磁盘 `.nebflow/flow-map.json` 00:35 观测为 **15 节点**，含 deps 链三节点：n-219106db（实施-deps依赖连接，wiring）、n-c1f72554（设计-deps依赖连接，completed）、n-4e87d4d1（本文节点，running）。作者报部分运行视图（NodeList）为 13 节点、不含该链。可能机制与宿主 beta.54 未重建（§0.1 #3）及内存 Ref/磁盘 write-through 的可见性窗口有关——**本文仅记录，不修图不定论**；分歧若持续，建议宿主重建后复测（NodeList 与磁盘 JSON 节点集比对）。
2. **并行演进观测**：分发器单例化在本文写作期间由工作区未提交改动变为 commit `76f3808c` 进 main（00:4x）——主仓多节点并行推进的事实样本；本文 §3 已按终态后状态编写。
3. **sandbox-2a 状态演进**：deps 原文档成文时为「与 main 同 commit + 18 文件未提交」；本文观测时已为提交分支 efa9f86e（21 文件 +750/-143），验证节点在途。原 docs 描述过时处以此为准。
4. 主仓工作区状态（本文完稿时）：clean（仅 2 个历史未跟踪 CONTRIBUTING 文件）——**本文对其零写入**。

---

## 6. 待作者确认清单（逐条：选项 + 建议；2026-09-03 00:59 状态：全部已定——#5 已裁定（裁定 A），#2/#6/#7 已确认（带裁定 B 注记），#1/#3/#4/#8-#13 已确认（作者「其他按建议来」）；除 #5 改写、#7 理由更新外，建议内容未改）

| # | 决策点 | 选项 | 建议 |
|---|---|---|---|
| 1 | verify 文法畸形降级方向 | a) 无锚定行→PASS 直通；b) 畸形→记 FAIL 重试 verify 一次；c) 畸形→整 Loop failed | **a**（与 BLOCKED 无损降级对称，§2.3 风险分析）；FAIL 锚定+JSON 畸形无论 a/b/c 都建议「FAIL 保留 + 占位意见」——**已确认（2026-09-03 00:59 作者「其他按建议来」）** |
| 2 | Loop 级跨轮重复检测阈值 | K=3/5/10 或不加 | **K=5**；不加则放弃无轮帽哲学的兜底（作者 23:58 裁定明确要靠精确重复检测兜底）——**已确认（2026-09-03 00:59）**：裁定 B 下保留且更关键（持久上下文也可能原地打转） |
| 3 | 内部 worker/verify 输出 BLOCKED 的处理 | a) 透传 Loop 级 blocked + 重入；b) 当 worker failed；c) 忽略当普通产出 | **a**——「任务无法继续」判定对整个 Loop 成立，重入机制正是解药——**已确认（2026-09-03 00:59 作者「其他按建议来」）** |
| 4 | PASS 后 out 投递内容 | a) worker 最终产出原文；b) 产出+验证附注；c) verify 渲染报告 | **a**（与普通节点 result 语义同构；下游输入段干净）——**已确认（2026-09-03 00:59 作者「其他按建议来」）** |
| 5 | verify 侧 agent/preset 选型 | **已裁定 2026-09-03 00:59（裁定 A）**：verify 专业化 = **通用 agent + plugins 动态分配**（与阶段 2 裁定⑧「专业化=通用 agent+Plugins」一致），执行期按验证域经 plugins 体系分配；原「verifyAgent 按域选 qa-*/Coder/Explorer」建议**废弃** | **verifyAgent 字段保留但语义升级**：双轨期（阶段 2 落地前）仍接受显式 agent 名，阶段 2 后走通用 agent + plugins（按验证域分配）；**verifyPreset 单列能力保留**（§2.1 字段设计，node.preset 归 worker）；「是否默认低价/低温 preset」子问题本次裁定未覆盖，仍待作者定（不冒认） |
| 6 | LoopNode × NodeCancel 语义 | a) 整 Loop cancelled；b) 仅停当前轮待续 | **a**（§2.4；轮间不设续跑检查点，重激活即全新重跑）——**已确认按建议（2026-09-03 00:59）**；裁定 B 注记：终态双会话销毁，重激活 = 新会话从第 1 轮重跑 |
| 7 | LoopNode 重激活后轮次处理 | a) 从第 1 轮重跑、历史清零；b) 从失败轮续跑 | **a**——**已确认按建议（2026-09-03 00:59）**；理由随裁定 B 更新：重激活即 LoopNode 重启，旧 worker/verify 会话已随前次终态销毁，历史不可续（loopState 本就不持久化），第 1 轮全新开始（见 §2.4） |
| 8 | 轮次状态持久化 | a) 仅内存 + FlowMapEventLog 审计；b) loopState 落 flow-map.json | **a**（b 会让 NodeDef 携带大体积运行态，破坏「终态即结果」的存储纪律；事件日志已可追溯）——**已确认（2026-09-03 00:59 作者「其他按建议来」）** |
| 9 | deps×LoopNode 组合语义复核 | 见 §2.7 表 | 四条组合语义如表（结构上天然成立，请作者确认无异议）——**已确认（2026-09-03 00:59 作者「其他按建议来」）** |
| 10 | loop 节点是否接受 maxRetries | a) 拒绝；b) 接受但仅对执行层 failed 生效 | **a**（无轮帽哲学下无意义；执行层重试落地时统一另议——反馈设计 §5 同款划界）——**已确认（2026-09-03 00:59 作者「其他按建议来」）** |
| 11 | verify 长思考与 TaskStuckWatcher 10min | a) 接受现状判据；b) Flow 会话 watcher 阈值配置化 | **a**（与普通节点暴露面一致，不因 Loop 恶化；b 是独立议题不绑入本批）——**已确认（2026-09-03 00:59 作者「其他按建议来」）** |
| 12 | LoopExecutor 编排 fiber 挂死的残余风险 | a) v1 接受（UI 轮次徽标可诊断）；b) 加编排层心跳/watcher 接入 | **a**（低概率高可诊断；b 增加机制面，等实际案例再议）——**已确认（2026-09-03 00:59 作者「其他按建议来」）** |
| 13 | 统一实施顺序复核 | §3.3 顺序（deps 后端→deps 前端→Loop 后端→Loop 前端，两处硬等待） | 如 §3.3；n-219106db 触发时点与「实施-LoopNode」新建节点安排请作者确认——**已确认（2026-09-03 00:59 作者「其他按建议来」）**；顺序不变，Loop 后端批内工作量随裁定 B 微调（§3.3） |

---

## 附：自检核对

- [x] 23:51 五点逐条落点：①数据模型→§1.1；②NodeEdit→§1.2；③NodeEngine 结算（含 D1 悬空补接线、failed/cancelled 不触发差异）→§1.3；④前端边区分+增量兼容→§1.4；⑤测试→§1.5/§4.1
- [x] 23:58 七决策点逐条落点：1 数据模型→§2.1；2 执行语义（状态机/输入模板/PASS 投递裁定）→§2.2；3 verify 文法（解析器级定义+示例+降级）→§2.3；4 异常路径（worker/verify failed、NodeCancel、retry、整 Loop 终态）→§2.4；5 watcher 交互（精确重复×轮次、零活动适用性）→§2.5；6 UI（呈现+NodeEdit 语法+校验）→§2.6；7 三者协同→§2.7
- [x] verify 文法有精确定义（锚定行正则/四态判定表/JSON schema）与解析失败降级（含风险分析）→§2.3
- [x] 异常路径全覆盖（八类异常逐一处置）→§2.4
- [x] 冲突窗口专节有文件级清单、矩阵与顺序建议（含 n-219106db 纳入与 deps/Loop 先后）→§3
- [x] 全文无占位符；代码锚点全部实核 @ 2f32d0a6（ProjectActor 按 76f3808c 复核）
- [x] 不确定处进 §6 待确认清单（13 条，含 deps×LoopNode 组合、verify 选型、NodeCancel 交互等）
- [x] 主仓零写入；唯一产出本文件 + deps 原文档头部注记
- [x] 2026-09-03 00:36 补录裁定①（禁止节点空连接）落点：下限语义与清场 abandon→§1.6（新建）+§1.0 注记；创建/改接校验与错误码 `EMPTY_NODE_CONNECTION`→§1.2 校验五/六；startNode 守卫降级为纵深防御→§1.3；前端无空态→§1.4；锚点补注→§0.2；loop 同规则→§2.6 校验 6；实施范围→§3.3 第 1 批 + n-219106db 说明④；测试 T6-T8 + L10 扩展 + §4.4 存量排查→§4
- [x] 补录裁定②（改接一等操作语义）落账确认：§1.2（既有机制清单 + 零连接为唯一新增约束）；§2.4 重激活/§2.7 重入处置补 deps；测试 T7
- [x] 补录裁定与 §6 十三条逐条核对：**无直接冲突，无需「新裁定优先」注记**（§6 各条均不依赖「零连接可驻留」语义；§6 清单本身未改动）
- [x] 全文无占位符（§2.7 排版残留「混_design/可推理型」已顺手修正为「混设计/可推理性」）
- [x] 2026-09-03 00:59 裁定 A（verify 选型 = 通用 agent + plugins，verifyPreset 单列保留）落点：§6 #5 改写；§2.1 LoopSpec verifyAgent/verifyPreset 注释；§2.4 verify failed 行重激活注记；§2.6 语法示例「双轨期示例」标注 + 校验规则不变注记
- [x] 2026-09-03 00:59 裁定 B（LoopNode 内部双会话贯穿存续期、终态销毁）落点：§0.2 LoopGuard 推论反转；§2.1 方案 (a) 表述 + 理由链（UserInput :243 / 注入排队 :188-201 先例）；§2.2 状态机/模板二简化/模板三注记/上下文保留改写；§2.4 watcher·NodeCancel 双会话销毁 + 重激活新会话语义；§2.5 两层防线划界重写；§2.6 会话数恒 2 注记；§2.7 blocked 重入 = 新会话重跑；§3.2 冲突矩阵增会话生命周期管理项 + NodeRunner 零改动复核；§3.3 Loop 批工作量注记；§4.2 L3/L9 校准 + L11-L13 新增
- [x] §6 十三条确认状态核对：#5 已裁定（裁定 A）；#2/#6/#7 已确认（带裁定 B 注记）；#1/#3/#4/#8-#13 已确认（作者「其他按建议来」）——除 #5 建议改写、#7 理由更新外，建议内容未改
- [x] 00:59 批后旧语义复查：grep "每轮"/"新会话"/"每轮各一个"/"无记忆" 逐处复核——旧「每轮新会话」论证残句清零，残留命中均为裁定 B 新语义或元描述（见变更记录与 §2.5 划界表述）
