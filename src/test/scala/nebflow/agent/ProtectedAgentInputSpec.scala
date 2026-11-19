package nebflow.agent

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import fs2.Stream
import io.circe.Json
import munit.CatsEffectSuite
import nebflow.actor.{ActorSystem, AgentCommand, AgentDef}
import nebflow.core.ExecutionCommitPort
import nebflow.shared.*

import scala.concurrent.duration.*

class ProtectedAgentInputSpec extends CatsEffectSuite:
  test("real idle actor durably accepts and deduplicates protected inputs without calling the model") {
    val system = ActorSystem("protected-input-spec")
    val program = for
      root <- IO.blocking(os.temp.dir(prefix = "protected-agent-input-"))
      calls <- Ref.of[IO, Int](0)
      llm = new LlmHandle[IO]:
        def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new IllegalStateException("unexpected send"))
        def sendStream(req: LlmRequest, onAttempt: Option[FallbackAttempt => IO[Unit]] = None): Stream[IO, StreamChunk] =
          Stream.eval(calls.update(_ + 1)).drain
      _ <- (IO.blocking(os.makeDir.all(root / "sessions")) *>
        ExecutionCommitStore.resource((root / "sessions").toNIO).use { authority =>
          val admission = ExecutionAdmission("protected-input", "run", "commit", "request", "attempt", "incarnation",
            "owner", "business", "audit", Json.fromString("initial"), "initial")
          for
            bound <- ExecutionCommitPort.admitHandle(authority, admission)
            handle = bound.toOption.get
            base <- SpecResources.mkResources(system, root, llm)
            resources = base.copy(executionCommitPort = authority, protectedExecutions = Map(admission.sessionId -> handle))
            actor <- system.spawn(
              AgentActor(AgentDef("protected", "protected"), resources, wsSend = (_: Json) => IO.unit,
                depth = 1, sessionId = Some(admission.sessionId)),
              admission.sessionId
            )
            _ <- actor ! AgentCommand.UserInput(text = "one", replyTo = None, clientMessageId = Some("stable-user"))
            _ <- actor ! AgentCommand.UserInput(text = "one", replyTo = None, clientMessageId = Some("stable-user"))
            _ <- actor ! AgentCommand.ImmediateInput(text = "two", clientMessageId = Some("stable-immediate"))
            observed <- {
              def waitForInputs: IO[ExecutionChainObservation] =
                authority.observeChain(admission.sessionId, admission.executionId).flatMap {
                  case verified @ ExecutionChainObservation.Verified(state) if state.pendingInputs.size == 3 => IO.pure(verified)
                  case _ => IO.sleep(10.millis) *> waitForInputs
                }
              waitForInputs.timeout(5.seconds)
            }
            count <- calls.get
            _ <- system.stopAll
          yield
            val state = observed.asInstanceOf[ExecutionChainObservation.Verified].state
            assertEquals(state.pendingInputs, List("initial", "stable-user", "stable-immediate"))
            assertEquals(count, 0)
        }).guarantee(system.stopAll *> IO.blocking(os.remove.all(root)))
    yield ()
    program
  }
