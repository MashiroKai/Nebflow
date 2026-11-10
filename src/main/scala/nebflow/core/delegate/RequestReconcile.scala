package nebflow.core.delegate

import WorkspaceLedger.*
import io.circe.generic.semiauto.deriveEncoder

import scala.util.Try

/**
 * Read-only startup reconciliation for in-flight merge requests. Inspects the
 * actual Git state of every non-terminal request item and classifies it:
 *
 *   - `integrated` — the recorded source is already contained in the recorded
 *     target branch. When the flow's own `--no-ff` two-parent merge commit can
 *     be located (second parent == recorded source) it is recovered as the
 *     item's merge commit (`integration=merge-commit`); otherwise the source
 *     was integrated externally (cherry-pick, squash, fast-forward) and the
 *     reading is `integration=source-ancestor` with no merge commit.
 *   - `resumable` — not integrated, workspace intact: the merger worker can
 *     run it from the rest state.
 *   - `unknown` — workspace directory missing, branch gone, or Git unreadable.
 *
 * Reconciliation never writes the ledger and never writes Git; it produces a
 * structured reading only. A per-task merger instance consumes these
 * readings: an integrated item closes out with its recovered facts, a
 * resumable item is executed from the rest state, and an unknown item rests
 * until a readable state returns.
 */
object RequestReconcile:

  final case class ItemReading(
    workspaceId: String,
    phase: String,
    reading: String,
    integration: Option[String],
    mergeCommit: Option[String],
    detail: String
  )
  given io.circe.Encoder[ItemReading] = deriveEncoder

  final case class RequestReading(requestId: String, status: String, items: List[ItemReading])
  given io.circe.Encoder[RequestReading] = deriveEncoder

  private val Integrated = "integrated"
  private val Resumable = "resumable"
  private val Unknown = "unknown"

  /** Read one request's non-terminal items against the actual Git state. */
  def request(request: MergeRequest, workspaces: List[Workspace]): RequestReading =
    val items = request.items
      .filterNot(i => i.phase == "completed" || i.phase == "cancelled")
      .map(i => readItem(request.repository, workspaces.find(_.id == i.workspaceId), i))
    RequestReading(request.id, request.status, items)

  /** Every request not yet terminal for the merger with the given identity. */
  def forMerger(mergerId: String, ledger: WorkspaceLedger = WorkspaceLedger.default): List[RequestReading] =
    val snapshot = ledger.snapshot
    snapshot.requests
      .filter(r => r.mergerId == mergerId && !TerminalRequestStatuses.contains(r.status))
      .map(r => request(r, snapshot.workspaces))

  /** Read-only classification of one item; any Git or filesystem failure reads as unknown. */
  def readItem(repository: String, workspace: Option[Workspace], item: MergeItem): ItemReading =
    def unknown(detail: String) = ItemReading(item.workspaceId, item.phase, Unknown, None, None, detail)
    def gitRc(args: String*): Int = Try {
      os.proc(Seq("git", "--git-dir", repository) ++ args)
        .call(check = false, stdout = os.Pipe, stderr = os.Pipe)
        .exitCode
    }.getOrElse(2)
    def gitText(args: String*): String = Try {
      os.proc(Seq("git", "--git-dir", repository) ++ args).call(stdout = os.Pipe, stderr = os.Pipe).out.text().trim
    }.getOrElse("")
    val integrated: Option[ItemReading] =
      if gitRc("rev-parse", "--verify", s"refs/heads/${item.targetBranch}^{commit}") != 0 then
        Some(unknown(s"Target branch ${item.targetBranch} is unreadable or gone"))
      else
        gitRc("merge-base", "--is-ancestor", item.sourceCommit, s"refs/heads/${item.targetBranch}") match
          case rc if rc >= 2 => Some(unknown("Cannot read repository ancestry"))
          case 0 =>
            // Locate this flow's own --no-ff merge: a two-parent merge commit on the
            // target whose second parent is the recorded source.
            val recovered = gitText("log", "--merges", "--format=%H %P", s"refs/heads/${item.targetBranch}")
              .linesIterator
              .map(_.trim.split(" ").toList)
              .collectFirst { case commit :: _ :: second :: Nil if second == item.sourceCommit => commit }
            Some(
              recovered match
                case Some(commit) =>
                  ItemReading(
                    item.workspaceId,
                    item.phase,
                    Integrated,
                    Some("merge-commit"),
                    Some(commit),
                    s"Source is contained in ${item.targetBranch}; recovered merge commit $commit"
                  )
                case None =>
                  ItemReading(
                    item.workspaceId,
                    item.phase,
                    Integrated,
                    Some("source-ancestor"),
                    None,
                    s"Source is contained in ${item.targetBranch} without a two-parent merge commit (externally integrated)"
                  )
            )
          case _ => None // rc == 1: the source is not contained in the target
    end integrated

    integrated match
      case Some(reading) => reading
      case None =>
        workspace match
          case None => unknown("Workspace record is gone")
          case Some(w) =>
            val directory = Try(java.nio.file.Files.exists(java.nio.file.Path.of(w.path))).getOrElse(false)
            val branch = gitRc("rev-parse", "--verify", s"refs/heads/${w.branch}^{commit}")
            if directory && branch == 0 then
              ItemReading(
                item.workspaceId,
                item.phase,
                Resumable,
                None,
                None,
                "Workspace intact and source not contained in the target; resume the request manually"
              )
            else if !directory then unknown("Workspace directory is missing")
            else unknown("Workspace branch is gone")
          end match
    end match
  end readItem

end RequestReconcile
