package nebflow.agent

import munit.FunSuite

class ChildReportSpec extends FunSuite:
  test("intermediate reports produce one nonterminal event") {
    val event = ChildReport.event("detail <report>progress</report>", true, "child")
    assertEquals(event.map(_.payload), Some("progress"))
    assertEquals(event.map(_.source), Some("child-report"))
    assertEquals(event.map(_.eventType), Some("progress"))
    assert(!TurnBoundaryDrains.isSubagentResult(event.get))
  }
  test("final report is left exclusively to the existing final result") {
    assertEquals(ChildReport.event("<report>final</report>", false, "child"), None)
  }
  test("intermediate text without a report produces no event") {
    assertEquals(ChildReport.event("process only", true, "child"), None)
  }
