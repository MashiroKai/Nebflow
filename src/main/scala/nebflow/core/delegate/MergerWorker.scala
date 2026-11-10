package nebflow.core.delegate

import cats.effect.IO
import cats.effect.unsafe.implicits.global

import io.circe.Json
import io.circe.syntax.*

import nebflow.core.GitWorktrees
import nebflow.shared.NebflowLogger

import WorkspaceLedger.{MergeItem, MergeReport, Workspace}

import java.time.Instant

import scala.util.Try

/**
 * Per-task merger instances: the mechanical consumer of persisted merge
 * requests. No merger stays resident between tasks — every merge request is
 * consumed by a freshly instantiated worker, and when one task has been merged
 * the next request starts from a brand-new instance.
 *
 * One instance claims exactly one request (ledger `claimNextMerge` enforces
 * the serial rule), then executes every item without any LLM session: reconcile the
 * item against actual Git state, merge with plumbing only (`merge-tree
 * --write-tree` -> `commit-tree` -> `update-ref` compare-and-swap, all under
 * the repository write lock), verify with ancestry facts, write evidence under
 * the main working tree's `.nebflow/evidence`, remove the worktree and delete
 * the merged branch. Every transition is reported through
 * `WorkspaceLedger.reportMerge`, which validates the same Git facts a live
 * merger session had to prove. Work already integrated is never undone; a
 * merge that already landed but lost its session resumes at boot
 * (`recoverRequests`) and closes out instead of merging twice.
 *
 * Instantiation points: a merge request submission or cancellation, and boot
 * recovery. A wake spawns one consumer that instantiates a fresh worker per
 * claimable request and exits when the queue is empty; overlapping wakes are
 * harmless because surplus instances find nothing claimable and exit
 * immediately.
 *
 * A merger never pushes, never tags, and never touches versions: it moves
 * only the development branch and target branch recorded in an authorized
 * request.
 */
object MergerWorker:

  private val logger = NebflowLogger.forName("nebflow.core.delegate.merger-worker")

  /** Commit identity applied only when the repository has none configured. */
  private val FallbackCommitterName = "nebflow-merger"
  private val FallbackCommitterEmail = "merger@nebflow.local"

  /** Queued request statuses a fresh instance may claim. */
  private val Claimable = Set("submitted", "accepted")

  private def hasClaimable: Boolean =
    WorkspaceLedger.default.snapshot.requests.exists(r => Claimable.contains(r.status))

  private def describe(e: Throwable): String = Option(e.getMessage).getOrElse(e.toString)

  /**
   * Per-task wake: run one consumer that instantiates a fresh merger per
   * claimable request. Redundant wakes are safe — the ledger claim keeps
   * execution serial and surplus consumers exit immediately.
   */
  def wake(): IO[Unit] = consume.start.void

  /**
   * Boot face: reconcile in-flight requests against actual Git state, then
   * wake per-task consumption for whatever re-queued. Recovery is fail-soft:
   * a failed pass is logged and queued requests still wake.
   */
  def recoverAndWake(): IO[Unit] =
    IO.blocking(WorkspaceLedger.default.recoverRequests())
      .handleErrorWith(e => logger.warn(s"merger recovery failed (queued requests still wake): ${describe(e)}")) *>
      wake()

  /**
   * Direct face for tests and tools: claim and drive at most one request with
   * a fresh instance. Returns a pass summary.
   */
  def runOnce(): IO[Option[String]] = new MergerWorker().runOnce()

  /** One fresh instance per claimable request; the consumer exits on an empty queue. */
  private def consume: IO[Unit] =
    IO.defer {
      if !hasClaimable then IO.unit
      else
        runOnce()
          .flatMap {
            case Some(_) => consume
            case None    => IO.unit
          }
          .handleErrorWith(e =>
            logger.warn(s"merger worker pass failed (the queue wakes on the next request): ${describe(e)}")
          )
    }

end MergerWorker

/**
 * One per-task merger instance: claims exactly one request and drives it to a
 * resting state. Holds nothing between tasks — every fact lives in the ledger,
 * so the next request starts from a clean instance.
 */
