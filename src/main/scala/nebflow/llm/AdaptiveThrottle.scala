package nebflow.llm

import cats.effect.IO
import cats.effect.kernel.Ref
import cats.effect.std.Semaphore
import cats.syntax.all.*
import io.circe.Json
import nebflow.core.LlmLogWriter
import nebflow.shared.*

import scala.annotation.tailrec

/**
 * LLM provider 自适应并发限流器（429 反应式，作者令 2026-10-29 21:32）——**并发维**
 * 的自适应闸，与既有 retry/health 的时间维退避（Stream retry 60s / Provider
 * DOWN/UP）互补不重叠。
 *
 * == 作者语义逐字 ==
 * 「如果429了 就统计一下当前该api并行使用的数量 在次基础上 按比如90% 70% 50%等比例
 * 比如90%之后还是429 就 70%等逐渐限流 ，然后在一定时间没有429之后又逐渐解开限制」
 *
 * == 状态机（单点 = [[AdaptiveThrottleMachine]]，纯函数，spec 直测）==
 *   - 档位（rung）阶梯：0 = 不限（透传）→ 1..N = 配置因子（缺省 90%/70%/50%）
 *     → 终档 = 硬下限（缺省 2）。
 *   - **降档**：账户级 429 到达时取当前在飞并行数 N：首刀（rung 0 → 1）
 *     refLimit = max(N, hardFloor)，limit = max(N×90%, hardFloor)；之后每收一刀
 *     rung+1（×70% → ×50% → 硬下限），refLimit 保持本风暴周期基线（档内 limit
 *     单调不增）。硬下限仍 429 ⇒ 不再降，交给既有 health/retry 路径（Transient
 *     → 60s 退避 → 耗尽 markDown → 探测恢复），闸只排队不判死不丢弃。
 *   - **回松**：静默窗（缺省 5min，可配）内无任何账户级 429 ⇒ 逐档回松
 *     （硬下限 → 50% → 70% → 90% → 不限），每档消耗一个观察窗；期间收到新 429
 *     ⇒ 立即降一档并重置静默钟。
 *   - **账户级判定**：分类 reason == RateLimit ∧ 非配额体（QuotaUpstreamCodes，
 *     如 1308 计划性额度）∧ 响应体命中账户级标记（缺省上游码 `1302` /
 *     文案「您的账户已达到速率限制」，可配）。非账户级 429（单请求类）走既有
 *     retry，不触发降档。
 *
 * == 闸形态 ==
 * 每 provider **账户**（键 = `provider.baseUrl`，zhipu/kimi/cmdcode 各一把）一个
 * FIFO 动态并发闸。排队而非拒绝（零超时红线——闸是排队点不是丢弃点）：等待无
 * 超时、满员排队、按到达顺序放行。等待机制 = cats-effect `std.Semaphore`
 * （FIFO 公平 + 取消安全——排队中被 SessionKick / watcher / 关机取消的等待者
 * 不泄漏配额）；动态上限层（cap 变更 / 铸造-吸收-归还簿记）在本文件内实现。
 *
 * == 接线 ==
 * 过闸点 = `ProviderRegistry.getAdapter`（返回 [[GatedAdapter]] 包装）——handle 的
 * 流/非流路径、LlmAllocator、DelegateModeMachine、DelegateJudgeMachine 全部经它，
 * 单点覆盖全部模型流量；健康探测走 `getAdapterRaw` 保持不闸（探测是恢复机制，
 * 1 次/2min 微量，排队反而拖慢恢复判定）。429 信号喂入点 =
 * interface.scala 流/非流两条路径的错误分类处。
 *
 * == 总闸 ==
 * `nebflow.json` `llm.adaptiveThrottle.enabled`（缺省 false = **零行为变化**），
 * 每次 acquire/信号/回松现读（热读，配置改动即刻生效，无重启门槛）。关闭时
 * acquire = 纯透传，不触任何状态。
 *
 * == 已知收敛语义（申报）==
 * 降档（cap 收缩）经控制器吸收在飞释放逐步收敛：新到请求在 FIFO 里排在吸收
 * 需求之后 ⇒ 对新请求即刻生效；已在飞的请求（> 新上限）自然流尽，收敛窗
 * ≤ 一个请求时长。回松（cap 扩张）即时生效。
 */
