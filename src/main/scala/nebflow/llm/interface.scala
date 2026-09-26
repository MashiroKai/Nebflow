package nebflow.llm

import cats.effect.IO
import cats.effect.kernel.{Deferred, Ref}
import cats.effect.std.Dispatcher
import cats.syntax.all.*
import nebflow.shared.{NebflowLogger, *}
import sttp.capabilities.fs2.Fs2Streams
import sttp.client4.StreamBackend
import sttp.client4.httpclient.fs2.HttpClientFs2Backend

import scala.concurrent.duration.*

/** Raised when a JVM shutdown hook aborts an in-flight LLM request. */
final class ShutdownAbort extends RuntimeException("Nebflow shutting down: LLM request aborted")

/**
 * Raised when TaskStuckWatcher hard-cancels an agent's in-flight LLM request
 * (gate-wedge P1-1, 2026-08-20): the agent ignored ≥2 Stop mailbox messages
 * because it was suspended on its LLM fiber, so the watcher escalates to
 * cancelling the fiber itself. Classified Fatal — the provider is NOT at
 * fault, no fallback/retry must re-send the full context; the agent's turn
 * fails and its own bounded retry loop (MaxTurnLlmCalls) takes over.
 */
final class StuckAbort(val sessionId: String)
    extends RuntimeException(
      s"TaskStuckWatcher hard-cancel: LLM request of session $sessionId aborted (agent unresponsive to Stop)"
    )

/**
 * Hard-recovery P6 (2026-09-07): raised when the transport of an in-flight LLM
 * request is force-aborted (per-request HttpClient shutdownNow — the only
 * primitive proven to unblock a parked body read, 取证 2026-09-07 §1.3) by
 * SessionKick (user message/interrupt on a wedged turn) or watcher L2. The
 * provider is NOT at fault — the stream-level classification is Fatal (no
 * provider fallback, same as StuckAbort: partial content must never be
 * stitched), but the AGENT layer treats it as retryable (re-send the whole
 * turn within the existing OverloadRetryMax/MaxTurnLlmCalls budget) or yields
 * to queued user input at the turn boundary — see AgentActor.llmFailureRetryable.
 */
final class RecoverableAbort(val sessionId: String)
    extends RuntimeException(
      s"transport abort: LLM request of session $sessionId force-aborted (recoverable — turn will be re-sent)"
    )

