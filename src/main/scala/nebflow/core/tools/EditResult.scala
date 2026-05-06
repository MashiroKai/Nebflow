package nebflow.core.tools

/** A single hunk in unified diff format.
 *
 *  @param oldStart  1-based start line in old file (includes context)
 *  @param oldCount  total lines from old file in this hunk (context + removed)
 *  @param newStart  1-based start line in new file (includes context)
 *  @param newCount  total lines in new file in this hunk (context + added)
 *  @param lines     unified diff lines with " "/"-""+" prefix
 */
case class DiffHunk(
  oldStart: Int, oldCount: Int,
  newStart: Int, newCount: Int,
  lines: List[String]
):
  def header: String = s"@@ -$oldStart,$oldCount +$newStart,$newCount @@"

/** Structured result of an edit operation. */
case class EditResult(
  filePath: String,
  addedLines: Int,
  removedLines: Int,
  hunks: List[DiffHunk],
  diffText: String
):
  /** Render as the wire-format result string consumed by summarizeResult. */
  def toResultString: String =
    val short = filePath.split("/").lastOption.getOrElse(filePath)
    s"${DiffUtil.OkUpdatedPrefix} $short, $addedLines added, $removedLines removed\n$diffText"

object EditResult:
  /** Render hunks as unified diff text. */
  def renderHunks(hunks: List[DiffHunk]): String =
    if hunks.isEmpty then "(no changes)"
    else hunks.map(h => h.header + "\n" + h.lines.mkString("\n")).mkString("\n")
