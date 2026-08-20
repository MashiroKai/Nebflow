package nebflow.core.compact

import io.circe.Json
import io.circe.syntax.*
import nebflow.shared.*

/**
 * Tool-result TTL cleanup (#341, design
 * docs/Nebflow/20260820_tool-result-ttl.md).
 *
 * REQUEST-ONLY: replaces stale, oversized tool results with a self-describing
 * placeholder in the LLM request copy. The session file (state.messages /
 * persisted history) is NEVER touched — the three planes (display / LLM
 * context / session file) stay separate. Contrast with FastMicroCompact,
 * which rewrites persisted history.
 *
 * A result is replaced only when ALL hold:
 *   1. enabled, and the turn is not compact/save/ask (those need full input)
 *   2. cache is cold (no assistant message within ColdAfterMs — replacing
 *      prefix content while the cache is hot would re-bill the tail for
 *      nothing; when cold, any shrink is pure savings, hence no savings-ratio
 *      guard unlike FastMicroCompact)
 *   3. the result is older than ttlMinutes (message timestamp)
 *   4. it falls outside the keepRecent window of compactable results — this
 *      also makes mid-turn safety constructive: the current turn's results
 *      are always the most recent ones
 *   5. its content exceeds minChars
 *
 * Scope: re-runnable tools only (same set as FastMicroCompact) — the
 * placeholder tells the model it can re-run the tool. Mail/SubTask results
 * are out of scope for v1.
 */
object ToolResultTtl:

  /** Cold-cache threshold — shared semantics with FastMicroCompact. */
  val ColdAfterMs: Long = FastMicroCompact.DefaultColdAfterMs

  /** Re-runnable tools whose results may be archived (mirrors FastMicroCompact). */
  private val CompactableTools = Set(
    "Read",
    "Bash",
    "Grep",
    "Glob",
    "WebSearch",
    "WebFetch",
    "Curl",
    "Edit",
    "Write"
  )

  /**
   * Build the cleaned request view of the messages. Returns None when the
   * config is disabled, the cache is hot, or nothing qualifies — callers
   * keep the original list then. Pure function; callers must NOT write the
   * result back to state.messages (that would break the session-file plane).
   */
  def cleanRequestMessages(
    messages: List[Message],
    cfg: ToolResultTtlConfig,
    nowMs: Long = System.currentTimeMillis()
  ): Option[List[Message]] =
    if !cfg.enabled || messages.isEmpty then None
    else if !cacheIsCold(messages, nowMs) then None
    else
      // qa #341 FAIL fix (2026-08-20): candidates must be ORDERED (message
      // order, oldest → newest) — a Set's iteration order is hash-based, so
      // takeRight(keepRecent) on it kept ARBITRARY entries, not the newest N
      // (real toolUseIds are UUID-like → random subset was the norm). List
      // + separate Set for O(1) membership.
      val candidates = compactableToolUseIds(messages)
      if candidates.size <= cfg.keepRecent then None
      else
        val candidateSet = candidates.toSet
        val keepSet = candidates.takeRight(cfg.keepRecent).toSet // true newest N
        val ttlCutoff = nowMs - cfg.ttlMinutes.toLong * 60_000L
        var changed = false
        val cleaned = messages.map {
          case msg @ Message(MessageRole.User, Right(blocks), ts, _) =>
            var msgChanged = false
            val newBlocks = blocks.map {
              case tr: ContentBlock.ToolResult
                  if candidateSet.contains(tr.toolUseId) &&
                    !keepSet.contains(tr.toolUseId) &&
                    tr.content.length > cfg.minChars &&
                    (ts <= 0 || ts <= ttlCutoff) =>
                changed = true
                msgChanged = true
                tr.copy(content = placeholder(tr.content.length, nowMs - ts))
              case other => other
            }
            if msgChanged then msg.copy(content = Right(newBlocks)) else msg
          case other => other
        }
        if changed then Some(cleaned) else None

  /** Cold-cache check: no assistant message within ColdAfterMs of now.
    * timestamp == 0 (legacy messages without ts) → treat as cold (same as
    * FastMicroCompact). */
  private def cacheIsCold(messages: List[Message], nowMs: Long): Boolean =
    val lastAssistantTs = messages.collect {
      case m if m.role == MessageRole.Assistant && m.timestamp > 0 => m.timestamp
    }.maxOption
    lastAssistantTs match
      case Some(ts) => nowMs - ts >= ColdAfterMs
      case None     => true

  /** Compactable tool_use ids in MESSAGE ORDER (oldest → newest), distinct.
    * ORDER MATTERS: keepRecent = takeRight of this list = the true newest N
    * (qa #341 FAIL — a Set here made the keep window arbitrary). */
  private def compactableToolUseIds(messages: List[Message]): List[String] =
    messages.flatMap {
      case Message(MessageRole.Assistant, Right(blocks), _, _) =>
        blocks.collect {
          case ContentBlock.ToolUse(id, name, _) if CompactableTools.contains(name) => id
        }
      case _ => Nil
    }.distinct

  private def placeholder(chars: Int, ageMs: Long): String =
    val ageMin = math.max(1, ageMs / 60_000L)
    s"[Tool output archived: age ${ageMin}min, $chars chars — full content kept in session history; re-run the tool if you need it again]"

end ToolResultTtl

/**
 * #341 config (nebflow.json top-level `toolResultTtl` node). Default OFF
 * (user ruling). Fail-safe: invalid node decodes to disabled.
 */
final case class ToolResultTtlConfig(
  enabled: Boolean = false,
  ttlMinutes: Int = 60,
  keepRecent: Int = 5,
  minChars: Int = 2000
):
  /** Normalized validity for load-time fail-safe. */
  def sanitized: ToolResultTtlConfig =
    ToolResultTtlConfig(
      enabled = enabled,
      ttlMinutes = math.max(1, ttlMinutes),
      keepRecent = math.max(0, keepRecent),
      minChars = math.max(0, minChars)
    )

object ToolResultTtlConfig:

  given io.circe.Decoder[ToolResultTtlConfig] =
    io.circe.Decoder.instance { c =>
      for
        enabled <- c.downField("enabled").as[Option[Boolean]].map(_.getOrElse(false))
        ttlMinutes <- c.downField("ttlMinutes").as[Option[Int]].map(_.getOrElse(60))
        keepRecent <- c.downField("keepRecent").as[Option[Int]].map(_.getOrElse(5))
        minChars <- c.downField("minChars").as[Option[Int]].map(_.getOrElse(2000))
      yield ToolResultTtlConfig(enabled, ttlMinutes, keepRecent, minChars)
    }

  given io.circe.Encoder[ToolResultTtlConfig] = io.circe.generic.semiauto.deriveEncoder

  /** Fail-safe load from the raw config node (mirrors FreezeSchedule.load):
    * absent / invalid / garbage → disabled default. */
  def load(json: Option[Json]): ToolResultTtlConfig =
    json
      .flatMap(_.as[ToolResultTtlConfig].toOption)
      .map(_.sanitized)
      .getOrElse(ToolResultTtlConfig())

end ToolResultTtlConfig
