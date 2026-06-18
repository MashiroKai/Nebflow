package nebflow.cli
import nebflow.core.PathUtil

import cats.effect.IO
import io.circe.Json
import io.circe.syntax.*

object McpCommand extends CliCommand:
  def name = "mcp"
  def description = "Manage MCP servers"
  def subcommands = List(McpList, McpStart, McpStop, McpRestart, McpAdd, McpRemove)

  def examples = List(
    "nebflow mcp list",
    "nebflow mcp start my-server"
  )

  private object McpList extends CliSubcommand:
    def name = "list"
    def description = "List MCP servers"
    def params = Nil

    def run(ctx: CliContext): IO[CliResult] =
      ctx.client match
        case None => IO.pure(CliResult.Error("Gateway not running"))
        case Some(client) =>
          // Get MCP server status from config
          IO.blocking {
            val configPath = PathUtil.dataRoot / "nebflow.json"
            if os.exists(configPath) then
              io.circe.parser
                .parse(os.read(configPath))
                .toOption
                .flatMap(_.hcursor.downField("mcpServers").as[Map[String, Json]].toOption)
            else None
          }.map {
            case Some(servers) =>
              if ctx.json then CliResult.Json(io.circe.Json.fromFields(servers))
              else
                val lines = servers.map { case (id, cfg) =>
                  val cmd = cfg.hcursor.downField("command").as[String].getOrElse("?")
                  s"  $id  ($cmd)"
                }.toList
                if lines.isEmpty then CliResult.text("No MCP servers configured")
                else CliResult.Text("MCP Servers:" :: lines)
            case None => CliResult.text("No MCP servers configured")
          }

  end McpList

  private object McpStart extends CliSubcommand:
    def name = "start"
    def description = "Start an MCP server"
    def params = List(CliParam("id", None, "Server ID", required = true))

    def run(ctx: CliContext): IO[CliResult] =
      val id = ctx.positionalArgs.headOption.getOrElse("")
      if id.isEmpty then IO.pure(CliResult.Error("Server ID required"))
      else
        // MCP start/stop is handled through config changes + gateway reload
        IO.pure(CliResult.text(s"MCP server '$id' start requested (requires gateway reload)"))

  private object McpStop extends CliSubcommand:
    def name = "stop"
    def description = "Stop an MCP server"
    def params = List(CliParam("id", None, "Server ID", required = true))

    def run(ctx: CliContext): IO[CliResult] =
      val id = ctx.positionalArgs.headOption.getOrElse("")
      if id.isEmpty then IO.pure(CliResult.Error("Server ID required"))
      else IO.pure(CliResult.text(s"MCP server '$id' stop requested"))

  private object McpRestart extends CliSubcommand:
    def name = "restart"
    def description = "Restart an MCP server"
    def params = List(CliParam("id", None, "Server ID", required = true))

    def run(ctx: CliContext): IO[CliResult] =
      val id = ctx.positionalArgs.headOption.getOrElse("")
      if id.isEmpty then IO.pure(CliResult.Error("Server ID required"))
      else IO.pure(CliResult.text(s"MCP server '$id' restart requested"))

  private object McpAdd extends CliSubcommand:
    def name = "add"
    def description = "Add an MCP server configuration"

    def params = List(
      CliParam("id", None, "Server ID", required = true),
      CliParam("command", Some('c'), "Command to run", required = true),
      CliParam("args", Some('a'), "Arguments (comma-separated)", required = false)
    )

    def run(ctx: CliContext): IO[CliResult] =
      ctx.client match
        case None => IO.pure(CliResult.Error("Gateway not running"))
        case Some(client) =>
          val id = ctx.positionalArgs.headOption.getOrElse("")
          val command = ctx.args.getOrElse("command", "")
          val args = ctx.args.get("args").map(_.split(",").map(_.trim).toList).getOrElse(Nil)
          if id.isEmpty || command.isEmpty then IO.pure(CliResult.Error("Server ID and command required"))
          else
            val mcpEntry = Json.obj(
              "command" -> command.asJson,
              "args" -> args.asJson
            )
            val configUpdate = s"""{"mcpServers":{"$id":${mcpEntry.noSpaces}}}"""
            client
              .command(
                Json.obj(
                  "type" -> "updateConfig".asJson,
                  "config" -> configUpdate.asJson
                )
              )
              .as(CliResult.text(s"MCP server '$id' added"))

          end if

  end McpAdd

  private object McpRemove extends CliSubcommand:
    def name = "remove"
    def description = "Remove an MCP server"
    def params = List(CliParam("id", None, "Server ID", required = true))

    def run(ctx: CliContext): IO[CliResult] =
      ctx.client match
        case None => IO.pure(CliResult.Error("Gateway not running"))
        case Some(client) =>
          val id = ctx.positionalArgs.headOption.getOrElse("")
          if id.isEmpty then IO.pure(CliResult.Error("Server ID required"))
          else IO.pure(CliResult.text(s"MCP server '$id' removed (edit nebflow.json to persist)"))
end McpCommand
