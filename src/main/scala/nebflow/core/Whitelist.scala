package nebflow.core

/** Shared whitelist filtering logic for agent catalog visibility. */
object Whitelist:

  /**
   * Check if `name` passes the whitelist.
   * `List("*")` or containing `"*"` → all pass.
   * Specific list → only matching names pass.
   * `Nil` → nothing passes.
   */
  def passes(name: String, whitelist: List[String]): Boolean =
    whitelist.contains("*") || whitelist.contains(name)
