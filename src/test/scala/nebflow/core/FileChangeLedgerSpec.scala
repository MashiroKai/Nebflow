package nebflow.core

import cats.effect.IO
import munit.CatsEffectSuite

/**
 * FileChangeLedger unit regression (file-diff feature capture face).
 *
 * Covers: tool-result parsing (created / updated / oversized degradation /
 * reminder-tail contamination guard), the per-key cap with oldest-first
 * eviction, multi-key attribution (node drain must not consume the task
 * bucket), and the per-path coalescing for delivery.
 */
class FileChangeLedgerSpec extends CatsEffectSuite:

  private val CreatedResult =
    """OK:CREATED Alpha.scala, 3 lines
      |@@ -0,0 +1,3 @@
      |+val a = 1
      |+val b = 2
      |+val c = 3""".stripMargin

  private val UpdatedResult =
    """OK:UPDATED Beta.scala, 2 added, 1 removed
      |@@ -1,4 +1,5 @@
      | context
      |-old line
      |+new line 1
      |+new line 2
      | tail""".stripMargin

  test("parseToolResult: created result parses as added with full-additions diff") {
    val e = FileChangeLedger.parseToolResult("Write", "/tmp/Alpha.scala", CreatedResult, 1L)
    assert(e.isDefined)
    val ev = e.get
    assertEquals(ev.changeType, FileChangeType.Added)
    assertEquals(ev.addedLines, 3)
    assertEquals(ev.removedLines, 0)
    assert(ev.diffText.exists(_.startsWith("@@ -0,0 +1,3 @@")))
    assert(!ev.diffText.exists(_.contains("OK:CREATED"))) // header never leaks into the body
    assertEquals(ev.sourceTool, "Write")
  }

  test("parseToolResult: updated result parses counts from the header stats") {
    val ev = FileChangeLedger.parseToolResult("Edit", "/tmp/Beta.scala", UpdatedResult, 2L).get
    assertEquals(ev.changeType, FileChangeType.Modified)
    assertEquals(ev.addedLines, 2)
    assertEquals(ev.removedLines, 1)
    assert(ev.diffText.exists(_.contains("-old line")))
    assert(ev.diffText.exists(_.contains("+new line 2")))
  }

  test("parseToolResult: appended reminder tail is cut out of body and counts") {
    val contaminated = UpdatedResult + "\n<system-reminder>Overbudget notice</system-reminder>"
    val ev = FileChangeLedger.parseToolResult("Edit", "/tmp/Beta.scala", contaminated, 3L).get
    assert(ev.diffText.isDefined)
    assert(!ev.diffText.exists(_.contains("system-reminder")))
    assertEquals(ev.addedLines, 2)
    assertEquals(ev.removedLines, 1)
  }

  test("parseToolResult: oversized body degrades to counts-only with a reason") {
    val bigBody = "@@ -1,1 +1,2 @@\n" + List.fill(6000)("+" + "x" * 20).mkString("\n") // > 100k chars
    val result = s"OK:UPDATED Huge.txt, 6000 added, 0 removed\n$bigBody"
    val ev = FileChangeLedger.parseToolResult("Write", "/tmp/Huge.txt", result, 4L).get
    assertEquals(ev.addedLines, 6000)
    assertEquals(ev.diffText, None)
    assert(ev.degradedReason.exists(_.startsWith("diff-too-large")))
  }

  test("parseToolResult: non-OK strings return None") {
    assertEquals(FileChangeLedger.parseToolResult("Edit", "/tmp/x", "some error text", 5L), None)
  }

  test("record: per-key cap evicts the oldest events") {
    FileChangeLedger.make().flatMap { ledger =>
      List.tabulate(FileChangeLedger.MaxEventsPerKey + 5) { i =>
        ledger.record(List("k"), FileChangeEvent(s"/f$i", FileChangeType.Modified, 1, 0, None, None, "Edit", i.toLong))
      }.sequence *> ledger.snapshot("k")
    }.map { events =>
      assertEquals(events.length, FileChangeLedger.MaxEventsPerKey)
      assertEquals(events.head.timestamp, 5L) // oldest 5 evicted
    }
  }

  test("multi-key attribution: node drain keeps the task and session buckets") {
    FileChangeLedger.make().flatMap { ledger =>
      for
        _ <- ledger.record(List("sid-1", "node-1", "task-7"), FileChangeEvent("/a", FileChangeType.Modified, 1, 0, None, None, "Edit", 1L))
        _ <- ledger.record(List("sid-2", "node-2", "task-7"), FileChangeEvent("/b", FileChangeType.Modified, 1, 0, None, None, "Write", 2L))
        node1 <- ledger.drain("node-1")
        node1Again <- ledger.drain("node-1")
        taskBucket <- ledger.snapshot("task-7")
        sid1 <- ledger.snapshot("sid-1")
      yield
        assertEquals(node1.map(_.path), List("/a"))
        assertEquals(node1Again, Nil) // drain is consuming
        assertEquals(taskBucket.map(_.path), List("/a", "/b")) // task bucket untouched by node drain
        assertEquals(sid1.map(_.path), List("/a")) // session bucket untouched too
    }
  }

  test("recordToolResult: parse failure records nothing") {
    FileChangeLedger.make().flatMap { l =>
      l.recordToolResult(Some("s"), None, None, "Edit", "/x", "not-an-ok-result") *> l.snapshot("s")
    }.map(events => assertEquals(events, Nil))
  }

  test("coalesce: same-path events merge (counts sum, changeType escalates, diffs join in write order)") {
    val e1 = FileChangeEvent("/p", FileChangeType.Added, 3, 0, Some("@@ -0,0 +1,3 @@\n+a\n+b\n+c"), None, "Write", 10L)
    val e2 = FileChangeEvent("/p", FileChangeType.Modified, 1, 1, Some("@@ -2,2 +2,2 @@\n-b\n+B"), None, "Edit", 20L)
    val e3 = FileChangeEvent("/q", FileChangeType.Modified, 5, 0, None, Some("diff-too-large"), "Edit", 30L)
    val merged = FileChangeLedger.coalesce(List(e1, e2, e3))
    assertEquals(merged.map(_.path), List("/p", "/q")) // first-appearance order preserved
    val p = merged.head
    assertEquals(p.changeType, FileChangeType.Added) // creation escalates
    assertEquals(p.addedLines, 4)
    assertEquals(p.removedLines, 1)
    val d = p.diffText.get
    assert(d.indexOf("@@ -0,0") < d.indexOf("@@ -2,2")) // write order kept
    assertEquals(p.timestamp, 20L)
    assertEquals(merged(1).degradedReason, Some("diff-too-large"))
  }

end FileChangeLedgerSpec