final class AdaptiveThrottle private[llm] (configRef: Ref[IO, NebflowServiceConfig]):

  private val logger = NebflowLogger.forName("nebflow.llm.throttle")

  /** 一把闸的运行态。`era` = None ⇒ 旁路段（rung 0 / 不限），请求零接触信号量。 */
  private final case class GateState(
    era: Option[Era],
    rung: Int,
    refLimit: Int,
    quietSinceMs: Long,
    inFlight: Int,
    queued: Int,
    /** 信号量纪元计数器（每次铸造新纪元 +1；0 = 从未入闸段）。 */
    genCounter: Long
  )

  private final case class Era(
    sem: Semaphore[IO],
    /** 纪元号（>0）；Permit 携带 issuing gen，纪元更替后的旧 permit 释放不再回流。 */
    gen: Long,
    /** 当前并发上限（有限值）。 */
    cap: Int,
    /** 本纪元累计铸造配额（守恒锚：held + available + 在飞持票 = total）。 */
    total: Int,
    /** 控制器为 cap 收缩而吸收持有的票数（目标 = max(0, total - cap)）。 */
    held: Int
  )

  private final case class Plan(giveBack: Int = 0, mint: Int = 0, absorb: Int = 0)

  private def freshGate: GateState = GateState(None, 0, 0, 0L, 0, 0, 0)

  private val gates: Ref[IO, Map[String, GateState]] = Ref.unsafe(Map.empty)

  // ── 配置（热读）────────────────────────────────────────────────────────

  def params: IO[ThrottleParams] =
    configRef.get.map(cfg => ThrottleParams.from(cfg.llm.adaptiveThrottle))

  private def ladderOf(p: ThrottleParams): AdaptiveThrottleMachine.Ladder =
    AdaptiveThrottleMachine.Ladder(p.factorsPct, p.hardFloor, p.quietWindowMs)

  // ── 判定：账户级 429 ────────────────────────────────────────────────────

  /**
   * 响应体是否命中**账户级**标记：上游码形（`"code":"1302"` / `[1302]`，与
   * Fallback.carriesUpstreamCode 同判据）或文本标记。缺省集 = 今日 zhipu 实证
   * 形态（1302 =「您的账户已达到速率限制」频率类）；其余 provider 形态经配置
   * `accountUpstreamCodes` / `accountTextMarkers` 扩展（只增确证语义的码，
   * 禁凭形状编造——Fallback.QuotaUpstreamCodes 同纪律）。
   */
  def isAccountLevel429(body: String, p: ThrottleParams): Boolean =
    p.accountCodes.exists(c => Fallback.carriesUpstreamCode(body, c)) ||
      p.accountTextMarkers.exists(mk => body.contains(mk))

  /** 配额体排除（1308 计划性额度 = 换链腿，不属并发维）：QuotaUpstreamCodes 同判据。 */
  private def isQuotaBody(body: String): Boolean =
    Fallback.QuotaUpstreamCodes.exists(c => Fallback.carriesUpstreamCode(body, c))

  // ── 429 信号喂入（interface.scala 两路径调用）──────────────────────────

  /**
   * 一次失败分类的喂入点。内部自判：总闸关 / 非 RateLimit / 配额体 / 非账户级
   * ⇒ no-op（既有 retry 路径零变化）；账户级 ⇒ 降一档 + INFO 一行。
   *
   * @param now
   *   时钟注入口（spec 注入模拟时间；生产 = 系统时钟，与 loosenTick 同一钟面）。
   */
  def onRateLimitSignal(key: String, reason: FailoverReason, body: String, now: Long = System.currentTimeMillis()): IO[Unit] =
    params.flatMap { p =>
      val excerpt = LlmLogWriter.redactSecrets(body.take(160))
      if !p.enabled || reason != FailoverReason.RateLimit || isQuotaBody(body) ||
        !isAccountLevel429(body, p)
      then IO.unit
      else
        val ladder = ladderOf(p)
        for
          (downgrade, cap) <- gates.modify { m =>
            val g = m.getOrElse(key, freshGate)
            val (ns, dg) = AdaptiveThrottleMachine.onAccount429(
              AdaptiveThrottleMachine.State(g.rung, g.refLimit, g.quietSinceMs),
              g.inFlight,
              now,
              ladder
            )
            val g2 = g.copy(rung = ns.rung, refLimit = ns.refLimit, quietSinceMs = ns.quietSinceMs)
            val limit = AdaptiveThrottleMachine.limitAt(ns.rung, ns.refLimit, ladder)
            (m.updated(key, g2), (dg, limit))
          }
          _ <- downgrade match
            case None => IO.unit // 已在硬下限：静默钟已重置，无档位变化不刷日志
            case Some(dg) =>
              syncCap(key, cap) *>
                logger.info(
                  s"[adaptive-throttle] ${AdaptiveThrottle.hostOf(key)} downgrade concurrency limit " +
                    s"${AdaptiveThrottleMachine.disp(dg.oldLimit)} -> ${AdaptiveThrottleMachine.disp(dg.newLimit)} " +
                    s"(rung ${dg.label}, inFlight=${dg.inFlight}, trigger=account-level 429: \"$excerpt\")"
                )
        yield ()
    }

  // ── 静默回松（懒触发：acquire 时顺带检查，无后台线程）──────────────────

  /**
   * 静默窗检查：距上次账户级 429 已满 `quietWindowMs` ⇒ 回松一档（每档消耗一个
   * 观察窗，长静默逐档爬满后停在不限档）。无 429 无新请求时无需回松（上限只在
   * 请求到达时起作用）——下一次 acquire 到达时先回松再入队。
   *
   * @param now
   *   时钟注入口（spec 注入模拟时间；生产 = 系统时钟）。
   */
  def loosenTick(key: String, now: Long = System.currentTimeMillis()): IO[Unit] =
    params.flatMap { p =>
      if !p.enabled then IO.unit
      else
        val ladder = ladderOf(p)
        for
          (steps, newLimit) <- gates.modify { m =>
            m.get(key) match
              case None => (m, (Nil, Int.MaxValue))
              case Some(g) =>
                val (ns, steps) = AdaptiveThrottleMachine.loosen(
                  AdaptiveThrottleMachine.State(g.rung, g.refLimit, g.quietSinceMs),
                  now,
                  ladder
                )
                if steps.isEmpty then
                  (m, (Nil, AdaptiveThrottleMachine.limitAt(g.rung, g.refLimit, ladder)))
                else
                  val g2 = g.copy(rung = ns.rung, refLimit = ns.refLimit, quietSinceMs = ns.quietSinceMs)
                  val limit = AdaptiveThrottleMachine.limitAt(ns.rung, ns.refLimit, ladder)
                  (m.updated(key, g2), (steps, limit))
          }
          _ <-
            if steps.isEmpty then IO.unit
            else
              syncCap(key, newLimit) *>
                steps.traverse_ { st =>
                  logger.info(
                    s"[adaptive-throttle] ${AdaptiveThrottle.hostOf(key)} loosen concurrency limit " +
                      s"${st.oldLimitDisp} -> ${st.newLimitDisp} (rung ${st.label}, quiet=${p.quietWindowMs}ms)"
                  )
                }
        yield ()
    }

  // ── 过闸面（GatedAdapter 调用）─────────────────────────────────────────

  /**
   * 取一个过闸许可。总闸关 ⇒ 全局 Noop 哨兵（纯透传，零状态）；开闸 ⇒ 不限档
   * 也计在飞（作者语义「收到 429 时统计当前在飞并行数 N」要求旁路段在飞量可读，
   * 否则首刀 N 恒 0）；有限档再过 FIFO 信号量排队（无超时、不拒绝），按到达
   * 顺序放行。inFlight 只数**已获票**的请求（排队中不占在飞），记账在
   * uncancelable 段内必达——取消（SessionKick / watcher / 关机 / 断开）不泄漏
   * 票也不泄漏计数。
   */
  def acquire(key: String): IO[ThrottlePermit] =
    params.flatMap { p =>
      if !p.enabled then IO.pure(ThrottlePermit.Noop)
      else
        loosenTick(key) *>
          IO.uncancelable { poll =>
            gates.modify { m =>
              val g0 = m.getOrElse(key, freshGate)
              g0.era match
                // 旁路段（rung 0 / 不限）：只记在飞，不触信号量
                case None => (m.updated(key, g0.copy(inFlight = g0.inFlight + 1)), None)
                case Some(era) => (m, Some(era))
            }.flatMap {
              case None => IO.pure(ThrottlePermit(key, 0, this))
              case Some(era) =>
                val gen = era.gen
                bump(key, g => g.copy(queued = g.queued + 1)) *>
                  poll(era.sem.acquire)
                    // 排队中被取消：信号量自身取消安全，这里只回扣排队计数
                    .onCancel(bump(key, g => g.copy(queued = math.max(0, g.queued - 1))))
                    .flatMap { _ =>
                      bump(key, g =>
                        g.copy(queued = math.max(0, g.queued - 1), inFlight = g.inFlight + 1))
                        .as(ThrottlePermit(key, gen, this))
                    }
            }
          }
    }

  private[llm] def release(permit: ThrottlePermit): IO[Unit] =
    if permit.gen <= 0 then
      // 旁路许可：无信号量票，只回扣在飞计数（闸不存在 = 状态已被清，no-op）
      bump(permit.key, g => g.copy(inFlight = math.max(0, g.inFlight - 1)))
    else
      gates.modify { m =>
        m.get(permit.key) match
          case Some(g) =>
            val g2 = g.copy(inFlight = math.max(0, g.inFlight - 1))
            g2.era match
              case Some(e) if e.gen == permit.gen => (m.updated(permit.key, g2), Some(e.sem))
              case _ => (m.updated(permit.key, g2), None) // 旧纪元许可：不回流（防跨纪元配额污染）
          case None => (m, None)
      }.flatMap {
        case Some(sem) => sem.release
        case None => IO.unit
      }

  private def bump(key: String, f: GateState => GateState): IO[Unit] =
    gates.modify { m =>
      m.get(key) match
        case Some(g) => (m.updated(key, f(g)), ())
        case None => (m, ())
    }

  // ── cap 同步（降档/回松的唯一执行点）──────────────────────────────────

  /**
   * 把闸的上限同步到 `newCap`：Int.MaxValue = 拆除纪元回旁路（旧许可释放时按
   * 纪元号判废，不回流）；有限值 = 确保纪元存在并按需铸造 / 吸收 / 归还。
   */
  private def syncCap(key: String, newCap: Int): IO[Unit] =
    if newCap >= Int.MaxValue then dismantleEra(key)
    else
      ensureEra(key, newCap).flatMap { created =>
        if created then IO.unit else adjustCap(key, newCap)
      }

  private def dismantleEra(key: String): IO[Unit] =
    gates.modify { m =>
      m.get(key) match
        case Some(g) if g.era.isDefined => (m.updated(key, g.copy(era = None)), ())
        case _ => (m, ())
    }

  /**
   * 确保纪元存在且 cap = `newCap`（rung 0 首降时创建；已存在则走差值调整）。
   * 返回是否**新建**了纪元（新建 = Semaphore 直接以 newCap 铸造，无需调整）。
   * 并发首降竞态：输家用胜者的纪元再走 adjustCap 收敛到同一 cap。
   */
  private def ensureEra(key: String, newCap: Int): IO[Boolean] =
    gates.get.flatMap { m =>
      m.get(key).flatMap(_.era) match
        case Some(_) => IO.pure(false)
        case None =>
          Semaphore[IO](math.max(1, newCap)).flatMap { sem =>
            gates.modify { m2 =>
              val g = m2.getOrElse(key, freshGate)
              g.era match
                case Some(_) => (m2, false)
                case None =>
                  val gen = g.genCounter + 1
                  val era = Era(sem, gen, newCap, newCap, 0)
                  (m2.updated(key, g.copy(era = Some(era), genCounter = gen)), true)
            }
          }
    }

  /**
   * cap 差值调整（纪元内）：扩 = 先归还吸收票、不足再铸造（releaseN 即时生效）；
   * 缩 = 立即写 cap，控制器吸收（acquireN）随在飞释放逐步收敛——吸收期间新请求
   * 在 FIFO 中排在吸收需求之后 ⇒ 对新请求即刻生效。
   */
  private def adjustCap(key: String, newCap: Int): IO[Unit] =
    gates.modify { m =>
      m.get(key).flatMap(_.era) match
        case None => (m, None)
        case Some(e) =>
          if e.cap == newCap then (m, None)
          else
            var e2 = e.copy(cap = newCap)
            var plan = Plan()
            if newCap > e.total then
              val mint = newCap - e.total
              e2 = e2.copy(total = newCap)
              plan = plan.copy(mint = mint)
            val desiredHeld = math.max(0, e2.total - newCap)
            val excess = e.held - desiredHeld
            if excess > 0 then
              e2 = e2.copy(held = desiredHeld)
              plan = plan.copy(giveBack = excess)
            else if excess < 0 then plan = plan.copy(absorb = -excess)
            (m.updated(key, m(key).copy(era = Some(e2))), Some((e.sem, plan)))
    }.flatMap {
      case None => IO.unit
      case Some((sem, plan)) =>
        // 归还/铸造即时生效（releaseN）；吸收走分离 fiber（等在飞释放，绝不阻塞
        // 429 处理路径），完成后 reconcile 防中途 cap 再变导致的超持。
        (if plan.giveBack > 0 then sem.releaseN(plan.giveBack) else IO.unit) *>
          (if plan.mint > 0 then sem.releaseN(plan.mint) else IO.unit) *>
          (if plan.absorb > 0 then
             (sem.acquireN(plan.absorb) *> reconcileAbsorb(key, plan.absorb, sem)).start.void
           else IO.unit)
    }

  /** 吸收完成后按**当前** cap 重新对账：中途 cap 再变（回松先到）⇒ 超持部分归还。 */
  private def reconcileAbsorb(key: String, acquired: Int, sem: Semaphore[IO]): IO[Unit] =
    gates.modify { m =>
      m.get(key).flatMap(_.era) match
        case Some(e) =>
          val held = e.held + acquired
          val desired = math.max(0, e.total - e.cap)
          val excess = math.max(0, held - desired)
          (m.updated(key, m(key).copy(era = Some(e.copy(held = held - excess)))), (excess, e.sem))
        case None => (m, (0, sem)) // 纪元已拆：吸收票随纪元废弃，不回流
    }.flatMap { case (excess, target) =>
      if excess > 0 then target.releaseN(excess) else IO.unit
    }

  // ── 观测面 ──────────────────────────────────────────────────────────────

  final case class GateSnapshot(limit: Option[Int], rungLabel: String, inFlight: Int, queued: Int)

  /** 测试 / 健康面读数：key → 当前上限（None = 不限）、档位、在飞、排队。 */
  def snapshot: IO[Map[String, GateSnapshot]] =
    for
      p <- params
      m <- gates.get
      ladder = ladderOf(p)
    yield m.map { case (k, g) =>
      val limit =
        if g.rung <= 0 then None
        else Some(AdaptiveThrottleMachine.limitAt(g.rung, g.refLimit, ladder))
      k -> GateSnapshot(limit, AdaptiveThrottleMachine.runLabel(g.rung, ladder), g.inFlight, g.queued)
    }

  /**
   * /api/health `throttle` 键载荷（HealthRoutes 并入；有状态才出现——总闸关或
   * 从无闸时返回 None，默认载荷逐字节不变，探针契约零回归）。
   */
  def healthJson: IO[Option[Json]] =
    params.flatMap { p =>
      if !p.enabled then IO.pure(None)
      else
        snapshot.map { snaps =>
          if snaps.isEmpty then None
          else
            val gateJson = snaps.map { case (k, s) =>
              AdaptiveThrottle.hostOf(k) -> Json.obj(
                "limit" -> s.limit.fold[Json](Json.Null)(Json.fromInt),
                "rung" -> Json.fromString(s.rungLabel),
                "inFlight" -> Json.fromInt(s.inFlight),
                "queued" -> Json.fromInt(s.queued)
              )
            }
            Some(Json.obj("enabled" -> Json.fromBoolean(true), "gates" -> Json.obj(gateJson.toList*)))
        }
    }

