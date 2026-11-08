package nebflow.core.delegate

import cats.effect.IO
import cats.syntax.all.*
import io.circe.syntax.*
import nebflow.actor.AgentCommand
import nebflow.core.AgentRuntimePort
import nebflow.core.mail.SessionMailbox
import nebflow.core.project.ProjectStore
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
          IO.blocking {
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
                        Right(existing)
                      case Some(_) => Left("Request identity conflicts with its recorded authorization scope")
                      case None =>
                        selected.traverse(MergerFacts.pinSource).flatMap { items =>
                          ledger.requestMerge(
                            WorkspaceLedger.MergeRequest(requestId, repository, merger.delegateId, authorization, items)
                          )
                        }
              }
          }.flatMap {
            case Left(error) => IO.pure(Left(error))
            case Right(request) =>
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
          }
      }
      .handleError(e => Left(Option(e.getMessage).getOrElse("Merge request failed")))
end MergeRequests
