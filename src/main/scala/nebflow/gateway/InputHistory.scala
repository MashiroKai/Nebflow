package nebflow.gateway

import io.circe.{Json, JsonObject}

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Builder for `~/.nebflow/input_history.jsonl` entries. Extracted pure so the
 * field contract is testable: text/ts/type(/files)/sessionId/session/agent.
 * Context fields (sessionId, session, agent) are omitted when blank — old
 * entries without them stay naturally compatible (eco #9-P1).
 */
object InputHistory:

  private val TsFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

  def buildEntry(
    content: String,
    attachments: List[Json],
    sessionId: String,
    session: String,
    agent: String,
    now: LocalDateTime
  ): Json =
    val inputType =
      if attachments.nonEmpty then "file"
      else if content.length > 200 then "paste"
      else "input"
    val files = attachments.flatMap(_.hcursor.downField("name").as[String].toOption)
    var obj = JsonObject(
      "text" -> Json.fromString(content.take(2000)),
      "ts" -> Json.fromString(now.format(TsFormat)),
      "type" -> Json.fromString(inputType)
    )
    if files.nonEmpty then obj = obj.add("files", Json.fromValues(files.map(Json.fromString)))
    if sessionId.nonEmpty then obj = obj.add("sessionId", Json.fromString(sessionId))
    // Session display name; "-" is the "unknown" placeholder at the call site
    if session.nonEmpty && session != "-" then obj = obj.add("session", Json.fromString(session))
    if agent.nonEmpty then obj = obj.add("agent", Json.fromString(agent))
    Json.fromJsonObject(obj)

end InputHistory
