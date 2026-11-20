package nebflow.agent

import munit.FunSuite

class ChildReportSpec extends FunSuite:
  test("intermediate reports produce one nonterminal event") {
    val event = ChildReport.event("detail <report>progress</report>", true, "child", Some("session"), 7)
    assertEquals(event.map(_.payload), Some("progress"))
    assertEquals(event.map(_.source), Some("child-report"))
    assertEquals(event.map(_.eventType), Some("progress"))
    assertEquals(event.flatMap(_.correlationId), Some("child-report:session:7"))
    assertEquals(ChildReport.event("<report>progress</report>", true, "child", Some("session"), 7).flatMap(_.correlationId), event.flatMap(_.correlationId))
    assertNotEquals(ChildReport.event("<report>progress</report>", true, "child", Some("session"), 8).flatMap(_.correlationId), event.flatMap(_.correlationId))
    assert(!TurnBoundaryDrains.isSubagentResult(event.get))
  }
  test("final report is left exclusively to the existing final result") {
    assertEquals(ChildReport.event("<report>final</report>", false, "child", Some("session"), 7), None)
  }
  test("intermediate text without a report produces no event") {
    assertEquals(ChildReport.event("process only", true, "child", Some("session"), 7), None)
  }
