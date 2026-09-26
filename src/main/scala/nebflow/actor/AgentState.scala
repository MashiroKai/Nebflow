/* 从 protocol.scala 迁出(行为保持重构,2026-09-25)。 */
package nebflow.actor

import cats.effect.IO
import io.circe.Json
import nebflow.actor.ActorRef
import nebflow.shared.*

/**
 * Display/routing kind of an agent (P1 统一注册表). Replaces the nodeSessionId
 * string-prefix conventions (team-/dag-/delegate-/ephemeral-) as the *identity*
 * discriminator; P3 drops the prefixes entirely and routes by this field.
 */
enum AgentKind:
  case Root, Team, Flow, Delegate, Ephemeral, Plan, SubTask

/**
 * Unified registry entry — one identity per agent (P1 统一注册表).
 *
 * sessionId is the single identity: execution key + persistence key + display key.
 * ref is the runtime reply target; kind is the display type; rootSessionId anchors
 * the permission-policy bucket (P2) and the inheritance chain; parentRef is kept
 * only for Mail semantics / upward diagnostics, NOT for interaction forwarding.
 */
case class AgentRecord(
  sessionId: String,
  ref: ActorRef[AgentCommand],
  kind: AgentKind,
  rootSessionId: String,
  parentRef: Option[ActorRef[AgentCommand]] = None,
  /**
   * P0 阶段 3（2026-08-18，设计 §4.4）：registry 注册时刻（task 生命周期起点）。
   * 默认 0 = 未设置（旧注册点）；TaskStuckWatcher 只依赖 lastActivityMs，
   * startedAt 供前端展示（二期 §4.6）。
   */
  startedAt: Long = 0L,
  /**
   * P0 阶段 3：当前 turn 状态快照——TaskStuckWatcher 判定 "Processing 且
   * 长时间无活动" 的 status 来源。由 AgentCore.pipeLlmCall（Processing）与
   * AgentActor.finishTurnCont 回 idle 分支（Idle）维护；run_in_background
   * 时 agent 回 Idle 为合法状态，永不判卡死（防误杀铁律）。
   */
  status: AgentStatus = AgentStatus.Idle,
  /**
   * P0 阶段 3 + 2026-09-10 卡死判据换轴（信号拆分）：最近一次 **agent 侧**
   * 活动时间戳——只允许由 agent 侧事件写入：LLM 调用开始 / 流 chunk / 工具
   * 批次完成（AgentCore.pipeLlmCall / pipeToolExecutions）、turn 终态与控制
   * 消息（AgentActor 各点）、AskUser/permission 进出（AgentCore.askUserPermission）、
   * 出生注册。**进程侧活性不得再写入本字段**（写入点见 `processActivityMs`）。
   * TaskStuckWatcher 判卡死的数据源之一。
   */
  lastActivityMs: Long = 0L,
  /**
   * 2026-09-10 卡死判据换轴（取证 `20260910_130621_flow-node-activity-signal-forensics.md`）：
   * **进程侧**活性时间戳——唯一写点 = `BashTool.startActivityBridge`（前台命令
   * 有进展：stdout 行数增长 / CPU ≥ CpuActiveThresholdNanos / sleep-like）与
   * `RemoteExecutor` 远程前台心跳（每 30s 无条件）。
   *
   * 语义 =「这个会话正在跑的子进程还有动静」，**不是**「agent 还在推进」——
   * 本次事故（n-5c69c793 会话被前台 `npx next dev` 占死 2h50m）里 dev server
   * 的 CPU 微动（实测 1.786 ms/s = 10ms 阈值的 5.4 倍）持续刷新 agent 侧戳，
   * 导致 TaskStuckWatcher / SessionKick / AgentControl 全部失明。
   *
   * **本字段不得再被 TaskStuckWatcher / SessionKick / 人可见 idle 读取**：
   * 它只能作为「子进程确实活着」的旁证，不构成「有进展」。
   */
  processActivityMs: Long = 0L,
  /**
   * 2026-09-10 卡死判据换轴：当前 turn 起点（该 turn 首次进入 Processing 的
   * 时刻）。由 AgentCore.touchRegistryActivity 在 status 由非 Processing
   * 转入 Processing 时置位（同 turn 内的多轮工具循环不重置）；仅诊断/展示用，
   * 不参与卡死判据（判据用 `currentToolStartedAt`）。
   */
  turnStartedAt: Long = 0L,
  /**
   * 2026-09-10 卡死判据换轴：当前 turn 内在飞的工具名。写入点 = 工具执行开始
   * （AgentCore.pipeToolExecutions 既有工具循环内，与 touchRegistryActivity 同
   * 一写入路径）；离开 Processing（Idle/WaitingForUser/Frozen/Error）或工具批次
   * 完成时清空。人可见性：AgentControl list 的 phase 列 / status 的 toolPhase 行。
   */
  currentToolName: Option[String] = None,
  /**
   * 2026-09-10 卡死判据换轴：当前工具调用的起始时刻（0 = 无在飞工具）。
   * **新卡死判据的主轴**：同一 turn 内单个工具调用持续超过
   * `Defaults.ToolPhaseStuckMs`（默认 10min）且 status 仍为 Processing →
   * 判卡死并走既有 L1→L4 分级恢复。判据**不引用任何进程 CPU**。
   */
  currentToolStartedAt: Long = 0L,
  /**
   * R6（取消静默死锁修复批 2026-09-10，作者裁定 R6 方案 2）：当前在飞工具**自己
   * 声明**的授权时长（ms）——工具开始时从入参 JSON 的 `timeout` 字段读出
   * （[[nebflow.shared.Defaults.declaredToolTimeoutMs]]，只读声明值、不读工具默认
   * 值）。0 = 未声明。
   *
   * 判据消费单点 = `TaskStuckWatcher.assess` 的 toolOverdue 轴（经
   * [[nebflow.core.processor.ToolStuckJudgment.effectiveToolPhaseMs]]）：
   * `toolPhaseMs > max(ToolPhaseStuckMs, currentToolDeadlineMs + slack)` —— 判据
   * 尊重命令自己声明的合法时长（案例 1 的 `timeout=900000ms` 不再在 11.2 分钟被
   * 判死）。**只放宽不收紧**；未声明 `timeout` 的工具仍按默认档 10min 判死。
   * 口径已裁定（2026-09-10：以 `max` 为准；设计 §6-R6 逐字写 `min`，与本项立论
   * 「尊重命令自己声明的合法时长」不自洽——见设计 §6-R6 订正注记）。
   * 判据仍**不引用任何进程 CPU**。
   *
   * 生命周期与 currentToolStartedAt 同步（工具开始置位 / 工具批次完成与离开
   * Processing 清 0）。
   */
  currentToolDeadlineMs: Long = 0L,
  /**
   * AgentControl（2026-08-19 spec §3.1）：监听该 agent 终态的 adapter 引用——
   * ephemeral Delegate/SubTask = BackoffSupervisor；persistent Delegate =
   * persistentAdapter；Ephemeral/Flow/Root/Team = None（无取消通道，cancel 走
   * 降级 Stop 路径）。默认 None → 既有注册点零改动。
   */
  supervisorRef: Option[ActorRef[AgentEvent]] = None,
  /**
   * AgentControl：任务归属的父会话（SubAgentTaskStore 文件键）。Delegate/
   * SubTask 注册点已有值；list/restart 用它反查任务元数据，避免全目录扫描。
   */
  parentSessionId: String = "",
  /**
   * issue #31 (2026-08-20) Fix D — phantom barrier 可见化：该 agent 自身
   * outstandingSubagentResults 的最近快照（spawn 计数 / ExternalEvent 三分支
   * 时刷新）。诊断语义：idle 期 outstanding > 0 且无在飞任务 = barrier 被
   * phantom slot 占据（成员 hang/停止失败时发生，held 结果永不注入）。
   */
  outstandingSubagents: Int = 0,
  /**
   * issue #31 Fix D — 同上：该 agent pendingEvents（被 HOLD 的子代理结果
   * 队列）长度的最近快照。outstanding>0 时 pending>0 = 有结果被扣留等批。
   */
  pendingEventCount: Int = 0,
  /**
   * v2 冻结式错误恢复升级链（§5.2）：升级链状态快照（P0 内存态）——
   * enterErrorFrozen 第 3 次进入时设置、FreezeScheduler.scan 读它检查
   * escalateAt 到期（升级/跳级/到达用户）。与 AgentState.execution.escalation
   * 同步维护（registry 层供扫描器只读，actor 层为权威）。
   */
  escalation: Option[EscalationInfo] = None,
  /**
   * v2 冻结式错误恢复：当前冻结的 reason 字符串快照（"schedule"/"llm-transient"/
   * "network"/"provider-down"/"restart-recovery"）。WS parentRestart 用它区分
   * 时间表冻结（正常调度，不可父重启）与错误族冻结（可重启）。与
   * AgentState 的 lastErrorFreezeReason 同步维护；恢复/解除冻结时清 None。
   */
  frozenReason: Option[String] = None,
  /**
   * Block 3 循环检测器观测镜像（supervision trio §D2-B）：当前 turn 的
   * 同参同败连续计数 / 非进展轮数——pipeToolExecutions 每轮随 touchRegistryActivity
   * 同步写入。AgentControl list 的 stuck? 列旁显示 loop×N（诊断「高活动零进展」）。
   */
  loopStreak: Int = 0,
  loopRounds: Int = 0,
  /**
   * Project 归属（2026-09-06 作者裁定：Sub-Agents 面板 Flow 徽标旁标注项目名）。
   * 仅 Project 域会话（node- 与 dispatcher- 前缀，NodeEngine/ProjectActor 注册点）有值；
   * Delegate/SubTask/Ephemeral/FlowDAG/Team 等 None（默认 = 既有注册点零改动）。
   * 恢复路径数据源：activeAgentEntryJson 输出 project 字段供前端刷新后渲染徽标；
   * 实时路径不经此字段（agentStart 帧由 routeSubagentWsSend 转发层注入）。
   */
  project: Option[String] = None,
  /**
   * 会话展示名（刷新恢复路径专用，20260907 节点名刷新持久化批）：Project 域注册点
   * （NodeEngine 节点 / ProjectActor 分发器）写 Flow Map 节点名 / "dispatcher/<project>"；
   * 其余域 None（默认 = 既有注册点零改动）。activeAgentEntryJson 恢复链消费：
   * meta.agentName → displayName → sessionId 三档。
   */
  displayName: Option[String] = None,
  /**
   * 正信号（**进展证据**）时间戳（stuck 自动恢复批 P1，2026-09-11 作者裁定 R-3）。
   *
   * 语义 = 「本会话当前在飞的工具，在上一个采样窗里**确有推进**」——由工具活动桥的
   * 采样点判定（stdout 行数增长 ∨ 单窗 CPU 增量 > 活动桥阈值），写点 =
   * `AgentCore.markToolProgress`（唯一写入语义落点；真实传感器 = 工具活动桥采样循环，
   * 它与 `processActivityMs` **同点、同判据、不同语义**：后者是「子进程还活着」的
   * 旁证，本字段是「本窗有进展」的证据）。
   *
   * **方向性（红线 R6-4 的边界，作者已确认）**：本字段**只阻止判死、绝不促成判死**
   * ——它只出现在「⇒ 归 类② 假阳性、本拍不动作」的合取项里，不出现在
   * `TaskStuckWatcher.assessDetailed` 任何判死不等式内。0 = 从未观测到进展。
   *
   * 与 `processActivityMs` 的关键差异：后者被明文禁止被 watcher 读取（见其上注释），
   * 本字段是为此**显式新开**的、语义为「进展证据」的通道——两者不可互相顶替。
   */
  lastProgressSignalAt: Long = 0L,
  /**
   * LoopGuard 跨轮指纹**只读投影**（stuck 自动恢复批 P1：互斥点 2 的可见性缺口，
   * 设计 §3.4/§3.8）。`AgentState.loopCounters` 在 actor 内不可读，而 watcher 需要
   * 「本会话是否处于循环形态」的只读快照来识别「恢复 → 立刻又被 LoopGuard 冻结」
   * 的自激循环。
   *
   * 语义 = `Counters.crossTurn` 的**累计命中数**（fp → 失败过的 turn 集合，各自大小求和）。
   * 写点 = `AgentCore.pipeToolExecutions` 既有 loopStreak/loopRounds 镜像同点（**不碰
   * 冻结面** `AgentActor`/`LoopGuard`）。
   *
   * **只读不回流判据**：本字段**不参与任何判死不等式**，只作为「停恢复链」的
   * 互斥信号（恢复前后对比：计数上升 = 其间发生过循环命中）。
   */
  loopStrikeCount: Int = 0,
  /**
   * LoopGuard 最近一次跨轮命中的指纹（`Counters.crossTurn` 中命中次数最多者，
   * 同数取字典序最小 —— 确定性投影，与 [[loopStrikeCount]] 同点写入）。
   * 空串 = 无跨轮命中记录。**只读不回流判据**（同 [[loopStrikeCount]]）。
   */
  lastLoopFp: String = ""
)