end AdaptiveThrottle

private[llm] object AdaptiveThrottle:

  /** 缺省降档因子（作者语义：90% → 70% → 50%，可配）。 */
  val DefaultFactors: List[Double] = List(0.9, 0.7, 0.5)
  private[llm] val DefaultFactorsPct: List[Int] = List(90, 70, 50)
  /** 硬下限缺省（作者建议 1–2 取 2：保留一线吞吐，spec 可配 1）。 */
  val DefaultHardFloor: Int = 2
  /** 静默窗缺省（作者建议 5–10min 取 5min，可配）。 */
  val DefaultQuietWindowMs: Long = 300_000L
  /** 账户级上游码缺省（今日 zhipu 实证形态；Fallback.QuotaUpstreamCodes 同纪律）。 */
  val DefaultAccountCodes: List[String] = List("1302")
  /** 账户级文本标记缺省（zhipu 1302 错误体文案）。 */
  val DefaultAccountTextMarkers: List[String] = List("您的账户已达到速率限制")

  /** 键展示：baseUrl → host[:port]（日志/健康面不落完整 URL 与任何凭据）。 */
  private[llm] def hostOf(key: String): String =
    try
      val u = new java.net.URI(key)
      val h = u.getHost
      if h == null then key
      else Option(u.getPort).filter(p => p > 0).map(p => s"$h:$p").getOrElse(h)
    catch case _: Exception => key
