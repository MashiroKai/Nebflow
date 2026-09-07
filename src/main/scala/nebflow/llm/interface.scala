package nebflow.llm

import cats.effect.IO
import cats.effect.kernel.{Deferred, Ref}
import cats.effect.std.Dispatcher
import cats.syntax.all.*
import nebflow.core.NebflowLogger
import nebflow.shared.*
import sttp.capabilities.fs2.Fs2Streams
import sttp.client4.StreamBackend
import sttp.client4.httpclient.fs2.HttpClientFs2Backend

import scala.concurrent.duration.*

/** Raised when a JVM shutdown hook aborts an in-flight LLM request. */
final class ShutdownAbort
    extends RuntimeException("Nebflow shutting down: LLM request aborted")

/** Raised when TaskStuckWatcher hard-cancels an agent's in-flight LLM request
  * (gate-wedge P1-1, 2026-08-20): the agent ignored ≥2 Stop mailbox messages
  * because it was suspended on its LLM fiber, so the watcher escalates to
  * cancelling the fiber itself. Classified Fatal — the provider is NOT at
  * fault, no fallback/retry must re-send the full context; the agent's turn
  * fails and its own bounded retry loop (MaxTurnLlmCalls) takes over. */
final class StuckAbort(val sessionId: String)
    extends RuntimeException(s"TaskStuckWatcher hard-cancel: LLM request of session $sessionId aborted (agent unresponsive to Stop)")

/** Hard-recovery P6 (2026-09-07): raised when the transport of an in-flight LLM
  * request is force-aborted (per-request HttpClient shutdownNow — the only
  * primitive proven to unblock a parked body read, 取证 2026-09-07 §1.3) by
  * SessionKick (user message/interrupt on a wedged turn) or watcher L2. The
  * provider is NOT at fault — the stream-level classification is Fatal (no
  * provider fallback, same as StuckAbort: partial content must never be
  * stitched), but the AGENT layer treats it as retryable (re-send the whole
  * turn within the existing OverloadRetryMax/MaxTurnLlmCalls budget) or yields
  * to queued user input at the turn boundary — see AgentActor.llmFailureRetryable. */
final class RecoverableAbort(val sessionId: String)
    extends RuntimeException(
      s"transport abort: LLM request of session $sessionId force-aborted (recoverable — turn will be re-sent)"
    )