enum AgentStatus:
  case Idle
  case Processing
  case WaitingForUser

  /** 冻结调度：dispatch 边界被冻结时间表拦住（#337 黑名单语义），挂起等待出冻结段/用户唤醒。 */
  case Frozen
  case Error(msg: String)

case class CompactionResult(before: Int, after: Int)

/**
 * Compaction execution phase. Single-stage model (2026-08-31 redesign):
 *  - Compact: tools disabled, single text-only summary turn.
 * The former Save stage (two-stage model) was removed — compaction only
 * compresses; memory maintenance happens outside the compaction round
 * (event-time writes + periodic consolidation).
 */
enum CompactionPhase:
  case Compact

case class CompactionJob(
  subagentId: String,
  mode: String,
  replyDeferred: Option[cats.effect.Deferred[IO, Either[String, CompactionResult]]] = None,
  replyTo: Option[ActorRef[AgentEvent]] = None,
  resumeAfterCompact: Boolean = true,
  postCompactInstruction: Option[String] = None,
  phase: CompactionPhase = CompactionPhase.Compact // single-stage: always Compact
)

case class TurnContext(
  agentDef: AgentDef,
  /**
   * 阶段 2 批 A（2026-09）systemPrefix 整层退役——恒空串；字段保留至阶段 3
   * 随 TurnContext 清理一并移除（PromptSections.assembleSystemPrompt 的
   * prefix 参数已同批删除，稳定首段 = agent system.md）。
   */
  systemPrefix: String,
  projectRoot: Option[String],
  rulesMd: Option[String],
  /**
   * §E.2: workspace-root AGENTS.md（每 turn 重读盘；仅 project 会话，gating 见
   * ContextRefresher.agentsMdEnabledFor）。默认 None 保构造点兼容。
   */
  agentsMd: Option[String] = None,
  thinkingConfig: nebflow.shared.ThinkingConfig,
  branchChange: Option[SystemReminder] = None,
  currentBranch: Option[String] = None,
  skillCatalog: String = "",
  teamCatalog: String = "",
  flowCatalog: String = "",
  memoryBlock: String = ""
)

