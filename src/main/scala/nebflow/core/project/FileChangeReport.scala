package nebflow.core.project

import cats.effect.IO
import cats.syntax.all.*
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.core.AtomicJson
import nebflow.core.FileChangeEvent
import nebflow.core.FileChangeLedger
import nebflow.core.FileChangeType
import nebflow.shared.NebflowLogger
import nebflow.shared.PathUtil

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

import scala.concurrent.duration.*

private val fileDiffLogger = NebflowLogger.forName("nebflow.filediff")

/**
 * Per-node file-change report: the durable artifact behind the frontend diff
 * view for completed nodes.
 *
 * Sources, in priority order (author acceptance: git-repository files prefer
 * the real git diff; non-git files fall back to tool-captured snapshots):
 *  1. git — read-only `git status --porcelain` + `git diff HEAD -- <path>`
 *     inside the node's resolved root (workspace or worktree). This covers ALL
 *     write paths, including Bash-side writes the tool layer never sees.
 *  2. tool ledger — FileChangeLedger events coalesced per path, for files the
 *     git face cannot express (non-git directories) or does not list (changes
 *     already committed during the session).
 *
 * Safety envelope (mirrors CompletionGate's discipline):
 *  - git is always invoked `--no-optional-locks -C <root>` and READ-ONLY
 *    (status / diff only — never add/commit/checkout/stash);
 *  - every git failure is fail-open: that source degrades, the completion
 *    chain never breaks;
 *  - oversized inputs degrade to counts-only entries (no diff body) instead of
 *    hard computation.
 *
 * The report persists at `<workspace>/.nebflow/file-changes/<nodeId>.json`
 * (git-ignored process material) and is served by
 * `GET /api/projects/<name>/flow-map/nodes/<nodeId>/file-changes`.
 */