final class MergerWorker:

  /** Blocking: claim and drive at most one request. Returns a pass summary. */
  def runOnce(): IO[Option[String]] = IO.blocking {
    WorkspaceLedger.default.claimNextMerge() match
      case None => None
      case Some(id) =>
        val outcome = Try(runRequest(id)).toEither.left
          .map(e => Option(e.getMessage).getOrElse(e.toString))
        outcome.fold(
          error => {
            warn(s"merge request $id failed unexpectedly: $error")
            abandon(id, error)
            Some(s"request $id abandoned: $error")
          },
          summary => Some(s"request $id: $summary")
        )
  }

  /** Blocking: drive one claimed request item by item to a resting state. */
  private def runRequest(id: String): String =
    val ledger = WorkspaceLedger.default
    var outcome = "done"
    var continue = true
    while continue do
      ledger.snapshot.requests.find(_.id == id) match
        case None =>
          outcome = "record vanished"
          continue = false
        case Some(r) if r.cancelRequested =>
          releaseCancelled(id, r.mergerId)
          outcome = "cancelled"
          continue = false
        case Some(r) if !Set("claimed", "verifying").contains(r.status) =>
          outcome = r.status
          continue = false
        case Some(r) =>
          r.items.find(i => i.phase == "reserved" || i.phase == "merged-cleanup-pending") match
            case None =>
              outcome = r.status
              continue = false
            case Some(item) if item.phase == "merged-cleanup-pending" =>
              // A previous pass integrated the source but did not finish the
              // closure (crash or cleanup refusal); resume without re-beginning.
              resumeClosure(ledger, id, r.mergerId, r.repository, item) match
                case Left(error) =>
                  warn(s"merge request $id closure resume deferred: $error")
                  outcome = "closure pending"
                  continue = false
                case Right(_) => () // next item on the next loop turn
            case Some(item) =>
              ledger.beginItem(id, r.mergerId, item.workspaceId) match
                case Left(error) =>
                  warn(s"merge request $id begin refused: $error")
                  outcome = s"begin refused: $error"
                  continue = false
                case Right(_) =>
                  runItem(ledger, id, r.mergerId, r.repository, item) match
                    case Left(reason) =>
                      outcome = reason
                      continue = false
                    case Right(_) => () // next item on the next loop turn
    outcome
  end runRequest

  /**
   * One item from the rest state: integrate (or prove an existing integration),
   * verify, write evidence, clean up, complete — or report a failed item and
   * release the rest of the request. A Left is the item's final reason: either
   * a reported failure, or a resting state deferred to boot recovery.
   */
  private def runItem(
    ledger: WorkspaceLedger,
    id: String,
    mergerId: String,
    repository: String,
    item: MergeItem
  ): Either[String, Unit] =
    ledger.snapshot.workspaces.find(_.id == item.workspaceId) match
      case None => Left(s"Workspace ${item.workspaceId} record is gone; the item rests for boot recovery")
      case Some(ws) =>
        val reading = RequestReconcile.readItem(repository, Some(ws), item)
        val targetReadable =
          GitWorktrees.resolveRef(os.Path(ws.path), s"refs/heads/${item.targetBranch}").isDefined
        reading.reading match
          case "integrated" =>
            // Fast path: the source already reached the target (recovered merge
            // commit or contained source). Never merge again; close out.
            integrate(ledger, id, mergerId, repository, ws, item, reading.mergeCommit, reading.integration)
          case "resumable" if targetReadable =>
            mergeLeg(repository, ws, item) match
              case Left(failure) => failItem(ledger, id, mergerId, ws, item, failure)
              case Right(commit) =>
                integrate(ledger, id, mergerId, repository, ws, item, Some(commit), Some("merge-commit"))
          case _ if !targetReadable =>
            // Repo unreadable (target gone or Git broken): a failed report
            // cannot pass fact validation either. Rest until boot recovery.
            Left(s"${reading.detail}; the item rests for boot recovery")
          case _ =>
            // Readable repo, but the workspace side is unreconcilable (missing
            // directory or branch): fail cleanly with the reconcile detail.
            failItem(ledger, id, mergerId, ws, item, reading.detail)
    end match
  end runItem

  /** Record the integration facts, then run evidence, cleanup, and completion. */
  private def integrate(
    ledger: WorkspaceLedger,
    id: String,
    mergerId: String,
    repository: String,
    ws: Workspace,
    item: MergeItem,
    mergeCommit: Option[String],
    integration: Option[String]
  ): Either[String, Unit] =
    ledger.snapshot.requests.find(_.id == id) match
      case None => Left("Request record is gone; the item rests for boot recovery")
      case Some(r) =>
        report(ledger, id, mergerId, r.revision, ws, "merged-cleanup-pending", mergeCommit, integration = integration) match
          case Left(error) => Left(s"integration report refused: $error")
          case Right(_) =>
            writeEvidence(repository, id, ws, item, mergeCommit) match
              case Left(error) =>
                Left(s"evidence unavailable ($error); the item rests merged-cleanup-pending")
              case Right(evidencePath) =>
                cleanupAndComplete(ledger, id, mergerId, repository, ws, evidencePath)

  /** Crash resume for an item already resting merged-cleanup-pending. */
  private def resumeClosure(
    ledger: WorkspaceLedger,
    id: String,
    mergerId: String,
    repository: String,
    item: MergeItem
  ): Either[String, Unit] =
    val state = ledger.snapshot
    (state.requests.find(_.id == id), state.workspaces.find(_.id == item.workspaceId)) match
      case (Some(_), Some(ws)) =>
        writeEvidence(repository, id, ws, item, item.mergeCommit) match
          case Left(error) => Left(s"evidence unavailable: $error")
          case Right(evidencePath) =>
            cleanupAndComplete(ledger, id, mergerId, repository, ws, evidencePath)
      case _ => Left("Request or workspace record is gone")

  /** Cleanup legs, then the completed report; a refused cleanup rests pending. */
  private def cleanupAndComplete(
    ledger: WorkspaceLedger,
    id: String,
    mergerId: String,
    repository: String,
    ws: Workspace,
    evidencePath: String
  ): Either[String, Unit] =
    cleanupLegs(repository, ws) match
      case Left(error) =>
        warn(s"merge request $id cleanup incomplete (workspace ${ws.id} rests merged-cleanup-pending): $error")
        Right(())
      case Right(_) =>
        ledger.snapshot.requests.find(_.id == id) match
          case None => Left("Request record is gone; the workspace rests merged-cleanup-pending")
          case Some(r) =>
            report(ledger, id, mergerId, r.revision, ws, "completed", verification = Some(evidencePath)).map(_ => ())

  /**
   * Report the item failed (only readable repositories accept failed facts),
   * then close out the rest of the request deterministically. A Left carries
   * the final reason of the item.
   */
  private def failItem(
    ledger: WorkspaceLedger,
    id: String,
    mergerId: String,
    ws: Workspace,
    item: MergeItem,
    failure: String
  ): Either[String, Unit] =
    ledger.snapshot.requests.find(_.id == id) match
      case None => Left(s"$failure; the item rests for boot recovery")
      case Some(r) =>
        report(ledger, id, mergerId, r.revision, ws, "failed", note = Some(failure)) match
          case Left(error) => Left(s"failure report refused ($error); the item rests for boot recovery")
          case Right(_) =>
            ledger.releaseUnfinished(id, mergerId, s"Request failed: ${failure.take(200)}") match
              case Right(_) => Left(failure)
              case Left(error) => Left(s"failure landed but release was refused ($error)")

  /** Cancellation release: integrated items keep their facts, the rest reopen. */
  private def releaseCancelled(id: String, mergerId: String): Unit =
    WorkspaceLedger.default.releaseUnfinished(id, mergerId, "Merge request cancelled") match
      case Right(_) => ()
      case Left(error) => warn(s"merge request $id cancellation release refused: $error")

  /** Emergency valve after an unexpected worker error (never wedges the slot). */
  private def abandon(id: String, error: String): Unit =
    val ledger = WorkspaceLedger.default
    ledger.snapshot.requests.find(_.id == id).foreach { r =>
      ledger.abandonRequest(id, r.mergerId, s"Worker aborted the pass: ${error.take(280)}")
      ()
    }

  /**
   * The mechanical merge, serialized on the repository write lock: pin the
   * source exactly as authorized, build the merge tree, commit it with two
   * parents, then move the target ref with a compare-and-swap so a moved
   * target fails loudly instead of overwriting.
   */
  private def mergeLeg(repository: String, ws: Workspace, item: MergeItem): Either[String, String] =
    guard {
      RepositoryWriteLock.withLock(repository) {
        val tree = os.Path(ws.path)
        val steps = for
          pinned <- MergerFacts.pinSource(ws)
          _ <- Either.cond(
            pinned.sourceCommit == item.sourceCommit,
            (),
            s"Source branch ${ws.branch} moved after authorization (now ${pinned.sourceCommit.take(12)})"
          )
          targetTip <- GitWorktrees
            .resolveRef(tree, s"refs/heads/${item.targetBranch}")
            .toRight(s"Target branch ${item.targetBranch} is unreadable")
          contained = GitWorktrees.isAncestor(tree, item.sourceCommit, targetTip)
          _ <- Either.cond(
            contained.contains(false),
            (),
            "Cannot verify whether the source is already integrated"
          )
          merged <- GitWorktrees
            .mergeTree(tree, targetTip, item.sourceCommit)
            .toRight("git merge-tree is unavailable for this repository")
          (treeOid, conflicts) = merged
          _ <- Either.cond(conflicts.isEmpty, (), s"Merge conflicts: ${conflicts.mkString(", ")}")
          commit <- commitTree(tree, treeOid, targetTip, item.sourceCommit, item.targetBranch, ws.branch)
            .toRight("git commit-tree failed")
          moved = os
            .proc("git", "-C", tree.toString, "update-ref", s"refs/heads/${item.targetBranch}", commit, targetTip)
            .call(cwd = tree, check = false, mergeErrIntoOut = true)
          _ <- Either.cond(
            moved.exitCode == 0,
            (),
            s"Target branch moved during the merge: ${moved.out.text().trim.take(200)}"
          )
        yield commit
        steps
      }.flatMap(identity)
    }

  /**
   * Verification evidence for the closure, hosted by the main working tree of
   * the repository. The recorded command re-proves exactly what the ledger's
   * fact validation asserts: the merge commit is contained in the target and
   * contains the authorized source (or, for a recovered ancestor closure, that
   * the source itself is contained). A non-zero exit is recorded as-is: the
   * item then cannot reach completed and rests merged-cleanup-pending.
   */
  private def writeEvidence(
    repository: String,
    requestId: String,
    ws: Workspace,
    item: MergeItem,
    mergeCommit: Option[String]
  ): Either[String, String] =
    mainTreeRoot(repository).toRight(
      "Repository has no main working tree to host verification evidence"
    ).flatMap { root =>
      val target = s"refs/heads/${item.targetBranch}"
      def rc(a: String, b: String): Int = Try {
        os.proc("git", "--git-dir", repository, "merge-base", "--is-ancestor", a, b)
          .call(check = false, stdout = os.Pipe, stderr = os.Pipe)
          .exitCode
      }.getOrElse(2)
      val (command, exitCode) = mergeCommit match
        case Some(commit) =>
          val cmd = s"git merge-base --is-ancestor $commit $target && " +
            s"git merge-base --is-ancestor ${item.sourceCommit} $commit"
          val left = rc(commit, target)
          val right = rc(item.sourceCommit, commit)
          val code = if left == 0 && right == 0 then 0 else if left >= 2 || right >= 2 then 2 else 1
          (cmd, code)
        case None =>
          val cmd = s"git merge-base --is-ancestor ${item.sourceCommit} $target"
          val code = rc(item.sourceCommit, target) match
            case 0 => 0
            case 1 => 1
            case _ => 2
          (cmd, code)
      val fields: List[(String, Json)] =
        List("status" -> "passed".asJson, "sourceCommit" -> item.sourceCommit.asJson) ++
          mergeCommit.map(commit => "mergeCommit" -> commit.asJson).toList ++
          (if mergeCommit.isEmpty then List("integration" -> "source-ancestor".asJson) else Nil) ++
          List(
            "command" -> command.asJson,
            "exitCode" -> exitCode.asJson,
            "ranAt" -> Instant.now().toString.asJson
          )
      val payload = Json.obj(fields*).noSpaces
      val dir = root / ".nebflow" / "evidence" / "merger" / requestId
      val path = dir / s"${ws.id}.json"
      Try {
        os.makeDir.all(dir)
        if os.exists(path) then os.write.over(path, payload) else os.write(path, payload)
      }.toEither.left
        .map(describe)
        .map(_ => path.toString)
    }

  /**
   * Remove the worktree and delete the branch it carried — best effort, never
   * force: the worktree removal refuses a dirty tree, and the branch delete
   * only fires when the branch is fully contained in its base (zero ahead).
   * Any refusal leaves the workspace merged-cleanup-pending for the panel's
   * manual cleanup retry.
   */
  private def cleanupLegs(repository: String, ws: Workspace): Either[String, Unit] =
    guard {
      val root = mainTreeRoot(repository).getOrElse(os.Path(ws.path) / os.up)
      RepositoryWriteLock.withLock(repository) {
        // Ancestry must be measured before the worktree directory disappears.
        val measured = WorkspaceLedger.default.snapshot.workspaces.find(_.id == ws.id).toRight(
          "Workspace record is gone"
        ).map { current =>
          val ahead = GitWorktrees.commitsAhead(root, current.baseBranch, current.branch)
          val ancestor = GitWorktrees.isAncestor(root, current.branch, current.baseBranch)
          (current, ahead, ancestor)
        }
        measured.flatMap { case (current, ahead, ancestor) =>
          val remove =
            if !os.exists(os.Path(current.path)) then Right(())
            else GitWorktrees.removeWorktree(root, current.path).map(_ => ())
          remove.flatMap { _ =>
            if ahead == 0 && ancestor.contains(true) then GitWorktrees.deleteBranch(root, current.branch).map(_ => ())
            else Right(())
          }
        }
      }.flatMap(identity)
    }

  /**
   * Main working tree hosting `.nebflow/evidence` (the parent of the git
   * common dir when its name is `.git`); None for bare repositories.
   */
  private def mainTreeRoot(repository: String): Option[os.Path] =
    val path = java.nio.file.Path.of(repository)
    Option.when(path.getFileName != null && path.getFileName.toString == ".git")(os.Path(path.getParent))

  /** Commit a prepared tree with two parents; None on any Git failure. */
  private def commitTree(
    tree: os.Path,
    treeOid: String,
    targetTip: String,
    sourceCommit: String,
    targetBranch: String,
    sourceBranch: String
  ): Option[String] =
    def configured(key: String): String = Try {
      os.proc("git", "-C", tree.toString, "config", key).call(stdout = os.Pipe, stderr = os.Pipe).out.text().trim
    }.getOrElse("")
    val identity =
      (if configured("user.name").nonEmpty then Nil
       else Seq("-c", s"user.name=${MergerWorker.FallbackCommitterName}")) ++
        (if configured("user.email").nonEmpty then Nil
         else Seq("-c", s"user.email=${MergerWorker.FallbackCommitterEmail}"))
    Try {
      os.proc(
        Seq("git", "-C", tree.toString) ++
          identity ++
          Seq(
            "commit-tree",
            treeOid,
            "-p",
            targetTip,
            "-p",
            sourceCommit,
            "-m",
            s"Merge branch '$sourceBranch' into $targetBranch"
          )
      ).call(stdout = os.Pipe, stderr = os.Pipe).out.text().trim
    }.toOption.filter(_.matches("[0-9a-f]{40}|[0-9a-f]{64}"))

  /**
   * Report one transition with a deterministic event id, so a crash replay of
   * the same phase is idempotent. Bounded stale-revision retry: while the
   * request is claimed, only this worker moves the revision.
   */
  private def report(
    ledger: WorkspaceLedger,
    id: String,
    mergerId: String,
    expectedRevision: Long,
    ws: Workspace,
    phase: String,
    mergeCommit: Option[String] = None,
    verification: Option[String] = None,
    integration: Option[String] = None,
    note: Option[String] = None,
    tries: Int = 5
  ): Either[String, WorkspaceLedger.MergeRequest] =
    val event = MergeReport(
      s"worker:$id:${ws.id}:$phase",
      expectedRevision,
      ws.id,
      phase,
      mergeCommit,
      verification,
      integration,
      note
    )
    ledger.reportMerge(id, mergerId, event)(MergerFacts.validate) match
      case Left("Stale merge request revision") if tries > 1 =>
        ledger.snapshot.requests.find(_.id == id) match
          case Some(r) =>
            report(
              ledger,
              id,
              mergerId,
              r.revision,
              ws,
              phase,
              mergeCommit,
              verification,
              integration,
              note,
              tries - 1
            )
          case None => Left("Request record is gone")
      case other => other

  /** Run a blocking Either body, turning any thrown error into a Left. */
  private def guard[A](body: => Either[String, A]): Either[String, A] =
    Try(body).toEither.left.map(describe).flatMap(identity)

  private def describe(e: Throwable): String = MergerWorker.describe(e)

  /** Emit a warning now; the blocking worker passes run outside the IO run. */
  private def warn(msg: String): Unit = MergerWorker.logger.warn(msg).unsafeRunSync()(using global)

end MergerWorker
