package nebflow.agent

import nebflow.shared.NebflowLogger

import scala.collection.mutable

/**
 * Stand 场景低成本保温——**shadow 交付**（cache-warmup 批 2026-11-06，方案 §四 +
 * 作者补充令 §四）。
 *
 * 两层分清（方案 §四 开篇）：actor/session 驻留复用是**本地零请求**；供应商 KV
 * 续租**可能收费/占并发额度**。本模块只做后者（前者由引擎既有生命周期承担：
 * DispatcherIdleWindowMs / DispatcherTaskLifecycleAnchor / NodeDestroyWindowMs，
 * 不新增永久调度器）。
 *
 * 本批决策（收益门与能力现状裁定）：**保温保持 shadow**——
 *   · Zai TTL 未知、价格未配 ⇒ 按方案 §四「价格/quota 未知就保守不发」，计入
 *     miss 时预热按全额计费的可能；
 *   · 引擎侧无安全保温 transport（不抢业务槽/不污染健康表/不注入 UI）⇒ 按补充令
 *     §12「缺少安全保温能力时交付 shadow 而非强行启用」；
 *   · `evaluate` 在本批**结构性不产 WarmSend**（无调度器、无发送器、无后台任务）
 *     ——不创建 agent turn、不注入消息、不执行 tool_calls、不落历史/UI/任务状态。
 *     启用开关留给后续批（供应商能力与价格核验后），决策/预计费用面已可用。
 *
 * 角色策略（方案 §四 逐条，判据表见 [[CacheWarmingSpec]] ⑨）：
 *   · 主会话/kernel：有新输入自然续用，纯空闲不自动花钱保温；
 *   · 等待活跃子任务返回且上下文大：候选（过收益门）；
 *   · dispatcher：仅任务未终态且有 running 子任务/可预测回报，DAG 估计下次唤醒，
 *     不轮询全项目；
 *   · subagent/node：频繁工具往返时真实请求已续用 ⇒ 禁止重复预热；新建短叶子
 *     默认不预热；blocked/frozen/取消/结束/owner-gone 不保温；
 *   · 终态立即取消计划；账本进程内存活，重启清零 ⇒ 过期保温任务不复活。
 *
 * 收益门（方案 §四）：E[可避免冷读额外成本] > E[全部保温输入读写输出成本 +
 * 限额/并发机会成本] × (1+安全余量) 才发；未知价格/TTL ⇒ shadow；预算/次数/
 * 时长上限触顶 ⇒ 停止；累计保温 ≥ 单次冷读 ⇒ 拒绝。
 *
 * 运行入口：`sbt "Test/testOnly nebflow.agent.CacheWarmingSpec"`
 * 归属 CI：既有 `sbt test` 家族。
 */
