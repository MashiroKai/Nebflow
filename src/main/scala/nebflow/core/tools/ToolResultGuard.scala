package nebflow.core.tools

import nebflow.core.compact.CompactConfig

/**
 * Centralized guard that checks tool results against context window pressure.
 * Instead of truncating, results that would overflow the context budget are
 * rejected with a clear error message so the AI can adjust (compact, narrow
 * the query, use offset/limit, etc.).
 */
object ToolResultGuard:

  // Rough token estimation: 1 token ≈ 4 chars
  private val CharsPerToken = 4
  // Context pressure threshold — reject if projected usage exceeds this fraction
  private val PressureLimit = 0.8

  sealed trait GuardResult
  case class Ok(content: String) extends GuardResult
  case class Rejected(message: String) extends GuardResult

  /**
   * Check if adding this tool result to the context would exceed the pressure limit.
   * Returns Ok(content) if fine, or Rejected(errorMessage) if it would overflow.
   */
  def guard(content: String, toolName: String, ctx: ToolContext): GuardResult =
    val currentTokens = ctx.inputTokens.getOrElse(0)
    val resultTokens = estimateTokens(content.length)
    val threshold = (ctx.contextWindow * PressureLimit).toInt
    val projected = currentTokens + resultTokens

    if projected <= threshold then Ok(content)
    else
      val overBy = projected - threshold
      val availableTokens = math.max(0, threshold - currentTokens)
      val availableChars = availableTokens * CharsPerToken
      Rejected(
        s"Tool output too large for remaining context capacity " +
          s"(current: ${currentTokens / 1000}k + output: ~${resultTokens / 1000}k tokens would exceed ${(threshold / 1000)}k limit). " +
          s"Available: ~${availableChars / 1000}k chars. " +
          s"Use ContextManage to compact, or narrow the query to get smaller output."
      )

  private def estimateTokens(chars: Int): Int = (chars + CharsPerToken - 1) / CharsPerToken

end ToolResultGuard