case class SessionContext(
  sessionId: Option[String] = None,
  sessionName: Option[String] = None,
  recentMessageIds: List[String] = Nil,
  wsSend: Json => IO[Unit] = _ => IO.unit,
  depth: Int = 0,
  readTracker: Option[nebflow.core.tools.ReadTracker] = None,
  fileHistory: Option[nebflow.core.tools.FileHistory] = None,
  contextWindow: Int = nebflow.shared.Defaults.ContextWindow,
  askMode: Option[String] = None,
  language: Option[String] = None,
  projectRoot: Option[String] = None,
  rulesMd: Option[String] = None,
  /**
   * §E.2: workspace-root AGENTS.md spawn 快照（消费权威在 refreshTurn 每 turn
   * 重读盘的 TurnContext.agentsMd）。默认 None 保序列化/构造点兼容。
   */
  agentsMd: Option[String] = None,
  folderId: Option[String] = None,
  chatWidth: Int = 0,
  gitBranch: Option[String] = None,
  safetyMode: String = "confirm-edits",
  /**
   * P2: permission-policy bucket + interaction routing anchor. Passed as a
   * constructor parameter at every spawn site (Root=itself, Team=parent root
   * session, Flow/Ephemeral=itself, Delegate=resolved caller root). Falls back
   * to sessionId so legacy spawn sites still get a sane bucket.
   */
  rootSessionId: String = "",
  pendingAskUser: Option[cats.effect.Deferred[IO, List[String]]] = None,
  pendingPermission: Option[cats.effect.Deferred[IO, Boolean]] = None,
  pendingAskUserReplyTo: Option[ActorRef[List[String]]] = None,
  /**
   * When true, agent must call Mail at least once before finishing.
   *  Used by flow agents to enforce structured result reporting.
   */
  expectsMail: Boolean = false,
  /** Total mail turns completed in this session. */
  mailTurnCount: Int = 0,
  /**
   * 阶段 2a 沙箱会话开关（§A.6/H-5①）：true 时 AgentCore 从 projectRoot 派生
   * ToolContext.sandbox（root=worktree 或 workspace；分发器=project workspace）。
   * 置位点 = project 节点（NodeEngine）与分发器（ProjectActor）spawn，以及
   * Nebula 根会话（WebSocketRoutes，2026-09-05 裁定）；team/flow/Delegate 等双轨
   * 会话默认 false=旧行为（§A.7，双轨期不动旧体系）。
   * 边界（[沙箱拆围栏批 S1, 2026-09-10]）：本字段自本批起**只承载「围栏总闸」**
   * ——root 推导 + 闸门开关；「是否项目作用域会话」由 projectSession 独立承载
   * （AGENTS.md 注入判据），两信号不再互为代名词。
   */
  sandboxEnabled: Boolean = false,
  /**
   * 显式沙箱根（2026-09-05 21:05 作者裁定——worktree 节点继承项目沙箱）：
   * NodeEngine spawn 点传入项目工作区根，worktree 节点沙箱 root 收敛为工作区
   * 根而非 worktree 目录自身（主仓 .git/worktrees/<name>/ 元数据可直写，git
   * commit 走通）；节点 cwd / projectRoot 工具语义不动，只放宽写边界。None =
   * 沿用 projectRoot 推导（分发器/未接线节点旧行为逐字节不变）。推导权威在
   * SandboxPolicy.sessionRoot。
   */
  sandboxRoot: Option[String] = None,
  /**
   * **会话初始 cwd 信号**（B5 缺口② · 作者 2026-09-17 M-1 裁定「会话启动即 `cd`
   * 座椅」，选项①）：Some = 该会话的 shell 初 cwd（**座椅路径**，worktree 节点的
   * `<ws>/.nebflow/worktrees/<name>`）；None = 无座椅信号 ⇒ 旧行为（初 cwd = 沙箱根
   * = 工作区根，逐字节不变）。
   *
   * 与 [[sandboxRoot]] **分道**：sandboxRoot 是**围栏面**（2026-09-05 21:05 作者裁定
   * 的写边界——本批不动它，root 仍 = 工作区根，不推翻该裁定）；本字段只是**会话 cwd
   * 面**：围栏不窄、cwd 落到座椅，提示词「worktree 节点 = worktree 根」由此从承诺变成
   * 机制。非 worktree 节点两值同源（projectRoot = 工作区根 = 沙箱根）⇒ 行为无差别。
   *
   * 🔴 **fail-closed**（作者明示接受该新失败面）：座椅目录缺失 ⇒ 该会话的 Bash
   * **显式失败**（`ShellSession.resolveCwdOrFail` → `InvalidCwdError`），**禁**静默回落
   * 工作区根——后者正是缺口② 的根因形态（静默降级）。置位点 = NodeEngine 两个 spawn
   * 点（普通节点 / loop 会话）；分发器 / WS 根会话 / team / flow / Delegate 轨不传
   * （None ⇒ 零变化）。消费单点 = `BashTool` 的 `initialDir` 推导。
   */
  sessionCwd: Option[String] = None,
  /**
   * 项目会话信号（沙箱拆围栏批 S1/R8 解耦，2026-09-10）：true = 本会话属项目
   * 作用域（project 节点 / 分发器）——项目级契约文件 AGENTS.md 的注入判据
   * （ContextRefresher.agentsMdEnabledFor）。
   *
   * 与 sandboxEnabled 分道的原因：拆围栏批将退役 sandboxEnabled 这一「围栏总闸」
   * 语义，而 AGENTS.md 注入必须继续生效——继续复用旧信号就会造成「拆围栏顺带
   * 关掉项目契约注入」的静默回归（design §0 结论 4 / R8 的 h2 禁止项）。判据按
   * 会话形态（spawn 置位）而非围栏开关置位，两者生命周期就此解耦。
   *
   * 置位点（call site 口径 3 处）：NodeEngine 节点 spawn ×2（普通节点 / loop
   * worker·verify）+ ProjectActor 分发器 spawn ×1；WebSocketRoutes 的 WS 根会话
   * （含 Nebula）保持 false——AGENTS.md 接收面 = project 分发器 + 节点会话不变。
   * 详见 design §4.4 S1 / §7 交下游纪律 1。
   */
  projectSession: Boolean = false,
  /** Last experience extraction timestamp. */
  lastExperienceAt: Option[Long] = None,
  /**
   * True when this agent is a SubTask worker (spawned via SubTaskTool).
   * Workers are leaf agents: no Mail/SubTask/Delegate tools, no team
   * context injection (categoryPrefix/managerPrefix/memoryBlock/teamCatalog/
   * flowCatalog stripped), and a fixed Worker Block appended to the prompt.
   */
  isSubTaskWorker: Boolean = false,
  /**
   * #406: true when this agent is a one-shot FlowExecute node (spawned by
   * FlowDagExecutor.executeAgent with isFlowNode=true). Flow nodes are leaf
   * agents: FlowExecute/FlowTrigger/SubTask/Delegate are stripped to prevent
   * recursive flow-in-flow explosions — the same leaf rule SubTask workers
   * get. Differs from isSubTaskWorker in that Mail stays available for
   * team-category node agents (flow nodes may Mail the caller's team).
   */
  isFlowNode: Boolean = false,
  /**
   * 轨道二 #5：本节点的 userFacing 白名单声明（FlowNode.userFacing 原样透传）。
   * spawn 时从 DAG 定义读入，与 dedicatedAgents 开关解耦——开关在消费点
   * （buildAllowedToolSet 剥离 / PromptSections 条款变体）每 turn 热读，改动
   * nebflow.json 后下个 turn 生效，无需重启或 respawn。
   */
  userFacingNode: Boolean = false,
  /**
   * Project 任务板身份（TaskBoard 批 2 接线，规格 §1d）：flowNodeId = project 节点
   * 会话的 NodeDef.id（NodeEngine spawn 点置位）；isDispatcher = 分发器会话标记
   * （ProjectActor spawn 点置位）。经 AgentCore 透传进 ToolContext——TaskBoard
   * 工具的引擎侧身份判定来源（不信客户端参数）。两字段皆空 = 非项目会话
   * （Nebula/team/flow 双轨/REST）→ 工具未挂载 + 工具内拒绝，双保险不可达。
   */
  flowNodeId: Option[String] = None,
  isDispatcher: Boolean = false,
  /**
   * 节点角色（nrloop 一期 2026-09-12；设计 §3.2 + B1 透传链）：本节点会话所属
   * `NodeDef.role`（`task` | `verifier`，见 `NodeRoles`）。NodeEngine 节点 spawn
   * 点从 `NodeDef.role` 置位 → 经 AgentCore 透传进 `ToolContext.flowNodeRole`
   * ——`node_report` 值域按角色分化的判据来源（错误码
   * `NODE_REPORT_CATEGORY_ROLE`）；同时驱动 `ProtocolFootnote` 的角色分支注入。
   * None = 非项目节点会话（分发器/Nebula/team/flow 双轨/REST）或旧会话——判据侧
   * 回落 `NodeRoles.Task`（缺省语义，与 `NodeDef` 解码缺省同口径）。
   */
  flowNodeRole: Option[String] = None,
  /**
   * 所属项目名（TaskBoard 批 2 身份链随路接通）：分发器/节点 spawn 注入 →
   * AgentCore 透传 ToolContext.projectName——该字段此前存在但生产代码从未赋值
   * （证据 §6-2），本批接通后 Node 系工具的 project 缺省解析（NodeTools.
   * resolveProject fallback 链）在节点会话内也生效。None = 非项目会话。
   */
  projectName: Option[String] = None,
  /**
   * 节点人类可读名（D6 批 F1 G9 路径 a：spawn 置位随路注入，spec §3.3）——
   * NodeEngine 置 node.name（loop worker/verify 同属该 loop 节点名）；
   * AskUser payload 的 nodeName 字段来源（badge「project · nodeName」归因）。
   * None = 非项目节点会话（Nebula/分发器/REPL——分发器由 isDispatcher 标注）。
   */
  flowNodeName: Option[String] = None,
  /**
   * 链级抽象 P2（20260910 process-doc-chain-attribution spec §9.2 项 2）：本节点
   * 所属链 id（NodeEngine 节点 spawn 时经 FlowMapStore.chainIdOf 判据单点取
   * spawn 时刻快照注入；分量成员数 ≥2 才带值）。经 AgentCore 透传
   * ToolContext.flowChainId——节点在过程文档**文件名尾段**写链归属 `__<chainId>`
   * 的值来源（正文零元数据头；2026-09-11 作者裁定 R-3）。
   * 分发器/非项目会话/孤立单节点分量 = None（分发器口径显式化见 ProjectActor
   * spawn 点）。快照语义见 ToolContext.flowChainId 注释。
   */
  flowChainId: Option[String] = None,
  /**
   * D11 交互豁免（freeze-schedule spec v1.1）：用户在场等待的交互会话
   * 不参与冻结——冻结它们省下的 token 远低于浪费的用户等待时间。
   * 其余 spawn 点默认 false 零改动。ask 轮的豁免走
   * gate 内的 askMode.isDefined 检查，不经此字段。
   */
  freezeExempt: Boolean = false,
  /**
   * **会话级压缩阈值比例覆盖**（ctxthresh 批，2026-09-15 方案 A；作者卡答逐字
   * 「按方案A实施」+「上限90%，下限不得小于当前上下文用量而且大于15%」）。
   *
   * 语义 = 「有会话覆盖用覆盖，无覆盖走现值函数」——判定/上报一律经
   * [[CompactThresholdOverride.effectiveThreshold]] / `effectiveRatio`，
   * `None`（默认）分支逐字等于 `CompactThreshold.threshold(contextWindow)`
   * （口径②承重钉，`CompactThreshold.scala` 本批零改动）。
   *
   * 🔴 **作用域 = 仅 root 会话**（口径③）：本字段的**唯一注入点**是
   * `WebSocketRoutes.doSpawnRootAgent`（depth=0 全仓唯一 spawn 点）。非 root
   * spawn（`NodeRunner` / `EphemeralAgentRunner` / `MemoryTrack` / `MailTool` /
   * `FlowTreeActor`）一律不传 ⇒ 恒为 `None` ⇒ 走现值函数。
   * 🔴 **禁**把本字段放进 `SpawnParams` / `ToolContext`（那会让一次设定传染给
   * 全部子 agent / 节点，直接违反口径③）——静态泄漏判据见
   * `.nebflow/tools/20260915_ctxthresh_leak-check.sh`。
   */
  compactThresholdRatio: Option[Double] = None
)