object CacheWarming:

  private val logger = NebflowLogger.forName("nebflow.agent.cache-warming")

  // ── 模型 ──────────────────────────────────────────────────────────────

  sealed trait Role:
    def name: String

  case object MainOrKernel extends Role:
    val name = "main-or-kernel"

  case object Dispatcher extends Role:
    val name = "dispatcher"

  case object ParentAwaitingChild extends Role:
    val name = "parent-awaiting-child"

  case object SubagentOrNode extends Role:
    val name = "subagent-or-node"

  sealed trait SessionState:
    def name: String

  /** 活跃工具往返——真实请求已在续租供应商 KV。 */
  case object ActiveToolRoundTrip extends SessionState:
    val name = "active-tool-roundtrip"

  /** 用户纯空闲（无活任务、无可预测回报）。 */
  case object IdleUser extends SessionState:
    val name = "idle-user"

  /** 等待活跃子任务返回（大上下文、回报可预测）。 */
  case object WaitingChildReturn extends SessionState:
    val name = "waiting-child-return"

  /** blocked / frozen / owner-gone。 */
  case object BlockedOrFrozen extends SessionState:
    val name = "blocked-or-frozen"

  /** 终态（完成/取消）——立即取消计划，零补发。 */
  case object Terminal extends SessionState:
    val name = "terminal"

  /** 新建短叶子会话（输入快速增长中）。 */
  case object FreshShortLeaf extends SessionState:
    val name = "fresh-short-leaf"

  /** 长 Bash/人工等待，但预计会恢复的活跃节点。 */
  case object LongWaitExpectedResume extends SessionState:
    val name = "long-wait-expected-resume"

  final case class Candidate(
    sessionId: String,
    role: Role,
    state: SessionState,
    /** 可复用前缀 token 数（持久历史+system+tools 的估算）。 */
    contextTokens: Long,
    /** 下次业务调用大概率发生并 miss 的概率（0-1；夹具注入，不猜）。 */
    coldBeforeNextBusinessProbability: Double,
    expectedNextBusinessMs: Option[Long],
    /** 距上次真实业务请求的 ms（虚拟时钟注入）。 */
    idleMs: Long
  )

  /** 价格面（$/1M tokens）。None = 未知 ⇒ 一律 shadow，不伪称省钱。 */
  final case class Economics(
    inputPerMTok: Option[BigDecimal] = None,
    cacheReadPerMTok: Option[BigDecimal] = None,
    cacheWritePerMTok: Option[BigDecimal] = None,
    outputPerMTok: Option[BigDecimal] = None,
    /** 供应商 KV TTL（秒）。None = 未知 ⇒ shadow。 */
    ttlSec: Option[Long] = None,
    /** 安全余量（收益须超过成本 (1+margin) 倍）。 */
    safetyMargin: Double = 0.5,
    /** 每域每会话保温预算（$）。None = 不限但保守面已由其余闸兜住。 */
    budgetUsd: Option[BigDecimal] = None,
    /**
     * 每会话最大保温次数。**0 = 显式禁保温**（NoWarm warming-disabled-zero-cap）；
     * 缺省 4 = 保守小额上限（启用批再按域配置）。
     */
    maxWarmsPerSession: Int = 4
  )

  sealed trait Decision:
    def reason: String

  /** 不保温（含终态/真实流量已续用/负收益/预算触顶等）。 */
  final case class NoWarm(reason: String) extends Decision

  /** shadow：只出决策与预计费用，不发额外请求。 */
  final case class WarmShadow(reason: String, estimatedCostUsd: BigDecimal, estimatedAvoidableUsd: BigDecimal)
      extends Decision

  /** 预留：本批 evaluate 结构性不产（无发送器；启用面留后续批）。 */
  final case class WarmSend(reason: String) extends Decision

  // ── 角色硬闸（补充令 §四.9 表驱动判据的实现面）──────────────────────

  /** 角色×状态硬闸：返回 Some(NoWarm 理由) = 该会话在**任何经济参数**下都不保温。 */
  def hardGate(cand: Candidate): Option[String] =
    cand.state match
      case Terminal => Some("terminal-state-zero-reissue") // 终态立即取消，重启不复活
      case BlockedOrFrozen => Some("blocked-frozen-owner-gone")
      case ActiveToolRoundTrip => Some("real-traffic-renews") // 频繁真实调用已续用 ⇒ 零额外保温
      case FreshShortLeaf => Some("fresh-short-leaf-no-warm") // 几秒内 miss/内容快速增长不用心跳掩盖
      case IdleUser if cand.role == MainOrKernel => Some("idle-main-default-zero") // 主会话/kernel 纯空闲不花钱
      case IdleUser => Some("idle-no-pending-work")
      case _ => None // WaitingChildReturn / LongWaitExpectedResume：进收益门

  // ── 账本（进程内存活；重启清零 ⇒ 不复活过期保温）─────────────────────

  final class Ledger:
    private val warmsPerSession = mutable.Map.empty[String, Int]
    private var totalCostUsd = BigDecimal(0)

    /** 业务让位闸的观测：本批无发送器，账本只记录决策面消耗。 */
    private val entries = mutable.ArrayDeque.empty[(String, Long, BigDecimal)] // (sessionId, atMs, cost)

    def recordWarm(sessionId: String, atMs: Long, costUsd: BigDecimal): Unit = synchronized {
      warmsPerSession.update(sessionId, warmsPerSession.getOrElse(sessionId, 0) + 1)
      totalCostUsd += costUsd
      entries.addOne((sessionId, atMs, costUsd))
      while entries.length > 512 do entries.removeHead()
    }

    def warmCount(sessionId: String): Int = synchronized(warmsPerSession.getOrElse(sessionId, 0))
    def spentUsd: BigDecimal = synchronized(totalCostUsd)
    def entryCount: Int = synchronized(entries.length)
  end Ledger

  // ── 收益门 + 决策（纯函数；nowMs 虚拟时钟注入，零 sleep）────────────

  /** 单次保温的预计全成本（输入写 + 读 + 输出；价格未知 ⇒ None）。 */
  def warmCostUsd(contextTokens: Long, econ: Economics): Option[BigDecimal] =
    for
      inW <- econ.inputPerMTok // 保温请求的输入按 cache-write 价落账
      outW <- econ.outputPerMTok
    yield
      val write = BigDecimal(contextTokens) / 1_000_000 * econ.cacheWritePerMTok.getOrElse(inW)
      val out = BigDecimal(64) / 1_000_000 * outW // 保温用最小安全输出预算（不做 1-token 假设，64 为保守小额）
      write + out

  /** 单次冷读的可避免额外成本（全前缀按 input 价重算 vs cache-read 价）。 */
  def coldReadExtraUsd(contextTokens: Long, econ: Economics): Option[BigDecimal] =
    for
      inW <- econ.inputPerMTok
      rdW <- econ.cacheReadPerMTok
    yield BigDecimal(contextTokens) / 1_000_000 * (inW - rdW)

  def evaluate(cand: Candidate, econ: Economics, ledger: Ledger, nowMs: Long): Decision =
    // ① 角色×状态硬闸（任何经济参数下不保温）
    hardGate(cand) match
      case Some(reason) => NoWarm(reason)
      case None =>
        // ② 预算/次数上限触顶 ⇒ 停止（0 = 显式禁保温）
        if econ.maxWarmsPerSession <= 0 then NoWarm("warming-disabled-zero-cap")
        else if ledger.warmCount(cand.sessionId) >= econ.maxWarmsPerSession then NoWarm("session-warm-count-cap")
        else if econ.budgetUsd.exists(_ <= ledger.spentUsd) then NoWarm("budget-exhausted")
        else
          // ③ 未知价格/TTL ⇒ shadow（不伪称省钱；补充令 §四.10）
          val cost = warmCostUsd(cand.contextTokens, econ)
          val saving = coldReadExtraUsd(cand.contextTokens, econ)
          (cost, saving, econ.ttlSec) match
            case (Some(c), Some(s), Some(_)) =>
              // ④ 收益门：E[可避免] > E[全成本] × (1+安全余量) 才有正期望
              val marginFactor = BigDecimal(1.0 + econ.safetyMargin)
              if s <= c * marginFactor then NoWarm("negative-expected-value")
              // ⑤ 累计保温比一次冷读更贵 ⇒ 拒绝
              else if c * (ledger.warmCount(cand.sessionId) + 1) >= s then
                NoWarm("cumulative-warming-costlier-than-cold")
              // ⑥ 发送器能力缺席（本批结构性）⇒ shadow 交付
              else WarmShadow("send-capability-absent-shadow-only", c, s)
            case _ =>
              WarmShadow("unknown-price-or-ttl", cost.getOrElse(BigDecimal(0)), saving.getOrElse(BigDecimal(0)))

  // ── 请求构造器（为启用批预置；本批不发送）────────────────────────────

  /**
   * 影响前缀的配置必须原样保留（tools/system/thinking/tool 顺序）；输出上限与
   * thinking 预算不兼容/不支持安全低输出 ⇒ 拒绝构造（不盲用 max_tokens=1）。
   */
  def buildWarmingPlan(
    sessionId: String,
    providerId: String,
    model: String,
    toolsFingerprint: String,
    systemFingerprint: String,
    thinkingBudgetTokens: Int,
    supportsSmallOutputCap: Boolean
  ): Either[String, WarmingPlan] =
    if !supportsSmallOutputCap then Left("no-safe-small-output-cap-skip")
    else if thinkingBudgetTokens > 0 && thinkingBudgetTokens < 1024 then Left("thinking-budget-incompatible-skip")
    else Right(WarmingPlan(sessionId, providerId, model, toolsFingerprint, systemFingerprint, thinkingBudgetTokens))

  /** 保温请求计划——复用相同真实缓存域与可复用前缀；不改 thinking/tool_choice。 */
  final case class WarmingPlan(
    sessionId: String,
    providerId: String,
    model: String,
    toolsFingerprint: String, // 与业务请求同序同 schema（改了=换缓存域，禁止）
    systemFingerprint: String,
    thinkingBudgetTokens: Int // 不为省输出关 thinking（前缀完整性优先）
  )

end CacheWarming
