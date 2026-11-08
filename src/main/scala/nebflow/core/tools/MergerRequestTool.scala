package nebflow.core.tools

import cats.effect.IO
import io.circe.{Json, JsonObject}
import io.circe.syntax.*
import nebflow.core.delegate.MergeRequests

object MergerRequestTool extends Tool:
  val Name = "merger_request"
  def name: String = Name
  def description: String = "Use action=cancel to request a safe stop via the same persistent channel as the panel; this does not undo merges. Use action=submit to submit explicitly authorized idle workspaces to the project's existing merger. Use a stable requestId and cite the user's authorization. " +
    "This reserves the exact workspace list and current source commits atomically; busy workspaces are refused. Create Delegate(mode=merger, project=...) first. " +
    "Authorization includes merging, verification and worktree removal; never push, publish, tag or change versions. A non-live merger stays queued for manual resume."
  def inputSchema: JsonObject = JsonObject("type" -> "object".asJson,
    "properties" -> Json.obj(
      "action" -> Json.obj("type" -> "string".asJson, "enum" -> List("submit", "cancel").asJson),
      "project" -> Json.obj("type" -> "string".asJson), "requestId" -> Json.obj("type" -> "string".asJson),
      "workspaceIds" -> Json.obj("type" -> "array".asJson, "items" -> Json.obj("type" -> "string".asJson)),
      "authorization" -> Json.obj("type" -> "string".asJson)),
    "required" -> List("project", "requestId").asJson)
  def summarize(input: JsonObject): String = Name
  def summarizeResult(input: JsonObject, result: String): String = result.take(200)
  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    if !ctx.isRootAgent then IO.pure(Left(ToolError("Only the main agent may submit an authorized merge request")))
    else
      val c = Json.fromJsonObject(input).hcursor
      if input("action").flatMap(_.asString).contains("cancel") then
        (c.get[String]("project"), c.get[String]("requestId"), ctx.sharedResources) match
          case (Right(project), Right(id), Some(resources)) =>
            return MergeRequests.cancel(project, id, resources).map(_.left.map(ToolError(_)))
          case _ => return IO.pure(Left(ToolError("Missing project, requestId or runtime")))
      val parsed = for
        project <- c.get[String]("project")
        id <- c.get[String]("requestId")
        ids <- c.get[List[String]]("workspaceIds")
        authorization <- c.get[String]("authorization")
      yield (project, id, ids, authorization)
      (parsed, ctx.sharedResources) match
        case (Right((project, id, ids, authorization)), Some(resources)) =>
          MergeRequests.submit(project, id, ids, authorization, resources).map(_.left.map(ToolError(_)))
        case _ => IO.pure(Left(ToolError("Missing request fields or runtime")))