case class InteractionState(
  pendingAskUser: Option[cats.effect.Deferred[IO, List[String]]] = None,
  pendingPermission: Option[cats.effect.Deferred[IO, Boolean]] = None,
  pendingAskUserReplyTo: Option[ActorRef[List[String]]] = None
)

/** Tracks what was last dispatched (LLM call or tool execution) for Retry. */
case class LastDispatch(
  isToolExecution: Boolean,
  llmResult: Option[ConsumeResult] = None
)

case class ExecutionContext(
  messages: List[Message] = Nil,
  status: AgentStatus = AgentStatus.Idle,
  turnIdx: Int = 0,
  currentTurnId: Long = 0L,
  interaction: Option[InteractionState] = None,
  pendingEvents: List[AgentCommand.ExternalEvent] = Nil,
  /**
   * Sub-agent result barrier (2026-08-18, worker blocking semantics): number of
   * parallel sub-agent (Delegate/SubTask) results still awaited before their
   * batch is delivered to the agent. Incremented at ToolsComplete by the count
   * of Delegate/SubTask calls made that turn; decremented on each ExternalEvent
   * with source "subtask"/"delegate" (completed OR failed — failures still
   * count as results, so a crashed worker cannot stall the barrier forever).
   *
   * While > 0, subtask/delegate result events are HELD in [[pendingEvents]] —
   * the agent is NOT interrupted one result at a time. When the counter
   * reaches 0, ALL held results are injected together in a single turn.
   *
   * In-memory only (not persisted across crash recovery, same as
   * pendingEvents): a crash mid-batch degrades to per-result delivery.
   */
  outstandingSubagentResults: Int = 0,
  /**
   * #25 (nested delegation dead-letter): completion-notification debt. When a
   * turn ends while outstandingSubagentResults > 0, the replyTo that would
   * have received AgentEvent.Completed is PARKED here instead of being sent —
   * the actor lifecycle must not treat "turn ended" as "task completed" while
   * spawned sub-agents are still in flight (the supervisor adapter would stop
   * this actor and the grandchildren's results would dead-letter). When a
   * later turn ends with the barrier at 0, every parked ref receives the
   * Completed event — carrying the FINAL synthesized text — and the debt
   * clears. In-memory only (same lifecycle as pendingEvents): a crash
   * mid-flight degrades to the pre-#25 notification-less state.
   */
  owedCompletion: List[ActorRef[AgentEvent]] = Nil,
  pendingImmediateInputs: List[AgentCommand.ImmediateInput] = Nil,
  emptyResponseRetries: Int = 0,
  lastDispatch: Option[LastDispatch] = None,
  delegateCount: Int = 0,
  lastMaintenanceDelegateCount: Int = 0,
  // Snapshot of messages.size at turn start. Used to scope the expectsMail
  // check so only Mail calls within the current turn count. Persisted to
  // TurnStateStore for crash recovery — restored via ResumeTurn.
  turnStartMessageCount: Int = 0,
  // Per-turn flag: true once the agent called the Mail tool at any point during
  // the current turn (set in ToolsComplete, reset when a new turn starts). This
  // is an event-driven flag, NOT a message-index scan, so it survives
  // compaction (which rewrites/shortens `messages` and would invalidate any
  // index-based check). It captures the user's intent: "from user input → to
  // LLM finish/badge, did any tool call in that whole span include Mail?"
  mailUsedThisTurn: Boolean = false,
  // How many "you must call Mail" system-reminders have been injected this turn
  // without the agent subsequently calling Mail. Bounded by MaxMailReminders so
  // a flow agent that keeps producing text-without-Mail cannot loop forever —
  // after the cap it is allowed to finishTurn (emit agentDone, release busy).
  mailReminders: Int = 0,
  // Consecutive transient LLM failures auto-retried this turn (bounded by
  // AgentActor.LlmFailRetryMax). Reset on any successful LLM completion.
  llmFailRetries: Int = 0,
  // Per-turn LLM RETRY counter (2026-08-18 token incident, plan C): counts
  // ONLY failed-retry re-dispatches (incremented in the AgentActor LlmFailed
  // retry branch). Normal tool-loop calls do NOT count — tool-intensive agents
  // (read → edit → compile → ...) never trip it. Bounded by
  // Fallback.MaxTurnLlmCalls — exceeding it raises TurnBudgetExceeded
  // (Permanent) so the turn fails fast instead of amplifying token spend via
  // full-context re-dispatches. Monotonic within the turn; reset when the
  // turn ends (ExecutionContext.idle rebuilds the counter to 0).
  llmCallsThisTurn: Int = 0,
  // Pending queue mails (delivery=queue): counter only — actual items live on
  // disk (MailQueueStore). Drained one per turn at turn end, after immediate
  // inputs. Incremented by MailQueued in processing state, reset when drained.
  pendingMailQueueCount: Int = 0,
  // User inputs (UserInput/SkillActivate/AskQuestion) that arrived while the
  // agent was busy (processing). Drained one per turn boundary — the head is
  // re-sent to self so the idle handler processes it with full metadata.
  // Replaces the dead-end `pending` function parameter on the processing
  // behavior (messages entered but were never drained).
  pendingUserInputs: List[AgentCommand] = Nil,
  /**
   * P0 阶段 3（2026-08-18，设计 §4.4）：最近一次 turn 活动时间戳——状态层
   * 字段（registry 层权威源见 AgentRecord.lastActivityMs，TaskStuckWatcher
   * 读它；本字段供 AgentState 使用与二期前端展示）。touch 点见 touchActivity。
   */
  lastActivityMs: Long = 0L,
  /**
   * v2 冻结式错误恢复（§3.3）：同 reason 连续 ErrorFrozen 进入次数（跨恢复
   * 累计——resumed 续跑失败仍算连续，直至 turn 成功完成回 idle 清零）。
   * Schedule 时间表冻结不参与计数。≥3 触发升级链（不再直接 fatal）。
   * 内存态：ExecutionContext.idle 重建即清零（续跑成功=问题缓解）。
   */
  errorFreezeCount: Int = 0,
  /** v2：上次错误冻结的 reason——判定「同 reason 连续」；reason 变化重置计数。 */
  lastErrorFreezeReason: Option[FreezeReason] = None,
  /** v2 升级链状态（§5.2，P0 内存态）：进入升级链后挂起，父决策/超时升级时更新。 */
  escalation: Option[EscalationInfo] = None
)

