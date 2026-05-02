package nebflow.core.tools

import com.github.difflib.DiffUtils.diff
import com.github.difflib.patch.{AbstractDelta, DeltaType}

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.jdk.CollectionConverters.*

/** Shared utilities for EditTool and WriteTool. */
object DiffUtil:

  /** Count non-overlapping occurrences of needle in haystack using indexOf. */
  def countMatches(haystack: String, needle: String): Int =
    var count = 0
    var idx = 0
    while idx <= haystack.length - needle.length do
      val found = haystack.indexOf(needle, idx)
      if found >= 0 then
        count += 1
        idx = found + needle.length
      else idx = haystack.length + 1
    count

  /** Detect line separator used in content. */
  def detectLineSep(content: String): String =
    if content.contains("\r\n") then "\r\n" else "\n"

  /** Read file content as UTF-8. */
  def readFile(path: Path): String =
    Files.readString(path, StandardCharsets.UTF_8)

  /** Write file content as UTF-8, preserving original line separator. */
  def writeFile(path: Path, content: String, lineSep: String): Unit =
    val normalized = content.replace("\r\n", "\n")
    val finalContent = if lineSep != "\n" then normalized.replace("\n", lineSep) else normalized
    Files.writeString(path, finalContent, StandardCharsets.UTF_8)

  /**
   * Compute added/removed line counts using diff-utils.
   */
  def lineStats(oldLines: List[String], newLines: List[String]): (Int, Int) =
    val patch = diff(oldLines.asJava, newLines.asJava)
    var added = 0
    var removed = 0
    patch.getDeltas.asScala.foreach { delta =>
      removed += delta.getSource.size()
      added += delta.getTarget.size()
    }
    (added, removed)

  private val ContextLines = 2

  /**
   * Produce a diff output using diff-utils (Myers algorithm).
   * Shows context lines, removals (-), and additions (+).
   */
  def makeDiff(fileName: String, oldContent: String, newContent: String): String =
    val oldLines = oldContent.split("\\r?\\n").toList
    val newLines = newContent.split("\\r?\\n").toList
    val patch = diff(oldLines.asJava, newLines.asJava)

    if patch.getDeltas.isEmpty then "(no changes)"
    else
      val sb = new StringBuilder
      val deltas = patch.getDeltas.asScala.toList.sortBy(_.getSource.getPosition)

      // Walk through old lines, applying deltas to produce output
      var oldIdx = 0
      var newIdx = 0

      for delta <- deltas do
        val srcPos = delta.getSource.getPosition
        val srcSize = delta.getSource.size()
        val tgtPos = delta.getTarget.getPosition
        val tgtSize = delta.getTarget.size()

        // Context lines before this delta
        val contextStart = math.max(oldIdx, srcPos - ContextLines)
        while oldIdx < srcPos do
          if oldIdx >= contextStart then
            val nn = newIdx + 1
            sb.append(f"$nn%3d |${oldLines(oldIdx)}\n")
          oldIdx += 1
          newIdx += 1

        // Removed lines
        val srcLines = delta.getSource.getLines.asScala
        for i <- srcLines.indices do
          val on = oldIdx + 1
          sb.append(f"$on%3d |-${srcLines(i)}\n")
          oldIdx += 1

        // Added lines
        val tgtLines = delta.getTarget.getLines.asScala
        for i <- tgtLines.indices do
          val nn = newIdx + 1
          sb.append(f"$nn%3d |+${tgtLines(i)}\n")
          newIdx += 1

      // Context lines after last delta
      while oldIdx < oldLines.length do
        val nn = newIdx + 1
        if nn <= newLines.length then
          sb.append(f"$nn%3d |${oldLines(oldIdx)}\n")
        oldIdx += 1
        newIdx += 1

      sb.toString().trim

  end makeDiff

end DiffUtil
