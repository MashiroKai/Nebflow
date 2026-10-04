package nebflow.core.tools

import nebflow.shared.MemoryBudget
import nebflow.shared.MemoryPaths
import nebflow.shared.MemoryWriteGate

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap
import scala.util.Try

/**
 * MemoryDirectWriteGuard — budget pre-check + over-budget system-reminder for
 * the direct-write memory form (govmemory batch 2026-09-25, author ruling (e)①:
 * 写前检查 + 超限即时提醒 + 24h 防骚扰).
 *
 * Since the MemoryNote tool and the memory queue are retired, memory is written
 * by agents directly with Edit/Write on the three memory layers. This guard
 * gives that path the same budget discipline the old single-point write face
 * had, WITHOUT becoming a second write gate:
 *
 *   - [[preCheck]] (read-only, runs before the write): classifies the target
 *     path against the three memory layers; if the projected content would push
 *     the file past its hard cap AND the write is net growth, the write is
 *     refused with a structured error (zero file writes, [[MemoryBudget]]
 *     constants only — no new thresholds). A write that shrinks the file stays
 *     exempt: shrinking is the self-rescue channel, and the byte-ratio judgment
 *     is single-sourced in [[MemoryWriteGate.shrinkExempt]].
 *   - [[postWriteReminder]] (runs after a successful write): if the file now
 *     sits over its 80% soft line, returns a `<system-reminder>` block that the
 *     caller appends to the tool result (same form as the engine's other
 *     system-reminders; hard-cap variant when the file is over the cap).
 *   - 24h anti-harassment: at most one reminder per file per 24h window
 *     (in-memory, process lifetime — a restart resets it; the injection-side
 *     hygiene notice stays the per-lifecycle channel).
 *
 * Scope notes:
 *   - Only the three budgeted layers are classified (user `~/.nebflow/User.md`,
 *     agent `~/.nebflow/agents/Nebula/memory.md`, project
 *     `<workspace>/.nebflow/memory.md`). Detail files (`~/.nebflow/memory/<id>.md`)
 *     and other agents' memory files are outside the budget whitelist (same
 *     scope as the retired bookkeeping tool's targets).
 *   - Bash writes remain structurally unguarded (completeness argument
 *     unchanged; documented in MemoryWriteGate's boundary note).
 */
