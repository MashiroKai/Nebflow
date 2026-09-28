package nebflow.cli

import cats.effect.IO
import io.circe.Json
import io.circe.syntax.*
import nebflow.core.skill.{SkillAudit, SkillAuditReport, SkillService}

object SkillCommand extends CliCommand:
  def name = "skill"
  def description = "Manage skills"
  def subcommands = List(SkillList, SkillRun, SkillAuditCmd)
  def examples = List("nebflow skill list", "nebflow skill run my-skill", "nebflow skill audit")

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

  /** Local file scan — no gateway needed. Report only; verdicts belong to humans. */
  private object SkillAuditCmd extends CliSubcommand:
    def name = "audit"
    def description = "Audit skill subscriptions: skill→subscribers, orphans, stale (report only)"
    def params = Nil

    def run(ctx: CliContext): IO[CliResult] =
      SkillAudit.run().map { report =>
        if ctx.json then CliResult.Json(report.asJson)
        else CliResult.Text(formatAudit(report))
      }

    private def formatAudit(r: SkillAuditReport): List[String] =
      val header = s"Skill audit — ${r.skills.size} skills, ${r.agentsScanned} agent definitions scanned" +
        s" (${r.agentsWithDeclarations} with skills declarations)"

      val mapTitle = "\n[1] Skill → subscribers"
      val padWidth = r.skills.map(_.name.length).foldLeft(20)((a, b) => math.max(a, b))
      val mapLines = r.skills.map { s =>
        val subs = r.subscribersBySkill.get(s.name).getOrElse(Nil)
        val right = if subs.isEmpty then "(no subscribers)" else subs.map(_.display).mkString(", ")
        s"  ${s.name.padTo(padWidth, ' ')}  ← $right"
      }

      val wildcardLines =
        if r.wildcardSubscribers.isEmpty then List("  (none)")
        else List(s"  Wildcard subscribers (see every skill): ${r.wildcardSubscribers.map(_.display).mkString(", ")}")

      val orphanLines =
        if r.orphanSkills.isEmpty then List("  (none)")
        else r.orphanSkills.map(s => s"  ${s.name}")

      val staleLines =
        if r.staleSkills.isEmpty then List("  (none)")
        else r.staleSkills.map(st => s"  ${st.skill.name}  (last verified ${st.lastVerified}, ${st.ageDays} days ago)")

      List(
        header,
        mapTitle
      ) ++ mapLines ++ wildcardLines ++
        List(
          "\n[2] Orphan skills — model-invocable, zero subscribers (report only, no action taken)",
          "    skill-creator is always visible when a catalog is injected and never counted here"
        ) ++ orphanLines ++
        List(
          s"\n[3] Stale skills — last_verified older than ${SkillAudit.StaleAfterDays} days (report only)"
        ) ++ staleLines ++
        List(
          s"\n${r.skillsWithoutVerifiedDate} skill(s) have no parseable last_verified date.",
          "Report only — retirement decisions belong to Nebula/Manager (eco decision 1)."
        )
  end SkillAuditCmd
end SkillCommand