/** P0 阶段 3：touch turn 活动戳（幂等——仅更新时间戳，不改变其他状态）。 */
extension (e: ExecutionContext)

  def touchActivity(now: Long = System.currentTimeMillis()): ExecutionContext =
    e.copy(lastActivityMs = now)

object ExecutionContext:

  def idle(messages: List[Message], turnIdx: Int = 0, currentTurnId: Long = 0L): ExecutionContext =
    ExecutionContext(
      messages = messages,
      status = AgentStatus.Idle,
      turnIdx = turnIdx,
      currentTurnId = currentTurnId,
      interaction = None,
      pendingEvents = Nil,
      pendingImmediateInputs = Nil,
      emptyResponseRetries = 0,
      turnStartMessageCount = 0,
      mailUsedThisTurn = false,
      mailReminders = 0
    )
end ExecutionContext

case class CompactionState(
  pendingJob: Option[CompactionJob] = None,
  compactionFailures: Int = 0,
  lastCompactionFailureAt: Long = 0L,
  latestUsage: Option[TokenUsage] = None,
  lastModel: Option[String] = None
)

case class AgentSessionInfo(
  address: String,
  agentName: String,
  taskDescription: String,
  status: String = "running",
  createdAt: Long = System.currentTimeMillis()
)

/**
 * Snapshot of the dynamic values injected into systemStable at its last build
 * (cache-optimization v2). systemStable is rebuilt only at lifecycle nodes
 * (new session / compaction complete / restart); mid-session changes to these
 * values are reported via change reminders instead of invalidating the cache.
 */
case class SystemStableSnapshot(
  devices: String = "",
  sessions: String = "",
  language: Option[String] = None,
  envInfo: String = "",
  /**
   * Mounted-project list body at systemStable build time (cache v2 change
   * detection). Only populated for the root Nebula agent; "" for others.
   */
  mountedProjects: String = "",
  /**
   * Rendered `# Plugin Catalog` section the session was last TOLD about
   * (plugins-live 批 2026-09-12 change detection). Dispatcher sessions only:
   * ProjectActor injects the catalog into the session's first message at spawn,
   * so this field starts as that snapshot and is advanced by AgentCore whenever a
   * plugin-surface reminder is actually emitted (trust/enabled change) — the
   * reminder is the only refresh path for an open session. "" = no plugin face
   * in context (non-dispatcher sessions).
   */
  pluginCatalog: String = ""
)

case class AgentState(
  session: SessionContext,
  execution: ExecutionContext,
  compaction: CompactionState,
  agentSessions: List[AgentSessionInfo],
  /**
   * Last built systemStable string — reused on non-lifecycle turns.
   *
   * Restart invariant (2026-08-15 FlowTrigger outage audit): AgentState is
   * NEVER persisted — a JVM restart produces a fresh actor whose cache is
   * None, so the first restored turn always takes the isLifecycleRebuild
   * path and rebuilds systemStable from the CURRENT AgentDef (tools, flows,
   * skills sections). That is what makes "restart" a lifecycle node per the
   * cache-optimization design. If AgentState ever becomes persisted, the
   * restore path MUST clear cachedSystemStable (invalidateSystemStableCache)
   * or the restored session keeps advertising pre-restart tool sections.
   */
  cachedSystemStable: Option[String],
  /** Dynamic values at the time systemStable was last built (change detection). */
  stableSnapshot: Option[SystemStableSnapshot],
  /**
   * Block 3 循环检测器计数器（supervision trio §D1）：顶层——S3 跨 turn 保留
   * （turn 边界只清 S1 与 R 连续重复计数，见 LoopGuard.evaluate 的 turnKey 判定）。
   */
  loopCounters: nebflow.core.processor.LoopGuard.Counters,
  /**
   * Block 3：逻辑 turn 纪元（每次真实 turn 开始 +1——UserInput/ExternalEvent
   * 唤醒/Mail 激活/冻结唤醒等 dispatch 起点；ToolsComplete 续轮/retry/压缩
   * 续跑不递增）。LoopGuard 的 turnKey 来源——currentTurnId 是每次 LLM
   * dispatch 都 +1 的序号（wiring 实证），不能当 turn 身份用。
   * （默认值只在 object AgentState.apply 提供——case class 字段带默认会与
   * 自定义 apply 的全默认参数形成重载冲突。）
   */
  loopTurnKey: Long
)

