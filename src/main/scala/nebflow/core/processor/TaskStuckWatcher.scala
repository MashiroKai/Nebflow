package nebflow.core.processor

import cats.effect.IO
import cats.syntax.all.*
import io.circe.syntax.*
import nebflow.agent.{AgentCommand, AgentEvent, AgentKind, AgentRecord, AgentStatus, SharedResources}
import nebflow.core.NebflowLogger
import nebflow.gateway.WsHub

import scala.concurrent.duration.*
import scala.concurrent.duration.FiniteDuration

/**
 * P0 阶段 3（2026-08-18，设计 §4.4）：卡死识别与恢复扫描器。
 *
 * 背景：12:39 现场事故——7 个并发 Delegate 撞 API 限流后各自进入 retry/fallback
 * 循环，turn 长期不完成、零事件输出，BackoffSupervisor 只在 Terminated（崩溃）
 * 时介入，卡死（活着但不动）永不触发。本扫描器补上"Processing 态 + turn 活动
 * 长时间为零"的识别与恢复。
 *
 * 判定（只对 taskKinds = {Delegate, Ephemeral, Flow, SubTask}，与
 * WebSocketRoutes.filterActiveAgents 同集合）：
 *   - AgentRecord.status == Processing 且 now - lastActivityMs > threshold
 *   - idle 态永不判卡死（run_in_background 时 agent 回 Idle 为合法状态）——
 *     防误杀铁律；status/lastActivityMs 由 AgentCore.touchRegistryActivity
 *     在 LLM 流 chunk / 工具完成 / turn 完成时维护。
 *
 * 2026-09-10 卡死判据换轴（取证 20260910_130621_flow-node-activity-signal-forensics.md）：
 * 事故实证（n-5c69c793 会话被一条前台常驻 dev server 命令占死 2h50m）——旧判据的
 * 唯一周期写点 BashTool.startActivityBridge 把**进程侧 CPU 微动**当成了 agent 侧
 * 活动（实测 1.786 ms/s = 10ms 阈值的 5.4 倍 ⇒ 恒有「进展」），于是 `idle` 恒 <30s、
 * 本 watcher 与 SessionKick 双双失明。换轴后判据 = 两条 **agent 侧** 判据的并集
 * （`assess`，**不引用任何进程 CPU**）：
 *   - ① agent 侧事件流停滞：now - lastActivityMs > threshold（LLM 流楔死形态；
 *     lastActivityMs 现在只由 agent 侧事件写，进程活性另存 processActivityMs）
 *   - ② 工具相位超时：同一 turn 内单个工具调用持续 > Defaults.ToolPhaseStuckMs
 *     （默认 10min，与前台 no-progress ceiling 同档）且该 turn 未完成
 *     （status 仍 Processing）——进程占死形态（本次事故形态）。
 * 恢复链（L1→L4 / Team 只读 / 子 agent Stop）保持不变，见 recover。
 *
 * 恢复：
 *   - 子 agent（有 parentRef）：发 AgentCommand.Stop → AgentActor 的 Stop
 *     handler cancelCurrentTurn（杀挂死的 LLM 流 fiber）→ actorLoop 退出 →
 *     BackoffSupervisor death-watch 收 Terminated → 按现有退避（5s→60s）重启，
 *     不新造恢复机制。重启后若仍卡死，watcher 再次 Stop → 重启计数 +1 →
 *     maxRestarts 熔断（supervisor 现有逻辑）。
 *   - gate-wedge P1-1（2026-08-20）：Stop 是 mailbox 消息，suspended 在 LLM
 *     fiber 上的 agent 永不消费（事故：6.5h 每 30s 重发全部无效）。同一
 *     session 累计 StopAttempts(=2) 次无响应后，watcher 升级为经 inflight
 *     注册表按 session 硬取消在飞 LLM 请求（StuckAbort→Fatal，不 fallback
 *     不重发上下文）——turn 走 llm-fail，mailbox 恢复轮转，Stop 终于被消费。
 *   - issue #31 终极兜底（2026-08-20）：硬取消后仍 stuck（attempts ≥
 *     StopAttempts+2，即 escalate 后约两轮扫描仍无活动）——Stop 与硬取消
 *     双失效（当晚 design-engineer 47 次 Stop + 反复 hard-cancel 全无效，
 *     turn fiber 挂在无取消注册的等待上），supervisor 永远等不到终态 →
 *     父 barrier 留 phantom slot、held 结果永久滞留。此时经 supervisorRef
 *     发 AgentEvent.Cancelled（AgentControl cancel 同链路）：父 barrier 归还、
 *     held 注入触发轮次、taskStore cancelled、registry 移除、child Stop、
 *     supervisor 自停。
 *   - Project flow 会话（node-/dispatcher-，supervisorRef=观察桥）卡死：广播
 *     taskStuck(action=restart) + 第 1 次即硬取消在飞 LLM；StopAttempts+2 轮仍
 *     卡 → 经 supervisorRef 发 AgentEvent.Cancelled（观察桥 → dispatcher 清
 *     registry+停 agent / node 走 engine cancelNode 全链清理）。不发 raw Stop
 *     （单次会话无 supervisor 重启，Stop 只会杀 actor 而不发终态事件）。
 *   - 根 agent（无 parentRef）：不自动重启——广播 taskStuck WS 事件 + 日志，
 *     由用户决定。
 *
 * 阈值协同：默认 10min ≫ llm-fail 退避上限（8s×3 + probe 120s）——重试链每次
 * 动作都 touch 活动戳，不会在重试链完成前误判。
 */
