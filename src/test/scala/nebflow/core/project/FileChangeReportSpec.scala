package nebflow.core.project

import cats.effect.IO
import cats.syntax.all.*
import munit.CatsEffectSuite
import nebflow.core.{FileChangeLedger, FileChangeType}

import java.nio.charset.StandardCharsets

import scala.concurrent.duration.*

/**
 * FileChangeReport regression (file-diff feature delivery face).
 *
 * Covers the unit-checkable acceptance slices:
 *  - non-git directory: tool-ledger events produce per-file unified diffs with
 *    hunk headers and context (AC2);
 *  - git repository: tracked modify / untracked add / tracked delete come out
 *    as git-recomputed entries, and the whole build provably writes nothing to
 *    git (HEAD, porcelain status and the .git file set read identical
 *    before/after — AC3);
 *  - git escape guard: a non-git workspace nested inside a git repository is
 *    treated as non-git (ledger only) — outer-repo files never leak in;
 *  - degradation: an oversized file keeps the entry but carries no diff body
 *    (AC4);
 *  - porcelain parsing incl. rename and quoted-path forms.
 *
 * `git` is required for the git-repository block; that block is skipped
 * (logged) when git is unavailable — the same best-effort contract as
 * CompletionGateSpec.
 */
class FileChangeReportSpec extends CatsEffectSuite:

  override def munitIOTimeout: scala.concurrent.duration.FiniteDuration = 240.seconds

  // Temp root must live OUTSIDE the sbt project tree: under target/ the
  // enclosing Nebflow repo's `git status --porcelain` (repo-root-relative)
  // would leak every workspace-dirty file into the "non-git" fixtures.
  private val tempRoot: os.Path = os.temp.dir(prefix = "nb-file-diff")
  private val ws = tempRoot / "ws"

  override def beforeAll(): Unit =
    os.remove.all(tempRoot)
    os.makeDir.all(ws)

  private def gitAvailable: Boolean =
    try
      val pb = new java.lang.ProcessBuilder("git", "--version")
      pb.redirectErrorStream(true)
      val p = pb.start()
      p.waitFor() == 0
    catch case _: Exception => false

  private def runGit(cwd: os.Path, args: String*): String =
    val pb = new java.lang.ProcessBuilder(("git" :: "--no-optional-locks" :: "-C" :: cwd.toString :: args.toList)*)
    pb.redirectErrorStream(true)
    val p = pb.start()
    val out = new String(p.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
    val code = p.waitFor()
    assertEquals(code, 0, clue = s"git $args failed: $out")
    out

  private def write(path: os.Path, content: String): Unit =
    os.makeDir.all(path / os.up)
    os.write.over(path, content)

  /** Hand-formed tool result strings (same contract DiffUtil emits). */
  private def createdResult(path: os.Path, content: String): String =
    val lines = content.split("\n").toList
    s"OK:CREATED ${path.last}, ${lines.length} lines\n@@ -0,0 +1,${lines.length} @@\n" + lines
      .map(l => "+" + l)
      .mkString("\n")

  private def updatedResult(added: Int, removed: Int, body: String): String =
    s"OK:UPDATED x, $added added, $removed removed\n$body"

  // ── AC2: non-git directory — ledger events become per-file diffs ─────────

  test("non-git workspace: ledger events build per-file unified diffs (tool-ledger source)") {
    for
      ledger <- FileChangeLedger.make()
      // file 1: created then edited again — coalesces (counts sum, order kept)
      _ <- ledger.recordToolResult(
        Some("s"),
        Some("node-a"),
        None,
        "Write",
        (ws / "one.txt").toString,
        createdResult(ws / "one.txt", "l1\nl2\nl3\n")
      )
      _ <- ledger.recordToolResult(
        Some("s"),
        Some("node-a"),
        None,
        "Edit",
        (ws / "one.txt").toString,
        updatedResult(1, 1, "@@ -2,2 +2,2 @@\n-l2\n+L2")
      )
      // file 2: single edit in a subdirectory
      _ <- ledger.recordToolResult(
        Some("s"),
        Some("node-a"),
        None,
        "Edit",
        (ws / "sub" / "two.txt").toString,
        updatedResult(2, 0, "@@ -1,3 +1,5 @@\n ctx\n+na\n+nb\n ctx2\n+nc\n+nd")
      )
      events <- ledger.drain("node-a")
      report <- FileChangeReport.build(ws.toString, None, "node-a", events)
    yield
      assertEquals(report.files.map(_.path), List("one.txt", "sub/two.txt")) // sorted, root-relative
      val one = report.files.head
      assertEquals(one.source, FileChangeReport.SourceToolLedger)
      assertEquals(one.changeType, FileChangeType.Added) // creation escalates
      assertEquals(one.added, 4) // 3 (creation) + 1 (edit)
      assertEquals(one.removed, 1)
      assert(one.diffText.exists(_.contains("@@ -0,0 +1,3 @@")))
      assert(one.diffText.exists(d => d.indexOf("@@ -0,0") < d.indexOf("@@ -2,2")))
      val two = report.files(1)
      assertEquals(two.added, 2)
      assert(two.diffText.exists(_.contains("+nb")))
  }

  test("persist: writes the per-node JSON under <workspace>/.nebflow/file-changes/") {
    val report = FileChangeReport.Report(
      "node-p",
      123L,
      ws.toString,
      List(
        FileChangeReport.Entry(
          "a.txt",
          FileChangeType.Modified,
          2,
          1,
          FileChangeReport.SourceToolLedger,
          Some("@@ -1,2 +1,2 @@\n-x\n+y"),
          None
        )
      )
    )
    FileChangeReport.persist(ws.toString, report).map { _ =>
      val f = ws / ".nebflow" / "file-changes" / "node-p.json"
      assert(os.exists(f))
      val json = io.circe.parser.parse(os.read(f)).toOption.get
      assertEquals(json.hcursor.downField("nodeId").as[String].toOption, Some("node-p"))
      val files = json.hcursor.downField("files").as[List[io.circe.Json]].toOption.get
      assertEquals(files.length, 1)
      assertEquals(files.head.hcursor.downField("path").as[String].toOption, Some("a.txt"))
      assert(files.head.hcursor.downField("diffText").as[String].toOption.exists(_.contains("+y")))
    }
  }

  // ── AC3: git repository — git-recomputed entries + zero git writes ───────

  private def prepareGitFixture(): os.Path =
    val repo = tempRoot / s"repo-${System.nanoTime()}"
    os.makeDir.all(repo)
    runGit(repo, "init")
    runGit(repo, "config", "user.email", "file-diff@test.local")
    runGit(repo, "config", "user.name", "file-diff-test")
    write(repo / "tracked.txt", "alpha\nbeta\ngamma\n")
    write(repo / "doomed.txt", "gone-1\ngone-2\n")
    runGit(repo, "add", ".")
    runGit(repo, "commit", "-m", "base")
    repo
  end prepareGitFixture

  private def gitState(repo: os.Path): (String, String, Set[String]) =
    val head = runGit(repo, "rev-parse", "HEAD").trim
    val status = runGit(repo, "status", "--porcelain")
    val gitDirFiles = os.walk(repo / ".git").map(_.relativeTo(repo / ".git").toString).toSet
    (head, status, gitDirFiles)

  if gitAvailable then
    test(
      "git workspace: modify/untracked/delete → git entries; zero git writes (HEAD+status+.git file set unchanged)"
    ) {
      val repo = prepareGitFixture()

      // simulate the node's session changes on the worktree
      write(repo / "tracked.txt", "alpha\nbeta-prime\ngamma\ndelta\n") // modify
      write(repo / "brand-new.txt", "n1\nn2\n") // untracked new file
      os.remove(repo / "doomed.txt") // tracked delete

      // Zero-write evidence baseline is taken AFTER the worktree mutations and
      // BEFORE the build: build(repo) itself must leave HEAD, porcelain status
      // and the .git file set byte-identical.
      val before = gitState(repo)

      val reportIO = FileChangeReport.build(repo.toString, None, "node-g", Nil, CompletionGate.defaultRunner)
      reportIO.map { report =>
        val byPath = report.files.map(f => f.path -> f).toMap

        val mod = byPath("tracked.txt")
        assertEquals(mod.changeType, FileChangeType.Modified)
        assertEquals(mod.source, FileChangeReport.SourceGit)
        assertEquals(mod.added, 2)
        assertEquals(mod.removed, 1)
        assert(mod.diffText.exists(_.contains("-beta")))
        assert(mod.diffText.exists(_.contains("+beta-prime")))
        assert(mod.diffText.exists(_.contains("@@")))

        val fresh = byPath("brand-new.txt")
        assertEquals(fresh.changeType, FileChangeType.Added)
        assertEquals(fresh.added, 2)
        assert(fresh.diffText.exists(_.startsWith("@@ -0,0 +1,2 @@")))

        val dead = byPath("doomed.txt")
        assertEquals(dead.changeType, FileChangeType.Deleted)
        assertEquals(dead.removed, 2)
        assert(dead.diffText.exists(_.contains("-gone-1")))

        // zero-git-write evidence: HEAD, porcelain status and the .git file set
        // are byte-identical across the build (status uses --no-optional-locks;
        // diff/rev-parse are read-only by contract).
        val after = gitState(repo)
        assertEquals(after._1, before._1, "HEAD moved during build")
        assertEquals(after._2, before._2, "porcelain status changed during build")
        assertEquals(after._3, before._3, "files inside .git changed during build")
      }
    }

    test("git workspace: ledger paths already covered by git are skipped (git is authoritative)") {
      val repo = prepareGitFixture()
      write(repo / "tracked.txt", "alpha\nbeta-prime\n")
      val ledgerEvent = nebflow.core.FileChangeEvent(
        (repo / "tracked.txt").toString,
        FileChangeType.Modified,
        9,
        9,
        Some("@@ -9,9 +9,9 @@\n+stale"),
        None,
        "Edit",
        1L
      )
      FileChangeReport.build(repo.toString, None, "node-h", List(ledgerEvent), CompletionGate.defaultRunner).map {
        report =>
          val mod = report.files.find(_.path == "tracked.txt").get
          assertEquals(mod.source, FileChangeReport.SourceGit)
          assertEquals(mod.added, 1) // git's numbers, not the ledger's 9
          assert(!mod.diffText.exists(_.contains("stale")))
      }
    }

    test("nested non-git workspace inside a git repo: outer-repo files must not leak (git escape guard)") {
      // A workspace that is NOT itself a repository but merely sits inside one:
      // `git rev-parse --show-toplevel` resolves upward to the OUTER toplevel,
      // which is not the workspace — the git face must be skipped entirely
      // (ledger-only report), never flood the report with the outer repo's
      // tracked/dirty files.
      val outer = tempRoot / s"outer-${System.nanoTime()}"
      os.makeDir.all(outer)
      runGit(outer, "init")
      runGit(outer, "config", "user.email", "file-diff@test.local")
      runGit(outer, "config", "user.name", "file-diff-test")
      write(outer / "outer-tracked.txt", "outer-1\n")
      runGit(outer, "add", ".")
      runGit(outer, "commit", "-m", "base")
      write(outer / "outer-dirty.txt", "outer noise\n") // untracked outer-repo noise
      val nested = outer / "ws-nested"
      os.makeDir.all(nested)
      for
        ledger <- FileChangeLedger.make()
        _ <- ledger.recordToolResult(
          Some("s"),
          Some("node-nested"),
          None,
          "Write",
          (nested / "deeper.txt").toString,
          createdResult(nested / "deeper.txt", "d1\nd2\n")
        )
        events <- ledger.drain("node-nested")
        report <- FileChangeReport.build(nested.toString, None, "node-nested", events, CompletionGate.defaultRunner)
      yield
        // Exactly the node's own ledger slice — zero outer-repo entries, zero
        // git-sourced entries (the whole git face is off for this root).
        assertEquals(report.files.map(_.path), List("deeper.txt"))
        val e = report.files.head
        assertEquals(e.source, FileChangeReport.SourceToolLedger)
        assertEquals(e.added, 2)
        assert(e.diffText.exists(_.contains("@@ -0,0 +1,2 @@")))
        assert(!report.files.exists(_.path.contains("outer")))
      end for
    }
  else println("FileChangeReportSpec: git unavailable — git-integration block skipped")
  end if

  // ── AC4: degradation ─────────────────────────────────────────────────────

  test("oversized untracked file: counts entry without a diff body (file-too-large)") {
    val repo = tempRoot / s"big-${System.nanoTime()}"
    os.makeDir.all(repo)
    runGit(repo, "init")
    val big = "x" * 256 + "\n"
    val lines = (FileChangeReport.MaxFileBytesForDiff / 256 + 1).toInt
    write(repo / "big.txt", big * lines) // untracked, > MaxFileBytesForDiff
    val entryEvent = nebflow.core.FileChangeEvent(
      (repo / "big.txt").toString,
      FileChangeType.Added,
      1,
      0,
      None,
      None,
      "Write",
      1L
    )
    FileChangeReport.build(repo.toString, None, "node-big", List(entryEvent), CompletionGate.defaultRunner).map {
      report =>
        val e = report.files.find(_.path == "big.txt").get
        assertEquals(e.source, FileChangeReport.SourceGit) // the git face owned the entry
        assertEquals(e.diffText, None)
        assert(e.degradedReason.exists(_.startsWith("file-too-large")))
        assertEquals(e.added, 0)
    }
  }

  // ── porcelain parsing ────────────────────────────────────────────────────

  test("parsePorcelain: untracked / delete / staged-add / rename / quoted path") {
    val parsed = List("?? new.txt", " D old.txt", "A  staged.txt", "R  ren-old -> ren-new", " M mod.txt", "MM both.txt")
      .flatMap(FileChangeReport.parsePorcelain)
    assertEquals(
      parsed.map(p => p.path -> p.changeType),
      List(
        "new.txt" -> FileChangeType.Added,
        "old.txt" -> FileChangeType.Deleted,
        "staged.txt" -> FileChangeType.Added,
        "ren-new" -> FileChangeType.Modified,
        "mod.txt" -> FileChangeType.Modified,
        "both.txt" -> FileChangeType.Modified
      )
    )
    assertEquals(FileChangeReport.parsePorcelain("?? "), None) // no path → dropped
  }

  test("parsePorcelain: trimmed first status line re-aligns (defaultRunner output trim)") {
    // The runner trims the whole status output; the first line loses its pad
    // column. Column 3 is not the separator space there — the parser must
    // re-align instead of slicing the first path character off.
    val parsed = List("D gone.txt", " M kept.txt").flatMap(FileChangeReport.parsePorcelain)
    assertEquals(
      parsed.map(p => p.path -> p.changeType),
      List("gone.txt" -> FileChangeType.Deleted, "kept.txt" -> FileChangeType.Modified)
    )
  }

  test("dedupePaths keeps the highest-priority change type per path") {
    val deduped = FileChangeReport.dedupePaths(
      List(
        FileChangeReport.PorcelainPath("a", FileChangeType.Modified),
        FileChangeReport.PorcelainPath("a", FileChangeType.Added),
        FileChangeReport.PorcelainPath("b", FileChangeType.Deleted)
      )
    )
    assertEquals(deduped, List("a" -> FileChangeType.Added, "b" -> FileChangeType.Deleted))
  }

  // ── delegate-completion chat face (bug #58 修, 2026-10-09) ───────────────

  test("delegateBatchMetadata: non-git delegate batch — ledger slice by session id, diffs attached") {
    for
      ledger <- FileChangeLedger.make()
      // Write under the DELEGATE SESSION id (no flow node) — the attribution
      // shape real delegate sessions carry (scope flow maps have no nodes).
      _ <- ledger.recordToolResult(
        Some("delegate-nebflow-x"),
        None,
        None,
        "Write",
        (ws / "msgQuote.js").toString,
        createdResult(ws / "msgQuote.js", "a\nb\n")
      )
      _ <- ledger.recordToolResult(
        Some("delegate-nebflow-x"),
        None,
        None,
        "Edit",
        (ws / "chat.js").toString,
        updatedResult(1, 1, "@@ -1,2 +1,2 @@\n-old\n+new")
      )
      meta <- FileChangeReport.delegateBatchMetadata(ws.toString, None, "delegate-nebflow-x", ledger)
      // Drain semantics: the session bucket is consumed — a second build is empty.
      second <- FileChangeReport.delegateBatchMetadata(ws.toString, None, "delegate-nebflow-x", ledger)
    yield
      val json = meta.getOrElse(fail("expected a payload for a changed-file batch"))
      assertEquals(json.hcursor.downField("nodeId").as[String].toOption, Some("delegate-nebflow-x"))
      val files = json.hcursor.downField("files").as[List[io.circe.Json]].toOption.get
      assertEquals(
        files.map(_.hcursor.downField("path").as[String].toOption),
        List(Some("chat.js"), Some("msgQuote.js"))
      )
      assert(files(1).hcursor.downField("diffText").as[String].toOption.exists(_.contains("@@ -0,0 +1,2 @@")))
      assert(second.isEmpty, "drained bucket → None (no double-attach)")
  }

  test("delegateBatchMetadata: batch with zero changes → None (row stays byte-identical)") {
    for
      ledger <- FileChangeLedger.make()
      meta <- FileChangeReport.delegateBatchMetadata(ws.toString, None, "delegate-nebflow-empty", ledger)
    yield assert(meta.isEmpty)
  }

  test("delegateBatchMetadata: oversized batch keeps every diffText full (chat-face cap removed, 2026-10-10)") {
    // 2026-10-10 user report (screenshot): a 197979-char batch was stripped to
    // counts-only with "batch-too-large … exceeds chat cap 60000". The chat
    // face is gone — the wire payload carries diff bodies verbatim (Canvas
    // renders them in full; the retired degradation surface must never recur).
    val bigBody = "+" * 70000
    for
      ledger <- FileChangeLedger.make()
      _ <- ledger.record(
        List("delegate-big"),
        nebflow.core.FileChangeEvent(
          (ws / "big.txt").toString,
          FileChangeType.Modified,
          70000,
          0,
          Some(bigBody),
          None,
          "Write",
          1L
        )
      )
      meta <- FileChangeReport.delegateBatchMetadata(ws.toString, None, "delegate-big", ledger)
    yield
      val json = meta.getOrElse(fail("expected a payload for a changed-file batch"))
      val files = json.hcursor.downField("files").as[List[io.circe.Json]].toOption.get
      assertEquals(files.length, 1)
      // Counts survive (bubble header row shows +a −r)…
      assertEquals(files.head.hcursor.downField("added").as[Int].toOption, Some(70000))
      // …and the full body rides the wire verbatim — no strip, no shared reason.
      assertEquals(files.head.hcursor.downField("diffText").as[String].toOption, Some(bigBody))
      assertEquals(files.head.hcursor.downField("degradedReason").as[String].toOption, None)
    end for
  }

  test("git workspace: file whose diff exceeds the old 200k per-file cap keeps the full body") {
    // Per-file generation-side cap (MaxDiffCharsPerFile=200000) removed with the
    // same ruling: a ~214k-char all-additions diff must arrive intact.
    val repo = tempRoot / s"wide-${System.nanoTime()}"
    os.makeDir.all(repo)
    runGit(repo, "init")
    val content = (1 to 5100).map(i => s"line-$i-${"y" * 30}").mkString("\n") // ≈214k chars
    write(repo / "wide.txt", content + "\n") // untracked → all-additions diff
    FileChangeReport.build(repo.toString, None, "node-wide", Nil, CompletionGate.defaultRunner).map { report =>
      val e = report.files.find(_.path == "wide.txt").get
      assertEquals(e.changeType, FileChangeType.Added)
      val diff = e.diffText.getOrElse(fail("expected a full diff body"))
      assert(diff.length > 200000, s"diff body must exceed the removed cap, got ${diff.length}")
      assertEquals(e.degradedReason, None)
      assert(diff.contains("@@ -0,0 +1,5100 @@"))
      assert(diff.contains(s"+${content.linesIterator.toList.last}"))
    }
  }

end FileChangeReportSpec
