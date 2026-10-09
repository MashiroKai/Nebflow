package nebflow.core.tools

import cats.effect.IO
import cats.syntax.all.*
import io.circe.JsonObject
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.core.{FileChangeLedger, FileChangeType}
import nebflow.core.project.{CompletionGate, FileChangeReport}

import java.nio.charset.StandardCharsets

/**
 * File-diff feature — capture leg driven through the REAL tool layer (AC2):
 * Write / Edit / MultiEdit executed against a temp NON-git directory with
 * `fileChangeLedger` wired into the ToolContext must land one ledger event per
 * successful write, drain per node id, and build per-file unified diffs that
 * keep hunk headers and context lines. Also verifies attribution isolation:
 * the node drain must not consume the sibling session/task buckets.
 */
class FileDiffToolLayerSpec extends CatsEffectSuite:

  // Outside the sbt project tree (see FileChangeReportSpec): the enclosing
  // repo's git face must never leak into these non-git fixtures.
  private val root: os.Path = os.temp.dir(prefix = "nb-file-diff-tool") / "tool-leg"

  override def beforeAll(): Unit = os.remove.all(root)

  private def writeInput(path: os.Path, content: String): JsonObject =
    JsonObject("file_path" -> path.toString.asJson, "content" -> content.asJson)

  private def editInput(path: os.Path, oldS: String, newS: String): JsonObject =
    JsonObject("file_path" -> path.toString.asJson, "old_string" -> oldS.asJson, "new_string" -> newS.asJson)

  test("non-git dir: Write/Edit/MultiEdit through ToolContext produce drainable per-file unified diffs") {
    os.makeDir.all(root)
    val aTxt = root / "a.txt"
    val bTxt = root / "sub" / "b.txt"

    val leg = for
      ledger <- FileChangeLedger.make()
      ctx = ToolContext(
        projectRoot = root.toString,
        sessionId = Some("sess-toolleg"),
        flowNodeId = Some("node-toolleg"),
        taskId = Some("task-toolleg"),
        fileChangeLedger = Some(ledger)
      )
      // 1. create a.txt (3 lines)
      _ <- WriteTool.call(writeInput(aTxt, "l1\nl2\nl3\n"), ctx).map {
        case Right(out) => assert(out.startsWith(DiffUtil.OkCreatedPrefix), out)
        case Left(e) => fail(s"Write a.txt failed: ${e.message}")
      }
      // 2. edit the middle line — the hunk must carry context lines
      _ <- EditTool.call(editInput(aTxt, "l2", "L2"), ctx).map {
        case Right(out) => assert(out.startsWith(DiffUtil.OkUpdatedPrefix), out)
        case Left(e) => fail(s"Edit a.txt failed: ${e.message}")
      }
      // 3. create b.txt in a subdirectory, then MultiEdit it
      _ <- WriteTool.call(writeInput(bTxt, "alpha\nbeta\n"), ctx).map {
        case Right(_) => ()
        case Left(e) => fail(s"Write b.txt failed: ${e.message}")
      }
      _ <- MultiEditTool
        .call(
          JsonObject(
            "file_path" -> bTxt.toString.asJson,
            "edits" -> List(
              JsonObject("old_string" -> "alpha".asJson, "new_string" -> "ALPHA".asJson).asJson,
              JsonObject("old_string" -> "beta".asJson, "new_string" -> "BETA".asJson).asJson
            ).asJson
          ),
          ctx
        )
        .map {
          case Right(_) => ()
          case Left(e) => fail(s"MultiEdit b.txt failed: ${e.message}")
        }
      events <- ledger.drain("node-toolleg")
    yield (ledger, events)

    leg.flatMap { case (ledger, events) =>
      val byPath = events.groupBy(_.path)
      assertEquals(events.map(_.path).distinct.sorted, List(aTxt.toString, bTxt.toString).sorted)
      assertEquals(byPath(aTxt.toString).length, 2) // Write + Edit, both captured
      assertEquals(byPath(aTxt.toString).head.changeType, FileChangeType.Added)
      assert(byPath(aTxt.toString).last.diffText.exists(_.contains("+L2")), "edit diff must record the replacement")

      // Attribution isolation: session and task buckets keep their copies.
      ledger.snapshot("sess-toolleg").map(s => assert(s.nonEmpty, "session bucket must survive the node drain")) *>
        ledger.snapshot("task-toolleg").map(t => assertEquals(t.length, 4)) *>
        // Report build in a NON-git dir: git face fails open (no repo), so every
        // entry comes from the tool ledger with root-relative paths.
        FileChangeReport.build(root.toString, None, "node-toolleg", events, CompletionGate.defaultRunner).map {
          report =>
            val paths = report.files.map(_.path)
            assertEquals(paths, List("a.txt", "sub/b.txt")) // sorted, root-relative
            report.files.foreach { f =>
              assertEquals(f.source, FileChangeReport.SourceToolLedger)
              assert(f.diffText.exists(_.contains("@@ -")), s"${f.path} must carry a hunk header")
            }
            val a = report.files.head
            assertEquals(a.changeType, FileChangeType.Added) // creation escalates
            // 4 (created; DiffUtil.splitLines keeps the trailing newline's empty
            // line as a 4th "+" row) + 1 (edited in). The ledger records what the
            // tool reported — git recomputes the authoritative counts when the
            // file is git-covered.
            assertEquals(a.added, 5)
            assertEquals(a.removed, 1)
            assert(a.diffText.exists(_.contains("\n l1"))) // context line preserved
            val b = report.files(1)
            // journal sum: 3 creation rows (incl. the trailing newline's empty
            // row) + 2 MultiEdit replacements
            assertEquals(b.added, 5)
            assertEquals(b.removed, 2)
            assert(b.diffText.exists(_.contains("+ALPHA")))
            assert(b.diffText.exists(_.contains("+BETA")))
        }
    }
  }

  test("ledger-less ToolContext (None) is a silent no-op for the tools") {
    val dir = root / "nol" / "x"
    os.makeDir.all(dir)
    val ctx = ToolContext(projectRoot = dir.toString) // no ledger — direct REST / harness shape
    WriteTool.call(writeInput(dir / "c.txt", "x\n"), ctx).map {
      case Right(_) => assert(true)
      case Left(e) => fail(s"write should succeed without a ledger: ${e.message}")
    }
  }

  test("persisted report is a pure disk artifact under <workspace>/.nebflow/file-changes/") {
    val ws = root / "ws"
    os.makeDir.all(ws)
    val report = FileChangeReport.Report(
      "node-persist",
      42L,
      ws.toString,
      List(
        FileChangeReport.Entry(
          "a.txt",
          FileChangeType.Modified,
          1,
          1,
          FileChangeReport.SourceGit,
          Some("@@ -1,1 +1,1 @@\n-x\n+y"),
          None
        )
      )
    )
    FileChangeReport.persist(ws.toString, report).map { _ =>
      val f = ws / ".nebflow" / "file-changes" / "node-persist.json"
      assertEquals(f.relativeTo(ws), os.rel / ".nebflow" / "file-changes" / "node-persist.json")
      val text = os.read(f)
      assert(text.contains("\"source\":\"git\""))
      assert(text.contains("\"diffText\":\"@@ -1,1 +1,1 @@\\n-x\\n+y\""))
    }
  }

end FileDiffToolLayerSpec
