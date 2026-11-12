package nebflow.core.mail

import cats.effect.IO
import munit.CatsEffectSuite
import io.circe.syntax.*
import nebflow.shared.{Message, MessageRole, PathUtil}
import nebflow.shared.given

class CompletionDebtStoreSpec extends CatsEffectSuite:
  private val originalRoot = PathUtil.dataRoot
  private var tmp: os.Path = null

  override def beforeEach(context: munit.BeforeEach): Unit =
    tmp = os.Path(java.nio.file.Files.createTempDirectory("nb-completion-debt"))
    PathUtil.setDataRoot(tmp)

  override def afterEach(context: munit.AfterEach): Unit =
    PathUtil.setDataRoot(originalRoot)
    if tmp != null && os.exists(tmp) then os.remove.all(tmp)

  private val result = List(Message(MessageRole.Assistant, Left("final synthesized result")))

  test("stable owner and target debt survives re-read; ack must match target and durable result") {
    for
      recorded <- CompletionDebtStore.record("delegate-session-1", "parent-session", result)
      recovered <- CompletionDebtStore.pending("delegate-session-1")
      replayed <- CompletionDebtStore.pending("delegate-session-1")
      wrongTarget <- CompletionDebtStore.acknowledge("delegate-session-1", "other-parent", result)
      wrongResult <- CompletionDebtStore.acknowledge("delegate-session-1", "parent-session", Nil)
      stillPending <- CompletionDebtStore.pending("delegate-session-1")
      accepted <- CompletionDebtStore.acknowledge("delegate-session-1", "parent-session", result)
      cleared <- CompletionDebtStore.pending("delegate-session-1")
    yield
      assert(recorded)
      assertEquals(recovered.toOption.flatten.map(_.recipientSessionId), Some("parent-session"))
      assertEquals(replayed, recovered, "reopening/replaying does not consume the debt")
      assert(!wrongTarget && !wrongResult, "wrong receiver or result cannot acknowledge debt")
      assert(stillPending.toOption.flatten.isDefined)
      assert(accepted)
      assertEquals(cleared.toOption.flatten.map(_.phase), Some("settled"), "ack persists a terminal settlement tombstone")
  }

  test("missing target remains a durable obligation; explicit cancellation fences later completion") {
    for
      recorded <- CompletionDebtStore.record("delegate-session-2", "", result)
      unavailable <- CompletionDebtStore.pending("delegate-session-2")
      _ <- CompletionDebtStore.cancel("delegate-session-2")
      late <- CompletionDebtStore.record("delegate-session-2", "late-parent", result)
      cancelled <- CompletionDebtStore.pending("delegate-session-2")
    yield
      assert(recorded)
      assert(unavailable.toOption.flatten.exists(_.recipientSessionId.isEmpty))
      assert(!late, "cancel tombstone rejects a late completion write")
      assert(cancelled.toOption.flatten.exists(_.phase == "cancelled"))
  }

  test("durable terminal receiver accepts exact duplicate once and refuses conflicting outcome") {
    val event = nebflow.actor.AgentCommand.ExternalEvent("delegate", "completed", "done", io.circe.JsonObject.empty, Some("child"))
    val conflict = event.copy(eventType = "cancelled", payload = "cancelled")
    for
      first <- CompletionDebtStore.acceptOnce("child", "parent", event)
      duplicate <- CompletionDebtStore.acceptOnce("child", "parent", event)
      mismatch <- CompletionDebtStore.acceptOnce("child", "parent", conflict)
    yield
      assertEquals(first, CompletionDebtStore.Acceptance.New)
      assertEquals(duplicate, CompletionDebtStore.Acceptance.Duplicate)
      assertEquals(mismatch, CompletionDebtStore.Acceptance.Conflict, "a conflicting status/result cannot be accepted as the prior terminal")
  }

  test("generation identity: same-generation replay dedups, cross-generation rounds re-report (#2178)") {
    def event(gen: String, payload: String) =
      nebflow.actor.AgentCommand.ExternalEvent(
        "delegate",
        "completed",
        payload,
        io.circe.JsonObject("completionGeneration" -> io.circe.Json.fromString(gen)),
        Some("child")
      )
    for
      first <- CompletionDebtStore.acceptOnce("child", "parent", event("gen-1", "round one"))
      replay <- CompletionDebtStore.acceptOnce("child", "parent", event("gen-1", "round one"))
      drift <- CompletionDebtStore.acceptOnce("child", "parent", event("gen-1", "round one (edited)"))
      nextRound <- CompletionDebtStore.acceptOnce("child", "parent", event("gen-2", "round two"))
      nextReplay <- CompletionDebtStore.acceptOnce("child", "parent", event("gen-2", "round two"))
    yield
      assertEquals(first, CompletionDebtStore.Acceptance.New)
      assertEquals(replay, CompletionDebtStore.Acceptance.Duplicate, "same-generation duplicate is accepted exactly once")
      assertEquals(drift, CompletionDebtStore.Acceptance.Conflict, "payload drift inside one generation is a conflict, not a new report")
      assertEquals(nextRound, CompletionDebtStore.Acceptance.New, "a Mail-continuation round is NOT swallowed by the prior generation's row")
      assertEquals(nextReplay, CompletionDebtStore.Acceptance.Duplicate)
  }

  test("record: in-flight same-content re-declaration keeps the generation; settled debt re-opens as a NEW generation") {
    val msgs = List(Message(MessageRole.Assistant, Left("round result")))
    val msgs2 = List(Message(MessageRole.Assistant, Left("continuation result")))
    def ev(gen: String, payload: String) =
      nebflow.actor.AgentCommand.ExternalEvent(
        "delegate",
        "completed",
        payload,
        io.circe.JsonObject("completionGeneration" -> io.circe.Json.fromString(gen)),
        Some("delegate-session-gen")
      )
    for
      _ <- CompletionDebtStore.record("delegate-session-gen", "parent-session", msgs)
      gen1 <- CompletionDebtStore.pending("delegate-session-gen").map(_.toOption.flatten.map(_.generation).getOrElse(""))
      _ <- CompletionDebtStore.record("delegate-session-gen", "parent-session", msgs) // 同完成重声明
      gen1b <- CompletionDebtStore.pending("delegate-session-gen").map(_.toOption.flatten.map(_.generation).getOrElse(""))
      _ <- CompletionDebtStore.acknowledge("delegate-session-gen", "parent-session", msgs) // 第一轮交付确认
      again <- CompletionDebtStore.record("delegate-session-gen", "parent-session", msgs) // Mail 续轮（内容相同）
      gen2 <- CompletionDebtStore.pending("delegate-session-gen").map(_.toOption.flatten.map(_.generation).getOrElse(""))
      _ <- CompletionDebtStore.record("delegate-session-gen", "parent-session", msgs2) // 另一轮（内容不同）
      gen3 <- CompletionDebtStore.pending("delegate-session-gen").map(_.toOption.flatten.map(_.generation).getOrElse(""))
      phase <- CompletionDebtStore.pending("delegate-session-gen").map(_.toOption.flatten.map(_.phase).getOrElse(""))
      acceptance <- CompletionDebtStore.acceptOnce("delegate-session-gen", "parent-session", ev(gen2, "x"))
    yield
      assert(gen1.nonEmpty, "record mints a durable generation")
      assertEquals(gen1b, gen1, "same-content re-declaration keeps the identity stable")
      assert(again, "a settled debt must NOT block the next round (one-report-per-lifetime is abolished)")
      assert(gen2.nonEmpty && gen2 != gen1, "the next round mints a NEW generation")
      assert(gen3.nonEmpty && gen3 != gen2)
      assertEquals(phase, "deliverable")
      assertEquals(acceptance, CompletionDebtStore.Acceptance.New, "the new round is accepted fresh")
  }

  test("malformed debt is surfaced and original bytes are preserved; legacy owner/messages/phase decodes") {
    val sid = "delegate-session-corrupt"
    val debtFile = tmp / "sessions" / sid / "completion-debt.json"
    val corruptFile = tmp / "sessions" / sid / "completion-debt.json.corrupt"
    val raw = "{truncated".getBytes(java.nio.charset.StandardCharsets.UTF_8)
    for
      _ <- IO(os.makeDir.all(debtFile / os.up))
      _ <- IO(os.write.over(debtFile, raw))
      malformed <- CompletionDebtStore.pending(sid)
      preserved <- IO(os.read.bytes(corruptFile))
      legacySid = "delegate-session-legacy"
      legacyFile = tmp / "sessions" / legacySid / "completion-debt.json"
      legacyJson = io.circe.Json.obj(
        "ownerSessionId" -> legacySid.asJson,
        "messages" -> result.asJson
      )
      _ <- IO(os.write.over(legacyFile, legacyJson.noSpaces, createFolders = true))
      legacy <- CompletionDebtStore.pending(legacySid)
    yield
      assert(malformed.isLeft, "corruption cannot collapse into missing debt")
      assertEquals(preserved.toList, raw.toList)
      assertEquals(legacy.toOption.flatten.map(_.phase), Some("deliverable"))
  }
end CompletionDebtStoreSpec
