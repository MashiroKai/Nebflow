package nebscala.llm

import nebscala.shared.{LlmHandle, LlmRequest, LlmResponse, StreamChunk, FallbackStep, LlmOptions, LlmMeta, TokenUsage}
import cats.effect.IO
import cats.syntax.all.*

object LlmInterface:
  def createLlm(options: Option[LlmOptions] = None): LlmHandle[IO] =
    val config = Config.loadServiceConfig(options.flatMap(_.configPath))
    val registry = ProviderRegistry(config)
    val sessionOverrides = scala.collection.mutable.Map.empty[String, ModelCandidate]

    new LlmHandle[IO]:
      def send(req: LlmRequest): IO[LlmResponse] =
        val start = System.currentTimeMillis()
        val candidates = sessionOverrides.get(req.sessionId).toList ++ registry.getCandidates()
          .filterNot(c => sessionOverrides.get(req.sessionId).exists(o => o.providerId == c.providerId && o.model == c.model))

        Fallback.tryProviderWithFallback[AdapterResponse](
          candidates,
          candidate => registry.getAdapter(candidate.providerId).sendMessage(
            SendMessageParams(req.messages, candidate.model, req.tools, req.maxTokens)
          )
        ).map { result =>
          val durationMs = System.currentTimeMillis() - start
          val failedAttempts = result.attempts.filter(_.reason.isDefined)
          val fallbackChain = if failedAttempts.nonEmpty then
            Some(result.attempts.map(a => FallbackStep(a.providerId, a.model, a.reason.map(_.toString), a.durationMs)))
          else None

          if failedAttempts.nonEmpty then
            sessionOverrides(req.sessionId) = result.usedCandidate

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

      def sendStream(req: LlmRequest): fs2.Stream[IO, StreamChunk] =
        val candidates = sessionOverrides.get(req.sessionId).toList ++ registry.getCandidates()
          .filterNot(c => sessionOverrides.get(req.sessionId).exists(o => o.providerId == c.providerId && o.model == c.model))

        fs2.Stream.eval(IO.ref(false)).flatMap { lockedRef =>
          fs2.Stream.eval(IO.ref(List.empty[FallbackAttempt])).flatMap { failureRef =>
            def tryCandidate(candidates: List[ModelCandidate]): fs2.Stream[IO, StreamChunk] =
              candidates match
                case Nil =>
                  fs2.Stream.eval(
                    failureRef.get.flatMap { failures =>
                      IO.raiseError(new FallbackExhaustedError(
                        if failures.nonEmpty then failures
                        else candidates.map(c => FallbackAttempt(c.providerId, c.model, None, None, 0, 0, java.time.Instant.now().toString))
                      ))
                    }
                  )
                case candidate :: rest =>
                  val stream = registry.getAdapter(candidate.providerId).sendMessageStream(
                    SendMessageParams(req.messages, candidate.model, req.tools, req.maxTokens)
                  )
                  stream.evalTap { chunk =>
                    chunk match
                      case StreamChunk.TextDelta(_) | StreamChunk.ToolCallChunk(_) =>
                        lockedRef.set(true)
                      case _ => IO.unit
                  }.handleErrorWith { err =>
                    fs2.Stream.eval(lockedRef.get).flatMap { locked =>
                      if locked then
                        fs2.Stream.eval(IO.raiseError(err))
                      else
                        val attempt = FallbackAttempt(
                          candidate.providerId, candidate.model,
                          Some(Fallback.classifyError(err).reason),
                          Some(Fallback.classifyError(err).permanence),
                          0, 0, java.time.Instant.now().toString
                        )
                        fs2.Stream.eval(failureRef.update(_ :+ attempt)).flatMap(_ =>
                          tryCandidate(rest))
                    }
                  }

            tryCandidate(candidates)
          }
        }
