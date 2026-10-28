package nebflow.core

import cats.effect.IO
import cats.effect.Ref
import nebflow.core.tools.DiffUtil

/** How an agent-visible file changed (mirrors the delivery contract keys). */
object FileChangeType:
  val Added = "added"
  val Modified = "modified"
  val Deleted = "deleted"

/**
 * One captured file-change event, recorded by a write-path tool (Write / Edit /
 * MultiEdit) right after a successful write.
 *
 *  @param path           absolute path of the file as written
 *  @param changeType     "added" (file created by this call) or "modified"
 *  @param addedLines     lines added by this call
 *  @param removedLines   lines removed by this call
 *  @param diffText       unified diff body (hunks only, no OK: header); stored
 *                        verbatim — the capture-time char cap was removed
 *                        (2026-10-10 ruling, Canvas renders full bodies)
 *  @param degradedReason present when diffText is None: why the diff body was dropped
 *  @param sourceTool     the tool that produced the change ("Write" / "Edit" / "MultiEdit")
 *  @param timestamp      epoch millis of the write
 */
final case class FileChangeEvent(
  path: String,
  changeType: String,
  addedLines: Int,
  removedLines: Int,
  diffText: Option[String],
  degradedReason: Option[String],
  sourceTool: String,
  timestamp: Long
)

/**
 * Session-level ledger of file changes made through the tool layer.
 *
 * Events are indexed under every attribution key the calling session carries
 * (session id always; flow node id and task id when present), so a node/task
 * completion can drain exactly its own slice. Draining removes only the named
 * key's bucket — copies under the other keys (e.g. the task bucket fed by all
 * of a task's node sessions) survive for the later task-level drain.
 *
 * Memory-only by design; the durable artifact is the per-node JSON report the
 * completion hook persists under `<workspace>/.nebflow/file-changes/`
 * (nebflow.core.project.FileChangeReport). Bash-side writes bypass the tool
 * layer and are intentionally out of scope here — inside git repositories the
 * report builder recomputes from `git diff`, which covers that gap.
 */
class FileChangeLedger(private val state: Ref[IO, Map[String, Vector[FileChangeEvent]]]):

  /** Append one event under each of the given keys, evicting the oldest past the cap. */
  def record(keys: List[String], event: FileChangeEvent): IO[Unit] =
    if keys.isEmpty then IO.unit
    else
      state.update { m =>
        keys.foldLeft(m) { (acc, key) =>
          val next = acc.getOrElse(key, Vector.empty) :+ event
          acc.updated(
            key,
            if next.length > FileChangeLedger.MaxEventsPerKey then next.takeRight(FileChangeLedger.MaxEventsPerKey)
            else next
          )
        }
      }

  /** Remove and return the bucket for `key` (other keys' copies are kept). */
  def drain(key: String): IO[List[FileChangeEvent]] =
    state.modify(m => (m - key, m.getOrElse(key, Vector.empty).toList))

  /** Read-only peek (diagnostics / tests). */
  def snapshot(key: String): IO[List[FileChangeEvent]] =
    state.get.map(_.getOrElse(key, Vector.empty).toList)

  /**
   * Tool-side one-liner: parse a Write/Edit/MultiEdit success result string and
   * append it under the session's attribution keys. Results that match neither
   * OK: prefix are skipped (defensive — the callers only invoke on success).
   */
  def recordToolResult(
    sessionId: Option[String],
    flowNodeId: Option[String],
    taskId: Option[String],
    tool: String,
    path: String,
    result: String
  ): IO[Unit] =
    FileChangeLedger.parseToolResult(tool, path, result, System.currentTimeMillis()) match
      case Some(event) => record(FileChangeLedger.keysFor(sessionId, flowNodeId, taskId), event)
      case None => IO.unit
  end recordToolResult

end FileChangeLedger

