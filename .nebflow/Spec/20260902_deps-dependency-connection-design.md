# deps 依赖连接（Dependency Edge）设计——实施前置细化

> **注（2026-09-03）：本文已并入 `20260902_flowmap-engine-evolution-design.md`（统一设计批：deps + LoopNode + 反馈协议协同，作者 2026-09-02 23:58 裁定），以主文档 §1（deps 章）为准。本文保留作实现级细节参考（file:line 注释最全）；另注意其 §0 #6 对 sandbox-2a 的描述已过时（现为已提交分支 efa9f86e，以主文档 §3 为准）。**

- 日期：2026-09-02 23:51（作者裁定）→ 本文 2026-09-03 细化
- 作者裁定原文：现有 out 连接 = 输出连接（上游结果投递给下游）；新增**依赖连接（deps）**——下游不使用上游输出，只用其 task，仅依赖「上游节点完成」来触发执行。
- 基线：主仓 HEAD `2f32d0a6`（main，含 c467592f / 54c22d5b）。本文所有 file:line 以该 HEAD 为准。
- 范围：A 数据模型 / B NodeEdit / C NodeEngine 结算 / D 前端 Flow Map / E 测试方案。只写设计，不改主仓（主仓只读）。

---

## 0. 现状事实（写进设计，实施节点须知）

