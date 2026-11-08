package nebflow.core.delegate

import cats.effect.IO
import cats.syntax.all.*
import nebflow.core.tools.BgTaskRegistry

/**
 * WorkspaceOccupancy —— the single judgment point for "is an owner still
 * executing". The ledger's `activity` slot only records the *duty* state a
 * delegate declared (`admit`/`settle`): a dispatcher on standby has settled its
 * workspace back to `idle` while its Nodes and background write jobs are still
 * running. Merge reservation must treat that workspace as busy, and cleanup
 * must treat its owner as busy.
 *
 * Two readers are aggregated per owner session:
 *   1. **Scope-graph nodes** — non-terminal nodes in the owner's own scope
 *      graph (`DelegateInflightGate.read`; same criterion as the finish gate:
 *      non-terminal = `!NodeLifecycle.Terminal.contains(status)`).
 *   2. **Live background jobs** — every registry row bound to the owner
 *      session (`sessionId` or `rootSessionId` match; persistent service jobs
 *      included — a live server is still a live writer's process).
 *
 * Failure semantics are fail-closed by design (the opposite trade-off of the
 * finish gate's fail-soft): an occupancy reading that cannot be taken is
 * uncertainty about a *merge or a destructive cleanup*, so it becomes
 * `Left` — the whole batch is rejected / the cleanup is refused — never a
 * silent pass.
 *
 * The readers are constructor-injected so specs can feed synthetic readings;
 * [[WorkspaceOccupancy.default]] wires the production readers.
 */
final class WorkspaceOccupancy(
  inflight: String => IO[DelegateInflightGate.Reading],
  jobs: String => IO[List[BgTaskRegistry.ActiveTask]]
):

  /**
   * One owner's occupancy: `Left` = unreadable/uncertain (fail-closed);
   * `Right(reasons)` with an empty list = idle.
   */
  def occupancyOf(owner: String): IO[Either[String, List[String]]] =
    val nodeIo = inflight(owner).attempt
    val jobIo = jobs(owner).attempt
    (nodeIo, jobIo).mapN {
      case (Left(cause), _) => Left(WorkspaceOccupancy.unreadableText(owner, describe(cause)))
      case (_, Left(cause)) => Left(WorkspaceOccupancy.unreadableText(owner, describe(cause)))
      case (Right(reading), Right(live)) =>
        if !reading.readable then Left(WorkspaceOccupancy.unreadableText(owner, "the scope graph could not be read"))
        else
          val nodeReasons = reading.nodes.map(n => s"node ${n.id} (${n.name}) is still ${n.status}")
          val jobReasons = live.map(t => s"background job ${t.jobId} (${t.description}) is still running")
          Right(nodeReasons ++ jobReasons)
    }

  /**
   * Busy-owner set over candidate owners: owners with non-empty reasons.
   * `Left` when ANY candidate's occupancy is unreadable — the caller must
   * fail closed (refuse) instead of guessing.
   */
  def busyOwners(owners: Iterable[String]): IO[Either[String, Set[String]]] =
    owners.toList.distinct.traverse(owner => occupancyOf(owner).map(owner -> _)).map { readings =>
      readings.foldLeft[Either[String, Set[String]]](Right(Set.empty)) {
        case (Left(acc), _) => Left(acc)
        case (Right(_), (_, Left(reason))) => Left(reason)
        case (Right(busy), (_, Right(Nil))) => Right(busy)
        case (Right(busy), (owner, Right(_))) => Right(busy + owner)
      }
    }

  /**
   * Batch gate used by merge reservation: every candidate workspace's owner is
   * resolved; ANY busy or unreadable workspace fails the WHOLE batch.
   * `Left` = fail-closed refusal text (unreadable occupancy);
   * `Right(reasons)` with an empty list = every owner idle; non-empty =
   * one line per busy workspace.
   */
  def checkBatch(workspaces: Iterable[(String, String)]): IO[Either[String, List[String]]] =
    workspaces.toList.distinct.traverse { case (id, owner) =>
      occupancyOf(owner).map {
        case Left(reason) => Left(s"workspace $id (owner $owner): $reason")
        case Right(Nil) => Right(Nil)
        case Right(reasons) => Left(s"workspace $id (owner $owner) is busy: ${reasons.mkString("; ")}")
      }
    }.map { rows =>
      val unreadable = rows.collect { case Left(line) if line.contains(WorkspaceOccupancy.UnreadableMark) => line }
      if unreadable.nonEmpty then
        Left(
          s"workspace occupancy is unreadable — the whole batch is rejected (fail-closed): ${unreadable.mkString("; ")}"
        )
      else
        val busy = rows.collect { case Left(line) => line }
        Right(busy)
    }

  private def describe(cause: Throwable): String =
    Option(cause.getMessage).filter(_.nonEmpty).getOrElse(cause.getClass.getSimpleName)

end WorkspaceOccupancy

object WorkspaceOccupancy:

  /** Marker inside every unreadable line of [[checkBatch]] (failure-shape separation). */
  val UnreadableMark: String = "unreadable"

  private def unreadableText(owner: String, cause: String): String =
    s"occupancy for owner $owner is ${WorkspaceOccupancy.UnreadableMark} ($cause) — refusing to assume idle"

  /** Production scope-graph reader (single source: the finish gate's read). */
  private def productionInflight(owner: String): IO[DelegateInflightGate.Reading] = DelegateInflightGate.read(owner)

  /**
   * Production background-job reader: every registry row bound to the owner
   * session (own rows and child rows via `rootSessionId`; persistent service
   * jobs included).
   */
  private def productionJobs(owner: String): IO[List[BgTaskRegistry.ActiveTask]] =
    BgTaskRegistry.allActiveTasks
      .map(_.filter(t => t.sessionId == owner || t.rootSessionId == owner))
      .handleErrorWith(_ => IO.raiseError(new IllegalStateException("background job registry could not be read")))

  def default: WorkspaceOccupancy = new WorkspaceOccupancy(productionInflight, productionJobs)

end WorkspaceOccupancy
