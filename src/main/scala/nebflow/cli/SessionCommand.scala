package nebflow.cli

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*

object SessionCommand extends CliCommand:
  def name = "session"
  def description = "Manage sessions"
  def subcommands = List(SessionList, SessionCreate, SessionDelete, SessionRename, SessionHistory)

  def examples = List(
    "nebflow session list",
    "nebflow session create --name \"My Session\"",
    "nebflow session delete abc-123"
  )

  private object SessionList extends CliSubcommand:
    def name = "list"
    def description = "List sessions"

    def params = List(
      CliParam("agent", Some('a'), "Filter by agent name", required = false)
    )

    def run(ctx: CliContext): IO[CliResult] =
      ctx.client match
        case None => IO.pure(CliResult.Error("Gateway not running"))
        case Some(client) =>
          val payload = Json.obj("type" -> "listAgents".asJson)
          // Use the session list via WS-equivalent command
          client
            .command(
              Json.obj("type" -> "listAgentSessions".asJson, "name" -> ctx.args.getOrElse("agent", "Nebula").asJson)
            )
            .map { resp =>
              if ctx.json then CliResult.Json(resp)
              else
                val sessions = resp.hcursor.downField("sessions").as[List[Json]].getOrElse(Nil)
                val lines = sessions.map { s =>
                  val id = s.hcursor.downField("id").as[String].getOrElse("").take(8)
                  val name = s.hcursor.downField("name").as[String].getOrElse("-")
                  val ts = s.hcursor.downField("createdAt").as[Long].getOrElse(0L)
                  s"$id  $name"
                }
                if lines.isEmpty then CliResult.text("No sessions")
                else CliResult.Text("ID        Name" :: lines)
            }

  end SessionList

  private object SessionCreate extends CliSubcommand:
    def name = "create"
    def description = "Create a new session"

    def params = List(
      CliParam("name", Some('n'), "Session name", required = false),
      CliParam("folder", Some('f'), "Folder ID", required = false),
      CliParam("agent", Some('a'), "Agent name", required = false)
    )

    def run(ctx: CliContext): IO[CliResult] =
      ctx.client match
        case None => IO.pure(CliResult.Error("Gateway not running"))
        case Some(client) =>
          val name = ctx.args.getOrElse("name", "New Session")
          val folderId = ctx.args.get("folder")
          val agentName = ctx.args.get("agent")
          client
            .command(
              Json.obj(
                "type" -> "createSession".asJson,
                "name" -> name.asJson,
                "folderId" -> folderId.asJson,
                "agentName" -> agentName.asJson
              )
            )
            .map(resp => CliResult.Json(resp))

  end SessionCreate

  private object SessionDelete extends CliSubcommand:
    def name = "delete"
    def description = "Delete a session"
    def params = List(CliParam("session-id", None, "Session ID", required = true))

    def run(ctx: CliContext): IO[CliResult] =
      ctx.client match
        case None => IO.pure(CliResult.Error("Gateway not running"))
        case Some(client) =>
          val sessionId = ctx.positionalArgs.headOption.getOrElse(ctx.args.getOrElse("session-id", ""))
          if sessionId.isEmpty then IO.pure(CliResult.Error("Session ID required"))
          else
            client
              .command(
                Json.obj(
                  "type" -> "deleteSession".asJson,
                  "sessionId" -> sessionId.asJson
                )
              )
              .as(CliResult.text(s"Session $sessionId deleted"))

  end SessionDelete

  private object SessionRename extends CliSubcommand:
    def name = "rename"
    def description = "Rename a session"

    def params = List(
      CliParam("session-id", None, "Session ID", required = true),
      CliParam("name", Some('n'), "New name", required = true)
    )

    def run(ctx: CliContext): IO[CliResult] =
      ctx.client match
        case None => IO.pure(CliResult.Error("Gateway not running"))
        case Some(client) =>
          val sessionId = ctx.positionalArgs.headOption.getOrElse("")
          val newName = ctx.args.getOrElse("name", "")
          if sessionId.isEmpty || newName.isEmpty then IO.pure(CliResult.Error("Session ID and new name required"))
          else
            client
              .command(
                Json.obj(
                  "type" -> "renameSession".asJson,
                  "sessionId" -> sessionId.asJson,
                  "name" -> newName.asJson
                )
              )
              .as(CliResult.text(s"Session renamed to '$newName'"))

  end SessionRename

  private object SessionHistory extends CliSubcommand:
    def name = "history"
    def description = "View session history"

    def params = List(
      CliParam("session-id", None, "Session ID", required = true),
      CliParam("limit", Some('l'), "Max messages", required = false)
    )

    def run(ctx: CliContext): IO[CliResult] =
      ctx.client match
        case None => IO.pure(CliResult.Error("Gateway not running"))
        case Some(client) =>
          val sessionId = ctx.positionalArgs.headOption.getOrElse("")
          val limit = ctx.args.getOrElse("limit", "50").toIntOption.getOrElse(50)
          if sessionId.isEmpty then IO.pure(CliResult.Error("Session ID required"))
          else
            client
              .command(
                Json.obj(
                  "type" -> "getHistory".asJson,
                  "sessionId" -> sessionId.asJson,
                  "limit" -> limit.asJson
                )
              )
              .map(resp => CliResult.Json(resp))
  end SessionHistory
end SessionCommand
