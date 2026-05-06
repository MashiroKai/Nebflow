package nebflow.core.tools

/**
 * Centralized guard that truncates oversized tool results before they enter
 * the LLM message history.  Without this, a single huge Bash output or file
 * read can exceed the model's context window and crash the API call.
 *
 * Each tool type has its own char budget.  When the budget is exceeded the
 * content is truncated and a clear marker is appended so the LLM (and the
 * user via the frontend) knows data was cut.
 */
object ToolResultGuard:

  // --- Per-tool char budgets ---
  // Rough rule: 1 token ≈ 4 chars (English), so 50k chars ≈ 12.5k tokens.

  /** Default budget for tools without a specific override. */
  val DefaultMaxChars: Int = 50_000

  private val ToolLimits: Map[String, Int] = Map(
    "Bash"  -> 80_000,  // build logs, test output — tends to be verbose
    "Read"  -> 60_000,  // source files — can be long but usually structured
    "Grep"  -> 50_000,  // search results
    "Glob"  -> 30_000,  // file listings
    "Curl"  -> 50_000,  // HTTP responses (tool already limits to 100k)
    "WebSearch" -> 50_000,
    "WebFetch"  -> 50_000
  )

  /** Maximum chars for a given tool name. */
  def maxCharsFor(toolName: String): Int =
    ToolLimits.getOrElse(toolName, DefaultMaxChars)

  /**
   * Truncate content if it exceeds the budget for the given tool.
   * Returns (truncatedContent, wasTruncated).
   */
  def guard(content: String, toolName: String): (String, Boolean) =
    val limit = maxCharsFor(toolName)
    if content.length <= limit then (content, false)
    else
      val originalSize = content.length
      val truncated = content.take(limit) +
        s"\n\n[Output truncated: showing ${limit} of $originalSize chars. " +
        "Use more specific queries or pagination to get smaller results.]"
      (truncated, true)

end ToolResultGuard
