package nebflow.agent

import munit.FunSuite

/**
 * Behaviour-level spec for [[ChildReport]]'s single event construction point
 * (report 块可见性批，作者令 2026-11-20).
 *
 * 判据 = 用户面看得到的路由事实，不是内部形状：
 *   ① 中间进度产出**一件非终态**事件，正文只含块内拼接（块外叙述不投）；
 *   ② 终态消息（`continues=false`）**结构上不产出**进度事件——最终正文只走既有
 *      result channel，一条消息绝不两路都投；
 *   ③ 无块 / 未闭合 / 空块 ⇒ 无事件（进度通道只承载用户可见正文）；
 *   ④ 身份来自持久账本，不由轮次决定（见 [[ChildReportWriteAheadSpec]]）。
 */
class ChildReportSpec extends FunSuite:

  test("intermediate report produces exactly one nonterminal event") {
    val body = ChildReport.body("detail <report>progress</report>", continues = true)
    assertEquals(body, Some("progress"))
    val event = ChildReport.event("child-report:session:1", body.get, "child")
    assertEquals(event.payload, "progress")
    assertEquals(event.source, "child-report")
    assertEquals(event.eventType, "progress")
    assertEquals(event.correlationId, Some("child-report:session:1"))
    assertEquals(event.metadata("agentName").flatMap(_.asString), Some("child"))
    assert(!TurnBoundaryDrains.isSubagentResult(event))
  }

  test("multiple blocks join in order and outside text never ships") {
    val body = ChildReport.body("前置叙述\n<report>A</report>\n中间叙述\n<report> B </report>\n尾述", continues = true)
    assertEquals(body, Some("A\n\nB"))
  }

  test("final message is structurally excluded from the progress channel") {
    assertEquals(ChildReport.body("<report>final</report>", continues = false), None)
  }

  test("text without a complete report block produces no progress event") {
    assertEquals(ChildReport.body("process only", continues = true), None)
    assertEquals(ChildReport.body("streaming <report>half closed", continues = true), None)
    assertEquals(ChildReport.body("<report>   </report>", continues = true), None)
  }
end ChildReportSpec
