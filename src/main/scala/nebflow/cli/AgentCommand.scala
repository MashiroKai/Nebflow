package nebflow.cli

import cats.effect.IO
import io.circe.Json
import io.circe.syntax.*
import nebflow.core.PathUtil

object AgentCommand extends CliCommand:
  def name = "agent"
  def description = "Manage agents"
  def subcommands = List(AgentList, AgentShow, AgentEdit)

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
    def description = "Show agent system prompt"
    def params = List(CliParam("name", None, "Agent name", required = true))

    def run(ctx: CliContext): IO[CliResult] =
      ctx.client match
        case None => IO.pure(CliResult.Error("Gateway not running"))
        case Some(client) =>
          val name = ctx.positionalArgs.headOption.getOrElse("")
          if name.isEmpty then IO.pure(CliResult.Error("Agent name required"))
          else
            client.command(Json.obj("type" -> "getAgentSystemPrompt".asJson, "name" -> name.asJson)).map { resp =>
              val systemMd = resp.hcursor.downField("systemMd").as[String].getOrElse("")
              if ctx.json then CliResult.Json(resp)
              else if systemMd.nonEmpty then CliResult.Text(List(s"Agent: $name", "", systemMd))
              else CliResult.Text(List(s"Agent: $name", "", "(no system prompt)"))
            }

  end AgentShow

  private object AgentEdit extends CliSubcommand:
    def name = "edit"
    def description = "Edit agent system prompt in $EDITOR"

    def params = List(
      CliParam("name", None, "Agent name", required = true)
    )

    def run(ctx: CliContext): IO[CliResult] =
      ctx.client match
        case None => IO.pure(CliResult.Error("Gateway not running"))
        case Some(_) =>
          val name = ctx.positionalArgs.headOption.getOrElse("")
          if name.isEmpty then IO.pure(CliResult.Error("Agent name required"))
          else
            // Open system.md in $EDITOR
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
