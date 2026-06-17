package nebflow.llm

import cats.effect.IO
import cats.effect.kernel.Ref
import cats.syntax.all.*
import nebflow.core.NebflowLogger
import nebflow.shared.*
import sttp.client4.httpclient.fs2.HttpClientFs2Backend

import scala.concurrent.duration.*

object LlmInterface:
  private val logger = NebflowLogger.forName("nebflow.llm")

  /**
   * Stream pipe: raises TimeoutException if no element passes through within `d`.
   * Resets the timer on each element. Detects hung LLM connections (e.g. provider
   * sends initial events then stalls).
   *
   * Uses System.currentTimeMillis() (not nanoTime) because nanoTime freezes during
   * Mac sleep/wake, which would prevent timeout detection after wake.
   */
  private def inactivityTimeout[O](d: FiniteDuration): fs2.Pipe[IO, O, O] =
    val timeoutEx = new java.util.concurrent.TimeoutException(
      s"LLM stream inactive for ${d.toSeconds}s"
    )
    in =>
      fs2.Stream.eval(IO.ref(System.currentTimeMillis())).flatMap { lastActivity =>
        val main = in.evalTap(_ => lastActivity.set(System.currentTimeMillis()))
        // Check at 1/5 of the timeout interval for timely detection (min 5s, max 30s)
        val checkInterval = math.max(math.min(d.toMillis / 5, 30000L), 5000L).millis
        val watchdog = fs2.Stream
          .awakeEvery[IO](checkInterval)
          .evalMap { _ =>
            IO(System.currentTimeMillis()).flatMap { now =>
              lastActivity.get.flatMap { last =>
                if now - last > d.toMillis then IO.raiseError(timeoutEx)
                else IO.unit
              }
            }
          }
          .drain
        main.concurrently(watchdog)
      }

  end inactivityTimeout

  def createLlm(
    sessionOverrides: Ref[IO, Map[String, ModelCandidate]],
    options: Option[LlmOptions] = None,
    configRef: Option[Ref[IO, NebflowServiceConfig]] = None
  ): IO[(LlmHandle[IO], ProviderRegistry, IO[Unit])] =
    HttpClientFs2Backend.resource[IO]().allocated.flatMap { case (backend, release) =>
      val config = Config.loadServiceConfig(options.flatMap(_.configPath))
      val cfgRef: Ref[IO, NebflowServiceConfig] = configRef.getOrElse(Ref.unsafe(config))
      val registry = ProviderRegistry(cfgRef, backend)
      val result =

        val handle = new LlmHandle[IO]:
          def send(req: LlmRequest): IO[LlmResponse] =
            val start = System.currentTimeMillis()
            (for
              overrides <- sessionOverrides.get
              regCandidates <- registry.getCandidates()
            yield overrides.get(req.sessionId).toList ++ regCandidates
              .filterNot(c =>
                overrides.get(req.sessionId).exists(o => o.providerId == c.providerId && o.model == c.model)
              )).flatMap { candidates =>
              Fallback
                .tryProviderWithFallback[AdapterResponse](
                  candidates,
                  candidate =>
                    // Cap thinking budget to fit within candidate's maxTokens
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
                    registry
                      .getAdapter(candidate.providerId)
                      .flatMap(
                        _.sendMessage(
                          SendMessageParams(
                            req.messages,
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
                      )
                  ,
                  onAttempt = None
                )
                .flatMap { result =>
                  val durationMs = System.currentTimeMillis() - start
                  val failedAttempts = result.attempts.filter(_.reason.isDefined)
                  val fallbackChain =
                    if failedAttempts.nonEmpty then
                      Some(
                        result.attempts
                          .map(a => FallbackStep(a.providerId, a.model, a.reason.map(_.toString), a.durationMs))
                      )
                    else None

                  val overrideUpdate =
                    if failedAttempts.nonEmpty then sessionOverrides.update(_ + (req.sessionId -> result.usedCandidate))
                    else IO.unit

                  overrideUpdate.as(
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
                  regCandidates <- registry.getCandidates()
                yield overrides.get(req.sessionId).toList ++ regCandidates
                  .filterNot(c =>
                    overrides.get(req.sessionId).exists(o => o.providerId == c.providerId && o.model == c.model)
                  )
              )
              .flatMap { candidates =>

                val firstCandidate = candidates.headOption

                val maxRetries = Fallback.MaxRetries

                fs2.Stream.eval(IO.ref(false)).flatMap { lockedRef =>
                  fs2.Stream.eval(IO.ref(List.empty[FallbackAttempt])).flatMap { failureRef =>
                    fs2.Stream.eval(IO.ref(Option.empty[ModelCandidate])).flatMap { winnerRef =>
                      def tryCandidate(
                        remaining: List[ModelCandidate],
                        retriesLeft: Int = maxRetries,
                        backoffMs: Long = Fallback.InitialBackoffMs
                      ): fs2.Stream[IO, StreamChunk] =
                        remaining match
                          case Nil =>
                            fs2.Stream.eval(
                              failureRef.get.flatMap { failures =>
                                IO.raiseError(
                                  new FallbackExhaustedError(
                                    if failures.nonEmpty then failures
                                    else
                                      candidates.map(c =>
                                        FallbackAttempt(
                                          c.providerId,
                                          c.model,
                                          None,
                                          None,
                                          0,
                                          0,
                                          java.time.Instant.now().toString
                                        )
                                      )
                                  )
                                )
                              }
                            )
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
                              registry
                                .getAdapter(candidate.providerId)
                                .map(
                                  _.sendMessageStream(
                                    SendMessageParams(
                                      req.messages,
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
                                )
                            )
                            (stream
                              // Per-provider inactivity timeout: detect hung SSE connections
                              // (HTTP alive but no meaningful data). Applied per-provider so
                              // that a timeout on one provider allows the fallback to try the next.
                              .through(inactivityTimeout(Defaults.LlmStreamInactivitySec.seconds))
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
                                    if locked then IO.pure(done.copy(contextWindow = Some(candidate.contextWindow)))
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
                              ++ fs2.Stream.eval(
                                lockedRef.get.flatMap { locked =>
                                  if !locked then
                                    IO.raiseError(
                                      new RuntimeException(
                                        s"Stream completed with no content (${candidate.providerId}/${candidate.model})"
                                      )
                                    )
                                  else IO.unit
                                }
                              ).drain).handleErrorWith { err =>
                                val classification = Fallback.classifyError(err)
                                // Inactivity timeout is a transient error — always allow fallback
                                // even when lockedRef is true (provider sent partial content then hung).
                                // For other errors, if content was already streamed (locked), propagate
                                // upward to avoid duplicating partial output.
                                val isTimeout = classification.reason == FailoverReason.Timeout
                                fs2.Stream.eval(lockedRef.get).flatMap { locked =>
                                  if locked && !isTimeout then fs2.Stream.eval(IO.raiseError(err))
                                  else
                                    val resetLock = if isTimeout && locked then lockedRef.set(false) else IO.unit
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

                                    if classification.permanence == ErrorPermanence.Permanent then
                                      fs2.Stream.eval(
                                        resetLock *>
                                          logger.warn(
                                            s"Stream: ${candidate.providerId}/${candidate.model} permanent error (${classification.reason})"
                                          )
                                          *> failureRef.update(_ :+ attempt)
                                          *> notify
                                      ) *> tryCandidate(rest, maxRetries, Fallback.InitialBackoffMs)
                                    else if retriesLeft > 0 && !isTimeout then
                                      // Only retry same provider for non-timeout errors.
                                      // Timeout means the provider is unresponsive — skip to next.
                                      val jitter = java.util.concurrent.ThreadLocalRandom.current().nextLong(0, 2000)
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
                                      fs2.Stream.eval(
                                        resetLock *> logger.warn(
                                          s"Stream fallback: ${candidate.providerId}/${candidate.model} $skipMsg"
                                        )
                                          *> failureRef.update(_ :+ attempt)
                                          *> notify
                                      ) *> tryCandidate(rest, maxRetries, Fallback.InitialBackoffMs)
                                    end if
                                  end if
                                }
                              }

                      tryCandidate(candidates)
                        .onFinalize {
                          // Persist fallback winner so subsequent calls use the working model
                          winnerRef.get.flatMap { winnerOpt =>
                            (winnerOpt, firstCandidate) match
                              case (Some(winner), Some(first))
                                  if winner.providerId != first.providerId || winner.model != first.model =>
                                sessionOverrides.update(_ + (req.sessionId -> winner))
                              case _ => IO.unit
                          }
                        }
                    }
                  }
                }
              }
          end sendStream

        (handle, registry, release)
      end result
      IO(result).onError(_ => release)
    }
end LlmInterface
