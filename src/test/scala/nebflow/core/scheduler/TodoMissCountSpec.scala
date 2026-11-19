package nebflow.core.scheduler

import io.circe.Json
import io.circe.parser.decode
import munit.CatsEffectSuite

/**
 * 认知二期产品批③打卡缺卡调整流：
 *  - ScheduledTask.missCount 存量兼容（无键 → 0，withDefaults 零迁移）
 *  - digestPayloadWithStreak：达阈值条目附调整建议，未达不附（digestPayload
 *    旧契约逐字不变——既有 spec 钉死的内容零漂移）
 */
class TodoMissCountSpec extends CatsEffectSuite:

  private def todo(id: String, missCount: Int): ScheduledTask =
    ScheduledTask(
      id = id,
      sessionId = "s1",
      content = "今天：深蹲 5×5",
      triggerAt = 0L,
      createdAt = 1730000000000L,
      kind = Some("todo"),
      missCount = missCount
    )

  test("存量 JSON 无 missCount 键 → 解码 0（零迁移）") {
    val legacy = Json.obj(
      "id" -> Json.fromString("x"),
      "sessionId" -> Json.fromString("s1"),
      "content" -> Json.fromString("c"),
      "triggerAt" -> Json.fromLong(0L),
      "createdAt" -> Json.fromLong(0L),
      "kind" -> Json.fromString("todo")
    )
    decode[ScheduledTask](legacy.noSpaces) match
      case Right(t) => assertEquals(t.missCount, 0)
      case Left(e) => fail(e.getMessage)
  }

  test("missCount 随任务持久化 roundtrip") {
    val t = todo("a", 2)
    decode[ScheduledTask](
      implicitly[io.circe.Encoder[ScheduledTask]].apply(t).noSpaces
    ) match
      case Right(d) => assertEquals(d.missCount, 2)
      case Left(e) => fail(e.getMessage)
  }

  test("digestPayload 旧契约零漂移（无 missStreak 调用面 = 无建议文案）") {
    val payload = ScheduledTaskService.digestPayload(List(todo("a", 5)), 1730000000000L)
    assert(payload.contains("[id: a]"))
    assert(!payload.contains("未销账——建议"), "旧文案不附调整建议")
  }

  test("digestPayloadWithStreak：达阈值附建议，未达不附") {
    val todos = List(todo("a", 2), todo("b", 0))
    val payload = ScheduledTaskService.digestPayloadWithStreak(todos, Set("a"), 1730000000000L)
    assert(payload.contains("【已连续 3 次浮出未销账"), "达阈值条目带建议")
    assert(payload.contains("不擅自改计划"), "不擅自改计划在")
    // b 行不附建议：建议只挂在 a 行尾
    val bLine = payload.linesIterator.find(_.contains("[id: b]")).getOrElse(fail("b line missing"))
    assert(!bLine.contains("未销账——建议"), "未达阈值条目不附建议")
  }

  test("MissThreshold = 3（surfaceTodos 增量判定与文案数字同源）") {
    assertEquals(ScheduledTaskService.MissThreshold, 3)
  }

end TodoMissCountSpec
