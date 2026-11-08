package nebflow.core.delegate

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import nebflow.actor.{AgentCommand, AgentStatus}
import nebflow.core.AgentRuntimePort
import nebflow.core.project.ProjectStore
import nebflow.core.tools.BgTaskRegistry
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
                  live <- resources.agentRegistry.get
                  sessions = (live.keys.toList ++ List(workspace.owner) ++ WorkspaceLedger.default.snapshot.mergers
                    .filter(_.repository == workspace.repository)
                    .map(_.delegateId)).distinct
                  allJobs <- BgTaskRegistry.allActiveTasks
                  jobs = sessions
                    .map(sid => sid -> allJobs.exists(task => task.sessionId == sid || task.rootSessionId == sid))
                  busy = live.values
                    .filter(r =>
                      r.currentToolName.nonEmpty || !Set(AgentStatus.Idle, AgentStatus.Waiting, AgentStatus.Frozen)
                        .contains(r.status)
                    )
                    .flatMap(r => List(r.sessionId, r.rootSessionId))
                    .toSet ++ jobs.collect { case (sid, true) => sid } ++
                    live.values.filter(r => jobs.exists(j => j._1 == r.sessionId && j._2)).map(_.rootSessionId)
                  outcome <- IO.blocking {
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