object AgentState:

  def apply(
    messages: List[Message] = Nil,
    status: AgentStatus = AgentStatus.Idle,
    depth: Int = 0,
    sessionId: Option[String] = None,
    sessionName: Option[String] = None,
    pendingCompaction: Option[CompactionJob] = None,
    compactionFailures: Int = 0,
    latestUsage: Option[TokenUsage] = None,
    pendingAskUser: Option[cats.effect.Deferred[IO, List[String]]] = None,
    pendingPermission: Option[cats.effect.Deferred[IO, Boolean]] = None,
    turnIdx: Int = 0,
    wsSend: Json => IO[Unit] = _ => IO.unit,
    readTracker: Option[nebflow.core.tools.ReadTracker] = None,
    fileHistory: Option[nebflow.core.tools.FileHistory] = None,
    recentMessageIds: List[String] = Nil,
    contextWindow: Int = nebflow.shared.Defaults.ContextWindow,
    projectRoot: Option[String] = None,
    rulesMd: Option[String] = None,
    agentsMd: Option[String] = None,
    folderId: Option[String] = None,
    safetyMode: String = "confirm-edits",
    gitBranch: Option[String] = None,
    expectsMail: Boolean = false,
    rootSessionId: String = "",
    isSubTaskWorker: Boolean = false,
    freezeExempt: Boolean = false,
    isFlowNode: Boolean = false,
    userFacingNode: Boolean = false,
    flowNodeId: Option[String] = None,
    isDispatcher: Boolean = false,
    /** 节点角色（nrloop 一期，详见 SessionContext.flowNodeRole）。 */
    flowNodeRole: Option[String] = None,
    projectName: Option[String] = None,
    flowNodeName: Option[String] = None,
    /** 链级抽象 P2（§9.2 项 2）：节点所属链 id 快照（None = 无链/非项目会话）。 */
    flowChainId: Option[String] = None,
    sandboxEnabled: Boolean = false,
    sandboxRoot: Option[String] = None,
    /**
     * **会话初始 cwd**（B5 缺口② · 作者 2026-09-17 M-1 裁定，选项①）：spawn 侧
     * 置位，见 SessionContext.sessionCwd。默认 None = 旧行为（初 cwd = 沙箱根）。
     */
    sessionCwd: Option[String] = None,
    /**
     * 项目会话信号（沙箱拆围栏批 S1/R8 解耦）：spawn 侧置位，见
     * SessionContext.projectSession。默认 false = 非项目会话（WS 根会话 /
     * team / flow / Delegate / SubTask 双轨面）语义与旧行为逐字节不变。
     */
    projectSession: Boolean = false,
    /**
     * ctxthresh 批：会话级压缩阈值比例覆盖（详见 SessionContext.compactThresholdRatio）。
     * 默认 None = 无覆盖 ⇒ 走现值函数（除 root spawn 外**所有**构造点零改动）。
     */
    compactThresholdRatio: Option[Double] = None,
    loopTurnKey: Long = 0L
  ): AgentState =
    val interaction = (pendingAskUser, pendingPermission) match
      case (None, None) => None
      case _ => Some(InteractionState(pendingAskUser, pendingPermission))
    new AgentState(
      SessionContext(
        sessionId = sessionId,
        sessionName = sessionName,
        recentMessageIds = recentMessageIds,
        wsSend = wsSend,
        depth = depth,
        readTracker = readTracker,
        fileHistory = fileHistory,
        contextWindow = contextWindow,
        folderId = folderId,
        projectRoot = projectRoot,
        rulesMd = rulesMd,
        agentsMd = agentsMd,
        gitBranch = gitBranch,
        safetyMode = safetyMode,
        expectsMail = expectsMail,
        rootSessionId = rootSessionId,
        isSubTaskWorker = isSubTaskWorker,
        freezeExempt = freezeExempt,
        isFlowNode = isFlowNode,
        userFacingNode = userFacingNode,
        flowNodeId = flowNodeId,
        isDispatcher = isDispatcher,
        flowNodeRole = flowNodeRole,
        projectName = projectName,
        flowNodeName = flowNodeName,
        flowChainId = flowChainId,
        sandboxEnabled = sandboxEnabled,
        sandboxRoot = sandboxRoot,
        sessionCwd = sessionCwd,
        projectSession = projectSession,
        compactThresholdRatio = compactThresholdRatio
      ),
      ExecutionContext(messages, status, turnIdx, 0L, interaction),
      CompactionState(pendingCompaction, compactionFailures, 0L, latestUsage),
      Nil,
      None,
      None,
      nebflow.core.processor.LoopGuard.Counters.Empty,
      loopTurnKey
    )
  end apply
end AgentState

extension (s: AgentState)

  def withLoopCounters(c: nebflow.core.processor.LoopGuard.Counters): AgentState =
    s.copy(loopCounters = c)

  /**
   * Block 3：turn 纪元 +1——真实 turn 开始的 dispatch 点调用
   * （UserInput/ExternalEvent 唤醒/Mail 激活/冻结唤醒/队列 drain）；
   * ToolsComplete 续轮、retry、save/compact 续跑不递增。
   */
  def withNextLoopTurn: AgentState =
    s.copy(loopTurnKey = s.loopTurnKey + 1)

/**
 * 会话级压缩阈值比例覆盖的**值域与判定策略**（ctxthresh 批，2026-09-15 方案 A）。
 *
 * 逐字口径（作者卡答）：**上限 90%，下限不得小于当前上下文用量而且大于 15%**。
 *  - 值语义 = **窗口比例** `r`（不是绝对 token）——生效门限 = `(contextWindow * r).toInt`；
 *  - 静态值域 = `15% < r ≤ 90%`（两端含否逐字如左：15% 本身**禁选**）；
 *  - 动态下限 = `max(当前上下文用量比例, 15%+ε)`——低于当前用量即「设完就立刻
 *    触发压缩」，故 UI 侧**禁选 / 钳回**。
 *
 * 本对象是值域判据的**单一真值源**：WS 写入侧（`WebSocketRoutes.setCompactThreshold`）
 * 与前端面板（`js/ctxthresh.js`）同用一套常量（前端为同一批常量的 JS 镜像，
 * 见 `js/ctxthresh.js` 头部注释的同步要求）。
 *
 * **不做**：绝对 token 上限（设计 §9-O1 曾建议 `capAbs=512000`）——作者卡答只给
 * 了比例上限 90%，本条按逐字口径实现，`capAbs` 不启用（报告已注明 O1 处置）。
 *
 * **默认分支零改动**：无覆盖（`None`）时 [[CompactThreshold.threshold]] 逐字照走，
 * 本对象不参与计算（口径②/§8.1 承重钉）。
 */
object CompactThresholdOverride:
  /** 静态下限（**开区间**：`r` 必须严格大于本值）。 */
  val MinRatio: Double = 0.15

  /** 静态上限（**闭区间**）。 */
  val MaxRatio: Double = 0.90

  /** UI 步进（百分比 1 个点）。 */
  val StepRatio: Double = 0.01

  /** 浮点比较容差（避免 `0.9` 这类十进制字面量的二进制尾差把合法值判非法）。 */
  private val Eps = 1e-9

  /** 静态值域判定：`15% < r ≤ 90%` 且为有限数。 */
  def isValid(r: Double): Boolean =
    !r.isNaN && !r.isInfinite && r > MinRatio + Eps && r <= MaxRatio + Eps

  /**
   * 动态下限：`max(当前上下文用量比例, 静态下限之上最小可选值)`。
   *
   * `usageRatio` = 当前会话已用 token / 窗口（未知传 0）。返回值是 `r` 的**下界**
   * （含）；UI 侧把它作为滑杆 min 并对越界输入钳回本值。
   */
  def minRatioFor(usageRatio: Double): Double =
    val floor = MinRatio + StepRatio // 15% + 1 个点 = 16%（「大于 15%」的最小可选值）
    if usageRatio.isNaN || usageRatio < floor then floor else usageRatio

  /** 把任意输入钳到合法区间（钳回 = 报告口径「钳回」，不是静默丢弃）。 */
  def clamp(r: Double, usageRatio: Double): Double =
    val lo = minRatioFor(usageRatio)
    if r.isNaN then lo
    else if r < lo then lo
    else if r > MaxRatio then MaxRatio
    else r

  /**
   * **判定点唯一算法**：有覆盖用覆盖（比例 × 窗口），无覆盖走现值函数。
   *
   * 🔴 默认分支逐字 = `CompactThreshold.threshold(window)`（口径②）；
   * 🔴 本函数**不**回写任何全局值，也**不**改 `CompactThreshold.scala`（该文件
   * 本批零改动，`git diff` 反证）。
   */
  def effectiveThreshold(contextWindow: Int, overrideRatio: Option[Double]): Int =
    overrideRatio match
      case Some(r) => (contextWindow * r).toInt
      case None => CompactThreshold.threshold(contextWindow)

  /** 生效比例（UI 回显 / wire 上报用）：有覆盖 = 覆盖值本身，无覆盖 = 现值比例。 */
  def effectiveRatio(contextWindow: Int, overrideRatio: Option[Double]): Double =
    overrideRatio.getOrElse(CompactThreshold.thresholdRatio(contextWindow))

