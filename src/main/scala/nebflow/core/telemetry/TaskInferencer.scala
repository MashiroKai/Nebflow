package nebflow.core.telemetry

import io.circe.{Json, JsonObject}

/**
 * Infers the user's task type from the tool usage profile of a session.
 *
 * This runs client-side on session_end — no content is examined, only tool call counts.
 */
object TaskInferencer:

  enum InferredTask(val label: String):
    case Coding extends InferredTask("coding")
    case Debug extends InferredTask("debug")
    case CodeReview extends InferredTask("code_review")
    case Research extends InferredTask("research")
    case Ops extends InferredTask("ops")
    case ProjectInit extends InferredTask("project_init")
    case Conversation extends InferredTask("conversation")

  /**
   * Infer task type from tool usage counts.
   *
   * @param toolProfile Map of tool name -> call count
   * @param turnCount   Number of user-AI turns in the session
   * @return Inferred task label
   */
  def infer(toolProfile: Map[String, Int], turnCount: Int): String =
    val total = toolProfile.values.sum
    if total == 0 then return InferredTask.Conversation.label

    val edit = toolProfile.getOrElse("Edit", 0) + toolProfile.getOrElse("Write", 0)
    val read = toolProfile.getOrElse("Read", 0)
    val grep = toolProfile.getOrElse("Grep", 0)
    val bash = toolProfile.getOrElse("Bash", 0)
    val web =
      toolProfile.getOrElse("WebSearch", 0) + toolProfile.getOrElse("WebFetch", 0) + toolProfile.getOrElse("Curl", 0)
    val write = toolProfile.getOrElse("Write", 0)
    val readSearch = read + grep

    // Rule 1: Web research dominant
    if pct(web, total) > 0.50 then return InferredTask.Research.label

    // Rule 2: System ops
    if pct(bash, total) > 0.50 then return InferredTask.Ops.label

    // Rule 3: Project initialization — lots of Write, little Read
    if pct(write, total) > 0.40 && pct(read, total) < 0.20 then return InferredTask.ProjectInit.label

    // Rule 4: Active coding — significant edits relative to reading
    if edit > 0 && pct(edit, readSearch) > 0.4 then return InferredTask.Coding.label

    // Rule 5: Debug — heavy search, some edits
    if pct(readSearch, total) > 0.50 && edit > 0 && edit <= 3 then return InferredTask.Debug.label

    // Rule 6: Code review / understanding — mostly reading
    if pct(readSearch, total) > 0.60 && edit < 2 then return InferredTask.CodeReview.label

    // Rule 7: Some editing activity
    if edit > 0 then return InferredTask.Coding.label

    // Default
    InferredTask.Conversation.label

  end infer

  private def pct(part: Int, total: Int): Double =
    if total == 0 then 0.0 else part.toDouble / total

  /** Build a tool_profile JsonObject suitable for the session_end event. */
  def toolProfileJson(profile: Map[String, Int]): JsonObject =
    JsonObject.fromMap(profile.map { case (k, v) => k -> io.circe.Json.fromInt(v) })

end TaskInferencer
