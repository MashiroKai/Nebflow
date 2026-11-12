package nebflow.core.delegate

import cats.effect.IO
import cats.syntax.all.*

import nebflow.actor.AgentRecord
import nebflow.core.project.ProjectStore
import nebflow.shared.PathUtil

/**
 * MergerRecovery — the supported release/replace face for a project merger identity
 * whose delegate session was CANCELLED (cancelled-merger recovery batch).
 *
 * Failure shape this closes: `WorkspaceLedger.mergers` records one identity per
 * project repository for life. When that delegate session is cancelled (cancel
 * tombstone — revival is refused forever), every later `Delegate(mode=merger)` call
 * returns the dead address with `taskAccepted=false`, and integration was blocked
 * with no supported way out (incident: delegate-nebflow-f2f610f2).
 *
 * Division of labor (minimal surface; no second spawn path, no identity forgery):
 *
 *   - THIS object only unblocks: it releases the cancelled identity slot so the
 *     normal creation entry ([[nebflow.core.tools.DelegateTool]] with
 *     `mode=merger` → [[WorkspaceLedger.merger]]) can mint the replacement.
 *     The replacement is never minted here.
 *   - The ledger write is [[WorkspaceLedger.releaseMerger]] — one atomic
 *     transaction that re-checks every gate under the file lock, records a
 *     deterministic idempotent-replay event, and preserves all history (requests,
 *     workspaces, bootstrap seed, registry tombstone).
 *
 * Gates (fail-closed on busy or unclear):
 *   1. trusted root — enforced at the tool face (`ctx.isRootAgent`, MergerRequestTool);
 *   2. cancellation PROVEN by the registry record's cancel tombstone;
 *   3. the cancelled session is not live in the agent registry (injected reader so
 *      specs feed synthetic readings, WorkspaceOccupancy precedent);
 *   4. no spawn/repair of the same identity is in progress — the release runs under
 *      the existing session-start lease ([[MergerInitialization.exclusive]]);
 *   5. no non-terminal merge request and no merger-occupied workspace for the
 *      repository — re-checked atomically inside the ledger transaction.
 *
 * Persistence: a crash between the checks and the write leaves the merger intact
 * (retry re-runs the gates); a crash after the write is closed out by the recorded
 * release event (the same event id replays verbatim and never touches a replacement
 * created meanwhile). The event id is derived from immutable facts —
 * `release-merger:<repository>:<delegateId>:<tombstoneAt>` — so concurrent callers
 * of the same release derive the same key and the call is idempotent by construction.
 */
object MergerRecovery:

  /** Fixed audit reason (no input surface — the release reason cannot be injected). */
  val ReleaseReason: String =
    "cancelled merger identity released through MergerRequest action=releaseMerger (trusted root)"

  /** Deterministic, fact-derived replay key; immutable inputs only. */
  def eventIdFor(repository: String, delegateId: String, tombstoneAt: Long): String =
    s"release-merger:$repository:$delegateId:$tombstoneAt"

  /** Production liveness reader over the in-process agent registry. */
  def isLiveOf(registry: cats.effect.Ref[IO, Map[String, AgentRecord]]): String => IO[Boolean] =
    id => registry.get.map(_.contains(id))

  /**
   * Resolve a mounted project (self-device only, MergeRequests precedent), then
   * release its recorded merger identity under the full gate set.
   */
  def releaseForProject(project: String, isLive: String => IO[Boolean]): IO[Either[String, WorkspaceLedger.MergerRelease]] =
    ProjectStore
      .load(project)
      .flatMap {
        case None => IO.pure(Left("Project not found"))
        case Some(pd) if ProjectStore.effectiveDevice(pd) != "self" => IO.pure(Left("Use the project's device"))
        case Some(pd) =>
          IO.blocking(WorkspaceLedger.repositoryKey(os.Path(ProjectStore.brainWorkspace(pd), PathUtil.dataRoot)))
            .flatMap(repository => release(project, repository, ReleaseReason, isLive))
      }
      .handleError(e => Left(Option(e.getMessage).getOrElse("Merger release failed")))

  /**
   * Release the recorded merger identity of `repository` after every gate passes.
   * The `isLive` reader is injected (production: the agent registry; specs: synthetic).
   * Replay-aware: when the identity slot is already gone and a release event is
   * recorded for this project/repository, the recorded facts replay idempotently —
   * a second call, a retry after a crash, or a call from a fresh process all converge.
   */
  def release(
    project: String,
    repository: String,
    reason: String,
    isLive: String => IO[Boolean]
  ): IO[Either[String, WorkspaceLedger.MergerRelease]] =
    // Evidence pass (fail-closed on unclear): the merger must be recorded, its
    // delegate record present, its cancellation proven — or a recorded release
    // event replays. The ledger transaction re-validates all of this under the
    // lock; this pass only fixes the identity the liveness and lease gates run against.
    IO.blocking(evidence(project, repository)).flatMap {
      case Left(error) => IO.pure(Left(error))
      case Right(Evidence.AlreadyReleased(event)) => IO.pure(Right(event))
      case Right(Evidence.ToRelease(merger, tombstoneAt)) =>
        isLive(merger.delegateId).flatMap {
          case true => IO.pure(Left("The cancelled merger's session is still live; refusing to release (fail-closed)"))
          case false =>
            val eventId = eventIdFor(repository, merger.delegateId, tombstoneAt)
            // The session-start lease excludes a spawn or repair of the same
            // identity that is genuinely mid-flight (process-independent file
            // lock, MergerInitializationSpec-proven); SessionBusy refuses closed.
            MergerInitialization
              .exclusive(merger.delegateId)(
                IO.blocking(
                  WorkspaceLedger.default.releaseMerger(project, repository, merger.delegateId, eventId, reason)
                )
              )
              .attempt
              .map {
                case Left(_: MergerInitialization.SessionBusy) =>
                  Left("Merger initialization or recovery is in progress for this identity; refusing (fail-closed)")
                case Left(error) => Left(Option(error.getMessage).getOrElse(error.toString))
                case Right(result) => result
              }
        }
    }

  /** Evidence outcome of the read pass before any lease or write is taken. */
  private sealed trait Evidence
  private object Evidence:
    /** A recorded merger identity with a proven cancel tombstone — ready for the gates. */
    final case class ToRelease(merger: WorkspaceLedger.Merger, tombstoneAt: Long) extends Evidence

    /** The slot is already gone; a durable release event exists — replay its facts. */
    final case class AlreadyReleased(event: WorkspaceLedger.MergerRelease) extends Evidence
  end Evidence

  private def evidence(project: String, repository: String): Either[String, Evidence] =
    val ledger = WorkspaceLedger.default
    ledger.recordedMerger(project, repository) match
      case Right(merger) =>
        DelegateRegistry
          .find(merger.delegateId)
          .toRight("The merger's delegate record is missing; the state is unclear (fail-closed)")
          .flatMap { record =>
            record.cancelRequestedAt
              .toRight("The merger is not cancelled (no cancel tombstone); it keeps its own recovery path")
              .map(tombstoneAt => Evidence.ToRelease(merger, tombstoneAt))
          }
      case Left(noMerger) =>
        ledger.releasedMergerEvent(project, repository) match
          case Right(event) => Right(Evidence.AlreadyReleased(event))
          case Left(_) => Left(noMerger)

end MergerRecovery
