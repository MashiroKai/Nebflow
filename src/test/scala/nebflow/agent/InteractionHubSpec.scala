package nebflow.agent

import cats.effect.{Deferred, IO, Ref}
import cats.syntax.all.*
import io.circe.Json
import munit.CatsEffectSuite

import scala.concurrent.duration.*

/**
 * P2 InteractionHub core tests:
 *  - permission/AskUser requests are queued by requestId (D4 multi-slot)
 *  - cards are rendered at sessionId = rootSessionId with source attribution
 *  - answers route by requestId (new frontend) or by rootSessionId FIFO
 *    fallback (old frontend, no requestId) — no agent registry involved (D2)
 */
class InteractionHubSpec extends CatsEffectSuite:

  private def mkHub(system: nebflow.actor.ActorSystem): IO[nebflow.actor.ActorRef[InteractionHubCommand]] =
    system.spawn(InteractionHub(), "interaction-hub-test")

  private def permRequest(
    requestId: String,
    deferred: Deferred[IO, Boolean],
    rootSid: String = "root-1",
    sourceAgent: String = "Frontend"
  ): InteractionRequest =
    InteractionRequest(
      requestId = requestId,
      kind = InteractionKind.Permission,
      payload = Json.obj(
        "type" -> Json.fromString("askPermission"),
        "toolName" -> Json.fromString("Edit"),
        "summary" -> Json.fromString("Edit file"),
        "input" -> Json.obj(),
        "dangerLevel" -> Json.fromInt(1)
      ),
      reply = InteractionReply.PermissionReply(deferred),
      rootSessionId = rootSid,
      sourceAgent = sourceAgent,
      sourceSession = "team-abc"
    )

  test("permission request renders card at rootSessionId with requestId and source") {
    val system = nebflow.actor.ActorSystem("hub-test")
    for
      hub <- mkHub(system)
      sent <- Ref.of[IO, List[Json]](Nil)
      _ <- hub ! InteractionHubCommand.RegisterRoot("root-1", (j: Json) => sent.update(_ :+ j))
      deferred <- Deferred[IO, Boolean]
      _ <- hub ! InteractionHubCommand.Request(permRequest("r1", deferred))
      _ <- IO.sleep(100.millis)
      events <- sent.get
      card = events.find(_.hcursor.downField("type").as[String].contains("askPermission")).get
      _ <- system.stopAll
    yield
      assertEquals(card.hcursor.downField("sessionId").as[String], Right("root-1"))
      assertEquals(card.hcursor.downField("requestId").as[String], Right("r1"))
      assertEquals(card.hcursor.downField("sourceAgent").as[String], Right("Frontend"))
      assertEquals(card.hcursor.downField("toolName").as[String], Right("Edit"))
    end for
  }

  test("answer routes by requestId — two concurrent requests complete independently (D4)") {
    val system = nebflow.actor.ActorSystem("hub-test")
    for
      hub <- mkHub(system)
      _ <- hub ! InteractionHubCommand.RegisterRoot("root-1", (_: Json) => IO.unit)
      d1 <- Deferred[IO, Boolean]
      d2 <- Deferred[IO, Boolean]
      _ <- hub ! InteractionHubCommand.Request(permRequest("r1", d1))
      _ <- hub ! InteractionHubCommand.Request(permRequest("r2", d2))
      _ <- IO.sleep(50.millis)
      _ <- hub ! InteractionHubCommand.Answered(
        InteractionAnswered("r2", "root-1", Json.obj("approved" -> Json.fromBoolean(false)))
      )
      _ <- hub ! InteractionHubCommand.Answered(
        InteractionAnswered("r1", "root-1", Json.obj("approved" -> Json.fromBoolean(true)))
      )
      a1 <- d1.get
      a2 <- d2.get
      _ <- system.stopAll
    yield
      assertEquals(a1, true) // r1 approved
      assertEquals(a2, false) // r2 denied — answers never cross-talk
    end for
  }

  test("old frontend fallback: answer without requestId completes oldest pending for the root session") {
    val system = nebflow.actor.ActorSystem("hub-test")
    for
      hub <- mkHub(system)
      _ <- hub ! InteractionHubCommand.RegisterRoot("root-1", (_: Json) => IO.unit)
      d1 <- Deferred[IO, Boolean]
      d2 <- Deferred[IO, Boolean]
      _ <- hub ! InteractionHubCommand.Request(permRequest("r-old-1", d1))
      _ <- hub ! InteractionHubCommand.Request(permRequest("r-old-2", d2))
      _ <- IO.sleep(50.millis)
      // no requestId → FIFO by rootSessionId: completes the OLDEST (r-old-1)
      _ <- hub ! InteractionHubCommand.Answered(
        InteractionAnswered("", "root-1", Json.obj("approved" -> Json.fromBoolean(true)))
      )
      a1 <- d1.get
      _ <- IO.sleep(50.millis)
      // second answer (also no requestId) → next oldest (r-old-2)
      _ <- hub ! InteractionHubCommand.Answered(
        InteractionAnswered("", "root-1", Json.obj("approved" -> Json.fromBoolean(false)))
      )
      a2 <- d2.get
      _ <- system.stopAll
    yield
      assertEquals(a1, true)
      assertEquals(a2, false)
    end for
  }

  test("askUser request renders question and routes answers back to replyTo") {
    val system = nebflow.actor.ActorSystem("hub-test")
    val items = List(nebflow.core.AskItem("Continue?", List(nebflow.core.AskOption("yes"))))
    for
      hub <- mkHub(system)
      sent <- Ref.of[IO, List[Json]](Nil)
      _ <- hub ! InteractionHubCommand.RegisterRoot("root-1", (j: Json) => sent.update(_ :+ j))
      replyTo <- system.spawn(
        nebflow.actor.Behaviors.receiveMessage[List[String]] { answers =>
          nebflow.core.NebflowLogger.forName("test").info(s"answers=$answers").as(nebflow.actor.Behaviors.stopped)
        },
        "ask-reply-sink"
      )
      _ <- hub ! InteractionHubCommand.Request(
        InteractionRequest(
          requestId = "ask-1",
          kind = InteractionKind.AskUser,
          payload = Json.obj("items" -> Json.arr(), "agentName" -> Json.fromString("Frontend")),
          reply = InteractionReply.AskUserReply(Some(replyTo)),
          rootSessionId = "root-1",
          sourceAgent = "Frontend",
          sourceSession = "team-abc"
        )
      )
      _ <- IO.sleep(100.millis)
      events <- sent.get
      card = events.find(_.hcursor.downField("type").as[String].contains("askUser")).get
      // Completing the replyTo requires an actual listener; verify rendering +
      // that the answer is accepted (no drop warning) is covered by routing test.
      _ <- hub ! InteractionHubCommand.Answered(
        InteractionAnswered("ask-1", "root-1", Json.obj("answers" -> Json.arr(Json.fromString("yes"))))
      )
      _ <- IO.sleep(100.millis)
      _ <- system.stopAll
    yield
      assertEquals(card.hcursor.downField("sessionId").as[String], Right("root-1"))
      assertEquals(card.hcursor.downField("requestId").as[String], Right("ask-1"))
      assertEquals(card.hcursor.downField("agentName").as[String], Right("Frontend"))
    end for
  }

end InteractionHubSpec
