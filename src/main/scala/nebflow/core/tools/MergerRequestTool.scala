package nebflow.core.tools

import cats.effect.IO
import io.circe.{Json, JsonObject}
import io.circe.syntax.*
import nebflow.core.delegate.{MergeRequests, MergerRecovery}

object MergerRequestTool extends Tool:
  val Name = "MergerRequest"

  /**
   * Cancelled-merger recovery batch: release a merger identity whose delegate session
   * was cancelled, so the replacement can be minted through the normal creation entry
   * (Delegate(mode=merger)). Root-gated by the in-tool identity refusal below.
   */
  val ReleaseAction = "releaseMerger"

  def name: String = Name
  def description: String = "Use action=submit to submit explicitly authorized idle workspaces for merging; the background merger worker picks them up serially, so no live merger session is required. Use a stable requestId and cite the user's authorization. " +
    "This reserves the exact workspace list and current source commits atomically; busy workspaces are refused. Create Delegate(mode=merger, project=...) first. " +
    "Use action=cancel to request a safe stop of a recorded request; this does not undo merges. " +
    "Use action=releaseMerger when the recorded project merger's delegate was CANCELLED (Delegate(mode=merger) keeps returning its dead address): it releases that identity only with proof of the cancel tombstone, no in-flight merge request and no merger-occupied workspace — busy or unclear states are refused. After a release, mint the replacement normally with Delegate(mode=merger, project=...); recorded requests and their facts are preserved. " +
    "Authorization includes merging, verification and worktree removal; never push, publish, tag or change versions."
  def inputSchema: JsonObject = JsonObject("type" -> "object".asJson,
    "properties" -> Json.obj(
      "action" -> Json.obj("type" -> "string".asJson, "enum" -> List("submit", "cancel", ReleaseAction).asJson),
      "project" -> Json.obj("type" -> "string".asJson), "requestId" -> Json.obj("type" -> "string".asJson),
      "workspaceIds" -> Json.obj("type" -> "array".asJson, "items" -> Json.obj("type" -> "string".asJson)),
      "authorization" -> Json.obj("type" -> "string".asJson)),
    "required" -> List("project").asJson)
  def summarize(input: JsonObject): String = Name
  def summarizeResult(input: JsonObject, result: String): String = result.take(200)
  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    if !ctx.isRootAgent then IO.pure(Left(ToolError("Only the main agent may use the MergerRequest tool")))
    else
      val c = Json.fromJsonObject(input).hcursor
      val action = input("action").flatMap(_.asString)
      if action.contains(ReleaseAction) then
        c.get[String]("project") match
          case Right(project) =>
            ctx.sharedResources match
              case None => IO.pure(Left(ToolError("MergerRequest releaseMerger needs the agent runtime; refusing (fail-closed)")))
              case Some(resources) =>
                MergerRecovery
                  .releaseForProject(project, MergerRecovery.isLiveOf(resources.agentRegistry))
                  .map {
                    case Left(error) => Left(ToolError(error))
                    case Right(release) =>
                      Right(
                        release.asJson.deepMerge(
                          Json.obj(
                            "next" -> "Create the replacement with Delegate(mode=merger, project=...) — the normal creation path; recorded requests and their facts were preserved.".asJson
                          )
                        ).noSpaces
                      )
                  }
          case _ => IO.pure(Left(ToolError("Missing project")))
      else if action.contains("cancel") then
        (c.get[String]("project"), c.get[String]("requestId")) match
          case (Right(project), Right(id)) =>
            MergeRequests.cancel(project, id).map(_.left.map(ToolError(_)))
          case _ => IO.pure(Left(ToolError("Missing project or requestId")))
      else
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
