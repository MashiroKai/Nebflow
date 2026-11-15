package nebflow.agent

import cats.effect.{Deferred, IO}
import cats.effect.kernel.Outcome
import cats.syntax.all.*
import io.circe.{Decoder, Encoder, Json}
import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import io.circe.syntax.*
import nebflow.actor.*
import nebflow.core.ProtectedExecutionHandle
import nebflow.shared.*
import nebflow.shared.given

import scala.concurrent.duration.*

/** Operations on a confirmed protected attempt. This is not an admission gate. */
private[nebflow] object ProtectedAgentExecution:
  private given Encoder[Counters] = deriveEncoder[Counters]
  private given Decoder[Counters] = deriveDecoder[Counters]

  /** The immutable model/tool configuration fingerprint admitted with the run. */
  def configBinding(agentDef: AgentDef): String =
    Json.obj(
      "agent" -> agentDef.name.asJson,
      "configuredModel" -> agentDef.model.asJson,
      "effectiveModel" -> Json.Null
    ).noSpaces

  private def checked(handle: ProtectedExecutionHandle): IO[ExecutionChainState] =
    val admission = handle.admission
    handle.authority.observeChain(admission.sessionId, admission.executionId).flatMap {
      case ExecutionChainObservation.Verified(state)
          if state.attemptId == admission.attemptId && state.incarnationId == admission.incarnationId => IO.pure(state)
      case _ => IO.raiseError(new IllegalStateException("execution-attempt-unverified"))
    }

  private def command(handle: ProtectedExecutionHandle, ref: ExecutionCommitRef, event: ExecutionEvent): ExecutionTransition =
    val id = java.util.UUID.randomUUID().toString
    val admission = handle.admission
    ExecutionTransition(admission.sessionId, admission.executionId, id, id, ref,
      admission.attemptId, admission.incarnationId, event)

  private def confirmed(result: ExecutionCommitResult): IO[ExecutionCommitRef] = result match
    case ExecutionCommitResult.Committed(ref) => IO.pure(ref)
    case ExecutionCommitResult.Duplicate(ref) => IO.pure(ref)
    case other => IO.raiseError(new IllegalStateException(s"execution-commit-unconfirmed: $other"))

  def validate(handle: ProtectedExecutionHandle): IO[Unit] = checked(handle).flatMap { state =>
    IO.raiseWhen(state.phase != "active")(new IllegalStateException("execution-not-active"))
  }

  /** Revoke the owned runner before recording an interrupted boundary. */
  def suspendModel(handle: ProtectedExecutionHandle, runner: ExecutionCommitStore#RunnerHandle): IO[Unit] =
    suspendOwned(handle, runner, "model-boundary-review-required")

  def suspendOwned(handle: ProtectedExecutionHandle, runner: ExecutionCommitStore#RunnerHandle, reason: String): IO[Unit] =
    runner.revoke *> checked(handle).flatMap { current =>
      if current.phase == "suspended" && current.runnerStopped then IO.unit
      else
        val suspended = if current.phase == "suspended" then IO.pure(current.ref)
          else handle.authority.transition(command(handle, current.ref,
            ExecutionEvent.Suspended(reason))).flatMap(confirmed)
        suspended.flatMap { ref =>
          runner.confirmStopped(command(handle, ref, ExecutionEvent.RunnerStopped), 1.second).flatMap {
            case ExecutionCommitResult.Rejected("runner-stop-unconfirmed") => IO.unit
            case result => confirmed(result).void
          }
        }
    }

  /** The provider work is owned by the runner and cannot start before ModelRequested. */
  def model[A](
    handle: ProtectedExecutionHandle,
    runner: ExecutionCommitStore#RunnerHandle,
    request: Json,
    retryLimit: Int
  )(work: IO[A])(decision: A => ExecutionModelDecision): IO[A] =
    for
      current <- checked(handle)
      value <- Deferred[IO, A]
      started <- runner.startModel(command(handle, current.ref, ExecutionEvent.ModelRequested(request, retryLimit)))(
        work.flatMap(result => value.complete(result).void)
      )
      pair <- started match
        case Right(pair) => IO.pure(pair)
        case Left(error) => IO.raiseError(new IllegalStateException(s"model-start-unconfirmed: $error"))
      (requested, receipt) = pair
      outcome <- receipt.await
      result <- outcome match
        case Outcome.Succeeded(_) => value.get
        case _ => IO.raiseError(new IllegalStateException("model-result-unconfirmed"))
      installed <- runner.modelDecision(command(handle, requested, ExecutionEvent.ModelDecided(decision(result))), receipt)
      _ <- confirmed(installed)
    yield result

  def modelRound(
    handle: ProtectedExecutionHandle,
    runner: ExecutionCommitStore#RunnerHandle,
    request: LlmRequest,
    retryLimit: Int
  )(work: IO[ConsumeResult]): IO[ConsumeResult] =
    val tools = request.tools.map(_.map(t => Json.obj(
      "name" -> t.name.asJson, "description" -> t.description.asJson, "inputSchema" -> Json.fromJsonObject(t.inputSchema)
    )))
    val snapshot = Json.obj(
      "messages" -> request.messages.asJson, "sessionId" -> request.sessionId.asJson,
      "agentId" -> request.agentId.asJson, "tools" -> tools.asJson, "thinking" -> request.thinking.asJson,
      "systemStable" -> request.systemStable.asJson, "systemDynamic" -> request.systemDynamic.asJson,
      "agentModel" -> request.agentModel.asJson, "searchAllowed" -> request.searchAllowed.asJson,
      "requestOnlyContext" -> request.requestOnlyContext.asJson, "requestId" -> request.requestId.asJson
    )
    def decision(result: ConsumeResult): ExecutionModelDecision =
      val calls = result.toolCalls.map { call =>
        ExecutionToolIntent(java.util.UUID.randomUUID().toString, call.id, call.name, Json.fromJsonObject(call.input))
      }
      ExecutionModelDecision(Json.obj(
        "text" -> result.text.asJson, "thinking" -> result.thinking.asJson,
        "thinkingSignature" -> result.thinkingSignature.asJson, "stopReason" -> result.stopReason.asJson,
        "model" -> result.model.asJson, "contextWindow" -> result.contextWindow.asJson,
        "usage" -> result.usage.map(u => Json.obj(
          "inputTokens" -> u.inputTokens.asJson, "outputTokens" -> u.outputTokens.asJson,
          "cacheReadTokens" -> u.cacheReadTokens.asJson, "cacheWriteTokens" -> u.cacheWriteTokens.asJson
        )).asJson,
        "requestId" -> result.requestId.asJson,
        "rawArguments" -> result.toolCalls.map(_.rawArguments).asJson
      ), calls)
    def retrying(attempt: Int): IO[ConsumeResult] = work.handleErrorWith { error =>
      if attempt >= AgentActor.OverloadRetryMax || !AgentActor.llmFailureRetryable(error) then IO.raiseError(error)
      else
        checked(handle).flatMap { current =>
          val retry = command(handle, current.ref, ExecutionEvent.ModelRetryCharged("classified-transient-provider"))
          val delay = if AgentActor.llmFailureInactivityClass(error) then
            math.max(AgentActor.LlmFailBackoffBaseMs, AgentActor.effectiveInactivityBackoffMinMs)
          else math.min(math.max(AgentActor.LlmFailBackoffBaseMs, AgentActor.OverloadBackoffMinMs),
            AgentActor.LlmFailBackoffMaxMs)
          runner.chargeModelRetry(retry).flatMap(confirmed).void *>
            IO.sleep(delay.millis) *> retrying(attempt + 1)
        }
    }
    if request.pinSkips.nonEmpty || request.searchAllowed || retryLimit < 0 then
      IO.raiseError(new IllegalStateException("unsupported-model-request"))
    else
      model(handle, runner, snapshot, retryLimit)(retrying(0))(decision).flatTap { _ =>
        checked(handle).flatMap { current =>
          current.decision match
            case None => IO.raiseError(new IllegalStateException("model-decision-unavailable"))
            case Some(value) => value.calls.traverse_ { intent =>
              checked(handle).flatMap { before =>
                handle.authority.transition(command(handle, before.ref,
                  ExecutionEvent.ToolPrepared(intent.operationId, intent.asJson))).flatMap(confirmed).void
              }
            }
        }
      }

  /** A deliberately narrow checkpoint; unsupported volatile queues are never silently discarded. */
  def checkpoint(state: AgentState): Either[String, Json] =
    val execution = state.execution
    if state.pendingCompaction.nonEmpty || state.pingpong != nebflow.actor.PingpongState() ||
        execution.emptyResponseRetries < 0 || execution.turnStartMessageCount < 0 ||
        execution.turnStartMessageCount > execution.messages.size || execution.delegateCount != 0 ||
        execution.lastMaintenanceDelegateCount != 0 ||
        execution.mailUsedThisTurn || execution.mailReminders != 0 || execution.errorFreezeCount != 0 ||
        execution.lastErrorFreezeReason.nonEmpty || execution.escalation.nonEmpty ||
        execution.interaction.nonEmpty || execution.pendingEvents.nonEmpty ||
        execution.pendingImmediateInputs.nonEmpty || execution.pendingUserInputs.nonEmpty ||
        execution.outstandingSubagentResults != 0 || execution.owedCompletion.nonEmpty ||
        execution.pendingMailQueueCount != 0 || execution.stopDeferred then Left("unsupported-checkpoint-state")
    else Right(Json.obj(
      "version" -> 2.asJson,
      "messages" -> execution.messages.asJson,
      "turnIdx" -> execution.turnIdx.asJson,
      "currentTurnId" -> execution.currentTurnId.asJson,
      "loopTurnKey" -> state.loopTurnKey.asJson,
      "loopCounters" -> state.loopCounters.asJson,
      "emptyResponseRetries" -> execution.emptyResponseRetries.asJson,
      "turnStartMessageCount" -> execution.turnStartMessageCount.asJson,
      "llmFailRetries" -> execution.llmFailRetries.asJson,
      "llmCallsThisTurn" -> execution.llmCallsThisTurn.asJson
    ))

  def restore(base: AgentState, checkpoint: Json): Either[String, AgentState] =
    val cursor = checkpoint.hcursor
    val fields = Set("version", "messages", "turnIdx", "currentTurnId", "loopTurnKey", "loopCounters",
      "emptyResponseRetries", "turnStartMessageCount", "llmFailRetries", "llmCallsThisTurn")
    for
      _ <- Either.cond(checkpoint.asObject.exists(_.keys.toSet == fields), (), "unsupported-checkpoint-fields")
      version <- cursor.get[Int]("version").left.map(_.message)
      _ <- Either.cond(version == 2, (), "unsupported-checkpoint-version")
      messages <- cursor.get[List[Message]]("messages").left.map(_.message)
      turnIdx <- cursor.get[Int]("turnIdx").left.map(_.message)
      turn <- cursor.get[Long]("currentTurnId").left.map(_.message)
      loopTurnKey <- cursor.get[Long]("loopTurnKey").left.map(_.message)
      loopCounters <- cursor.get[Counters]("loopCounters").left.map(_.message)
      emptyRetries <- cursor.get[Int]("emptyResponseRetries").left.map(_.message)
      turnStart <- cursor.get[Int]("turnStartMessageCount").left.map(_.message)
      retries <- cursor.get[Int]("llmFailRetries").left.map(_.message)
      calls <- cursor.get[Int]("llmCallsThisTurn").left.map(_.message)
      _ <- Either.cond(turnIdx >= 0 && turn >= 0 && loopTurnKey >= 0 && retries >= 0 && calls >= 0 &&
        emptyRetries >= 0 && turnStart >= 0 && turnStart <= messages.size, (), "invalid-checkpoint-counters")
      _ <- checkpointStateEmpty(base)
    yield base.copy(loopTurnKey = loopTurnKey, loopCounters = loopCounters, execution = base.execution.copy(messages = messages, turnIdx = turnIdx,
      currentTurnId = turn, emptyResponseRetries = emptyRetries, turnStartMessageCount = turnStart,
      llmFailRetries = retries, llmCallsThisTurn = calls))

  def restoreVerified(handle: ProtectedExecutionHandle, base: AgentState): IO[AgentState] =
    checked(handle).flatMap { current =>
      current.checkpoint match
        case Some(value) if current.phase == "active" =>
          IO.fromEither(restore(base, value).left.map(new IllegalStateException(_)))
        case _ => IO.raiseError(new IllegalStateException("checkpoint-unavailable-or-not-active"))
    }

  private def checkpointStateEmpty(state: AgentState): Either[String, Unit] = checkpoint(state).map(_ => ())

  def suspend(handle: ProtectedExecutionHandle, reason: String): IO[Unit] =
    checked(handle).flatMap { current =>
      handle.authority.transition(command(handle, current.ref, ExecutionEvent.Suspended(reason))).flatMap(confirmed).void
    }

  def installTerminal(handle: ProtectedExecutionHandle, runner: ExecutionCommitStore#RunnerHandle, envelope: Json): IO[String] =
    checked(handle).flatMap { current =>
      val admission = handle.admission
      val id = s"terminal-${admission.attemptId}"
      val transition = ExecutionTransition(admission.sessionId, admission.executionId, id, id, current.ref,
        admission.attemptId, admission.incarnationId, ExecutionEvent.TerminalInstallPrepared("", envelope))
      runner.installTerminal(transition, envelope).flatMap(confirmed) *> checked(handle).flatMap { state =>
        IO.fromOption(state.terminalResultRef)(new IllegalStateException("terminal-reference-unavailable"))
      }
    }

  def commitTranscriptRound(handle: ProtectedExecutionHandle, state: AgentState): IO[Unit] =
    checked(handle).flatMap { current =>
      val represented = state.execution.messages.flatMap(_.messageId).toSet
      commitRound(handle, state, current.pendingInputs.filter(represented.contains))
    }

  def commitRound(handle: ProtectedExecutionHandle, state: AgentState, consumedInputIds: List[String]): IO[Unit] =
    for
      value <- IO.fromEither(checkpoint(state).left.map(new IllegalStateException(_)))
      current <- checked(handle)
      result <- handle.authority.commitRound(command(handle, current.ref, ExecutionEvent.RoundCommitted(consumedInputIds, value)))
      _ <- confirmed(result)
    yield ()
