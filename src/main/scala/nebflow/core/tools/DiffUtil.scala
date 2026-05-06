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
   * Single source of truth for splitting content into lines.
   * Uses split with limit -1 so trailing empty strings are preserved.
   * This keeps line indices, line-replace ranges, lineStats and makeDiff all
   * in agreement with each other.
   */
  def splitLines(content: String): List[String] =
    content.split("\\r?\\n", -1).toList

  /** Join lines using the given separator. */
  def joinLines(lines: List[String], lineSep: String): String =
    lines.mkString(lineSep)

  /** Compute added/removed line counts using diff-utils. */
  def lineStats(oldLines: List[String], newLines: List[String]): (Int, Int) =
    val patch = diff(oldLines.asJava, newLines.asJava)
    var added = 0
    var removed = 0
    patch.getDeltas.asScala.foreach { delta =>
      removed += delta.getSource.size()
      added += delta.getTarget.size()
    }
    (added, removed)

  /** Convenience overload that splits both contents using splitLines. */
  def lineStats(oldContent: String, newContent: String): (Int, Int) =
    lineStats(splitLines(oldContent), splitLines(newContent))

  private val ContextLines = 2

  /**
   * Produce a diff output using diff-utils (Myers algorithm).
   * Shows context lines, removals (-), and additions (+).
   */
  def makeDiff(fileName: String, oldContent: String, newContent: String): String =
    val oldLines = splitLines(oldContent)
    val newLines = splitLines(newContent)
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
      end for

      // Context lines after last delta
      while oldIdx < oldLines.length do
        val nn = newIdx + 1
        if nn <= newLines.length then sb.append(f"$nn%3d |${oldLines(oldIdx)}\n")
        oldIdx += 1
        newIdx += 1

      sb.toString().trim
    end if

  end makeDiff

  /**
   * Produce structured unified diff hunks using diff-utils.
   * Each hunk contains line-level detail with " "/"-""+" prefixed lines.
   */
  def makeUnifiedDiff(fileName: String, oldContent: String, newContent: String): List[DiffHunk] =
    val oldLines = splitLines(oldContent)
    val newLines = splitLines(newContent)
    val patch = diff(oldLines.asJava, newLines.asJava)

    if patch.getDeltas.isEmpty then Nil
    else
      val deltas = patch.getDeltas.asScala.toList.sortBy(_.getSource.getPosition)
      val hunks = List.newBuilder[DiffHunk]

      var oldIdx = 0
      var newIdx = 0

      for delta <- deltas do
        val srcPos = delta.getSource.getPosition
        val tgtPos = delta.getTarget.getPosition
        val lines = List.newBuilder[String]

        // Context lines before this delta
        val ctxStart = math.max(oldIdx, srcPos - ContextLines)
        while oldIdx < srcPos do
          if oldIdx >= ctxStart then lines += (" " + oldLines(oldIdx))
          oldIdx += 1
          newIdx += 1

        // Removed lines
        for line <- delta.getSource.getLines.asScala do lines += ("-" + line)
        oldIdx += delta.getSource.size()

        // Added lines
        for line <- delta.getTarget.getLines.asScala do lines += ("+" + line)

        // Context lines after delta (up to ContextLines)
        var ctxCount = 0
        while oldIdx < oldLines.length && ctxCount < ContextLines do
          lines += (" " + oldLines(oldIdx))
          oldIdx += 1
          ctxCount += 1

        hunks += DiffHunk(
          oldStart = srcPos + 1, // 1-based
          oldLines = delta.getSource.size(),
          newStart = tgtPos + 1, // 1-based
          newLines = delta.getTarget.size(),
          lines = lines.result()
        )

        newIdx += delta.getTarget.size()
      end for

      hunks.result()
    end if

  end makeUnifiedDiff

  // ---------------------------------------------------------------------------
  // Result format contract — single source of truth for OK:CREATED / OK:UPDATED
  // strings produced by EditTool and WriteTool, plus the matching parser used
  // by their summarizeResult implementations.
  // ---------------------------------------------------------------------------

  val OkCreatedPrefix: String = "OK:CREATED"
  val OkUpdatedPrefix: String = "OK:UPDATED"

  /** Render the success string returned when a brand new file was created. */
  def renderCreatedResult(filePath: Path): String =
    s"$OkCreatedPrefix $filePath"

  /** Render the success string returned when an existing file was updated. */
  def renderUpdatedResult(fileName: String, added: Int, removed: Int, diff: String): String =
    s"$OkUpdatedPrefix $fileName, $added added, $removed removed\n$diff"

  private val UpdatedStatsRegex = """OK:UPDATED\s+\S+,\s*(\d+)\s+added,\s*(\d+)\s+removed""".r

  /**
   * Parse an OK:UPDATED result string and return (added, removed) line counts.
   * Returns None when the input is not an OK:UPDATED result or does not match
   * the expected stats format.
   */
  def parseUpdatedStats(result: String): Option[(Int, Int)] =
    UpdatedStatsRegex.findFirstMatchIn(result).map { m =>
      (m.group(1).toInt, m.group(2).toInt)
    }

end DiffUtil
