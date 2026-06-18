package nebflow.core.tools

import nebflow.core.PathUtil
import cats.effect.IO
import cats.syntax.all.*
import nebflow.core.{NebflowLogger, ToolExecResult}
import nebflow.shared.{Defaults, ToolCall}

/**
 * Guards against oversized tool results that would blow the LLM context window.
 *
 * Two layers (inspired by Claude Code):
 *   1. Per-tool: if a single result exceeds the tool's maxResultSizeChars
 *      (clamped by Defaults.DefaultMaxResultSizeChars), persist to disk and
 *      replace LLM-visible content with a preview + file path.
 *   2. Per-message aggregate: if all results in one turn together exceed
 *      Defaults.MaxToolResultsPerMessageChars, persist the largest ones.
 *
 * Full content is always preserved in frontendContent so the user sees everything.
 */
object ToolResultGuard:

  private val logger = NebflowLogger.forName("nebflow.toolResultGuard")

  private val PersistedTag = "<persisted-output>"
  private val PersistedClosingTag = "</persisted-output>"

  /** Root directory for persisted tool results: ~/.nebflow/tool-results/{sessionId}/ */
  private def resultDir(sessionId: String): os.Path =
    PathUtil.dataRoot / "tool-results" / sessionId

  private def resultPath(sessionId: String, toolUseId: String): os.Path =
    resultDir(sessionId) / s"$toolUseId.txt"

  // ============================================================
  // Layer 1: Per-tool result guard
  // ============================================================

  /**
   * Guard a single tool result. If content exceeds the tool's threshold,
   * persist to disk and replace LLM-visible content with a preview.
   */
  def guardResult(
    call: ToolCall,
    result: ToolExecResult,
    sessionId: String
  ): IO[ToolExecResult] =
    if result.isError || result.content.isEmpty then IO.pure(result)
    else
      val tool = ToolRegistry.TOOL_MAP.get(call.name)
      val declaredMax = tool.map(_.maxResultSizeChars).getOrElse(Defaults.DefaultMaxResultSizeChars)
      if declaredMax == Int.MaxValue then IO.pure(result)
      else
        val threshold = math.min(declaredMax, Defaults.DefaultMaxResultSizeChars)
        if result.content.length <= threshold then IO.pure(result)
        else persistAndReplace(call, result, sessionId)

  // ============================================================
  // Layer 2: Per-message aggregate budget
  // ============================================================

  /**
   * Guard a batch of tool results. If their aggregate LLM-visible content
   * exceeds the per-message budget, persist the largest ones until under budget.
   */
  def guardBatch(
    results: List[(ToolCall, ToolExecResult)],
    sessionId: String
  ): IO[List[(ToolCall, ToolExecResult)]] =
    if results.size <= 1 then IO.pure(results)
    else
      val totalSize: Int = results.foldLeft(0)((acc, pair) => acc + pair._2.content.length)
      val budget = Defaults.MaxToolResultsPerMessageChars
      if totalSize <= budget then IO.pure(results)
      else enforceBatchBudget(results, sessionId, totalSize, budget)

  private def enforceBatchBudget(
    results: List[(ToolCall, ToolExecResult)],
    sessionId: String,
    totalSize: Int,
    budget: Int
  ): IO[List[(ToolCall, ToolExecResult)]] =
    val previewOverhead = Defaults.ToolResultPreviewSize + 200

    // Build (index, size) pairs, sorted largest-first, skip already-persisted
    val candidates: List[(Int, Int)] = results.zipWithIndex
      .collect {
        case (pair, idx) if !pair._2.content.startsWith(PersistedTag) =>
          (idx, pair._2.content.length): (Int, Int)
      }
      .sortBy((t: (Int, Int)) => -t._2)

    // Select indices to persist: largest first until under budget
    val selected: Set[Int] = candidates
      .foldLeft((totalSize, Set.empty[Int]): (Int, Set[Int])) {
        case ((remaining: Int, sel: Set[Int]), (idx: Int, size: Int)) if remaining > budget =>
          (remaining - (size - previewOverhead), sel + idx)
        case (acc, _) => acc
      }
      ._2

    if selected.isEmpty then IO.pure(results)
    else
      results.zipWithIndex.traverse { case (pair, idx) =>
        if selected.contains(idx) then persistAndReplace(pair._1, pair._2, sessionId).map(r => (pair._1, r))
        else IO.pure(pair)
      }
  end enforceBatchBudget

  // ============================================================
  // Persistence internals
  // ============================================================

  private def persistAndReplace(
    call: ToolCall,
    result: ToolExecResult,
    sessionId: String
  ): IO[ToolExecResult] =
    val toolUseId = call.id
    val content = result.content
    IO.blocking {
      val dir = resultDir(sessionId)
      os.makeDir.all(dir)
      val path = resultPath(sessionId, toolUseId)
      if !os.exists(path) then os.write.over(path, content)

      val sizeStr = formatSize(content.length)
      val preview = content.take(Defaults.ToolResultPreviewSize)
      val hasMore = content.length > Defaults.ToolResultPreviewSize

      val sb = new StringBuilder()
      sb.append(PersistedTag).append("\n")
      sb.append(s"Output too large ($sizeStr). Full output saved to: $path\n\n")
      sb.append(s"Preview (first ${Defaults.ToolResultPreviewSize} chars):\n")
      sb.append(preview)
      if hasMore then sb.append("\n...\n") else sb.append("\n")
      sb.append(PersistedClosingTag)

      logger.info(s"Persisted ${call.name} result ($sizeStr) to $path")
      sb.toString
    }.map { previewContent =>
      ToolExecResult(
        content = previewContent,
        isError = result.isError,
        frontendContent = result.frontendContent orElse Some(content)
      )
    }

  end persistAndReplace

  private def formatSize(chars: Int): String =
    if chars < 1024 then s"$chars chars"
    else if chars < 1024 * 1024 then f"${chars / 1024.0}%.1f KB"
    else f"${chars / (1024.0 * 1024.0)}%.1f MB"

end ToolResultGuard
