package nebflow.core.compact

import cats.effect.IO
import io.circe.syntax.*
import nebflow.agent.SharedResources
import nebflow.core.{NebflowLogger, PathUtil}
import nebflow.shared.*

import java.time.{Instant, ZoneId, ZonedDateTime}

/**
 * Experience extraction for worker agents (depth >= 2).
 *
 * After a task turn completes, analyzes the conversation and extracts
 * reusable patterns. Skill-class patterns go to skill-proposals/ for
 * manager review; memory-class patterns go directly to agent memory.
 *
 * Uses LLM for extraction. Falls back to no-op on failure (background fiber).
 */
object ExperienceExtractor:
  private val logger = NebflowLogger.forName("nebflow.experience")

  private val Prompt =
    """<system-reminder>
      |Reflect on the task you just completed and extract reusable experience.
      |Output <experience>:
      |CLASS: skill | memory | skip
      |PATTERN: [trigger] → [effective approach] (because [reason])
      |EVIDENCE: [what happened in this task]
      |SKILL_MATCH: [existing skill name or "new"]
      |</experience>
      |Classification: skill=cross-session reusable method, memory=project-specific fact, skip=one-off
      |Max 1 pattern. If nothing notable, CLASS: skip.
      |</system-reminder>""".stripMargin

  case class Experience(klass: String, pattern: String, evidence: String, skillMatch: String)

  /**
   * Extract experience from a completed turn and store it.
   * Called as a background fiber — failures are silently swallowed.
   */
  def extractAndStore(
    turnMessages: List[Message],
    agentName: String,
    sessionId: Option[String],
    resources: SharedResources
  ): IO[Unit] =
    if turnMessages.size < 5 then IO.unit
    else
      for
        result <- callLlm(turnMessages, agentName, sessionId, resources)
        _ <- result match
          case Some(exp) => storeExperience(exp, agentName)
          case None => IO.unit
      yield ()

  /** Make a standalone LLM call for experience extraction. */
  private def callLlm(
    messages: List[Message],
    agentName: String,
    sessionId: Option[String],
    resources: SharedResources
  ): IO[Option[Experience]] =
    val request = LlmRequest(
      messages = messages.takeRight(30) ++ List(Message(MessageRole.User, Left(Prompt))),
      sessionId = sessionId.getOrElse("experience"),
      agentId = agentName,
      tools = None,
      maxTokens = Some(2048),
      systemStable =
        Some("You are an experience extraction assistant. Analyze completed work and extract reusable patterns.")
    )
    resources.llm
      .send(request)
      .map(resp => parseResponse(resp.reply))
      .handleErrorWith(e =>
        logger.warn(s"Experience extraction LLM call failed for $agentName: ${e.getMessage}").as(None)
      )

  end callLlm

  /** Parse <experience> block from LLM response. */
  def parseResponse(text: String): Option[Experience] =
    "(?s)<experience>(.*?)</experience>".r.findFirstMatchIn(text) match
      case Some(m) =>
        val block = m.group(1).trim
        val klass = extractField(block, "CLASS").getOrElse("skip")
        if klass == "skip" then None
        else
          Some(
            Experience(
              klass = klass,
              pattern = extractField(block, "PATTERN").getOrElse(""),
              evidence = extractField(block, "EVIDENCE").getOrElse(""),
              skillMatch = extractField(block, "SKILL_MATCH").getOrElse("new")
            )
          )
      case None => None

  end parseResponse

  /**
   * Store experience: skill → proposal only. Memory class is dropped —
   *  Workers have no persistent memory. Only reusable skills (cross-session
   *  methods) are worth proposing. Project-specific facts belong in the
   *  Manager's memory, not the Worker's.
   */
  private def storeExperience(exp: Experience, agentName: String): IO[Unit] = IO.blocking {
    exp.klass match
      case "skill" => writeSkillProposal(exp, agentName)
      case _ => () // memory and skip are both dropped — Workers have no persistent memory
    logger.info(s"Experience stored for $agentName: class=${exp.klass}")
  }.void

  private def writeSkillProposal(exp: Experience, agentName: String): Unit =
    val dir = PathUtil.dataRoot / "agents" / agentName / "skill-proposals"
    os.makeDir.all(dir)
    val ts =
      ZonedDateTime.now(ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
    val name = exp.skillMatch match
      case "new" | "" => s"proposal-$ts"
      case existing => s"update-$existing-$ts"
    val content = s"""---
                     |name: $name
                     |description: ${exp.pattern.take(200)}
                     |proposed_by: $agentName
                     |proposed_date: ${Instant.now().toString}
                     |status: pending
                     |based_on_skill: ${exp.skillMatch}
                     |---
                     |
                     |## Proposed Addition
                     |${exp.pattern}
                     |
                     |## Evidence
                     |${exp.evidence}
                     |""".stripMargin
    os.write(dir / s"$name.md", content)

  end writeSkillProposal

  private def extractField(text: String, field: String): Option[String] =
    text.linesIterator
      .map(_.trim)
      .find(_.startsWith(s"$field:"))
      .map(_.substring(field.length + 1).trim)
      .filter(_.nonEmpty)

end ExperienceExtractor
