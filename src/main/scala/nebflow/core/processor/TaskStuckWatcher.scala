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

  /** LoopGuard L2 冻结在 `AgentRecord.frozenReason` 里的 wire 值（P3 互斥点 1 的判据）。
    * 单源 = `AgentRecord.toJson` 的 reason 映射（`protocol.scala`：`FreezeReason.Loop => "loop"`；
    * `AgentActor.reasonStr` 同映射）——此处只做**只读**匹配，不改枚举、不改序列化。 */
  val LoopFreezeReasonWire: String = "loop"

  /** 与 WebSocketRoutes.filterActiveAgents 相同的 task 集合（保持单一事实源意识）。 */
  private val taskKinds = Set(AgentKind.Delegate, AgentKind.Ephemeral, AgentKind.Flow, AgentKind.SubTask)

  /**
   * 2026-09-10 卡死判据换轴（唯一判据事实源）：本 watcher 的扫描 + AgentControlTool
   * 的 stuck?/status 展示走此函数，避免两处判据漂移（旧代码两处各写一遍
   * `now - lastActivityMs > 阈值`）。
   *
   * **口径如实（wd-fix 批 2026-09-12 实读订正，本处旧文为误述）**：
   * `WebSocketRoutes.maybeSessionKick` / `#isKickCandidate` **不调用**本函数——它是
   * 同一组不等式的**第二份内联副本**，且**阈值不同**（`Defaults.SessionKickIdleSec`
   * = 150s，本函数默认 `StuckThresholdMs` = 600s）并自带 `toolInFlight` 护栏。
   * ⇒ 判据源实为 **2 同源（watcher 扫描 + AgentControlTool）+ 1 副本（阈值不同、未复用）**。
   * 副本改走 `assess`/`classify` 属**行为变更**，不在 wd-fix 批范围内（建议另立条目）。
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
  /** 类② 假阳性：**有正信号**（还在推进）⇒ 本拍不动作。**与「是否已超授权」解耦**
    * （wd-fix 批第 i 刀 2026-09-12）：超授权不再前置否决正信号——处置权交回工具自身
    * 授权超时 / 前台 no-progress ceiling。 */
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
    * `AgentCore.markToolProgress`，传感器 = 工具活动桥采样点）。
    *
    * `windowMs` 是**窗的给定值**（纯函数，不做任何授权推导）；需要「窗随授权联动」的
    * 调用方先算 [[effectiveProgressWindowMs]]（[[classify]] 即如此）。 */
  def hasProgressSignal(
    rec: AgentRecord,
    now: Long,
    windowMs: Long = nebflow.shared.Defaults.StuckProgressSignalWindowMs
  ): Boolean =
    rec.lastProgressSignalAt > 0 && now - rec.lastProgressSignalAt <= math.max(0L, windowMs)

  /** 正信号新鲜窗的**授权联动**（wd-fix 批 ②，2026-09-12 作者裁定方向 A 第 ii 刀）——
    * 有效窗 = `max(baseWindowMs, declaredToolTimeoutMs / Defaults.StuckProgressSignalWindowDivisor)`
    * （默认 `max(60s, 声明/10)`）。
    *
    * 性质（验收口径）：**单调不减**（声明时长越大 ⇒ 窗不得越小；禁出现「授权越长反而
    * 越容易被判死」）；**未声明**（`<= 0`）⇒ `baseWindowMs` 逐字不变（未声明 `timeout`
    * 的工具行为零变化）。取数口唯一 = `AgentRecord.currentToolDeadlineMs`
    * （由 `Defaults.declaredToolTimeoutMs` 在工具入参上读出，**不另开取数口**）。
    */
  def effectiveProgressWindowMs(
    declaredToolTimeoutMs: Long,
    baseWindowMs: Long = nebflow.shared.Defaults.StuckProgressSignalWindowMs
  ): Long =
    val base = math.max(0L, baseWindowMs)
    val divisor = nebflow.shared.Defaults.StuckProgressSignalWindowDivisor
    if declaredToolTimeoutMs <= 0L || divisor <= 0L then base
    else math.max(base, declaredToolTimeoutMs / divisor)

  /** 判据序（设计 §2.1）的**唯一实现单点**——纯函数，可独立单测；扫描面与任何
    * 将来消费者共用同一口径（防分流漂移，与 [[assessDetailed]] 唯一事实源同源）。
    *
    * 判定顺序（严格自上而下，先命中先返回）：
    *   1. `inflight > 0` ⇒ 类④ [[ClassProviderHang]]（LLM 层自管，watcher 零动作）
    *   2. 有工具相位（`currentToolStartedAt > 0`）：
    *      a. **有正信号（有效窗内新鲜）** ⇒ 类② [[ClassFalsePositive]]
    *         （本拍不动作，只记 `suspect`）
    *      b. **无正信号** ⇒ 类① [[ClassTrueStuck]]（**非破坏档**：只允许 L1 + 恢复腿）
    *   3. 无工具相位 ⇒ 类① [[ClassTrueStuck]]
    *
    * **wd-fix 批（2026-09-12 作者裁定「方向 A」，只为消误判、不为减真判）**：
    *   - **第 i 刀（去掉前置）**：2a 旧写 `toolPhaseMs ≤ 有效阈值 ∧ 正信号新鲜`——
    *     `toolPhaseMs > 有效阈值` 时正信号**再新鲜也救不了**，直接落 2b。改为
    *     「正信号新鲜 ⇒ 2a，**无论工具耗时是否已超授权**」：超授权的处置权交回工具
    *     自身（工具授权超时 / 前台 no-progress ceiling），watcher 不再越权判死
    *     ——这是作者 2026-08-30 既有语义（判死权归工具授权 + ceiling）。
    *     今天 5 例误判（`node-eb8a9c70` 工具相位 604810ms / 正信号 3s 前等）全部
    *     由这条前置造成。
    *   - **第 ii 刀（窗随授权联动）**：2a 的正信号窗不再恒为 60s，改走
    *     [[effectiveProgressWindowMs]]（= `max(基础窗, 声明授权/10)`，单调不减）。
    *   - **零新信号**：不读 `processActivityMs` / 进程树 CPU / 活体探针（红线 R6-4）。
    *   - **真判不减少**：`无正信号 ∧ 确实停摆` 的形态判据与动作链**逐字未动**——
    *     类① 仍 `recoverable=true`、`destructiveAllowed=false`，L1→L3 分级与恢复腿
    *     闸门不受本批影响。
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
    // wd-fix ②（2026-09-12）：正信号窗随授权联动（未声明 ⇒ progressWindowMs 逐字不变）。
    val progressWindow = effectiveProgressWindowMs(rec.currentToolDeadlineMs, progressWindowMs)
    val progress = hasProgressSignal(rec, now, progressWindow)
    val progressAgoSecs = if rec.lastProgressSignalAt > 0 then (now - rec.lastProgressSignalAt) / 1000 else -1L
    // 机器可读读数（本批离线回归器与事件面按 note 取数；`authorised` 与
    // `progress signal <n>s ago` 两个 token 的形态**必须保留**）。
    val windowNote = s"window ${progressWindow / 1000}s"
    val authorisedNote = s"authorised ${effectiveToolPhaseMs / 1000}s"
    val toolNote = s"tool phase ${a.toolPhaseMs / 1000}s"
    val overAuthorised = a.toolPhaseMs > effectiveToolPhaseMs
    if inflight > 0 then
      StuckClassification(ClassProviderHang,
        s"$inflight in-flight LLM request(s) for this session — provider-hang (class 4): the LLM layer's own " +
          "watchdog owns this case, the watcher takes no action this round",
        recoverable = false, destructiveAllowed = false)
    else if rec.currentToolStartedAt > 0 then
      // wd-fix ①：正信号新鲜 ⇒ 类②，**不再前置要求 `toolPhaseMs ≤ 有效阈值`**。
      if progress then
        StuckClassification(ClassFalsePositive,
          s"tool '${a.toolName.getOrElse("?")}' " +
            (if overAuthorised then
               s"over its authorised window ($toolNote > $authorisedNote) but still advancing"
             else s"inside its authorised window ($toolNote ≤ $authorisedNote) and advancing") +
            s" (progress signal ${progressAgoSecs}s ago, $windowNote) — false positive (class 2): " +
            (if overAuthorised then
               "over-authorisation is the tool's own authorised timeout / foreground no-progress ceiling " +
                 "to enforce, the watcher takes no action this round"
             else "no action this round"),
          recoverable = false, destructiveAllowed = false)
      else
        // 文案如实（wd-fix ③）：本分支按定义**无新鲜正信号**（progress == false），故不会
        // 出现「既说没有正信号、又给出 3s 前正信号」的自相矛盾；超授权事实单列。
        StuckClassification(ClassTrueStuck,
          (if progressAgoSecs < 0 then s"no progress signal (never seen, $windowNote)"
           else s"no fresh progress signal (progress signal ${progressAgoSecs}s ago, $windowNote)") +
            s" while tool '${a.toolName.getOrElse("?")}' is in flight " +
            s"($toolNote, $authorisedNote)" +
            (if overAuthorised then s" — $toolNote > $authorisedNote (over its authorised window)" else "") +
            s" — true stuck (class 1): " +
            "non-destructive tier only (L1 halt + recovery leg; no process kill, no terminalization)",
          recoverable = true, destructiveAllowed = false)
    else
      StuckClassification(ClassTrueStuck,
        s"no tool phase in flight, no in-flight LLM request, no progress signal — " +
          "true stuck (class 1): non-destructive tier only (L1 halt + recovery leg)",
        recoverable = true, destructiveAllowed = false)

  /** [[assess]] 的详情版（唯一判据事实源）：返回命中的**分支身份**与两条轴的原始终
    * 读数，供 R8 事件面消费。`assess` 退化为它的投影——**既有两消费点
    * （watcher 扫描 / `AgentControlTool` 展示）签名与行为零改动**。
    *
    * **口径如实（wd-fix 批 2026-09-12 实读订正）**：`WebSocketRoutes.maybeSessionKick`
    * 的 kick 判据**不是**本函数的消费点——它是内联的第二份副本
    * （`#isKickCandidate`，阈值 `Defaults.SessionKickIdleSec` 150s ≠ 本函数默认 600s，
    * 且额外带 `toolInFlight` 护栏），故不存在「三消费点」同源关系。 */
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
        // P3（§3.3/§3.4）：恢复账本（跨轮累积；内存态，同族纪律）。
        ledger <- cats.effect.Ref.of[IO, Map[String, RecoveryLedger]](Map.empty)
        _ <- scanLoop(stopCounts, pendingL3, ledger)
      yield ()

    def scanLoop(
      stopCounts: cats.effect.Ref[IO, Map[String, Int]],
      pendingL3: cats.effect.Ref[IO, List[PendingL3]],
      ledger: cats.effect.Ref[IO, Map[String, RecoveryLedger]]
    ): IO[Unit] =
      scan(resources, wsHub, thresholdMs, stopCounts, pendingL3, ledger = ledger).handleErrorWith(e =>
        logger.warn(s"TaskStuckWatcher scan failed (will retry next cycle): ${e.getMessage}")
      ) *> IO.sleep(interval) >> scanLoop(stopCounts, pendingL3, ledger)
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

  // ── P3（§3.3/§3.4）：恢复预算 / 退避 / 冷却账本 + 判定序闸门 ────────────────

  /** 单会话的**恢复账本**（P3，进程内内存态——宿主重启即丢，与 `stopCounts` /
    * `pendingL3` 同族；设计明确接受，**不得**当作持久状态消费）。 */
  private[processor] final case class RecoveryLedger(
    /** 本代次（本次卡死 episode）已消耗的恢复次数；会话离开 stuck 候选集的扫描轮复位。 */
    genAttempts: Int = 0,
    /** 全链（跨代次）已消耗的恢复次数；只随会话消失复位（`Defaults.StuckRecoveryMaxPerChain`）。 */
    chainAttempts: Int = 0,
    /** 上次**成功**恢复的时刻（冷却窗基点；0 = 从未成功恢复过）。 */
    lastRecoveryAt: Long = 0L,
    /** 上次恢复**尝试**的时刻（退避基点；0 = 从未尝试）。 */
    lastAttemptAt: Long = 0L,
    /** 恢复当时的 LoopGuard 跨轮命中计数快照（互斥点 2 的基线）。 */
    strikeBaseline: Int = 0,
    /** 恢复当时的指纹快照（同上；判「新指纹命中」用）。 */
    fpBaseline: String = "",
    /** 互斥点 2 已命中 ⇒ 恢复链停止，不再消耗预算（进程生命周期内）。 */
    chainHalted: Boolean = false,
    /** 本会话已发过「一次上报」的时刻（0 = 未发）。结构性地保证「**恰好一次**上报」——
      * 不依赖 `failStuckRecovery` 内部的幂等（那是第二道防线，不是唯一防线）。 */
    reportedAt: Long = 0L
  )

  /** §3.4 判定序的闸门结果。`report` = 是否走「一次上报」（第 2/4 步与互斥点 2 为 true；
    * 第 3 步冷却为 false —— 只留痕，不误报）。 */
  private[processor] final case class RecoveryGate(kind: String, report: Boolean, note: String)

  /** 闸门类型词表（进事件面 `gate` 字段）。 */
  val GateLoopFrozen: String = "loop-frozen"
  val GateRecoveryLoopDetected: String = "recovery-loop-detected"
  val GateCooldown: String = "cooldown"
  val GateBackoff: String = "backoff"
  val GateBudgetExhausted: String = "budget-exhausted"

  /** 互斥点 2 的识别（§3.4；纯函数，可独立单测）：恢复**成功**后
    * [[nebflow.shared.Defaults.StuckRecoveryLoopDetectMs]] 窗内 LoopGuard 跨轮命中
    * 计数**上升**（或指纹变化 / 进入 Loop 冻结）⇒ 「反复卡 ⇒ 反复重试」识别点：
    * 立即停恢复链 + 一次上报（不再消耗预算）。
    *
    * 数据源 = P1 落地的**只读投影** `AgentRecord.loopStrikeCount` / `lastLoopFp`
    * （硬依赖 2 的可见性缺口补齐；`AgentState.loopCounters` 在 actor 内不可读）——
    * 两字段**不回流任何判死不等式**（红线：只作为「停恢复链」的互斥信号）。 */
  def loopDetectedAfterRecovery(rec: AgentRecord, led: RecoveryLedger, now: Long): Boolean =
    led.lastRecoveryAt > 0L &&
      (now - led.lastRecoveryAt) <= nebflow.shared.Defaults.StuckRecoveryLoopDetectMs &&
      (rec.loopStrikeCount > led.strikeBaseline ||
        (rec.lastLoopFp.nonEmpty && rec.lastLoopFp != led.fpBaseline) ||
        rec.frozenReason.contains(LoopFreezeReasonWire))

  /** §3.4 判定序的第 2/3/4 步（纯函数，可独立单测）：返回 `Some(gate)` = 本拍
    * **零动作**（唯一例外是 `gate.report` 的「一次上报」）。
    *
    * 顺序（强制，与设计 §3.4 逐字一致）= 互斥点 1（LoopGuard 冻结）→ 互斥点 2
    * （恢复链已停）→ 冷却窗 → 全链预算耗尽；末位补 **退避窗**（§3.3 退避曲线的唯一
    * 判据消费点，负控④「退避生效」要求它可观测）。*/
  def recoveryGate(rec: AgentRecord, led: RecoveryLedger, now: Long): Option[RecoveryGate] =
    if rec.status == AgentStatus.Frozen || rec.frozenReason.contains(LoopFreezeReasonWire) then
      Some(RecoveryGate(GateLoopFrozen, true,
        "会话带 LoopGuard 冻结记录 ⇒ 互斥点 1：零恢复动作（冻结的恢复条件仅人工：用户输入唤醒 / " +
          "AgentControl restart / cancel；365 天冻结不得被自动恢复绕过）"))
    else if led.chainHalted then
      Some(RecoveryGate(GateRecoveryLoopDetected, true,
        "互斥点 2 已命中（恢复后循环指纹再次命中）—— 恢复链已停，不再消耗预算"))
    else if led.lastRecoveryAt > 0L &&
      (now - led.lastRecoveryAt) < nebflow.shared.Defaults.StuckRecoveryCooldownMs then
      val left = (nebflow.shared.Defaults.StuckRecoveryCooldownMs - (now - led.lastRecoveryAt)) / 1000L
      Some(RecoveryGate(GateCooldown, false,
        s"恢复后冷却窗内（默认 ${nebflow.shared.Defaults.StuckRecoveryCooldownMs / 60000}min，剩余约 ${left}s）" +
          "—— 零动作、只留痕（防「恢复后立刻又被同轴判死」的自激振荡）"))
    else if led.chainAttempts >= nebflow.shared.Defaults.StuckRecoveryMaxPerChain then
      Some(RecoveryGate(GateBudgetExhausted, true,
        s"全链恢复预算耗尽（${led.chainAttempts}/${nebflow.shared.Defaults.StuckRecoveryMaxPerChain}）" +
          "—— 零动作 + 一次上报"))
    else if led.lastAttemptAt > 0L &&
      (now - led.lastAttemptAt) < nebflow.shared.Defaults.stuckRecoveryBackoffMs(led.chainAttempts + 1) then
      // 退避曲线（§3.3 第 3 行）：两次恢复尝试之间至少间隔 `StuckRecoveryBackoffMs` 的第
      // n 档。设计 §3.4 的列表没单列这一步，但 §3.3 明令该曲线存在，且 N1 验收的
      // **负控④** 逐字要求「退避生效（首次尝试后 ≥30s 才可能再判）」⇒ 它是可判据的
      // 一步，必须落成闸门（否则该曲线只是没人读的死常量）。
      val need = nebflow.shared.Defaults.stuckRecoveryBackoffMs(led.chainAttempts + 1)
      Some(RecoveryGate(GateBackoff, false,
        s"恢复退避窗内（第 ${led.chainAttempts + 1} 档 = ${need / 1000}s，剩余约 ${(need - (now - led.lastAttemptAt)) / 1000}s）" +
          "—— 零动作、只留痕（防「恢复→再卡→再恢复」链）"))
    else None

  /** 单轮扫描：识别卡死 agent 并执行恢复动作。独立成函数便于单元测试。 */
  def scan(
    resources: SharedResources,
    wsHub: WsHub,
    thresholdMs: Long,
    stopCounts: cats.effect.Ref[IO, Map[String, Int]] = cats.effect.Ref.unsafe(Map.empty),
    /** R8 ②：L3 待复查登记（默认空 Ref = 单测一次驱动不留状态）。 */
    pendingL3: cats.effect.Ref[IO, List[PendingL3]] = cats.effect.Ref.unsafe(List.empty),
    /** R8 ②：T+N 的 N（测试注入缩短窗口；生产默认常量 120s，无 prop）。 */
    l3VerifyDelayMs: Long = nebflow.shared.Defaults.L3VerifyDelayMs,
    /** P3（§3.3/§3.4）：恢复账本（预算 / 退避 / 冷却 / 互斥点 2）。默认空 Ref = 单测
      * 一次驱动不留状态（生产由 [[run]] 的扫描循环持有同一个 Ref 跨轮累积）。 */
    ledger: cats.effect.Ref[IO, Map[String, RecoveryLedger]] = cats.effect.Ref.unsafe(Map.empty)
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
        // P3（§3.3）：**episode 边界** —— 离开 stuck 候选集的扫描轮复位「本代次恢复
        // 预算」（与 stopCounts 同点、同纪律）；全链预算 / 冷却窗 / 互斥点 2 状态
        // **保留**（它们的语义是跨代次的）。
        ledger.update(m =>
          m.view.map { case (k, l) => (k, if stuckIds.contains(k) then l else l.copy(genAttempts = 0)) }.toMap) *>
        stuck.traverse_ { (rec, assessment) =>
          // 判据序（P1）：先分流根因类别，再进恢复链。`inflightFor` 是**只读**在飞
          // 计数（设计 §6.1 未证项 6 的补齐）——此前判「本会话是否有在飞 LLM」只能
          // 靠破坏性的 cancelInflightFor 反推。stopAttempts 取当轮值（内存态）。
          nebflow.llm.LlmInterface.inflightFor(rec.sessionId).flatMap { inflight =>
            recover(resources, wsHub, rec, assessment,
              classify(rec, assessment, now, inflight),
              stopCounts, pendingL3, l3VerifyDelayMs, ledger)
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
  /** **§3.4 判定序闸门（P3，2026-09-11）**：`recover()` 入口最前，先查互斥点 1（LoopGuard
    * 冻结）/ 互斥点 2（恢复后循环指纹再命中）/ 冷却窗 / 全链预算，命中即**零动作**
    * （唯一例外 = `gate.report` 的「一次上报」，且由账本 `reportedAt` 保证**恰好一次**）。
    *
    * 默认空账本 ⇒ 首轮 / 既有测试逐字等价（闸门不改变任何既有路径的行为）；只有
    * 「本会话确实发生过恢复」之后闸门才可能发声——这正是设计要的「防自激」。
    *
    * 未命中 ⇒ 交 [[recoverUngated]]（既有全部分支，**零改动**）。 */
  private def recover(
    resources: SharedResources,
    wsHub: WsHub,
    rec: AgentRecord,
    assessment: StuckAssessment,
    cls: StuckClassification,
    stopCounts: cats.effect.Ref[IO, Map[String, Int]],
    pendingL3: cats.effect.Ref[IO, List[PendingL3]],
    l3VerifyDelayMs: Long,
    ledger: cats.effect.Ref[IO, Map[String, RecoveryLedger]]
  ): IO[Unit] =
    val now = System.currentTimeMillis()
    ledger.modify(m => (m, m.getOrElse(rec.sessionId, RecoveryLedger()))).flatMap { led0 =>
      // 互斥点 2 先判（它把账本置为 chainHalted，从而在 [[recoveryGate]] 里生效）。
      val detected = !led0.chainHalted && loopDetectedAfterRecovery(rec, led0, now)
      val led = if detected then led0.copy(chainHalted = true) else led0
      val announce = if detected then
        logger.error(
          s"TaskStuckWatcher: ${rec.sessionId} — recovery-loop-detected (design §3.4 mutex 2): the session hit " +
            s"the LoopGuard fingerprint again within ${nebflow.shared.Defaults.StuckRecoveryLoopDetectMs / 1000}s of the " +
            s"last recovery (strikes ${led0.strikeBaseline} → ${rec.loopStrikeCount}, fp '${led0.fpBaseline}' → " +
            s"'${rec.lastLoopFp}') — the recovery chain STOPS here (repeated stall ⇒ repeated retry is not a " +
            "recovery, it is a loop)."
        ) *> recordRecoveryLoopDetected(rec, assessment, cls, led) *>
          ledger.update(m => m.updated(rec.sessionId, led))
      else IO.unit
      announce *> (recoveryGate(rec, led, now) match
        case None =>
          recoverUngated(resources, wsHub, rec, assessment, cls, stopCounts, pendingL3, l3VerifyDelayMs, ledger)
        case Some(gate) =>
          gateActions(wsHub, rec, assessment, cls, led, gate, stopCounts, ledger))
    }

  /** §3.4 判定序命中时的**零动作**出口（唯一例外 = `gate.report` 时的「一次上报」）。
    *
    * 「零动作」口径逐字取自设计 §3.4：不 halt、不 abort、不 kill、不终态化、不启动
    * 恢复腿——本拍只留痕（`stuck-detected` 由 [[recoverUngated]] 之外的本路径补一条带
    * `gate` 的 `stuck-recovery-gated` 事件）。`gate.report=true` 的三种闸门（互斥点 1 /
    * 互斥点 2 / 预算耗尽）额外做**恰好一次**上报（[[reportExhausted]] →
    * `NodeEngine.failStuckRecovery` → `failNode` 全链）；冷却闸门**不**上报（会话可能
    * 自己缓过来，误报是噪声）。 */
  private def gateActions(
    wsHub: WsHub,
    rec: AgentRecord,
    assessment: StuckAssessment,
    cls: StuckClassification,
    led: RecoveryLedger,
    gate: RecoveryGate,
    stopCounts: cats.effect.Ref[IO, Map[String, Int]],
    ledger: cats.effect.Ref[IO, Map[String, RecoveryLedger]]
  ): IO[Unit] =
    // 「宽松档」的两件事（设计 §3.4 第 2 步逐字「只广播 + 一次上报」）：
    // 广播照发（面板可见），上报**恰好一次**（由账本 reportedAt 结构性保证——
    // 不依赖 failStuckRecovery 的内部幂等，那是第二道防线）。
    // 上报文本的「已尝试恢复动作清单」（N1 验收 正控③ 要求）从账本派生：闸门路径
    // 自身不动作，但**此前的**尝试记录在案（逐次 `stuck-recovery-attempt` 事件）。
    val tried: List[String] =
      if led.chainAttempts > 0 then
        List(s"suspend → hard-resume leg ×${led.chainAttempts} (each logged as a stuck-recovery-attempt event; " +
          s"last attempt at ${led.lastAttemptAt})")
      else Nil
    val reportPart: IO[Unit] =
      if !gate.report then IO.unit
      else
        broadcastStuck(wsHub, rec, assessment.secs, "failed", assessment.reason) *>
          (if led.reportedAt != 0L then
             logger.info(
               s"TaskStuckWatcher: ${rec.sessionId} gate ${gate.kind} — report already sent once, not re-sending (exactly-once)")
           else
             stopCounts.get.flatMap { counts =>
               ledger.update(m => m.updated(rec.sessionId, led.copy(reportedAt = System.currentTimeMillis()))) *>
                 reportExhausted(wsHub, rec, assessment, cls, counts.getOrElse(rec.sessionId, 0), tried,
                   s"gated: ${gate.kind} — ${gate.note}")
             })
    recordClassification(rec, assessment, cls) *>
      logger.warn(
        s"TaskStuckWatcher: ${rec.sessionId} hit the stall judge [branch=${assessment.branch}, judge: ${assessment.reason}] " +
          s"but the §3.4 judgement order gates it [gate=${gate.kind}; " +
          s"chain=${led.chainAttempts}/${nebflow.shared.Defaults.StuckRecoveryMaxPerChain}, " +
          s"gen=${led.genAttempts}/${nebflow.shared.Defaults.StuckRecoveryMaxPerGen}] — ZERO action this round. ${gate.note}"
      ) *>
        recordGate(rec, assessment, cls, led, gate) *>
        reportPart

  /** 判据序未命中闸门时的**既有全部分支**（P1/P2 行为逐字保留）。 */
  private def recoverUngated(
    resources: SharedResources,
    wsHub: WsHub,
    rec: AgentRecord,
    assessment: StuckAssessment,
    cls: StuckClassification,
    stopCounts: cats.effect.Ref[IO, Map[String, Int]],
    pendingL3: cats.effect.Ref[IO, List[PendingL3]],
    l3VerifyDelayMs: Long,
    ledger: cats.effect.Ref[IO, Map[String, RecoveryLedger]]
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
            //
            // **P3 §3.4 互斥点 1(b)**：会话级原语只在 `status == Processing` 时执行
            // ——今天这本就隐含（watcher 只扫 Processing），显式化是为了「未来放宽扫描
            // 面时闸门仍然发声」。守卫判据由引擎侧单点提供（[[NodeEngine.sessionLevelPrimitiveAllowed]]），
            // **不改 `BgTaskRegistry`**（R-4 边界）。
            if !nebflow.core.project.NodeEngine.sessionLevelPrimitiveAllowed(rec) then
              logger.warn(
                s"TaskStuckWatcher: ${rec.sessionId} attempt $attempts (L2) — SKIPPED session-level primitives " +
                  s"(transport abort / reclaim): session status is ${rec.status}, not Processing " +
                  "[design §3.4 mutex 1b: a session-level primitive must not touch a frozen/other-state session]"
              )
            else
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
                  // P2（R-1=B）：L3 从「先终态化再救援」改成「**终态之前挂起并恢复**」。
                  // 旧写法是 bridgeCancelled（停 actor + 节点终态 cancelled）→ sleep 5s
                  // → hardResumeNode；新写法是 suspendNode（只停 actor，节点留 Running）
                  // → **有界等待终止确认** → 锚探测 → CAS + resume。全程不进 Cancelled。
                  // 副作用：不再需要「面板终态帧」补发（节点未终态，面板行由 nodeUpdated
                  // 驱动），故原 emitSubagentPanelDone 调用随之移除——这是**语义变化**，
                  // 不是遗漏：挂起腿不产生终态帧。
                  // R8 ②：登记 T+N 复查（**无条件**——shadow 与否都登记，复查本身只告警
                  // 不动作）。登记在 `.start` 之前 ⇒ 复查输入不依赖 resume fiber 的调度。
                  pendingL3.update(_.filterNot(_.sessionId == rec.sessionId) :+
                    PendingL3(rec.sessionId, rec.rootSessionId, System.currentTimeMillis(),
                      assessment.branch, assessment.toolPhaseMs, assessment.agentIdleMs, attempts)) *>
                  act(runRecoveryLeg(resources, wsHub, rec, assessment, cls, attempts, ledger).start.void)
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
                  // P3 §3.4 互斥点 1(b)：会话级原语的状态守卫（同 flow 分支）。
                  if !nebflow.core.project.NodeEngine.sessionLevelPrimitiveAllowed(rec) then
                    logger.warn(
                      s"TaskStuckWatcher: ${rec.sessionId} attempt $attempts (L2) — SKIPPED session-level primitives " +
                        s"(transport abort / reclaim): session status is ${rec.status}, not Processing [design §3.4 mutex 1b]"
                    )
                  else
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
                    ) *>
                      // P3 §3.4 互斥点 1(b)：会话级原语的状态守卫（同 flow 分支）。
                      (if !nebflow.core.project.NodeEngine.sessionLevelPrimitiveAllowed(rec) then
                         logger.warn(
                           s"TaskStuckWatcher: root agent ${rec.sessionId} L2 — SKIPPED session-level primitives " +
                             s"(transport abort / reclaim): session status is ${rec.status}, not Processing [design §3.4 mutex 1b]")
                       else
                      nebflow.llm.LlmInterface.transportAbortFor(rec.sessionId).flatMap { n =>
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
                    }.handleErrorWith(e => logger.warn(s"TaskStuckWatcher: L2 abort for ${rec.sessionId} failed: ${e.getMessage}"))))
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

  // ── P3：判定序 / 恢复尝试 / 互斥点 2 的事件面（与上面两条同面可 join）────────

  /** 判定序闸门命中（§3.4 第 2/3/4 步）：**零动作**拍的结构化留痕（含 gate 身份与
    * 账本读数），使「本拍为什么什么都没做」在审计面可判定——与 `stuck-detected`
    * （检出面）、`stuck-fire`（动作面）三面同 join 键 (sessionId, ts)。 */
  val RecoveryGatedType: String = "stuck-recovery-gated"

  private def recordGate(
    rec: AgentRecord,
    a: StuckAssessment,
    cls: StuckClassification,
    led: RecoveryLedger,
    gate: RecoveryGate
  ): IO[Unit] =
    WatchdogEventLog.append(io.circe.Json.obj(
      "ts" -> io.circe.Json.fromLong(System.currentTimeMillis()),
      "type" -> io.circe.Json.fromString(RecoveryGatedType),
      "sessionId" -> io.circe.Json.fromString(rec.sessionId),
      "kind" -> io.circe.Json.fromString(rec.kind.toString),
      "branch" -> io.circe.Json.fromString(a.branch),
      "class" -> io.circe.Json.fromString(cls.cls),
      "gate" -> io.circe.Json.fromString(gate.kind),
      "reported" -> io.circe.Json.fromBoolean(gate.report),
      "chainAttempts" -> io.circe.Json.fromInt(led.chainAttempts),
      "genAttempts" -> io.circe.Json.fromInt(led.genAttempts),
      "note" -> io.circe.Json.fromString(gate.note)
    ))

  /** 互斥点 2（§3.4）：恢复完成后 60s 窗内 LoopGuard 指纹再次命中 ⇒ 停恢复链。
    * 与 §3.5 的「恢复尝试（每次）事件流 `stuck-recovery-attempt`」同面。 */
  val RecoveryLoopDetectedType: String = "recovery-loop-detected"

  private def recordRecoveryLoopDetected(
    rec: AgentRecord,
    a: StuckAssessment,
    cls: StuckClassification,
    led: RecoveryLedger
  ): IO[Unit] =
    WatchdogEventLog.append(io.circe.Json.obj(
      "ts" -> io.circe.Json.fromLong(System.currentTimeMillis()),
      "type" -> io.circe.Json.fromString(RecoveryLoopDetectedType),
      "sessionId" -> io.circe.Json.fromString(rec.sessionId),
      "kind" -> io.circe.Json.fromString(rec.kind.toString),
      "branch" -> io.circe.Json.fromString(a.branch),
      "class" -> io.circe.Json.fromString(cls.cls),
      "strikeBaseline" -> io.circe.Json.fromInt(led.strikeBaseline),
      "strikeNow" -> io.circe.Json.fromInt(rec.loopStrikeCount),
      "fpBaseline" -> io.circe.Json.fromString(led.fpBaseline),
      "fpNow" -> io.circe.Json.fromString(rec.lastLoopFp),
      "sinceRecoveryMs" -> io.circe.Json.fromLong(System.currentTimeMillis() - led.lastRecoveryAt),
      "note" -> io.circe.Json.fromString(
        "recovery → LoopGuard fingerprint within the detect window: the recovery chain stops (repeated stall ⇒ repeated retry is a loop)")
    ))

  /** §3.5「恢复尝试（每次）」事件：含 attempt index / 锚探测结果 / 分支（重启 vs 续跑）。
    * **无通知**（通知只在耗尽时恰好一次）——这是「内部静默」的可审计对应面。 */
  val RecoveryAttemptType: String = "stuck-recovery-attempt"

  private def recordRecoveryAttempt(
    rec: AgentRecord,
    a: StuckAssessment,
    cls: StuckClassification,
    led: RecoveryLedger,
    anchor: Option[nebflow.core.project.NodeEngine.RecoveryAnchor],
    branchText: String
  ): IO[Unit] =
    WatchdogEventLog.append(io.circe.Json.obj(
      "ts" -> io.circe.Json.fromLong(System.currentTimeMillis()),
      "type" -> io.circe.Json.fromString(RecoveryAttemptType),
      "sessionId" -> io.circe.Json.fromString(rec.sessionId),
      "kind" -> io.circe.Json.fromString(rec.kind.toString),
      "branch" -> io.circe.Json.fromString(a.branch),
      "class" -> io.circe.Json.fromString(cls.cls),
      "chainAttemptNo" -> io.circe.Json.fromInt(led.chainAttempts),
      "genAttemptNo" -> io.circe.Json.fromInt(led.genAttempts),
      "anchorA1" -> io.circe.Json.fromBoolean(anchor.isDefined),
      "anchorA3" -> io.circe.Json.fromBoolean(anchor.exists(_.worktreeAvailable)),
      "hasOutput" -> io.circe.Json.fromBoolean(anchor.exists(_.hasOutput)),
      "replayBranch" -> io.circe.Json.fromString(branchText),
      "notified" -> io.circe.Json.fromBoolean(false)
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
    // P2（硬依赖 2）：定位键从 `rootSessionId`（多项目挂载下歧义）改为「会话属于哪个
    // store」（[[runtimeOwning]]）——复查的对象必须是**真正持有该节点**的那个引擎，
    // 否则复查自己也会落进「命中的是错引擎 ⇒ 节点查无 ⇒ 误判不足」的同一陷阱。
    runtimeOwning(p.sessionId).flatMap {
      case None =>
        logger.warn(
          s"TaskStuckWatcher: L3 self-check skipped for ${p.sessionId} — no project runtime owns this session " +
            s"(root=${p.rootSessionId}); verdict not derivable (restart/unmount tolerated by design §3.8)")
      case Some(rt) =>
          for
            snap <- rt.store.snapshot
            reg <- resources.agentRegistry.get
            node = nebflow.core.project.NodeEngine.findNodeForSession(snap, p.sessionId)
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

  /** P2（硬依赖 2，正交实验已证**正**）：按会话定位它**所属**的项目 runtime。
    *
    * 被替换掉的旧写法是 `rts.find(rt => rt.engine.rootSessionId == rec.rootSessionId)`
    * ——`rootSessionId` **不是**项目级唯一键：启动挂载把同一个顶层 rootSessionId 交给
    * 全部项目（`GatewayMain` 单个 rootSid → `ProjectActor.mountAll` → `mount` →
    * `NodeEngine(rootSessionId, …)`），`ProjectRuntimeRegistry.all` 是 Map 无序 ⇒
    * 16 个同 id 候选中任取其一。
    *
    * 实证（宿主日志 2026-09-11）：06:58 启动「16 project(s) mounted
    * (rootSessionId=5cc7590a-…)」；当天 `nebflow.node.engine - [hard-recovery] no node
    * owns session`（**引擎内**节点查无，`NodeEngine` 的 findNodeForSession 出口）**12**
    * 次，而 watcher 层「no project runtime for …」（旧 `rts.find` 整体落空）**0** 次
    * ⇒ find 总是命中、命中的却总是**错的引擎**，resume 成功率 0。
    *
    * 新口径：**唯一键 = 会话在哪个 store 里**（`NodeEngine.ownsSession` 只读扫
    * `sessionRef`/`sessionRefVerify`）。三个旧消费点
    * （`hardResumeFlowNode` / `checkL3Outcome` / `hardRecoveryFallback`）统一走本函数。 */
  private def runtimeOwning(sessionId: String): IO[Option[nebflow.core.project.ProjectRuntime]] =
    nebflow.core.project.ProjectRuntimeRegistry.all
      .flatMap { rts =>
        def go(rest: List[nebflow.core.project.ProjectRuntime]): IO[Option[nebflow.core.project.ProjectRuntime]] =
          rest match
            case Nil => IO.pure(None)
            case rt :: tail =>
              rt.engine.ownsSession(sessionId).flatMap(ok => if ok then IO.pure(Some(rt)) else go(tail))
        go(rts)
      }
      .handleErrorWith(e =>
        logger
          .error(s"TaskStuckWatcher: project runtime lookup failed: ${Option(e.getMessage).getOrElse(e.toString)}")
          .as(None))

  /** L3 恢复腿（Flow 节点）：**挂起 → 有界等待 → 锚探测 → CAS + resume**（R-1=B）。
    *
    * 与改造前的差异（这是本批的结构性改动）：
    *   - 前置动作从 `bridgeCancelled`（停 actor **+ 节点终态化**）改成
    *     `NodeEngine.suspendNode`（**只停 actor**，节点保持 `Running`）——全程不进
    *     `Cancelled`，`cancelled` 保持真终态语义（不可重激活）；
    *   - 固定 `IO.sleep(5s)` 改成**有界等待会话从 registry 摘除**（`StuckSuspendWaitMs`
    *     上限 + 轮询步长）——把「赌时序」换成「等可观测确认」，超时降级不 resume；
    *   - 锚探测（§3.1）成为**恢复前置步骤**：A1（transcript）不可用 ⇒ 不重试、直接
    *     一次上报；A3（worktree/git）作产物面旁证（§3.2 零产出/有产出分支）。
    *
    * 返回 `Some(nodeId)` = resume 真生效；`None` 已由本方法内部的
    * [[reportExhausted]]/日志收口（不再有「静默 None」出口）。 */
  private def runRecoveryLeg(
    resources: SharedResources,
    wsHub: WsHub,
    rec: AgentRecord,
    assessment: StuckAssessment,
    cls: StuckClassification,
    attempts: Int,
    ledger: cats.effect.Ref[IO, Map[String, RecoveryLedger]]
  ): IO[Unit] =
    val now = System.currentTimeMillis()
    // ── P3（R-2）：预算闸门 + 尝试消费 ───────────────────────────────────────
    // 预算在**尝试**时消费（不是成功后）——否则失败路径会无限重试，与
    // 「禁无条件重试」的立论直接冲突。两个预算都记：
    //   gen   = 本代次（episode）预算，[[Defaults.StuckRecoveryMaxPerGen]]（默认 1）
    //   chain = 全链（跨代次）预算，[[Defaults.StuckRecoveryMaxPerChain]]（默认 2）
    ledger.modify(m => (m, m.getOrElse(rec.sessionId, RecoveryLedger()))).flatMap { led0 =>
      val genExhausted = led0.genAttempts >= nebflow.shared.Defaults.StuckRecoveryMaxPerGen
      val chainExhausted = led0.chainAttempts >= nebflow.shared.Defaults.StuckRecoveryMaxPerChain
      if genExhausted || chainExhausted then
        val which =
          if chainExhausted then
            s"chain budget exhausted (${led0.chainAttempts}/${nebflow.shared.Defaults.StuckRecoveryMaxPerChain})"
          else s"per-generation budget exhausted (${led0.genAttempts}/${nebflow.shared.Defaults.StuckRecoveryMaxPerGen})"
        logger.error(
          s"TaskStuckWatcher: ${rec.sessionId} recovery NOT attempted — $which (design §3.3; " +
            "unconditional retry is forbidden: the budget is the answer to 'recovered and stalled again')"
        ) *>
          (if led0.reportedAt == 0L then
             ledger.update(m => m.updated(rec.sessionId, led0.copy(reportedAt = now))) *>
               reportExhausted(wsHub, rec, assessment, cls, attempts, Nil, s"budget exhausted: $which")
           else
             logger.info(s"TaskStuckWatcher: ${rec.sessionId} budget gate — report already sent once (exactly-once)"))
      else
        val led1 = led0.copy(
          genAttempts = led0.genAttempts + 1,
          chainAttempts = led0.chainAttempts + 1,
          lastAttemptAt = now)
        ledger.update(m => m.updated(rec.sessionId, led1)) *>
          runRecoveryLegInner(resources, wsHub, rec, assessment, cls, attempts, ledger, led1)
    }

  private def runRecoveryLegInner(
    resources: SharedResources,
    wsHub: WsHub,
    rec: AgentRecord,
    assessment: StuckAssessment,
    cls: StuckClassification,
    attempts: Int,
    ledger: cats.effect.Ref[IO, Map[String, RecoveryLedger]],
    led: RecoveryLedger
  ): IO[Unit] =
    runtimeOwning(rec.sessionId).flatMap {
      case None =>
        // 无 runtime 拥有该会话（节点已归档 / 从未绑定 / 项目已卸载）⇒ 无可恢复之物。
        logger.error(
          s"TaskStuckWatcher: no project runtime owns session ${rec.sessionId} — recovery impossible " +
            "(node archived / never bound / project unmounted)")
        reportExhausted(wsHub, rec, assessment, cls, attempts, Nil, "no project runtime owns this session")
      case Some(rt) =>
        for
          // ① A1 主锚（§3.1）：会话 transcript。空 / decode 失败 ⇒ 不可用 ⇒ **不重试**。
          a1 <- resources.sessionStore.loadMessagesForSession(rec.sessionId).attempt
          _ <- a1 match
            case Right(msgs) if msgs.nonEmpty =>
              rt.engine.probeRecoveryAnchors(rec.sessionId).flatMap { anchor =>
                for
                  _ <- logger.info(
                    s"TaskStuckWatcher: ${rec.sessionId} recovery anchors — A1 transcript=${msgs.size} msgs (available), " +
                      s"A3 worktree=${anchor.worktreeAvailable} (${anchor.probeDir}), hasOutput=${anchor.hasOutput}")
                  // §3.5「恢复尝试（每次）」事件：attempt index / 锚探测结果 / 分支
                  // （重启 vs 续跑）。**无通知**（通知只在耗尽时恰好一次）。
                  _ <- recordRecoveryAttempt(rec, assessment, cls, led, Some(anchor),
                    if anchor.hasOutput then "resume-from-breakpoint" else "restart-turn")
                  // 挂起 = **只停 actor，不终态化**（R-1=B）。同步发出（不 fork）——
                  // 「开火 ⇒ 中断信号」必须确定性成立；其后的「等终止确认 + resume」
                  // 才交给后台 fiber（见下方 .start），扫描循环不等。
                  _ <- rt.engine.suspendNode(rec.sessionId, s"stuck ${assessment.secs}s (${assessment.branch})")
                  // 挂起**已同步发出**（上一行，不 fork）——「L3 开火 ⇒ 中断信号已发出」
                  // 必须确定性成立，不依赖 fiber 调度时序。此处只把「等终止确认 +
                  // resume」交给后台 fiber（扫描循环不等它）。
                  _ <- (awaitSessionDrained(resources, rec.sessionId, nebflow.shared.Defaults.StuckSuspendWaitMs)
                    .flatMap {
                      case true =>
                        // §3.4 步骤 6（**硬约束② 修复**：写点 = `hardResumeNode` 的
                        // `onResumed` = **CAS 接受瞬间**）：恢复**成功** ⇒ 写冷却窗基点 +
                        // 互斥点 2 的指纹基线（下一个 60s 窗内指纹上升 = 反复卡识别）。
                        //
                        // 为什么必须是回调而不是返回值之后：`hardResumeNode` 尾部的
                        // `startNode` 同步等到被恢复会话的**整段终态**（`runWithAgent` 的
                        // `IO.race`）——恢复后的会话可能运行数十分钟，写点若挂在返回值之后，
                        // 这段运行期里 `lastRecoveryAt == 0` ⇒ 冷却窗（[[recoveryGate]]）与
                        // 互斥点 2（[[loopDetectedAfterRecovery]]）**不生效**（独立复核实测：
                        // 恢复成功后 25s 账本仍 `lastRecoveryAt=0`）。回调内失败仍只 warn，
                        // 不中断恢复腿；CAS 被拒时回调不触发 ⇒ 零写入（负控）。
                        val onResumed: IO[Unit] =
                          ledger
                            .update(m =>
                              m.updated(rec.sessionId, led.copy(
                                lastRecoveryAt = System.currentTimeMillis(),
                                strikeBaseline = rec.loopStrikeCount,
                                fpBaseline = rec.lastLoopFp)))
                            .handleErrorWith(e =>
                              logger.warn(s"TaskStuckWatcher: recovery ledger update failed: ${e.getMessage}")) *>
                            logger.info(
                              s"TaskStuckWatcher: ${rec.sessionId} recovery ACCEPTED (CAS) — cooldown " +
                                s"${nebflow.shared.Defaults.StuckRecoveryCooldownMs / 60000}min + 互斥点 2 指纹基线" +
                                s"已在恢复接受瞬间置位（hasOutput=${anchor.hasOutput}；不等被恢复会话终态）")
                        rt.engine.hardResumeNode(rec.sessionId, Some(anchor), onResumed).flatMap {
                          case Some(nodeId) =>
                            // 账本写点已在 `onResumed`（CAS 接受瞬间）完成；此处只记
                            // 「恢复腿已跑完（被恢复会话返回终态）」这一进度事实——不得再
                            // 承载冷却窗口径（那会把它重新拖回会话终态时点）。
                            logger.info(
                              s"TaskStuckWatcher: ${rec.sessionId} recovery OK — node '$nodeId' resumed run " +
                                s"returned (hasOutput=${anchor.hasOutput}; ledger baseline written at CAS accept)")
                          case None =>
                            logger.error(
                              s"TaskStuckWatcher: ${rec.sessionId} recovery resume not effective (CAS rejected / " +
                                "transcript vanished) — single report")
                            reportExhausted(wsHub, rec, assessment, cls, attempts,
                              List("suspend → hard-resume CAS/transcript leg"), "resume not effective")
                        }
                      case false =>
                        reportExhausted(wsHub, rec, assessment, cls, attempts, List("suspend"),
                          "suspend confirmation timed out")
                    }).start.void
                yield ()
              }
            case Right(_) =>
              logger.error(
                s"TaskStuckWatcher: ${rec.sessionId} recovery anchor A1 unavailable (transcript empty) — " +
                  "no retry, single report (design §3.3 condition 1)")
              reportExhausted(wsHub, rec, assessment, cls, attempts, Nil, "unrecoverable: empty transcript")
            case Left(e) =>
              logger.error(
                s"TaskStuckWatcher: ${rec.sessionId} recovery anchor A1 unreadable " +
                  s"(${Option(e.getMessage).getOrElse(e.toString)}) — no retry, single report (design §3.3 condition 1)")
              reportExhausted(wsHub, rec, assessment, cls, attempts, Nil, "unrecoverable: unreadable transcript")
        yield ()
    }

  /** 有界等待「会话已从 registry 摘除」= 引擎 fiber 的清理段已完成（取代固定 sleep 5s）。
    * 轮询步长/上限见 [[nebflow.shared.Defaults.StuckSuspendPollMs]] / `StuckSuspendWaitMs`。 */
  private def awaitSessionDrained(resources: SharedResources, sessionId: String, timeoutMs: Long): IO[Boolean] =
    val poll = math.max(1L, nebflow.shared.Defaults.StuckSuspendPollMs)
    val deadline = System.currentTimeMillis() + timeoutMs
    def go: IO[Boolean] =
      resources.agentRegistry.get.map(_.contains(sessionId)).flatMap {
        case false => IO.pure(true)
        case true =>
          if System.currentTimeMillis() >= deadline then IO.pure(false)
          else IO.sleep(poll.millis) >> go
      }
    go.handleErrorWith(e =>
      logger
        .warn(s"TaskStuckWatcher: suspend confirmation poll for $sessionId failed: ${Option(e.getMessage).getOrElse(e.toString)}")
        .as(false))

  /** 恢复耗尽 / 不可恢复时的**恰好一次**上报（设计 §3.5 的最后一格）。
    *
    * 语义（R-1=B 与 R-6 协同）：节点此时是 `Running`（挂起腿不终态化）⇒ 走
    * [[NodeEngine.failStuckRecovery]]（failNode 全链：result 含原因 / nodeUpdated /
    * failed 回流 / D5 零结算停等）。**去重靠既有链**：`notifySentAt` 持久标记 +
    * `DispatchNotify.releaseTerminalNotify` 占位归还——本轮改造不新造账本、不新增
    * `NotifyReason`/`CancelSource` 枚举值（R-6：上报面本批只做内部静默）。
    *
    * `actions` = 已尝试的恢复动作清单（进 result / 事件 / 通知文本，回答「引擎试过什么」）。 */
  private def reportExhausted(
    wsHub: WsHub,
    rec: AgentRecord,
    assessment: StuckAssessment,
    cls: StuckClassification,
    attempts: Int,
    actions: List[String],
    cause: String
  ): IO[Unit] =
    val text =
      s"stuck auto-recovery exhausted for session ${rec.sessionId} (class=${cls.cls}, judge branch=${assessment.branch}, " +
        s"agentIdle=${assessment.agentIdleMs / 1000}s, toolPhase=${assessment.toolPhaseMs / 1000}s, " +
        s"tool=${assessment.toolName.getOrElse("-")}, attempts=$attempts): $cause; " +
        s"recovery actions tried: ${if actions.isEmpty then "none (not recoverable)" else actions.mkString(" → ")}. " +
        "The engine suspended (not killed) the session and could not revive it from the on-disk transcript; " +
        "node re-judged as failed (D5 zero-settlement: downstream keeps waiting; reactivate via NodeEdit or rewire)."
    val rendered = s"cancelled[source=engine]: reason=$text" // 仅日志/事件用，不改节点状态
    runtimeOwning(rec.sessionId).flatMap {
      case Some(rt) => rt.engine.failStuckRecovery(rec.sessionId, text)
      case None =>
        logger.error(s"TaskStuckWatcher: $text — but no runtime owns the session; report could not be recorded")
    } *>
      WatchdogEventLog.append(io.circe.Json.obj(
        "ts" -> io.circe.Json.fromLong(System.currentTimeMillis()),
        "type" -> io.circe.Json.fromString(RecoveryExhaustedType),
        "sessionId" -> io.circe.Json.fromString(rec.sessionId),
        "kind" -> io.circe.Json.fromString(rec.kind.toString),
        "class" -> io.circe.Json.fromString(cls.cls),
        "branch" -> io.circe.Json.fromString(assessment.branch),
        "attempt" -> io.circe.Json.fromInt(attempts),
        "cause" -> io.circe.Json.fromString(cause),
        "actions" -> io.circe.Json.arr(actions.map(io.circe.Json.fromString)*)
      )) *>
      logger.error(s"TaskStuckWatcher: $text")

  /** 恢复耗尽事件类型（与 `stuck-detected`/`stuck-fire` 同面，可按 sessionId join）。 */
  val RecoveryExhaustedType: String = "stuck-recovery-exhausted"

  // ── 旧 R5 失败分支已随 P2 退场（2026-09-11）────────────────────────────────
  // 原 `hardRecoveryFallback`（找 runtime → NodeEngine.settleFailedHardResume）针对的是
  // 「先终态化再救援」形态（节点停在中间态 Cancelled，需要一条把 Cancelled 改判 failed
  // 的腿）。R-1=B 之后挂起腿全程不进 Cancelled，节点在恢复失败时是 **Running**，
  // 该函数的 CAS 守卫（`n.status == Cancelled`）恒不命中 ⇒ 成为纯死代码。
  // 它被 [[runRecoveryLeg]] 内的 [[reportExhausted]] → `NodeEngine.failStuckRecovery`
  // 取代（同一 failNode 全链、同一条「恰好一次回流」结构，状态守卫改为 Running）。
  // `NodeEngine.settleFailedHardResume` 本身**保留不动**：它是**既有** L3 形态与历史
  // 数据（节点已停在 Cancelled 的存量）的收口，删除会掐掉存量现场的处置路径。

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
