package nebflow.core.tools

import cats.effect.IO
import io.circe.{Json, JsonObject}
import io.circe.syntax.*
import nebflow.core.delegate.{MergerFacts, WorkspaceLedger}

object MergerReportTool extends Tool:
  val Name = "merger_report"
  def name: String = Name

  def description: String =
    "Use action=inspect to read your persisted requests, workspace state and the read-only reconcile reading of every non-terminal request (integrated / resumable / unknown); never re-merge an integrated source. " +
      "Use action=report to report one authorized workspace integration step as the project's recorded merger. " +
      "Use the request's current revision and a stable eventId. Process workspaces in order; pause on failure. " +
      "This tool never merges or deletes files. Completion requires a two-parent merge containing the authorized source, " +
      "a JSON verification record with status=passed, sourceCommit, mergeCommit, the command that ran, exitCode 0 and ranAt, and a removed worktree. " +
      "When the source is already contained in the target without this flow's merge commit (externally integrated, or recovered after a crash), report integration=\"source-ancestor\" at merged-cleanup-pending: cleanup only, no mergeCommit, never merge again. " +
      "After merged-cleanup-pending retry cleanup only; do not merge again. Cancellation does not undo a merge."

  def inputSchema: JsonObject = JsonObject(
    "type" -> "object".asJson,
    "properties" -> Json.obj(
      "action" -> Json.obj("type" -> "string".asJson, "enum" -> List("inspect", "report").asJson),
      "requestId" -> Json.obj("type" -> "string".asJson),
      "eventId" -> Json.obj("type" -> "string".asJson),
      "expectedRevision" -> Json.obj("type" -> "integer".asJson),
      "workspaceId" -> Json.obj("type" -> "string".asJson),
      "phase" -> Json.obj(
        "type" -> "string".asJson,
        "enum" -> List(
          "checking",
          "merging",
          "awaiting-user",
          "validating",
          "merged-cleanup-pending",
          "completed",
          "failed",
          "cancelled"
        ).asJson
      ),
      "mergeCommit" -> Json.obj("type" -> "string".asJson),
      "verification" -> Json.obj("type" -> "string".asJson),
      "integration" -> Json.obj("type" -> "string".asJson, "enum" -> List("merge-commit", "source-ancestor").asJson)
    ),
    "required" -> List("action").asJson
  )
  def summarize(input: JsonObject): String = s"merger_report(${input("phase").flatMap(_.asString).getOrElse("?")})"
  def summarizeResult(input: JsonObject, result: String): String = result.take(200)

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] = IO
    .blocking {
      val ledger = WorkspaceLedger.default
      val caller = ctx.sessionId.getOrElse("")
      if !ledger.snapshot.mergers.exists(_.delegateId == caller) then
        Left(ToolError("Only a recorded project merger may report"))
      else if input("action").flatMap(_.asString).contains("inspect") then
        val snapshot = ledger.snapshot
        val requests = snapshot.requests.filter(_.mergerId == caller)
        val ids = requests.flatMap(_.items.map(_.workspaceId)).toSet
        Right(
          Json
            .obj(
              "requests" -> requests.asJson,
              "workspaces" -> snapshot.workspaces.filter(w => ids(w.id)).asJson,
              "reconcile" -> nebflow.core.delegate.RequestReconcile.forMerger(caller, ledger).asJson
            )
            .noSpaces
        )
      else
        val c = Json.fromJsonObject(input).hcursor
        val decoded = for
          request <- c.get[String]("requestId")
          event <- c.get[String]("eventId")
          revision <- c.get[Long]("expectedRevision")
          workspace <- c.get[String]("workspaceId")
          phase <- c.get[String]("phase")
          merge <- c.get[Option[String]]("mergeCommit")
          verification <- c.get[Option[String]]("verification")
          integration <- c.get[Option[String]]("integration")
        yield (
          request,
          WorkspaceLedger.MergeReport(event, revision, workspace, phase, merge, verification, integration)
        )
        decoded.left.map(e => ToolError(e.getMessage)).flatMap { case (id, event) =>
          ledger.reportMerge(id, caller, event)(MergerFacts.validate).left.map(ToolError(_)).map(_.asJson.noSpaces)
        }
      end if
    }
    .handleError(e => Left(ToolError(Option(e.getMessage).getOrElse("Merger report failed"))))
end MergerReportTool
