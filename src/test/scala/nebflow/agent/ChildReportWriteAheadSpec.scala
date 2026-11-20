package nebflow.agent

import munit.FunSuite
import java.nio.file.Files

class ChildReportWriteAheadSpec extends FunSuite:
  test("redispatch reuses persisted identity and parent folds the duplicate") {
    val path = Files.createTempDirectory("report-wal-")
    val a = ChildReportJournal.open(path, "child")
    val first = a.reserve("logical-1", "progress")
    val retry = a.reserve("logical-1", "progress")
    assertEquals(first, retry)
    assertEquals(List(first, retry).distinct.size, 1)
  }
  test("crash after persistence reopens with the same identity") {
    val path = Files.createTempDirectory("report-wal-")
    val first = ChildReportJournal.open(path, "child").reserve("logical-1", "progress")
    val afterRestart = ChildReportJournal.open(path, "child").reserve("logical-1", "progress")
    assertEquals(first, afterRestart)
    assertEquals(List(first, afterRestart).distinct.size, 1)
  }
  test("distinct logical messages allocate distinct monotonic identities") {
    val path = Files.createTempDirectory("report-wal-")
    val journal = ChildReportJournal.open(path, "child")
    assertNotEquals(journal.reserve("logical-1", "same"), journal.reserve("logical-2", "same"))
  }