object TaskStuckWatcher:

  private val logger = NebflowLogger.forName("nebflow.core.processor.stuck")

  /** gate-wedge P1-1: after this many ignored Stops, escalate to hard-cancelling
    * the stuck agent's in-flight LLM fiber via the inflight registry. */
  val StopAttempts: Int = 2

  /** 与 WebSocketRoutes.filterActiveAgents 相同的 task 集合（保持单一事实源意识）。 */
  private val taskKinds = Set(AgentKind.Delegate, AgentKind.Ephemeral, AgentKind.Flow, AgentKind.SubTask)

  /**
   * 2026-09-10 卡死判据换轴（唯一判据事实源）：本 watcher 的扫描 + AgentControlTool
   * 的 stuck?/status 展示 + WebSocketRoutes.maybeSessionKick 全部走此函数，避免三处
   * 判据漂移（旧代码三处各写一遍 `now - lastActivityMs > 阈值`）。
   *
   * 返回 Some((停滞秒数, 原因文本)) = 判卡死；None = 不判。
   * 判据（并集，**均不读取任何进程 CPU / processActivityMs**）：
   *   ① agent 侧事件流停滞：lastActivityMs 超阈（LLM 流楔死 / turn 挂死形态）
   *   ② 工具相位超时：当前 turn 内单个工具调用持续超 Defaults.ToolPhaseStuckMs
   *      （进程占死形态——本次事故形态：零 LLM turn、零 chunk，旧判据因进程
   *      CPU 微动永不失明）
   * 前置条件（status == Processing、WaitingForUser 豁免、lastActivityMs==0 视作
   * 未 touch）由调用方保持不变：assess 只回答「给定记录是否超时」。
   *
   * R6（取消静默死锁修复批 2026-09-10，作者裁定方案 2）：轴 ② 的有效阈值改为
   * `[[ToolStuckJudgment.effectiveToolPhaseMs]]` —— **尊重命令自己声明的合法时长**
   * （有效阈值 = max(默认档, 声明 + 宽限)，即声明时长只可**放宽**判据、不可收紧到
   * 默认档之下；未声明取默认档。逐字公式差异与「已裁定以 max 为准」的理由见该
   * 函数注释）。
   * 三例误杀的案例 1 命令自带 `timeout=900000ms`（15min 授权），旧判据在其 11.2
   * 分钟处开火；改造后该命令在其授权期内不再判死。
   * 未声明时长（currentToolDeadlineMs == 0）→ 原 10min 档零变化。
   * **不得**把进程 CPU 重新引入判据（红线 R6-4）。
   */
  /** R8 方向①（看门狗自身监测）：判据**分支身份**取值域（设计 §2.3 的 `branch`）——
    * 与 [[assessDetailed]] 同源单点，事件面与日志面不二次派生（防漂移）。 */
  val BranchMerged: String = "merged"
  val BranchAgentStale: String = "agent-stale"
  val BranchToolOverdue: String = "tool-overdue"

  /** R8 方向①：判据命中详情（[[assess]] 的旧返回值 + 分支身份 + 原始读数）。
    * 事件面（`WatchdogEventLog`）消费之；**任何字段都不得回流进判据**——`branch` /
    * `toolPhaseMs` / `agentIdleMs` 只是同一次判定的读数快照，判据本体仍是
    * [[assessDetailed]] 内的两个不等式（红线 R6-4：不引入进程 CPU 等新信号）。 */
  final case class StuckAssessment(
    secs: Long,
    reason: String,
    branch: String,
    agentIdleMs: Long,
    toolPhaseMs: Long,
    toolName: Option[String]
  )

  // ── 判据序：根因分流（stuck 自动恢复批 P1，2026-09-11）────────────────────
  //
  // 设计 §2.1 的判据序：**先分流，再判死**。`assessDetailed` 回答「是否命中停滞
  // 候选」，本段回答「命中的是哪一类、本拍该不该动作」——两者严格分离，
  // 分流结果**绝不回流进判死不等式**（判死本体仍是 assessDetailed 的两条不等式）。

  /** 类① 真卡死：无进展且无事件（判据命中后无任何「还在干活」的证据）。 */
  val ClassTrueStuck: String = "true-stuck"
  /** 类② 假阳性：工具相位未超有效阈值**且**有正信号（还在推进）⇒ 本拍不动作。 */
  val ClassFalsePositive: String = "false-positive"
  /** 类④ provider hang：本会话有在飞 LLM 请求 ⇒ 交 LLM 层三档看护，watcher 不介入。 */
  val ClassProviderHang: String = "provider-hang"
  /** 判据序的分流结果（设计 §2.1）。 */
  final case class StuckClassification(
    /** ∈ [[ClassTrueStuck]] / [[ClassFalsePositive]] / [[ClassProviderHang]]。 */
    cls: String,
    /** 该类的机器可读判据读数（进事件面与上报文本；**不回流判据**）。 */
    note: String,
    /** true = 本拍允许进入恢复链（P2/P3 的断点恢复腿）。类②/④ 恒 false。 */
    recoverable: Boolean,
    /** true = 本拍允许执行**破坏档**（L2 进程 kill / 终态化）。类① 必须为 false
      * （设计 §2.2 建议动作档：类① 只允许非破坏档 L1 + 恢复腿）。 */
    destructiveAllowed: Boolean
  )

  /** 类③（环境失效）与类⑥/⑦ 不在本 watcher 的产出面，此处显式记录归属以免被误
    * 读成遗漏：
    *   - 类③ 工具显式失败 / cwd 失效：其可观测形态 = 工具相位被清（`currentToolStartedAt`
    *     归零）或 agent 事件恢复 ⇒ 本 watcher 不会把它判成停滞；剩余窗口（工具尚未
    *     返回而 cwd 已失效）在 registry 快照上**无信号可用**（`AgentRecord` 无 cwd
    *     字段）⇒ 本批**不产出该类**，正确出口仍是 agent 自纠（未证项，见报告）。
    *   - 类⑥ bgWait cap / bg idle：**R-4 裁定不并入本批**（归 home 板 #5），仅在
    *     「无正信号不得终态化」口径上与类② 同源。
    *   - 类⑦ `bg-harvest` 无 cause：本批 **P2 附加小项**（R-5），发射点
    *     `NodeEngine` 的 bg-harvest 写点。
    */

  /** 正信号（进展证据）是否新鲜——**只阻止判死、绝不促成判死**（作者裁定 R-3）。
    * 读 [[AgentRecord.lastProgressSignalAt]]（写点语义单点 =
    * `AgentCore.markToolProgress`，传感器 = 工具活动桥采样点）。 */
  def hasProgressSignal(
    rec: AgentRecord,
    now: Long,
    windowMs: Long = nebflow.shared.Defaults.StuckProgressSignalWindowMs
  ): Boolean =
    rec.lastProgressSignalAt > 0 && now - rec.lastProgressSignalAt <= math.max(0L, windowMs)

  /** 判据序（设计 §2.1）的**唯一实现单点**——纯函数，可独立单测；扫描面与任何
    * 将来消费者共用同一口径（防分流漂移，与 [[assessDetailed]] 唯一事实源同源）。
    *
    * 判定顺序（严格自上而下，先命中先返回）：
    *   1. `inflight > 0` ⇒ 类④ [[ClassProviderHang]]（LLM 层自管，watcher 零动作）
    *   2. 有工具相位（`currentToolStartedAt > 0`）：
    *      a. `toolPhaseMs ≤ 有效阈值` 且**有正信号** ⇒ 类② [[ClassFalsePositive]]
    *         （本拍不动作，只记 `suspect`——这条正是今天 11/11 样本的出口）
    *      b. 其余 ⇒ 类① [[ClassTrueStuck]]（**非破坏档**：只允许 L1 + 恢复腿）
    *   3. 无工具相位 ⇒ 类① [[ClassTrueStuck]]
    *
    * **类⑤（会话纤维挂起 / 邮箱堵）本批不产出**（**未尽事项，单列**）：设计 §2.2 的
    * 判据需要 `cancelInflightFor == 0 ∧ transportAbortFor == 0` 两个**返回值**读数，
    * 而这两者都是**破坏性探针**（会 complete halt Deferred / 触发 transport abort），
    * 不能放在「只观测、不动作」的分流位上（这正是本批新增只读 `inflightFor` 的原因）。
    * 用「`inflightFor == 0`」冒充该条件会造成**静默误分类**：正常的工具相位同样
    * inflight == 0，会把类① 判成类⑤ 而跳过恢复腿（本批实测：该冒充使
    * `TaskStuckWatcherSpec`/`WatchdogSelfMonitorSpec` 的 L3 用例由绿转红）。
    * ⇒ 类⑤ 归属维持设计原文的「**需更强原语，另批**」（§2.2 建议动作档），
    * 本批不宣称该类可判（报告「未尽事项」列明）。
    *
    * **不读取任何进程 CPU**（红线 R6-4）：`inflight` 是 LLM 在飞计数，正信号走
    * 「只阻止」方向，两者都不构成判死依据。 */
  def classify(
    rec: AgentRecord,
    a: StuckAssessment,
    now: Long,
    inflight: Int,
    toolPhaseThresholdMs: Long = nebflow.shared.Defaults.ToolPhaseStuckMs,
    progressWindowMs: Long = nebflow.shared.Defaults.StuckProgressSignalWindowMs
  ): StuckClassification =
    val effectiveToolPhaseMs = ToolStuckJudgment.effectiveToolPhaseMs(
      toolPhaseThresholdMs, rec.currentToolDeadlineMs, nebflow.shared.Defaults.ToolDeadlineSlackMs)
    val progress = hasProgressSignal(rec, now, progressWindowMs)
    val progressAgoSecs = if rec.lastProgressSignalAt > 0 then (now - rec.lastProgressSignalAt) / 1000 else -1L
    if inflight > 0 then
      StuckClassification(ClassProviderHang,
        s"$inflight in-flight LLM request(s) for this session — provider-hang (class 4): the LLM layer's own " +
          "watchdog owns this case, the watcher takes no action this round",
        recoverable = false, destructiveAllowed = false)
    else if rec.currentToolStartedAt > 0 then
      if a.toolPhaseMs <= effectiveToolPhaseMs && progress then
        StuckClassification(ClassFalsePositive,
          s"tool '${a.toolName.getOrElse("?")}' still inside its authorised window " +
            s"(${a.toolPhaseMs / 1000}s ≤ ${effectiveToolPhaseMs / 1000}s) and advancing " +
            s"(progress signal ${progressAgoSecs}s ago) — false positive (class 2): no action this round",
          recoverable = false, destructiveAllowed = false)
      else
        StuckClassification(ClassTrueStuck,
          s"no progress signal (last=${if progressAgoSecs < 0 then "never" else s"${progressAgoSecs}s ago"}) " +
            s"while tool '${a.toolName.getOrElse("?")}' is in flight " +
            s"(${a.toolPhaseMs / 1000}s, authorised ${effectiveToolPhaseMs / 1000}s) — true stuck (class 1): " +
            "non-destructive tier only (L1 halt + recovery leg; no process kill, no terminalization)",
          recoverable = true, destructiveAllowed = false)
    else
      StuckClassification(ClassTrueStuck,
        s"no tool phase in flight, no in-flight LLM request, no progress signal — " +
          "true stuck (class 1): non-destructive tier only (L1 halt + recovery leg)",
        recoverable = true, destructiveAllowed = false)

  /** [[assess]] 的详情版（唯一判据事实源）：返回命中的**分支身份**与两条轴的原始终
    * 读数，供 R8 事件面消费。`assess` 退化为它的投影——**既有三消费点
    * （watcher 扫描 / `AgentControlTool` 展示 / `WebSocketRoutes` kick）签名与行为
    * 零改动**。 */
  def assessDetailed(
    rec: AgentRecord,
    now: Long,
    thresholdMs: Long = nebflow.shared.Defaults.StuckThresholdMs,
    toolPhaseThresholdMs: Long = nebflow.shared.Defaults.ToolPhaseStuckMs
  ): Option[StuckAssessment] =
    val agentIdleMs = if rec.lastActivityMs > 0 then now - rec.lastActivityMs else 0L
    val agentStale = rec.lastActivityMs > 0 && agentIdleMs > thresholdMs
    val toolPhaseMs = if rec.currentToolStartedAt > 0 then now - rec.currentToolStartedAt else 0L
    // R6（**已裁定 2026-09-10：以 `max` 为准**）：有效工具相位阈值——命令声明了
    // 合法时长就取 max(默认档, 声明 + 宽限)。**只放宽不收紧**；未声明 `timeout`
    // 的工具仍按默认档 10min 判死。（旧文写 min，与「尊重命令自己声明的合法时长」
    // 的立论自相矛盾——min 在大声明时退化为 10min，案例 1 照旧误杀。）
    val effectiveToolPhaseMs = ToolStuckJudgment.effectiveToolPhaseMs(
      toolPhaseThresholdMs, rec.currentToolDeadlineMs, nebflow.shared.Defaults.ToolDeadlineSlackMs)
    val toolOverdue = rec.currentToolStartedAt > 0 && toolPhaseMs > effectiveToolPhaseMs
    val toolLabel = rec.currentToolName.getOrElse("?")
    // 声明时长参与判定时才附注（未声明 → 文案逐字不变 = 既有断言零漂移）。
    val deadlineNote =
      if rec.currentToolDeadlineMs > 0 then s" (declared timeout ${rec.currentToolDeadlineMs / 1000}s)"
      else ""
    if agentStale && toolOverdue then
      Some(StuckAssessment(math.max(agentIdleMs, toolPhaseMs) / 1000,
        s"agent idle ${agentIdleMs / 1000}s and tool '$toolLabel' running ${toolPhaseMs / 1000}s$deadlineNote in an unfinished turn",
        BranchMerged, agentIdleMs, toolPhaseMs, rec.currentToolName))
    else if agentStale then
      Some(StuckAssessment(agentIdleMs / 1000, s"agent idle ${agentIdleMs / 1000}s (no LLM/tool event)",
        BranchAgentStale, agentIdleMs, toolPhaseMs, rec.currentToolName))
    else if toolOverdue then
      Some(StuckAssessment(toolPhaseMs / 1000, s"tool '$toolLabel' running ${toolPhaseMs / 1000}s$deadlineNote in an unfinished turn",
        BranchToolOverdue, agentIdleMs, toolPhaseMs, rec.currentToolName))
    else None

  /** 判据（并集）的兼容投影：`Some((停滞秒数, 原因文本))` = 判卡死；None = 不判。
    * 语义与 [[assessDetailed]] 逐字一致（既有调用方零改动）。 */
  def assess(
    rec: AgentRecord,
    now: Long,
    thresholdMs: Long = nebflow.shared.Defaults.StuckThresholdMs,
    toolPhaseThresholdMs: Long = nebflow.shared.Defaults.ToolPhaseStuckMs
  ): Option[(Long, String)] =
    assessDetailed(rec, now, thresholdMs, toolPhaseThresholdMs).map(a => (a.secs, a.reason))

  /**
   * 扫描集合 = taskKinds + Root + Team。Root（Nebula 主窗口）虽不在活跃子代理列表
   * （filterActiveAgents 语义），但其 turn 同样可能卡死（如 12:39 现场 Nebula
   * 自身撞限流）——设计 §4.4 要求根 agent 也通知（不自动重启）。
   *
   * #22 (2026-08-19)：Team 加入扫描——Mail 激活的 team agent turn 静默挂死
   * 2 小时（Frontend 12:28-13:45 三案之一），因 Team 不在扫描集合而零可见
   * 性、零恢复。Team 是 AgentControl §4 只读 kind（用户可见的长驻会话），
   * 不自动 Stop——只广播 taskStuck(action=attention) 留痕，由用户/Nebula
   * 决策（AgentControl restart / 手动干预）。
   */
  private val scannedKinds: Set[AgentKind] = taskKinds + AgentKind.Root + AgentKind.Team

  /**
   * 周期扫描循环：scan → sleep(interval) → 递归。由 GatewayMain 以 fiber 启动
   * （.start），错误被 handleErrorWith 吞掉防止 fiber 崩溃——扫描器必须自愈。
   */
  def run(resources: SharedResources, wsHub: WsHub, interval: FiniteDuration, thresholdMs: Long): IO[Unit] =
    // NOTE: must use `>>` (by-name) for the recursion, NOT `*>` — `*>` evaluates
    // its right operand strictly, so `*> loop` would recurse infinitely while
    // BUILDING the IO description (StackOverflowError at startup, caught by
    // the s3 smoke test). `>>` defers `loop` until the sleep completes, so the
    // recursion crosses the async sleep boundary and stays stack-safe.
    //
    // gate-wedge P1-1 (2026-08-20): stopCounts tracks how many Stop mailbox
    // messages each stuck session has ignored. Stop is consumed by the actor
    // loop, which never turns while the agent is suspended on its LLM fiber —
    // the incident showed 6.5h of 30s-interval Stop resends with zero effect.
    // After StopAttempts ineffective Sends we escalate to cancelling the
    // in-flight LLM fiber itself (LlmInterface.cancelInflightFor): the turn
    // fails, the queued Stops are finally consumed, recovery proceeds.
    def loop: IO[Unit] =
      for
        stopCounts <- cats.effect.Ref.of[IO, Map[String, Int]](Map.empty)
        // R8 方向②：待复查的 L3 开火（内存态；宿主重启即丢——设计 §3.8 明确接受，
        // 与 stopCounts 同族，不得当作持久状态消费）。
        pendingL3 <- cats.effect.Ref.of[IO, List[PendingL3]](Nil)
        _ <- scanLoop(stopCounts, pendingL3)
      yield ()

    def scanLoop(stopCounts: cats.effect.Ref[IO, Map[String, Int]], pendingL3: cats.effect.Ref[IO, List[PendingL3]]): IO[Unit] =
      scan(resources, wsHub, thresholdMs, stopCounts, pendingL3).handleErrorWith(e =>
        logger.warn(s"TaskStuckWatcher scan failed (will retry next cycle): ${e.getMessage}")
      ) *> IO.sleep(interval) >> scanLoop(stopCounts, pendingL3)
    loop

  /** R8 方向②：一次待复查的 L3 开火（设计 §3.2-C「待验证」登记）。携带开火当时的
    * 判据读数与分支，使复查事件（`l3-ineffective`）与开火事件（`stuck-fire`）在同一
    * 事件面可按 (sessionId, firedAt) join。 */
  private[processor] final case class PendingL3(
    sessionId: String,
    rootSessionId: String,
    firedAt: Long,
    branch: String,
    toolPhaseMs: Long,
    agentIdleMs: Long,
    attempt: Int
  )

  /** 单轮扫描：识别卡死 agent 并执行恢复动作。独立成函数便于单元测试。 */
  def scan(
    resources: SharedResources,
    wsHub: WsHub,
    thresholdMs: Long,
    stopCounts: cats.effect.Ref[IO, Map[String, Int]] = cats.effect.Ref.unsafe(Map.empty),
    /** R8 ②：L3 待复查登记（默认空 Ref = 单测一次驱动不留状态）。 */
    pendingL3: cats.effect.Ref[IO, List[PendingL3]] = cats.effect.Ref.unsafe(List.empty),
    /** R8 ②：T+N 的 N（测试注入缩短窗口；生产默认常量 120s，无 prop）。 */
    l3VerifyDelayMs: Long = nebflow.shared.Defaults.L3VerifyDelayMs
  ): IO[Unit] =
    val now = System.currentTimeMillis()
    // R8 ②：复查搭**本**扫描轮（零新增定时器，设计 §3.4）——先复查上轮到期的 L3，
    // 再扫本轮卡死候选（顺序无关，但先复查可让同轮内「复查发现失效 → 下一轮的
    // 恢复动作」时序更直观）。
    verifyL3Outcomes(resources, pendingL3, now, l3VerifyDelayMs) *>
    resources.agentRegistry.get.flatMap { registry =>
      val stuck = registry.values.toList
        .filter(rec => scannedKinds.contains(rec.kind))
        .filter(rec => rec.status == AgentStatus.Processing)
        // R2 (wait-timeout-fix, 2026-09-03 作者裁定): WaitingForUser is a
        // first-class human-in-the-loop wait (AskUser pending / permission
        // card) — a person, not the process, is the progress driver. Never a
        // stuck candidate: this exclusion (paired with the status wiring)
        // kills all 116/day taskStuck false positives (audit 20260903) and
        // the destructive Stop→hard-cancel chain that killed sub-agents'
        // pending questions. Behaviorally subsumed by the Processing filter
        // above; kept as an explicit guard so a future widening of the scan
        // condition can never silently re-include human-in-the-loop waits.
        // Coverage resumes the moment the answer lands (paired restore to
        // Processing with a fresh lastActivityMs — AgentActor AskUser handler
        // / AgentCore.askUserPermission), so true hangs stay reachable.
        .filter(rec => rec.status != AgentStatus.WaitingForUser)
        // 2026-09-10 换轴：判据收敛到 assess（agent 侧事件停滞 ∪ 工具相位超时，
        // 二者都不引用进程 CPU）。旧行 `now - rec.lastActivityMs > thresholdMs`
        // 保留为 assess 的判据 ①，其余语义不变。
        // R8 ①：改用详情版（assess 的投影），事件面取分支身份与原始读数。
        .flatMap(rec => assessDetailed(rec, now, thresholdMs).map((rec, _)))
      val stuckIds = stuck.map(_._1.sessionId).toSet
      // Drop counters for sessions that recovered (fresh activity / different
      // status / gone) so a future stuck episode starts from Stop attempt 1.
      stopCounts.modify(m => (m.view.filterKeys(stuckIds.contains).toMap, ())) *>
        stuck.traverse_ { (rec, assessment) =>
          // 判据序（P1）：先分流根因类别，再进恢复链。`inflightFor` 是**只读**在飞
          // 计数（设计 §6.1 未证项 6 的补齐）——此前判「本会话是否有在飞 LLM」只能
          // 靠破坏性的 cancelInflightFor 反推。stopAttempts 取当轮值（内存态）。
          nebflow.llm.LlmInterface.inflightFor(rec.sessionId).flatMap { inflight =>
            recover(resources, wsHub, rec, assessment,
              classify(rec, assessment, now, inflight),
              stopCounts, pendingL3, l3VerifyDelayMs)
          }
        }
    }

  /** taskStuck WS 广播（统一 payload：sessionId/kind/idleSecs/action/reason）。 */
  private def broadcastStuck(wsHub: WsHub, rec: AgentRecord, idleSecs: Long, action: String, reason: String): IO[Unit] =
    wsHub
      .broadcast(
        io.circe.Json.obj(
          "type" -> "taskStuck".asJson,
          "sessionId" -> rec.sessionId.asJson,
          "kind" -> rec.kind.toString.asJson,
          "idleSecs" -> idleSecs.asJson,
          "action" -> action.asJson,
          // 2026-09-10 换轴：判据原因（哪条轴触发 / 工具名与已持续时间）——
          // 人类可见性的一部分（旧前端忽略未知键，向后兼容）。
          "reason" -> reason.asJson
        )
      )
      .handleErrorWith(e => logger.warn(s"TaskStuckWatcher: taskStuck WS broadcast failed: ${e.getMessage}"))

  /** 恢复动作：Team 只读通知 / Project flow 会话取消 / 子 agent 重启 / 根 agent 通知。
    *
    * R8（看门狗自身监测）在本函数内落三件事：
    *   ① 每次开火**无条件**写一条 `stuck-fire` 结构化事件（[[recordFire]]，
    *      fire-per-fire，不做单发去重）；
    *   ② **影子模式**（`Defaults.StuckShadowMode`，默认 false）：`shadow=true` 时
    *      本函数内全部破坏性动作（设计 §4.4 表 1–9）被 [[act]] 短路，只留事件 + 日志；
    *   ③ L3 开火时登记 [[PendingL3]]（方向②的 T+N 复查输入）。
    * ①②③ 都不改变非 shadow 分支的动作序列（影子开关关闭时逐字等价）。 */
  private def recover(
    resources: SharedResources,
    wsHub: WsHub,
    rec: AgentRecord,
    assessment: StuckAssessment,
    cls: StuckClassification,
    stopCounts: cats.effect.Ref[IO, Map[String, Int]],
    pendingL3: cats.effect.Ref[IO, List[PendingL3]],
    l3VerifyDelayMs: Long
  ): IO[Unit] =
    val idleSecs = assessment.secs
    val reason = assessment.reason
    val hard = nebflow.shared.Defaults.HardRecoveryEnabled
    // R8 ③：影子模式每次开火现读（不缓存，支持不重启翻转；设计 §4.3）。
    val shadow = nebflow.shared.Defaults.StuckShadowMode
    /** 破坏性动作闸门：shadow=true 时短路为 no-op（只记录不动作）。
      * **作用域仅本函数**——`SessionKick`/`hardResumeFlowNode` 的内部行为不进本开关面。 */
    def act[A](io: IO[A]): IO[A] = if shadow then IO.unit.asInstanceOf[IO[A]] else io
    /** R8 ①：开火留痕（含 shadow 标记，使标定窗口内的行自解释）。 */
    def recordFire(level: String, attempt: Int): IO[Unit] =
      recordStuckFire(rec, assessment, level, attempt, hard, shadow)
    // ── 判据序留痕（P1，2026-09-11）──────────────────────────────────────────
    // 每一次判定命中都留一行 `stuck-detected`（含**零动作**的类② 拍与交棒 LLM 层的
    // 类④ 拍）——fire-per-fire，与 R8 的 `stuck-fire` 同纪律、同面可按 (sessionId, ts)
    // join。**动作面留痕**（`stuck-fire`）与**检出面留痕**（`stuck-detected`）分开，
    // 使「改造后 11/11 转 suspect」这类零动作事实在审计面不被读成开火。
    val classifyNote = recordClassification(rec, assessment, cls)
    // ── 判据序闸门（P1，2026-09-11 作者裁定 R-3）──────────────────────────────
    // 类② 假阳性 ⇒ **本拍零动作**：不 halt、不 abort、不 kill、不终态化、不广播。
    // 这正是今天 11/11 样本改造后应有的出口（**预期行为，不是回归**）：它们的形态 =
    // 「>600s 单工具调用 + 工具持续推进」，落在类②（正信号在场 ⇒ 只阻止判死）。
    if cls.cls == ClassFalsePositive then
      logger.info(
        s"TaskStuckWatcher: ${rec.sessionId} (kind=${rec.kind}) hit the stall judge " +
          s"[branch=${assessment.branch}, judge: $reason] but is classified ${cls.cls} — " +
          s"no action this round (suspect only). ${cls.note}"
      ) *> classifyNote
    // 类④ provider hang：**只走 LLM 侧处置**（L1 halt / L2 transport abort 都是 LLM 层
    // 原语），**绝不进节点级腿**——`destructiveAllowed=false` 收回 L2 进程 kill，
    // `recoverable=false` 使 L3 节点级腿被跳过（见下方各分支的门）；`classifyNote`
    // 照常留痕。§2.1 的「LLM 层三档看护接管」在此落成：L1 的 halt 正是把控制权交还
    // LLM 层有界重试的既有原语（见 L1 分支注释）。
    // #22: Team kind 只读——长驻用户可见会话，绝不自动 Stop（AgentControl §4）。
    // 2026-08-19 Frontend 僵尸 turn 若有此广播，2 小时静默会变成即时可见。
    else if rec.kind == AgentKind.Team then
      logger.warn(
        s"TaskStuckWatcher: team agent ${rec.sessionId} stuck in Processing for ${idleSecs}s " +
          s"[judge: $reason] " +
          "— the actor is never auto-stopped (let-it-crash: crash+recover beats chronic hang), " +
          "but a looping turn may be terminated by loop guard; the team Manager or Nebula can " +
          "cancel/restart it via AgentControl"
      ) *>
        classifyNote *>
        recordFire("attention", 0) *>
        act(broadcastStuck(wsHub, rec, idleSecs, "attention", reason))
    // Project flow 会话（node-/dispatcher-，supervisorRef=观察桥）卡死：分级接管
    // L1→L4（hard-recovery P5，2026-09-07 设计 §2.6/§9）。回验 = 下一轮扫描的
    // stuck 复查（恢复则计数器随 filterKeys 复位，不再升级）。绝不发 raw Stop——
    // 单次会话无 supervisor 重启，Stop 杀掉 actor 而不发终态事件（桥收不到 →
    // engine fiber 挂死 / 桥僵尸）。dag- 旧 flow 会话（无 supervisorRef）不进此
    // 分支，走根 agent 分级（其取消走 cancelFlow）。
    else if rec.kind == AgentKind.Flow && rec.supervisorRef.isDefined then
      logger.warn(
        s"TaskStuckWatcher: Project flow session ${rec.sessionId} (kind=Flow) stuck in Processing " +
          s"for ${idleSecs}s > threshold [judge: $reason] — escalating (L1 halt → L2 transport abort → L3 resume)"
      ) *>
        // P7 诚实帧：硬分级时每扫描一帧**真实** action（在下述 match 内逐拍广播）；
        // 只有回滚形态（!hard）沿用旧语义在此统一广播 restart。
        classifyNote *>
        act(if hard then IO.unit else broadcastStuck(wsHub, rec, idleSecs, "restart", reason)) *>
        stopCounts.modify { m =>
          val n = m.getOrElse(rec.sessionId, 0) + 1
          (m.updated(rec.sessionId, n), n)
        }.flatMap { attempts =>
          def hardCancel() =
            nebflow.llm.LlmInterface.cancelInflightFor(rec.sessionId).flatMap { n =>
              logger.warn(
                s"TaskStuckWatcher: ${rec.sessionId} attempt $attempts — hard-cancelled $n in-flight LLM request(s)"
              )
            }.handleErrorWith(e =>
              logger.warn(s"TaskStuckWatcher: hard-cancel for ${rec.sessionId} failed: ${e.getMessage}")
            )
          def transportAbort() =
            // L2 双管（裁定① tier-2）：transport abort 解 LLM 流楔死 + 会话自有
            // 进程组 kill 解工具挂死（按自有 PGID 击杀=强进程验身，会话 actor 保留）。
            // 护栏（证据 #5 ① 双条件容器 CPU 盲区）：进程 kill（reclaimSession）仅在
            // transport abort 命中 ≥1 在飞请求（LLM 楔死形态成立——流中 parked / intake-
            // first-chunk park 均有在飞请求）时执行；命中 0 = 工具执行相位（docker/cargo
            // 容器负载的 CPU 在容器内，宿主侧观测为零——活动桥接 sees 仅直接子进程
            // CPU），收回进程 kill，升级链走 tier-3。kill 复用收殓支 reclaimSession
            // 原语（Nebula 2026-09-07 12:41：killSessionProcesses + unregisterSession +
            // WS 帧，幂等，不另造平行 kill 机制）。
            nebflow.llm.LlmInterface.transportAbortFor(rec.sessionId).flatMap { n =>
              logger.warn(
                s"TaskStuckWatcher: ${rec.sessionId} attempt $attempts (L2) — transport-aborted $n in-flight LLM request(s)"
              ) *>
                (if n > 0 && cls.destructiveAllowed then
                   nebflow.core.tools.BgTaskRegistry.reclaimSession(Some(rec.sessionId), wsHub.broadcast, rec.rootSessionId)
                     .handleErrorWith(e =>
                       logger.warn(s"TaskStuckWatcher: session reclaim for ${rec.sessionId} failed: ${e.getMessage}"))
                 else
                   // P1 类① 非破坏档限定（设计 §2.2 建议动作档）：类① 的进程 kill
                   // 被**结构性**收回——两条独立的闸门都指向同一结论：
                   //   ① 本批新增的判据序（[[classify]]）：`inflightFor > 0` ⇒ 类④
                   //      分流，永远走不到 L2 的 n > 0 分支；
                   //   ② 本处的 `cls.destructiveAllowed` 显式守卫：即使将来放宽扫描面
                   //      或时序在判据后发生变化，类① 也不会静默获得进程 kill 能力
                   //      （设计 §3.4 互斥点 (b) 的同款「显式化」纪律）。
                   logger.warn(
                     s"TaskStuckWatcher: ${rec.sessionId} attempt $attempts (L2) — no in-flight LLM request (tool-phase); " +
                       s"process kill withheld [evidence #5 container-blindness guard; class=${cls.cls} " +
                       s"destructiveAllowed=${cls.destructiveAllowed}]"
                   ))
            }.handleErrorWith(e =>
              logger.warn(s"TaskStuckWatcher: transport abort for ${rec.sessionId} failed: ${e.getMessage}")
            )
          def bridgeCancelled(reason: String) =
            rec.supervisorRef match
              case Some(sup) =>
                (sup ! AgentEvent.Cancelled(
                  rec.sessionId,
                  s"stuck for ${idleSecs}s ($reason) — released by TaskStuckWatcher"
                )).handleErrorWith(e =>
                  logger.warn(s"TaskStuckWatcher: bridge Cancelled for ${rec.sessionId} failed: ${e.getMessage}")
                )
              case None => IO.unit
          if !hard then
            // 回滚形态（HardRecoveryEnabled=false）：维持本批前行为——每轮
            // hard-cancel，StopAttempts+2 轮后 bridge Cancelled 终态收殓。
            classifyNote *>
            recordFire("stop", attempts) *>
            act(hardCancel()) *> {
              if attempts >= StopAttempts + 2 then
                logger.warn(
                  s"TaskStuckWatcher: ${rec.sessionId} still stuck after $attempts attempts — " +
                    "releasing via bridge Cancelled (hard-cancel ineffective; single-shot session, no restart)"
                ) *>
                  act(bridgeCancelled("hard-cancel ineffective")) *>
                  // 面板实时终态帧（Sub-Agents 面板取消实时刷新修复）：仅 node-*
                  // 补发——dispatcher-* 由其观察桥拆除点（ProjectActor）统一补发。
                  act(if rec.sessionId.startsWith(nebflow.core.project.NodeEngine.SessionPrefix)
                   then
                     nebflow.core.node.NodeRunner
                       .emitSubagentPanelDone(wsHub.broadcast, rec.sessionId, rec.rootSessionId)
                       .handleErrorWith(e =>
                         logger.warn(s"TaskStuckWatcher: panel done frame for ${rec.sessionId} failed: ${e.getMessage}")
                       )
                   else IO.unit) *>
                  stopCounts.update(_ - rec.sessionId)
              else IO.unit
            }
          else
            attempts match
              case 1 =>
                // L1 软恢复：halt 在飞 LLM——turn 若卡死在 LLM 流上，StuckAbort 浮出
                // → 有界重试 / turn-end 注入接管。action=halt（真实动作，P7 诚实帧）。
                logger.warn(
                  s"TaskStuckWatcher: ${rec.sessionId} attempt $attempts (L1) — halting in-flight LLM"
                ) *> recordFire("L1", attempts) *> act(broadcastStuck(wsHub, rec, idleSecs, "halt", reason)) *> act(hardCancel())
              case 2 =>
                // L2 硬中断（action=hard-abort）：transport abort + reclaimSession（见
                // transportAbort 的 evidence #5 护栏——非 LLM 楔死形态收回进程 kill）。
                logger.warn(
                  s"TaskStuckWatcher: ${rec.sessionId} attempt $attempts (L2) — transport abort + session process reclaim"
                ) *> recordFire("L2", attempts) *> act(broadcastStuck(wsHub, rec, idleSecs, "hard-abort", reason)) *> act(transportAbort())
              case 3 if !cls.recoverable =>
                // 判据序闸门（P1）：类④ provider hang ⇒ 节点级腿**跳过**（负控③
                // 「不执行 L2 进程 kill / L3」）。理由：本案的根因在 LLM 流（有在飞
                // 请求），节点级清场/恢复不是对症手段；L1 的 halt 已把控制权交还
                // LLM 层的有界重试（见 L1 注释），此后按 §3.3「立即放弃、不重试」
                // 条件 2 走一次上报而不是继续升级。
                logger.error(
                  s"TaskStuckWatcher: ${rec.sessionId} attempt $attempts (L3) — SKIPPED for class ${cls.cls} " +
                    s"(recoverable=false): the node-level leg is not the remedy here; the LLM layer owns this case. ${cls.note}"
                ) *> recordFire("L3", attempts)
              case 3 =>
                // L3 真重启（设计 D-5）：bridge Cancelled 清场（actor 停止 + 节点终
                // 态）→ 5s 让清场链走完 → hardResumeNode 从 transcript 断点续跑
                // （复用 boot-recovery 底座）。fork：扫描循环不等。action=restart
                // 此时为真（P7 诚实语义）。resume 成功才清计数（会话状态翻转/Running
                // → watcher 不再见其 stuck）；失败保留计数 → 第 4 拍 L4 failed 可达。
                logger.warn(
                  s"TaskStuckWatcher: ${rec.sessionId} attempt $attempts (L3) — releasing via bridge Cancelled, then hard-resume from transcript breakpoint"
                ) *> classifyNote *> recordFire("L3", attempts) *> act(broadcastStuck(wsHub, rec, idleSecs, "restart", reason)) *>
                  act(bridgeCancelled("L3 hard-recovery: true resume from transcript breakpoint")) *>
                  // 面板实时终态帧（Sub-Agents 面板取消实时刷新修复；beta.57 CI
                  // ProjectSessionCancelPanelFrameSpec 暴露的 hard 分支遗漏）：L3 桥
                  // Cancelled 是分级链上 node-* 旧会话的释放点，不补发则面板行幽灵
                  // 滞留到刷新。仅 node-* 补发——dispatcher-* 由其观察桥拆除点
                  // （ProjectActor Failed|Cancelled 分支）统一补发，与 !hard giveUp
                  // 分支同语义同护栏。
                  act(if rec.sessionId.startsWith(nebflow.core.project.NodeEngine.SessionPrefix)
                   then
                     nebflow.core.node.NodeRunner
                       .emitSubagentPanelDone(wsHub.broadcast, rec.sessionId, rec.rootSessionId)
                       .handleErrorWith(e =>
                         logger.warn(s"TaskStuckWatcher: panel done frame for ${rec.sessionId} failed: ${e.getMessage}")
                       )
                   else IO.unit) *>
                  // R8 ②：登记 T+N 复查（**无条件**——shadow 与否都登记，复查本身只告警
                  // 不动作）。登记在 `.start` 之前 ⇒ 复查输入不依赖 resume fiber 的调度。
                  pendingL3.update(_.filterNot(_.sessionId == rec.sessionId) :+
                    PendingL3(rec.sessionId, rec.rootSessionId, System.currentTimeMillis(),
                      assessment.branch, assessment.toolPhaseMs, assessment.agentIdleMs, attempts)) *>
                  act((IO.sleep(5.seconds) *>
                    hardResumeFlowNode(resources, rec).flatMap {
                      case Some(nodeId) =>
                        // R5（取消静默死锁修复批 2026-09-10）：resume **成功**腿留痕——
                        // 此前 6/6 次 L3 开火零留痕（引擎侧 hardResumeNode 内部的
                        // `[hard-recovery] … resumed from transcript breakpoint` +
                        // hard-recovery 事件是唯一一条；此处补 watcher 侧归属行，
                        // 使「L3 开火 → resume 结果」在同一日志流内可闭环对账）。
                        logger.info(
                          s"TaskStuckWatcher: ${rec.sessionId} L3 resume OK — node '$nodeId' resumed from transcript breakpoint"
                        ) *> stopCounts.update(_ - rec.sessionId)
                      case None =>
                        // R5 方案 3 留痕 + 方案 4（本批）：resume **失败**腿不再留
                        // 「节点永久 cancelled」——引擎侧 `settleFailedHardResume`
                        // 现在把节点**改判 failed**（经既有 failNode 全链：result 含
                        // 原因 / nodeUpdated / failed 回流 / D5 停等留痕）。
                        logger.error(
                          s"TaskStuckWatcher: ${rec.sessionId} L3 resume FAILED — re-judging node as failed " +
                            "(engine settleFailedHardResume: failNode full chain; D5 zero-settlement for downstream)"
                        ) *> hardRecoveryFallback(resources, rec)
                    }).start.void)
              case 4 =>
                // L4 响亮失败（诚实失败原则）：明确上报需人工处理、进度已落盘。
                logger.error(
                  s"TaskStuckWatcher: ${rec.sessionId} stuck for ${idleSecs}s — L1/L2/L3 all ineffective. " +
                    "This session needs MANUAL attention; progress is persisted (transcript + queues on disk)."
                ) *> recordFire("L4", attempts) *> act(broadcastStuck(wsHub, rec, idleSecs, "failed", reason))
              case _ =>
                recordFire("L4", attempts) *> act(broadcastStuck(wsHub, rec, idleSecs, "failed", reason))
            end match
        }
    else
        rec.parentRef match
        case Some(_) =>
          logger.warn(
            s"TaskStuckWatcher: sub-agent ${rec.sessionId} (kind=${rec.kind}) stuck in Processing " +
              s"for ${idleSecs}s > threshold [judge: $reason] — sending Stop for supervised restart"
          ) *>
            // AgentControl spec §3.5：子 agent 自动重启也广播 taskStuck（原先只有
            // 根 agent 广播）——前端可见「后台 agent 卡死，正在自动重启」，Nebula
            // 事后用 AgentControl(list) 能看到 retryCount。
            classifyNote *>
            act(broadcastStuck(wsHub, rec, idleSecs, "restart", reason)) *>
            // gate-wedge P1-1: count ineffective Stops. A suspended agent never
            // consumes mailbox messages, so resending forever is useless (the
            // incident: thousands of resends over 6.5h). At the Nth attempt we
            // ALSO hard-cancel the in-flight LLM fiber — queued
            // or streaming — so the turn fails and the mailbox finally turns.
            stopCounts.modify { m =>
              val n = m.getOrElse(rec.sessionId, 0) + 1
              (m.updated(rec.sessionId, n), n)
            }.flatMap { attempts =>
              val escalate =
                if attempts >= StopAttempts then
                  nebflow.llm.LlmInterface.cancelInflightFor(rec.sessionId).flatMap { n =>
                    logger.warn(
                      s"TaskStuckWatcher: ${rec.sessionId} ignored $attempts Stops — hard-cancelled $n in-flight LLM request(s) (agent was suspended on its LLM fiber)"
                    )
                  }.handleErrorWith(e =>
                    logger.warn(s"TaskStuckWatcher: hard-cancel for ${rec.sessionId} failed: ${e.getMessage}")
                  )
                else IO.unit
              // Hard-recovery P5（2026-09-07）：Stop + halt 双失效后补 L2 transport
              // abort——parked read 的唯一解法（取证 §1.3），让 StuckAbort 真正浮出、
              // mailbox 恢复轮转、排队的 Stop 被消费、BackoffSupervisor 重启。
              val transportEscalate =
                if nebflow.shared.Defaults.HardRecoveryEnabled && attempts >= StopAttempts + 1 then
                  // L2 双管（裁定① tier-2）：LLM 流 transport abort + 会话自有进程组
                  // kill（工具挂死形态；按自有 PGID 击杀，会话保留）。护栏（证据 #5 ①）：
                  // 仅 transport abort 命中 ≥1 在飞请求（LLM 楔死形态）才 reclaimSession；
                  // 工具相位（容器 CPU 盲区）收回 kill，升级链走 supervisor Cancelled。
                  nebflow.llm.LlmInterface.transportAbortFor(rec.sessionId).flatMap { n =>
                    logger.warn(
                      s"TaskStuckWatcher: ${rec.sessionId} attempt $attempts (L2) — transport-aborted $n in-flight LLM request(s)"
                    ) *>
                      // P1 判据序闸门：进程 kill 只在 `destructiveAllowed` 类上执行
                      // （类①/②/④ 均为 false ⇒ 结构性收回；见 [[classify]]）。
                      (if n > 0 && cls.destructiveAllowed then
                         nebflow.core.tools.BgTaskRegistry.reclaimSession(Some(rec.sessionId), wsHub.broadcast, rec.rootSessionId)
                           .handleErrorWith(e => logger.warn(s"TaskStuckWatcher: session reclaim for ${rec.sessionId} failed: ${e.getMessage}"))
                       else
                         logger.warn(
                           s"TaskStuckWatcher: ${rec.sessionId} attempt $attempts (L2) — no in-flight LLM request (tool-phase); " +
                             s"process kill withheld [evidence #5 container-blindness guard; class=${cls.cls} " +
                             s"destructiveAllowed=${cls.destructiveAllowed}]"))
                  }.handleErrorWith(e =>
                    logger.warn(s"TaskStuckWatcher: transport abort for ${rec.sessionId} failed: ${e.getMessage}")
                  )
                else IO.unit
              // issue #31 (2026-08-20): escalate 后两轮扫描仍 stuck = Stop
              // (mailbox，suspended 不消费) 与 hard-cancel (halt Deferred，
              // 当晚实证 turn fiber 挂在无取消注册的等待上、complete 无效) 双失效
              // ——supervisor 永远等不到终态，父的 outstandingSubagentResults 留下
              // phantom slot，held 结果永久滞留（root 永不触发轮次，直到重启）。
              // 兜底：经 supervisor 的 Cancelled 分支走完整清理链（与 AgentControl
              // cancel 同一路径，当晚 Nebula 手动 cancel 已实证可释放 barrier）：
              // 父 ExternalEvent(source=delegate/subtask → barrier 精确递减一次) +
              // taskStore cancelled + registry 移除 + child Stop + supervisor 自停。
              // reason 文本随 payload 注入父 LLM，指引重新派发。
              // 无 supervisorRef 的 Ephemeral/Flow 不进 barrier、无 phantom 风险
              // ——维持现状（Stop 循环），不发兜底。
              val giveUp =
                if attempts >= StopAttempts + 2 then
                  rec.supervisorRef match
                    case Some(sup) =>
                      logger.warn(
                        s"TaskStuckWatcher: ${rec.sessionId} still stuck after hard-cancel (attempt $attempts) — " +
                          "releasing parent barrier via supervisor Cancelled (Stop and hard-cancel both ineffective)"
                      ) *>
                        (sup ! AgentEvent.Cancelled(
                          rec.sessionId,
                          s"stuck for ${idleSecs}s with Stop and hard-cancel both ineffective — " +
                            "released by TaskStuckWatcher; consider re-delegating this task"
                        )).handleErrorWith(e =>
                          logger.warn(s"TaskStuckWatcher: supervisor Cancelled for ${rec.sessionId} failed: ${e.getMessage}")
                        ) *>
                        // 清掉本 session 的 Stop 计数：supervisor 自停后 registry 记录
                        // 被移除、下轮 scan 不再命中，此处手动清是双保险（避免同
                        // sessionId 未来回合继承旧计数提前触发 giveUp）。
                        stopCounts.update(_ - rec.sessionId)
                    case None => IO.unit
                else IO.unit
              classifyNote *>
                recordFire("stop", attempts) *>
                act(escalate *> transportEscalate *> giveUp) *>
                act((rec.ref ! AgentCommand.Stop(s"stuck-task-${rec.sessionId}"))
                  .handleErrorWith(e => logger.warn(s"TaskStuckWatcher: Stop to stuck sub-agent ${rec.sessionId} failed: ${e.getMessage}")))
            }
        case None =>
          // 根 agent（无 parentRef；含 dag- 旧 flow 会话）：分级接管 L1-L4（hard-
          // recovery P5 扩列，2026-09-07——此前只广播 attention，「卡死」的唯一
          // 出口 = 重启宿主，取证 §1.1）。回验同 flow 分支 = 下一轮扫描复查。
          // dag- 旧 flow 会话（kind=Flow，无 supervisorRef 也无 parentRef）EXEMPT：
          // 其取消走 cancelFlow / RunningFlowRegistry（TaskStuckWatcherSpec
          // :651 notice-only 铁律）——分级只适用于真 root（General/Team 根会话），
          // 不得对 dag- 施加任何硬取消/kill 动作。
          if !nebflow.shared.Defaults.HardRecoveryEnabled || rec.kind == nebflow.agent.AgentKind.Flow then
            logger.warn(
              s"TaskStuckWatcher: root agent ${rec.sessionId} (kind=${rec.kind}) stuck in Processing for ${idleSecs}s " +
                s"[judge: $reason] — not auto-restarting, broadcast taskStuck for user decision"
            ) *>
              classifyNote *>
              recordFire("attention", 0) *>
              act(broadcastStuck(wsHub, rec, idleSecs, "attention", reason))
          else
            stopCounts.modify { m =>
              val n = m.getOrElse(rec.sessionId, 0) + 1
              (m.updated(rec.sessionId, n), n)
            }.flatMap { attempts =>
              val (action, io) = attempts match
                case 1 =>
                  ("halt",
                    logger.warn(
                      s"TaskStuckWatcher: root agent ${rec.sessionId} stuck for ${idleSecs}s — L1: halting in-flight LLM"
                    ) *> nebflow.llm.LlmInterface.cancelInflightFor(rec.sessionId).void
                      .handleErrorWith(e => logger.warn(s"TaskStuckWatcher: L1 halt for ${rec.sessionId} failed: ${e.getMessage}")))
                case 2 =>
                  ("hard-abort",
                    logger.warn(
                      s"TaskStuckWatcher: root agent ${rec.sessionId} still stuck — L2: transport abort + session process reclaim"
                    ) *> nebflow.llm.LlmInterface.transportAbortFor(rec.sessionId).flatMap { n =>
                      logger.warn(s"TaskStuckWatcher: L2 aborted $n in-flight LLM request(s) of ${rec.sessionId}") *>
                        // 证据 #5 ① 护栏（同 flow transportAbort）：仅 LLM 楔死形态
                        // （n>0）才 reclaimSession 进程 kill；工具相位（容器 CPU 盲区）
                        // 收回，升级链走 tier-3。
                        // P1 判据序闸门（同 flow 分支）：进程 kill 只在
                        // `destructiveAllowed` 类上执行。
                        (if n > 0 && cls.destructiveAllowed then
                           nebflow.core.tools.BgTaskRegistry.reclaimSession(Some(rec.sessionId), wsHub.broadcast, rec.rootSessionId)
                             .handleErrorWith(e => logger.warn(s"TaskStuckWatcher: L2 reclaim for ${rec.sessionId} failed: ${e.getMessage}"))
                         else
                           logger.warn(
                             s"TaskStuckWatcher: root agent ${rec.sessionId} L2 — no in-flight LLM request (tool-phase); " +
                               s"process kill withheld [evidence #5 container-blindness guard; class=${cls.cls} " +
                               s"destructiveAllowed=${cls.destructiveAllowed}]"))
                    }.handleErrorWith(e => logger.warn(s"TaskStuckWatcher: L2 abort for ${rec.sessionId} failed: ${e.getMessage}")))
                case 3 =>
                  ("restart",
                    logger.warn(
                      s"TaskStuckWatcher: root agent ${rec.sessionId} still stuck — L3: full actor restart " +
                        "(transcript reload from disk, queued injections preserved)"
                    ) *> (rec.ref ! nebflow.agent.AgentCommand.RestartAgent(nebflow.agent.RestartLevel.Full))
                      .handleErrorWith(e => logger.warn(s"TaskStuckWatcher: L3 restart for ${rec.sessionId} failed: ${e.getMessage}"))
                      *> stopCounts.update(_ - rec.sessionId))
                case 4 =>
                  ("failed",
                    logger.error(
                      s"TaskStuckWatcher: root agent ${rec.sessionId} stuck for ${idleSecs}s — L1/L2/L3 all ineffective. " +
                        "This session needs MANUAL attention (progress persisted on disk: transcript + injection queues)."
                    ))
                case _ =>
                  ("failed", IO.unit)
              classifyNote *>
                recordFire(s"L${math.min(attempts, 4)}", attempts) *>
                act(broadcastStuck(wsHub, rec, idleSecs, action, reason)) *> act(io)
            }

    end if

  /** R8 方向①（看门狗自身监测，设计 §2.3）：一次开火的**无条件**结构化留痕。
    *
    * 字段 = 设计 §2.3 建议集：`ts` / `type` / `sessionId` / `kind` / `branch` /
    * `toolPhaseMs` / `agentIdleMs` / `toolName` / `level` / `attempt` /
    * `hardRecoveryEnabled`（+ `shadow`：标定窗口内让每行自解释，本批新增字段）。
    *
    *   - `attempt` = `stopCounts` 当轮值——**内存态，宿主重启归零**（设计 §1.2-F2）：
    *     如实记录、不得当作连续计数消费。
    *   - `branch` ∈ [[BranchMerged]] / [[BranchAgentStale]] / [[BranchToolOverdue]]
    *     （由 [[assessDetailed]] 单点产出）。
    *   - **不写**进程 CPU / `processActivityMs` / 任何 `assess` 未用的信号
    *     （红线 R6-4）；**不嵌** progress 快照（v2 再议）。
    *   - best-effort：写失败只 WARN（[[WatchdogEventLog.append]] 内兜底）。 */
  private def recordStuckFire(
    rec: AgentRecord,
    a: StuckAssessment,
    level: String,
    attempt: Int,
    hard: Boolean,
    shadow: Boolean
  ): IO[Unit] =
    WatchdogEventLog.append(io.circe.Json.obj(
      "ts" -> io.circe.Json.fromLong(System.currentTimeMillis()),
      "type" -> io.circe.Json.fromString(WatchdogEventLog.StuckFireType),
      "sessionId" -> io.circe.Json.fromString(rec.sessionId),
      "kind" -> io.circe.Json.fromString(rec.kind.toString),
      "branch" -> io.circe.Json.fromString(a.branch),
      "toolPhaseMs" -> io.circe.Json.fromLong(a.toolPhaseMs),
      "agentIdleMs" -> io.circe.Json.fromLong(a.agentIdleMs),
      "toolName" -> a.toolName.fold(io.circe.Json.Null)(io.circe.Json.fromString),
      "level" -> io.circe.Json.fromString(level),
      "attempt" -> io.circe.Json.fromInt(attempt),
      "hardRecoveryEnabled" -> io.circe.Json.fromBoolean(hard),
      "shadow" -> io.circe.Json.fromBoolean(shadow)
    ))

  /** 判据序（P1）的事件类型：**每次判定命中的类别**（含「本拍不动作」的类②/④）。
    *
    * 与 R8 的 `stuck-fire`（[[WatchdogEventLog.StuckFireType]]，只在**动作开火**时写）
    * 严格区分——本类型描述的是「检出面」而不是「开火面」（设计 §3.5 的两行表：
    * 「检出 + 根因分类 → 事件流 `stuck-detected`」/「恢复尝试 → `stuck-recovery-attempt`」）。
    * 混用会让「今天 11/11 转 suspect」这类**零动作**事实在审计面被读成开火。
    *
    * 事件类型词表由**生产者**持有（本对象），与 R8 的 `WatchdogEventLog` 常量并存——
    * 避免为一个新类型改动 R8 已落地的审计模块（本批文件面纪律）。
    */
  val StuckDetectedType: String = "stuck-detected"

  /** 判据序的分流留痕（**无条件**，含类②/④ 的零动作拍）：一行结构化事件，字段 =
    * 类别 + 判据读数 + 分支身份，与 R8 的 `stuck-fire` 同面可按 (sessionId, ts) join。
    *
    * **不写**进程 CPU / `processActivityMs`（红线 R6-4）；正信号只以**布尔方向**
    * （是否阻止了本拍判死）出现，不把「行数/CPU 增量」原文写进事件面（设计 §2.2-C
    * 的「事件内不嵌 progress 快照」纪律，v2 再议）。
    * best-effort：写失败只 WARN（[[WatchdogEventLog.append]] 内兜底）。 */
  private def recordClassification(
    rec: AgentRecord,
    a: StuckAssessment,
    cls: StuckClassification
  ): IO[Unit] =
    WatchdogEventLog.append(io.circe.Json.obj(
      "ts" -> io.circe.Json.fromLong(System.currentTimeMillis()),
      "type" -> io.circe.Json.fromString(StuckDetectedType),
      "sessionId" -> io.circe.Json.fromString(rec.sessionId),
      "kind" -> io.circe.Json.fromString(rec.kind.toString),
      "branch" -> io.circe.Json.fromString(a.branch),
      "class" -> io.circe.Json.fromString(cls.cls),
      "toolPhaseMs" -> io.circe.Json.fromLong(a.toolPhaseMs),
      "agentIdleMs" -> io.circe.Json.fromLong(a.agentIdleMs),
      "toolName" -> a.toolName.fold(io.circe.Json.Null)(io.circe.Json.fromString),
      "recoverable" -> io.circe.Json.fromBoolean(cls.recoverable),
      "destructiveAllowed" -> io.circe.Json.fromBoolean(cls.destructiveAllowed),
      "note" -> io.circe.Json.fromString(cls.note)
    ))

  // ── R8 方向②：L3 有效性自检（T+N 行为面复查）────────────────────────────

  /** 复查到期登记（到期的取出，未到期的留待下一轮）；再逐条裁决。 */
  private def verifyL3Outcomes(
    resources: SharedResources,
    pendingL3: cats.effect.Ref[IO, List[PendingL3]],
    now: Long,
    l3VerifyDelayMs: Long
  ): IO[Unit] =
    pendingL3
      .modify { ps =>
        val (due, rest) = ps.partition(p => now - p.firedAt >= l3VerifyDelayMs)
        (rest, due)
      }
      .flatMap(_.traverse_(checkL3Outcome(resources, _, l3VerifyDelayMs)))
      .handleErrorWith(e =>
        logger.warn(s"TaskStuckWatcher: L3 outcome self-check failed (audit-only): ${Option(e.getMessage).getOrElse(e.toString)}"))

  /** 单条 L3 开火的 T+N 裁决（设计 §3.3 判据，**行为面**——不看任何日志）：
    *
    *   - `(ii)` 节点状态仍是 `Cancelled` ⇒ **失效**（本次事故主判据：14/14 命中）；
    *   - `(i)` 节点未复活（非 Pending/Running/Completed）而会话仍在 `Processing`
    *     ⇒ 失效（「桥 Cancelled 本身没落地」形态）；
    *   - `(iii)` 节点已被 resume 复活（Pending/Running/Completed）⇒ 生效（设计
    *     §3.6 第 3 行：只要求「出现过 Pending/Running」，不要求稳态）；
    *   - 节点查无（已归档/从未绑定）而会话也不在 Processing ⇒ 无从判失效（保守不告警）。
    *
    * **只告警不动作**（设计 §3.6 末）：写 ① `l3-ineffective` 事件 + `logger.error`；
    * 不重试、不改判、不 kill；**一期不回流分发器**（`NotifyReason` 无 stuck 类值，
    * 与 R7 taxonomy 一起做——任务书 ③ 口径）。 */
  private def checkL3Outcome(resources: SharedResources, p: PendingL3, l3VerifyDelayMs: Long): IO[Unit] =
    nebflow.core.project.ProjectRuntimeRegistry.all.flatMap { rts =>
      rts.find(rt => rt.engine.rootSessionId.nonEmpty && rt.engine.rootSessionId == p.rootSessionId) match
        case None =>
          logger.warn(
            s"TaskStuckWatcher: L3 self-check skipped for ${p.sessionId} — no project runtime (root=${p.rootSessionId}); " +
              "verdict not derivable (restart/unmount tolerated by design §3.8)")
        case Some(rt) =>
          for
            snap <- rt.store.snapshot
            reg <- resources.agentRegistry.get
            node = snap.nodes.values.find(n =>
              n.sessionRef.contains(p.sessionId) || n.sessionRefVerify.contains(p.sessionId))
            recordProcessing = reg.get(p.sessionId).exists(_.status == AgentStatus.Processing)
            verdict = node match
              case Some(n) if n.status == nebflow.core.project.NodeLifecycle.Cancelled =>
                Some(("node-still-cancelled", n.id))
              case Some(n) if LiveForL3Check.contains(n.status) => None
              case Some(n) if recordProcessing => Some(("session-still-processing", n.id))
              case Some(n) => None
              case None if recordProcessing => Some(("session-still-processing", ""))
              case None => None
            _ <- verdict match
              case Some((evidence, nodeId)) =>
                logger.error(
                  s"TaskStuckWatcher: L3 INEFFECTIVE for ${p.sessionId} (evidence=$evidence, node=${if nodeId.isEmpty then "-" else nodeId}) — " +
                    s"fired at ${p.firedAt} (level=L3, branch=${p.branch}); no state migration within +${l3VerifyDelayMs / 1000}s " +
                    "⇒ the L3 leg is not working (alert only, no action by design §3.6)"
                ) *> WatchdogEventLog.append(io.circe.Json.obj(
                  "ts" -> io.circe.Json.fromLong(System.currentTimeMillis()),
                  "type" -> io.circe.Json.fromString(WatchdogEventLog.L3IneffectiveType),
                  "sessionId" -> io.circe.Json.fromString(p.sessionId),
                  "rootSessionId" -> io.circe.Json.fromString(p.rootSessionId),
                  "nodeId" -> io.circe.Json.fromString(nodeId),
                  "branch" -> io.circe.Json.fromString(p.branch),
                  "toolPhaseMs" -> io.circe.Json.fromLong(p.toolPhaseMs),
                  "agentIdleMs" -> io.circe.Json.fromLong(p.agentIdleMs),
                  "level" -> io.circe.Json.fromString("L3"),
                  "attempt" -> io.circe.Json.fromInt(p.attempt),
                  "firedAt" -> io.circe.Json.fromLong(p.firedAt),
                  "verifiedAt" -> io.circe.Json.fromLong(System.currentTimeMillis()),
                  "evidence" -> io.circe.Json.fromString(evidence)
                ))
              case None => IO.unit
          yield ()
    }.handleErrorWith(e =>
      logger.warn(s"TaskStuckWatcher: L3 self-check for ${p.sessionId} failed: ${Option(e.getMessage).getOrElse(e.toString)}"))

  /** L3 复查视为「resume 已生效」的节点状态集（设计 §3.3(iii)：只要求出现过
    * Pending/Running；Completed 一并放行——带 resume 的节点跑到完成同样证明该腿有效）。 */
  private val LiveForL3Check: Set[String] = Set(
    nebflow.core.project.NodeLifecycle.Pending,
    nebflow.core.project.NodeLifecycle.Running,
    nebflow.core.project.NodeLifecycle.Completed
  )

  /** L3 for Flow 节点：定位 session 所属项目的 runtime 并 hardResumeNode（跨项目
    * 按 rootSessionId 匹配 AgentRecord.rootSessionId = 项目 root 会话）。
    * R5 留痕契约：返回 `Some(nodeId)` = resume 真生效（节点已由 Cancelled/Running
    * 翻回 Pending 并从 transcript 续跑）；`None` = 未生效（无 runtime / 无
    * transcript / CAS 被拒 / 异常）——各失败出口在 NodeEngine.hardResumeNode 内
    * 均有 WARN/ERROR 留痕（R5 补），调用方据 None 走 [[hardRecoveryFallback]]。 */
  private def hardResumeFlowNode(resources: SharedResources, rec: AgentRecord): IO[Option[String]] =
    nebflow.core.project.ProjectRuntimeRegistry.all
      .flatMap { rts =>
        rts.find(rt => rt.engine.rootSessionId.nonEmpty && rt.engine.rootSessionId == rec.rootSessionId) match
          case Some(rt) =>
            rt.engine.hardResumeNode(rec.sessionId)
              .handleErrorWith(e =>
                logger.error(
                  s"TaskStuckWatcher: hard-resume failed for ${rec.sessionId}: ${Option(e.getMessage).getOrElse(e.toString)}"
                ).as(None))
          case None =>
            logger.warn(
              s"TaskStuckWatcher: no project runtime for ${rec.sessionId} (root=${rec.rootSessionId}) — L3 resume unavailable"
            ).as(None)
      }
      .handleErrorWith(e =>
        logger.error(s"TaskStuckWatcher: project runtime lookup failed: ${Option(e.getMessage).getOrElse(e.toString)}")
          .as(None))

  /** R5 失败分支（取消静默死锁修复批 2026-09-10）：L3 resume 未生效 ⇒ 引擎侧把节点
    * **改判 failed**（R5 方案 4，见 `NodeEngine.settleFailedHardResume`：failNode 全链
    * = result 含原因 / nodeUpdated / failed 回流 / D5 停等留痕），本方法只是「找 runtime
    * → 转交引擎」的定位层：
    *   - 找得到 runtime → [[NodeEngine.settleFailedHardResume]]（唯一终局写点；节点
    *     **不摘除、不打 pendingSuccession**——failed 侧零标的 D5 纪律）。
    *   - 找不到 runtime（跨项目/未挂载）→ 响亮 ERROR（诚实失败；节点仍 cancelled，
    *     可见性由事件流 `cancelled` + 周期 `mount-stalled` 兜底）——此出口**无法**改判
    *     （没有引擎实例可写节点），是本方法的已知残口，不在本批修复面内。 */
  private def hardRecoveryFallback(resources: SharedResources, rec: AgentRecord): IO[Unit] =
    nebflow.core.project.ProjectRuntimeRegistry.all
      .flatMap { rts =>
        rts.find(rt => rt.engine.rootSessionId.nonEmpty && rt.engine.rootSessionId == rec.rootSessionId) match
          case Some(rt) => rt.engine.settleFailedHardResume(rec.sessionId)
          case None =>
            logger.error(
              s"TaskStuckWatcher: L3 resume failed and no project runtime for ${rec.sessionId} " +
                s"(root=${rec.rootSessionId}) — node left cancelled without the failed re-judge / dispatcher notify " +
                "(manual intervention required)"
            )
      }
      .handleErrorWith(e =>
        logger.error(
          s"TaskStuckWatcher: L3 fallback (failed re-judge) failed for ${rec.sessionId}: ${Option(e.getMessage).getOrElse(e.toString)}"
        ))

