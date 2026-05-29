package nebflow.core.tools

import com.github.difflib.DiffUtils.diff

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

  /** Read file content as UTF-8. Uses readAllBytes + String ctor to avoid
   *  Files.readString windows cross-drive decoder issue ("Input length = 1"). */
  def readFile(path: Path): String =
    new String(Files.readAllBytes(path), StandardCharsets.UTF_8)

  /** Write file content as UTF-8, preserving original line separator.
   *  Uses Files.write (byte array) to avoid windows cross-drive decoder issues. */
  def writeFile(path: Path, content: String, lineSep: String): Unit =
    val normalized = content.replace("\r\n", "\n")
    val finalContent = if lineSep != "\n" then normalized.replace("\n", lineSep) else normalized
    Files.write(path, finalContent.getBytes(StandardCharsets.UTF_8))

  /**
   * Single source of truth for splitting content into lines.
   * Uses split with limit -1 so trailing empty strings are preserved.
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
   * Produce structured unified diff hunks using diff-utils.
   * Each DiffHunk.oldCount/newCount reflects the total lines in the hunk
   * (context + changed), matching standard unified diff @@ header semantics.
   */
  def makeUnifiedDiff(oldContent: String, newContent: String): List[DiffHunk] =
    val oldLines = splitLines(oldContent)
    val patch = diff(oldLines.asJava, splitLines(newContent).asJava)

    if patch.getDeltas.isEmpty then Nil
    else
      val deltas = patch.getDeltas.asScala.toList.sortBy(_.getSource.getPosition)
      val hunks = List.newBuilder[DiffHunk]
      var oldIdx = 0

      for delta <- deltas do
        val srcPos = delta.getSource.getPosition
        val tgtPos = delta.getTarget.getPosition
        val hunkLines = List.newBuilder[String]

        // Context lines before this delta
        val ctxStart = math.max(oldIdx, srcPos - ContextLines)
        val ctxBeforeCount = srcPos - ctxStart
        while oldIdx < srcPos do
          if oldIdx >= ctxStart then hunkLines += (" " + oldLines(oldIdx))
          oldIdx += 1

        // Removed lines
        for line <- delta.getSource.getLines.asScala do hunkLines += ("-" + line)
        oldIdx += delta.getSource.size()

        // Added lines
        for line <- delta.getTarget.getLines.asScala do hunkLines += ("+" + line)

        // Context lines after delta (up to ContextLines)
        var ctxCount = 0
        while oldIdx < oldLines.length && ctxCount < ContextLines do
          hunkLines += (" " + oldLines(oldIdx))
          oldIdx += 1
          ctxCount += 1

        val result = hunkLines.result()
        hunks += DiffHunk(
          oldStart = ctxStart + 1, // 1-based, includes context
          oldCount = result.count(l => l.startsWith(" ") || l.startsWith("-")),
          newStart = tgtPos - ctxBeforeCount + 1, // 1-based, accounts for context
          newCount = result.count(l => l.startsWith(" ") || l.startsWith("+")),
          lines = result
        )
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
