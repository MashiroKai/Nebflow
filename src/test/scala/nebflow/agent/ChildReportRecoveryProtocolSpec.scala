package nebflow.agent

import munit.FunSuite
import java.nio.file.Files

class ChildReportRecoveryProtocolSpec extends FunSuite:
  test("failed open request is discoverable and closure permits a new request") {
    val root = Files.createTempDirectory("child-failure-")
    val journal = ChildReportJournal.open(root, "child")
    val failed = journal.beginRequest()
    assertEquals(ChildReportJournal.open(root, "child").openRequests(), List(failed))
    journal.openRequests().foreach(journal.closeNoReport)
    assertNotEquals(journal.beginRequest(), failed)
  }

  test("no-response request can be durably closed before a new request") {
    val root = Files.createTempDirectory("child-no-response-")
    val first = ChildReportJournal.open(root, "child")
    val slot = first.beginRequest()
    ChildReportJournal.open(root, "child").closeNoReport(slot)
    assertNotEquals(ChildReportJournal.open(root, "child").beginRequest(), slot)
  }

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
