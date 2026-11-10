package nebflow.core.tools

import cats.effect.IO
import io.circe.{Json, JsonObject}
import io.circe.syntax.*
import nebflow.core.delegate.MergeRequests

object MergerRequestTool extends Tool:
  val Name = "MergerRequest"
  def name: String = Name
  def description: String = "Use action=submit to submit explicitly authorized idle workspaces for merging; the background merger worker picks them up serially, so no live merger session is required. Use a stable requestId and cite the user's authorization. " +
    "This reserves the exact workspace list and current source commits atomically; busy workspaces are refused. Create Delegate(mode=merger, project=...) first. " +
    "Use action=cancel to request a safe stop of a recorded request; this does not undo merges. " +
    "Authorization includes merging, verification and worktree removal; never push, publish, tag or change versions."
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
        (c.get[String]("project"), c.get[String]("requestId")) match
          case (Right(project), Right(id)) =>
            return MergeRequests.cancel(project, id).map(_.left.map(ToolError(_)))
          case _ => return IO.pure(Left(ToolError("Missing project or requestId")))
      val parsed = for
        project <- c.get[String]("project")
        id <- c.get[String]("requestId")
        ids <- c.get[List[String]]("workspaceIds")
        authorization <- c.get[String]("authorization")
      yield (project, id, ids, authorization)
      parsed match
        case Right((project, id, ids, authorization)) =>
          MergeRequests.submit(project, id, ids, authorization).map(_.left.map(ToolError(_)))
        case _ => IO.pure(Left(ToolError("Missing request fields")))
