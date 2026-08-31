package nebflow.core.compact

import cats.effect.IO
import nebflow.agent.SharedResources
import nebflow.core.NebflowLogger
import nebflow.shared.*

/**
 * Pre-compaction hook for the Root agent (Nebula, depth 0).
 *
 * Replaces DreamMode's idle timer: instead of a 5-minute polling cycle,
 * durable facts are extracted from the conversation right before it is
 * compacted. Extracted facts are MERGED into `~/.nebflow/User.md` via
 * `DreamMode.updateMemory` (2026-08-31 merged-write model — facts land in a
 * stable `## Dream Extract` section by category, deduplicated; no more
 * timestamped append sections, no more `## 使用模式` rewrite).
 */
object NebulaMemoryHook extends PreCompactionHook:
  private val logger = NebflowLogger.forName("nebflow.prehook.nebula")

  def run(
    messages: List[Message],
    agentName: String,
    sessionId: Option[String],
    teamName: Option[String],
    resources: SharedResources
  ): IO[Unit] =
    if messages.size < 20 then IO.unit
    else
      for
        facts <- extractFacts(messages, agentName, sessionId, resources)
        _ <-
          if facts.nonEmpty then
            DreamMode
              .updateMemory(facts)
              .handleErrorWith(e => logger.warn(s"Memory update failed: ${e.getMessage}"))
          else IO.unit
      yield ()

  private def extractFacts(
    messages: List[Message],
    agentName: String,
    sessionId: Option[String],
    resources: SharedResources
  ): IO[List[String]] =
    val request = LlmRequest(
      messages = messages.takeRight(60) ++ List(Message(MessageRole.User, Left(DreamMode.DreamPrompt))),
      sessionId = sessionId.getOrElse("prehook"),
      agentId = agentName,
      tools = None,
      maxTokens = Some(4096),
      systemStable = Some("You are a memory extraction assistant.")
    )
    resources.llm
      .send(request)
      .map(resp => DreamMode.parseResponse(resp.reply))
      .handleErrorWith(e => logger.warn(s"Fact extraction LLM failed: ${e.getMessage}").as(Nil))
  end extractFacts
end NebulaMemoryHook
