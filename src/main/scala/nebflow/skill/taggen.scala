package nebflow.skill

import cats.effect.IO
import io.circe.parser.parse
import nebflow.core.NebflowLogger
import nebflow.shared.*

object SkillTagGenerator:
  private val logger = NebflowLogger.forName("nebflow.skill.taggen")

  private object re:
    // Extract JSON object from text that may be wrapped in markdown code blocks
    val ExtractJson: scala.util.matching.Regex = """(?s).*?```(?:json)?\s*(\{.*?\})\s*```.*""".r

  def generateTags(
    llm: LlmHandle[IO],
    skillName: String,
    skillDesc: String,
    language: String
  ): IO[List[String]] =
    val prompt =
      s"""You are a semantic search tag specialist for Nebflow. Your job is to predict what users would naturally say or type when they need a particular skill. Think in REVERSE: given a skill's description, imagine the exact words, phrases, or questions a user would express to trigger it.

Tags must be real user utterances — the kind of things people actually type in a chat. NOT abstract keywords, category labels, or technical jargon.

Rules:
- Generate 5-10 tags in $language
- Each tag = something a user would naturally say or type in a chat
- Mix short commands, casual phrases, and question forms
- Imagine yourself as the user needing this skill right now — what do you say?

Examples:

Skill: paper-review (reviews academic papers for methodology, clarity, contributions)
Tags: {"tags": ["help me review this paper", "check this paper for me", "analyze this paper", "what do you think of this paper", "review this manuscript", "any issues with this paper", "help me evaluate this study"]}

Skill: pr-review (reviews code changes before merging, checks style, bugs, test coverage)
Tags: {"tags": ["review this code before merging", "merge to main", "can I merge this", "check this PR", "is this safe to merge", "pre-merge review", "help me review this MR"]}

Skill: commit-writer (generates conventional commit messages from staged changes)
Tags: {"tags": ["help me write a commit message", "generate commit message", "what should my commit say", "write a commit for me", "summarize my changes"]}

Skill: api-docs (generates API documentation from code annotations and signatures)
Tags: {"tags": ["generate API docs", "write API documentation", "update the docs", "document these endpoints", "API docs are outdated"]}

Now generate tags for:
Skill: $skillName
Description: $skillDesc

Respond with ONLY a JSON object: {"tags": ["tag1", "tag2", ...]}"""

    val request = LlmRequest(
      messages = List(Message(MessageRole.User, Left(prompt))),
      sessionId = "taggen",
      agentId = "skill-tag-generator",
      maxTokens = Some(256)
    )

    llm
      .send(request)
      .map { response =>
        val text = response.reply.trim
        // Extract JSON from possible markdown code blocks or raw text
        val jsonStr = text match
          case SkillTagGenerator.re.ExtractJson(json) => json
          case _ => text
        parse(jsonStr).toOption
          .flatMap(_.hcursor.get[List[String]]("tags").toOption)
          .filter(_.nonEmpty)
          .getOrElse {
            logger.warn(s"Failed to parse tags for skill '$skillName', response: ${text.take(100)}")
            List(skillName, skillDesc).filter(_.nonEmpty)
          }
      }
      .handleErrorWith { e =>
        logger.warn(s"Tag generation failed for '$skillName': ${e.getMessage}") *>
          IO.pure(List(skillName))
      }
  end generateTags
end SkillTagGenerator
