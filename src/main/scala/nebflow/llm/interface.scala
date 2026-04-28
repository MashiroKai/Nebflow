package nebflow.llm

import cats.effect.IO
import cats.syntax.all.*
import nebflow.core.NebflowLogger
import nebflow.shared.*
import sttp.client4.httpclient.fs2.HttpClientFs2Backend

object LlmInterface:
  private val logger = NebflowLogger.forName("nebflow.llm")

  def createLlm(options: Option[LlmOptions] = None): IO[(LlmHandle[IO], IO[Unit])] =
    HttpClientFs2Backend.resource[IO]().allocated.map { case (backend, release) =>
      val config = Config.loadServiceConfig(options.flatMap(_.configPath))
      val registry = ProviderRegistry(config, backend)
      val sessionOverrides = scala.collection.mutable.Map.empty[String, ModelCandidate]

      val handle = new LlmHandle[IO]:
        def send(req: LlmRequest): IO[LlmResponse] =
          val start = System.currentTimeMillis()
          val candidates = sessionOverrides.get(req.sessionId).toList ++ registry
            .getCandidates()
            .filterNot(c =>
              sessionOverrides.get(req.sessionId).exists(o => o.providerId == c.providerId && o.model == c.model)
            )

          Fallback
            .tryProviderWithFallback[AdapterResponse](
              candidates,
              candidate =>
                registry
                  .getAdapter(candidate.providerId)
                  .sendMessage(
                    SendMessageParams(req.messages, candidate.model, req.tools, req.maxTokens, req.thinking)
                  )
            )
            .map { result =>
              val durationMs = System.currentTimeMillis() - start
              val failedAttempts = result.attempts.filter(_.reason.isDefined)
              val fallbackChain =
                if failedAttempts.nonEmpty then
                  Some(
                    result.attempts
                      .map(a => FallbackStep(a.providerId, a.model, a.reason.map(_.toString), a.durationMs))
                  )
                else None

              if failedAttempts.nonEmpty then sessionOverrides(req.sessionId) = result.usedCandidate

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
            }
        end send

        def sendStream(req: LlmRequest): fs2.Stream[IO, StreamChunk] =
          val candidates = sessionOverrides.get(req.sessionId).toList ++ registry
            .getCandidates()
            .filterNot(c =>
              sessionOverrides.get(req.sessionId).exists(o => o.providerId == c.providerId && o.model == c.model)
            )

          fs2.Stream.eval(IO.ref(false)).flatMap { lockedRef =>
            fs2.Stream.eval(IO.ref(List.empty[FallbackAttempt])).flatMap { failureRef =>
              def tryCandidate(candidates: List[ModelCandidate]): fs2.Stream[IO, StreamChunk] =
                candidates match
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
                    val stream = registry
                      .getAdapter(candidate.providerId)
                      .sendMessageStream(
                        SendMessageParams(req.messages, candidate.model, req.tools, req.maxTokens, req.thinking)
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
                            val attempt = FallbackAttempt(
                              candidate.providerId,
                              candidate.model,
                              Some(Fallback.classifyError(err).reason),
                              Some(Fallback.classifyError(err).permanence),
                              0,
                              0,
                              java.time.Instant.now().toString
                            )
                            fs2.Stream
                              .eval(
                                logger.warn(
                                  s"Stream fallback: ${candidate.providerId}/${candidate.model} failed, trying next"
                                )
                                  *> failureRef.update(_ :+ attempt)
                              )
                              .flatMap(_ => tryCandidate(rest))
                        }
                      }

              tryCandidate(candidates)
            }
          }
        end sendStream

      (handle, release)
    }
end LlmInterface
