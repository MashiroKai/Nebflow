package nebflow.core.project

import cats.effect.IO
import io.circe.Json
import io.circe.syntax.*
import nebflow.shared.PathUtil

/**
 * Flow Map 调整事件审计日志（blocked 反馈重入设计 §4.4）。
 *
 * `<workspace>/.nebflow/flow-map-events.jsonl` 追加式 JSONL，每行一事件：
 * `{ts, type, project, nodeId, summary}`，
 * type ∈ blocked / reentry-triggered / reactivated / abandoned / escalated /
 *   cooldown-on / reaped / merge-blocked /
 *   settle-sweep / trigger-starved / start-aborted（trigger-chain-fix 批）/
 *   bg-wait / bg-wait-timeout / bg-released（bgtask-completion-gate 批）/
 *   mount-stalled（mount-enforce 批：可触发点后 60s 仍未触发的挂载停滞留痕，
 *   summary 含等待原因——上游终态明细 + barrier 残缺清单）/
 *   node-ask（D6 批 F1 2026-09-08：项目节点 AskUser 提问留痕——方案 A 直达作者
 *   的监督补齐件，summary 含节点名/requestId/问题摘要；写入点 AgentActor AskUser
 *   处理链，分发器经事件流审计可见）/
 *   boot-recovery（crash-recovery 批 2026-09-07：boot sweep 每个认领动作——
 *   rehydrate 认领 / (c) 类 failNode，summary 含三分类与 transcript 指针——
 *   「禁止静默自愈」纪律，settle-sweep 先例同款）/
 *   chain-archived（P3 引擎侧归档联动批 2026-09-10：整链出库（sweepCompletedChains）
 *   时追加——nodeId = 分量内 createdAt 最早节点（与链 id 派生同源），summary =
 *   `chain=<id> archivedAt=<ms> members=<n>`，顶层 chainId 同值；索引维护消费者
 *   DocIndexConsumer 据此翻 INDEX.md 条目 state）/ chain-restored（同批定义的对称
 *   事件类型——链抽象 P2 restoreChain 拉回时索引回翻；**接口点，本批无写入点**）/
 *   chain-cancelled（**chaincancel 批 2026-09-17**：链级/级联取消的聚合留痕——
 *   一次链级取消操作恰一条（成员清单/保留/跳过/注入次数），写点 =
 *   `DispatchNotify.notifyChainCancelled`；见 [[ChainCancelledType]]）/
 *   chain-membership-changed（**chainmodel 批一 2026-09-19**：节点链归属变更留痕——
 *   声明 / 改号 / 兜底重归三类原因，字段 = 节点 id + 旧链号 + 新链号 + 原因 + 时戳，
 *   写点 = `NodeTools.emitChainMembershipChanges`；见 [[ChainMembershipChangedType]]）/
 *   hard-recovery（hard-recovery 批 2026-09-07 起由 NodeEngine.hardResumeNode 写
 *   「resumed from stuck」；取消静默死锁修复批 R5 补写 resume **失败**腿——
 *   `L3 resume FAILED … node left cancelled; dispatcher notified (R1) + out detached (R4)`）/
 *   cancelled（**取消静默死锁修复批 R2** 2026-09-10：cancelled 终态化留痕——
 *   `node cancelled [source=engine|user]: <reason>`（+ R4 摘除目标清单）。此前
 *   cancelled 唯一留痕是 bg-harvest 那行**无原因**文本，取消原因全系统零落盘）/
 *   barrier-blocked（**取消静默死锁修复批 R3**：终态写点（cancelled/failed）
 *   同步做下游 barrier 检查，已被终态上游永久闸死 → **即时**告警（0 延迟，不设
 *   60s 档）。周期回扫的 mount-stalled 保留为兜底，两者由 NodeEngine 的
 *   stallNotified + barrierAlerted 单发记账去重——同一停滞不发两条）/
 *   dispatcher-idle-expired（**令 3 分发器生命周期** 2026-09-12：分发器会话空闲
 *   超过 `Defaults.DispatcherIdleWindowMs` 被 30 s 扫描腿拆除时留痕——
 *   summary = `session=<dispatcher-xxxxxxxx> idleSecs=<n> windowMs=<n>`，
 *   nodeId 字段承载会话 id；「活着但空闲」与「已销毁」的事后对齐面）/
 *   dispatcher-wake（**宿主启动自动重入批** 2026-09-13，方案件 A 档 A1：boot 链
 *   `projectBootWake` 腿对每个在册项目做一次唤醒判定，写点 = `BootDispatcherWake.record`
 *   ——「谁 / 何时 / 结果」的正向留痕，取代此前「零动作 boot 零日志行、只能靠缺失行
 *   推断」的取证面；summary 见 [[dispatcherWakeSummary]]，nodeId 字段承载项目名）/
 *   merge-queue（**mergefifo-engine 批** 2026-09-13：合并窗 FIFO 互斥闸的停等留痕
 *   （`kind=hold`）+ **O-1 已知缺口告警**（`kind=same-git-dir-multi-project`：两项目
 *   共用同一 git 目录 ⇒ 引擎侧漏互斥，本批只检测告警不实现 claim；写点 =
 *    `NodeEngine.logMutexHold` / `NodeEngine.alarmSameGitDirProjects`，summary 见
 *   [[mergeQueueHoldSummary]] / [[mergeQueueSameGitDirSummary]]）/
 *   abandoned-detach（**cancelled 滞留主图修复批 · 案 A** 2026-09-14：存量回填腿
 *   对被 retired 却仍挂在活链上的 cancelled 节点补做摘边时的留痕，写点 =
 *   `NodeEngine.backfillAbandonedDetach`；见 [[AbandonedDetachType]]）/
 *   verifier-route-lost（**failroute-guard 批 2026-09-21 · 案 A**：摘边 / 判词后果面
 *   致某 verifier 失去 fail 路由时的**可行动**留痕——主语 = **受害 verifier**（非退役
 *   节点）；按批聚合、摘要逐位载被摘目标/保留 pass 面/恢复文案；见
 *   [[VerifierRouteLostType]]）/
 *   verifier-pass-unconsumed（**verdict-consumer batch 2026-10-01 · case A tier (a),
 *   warning only**：the PASS-face defect — after a verifier is created/rewired its
 *   declaration face carries **no pass outlet** (a positive verdict nobody receives).
 *   Sits BESIDE — never instead of — the fail-face `verifier-route-lost` (both
 *   judgements can hold at once). Write point = the successful legs of `NodeEditTool`
 *   create/rewire; summary = that verifier's name/id; see
 *   [[VerifierPassUnconsumedType]]）/
 *   chain-archive-held（**same batch · case A tier (b) release + warn**：the chain
 *   archives as usual, but its component holds a "pass verdict with zero consumer"
 *   (V-0) member ⇒ the conclusion is carried out on this line, and the summary spells
 *   out both manual exits verbatim. Same family as `chain-archived`, DIFFERENT fact —
 *   the two must never be merged into one line; see [[ChainArchiveHeldType]]）/
 *   node-report-unconsumed（**engine-defects 批 #239①** 2026-09-15：`node_report` 申报
 *   已被 `drain` take-and-remove 取走、而终态写按 R2 fresh-read 纪律**拒写**（节点已
 *   消失 / 状态已变 / 关机期 draining 抑制）时的**补偿写回**——summary 含 sessionId +
 *   category + detail + suggestion 全文（不截断），写点 =
 *   `NodeEngine.compensateUnconsumedReport`；旧口径下该形态零痕迹、申报永久丢失）。
 * 注册式扩展：append API 无 schema 变更，新事件类型 = 本清单加一词 + 写入点调用；
 * chainId 为顶层**可选**字段（2026-09-10 加，spec §9.2 项 9）：旧行无该键照常解析
 * （零迁移、append-only），新行仅在链族事件带上。
 *
 * 0 schema 迁移（独立文件不碰 flow-map.json 契约）、append-only、重启保留、grep 友好。
 * 写入点：NodeEngine.blockedNode（blocked）/ mergeBlockedByUpstream
 * Failure（merge-blocked）/ runWithAgent 翻转异常中止（start-aborted）/ settleRunnable
 * Sweep（settle-sweep、trigger-starved、mount-stalled）/ reapStaleRunning（reaped）、
 * FeedbackRouter（reentry-triggered / escalated / cooldown-on）、NodeEditTool 重激活与
 * abandon 两分支（reactivated / abandoned）/ NodeEngine.compensateUnconsumedReport
 * （node-report-unconsumed，#239①）。
 */