end AdaptiveThrottle

/** 过闸许可：`gen <= 0` = 旁路许可（计数回扣腿）；`throttle == null` = 全局 Noop 哨兵（总闸关，纯 no-op）。 */
final case class ThrottlePermit private[llm] (key: String, gen: Long, private[llm] throttle: AdaptiveThrottle):
  def release: IO[Unit] =
    if throttle == null then IO.unit // Noop 哨兵：总闸关路径的透传许可，零状态可扣
    else throttle.release(this)

private[llm] object ThrottlePermit:
  val Noop: ThrottlePermit = new ThrottlePermit("", 0, null)
end ThrottlePermit

/** `llm.adaptiveThrottle` 节的运行参数（缺省 + 钳制后的解析产物）。 */
final case class ThrottleParams(
  enabled: Boolean,
  /** 降档因子（整数百分比，降序）。整数算术精确 floor，无浮点陷阱。 */
  factorsPct: List[Int],
  hardFloor: Int,
  quietWindowMs: Long,
  accountCodes: List[String],
  accountTextMarkers: List[String]
)

object ThrottleParams:
  def from(cfg: Option[AdaptiveThrottleConfig]): ThrottleParams =
    val c = cfg.getOrElse(AdaptiveThrottleConfig())
    val factors = c.factors
      .getOrElse(AdaptiveThrottle.DefaultFactors)
      .filter(f => f > 0.0 && f < 1.0)
      .map(f => math.round(f * 100).toInt)
      .distinct
      .sorted(Ordering[Int].reverse)
    ThrottleParams(
      enabled = c.isEnabled,
      factorsPct = if factors.isEmpty then AdaptiveThrottle.DefaultFactorsPct else factors,
      hardFloor = math.max(1, c.hardFloor.getOrElse(AdaptiveThrottle.DefaultHardFloor)),
      quietWindowMs = math.max(1000L, c.quietWindowMs.getOrElse(AdaptiveThrottle.DefaultQuietWindowMs)),
      accountCodes = c.accountUpstreamCodes.getOrElse(AdaptiveThrottle.DefaultAccountCodes),
      accountTextMarkers = c.accountTextMarkers.getOrElse(AdaptiveThrottle.DefaultAccountTextMarkers)
    )
