package nebflow.core.tools

import cats.effect.IO
import cats.syntax.all.*
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.core.flow.{FlowVerifyRegistry, VerifyResult}
import nebflow.core.NebflowLogger

/** FlowVerify — report verification result for a flow step.
  *
  * This tool is mandatory for flow verify agents. The verify agent MUST call
  * it to report whether the work passed or failed. If the agent finishes
  * without calling this tool, the flow will fail and retry.
  */
object FlowVerifyTool extends Tool:
  private val logger = NebflowLogger(getClass)

  val name = "FlowVerify"

  val description =
    """Report the verification result for a flow step. You MUST call this tool to report whether the work passed or failed.

- Set "passed" to true if the work meets all requirements
- Set "passed" to false if there are issues
- Provide a concise "summary" of what was verified and any issues found

This tool is mandatory. If you finish without calling it, the verification will fail and the flow will retry."""

  val inputSchema = JsonObject(
    "type" -> "object".asJson,
    "properties" -> Json.obj(
      "passed" -> Json.obj(
        "type" -> "boolean".asJson,
        "description" -> "true if verification passed, false if issues found.".asJson
      ),
      "summary" -> Json.obj(
        "type" -> "string".asJson,
        "description" -> "Concise summary of results, findings, and any issues.".asJson
      )
    ),
    "required" -> Json.arr("passed".asJson, "summary".asJson)
  )

  def summarize(input: JsonObject): String =
    val passed = input("passed").flatMap(_.asBoolean).getOrElse(false)
    s"FlowVerify(${if passed then "PASS" else "FAIL"})"

  def summarizeResult(input: JsonObject, result: String): String = result

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    val passed = input("passed").flatMap(_.asBoolean).getOrElse(false)
    val summary = input("summary").flatMap(_.asString).getOrElse("")
    val agentPath = ctx.agentActorRef.map(_.path.toString).getOrElse("")

    if agentPath.isBlank then
      IO.pure(Left(ToolError("Cannot determine agent identity for FlowVerify.")))
    else
      for
        success <- FlowVerifyRegistry.complete(agentPath, VerifyResult(passed, summary))
        _ <- logger.info(s"FlowVerify called: passed=$passed, agent=$agentPath")
      yield
        if success then
          Right(if passed then "Verification PASSED." else s"Verification FAILED: $summary")
        else
          Left(ToolError("No pending flow verification for this agent. This tool is only for flow verify steps."))

end FlowVerifyTool