object FlowMapEventLog:
  val FileName = "flow-map-events.jsonl"

  /** 链归档事件类型（写点：ProjectActor TtlTick → sweep 出库后追加）。 */
  val ChainArchivedType = "chain-archived"

  /**
   * **abandon 回填摘边事件类型**（cancelled 滞留主图修复批 · 案 A 腿 2，2026-09-14
   * 作者 17:24 拍板）。写点 = [[NodeEngine.backfillAbandonedDetach]]（30s `TtlTick`
   * 扫描腿，排在链级归档 sweep 之前）——对**已 cancelled 且仍有挂线**的滞留节点补做
   * 摘边（`NodeEngine.detachAbandonedNode`）时逐件留痕。
   *
   * 与同批的 `abandoned`（工具路径 `NodeEditTool.abandonNode` 的写点）**分开记账**：
   * 本条回答的是「我没动过这个节点，它的拓扑为什么变了」——回填是引擎自主动作，
   * 与被退役时刻的 `abandoned` 行不是同一事实。幂等：`RetireDetach.isEmpty` 时零写。
   */
  val AbandonedDetachType = "abandoned-detach"

  /**
   * **待接线登记事件类型**（B5 缺口③ · 作者 2026-09-17 M-3 裁定，选项①）。
   *
   * 写点 = `NodeTools` 的 NodeEdit 写路径（唯一）：本次编辑里指向 **running** 目标的
   * **控制边**（`:loop`）不进 `out`、改入 `NodeDef.pendingOut` 时逐次留痕。此行的存在
   * 是机制的成立条件——「接线时刻不确定」必须对分发器可见（否则分发器以为已接、实际
   * 待接）。一次编辑恰一条（`pendingOut` 为空时零写，幂等）。
   */
  val WiringDeferredType = "wiring-deferred"

  /**
   * **待接线自动接线事件类型**（同批，与 [[WiringDeferredType]] 成对）。
   *
   * 写点 = `NodeEngine.applyDeferredWiring`（30s `TtlTick` 扫描腿）：目标离开 running 后
   * 把 `pendingOut` 里的控制边并入 `out` 时逐节点留痕。控制边零投递语义 ⇒ 本事件代表的
   * 是**纯声明面追加**（零补投递、零启动副作用）。
   */
  val WiringAppliedType = "wiring-applied"

  /**
   * **受害 verifier 拒绝态事件类型**（failroute-guard 批 2026-09-21 · 案 A，
   * 作者选型 = 案 A 薄）。
   *
   * 语义 = 「某个 verifier 的 fail 路由没了」——**主语（`nodeId` 字段）= 受害 verifier**，
   * **不是**退役节点。这是本类型存在的理由：摘边事件（`abandoned` / `abandoned-detach`）
   * 的主语是**退役位**，其文案里只有一句裸计数（`out-refs=1`）——「哪条边被摘、谁因此
   * 变非法」在审计面**结构性不可见**（实盘取证：某位全史零路由事件，成因无法从审计面
   * 重建）。本行是被摘除方的**可行动**留痕。
   *
   * 两个写点（同一事实的两个时刻，禁合并归他型）：
   *  1. `NodeEngine.emitVerifierRouteLost`（摘边后果面）——工具腿 `NodeEdit(abandon)` 与
   *     引擎腿 `NodeCancel → 30s 回填腿` 到达同一摘除点，一律经此收口；
   *  2. 同函数（判词期）——`NodeEngine.verifierFailR` 的「无可用 fail 路由」分支
   *     （判词无处可去 ⇒ 拒绝态留痕；旧口径只有一句「良性退化」文案）。
   *
   * **按批聚合**（裁定⑤）：一次退役动作 / 一批回填退役 / 一次判词各**恰一条**（防同批
   * 多退役逐位刷屏，与 `chain-cancelled` 的聚合纪律同源）；summary 逐位载四项 = 受害
   * verifier 名/id + 被摘的 fail 目标 id + 保留的 pass 目标集 + 可行动恢复文案，形态见
   * [[verifierRouteLostSummary]]。幂等：无受害 ⇒ 零写。
   *
   * 🔴 **不新增通知族、不发 Mail 面**（节点无 Mail 身份）：可见性三级 = 本事件行 +
   * 引擎 WARN + `NodeList` 载荷派生键 `verifierRoute`（见 `NodePayload`）。
   */
  val VerifierRouteLostType = "verifier-route-lost"

  /** `verifier-route-lost` 的单受害者视图（写点组装的纯数据；summary 组装单点消费）。 */
  final case class VerifierRouteLostView(
    verifierId: String,
    verifierName: String,
    lostTargets: List[String],
    keptPassTargets: List[String]
  )

  /**
   * `verifier-route-lost` 结构化 summary（单行、可 grep、给人看）：逐位四项 + 可行动文案。
   * 分组符号与家族惯例一致（`;` 分位、`|` 分项、`-` 表空），空白照常（与 `abandoned` /
   * `cancelled` 的英文自由文本同族；链族/分发器族的 `k=v` 纪律不适用本型）。
   */
  def verifierRouteLostSummary(scope: String, views: List[VerifierRouteLostView]): String =
    def items(xs: List[String]): String = if xs.isEmpty then "-" else xs.mkString("|")
    val ordered = views.sortBy(_.verifierId)
    val detail = ordered
      .map { v =>
        s"${v.verifierId}('${v.verifierName}') lost=${items(v.lostTargets)} kept=${items(v.keptPassTargets)}"
      }
      .mkString("; ")
    s"verifier fail route lost (scope=$scope; victims=${ordered.size}) — " +
      s"$detail — the verifier is in the REJECTION STATE: it declares no usable '(fail)<target>:loop' route, " +
      "so a fail verdict can no longer re-run anything and the chain stops there. " +
      "Restore the route with NodeEdit out=\"(pass)<landing>, (fail)<worker>:loop\" (NODE_VERIFIER_NEEDS_ROUTE)"

  /**
   * **zero-consumer pass verdict** event type (verdict-consumer batch 2026-10-01 ·
   * case A warning tier · design card §2.1 / §3.3). Registered-style extension: the
   * `append` API takes no schema change — a new type is one word in the list above plus
   * a writing point.
   *
   * Semantics = "this verifier judged `pass`, and its declaration face carries **no pass
   * outlet at all** — the positive verdict has nowhere to go". The subject (`nodeId`
   * field) is the **verifier itself** (same discipline as [[VerifierRouteLostType]]:
   * the victim is the subject, never the silent actor).
   *
   * 🔴 It sits **beside**, never instead of, the fail-face family: `NODE_VERIFIER_NEEDS_ROUTE`
   * / `verifierRouteInvalid` / [[VerifierRouteLostType]] all judge whether a **rejected**
   * target can be re-run. This one judges where an **accepted** verdict is delivered —
   * both conditions can hold at once, and neither may be merged into the other.
   *
   * The judging authority is the single point [[NodeTools.passOutlet]] (never re-derived
   * here, in the frontend, or in the dispatcher). Idempotent by construction: on a
   * create/rewire call the line is appended only when the judgement holds at that call's
   * close, and it is a pure derived quantity ⇒ re-wiring the pass leg removes the
   * condition with no bookkeeping. Warning tier only: **zero rejection paths**, no new
   * Mail face (a node has no Mail identity).
   */
  val VerifierPassUnconsumedType = "verifier-pass-unconsumed"

  /**
   * **chain archived with unconsumed verdicts** event type (same batch · case A (b)
   * release + warn). The chain is archived **as usual** (the release behaviour is
   * deliberately unchanged: a blocking gate would manufacture "the chain never archives",
   * the same family as #1180 "waiting ≠ no progress"); this line carries the conclusion
   * outward so the state is not silent.
   *
   * The symmetric precedent is [[ChainRestoredType]] (`chain-restored`): chain-family
   * events grow by one type rather than being merged, and "the whole chain went out" vs
   * "the whole chain went out while carrying unconsumed verdicts" are **two different
   * facts** — 🔴 never merged into one line.
   */
  val ChainArchiveHeldType = "chain-archive-held"

  /**
   * 链拉回事件类型（对称口径，spec §6.2/§9.3：链抽象 P2 `restoreChain` 落地后由
   * 其调用点写入；**本批只定义类型 + 消费者回翻分支，无写入点**——禁止虚构调用点）。
   */
  val ChainRestoredType = "chain-restored"

  /**
   * **链级 / 级联取消事件类型**（chaincancel 批 2026-09-17，R2 §2.3-3）。
   *
   * 写点 = `DispatchNotify.notifyChainCancelled`（**唯一**写点）：一次链级取消操作
   * 恰一条——`nodeId` 字段承载**本次首个被取消节点**（升序首项；链标识由顶层
   * `chainId` 字段承载，与 [[ChainArchivedType]] 以「分量头节点」承载 nodeId 同族：
   * 本字段承载代表节点标识），`chainId` = 该链 id。
   *
   * 与逐节点 `cancelled` 事件**留痕不合并**（设计 D3）：N 条 `cancelled` 各自回答
   * 「哪个节点因何被取消」，本条回答「**一次**链级操作发生过、它的成员/保留/跳过
   * 清单是什么、注入了几次」——即 C5 判据的观测面（`chain-cancelled` == 1 条）。
   * 幂等：无被取消节点（重复调用 / 全终态链）⇒ 零写（C6/C5 的第二次调用读数 == 0）。
   */
  val ChainCancelledType = "chain-cancelled"

  /**
   * **链归属变更事件类型**（chainmodel 批一 ⑤；设计件取证 6 的「最硬未决项」：事件流
   * 44 类里零该类型，重归只能靠人写的 `node-message` 正文回溯 ⇒ 跳号不可事后追）。
   *
   * 写点 = `NodeTools.emitChainMembershipChanges`（NodeEdit **create / edit 两条写路径**
   * 的尾部各一处，是本类型的**唯一**生产写入点）：每次写操作前后各取一次「有效链归属
   * 视图」（`FlowMapStore.chainIdView`，与载荷 `chainId` 判据同源），逐节点比对 ——
   * 归属发生变化者逐条留痕。三项变更原因（`reason` 值域）：
   *   - `declaration`：本节点首次声明链归属（旧值缺省 → 新值 = 声明值）；
   *   - `re-id`：本节点声明值变更（改号；旧值 → 新值都是声明值）；
   *   - `fallback`：其余（拓扑/归档等令**派生**分量重组 ⇒ 归属变化，旧口径下完全静默）。
   *
   * `nodeId` = 归属发生变化的节点；顶层 `chainId` = **新**链号（无归属时缺键，与
   * [[ChainArchivedType]] 同款；**旧**链号在 summary 里）。summary 形态见
   * [[chainMembershipChangedSummary]]（`k=v` 单空格）。
   *
   * 与链族既有三型的分工（同族不同事实，禁合并）：`chain-archived` = 整链出库、
   * `chain-restored` = 整链拉回、`chain-cancelled` = 一次链级取消操作；本型回答的是
   * 「**哪个节点的链号从 X 变成 Y、为什么**」——跨链并合/拆分的唯一机械观测面。
   */
  val ChainMembershipChangedType = "chain-membership-changed"

  /**
   * **链控态变更事件类型**（chainview 批 2026-10-01）：链级 pause / resume / cancel 的
   * 台账状态面变动，一条一留痕。
   *
   * 与链族既有四型的分工（同族不同事实，禁合并）：`chain-archived` = 整链出库、
   * `chain-restored` = 整链拉回、`chain-cancelled` = **一次取消操作的成员清单**（本批
   * 照旧由 `NodeCanceller.cancelNodes` 的聚合腿发射）；本型回答的是「**这条链的控制态
   * 此刻是什么、什么时候变的**」——三态（`active` / `paused` / `cancelled`）的唯一审计面，
   * 与 WS 的 `chainState` 帧同源（同一 [[ChainLedger.ChainControl]] 投影）。
   *
   * `nodeId` = 链首成员（可能为空——链条目未必存在；`append` 对空 id 照写，消费方按
   * 顶层 `chainId` 归属）；顶层 `chainId` = 链号（原样回显调用方给的号）。
   */
  val ChainStateChangedType = "chain-state-changed"

  /** `chain-state-changed` 结构化 summary（`k=v` 单空格；沿 [[noWs]] 纪律）。 */
  def chainStateChangedSummary(status: String, at: Long): String =
    s"status=${noWs(status)} at=$at"

  /**
   * `chain-membership-changed` 结构化 summary（`k=v` 单空格分隔，值不含空白——
   * 沿 [[noWs]] 纪律）：`from` = 旧链号（`-` = 无归属）、`to` = 新链号（`-` = 无归属）、
   * `reason` ∈ declaration | re-id | fallback。时戳由 [[append]] 的顶层 `ts` 字段承载，
   * 节点 id 由 `nodeId` 字段承载，新链号由顶层 `chainId` 字段承载（三字段分工既有先例）。
   */
  def chainMembershipChangedSummary(
    from: Option[String],
    to: Option[String],
    reason: String
  ): String =
    s"from=${from.filter(_.trim.nonEmpty).map(noWs).getOrElse("-")} " +
      s"to=${to.filter(_.trim.nonEmpty).map(noWs).getOrElse("-")} reason=${noWs(reason)}"

  /**
   * **边集变更事件类型**（缺陷③批 2026-10-01 · 案 A「单点统一事件」；root #335① 裁定）。
   *
   * 语义 = 「地图里**某条边**变了」——回答此前**结构性不可判**的问题：「我没动过这个节点，
   * 它的拓扑为什么变了 / 那条边是什么时候没的」。旧口径下 `out` / `in` / `deps` 三类边集
   * 变更**一个逐笔事件都没有**（`chainId` 声明面有逐笔 `from=/to=` 而边集面零留痕 ⇒ 成因
   * 不可从审计面重建；在册实证 = failroute-guard 批设计件 `:203`「该位全史零路由事件 ⇒
   * 成因无法从审计面重建」）。
   *
   * 写点（**choke point 单点族**，调用点埋点必漏）：[[NodeTools.setOut]]（含**归档分支**）、
   * NodeEditTool create 事务、NodeEditTool edit 尾（in 追加 / 归档上游 / deps 替换）、
   * `NodeCompletion.detachCancelledUpstream` / `detachAbandonedNode`、
   * `NodeDelivery.reversePruneReferences`、`NodeEngine.applyDeferredWiring`，
   * 以及**编辑期拒绝面**（`reason=refused`：`ensureMergePassOnly` / `ensureTargetNotRunning` /
   * `verdictRouteGate` / `verdictRouteGateForAppends` / `loopGateViolation`）。
   *
   * 幂等：`before == after`（canonical 口径）⇒ **零行**；一次编辑里未变的边**不得出声**。
   * **逐边一行**（#335② 裁定）：🔴 禁按批聚合——聚合会让「哪条边」在审计面重新模糊。
   * `nodeId` 字段承载 **owner**（边集发生变化的节点），`chainId` 不写（边变更不是链族事实）。
   */
  val EdgeChangedType = "edge-changed"

  /** `edge-changed` 的变更面（边集方向三族 + 拒绝面一值；值域不含空白，沿 [[noWs]] 纪律）。 */
  object EdgeChangeKind:
    val Out = "out"
    val In = "in"
    val Deps = "deps"
    /** 拒绝面：**不写边** ⇒ `from`/`to` 恒 `-`，`kind` = 被拒门类（见 [[EdgeChangeReason.Refused]]）。 */
    val Refused = "refused"

  /** `edge-changed` 的成因（值域不含空白）。 */
  object EdgeChangeReason:
    /** 工具路径（`NodeEdit`）的边集写入。 */
    val Machine = "machine"
    /** 引擎侧迟到摘除（`NodeDelivery.reversePruneReferences`）。 */
    val Engine = "engine"
    /** 取消链摘边（`NodeCompletion.detachCancelledUpstream`）。 */
    val Cancel = "cancel"
    /** 退役摘边（`NodeCompletion.detachAbandonedNode`）。 */
    val Abandon = "abandon"
    /** 待接线队列到点自动接线（`NodeEngine.applyDeferredWiring`）。 */
    val AutoWire = "auto-wire"
    /** 编辑期硬拒（不写边；`detail` = 既有错误码）。 */
    val Refused = "refused"

  /**
   * 单条边变更视图（写点组装的纯数据；summary 组装单点消费）。
   *
   * `from` / `to` = 该 owner 边集里**被移除 / 被新增的那一项**的身份（解析后节点 id；
   * 边集侧恒单条，🔴 禁逗号拼接多边）；`None` = 无该侧（渲染为 `-`）。
   * `mode` / `gate` = 该边的模式与门集（拒绝面无边 ⇒ `None` → `-`）。
   */
  final case class EdgeChangeView(
    owner: String,
    from: Option[String],
    to: Option[String],
    kind: String,
    mode: Option[String] = None,
    gate: Option[String] = None,
    reason: String = EdgeChangeReason.Machine,
    detail: Option[String] = None
  )

  /**
   * `edge-changed` 结构化 summary（单行、可 grep；`k=v` 单空格分隔，**值不含空白**——
   * 沿 [[noWs]] 纪律，`-` = 空/不适用，与 [[chainMembershipChangedSummary]] 的 `-` 约定同源）。
   *
   * 键序固定：`owner` `from` `to` `kind` `mode` `gate` `reason`（+ 可选 `detail`）。
   * `detail` 只在拒绝面出现，值 = **既有错误码常量**（如 `NODE_MERGE_PASS_ONLY`）——
   * 🔴 禁把完整错误文案塞进 payload（含空白，违反行内纪律）。
   */
  def edgeChangedSummary(v: EdgeChangeView): String =
    def valOf(s: Option[String]): String = s.map(noWs).filter(_.nonEmpty).getOrElse("-")
    s"owner=${valOf(Some(v.owner))} from=${valOf(v.from)} to=${valOf(v.to)} kind=${valOf(Some(v.kind))}" +
      s" mode=${valOf(v.mode)} gate=${valOf(v.gate)} reason=${valOf(Some(v.reason))}" +
      v.detail.map(d => s" detail=${valOf(Some(d))}").getOrElse("")

  /** 门集规范式（`|` 分隔、排序确定；空集 = `-`）。 */
  private def gateSpec(e: OutEdge): String =
    if e.on.isEmpty then "-" else e.on.toList.sorted.mkString("|")

  /** 边身份键（**解析后**目标 + 模式 + 门集；20260909 in 丢失事故的口径：diff 必须在
    * 「节点身份」空间进行，名字/ id 两形态不得互相误判为改接）。 */
  private def edgeIdentity(resolve: String => String, e: OutEdge): (String, String, Set[String]) =
    (resolve(e.to), e.mode, e.on)

  /**
   * **边集变更视图（纯函数，零 IO）**：`beforeOut` ↔ `afterOut` 的 canonical 逐边 diff ⇒
   * 逐边一条视图（先移除、后新增，序确定）。`before == after` ⇒ `Nil`（**零行**，与
   * `chain-membership-changed` 的「不变则 Nil」同款）。
   *
   * `resolve` = 目标串 → 身份串（调用方传 `OutEdge.resolveTargetId(nodes, _)` 的收口）；
   * 视图里的值取解析结果，悬空时由调用方回落原串。
   */
  def outEdgeViews(
    owner: String,
    beforeOut: List[OutEdge],
    afterOut: List[OutEdge],
    reason: String,
    resolve: String => String
  ): List[EdgeChangeView] =
    val before = OutEdge.canonical(beforeOut)
    val after = OutEdge.canonical(afterOut)
    val bKeys = before.map(e => edgeIdentity(resolve, e)).toSet
    val aKeys = after.map(e => edgeIdentity(resolve, e)).toSet
    val removed = before.filterNot(e => aKeys.contains(edgeIdentity(resolve, e)))
    val added = after.filterNot(e => bKeys.contains(edgeIdentity(resolve, e)))
    removed.map(e =>
      EdgeChangeView(owner, Some(resolve(e.to)), None, EdgeChangeKind.Out, Some(e.mode), Some(gateSpec(e)), reason)
    ) ++
      added.map(e =>
        EdgeChangeView(owner, None, Some(resolve(e.to)), EdgeChangeKind.Out, Some(e.mode), Some(gateSpec(e)), reason)
      )

  /**
   * **`in` 镜像变更视图（纯函数，零 IO）**：`s.in` 列表的 canonical 逐项 diff。
   *
   * `kind=in` 是 `kind=out` 的**同一事实的另一面**（下游的入边随上游出边增减）⇒ 必须
   * **另发一行**（🔴 不得并入 out 那一行：并入会让「谁的边变了」在审计面重新模糊）。
   * `owner` = **镜像所在节点**（入边的主人），`from`/`to` = 该 in 项的增删（项值恒为
   * 上游节点 id；`in` 列表历史上只存 id）。
   */
  def inMirrorViews(
    owner: String,
    beforeIn: List[String],
    afterIn: List[String],
    reason: String
  ): List[EdgeChangeView] =
    val b = beforeIn.distinct
    val a = afterIn.distinct
    val removed = b.filterNot(a.contains)
    val added = a.filterNot(b.contains)
    removed.map(up => EdgeChangeView(owner, Some(up), None, EdgeChangeKind.In, None, None, reason)) ++
      added.map(up => EdgeChangeView(owner, None, Some(up), EdgeChangeKind.In, None, None, reason))

  /**
   * **`in` 镜像的批量视图**（多个节点的 in 列表同时变化 ⇒ 逐节点、逐项一行）。
   * `before`/`after` = 「节点 id → 该节点的 in 列表现值」两时点各取一次；只对**真正变化**
   * 的节点出声（未变节点零行——R5 负控的机械承担点）。
   */
  def inMirrorViewsBatch(
    before: Map[String, List[String]],
    after: Map[String, List[String]],
    reason: String
  ): List[EdgeChangeView] =
    (before.keySet ++ after.keySet).toList.sorted.flatMap { id =>
      inMirrorViews(id, before.getOrElse(id, Nil), after.getOrElse(id, Nil), reason)
    }

  /**
   * **`deps` 替换视图（纯函数，零 IO）**：`NodeDef.deps` 是**替换**语义（非增删）——
   * 逐项 diff 记 `kind=deps from=<旧> to=<新>`（`NodeEdit(deps="X")` → `NodeEdit(deps="Y")`
   * 恰好一行 `from=X to=Y`）。
   *
   * 🔴 **成对渲染**（本批 §2.4 R3 的判据面）：替换语义下「旧项 → 新项」是**同一槽位的
   * 一次改写**，不是「一条边被摘 + 另一条边被加」两件独立事实 ⇒ 逐对发一行
   * （`from` / `to` 同时非空）。集合大小不等时余项各自单侧（新增：`from=-`；移除：
   * `to=-`），序确定（配对按 canonical 序，余项接续）。
   * 两集合相等 ⇒ `Nil`（**零行**——R5 负控的机械承担点）。
   */
  def depsViews(owner: String, beforeDeps: List[String], afterDeps: List[String], reason: String): List[EdgeChangeView] =
    val b = beforeDeps.distinct
    val a = afterDeps.distinct
    if b == a then Nil
    else
      val removed = b.filterNot(a.contains)
      val added = a.filterNot(b.contains)
      val paired = removed.zipAll(added, "", "").map { (oldD, newD) =>
        EdgeChangeView(
          owner,
          if oldD.isEmpty then None else Some(oldD),
          if newD.isEmpty then None else Some(newD),
          EdgeChangeKind.Deps,
          None,
          None,
          reason
        )
      }
      paired

  /**
   * 拒绝面视图（纯函数，零 IO）：编辑期硬拒**落一笔事件**（🔴 不新造平行类型，沿 `kind` /
   * `reason` 值域扩展）。`from`/`to` 恒 `-`（拒绝面不写边）——只记「谁被谁拒」+ 错误码。
   */
  def refusedView(owner: String, gateKind: String, errorCode: String): EdgeChangeView =
    EdgeChangeView(
      owner = owner,
      from = None,
      to = None,
      kind = if gateKind.trim.isEmpty then EdgeChangeKind.Refused else gateKind,
      mode = None,
      gate = None,
      reason = EdgeChangeReason.Refused,
      detail = Some(errorCode)
    )

  /**
   * 逐条落 `edge-changed`（**逐边一行**，逐条 append ⇒ 一行一 `os.write.append`）。
   * `Nil` ⇒ 零 IO（幂等出口的机械承担点：无变更视图就不落任何字节）。
   */
  def appendEdgeChanges(workspace: String, project: String, views: List[EdgeChangeView]): IO[Unit] =
    views.foldLeft(IO.unit)((acc, v) => acc *> append(workspace, project, v.owner, EdgeChangedType, edgeChangedSummary(v)))

  /** 单条落 `edge-changed`（拒绝面 / 单点写点的便捷入口）。 */
  def appendEdgeChange(workspace: String, project: String, view: EdgeChangeView): IO[Unit] =
    append(workspace, project, view.owner, EdgeChangedType, edgeChangedSummary(view))

  /**
   * **重激活触发源判定单点**（缺陷③批 2026-10-01 · 案 A 写点 10，纯函数、零 IO）。
   *
   * 此前 `reactivated` 的 `source=` 是**硬编码 `human`**（`NodeEditTool`），于是「谁把
   * 这个终态节点放回重跑」在审计面恒不可区分——节点会话、分发器会话与真人工三个来源
   * 同形。本函数按调用方身份三面判定（优先级自上而下）：
   *
   *   - `isDispatcher` ⇒ `dispatcher`（分发器会话）；
   *   - `flowNodeId` 非空 ⇒ `node`（**project 节点会话**）；
   *   - `sessionId` 非空 ⇒ `machine`（其余具名会话：宿主/REST 直调等）；
   *   - **零身份面**（三者皆空）⇒ `human`——**回落语义，不是「未知」**（既有调用方
   *     多为人工/工具直调，保守归到 human 与旧口径一致）。
   */
  def reactivateSource(isDispatcher: Boolean, flowNodeId: Option[String], sessionId: Option[String]): String =
    if isDispatcher then "dispatcher"
    else if flowNodeId.exists(_.trim.nonEmpty) then "node"
    else if sessionId.exists(_.trim.nonEmpty) then "machine"
    else "human"

  /**
   * `actor=` 尾段（会话 id；🔴 **键非空才发**——缺身份面时返回空串，禁发空值键，与
   * `chainMembershipChangedSummary` 的「不变则 Nil」同款「不写无信息键」纪律）。
   * 值经 [[noWs]]（行内不得含空白，与 [[edgeChangedSummary]] 同源纪律）。
   */
  def reactivateActorSuffix(sessionId: Option[String]): String =
    sessionId.map(noWs).filter(_.nonEmpty).map(id => s" actor=$id").getOrElse("")

  /**
   * `chain-cancelled` 结构化 summary（`k=v` 单空格分隔，值不含空白——沿
   * [[dispatcherWakeSummary]] 的 [[noWs]] 纪律，reason 全文进通知文本/节点 result）。
   */
  def chainCancelledSummary(
    chainId: String,
    source: CancelSource,
    reason: String,
    cancelled: Int,
    preserved: Int,
    skipped: Int,
    memberIds: List[String]
  ): String =
    s"chain=${if chainId.isEmpty then "-" else noWs(chainId)} source=${CancelSource.code(source)} " +
      s"reason=${noWs(reason).take(80)} cancelled=$cancelled preserved=$preserved skipped=$skipped " +
      s"members=${memberIds.mkString(",")}"

  /**
   * **判词闸回退告警**事件类型（engine-defects 批 #238：2026-09-15 `8a3ac535e` 定义，
   * 同日泛化笔 `v238-impl` 语义反转为**回退检测器**——类型串保持不变，消费面零迁移）。
   *
   * 写点 = [[NodeEngine.startNode]] 的 verdict 闸收口（`NodeEngine.logVerdictGateBreach`）。
   *   - **泛化前**（`8a3ac535e`）：判词闸是 merge-only ⇒「非 merge 收口位带非 pass 判词
   *     上游仍被拉起」属**常态**，本行 = 「人肉口径 → 机械口径」的可见化；
   *   - **泛化后**（同批第二笔）：闸覆盖全部收口位（判据对节点形态零分叉）⇒ 该形态
   *     **结构性不可能再发生**（本告警与闸共用 `staleVerdictUps` 单点，闸持有时走不到写点）
   *     ⇒ 本行语义 = **不变式告警**：出现即表示闸被绕过 / 被改弱（或新增了绕开
   *     `startNode` 收口的启动腿）。
   * nodeId = 被启动的下游；同一 (下游, 持有者清单) 只发一次（单发记账防刷屏）。
   * 取证：`grep 'verdict-gate-gap' <ws>/.nebflow/flow-map-events.jsonl`。
   */
  val VerdictGateGapType = "verdict-gate-gap"

  /**
   * 分发器会话空闲到期销毁事件类型（**令 3 分发器生命周期** 2026-09-12 批，设计
   * §3.2/§4 R4-(a)）：写点 = `ProjectActor.expireIdleDispatcher`（30 s `TtlTick`
   * 扫描腿到点拆除时）。语义 = 「保活期结束 ⇒ 会话已销毁」，使「活着但空闲」与
   * 「已销毁」在事后可对齐（面板/registry 在 turn 末即无行，空闲期无第二观察面）。
   * `nodeId` 字段承载**会话 id**（`dispatcher-<uuid8>`）——分发器不是 Flow 节点、
   * 无 NodeDef.id（`ProjectActor` spawn 处 `flowChainId = None` 同口径）；不写
   * `chainId`（分发器不属任何链）。
   */
  val DispatcherIdleExpiredType = "dispatcher-idle-expired"

  /** Dispatcher **task-terminal teardown** event type (taskunify merge batch 2026-09-24,
    * landing point 5 / ruling d①).
    *
    * Write point = `ProjectActor.expireTaskTerminalDispatcher` (the 30 s `TtlTick` sweep leg
    * finds that "the task this dispatcher is bound to reached a terminal state" and tears
    * the session down).
    *
    * 🔴 **Why this needs its own type (implplan §5 "new audit face")**: the semantics of
    * `dispatcher-idle-expired` is "the keep-alive window ended (a **time** criterion)";
    * this type's semantics is "**the task is finished** (a **business** criterion)". Sharing
    * one type name would make the two causes **indistinguishable** in the event stream, and
    * nobody could answer afterwards "was this teardown a timeout or a task terminal state".
    * ⇒ Criteria 5.1/5.2 use it to verify the two paths separately.
    *
    * The `nodeId` field carries the **session id** (same precedent as
    * [[DispatcherIdleExpiredType]] — a dispatcher is not a Flow node and has no
    * `NodeDef.id`); `taskId` is this change's **business key** (written into the summary so
    * "which finished task caused the teardown" is mechanically greppable — deliberately not
    * stuffed into `nodeId`, which would clash with the precedent's semantics).
    * `chainId` is not written (a dispatcher belongs to no chain). */
  val DispatcherTaskTerminalType = "dispatcher-task-terminal"

  /** Task-terminal teardown structured summary (`k=v`, single-space separated, **values
    * carry no whitespace**). */
  def dispatcherTaskTerminalSummary(sessionId: String, taskId: String, state: String, reason: String): String =
    s"session=$sessionId task=$taskId state=$state reason=$reason"

  /** Revive-evict teardown event type (Mail task-continuation batch, 2026-09-28): a Mail
    * revive trigger found the task's dispatcher slot still occupied (the residue a 30 s
    * task-terminal sweep window can leave behind) and evicted it so a FRESH bound session
    * takes over — "dispatcher re-mounted" is only true if the old registration verifiably
    * died. A **new type**, deliberately distinguishable from the sweep's
    * `dispatcher-task-terminal` (different cause: revival, not expiry); the `nodeId` field
    * carries the session id under the same precedent as the terminal/idle events. */
  val DispatcherReviveEvictType = "dispatcher-revive-evict"

  /** Revive-evict structured summary (`k=v`, single-space separated, values carry no
    * whitespace). `reason=revived-by-mail` = the Mail task-continuation leg did this. */
  def dispatcherReviveEvictSummary(sessionId: String, taskId: String, reason: String): String =
    s"session=$sessionId task=$taskId reason=$reason"

  /** Concurrency-cap refusal event type (taskunify merge batch 2026-09-24; design §4d
    * mandatory anti-leak item).
    *
    * Write point = the pre-spawn gate in `ProjectActor.dispatchTask`: the dispatcher session
    * count has reached [[nebflow.shared.Defaults.DispatcherMaxConcurrentSessions]] ⇒
    * **explicit refusal + alert** (never a silent over-spawn). 🔴 Division of labour with
    * the `TASK_*` family: that family is the **tool-call-level** refusal text; this type is
    * the **engine-side** gate's trace face (the "event face" of the double-trace rule).
    *
    * `nodeId` carries the **project name** (there is no session to point at — the refusal
    * happens before any session exists; same precedent as [[DispatcherWakeType]] carrying a
    * project name in this field). */
  val DispatcherConcurrencyRefusedType = "dispatcher-concurrency-refused"

  /** Concurrency-cap refusal structured summary (`k=v`, single-space separated, **values
    * carry no whitespace**).
    *
    * 🔴 **All four elements must live in this one summary line** (project name ∧ current live
    * count ∧ cap ∧ **way out**) — the hard requirement is "visible + locatable, not silent",
    * so the mechanical criterion has to be assertable **on the event line alone** (the WARN
    * face carries the same text but must not be needed to complete the four elements).
    * [[wayOut]] is passed in rather than inlined so the event face and the WARN face state
    * exactly the same remedy wording. */
  def dispatcherConcurrencyRefusedSummary(project: String, active: Int, cap: Int, reason: String, way: String): String =
    s"project=${project.replaceAll("\\s+", "_")} active=$active cap=$cap reason=${reason.replaceAll("\\s+", "_")} way=${way.replaceAll("\\s+", "_")}"

  /** The single source of the cap-refusal way-out wording (shared by the event summary's
    * `way=` field and the WARN text). */
  val DispatcherConcurrencyCapWayOut: String =
    "raise nebflow.dispatcher.maxConcurrentSessions, or let the running dispatcher finish / cancel it"

  /** 🔴 **Uplink-refused trace event type** (taskunify merge batch 2026-09-24 · ruling T ·
    * implplan §8/§10.4).
    *
    * Semantics = "one uplink notification whose **attribution cannot be resolved** (the node
    * has no `taskId` fingerprint) was **refused fail-closed**". Write point = the single
    * engine-side uplink circuit [[NodeEngine.uplinkAllowed]] (the common precondition of all
    * seven uplink paths U1–U7).
    *
    * 🔴 **Why this event must exist (ruling T = ⓑ double trace)**: the `TASK_*` error-code
    * family covers only the **tool-call-level** refusal text (the `Task` / `TaskInfo`
    * returns). The **engine-side uplinks** (node completed/failed notification, blocked
    * reentry, landing/merge uplink, cancel notification, manual redelivery) go through no
    * tool at all ⇒ without a dedicated event face here, "the landing failed but could not be
    * sent out" would be **silent** — exactly the case implplan §10.4 marks as "easiest to
    * miss" (U3's no-loss argument happens not to cover it).
    *
    * 🔴 **One refusal ⇒ exactly one event** (criteria 10.4.1/10.4.2): **no merging, no
    * suppression** (no same-key window suppression — "two in a row ⇒ two events" is a hard
    * criterion). **A log line alone is forbidden** (criterion 10.4.5: a missing event face
    * is a red).
    *
    * `nodeId` carries the **refused node's id** (that node is this event's subject — same
    * family as [[DispatcherIdleExpiredType]] carrying a session id in this field: the field
    * carries the subject's identifier). `chainId` is passed through from the call site when
    * present, for chain-based lookback. */
  val UplinkRefusedType = "uplink-refused"

  /** Structured summary for an uplink refusal (`k=v`, single-space separated, **values carry
    * no whitespace** — whitespace inside the text is normalised to `_` so that class-counting
    * criteria such as `grep 'uplink-refused' | grep -c 'landing'` are not broken by spaces).
    *
    * 🔴 **The three text elements must all be present (criterion 10.4.4)**: one single line
    * carrying ① the **node id** (`node=<id>`) ② the **refusal reason**
    * (`reason=no-attribution` — the reason face is "no attribution fingerprint" / "not
    * registered in the ledger") ③ the **way out** (`way=<...>` — `register-attribution` /
    * `node_report` / `Flow Map` / `manual`). `kind` is the uplink class, and the
    * **landing/merge class must be distinguishable** (`kind=landing`, the core of criterion
    * 10.4.3). */
  def uplinkRefusedSummary(
    nodeId: String,
    nodeName: String,
    kind: String,
    reason: String,
    way: String
  ): String =
    def norm(s: String): String = s.replaceAll("\\s+", "_").trim
    s"node=${norm(nodeId)} name=${norm(nodeName)} kind=${norm(kind)} reason=${norm(reason)} way=${norm(way)}"

  /** 分发器**未消费注入件**审计事件类型（mailack 批 2026-09-23，D 项止损）。
    *
    * 写点 = `ProjectActor` 的**每一处分发器会话拆除**（`dispatcherBridge.teardown` 与
    * `expireIdleDispatcher`）：拆除时若 `pendingTaskTexts` 非空（已注入但 turn 尚未消费
    * 的件），把这些件**数量 + 逐件首行**落一条事件。
    *
    * 动因（2026-09-23 audit 实测）：`cancelAgent` / 空闲到期拆除**不检查**未消费队列，
    * 队列里的件随会话**静默蒸发**——零审计、零补投（分发器任务不在任何补投扫描内，
    * `ProjectActor` 的 `redeliver` 只覆盖 dispatch-notify 与节点结果）。实证 18:18:08
    * 一次 `cancelAgent (panel)` 时 `pending=31`。本事件使该形态**可审计**（不是补投：
    * 补投需要幂等键，件正文非幂等语义载体，属另批）。
    *
    * `nodeId` 字段承载**会话 id**（同 [[DispatcherIdleExpiredType]] 先例）。 */
  val DispatcherQueueDroppedType = "dispatcher-queue-dropped"

  /** 未消费件审计 summary（`k=v` 单空格分隔，**值不含空白**：首行空白归一为 `_` 并截断，
    * 防 k=v 解析被破坏；完整正文不落事件行——事件行必须保持单行）。 */
  def dispatcherQueueDroppedSummary(sessionId: String, dropped: Int, reason: String, firstLines: List[String]): String =
    def norm(s: String): String =
      val t = s.replaceAll("\\s+", "_").trim
      if t.length > 80 then t.take(80) + "…" else t
    val head = s"session=$sessionId dropped=$dropped reason=${norm(reason)}"
    if firstLines.isEmpty then head
    else head + " items=" + firstLines.map(norm).mkString("|")

  /** 空闲到期事件结构化 summary（`k=v` 单空格分隔，值不含空白；`session` 值形如
    * `dispatcher-<8hex>`，天然无空白）。 */
  def dispatcherIdleSummary(sessionId: String, idleSecs: Long, windowMs: Long): String =
    s"session=$sessionId idleSecs=$idleSecs windowMs=$windowMs"

  /**
   * 宿主启动自动重入事件类型（boot-wake 批 2026-09-13，方案件 A 档 A1「控制面唤醒腿」）。
   *
   * 写点 = `BootDispatcherWake.record`（GatewayMain boot 链 `projectBootWake` 腿）：
   * 每 boot 每在册项目**恰一条**——含未唤醒形态（`skipped` + reason），使「零唤醒 boot」
   * 在事件流里可审计（方案 §1.4(c)/R9：此前唯一正证据只有 `mount-stalled`，其余全是
   * 「缺失的日志行」）。
   *
   * `nodeId` 字段承载**项目名**（分发器不是 Flow 节点、无 `NodeDef.id`；同
   * [[DispatcherIdleExpiredType]] 以会话 id 承载该字段的先例：此字段承载发起者标识）。
   * 不写 `chainId`（唤醒是项目级动作，不属任何链）。清单正文只进分发器首条输入与
   * `boot-wake.json`（事件行必须保持单行 `k=v`）。
   */
  val DispatcherWakeType: String = "dispatcher-wake"

  /**
   * 唤醒事件结构化 summary（`k=v` 单空格分隔，**值不含空白**——reason 内的空白
   * 归一为 `_` 并截断，防 k=v 解析被破坏）。`counts` = (nodes, B1, B2, B3, B4)。
   * `cause`（hostresume 批 2026-09-22 可选新增，D-5 仅措辞）：上次停机成因标注——
   * None = 不追加任何字节（既有 summary 逐字不变）；Some = 尾部追加 ` cause=…`。
   */
  def dispatcherWakeSummary(
    bootId: String,
    atMs: Long,
    result: String,
    reason: String,
    counts: (Int, Int, Int, Int, Int),
    items: Int,
    truncated: Int,
    cause: Option[String] = None
  ): String =
    val (nodes, b1, b2, b3, b4) = counts
    val r = if reason.isEmpty then "-" else reason.replaceAll("\\s+", "_").take(80)
    s"boot=${bootId.replaceAll("\\s+", "_")} at=$atMs result=$result reason=$r" +
      s" nodes=$nodes b1=$b1 b2=$b2 b3=$b3 b4=$b4 items=$items truncated=$truncated" +
      cause.map(c => s" cause=${noWs(c).take(80)}").getOrElse("")
  end dispatcherWakeSummary

  /**
   * 宿主睡眠/唤醒审计事件类型（hostresume 批 2026-09-22，设计卡 §4 #10，D-7 裁定
   * 「唤醒仅审计事件、不揽分发器」）。
   *
   * 写点 = `WakeSensor` 双钟断流纤维判出睡眠窗后（每在册项目一条；窗检测与
   * 台账 append 同点、同 fail-soft 纪律）。语义 = 「宿主经历了冻结窗，此刻已醒」——
   * 零节点写、零重入、零分发器通知（挂起-恢复内存世界完好、分发器自愈已实证，
   * 设计卡 §2.3 唤醒面）。
   *
   * `nodeId` 字段承载**宿主实例标识**（= `BootDispatcherWake.instanceId`，观测进程
   * 的 JVM startTime-pid；同 [[DispatcherWakeType]] 以发起者标识承载该字段的先例）。
   * 不写 `chainId`（宿主级事件不属任何链）。
   */
  val HostWakeType: String = "host-wake"

  /**
   * `host-wake` 结构化 summary（`k=v` 单空格分隔、值不含空白——[[noWs]] 纪律同
   * [[dispatcherWakeSummary]]）。`wallMs`/`nanoMs` = 该窗的双钟原始读数差（取证对账
   * 面：`frozenMs = wallMs - nanoMs`）。
   */
  def hostWakeSummary(
    bootId: String,
    sleepAtMs: Long,
    wakeAtMs: Long,
    frozenMs: Long,
    wallMs: Long,
    nanoMs: Long,
    slopMs: Long
  ): String =
    s"boot=${noWs(bootId)} sleepAt=$sleepAtMs wakeAt=$wakeAtMs frozenMs=$frozenMs" +
      s" wallMs=$wallMs nanoMs=$nanoMs slopMs=$slopMs"

  /**
   * 合并窗 FIFO 互斥闸事件类型（**mergefifo-engine 批** 2026-09-13，作者 A-4 裁决）。
   *
   * 写点 = `NodeEngine` 的 merge 互斥闸判定位（[[logMutexHold]] 与
   * `alarmSameGitDirProjects`）。两种 summary（`k=v` 单行）：
   *   - `kind=hold`：本 merge 被同键更高优先者挡住（闸停等留痕，单发=持有者集合变化时
   *     才写，禁每轮刷屏）；
   *   - `kind=same-git-dir-multi-project`：**O-1 已知缺口告警**——检测到另一在册项目
   *     与本项目**同键**（`realpath(git-common-dir)` 相等）⇒ 引擎侧持有者派生自本项目
   *     store，此形态**漏互斥**（本批不实现 claim/抢占，作者令）；只做「发生即告警」。
   * 设计件 §7.2 规划的第三种 summary（等待超预算）**引擎侧不写**：等待超预算的上报按
   * SEM-3 由 sink 自身承担（引擎不自动上报，与既有「合法等待」口径一致）。
   * `nodeId` 字段对 hold 形态承载节点 id；对相同 git 目录形态承载**项目名**（与
   * [[DispatcherWakeType]] 同款先例：该字段承载发起者标识）。不写 `chainId`。
   */
  val MergeQueueType: String = "merge-queue"

  /**
   * k=v 值归一（空白 → `_`，与 [[dispatcherWakeSummary]] 同款；防 `k=v` 解析被注释
   * 或路径中的空白破坏——真实键是绝对路径，本仓工作区含空格）。
   */
  def noWs(s: String): String = s.replaceAll("\\s+", "_")

  /** `merge-queue` / hold 形态 summary：本 merge 被同键更高优先者挡住。 */
  def mergeQueueHoldSummary(where: String, holders: List[String]): String =
    s"kind=hold at=${noWs(where)} holders=${holders.mkString(",")}"

  /**
   * `merge-queue` / 同键多项目形态 summary（O-1 告警；键按 k=v 纪律归一，原始键
   * 全文在同期 WARN 日志行里，取证走日志面）。`foreignRunning` = 他项目中此刻处于
   * running 的 merge 节点数（>0 = 真实并发争用，而非静态配置问题）。
   */
  def mergeQueueSameGitDirSummary(
    key: String,
    foreign: List[String],
    foreignRunning: Int,
    mineRunning: Int
  ): String =
    s"kind=same-git-dir-multi-project key=${noWs(key)} foreign=${foreign.mkString(",")} " +
      s"foreignRunning=$foreignRunning mineRunning=$mineRunning"

  /**
   * 归档事件结构化 summary（`k=v` 单空格分隔，值不含空白；消费者侧解析单点
   * [[parseChainSummary]] 与本函数同源，防写读口径漂移）。
   */
  def chainArchivedSummary(chainId: String, archivedAt: Long, members: Int): String =
    s"chain=$chainId archivedAt=$archivedAt members=$members"

  /**
   * `chain-archive-held` structured summary single point (verdict-consumer batch ·
   * case A (b)), mirroring [[chainArchivedSummary]] line for line: `k=v` pairs, single
   * spaces, values carrying no whitespace.
   *
   * 🔴 **append-only**: the new key `unconsumedVerdicts` is appended **after** the frozen
   * `chain=` / `archivedAt=` / `members=` triple, whose keys and order stay byte-for-byte
   * unchanged — and it rides **this** event, never `chain-archived` (that event's summary
   * is frozen for its existing consumers). Old readers keep parsing these lines because
   * [[parseChainSummary]] accepts unknown keys as-is (zero migration).
   *
   * The summary must carry the two manual exits **verbatim** (same actionable-trace
   * discipline as [[verifierRouteLostSummary]]): ① wire `(pass)<landing>` — the condition
   * is a pure derived quantity, so the next sweep releases the chain with no extra action;
   * ② retire the verifier with `NodeEdit(abandon=true)`.
   */
  def chainArchiveHeldSummary(chainId: String, archivedAt: Long, members: Int, unconsumed: List[String]): String =
    val ids = unconsumed.distinct.sorted
    val detail = if ids.isEmpty then "-" else ids.mkString(",")
    s"chain=$chainId archivedAt=$archivedAt members=$members unconsumedVerdicts=${noWs(detail)} " +
      "reason=pass-verdict-unconsumed — the chain was archived as usual; these verifiers judged `pass` with no pass outlet " +
      "(nothing consumes their positive verdict). Manual exits: (1) wire '(pass)<landing>' on the verifier " +
      "(pure derived judgement — the next sweep releases it with no further action); " +
      "(2) retire the verifier with NodeEdit(abandon=true)."

  /** 拉回事件结构化 summary（对称口径；restoredAt 与 archivedAt 同键位语义）。 */
  def chainRestoredSummary(chainId: String, restoredAt: Long, members: Int): String =
    s"chain=$chainId restoredAt=$restoredAt members=$members"

  /**
   * 结构化 summary 解析：按空白切分取 `k=v` 对（非 `k=v` 词条丢弃）。消费者只取
   * `chain` / `archivedAt` / `restoredAt` / `members`，未知键照收不拒（向前兼容）。
   */
  def parseChainSummary(summary: String): Map[String, String] =
    summary
      .split("\\s+")
      .iterator
      .filter(t => t.indexOf('=') > 0)
      .map { t =>
        val i = t.indexOf('=')
        t.substring(0, i) -> t.substring(i + 1)
      }
      .toMap

  /**
   * 追加一条审计事件。workspace 为项目工作区绝对路径；IO.blocking 隔离磁盘写。
   * dispatch-notify 批（2026-09-05）：新增事件 type `dispatch-notify`（节点终态
   * 回流分发器通知——triggered / budget-exhausted 两形态，写点在 DispatchNotify，
   * 追加式注册同 bg-wait/trigger-starved 先例）。
   * P3 归档联动批（2026-09-10）：新增可选顶层 `chainId`（默认 None = 不写该键，
   * 既有调用点零改动、旧行零迁移）；链族事件（[[ChainArchivedType]] /
   * [[ChainRestoredType]]）写入时带上，消费者免从 summary 反解析取链 id。
   */
  def append(
    workspace: String,
    project: String,
    nodeId: String,
    typ: String,
    summary: String,
    chainId: Option[String] = None
  ): IO[Unit] =
    // ts 求值时点修复（noderpt 批 B 段 2026-09-11，实测 `n-0931699e`）：此前
    // `System.currentTimeMillis()` 在 **IO 构造期**求值（在 `IO.blocking` 之外），
    // 事件行的 ts 因此是「构造该 IO 的时刻」而不是「真正落盘的时刻」——对
    // **延迟触发**的写入点（`bg-wait` 武装时构造、cap 到点才执行的 `bg-wait-timeout`）
    // 偏差可达整个等待窗口（实测 harvest − timeout = 7,199,951ms ≈ 1 个 cap，而
    // bg-wait 与 bg-wait-timeout 两行 ts 只差 5ms ⇒ 全部按「构造时刻」写）。任何按 ts
    // 反推「节点挂了多久」的取证都会得出错误结论。修法 = 把 ts 求值移进同一
    // `IO.blocking`（执行时刻求值，与落盘同一时刻），构造与执行分离时 ts = 真实触发
    // 时刻（≥ 执行开始时刻）。JSON 组装一并移入（零额外开销、字段集与顺序逐字不变）。
    IO.blocking {
      val ts = System.currentTimeMillis()
      val base = List(
        "ts" -> ts.asJson,
        "type" -> typ.asJson,
        "project" -> project.asJson,
        "nodeId" -> nodeId.asJson,
        "summary" -> summary.asJson
      )
      val fields = chainId.filter(_.trim.nonEmpty).map(id => base :+ ("chainId" -> id.asJson)).getOrElse(base)
      val line = Json.obj(fields*).noSpaces
      // createFolders：`.nebflow/` 缺席（未挂载工作区/测试新目录）时自建——审计写永不
      // 因目录缺失整条丢失（既有写点均在已挂载项目内，本参数对其零行为变化）。
      os.write.append(os.Path(workspace, PathUtil.dataRoot) / ".nebflow" / FileName, line + "\n", createFolders = true)
    }.void
end FlowMapEventLog
