package nebflow.core.tools

import cats.effect.IO
import io.circe.JsonObject
import io.circe.syntax.*

import java.nio.file.{Files, Path, Paths}

object EditTool extends Tool:
  val name = "Edit"

  val description = """Performs exact string replacements, line-range replacements, or line insertions in files.

Three modes:
1. Replace mode: Provide old_string + new_string. The edit will FAIL if old_string is not unique.
2. Line Replace mode: Provide start_line [+ end_line] + new_string. Replace lines by line number. More efficient than string matching.
3. Insert mode: Provide insert_after_line + content. Insert lines at a specific position.

Usage:
- You must use your Read tool at least once in the conversation before editing.
- When editing text from Read tool output, ensure you preserve the exact indentation (tabs/spaces) as appears AFTER the line number prefix.
- ALWAYS prefer editing existing files in the codebase. NEVER write new files unless explicitly required.
- Use replace_all for replacing and renaming strings across the file.
- Do not use Bash (sed, awk) to edit files — use this tool instead.

Edit patterns:
- Rename a variable: Use replace_all to change every occurrence. Do not do it one at a time.
- Modify a specific function: Include enough context (function signature, surrounding lines) to make the old_string unique.
- Multi-location edits: If the same change needs to happen in multiple places, use multiple Edit calls in parallel rather than trying to write a complex regex.
- Large refactors: If a change affects more than 3-4 files, consider whether the scope matches what the user asked for.

Line Replace mode:
- Provide start_line (1-based) and new_string to replace a single line.
- Add end_line to replace a range of lines (inclusive).
- Example: start_line=10, end_line=15, new_string="replacement" replaces lines 10-15.

Insert mode:
- insert_after_line=0 inserts at the beginning of the file.
- insert_after_line=-1 appends to the end of the file."""

  val inputSchema = JsonObject.fromIterable(
    List(
      "type" -> "object".asJson,
      "properties" -> io.circe.Json.obj(
        "file_path" -> io.circe.Json
          .obj("type" -> "string".asJson, "description" -> "The absolute path to the file to modify".asJson),
        "old_string" -> io.circe.Json
          .obj("type" -> "string".asJson, "description" -> "The text to replace (replace mode)".asJson),
        "new_string" -> io.circe.Json.obj(
          "type" -> "string".asJson,
          "description" -> "The replacement text (used in replace and line-replace modes)".asJson
        ),
        "replace_all" -> io.circe.Json.obj(
          "type" -> "boolean".asJson,
          "description" -> "Replace all occurences of old_string (default false)".asJson
        ),
        "start_line" -> io.circe.Json.obj(
          "type" -> "integer".asJson,
          "description" -> "Start line to replace (1-based, inclusive). Enables line-replace mode.".asJson
        ),
        "end_line" -> io.circe.Json.obj(
          "type" -> "integer".asJson,
          "description" -> "End line to replace (1-based, inclusive). Defaults to start_line.".asJson
        ),
        "insert_after_line" -> io.circe.Json.obj(
          "type" -> "integer".asJson,
          "description" -> "Insert content after this line number (1-based). 0=beginning, -1=end.".asJson
        ),
        "content" -> io.circe.Json.obj(
          "type" -> "string".asJson,
          "description" -> "Content to insert (used with insert_after_line)".asJson
        )
      ),
      "required" -> io.circe.Json.arr("file_path".asJson)
    )
  )

  def summarize(input: JsonObject): String =
    val path = input("file_path").flatMap(_.asString).getOrElse("")
    val short = path.split("/").lastOption.getOrElse(path)
    if input.contains("start_line") then
      val start = input("start_line").flatMap(_.asNumber).flatMap(_.toInt).getOrElse(0)
      input("end_line").flatMap(_.asNumber).flatMap(_.toInt) match
        case Some(e) if e != start => s"Edit($short:$start-$e)"
        case _ => s"Edit($short:$start)"
    else if input.contains("insert_after_line") then
      val line = input("insert_after_line").flatMap(_.asNumber).flatMap(_.toInt).getOrElse(-1)
      s"Insert($short:$line)"
    else s"Edit($short)"

  def summarizeResult(input: JsonObject, result: String): String =
    if result.startsWith("OK:") then
      val added = "(\\d+) added".r.findFirstMatchIn(result).map(_.group(1))
      val removed = "(\\d+) removed".r.findFirstMatchIn(result).map(_.group(1))
      val parts = List(
        added.map(a => s"$a line${if a == "1" then "" else "s"} added"),
        removed.map(r => s"$r line${if r == "1" then "" else "s"} removed")
      ).flatten
      if parts.nonEmpty then parts.mkString(", ") else "Edited"
    else if result.contains("not found") then "No match found"
    else if result.contains("matches") then result.split("\\n").headOption.getOrElse(result)
    else result.split("\\n").headOption.getOrElse(result)

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] = IO.blocking {
    val filePathStr = input("file_path").flatMap(_.asString).getOrElse("")
    val filePath =
      if filePathStr.startsWith("/") then Paths.get(filePathStr)
      else Paths.get(ctx.projectRoot, filePathStr)

    if !Files.exists(filePath) then Left(ToolError(s"File does not exist: $filePath"))
    else
      val startLineOpt = input("start_line").flatMap(_.asNumber).flatMap(_.toInt)
      val endLineOpt = input("end_line").flatMap(_.asNumber).flatMap(_.toInt)
      val insertLine = input("insert_after_line").flatMap(_.asNumber).flatMap(_.toInt)

      // Priority: start_line > insert_after_line > old_string
      startLineOpt match
        case Some(startLine) =>
          // === Line Replace mode ===
          val newString = input("new_string").flatMap(_.asString).getOrElse("")
          if newString.isEmpty then Left(ToolError("new_string is required when using start_line"))
          else if input.contains("old_string") || input.contains("replace_all") || insertLine.isDefined then
            Left(ToolError("Cannot use start_line together with old_string/replace_all/insert_after_line"))
          else
            try
              val original = DiffUtil.readFile(filePath)
              val lineSep = DiffUtil.detectLineSep(original)
              val mtime = Files.getLastModifiedTime(filePath)
              val lines = original.split("\\r?\\n", -1).toList

              // 1-based → 0-based
              val start = (startLine - 1).max(0)
              val end = endLineOpt.map(e => (e - 1).max(0)).getOrElse(start)

              if start >= lines.length then
                Left(ToolError(s"start_line $startLine exceeds file length (${lines.length} lines)"))
              else if end >= lines.length then
                Left(
                  ToolError(s"end_line ${endLineOpt.getOrElse(startLine)} exceeds file length (${lines.length} lines)")
                )
              else if start > end then
                Left(ToolError(s"start_line ($startLine) must be <= end_line (${endLineOpt.getOrElse(startLine)})"))
              else if Files.getLastModifiedTime(filePath) != mtime then
                Left(ToolError("File was modified externally. Please re-read and retry."))
              else
                val replacedCount = end - start + 1
                val newContentLines = newString.split("\\r?\\n", -1).toList
                val updated = lines.take(start) ++ newContentLines ++ lines.drop(end + 1)
                val updatedStr = updated.mkString(lineSep)
                DiffUtil.writeFile(filePath, updatedStr, lineSep)

                val short = filePath.getFileName.toString
                val diff = DiffUtil.makeDiff(short, original, updatedStr)
                val (added, removed) = DiffUtil.lineStats(
                  original.split("\\r?\\n").toList,
                  updatedStr.split("\\r?\\n").toList
                )
                Right(s"OK: $short updated, $added added, $removed removed\n$diff")
            catch case e: Exception => Left(ToolError(s"Error editing file: ${e.getMessage}"))
          end if

        case None =>
          insertLine match
            case Some(lineNum) =>
              // === Insert mode ===
              val insertContent = input("content").flatMap(_.asString).getOrElse("")
              if input.contains("old_string") || input.contains("new_string") then
                Left(ToolError("Cannot use insert_after_line together with old_string/new_string"))
              else if insertContent.isEmpty then Left(ToolError("content is required when using insert_after_line"))
              else
                try
                  val original = DiffUtil.readFile(filePath)
                  val lineSep = DiffUtil.detectLineSep(original)
                  val lines = original.split("\\r?\\n", -1).toList
                  val insertIdx = if lineNum < 0 then lines.length else lineNum
                  val clamped = Math.max(0, Math.min(insertIdx, lines.length))
                  val insertLines = insertContent.split("\\r?\\n", -1).toList
                  val newLines = lines.take(clamped) ++ insertLines ++ lines.drop(clamped)
                  val updated = newLines.mkString(lineSep)
                  DiffUtil.writeFile(filePath, updated, lineSep)

                  val short = filePath.getFileName.toString
                  val diff = DiffUtil.makeDiff(short, original, updated)
                  Right(s"OK: $short updated, ${insertLines.length} inserted after line $clamped\n$diff")
                catch case e: Exception => Left(ToolError(s"Error inserting: ${e.getMessage}"))

            case None =>
              // === Replace mode (string matching) ===
              val oldString = input("old_string").flatMap(_.asString).getOrElse("")
              val newString = input("new_string").flatMap(_.asString).getOrElse("")
              val replaceAll = input("replace_all").flatMap(_.asBoolean).getOrElse(false)

              if oldString.isEmpty then Left(ToolError("old_string is required for string replace mode"))
              else if oldString == newString then
                Right("old_string and new_string are exactly the same. No changes to make.")
              else
                try
                  val original = DiffUtil.readFile(filePath)
                  val lineSep = DiffUtil.detectLineSep(original)
                  val mtime = Files.getLastModifiedTime(filePath)

                  if !original.contains(oldString) then
                    Left(
                      ToolError(
                        "old_string not found in file. Ensure the string matches exactly, including whitespace and indentation."
                      )
                    )
                  else
                    val matchCount = DiffUtil.countMatches(original, oldString)
                    if matchCount > 1 && !replaceAll then
                      Left(
                        ToolError(
                          s"Found $matchCount matches of old_string. Either provide more context to make it unique, or set replace_all to true."
                        )
                      )
                    else if Files.getLastModifiedTime(filePath) != mtime then
                      Left(ToolError("File was modified externally. Please re-read and retry."))
                    else
                      val updated =
                        if replaceAll then original.replace(oldString, newString)
                        else
                          original.replaceFirst(
                            java.util.regex.Pattern.quote(oldString),
                            java.util.regex.Matcher.quoteReplacement(newString)
                          )
                      DiffUtil.writeFile(filePath, updated, lineSep)

                      val oldLines = original.split("\\r?\\n").toList
                      val newLines = updated.split("\\r?\\n").toList
                      val (added, removed) = DiffUtil.lineStats(oldLines, newLines)

                      val short = filePath.getFileName.toString
                      val diff = DiffUtil.makeDiff(short, original, updated)
                      Right(s"OK: $short updated, $added added, $removed removed\n$diff")
                    end if
                  end if
                catch case e: Exception => Left(ToolError(s"Error editing file: ${e.getMessage}"))
              end if
          end match
      end match
    end if
  }
end EditTool
