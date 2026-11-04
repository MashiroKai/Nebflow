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
        val candidates: IO[List[ModelCandidate]] =
          if registry == null then IO.pure(Nil) else registry.getCandidates()
        (for
          now <- IO(System.currentTimeMillis())
          keys <- candidates.map(_.map(c => key(c.providerId, c.model)).distinct)
          doc <- h.recoverAvoidGuardConservative(now, keys, Defaults.QuotaAvoidWindowMs)
          _ <- avoidUntilRef.update(m =>
            m ++ doc.avoidUntil.filter((_, until) => until > System.currentTimeMillis())
              .filterNot((k, _) => m.contains(k)))
          _ <- statesRef.update(s =>
            doc.down.foldLeft(s) { (acc, kv) =>
              acc.get(kv._1) match
                case Some(HealthState.Down(_, _)) | Some(HealthState.Up) => acc
                case None => acc.updated(kv._1, HealthState.Down(
                  s"recovered from guard (persisted down since ${kv._2})", kv._2))
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

  /** Deferred that completes when any provider transitions Down→Up. */
  private val signalRef: Ref[IO, Deferred[IO, Unit]] =
    Ref.unsafe(Deferred.unsafe[IO, Unit])

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

  /** Mark a candidate as Down. No-op if already Down. Persisted (B2：转换时写穿). */
  def markDown(providerId: String, model: String, reason: String): IO[Unit] =
    ensureGuards *> {
      val k = key(providerId, model)
      statesRef.get.flatMap { states =>
        states.get(k) match
          case Some(HealthState.Down(_, _)) => IO.unit
          case _ =>
            statesRef
              .set(states.updated(k, HealthState.Down(reason, System.currentTimeMillis())))
              .*>(logger.warn(s"Provider $k marked DOWN: $reason"))
              .*>(persistGuardDoc())
      }
    }

  /** Mark a candidate as Up and wake waiters. No-op if already Up. Persisted（B2）. */
  def markUp(providerId: String, model: String): IO[Unit] =
    ensureGuards *> {
      val k = key(providerId, model)
      statesRef.get.flatMap { states =>
        states.get(k) match
          case Some(HealthState.Down(_, _)) =>
            statesRef
              .set(states.updated(k, HealthState.Up))
              .*>(logger.info(s"Provider $k recovered (UP)"))
              .*>(signalRecovery())
              .*>(persistGuardDoc())
          case _ => IO.unit
      }
    }

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
    ensureGuards *> {
      for
        states <- statesRef.get
        avoids <- avoidUntilRef.get
      yield
        val now = System.currentTimeMillis()
        def avoided(c: ModelCandidate): Boolean =
          avoids.get(key(c.providerId, c.model)).exists(_ > now)
        val (eligible, _) = candidates.partition(c => !avoided(c))
        eligible.partition { c =>
          states.get(key(c.providerId, c.model)) match
            case Some(HealthState.Down(_, _)) => false
            case _ => true
        }
    }

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
        downCandidates <- downCandidates()
        _ <-
          if downCandidates.nonEmpty then downCandidates.parTraverse_(probe)
          else IO.unit
        _ <- IO.sleep(ProviderHealthMonitor.ProbeIntervalSec.seconds)
        _ <- loop
      yield ()
    loop
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
          val detail = Option(err.getMessage).getOrElse(err.getClass.getSimpleName)
          logger.debug(s"Probe failed for ${candidate.providerId}/${candidate.model}: $detail")
      }

  end probe

  /** Complete the current signal Deferred (wake all waiters). */
  private def signalRecovery(): IO[Unit] =
    signalRef.get.flatMap(_.complete(()).attempt.void)
end ProviderHealthMonitor
