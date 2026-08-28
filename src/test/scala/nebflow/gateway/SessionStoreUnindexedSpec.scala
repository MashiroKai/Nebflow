package nebflow.gateway

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import munit.CatsEffectSuite
import nebflow.shared.{SessionMeta, UiMessage}

import java.nio.file.{Files, Path}
import java.nio.file.attribute.FileTime
import scala.jdk.CollectionConverters.*

/**
 * Tests for the search-scope session list (listSessionsIncludeUnindexed):
 *   - union of indexed sessions + on-disk-only .ui.json files (delegate /
 *     subtask / dag sub-agent sessions)
 *   - dedup: an indexed session's own .ui.json must not be double-listed
 *   - regression: listSessions (the sidebar path, i.e. GET /api/sessions
 *     without the includeUnindexed param) must never surface unindexed files
 */
class SessionStoreUnindexedSpec extends CatsEffectSuite:

  private def withStore(test: (SessionStore, os.Path) => IO[Unit]): Unit =
    val tmp = Files.createTempDirectory("nebflow-unindexed-test")
    val sessionsDir = os.Path(tmp.resolve("sessions"))
    val tasksDir = os.Path(tmp.resolve("tasks"))
    try
      val store = SessionStore(sessionsDir, tasksDir)
      store.load.unsafeRunSync()
      test(store, sessionsDir).unsafeRunSync()
    finally
      if Files.exists(tmp) then
        Files.walk(tmp).sorted(java.util.Comparator.reverseOrder()).iterator().asScala.foreach(Files.deleteIfExists)

  /** Write an on-disk-only .ui.json — the shape unindexed sub-agent sessions leave behind. */
  private def writeUnindexedUi(sessionsDir: os.Path, id: String, mtime: Long = 0L): Unit =
    val f = sessionsDir / s"$id.ui.json"
    os.write.over(f, "[]", createFolders = true)
    if mtime > 0 then Files.setLastModifiedTime(f.toNIO, FileTime.fromMillis(mtime))

  private def ids(metas: List[SessionMeta]): List[String] = metas.map(_.id)

  test("includeUnindexed unions indexed + on-disk-only sessions, sorted by updatedAt desc") {
    withStore { (store, sessionsDir) =>
      // mtimes far in the past (1970) so indexed sessions (~now) always sort first
      writeUnindexedUi(sessionsDir, "delegate-Explorer-26fb3915", mtime = 1000000L)
      writeUnindexedUi(sessionsDir, "subtask-335d582d", mtime = 3000000L)
      writeUnindexedUi(sessionsDir, "dag-git-merge-scanner-405090", mtime = 2000000L)
      for
        indexed  <- store.listSessions
        merged   <- store.listSessionsIncludeUnindexed
        mergedIds = ids(merged)
      yield
        // every indexed session survives the union
        assert(indexed.map(_.id).forall(mergedIds.contains), "indexed sessions must all be present")
        // the three unindexed ids are now visible
        assert(mergedIds.contains("delegate-Explorer-26fb3915"))
        assert(mergedIds.contains("subtask-335d582d"))
        assert(mergedIds.contains("dag-git-merge-scanner-405090"))
        // unindexed extras only — no phantom additions
        assertEquals(mergedIds.length, indexed.length + 3)
        // sorted by updatedAt descending (indexed ~now, then 3M/2M/1M)
        assertEquals(merged.map(_.updatedAt), merged.map(_.updatedAt).sorted.reverse)
        val unindexedPart = merged.drop(indexed.length)
        assertEquals(ids(unindexedPart), List("subtask-335d582d", "dag-git-merge-scanner-405090", "delegate-Explorer-26fb3915"))
    }
  }

  test("indexed session's own .ui.json is not double-listed (dedup by id)") {
    withStore { (store, _) =>
      for
        meta      <- store.createSession("Dedup Test")
        _         <- store.appendUiMessages(meta.id, List(UiMessage.User("hello")))
        _         <- store.flushPendingUiWrites
        merged    <- store.listSessionsIncludeUnindexed
        occurrences = merged.count(_.id == meta.id)
      yield assertEquals(occurrences, 1, "indexed session with .ui.json on disk must appear exactly once")
    }
  }

  test("listSessions (no-param / sidebar path) is identical with or without unindexed files") {
    withStore { (store, sessionsDir) =>
      for
        before <- store.listSessions
        _       = writeUnindexedUi(sessionsDir, "delegate-Coder-11111111")
        _       = writeUnindexedUi(sessionsDir, "whatever-001")
        after  <- store.listSessions
        merged <- store.listSessionsIncludeUnindexed
      yield
        // strongest regression form: full SessionMeta list unchanged
        assertEquals(after, before, "listSessions must not be affected by unindexed files")
        assert(!ids(after).contains("delegate-Coder-11111111"), "sidebar path must never see delegate sessions")
        assert(!ids(after).contains("whatever-001"))
        // and the merged view = no-param view + exactly the unindexed extras
        assertEquals(ids(merged).toSet, ids(before).toSet ++ Set("delegate-Coder-11111111", "whatever-001"))
    }
  }

  test("unindexed entries derive name/agentName/updatedAt from id and file mtime") {
    withStore { (store, sessionsDir) =>
      writeUnindexedUi(sessionsDir, "delegate-Explorer-26fb3915", mtime = 1000000L)
      writeUnindexedUi(sessionsDir, "delegate-qa-backend-aabbccdd", mtime = 1000000L)
      writeUnindexedUi(sessionsDir, "subtask-335d582d", mtime = 1000000L)
      writeUnindexedUi(sessionsDir, "dag-git-merge-scanner-405090", mtime = 1000000L)
      writeUnindexedUi(sessionsDir, "usable-001", mtime = 1000000L)
      for merged <- store.listSessionsIncludeUnindexed
      yield
        val byId = merged.map(m => m.id -> m).toMap
        val delegate = byId("delegate-Explorer-26fb3915")
        assertEquals(delegate.name, "Explorer (delegate)")
        assertEquals(delegate.agentName, Some("Explorer"))
        assertEquals(delegate.updatedAt, 1000000L, "updatedAt must be the file mtime")
        assertEquals(delegate.createdAt, 1000000L)
        // hyphenated agent names survive (greedy middle + anchored uuid suffix)
        assertEquals(byId("delegate-qa-backend-aabbccdd").agentName, Some("qa-backend"))
        val subtask = byId("subtask-335d582d")
        assertEquals(subtask.name, "Subtask 335d582d")
        assertEquals(subtask.agentName, Some(""))
        val dag = byId("dag-git-merge-scanner-405090")
        assertEquals(dag.agentName, Some("scanner"), "dag agent = last segment before the 6-digit suffix")
        assertEquals(dag.name, "git-merge-scanner (flow)")
        val unknown = byId("usable-001")
        assertEquals(unknown.name, "usable-001")
        assertEquals(unknown.agentName, Some(""), "unparseable id falls back to empty agentName")
    }
  }

  test("non-.ui.json files and index bookkeeping are ignored by the scan") {
    withStore { (store, sessionsDir) =>
      os.write.over(sessionsDir / "stray.json", "[]", createFolders = true)
      os.write.over(sessionsDir / "deadbeef.meta.json", """{"folderId":"f"}""", createFolders = true)
      os.makeDir.all(sessionsDir / "not-a-session.ui.json")
      for
        merged <- store.listSessionsIncludeUnindexed
      yield
        val mergedIds = ids(merged)
        assert(!mergedIds.contains("stray"))
        assert(!mergedIds.contains("deadbeef"))
        assert(!mergedIds.contains("not-a-session.ui.json"), "directories must be skipped")
    }
  }
end SessionStoreUnindexedSpec