| # | 事实 | 证据 |
|---|------|------|
| 1 | 主仓 HEAD `2f32d0a6`；`git status` 干净（仅 2 个未跟踪 CONTRIBUTING 文件） | `git log -1` / `git status` |
| 2 | c467592f（barrier 投递根因修复：终态化现读 fresh NodeDef + edit 追加补投递 + parseIn/parseOut 宽容解析）**已在 main** | `git show c467592f --stat`（改 FlowMapStore/NodeEngine/NodeTools + 回归 NodeBarrierDeliverySpec） |
| 3 | **运行宿主 v1.4.1-beta.54 未重建**——宿主进程不含 c467592f。本文设计的 deps 语义随**下次重建**生效；在此之前宿主上验证 barrier 行为会得到修复前的旧语义 | 环境：Nebflow v1.4.1-beta.54（PID 17392）vs HEAD |
| 4 | 反馈协议后端 54c22d5b 已终态（Node→分发器反馈路径与分发器重入协议） | `git log`；NodeEngine.blockedNode → FeedbackRouter.route（NodeEngine.scala:314-340） |
| 5 | 分发器单例化正在主仓实施（TaskTool/MailTool/ProjectActor 域）。**当前 HEAD** 的触发路径：TaskTool.scala:78 / MailTool.scala:195,253 → `ProjectActor.TriggerDispatcher` → dispatchTask（ProjectActor.scala:226-229）→ spawnDispatcher（ProjectActor.scala:249-323，每触发 spawn 一个单次分发器会话） | 见 §C.6 交集验证 |
| 6 | 2a 沙箱分支在 worktree `.nebflow/worktrees/sandbox-2a`（branch sandbox-2a，当前与 main 同 commit，未提交改动 18 文件）：agent/*、NodeRunner、NodeEngine、ProjectActor、BashTool/EditTool/GlobTool/GrepTool/MultiEditTool/ReadTool/WriteTool、shell、types、GatewayMain、llm/config | `git worktree list`；worktree `git diff --stat` |
| 7 | 存量断链教训：归档中 5 个 completed 节点结果从未投递给仍 wiring 的下游（上游被 TTL 隐藏后下游看起来孤立）——D1 修复由此来（NodeTools.scala:408-425 注释），前端可见性由 visibleNodes 过滤（flowMapTab.js:203-209） | NodeTools.scala:408-425；FlowMapStore.scala:107-111 |

---

## 1. 语义定义：三种连接对照

| | out（输出连接，现有） | in（barrier 入边，现有） | deps（依赖连接，本设计新增） |
|---|---|---|---|
| 权威存储 | 上游节点 `out: Option[String]`（单值） | 下游节点 `in: List[String]`（与上游 out 双写镜像，setOut 维持一致，NodeTools.scala:93-106） | **下游节点 `deps: List[String]`（本设计新增，下游单侧持有，不回写上游）** |
| 等什么 | — | 等上游「结果投递」：deliveredTo 累计（NodeEngine.scala:409-410） | 等上游「完成信号」：上游 `status == completed` |
| 投递什么 | 上游 result 注入下游输入（buildInput，NodeEngine.scala:133-143） | 同左（in 边的结果投递路径） | **什么都不投递。下游输入 = 自身 task（自足），绝不注入上游 result** |
| 上游 failed | out=Nebula → 通知；out=节点 → deliverFailed 占位结算（collect 语义，NodeEngine.scala:415-439） | **计入 barrier 照常触发下游**（占位符继续，§2.4 默认 collect） | **不触发**。下游保持 pending/wiring 可见（§C.4） |
| 上游 cancelled | 无投递 | 无投递 → barrier 永久悬挂（现状行为，cancelNode 不结算，NodeEngine.scala:365-383） | 不触发，下游 pending 可见（与 in 的悬挂不同，下游至少是可诊断的可见等待态） |
| 上游 blocked | 不结算（传播停止是特性，NodeEngine.scala:312 注释） | 不结算 | 不触发（blocked ∉ completed） |
| 典型用途 | 结果流水线 | 结果汇合（merge） | 「先跑完 A 再跑 B，但 B 不需要 A 的产出」（如：先建 worktree 再开工；先出设计稿再动工） |

**in + deps 并存规则**：同节点可同时有 in 和 deps。同一上游既 in 又 deps：**结果投递路径优先承载触发**（in 边照常 deliveredTo + barrier），deps 判定被同一完成事件幂等满足，二者汇合到同一个 startNode 入口（幂等跳过已运行/终态，NodeEngine.scala:119-120），**冗余无害，不重复 spawn**。NodeEdit 不拒绝这种重叠（校验成本 > 收益）；工具文档注明「需要结果用 in，只需要完成信号用 deps，两者都要就都写」。

---

## A. 数据模型

### A.1 NodeDef 增 deps 字段

定义点：`src/main/scala/nebflow/core/project/ProjectTypes.scala:41-65`（case class NodeDef）。
插入位置：`out` 字段（:51）之后，`deliveredTo`（:52）之前：

```scala
out: Option[String] = None,
deps: List[String] = Nil,     // ← 新增：依赖连接（下游持有；只等完成信号，不投结果）
deliveredTo: List[String] = Nil,
```

**全部引用链（锚点）**：

| 引用处 | 位置 | 是否需改 |
|---|---|---|
| NodeDef 定义 + Codec | ProjectTypes.scala:41-69 | 增字段（Codec = `Configuration.default.withDefaults`，:68——**缺省字段反序列化为 Nil，向后兼容**） |
| 节点 JSON 序列化单点 | ProjectTypes.scala:76-106（`NodePayload.buildNodeJson`） | 增 `"deps" -> node.deps.asJson`（NodeList 工具 / REST flow-map / WS 事件三源共用此单点，一处加全通） |
| FlowMapState / FlowMapArchive | ProjectTypes.scala:108-128 | **不改**（内嵌 NodeDef 自动携带） |
| NodeEngine | NodeEngine.scala:113-128（startNode）、:279-304（completeNode）、:388-413（deliverOut） | 见 §C |
| NodeTools / NodeEditTool | NodeTools.scala:216-241（schema）、:280-440（create）、:471-683（edit） | 见 §B |
| FlowMapStore 环检测 | FlowMapStore.scala:64-78（wouldCreateCycle） | 见 §B.3 |
| 磁盘序列化 | FlowMapStore.scala:99-105（persistState/persistArchive，circe 自动派生） | **不改**（NodeDef Codec 派生自动携带 deps） |
| REST 端点 | RestApiRoutes.scala:310-316（GET /projects/:name/flow-map → NodeTools.buildNodeListPayload，NodeTools.scala:166-187） | **不改**（走 NodePayload 单点） |
| 前端 | flowMapTab.js 多处、flowMap.css、locales/*.js、taskList.js | 见 §D |

### A.2 序列化形态与向后兼容

- JSON 形态：`"deps": ["n-aaaaaaaa", "n-bbbbbbbb"]`（字符串数组；与 `in` 同构）。
- **缺省 = 空 = 无依赖**：旧 flow-map.json / flow-map-archive.json 无 `deps` 键 → `withDefaults` Codec 解码为 `Nil`（ProjectTypes.scala:68），加载零迁移。加载净化 `normalizeOut`（FlowMapStore.scala:110-111）不动 deps（deps 无 "null" 字面串问题——数组类型 + NodeEdit 解析层过滤，见 §B.2）。

### A.3 in / deps / out 的存储权威性差异（关键设计约束）

- in 边是**双写镜像**（上游.out 与 下游.in 由 setOut 同事务维持一致）→ 环检测沿 out 单向 DFS 即可覆盖。
- deps 边是**下游单侧持有**（上游.out 不动——这正是「不用其输出」语义的结构体现：上游甚至不知道自己被依赖）→ **环检测与反向触发都必须显式处理 deps**：
  - 环检测 DFS 须扩展（§B.3）；
  - 上游完成时靠**反向扫描**活动区找依赖者（§C.3，活动区 nodes 是小 Map，`values.filter(_.deps.contains(id))` 足够）。

---

## B. NodeEdit：deps 参数

### B.1 参数与 schema

- schema（NodeTools.scala:216-241）在 `in`（:224-227）后加 `deps`，形态与 in 完全一致：`oneOf: string | array<string>`；description 注明「completion-only dependency: downstream runs when these upstreams complete; their results are NOT injected. Provided value replaces the full deps list」。
- `call`（NodeTools.scala:250-276）读取 `input("deps")`，透传 createNode/editNode（两签名各加一个参数）。

### B.2 解析：parseIn 宽容解析先例直接复用

- **不新写解析器**。c467592f 的 `NodeTools.parseIn`（NodeTools.scala:68-90，原生数组 / JSON 数组字符串 / 逗号串三形态，trim + 空段过滤，解析失败报错附原文）对 deps 完全适用：`val depsIds = NodeTools.parseIn(depsJson)`。
- 语义选择（**裁定建议：replace-on-provide**，与 in 的 append 不同）：
  - 未传 deps → 不改动（与 in/out 一致）；
  - 传了（任何形态）→ **整体替换** deps 列表；空数组 / null → 清空。
  - 理由：in 边的删除有上游侧句柄（上游 setOut 改投即从旧目标 in 移除，NodeTools.scala:97-100）；deps 边上游侧无句柄，若做 append-only 则**永远无法删除错接线**。deps 是下游自持列表，replace 是唯一完整 CRUD 原语，且对分发器幂等。

### B.3 校验

1. **目标存在**：`NodeTools.ensureNodeExists`（NodeTools.scala:36-40）——已走 `store.findNode`（活动区优先、归档区兜底，FlowMapStore.scala:38-42），deps 引用归档节点天然合法。
2. **无环检测（混合图）**：现 `wouldCreateCycle`（FlowMapStore.scala:64-78）只沿 out DFS（:74-76）。in 边因与 out 双写镜像可被覆盖，**deps 边覆盖不到**。改法（单点改，全部既有调用点自动获得混合图覆盖）：

```scala
// FlowMapStore.wouldCreateCycle 内 reachable 扩展：
// 后继 = out 目标（跳过 Nebula） ∪ { m | m.deps.contains(start) }
s.nodes.get(start).flatMap(_.out).filter(_ != "Nebula") match { ... }          // 现有
++ s.nodes.values.filter(_.deps.contains(start)).map(_.id)                     // 新增：deps 反向展开
```

   调用点无需改：create 路径（NodeTools.scala:335,340）、edit 路径（:517,568）全走同一方法。**DFS 必须覆盖 in+deps 混合图**——本改法下 out 展开（覆盖 in 镜像）+ deps 反向展开，A -in→ B -deps→ C 再接 C→A 一类混合环全部被拒。
3. **目标 running 冻结语义**：与 in 的「输入已冻结」对齐（`ensureTargetNotRunning`，NodeTools.scala:42-48，报错文案 "input is frozen. NodeCancel it first"）。deps 对 running 下游的语义：running 节点不需要再被触发，mid-run 改 deps 无意义且会造成触发态漂移——**编辑 running 节点时传 deps → 同款拒绝**（editNode 的 guard 段，NodeTools.scala:504-511，加 deps 分支）。给下游加 deps 的上游若是 running：合法（等它完成正是 deps 语义）。
4. **blocked 重激活联动**：editNode 重激活分支（NodeTools.scala:530-537, 584-632）的 `actualChange` 判定（:536）加入 `depsChanged`；重激活后的补投递链（:613-631）**不改**——它对 in 上游重投后调 startNode，startNode 内的 deps 闸门（§C.2）自动把关：deps 未满足则保持 pending，满足了才放行。
5. **D1 悬空补接线对 deps 同样适用**：create（NodeTools.scala:415-425）/ edit（:654-671）路径在接线后对「已终态且有结果的 in 上游」补投递。deps 版等价动作：接线后若 `deps 全满足 && in barrier 已归零 && 自身 wiring/pending` → `runDetached(startNode(nodeId))`。这正是「接线时上游已 completed → 立即触发」的落地（与 c467592f「edit 追加补投递」同型，只是不投 result 只触发）。归档上游同样命中（findNode 兜底）——这是存量断链教训的直接应用。
6. **创建即运行的判定**（NodeTools.scala:374, 430-432）：入口节点判定 `task.isDefined && ins.isEmpty` **不改**（deps 不产生输入，不构成「可立即运行」——deps 闸门在 startNode 内兜底，即使误判也会被拦）。

---

## C. NodeEngine barrier 结算扩展

### C.1 现有 barrier 满足判定与投递路径（定位）

| 环节 | 位置 | 行为 |
|---|---|---|
| 触发入口（幂等闸门） | NodeEngine.scala:113-128 `startNode` | running/终态跳过（:119-120）；wiring 空节点（无 task 无 in）不空跑（:123） |
| 输入构造 | NodeEngine.scala:133-143 `buildInput` | 自身 task + 各 in 上游 result（`=== Node <name> ===` 头）+ 协议脚注。**deps 不进 buildInput——这是「不使用上游输出」的代码落点** |
| 完成终态化 | NodeEngine.scala:279-304 `completeNode` | mutate 事务内现读 fresh 写 status/result/ttl（c467592f 修复），随后 deliverOut |
| 投递 + barrier 结算 | NodeEngine.scala:388-413 `deliverOut` | deliveredTo 增量（:401-406）→ `allArrived = tn.in.forall(deliveredTo.contains)`（:409）→ `allArrived && 非 running → startNode`（:410） |
| 显式补投递 | NodeEngine.scala:91-110 `deliverOutTo` | 同款 barrier 复查（:107-109） |
| failed 结算 | NodeEngine.scala:342-363 `failNode` → :415-439 `deliverFailed` | in 边：failed 上游 deliveredTo 也计数 → barrier 归零照常触发下游（§2.4 默认 collect，错误串作为上游 result 注入） |
| cancelled | NodeEngine.scala:365-383 `cancelNode` | **无任何投递** → in barrier 永久悬挂（现状） |
| blocked | NodeEngine.scala:314-340 `blockedNode` | 不结算下游（:312 注释「传播停止是特性」） |

### C.2 deps 闸门：单点放进 startNode

**核心改法：deps 满足判定作为 startNode 的内部前置条件（单权威闸门），而非散在各投递路径。**

```scala
// startNode（NodeEngine.scala:113-128）内，status 幂等检查之后：
// deps 闸门：任一依赖上游未 completed → 保持 pending（不报错，静默等待）
depsOk <- node.deps.traverse(id => store.findNode(id)).map(_.forall(_.exists(_.status == NodeLifecycle.Completed)))
// wiring 空节点守卫扩展：task.isEmpty && in.isEmpty && deps.isEmpty 才跳过（:123）
```

- 用 **findNode**（归档兜底）而非 getNode——TTL 归档不影响判定（§C.5）。
- 满足判定是**状态查询**（声明式、幂等），不是 deliveredTo 那样的事件累计——**无需新增任何记账字段**（不加 depsDeliveredTo 之类的计数器），重复判定零成本。
- 该闸门自动保护所有启动入口：deliverOut（:410）、deliverOutTo（:109）、D1 补投递（NodeTools.scala:415-425, 654-671）、重激活链（:613-631）、入口节点启动（:430-432）——**任何绕过 deps 的启动都被拦在最后一关**。
- **buildInput（:133-143）零改动**：deps 上游的 result 不进输入。T1 用例（§E）断言这一点。

### C.3 完成信号 → 触发下游

`completeNode`（NodeEngine.scala:279-304）在 `deliverOut(completed, resultText)`（:301）之后追加 **deps 反向结算**：

```scala
// completeNode 尾部：反向扫描活动区，触发以本节点为 deps 的下游
settleDeps(completed): 
  store.snapshot.map(_.nodes.values.filter(_.deps.contains(completed.id)))
  >> 对每个依赖者 d（d 非 running/终态）：
       startNode(d.id)   // 闸门在 startNode 内：deps 未全满足 / in 未归零都会静默返回
```

- 只扫活动区：归档区只有终态节点，pending 依赖者必在活动区。
- 直接调用 startNode（不经 runDetached）：与 deliverOut 同一完成 fiber 顺序推进——上游已完成，链式阻塞无副作用（现状 deliverOut→startNode 同款语义，NodeEngine.scala:410）。
- 同一上游既 in 又 deps 时：deliverOut 的 in 结算（:401-410）与 settleDeps 在同一 fiber 内先后到达 startNode，第二次调用被幂等跳过（:119-120）→ **结果投递优先、完成信号冗余无害**（§1 裁定落地）。

### C.4 failed/cancelled 不触发 deps 下游（与 in 边的结算差异，写明）

- **deps 边**：failed/cancelled/blocked 均不满足闸门（status ∄ completed）→ 下游保持 wiring/pending，NodeList/Flow Map 可见（task 明确要求「下游保持 pending 可见」）。分发器（重入会话或下轮触发）经 NodeList 看到未满足的 deps，自行处置（换上游 / 改接 / abandon）。
- **in 边现状差异（必须写进实现者认知）**：
  - failed：deliverFailed（:415-439）把 failed 上游**计入 barrier**（deliveredTo 照加，result=错误串），下游照常启动（collect 占位符语义，§2.4 默认）——in 边 failed = 「带着错误继续」，deps 边 failed = 「停住不动」。
  - cancelled：cancelNode（:365-383）无投递 → in barrier 永久悬挂（下游假死 wiring，现状缺陷）；deps 边 cancelled = 下游 pending 可见可诊断。**deps 语义在此比 in 更健康**；in 的 cancelled 悬挂不在本设计范围，仅记录。

### C.5 终态清理 / TTL / 归档不影响 deps 触发判定

- sweepExpired（FlowMapStore.scala:82-95）整节点搬入归档区，status/result 原样保留 → findNode 兜底查得 → 闸门判定不受影响。
- WS nodeRemoved（ProjectActor.scala:183-186 → engine.emitRemoved）只是前端移除卡片，后端状态完好。
- **归档上游触发 deps 下游——裁定建议：允许触发（选「是」）**。理由：
  1. 语义自洽：deps 等的是「完成」这个事实，TTL 只管显示（FlowMapStore.scala:15 头注释「TTL 只管显示」、ProjectTypes.scala:12「归档结果全文保留可长期接线投递」），完成事实不因归档消失；
  2. 先例一致：D1 补投递对归档 completed 上游明确是「唯一投递入口」（NodeTools.scala:411-413 注释原文），deps 若排除归档则与之分裂；
  3. 反面场景不可接受：若归档节点不能触发，下游将**永远 pending**（不存在未来的完成事件），重演存量断链教训（5 个 completed 上游 + 永久 wiring 下游）。
  - 边界对称性：归档 failed/cancelled/blocked 同样不触发（与活动图一致）。
- 落点：§B.3-5 的 D1-deps 补触发用 findNode（归档命中即触发）+ §C.2 闸门用 findNode，两处已覆盖，无需额外代码。

### C.6 交集验证（分发器单例化 / 2a 沙箱 / 反馈协议）

- **deps 触发不经 Task/Mail 入口**：完整触发链 = 节点 agent 完成 → bridge（NodeEngine.scala:190-199）→ runWithAgent 尾部 → completeNode → settleDeps → startNode，全程在 NodeEngine 内部 fiber。TaskTool.scala:78 / MailTool.scala:195,253 → ProjectActor.TriggerDispatcher → spawnDispatcher（ProjectActor.scala:226-323）这条链是「人/上游 agent 派新任务」的入口，与节点间触发**零调用关系**。分发器单例化无论改成什么形态（复用单会话 vs 每触发 spawn），都不触及 NodeEngine 内部触发链。**交集 = 空集**。
- **2a 沙箱分支**：实测 worktree 未提交 diff 共 18 文件（§0 表 #6）。与 deps 实现目标文件的交集：
  - **文件级交集 = { NodeEngine.scala, ProjectActor.scala }**（比「应为空集」的预期多出这两个——如实写明）：
    - sandbox-2a 对 NodeEngine.scala 的全部改动 = 在 SpawnParams 追加 `sandboxEnabled = true` 一行（worktree 版 NodeEngine.scala:220-223 区域）；
    - 对 ProjectActor.scala 的全部改动 = spawnDispatcher 的 SpawnParams 同款一行（worktree 版 :280-284 区域）。
  - **区域级不相交**：deps 触达 NodeEngine 的 startNode（:113-128）/buildInput（:133-143）/completeNode（:279-304）+ 新增 settleDeps + ProjectTypes/NodeTools/FlowMapStore——与 2a 的 spawn 参数行不同 hunk。git 合并可自动收敛，冲突风险可忽略；ProjectActor.scala deps 甚至不改（反向结算在 NodeEngine，emitEvent 链路不动）。
  - 其余 deps 目标文件（ProjectTypes.scala、NodeTools.scala、FlowMapStore.scala、flowMapTab.js、flowMap.css、locales/*、测试）与 2a 18 文件**零交集**。
- **反馈协议 54c22d5b**：blocked 路径（blockedNode :314-340 → FeedbackRouter.route :335）在 completeNode 的 blocked 分流处就与 completed 路径分叉，deps 结算只挂在 completed 分支尾部——**与 FeedbackRouter 零交集**；blocked 不触发 deps 由闸门 status==completed 判定天然覆盖。

---

## D. 前端 Flow Map

### D.1 两种边的视觉区分（亮暗双主题）

- 现有输出边：`.flow-edge.fm-edge` 三档状态类 delivered/inflight/idle（flowMapTab.js:165-169 edgeStateOf、:182-184 应用），CSS 在 flowMap.css:182-206（delivered=蓝宝石实线加重、inflight=行军蚁、idle=静止细线；箭头圆点 :201-203；过渡 :240-242）。颜色全部走 CSS 变量（base.css:2-35 亮色、:55+ 暗色覆盖）——**双主题自适应已由变量层解决**。
- deps 边设计（最小 diff：复用三档状态机 + 追加类型基类）：
  - collectEdges（flowMapTab.js:173-187）扩展：

  ```js
  for (const n of vis) {
    for (const up of n.deps || []) {                 // 新增：deps 边按 n.deps 渲染
      if (!ids.has(up)) continue;
      edges.set(`${up}~>${n.id}`, {                  // id 用 ~> 与输出边 => 区分
        x1: positions[up].x, y1: positions[up].y, x2: positions[n.id].x, y2: positions[n.id].y,
        state: edgeStateOf(/* 上游节点 */), kind: 'deps',
      });
    }
  }
  ```

  - 注意：collectEdges **现只按 n.out 画边、n.in 不画**（:178，边由上游 out 单向遍历）。deps 边没有上游侧 out 镜像，**必须按下游 n.deps 反向渲染**（上游 id → 下游 id 方向画箭头）。
  - DOM：`class="flow-edge fm-edge fm-edge-deps ${state}"`——applyEdgeDiff（:486, :492）把 `e.kind` 拼进 class（两处 setAttribute）。
  - CSS（flowMap.css :182-206 后追加）：

  ```css
  .flow-edge.fm-edge-deps { stroke-dasharray: 3 4; }                    /* 虚线=类型 */
  .flow-edge.fm-edge-deps.delivered { stroke: rgb(var(--color-success) / 0.8); stroke-width: 2; animation: none; }
  .flow-edge.fm-edge-deps.inflight  { stroke: var(--color-warning); animation: flow-dash 1.2s linear infinite; }
  .flow-edge.fm-edge-deps.idle      { stroke: var(--color-border); }
  .fm-edge-arrow.fm-edge-deps { r: 2.5; fill: none; stroke-width: 1.5; } /* 空心小箭头=类型二重编码 */
  ```

  双重编码（线型 + 颜色 + 箭头形状）：不依赖单一色觉通道，暗色主题下 `--color-success/--color-warning/--color-border` 自动切换（base.css 暗色段）。
- **图例（legend，新增）**：全量渲染路径（flowMapTab.js:597-612 renderFlowMap innerHTML 模板）在 `.flowmap-card` 底部加图例条：

  ```
  ─ 蓝实线 输出·已投递   ┄ 蓝蚁 输出·等待   ─ 灰线 输出·未投递
  ┄ 绿实线 依赖·已满足   ┄ 橙蚁 依赖·等待   ┄ 灰虚线 依赖·未满足
  ```

  增量路径（renderFlowMapDiff，:530-558）只动 canvas/svg，图例是模板静态部分、渲染一次常驻，零增量维护成本。i18n 键：`flowmap.legend.*` 六条 + 标题，落 locales/en.js:41-56 与 locales/zh-CN.js 同段（en/zh 两文件同步加，verify-i18n-sweep.cjs 会查漏）。

### D.2 实时增量兼容（逐点）

| 适配点 | 位置 | 改法 |
|---|---|---|
| WS/REST 载荷 | ProjectTypes.scala:76-106 NodePayload 单点加 `"deps"` | 后端单点改完，四类 WS 事件（nodeCreated/nodeUpdated/nodeCompleted/nodeRemoved，flowMapTab.js:874-877 订阅）与 REST（RestApiRoutes.scala:310-316）自动携带 |
| handleNodeWsEvent | flowMapTab.js:846-870 | **零改动**（payload 形状无关：整节点对象并入缓存 :854-859） |
| layoutNodes | flowMapTab.js:78-118 | ① childrenMap 增 deps 边：`for (const up of n.deps||[]) childrenMap.get(up)?.push(n.id)`（:82-87 区域）；② 根节点判定：有 deps 的节点不是根——`(n.in||[]).some(...) || (n.deps||[]).some(x=>nodeIds.has(x))`（:97-100）。否则纯 deps 下游会被当根放第 0 层、边画成逆向 |
| collectEdges | flowMapTab.js:173-187 | §D.1 改法（**按 n.deps 渲染**，与现按 n.out 画 in 边对应物互补） |
| nodeContentKey | flowMapTab.js:388-402 | 加 deps 段：`st==='pending' && (n.deps||[]).length ? (n.deps||[]).length : 0`（与 :399 的 in barrier 提示同款），deps 变化才触发卡片重建 |
| applyEdgeDiff | flowMapTab.js:470-525 | class 拼接带 kind（:486, :492 两处 setAttribute）；edge id 含 `~>` 天然不与 `=>` 冲突；其余 diff/插值/退场逻辑零改动 |
| taskList.js 节点条目 | taskList.js:114-163（nodeCache/applyNodeWsEvent）、:129-134（nodeIsLive） | **零改动**（缓存整节点对象；deps 随 payload 自动可用） |

### D.3 「wiring 节点等待谁」可见（脚注，断链诊断教训的低成本方案）

- nodeHtml（flowMapTab.js:121-149）增一行脚注：节点处于 wiring/pending 且（in 或 deps）非空时显示 `等待: 设计稿(n-abc) · 审定(n-def✦)`——名字从 `fmByProject`（:631 同源 fm.nodes）按 id 解析；**解析不到（上游已 TTL 归档）→ 显示裸 id + ✦ 标记**（REST 载荷只含活动区节点，NodeTools.scala:174——归档上游名字前端不可得，裸 id 是诚实降级，比「看起来孤立」强）。
- 同规则可在 taskList 节点条目加同款副行（taskList.js 渲染段），P2 可选。
- 配套：deps 未满足的 pending 节点脚注标橙（`.fm-waiting-deps` 类），与 §C.4「pending 可见」呼应。

---

## E. 测试方案

### E.1 后端 5 条核心用例

落点：新建 `src/test/scala/nebflow/core/project/NodeDepsSpec.scala`（复用 NodeBarrierDeliverySpec 的基建：CaptureLlm 捕获输入流，NodeBarrierDeliverySpec.scala:54-66；临时 dataRoot + test-agent，:37-50；真实 spawn 轮次模式）。

| # | 用例 | 场景 | 断言方法 |
|---|---|---|---|
| T1 | deps 触发且不投递 result | A（task, out=null）+ B（task, deps=[A]）；A 完成 | ① B 启动并完成（store 状态断言）；② **CaptureLlm.inputs 中 B 的输入不含 `=== Node A ===` 段**（buildInput 未污染）；③ A.result 不变 |
| T2 | in+deps 混合 | A.out=C（in 边）；B -deps→ C；delayOf 让 B 晚于 A 完成 | C 仅在 A、B **都**终态后启动；C 输入**含** `=== Node A ===`（in 投递）；B 完成前 C.status==pending |
| T3 | 环检测含混合图 | FlowMapStoreSpec（FlowMapStoreSpec.scala:70-105 同款）+ NodeToolsSpec e2e | ① store：a.deps=[b] 后 `wouldCreateCycle(from=b, to=a)==true`；in+deps 混链 A-in→B-deps→C 再接 C→A 被拒；② NodeEdit 返回 cycle 错误（Left 断言），store 无新边 |
| T4 | D1 补触发（含归档变体） | A 完成（out=null）→ 先活动区接线、再 sweepExpired 归档后接线 | 两条路径 B 都立即启动（NodeEdit 返回后轮询 B.status→running/completed）；B 输入不含 A result；**归档变体是裁定「归档可触发」的回归锚** |
| T5 | failed 不触发 | A 的 LlmHandle sendStream 抛错 → A failed；B deps=[A] | A.status==failed 后 B.status 保持 pending/wiring；CaptureLlm 无 B 的输入记录；对照断言（可选）：同拓扑改 in 边时 B 会被 failed 结算触发（现状 collect 行为，锁定 §C.4 差异） |

补：cancelled 不触发（A cancelNode 后 B pending）可作为 T5 变体并入；`startNode` 闸门单测（deps 未满足时直接调用 startNode 被静默拦）并入 T1 前置。

### E.2 前端 harness 用例

落点：新建 `scripts/verify-flowmap-deps.cjs`（模式照抄 verify-batch829.cjs 的 playwright + 静态路由 / shot-tasklist-nodes.cjs 的自包含打桩 + MockWebSocket + 亮暗双主题 `emulateMedia({colorScheme})`，不起端口不碰 8080）：

1. 打桩 flow-map 载荷：含 in 边对（A.out=B）+ deps 对（C.deps=[D]，D running）+ 归档缺失上游（E.deps=[n-ghost]）；
2. 断言：deps 边 path 带 `fm-edge-deps` 类且 `getComputedStyle.strokeDasharray !== 'none'`（虚线）；输出边 delivered 实线（dasharray none）；箭头空心（fill none）；
3. 图例：`.fm-legend` 存在且含 6 个档位样本与文案（i18n zh/en 各跑一遍）；
4. 增量：applyNodeWsEvent 注入 D→completed 的 nodeUpdated → deps 边类从 inflight 变 delivered、图例仍在、无全量重建（canvas 子节点复用断言）；
5. 脚注：pending 节点卡片含「等待: …」，归档上游显示裸 id ✦；
6. 亮暗双主题各截图（供视觉验收）。

### E.3 全量回归点

- **必改存量**：NodeEventPushSpec.scala:104-108 的 payload 字段集断言 `Set("id",...,"ttlLeftSec")` 加 `"deps"`（否则必红）。
- 后端全量：`nebflow.core.project.* / nebflow.core.tools.*`（c467592f 基线 422 测试）+ FlowMapStoreSpec（wouldCreateCycle 混合图新断言）+ NodeAcceptanceSpec / NodeGhostRowSpec（D1 相关回归）。
- 前端：`scripts/check-js-types.mjs` + verify-i18n-sweep.cjs（新 i18n 键双语齐全）+ 既有 verify-*（flowMap 相关）不回归。
- 版本注记：回归在 HEAD 2f32d0a6 上跑；宿主 beta.54 未重建（§0 #3），**deps 语义随下次重建生效**，重建前不要在宿主上做行为验证。

---

## F. 实施顺序建议（给实施节点）

1. ProjectTypes：NodeDef.deps + NodePayload（A）→ 2. FlowMapStore.wouldCreateCycle 混合 DFS（B.3）→ 3. NodeEngine startNode 闸门 + completeNode.settleDeps（C.2/C.3）→ 4. NodeEdit schema/解析/校验/D1-deps（B）→ 5. 后端 NodeDepsSpec 五用例全绿 → 6. 前端 collectEdges/layout/nodeContentKey/脚注/图例/CSS/i18n（D）→ 7. verify-flowmap-deps.cjs + 全量回归（E）。
每步可独立 commit；步骤 3 是语义核心（单闸门 + 反向结算共约 30 行）；步骤 6 与后端无耦合可并行。

## 关键锚点速查（file:line）

- 数据模型：ProjectTypes.scala:41-65（NodeDef，:50 in / :51 out / :52 deliveredTo）、:68（withDefaults Codec）、:76-106（NodePayload 单点）、:108-128（State/Archive）
- NodeEdit：NodeTools.scala:216-241（schema）、:54-66（parseOut）、:68-90（parseIn→deps 复用）、:36-48（存在性/冻结守卫）、:93-106（setOut 镜像）、:318-440（proceed+D1:415-425）、:471-683（editNode：guard:504-511、环检:516-518、重激活:530-537/584-632、D1:654-671）、:166-187（NodeList 载荷）
- 结算：NodeEngine.scala:113-128（startNode→闸门落点）、:133-143（buildInput→不动）、:279-304（completeNode→settleDeps 落点:301 后）、:388-413（deliverOut:409-410）、:415-439（deliverFailed=collect）、:365-383（cancelNode 无投递）、:314-340（blockedNode 不结算）、:91-110（deliverOutTo）
- 环检测：FlowMapStore.scala:64-78（DFS 扩展点）、:38-42（findNode 归档兜底）、:82-95（sweepExpired）
- 触发入口（无交集验证）：TaskTool.scala:78、MailTool.scala:195,253、ProjectActor.scala:226-229,249-323、:183-186（TtlTick）
- 前端：flowMapTab.js:78-118（layout）、:97-100（根判定）、:121-149（nodeHtml→脚注）、:165-169（edgeStateOf）、:173-187（collectEdges→deps 渲染点）、:388-402（nodeContentKey）、:470-525（applyEdgeDiff:486,492）、:530-558（diff）、:597-612（全量模板→图例落点）、:846-877（WS）；flowMap.css:182-206,240-242；base.css:2-35,55+（主题变量）；locales/en.js:41-56 + zh-CN.js
- 测试：NodeBarrierDeliverySpec.scala:54-66（CaptureLlm）、NodeEventPushSpec.scala:104-108（字段集必改）、FlowMapStoreSpec.scala:70-105、scripts/shot-tasklist-nodes.cjs（双主题 harness 先例）
