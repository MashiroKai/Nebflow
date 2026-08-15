package nebflow.core.tools

import cats.effect.{IO, Ref}
import io.circe.syntax.*
import io.circe.{Json, JsonObject}

/** Stored FlowReport payload: verdict + output + structured slots. */
case class FlowReportData(
  verdict: String,
  output: String,
  slots: JsonObject = JsonObject.empty
)

/**
 * In-memory store for FlowReport results, keyed by session ID.
 * FlowDagExecutor reads the report after agent completion to get verdict + output + slots.
 */
object FlowReportStore:
  private val reports: Ref[IO, Map[String, FlowReportData]] = Ref.unsafe(Map.empty)

  def set(sessionId: String, data: FlowReportData): IO[Unit] =
    reports.update(_.updated(sessionId, data))

  def get(sessionId: String): IO[Option[FlowReportData]] =
    reports.get.map(_.get(sessionId))

  def remove(sessionId: String): IO[Unit] =
    reports.update(_ - sessionId)
end FlowReportStore

/**
 * FlowReport tool — called by flow agents to report their verdict, output and
 * structured slots to the pipeline. Replaces free-text parsing for switch routing.
 *
 * The verdict value determines which node runs next in the DAG.
 * The output is passed to downstream nodes as $<nodeId>.output.
 * The slots are passed as $<nodeId>.slots.<field> (typed per the node's
 * `outputs` declaration in flow.json).
 *
 * R8-P1 contract validation (always on when the agent carries a FlowNodeContract):
 * verdict must be a declared switch case key; slots must match the node's
 * `outputs` schema (exact keys, correct types). Violations return a ToolError
 * so the agent self-corrects within the same turn — the cheapest place to fix
 * a structured-output mistake (one tool round-trip, not a mis-routed flow).
 */
object FlowReportTool extends Tool:
  val name = "FlowReport"

  val description =
    """Report your result to the flow pipeline. You MUST call this tool before finishing — your turn will not complete without it.

## Parameters
- verdict (string, required): Your assessment. Allowed values:
  - Switch node (has onComplete.switch in flow.json): exactly one of the case keys declared there — the flow's contract is the single authority (e.g. "pass" | "fix", "merge" | "reject", "ok" | "error"). See "This node's contract" below if present.
  - No switch routing (sequential node): "done".
  - Generic binary outcome: "ok" / "error".
- output (string, required): Your work output — findings, code changes, analysis results, etc.
- slots (object, optional): Typed fields declared in this node's `outputs` contract (if any). Keys and types must match the declaration exactly — "string" fields take a string, "array" fields take a JSON array.

## Rules
- Call this tool exactly once at the end of your work.
- The verdict value determines which node runs next in the pipeline.
- Put your full findings/analysis in the output parameter — this is what downstream nodes receive as $<nodeId>.output.
- If the node declares an outputs contract, fill every declared slot — downstream nodes read them as $<nodeId>.slots.<field>.
- A rejected call (ToolError) lists what the contract expects — fix the values and call again in the same turn."""

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
        ),
        "slots" -> Json.obj(
          "type" -> "object".asJson,
          "description" -> "Typed fields declared in this node's outputs contract (if any). Keys and types must match the declaration exactly.".asJson
        )
      ),
      "required" -> Json.arr("verdict".asJson, "output".asJson)
    )
  )

  def summarize(input: JsonObject): String =
    val verdict = input("verdict").flatMap(_.asString).getOrElse("?")
    val slotKeys = input("slots").flatMap(_.asObject).map(_.keys.toList.sorted.mkString(",")).getOrElse("")
    s"FlowReport(verdict=$verdict${if slotKeys.nonEmpty then s", slots=$slotKeys" else ""})"

  def summarizeResult(input: JsonObject, result: String): String = result

  /**
   * Validate a call against the node contract. Returns the canonical verdict
   * (declared case key) and cleaned error list; empty list = valid.
   *
   * Rules:
   *  - verdict must (case-insensitively) equal a declared case key — matched
   *    key is canonicalized so the stored verdict equals the flow.json key;
   *  - every declared slot must be present with the declared type;
   *  - no undeclared slot keys.
   */
  private def validateContract(
    verdict: String,
    slotsOpt: Option[JsonObject],
    contract: nebflow.agent.FlowNodeContract
  ): (String, List[String]) =
    val verdictErrors =
      if contract.caseKeys.isEmpty then Nil
      else
        contract.caseKeys.find(k => k.trim.toLowerCase == verdict.trim.toLowerCase) match
          case Some(canonical) => Nil
          case None =>
            List(s"verdict must be one of: ${contract.caseKeys.toList.sorted.mkString(" | ")} (got \"$verdict\")")
    val canonicalVerdict =
      contract.caseKeys.find(k => k.trim.toLowerCase == verdict.trim.toLowerCase).getOrElse(verdict)

    val provided = slotsOpt.getOrElse(JsonObject.empty)
    val slotErrors = List.newBuilder[String]
    // missing / mistyped declared slots
    contract.slots.foreach { (slot, declType) =>
      provided(slot) match
        case None => slotErrors += s"slots.$slot is declared as $declType but missing"
        case Some(v) =>
          val typeOk = declType match
            case "string" => v.isString
            case "array"  => v.isArray
            case other    => false // loader rejects unknown types; defensive here
          if !typeOk then slotErrors += s"slots.$slot must be of type $declType (got ${jsonTypeName(v)})"
    }
    // undeclared extra slots
    provided.keys.foreach { key =>
      if !contract.slots.contains(key) then slotErrors += s"slots.$key is not declared in this node's outputs contract"
    }
    (canonicalVerdict, verdictErrors ++ slotErrors.result())

  /** Json → type name for error messages. */
  private def jsonTypeName(v: Json): String =
    if v.isNull then "null"
    else if v.isBoolean then "boolean"
    else if v.isNumber then "number"
    else if v.isString then "string"
    else if v.isArray then "array"
    else "object"

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    val verdict = input("verdict").flatMap(_.asString).getOrElse("done")
    val output = input("output").flatMap(_.asString).getOrElse("")
    val slotsOpt = input("slots").flatMap(_.asObject) // absent or non-object → None
    val sessionId = ctx.sessionId.getOrElse("unknown")
    ctx.agentDef.flatMap(_.flowContract) match
      case Some(contract) =>
        val (canonical, errors) = validateContract(verdict, slotsOpt, contract)
        if errors.nonEmpty then
          IO.pure(
            Left(
              ToolError(
                s"FlowReport rejected by this node's contract — fix and call again:\n- ${errors.mkString("\n- ")}"
              )
            )
          )
        else
          FlowReportStore.set(sessionId, FlowReportData(canonical, output, slotsOpt.getOrElse(JsonObject.empty))) *>
            IO.pure(
              Right(
                s"Reported: verdict=$canonical, output length=${output.length}, slots=${slotsOpt.map(_.keys.toList.sorted.mkString(",")).getOrElse("")}"
              )
            )
      case None =>
        // No contract (non-flow agent, or flow node without switch/outputs):
        // legacy behavior — accept any verdict, pass slots through untouched.
        FlowReportStore.set(sessionId, FlowReportData(verdict, output, slotsOpt.getOrElse(JsonObject.empty))) *>
          IO.pure(Right(s"Reported: verdict=$verdict, output length=${output.length}"))

end FlowReportTool
