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

  def createLlm(options: Option[LlmOptions] = None): IO[(LlmHandle[IO], IO[Unit])] =
    HttpClientFs2Backend.resource[IO]().allocated.map { case (backend, release) =>
      val config = Config.loadServiceConfig(options.flatMap(_.configPath))
      val registry = ProviderRegistry(config, backend)
      val sessionOverrides: Ref[IO, Map[String, ModelCandidate]] = Ref.unsafe(Map.empty)

      val handle = new LlmHandle[IO]:
        def send(req: LlmRequest): IO[LlmResponse] =
          val start = System.currentTimeMillis()
          sessionOverrides.get.flatMap { overrides =>
            val candidates = overrides.get(req.sessionId).toList ++ registry
              .getCandidates()
              .filterNot(c =>
                overrides.get(req.sessionId).exists(o => o.providerId == c.providerId && o.model == c.model)
              )

            Fallback
              .tryProviderWithFallback[AdapterResponse](
                candidates,
                candidate =>
                  registry
                    .getAdapter(candidate.providerId)
                    .flatMap(
                      _.sendMessage(
                        SendMessageParams(
                          req.messages,
                          candidate.model,
                          req.tools,
                          Some(candidate.maxTokens),
                          req.thinking,
                          req.systemStable,
                          req.systemDynamic,
                          Some(req.sessionId),
                          Some(req.agentId)
                        )
                      )
                    ),
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
                      fallbackChain = fallbackChain
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
          fs2.Stream.eval(sessionOverrides.get).flatMap { overrides =>
            val candidates = overrides.get(req.sessionId).toList ++ registry
              .getCandidates()
              .filterNot(c =>
                overrides.get(req.sessionId).exists(o => o.providerId == c.providerId && o.model == c.model)
              )

            val maxRetries = Fallback.MaxRetries

            fs2.Stream.eval(IO.ref(false)).flatMap { lockedRef =>
              fs2.Stream.eval(IO.ref(List.empty[FallbackAttempt])).flatMap { failureRef =>
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
                                req.thinking,
                                req.systemStable,
                                req.systemDynamic,
                                Some(req.sessionId),
                                Some(req.agentId)
                              )
                            )
                          )
                      )
                      stream
                        .evalTap { chunk =>
                          chunk match
                            case StreamChunk.TextDelta(_) | StreamChunk.ToolCallChunk(_) =>
                              lockedRef.set(true)
                            case _ => IO.unit
                        }
                        .handleErrorWith { err =>
                          fs2.Stream.eval(lockedRef.get).flatMap { locked =>
                            if locked then fs2.Stream.eval(IO.raiseError(err))
                            else
                              val classification = Fallback.classifyError(err)
                              val attempt = FallbackAttempt(
                                candidate.providerId,
                                candidate.model,
                                Some(classification.reason),
                                Some(classification.permanence),
                                0,
                                maxRetries - retriesLeft,
                                java.time.Instant.now().toString
                              )
                              val notify = onAttempt.traverse_(_.apply(attempt))

                              if classification.permanence == ErrorPermanence.Permanent then
                                fs2.Stream.eval(
                                  logger.warn(
                                    s"Stream: ${candidate.providerId}/${candidate.model} permanent error (${classification.reason})"
                                  )
                                    *> failureRef.update(_ :+ attempt)
                                    *> notify
                                ) *> tryCandidate(rest, maxRetries, Fallback.InitialBackoffMs)
                              else if retriesLeft > 0 then
                                val jitter = java.util.concurrent.ThreadLocalRandom.current().nextLong(0, 2000)
                                val delay = math.min(backoffMs + jitter, Fallback.MaxBackoffMs)
                                fs2.Stream.eval(
                                  notify *> logger.warn(
                                    s"Stream retry ${candidate.providerId}/${candidate.model}: ${classification.reason} (${retriesLeft} left, ${delay}ms)"
                                  ) *> IO.sleep(delay.millis)
                                ) *> tryCandidate(remaining, retriesLeft - 1, backoffMs * 2)
                              else
                                fs2.Stream.eval(
                                  logger.warn(
                                    s"Stream fallback: ${candidate.providerId}/${candidate.model} retries exhausted"
                                  )
                                    *> failureRef.update(_ :+ attempt)
                                    *> notify
                                ) *> tryCandidate(rest, maxRetries, Fallback.InitialBackoffMs)
                              end if
                          }
                        }

                tryCandidate(candidates)
              }
            }
          }
        end sendStream

      (handle, release)
    }
end LlmInterface
