package nebflow.agent

import cats.effect.{Deferred, IO}
import cats.syntax.all.*
import fs2.Stream
import io.circe.{Json, JsonObject}
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.{ActorSystem, AgentCommand, AgentDef}
import nebflow.core.mail.CompletionDebtStore
import nebflow.shared.{FallbackAttempt, LlmHandle, LlmRequest, LlmResponse, Message, MessageRole, PathUtil, StreamChunk}
import nebflow.shared.given

import scala.concurrent.duration.*

/** Real actor regression: a stale terminal must not settle a newer completion generation. */
class CompletionIdentityActorGateSpec extends CatsEffectSuite:
  override def munitIOTimeout: FiniteDuration = 90.seconds

  test("idle actor: stale generation with matching owner, recipient and messages cannot settle current debt") {
    val tmp = os.temp.dir(prefix = "completion-identity-gate-")
    val previousRoot = PathUtil.dataRoot
    val previousLog = nebflow.core.LlmLogWriter.isEnabled
    val system = ActorSystem("completion-identity-stale")
    val owner = "delegate-identity-stale"
    val recipient = "identity-parent"
    val messages = List(Message(MessageRole.Assistant, Left("same report")))
    val program = for
      _ <- IO { PathUtil.setDataRoot(tmp); nebflow.core.LlmLogWriter.setEnabled(false) }
      entered <- Deferred[IO, Unit]
      llm = new LlmHandle[IO]:
        def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new RuntimeException("unexpected send"))
        def sendStream(req: LlmRequest, onAttempt: Option[FallbackAttempt => IO[Unit]] = None): Stream[IO, StreamChunk] =
          Stream.eval(entered.complete(())).drain ++ Stream(StreamChunk.TextDelta("ok"), StreamChunk.Done(None, None))
      resources <- SpecResources.mkResources(system, tmp, llm)
      _ <- CompletionDebtStore.record(owner, recipient, messages)
      initial <- CompletionDebtStore.pending(owner)
      generation = initial.toOption.flatten.get.generation
      _ = assert(generation.nonEmpty)
      actor <- system.spawn(
        AgentActor(AgentDef(name = "IdentityGate", description = "identity gate", tools = Nil, systemPrompt = ""),
          resources, wsSend = (_: Json) => IO.unit, depth = 0, sessionId = Some(recipient), sessionName = Some(recipient)),
        recipient
      )
      _ <- actor ! AgentCommand.ExternalEvent(
        source = "delegate", eventType = "completed", payload = "same report",
        correlationId = Some(owner),
        metadata = JsonObject(
          "parentSessionId" -> Json.fromString(recipient),
          "completionGeneration" -> Json.fromString("stale-generation"),
          "completionMessages" -> messages.asJson
        )
      )
      _ <- entered.get.timeout(30.seconds)
      after <- CompletionDebtStore.pending(owner)
    yield
      assertEquals(after.toOption.flatten.map(_.generation), Some(generation))
      assertEquals(after.toOption.flatten.map(_.phase), Some("deliverable"), "stale generation must not acknowledge current debt")
    program.guarantee(system.stopAll *> IO { PathUtil.setDataRoot(previousRoot); nebflow.core.LlmLogWriter.setEnabled(previousLog) })
  }
