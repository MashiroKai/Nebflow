package nebflow.core.tools

import cats.effect.IO
import io.circe.JsonObject
import io.circe.syntax.*
import nebflow.actor.ActorRef
import nebflow.agent.{AgentCommand, AgentStatus}
import nebflow.core.{AskItem, AskOption, AskPreview, HeadlessMode, QuestionDependency}

object AskUserQuestionTool extends Tool:
  val name = "AskUserQuestion"

  val description =
    """Ask the user one or more questions. Each question can have predefined options or be open-ended.

Use this tool when you need user input to proceed. Never ask clarifying questions in plain text — use this tool instead.

Guidelines:
- For multiple-choice questions, provide clear label values and optional description for each option.
- When several answers may apply to the same question (e.g. "Which areas should we cover?"), set "multiple": true on that question — the user can check several options and the answer comes back as an array of the selected values. Use it only when the choices are genuinely non-exclusive.
- For open-ended questions, omit options so the user gets a free-text input.
- The UI always provides an "Other..." option so the user can type freely even for multiple-choice.
- Independent questions are shown together and can be answered at once.

Visual selection support (askuser-canvas direction C):
- Set `canvas` on a question (absolute file path) to auto-open a comparison page in the Canvas panel when the question appears.
- Set `preview` on an option to embed an inline thumbnail: `{"type": "swatch", "colors": ["#hex", ...]}` shows 1-5 color stripes; `{"type": "image", "src": "<url>"}` shows an image.

Conditional branching (dependsOn):
- Give the upstream question an `id`, then set `dependsOn: {"ref": "<id>", "equals": "<answer>"}` on the dependent question.
- The dependent question is only shown when the referenced answer matches `equals`.

Behavior:
- This tool blocks until the user responds. Your turn pauses and resumes automatically when the user answers."""

  val inputSchema = JsonObject.fromIterable(
    List(
      "type" -> "object".asJson,
      "properties" -> io.circe.Json.obj(
        "questions" -> io.circe.Json.obj(
          "type" -> "array".asJson,
          "description" -> "Questions to ask the user, each with its own options".asJson,
          "items" -> io.circe.Json.obj(
            "type" -> "object".asJson,
            "properties" -> io.circe.Json.obj(
              "question" -> io.circe.Json.obj("type" -> "string".asJson, "description" -> "The question to ask".asJson),
              "id" -> io.circe.Json.obj(
                "type" -> "string".asJson,
                "description" -> "Unique identifier for this question. Required when other questions depend on this one.".asJson
              ),
              "dependsOn" -> io.circe.Json.obj(
                "type" -> "object".asJson,
                "description" -> "Only show this question when the referenced question's answer matches. Use for conditional branching.".asJson,
                "properties" -> io.circe.Json.obj(
                  "ref" -> io.circe.Json
                    .obj("type" -> "string".asJson, "description" -> "The id of the question this depends on".asJson),
                  "equals" -> io.circe.Json.obj(
                    "type" -> "string".asJson,
                    "description" -> "The answer value that must match for this question to appear".asJson
                  )
                ),
                "required" -> io.circe.Json.arr("ref".asJson, "equals".asJson)
              ),
              "multiple" -> io.circe.Json.obj(
                "type" -> "boolean".asJson,
                "description" ->
                  "Allow selecting several options (checkboxes). Default false = single choice. The answer for this question is returned as an array of the selected option values.".asJson
              ),
              "canvas" -> io.circe.Json.obj(
                "type" -> "string".asJson,
                "description" -> "Optional absolute path to a comparison page that is auto-opened in the Canvas panel when this question appears.".asJson
              ),
              "options" -> io.circe.Json.obj(
                "type" -> "array".asJson,
                "description" -> "Predefined choices for this question".asJson,
                "items" -> io.circe.Json.obj(
                  "type" -> "object".asJson,
                  "properties" -> io.circe.Json.obj(
                    "label" -> io.circe.Json
                      .obj("type" -> "string".asJson, "description" -> "Short option label".asJson),
                    "description" -> io.circe.Json
                      .obj("type" -> "string".asJson, "description" -> "Optional explanation".asJson),
                    "preview" -> io.circe.Json.obj(
                      "type" -> "object".asJson,
                      "description" -> "Optional inline preview for this option: {type:'swatch', colors:[...]} shows 1-5 color stripes; {type:'image', src:'<url>'} shows an image thumbnail. Omit for no preview.".asJson,
                      "properties" -> io.circe.Json.obj(
                        "type" -> io.circe.Json.obj(
                          "type" -> "string".asJson,
                          "description" -> "'swatch' or 'image'".asJson
                        ),
                        "colors" -> io.circe.Json.obj(
                          "type" -> "array".asJson,
                          "description" -> "For swatch: 1-5 CSS color strings, displayed as equal-width stripes".asJson,
                          "items" -> io.circe.Json.obj("type" -> "string".asJson)
                        ),
                        "src" -> io.circe.Json.obj(
                          "type" -> "string".asJson,
                          "description" -> "For image: the thumbnail URL".asJson
                        )
                      )
                    )
                  ),
                  "required" -> io.circe.Json.arr("label".asJson)
                )
              )
            ),
            "required" -> io.circe.Json.arr("question".asJson)
          )
        )
      ),
      "required" -> io.circe.Json.arr("questions".asJson)
    )
  )

  def summarize(input: JsonObject): String =
    val questions = input("questions").flatMap(_.asArray).getOrElse(Nil)
    if questions.isEmpty then "AskUser()"
    else if questions.length == 1 then
      val q = questions.head.hcursor.downField("question").as[String].getOrElse("")
      val short = if q.length > 40 then q.take(37) + "..." else q
      s"AskUser($short)"
    else s"AskUser(${questions.length} questions)"

  def summarizeResult(input: JsonObject, result: String): String =
    if result.length > 100 then result.take(97) + "..." else result

  /** Parse the raw `questions` JSON array into AskItems. Pure — spec-covered.
    * Malformed entries (empty question / empty option label) are skipped, matching
    * the historical behavior. `multiple` defaults to false when absent.
    */
  def parseItems(questionsJson: Seq[io.circe.Json]): List[AskItem] =
    questionsJson.flatMap { q =>
      val question = q.hcursor.downField("question").as[String].getOrElse("")
      if question.isBlank then None // skip malformed entries with empty question
      else
        val id = q.hcursor.downField("id").as[String].toOption
        val dependsOn = for
          dep <- q.hcursor.downField("dependsOn").focus
          ref <- dep.hcursor.downField("ref").as[String].toOption
          equals <- dep.hcursor.downField("equals").as[String].toOption
        yield QuestionDependency(ref, equals)
        val multiple = q.hcursor.downField("multiple").as[Boolean].getOrElse(false)
        val canvas = q.hcursor.downField("canvas").as[String].toOption
        val options = q.hcursor.downField("options").as[List[io.circe.Json]].getOrElse(Nil)
        val opts = options.flatMap { o =>
          val label = o.hcursor.downField("label").as[String].getOrElse("")
          if label.isBlank then None // skip options with empty label
          else
            val desc = o.hcursor.downField("description").as[String].toOption
            val preview = for
              pv <- o.hcursor.downField("preview").focus
              t <- pv.hcursor.downField("type").as[String].toOption
            yield AskPreview(
              `type` = t,
              colors = pv.hcursor.downField("colors").as[List[String]].toOption,
              src = pv.hcursor.downField("src").as[String].toOption
            )
            Some(AskOption(label, desc, preview))
        }
        Some(AskItem(question, opts, id = id, dependsOn = dependsOn, multiple = multiple, canvas = canvas))
      end if
    }.toList

  /** Error text returned when headless mode blocks an AskUser call. */
  val HeadlessErrorMessage =
    "Headless mode: no interactive user available — decide autonomously and continue with your best judgment."

  /** Headless guard: NEBFLOW_HEADLESS=1 (benchmark mode) means no interactive
    * user — dispatching AgentCommand.AskUser would park the run on a reply
    * that never arrives. Returning a ToolError instead tells the agent to
    * decide autonomously and continue. Pure (flag passed in) so both branches
    * are spec-covered; the call touchpoint binds HeadlessMode.enabled.
    */
  def askGuard(headless: Boolean = HeadlessMode.enabled): Option[ToolError] =
    if headless then Some(ToolError(HeadlessErrorMessage)) else None

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    // Headless benchmark mode: fail fast at the entry — no AskUser dispatch,
    // no wait; the error message pushes the agent to proceed on its own.
    askGuard() match
      case Some(err) => IO.pure(Left(err))
      case None      => askUser(input, ctx)

  private def askUser(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    val questionsJson = input("questions").flatMap(_.asArray).getOrElse(Nil)

    if questionsJson.isEmpty then IO.pure(Left(ToolError("No valid questions provided")))
    else
      val items = parseItems(questionsJson)

      if items.isEmpty then IO.pure(Left(ToolError("No valid questions provided")))
      else
        ctx.agentActorRef match
          case Some(agentRef) =>
            val requestId = java.util.UUID.randomUUID().toString.take(8)
            agentRef
              .?(
                (replyTo: ActorRef[List[String]]) => AgentCommand.AskUser(requestId, items, Some(replyTo)),
                timeout = None
              )
              .flatMap { answers =>
                // R2 closure (wait-timeout-fix): the answer landed — paired
                // un-mark for the WaitingForUser status set by the agent's
                // AskUser handler. Fresh activity stamp so the TaskStuckWatcher
                // idle window restarts from the answer, and the session is back
                // under true-stuck coverage while the turn continues.
                restoreRegistryAfterAnswer(ctx).as(Right(formatAnswer(items, answers)))
              }
          case None =>
            IO.pure(Left(ToolError("AskUserQuestion requires agent actor")))
      end if
    end if
  end askUser

  /** R2 (wait-timeout-fix): paired un-mark for the WaitingForUser status the
    * agent's AskUser handler set when this question was dispatched. Registry
    * entry present → status=Processing + fresh lastActivityMs (same touch
    * semantics as AgentCore.touchRegistryActivity — never creates a ghost
    * row). No-op when sharedResources/sessionId are absent (harness calls).
    * Failure-safe: a registry touch must never fail the user's answer.
    * private[tools]: ProjectCreateTool's path panel dispatches the same
    * AgentCommand.AskUser and must pair the same un-mark (one shared
    * implementation — no divergent copy). */
  private[tools] def restoreRegistryAfterAnswer(ctx: ToolContext): IO[Unit] =
    (ctx.sharedResources, ctx.sessionId) match
      case (Some(res), Some(sid)) =>
        val now = System.currentTimeMillis()
        res.agentRegistry.modify { m =>
          m.get(sid) match
            case Some(rec) => (m.updated(sid, rec.copy(status = AgentStatus.Processing, lastActivityMs = now)), ())
            case None      => (m, ())
        }.handleErrorWith(_ => IO.unit)
      case _ => IO.unit

  /** Normalize a multi-select answer for the LLM: canonical compact JSON array
    * (`["A","B"]`). The frontend serializes a multi-select answer as a JSON
    * array string in its answers slot (the wire stays List[String], one slot
    * per question). Non-JSON payloads (older frontends, joined text) pass
    * through unchanged.
    */
  private def formatMultiple(raw: String): String =
    io.circe.parser.decode[List[String]](raw) match
      case Right(values) => values.asJson.noSpaces
      case Left(_)       => raw

  /** Format user answers for display. */
  def formatAnswer(items: List[AskItem], answers: List[String]): String =
    def present(item: Option[AskItem], raw: String): String =
      item match
        case Some(i) if i.multiple => formatMultiple(raw)
        case _                     => raw
    if items.size <= 1 then
      answers.headOption.filter(_.nonEmpty).map(a => present(items.headOption, a)).getOrElse("")
    else
      items.zipWithIndex
        .map { case (item, idx) =>
          val raw = answers.lift(idx).getOrElse("")
          val answer = if raw.isEmpty then "(skipped)" else present(Some(item), raw)
          s"${idx + 1}. ${item.question.take(60)}\n   → $answer"
        }
        .mkString("\n")
end AskUserQuestionTool
