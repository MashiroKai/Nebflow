package nebflow.agent

import munit.FunSuite
import java.nio.file.Files

class ChildReportRecoveryProtocolSpec extends FunSuite:
  test("interrupted delivery flushes persisted old report before new request") {
    val root = Files.createTempDirectory("child-recovery-")
    val first = ChildReportJournal.open(root, "child")
    val slot = first.beginRequest()
    val old = first.prepareReport(slot, "old progress")
    val recovered = ChildReportJournal.open(root, "child")
    val replay = recovered.pendingRecovery()
    assertEquals(replay.map(_._1), List(old))
    recovered.markFlushed(slot)
    assertEquals(ChildReportJournal.open(root, "child").pendingRecovery(), Nil)
    val next = recovered.beginRequest()
    assertNotEquals(next, slot)
  }