object MemoryDirectWriteGuard:

  /** Anti-harassment window (same key = same file path). */
  private val SuppressionWindowMs: Long = 24L * 60 * 60 * 1000

  private val lastRemindedAt = new ConcurrentHashMap[String, Long]()

  /** Classify a path against the three budgeted memory layers.
    * `Some("user" | "agent" | "project")` = budgeted layer; `None` = not guarded.
    *
    * personal-agent 批 2026-10-04：`"agent"` 层从 `agents/<root>/memory.md` 上收到
    * 根层 `<root>/Soul.md`（与 `User.md` 同级）。**双读过渡**⇒两处都判 `"agent"`
    * （旧位仍可被直写，归档期不能失去预算保护）。路径基见
    * [[nebflow.shared.MemoryPaths.globalBases]]——并认运行时数据根与默认 home 根，
    * 使层判定与进程内换根顺序解耦（此前只认运行时根 ⇒ 被不还原的 setDataRoot
    * 的相邻 suite 打成全 `None`，闸门 fail-open + 顺序相关假红）。
    */
  def classify(pathStr: String): Option[String] =
    val normalized = MemoryPaths.normalizePath(pathStr)
    val bases = MemoryPaths.globalBases
    val rootName = nebflow.actor.RootAgentIdentity.Name
    if bases.exists(b => normalized == s"$b/User.md") then Some("user")
    else if bases.exists(b => normalized == s"$b/Soul.md") then Some("agent")
    else if bases.exists(b => normalized == s"$b/agents/$rootName/memory.md") then Some("agent")
    else if normalized.endsWith("/.nebflow/memory.md")
      && !MemoryPaths.isUnderGlobalBase(normalized, bases) then Some("project")
    else None

  /** Current file size; missing/unreadable ⇒ 0 (first write ⇒ anything is net
    * growth, matching MemoryWriteGate.preSizeBytes semantics). Read-only. */
  private def preSizeBytes(pathStr: String): Long =
    Try(if Files.exists(Paths.get(pathStr)) then Files.size(Paths.get(pathStr)).toLong else 0L)
      .getOrElse(0L)

  /** Budget pre-check (read-only, zero file writes). `Left` = refuse with a
    * structured error (caller must not write); `Right` = allowed.
    *
    * `projectedContent` = the full file content this write would produce (Write:
    * the new content; Edit: the updated buffer). Refusal only fires when the
    * projected size is net growth past the hard cap — shrinkExempt (byte ratio,
    * single-sourced) is consulted first so the self-rescue path stays open. */
  def preCheck(pathStr: String, projectedContent: String): Either[ToolError, Unit] =
    classify(pathStr) match
      case None => Right(())
      case Some(target) =>
        val projected = projectedContent.getBytes(StandardCharsets.UTF_8).length.toLong
        val exempt = MemoryWriteGate.shrinkExempt(preSizeBytes(pathStr), projected, shrinkChannel = true)
        if exempt then Right(())
        else
          MemoryBudget.verdict(target, projected) match
            case MemoryBudget.Exceeded(_, _) =>
              Left(ToolError(MemoryBudget.exceededMessage("write", target, pathStr, projected, projectedContent)))
            case MemoryBudget.Warn(_, _, _) => Right(())
            case MemoryBudget.Within        => Right(())

  /** Post-write reminder text (""): empty = no reminder (not a budgeted layer,
    * within budget, or suppressed by the 24h window). Non-empty = a
    * `<system-reminder>` block the caller appends to the tool result.
    * Reads the file size from disk (POST-WRITE state). */
  def postWriteReminder(pathStr: String, nowMs: Long = System.currentTimeMillis()): String =
    classify(pathStr) match
      case None => ""
      case Some(target) =>
        val (soft, hard) = target match
          case "user"    => (MemoryBudget.UserSoftBytes, MemoryBudget.UserHardBytes)
          case "agent"   => (MemoryBudget.AgentSoftBytes, MemoryBudget.AgentHardBytes)
          case "project" => (MemoryBudget.ProjectSoftBytes, MemoryBudget.ProjectHardBytes)
          case other     => (-1L, -1L) // unreachable: classify only yields the three
        val bytes = preSizeBytes(pathStr)
        if bytes <= soft then ""
        else
          val key = s"$target:$pathStr"
          val last = Option(lastRemindedAt.get(key)).getOrElse(0L)
          if nowMs - last < SuppressionWindowMs then ""
          else
            lastRemindedAt.put(key, nowMs)
            reminderText(pathStr, bytes, soft, hard)

  /** Reminder copy (§4.3 drafts: soft-line variant / hard-cap variant). */
  private def reminderText(pathStr: String, bytes: Long, soft: Long, hard: Long): String =
    val pct = if hard > 0 then f"${bytes * 100.0 / hard}%.0f%%" else "?"
    if bytes > hard then
      s"""<system-reminder>
         |Memory budget HARD CAP: $pathStr is at $bytes bytes, over the $hard-byte hard cap. Writes that grow this layer are refused. Shrink first (rewrite the largest `## ` section down, delete stale `- ` entries), snapshot to `~/.nebflow/memory-backups/<ts>/` before editing, then retry the write.
         |</system-reminder>"""
    else
      s"""<system-reminder>
         |Memory budget: $pathStr is at $bytes bytes ($pct% of the $hard-byte hard cap; soft line $soft).
         |Consolidate this turn: trim stale batch-status sections and superseded rulings first (replace in place, never append a correction beside the old line), or demote detail into `~/.nebflow/memory/<id>.md` files. Further writes past the hard cap will be refused until the file is back under budget.
         |</system-reminder>"""

  /** Test hook: clear the suppression ledger (spec only; production code must
    * not call this). */
  private[tools] def resetForTest(): Unit = lastRemindedAt.clear()

end MemoryDirectWriteGuard
