package nebflow.core.tools

import nebflow.core.compact.CompactConfig

/**
 * Centralized guard that checks tool results against context window pressure.
 * Instead of rejecting oversized results, it truncates them to fit the budget
 * so the LLM can still see partial output and decide what to do next.
 *
 * NOTE: This only guards individual tool results against the LAST KNOWN LLM
 * input token count (ctx.inputTokens). It does NOT account for accumulated
 * tool results across the current turn. The cumulative safety net is in
 * AgentCore.pipeLlmCall's pre-flight budget check, which forces compaction
 * before the LLM call if total estimated tokens exceed the context window.
 */
object ToolResultGuard:

  // Rough token estimation: 1 token ≈ 4 chars
  private val CharsPerToken = 4
  // Context pressure threshold — truncate if projected usage exceeds this fraction
  private val PressureLimit = 0.8
  // Minimum chars to keep even when truncating
  private val MinKeepChars = 500

  sealed trait GuardResult
  case class Ok(content: String) extends GuardResult

  /**
   * Check if adding this tool result to the context would exceed the pressure limit.
   * Returns Ok(content) if fine, or Ok(truncatedContent) if the output was trimmed.
   * The truncation marker tells the LLM that content was cut so it can request
   * more (offset/limit, Read with range, etc.) or compact.
   */
  def guard(content: String, toolName: String, ctx: ToolContext): GuardResult =
    val currentTokens = ctx.inputTokens.getOrElse(0)
    val resultTokens = estimateTokens(content.length)
    val threshold = (ctx.contextWindow * PressureLimit).toInt
    val projected = currentTokens + resultTokens

    if projected <= threshold then Ok(content)
    else
      val availableTokens = math.max(0, threshold - currentTokens)
      val availableChars = math.max(MinKeepChars, availableTokens * CharsPerToken)
      val truncated = truncate(content, availableChars, resultTokens * CharsPerToken)
      Ok(truncated)

  /** Truncate content to fit within maxChars, adding a truncation marker. */
  private def truncate(content: String, maxChars: Int, originalChars: Int): String =
    if content.length <= maxChars then content
    else
      val marker =
        s"\n\n[...truncated: output was ~${originalChars / 1000}k chars but only ~${maxChars / 1000}k chars fit in context.]"
      val keepChars = math.max(0, maxChars - marker.length)
      // Keep the head of the output (usually the most informative part)
      content.take(keepChars) + marker

  private def estimateTokens(chars: Int): Int = (chars + CharsPerToken - 1) / CharsPerToken

end ToolResultGuard
