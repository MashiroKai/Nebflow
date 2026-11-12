package nebflow.core.delegate

import cats.effect.IO
import nebflow.core.GitWorktrees
import nebflow.core.delegate.WorkspaceLedger.*

/**
 * Explicit, root-confirmed reconciliation of exactly one registered workspace.
 *
 * Two durable stores are touched (workspace ledger + delegate registry) and Git
 * is external to both — no cross-store atomicity is claimed. Ordering makes a
 * partial failure honest instead of false-finished:
 *
 *   1. all validation (identity, revision, occupancy, Git clean/HEAD/branch)
 *      happens BEFORE any write;
 *   2. the ledger transaction commits the completion + corrected branch under
 *      the ledger file lock, re-checking identity and pinning the exact HEAD
 *      inside the lock;
 *   3. only after the ledger commit is the registry report slot synced;
 *   4. a retry of the same operationId replays: a completed confirmation is
 *      re-synced into the registry (idempotent) and returned unchanged — a
 *      crash between 2 and 3 heals on retry, and no path can mark the registry
 *      finished while the ledger never completed.
 */
final class WorkspaceReconcile(
  ledger: WorkspaceLedger,
  occupancy: WorkspaceOccupancy,
  registry: (String, String, String) => Either[String, Unit] =
    (owner, operationId, receipt) => DelegateRegistry.confirmWorkspaceCompletion(owner, operationId, receipt),
  now: () => Long = () => System.currentTimeMillis()
):
  private sealed trait Plan
  private case class ReplayPlan(existing: CompletionConfirmation) extends Plan
  private case class FreshPlan(workspace: Workspace) extends Plan

  def confirm(operation: CompletionConfirmation): IO[Either[String, CompletionConfirmation]] =
    IO.blocking(plan(operation))
      .flatMap {
        case Left(error) => IO.pure(Left(error))
        case Right(ReplayPlan(existing)) => IO.blocking(syncRegistry(existing)).map(_.map(_ => existing))
        case Right(FreshPlan(workspace)) => fresh(operation, workspace)
      }
      .handleError(e => Left(s"Workspace reconcile failed: ${Option(e.getMessage).getOrElse(e.getClass.getSimpleName)}"))

  /** Read-only classification: field validity, replay vs fresh, preflight identity/revision. */
  private def plan(operation: CompletionConfirmation): Either[String, Plan] =
    if !fieldsValid(operation) then Left("Missing or invalid structured confirmation fields")
    else
      val snapshot = ledger.snapshot
      snapshot.completionConfirmations.get(operation.operationId) match
        case Some(existing) if existing.stage == "completed" =>
          if sameIdentity(existing, operation) then Right(ReplayPlan(existing))
          else Left("Operation id conflicts with its recorded confirmation")
        case _ =>
          snapshot.workspaces.find(_.id == operation.workspaceId) match
            case None => Left("Unknown workspace")
            case Some(w) if w.owner != operation.owner || w.path != operation.expectedPath =>
              Left("Workspace owner or exact path mismatch")
            case Some(w) if w.revision != operation.expectedRevision => Left("Stale workspace revision")
            case Some(w) => Right(FreshPlan(w))

  /** Fresh path: occupancy → Git verify → occupancy re-check → locked ledger commit → registry sync. */
  private def fresh(operation: CompletionConfirmation, workspace: Workspace): IO[Either[String, CompletionConfirmation]] =
    occupancyGate(workspace.owner).flatMap {
      case Left(error) => IO.pure(Left(error))
      case Right(()) => IO.blocking(verifyGit(operation, workspace)).flatMap {
        case Left(error) => IO.pure(Left(error))
        case Right((realPath, branch, pinnedHead)) => occupancyGate(workspace.owner).flatMap {
          case Left(error) => IO.pure(Left(error))
          case Right(()) => IO.blocking {
            val confirmed = operation.copy(actualBranch = Some(branch), recordedAt = now(), stage = "completed")
            ledger.reconcileCompletion(confirmed, current =>
              if current.path != realPath || current.owner != operation.owner then Left("Workspace identity changed")
              else
                val currentHead = git1(realPath, "rev-parse", "--verify", "HEAD^{commit}")
                val currentStatus = git1(realPath, "status", "--porcelain", "--untracked-files=all")
                if currentHead != pinnedHead then Left("Workspace HEAD changed during reconciliation")
                else if currentStatus.nonEmpty then Left("Workspace became dirty during reconciliation")
                else Right(branch)
            ) match
              case Left(error) => Left(error)
              case Right(done) => syncRegistry(done).map(_ => done)
          }
        }
      }
    }

  /** Fail-closed live occupancy; busy and unreadable both refuse. */
  private def occupancyGate(owner: String): IO[Either[String, Unit]] =
    occupancy.occupancyOf(owner).attempt.map {
      case Left(error) => Left(s"Occupancy unknown: ${Option(error.getMessage).getOrElse(error.getClass.getSimpleName)}")
      case Right(Left(error)) => Left(error)
      case Right(Right(busy)) if busy.nonEmpty => Left(s"Owner is live/busy: ${busy.mkString("; ")}")
      case Right(Right(_)) => Right(())
    }

  /** Git identity checks OUTSIDE the lock (preflight); the transaction re-checks and pins. */
  private def verifyGit(operation: CompletionConfirmation, w: Workspace): Either[String, (String, String, String)] =
    val path = os.Path(operation.expectedPath)
    val realPath = path.toNIO.toRealPath().toString
    if realPath != w.path then Left("Workspace path resolves differently from the registered path")
    else if !GitWorktrees.isGitRepo(path) then Left("Registered workspace is not a Git worktree")
    else if os.proc("git", "-C", path.toString, "rev-parse", "--show-toplevel")
      .call(stdout = os.Pipe, stderr = os.Pipe).out.text().trim != realPath
    then Left("Registered path is not the Git worktree root")
    else if WorkspaceLedger.repositoryKey(path) != w.repository then
      Left("Workspace Git repository identity does not match its ledger")
    else
      val porcelain = git1(realPath, "status", "--porcelain", "--untracked-files=all")
      val branch = git1(realPath, "branch", "--show-current")
      val head = git1(realPath, "rev-parse", "--verify", "HEAD^{commit}")
      if porcelain.nonEmpty then Left("Workspace has tracked or untracked changes")
      else if head != operation.expectedHead then Left("Workspace HEAD does not match expectedHead")
      else if branch.isEmpty || branch.startsWith("(HEAD") then Left("Workspace is detached or has no branch")
      else Right((realPath, branch, head))

  private def git1(cwd: String, args: String*): String =
    os.proc(Seq("git", "-C", cwd) ++ args).call(stdout = os.Pipe, stderr = os.Pipe).out.text().trim

  /** Idempotent registry sync; deterministic receipt text so replays converge. */
  private def syncRegistry(c: CompletionConfirmation): Either[String, Unit] =
    registry(c.owner, c.operationId, receiptOf(c))

  private def receiptOf(c: CompletionConfirmation): String =
    s"workspace-reconcile ${c.operationId} owner=${c.owner} workspace=${c.workspaceId} path=${c.expectedPath} " +
      s"head=${c.expectedHead} branch=${c.actualBranch.getOrElse("?")} evidence=${c.acceptanceEvidence} confirmedAt=${c.recordedAt}"

  private def sameIdentity(a: CompletionConfirmation, b: CompletionConfirmation): Boolean =
    a.workspaceId == b.workspaceId && a.owner == b.owner && a.expectedPath == b.expectedPath &&
      a.expectedHead == b.expectedHead && a.expectedRevision == b.expectedRevision &&
      a.acceptanceEvidence == b.acceptanceEvidence && a.explicitRootConfirmation == b.explicitRootConfirmation

  private def fieldsValid(operation: CompletionConfirmation): Boolean =
    operation.operationId.trim.nonEmpty && operation.operationId.length <= 200 &&
      operation.workspaceId.trim.nonEmpty && operation.owner.trim.nonEmpty &&
      operation.expectedPath.trim.nonEmpty && operation.acceptanceEvidence.trim.nonEmpty &&
      operation.explicitRootConfirmation.trim.nonEmpty &&
      operation.expectedHead.matches("[0-9a-f]{40}|[0-9a-f]{64}")
end WorkspaceReconcile

object WorkspaceReconcile:
  def default: WorkspaceReconcile = new WorkspaceReconcile(WorkspaceLedger.default, WorkspaceOccupancy.default)
