package nebflow.core.delegate

import cats.effect.IO
import nebflow.core.GitWorktrees
import nebflow.core.delegate.WorkspaceLedger.*

/** Explicit, root-confirmed reconciliation of exactly one registered workspace. */
final class WorkspaceReconcile(
  ledger: WorkspaceLedger,
  occupancy: WorkspaceOccupancy,
  now: () => Long = () => System.currentTimeMillis()
):
  def confirm(operation: CompletionConfirmation): IO[Either[String, CompletionConfirmation]] =
    if operation.operationId.trim.isEmpty || operation.operationId.length > 200 ||
      operation.workspaceId.trim.isEmpty || operation.owner.trim.isEmpty ||
      operation.expectedPath.trim.isEmpty || operation.acceptanceEvidence.trim.isEmpty ||
      operation.explicitRootConfirmation.trim.isEmpty ||
      !operation.expectedHead.matches("[0-9a-f]{40}|[0-9a-f]{64}") then
      IO.pure(Left("Missing or invalid structured confirmation fields"))
    else
      IO.blocking(ledger.snapshot).flatMap { snapshot =>
        snapshot.completionConfirmations.get(operation.operationId) match
          case Some(_) => IO.blocking(ledger.reconcileCompletion(operation, _ => Left("Idempotent replay")))
          case None => IO.pure(snapshot.workspaces.find(_.id == operation.workspaceId)).flatMap {
            case None => IO.pure(Left("Unknown workspace"))
            case Some(w) if w.owner != operation.owner || w.path != operation.expectedPath =>
              IO.pure(Left("Workspace owner or exact path mismatch"))
            case Some(w) if w.revision != operation.expectedRevision =>
              IO.pure(Left("Stale workspace revision"))
            case Some(w) =>
              occupancy.occupancyOf(w.owner).flatMap {
                case Left(error) => IO.pure(Left(error))
                case Right(busy) if busy.nonEmpty => IO.pure(Left(s"Owner is live/busy: ${busy.mkString("; ")}"))
                case Right(_) => IO.blocking {
                  val path = os.Path(operation.expectedPath)
                  val realPath = path.toNIO.toRealPath().toString
                  if realPath != w.path then Left("Workspace path resolves differently from the registered path")
                  else if !GitWorktrees.isGitRepo(path) then Left("Registered workspace is not a Git worktree")
                  else if os.proc("git", "-C", path.toString, "rev-parse", "--show-toplevel")
                    .call(stdout = os.Pipe, stderr = os.Pipe).out.text().trim != realPath
                  then Left("Registered path is not the Git worktree root")
                  else if WorkspaceLedger.repositoryKey(path) != w.repository then Left("Workspace Git repository identity does not match its ledger")
                  else
                    val porcelain = os.proc("git", "-C", path.toString, "status", "--porcelain", "--untracked-files=all")
                      .call(stdout = os.Pipe, stderr = os.Pipe).out.text().trim
                    val branch = os.proc("git", "-C", path.toString, "branch", "--show-current")
                      .call(stdout = os.Pipe, stderr = os.Pipe).out.text().trim
                    val head = os.proc("git", "-C", path.toString, "rev-parse", "--verify", "HEAD^{commit}")
                      .call(stdout = os.Pipe, stderr = os.Pipe).out.text().trim
                    if porcelain.nonEmpty then Left("Workspace has tracked or untracked changes")
                    else if head != operation.expectedHead then Left("Workspace HEAD does not match expectedHead")
                    else if branch.isEmpty || branch.startsWith("(HEAD") then Left("Workspace is detached or has no branch")
                    else
                      val confirmed = operation.copy(actualBranch = Some(branch), recordedAt = now())
                      ledger.reconcileCompletion(confirmed, current =>
                        if current.path != realPath || current.owner != operation.owner then Left("Workspace identity changed")
                        else
                          val currentHead = os.proc("git", "-C", realPath, "rev-parse", "--verify", "HEAD^{commit}")
                            .call(stdout = os.Pipe, stderr = os.Pipe).out.text().trim
                          val currentStatus = os.proc("git", "-C", realPath, "status", "--porcelain", "--untracked-files=all")
                            .call(stdout = os.Pipe, stderr = os.Pipe).out.text().trim
                          if currentHead != operation.expectedHead then Left("Workspace HEAD changed during reconciliation")
                          else if currentStatus.nonEmpty then Left("Workspace became dirty during reconciliation")
                          else Right(branch)
                      )
                  }.handleError(e => Left(s"Workspace verification failed: ${Option(e.getMessage).getOrElse(e.getClass.getSimpleName)}"))
              }
          }
        }
end WorkspaceReconcile

object WorkspaceReconcile:
  def default: WorkspaceReconcile = new WorkspaceReconcile(WorkspaceLedger.default, WorkspaceOccupancy.default)
