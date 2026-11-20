package nebflow.core.tools

import cats.effect.IO
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.shared.PathUtil

/** On-demand, read-only search of the current user's indexed main-session history. */
object ConversationSearchTool extends Tool:
  val Name = "ConversationSearch"
  val DefaultLimit = 5
  val MaxLimit = 20
  val MaxContext = 3
  val MaxOutputChars = 12000
  val MaxQueryChars = 512
  val TimeoutMs = 30000
  private val TimeBoundPattern = "^\\d{4}-\\d{2}-\\d{2}(?: \\d{2}:\\d{2})?$".r

  private def validTimeBound(value: String): Boolean = TimeBoundPattern.matches(value)
  override def name: String = Name

  override def description: String =
    "Search the current user's indexed main-session conversation history on demand (read-only). Returns matching user/assistant messages with nearby context, timestamp, and source session; background-agent and dispatched sessions are excluded by the stand-recall corpus rules. Nebula-root only. No shell, arbitrary path/database, cross-project selector, or history mutation is exposed."

  override def inputSchema: JsonObject = JsonObject(
    "type" -> "object".asJson,
    "properties" -> Json.obj(
      "query" -> Json.obj(
        "type" -> "string".asJson,
        "description" -> "Natural-language question or keywords to search. Required.".asJson
      ),
      "limit" -> Json.obj("type" -> "integer".asJson),
      "context" -> Json.obj("type" -> "integer".asJson),
      "role" -> Json.obj("type" -> "string".asJson, "enum" -> Json.arr("user".asJson, "assistant".asJson)),
      "since" -> Json.obj("type" -> "string".asJson),
      "until" -> Json.obj("type" -> "string".asJson)
    ),
    "required" -> Json.arr("query".asJson),
    "additionalProperties" -> false.asJson
  )

  override def summarize(input: JsonObject): String =
    s"ConversationSearch(${input("query").flatMap(_.asString).getOrElse("").take(60)})"

  override def summarizeResult(input: JsonObject, result: String): String =
    if result.length > 200 then result.take(197) + "..." else result

  def resolveCli(): Option[os.Path] = nebflow.core.MemoryRecallDaemon.resolveToolPath(PathUtil.dataRoot)

  override def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    if !ctx.isNebulaRoot then
      IO.pure(
        Left(ToolError("ConversationSearch: permission denied — Nebula root only. (CONVERSATION_SEARCH_FORBIDDEN)"))
      )
    else
      val allowed = Set("query", "limit", "context", "role", "since", "until")
      val unknown = input.keys.toSet -- allowed
      val query = input("query").flatMap(_.asString).map(_.trim).getOrElse("")
      val limit = input("limit").flatMap(_.asNumber.flatMap(_.toInt)).getOrElse(DefaultLimit).max(1).min(MaxLimit)
      val context = input("context").flatMap(_.asNumber.flatMap(_.toInt)).getOrElse(1).max(0).min(MaxContext)
      val role = input("role").flatMap(_.asString)
      val since = input("since").flatMap(_.asString)
      val until = input("until").flatMap(_.asString)
      if unknown.nonEmpty then
        IO.pure(
          Left(ToolError(s"ConversationSearch: unsupported parameter(s): ${unknown.toList.sorted.mkString(", ")}."))
        )
      else if query.isEmpty then IO.pure(Left(ToolError("ConversationSearch: `query` must be a non-empty string.")))
      else if query.length > MaxQueryChars then
        IO.pure(Left(ToolError(s"ConversationSearch: `query` exceeds $MaxQueryChars characters.")))
      else if role.exists(r => r != "user" && r != "assistant") then
        IO.pure(Left(ToolError("ConversationSearch: `role` must be user or assistant.")))
      else if since.exists(s => !validTimeBound(s)) || until.exists(s => !validTimeBound(s)) then
        IO.pure(Left(ToolError("ConversationSearch: `since`/`until` must use YYYY-MM-DD[ HH:MM].")))
      else
        IO.blocking {
          val dataRoot = PathUtil.dataRoot
          val indexDb = dataRoot / "memory-db" / "memory.db"
          if !os.exists(indexDb) then
            Left(ToolError("ConversationSearch: history index is not available; no index was created."))
          else
            resolveCli() match
              case None =>
                Left(ToolError("ConversationSearch: stand-recall CLI not found; history search unavailable."))
              case Some(cli) =>
                val args = scala.collection.mutable.ListBuffer[String](
                  "python3",
                  cli.toString,
                  "--json",
                  "-n",
                  limit.toString,
                  "--context",
                  context.toString,
                  "--no-recency",
                  "--data-root",
                  dataRoot.toString,
                  "--db",
                  indexDb.toString,
                  "--readonly"
                )
                role.foreach(r => args ++= List("--role", r))
                since.foreach(s => args ++= List("--since", s))
                until.foreach(s => args ++= List("--until", s))
                args += query
                val res =
                  os.proc(args.toList).call(cwd = os.pwd, timeout = TimeoutMs, check = false, mergeErrIntoOut = true)
                val out = res.out.text().trim
                if res.exitCode != 0 then
                  Left(ToolError(s"ConversationSearch: CLI exit ${res.exitCode} — ${out.take(300)}"))
                else if out.length <= MaxOutputChars then Right(out)
                else Right(out.take(MaxOutputChars) + s"\n[truncated: $MaxOutputChars characters shown]")
          end if
        }.handleErrorWith(e => IO.pure(Left(ToolError(s"ConversationSearch failed: ${e.getMessage}"))))
      end if
end ConversationSearchTool
