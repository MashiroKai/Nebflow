package nebflow.llm

import cats.effect.IO
import cats.effect.kernel.Ref
import cats.effect.std.Dispatcher
import cats.syntax.all.*
import nebflow.core.NebflowLogger
import nebflow.shared.*
import sttp.client4.httpclient.fs2.HttpClientFs2Backend

import scala.concurrent.duration.*

object LlmInterface:
  private val logger = NebflowLogger.forName("nebflow.llm")

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
    subsequent: FiniteDuration
  ): fs2.Pipe[IO, O, O] =
    val firstEx = new java.util.concurrent.TimeoutException(
      s"LLM stream: no response within ${firstToken.toSeconds}s"
    )
    val inactEx = new java.util.concurrent.TimeoutException(
      s"LLM stream inactive for ${subsequent.toSeconds}s"
    )
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
                    val (limit, ex) =
                      if first then (subsequent.toMillis, inactEx)
                      else (firstToken.toMillis, firstEx)
                    if now - last > limit then IO.raiseError(ex)
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
    // session resumption clashes with the USTC gateway reverse proxy
    // (nginx/one-api style), producing intermittent bad_record_mac TLS alerts
    // on reused connections. curl never hits it — each request is a fresh
    // connection. HTTP/1.1 removes the multiplexed-reuse path entirely; if
    // bad_record_mac persists, next step is disabling TLS 1.3 resumption.
    val httpClient = java.net.http.HttpClient
      .newBuilder()
      .version(java.net.http.HttpClient.Version.HTTP_1_1)
      .build()
    Dispatcher.parallel[IO].allocated.flatMap { case (dispatcher, releaseDispatcher) =>
      val backend = HttpClientFs2Backend.usingClient[IO](httpClient, dispatcher)
      val release: IO[Unit] = releaseDispatcher *> IO(backend.close())
      IO.pure(backend).flatMap { backend =>
        val config = Config.loadServiceConfig(options.flatMap(_.configPath))
        val cfgRef: Ref[IO, NebflowServiceConfig] = configRef.getOrElse(Ref.unsafe(config))
        val registry = ProviderRegistry(cfgRef, backend)
        val healthMonitor = ProviderHealthMonitor(registry)
        val emptyTracker = EmptyCompletionTracker.shared
        val result =

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
                (up, _) <- healthMonitor.filterCandidates(candidates)
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
                          Some(req.agentId)
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
                    )
                  )
                )
              }
            end send

            def sendStream(
              req: LlmRequest,
              onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
            ): fs2.Stream[IO, StreamChunk] =
              fs2.Stream
                .eval(
                  for
                    overrides <- sessionOverrides.get
                    regCandidates <- registry.getCandidatesForAgent(req.agentModel)
                  yield overrides.get(req.sessionId).toList ++ regCandidates
                    .filterNot(c =>
                      overrides.get(req.sessionId).exists(o => o.providerId == c.providerId && o.model == c.model)
                    )
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
                                    yield adapter.sendMessageStream(
                                      SendMessageParams(
                                        effectiveMessages,
                                        candidate.model,
                                        req.tools,
                                        Some(candidate.maxTokens),
                                        cappedThinking,
                                        req.systemStable,
                                        req.systemDynamic,
                                        Some(req.sessionId),
                                        Some(req.agentId)
                                      )
                                    ))
                                  )
                                  (stream
                                    // Per-provider two-phase watchdog: detects both
                                    // dead connections (no first token) and mid-stream stalls.
                                    // Applied per-provider so a timeout on one allows fallback.
                                    .through(
                                      inactivityTimeout(
                                        Defaults.LlmFirstTokenTimeoutSec.seconds,
                                        Defaults.LlmStreamInactivitySec.seconds
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
                                        // Timeout needs special handling: even if partial content was
                                        // streamed (locked), we allow fallback to try the next provider.
                                        // Other errors with locked=true are propagated to avoid duplication.
                                        val isTimeout = classification.reason == FailoverReason.Timeout
                                        fs2.Stream.eval(lockedRef.get).flatMap { locked =>
                                          if locked && !isTimeout then fs2.Stream.eval(IO.raiseError(err))
                                          else
                                            val resetLock =
                                              if isTimeout && locked then lockedRef.set(false) else IO.unit
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
                                                  resetLock *> logger.warn(
                                                    s"Stream fatal: ${candidate.providerId}/${candidate.model} ${classification.reason} — aborting"
                                                  )
                                                    *> failureRef.update(_ :+ attempt)
                                                    *> notify
                                                ) *> fs2.Stream.raiseError[IO](
                                                  new FallbackExhaustedError(List(attempt))
                                                )
                                              case ErrorPermanence.Permanent =>
                                                fs2.Stream.eval(
                                                  resetLock *>
                                                    logger.warn(
                                                      s"Stream: ${candidate.providerId}/${candidate.model} permanent error (${classification.reason})"
                                                    )
                                                    *> failureRef.update(_ :+ attempt)
                                                    *> notify
                                                    *> healthMonitor
                                                      .markDown(candidate.providerId, candidate.model, downReason)
                                                ) *> tryCandidate(rest, maxRetries, Fallback.InitialBackoffMs)
                                              case ErrorPermanence.Transient =>
                                                if retriesLeft > 0 && !isTimeout then
                                                  // Only retry same provider for non-timeout errors.
                                                  // Timeout means the provider is unresponsive — skip to next.
                                                  val jitter =
                                                    java.util.concurrent.ThreadLocalRandom.current().nextLong(0, 2000)
                                                  val delay = math.min(backoffMs + jitter, Fallback.MaxBackoffMs)
                                                  fs2.Stream.eval(
                                                    resetLock *> notify *> logger.warn(
                                                      s"Stream retry ${candidate.providerId}/${candidate.model}: ${classification.reason} (${retriesLeft} left, ${delay}ms)"
                                                    ) *> IO.sleep(delay.millis)
                                                  ) *> tryCandidate(remaining, retriesLeft - 1, backoffMs * 2)
                                                else
                                                  // Timeout or retries exhausted — try next provider
                                                  val skipMsg =
                                                    if isTimeout then "inactivity timeout, skipping to next provider"
                                                    else "retries exhausted"
                                                  // Align with the Rust handle.rs fallback branch: timeout is
                                                  // classified Permanent, so the stream ALWAYS markDowns here
                                                  // (Rust handle.rs L720-735 has no locked check on mark_down).
                                                  // The locked-stream guard only: (1) propagates non-timeout
                                                  // errors when partial content was streamed (L681-685), and
                                                  // (2) resets the lock on timeout so the Done-without-content
                                                  // check doesn't fire mid-fallback. Scala mirrors both above.
                                                  // DOWN→probe→UP→timeout→DOWN churn is bounded by the
                                                  // waitForAnyUp timeout (120s) at the all-Down gate.
                                                  fs2.Stream.eval(
                                                    resetLock *> logger.warn(
                                                      s"Stream fallback: ${candidate.providerId}/${candidate.model} $skipMsg"
                                                    )
                                                      *> failureRef.update(_ :+ attempt)
                                                      *> notify
                                                      *> healthMonitor
                                                        .markDown(candidate.providerId, candidate.model, downReason)
                                                  ) *> tryCandidate(rest, maxRetries, Fallback.InitialBackoffMs)
                                                end if
                                            end match
                                          end if
                                        }
                                      }
                                    }

                            attemptWithHealthCheck
                          }
                        }
                      }
                    }
                  }
                }
            end sendStream

          (handle, registry, healthMonitor, release)
        end result
        IO(result).onError(_ => release)
      }
    }
  end createLlm
end LlmInterface