object LlmInterface:
  private val logger = NebflowLogger.forName("nebflow.llm")

  /** Test hook (issue #31 Fix B spec): overrides the whole-stream no-progress
    * window (Defaults.LlmStreamNoProgressTimeoutSec) so the outer watchdog can
    * be exercised in tests without waiting 600s. Global var — specs MUST reset
    * it to None in a finally. */
  private[llm] var noProgressTimeoutOverride: Option[FiniteDuration] = None

  /** Test hook (fallback seam spec): overrides the per-provider two-phase
    * watchdog windows (Defaults.LlmFirstTokenTimeoutSec /
    * LlmStreamInactivitySec) — (firstToken, subsequent). Global var — specs
    * MUST reset it to None in a finally. */
  private[llm] var streamInactivityOverride: Option[(FiniteDuration, FiniteDuration)] = None

  /** Flow-node supervision P3 (2026-08-26): apply llm.streamTimeouts config
    * overrides (boot-time, from GatewayMain). Each window is independently
    * optional — unspecified windows keep the Defaults value. Not a test hook:
    * this is the production config entry point (tests keep using the raw vars). */
  def applyStreamTimeouts(
    firstTokenSec: Option[Int],
    inactivitySec: Option[Int],
    noProgressSec: Option[Int]
  ): Unit =
    if firstTokenSec.isDefined || inactivitySec.isDefined then
      streamInactivityOverride = Some((
        firstTokenSec.map(_.seconds).getOrElse(Defaults.LlmFirstTokenTimeoutSec.seconds),
        inactivitySec.map(_.seconds).getOrElse(Defaults.LlmStreamInactivitySec.seconds)
      ))
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

  /** Hard-recovery P1: attach (or replace) the transport abort action for an
    * in-flight request. Called by sendStream's per-attempt transport setup. */
  private[llm] def attachTransportAbort(key: String, abort: IO[Unit]): IO[Unit] =
    setAbort(key, abort)

  private def setAbort(key: String, abort: IO[Unit]): IO[Unit] =
    inflight.get.flatMap { m =>
      m.get(key) match
        case Some(e) => e.abortRef.set(Some(abort))
        case None    => IO.unit
    }

  private[llm] def unregisterInflight(key: String): IO[Unit] =
    inflight.update(_ - key)

  /** gate-wedge P1-1: hard-cancel every in-flight LLM request belonging to a
    * session — wakes queued and streaming requests
    * alike (the interrupt wraps the whole candidate stream, not just the HTTP
    * layer). Returns how many requests were aborted. Used by TaskStuckWatcher
    * after repeated Stop mailbox messages went unconsumed. */
  def cancelInflightFor(sessionId: String): IO[Int] =
    inflight.get.flatMap { m =>
      val matching = m.toList.collect { case (k, e) if e.sessionId.contains(sessionId) => (k, e) }
      matching.traverse_ { case (_, e) =>
        e.halt.complete(Left(new StuckAbort(sessionId))).void.handleErrorWith(_ => IO.unit)
      } *> IO.pure(matching.size)
    }

  /** Hard-recovery P1/P4 (2026-09-07): L2 transport abort — force-abort every
    * in-flight LLM request belonging to a session at the TRANSPORT level
    * (per-request HttpClient shutdownNow). This is the only primitive proven
    * to unblock a fiber parked on the JDK HttpClient body read (半开连接 —
    * fs2 cancellation and the halt Deferred can only act at step boundaries,
    * which a parked read never crosses; 取证 2026-09-07 §1.3). Also completes
    * the halt with RecoverableAbort (belt: whichever surfaces first wins; the
    * per-attempt abortedRef mapping in sendStream makes the surfaced error
    * deterministically RecoverableAbort). No-op (0 aborted) when
    * PerRequestTransport is disabled — callers verify and escalate (设计 P2). */
  def transportAbortFor(sessionId: String): IO[Int] =
    inflight.get.flatMap { m =>
      val matching = m.toList.collect { case (k, e) if e.sessionId.contains(sessionId) => (k, e) }
      matching.traverse_ { case (_, e) =>
        e.abortRef.get.flatMap {
          case Some(abort) => abort.attempt.void
          case None        => IO.unit
        } *>
          // Belt: if the transport kill raced the stream past a step boundary,
          // interruptWhen surfaces this instead of the raw IOException.
          e.halt.complete(Left(new RecoverableAbort(sessionId))).void.handleErrorWith(_ => IO.unit)
      } *> IO.pure(matching.size)
    }

  /** Abort all in-flight LLM requests — streams fail with [[ShutdownAbort]]. */
  def cancelAllInflight(): IO[Unit] =
    inflight.get.flatMap { m =>
      m.values.toList.traverse_(_.halt.complete(Left(new ShutdownAbort))) *> inflight.set(Map.empty)
    }

  /** Synchronous variant for JVM shutdown hooks (runs on IORuntime.global). */
  def cancelAllInflightSync(): Unit =
    try cancelAllInflight().unsafeRunSync()(using cats.effect.unsafe.implicits.global)
    catch case _: Throwable => ()

  /**
   * Best-effort abort of a JDK HttpClient: calls `shutdownNow()` (JDK 21+)
   * via reflection, degrading gracefully to `close()` (JDK 11+) on JDK 17.
   * Without this, `httpClient.shutdownNow()` compiles fine (JDK 23 build) but
   * throws `NoSuchMethodError` at runtime on JDK 17 (real Windows machine,
   * CI packaging),
   * swallowed by the shutdown hook's `catch case _: Throwable => ()` →
   * Ctrl+C inflight-abort silently no-ops.
   */
  private[llm] def abortHttpClient(client: java.net.http.HttpClient): Unit =
    try
      val m = classOf[java.net.http.HttpClient].getMethod("shutdownNow")
      m.invoke(client)
    catch
      case _: NoSuchMethodException =>
        // JDK < 21: shutdownNow doesn't exist — close() is the best we have
        client.close()
      case e: java.lang.reflect.InvocationTargetException =>
        // shutdownNow exists but threw — still try close()
        try client.close() catch case _: Throwable => ()

  // ── Hard-recovery P1: per-attempt transport (设计 D-1 方案 A, 2026-09-07) ──

  private[llm] final case class AttemptTransport(
    backend: StreamBackend[IO, Fs2Streams[IO]],
    release: IO[Unit]
  )

  /** A dedicated HttpClient + fs2 backend + dispatcher per streaming attempt.
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
    * latency — 设计 §2.2). */
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
            IO(client.close()).attempt.void *>
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
    transientPhase2: Boolean = false
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
      if transientPhase2 then { age =>
        StreamInactivityTimeout(age, s"LLM stream inactive for ${subsequent.toSeconds}s")
      }
      else { _ =>
        new java.util.concurrent.TimeoutException(s"LLM stream inactive for ${subsequent.toSeconds}s")
      }
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
                          val age = now2 - last
                          if age > subsequent.toMillis then IO.raiseError(phase2Ex(age))
                          else IO.unit
                        else if now2 - last > firstToken.toMillis then IO.raiseError(firstEx)
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
    // Force HTTP/1.1: the JDK HttpClient's HTTP/2 connection-reuse + TLS 1.3
    // session resumption clashes with certain reverse proxies
    // (nginx/one-api style API gateways), producing intermittent bad_record_mac TLS alerts
    // on reused connections. curl never hits it — each request is a fresh
    // connection. HTTP/1.1 removes the multiplexed-reuse path entirely; if
    // bad_record_mac persists, next step is disabling TLS 1.3 resumption.
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
      //      JDK 17 fallback: shutdownNow() is JDK21+. On JDK17 the method
      //      doesn't exist — reflection probe + degrade to close() (JDK11+,
      //      AutoCloseable). close() waits for in-flight exchanges to complete
      //      rather than aborting them, but at least releases resources.
      //      Without this guard, NoSuchMethodError is swallowed by the
      //      hook's catch-all → Ctrl+C fix silently no-ops on JDK17 (real machine).
      //   2. httpClient.close() — best-effort close of cached/open connections.
      //   3. backend.close() — no-op for a user-provided client, kept for
      //      symmetry in case a backend-owned client is introduced later.
      //   4. releaseDispatcher — cancels dispatcher fibers.
      val doRelease: IO[Unit] =
        IO(abortHttpClient(httpClient)) *>
          IO(httpClient.close()) *>
          IO(backend.close()) *>
          releaseDispatcher
      val releasedRef: Ref[IO, Boolean] = Ref.unsafe(false)
      val release: IO[Unit] =
        releasedRef.modify {
          case false => (true, doRelease)
          case true  => (true, IO.unit)
        }.flatten
      IO.pure(backend).flatMap { backend =>
        val config = Config.loadServiceConfig(options.flatMap(_.configPath))
        val cfgRef: Ref[IO, NebflowServiceConfig] = configRef.getOrElse(Ref.unsafe(config))
        val registry = ProviderRegistry(cfgRef, backend)
        val healthMonitor = ProviderHealthMonitor(registry)
        val emptyTracker = EmptyCompletionTracker.shared
        val result =

          /** WebSearch P0: provider-native search injection for one candidate
            * — resolved per-candidate so a fallback switch drops the previous
            * provider's injection. The gate semantics (searchAllowed /
            * tools.isDefined / OpenAI-only) live in the pure resolver and
            * are unit-tested there (SearchProviderResolver.searchInjectionFor). */
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
                        case Right(budget) if budget > candidate.maxTokens / 2 =>
                          t.deepMerge(
                            io.circe.Json.obj(
                              "budget_tokens" -> io.circe.Json.fromInt(candidate.maxTokens / 2)
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
                          Some(candidate.maxTokens),
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
              fs2.Stream.eval(
                registerInflight(Some(req.sessionId)).flatTap { case (key, _) =>
                  // Intake trace — makes "request accepted but never fired"
                  // visible in the sse logs.
                  nebflow.core.LlmLogWriter.logIntake(key, req.sessionId, req.agentId)
                }
              ).flatMap { case (key, halt) =>
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

                            // Health-check wrapper: filters candidates by health state.
                            // If all are Down, notifies the frontend and blocks until
                            // at least one provider recovers, then re-filters.
                            def attemptWithHealthCheck: fs2.Stream[IO, StreamChunk] =
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
                                      fs2.Stream.eval(notifyUp).drain ++ attemptWithHealthCheck
                                    }
                                case (up, _) =>
                                  tryCandidate(up)
                              }

                            def tryCandidate(
                              remaining: List[ModelCandidate],
                              retriesLeft: Int = maxRetries,
                              backoffMs: Long = Fallback.InitialBackoffMs
                            ): fs2.Stream[IO, StreamChunk] =
                              remaining match
                                case Nil =>
                                  // All up candidates exhausted during this attempt —
                                  // cycle back through health check (will block if all Down)
                                  attemptWithHealthCheck
                                case candidate :: rest =>
                                  // Cap thinking budget to fit within candidate's maxTokens.
                                  // Some providers (e.g. zhipu/glm-5.1 with maxTokens=32000) crash
                                  // when budget_tokens exceeds their limit.
                                  val cappedThinking = req.thinking.map { t =>
                                    t.hcursor.downField("budget_tokens").as[Int] match
                                      case Right(budget) if budget > candidate.maxTokens / 2 =>
                                        // Cap thinking budget to half of maxTokens (leaving room for output)
                                        t.deepMerge(
                                          io.circe.Json.obj(
                                            "budget_tokens" -> io.circe.Json.fromInt(candidate.maxTokens / 2)
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
                                  val stream = fs2.Stream.force(
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
                                          Some(candidate.maxTokens),
                                          cappedThinking,
                                          req.systemStable,
                                          req.systemDynamic,
                                          Some(req.sessionId),
                                          Some(req.agentId),
                                          searchInjectionFor(req, candidate),
                                          attemptBackend = transportOpt.map(_.backend)
                                        )
                                      )
                                      .onFinalize(transportOpt.fold(IO.unit)(_.release))
                                    )
                                   ).handleErrorWith { err =>
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
                                            val fixedMeta = done.meta.map(_.copy(providerId = candidate.providerId))
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
                                                    .copy(meta = fixedMeta, contextWindow = Some(candidate.contextWindow))
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
                                                  emptyTracker.getRuntimeVision(candidate.providerId, candidate.model)
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
                                                      Fallback.InitialBackoffMs
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

                                            classification.permanence match
                                              case ErrorPermanence.Fatal =>
                                                // Error affects all providers — abort entire stream
                                                fs2.Stream.eval(
                                                  logger.warn(
                                                    s"Stream fatal: ${candidate.providerId}/${candidate.model} ${classification.reason} — aborting"
                                                  )
                                                    *> failureRef.update(_ :+ attempt)
                                                    *> notify
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
                                                //  - 其余（Auth/404/配额/EmptyStream 等确证死亡）：维持 markDown。
                                                val eviction =
                                                  if !classification.evict then IO.unit
                                                  else if classification.reason == FailoverReason.Timeout then
                                                    healthMonitor.softAvoid(
                                                      candidate.providerId,
                                                      candidate.model,
                                                      Defaults.TimeoutAvoidWindowMs
                                                    )
                                                  else healthMonitor
                                                    .markDown(candidate.providerId, candidate.model, downReason)
                                                fs2.Stream.eval(
                                                  logger.warn(
                                                      s"Stream: ${candidate.providerId}/${candidate.model} permanent error (${classification.reason})"
                                                    )
                                                    *> failureRef.update(_ :+ attempt)
                                                    *> notify
                                                    *> eviction
                                                ) *> tryCandidate(rest, maxRetries, Fallback.InitialBackoffMs)
                                              case ErrorPermanence.Transient =>
                                                if retriesLeft > 0 && !isTimeout then
                                                  // Only retry same provider for non-timeout errors.
                                                  // Timeout means the provider is unresponsive — skip to next.
                                                  val jitter =
                                                    java.util.concurrent.ThreadLocalRandom.current().nextLong(0, 2000)
                                                  // gate-wedge 止损: route through retryDelayMs so
                                                  // overload-class (429/529) waits >= the rate window
                                                  // (OverloadBackoffMinMs) — the old inline
                                                  // min(backoff+jitter, max) had no overload floor on
                                                  // the stream path.
                                                  val delay = Fallback.retryDelayMs(backoffMs, classification.reason, jitter)
                                                  fs2.Stream.eval(
                                                    notify *> logger.warn(
                                                      s"Stream retry ${candidate.providerId}/${candidate.model}: ${classification.reason} (${retriesLeft} left, ${delay}ms)"
                                                    ) *> IO.sleep(delay.millis)
                                                  ) *> tryCandidate(remaining, retriesLeft - 1, backoffMs * 2)
                                                else
                                                  // Timeout / retries exhausted — try next provider
                                                  val skipMsg =
                                                    if isTimeout then "inactivity timeout, skipping to next provider"
                                                    else "retries exhausted"
                                                  // Branch reachable only with locked=false (the seam guard
                                                  // above propagates every error once partial content was
                                                  // streamed), so the lock needs no reset.
                                                  // 审计 20260903 子项③：Timeout 类降级软下线——超时 = 慢，
                                                  // 不是死。跳过本次请求 + 软回避窗（TimeoutAvoidWindowMs），
                                                  // 不 markDown 不进探测集，窗口到期自然回链；markDown
                                                  // 保留给 Auth/404/配额等确证死亡。非超时 Transient 耗尽
                                                  // （如 429 重试耗尽）维持原 markDown 行为。
                                                  val eviction =
                                                    if isTimeout then
                                                      healthMonitor.softAvoid(
                                                        candidate.providerId,
                                                        candidate.model,
                                                        Defaults.TimeoutAvoidWindowMs
                                                      )
                                                    else healthMonitor
                                                      .markDown(candidate.providerId, candidate.model, downReason)
                                                  fs2.Stream.eval(
                                                    logger.warn(
                                                      s"Stream fallback: ${candidate.providerId}/${candidate.model} $skipMsg"
                                                    )
                                                      *> failureRef.update(_ :+ attempt)
                                                      *> notify
                                                      *> eviction
                                                  ) *> tryCandidate(rest, maxRetries, Fallback.InitialBackoffMs)
                                                end if
                                            end match
                                          end if
                                        }
                                      }
                                    }
                                  } // end per-attempt abortedRef flatMap (hard-recovery P1/P6)

                            attemptWithHealthCheck
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
                    noProgressTimeoutOverride.getOrElse(Defaults.LlmStreamNoProgressTimeoutSec.seconds)
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
