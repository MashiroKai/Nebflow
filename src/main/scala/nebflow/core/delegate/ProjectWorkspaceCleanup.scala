package nebflow.core.delegate

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import nebflow.actor.{AgentCommand, AgentStatus}
import nebflow.core.AgentRuntimePort
import nebflow.core.project.ProjectStore
import nebflow.shared.PathUtil

object ProjectWorkspaceCleanup:

  def run(
    project: String,
    id: String,
    resources: AgentRuntimePort,
    confirmation: Option[(String, Boolean, Boolean)]
  ): IO[Either[String, Json]] =
    ProjectStore
      .load(project)
      .flatMap {
        case None => IO.pure(Left("Project not found"))
        case Some(pd) if ProjectStore.effectiveDevice(pd) != "self" => IO.pure(Left("Clean up on the project's device"))
        case Some(pd) =>
          for
            scoped <- IO.blocking {
              val ledger = WorkspaceLedger.default
              val repository =
                WorkspaceLedger.repositoryKey(os.Path(ProjectStore.brainWorkspace(pd), PathUtil.dataRoot))
              ledger.snapshot.workspaces
                .find(w => w.id == id && w.repository == repository)
                .toRight("Workspace does not belong to this project")
            }
            result <- scoped match
              case Left(error) => IO.pure(Left(error))
              case Right(workspace) =>
                for
                  // Busy owners come from the single judgment point (WorkspaceOccupancy):
                  // an owner with non-terminal scope nodes or live background jobs is
                  // busy — including a standby dispatcher whose ledger activity slot
                  // already says idle. The live-executing face (registry record mid
                  // tool / not in a waiting state) is retained for the candidate
                  // owners themselves, preserving the previous behavior for a merger
                  // (or owner) caught inside a running turn.
                  live <- resources.agentRegistry.get
                  candidates = (List(workspace.owner) ++ WorkspaceLedger.default.snapshot.mergers
                    .filter(_.repository == workspace.repository)
                    .map(_.delegateId)).distinct
                  executing = live.values
                    .filter(r =>
                      (candidates.contains(r.sessionId) || candidates.contains(r.rootSessionId)) &&
                        (r.currentToolName.nonEmpty ||
                          !Set(AgentStatus.Idle, AgentStatus.Waiting, AgentStatus.Frozen).contains(r.status))
                    )
                    .flatMap(r => List(r.sessionId, r.rootSessionId))
                    .toSet
                  occupancy <- WorkspaceOccupancy.default.busyOwners(candidates)
                  busy = occupancy match
                    case Right(occupied) => occupied ++ executing
                    // Unreadable occupancy must never look idle: every candidate is
                    // treated as busy (fail-closed) and the refusal text below names
                    // the uncertainty explicitly.
                    case Left(_) => candidates.toSet
                  outcome <- occupancy match
                    case Left(unreadable) =>
                      IO.pure(
                        Left(
                          s"Workspace occupancy could not be read ($unreadable); cleanup is refused to avoid racing a live writer"
                        )
                      )
                    case Right(_) =>
                      IO.blocking {
                        val cleanup = new WorkspaceCleanup(WorkspaceLedger.default)
                        confirmation match
                          case None =>
                            cleanup.reconcile(id, busy).flatMap { current =>
                              if current.lifecycle.startsWith("closed-") then
                                if cleanup.removalComplete(current) then
                                  Right(Json.obj("alreadyRemoved" -> true.asJson, "workspace" -> current.asJson))
                                else
                                  Left(
                                    "Workspace is closed but its path or registration changed externally; inspect manually"
                                  )
                              else cleanup.inspect(id, busy).map(_.asJson)
                            }
                          case Some((token, unmerged, dirty)) =>
                            cleanup.remove(id, token, unmerged, dirty, busy).map(_.asJson)
                      }
                  _ <- (confirmation, outcome) match
                    case (Some(_), Right(_))
                        if WorkspaceLedger.default.owned(workspace.owner).exists(_.lifecycle.startsWith("closed-")) =>
                      live
                        .get(workspace.owner)
                        .fold(IO.unit)(r =>
                          (r.ref ! AgentCommand.Stop("Workspace explicitly cleaned and closed by user")).attempt.void
                        )
                    case _ => IO.unit
                yield outcome
          yield result
      }
      .handleError(e => Left(Option(e.getMessage).getOrElse("Workspace cleanup failed")))
end ProjectWorkspaceCleanup