object LlmInterface:
  private val logger = NebflowLogger.forName("nebflow.llm")

  /**
   * Test hook (issue #31 Fix B spec): overrides the whole-stream no-progress
   * window (Defaults.LlmStreamNoProgressTimeoutSec) so the outer watchdog can
   * be exercised in tests without waiting 600s. Global var — specs MUST reset
   * it to None in a finally.
   */
  private[llm] var noProgressTimeoutOverride: Option[FiniteDuration] = None

  /**
   * Test hook (fallback seam spec): overrides the per-provider two-phase
   * watchdog windows (Defaults.LlmFirstTokenTimeoutSec /
   * LlmStreamInactivitySec) — (firstToken, subsequent). Global var — specs
   * MUST reset it to None in a finally.
   */
  private[llm] var streamInactivityOverride: Option[(FiniteDuration, FiniteDuration)] = None

  /**
   * Flow-node supervision P3 (2026-08-26): apply llm.streamTimeouts config
   * overrides (boot-time, from GatewayMain). Each window is independently
   * optional — unspecified windows keep the Defaults value. Not a test hook:
   * this is the production config entry point (tests keep using the raw vars).
   */
  def applyStreamTimeouts(
    firstTokenSec: Option[Int],
    inactivitySec: Option[Int],
    noProgressSec: Option[Int]
  ): Unit =
    if firstTokenSec.isDefined || inactivitySec.isDefined then
      streamInactivityOverride = Some(
        (
          firstTokenSec.map(_.seconds).getOrElse(Defaults.LlmFirstTokenTimeoutSec.seconds),
          inactivitySec.map(_.seconds).getOrElse(Defaults.LlmStreamInactivitySec.seconds)
        )
      )
    noProgressTimeoutOverride = noProgressSec.map(_.seconds)

  // ── In-flight LLM request registry (shutdown abort, Task 2 2026-08-19) ──
  // Every active sendStream registers an abort signal here; a JVM shutdown
  // hook (Main.startGateway) and the GatewayMain graceful-cleanup guarantee
  // complete all signals so in-flight FS2/sttp HTTP requests abort instead of
  // burning tokens while the JVM drains after Ctrl+C. Registry is a global
  // singleton because the hook has no handle
  // reference — it must reach every stream regardless of which LlmHandle ran
  // it. Completing a Deferred from another runtime (IORuntime.global in the
  // hook thread) is safe: it only wakes the waiters, which continue on their
  // own runtime.
  private final case class InflightEntry(
    sessionId: Option[String],
    halt: Deferred[IO, Either[Throwable, Unit]],
    // Hard-recovery P1 (2026-09-07): per-attempt transport abort thunk. None
    // until (and unless) the attempt's per-request HttpClient is created and
    // attached — see attachTransportAbort / makeAttemptTransport.
    abortRef: Ref[IO, Option[IO[Unit]]]
  )

  private val inflight: Ref[IO, Map[String, InflightEntry]] =
    Ref.unsafe(Map.empty)

  def registerInflight(
    sessionId: Option[String] = None
  ): IO[(String, Deferred[IO, Either[Throwable, Unit]])] =
    for
      key <- IO(java.util.UUID.randomUUID().toString)
      halt <- IO.deferred[Either[Throwable, Unit]]
      abortRef <- IO.ref(Option.empty[IO[Unit]])
      _ <- inflight.update(_ + (key -> InflightEntry(sessionId, halt, abortRef)))
    yield (key, halt)

  /**
   * Hard-recovery P1: attach (or replace) the transport abort action for an
   * in-flight request. Called by sendStream's per-attempt transport setup.
   */
  private[llm] def attachTransportAbort(key: String, abort: IO[Unit]): IO[Unit] =
    setAbort(key, abort)

  private def setAbort(key: String, abort: IO[Unit]): IO[Unit] =
    inflight.get.flatMap { m =>
      m.get(key) match
        case Some(e) => e.abortRef.set(Some(abort))
        case None => IO.unit
    }

  private[llm] def unregisterInflight(key: String): IO[Unit] =
    inflight.update(_ - key)

  /**
   * gate-wedge P1-1: hard-cancel every in-flight LLM request belonging to a
   * session — wakes queued and streaming requests
   * alike (the interrupt wraps the whole candidate stream, not just the HTTP
   * layer). Returns how many requests were aborted. Used by TaskStuckWatcher
   * after repeated Stop mailbox messages went unconsumed.
   */
  def cancelInflightFor(sessionId: String): IO[Int] =
    inflight.get.flatMap { m =>
      val matching = m.toList.collect { case (k, e) if e.sessionId.contains(sessionId) => (k, e) }
      matching.traverse_ { case (_, e) =>
        e.halt.complete(Left(new StuckAbort(sessionId))).void.handleErrorWith(_ => IO.unit)
      } *> IO.pure(matching.size)
    }

  /**
   * Hard-recovery P1/P4 (2026-09-07): L2 transport abort — force-abort every
   * in-flight LLM request belonging to a session at the TRANSPORT level
   * (per-request HttpClient shutdownNow). This is the only primitive proven
   * to unblock a fiber parked on the JDK HttpClient body read (半开连接 —
   * fs2 cancellation and the halt Deferred can only act at step boundaries,
   * which a parked read never crosses; 取证 2026-09-07 §1.3). Also completes
   * the halt with RecoverableAbort (belt: whichever surfaces first wins; the
   * per-attempt abortedRef mapping in sendStream makes the surfaced error
   * deterministically RecoverableAbort). No-op (0 aborted) when
   * PerRequestTransport is disabled — callers verify and escalate (设计 P2).
   */
  def transportAbortFor(sessionId: String): IO[Int] =
    inflight.get.flatMap { m =>
      val matching = m.toList.collect { case (k, e) if e.sessionId.contains(sessionId) => (k, e) }
      matching.traverse_ { case (_, e) =>
        e.abortRef.get.flatMap {
          case Some(abort) => abort.attempt.void
          case None => IO.unit
        } *>
          // Belt: if the transport kill raced the stream past a step boundary,
          // interruptWhen surfaces this instead of the raw IOException.
          e.halt.complete(Left(new RecoverableAbort(sessionId))).void.handleErrorWith(_ => IO.unit)
      } *> IO.pure(matching.size)
    }

  /**
   * stuck 自动恢复批 P1（2026-09-11）：**只读** per-session 在飞 LLM 请求数。
   *
   * 与 [[cancelInflightFor]] / [[transportAbortFor]] 的关键差异：本方法是**纯读**
   * ——不完成任何 halt、不触发任何 abort、不改 registry，可在「判定前分流」这类
   * 只观测不动作的位置安全使用（此前判据序要判「本会话是否有在飞 LLM」只能靠
   * 破坏性的 cancel 调用反推，设计 §6.1 未证项 6 即此）。
   *
   * 消费点（唯一）：`TaskStuckWatcher.classify` 的**判据序第一档**——`> 0` ⇒ 归
   * 类④ provider hang（LLM 层三档看护自管），watcher 本拍零动作（设计 §2.1）。
   */
  def inflightFor(sessionId: String): IO[Int] =
    inflight.get.map(_.count { case (_, e) => e.sessionId.contains(sessionId) })

  /** Abort all in-flight LLM requests — streams fail with [[ShutdownAbort]]. */
  def cancelAllInflight(): IO[Unit] =
    inflight.get.flatMap { m =>
      m.values.toList.traverse_(_.halt.complete(Left(new ShutdownAbort))) *> inflight.set(Map.empty)
    }

  /** Hot-restart quiesce (F4 域，hot-restart 批)：在飞 LLM 请求数。空闲判定 = 0。 */
  def inflightCount: IO[Int] = inflight.get.map(_.size)

  /** Synchronous variant for JVM shutdown hooks (runs on IORuntime.global). */
  def cancelAllInflightSync(): Unit =
    try cancelAllInflight().unsafeRunSync()(using cats.effect.unsafe.implicits.global)
    catch case _: Throwable => ()

  /**
   * Best-effort abort of a JDK HttpClient: calls `shutdownNow()` (JDK 21+),
   * falling back to `close()` (also JDK 21+ — HttpClient became AutoCloseable
   * in 21). Both are 21+ APIs and are reached **reflectively** because this
   * source tree compiles with `-release:17` (see build.sbt): the jar must load
   * on a 17 JVM so the "requires Java 21" banner can print and
   * `update`/`doctor`/`version` stay usable, which means no 21+ API may appear
   * in the compile-time surface. On a ≤20 JVM both calls are best-effort
   * no-ops; the abort path only has real work to do on the supported 21+
   * runtime. Without the reflective indirection, `httpClient.shutdownNow()`
   * compiles fine on a 21 build but throws `NoSuchMethodError` at runtime below
   * 21 (real Windows machine, CI packaging), and that error is swallowed by
   * the shutdown hook's `catch case _: Throwable => ()` → Ctrl+C inflight-abort
   * silently no-ops.
   */
  private[llm] def abortHttpClient(client: java.net.http.HttpClient): Unit =
    val _ = if !invokeNoArg(client, "shutdownNow") then invokeNoArg(client, "close")

  /**
   * `HttpClient#close()` is JDK 21+ (AutoCloseable) — routed through reflection
   * so the call site compiles under `-release:17`. No-op on ≤20 (method absent).
   */
  private[llm] def closeHttpClient(client: java.net.http.HttpClient): Unit =
    invokeNoArg(client, "close")

  /**
   * Invoke a no-arg method on a JDK HttpClient by name; `false` = the method is
   * absent on this JVM (older release) or the invocation itself threw. The
   * caller decides whether to fall back to another primitive.
   */
  private def invokeNoArg(target: java.net.http.HttpClient, method: String): Boolean =
    try
      classOf[java.net.http.HttpClient].getMethod(method).invoke(target)
      true
    catch
      case _: NoSuchMethodException => false
      case _: java.lang.reflect.InvocationTargetException => false
      case scala.util.control.NonFatal(_) => false

  // ── Hard-recovery P1: per-attempt transport (设计 D-1 方案 A, 2026-09-07) ──

  private[llm] final case class AttemptTransport(
    backend: StreamBackend[IO, Fs2Streams[IO]],
    release: IO[Unit]
  )

  /**
   * A dedicated HttpClient + fs2 backend + dispatcher per streaming attempt.
   * shutdownNow() on this client aborts exactly ONE request — the only
   * primitive that provably unblocks a fiber parked on the JDK body read
   * (半开连接; 取证 2026-09-07: 22min hang, all fiber-level cancellation
   * deferred at step boundaries, only the shutdown hook's shutdownNow
   * unwedged it). The abort thunk is registered on the inflight entry so
   * [[transportAbortFor]] can reach it (L2 / SessionKick); it flips
   * abortedRef FIRST so whatever error the kill surfaces re-raises
   * deterministically as RecoverableAbort. None when
   * Defaults.PerRequestTransport is disabled (legacy shared client, L2
   * degrades to no-op → callers escalate). Cost: one TLS handshake + one
   * selector thread per request (~100-300ms, negligible next to LLM
   * latency — 设计 §2.2).
   *
   * D1 — the HTTP/1.1 pin on the per-attempt client below, and what would
   * flip it: this peer is the LLM gateway, a DIFFERENT reverse proxy from
   * neblink's Caddy and the closest one to the original 2026-08-11 upstream
   * (nginx/one-api) incident ⇒ the neblink 2026-09-12 probe (14/14 HTTP_2
   * 200, 0 TLS alerts) does not transfer here. What is measured on this peer
   * since the incident: nothing — the original alert text and frequency were
   * never retained ⇒ the pin is UNPROVEN, not refuted. Judge-red: a
   * reproduced bad_record_mac / TLS alert on this path, or a GOAWAY /
   * closed-reset bucket in the outbound-failure counters.
   * This client stays per-attempt by design (its shutdownNow() is the abort
   * primitive described above), so it is NOT a D5 convergence candidate.
   */
  private[llm] def makeAttemptTransport(
    key: String,
    sessionId: String,
    abortedRef: Ref[IO, Boolean]
  ): IO[Option[AttemptTransport]] =
    if !Defaults.PerRequestTransport then IO.pure(None)
    else
      Dispatcher.parallel[IO].allocated.flatMap { case (dispatcher, releaseDispatcher) =>
        val client = java.net.http.HttpClient
          .newBuilder()
          .version(java.net.http.HttpClient.Version.HTTP_1_1)
          .connectTimeout(java.time.Duration.ofSeconds(Defaults.LlmConnectTimeoutSec.toLong))
          .build()
        val backend = HttpClientFs2Backend.usingClient[IO](client, dispatcher)
        val abort = abortedRef.set(true) *> IO(abortHttpClient(client)).attempt.void
        val release =
          IO(abortHttpClient(client)).attempt.void *>
            IO(closeHttpClient(client)).attempt.void *>
            releaseDispatcher
        setAbort(key, abort).as(Some(AttemptTransport(backend, release)))
      }

  // ── Vision PreCheck helpers ────────────────────────────

  /** Check if any message in the list contains an Image content block. */
  private[llm] def hasImage(messages: List[Message]): Boolean =
    messages.exists(_.content match
      case Right(blocks) => blocks.exists(_.isInstanceOf[ContentBlock.Image])
      case Left(_) => false)

  /** Replace all Image blocks with a text placeholder (for non-vision models). */
  private[llm] def stripImages(messages: List[Message]): List[Message] =
    messages.map { msg =>
      msg.content match
        case Right(blocks) =>
          val stripped = blocks.map {
            case ContentBlock.Image(_, _) =>
              ContentBlock.Text("[image omitted: model does not support vision]")
            case other => other
          }
          msg.copy(content = Right(stripped))
        case Left(_) => msg
    }

  /**
   * Two-phase stream watchdog:
   *   Phase 1 (firstToken): no chunks received yet — if no chunk arrives within
   *     `firstToken`, the stream is considered hung (connection dead / provider down).
   *   Phase 2 (subsequent): chunks received — if no chunk for `subsequent` duration,
   *     the stream stalled mid-generation.
   *
   * Resets the timer on each element. Uses System.currentTimeMillis() (not nanoTime)
   * because nanoTime freezes during Mac sleep/wake.
   */
  private[llm] def inactivityTimeout[O](
    firstToken: FiniteDuration,
    subsequent: FiniteDuration,
    transientPhase2: Boolean = false,
    // 时间基修正缝（hostresume 批 2026-09-22，设计卡 §4 #5，D-6 首批消费点之一）：
    // 默认恒等（裸差值 = 现状逐字节）；仅整流 no-progress 守卫调用点注入
    // `PowerStateTracker.effectiveElapsed` 扣减宿主睡眠冻结秒。每供应商 120s Transient
    // 看门狗调用点零改动（卡「明确不改」：跨睡眠快速失败 + 重试正是软着陆本体）。
    effectiveElapsed: (Long, Long) => Long = (startMs, nowMs) => nowMs - startMs
  ): fs2.Pipe[IO, O, O] =
    val firstEx = new java.util.concurrent.TimeoutException(
      s"LLM stream: no response within ${firstToken.toSeconds}s"
    )
    // Phase-2 exception shape depends on the caller (flow-node supervision P1):
    //  - per-provider stream watchdog (transientPhase2=true): StreamInactivityTimeout
    //    → Transient. A mid-stream stall is upstream jitter, not a dead provider.
    //  - whole-stream 600s no-progress guard (default): plain TimeoutException
    //    → Permanent. A system-level hang must keep the strict fail-fast path.
    val phase2Ex: Long => Throwable =
      if transientPhase2 then age => StreamInactivityTimeout(age, s"LLM stream inactive for ${subsequent.toSeconds}s")
      else _ => new java.util.concurrent.TimeoutException(s"LLM stream inactive for ${subsequent.toSeconds}s")
    in =>
      fs2.Stream.eval(IO.ref(System.currentTimeMillis())).flatMap { lastActivity =>
        fs2.Stream.eval(IO.ref(false)).flatMap { gotFirst =>
          val main = in.evalTap(_ => lastActivity.set(System.currentTimeMillis()) *> gotFirst.set(true))
          // Check interval: use the shorter timeout / 5 (min 2s, max 30s)
          val shorterMs = math.min(firstToken.toMillis, subsequent.toMillis)
          val checkInterval = math.max(math.min(shorterMs / 5, 30000L), 500L).millis
          val watchdog = fs2.Stream
            .awakeEvery[IO](checkInterval)
            .evalMap { _ =>
              IO(System.currentTimeMillis()).flatMap { now =>
                lastActivity.get.flatMap { last =>
                  gotFirst.get.flatMap { first =>
                    val now2 = now
                    if first then
                      val age = effectiveElapsed(last, now2)
                      if age > subsequent.toMillis then IO.raiseError(phase2Ex(age))
                      else IO.unit
                    else if effectiveElapsed(last, now2) > firstToken.toMillis then IO.raiseError(firstEx)
                    else IO.unit
                  }
                }
              }
            }
            .drain
          main.concurrently(watchdog)
        }
      }

  end inactivityTimeout

  def createLlm(
    sessionOverrides: Ref[IO, Map[String, ModelCandidate]],
    options: Option[LlmOptions] = None,
    configRef: Option[Ref[IO, NebflowServiceConfig]] = None
  ): IO[(LlmHandle[IO], ProviderRegistry, ProviderHealthMonitor, IO[Unit])] =
    // HTTP/1.1 pin (D1) — evidence, then judge-red:
    //   · Evidence: the 2026-08-11 upstream gateway incident (commit 3773699b —
    //     bad_record_mac TLS alerts on reused multiplexed connections; curl
    //     with a fresh connection per request never triggered it). That is an
    //     incident report, not a reproduction: the alert text and its
    //     frequency were never retained, and this peer (nginx/one-api style API
    //     gateway) has not been re-probed since ⇒ 未证. Do not read the
    //     neblink/Caddy probe (2026-09-12: 14/14 HTTP_2 200, 0 TLS alerts) as
    //     evidence here — different reverse proxy, and one green local window
    //     is not evidence a trap is absent.
    //   · Judge-red: a reproduced bad_record_mac / TLS alert on this path, or a
    //     GOAWAY / closed-reset bucket in the outbound-failure counters, or
    //     same-window h2 p95 > h1 p95 × 1.2 (n ≥ 100/arm). Only then revisit
    //     the pin; the documented next diagnostic step is disabling TLS 1.3
    //     session resumption (a switch for diagnosing, not a speed-up: it
    //     costs ~222 ms per cold request).
    val httpClient = java.net.http.HttpClient
      .newBuilder()
      .version(java.net.http.HttpClient.Version.HTTP_1_1)
      // Hard-recovery §2.2 配套小修: bound TCP establishment — a half-open
      // dial after a VPN switch must fail within 30s, not hang forever (the
      // JDK default connect timeout is infinite).
      .connectTimeout(java.time.Duration.ofSeconds(Defaults.LlmConnectTimeoutSec.toLong))
      .build()
    Dispatcher.parallel[IO].allocated.flatMap { case (dispatcher, releaseDispatcher) =>
      val backend = HttpClientFs2Backend.usingClient[IO](httpClient, dispatcher)
      // Release is idempotent: both the graceful `.guarantee` path (server
      // stopped normally) and the JVM shutdown hook (Ctrl+C / SIGTERM) call
      // it, and either may win the race. The guard makes the second call a
      // no-op. Order matters — abort in-flight HTTP FIRST:
      //   1. httpClient.shutdownNow() — JDK 21+ API. Aborts non-completed
      //      requests at the TCP level (closes the selector + channels), so
      //      the provider stops generating/billing the response for a request
      //      whose turn is already gone. Before this, Ctrl+C ran only the
      //      daemon hook: the sttp backend never closed the client
      //      (usingClient sets closeClient=false → backend.close() is a no-op)
      //      and in-flight FS2/sttp exchanges kept running until the JVM
      //      halted on its own — token spend continued.
      //      Below 21 the method doesn't exist — reflection probe degrades to
      //      close() (also JDK 21+, so a no-op there). close() waits for
      //      in-flight exchanges to complete rather than aborting them, but at
      //      least releases resources. Both calls go through reflection (see
      //      abortHttpClient / closeHttpClient) because this tree compiles with
      //      -release:17 so the jar stays loadable on a 17 JVM — without it the
      //      raw call compiles on a 21 build but throws NoSuchMethodError / is
      //      an API-surface violation.
      //   2. closeHttpClient(httpClient) — best-effort close of cached/open connections.
      //   3. backend.close() — no-op for a user-provided client, kept for
      //      symmetry in case a backend-owned client is introduced later.
      //   4. releaseDispatcher — cancels dispatcher fibers.
      val doRelease: IO[Unit] =
        IO(abortHttpClient(httpClient)) *>
          IO(closeHttpClient(httpClient)) *>
          IO(backend.close()) *>
          releaseDispatcher
      val releasedRef: Ref[IO, Boolean] = Ref.unsafe(false)
      val release: IO[Unit] =
        releasedRef.modify {
          case false => (true, doRelease)
          case true => (true, IO.unit)
        }.flatten
      IO.pure(backend).flatMap { backend =>
        val config = Config.loadServiceConfig(options.flatMap(_.configPath))
        val cfgRef: Ref[IO, NebflowServiceConfig] = configRef.getOrElse(Ref.unsafe(config))
        val registry = ProviderRegistry(cfgRef, backend)
        val healthMonitor = ProviderHealthMonitor(registry)
        val emptyTracker = EmptyCompletionTracker.shared
        val result =

          /**
           * WebSearch P0: provider-native search injection for one candidate
           * — resolved per-candidate so a fallback switch drops the previous
           * provider's injection. The gate semantics (searchAllowed /
           * tools.isDefined / OpenAI-only) live in the pure resolver and
           * are unit-tested there (SearchProviderResolver.searchInjectionFor).
           */
          def searchInjectionFor(
            req: LlmRequest,
            candidate: ModelCandidate
          ): Option[ProviderSearchKind] =
            SearchProviderResolver.searchInjectionFor(
              req.searchAllowed,
              req.tools,
              candidate.provider.protocol,
              candidate.providerId,
              candidate.provider.baseUrl
            )

          val handle = new LlmHandle[IO]:
            def send(req: LlmRequest): IO[LlmResponse] =
              val start = System.currentTimeMillis()
              (for
                overrides <- sessionOverrides.get
                regCandidates <- registry.getCandidatesForAgent(req.agentModel)
                candidates = overrides.get(req.sessionId).toList ++ regCandidates
                  .filterNot(c =>
                    overrides.get(req.sessionId).exists(o => o.providerId == c.providerId && o.model == c.model)
                  )
                // #33: drop stale session overrides whose provider was removed
                // by a config hot-reload — graceful skip, never "Unknown provider".
                filtered <- registry.filterKnownProviders(candidates)
                (up, _) <- healthMonitor.filterCandidates(filtered)
                _ <- IO.raiseWhen(up.isEmpty)(new RuntimeException("All providers unavailable"))
                result <- Fallback.tryProviderWithFallback[AdapterResponse](
                  up,
                  candidate =>
                    val cappedThinking = req.thinking.map { t =>
                      t.hcursor.downField("budget_tokens").as[Int] match
                        // Clamp at the internal ceiling (maxcfg batch 2026-09-16): the budget
                        // no longer follows the removed per-model `maxTokens` config — the old
                        // `maxTokens / 2` clamp cut "high" thinking (32768) to 8192 with the
                        // default config, which OpenAiAdapter.budgetToEffort then read as the
                        // "medium" effort class. Defaults.MaxThinkingBudget equals the highest
                        // budget the product can produce, so no reachable configuration is
                        // clamped; the clamp still bounds legacy / hand-edited values (the
                        // original reason it exists: providers such as zhipu/glm crash when
                        // budget_tokens exceeds their limit).
                        case Right(budget) if budget > Defaults.MaxThinkingBudget =>
                          t.deepMerge(
                            io.circe.Json.obj(
                              "budget_tokens" -> io.circe.Json.fromInt(Defaults.MaxThinkingBudget)
                            )
                          )
                        case _ => t
                    }
                    // PreSendChecker: strip images for non-vision models.
                    // Also consult the runtime vision override from EmptyCompletionTracker,
                    // which can demote a config-vision model to non-vision at runtime.
                    for
                      runtimeVision <- emptyTracker.getRuntimeVision(candidate.providerId, candidate.model)
                      effectiveVision = candidate.vision && runtimeVision.getOrElse(true)
                      effectiveMessages =
                        if !effectiveVision && hasImage(req.messages) then stripImages(req.messages)
                        else req.messages
                      adapter <- registry.getAdapter(candidate.providerId)
                      resp <- adapter.sendMessage(
                        SendMessageParams(
                          effectiveMessages,
                          candidate.model,
                          req.tools,
                          cappedThinking,
                          req.systemStable,
                          req.systemDynamic,
                          Some(req.sessionId),
                          Some(req.agentId),
                          searchInjectionFor(req, candidate)
                        )
                      )
                      // On success, clear the empty-completion counter.
                      // Oscillation fix: only an image-bearing success lifts
                      // a vision=false override; after stripImages the
                      // success proves nothing about vision.
                      _ <- emptyTracker.resetOnSuccess(
                        candidate.providerId,
                        candidate.model,
                        hadImage = hasImage(effectiveMessages)
                      )
                    yield resp
                    end for
                  ,
                  onAttempt = None,
                  onProviderExhausted = Some(c => healthMonitor.markDown(c.providerId, c.model, "provider exhausted"))
                )
              yield result).flatMap { result =>
                val durationMs = System.currentTimeMillis() - start
                val failedAttempts = result.attempts.filter(_.reason.isDefined)
                val fallbackChain =
                  if failedAttempts.nonEmpty then
                    Some(
                      result.attempts
                        .map(a => FallbackStep(a.providerId, a.model, a.reason.map(_.toString), a.durationMs))
                    )
                  else None

                IO.pure(
                  LlmResponse(
                    reply = result.data.reply,
                    toolCalls = result.data.toolCalls,
                    usage = result.data.usage,
                    meta = LlmMeta(
                      sessionId = req.sessionId,
                      agentId = req.agentId,
                      providerId = result.usedCandidate.providerId,
                      model = result.usedCandidate.model,
                      durationMs = durationMs,
                      fallbackChain = fallbackChain,
                      contextWindow = Some(result.usedCandidate.contextWindow)
                    ),
                    searchInfo = result.data.searchInfo
                  )
                )
              }
            end send

            def sendStream(
              req: LlmRequest,
              onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
            ): fs2.Stream[IO, StreamChunk] =
              // Shutdown-abort wiring (Task 2): register an abort signal for
              // this request; a JVM shutdown hook / graceful-cleanup guarantee
              // completes it, interrupting the stream (and the underlying
              // sttp/FS2 HTTP request) with ShutdownAbort. Unregistered on
              // finalize whether the stream completes, fails or is aborted.
              fs2.Stream
                .eval(
                  registerInflight(Some(req.sessionId)).flatTap { case (key, _) =>
                    // Intake trace — makes "request accepted but never fired"
                    // visible in the sse logs.
                    nebflow.core.LlmLogWriter.logIntake(key, req.sessionId, req.agentId)
                  }
                )
                .flatMap { case (key, halt) =>
                  fs2.Stream
                    .eval(
                      for
                        overrides <- sessionOverrides.get
                        regCandidates <- registry.getCandidatesForAgent(req.agentModel)
                        candidates = overrides.get(req.sessionId).toList ++ regCandidates
                          .filterNot(c =>
                            overrides.get(req.sessionId).exists(o => o.providerId == c.providerId && o.model == c.model)
                          )
                        // #33: same graceful skip as the non-streaming path.
                        filtered <- registry.filterKnownProviders(candidates)
                      yield filtered
                    )
                    .flatMap { candidates =>

                      val maxRetries = Fallback.MaxRetries

                      fs2.Stream.eval(IO.ref(false)).flatMap { lockedRef =>
                        fs2.Stream.eval(IO.ref(List.empty[FallbackAttempt])).flatMap { failureRef =>
                          fs2.Stream.eval(IO.ref(Option.empty[ModelCandidate])).flatMap { winnerRef =>
                            // PreSendChecker + PostEmptyRecovery state
                            fs2.Stream.eval(IO.ref(req.messages)).flatMap { messagesRef =>
                              fs2.Stream.eval(IO.ref(false)).flatMap { imageStrippedRef =>

                                /**
                                 * 案① A1：候选身份键（`providerId/model`）——「本轮已尝试候选集合」
                                 * 的元素形态，只用于事实面（日志/终局错误）与空集护栏。
                                 */
                                def candidateKey(c: ModelCandidate): String = s"${c.providerId}/${c.model}"

                                // Health-check wrapper: filters candidates by health state.
                                // If all are Down, notifies the frontend and blocks until
                                // at least one provider recovers, then re-filters.
                                //
                                // 案① A2 (chain-llmstall-fix, 2026-09-21 作者绿灯)：本 wrapper 携带
                                // **全链失败轮次** `round`（1-based）。它现在只从 tryCandidate 的
                                // 「本轮全部候选都已被试过」出口被再次进入，且该出口带轮次上限
                                // （见 tryCandidate `case Nil`）⇒ 入口调用点（下方 `attemptWithHealthCheck`）
                                // 用默认轮次 1 起跑。
                                def attemptWithHealthCheck(round: Int = 1): fs2.Stream[IO, StreamChunk] =
                                  fs2.Stream.eval(healthMonitor.filterCandidates(candidates)).flatMap {
                                    case (Nil, down) =>
                                      val notifyDown = onAttempt.traverse_(
                                        _.apply(
                                          FallbackAttempt(
                                            providerId = "",
                                            model = "",
                                            reason = None,
                                            permanence = None,
                                            durationMs = 0,
                                            retriesUsed = 0,
                                            timestamp = java.time.Instant.now().toString,
                                            message = Some("所有模型不可用，等待恢复中...")
                                          )
                                        )
                                      )
                                      // Probe Down candidates immediately instead of waiting for the
                                      // next background cycle — cuts worst-case recovery from ~2min to ~15s.
                                      fs2.Stream
                                        .eval(
                                          notifyDown *> healthMonitor.probeNow(down) *> healthMonitor
                                            .waitForAnyUp(candidates)
                                            // All candidates Down and none recovered within one probe
                                            // cycle — surface the failure instead of blocking forever.
                                            // Re-mapped to AllProvidersDownTimeout (Transient) so the
                                            // agent-level llm-fail retry fires; a raw TimeoutException
                                            // is classified Permanent and would kill the turn.
                                            .timeout(ProviderHealthMonitor.ProbeIntervalSec.seconds)
                                            .adaptError { case _: java.util.concurrent.TimeoutException =>
                                              new AllProvidersDownTimeout(
                                                ProviderHealthMonitor.ProbeIntervalSec * 1000L
                                              )
                                            }
                                        )
                                        .flatMap { _ =>
                                          val notifyUp = onAttempt.traverse_(
                                            _.apply(
                                              FallbackAttempt(
                                                providerId = "",
                                                model = "",
                                                reason = Some(FailoverReason.Unknown),
                                                permanence = None,
                                                durationMs = 0,
                                                retriesUsed = 0,
                                                timestamp = java.time.Instant.now().toString,
                                                message = Some("模型已恢复，继续处理...")
                                              )
                                            )
                                          )
                                          fs2.Stream.eval(notifyUp).drain ++ attemptWithHealthCheck(round)
                                        }
                                    case (up, _) =>
                                      // 新一轮：已尝试集合清空（`remaining = up` 是本链的**全量**候选），
                                      // 轮次原样传入——轮次只在「一轮全部候选试完」时递增。
                                      tryCandidate(up, maxRetries, Fallback.InitialBackoffMs, Set.empty, round)
                                  }

                                def tryCandidate(
                                  remaining: List[ModelCandidate],
                                  retriesLeft: Int = maxRetries,
                                  backoffMs: Long = Fallback.InitialBackoffMs,
                                  // 案① A1（chain-llmstall-fix）：本轮**已尝试候选集合**（`provider/model`）。
                                  // 只增不减、只在换轮时清空；用途 = ① 终局判定「这轮到底试没试过东西」
                                  // （空集 = 一次都没试 ⇒ 不得再进健康检查环，直接终态）② 终局日志/错误的
                                  // 事实面（「试过谁」可读）。
                                  attempted: Set[String] = Set.empty,
                                  // 案① A1/A2：全链失败轮次（1-based）。与 attemptWithHealthCheck 的
                                  // `round` 同源——见其 `case Nil` 出口。
                                  round: Int = 1
                                ): fs2.Stream[IO, StreamChunk] =
                                  remaining match
                                    case Nil =>
                                      // 本轮所有候选都已试过（或本轮起始候选集为空）。
                                      //
                                      // 病（2026-09-21 硬杀波定谳）：全链 provider 永久错误（400 Format ⇒
                                      // fallback.scala:107 `evict=false`）时无人被 markDown ⇒
                                      // `attemptWithHealthCheck` 的 filterCandidates 返回**同一全链** ⇒
                                      // `tryCandidate(up)` 重投全链 ⇒ 无计数 / 无退避 / 无终态的闭环：
                                      // 不产 chunk、不抛错 ⇒ AgentActor.lastActivityMs 冻结 ⇒
                                      // TaskStuckWatcher 在 600s 判 agent-stale ⇒ L1 hard-cancel（StuckAbort）
                                      // ⇒ 节点 failed。现场读数：单日 11,102 条 `permanent error (Format)`、
                                      // 13 个节点同型被杀。
                                      //
                                      // 停药（A1+A2）：轮次上限 + 「本轮一枚都没试过」的 fail-closed 护栏。
                                      // 上限 = Fallback.MaxChainRounds（≥2 ⇒ 既有 FormatErrorNoEvictSpec T1
                                      // 「A 命中恰好 1 次 + B 成功」的**轮内**语义逐字不变）。
                                      if round >= Fallback.MaxChainRounds || attempted.isEmpty then
                                        fs2.Stream.eval(failureRef.get).flatMap { failures =>
                                          fs2.Stream.eval(
                                            logger.warn(
                                              s"Stream: all candidates exhausted after $round round(s) " +
                                                s"(cap ${Fallback.MaxChainRounds}) — failing fast instead of " +
                                                s"re-entering the health-check loop " +
                                                s"[providers attempted: ${failures.size} attempt(s), " +
                                                s"candidates: ${attempted.toList.sorted.mkString(", ")}]"
                                            )
                                          ) *> fs2.Stream.raiseError[IO](new FallbackExhaustedError(failures))
                                        }
                                      else
                                        // 尚有余轮：进健康检查（全员 Down 时阻塞等待恢复），换轮重试。
                                        attemptWithHealthCheck(round + 1)
                                    case candidate :: rest =>
                                      // Clamp the thinking budget at the internal ceiling (maxcfg batch
                                      // 2026-09-16). The budget no longer follows the removed
                                      // per-model `maxTokens` config: the old `maxTokens / 2` clamp
                                      // (8192 with the old default) silently downgraded "high"
                                      // thinking (32768) to the "medium" effort class via
                                      // OpenAiAdapter.budgetToEffort, and left the Anthropic face with
                                      // a budget that no longer matched its output cap.
                                      // Defaults.MaxThinkingBudget = the highest budget the product can
                                      // produce ⇒ no reachable configuration is clamped; the clamp
                                      // still bounds legacy / hand-edited values (its original
                                      // purpose: providers such as zhipu/glm crash when budget_tokens
                                      // exceeds their limit).
                                      val cappedThinking = req.thinking.map { t =>
                                        t.hcursor.downField("budget_tokens").as[Int] match
                                          case Right(budget) if budget > Defaults.MaxThinkingBudget =>
                                            t.deepMerge(
                                              io.circe.Json.obj(
                                                "budget_tokens" -> io.circe.Json.fromInt(Defaults.MaxThinkingBudget)
                                              )
                                            )
                                          case _ => t
                                      }
                                      // Hard-recovery P1/P6 (2026-09-07): per-attempt transport
                                      // (per-request HttpClient, 设计 D-1 方案 A) + deterministic
                                      // RecoverableAbort mapping. abortedRef flips BEFORE the client
                                      // is killed, so whichever error the transport kill surfaces
                                      // (read IOException racing interruptWhen) re-raises as
                                      // RecoverableAbort instead of relying on IOException message
                                      // matching.
                                      fs2.Stream.eval(IO.ref(false)).flatMap { abortedRef =>
                                        val stream = fs2.Stream
                                          .force(
                                            (for
                                              // PreSendChecker: strip images for non-vision models
                                              // Also check runtime vision override from EmptyCompletionTracker
                                              msgs <- messagesRef.get
                                              runtimeVision <- emptyTracker
                                                .getRuntimeVision(candidate.providerId, candidate.model)
                                              effectiveVision = candidate.vision && runtimeVision.getOrElse(true)
                                              effectiveMessages =
                                                if !effectiveVision && hasImage(msgs) then stripImages(msgs) else msgs
                                              adapter <- registry.getAdapter(candidate.providerId)
                                              transportOpt <- makeAttemptTransport(key, req.sessionId, abortedRef)
                                            yield adapter
                                              .sendMessageStream(
                                                SendMessageParams(
                                                  effectiveMessages,
                                                  candidate.model,
                                                  req.tools,
                                                  cappedThinking,
                                                  req.systemStable,
                                                  req.systemDynamic,
                                                  Some(req.sessionId),
                                                  Some(req.agentId),
                                                  searchInjectionFor(req, candidate),
                                                  attemptBackend = transportOpt.map(_.backend)
                                                )
                                              )
                                              .onFinalize(transportOpt.fold(IO.unit)(_.release)))
                                          )
                                          .handleErrorWith { err =>
                                            fs2.Stream.eval(abortedRef.get).flatMap { aborted =>
                                              fs2.Stream.raiseError[IO](
                                                if aborted then new RecoverableAbort(req.sessionId) else err
                                              )
                                            }
                                          }
                                        (stream
                                          // Per-provider two-phase watchdog: detects both
                                          // dead connections (no first token) and mid-stream stalls.
                                          // Applied per-provider so a timeout on one allows fallback.
                                          // transientPhase2=true: a mid-stream stall (phase 2) raises
                                          // StreamInactivityTimeout → Transient (upstream jitter, the
                                          // turn can be retried from a clean checkpoint).
                                          .through(
                                            inactivityTimeout(
                                              streamInactivityOverride
                                                .map(_._1)
                                                .getOrElse(Defaults.LlmFirstTokenTimeoutSec.seconds),
                                              streamInactivityOverride
                                                .map(_._2)
                                                .getOrElse(Defaults.LlmStreamInactivitySec.seconds),
                                              transientPhase2 = true
                                            )
                                          )
                                          .evalTap { chunk =>
                                            chunk match
                                              case StreamChunk.TextDelta(_) | StreamChunk.ToolCallChunk(_) |
                                                  StreamChunk.ThinkingDelta(_) =>
                                                lockedRef.set(true) *> winnerRef.set(Some(candidate))
                                              case _ => IO.unit
                                          }
                                          .evalMap {
                                            case done: StreamChunk.Done =>
                                              lockedRef.get.flatMap { locked =>
                                                if locked then
                                                  // Fix the meta's providerId — adapters hardcode it (e.g., "openai"
                                                  // for any OpenAI-compatible provider). Use the actual providerId
                                                  // from the candidate so the frontend shows correct provider name.
                                                  val fixedMeta =
                                                    done.meta.map(_.copy(providerId = candidate.providerId))
                                                  // Oscillation fix: only an image-bearing success lifts a
                                                  // vision=false override. messagesRef holds what was actually
                                                  // sent (post-strip if PostEmptyRecovery fired), so a stripped
                                                  // retry success keeps the override in place.
                                                  messagesRef.get.flatMap { sentMsgs =>
                                                    emptyTracker
                                                      .resetOnSuccess(
                                                        candidate.providerId,
                                                        candidate.model,
                                                        hadImage = hasImage(sentMsgs)
                                                      )
                                                      .as(
                                                        done
                                                          .copy(
                                                            meta = fixedMeta,
                                                            contextWindow = Some(candidate.contextWindow)
                                                          )
                                                      )
                                                  }
                                                else
                                                  IO.raiseError(
                                                    new RuntimeException(
                                                      s"Stream completed with no content (${candidate.providerId}/${candidate.model})"
                                                    )
                                                  )
                                              }
                                            case other => IO.pure(other)
                                          }
                                        // Guard: if the stream completes but never emitted any content
                                        // (no Done chunk, no text/thinking/tool chunks), some providers
                                        // close the SSE connection without a terminal event. Without this
                                        // check, the empty response slips past sendStream's fallback and
                                        // only reaches AgentActor's retry — which lacks provider fallback.
                                          ++ fs2.Stream
                                            .eval(
                                              lockedRef.get.flatMap { locked =>
                                                if !locked then
                                                  IO.raiseError(
                                                    new RuntimeException(
                                                      s"Stream completed with no content (${candidate.providerId}/${candidate.model})"
                                                    )
                                                  )
                                                else IO.unit
                                              }
                                            )
                                            .drain)
                                          // PostEmptyRecovery: if empty completion with images on non-vision model,
                                          // strip images and retry same candidate before falling through to normal error handling.
                                          .handleErrorWith { err =>
                                            val isEmptyCompletion = err.getMessage != null &&
                                              err.getMessage.contains("Stream completed with no content")
                                            if isEmptyCompletion then
                                              fs2.Stream
                                                .eval(for
                                                  alreadyStripped <- imageStrippedRef.get
                                                  msgs <- messagesRef.get
                                                yield (alreadyStripped, msgs))
                                                .flatMap {
                                                  case (false, msgs) if hasImage(msgs) =>
                                                    // Check both config vision and runtime override
                                                    fs2.Stream
                                                      .eval(
                                                        emptyTracker
                                                          .getRuntimeVision(candidate.providerId, candidate.model)
                                                      )
                                                      .flatMap { runtimeVision =>
                                                        val effectiveVision =
                                                          candidate.vision && runtimeVision.getOrElse(true)
                                                        if !effectiveVision then
                                                          // Strip images and retry same candidate
                                                          fs2.Stream
                                                            .eval(for
                                                              _ <- imageStrippedRef.set(true)
                                                              _ <- messagesRef.set(stripImages(msgs))
                                                              _ <- lockedRef.set(false)
                                                              _ <- logger.warn(
                                                                s"PostEmptyRecovery: empty completion with image on non-vision model " +
                                                                  s"${candidate.providerId}/${candidate.model}, stripping and retrying"
                                                              )
                                                            yield ())
                                                            .drain ++ tryCandidate(
                                                            candidate :: rest,
                                                            maxRetries,
                                                            Fallback.InitialBackoffMs,
                                                            attempted,
                                                            round
                                                          )
                                                        else fs2.Stream.raiseError[IO](err)
                                                        end if
                                                      }
                                                  case _ =>
                                                    fs2.Stream.raiseError[IO](err)
                                                }
                                            else fs2.Stream.raiseError[IO](err)
                                            end if
                                          }
                                          .handleErrorWith { err =>
                                            // Phase 2: EmptyCompletionTracker + CapabilityMismatch classification
                                            val rawClassification = Fallback.classifyError(err)
                                            val isEmptyCompletion = err.getMessage != null &&
                                              err.getMessage.contains("Stream completed with no content")
                                            // For empty completions, record in tracker and check for capability mismatch.
                                            // For other errors, check for explicit vision/multimodal blame in the
                                            // message (B3 Phase 1: immediate demotion, no threshold wait).
                                            val trackerIO =
                                              if isEmptyCompletion then
                                                for
                                                  msgs <- messagesRef.get
                                                  img = hasImage(msgs)
                                                  _ <- emptyTracker.onEmptyCompletion(
                                                    candidate.providerId,
                                                    candidate.model,
                                                    img
                                                  )
                                                yield img
                                              else
                                                for
                                                  msgs <- messagesRef.get
                                                  img = hasImage(msgs)
                                                  _ <- IO.whenA(img)(
                                                    emptyTracker.onVisionError(
                                                      candidate.providerId,
                                                      candidate.model,
                                                      Option(err.getMessage).getOrElse("")
                                                    )
                                                  )
                                                yield img

                                            fs2.Stream.eval(trackerIO).flatMap { hadImage =>
                                              // Override classification: empty completion with image on non-vision model
                                              // → CapabilityMismatch (Permanent, skip this provider)
                                              val classification =
                                                if isEmptyCompletion && hadImage && !candidate.vision then
                                                  rawClassification.copy(reason = FailoverReason.CapabilityMismatch)
                                                else rawClassification
                                              // Seam guard: content chunks that enter the final
                                              // aggregation (TextDelta / ThinkingDelta /
                                              // ToolCallChunk) were already pulled downstream
                                              // (compile.toList) — they CANNOT be recalled.
                                              // Continuing the fallback chain would stitch the
                                              // next provider's output after our partial content
                                              // into the same aggregation — user-visible
                                              // duplication (production evidence 2026-08-20: one
                                              // stream mixing qwen tool_use fragments with kimi
                                              // end_turn/usage). The old timeout carve-out
                                              // (reset lock + fall through) was exactly this
                                              // seam. Timeout-with-partial-content now behaves
                                              // like every other error-with-partial-content:
                                              // the whole stream fails; the agent's llm-fail
                                              // path surfaces it (per the 2026-08-18 token-
                                              // incident ruling, non-overload transients do not
                                              // auto-retry at the agent level).
                                              val isTimeout = classification.reason == FailoverReason.Timeout
                                              fs2.Stream.eval(lockedRef.get).flatMap { locked =>
                                                if locked then
                                                  val seamWarn =
                                                    if isTimeout then
                                                      logger.warn(
                                                        s"Stream aborted after partial content " +
                                                          s"(${candidate.providerId}/${candidate.model}): " +
                                                          "inactivity timeout — provider switch suppressed " +
                                                          "(emitted chunks cannot be recalled; stitched-output guard)"
                                                      )
                                                    else IO.unit
                                                  fs2.Stream.eval(seamWarn) *> fs2.Stream
                                                    .eval(IO.raiseError(err))
                                                else
                                                  val attempt = FallbackAttempt(
                                                    candidate.providerId,
                                                    candidate.model,
                                                    Some(classification.reason),
                                                    Some(classification.permanence),
                                                    0,
                                                    maxRetries - retriesLeft,
                                                    java.time.Instant.now().toString,
                                                    classification.message.orElse(Option(err.getMessage))
                                                  )
                                                  val notify = onAttempt.traverse_(_.apply(attempt))
                                                  val downReason = classification.message
                                                    .orElse(Option(err.getMessage))
                                                    .getOrElse(classification.reason.toString)

                                                  // 案① A5（作者令「随案① 落地带上」）：4xx 的 provider
                                                  // 响应体**不受 llmLog `enabled` 开关门控**，常驻落盘
                                                  // （`logs/router/{date}_httperror.jsonl`）。动因：事故当日
                                                  // 11,102 条 `permanent error (Format)` 的**响应体原文缺失**
                                                  // （默认关 ⇒ 一行不写），三条候选成因无法区分。只落状态码 +
                                                  // 响应体 + 关联 id；LlmLogWriter 侧结构性不接受任何请求头 /
                                                  // 凭据参数，且对响应体做一次防御性凭据抹除（见
                                                  // [[nebflow.core.LlmLogWriter.logHttpError]]）。
                                                  def retain4xx: IO[Unit] =
                                                    classification.statusCode
                                                      .filter(c => c >= 400 && c < 500)
                                                      .traverse_(code =>
                                                        nebflow.core.LlmLogWriter.logHttpError(
                                                          statusCode = code,
                                                          body = classification.message
                                                            .orElse(Option(err.getMessage))
                                                            .getOrElse(""),
                                                          requestId = key,
                                                          sessionId = req.sessionId,
                                                          agentId = req.agentId,
                                                          providerId = candidate.providerId,
                                                          model = candidate.model
                                                        )
                                                      )

                                                  classification.permanence match
                                                    case ErrorPermanence.Fatal =>
                                                      // Error affects all providers — abort entire stream
                                                      fs2.Stream.eval(
                                                        logger.warn(
                                                          s"Stream fatal: ${candidate.providerId}/${candidate.model} ${classification.reason} — aborting"
                                                        )
                                                          *> failureRef.update(_ :+ attempt)
                                                          *> notify
                                                          *> retain4xx
                                                      ) *> fs2.Stream.raiseError[IO](
                                                        new FallbackExhaustedError(List(attempt))
                                                      )
                                                    case ErrorPermanence.Permanent =>
                                                      // 审计 20260903 子项②③——eviction 分流：
                                                      //  - evict=false（400 Format/重放形状类）：provider 秒回
                                                      //    400 恰恰证明它活着（解析并拒绝了我们的请求），失败根源
                                                      //    是重放形状 vs 契约——不驱逐，只跳本次请求。markDown 在此
                                                      //    只会制造 flap：探测空历史永远成功 → 秒回 UP → 下一个
                                                      //    fallback 再 400（实锤：deepseek 7min 35 次 DOWN）。
                                                      //  - reason=Timeout（首 token 看门狗 TimeoutException 走
                                                      //    Permanent 分类）：软下线回避窗——「慢 ≠ 死」，窗口后自然
                                                      //    回链，无需探测恢复。
                                                      //  - quota=true（配额分层，令 2026-09-21 19:16 腿 b）：
                                                      //    **计划性额度耗尽**（403 / 429-1308）⇒ 换链 + 配额软回避窗
                                                      //    （QuotaAvoidWindowMs ≫ 瞬时窗）。阻塞等待无意义：短窗内
                                                      //    不会自愈，探测也不会把它救回来 ⇒ 不用 markDown 进探测集。
                                                      //  - 其余（Auth/404/EmptyStream 等确证死亡）：维持 markDown。
                                                      val eviction =
                                                        if !classification.evict then IO.unit
                                                        else if classification.quota then
                                                          healthMonitor.softAvoid(
                                                            candidate.providerId,
                                                            candidate.model,
                                                            Defaults.QuotaAvoidWindowMs,
                                                            label = "quota exhausted"
                                                          )
                                                        else if classification.reason == FailoverReason.Timeout then
                                                          healthMonitor.softAvoid(
                                                            candidate.providerId,
                                                            candidate.model,
                                                            Defaults.TimeoutAvoidWindowMs
                                                          )
                                                        else
                                                          healthMonitor
                                                            .markDown(candidate.providerId, candidate.model, downReason)
                                                      fs2.Stream.eval(
                                                        logger.warn(
                                                          s"Stream: ${candidate.providerId}/${candidate.model} permanent error (${classification.reason})"
                                                          // 案① A3：补回被丢弃的 provider 响应体（原 warn 只报
                                                          // reason 串 —— 这正是事故当日「无法定因」的直接原因）。
                                                          // 前缀 `permanent error (Format)` 逐字保留（现场 grep 锚点）。
                                                          //
                                                          // 🔴 隐私面（与 A5 同口径）：provider 可能把收到的凭据
                                                          // 回显在错误体里 ⇒ 落盘前先走 `redactSecrets`。不抹除的话
                                                          // 本行会在事故形态下（秒级重投 × 每候选每轮一条）把密钥
                                                          // 写进 nebflow.log，而同一份响应体在 httperror.jsonl 里
                                                          // 反而是抹除过的——两条腿不一致。截断 + 抹除都不影响
                                                          // 「响应体可读」这个 A3 目的。
                                                            + classification.message
                                                              .map(m =>
                                                                s" — provider response: ${nebflow.core.LlmLogWriter.redactSecrets(m.take(2048))}"
                                                              )
                                                              .getOrElse("")
                                                            + s" [status=${classification.statusCode.map(_.toString).getOrElse("n/a")}, " +
                                                            s"evict=${classification.evict}, round=$round/${Fallback.MaxChainRounds}]"
                                                        )
                                                          *> failureRef.update(_ :+ attempt)
                                                          *> notify
                                                          *> retain4xx
                                                          *> eviction
                                                      ) *> tryCandidate(
                                                        rest,
                                                        maxRetries,
                                                        Fallback.InitialBackoffMs,
                                                        attempted + candidateKey(candidate),
                                                        round
                                                      )
                                                    case ErrorPermanence.Transient =>
                                                      if retriesLeft > 0 && !isTimeout then
                                                        // Only retry same provider for non-timeout errors.
                                                        // Timeout means the provider is unresponsive — skip to next.
                                                        val jitter =
                                                          java.util.concurrent.ThreadLocalRandom
                                                            .current()
                                                            .nextLong(0, 2000)
                                                        // gate-wedge 止损: route through retryDelayMs so
                                                        // overload-class (429/529) waits >= the rate window
                                                        // (OverloadBackoffMinMs) — the old inline
                                                        // min(backoff+jitter, max) had no overload floor on
                                                        // the stream path.
                                                        val delay = Fallback.retryDelayMs(
                                                          backoffMs,
                                                          classification.reason,
                                                          jitter
                                                        )
                                                        fs2.Stream.eval(
                                                          notify *> logger.warn(
                                                            s"Stream retry ${candidate.providerId}/${candidate.model}: ${classification.reason} (${retriesLeft} left, ${delay}ms)"
                                                          ) *> IO.sleep(delay.millis)
                                                        ) *> tryCandidate(
                                                          remaining,
                                                          retriesLeft - 1,
                                                          backoffMs * 2,
                                                          attempted,
                                                          round
                                                        )
                                                      else
                                                        // Timeout / retries exhausted — try next provider
                                                        val skipMsg =
                                                          if isTimeout then
                                                            "inactivity timeout, skipping to next provider"
                                                          else "retries exhausted"
                                                        // Branch reachable only with locked=false (the seam guard
                                                        // above propagates every error once partial content was
                                                        // streamed), so the lock needs no reset.
                                                        // 审计 20260903 子项③：Timeout 类降级软下线——超时 = 慢，
                                                        // 不是死。跳过本次请求 + 软回避窗（TimeoutAvoidWindowMs），
                                                        // 不 markDown 不进探测集，窗口到期自然回链；markDown
                                                        // 保留给 Auth/404 等确证死亡。非超时 Transient 耗尽
                                                        // （如 429 重试耗尽）维持原 markDown 行为。
                                                        // 配额分层（令 2026-09-21 19:16 腿 b）：配额类恒为
                                                        // Permanent（不可自愈），正常不到达本分支；此处仍置于
                                                        // 最前作为防御一致性——任何路径的配额类都走配额窗 + 换链，
                                                        // 绝不落进 markDown/阻塞等待。
                                                        val eviction =
                                                          if classification.quota then
                                                            healthMonitor.softAvoid(
                                                              candidate.providerId,
                                                              candidate.model,
                                                              Defaults.QuotaAvoidWindowMs,
                                                              label = "quota exhausted"
                                                            )
                                                          else if isTimeout then
                                                            healthMonitor.softAvoid(
                                                              candidate.providerId,
                                                              candidate.model,
                                                              Defaults.TimeoutAvoidWindowMs
                                                            )
                                                          else
                                                            healthMonitor
                                                              .markDown(
                                                                candidate.providerId,
                                                                candidate.model,
                                                                downReason
                                                              )
                                                        fs2.Stream.eval(
                                                          logger.warn(
                                                            s"Stream fallback: ${candidate.providerId}/${candidate.model} $skipMsg"
                                                          )
                                                            *> failureRef.update(_ :+ attempt)
                                                            *> notify
                                                            *> eviction
                                                        ) *> tryCandidate(
                                                          rest,
                                                          maxRetries,
                                                          Fallback.InitialBackoffMs,
                                                          attempted + candidateKey(candidate),
                                                          round
                                                        )
                                                      end if
                                                  end match
                                                end if
                                              }
                                            }
                                          }
                                      } // end per-attempt abortedRef flatMap (hard-recovery P1/P6)

                                // 入口：轮次从 1 起跑（案① A2 —— wrapper 现在带轮次参数）。
                                attemptWithHealthCheck()
                              }
                            }
                          }
                        }
                      }
                    }
                    // issue #31 Fix B (2026-08-20): whole-stream no-progress watchdog.
                    // The per-provider inactivityTimeout (inside tryCandidate) only
                    // arms AFTER sendMessageStream starts producing — the
                    // intake→first-chunk evaluation chain (candidates resolve /
                    // health check / adapter fetch / HTTP setup /
                    // consumer-side processing) had NO coverage: a fiber parked
                    // there hangs forever with the barrier slot held (incident:
                    // intake at 22:48:41, then 40min zero traces, Stop + hard-cancel
                    // both ineffective). This outer guard bounds the blind window:
                    // no chunk within Defaults.LlmStreamNoProgressTimeoutSec →
                    // TimeoutException, which propagates OUTSIDE tryCandidate's
                    // per-provider handleErrorWith (those wrap the inner stream
                    // only) — the whole sendStream fails, the agent's llm-fail
                    // path takes over. Reuses the same two-phase pipe with equal
                    // windows: legal fallback silences (backoff +
                    // first-token per hop) are bounded well below 600s.
                    .through(
                      inactivityTimeout(
                        noProgressTimeoutOverride.getOrElse(Defaults.LlmStreamNoProgressTimeoutSec.seconds),
                        noProgressTimeoutOverride.getOrElse(Defaults.LlmStreamNoProgressTimeoutSec.seconds),
                        // 时间基修正（hostresume 批 2026-09-22，设计卡 §4 #5，D-3 裁定「扣睡眠
                        // 后仍 Permanent」）：宿主睡眠冻结秒经 PowerStateTracker 扣减，跨睡眠
                        // 不再误触整流硬超时；超时分类仍是 plain TimeoutException → Permanent
                        // fail-fast（禁重分类 Transient）。空窗集 / kill-switch ⇒ 逐字节现状。
                        effectiveElapsed = nebflow.shared.PowerStateTracker.effectiveElapsed
                      )
                    )
                    .interruptWhen(halt.get)
                    .onFinalize(unregisterInflight(key))
                }
            end sendStream

          (handle, registry, healthMonitor, release)
        end result
        IO(result).onError(_ => release)
      }
    }
  end createLlm
end LlmInterface
