package nebflow.llm

import cats.effect.IO
import cats.effect.kernel.{Deferred, Ref}
import cats.effect.std.Mutex
import cats.syntax.all.*
import nebflow.core.ProviderHealthPort
import nebflow.db.format.GuardDocs
import nebflow.db.{DbHandle, DbHost}
import nebflow.shared.*

import scala.concurrent.duration.*

object ProviderHealthMonitor:
  /** Interval between background probe cycles for Down providers (2 minutes). */
  val ProbeIntervalSec = 120

  /**
   * Probe budget for the FIRST SERVING EVENT (default = the real path's
   * first-token watchdog, `Defaults.LlmFirstTokenTimeoutSec`). The probe
   * succeeds at the first content chunk of a streaming request, so its budget
   * must be the same window a real call would have gotten its first token in —
   * a probe that succeeds implies a real call would have been served, and vice
   * versa. Shrinking in spec: sys prop `nebflow.health.probeTimeoutSec`
   * (read at every probe, `Defaults.BashMaxTimeoutMs` pattern — never a
   * startup-time constant).
   */
  def ProbeTimeoutSec: Int =
    sys.props.getOrElse("nebflow.health.probeTimeoutSec", Defaults.LlmFirstTokenTimeoutSec.toString).toInt

  /**
   * How often a [[ProviderHealthMonitor.waitForAnyUp]] waiter re-checks health
   * state when woken by a signal that did not bring its candidates Up (stale
   * or foreign-provider signal). Also the worst-case extra delay a waiter pays
   * when its blocking Deferred got replaced underneath it by another waiter.
   */
  val RecheckIntervalSec = 5

  // ── 健康表退避阶梯（假 DOWN 批 2026-11-05）──────────────────────────────────
  //
  // 作者令（本批判据 B）：「健康表只许延迟、不许否决」——长期 DOWN 的候选也
  // 必须按退避被**真实试一次**（真实失败才继续跳过），不能凭一条历史记录永久
  // 排除。同族已合入修正 = FreezeAlertSweep 的 `shouldAttemptResume`
  //（健康表只延迟续跑，到窗口强制放行）。
  //
  // 阶梯（trials = 自本次 Down 以来已放行的试探次数）：
  //   0 → 60s，1 → 120s，2 → 240s，3 → 480s，4+ → 600s（封顶）
  //
  // 取值的两条约束：
  //   1. **首次试探 ≤ 1 个探测周期**（[[ProbeIntervalSec]] = 120s）：健康面自己
  //      承诺的最坏恢复时延是「探活周期 + 探测预算」，退避不能比它更久——否则
  //      「探测能救回来的 provider」被退避挡在门外，等于又是一种否决；
  //   2. **封顶 10 分钟**：真死的 provider（401 无订阅 / 域名下线）每 10 分钟
  //      被真实试一次，烧的是 1 次失败请求（无 token 消耗），换来的是「配置修好
  //      即回链」——不需要等探测、不需要重启。
  //
  // 试探一律是**真实调用**（不是探测）：真实请求成功 ⇒ markUp，失败 ⇒
  // 原因刷新 + trials+1 ⇒ 下一次更晚。这条腿的存在理由：探测成功只证明「空历史
  // 一问一答能通」，证明不了「带真上下文的请求能通」（协议/形状/工具面的失败
  // 探测永远看不见）。
  def DownRetryBaseMs: Long =
    sys.props.getOrElse("nebflow.health.downRetryBaseMs", "60000").toLong

  /** 退避封顶（10 分钟）：真死的候选也不至于被无限重试烧额度/烧延迟。 */
  final val DownRetryMaxMs: Long = 600_000L

  /** `trials` = 已放行的试探次数（0 = 本次 Down 后还没试过）。 */
  def downRetryBackoffMs(trials: Int): Long =
    val n = math.max(0, math.min(trials, 16))
    math.min(DownRetryBaseMs * (1L << n), math.max(DownRetryMaxMs, DownRetryBaseMs))

  /**
   * 半开试探记账（假 DOWN 批 2026-11-05）：`at` = 上一次放行/转 Down 的时刻，
   * `trials` = 本次 Down 以来已放行的试探次数（0 = 还没试过）。
   */
  final case class HalfOpenTrial(at: Long, trials: Int)

/**
 * Tracks the health of every provider+model in the candidate chain.
 *
 * Three signals drive state transitions (down→up clears are event-driven):
 *   - Real request failures: when a candidate exhausts retries (or hits a
 *     permanent error), `markDown` is called by the LLM interface.
 *   - Background probes: every [[ProviderHealthMonitor.ProbeIntervalSec]],
 *     each Down candidate is probed with a minimal streaming completion
 *     request. On the FIRST serving event, `markUp` fires and wakes any
 *     request blocked in [[waitForAnyUp]].
 *   - Real request successes: a streaming winner (first content chunk) and a
 *     non-streaming reply each call `markUp` (wired by the LLM interface) —
 *     a call that actually succeeds is itself a serving signal, so a marker
 *     written by a concurrent request's failure verdict is cleared at success
 *     time instead of waiting for the next probe cycle.
 *
 * Additionally, when all candidates are Down and a request is about to block
 * in [[waitForAnyUp]], [[probeNow]] is called to probe the Down candidates
 * immediately instead of waiting for the next background cycle — this cuts
 * the worst-case recovery latency from ~`ProbeIntervalSec + ProbeTimeoutSec`
 * down to ~`ProbeTimeoutSec`.
 *
 * Up providers are never probed proactively — the first real request after
 * a provider comes back online will naturally discover if it is still healthy.
 */
