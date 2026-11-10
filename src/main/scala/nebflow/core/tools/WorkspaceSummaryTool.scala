package nebflow.core.tools

import cats.effect.IO
import io.circe.{Json, JsonObject}
import io.circe.syntax.*
import nebflow.core.delegate.{WorkspaceAdvisor, WorkspaceFacts, WorkspaceLedger}
import nebflow.shared.PathUtil

import scala.util.Try

/**
 * WorkspaceSummary — the main agent's per-project workspace coordination
 * picture (worktree-merger architecture §七): workspaces with their live
 * Git facts, pending merge requests with per-item phases and their origin,
 * advisor suggestions with their states, and the merger initialization
 * status. `action="dismiss-suggestion"` marks one advisor suggestion
 * dismissed; it never touches a real merge request.
 *
 * Everything here is read on demand — no state is remembered between calls.
 * The advisor's rules ride the read face: a completion claim is only trusted
 * when it matches the persisted finish report, an empty occupancy reading and
 * a clean committed tree; suggestions never auto-create merge requests, and
 * the user's current instructions override any proactive suggestion.
 *
 * Authorization is the MergerRequestTool pattern: root-only on the mount face
 * plus this in-tool identity refusal (fail-closed for everyone else).
 */
object WorkspaceSummaryTool extends Tool:
  val Name = "WorkspaceSummary"
  def name: String = Name

  def description: String =
    "Read one project's workspace coordination picture: workspaces with lifecycle/activity and ahead/dirty/HEAD facts, " +
      "pending merge requests with per-item phases and their origin (panel-submitted or agent-submitted), advisor " +
      "suggestions with their states (suggested/stale/dismissed/integrated/pending-coordination), and merger " +
      "initialization status. Facts are read on demand, not remembered. Trust a completion claim only after it matches " +
      "the developer's persisted finish report, an empty occupancy reading and a clean committed tree. Advisor " +
      "suggestions never auto-create merge requests: submitting one needs the user's explicit authorization, and the " +
      "user's current instructions override any suggestion — when you surface one, state its trigger basis and origin. " +
      "action=dismiss-suggestion (workspaceId + headCommit) marks one suggestion dismissed; it never touches real " +
      "merge requests. Main agent only."

  def inputSchema: JsonObject = JsonObject(
    "type" -> "object".asJson,
    "properties" -> Json.obj(
      "project" -> Json.obj("type" -> "string".asJson),
      "action" -> Json.obj("type" -> "string".asJson, "enum" -> List("summary", "dismiss-suggestion").asJson),
      "workspaceId" -> Json.obj("type" -> "string".asJson),
      "headCommit" -> Json.obj("type" -> "string".asJson)
    ),
    "required" -> List("project").asJson
  )

  def summarize(input: JsonObject): String =
    s"$Name(${input("project").flatMap(_.asString).getOrElse("?")})"

  def summarizeResult(input: JsonObject, result: String): String = result.take(200)

  /** Read-time provenance: the panel channel stamps its submissions; everything else is agent-submitted. */
  def requestOrigin(request: WorkspaceLedger.MergeRequest): String =
    if request.authorization.startsWith("authenticated project panel selection") then "panel" else "agent"

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    if !ctx.isRootAgent then IO.pure(Left(ToolError("WorkspaceSummary is limited to the main agent")))
    else
      val c = Json.fromJsonObject(input).hcursor
      val project = c.get[String]("project").getOrElse("")
      val action = c.get[String]("action").getOrElse("summary")
      if project.trim.isEmpty then IO.pure(Left(ToolError("Missing project")))
      else if action == "dismiss-suggestion" then dismiss(c, project)
      else if action != "summary" then IO.pure(Left(ToolError(s"Unknown action $action")))
      else
        nebflow.core.project.ProjectStore.load(project).flatMap {
          case None => IO.pure(Left(ToolError("Project not found")))
          case Some(pd) =>
            IO.blocking {
              val ws = os.Path(nebflow.core.project.ProjectStore.brainWorkspace(pd), PathUtil.dataRoot)
              val repository = WorkspaceLedger.repositoryKey(ws)
              summary(repository)
            }.map(Right(_)).handleError(e => Left(ToolError(Option(e.getMessage).getOrElse("WorkspaceSummary failed"))))
        }
  end call

  private def dismiss(c: io.circe.HCursor, project: String): IO[Either[ToolError, String]] =
    val workspaceId = c.get[String]("workspaceId").getOrElse("")
    val headCommit = c.get[String]("headCommit").getOrElse("")
    if workspaceId.trim.isEmpty || headCommit.trim.isEmpty then
      IO.pure(Left(ToolError("dismiss-suggestion needs workspaceId and headCommit")))
    else
      IO.blocking(WorkspaceAdvisor.default.dismiss(workspaceId, headCommit)).map {
        case Left(error) => Left(ToolError(error))
        case Right(row) =>
          Right(
            Json
              .obj(
                "dismissed" -> true.asJson,
                "suggestion" -> row.asJson,
                "note" -> "the dismissed suggestion will not be re-suggested for this commit; real merge requests are untouched".asJson
              )
              .noSpaces
          )
      }
  end dismiss

  /** One repository's coordination picture (blocking; every leg fails soft into an explicit marker). */
  private def summary(repository: String): String =
    val ledger = WorkspaceLedger.default
    val snapshot = ledger.snapshot
    val advisor = WorkspaceAdvisor.default
    val rowsEither = advisor.rows()
    val suggestions = rowsEither match
      case Right(rows) => rows.filter(_.repository == repository)
      case Left(_)     => Nil // surfaced as the advisor error below; the rest of the payload stays intact
    val facts = Try(WorkspaceFacts.forRepository(repository, snapshot)).getOrElse(io.circe.Json.obj())
    val merger = snapshot.mergers.find(_.repository == repository)
    Json
      .obj(
        "repository" -> repository.asJson,
        "revision" -> snapshot.revision.asJson,
        "baseline" -> ledger.baseline(repository).asJson,
        "workspaces" -> snapshot.workspaces
          .filter(_.repository == repository)
          .map(w =>
            Json
              .obj(
                "workspaceId" -> w.id.asJson,
                "branch" -> w.branch.asJson,
                "baseBranch" -> w.baseBranch.asJson,
                "baseCommit" -> w.baseCommit.asJson,
                "owner" -> w.owner.asJson,
                "ownerMode" -> w.ownerMode.asJson,
                "lifecycle" -> w.lifecycle.asJson,
                "activity" -> w.activity.asJson,
                "reservation" -> w.reservation.asJson,
                "facts" -> facts.hcursor.downField(w.id).focus.getOrElse(Json.Null)
              )
              .asJson
          )
          .asJson,
        "mergeRequests" -> snapshot.requests
          .filter(_.repository == repository)
          .map(r =>
            Json.obj(
              "requestId" -> r.id.asJson,
              "status" -> r.status.asJson,
              "origin" -> requestOrigin(r).asJson,
              "cancelRequested" -> r.cancelRequested.asJson,
              "items" -> r.items
                .map(i =>
                  Json.obj(
                    "workspaceId" -> i.workspaceId.asJson,
                    "phase" -> i.phase.asJson,
                    "sourceCommit" -> i.sourceCommit.asJson,
                    "targetBranch" -> i.targetBranch.asJson,
                    "mergeCommit" -> i.mergeCommit.asJson,
                    "verification" -> i.verification.asJson
                  )
                )
                .asJson
            )
          )
          .asJson,
        "suggestions" -> suggestions.asJson,
        "advisorError" -> rowsEither.left.toOption.asJson,
        "mergeRequestOrigins" -> snapshot.requests
          .filter(_.repository == repository)
          .map(r => r.id -> requestOrigin(r))
          .toMap
          .asJson,
        "merger" -> merger.asJson,
        "mergerInitialization" -> merger
          .flatMap(m => snapshot.mergerBootstraps.get(m.delegateId))
          .map(seed => Json.obj("status" -> seed.status.asJson, "error" -> seed.error.asJson))
          .asJson
      )
      .noSpaces
  end summary

end WorkspaceSummaryTool
