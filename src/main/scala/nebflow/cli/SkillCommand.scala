package nebflow.cli

import cats.effect.IO
import io.circe.Json
import io.circe.syntax.*

object SkillCommand extends CliCommand:
  def name = "skill"
  def description = "Manage skills"
  def subcommands = List(SkillList, SkillRun)
  def examples = List("nebflow skill list", "nebflow skill run my-skill")

  private object SkillList extends CliSubcommand:
    def name = "list"
    def description = "List available skills"
    def params = Nil

    def run(ctx: CliContext): IO[CliResult] =
      ctx.client match
        case None => IO.pure(CliResult.Error("Gateway not running"))
        case Some(client) =>
          client.command(Json.obj("type" -> "getSkills".asJson)).map { resp =>
            if ctx.json then CliResult.Json(resp)
            else
              val skills = resp.hcursor.downField("skills").as[List[Json]].getOrElse(Nil)
              val lines = skills.map { s =>
                val name = s.hcursor.downField("name").as[String].getOrElse("?")
                val desc = s.hcursor.downField("description").as[String].getOrElse("").take(60)
                s"  $name  $desc"
              }
              if lines.isEmpty then CliResult.text("No skills available")
              else CliResult.Text("Skills:" :: lines)
          }

  end SkillList

  private object SkillRun extends CliSubcommand:
    def name = "run"
    def description = "Execute a skill"

    def params = List(
      CliParam("name", None, "Skill name", required = true),
      CliParam("session", Some('s'), "Session ID", required = false),
      CliParam("input", Some('i'), "Input text", required = false)
    )

    def run(ctx: CliContext): IO[CliResult] =
      ctx.client match
        case None => IO.pure(CliResult.Error("Gateway not running"))
        case Some(client) =>
          val skillName = ctx.positionalArgs.headOption.getOrElse("")
          val input = ctx.args.getOrElse("input", "")
          val sessionId = ctx.args.getOrElse("session", "")
          if skillName.isEmpty then IO.pure(CliResult.Error("Skill name required"))
          else if sessionId.isEmpty then IO.pure(CliResult.Error("Session ID required (--session)"))
          else
            client
              .command(
                Json.obj(
                  "type" -> "skill".asJson,
                  "skillName" -> skillName.asJson,
                  "input" -> input.asJson,
                  "sessionId" -> sessionId.asJson
                )
              )
              .map(resp => CliResult.Json(resp))
  end SkillRun
end SkillCommand