end ThrottleParams

/**
 * 档位状态机——**纯函数单点**（spec 直测，零 IO）。档位阶梯：
 * rung 0 = 不限；rung 1..factorsPct.length = 对应因子档；rung factorsPct.length+1 =
 * 硬下限（终档）。
 *
 * 降档基线（refLimit）语义：本风暴周期进入 rung 1 时刻的在飞并行数 N（= 收到
 * 429 那一刻闸内实际并行数，含 429 请求本身——喂入点在许可释放之前）；周期内
 * 后续降档只推 rung 不重估基线（limit 单调不增）。新风暴（从不限档再降）重估。
 */
object AdaptiveThrottleMachine:

  final case class State(rung: Int, refLimit: Int, quietSinceMs: Long)

  final case class Ladder(factorsPct: List[Int], hardFloor: Int, quietWindowMs: Long)

  final case class Downgrade(oldLimit: Int, newLimit: Int, rung: Int, label: String, inFlight: Int)

  final case class Loosen(oldLimitDisp: String, newLimitDisp: String, rung: Int, label: String)

  /** 档位上限：rung 0 = 不限（Int.MaxValue）；因子档 = max(N×pct/100, 硬下限)（整数除法 = floor）。 */
  def limitAt(rung: Int, refLimit: Int, l: Ladder): Int =
    if rung <= 0 then Int.MaxValue
    else if rung <= l.factorsPct.length then math.max(refLimit * l.factorsPct(rung - 1) / 100, l.hardFloor)
    else l.hardFloor

  def runLabel(rung: Int, l: Ladder): String =
    if rung <= 0 then "100%"
    else if rung <= l.factorsPct.length then s"${l.factorsPct(rung - 1)}%"
    else s"floor(${l.hardFloor})"

  def disp(v: Int): String = if v >= Int.MaxValue then "unlimited" else v.toString

  /**
   * 一记账户级 429：
   *  - 已在硬下限终档 ⇒ 不再降（交给既有 health/retry 路径），仅重置静默钟
   *    （429 还在发生就没有回松资格）；
   *  - 否则 rung+1；从不限档降下时重估基线 refLimit = max(在飞 N, 硬下限)。
   */
  def onAccount429(s: State, inFlight: Int, nowMs: Long, l: Ladder): (State, Option[Downgrade]) =
    val floorRung = l.factorsPct.length + 1
    if s.rung >= floorRung then (s.copy(quietSinceMs = nowMs), None)
    else
      val newRung = s.rung + 1
      val ref = if s.rung <= 0 then math.max(inFlight, l.hardFloor) else s.refLimit
      val oldLimit = limitAt(s.rung, ref, l)
      val newLimit = limitAt(newRung, ref, l)
      (State(newRung, ref, nowMs), Some(Downgrade(oldLimit, newLimit, newRung, runLabel(newRung, l), inFlight)))

  /**
   * 静默回松：`nowMs - quietSinceMs >= quietWindowMs` ⇒ 升一档，静默钟**消耗**一个
   * 观察窗（quietSinceMs += window）——长静默按窗数逐档爬满后停在不限档，绝不
   * 一步跳回 100%。循环终止：rung 0 或静默不足一窗。返回步序 = 升档顺序。
   */
  def loosen(s: State, nowMs: Long, l: Ladder): (State, List[Loosen]) =
    @tailrec def loop(cur: State, acc: List[Loosen]): (State, List[Loosen]) =
      if cur.rung <= 0 then (cur, acc)
      else if nowMs - cur.quietSinceMs < l.quietWindowMs then (cur, acc)
      else
        val newRung = cur.rung - 1
        val oldLimit = limitAt(cur.rung, cur.refLimit, l)
        val newLimit = limitAt(newRung, cur.refLimit, l)
        val step = Loosen(disp(oldLimit), disp(newLimit), newRung, runLabel(newRung, l))
        loop(cur.copy(rung = newRung, quietSinceMs = cur.quietSinceMs + l.quietWindowMs), step :: acc)
    loop(s, Nil)
end AdaptiveThrottleMachine

/**
 * 过闸适配器包装（`ProviderRegistry.getAdapter` 返回值）：每次 sendMessage /
 * sendMessageStream 调用 = 一次过闸（acquire 在 HTTP 发出前，release 在响应
 * 终止/失败/取消后）——闸的粒度 = 单次 HTTP attempt，fallback 换 provider 时
 * 各自过各自的闸，账户键 = 候选 provider 的 baseUrl。
 */
private[llm] final class GatedAdapter(
  raw: ProviderAdapter[IO],
  key: String,
  throttle: AdaptiveThrottle
) extends ProviderAdapter[IO]:

  def sendMessage(params: SendMessageParams): IO[AdapterResponse] =
    throttle.acquire(key).bracket(_ => raw.sendMessage(params))(_.release)

  def sendMessageStream(params: SendMessageParams): fs2.Stream[IO, StreamChunk] =
    // bracket 挂流作用域：流终止（完成/失败/取消）即释放——在飞计数 = 真实并行量。
    fs2.Stream.bracket(throttle.acquire(key))(_.release) *> raw.sendMessageStream(params)
end GatedAdapter
