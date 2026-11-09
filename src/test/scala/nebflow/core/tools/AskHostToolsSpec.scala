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
      rows2 <- IO(os.read.lines(tmp / "askhost" / "ledger.jsonl"))
      _ = assert(accepted)
      _ = assertEquals(result, "reject: unsafe")
      _ = assert(!repeated)
      _ = assert(rows2.exists(_.contains("delivered")))
    yield ()
    check.guarantee(IO {
      PathUtil.setDataRoot(previous)
      AskHostLedger.clearForTests()
      os.remove.all(tmp)
    })
  }

  test("AskHost tool schema is correlation-free and requires only a question") {
    val required = AskHostTool.inputSchema("required").flatMap(_.asArray).getOrElse(Vector.empty)
    assertEquals(required.flatMap(_.asString), Vector("question"))
    assert(!AskHostTool.inputSchema("properties").flatMap(_.hcursor.downField("correlationId").success).isDefined)
  }
end AskHostToolsSpec
