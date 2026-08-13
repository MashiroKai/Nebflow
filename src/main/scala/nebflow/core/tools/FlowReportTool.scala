package nebflow.core.tools

import cats.effect.{IO, Ref}
import io.circe.syntax.*
import io.circe.{Json, JsonObject}

/**
 * In-memory store for FlowReport results, keyed by session ID.
 * FlowDagExecutor reads the report after agent completion to get verdict + output.
 */
object FlowReportStore:
  private val reports: Ref[IO, Map[String, (String, String)]] = Ref.unsafe(Map.empty)

  def set(sessionId: String, verdict: String, output: String): IO[Unit] =
    reports.update(_.updated(sessionId, (verdict, output)))

  def get(sessionId: String): IO[Option[(String, String)]] =
    reports.get.map(_.get(sessionId))

  def remove(sessionId: String): IO[Unit] =
    reports.update(_ - sessionId)
end FlowReportStore

/**
 * FlowReport tool — called by flow agents to report their verdict and output
 * to the pipeline. Replaces free-text parsing for switch routing.
 *
 * The verdict value determines which node runs next in the DAG.
 * The output is passed to downstream nodes as $<nodeId>.output.
 */
object FlowReportTool extends Tool:
  val name = "FlowReport"

  val description =
    """Report your result to the flow pipeline. You MUST call this tool before finishing — your turn will not complete without it.

## Parameters
- verdict (string, required): Your assessment. Allowed values:
  - Switch node (has onComplete.switch in flow.json): exactly one of the case keys declared there — the flow's contract is the single authority (e.g. "pass" | "fix", "merge" | "reject", "ok" | "error").
  - No switch routing (sequential node): "done".
  - Generic binary outcome: "ok" / "error".
- output (string, required): Your work output — findings, code changes, analysis results, etc.

## Rules
- Call this tool exactly once at the end of your work.
- The verdict value determines which node runs next in the pipeline.
- Put your full findings/analysis in the output parameter — this is what downstream nodes receive."""

  val inputSchema = JsonObject.fromIterable(
    List(
      "type" -> "object".asJson,
      "properties" -> Json.obj(
        "verdict" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "Assessment verdict. One of: the flow.json switch case keys for this node (authoritative), \"done\" for sequential nodes, or \"ok\"/\"error\" for generic binary outcomes.".asJson
        ),
        "output" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "Your full work output — findings, analysis, code changes, etc.".asJson
        )
      ),
      "required" -> Json.arr("verdict".asJson, "output".asJson)
    )
  )

  def summarize(input: JsonObject): String =
    val verdict = input("verdict").flatMap(_.asString).getOrElse("?")
    s"FlowReport(verdict=$verdict)"

  def summarizeResult(input: JsonObject, result: String): String = result

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    val verdict = input("verdict").flatMap(_.asString).getOrElse("done")
    val output = input("output").flatMap(_.asString).getOrElse("")
    val sessionId = ctx.sessionId.getOrElse("unknown")
    FlowReportStore.set(sessionId, verdict, output) *>
      IO.pure(Right(s"Reported: verdict=$verdict, output length=${output.length}"))

end FlowReportTool
