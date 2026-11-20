package nebflow.pilogic

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import munit.CatsEffectSuite

/** 循环契约件：续跑校验（A-8）+ 策略三元全 Option 谓词（L-10）+ 序域单写者 + 崩溃合成。 */
class LoopContractSpec extends CatsEffectSuite:

  private val userMsg = LoopMessage(LoopMessageRole.User, Json.Null)
  private val asstMsg = LoopMessage(LoopMessageRole.Assistant, Json.Null)

  test("continueFrom 前置校验（A-8）：空上下文拒 / 末条 assistant 拒 / 其余过"):
    assertEquals(AgentLoop.validateContinuation(LoopContext(Vector.empty)).isLeft, true)
    assertEquals(AgentLoop.validateContinuation(LoopContext(Vector(userMsg, asstMsg))).isLeft, true)
    assertEquals(AgentLoop.validateContinuation(LoopContext(Vector(asstMsg, userMsg))).isRight, true)

  test("策略三元全 Option（L-10）：None 永不超帽；maxTurns=Some(3) ⇒ 第 4 轮开跑前恰命中"):
    assertEquals(LoopPolicy.none.exceedsTurns(TurnNumber(Long.MaxValue)), false)
    val capped = LoopPolicy(maxTurns = Some(TurnNumber(3)))
    assertEquals(capped.exceedsTurns(TurnNumber(3)), false)
    assertEquals(capped.exceedsTurns(TurnNumber(4)), true)
    assertEquals(LoopPolicy(tokenBudget = Some(TokenBudget(100))).exceedsTokens(101), true)

  test("序域单写者分配：从 1 起单调递增（L-8 每 run 重置、run 内单调的分配侧）"):
    Sequence.allocator[IO].flatMap { a =>
      (a.next, a.next, a.next).mapN { (x, y, z) =>
        assertEquals(Vector(x, y, z), Vector(1L, 2L, 3L))
      }
    }

  test("崩溃合成助手：有活跃轮 ⇒ TurnEnded(空)+RunEnded 终态对；无活跃轮 ⇒ 仅 RunEnded"):
    val evs = EventSink.synthesizeTruncatedEvents(Some(TurnNumber(2)), aborted = false)
    assertEquals(evs.size, 2)
    assert(evs.last.isInstanceOf[LoopEvent.RunEnded])
    assertEquals(EventSink.synthesizeTruncatedEvents(None, aborted = true).size, 1)
end LoopContractSpec