object FileChangeReport:

  /** Files above this size are not read into a diff — counts-only entry. */
  val MaxFileBytesForDiff: Long = 1024L * 1024

  /** Per-file cap on a computed diff body; larger diffs degrade to counts-only. */
  val MaxDiffCharsPerFile: Int = 200000

  /** Directory name under `<workspace>/.nebflow/` (git-ignored). */
  val FileChangesDirName: String = "file-changes"

  /** Whole build+persist leg timeout — a stuck git must not delay completion. */
  private val ReportTimeout: FiniteDuration = 20.seconds

  /** One delivered file entry (wire shape; keys are camelCase in JSON). */
  final case class Entry(
    path: String,
    changeType: String,
    added: Int,
    removed: Int,
    source: String,
    diffText: Option[String],
    degradedReason: Option[String]
  )

  final case class Report(nodeId: String, generatedAt: Long, root: String, files: List[Entry])

  /** Source tags on entries. */
  val SourceGit = "git"
  val SourceToolLedger = "tool-ledger"

  // ── Production entry (completion hook) ────────────────────────────────────

  /**
   * Drain the node's ledger slice, build the report, persist it. Best-effort:
   * every failure path logs a warning and returns None — completion semantics
   * are untouched. Returns a marker string when a non-empty report was written.
   *
   * `workspace` is the BUILD root base (git face; a scoped delegate host passes
   * the delegate cwd). `persistWorkspace` overrides the PERSIST location — the
   * canonical read face is the PROJECT workspace
   * (`GET /api/projects/<name>/flow-map/nodes/<id>/file-changes` reads
   * `<project ws>/.nebflow/file-changes/`), so scoped hosts pass the base
   * project workspace here. Default = persist under `workspace` (per-project
   * engines: identical path, byte-equal behavior).
   */
  def buildAndPersist(
    workspace: String,
    worktree: Option[String],
    nodeId: String,
    ledger: FileChangeLedger,
    runner: CompletionGate.GitRunner = CompletionGate.defaultRunner,
    persistWorkspace: Option[String] = None
  ): IO[Option[String]] =
    val persistWs = persistWorkspace.getOrElse(workspace)
    val leg = for
      events <- ledger.drain(nodeId)
      report <- build(workspace, worktree, nodeId, events, runner)
      path <-
        if report.files.isEmpty then IO.none[String]
        else persist(persistWs, report).as(Some(reportFilePath(persistWs, report.nodeId)))
    yield path
    leg
      .timeout(ReportTimeout)
      .handleErrorWith(e =>
        fileDiffLogger.warn(
          s"file-change report for node '$nodeId' skipped: ${Option(e.getMessage).getOrElse(e.toString)}"
        ) *> IO.pure(None)
      )
  end buildAndPersist

  def reportFilePath(workspace: String, nodeId: String): String =
    (os.Path(workspace, PathUtil.dataRoot) / ".nebflow" / FileChangesDirName / s"$nodeId.json").toString

  // ── Build (git via injected read-only runner) ─────────────────────────────

  /**
   * Build the report from git status/diff + ledger events. Never throws on git
   * failure (fail-open per source). `events` are the node's ledger slice in
   * write order.
   */
  def build(
    workspace: String,
    worktree: Option[String],
    nodeId: String,
    events: List[FileChangeEvent],
    runner: CompletionGate.GitRunner = CompletionGate.defaultRunner
  ): IO[Report] =
    val root = PathUtil.resolveNodeProjectRoot(workspace, worktree)
    for
      // Git-escape guard: only trust the git face when the node root IS the
      // repository toplevel (normal repo, or a linked worktree whose
      // show-toplevel is the worktree dir itself). A non-git workspace nested
      // inside an outer repository resolves upward to the OUTER toplevel —
      // building the git face there floods the report with the outer repo's
      // unrelated dirty files. Unequal → treated as non-git (ledger only).
      toplevel <- gitToplevel(root, runner)
      statusLines <- toplevel match
        case Some(r) => gitStatus(r, runner)
        case None    => IO.pure(Nil)
      gitEntries <- gitEntriesFor(root, statusLines, runner)
      covered = gitEntries.map(_.path).toSet
      ledgerEntries = ledgerOnlyEntries(root, events, covered)
    yield Report(nodeId, System.currentTimeMillis(), root, (gitEntries ++ ledgerEntries).sortBy(_.path))
  end build

  /**
   * The repository toplevel `root` belongs to, or None when the git face must
   * not run: git absent, root outside any work tree, or the toplevel differs
   * from the node root (the nested-workspace escape case — see [[build]]).
   * Fail-open: any runner failure degrades to None (ledger-only report).
   */
  private def gitToplevel(root: String, runner: CompletionGate.GitRunner): IO[Option[String]] =
    runner(root, List("rev-parse", "--show-toplevel")).map {
      case Right(top) =>
        val topPath = Paths.get(top.trim).toAbsolutePath.normalize
        val rootPath = Paths.get(root).toAbsolutePath.normalize
        if topPath == rootPath || isSameRealDir(topPath, rootPath) then Some(root) else None
      case Left(_) => None
    }

  /** Symlink-tolerant equality fallback (e.g. /tmp vs /private/tmp layouts). */
  private def isSameRealDir(a: java.nio.file.Path, b: java.nio.file.Path): Boolean =
    try a.toRealPath() == b.toRealPath()
    catch case _: Exception => false

  /** `git status --porcelain` lines (empty on any failure — fail-open). */
  private def gitStatus(root: String, runner: CompletionGate.GitRunner): IO[List[String]] =
    runner(root, List("status", "--porcelain")).map {
      case Right(out) => out.linesIterator.map(_.stripTrailing).filter(_.nonEmpty).toList
      case Left(_) => Nil
    }

  private def gitEntriesFor(
    root: String,
    lines: List[String],
    runner: CompletionGate.GitRunner
  ): IO[List[Entry]] =
    dedupePaths(lines.flatMap(parsePorcelain)).traverse { case (path, changeType) =>
      buildGitEntry(root, path, changeType, runner)
    }
  end gitEntriesFor

  /** Dedupe porcelain paths, keeping the highest-priority change type per path. */
  private[project] def dedupePaths(items: List[PorcelainPath]): List[(String, String)] =
    val priority = Map(FileChangeType.Added -> 0, FileChangeType.Deleted -> 1, FileChangeType.Modified -> 2)
    items
      .foldLeft(scala.collection.mutable.LinkedHashMap.empty[String, String]) { (m, p) =>
        m.get(p.path) match
          case Some(existing) if priority.getOrElse(existing, 9) <= priority.getOrElse(p.changeType, 9) => m
          case _ => m.addOne(p.path -> p.changeType)
      }
      .toList
  end dedupePaths

  private[project] final case class PorcelainPath(path: String, changeType: String)

  /** Parse one porcelain line; unparseable lines are dropped (defensive). */
  private[project] def parsePorcelain(line: String): Option[PorcelainPath] =
    // CompletionGate.defaultRunner trims the whole output, so the FIRST status
    // line loses its leading status column (" D p" → "D p"). Re-align to the
    // porcelain v1 "XY<space>path" shape: when column 3 is not the separator
    // space, the X column alone survived the trim — reinsert the pad column.
    val aligned = if line.length >= 3 && line.charAt(2) != ' ' then " " + line else line
    if aligned.length < 4 then None
    else
      val x = aligned.charAt(0)
      val y = aligned.charAt(1)
      val rawPath = aligned.substring(3).trim
      // rename/copy form: "R  old -> new" — the new path is the surviving file
      val path0 =
        if rawPath.contains(" -> ") then rawPath.substring(rawPath.lastIndexOf(" -> ") + 4) else rawPath
      val path = stripQuotes(path0)
      if path.isEmpty then None
      else
        val changeType =
          if x == '?' && y == '?' then FileChangeType.Added
          else if x == 'D' || y == 'D' then FileChangeType.Deleted
          else if x == 'A' then FileChangeType.Added
          else FileChangeType.Modified
        Some(PorcelainPath(path, changeType))
  end parsePorcelain

  /** porcelain v1 wraps exotic paths in double quotes — unwrap the common form. */
  private def stripQuotes(p: String): String =
    if p.length >= 2 && p.startsWith("\"") && p.endsWith("\"") then p.substring(1, p.length - 1) else p

  /** Build one entry for a git-listed path (git is the authoritative face). */
  private def buildGitEntry(
    root: String,
    path: String,
    changeType: String,
    runner: CompletionGate.GitRunner
  ): IO[Entry] =
    changeType match
      case FileChangeType.Added =>
        // New file (staged or untracked): the whole file is the addition.
        readAsAllAdditions(root, path)
      case FileChangeType.Deleted =>
        runner(root, List("diff", "HEAD", "--", path)).map {
          case Right(diff) if diff.trim.nonEmpty =>
            val (a, r) = countsFromDiff(diff)
            capEntry(path, FileChangeType.Deleted, a, r, diff, SourceGit)
          case _ =>
            Entry(path, FileChangeType.Deleted, 0, 0, SourceGit, None, Some("git-diff-unavailable"))
        }
      case _ =>
        // Modified: worktree (and index) vs HEAD; a repo without HEAD falls
        // back to index-vs-worktree.
        runner(root, List("diff", "HEAD", "--", path)).flatMap {
          case Right(diff) if diff.trim.nonEmpty =>
            val (a, r) = countsFromDiff(diff)
            IO.pure(capEntry(path, FileChangeType.Modified, a, r, diff, SourceGit))
          case Right(_) =>
            // Status says dirty but the HEAD diff is empty (e.g. mode-only or
            // staged-then-reverted): degrade honestly instead of fabricating.
            IO.pure(Entry(path, FileChangeType.Modified, 0, 0, SourceGit, None, Some("no-head-diff")))
          case Left(_) =>
            runner(root, List("diff", "--", path)).map {
              case Right(diff) if diff.trim.nonEmpty =>
                val (a, r) = countsFromDiff(diff)
                capEntry(path, FileChangeType.Modified, a, r, diff, SourceGit)
              case _ =>
                Entry(path, FileChangeType.Modified, 0, 0, SourceGit, None, Some("git-diff-unavailable"))
            }
        }
  end buildGitEntry

  /** Read a new file and render an all-additions unified diff (git-style header). */
  private def readAsAllAdditions(root: String, path: String): IO[Entry] =
    IO.blocking {
      val file = Paths.get(root).resolve(path)
      if !Files.isRegularFile(file) then
        Entry(path, FileChangeType.Added, 0, 0, SourceGit, None, Some("file-unreadable"))
      else if Files.size(file) > MaxFileBytesForDiff then
        Entry(
          path,
          FileChangeType.Added,
          0,
          0,
          SourceGit,
          None,
          Some(s"file-too-large: ${Files.size(file)} bytes exceeds cap $MaxFileBytesForDiff")
        )
      else
        val bytes = Files.readAllBytes(file)
        if bytes.take(8192).contains(0) then
          Entry(path, FileChangeType.Added, 0, 0, SourceGit, None, Some("binary-file"))
        else
          val content = new String(bytes, StandardCharsets.UTF_8)
          val raw = content.split("\\r?\\n", -1).toList
          // A trailing separator yields a phantom empty last element — git
          // counts the final newline-terminated line once.
          val lines = raw match
            case init :+ last if last.isEmpty && raw.length > 1 => init
            case other => other
          val n = lines.length
          if n == 0 then
            Entry(path, FileChangeType.Added, 0, 0, SourceGit, None, None)
          else
            val body = s"@@ -0,0 +1,$n @@\n" + lines.map(l => "+" + l).mkString("\n")
            capEntry(path, FileChangeType.Added, n, 0, body, SourceGit)
          end if
    }.handleError { e =>
      fileDiffLogger.warn(s"file-change read failed for $path: ${e.getMessage}")
      Entry(path, FileChangeType.Added, 0, 0, SourceGit, None, Some("file-unreadable"))
    }
  end readAsAllAdditions

  /** Apply the per-file diff char cap: oversized bodies degrade to counts-only. */
  private def capEntry(path: String, changeType: String, a: Int, r: Int, diff: String, source: String): Entry =
    if diff.length > MaxDiffCharsPerFile then
      Entry(path, changeType, a, r, source, None, Some(s"diff-too-large: ${diff.length} chars exceeds cap $MaxDiffCharsPerFile"))
    else Entry(path, changeType, a, r, source, Some(diff), None)

  /** Count +/- payload lines in a git diff body (file headers +++/--- excluded). */
  private[project] def countsFromDiff(diff: String): (Int, Int) =
    var added = 0
    var removed = 0
    diff.linesIterator.foreach { l =>
      if l.startsWith("+") && !l.startsWith("+++") then added += 1
      else if l.startsWith("-") && !l.startsWith("---") then removed += 1
    }
    (added, removed)

  /**
   * Ledger slice for paths the git face does not cover: files outside any git
   * repository, or session changes already committed (git diff empty). Paths
   * git already reports are skipped — git is the authoritative face there.
   */
  private def ledgerOnlyEntries(root: String, events: List[FileChangeEvent], covered: Set[String]): List[Entry] =
    FileChangeLedger
      .coalesce(events)
      .filterNot(e => covered.contains(relativize(root, e.path)))
      .map { e =>
        val degraded =
          if e.diffText.isEmpty then e.degradedReason.orElse(Some("diff-unavailable"))
          else None
        Entry(relativize(root, e.path), e.changeType, e.addedLines, e.removedLines, SourceToolLedger, e.diffText, degraded)
      }
  end ledgerOnlyEntries

  /** Root-relative display path when under the root, absolute otherwise. */
  private[project] def relativize(root: String, path: String): String =
    try
      val rootP = Paths.get(root).toAbsolutePath.normalize
      val pathP = Paths.get(path).toAbsolutePath.normalize
      val rel = rootP.relativize(pathP).toString
      if rel.startsWith("..") then path else rel
    catch case _: Exception => path

  // ── Persist ───────────────────────────────────────────────────────────────

  /** Wire JSON: {nodeId, generatedAt, root, files:[{path,changeType,added,removed,source,diffText?,degradedReason?}]}. */
  def reportJson(report: Report): Json =
    Json.fromJsonObject(
      JsonObject(
        "nodeId" -> report.nodeId.asJson,
        "generatedAt" -> report.generatedAt.asJson,
        "root" -> report.root.asJson,
        "files" -> report.files.map(entryJson).asJson
      )
    )

  private def entryJson(e: Entry): Json =
    val base = JsonObject(
      "path" -> e.path.asJson,
      "changeType" -> e.changeType.asJson,
      "added" -> e.added.asJson,
      "removed" -> e.removed.asJson,
      "source" -> e.source.asJson
    )
    val withDiff = e.diffText.fold(base)(d => base.add("diffText", d.asJson))
    val withReason = e.degradedReason.fold(withDiff)(r => withDiff.add("degradedReason", r.asJson))
    Json.fromJsonObject(withReason)

  /** Persist atomically to `<workspace>/.nebflow/file-changes/<nodeId>.json`. */
  def persist(workspace: String, report: Report): IO[Unit] =
    IO.blocking {
      val dir = os.Path(workspace, PathUtil.dataRoot) / ".nebflow" / FileChangesDirName
      os.makeDir.all(dir)
      AtomicJson.writeSync(dir / s"${report.nodeId}.json", reportJson(report).noSpaces)
    }

end FileChangeReport
