package nebflow.core.hooks

/**
 * Tool name matching for HookRule.matcher.
 * Supports: exact, pipe-separated, wildcard, regex.
 */
object HookMatcher:

  /** Check if a tool name matches a matcher pattern. */
  def matches(matcher: String, toolName: String): Boolean =
    val trimmed = matcher.trim
    if trimmed == "*" then true
    else if trimmed.contains('|') then
      // Pipe-separated: "Edit|Write"
      trimmed.split("\\|").exists(_.trim == toolName)
    else if isRegex(trimmed) then
      // Regex pattern
      trimmed.r.findFirstIn(toolName).isDefined
    else
      // Exact match
      trimmed == toolName

  /** Heuristic: treat as regex if it contains regex-specific chars. */
  private def isRegex(s: String): Boolean =
    s.contains('^') || s.contains('$') || s.contains(".*") || s.contains("\\d")