end TaskStuckWatcher

/**
 * R6 判据单点（取消静默死锁修复批 2026-09-10，作者裁定 R6 方案 2）：工具相位轴的
 * **有效阈值**计算。抽成独立纯函数 = 可独立单测边界（声明/未声明/极小声明/极大声明），
 * 且 `assess` 与任何将来消费者共用同一口径（防判据漂移，与 `assess` 唯一事实源同源）。
 *
 *   effective = if declaredMs > 0 then max(defaultMs, declaredMs + slackMs) else defaultMs
 *
 * 语义（红线）：只放宽、不放严。声明时长只参与**放宽**（案例 1 的 `timeout=900000ms`
 * → 阈值 960s），声明 + 宽限小于默认档（极小声明）时仍取默认档——命令自己声明的小
 * 授权不得把 10min 安全网缩到其下（那一档的判死由工具自身的授权超时承担，红线
 * R6-3：本函数不改 Bash 工具授权语义）。
 * **不读取任何进程 CPU**（红线 R6-4）。
 */
object ToolStuckJudgment:
  /** 有效工具相位阈值。
    *
    * ⚠ **实现口径与设计文档逐字公式的差异（已裁定，2026-09-10）**：设计 §6-R6
    * 方案 2 的公式逐字写作 `min(ToolPhaseStuckMs, deadline + slack)`，但该公式与
    * 同一裁定项的两条验收口径自相矛盾：`min` 在 deadline > ToolPhaseStuckMs 时退化
    * 为 ToolPhaseStuckMs，**案例 1（`timeout=900000ms`，11.2min 被判死）照旧被误杀**
    * —— 而方案 2 的立论原文就是「尊重命令自己声明的合法时长」，验收口径亦明写
    * 「命令自带大 timeout 且持续推进 → 改造后不判」。**已裁定（2026-09-10）：以
    * `max` 为准**（有效阈值 = **max(默认档, 声明 + 宽限)**：声明时长只可**放宽**
    * 判据、不可收紧到默认档之下；未声明 ⇒ 默认档，仍 10min 判死）。设计文档
    * §6-R6 已加同源订正注记（正文照旧保留）。
    *
    * **不读取任何进程 CPU**（红线 R6-4）。 */
  def effectiveToolPhaseMs(defaultMs: Long, declaredMs: Long, slackMs: Long): Long =
    if declaredMs > 0 then math.max(defaultMs, declaredMs + math.max(0L, slackMs))
    else defaultMs
