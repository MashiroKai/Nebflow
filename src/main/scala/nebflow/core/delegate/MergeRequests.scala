package nebflow.core.delegate

import cats.effect.IO
import cats.syntax.all.*
import io.circe.syntax.*
import nebflow.core.project.{ProjectDef, ProjectStore}
import nebflow.shared.PathUtil

object MergeRequests:

  def cancel(project: String, requestId: String): IO[Either[String, String]] =
    ProjectStore
      .load(project)
      .flatMap {
        case None => IO.pure(Left("Project not found"))
        case Some(pd) if ProjectStore.effectiveDevice(pd) != "self" => IO.pure(Left("Use the project's device"))
        case Some(pd) =>
          IO.blocking {
            val ledger = WorkspaceLedger.default
            val repository = WorkspaceLedger.repositoryKey(os.Path(ProjectStore.brainWorkspace(pd), PathUtil.dataRoot))
            ledger.snapshot.requests
              .find(r => r.id == requestId && r.repository == repository)
              .toRight("Request does not belong to this project")
              .flatMap(_ => ledger.cancelRequest(requestId))
          }.flatMap {
            case Left(error) => IO.pure(Left(error))
            case Right(request) =>
              IO {
                MergerWorker.nudge()
                Right(
                  io.circe.Json
                    .obj("request" -> request.asJson, "cancelRequested" -> request.cancelRequested.asJson)
                    .noSpaces
                )
              }
          }
      }
      .handleError(e => Left(Option(e.getMessage).getOrElse("Cancellation request failed")))

  def submit(
    project: String,
    requestId: String,
    ids: List[String],
    authorization: String
  ): IO[Either[String, String]] =
    ProjectStore
      .load(project)
      .flatMap {
        case None => IO.pure(Left("Project not found"))
        case Some(pd) if ProjectStore.effectiveDevice(pd) != "self" => IO.pure(Left("Submit on the project's device"))
        case Some(pd) =>
          // Read-only preparation (everything up to the ledger write), split from the
          // write so the occupancy gate can run between them.
          IO.blocking(prepare(pd, requestId, ids, authorization)).flatMap {
            case Left(error) => IO.pure(Left(error))
            case Right(SubmitPlan.Idempotent(existing)) => deliver(existing)
            case Right(SubmitPlan.Fresh(repository, selected, request)) =>
              // Occupancy gate BEFORE any write: a workspace whose owner still has
              // non-terminal scope nodes or live background jobs (a standby
              // dispatcher included) is busy, and an unreadable occupancy reading
              // is fail-closed. A rejected request is NOT queued or registered —
              // nothing waits.
              WorkspaceOccupancy.default
                .checkBatch(selected.map(w => w.id -> w.owner))
                .flatMap {
                  case Left(unreadable) => IO.pure(Left(s"Merge request rejected — $unreadable"))
                  case Right(reasons) if reasons.nonEmpty =>
                    IO.pure(
                      Left(
                        s"Merge request rejected — every selected workspace must have an idle owner: ${reasons.mkString("; ")}"
                      )
                    )
                  case Right(_) =>
                    IO.blocking(WorkspaceLedger.default.requestMerge(request)).flatMap {
                      case Left(error) => IO.pure(Left(error))
                      case Right(accepted) => deliver(accepted)
                    }
                }
          }
      }
      .handleError(e => Left(Option(e.getMessage).getOrElse("Merge request failed")))

  /** Read-only plan for one submit call; the ledger is written only by [[SubmitPlan.Fresh]]. */
  private sealed trait SubmitPlan

  private object SubmitPlan:
    /** The identical request is already recorded — nothing to write, queueing only. */
    final case class Idempotent(existing: WorkspaceLedger.MergeRequest) extends SubmitPlan

    /** A fresh request: occupancy gate, then the all-or-nothing ledger reservation. */
    final case class Fresh(
      repository: String,
      selected: List[WorkspaceLedger.Workspace],
      request: WorkspaceLedger.MergeRequest
    ) extends SubmitPlan

  end SubmitPlan

  private def prepare(
    pd: ProjectDef,
    requestId: String,
    ids: List[String],
    authorization: String
  ): Either[String, SubmitPlan] =
    val root = os.Path(ProjectStore.brainWorkspace(pd), PathUtil.dataRoot)
    val ledger = WorkspaceLedger.default
    val repository = WorkspaceLedger.repositoryKey(root)
    val snapshot = ledger.snapshot
    snapshot.mergers
      .find(_.repository == repository)
      .toRight("Create the project merger first with Delegate(mode=merger)")
      .flatMap { merger =>
        if DelegateRegistry.find(merger.delegateId).isEmpty then
          Left("Merger initialization is incomplete; recover its recorded identity before submitting")
        else
          val selected =
            ids.distinct.flatMap(id => snapshot.workspaces.find(w => w.id == id && w.repository == repository))
          if selected.size != ids.distinct.size || ids.isEmpty then Left("Unknown or empty workspace selection")
          else
            snapshot.requests.find(_.id == requestId) match
              case Some(existing)
                  if existing.repository == repository &&
                    existing.items.map(_.workspaceId) == ids.distinct =>
                Right(SubmitPlan.Idempotent(existing))
              case Some(_) => Left("Request identity conflicts with its recorded authorization scope")
              case None =>
                selected
                  .traverse(MergerFacts.pinSource)
                  .map(items =>
                    SubmitPlan.Fresh(
                      repository,
                      selected,
                      WorkspaceLedger.MergeRequest(requestId, repository, merger.delegateId, authorization, items)
                    )
                  )
          end if
      }

  end prepare

  /** Queue the request for the persistent merger worker and wake it. */
  private def deliver(request: WorkspaceLedger.MergeRequest): IO[Either[String, String]] =
    IO {
      MergerWorker.nudge()
      Right(io.circe.Json.obj("request" -> request.asJson, "queued" -> true.asJson).noSpaces)
    }
  end deliver
end MergeRequests
