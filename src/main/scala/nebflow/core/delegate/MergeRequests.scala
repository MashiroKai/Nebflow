package nebflow.core.delegate

import cats.effect.IO
import cats.syntax.all.*
import io.circe.syntax.*
import nebflow.actor.AgentCommand
import nebflow.core.AgentRuntimePort
import nebflow.core.mail.SessionMailbox
import nebflow.core.project.{ProjectDef, ProjectStore}
import nebflow.shared.PathUtil

object MergeRequests:

  def cancel(project: String, requestId: String, resources: AgentRuntimePort): IO[Either[String, String]] =
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
              val event = AgentCommand.ExternalEvent(
                "mail",
                "info",
                s"Cancellation requested for merge request ${request.id}. Inspect its actual state and stop at a safe boundary. " +
                  "Report completed, integrated-but-not-cleaned and untouched workspaces separately; never undo an existing merge."
              )
              SessionMailbox
                .mailOnce(
                  request.mergerId,
                  event,
                  "merger-control",
                  s"merger-cancel:${request.id}",
                  deduplicateReceipts = true
                )
                .flatMap { delivery =>
                  resources.agentRegistry.get.flatMap { live =>
                    live.get(request.mergerId).fold(IO.unit)(r => (r.ref ! AgentCommand.MailboxArrived).attempt.void) *>
                      IO.pure(
                        Right(
                          io.circe.Json
                            .obj(
                              "request" -> request.asJson,
                              "delivery" -> delivery.toString.asJson,
                              "notificationPending" -> (delivery match
                                case _: SessionMailbox.MailResult.Failed => true
                                case _ => false
                              ).asJson
                            )
                            .noSpaces
                        )
                      )
                  }
                }
          }
      }
      .handleError(e => Left(Option(e.getMessage).getOrElse("Cancellation request failed")))

  def submit(
    project: String,
    requestId: String,
    ids: List[String],
    authorization: String,
    resources: AgentRuntimePort
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
            case Right(SubmitPlan.Idempotent(existing)) => deliver(existing, resources)
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
                      case Right(accepted) => deliver(accepted, resources)
                    }
                }
          }
      }
      .handleError(e => Left(Option(e.getMessage).getOrElse("Merge request failed")))

  /** Read-only plan for one submit call; the ledger is written only by [[SubmitPlan.Fresh]]. */
  private sealed trait SubmitPlan

  private object SubmitPlan:
    /** The identical request is already recorded — nothing to write, delivery only. */
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

  /** Mail the recorded request to its merger and wake it (shared by both plan faces). */
  private def deliver(request: WorkspaceLedger.MergeRequest, resources: AgentRuntimePort): IO[Either[String, String]] =
    val event = AgentCommand.ExternalEvent(
      "mail",
      "info",
      s"Authorized merge request ${request.id}. Inspect the persisted request using merger_report(action=inspect). " +
        "Only its recorded workspaces and source commits are authorized; pause on failure. No push, publish, tag or version changes."
    )
    SessionMailbox
      .mailOnce(
        request.mergerId,
        event,
        "merger-request",
        s"merger-request:${request.id}",
        deduplicateReceipts = true
      )
      .flatMap { delivery =>
        resources.agentRegistry.get.flatMap { live =>
          live.get(request.mergerId).fold(IO.unit)(r => (r.ref ! AgentCommand.MailboxArrived).attempt.void) *>
            IO.pure(
              Right(
                io.circe.Json
                  .obj(
                    "request" -> request.asJson,
                    "mergerAddress" -> s"delegate:${request.mergerId}".asJson,
                    "mergerLive" -> live.contains(request.mergerId).asJson,
                    "delivery" -> delivery.toString.asJson,
                    "notificationPending" -> (delivery match
                      case _: SessionMailbox.MailResult.Failed => true
                      case _ => false
                    ).asJson
                  )
                  .noSpaces
              )
            )
        }
      }
  end deliver
end MergeRequests
