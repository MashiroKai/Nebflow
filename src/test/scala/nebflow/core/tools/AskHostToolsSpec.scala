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