object FileChangeLedger:

  /** Per-key event cap; the oldest events are evicted first (bounded memory). */
  val MaxEventsPerKey: Int = 200

  /** Global instance — SharedResources' default; tests build isolated ones via [[make]]. */
  val unsafe: FileChangeLedger = new FileChangeLedger(Ref.unsafe[IO, Map[String, Vector[FileChangeEvent]]](Map.empty))

  def make(): IO[FileChangeLedger] =
    Ref.of[IO, Map[String, Vector[FileChangeEvent]]](Map.empty).map(new FileChangeLedger(_))

  /**
   * Attribution keys for one session: the session id always, plus the flow node
   * id and task id when the engine injected them. Order is irrelevant (bucket
   * set membership is all that matters).
   */
  def keysFor(sessionId: Option[String], flowNodeId: Option[String], taskId: Option[String]): List[String] =
    List(sessionId, flowNodeId, taskId).flatten.distinct

  /**
   * Parse a Write/Edit/MultiEdit success result string into an event.
   *
   * The result contract is DiffUtil's: `OK:CREATED <name>, N lines` or
   * `OK:UPDATED <name>, A added, R removed`, followed by the unified diff body.
   * Any reminder text a caller appended AFTER this string was formed stays in
   * the tail; the parser stops at the first line that is not a hunk/plus/minus/
   * context line, so reminder contamination cannot corrupt the diff body.
   * Returns None when the string matches neither prefix (defensive — callers
   * only invoke this on the success path).
   */
  def parseToolResult(tool: String, path: String, result: String, now: Long): Option[FileChangeEvent] =
    val firstBreak = result.indexOf('\n')
    val header = if firstBreak < 0 then result else result.substring(0, firstBreak)
    val rawBody = if firstBreak < 0 then "" else result.substring(firstBreak + 1)
    // Cut at the first line that is not diff-shaped so any tail a caller
    // appended after the result was formed (system reminders) cannot leak into
    // the stored body or the counts.
    val body = rawBody.linesIterator.takeWhile(isDiffLine).toList.mkString("\n")
    if header.startsWith(DiffUtil.OkCreatedPrefix) then
      val (diff, degraded) = capBody(body)
      val added = countLines(body, '+')
      Some(
        FileChangeEvent(path, FileChangeType.Added, added, 0, diff, degraded, tool, now)
      )
    else if header.startsWith(DiffUtil.OkUpdatedPrefix) then
      val (diff, degraded) = capBody(body)
      // Counts come from the header stats (authoritative) when parseable, with
      // a body recount as fallback for hand-formed results.
      val (added, removed) =
        DiffUtil.parseUpdatedStats(header).getOrElse((countLines(body, '+'), countLines(body, '-')))
      Some(
        FileChangeEvent(path, FileChangeType.Modified, added, removed, diff, degraded, tool, now)
      )
    else None
    end if
  end parseToolResult

  private def isDiffLine(l: String): Boolean =
    l.isEmpty || l.startsWith("@@") || l.startsWith(" ") || l.startsWith("+") || l.startsWith("-")

  /** Bodies ship verbatim (2026-10-10 ruling): empty body = no diff (counts-only,
    * no reason — nothing was dropped), otherwise the full body is kept. */
  private def capBody(body: String): (Option[String], Option[String]) =
    if body.isEmpty then (None, None) else (Some(body), None)

  /** Count diff payload lines with the given marker, stopping at non-diff lines. */
  private def countLines(body: String, marker: Char): Int =
    body.linesIterator
      .takeWhile(l => l.isEmpty || l.startsWith("@@") || l.startsWith(" ") || l.startsWith("+") || l.startsWith("-"))
      .count(l => l.startsWith(marker.toString) && !l.startsWith(marker.toString * 3))

  /**
   * Coalesce a session's events into one per path (delivery is per-file):
   * changeType escalates to "added" when any event created the file; counts sum
   * across events; diff bodies join in write order (an edit journal — each
   * event's hunks render independently). Joins keep the full merged body (no
   * capture cap, 2026-10-10 ruling); a join over two None bodies stays None.
   * Input order is preserved (first appearance), matching an ascending timeline.
   */
  def coalesce(events: List[FileChangeEvent]): List[FileChangeEvent] =
    if events.isEmpty then Nil
    else
      // Events arrive in write order; merging into a LinkedHashMap keyed by path
      // preserves first-appearance order (ascending timeline per path).
      val merged = scala.collection.mutable.LinkedHashMap.empty[String, FileChangeEvent]
      events.foreach { e =>
        merged.get(e.path) match
          case None => merged.put(e.path, e)
          case Some(prev) =>
            val diffJoin = (prev.diffText, e.diffText) match
              case (Some(a), Some(b)) => Some(s"$a\n$b")
              case (Some(a), None) => Some(a)
              case (None, Some(b)) => Some(b)
              case (None, None) => None
            val diffText = diffJoin
            val degradedFinal =
              if diffText.isEmpty then
                prev.degradedReason.orElse(e.degradedReason)
              else None
            merged.put(
              e.path,
              prev.copy(
                changeType =
                  if prev.changeType == FileChangeType.Added || e.changeType == FileChangeType.Added then
                    FileChangeType.Added
                  else FileChangeType.Modified,
                addedLines = prev.addedLines + e.addedLines,
                removedLines = prev.removedLines + e.removedLines,
                diffText = diffText,
                degradedReason = degradedFinal,
                sourceTool = e.sourceTool,
                timestamp = math.max(prev.timestamp, e.timestamp)
              )
            )
      }
      merged.values.toList
  end coalesce

end FileChangeLedger
