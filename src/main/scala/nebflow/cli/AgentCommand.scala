package nebflow.cli

import nebflow.core.PathUtil

import cats.effect.IO
import io.circe.Json
import io.circe.syntax.*

object AgentCommand extends CliCommand:
  def name = "agent"
  def description = "Manage agents"
  def subcommands = List(AgentList, AgentShow, AgentCreate, AgentEdit)

  def examples = List(
    "nebflow agent list",
    "nebflow agent show Nebula"
  )

  private object AgentList extends CliSubcommand:
    def name = "list"
    def description = "List registered agents"
    def params = Nil

    def run(ctx: CliContext): IO[CliResult] =
      ctx.client match
        case None => IO.pure(CliResult.Error("Gateway not running"))
        case Some(client) =>
          client.command(Json.obj("type" -> "listAgents".asJson)).map { resp =>
            if ctx.json then CliResult.Json(resp)
            else
              val agents = resp.hcursor.downField("agents").as[List[Json]].getOrElse(Nil)
              val lines = agents.map { a =>
                val name = a.hcursor.downField("name").as[String].getOrElse("?")
                val displayName = a.hcursor.downField("displayName").as[String].getOrElse(name)
                s"  $name  ($displayName)"
              }
              if lines.isEmpty then CliResult.text("No agents")
              else CliResult.Text("Agents:" :: lines)
          }

  end AgentList

  private object AgentShow extends CliSubcommand:
    def name = "show"
    def description = "Show agent configuration"
    def params = List(CliParam("name", None, "Agent name", required = true))

    def run(ctx: CliContext): IO[CliResult] =
      ctx.client match
        case None => IO.pure(CliResult.Error("Gateway not running"))
        case Some(client) =>
          val name = ctx.positionalArgs.headOption.getOrElse("")
          if name.isEmpty then IO.pure(CliResult.Error("Agent name required"))
          else
            client.command(Json.obj("type" -> "getAgentConfig".asJson, "name" -> name.asJson)).map { resp =>
              val configJson = resp.hcursor.downField("configJson").as[String].getOrElse("{}")
              val systemMd = resp.hcursor.downField("systemMd").as[String].getOrElse("")
              if ctx.json then CliResult.Json(resp)
              else
                val lines = List(s"Agent: $name", "", "Config:", configJson)
                if systemMd.nonEmpty then CliResult.Text(lines ++ List("", "System Prompt:", systemMd))
                else CliResult.Text(lines)
            }

  end AgentShow

  private object AgentCreate extends CliSubcommand:
    def name = "create"
    def description = "Create a new agent"

    def params = List(
      CliParam("name", None, "Agent name", required = true),
      CliParam("config", Some('c'), "Config JSON", required = false),
      CliParam("system", Some('s'), "System prompt", required = false)
    )

    def run(ctx: CliContext): IO[CliResult] =
      ctx.client match
        case None => IO.pure(CliResult.Error("Gateway not running"))
        case Some(client) =>
          val name = ctx.positionalArgs.headOption.getOrElse("")
          if name.isEmpty then IO.pure(CliResult.Error("Agent name required"))
          else
            val configJson = ctx.args.getOrElse("config", "{}")
            val systemMd = ctx.args.getOrElse("system", "")
            client
              .command(
                Json.obj(
                  "type" -> "createAgent".asJson,
                  "name" -> name.asJson,
                  "configJson" -> configJson.asJson,
                  "systemMd" -> systemMd.asJson
                )
              )
              .as(CliResult.text(s"Agent '$name' created"))

  end AgentCreate

  private object AgentEdit extends CliSubcommand:
    def name = "edit"
    def description = "Edit agent configuration"

    def params = List(
      CliParam("name", None, "Agent name", required = true),
      CliParam("config", Some('c'), "Config JSON", required = false),
      CliParam("system", Some('s'), "System prompt", required = false)
    )

    def run(ctx: CliContext): IO[CliResult] =
      ctx.client match
        case None => IO.pure(CliResult.Error("Gateway not running"))
        case Some(client) =>
          val name = ctx.positionalArgs.headOption.getOrElse("")
          if name.isEmpty then IO.pure(CliResult.Error("Agent name required"))
          else
            // Open agent directory in $EDITOR
            val agentDir = PathUtil.dataRoot / "agents" / name
            if !os.exists(agentDir) then IO.pure(CliResult.Error(s"Agent directory not found: $agentDir"))
            else
              val editor = sys.env.getOrElse("EDITOR", sys.env.getOrElse("VISUAL", "vi"))
              val systemFile = agentDir / "system.md"
              IO.blocking {
                val pb = new ProcessBuilder((editor.split("\\s+").toList :+ systemFile.toString)*)
                pb.inheritIO().start().waitFor()
              }.as(CliResult.ok)
  end AgentEdit
end AgentCommand