/**
 * db 接线二阶 B2（D-10 + M37 教训）：构造缝 `db`（测试注入）；生产激活 = `DbHost.peek`
 * **只查不开**（C5 FlowMapEventLog 先例——createLlm 被 8+ 测试直调，openBestEffort 会
 * 令测试 JVM 开真实数据根，禁；boot 序已验证 sessionStore:547 生产根自开先于
 * createLlm:555 ⇒ peek 稳定命中）。
 */
final class ProviderHealthMonitor(registry: ProviderRegistry, db: Option[DbHandle] = None)
    extends ProviderHealthPort:
  private val logger = NebflowLogger.forName("nebflow.llm.health")

  /**
   * guard 档句柄（lazy：首用一次求值）。None = 未接线（全部落盘腿 no-op，现状行为）。
   * 坏档写拒绝（V3）在 writeAvoidGuard 内——hydration 先行隔离后写腿自然恢复。
   */
  private lazy val guardDb: Option[DbHandle] =
    db.orElse(DbHost.peek(PathUtil.dataRoot.toNIO))

  /**
   * 窗口态写穿串行闸：快照两 ref → 整档五步写，防止并发转换交错丢更新
   * （advisory 面：丢一档的代价 = 下次转换重写自愈）。
   */
  private lazy val guardWriteLock: Mutex[IO] =
    import cats.effect.unsafe.implicits.global
    Mutex[IO].unsafeRunSync()

  /** 首用一次性重水合门（DispatchNotify C2 同形）：started 单飞 + gate 等待。 */
  private val guardsHydrated: Ref[IO, Boolean] = Ref.unsafe(false)
  private val hydrateStarted: Ref[IO, Boolean] = Ref.unsafe(false)
  private val hydrateGate: Deferred[IO, Unit] = Deferred.unsafe[IO, Unit]

  private def ensureGuards: IO[Unit] =
    guardsHydrated.get.flatMap { done =>
      if done then IO.unit
      else
        hydrateStarted.getAndSet(true).flatMap { started =>
          if started then hydrateGate.get
          else hydrateGuards.guarantee(hydrateGate.complete(()).attempt.void)
        } *> guardsHydrated.set(true)
    }

  /**
   * 重水合（§3.11 步 5，D-10）：Present ⇒ 避坑窗 + 熔断面回填（运行时已有键优先——
   * 内存态比盘新）；Absent ⇒ 空；Corrupt ⇒ 保守向（有界全避 + 隔离，库侧腿）。
   * best-effort：db 故障 ⇒ WARN + 现状内存起步（绝不阻断）。
   */
  private def hydrateGuards: IO[Unit] =
    guardDb match
      case None => IO.unit
      case Some(h) =>
        // IO.defer：getCandidates 可能在**构造期**同步抛（registry 的 configRef 为 null 的
        // 测试桩/子类），defer 把它转成 IO 错误再降级为 Nil——guard 重水合是 best-effort，
        // 绝不允许把「候选键源不可得」升级成调用方（markDown/softAvoid/filter）的失败。
        val candidates: IO[List[ModelCandidate]] =
          if registry == null then IO.pure(Nil)
          else IO.defer(registry.getCandidates()).handleErrorWith(_ => IO.pure(Nil))
        (for
          now <- IO(System.currentTimeMillis())
          keys <- candidates.map(_.map(c => key(c.providerId, c.model)).distinct)
          doc <- h.recoverAvoidGuardConservative(now, keys, Defaults.QuotaAvoidWindowMs)
          _ <- avoidUntilRef.update(m =>
            m ++ doc.avoidUntil.filter((_, until) => until > System.currentTimeMillis())
              .filterNot((k, _) => m.contains(k)))
          existing <- statesRef.get
          // 只有「本次新回填」的 Down 才初始化半开账本：已存在（含 Up）的键不动。
          newlyDown = doc.down.filterNot((k, _) => existing.contains(k))
          _ <- statesRef.update(s =>
            doc.down.foldLeft(s) { (acc, kv) =>
              acc.get(kv._1) match
                case Some(HealthState.Down(_, _)) | Some(HealthState.Up) => acc
                case None => acc.updated(kv._1, HealthState.Down(
                  s"recovered from guard (persisted down since ${kv._2})", kv._2))
            })
          // 回填的 Down 必须等自己的退避窗才被真实试一次：没有 trials 账目时
          // filterCandidatesDetailed 会把它当「窗已到」立刻放回链（观测面还会报可用），
          // 等于把第 60s 的退避直接跳过——首用即补一条 trial(now, 0)。
          _ <- halfOpenRef.update(m =>
            newlyDown.foldLeft(m) { case (acc, (k, _)) =>
              if acc.contains(k) then acc else acc.updated(k, ProviderHealthMonitor.HalfOpenTrial(now, 0))
            })
        yield ()).handleErrorWith { e =>
          logger.warn(s"provider-avoid guard hydration failed (degrade to in-memory): ${e.getMessage}")
        }

  /**
   * 窗口态写穿（best-effort：db 故障只 WARN，回落「本进程内窗口仍有效」——与
   * DispatchNotify.persistGuard 同形）。avoid 档写过期窗剪枝（防无界增长）。
   */
  private def persistGuardDoc(): IO[Unit] =
    guardDb match
      case None => IO.unit
      case Some(h) =>
        guardWriteLock.lock.surround {
          for
            now <- IO(System.currentTimeMillis())
            avoids <- avoidUntilRef.get.map(_.filter((_, until) => until > now))
            downs <- statesRef.get.map(_.collect {
              case (k, HealthState.Down(_, since)) => k -> since
            })
            _ <- h.writeAvoidGuard(GuardDocs.AvoidGuardDoc(
              version = 1,
              source = nebflow.db.Db.AvoidGuardSource,
              avoidUntil = avoids,
              updatedAt = now,
              down = downs
            )).handleErrorWith { e =>
              logger.warn(s"provider-avoid guard persist failed: ${e.getMessage}")
            }
          yield ()
        }

  private val statesRef: Ref[IO, Map[String, HealthState]] = Ref.unsafe(Map.empty)

  /**
   * 软回避窗（审计 20260903 子项③「慢 ≠ 死」）：key → avoidUntil epoch ms。
   * 超时类失败只把 provider 软回避到该时刻——不进健康状态（不 Down）、不触发
   * 探测、无需恢复动作，窗口到期自然回到候选链。与 markDown（确证死亡驱逐，
   * 需探测恢复）严格分层。
   */
  private val avoidUntilRef: Ref[IO, Map[String, Long]] = Ref.unsafe(Map.empty)

  /**
   * 5xx 冷却的连续命中计数（5xx 归批 2026-11-05）：key → strikes。
   *
   * 与 [[avoidUntilRef]] 配对使用：[[]] 里 5xx 走 [[coolDown]] 而非
   * [[markDown]]——窗长 = `UpstreamUnavailable.cooldownMs(strikes)`
   * （15s → 30s → 60s → 120s → 240s 封顶，strikes 封顶
   * [[UpstreamUnavailable.CooldownMaxStrikes]]）。**成功即清零**
   * （[[markUp]] 与真实请求成功都过这里）——「冷却后成功 ⇒ 恢复」的判据就是
   * 这一条，不需要额外的探测动作。窗口到期后 candidate 自然回到链上 = 半开
   * 试探（试一发，成功清零 / 失败 strikes 继续递增）。
   */
  private val strikesRef: Ref[IO, Map[String, Int]] = Ref.unsafe(Map.empty)

  /** Deferred that completes when any provider transitions Down→Up. */
  private val signalRef: Ref[IO, Deferred[IO, Unit]] =
    Ref.unsafe(Deferred.unsafe[IO, Unit])

  /**
   * 半开试探记账（假 DOWN 批 2026-11-05）：key → (最近一次放行时刻, 已放行次数)。
   *
   * 它把「Down」从**否决**降级为**延迟**：[[filterCandidatesDetailed]] 只在
   * `now - at >= downRetryBackoffMs(trials)` 时把这个候选放回链上试一次（半开），
   * 其余时间照旧跳过（带剩余时间进账本）。写入点：
   *   - [[markDown]] 首次转 Down：记 `(now, 0)` ⇒ 首次试探在 base 之后；
   *   - 放行时（filter）：`(now, trials+1)` ⇒ 下次更晚（指数退避）；
   *   - [[markUp]]／探测成功：删除 ⇒ 回 UP 后阶梯清零。
   *
   * 为什么必须单独记账而不用 `HealthState.Down.since`：`since` 是「从什么时候
   * 开始坏」的事实面（面板/审计要这个语义，不能被试探重置），而退避需要的是
   * 「上一次**证据**时刻」。两者在同一条记录里会互相污染（历史实锤：`since`
   * 一被刷新，面板的「已 down 多久」就永远停留在 0）。
   */
  private val halfOpenRef: Ref[IO, Map[String, ProviderHealthMonitor.HalfOpenTrial]] =
    Ref.unsafe(Map.empty)

  /**
   * 探测失败签名单（可诊断性，假 DOWN 批 2026-11-05）：key → 上一次探测失败的
   * 失败链文本。同一签名只 WARN 一次（DOWN 候选每 120s 探一次，真死的 provider
   * 不该每轮刷一条同样的 warn），签名变了才再报——**兹证明探测到底失败在哪**：
   * 旧实现把探测失败日志打在 debug，现场日志里一条都没有（`grep "Probe failed"`
   * = 0），于是「探测为什么救不回来」根本无从判断。
   */
  private val probeFailSigRef: Ref[IO, Map[String, String]] = Ref.unsafe(Map.empty)

  /**
   * Tier 2a standalone search API health (P2, 2026-08-25). Starts
   * Unconfigured; the first standalone search call records Up/Down.
   */
  private val searchHealthRef: Ref[IO, SearchApiHealth] =
    Ref.unsafe(SearchApiHealth.Unconfigured)

  /** Record a successful Tier 2a standalone search call. */
  def recordSearchSuccess(): IO[Unit] =
    searchHealthRef.set(SearchApiHealth.Up)

  /**
   * Record a failed Tier 2a standalone search call (reason = the classified
   * diagnostic, e.g. quota/rate limited / auth failed / timeout).
   */
  def recordSearchFailure(reason: String): IO[Unit] =
    searchHealthRef.set(SearchApiHealth.Down(reason, System.currentTimeMillis()))

  /** Current Tier 2a search API health (P2-6 layered /api/health). */
  def getSearchHealth: IO[SearchApiHealth] =
    searchHealthRef.get

  private def key(providerId: String, model: String): String =
    s"$providerId/$model"

  /**
   * Mark a candidate as Down. No-op if already Down.
   *
   * 假 DOWN 批（2026-11-05）语义变更：**已经 Down 就刷新原因**（旧实现
   * `case Some(Down(_)) => IO.unit` 把首次原因钉死成化石——20:04 一句
   * `Exception when sending request: POST …` 写进表后，后 48 分钟里每一次
   * 探测/试探的真实失败形态一条都没记下来，作者面能看到的永远是那句话，
   * 无法归类）。`since` 保持首次时刻（「坏了多久」是事实，不是重试计时器）；
   * 退避账本 [[halfOpenRef]] 只在**首次**转 Down 时初始化。
   */
  def markDown(providerId: String, model: String, reason: String): IO[Unit] =
    val k = key(providerId, model)
    val now = System.currentTimeMillis()
    ensureGuards *> statesRef.get.flatMap { states =>
      states.get(k) match
        case Some(HealthState.Down(_, since)) =>
          statesRef
            .set(states.updated(k, HealthState.Down(reason, since)))
            .*>(logger.warn(s"Provider $k still DOWN (fresh reason): $reason"))
        case _ =>
          statesRef
            .set(states.updated(k, HealthState.Down(reason, now)))
            .*>(
              halfOpenRef.update(m =>
                if m.contains(k) then m - k else m + (k -> ProviderHealthMonitor.HalfOpenTrial(now, 0))
              )
            )
            .*>(logger.warn(s"Provider $k marked DOWN: $reason"))
    }.flatTap(_ => persistGuardDoc())

  /** Mark a candidate as Up and wake waiters. No-op if already Up. Persisted（B2）. */
  def markUp(providerId: String, model: String): IO[Unit] =
    val k = key(providerId, model)
    // 5xx 归批（2026-11-05）：一次真实成功 = 冷却计数清零（半开试探成功的判据），
    // 与健康态清理解耦——provider 本来就没被 markDown，strikes 却是要清的。
    val clearStrikes = strikesRef.update(m => if m.contains(k) then m - k else m)
    // 假 DOWN 批（2026-11-05）：回 UP 即清退避阶梯 + 探测失败签名——下次再 Down
    // 从第一档 60s 重新起步（阶梯是「本次故障」的账，不是终身黑历史）。
    val clearLedgers = halfOpenRef.update(m => if m.contains(k) then m - k else m) *>
      probeFailSigRef.update(m => if m.contains(k) then m - k else m)
    ensureGuards *> statesRef.get.flatMap { states =>
      states.get(k) match
        case Some(HealthState.Down(_, _)) =>
          clearStrikes *> clearLedgers *> statesRef
            .set(states.updated(k, HealthState.Up))
            .*>(logger.info(s"Provider $k recovered (UP)"))
            .*>(signalRecovery())
        case _ => clearStrikes *> clearLedgers
    }.flatTap(_ => persistGuardDoc())

  /**
   * 5xx 冷却（5xx 归批 2026-11-05，作者令「deepseek 抖一下不能把 agent 冻掉」）：
   * 上游暂时不可用（500/502/503/504/550，见 [[UpstreamUnavailable]]）时，
   * 不 markDown（进探测集 ⇒ 空历史探测秒成功 ⇒ 秒回 UP ⇒ 下一发再 5xx ⇒
   * DOWN/UP flap 风暴），而是记一次 strike 并把 candidate 软回避
   * `UpstreamUnavailable.cooldownMs(strikes)` 毫秒（15s→30s→60s→120s→240s
   * 封顶，strikes 封顶 [[UpstreamUnavailable.CooldownMaxStrikes]]）。窗口到期候选
   * 自然回链 = 半开试探：一次成功 [[markUp]] 清零 strikes，一次失败 strikes+1。
   *
   * 冷却期内 [[filterCandidates]] 把它从候选里剔掉（不进 down/探测集），
   * 全链都在冷却时由 all-Down 门的 5s tick 兜底（窗到期自行放回）——与配额软
   * 回避窗同一机制，语义标签不同，面板/日志都能 grep 到。
   */
  def coolDown(providerId: String, model: String, detail: String = "upstream 5xx"): IO[Unit] =
    val k = key(providerId, model)
    strikesRef.get.flatMap { m =>
      val strikes = math.min(m.getOrElse(k, 0) + 1, UpstreamUnavailable.CooldownMaxStrikes)
      val windowMs = UpstreamUnavailable.cooldownMs(strikes)
      strikesRef
        .update(_.updated(k, strikes))
        .*>(
          avoidUntilRef
            .update(_.updated(k, System.currentTimeMillis() + windowMs))
            .*>(logger.warn(
              s"Provider $k cooled down for ${windowMs}ms ($detail — strike $strikes/" +
                s"${UpstreamUnavailable.CooldownMaxStrikes}, NOT evicted, no probe; half-open on expiry)"
            ))
        )
    }

  /** 当前连续 5xx 计数（观测 / spec 断言用）。 */
  private[llm] def getCooldownStrikes: IO[Map[String, Int]] = strikesRef.get

  /**
   * 软回避（审计 20260903 子项③；配额分层 2026-09-21 令）：把 candidate 排除出
   * 候选链 `windowMs` 毫秒——与 [[markDown]] 的区别：不写健康状态（[[getStates]]
   * 看不到 Down）、不进探测集（不烧慢/额度死 provider 的探测请求）、无恢复动作
   * （窗口到期 [[filterCandidates]] 自然放回）。时限与语义标签由调用方传入：
   *
   *   - 瞬时类（首 token / 流间隙超时）：`label = "timeout"`，
   *     `windowMs = Defaults.TimeoutAvoidWindowMs`（45s，「慢 ≠ 死」）；
   *   - **配额类**（计划性额度耗尽：403 / 429-1308）：`label = "quota exhausted"`，
   *     `windowMs = Defaults.QuotaAvoidWindowMs`（300s）——同业等待无意义，
   *     触发的是**换链**（退出本轮候选、立即推进下一根），显式分层于瞬时类。
   *
   * spec 可缩窗模拟（生产值见 Defaults）。
   */
  def softAvoid(providerId: String, model: String, windowMs: Long, label: String = "timeout"): IO[Unit] =
    ensureGuards *> {
      val k = key(providerId, model)
      avoidUntilRef
        .update(_.updated(k, System.currentTimeMillis() + windowMs))
        .*>(logger.warn(s"Provider $k soft-avoided for ${windowMs}ms ($label — NOT evicted, no probe)"))
        .*>(persistGuardDoc())
    }

  /** 当前软回避截止时刻（观测 / spec 断言用）。 */
  private[llm] def getAvoidUntil: IO[Map[String, Long]] = ensureGuards *> avoidUntilRef.get

  /**
   * Partition candidates into (up, down) based on current health state.
   *
   * 软回避（子项③ + 配额分层）：avoid 窗口内的 candidate 从 up 中剔除，但也不进
   * down（down 集合 = 探测集，健康探测不应该烧一个只是「慢」或**额度计划性耗尽**
   * 的 provider）。avoid-only 的全空链由 all-Down 门的 waitForAnyUp 5s tick 兜底
   * ——窗口到期 filterCandidates 放回，等待者 ≤5s 内自行通过，无需额外信号。
   */
  def filterCandidates(
    candidates: List[ModelCandidate]
  ): IO[(List[ModelCandidate], List[ModelCandidate])] =
    // 观测面（面板 /api/health、PresenceRoutes 的健康视图、[[waitForAnyUp]]）：
    // `claim = false` ⇒ **不消费**半开试探名额。否则一次面板刷新会把退避窗内的
    // 候选放回链又被别人跳过，或者反过来把「本次该被真实试一次」的名额提前烧掉
    // （→ 下一个真实请求反而被跳过）。只有真正要发请求的调用点才 claim。
    ensureGuards *> filterCandidatesDetailed(candidates, claim = false).map {
      case (eligible, skipped) =>
        (eligible, skipped.collect { case (c, CandidateSkipCause.HealthDown(_)) => c })
    }

  /**
   * [[filterCandidates]] 的**带原因**版本（缺陷批 2026-11-05，作者报障「有可用模型
   * 却报所有模型不可用」）：返回 `(可试候选, 被跳候选 + 跳过原因)`。旧的两分返回值
   * 把 down / soft-avoid 两类跳过**丢掉原因**（down 集合只剩候选对象），于是终局
   * 错误与日志里都没有「谁被跳了、为什么」——作者面表现为「队列里明明还有模型」。
   *
   * 假 DOWN 批（2026-11-05）**半开**：Down 不再等于「永久出链」。`state` 里的
   * Down 只在退避窗（[[ProviderHealthMonitor.downRetryBackoffMs]]，60→120→240→
   * 480→600s 封顶）内被跳过，窗一到就把该候选放回链上**真实试一次**；试成功由
   * 正常成功路径 [[markUp]] 回 UP，试失败则由 [[markDown]] 刷新原因并把
   * `trials+1`（下一次更晚）。⇒ 健康表**只延迟、不否决**。
   *
   * `claim = true`（真实请求的决策点）才记账；观测面传 `claim = false` 只读。
   */
  def filterCandidatesDetailed(
    candidates: List[ModelCandidate],
    claim: Boolean = true
  ): IO[(List[ModelCandidate], List[(ModelCandidate, CandidateSkipCause)])] =
    // 跳过原因（Down 且仍在退避窗内）：必须写明「还差多久才会被真实试一次」——
    // 这是「不否决」的可读面，作者能一眼看出它不是永远出局。秒数**向上取整**：
    // 刚 markDown 的那一刻剩余 59999ms 应显示 60s 而不是 59s（面板/日志对读者的
    // 是一致的读数，不是毫秒漂移）；下限 1s —— 绝不出现 "in 0s"（那读起来像
    // 「永远不试」）。账本从 [[halfOpenRef]] **现读**（不用上面那份快照）：跳过行
    // 必须反映此刻的真实阶梯。
    def skipOf(c: ModelCandidate, reason: String, k: String): IO[Option[(ModelCandidate, CandidateSkipCause)]] =
      halfOpenRef.get.map { m =>
        val now = System.currentTimeMillis()
        val t = m.get(k)
        val remaining = t
          .map(tt => math.max(0L, ProviderHealthMonitor.downRetryBackoffMs(tt.trials) - (now - tt.at)))
          .getOrElse(ProviderHealthMonitor.downRetryBackoffMs(0))
        val inSec = math.max(1L, (remaining + 999L) / 1000L)
        val n = t.map(_.trials).getOrElse(0) + 1
        Some(
          (
            c,
            CandidateSkipCause.HealthDown(
              s"$reason; half-open trial #$n in ${inSec}s (never veto it — the candidate will be retried)"
            )
          )
        )
      }

    for
      states <- statesRef.get
      avoids <- avoidUntilRef.get
      trials <- halfOpenRef.get
      ready <- candidates.traverse { c =>
        val k = key(c.providerId, c.model)
        val now = System.currentTimeMillis()
        // 软回避窗优先于 Down（既有分层，不得反转）：窗内的候选连探测集都不进
        // （「慢/额度耗尽」不该烧一个刚超时的 provider 的探测请求），故连半开也
        // 不参与——窗到期后自然回落到下面的 Down 腿。
        avoids.get(k).filter(_ > now) match
          case Some(until) =>
            IO.pure(Some((c, CandidateSkipCause.SoftAvoid(until - now, "soft-avoid"))))
          case None =>
            states.get(k) match
              case Some(HealthState.Down(reason, _)) =>
                val inWindow =
                  trials.get(k).exists(t => now - t.at < ProviderHealthMonitor.downRetryBackoffMs(t.trials))
                if inWindow then skipOf(c, reason, k)
                else if !claim then
                  // 观测面（面板 /api/health、PresenceRoutes 健康视图、[[waitForAnyUp]]）：
                  // `claim = false` ⇒ **不消费**试探名额，但窗已到就该报「可用」（下一次
                  // 真实请求会试它——这是事实；等待者也要被这个读数唤醒，否则又退回
                  // 「等 120s 探测」的老坑）。
                  IO.pure(None)
                else
                  // 到期 ⇒ 半开：放回链 + **原子**记账（trials+1、at=now）。并发 N 路
                  // 同时到来时只能有一路拿到这次真实试探（`modify` 里复检窗口），其余
                  // 落到跳过腿——否则 8 路都会把一个真死的候选试一遍（额度/延迟双爆）。
                  halfOpenRef
                    .modify { m =>
                      val t = m.get(k)
                      val expired =
                        t.forall(tt => now - tt.at >= ProviderHealthMonitor.downRetryBackoffMs(tt.trials))
                      if expired then
                        (
                          m.updated(k, ProviderHealthMonitor.HalfOpenTrial(now, t.map(_.trials).getOrElse(0) + 1)),
                          true
                        )
                      else (m, false)
                    }
                    .flatMap {
                      case true =>
                        logger
                          .info(s"Provider $k half-open trial: backoff expired, trying for real (was DOWN: $reason)")
                          .as(None)
                      case false => skipOf(c, reason, k) // 别人刚领走这次试探
                    }
              case _ => IO.pure(None)
      }
      skipped = ready.flatten
      skipKeys = skipped.map { case (c, _) => key(c.providerId, c.model) }.toSet
      eligible = candidates.filterNot(c => skipKeys.contains(key(c.providerId, c.model)))
    yield (eligible, skipped)

  /**
   * Block until at least one of the given candidates is Up again.
   *
   * Design (fixed after the 2026-08-15 dual-DOWN incident): health state in
   * [[statesRef]] is the source of truth; the signal Deferred is only a
   * wake-up *hint*. The old implementation consumed a one-shot Deferred —
   * the first woken waiter refreshed it, so a second concurrent waiter
   * blocked on a fresh empty Deferred even though every provider was already
   * Up, riding a ghost 120s timeout (err=120 seconds). Now every wake (signal
   * or periodic tick) re-checks [[filterCandidates]] directly: a waiter whose
   * candidates are already Up returns immediately no matter which waiter
   * consumed the signal.
   *
   * Anti-spin: when woken without our candidates being Up, we replace the
   * (possibly completed) Deferred with a fresh one and sleep one recheck
   * interval before looping — a completed Deferred would make `.get` return
   * instantly and spin the loop.
   */
  def waitForAnyUp(candidates: List[ModelCandidate]): IO[Unit] =
    def anyUp: IO[Boolean] =
      filterCandidates(candidates).map(_._1.nonEmpty)

    def loop: IO[Unit] =
      anyUp.flatMap {
        case true => IO.unit
        case false =>
          IO
            .race(
              signalRef.get.flatMap(_.get),
              IO.sleep(ProviderHealthMonitor.RecheckIntervalSec.seconds)
            )
            .flatMap { _ =>
              anyUp.ifM(
                IO.unit,
                Deferred[IO, Unit]
                  .flatMap(fresh => signalRef.tryModify(_ => (fresh, ())).void)
                  *> IO.sleep(ProviderHealthMonitor.RecheckIntervalSec.seconds)
                  *> loop
              )
            }
      }
    loop
  end waitForAnyUp

  /** Test seam: force-replace the signal Deferred (simulates anti-spin refresh orphaning a parked waiter). */
  private[llm] def replaceSignalForTest(fresh: Deferred[IO, Unit]): IO[Unit] =
    signalRef.set(fresh)

  /** Snapshot of all health states (for UI / logging). */
  def getStates: IO[Map[String, HealthState]] =
    ensureGuards *> statesRef.get

  /**
   * 半开试探账本快照：key → (最近放行时刻, 已放行次数)。面板/diagnose 用；
   * spec 用它断言退避阶梯（不 sleep）。
   */
  def getHalfOpenTrials: IO[Map[String, ProviderHealthMonitor.HalfOpenTrial]] =
    halfOpenRef.get

  /**
   * Test seam：把某个 key 的半开账本**人为拨老** `ms` 毫秒，模拟退避窗到期
   * （spec 不允许 sleep 60s，也不允许把生产阶梯改成可注入常数）。
   */
  private[llm] def ageHalfOpenForTest(providerId: String, model: String, ms: Long): IO[Unit] =
    halfOpenRef.update(m =>
      m.updated(key(providerId, model), ProviderHealthMonitor.HalfOpenTrial(System.currentTimeMillis() - ms, m.get(key(providerId, model)).map(_.trials).getOrElse(0)))
    )

  /**
   * 幻影 DOWN 清理（假 DOWN 批 2026-11-05）：表里存在、但 `registry` 已解析不出
   * 对应候选（provider 改名 / 从配置删除）的 Down 表项——它们既不会被探测
   * （[[downCandidates]] 跳过）、也永远不会被试用（候选链里没有它），却**永久
   * 占用**健康面的读数。现场实锤：`/api/health` 里 `deepseek/deepseek-flash: down`
   * （配置里早已不存在这个名字）和 `ds/deepseek-flash: down` 一直挂着。
   *
   * 语义安全：只删「解析不出来」的项；能解析出来的（哪怕真死）一律留下，走半开。
   */
  private[llm] def gcUnresolvableDown(): IO[Unit] =
    statesRef.get.flatMap { states =>
      states.collect { case (k, HealthState.Down(_, _)) => k }.toList
        .traverse(k => registry.getCandidateForRef(k).map(k -> _.isDefined))
        .flatMap { resolved =>
          val ghosts = resolved.collect { case (k, false) => k }
          if ghosts.isEmpty then IO.unit
          else
            statesRef.update(s => ghosts.foldLeft(s)(_ - _)) *>
              halfOpenRef.update(m => ghosts.foldLeft(m)(_ - _)) *>
              logger.warn(s"health table: dropped ${ghosts.size} unresolvable DOWN entr(ies): ${ghosts.mkString(", ")}")
        }
    }

  /**
   * 探测/试探失败时刷新表内原因（假 DOWN 批 2026-11-05）。表里存的必须是最新失败
   * 形态（可归类、带完整 cause 链），不是 20:04 那条化石。
   */
  private def failRefreshReason(providerId: String, model: String, detail: String): IO[Unit] =
    val k = key(providerId, model)
    statesRef.update(s =>
      s.get(k) match
        case Some(HealthState.Down(_, since)) => s.updated(k, HealthState.Down(detail, since))
        case _                                => s
    )

  /**
   * Candidates currently marked Down — across ALL chains, not just the default
   * preset chain. 2026-08-25 kimi incident: the probe set was previously
   * derived from `registry.getCandidates()` (default preset chain only); a
   * candidate that appears in another preset/agent chain (e.g. Vision preset's
   * kimi/k3-256k) was never probed once Down, leaving it DOWN forever (until
   * restart). Refs whose provider/model was removed from config are skipped.
   */
  private[llm] def downCandidates(): IO[List[ModelCandidate]] =
    ensureGuards *> {
      statesRef.get.flatMap { states =>
        states.collect { case (k, HealthState.Down(_, _)) => k }.toList.flatTraverse { k =>
          registry.getCandidateForRef(k).map(_.toList)
        }
      }
    }

  /** Start the background probing loop. Runs forever. */
  def start(): IO[Unit] =
    def loop: IO[Unit] =
      for
        // 假 DOWN 批（2026-11-05）：幻影 DOWN 周期清理（provider 改名/删除后残留的
        // 表项永远不会被探测、也不会被试用，等于永久否决一个不存在的候选）。
        _ <- gcUnresolvableDown().attempt.void
        downCandidates <- downCandidates()
        _ <-
          if downCandidates.nonEmpty then downCandidates.parTraverse_(probe)
          else IO.unit
        _ <- IO.sleep(ProviderHealthMonitor.ProbeIntervalSec.seconds)
        _ <- loop
      yield ()
    // `IO.defer` = 修「探测链静默死」（假 DOWN 批 2026-11-05）。旧实现把 `loop`
    // 当**值**立刻展开：`downCandidates()` 等任一 IO 抛错会让整条探测链**永久
    // 停摆**（Down 候选唯一的被动恢复路径断了，正是现场「48 分钟零探测、零 UP」
    // 的成因之一）。现在：单轮异常记 WARN 后继续下一轮——探测面永不退出。
    IO.defer {
      loop.handleErrorWith(err =>
        logger.warn(s"health probe loop iteration failed (continuing): ${FailureChain.of(err)}") *>
          IO.sleep(ProviderHealthMonitor.ProbeIntervalSec.seconds) *> IO.defer(loop)
      )
    }
  end start

  /**
   * Immediately probe the given candidates (typically the Down set) without
   * waiting for the next background cycle. Called by the LLM interface when
   * all candidates are Down and a request is about to block — this cuts the
   * worst-case recovery latency from ~`ProbeIntervalSec + ProbeTimeoutSec`
   * down to ~`ProbeTimeoutSec`.
   */
  def probeNow(candidates: List[ModelCandidate]): IO[Unit] =
    // Parallel: serial probing of N candidates cost N x ProbeTimeoutSec worst
    // case (two Down providers = up to 60s) before waiters even start waiting.
    candidates.parTraverse_(probe)

  /**
   * Probe a single candidate with a minimal streaming completion request.
   *
   * Serving criterion = the FIRST content event (`TextDelta` / `ThinkingDelta`
   * / `ToolCallChunk` — the exact chunks the real streaming path locks a
   * winner on), NOT a completed reply, and the stream is cancelled right
   * after that event: the clear lands at probe-success time, never at a
   * completion or cycle tail.
   *
   * Why streaming-first-event (修 2026-10-21 「供应商恢复后不回绿」): the old
   * probe awaited a full non-stream `hi` completion under a 30s cap. A
   * thinking provider that recovered spends its first seconds (often minutes)
   * on reasoning before any final text — the probe false-failed on timeout
   * every cycle while real streaming calls (90s first-token watchdog, served
   * by the very same thinking events) succeeded. The provider stayed DOWN
   * indefinitely: the only clear path kept rejecting the proof of recovery.
   *
   * Per-probe output cap (clamp batch 2026-10-21, updated): the probe pins
   * `Defaults.ProbeMaxTokens` (16384, the pre-raise serving value) via
   * `maxTokensOverride` — after the serving cap rose to 128000, a verbatim
   * constant would hand every probe a max_tokens that strict endpoints (glm
   * flash tiers et al.) may 400, flipping providers DOWN fleet-wide. The
   * override bypasses the clamp legs on purpose: the value is engine-internal
   * and years-proven. The serving criterion reads the provider's own first
   * event, so the small cap has nothing left to break.
   */
  private[llm] def probe(candidate: ModelCandidate): IO[Unit] =
    val params = SendMessageParams(
      messages = List(Message(MessageRole.User, Left("hi"))),
      model = candidate.model,
      sessionId = Some("health-check"),
      agentId = Some("health-check"),
      maxTokensOverride = Some(Defaults.ProbeMaxTokens)
    )
    def serving(c: StreamChunk): Boolean = c match
      case _: StreamChunk.TextDelta | _: StreamChunk.ThinkingDelta | _: StreamChunk.ToolCallChunk => true
      case _ => false
    registry
      // getAdapterRaw（不过闸裸视图，429 反应式并发闸批）：探测是恢复机制，
      // 1 次/2min 微量请求——排在饱和闸后会拖慢恢复判定（探测超时计入排队等待
      // ⇒ 假阴性 DOWN 延长）。其余调用方一律走 getAdapter（过闸视图）。
      .getAdapterRaw(candidate.providerId)
      // `find` terminates the stream right after the first serving chunk (the
      // upstream request is cancelled by the compile scope); a stream that
      // ends without one — including a transport error raised before it —
      // surfaces as Left and keeps the candidate Down.
      .flatMap(_.sendMessageStream(params).find(serving).compile.lastOrError.void)
      .timeout(ProviderHealthMonitor.ProbeTimeoutSec.seconds)
      .attempt
      .flatMap {
        case Right(_) => markUp(candidate.providerId, candidate.model)
        case Left(err) =>
          // 假 DOWN 批（2026-11-05）：探测失败必须是**可见的、带完整失败链的**。
          // 旧实现打在 debug——现场 `grep "Probe failed"` = 0 条，于是「探测到底
          // 为什么救不回来」无从判断（这条腿是本作者报障里最关键的盲区）。
          // 同一签名只 WARN 一次（每 120s 一轮不必刷屏），签名变了立刻再报；
          // 同时刷新健康表原因，让表里存的永远是**最新**失败形态。
          val k = key(candidate.providerId, candidate.model)
          val detail = FailureChain.of(err)
          probeFailSigRef.get.flatMap { sigs =>
            val fresh = sigs.get(k).forall(_ != detail)
            (if fresh then logger.warn(s"Probe failed for $k: $detail") else IO.unit) *>
              probeFailSigRef
                .update(_.updated(k, detail))
                .*>(failRefreshReason(candidate.providerId, candidate.model, detail))
          }
      }

  end probe

  /** Complete the current signal Deferred (wake all waiters). */
  private def signalRecovery(): IO[Unit] =
    signalRef.get.flatMap(_.complete(()).attempt.void)
end ProviderHealthMonitor
