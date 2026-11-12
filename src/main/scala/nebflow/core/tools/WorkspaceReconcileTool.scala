package nebflow.core.tools

import cats.effect.IO
import io.circe.{Json, JsonObject}
import io.circe.syntax.*
import nebflow.core.delegate.{WorkspaceLedger, WorkspaceReconcile}

/** Root-only structured confirmation; no assistant prose is interpreted as proof. */
object WorkspaceReconcileTool extends Tool:
  val Name = "WorkspaceReconcile"
  def name: String = Name
  def description: String = "Root-only explicit completion confirmation for exactly one registered workspace. Requires owner, exact path, expected HEAD/revision, explicit root confirmation and acceptance evidence reference. Validates clean Git state and live occupancy; never merges or moves refs."
  def inputSchema: JsonObject = JsonObject("type" -> "object".asJson, "properties" -> Json.obj(
    "operationId" -> Json.obj("type" -> "string".asJson), "workspaceId" -> Json.obj("type" -> "string".asJson),
    "owner" -> Json.obj("type" -> "string".asJson), "expectedPath" -> Json.obj("type" -> "string".asJson),
    "expectedHead" -> Json.obj("type" -> "string".asJson), "expectedRevision" -> Json.obj("type" -> "integer".asJson),
    "rootConfirmation" -> Json.obj("type" -> "string".asJson), "acceptanceEvidence" -> Json.obj("type" -> "string".asJson),
    "recoverRunning" -> Json.obj("type" -> "boolean".asJson, "description" -> "Root-explicit stale-running recovery: owner session truly terminal, live occupancy zero; atomically transitions the running seat to idle with the completion in one locked transaction".asJson)
  ), "required" -> List("operationId", "workspaceId", "owner", "expectedPath", "expectedHead", "expectedRevision", "rootConfirmation", "acceptanceEvidence").asJson)
  def summarize(input: JsonObject): String = s"WorkspaceReconcile(${input("workspaceId").flatMap(_.asString).getOrElse("?")})"
  def summarizeResult(input: JsonObject, result: String): String = result.take(200)
  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    if !ctx.isRootAgent then IO.pure(Left(ToolError("WorkspaceReconcile is limited to the main agent")))
    else
      val c = Json.fromJsonObject(input).hcursor
      val parsed = for
        id <- c.get[String]("operationId")
        workspaceId <- c.get[String]("workspaceId")
        owner <- c.get[String]("owner")
        path <- c.get[String]("expectedPath")
        head <- c.get[String]("expectedHead")
        revision <- c.get[Long]("expectedRevision")
        confirmation <- c.get[String]("rootConfirmation")
        evidence <- c.get[String]("acceptanceEvidence")
        recoverRunning <- c.get[Option[Boolean]]("recoverRunning")
      yield WorkspaceLedger.CompletionConfirmation(id, workspaceId, owner, path, head, revision, evidence, confirmation, recoverRunning = recoverRunning.getOrElse(false))
      parsed match
        case Left(_) => IO.pure(Left(ToolError("Missing structured reconciliation fields")))
        case Right(request) => WorkspaceReconcile.default.confirm(request).map(_.left.map(ToolError(_)).map(_.asJson.noSpaces))
end WorkspaceReconcileTool
