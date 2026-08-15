package nebflow.core.skill

import cats.effect.IO
import io.circe.parser.parse
import io.circe.syntax.*
import io.circe.{Encoder, Json}
import nebflow.core.PathUtil

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** One agent that declares skills in its agent.json. scope = "global" or the team name. */
final case class SkillSubscriber(agent: String, scope: String):
  def display: String = if scope == "global" then agent else s"$agent (team: $scope)"

object SkillSubscriber:
  given Encoder[SkillSubscriber] = Encoder.instance { s =>
    Json.obj("agent" -> s.agent.asJson, "scope" -> s.scope.asJson)
  }

/** A skill whose last_verified date is older than the audit threshold. */
final case class StaleSkill(skill: SkillInfo, lastVerified: LocalDate, ageDays: Long)

object StaleSkill:
  given Encoder[StaleSkill] = Encoder.instance { s =>
    Json.obj(
      "name" -> s.skill.name.asJson,
      "lastVerified" -> s.lastVerified.toString.asJson,
      "ageDays" -> s.ageDays.asJson
    )
  }

/**
 * Structural audit report for the skill library (report-only — no action, no
 * automatic retirement; verdicts belong to Nebula/Manager, eco decision 1).
 *
 *  - subscribersBySkill: the reverse index of agent.json `skills` declarations
 *  - orphanSkills: model-invocable skills with zero subscribers (no wildcard)
 *  - staleSkills: last_verified older than [[SkillAudit.StaleAfterDays]]
 */
final case class SkillAuditReport(
  skills: List[SkillInfo],
  subscribersBySkill: Map[String, List[SkillSubscriber]],
  wildcardSubscribers: List[SkillSubscriber],
  orphanSkills: List[SkillInfo],
  staleSkills: List[StaleSkill],
  agentsScanned: Int,
  agentsWithDeclarations: Int,
  skillsWithoutVerifiedDate: Int
)

object SkillAuditReport:
  given Encoder[SkillAuditReport] = Encoder.instance { r =>
    Json.obj(
      "skills" -> r.skills.asJson,
      "subscribersBySkill" -> r.subscribersBySkill.map { case (k, v) => k -> v.asJson }.asJson,
      "wildcardSubscribers" -> r.wildcardSubscribers.asJson,
      "orphanSkills" -> r.orphanSkills.map(_.name).asJson,
      "staleSkills" -> r.staleSkills.asJson,
      "agentsScanned" -> r.agentsScanned.asJson,
      "agentsWithDeclarations" -> r.agentsWithDeclarations.asJson,
      "skillsWithoutVerifiedDate" -> r.skillsWithoutVerifiedDate.asJson
    )
  }

object SkillAudit:

  /** A skill counts as stale when last_verified is older than this many days. */
  val StaleAfterDays = 90L

  /**
   * Scan the skill library and every agent.json for subscriptions.
   * `today` is injectable for deterministic tests.
   */
  def run(today: LocalDate = LocalDate.now()): IO[SkillAuditReport] =
    SkillService.listSkills().map { skills =>
      val declarations = scanDeclarations()
      val wildcard = declarations.collect { case (agent, scope, decl) if decl.contains("*") =>
        SkillSubscriber(agent, scope)
      }.toList
      val bySkill = declarations
        .flatMap { case (agent, scope, decl) =>
          decl.filter(_ != "*").map(name => name -> SkillSubscriber(agent, scope))
        }
        .groupMap(_._1)(_._2)
        .map { case (name, subs) => name -> subs.distinctBy(s => (s.agent, s.scope)).toList }

      val orphans = skills.filter { s =>
        s.modelInvocable &&
        !SkillService.alwaysVisible.contains(s.name) &&
        bySkill.get(s.name).forall(_.isEmpty) &&
        wildcard.isEmpty
      }.sortBy(_.name)

      def parseDate(v: String): Option[LocalDate] = scala.util.Try(LocalDate.parse(v)).toOption

      val stale = skills.flatMap { s =>
        s.lastVerified.flatMap(parseDate) match
          case Some(date) =>
            val age = ChronoUnit.DAYS.between(date, today)
            if age > StaleAfterDays then Some(StaleSkill(s, date, age)) else None
          case None => None
      }.sortBy(-_.ageDays)

      SkillAuditReport(
        skills = skills.sortBy(_.name),
        subscribersBySkill = bySkill,
        wildcardSubscribers = wildcard,
        orphanSkills = orphans,
        staleSkills = stale,
        agentsScanned = declarations.size,
        agentsWithDeclarations = declarations.count(_._3.nonEmpty),
        skillsWithoutVerifiedDate = skills.count(s => s.lastVerified.flatMap(parseDate).isEmpty)
      )
    }

  /**
   * Scan all agent definitions for `skills` declarations:
   *   ~/.nebflow/agents/<name>/agent.json           → scope "global"
   *   ~/.nebflow/teams/<t>/agents/<name>/agent.json → scope <t>
   * Team leads are global agents (TeamDef), so the global scan already covers them —
   * team.json has no skills field today (verified), hence no team.json scan.
   * Returns (agentName, scope, declaredSkills); unparsable skills fall back to Nil.
   */
  private def scanDeclarations(): Seq[(String, String, List[String])] =
    val root = PathUtil.dataRoot

    def listDirs(d: os.Path): Seq[os.Path] =
      if os.isDir(d) then os.list(d).filter(os.isDir).toSeq else Seq.empty

    def entry(agentDir: os.Path, scope: String): Option[(String, String, List[String])] =
      val f = agentDir / "agent.json"
      if !os.isFile(f) then None
      else
        val declared = parse(os.read(f)).toOption
          .flatMap(_.hcursor.downField("skills").as[Option[List[String]]].toOption.flatten)
          .getOrElse(Nil)
        Some((agentDir.baseName, scope, declared))

    val global = listDirs(root / "agents").flatMap(d => entry(d, "global"))
    val team = listDirs(root / "teams").flatMap { t =>
      listDirs(t / "agents").flatMap(a => entry(a, t.baseName))
    }
    global ++ team
  end scanDeclarations

end SkillAudit
