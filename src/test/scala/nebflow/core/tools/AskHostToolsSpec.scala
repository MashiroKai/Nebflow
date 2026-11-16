package nebflow.core.tools

import cats.effect.IO
import io.circe.JsonObject
import munit.CatsEffectSuite
import nebflow.actor.AgentDef
import nebflow.shared.PathUtil

class AskHostToolsSpec extends CatsEffectSuite:
  test("AskHost ledger persists pending source/question and accepts one correlated decision") {
    val tmp = os.temp.dir()
    val previous = PathUtil.dataRoot
    PathUtil.setDataRoot(tmp)
    val check = for
      begun <- AskHostLedger.begin("worker-1", "root-1", "worker-agent", "choose a mode")
      (pending, reply) = begun
      rows1 <- IO(os.read.lines(tmp / "askhost" / "ledger.jsonl"))
      _ = assert(rows1.head.contains(pending.correlationId))
      _ = assert(rows1.head.contains("choose a mode"))
      _ = assert(rows1.head.contains("worker-1"))
      accepted <- AskHostLedger.answer(pending.correlationId, "reject: unsafe")
      result <- reply.get
      repeated <- AskHostLedger.answer(pending.correlationId, "different")
      recovered <- AskHostLedger.recover
      rows2 <- IO(os.read.lines(tmp / "askhost" / "ledger.jsonl"))
      _ = assert(accepted)
      _ = assertEquals(result, "reject: unsafe")
      _ = assert(!repeated)
      _ = assert(recovered.find(_.pending.correlationId == pending.correlationId).flatMap(_.answer).contains("reject: unsafe"))
      _ = assert(rows2.exists(_.contains("answered")))
    yield ()
    check.guarantee(IO {
      PathUtil.setDataRoot(previous)
      AskHostLedger.clearForTests()
      os.remove.all(tmp)
    })
  }

  test("AskHost journal replay restores unanswered requests and treats delivered as terminal") {
    val tmp = os.temp.dir()
    val previous = PathUtil.dataRoot
    PathUtil.setDataRoot(tmp)
    val check = for
      (pending, _) <- AskHostLedger.begin("worker-replay", "root-replay", "kernel", "question survives restart")
      first <- AskHostLedger.recover
      _ <- AskHostLedger.markUnavailable(pending.correlationId, "worker session unavailable")
      second <- AskHostLedger.recover
      _ = assertEquals(first.map(_.pending.correlationId), List(pending.correlationId))
      _ = assert(second.isEmpty)
    yield ()
    check.guarantee(IO {
      PathUtil.setDataRoot(previous)
      AskHostLedger.clearForTests()
      os.remove.all(tmp)
    })
  }

  test("crash recovery accepts a delayed answer by the original request id without a live waiter") {
    val tmp = os.temp.dir()
    val previous = PathUtil.dataRoot
    PathUtil.setDataRoot(tmp)
    (for
      (pending, _) <- AskHostLedger.begin("worker", "root", "agent", "first")
      _ <- IO(AskHostLedger.clearForTests())
      accepted <- AskHostLedger.answer(pending.correlationId, "decision")
      restored <- AskHostLedger.restoreWaiter(pending.correlationId)
      result <- restored.get.timeout(scala.concurrent.duration.DurationInt(3).seconds)
      _ = assert(accepted)
      _ = assertEquals(result, "decision")
    yield ()).guarantee(IO { PathUtil.setDataRoot(previous); AskHostLedger.clearForTests(); os.remove.all(tmp) })
  }

  test("duplicate decision is accepted only once and distinct pending requests remain independent") {
    val tmp = os.temp.dir()
    val previous = PathUtil.dataRoot
    PathUtil.setDataRoot(tmp)
    (for
      (a, _) <- AskHostLedger.begin("worker", "root", "agent", "one")
      (b, _) <- AskHostLedger.begin("worker", "root", "agent", "two")
      accepted <- AskHostLedger.answer(a.correlationId, "yes")
      duplicate <- AskHostLedger.answer(a.correlationId, "yes")
      rows <- AskHostLedger.recover
      _ = assert(accepted && !duplicate)
      _ = assertEquals(rows.size, 2)
      _ = assertNotEquals(a.correlationId, b.correlationId)
    yield ()).guarantee(IO { PathUtil.setDataRoot(previous); AskHostLedger.clearForTests(); os.remove.all(tmp) })
  }

  test("recovery repeatedly reattaches to the same request and preserves distinct calls") {
    val tmp = os.temp.dir()
    val previous = PathUtil.dataRoot
    PathUtil.setDataRoot(tmp)
    (for
      (a, _) <- AskHostLedger.beginForCall("worker", "root", "agent", "one", "call-a")
      (b, _) <- AskHostLedger.beginForCall("worker", "root", "agent", "two", "call-b")
      _ <- IO(AskHostLedger.clearForTests())
      (again, _) <- AskHostLedger.beginForCall("worker", "root", "agent", "one", "call-a")
      _ <- IO(AskHostLedger.clearForTests())
      (third, _) <- AskHostLedger.beginForCall("worker", "root", "agent", "one", "call-a")
      rows <- AskHostLedger.recover
      _ = assertEquals(again.correlationId, a.correlationId)
      _ = assertEquals(third.correlationId, a.correlationId)
      _ = assertNotEquals(a.correlationId, b.correlationId)
      _ = assertEquals(rows.size, 2)
    yield ()).guarantee(IO { PathUtil.setDataRoot(previous); AskHostLedger.clearForTests(); os.remove.all(tmp) })
  }

  test("request replay is suppressed after the mailbox has taken and consumed it") {
    val tmp = os.temp.dir()
    val previous = PathUtil.dataRoot
    PathUtil.setDataRoot(tmp)
    val event = nebflow.actor.AgentCommand.ExternalEvent("askhost", "request", "question", JsonObject.empty, Some("request-a"))
    import nebflow.core.mail.SessionMailbox
    (for
      first <- SessionMailbox.mailOnce("root", event, "askhost-request", "request-a", deduplicateReceipts = true)
      entries <- SessionMailbox.scanPending("root")
      _ <- SessionMailbox.take("root", entries)
      during <- SessionMailbox.mailOnce("root", event, "askhost-request", "request-a", deduplicateReceipts = true)
      _ <- SessionMailbox.unlinkTaken("root", List("request-a"))
      after <- SessionMailbox.mailOnce("root", event, "askhost-request", "request-a", deduplicateReceipts = true)
      _ = assert(first.isInstanceOf[SessionMailbox.MailResult.Enqueued])
      _ = assertEquals(during, SessionMailbox.MailResult.AlreadyPresent)
      _ = assertEquals(after, SessionMailbox.MailResult.AlreadyPresent)
    yield ()).guarantee(IO { PathUtil.setDataRoot(previous); AskHostLedger.clearForTests(); os.remove.all(tmp) })
  }

  test("recovered handoff waits for the original request and returns its decision as the original tool result") {
    val tmp = os.temp.dir()
    val previous = PathUtil.dataRoot
    PathUtil.setDataRoot(tmp)
    import nebflow.shared.{Message, MessageRole, ContentBlock}
    import nebflow.core.ResumeHandoff
    val transcript = List(Message(MessageRole.Assistant, Right(List(ContentBlock.ToolUse("call-a", "AskHost", JsonObject.empty)))))
    (for
      (request, _) <- AskHostLedger.beginForCall("worker", "root", "agent", "one", "call-a")
      _ <- IO(AskHostLedger.clearForTests())
      safe <- ResumeHandoff.recoverableAskHostOnly("worker", transcript)
      p = ResumeHandoff.plan(transcript, ResumeHandoff.Cause.EngineRestart)
      fiber <- ResumeHandoff.resolveAskHost("worker", p).start
      _ <- AskHostLedger.answer(request.correlationId, "approved")
      resolved <- fiber.joinWithNever
      results = resolved.injectBlocks.toList.flatten.collect { case x: ContentBlock.ToolResult => x }
      _ = assert(safe)
      _ = assertEquals(results.map(r => (r.toolUseId, r.content, r.isError)), List(("call-a", "approved", Some(false))))
    yield ()).guarantee(IO { PathUtil.setDataRoot(previous); AskHostLedger.clearForTests(); os.remove.all(tmp) })
  }

  test("AskUserQuestion remains non-blocking and keeps its existing question schema") {
    val required = AskUserQuestionTool.inputSchema("required").flatMap(_.asArray).getOrElse(Vector.empty)
    assertEquals(required.flatMap(_.asString), Vector("questions"))
    assert(!AskUserQuestionTool.inputSchema("properties").flatMap(_.hcursor.downField("mode").success).isDefined)
  }

  test("AskHost tool schema is correlation-free and requires only a question") {
    val required = AskHostTool.inputSchema("required").flatMap(_.asArray).getOrElse(Vector.empty)
    assertEquals(required.flatMap(_.asString), Vector("question"))
    assert(!AskHostTool.inputSchema("properties").flatMap(_.hcursor.downField("correlationId").success).isDefined)
  }
end AskHostToolsSpec