end CompactThresholdOverride

extension (s: AgentState)
  def messages: List[Message] = s.execution.messages
  def status: AgentStatus = s.execution.status
  def sessionId: Option[String] = s.session.sessionId
  def sessionName: Option[String] = s.session.sessionName
  def wsSend: Json => IO[Unit] = s.session.wsSend
  def depth: Int = s.session.depth
  def turnIdx: Int = s.execution.turnIdx
  def currentTurnId: Long = s.execution.currentTurnId
  def recentMessageIds: List[String] = s.session.recentMessageIds
  def pendingCompaction: Option[CompactionJob] = s.compaction.pendingJob
  def compactionFailures: Int = s.compaction.compactionFailures
  def lastCompactionFailureAt: Long = s.compaction.lastCompactionFailureAt
  def latestUsage: Option[TokenUsage] = s.compaction.latestUsage
  def lastModel: Option[String] = s.compaction.lastModel
  def emptyResponseRetries: Int = s.execution.emptyResponseRetries
  def mailUsedThisTurn: Boolean = s.execution.mailUsedThisTurn
  def mailReminders: Int = s.execution.mailReminders
  def pendingAskUser: Option[cats.effect.Deferred[IO, List[String]]] = s.execution.interaction.flatMap(_.pendingAskUser)

  def pendingPermission: Option[cats.effect.Deferred[IO, Boolean]] =
    s.execution.interaction.flatMap(_.pendingPermission)
  def readTracker: Option[nebflow.core.tools.ReadTracker] = s.session.readTracker
  def fileHistory: Option[nebflow.core.tools.FileHistory] = s.session.fileHistory
  def contextWindow: Int = s.session.contextWindow
  def askMode: Option[String] = s.session.askMode
  def language: Option[String] = s.session.language
  def projectRoot: Option[String] = s.session.projectRoot
  def sandboxEnabled: Boolean = s.session.sandboxEnabled
  def sandboxRoot: Option[String] = s.session.sandboxRoot

  /**
   * **会话初始 cwd**（B5 缺口② · M-1 裁定）：见 SessionContext.sessionCwd。
   * 消费单点 = AgentCore → ToolContext.sessionCwd → BashTool.initialDir。
   */
  def sessionCwd: Option[String] = s.session.sessionCwd

  /**
   * 项目会话信号（沙箱拆围栏批 S1/R8 解耦）：AGENTS.md 注入判据的来源，见
   * SessionContext.projectSession。
   */
  def projectSession: Boolean = s.session.projectSession
  def rulesMd: Option[String] = s.session.rulesMd
  def agentsMd: Option[String] = s.session.agentsMd
  def folderId: Option[String] = s.session.folderId
  def gitBranch: Option[String] = s.session.gitBranch
  def safetyMode: String = s.session.safetyMode
  def rootSessionId: String = s.session.rootSessionId
  def expectsMail: Boolean = s.session.expectsMail
  def isSubTaskWorker: Boolean = s.session.isSubTaskWorker
  def isFlowNode: Boolean = s.session.isFlowNode
  def userFacingNode: Boolean = s.session.userFacingNode
  def flowNodeId: Option[String] = s.session.flowNodeId
  def isDispatcher: Boolean = s.session.isDispatcher
  def projectName: Option[String] = s.session.projectName
  def flowNodeName: Option[String] = s.session.flowNodeName

  /** 链级抽象 P2（§9.2 项 2）：本节点所属链 id 快照（None = 无链/非项目会话）。 */
  def flowChainId: Option[String] = s.session.flowChainId

  def withSession(session: SessionContext): AgentState = s.copy(session = session)
  def withExecution(execution: ExecutionContext): AgentState = s.copy(execution = execution)
  def withCompaction(compaction: CompactionState): AgentState = s.copy(compaction = compaction)
  def withAgentSessions(sessions: List[AgentSessionInfo]): AgentState = s.copy(agentSessions = sessions)
  def withMessages(msgs: List[Message]): AgentState = s.copy(execution = s.execution.copy(messages = msgs))
  def withStatus(st: AgentStatus): AgentState = s.copy(execution = s.execution.copy(status = st))
  def withTurnIdx(idx: Int): AgentState = s.copy(execution = s.execution.copy(turnIdx = idx))

  def withTurnStart(count: Int): AgentState =
    s.copy(execution = s.execution.copy(turnStartMessageCount = count))
  def withCurrentTurnId(id: Long): AgentState = s.copy(execution = s.execution.copy(currentTurnId = id))

  def withMailUsedThisTurn(b: Boolean): AgentState =
    s.copy(execution = s.execution.copy(mailUsedThisTurn = b))

  def withMailReminders(n: Int): AgentState =
    s.copy(execution = s.execution.copy(mailReminders = n))

  def withInteraction(interaction: Option[InteractionState]): AgentState =
    s.copy(execution = s.execution.copy(interaction = interaction))

  def withLastDispatch(d: Option[LastDispatch]): AgentState =
    s.copy(execution = s.execution.copy(lastDispatch = d))

  def lastDispatch: Option[LastDispatch] = s.execution.lastDispatch

  def withPendingAskUser(d: Option[cats.effect.Deferred[IO, List[String]]]): AgentState =
    s.copy(execution =
      s.execution.copy(interaction =
        Some(s.execution.interaction.getOrElse(InteractionState()).copy(pendingAskUser = d))
      )
    )

  def withPendingPermission(d: Option[cats.effect.Deferred[IO, Boolean]]): AgentState =
    s.copy(execution =
      s.execution.copy(interaction =
        Some(s.execution.interaction.getOrElse(InteractionState()).copy(pendingPermission = d))
      )
    )
  def withRecentMessageIds(ids: List[String]): AgentState = s.copy(session = s.session.copy(recentMessageIds = ids))
  def withContextWindow(window: Int): AgentState = s.copy(session = s.session.copy(contextWindow = window))

  /** ctxthresh 批：本会话的阈值比例覆盖（`None` = 无覆盖 ⇒ 走现值函数）。 */
  def compactThresholdRatioOverride: Option[Double] = s.session.compactThresholdRatio

  /**
   * ctxthresh 批：热更入口（[[AgentCommand.SetCompactThresholdRatio]] 的三个
   * behavior 分支都用它；`None` = 清除覆盖）。
   */
  def withCompactThresholdRatio(ratio: Option[Double]): AgentState =
    s.copy(session = s.session.copy(compactThresholdRatio = ratio))

  /**
   * ctxthresh 批：**生效门限**（绝对 token）——判定点唯一读数。
   *
   * 有覆盖 ⇒ `(contextWindow × r).toInt`；无覆盖 ⇒ `CompactThreshold.threshold(window)`
   * **逐字**（口径②承重钉：`CompactThreshold.scala` 零改动，diff 反证）。
   */
  def compactThresholdTokens: Int =
    CompactThresholdOverride.effectiveThreshold(s.session.contextWindow, s.session.compactThresholdRatio)

  /**
   * ctxthresh 批：生效比例（wire 上报 / UI 回显：`Done` / `UsageUpdate` /
   * `CompactStart` 的 `compactThreshold` 字段统一取它，保证「判定面」与
   * 「上报面」同源——否则面板显示的阈值与真正触发压缩的门限会脱节）。
   */
  def effectiveCompactThresholdRatio: Double =
    CompactThresholdOverride.effectiveRatio(s.session.contextWindow, s.session.compactThresholdRatio)
  def withAskMode(mode: Option[String]): AgentState = s.copy(session = s.session.copy(askMode = mode))
  def withLanguage(lang: Option[String]): AgentState = s.copy(session = s.session.copy(language = lang))
  def mailTurnCount: Int = s.session.mailTurnCount
  def withMailTurnCount(count: Int): AgentState = s.copy(session = s.session.copy(mailTurnCount = count))
  def lastExperienceAt: Option[Long] = s.session.lastExperienceAt
  def withLastExperienceAt(ts: Long): AgentState = s.copy(session = s.session.copy(lastExperienceAt = Some(ts)))

  def withPendingCompaction(job: Option[CompactionJob]): AgentState =
    s.copy(compaction = s.compaction.copy(pendingJob = job))

  def withCompactionFailures(failures: Int): AgentState =
    s.copy(compaction = s.compaction.copy(compactionFailures = failures))

  def withLastCompactionFailureAt(ts: Long): AgentState =
    s.copy(compaction = s.compaction.copy(lastCompactionFailureAt = ts))

  def withEmptyResponseRetries(count: Int): AgentState =
    s.copy(execution = s.execution.copy(emptyResponseRetries = count))
  def llmFailRetries: Int = s.execution.llmFailRetries

  def withLlmFailRetries(count: Int): AgentState =
    s.copy(execution = s.execution.copy(llmFailRetries = count))

  def llmCallsThisTurn: Int = s.execution.llmCallsThisTurn

  def withLlmCallsThisTurn(count: Int): AgentState =
    s.copy(execution = s.execution.copy(llmCallsThisTurn = count))

  def errorFreezeCount: Int = s.execution.errorFreezeCount

  def lastErrorFreezeReason: Option[FreezeReason] = s.execution.lastErrorFreezeReason

  def escalation: Option[EscalationInfo] = s.execution.escalation

  /** v2 冻结式错误恢复：计数 + reason 记录（内存态，turn 完成回 idle 自动清零）。 */
  def withErrorFreezeCount(count: Int, reason: FreezeReason): AgentState =
    s.copy(execution = s.execution.copy(errorFreezeCount = count, lastErrorFreezeReason = Some(reason)))

  def withEscalation(esc: Option[EscalationInfo]): AgentState =
    s.copy(execution = s.execution.copy(escalation = esc))
  def withGitBranch(branch: Option[String]): AgentState = s.copy(session = s.session.copy(gitBranch = branch))

  // 2026-09-13（permshield S1）：`withSafetyMode` 随 `AgentCommand.SetSafetyMode`
  // 一并删除（唯一调用点）。`SessionContext.safetyMode` 字段保留 = 历史序列化兼容
  // （存量会话文件仍带该键）；它**不是权威档位**，判定/卡帧一律读
  // `SharedResources.effectiveSafetyMode`。

  // --- Cache v2: systemStable + dynamic snapshot (lifecycle-node updates) ---

  /** Store the rebuilt systemStable and the dynamic snapshot it was built from. */
  def withSystemStableCache(stable: String, snapshot: SystemStableSnapshot): AgentState =
    s.copy(cachedSystemStable = Some(stable), stableSnapshot = Some(snapshot))

  /** Mark the cache for rebuild (called at compaction complete / session reset). */
  def invalidateSystemStableCache: AgentState =
    s.copy(cachedSystemStable = None, stableSnapshot = None)

  /**
   * Advance ONLY the plugin-catalog baseline of the current snapshot (plugins-live
   * 批 2026-09-12): called when a plugin-surface reminder was actually emitted, so
   * the next turn compares against the value the session has just been told. The
   * other snapshot fields keep their systemStable-era values (their own delta
   * channels must not be reset). No snapshot at all ⇒ no-op — the baseline then
   * falls back to the current render inside the change detection (no reminder).
   */
  def withPluginSurfaceBaseline(catalog: String): AgentState =
    s.copy(stableSnapshot = s.stableSnapshot.map(_.copy(pluginCatalog = catalog)))

  def delegateCount: Int = s.execution.delegateCount
  def lastMaintenanceDelegateCount: Int = s.execution.lastMaintenanceDelegateCount

  def withDelegateCount(count: Int): AgentState =
    s.copy(execution = s.execution.copy(delegateCount = count))

  def withLastMaintenanceDelegateCount(count: Int): AgentState =
    s.copy(execution = s.execution.copy(lastMaintenanceDelegateCount = count))

  def outstandingSubagentResults: Int = s.execution.outstandingSubagentResults

  def withOutstandingSubagentResults(count: Int): AgentState =
    s.copy(execution = s.execution.copy(outstandingSubagentResults = count))

  /** #25: set the parked completion-notification debt. */
  def withOwedCompletion(targets: List[ActorRef[AgentEvent]]): AgentState =
    s.copy(execution = s.execution.copy(owedCompletion = targets))

  def owedCompletion: List[ActorRef[AgentEvent]] = s.execution.owedCompletion

  def withLatestUsage(usage: Option[TokenUsage]): AgentState =
    s.copy(compaction = s.compaction.copy(latestUsage = usage))
  def withLastModel(model: Option[String]): AgentState = s.copy(compaction = s.compaction.copy(lastModel = model))

  def updateContextWindowIfNeeded(reported: Option[Int]): AgentState = reported match
    case Some(cw) if cw != s.session.contextWindow => s.copy(session = s.session.copy(contextWindow = cw))
    case _ => s

  def resetToIdle(messages: List[Message], turnIdx: Int = s.execution.turnIdx): AgentState =
    s.copy(execution =
      ExecutionContext
        .idle(messages, turnIdx, s.execution.currentTurnId)
        // Sub-agent barrier: already-received results held for batch delivery are
        // still due to the agent — survive the reset (the workers keep running).
        .copy(
          pendingEvents = s.execution.pendingEvents,
          outstandingSubagentResults = s.execution.outstandingSubagentResults,
          // #25: a parked completion notification is still owed — the
          // supervisor/bridge is still waiting for the final answer.
          owedCompletion = s.execution.owedCompletion
        )
    )

  def resetForInterrupt: AgentState = s.copy(
    execution = ExecutionContext
      .idle(s.execution.messages, s.execution.turnIdx, s.execution.currentTurnId)
      // Sub-agent barrier: held results survive an interrupt — they are still due.
      .copy(
        pendingEvents = s.execution.pendingEvents,
        outstandingSubagentResults = s.execution.outstandingSubagentResults,
        // #25: parked completion debt survives an interrupt/restart —
        // the waiting requester is still owed the final answer.
        owedCompletion = s.execution.owedCompletion,
        // #13: undelivered immediate inputs (queued Mail) survive
        // interrupt/restart — they are user-originated work; resetting
        // them away silently dropped tasks on every restartAgent.
        pendingImmediateInputs = s.execution.pendingImmediateInputs
      ),
    compaction = s.compaction.copy(pendingJob = None)
  )
end extension

case class ConsumeResult(
  text: String,
  toolCalls: List[ToolCall],
  results: List[(ToolCall, ToolExecResult)],
  stopReason: Option[String],
  usage: Option[TokenUsage] = None,
  thinking: Option[String] = None,
  thinkingSignature: Option[String] = None,
  model: Option[String] = None,
  contextWindow: Option[Int] = None,
  /**
   * 当轮 LLM 请求 id（审计 20260903 方案 B）：pipeLlmCall 生成并同时传给
   * LlmLogWriter（router JSONL 的 request_id）与本字段；工具执行轮经
   * pipeToolExecutions 流入 ToolContext.requestId，实现工具日志与 router
   * 日志精确对齐。Retry 重跑同一 cr 时 id 不变（同一 LLM 响应）。
   */
  requestId: Option[String] = None
)
