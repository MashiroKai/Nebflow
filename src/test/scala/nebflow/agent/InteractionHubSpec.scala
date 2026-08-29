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

  // ---------- #12: approval link reliability ----------

  // ===== 第六件 (2026-08-30): chat-input passthrough for pending AskUser =====

  private def askRequest(
      requestId: String,
      gotAnswers: Ref[IO, Option[List[String]]],
      rootSid: String = "root-1",
      system: nebflow.actor.ActorSystem
  ): IO[InteractionRequest] =
    system
      .spawn(
        nebflow.actor.Behaviors.receiveMessage[List[String]] { answers =>
          gotAnswers.set(Some(answers)).as(nebflow.actor.Behaviors.stopped)
        },
        s"ask-sink-$requestId"
      )
      .map { sink =>
        InteractionRequest(
          requestId = requestId,
          kind = InteractionKind.AskUser,
          payload = Json.obj("items" -> Json.arr(), "agentName" -> Json.fromString("Frontend")),
          reply = InteractionReply.AskUserReply(Some(sink)),
          rootSessionId = rootSid,
          sourceAgent = "Frontend",
          sourceSession = "team-abc"
        )
      }

  test("chat-input passthrough: pending AskUser consumes text as tool result + card closes + slot removed") {
    val system = nebflow.actor.ActorSystem("hub-passthrough-1")
    for
      hub <- mkHub(system)
      sent <- Ref.of[IO, List[Json]](Nil)
      _ <- hub ! InteractionHubCommand.RegisterRoot("root-1", (j: Json) => sent.update(_ :+ j))
      gotAnswers <- Ref.of[IO, Option[List[String]]](None)
      req <- askRequest("pt-1", gotAnswers, system = system)
      _ <- hub ! InteractionHubCommand.Request(req)
      _ <- IO.sleep(50.millis)
      answered <- Deferred[IO, Boolean]
      _ <- hub ! InteractionHubCommand.AnswerViaChatInput("root-1", "自由输入的回答", answered)
      hit <- answered.get
      answers <- gotAnswers.get
      _ <- IO.sleep(50.millis)
      events <- sent.get
      _ <- system.stopAll
    yield
      assertEquals(hit, true) // gateway must NOT fall back to normal dispatch
      assertEquals(answers, Some(List("自由输入的回答"))) // free-text IS the tool result
      // frontend card-close signal broadcast with the consumed requestId
      val close = events.find(_.hcursor.downField("type").as[String].contains("askUserAnswered")).get
      assertEquals(close.hcursor.downField("requestId").as[String], Right("pt-1"))
      assertEquals(close.hcursor.downField("via").as[String], Right("chat-input"))
    end for
  }

  test("chat-input passthrough: permission cards are never consumed (kind filter)") {
    val system = nebflow.actor.ActorSystem("hub-passthrough-2")
    for
      hub <- mkHub(system)
      _ <- hub ! InteractionHubCommand.RegisterRoot("root-1", (_: Json) => IO.unit)
      dPerm <- Deferred[IO, Boolean]
      _ <- hub ! InteractionHubCommand.Request(permRequest("pt-perm", dPerm))
      _ <- IO.sleep(50.millis)
      answered <- Deferred[IO, Boolean]
      _ <- hub ! InteractionHubCommand.AnswerViaChatInput("root-1", "hello", answered)
      hit <- answered.get
      _ <- IO.sleep(50.millis)
      permState <- dPerm.tryGet
      _ <- system.stopAll
    yield
      assertEquals(hit, false) // fall back to normal dispatch
      assertEquals(permState, None) // permission card untouched, still answerable
    end for
  }

  test("chat-input passthrough: no pending card → false (normal dispatch preserved)") {
    val system = nebflow.actor.ActorSystem("hub-passthrough-3")
    for
      hub <- mkHub(system)
      _ <- hub ! InteractionHubCommand.RegisterRoot("root-1", (_: Json) => IO.unit)
      answered <- Deferred[IO, Boolean]
      _ <- hub ! InteractionHubCommand.AnswerViaChatInput("root-1", "plain message", answered)
      hit <- answered.get
      _ <- system.stopAll
    yield assertEquals(hit, false)
    end for
  }

  test("chat-input passthrough: consumes only the OLDEST AskUser — queued cards survive untouched") {
    val system = nebflow.actor.ActorSystem("hub-passthrough-4")
    for
      hub <- mkHub(system)
      sent <- Ref.of[IO, List[Json]](Nil)
      _ <- hub ! InteractionHubCommand.RegisterRoot("root-1", (j: Json) => sent.update(_ :+ j))
      gotA <- Ref.of[IO, Option[List[String]]](None)
      gotB <- Ref.of[IO, Option[List[String]]](None)
      reqA <- askRequest("pt-old", gotA, system = system)
      _ <- hub ! InteractionHubCommand.Request(reqA)
      _ <- IO.sleep(30.millis)
      reqB <- askRequest("pt-new", gotB, system = system)
      _ <- hub ! InteractionHubCommand.Request(reqB)
      _ <- IO.sleep(50.millis)
      answered <- Deferred[IO, Boolean]
      _ <- hub ! InteractionHubCommand.AnswerViaChatInput("root-1", "first answer", answered)
      hit <- answered.get
      aAns <- gotA.get
      _ <- IO.sleep(50.millis)
      // 场景② invariant: the QUEUED (newer) card is untouched — still answerable
      _ <- hub ! InteractionHubCommand.Answered(
        InteractionAnswered("pt-new", "root-1", Json.obj("answers" -> Json.arr(Json.fromString("later pick"))))
      )
      _ <- IO.sleep(300.millis)
      bAns <- gotB.get
      _ <- system.stopAll
    yield
      assertEquals(hit, true)
      assertEquals(aAns, Some(List("first answer"))) // oldest consumed
      assertEquals(bAns, Some(List("later pick"))) // queued card intact
    end for
  }

  test("chat-input passthrough: session-scoped — another root's pending card is not routed") {
    val system = nebflow.actor.ActorSystem("hub-passthrough-5")
    for
      hub <- mkHub(system)
      _ <- hub ! InteractionHubCommand.RegisterRoot("root-1", (_: Json) => IO.unit)
      gotA <- Ref.of[IO, Option[List[String]]](None)
      reqA <- askRequest("pt-x", gotA, rootSid = "root-other", system = system)
      _ <- hub ! InteractionHubCommand.Request(reqA)
      _ <- IO.sleep(50.millis)
      answered <- Deferred[IO, Boolean]
      _ <- hub ! InteractionHubCommand.AnswerViaChatInput("root-1", "wrong window", answered)
      hit <- answered.get
      aAns <- gotA.get
      _ <- IO.sleep(50.millis)
      // the foreign card is still consumable by its own session
      _ <- hub ! InteractionHubCommand.Answered(
        InteractionAnswered("pt-x", "root-other", Json.obj("answers" -> Json.arr(Json.fromString("right window"))))
      )
      _ <- IO.sleep(300.millis)
      aAns2 <- gotA.get
      _ <- system.stopAll
    yield
      assertEquals(hit, false) // no cross-session routing
      assertEquals(aAns, None)
      assertEquals(aAns2, Some(List("right window"))) // card survived, still answerable
    end for
  }

  // 递进式放行链 (2026-08-30): escalated answers carry an extra `upgradeMode`
  // field — the hub must treat the payload exactly like a plain approval
  // (extra fields are opaque; only `approved` drives the deferred).
  test("escalated answer (payload with upgradeMode) completes like a plain approval") {
    val system = nebflow.actor.ActorSystem("hub-test-escalate")
    for
      hub <- mkHub(system)
      _ <- hub ! InteractionHubCommand.RegisterRoot("root-1", (_: Json) => IO.unit)
      d <- Deferred[IO, Boolean]
      _ <- hub ! InteractionHubCommand.Request(permRequest("r-up", d))
      _ <- IO.sleep(50.millis)
      _ <- hub ! InteractionHubCommand.Answered(
        InteractionAnswered(
          "r-up",
          "root-1",
          Json.obj("approved" -> Json.fromBoolean(true), "upgradeMode" -> Json.fromString("auto-edits"))
        )
      )
      a <- d.get.timeout(2.seconds)
      _ <- system.stopAll
    yield assertEquals(a, true)
    end for
  }

  test("#12 invalid answer shape does not consume the card — deferred still completable") {
    val system = nebflow.actor.ActorSystem("hub-test")
    for
      hub <- mkHub(system)
      _ <- hub ! InteractionHubCommand.RegisterRoot("root-1", (_: Json) => IO.unit)
      d <- Deferred[IO, Boolean]
      _ <- hub ! InteractionHubCommand.Request(permRequest("r-shape", d))
      _ <- IO.sleep(50.millis)
      // askUser-shaped answer routed at a permission card: must NOT complete
      // anything and must NOT delete the slot (the card stays answerable).
      _ <- hub ! InteractionHubCommand.Answered(
        InteractionAnswered("r-shape", "root-1", Json.obj("answers" -> Json.arr(Json.fromString("yes"))))
      )
      _ <- IO.sleep(50.millis)
      notCompleted <- d.tryGet
      // correct answer still reaches the SAME card
      _ <- hub ! InteractionHubCommand.Answered(
        InteractionAnswered("r-shape", "root-1", Json.obj("approved" -> Json.fromBoolean(true)))
      )
      a <- d.get
      _ <- system.stopAll
    yield
      assertEquals(notCompleted, None) // card RETAINED, deferred not stranded
      assertEquals(a, true)
    end for
  }

  test("#12 fallback skips kind-incompatible cards — askUser answer never wires into an older permission card") {
    val system = nebflow.actor.ActorSystem("hub-test")
    val items = List(nebflow.core.AskItem("Continue?", List(nebflow.core.AskOption("yes"))))
    for
      hub <- mkHub(system)
      _ <- hub ! InteractionHubCommand.RegisterRoot("root-1", (_: Json) => IO.unit)
      // permission card is the OLDEST pending (would win naive FIFO matching)
      dPerm <- Deferred[IO, Boolean]
      _ <- hub ! InteractionHubCommand.Request(permRequest("r-perm-old", dPerm))
      gotAnswers <- Ref.of[IO, Option[List[String]]](None)
      replyTo <- system.spawn(
        nebflow.actor.Behaviors.receiveMessage[List[String]] { answers =>
          gotAnswers.set(Some(answers)).as(nebflow.actor.Behaviors.stopped)
        },
        "ask-reply-sink-12"
      )
      _ <- hub ! InteractionHubCommand.Request(
        InteractionRequest(
          requestId = "r-ask-new",
          kind = InteractionKind.AskUser,
          payload = Json.obj("items" -> Json.arr(), "agentName" -> Json.fromString("Frontend")),
          reply = InteractionReply.AskUserReply(Some(replyTo)),
          rootSessionId = "root-1",
          sourceAgent = "Frontend",
          sourceSession = "team-abc"
        )
      )
      _ <- IO.sleep(50.millis)
      // answer WITHOUT requestId (old frontend) but askUser-shaped: the naive
      // oldest-first match would wire it into the permission deferred and
      // strand it; #12 requires it to reach the askUser card instead.
      _ <- hub ! InteractionHubCommand.Answered(
        InteractionAnswered("", "root-1", Json.obj("answers" -> Json.arr(Json.fromString("yes"))))
      )
      _ <- IO.sleep(100.millis)
      permState <- dPerm.tryGet
      answersOpt <- gotAnswers.get
      _ <- system.stopAll
    yield
      assertEquals(permState, None) // permission card untouched (not cross-wired)
      assertEquals(answersOpt, Some(List("yes"))) // answer reached the askUser card
    end for
  }

  // ===== F4 (#433): unreachable-root fanout =====

  test("F4: unreachable root fans the card out to other registered roots with fallback flag") {
    val system = nebflow.actor.ActorSystem("hub-test-f4a")
    for
      hub <- mkHub(system)
      sentA <- Ref.of[IO, List[Json]](Nil)
      sentB <- Ref.of[IO, List[Json]](Nil)
      _ <- hub ! InteractionHubCommand.RegisterRoot("live-root", (j: Json) => sentA.update(_ :+ j))
      _ <- hub ! InteractionHubCommand.RegisterRoot("live-root-2", (j: Json) => sentB.update(_ :+ j))
      deferred <- Deferred[IO, Boolean]
      // root "zombie" never registered — the incident shape
      _ <- hub ! InteractionHubCommand.Request(permRequest("f4-1", deferred, rootSid = "zombie"))
      _ <- IO.sleep(100.millis)
      eventsA <- sentA.get
      eventsB <- sentB.get
      cardA = eventsA.find(_.hcursor.downField("type").as[String].contains("askPermission")).get
      cardB = eventsB.find(_.hcursor.downField("type").as[String].contains("askPermission")).get
      _ <- system.stopAll
    yield
      // both live roots got the card, flagged fallback with the zombie root id
      assertEquals(cardA.hcursor.downField("fallback").as[Boolean], Right(true))
      assertEquals(cardB.hcursor.downField("fallback").as[Boolean], Right(true))
      assertEquals(cardA.hcursor.downField("fallbackRoot").as[String], Right("zombie"))
      assertEquals(cardB.hcursor.downField("fallbackRoot").as[String], Right("zombie"))
      // requestId preserved so an answer from ANY window completes the pending
      assertEquals(cardA.hcursor.downField("requestId").as[String], Right("f4-1"))
    end for
  }

  test("F4: answering a fanned-out card by requestId completes the pending deferred") {
    val system = nebflow.actor.ActorSystem("hub-test-f4b")
    for
      hub <- mkHub(system)
      sent <- Ref.of[IO, List[Json]](Nil)
      _ <- hub ! InteractionHubCommand.RegisterRoot("live-root", (j: Json) => sent.update(_ :+ j))
      deferred <- Deferred[IO, Boolean]
      _ <- hub ! InteractionHubCommand.Request(permRequest("f4-2", deferred, rootSid = "zombie"))
      _ <- IO.sleep(100.millis)
      card = sent.get.map(_.find(_.hcursor.downField("type").as[String].contains("askPermission")).get).unsafeRunSync()
      // user answers from the LIVE window (sessionId = live-root), card was for zombie —
      // requestId matching must complete the pending request anyway
      _ <- hub ! InteractionHubCommand.Answered(
        InteractionAnswered(
          requestId = card.hcursor.downField("requestId").as[String].getOrElse(""),
          rootSessionId = "live-root",
          payload = Json.obj("approved" -> Json.fromBoolean(true))
        )
      )
      answered <- deferred.get.timeout(2.seconds)
      _ <- system.stopAll
    yield assertEquals(answered, true)
    end for
  }

  test("F4: no registered roots at all keeps the original WARN-drop behavior") {
    val system = nebflow.actor.ActorSystem("hub-test-f4c")
    for
      hub <- mkHub(system)
      deferred <- Deferred[IO, Boolean]
      _ <- hub ! InteractionHubCommand.Request(permRequest("f4-3", deferred, rootSid = "zombie"))
      _ <- IO.sleep(100.millis)
      // pending slot still registered — the deferred remains completable
      completed <- deferred.tryGet
      _ <- system.stopAll
    yield assertEquals(completed, None) // timed-out path still owns auto-deny
    end for
  }

end InteractionHubSpec
