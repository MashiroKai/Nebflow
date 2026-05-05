package nebflow.core.tools

import cats.effect.IO
import cats.syntax.all.*
import io.circe.JsonObject
import io.circe.syntax.*

import java.nio.file.{Files, Path, Paths}

object EditTool extends Tool:
  val name = "Edit"

  val description = """Performs exact string replacements, line-range replacements, or line insertions in files.

Three modes (selected via the required `mode` field):
1. replace — provide old_string + new_string. The edit will FAIL if old_string is not unique.
2. line_replace — provide start_line [+ end_line] + new_string. Replace lines by line number. More efficient than string matching.
3. insert — provide insert_after_line + content. Insert lines at a specific position.

Usage:
- Recommended: read the file with the Read tool first so the edit is informed by current state and exact indentation.
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
- new_string="" deletes the specified line range entirely (no blank line is left behind).

Insert mode:
- insert_after_line=0 inserts at the beginning of the file.
- insert_after_line=-1 appends to the end of the file.
- content="" inserts a single empty line (useful as a separator/placeholder)."""

  val inputSchema = JsonObject.fromIterable(
    List(
      "type" -> "object".asJson,
      "properties" -> io.circe.Json.obj(
        "file_path" -> io.circe.Json
          .obj("type" -> "string".asJson, "description" -> "The absolute path to the file to modify".asJson),
        "mode" -> io.circe.Json.obj(
          "type" -> "string".asJson,
          "enum" -> io.circe.Json.arr("replace".asJson, "line_replace".asJson, "insert".asJson),
          "description" -> "Which edit mode to use. Required.".asJson
        ),
        "old_string" -> io.circe.Json
          .obj("type" -> "string".asJson, "description" -> "The text to replace (replace mode)".asJson),
        "new_string" -> io.circe.Json.obj(
          "type" -> "string".asJson,
          "description" -> "The replacement text (used in replace and line_replace modes; empty string in line_replace deletes the range)".asJson
        ),
        "replace_all" -> io.circe.Json.obj(
          "type" -> "boolean".asJson,
          "description" -> "Replace all occurences of old_string (default false)".asJson
        ),
        "start_line" -> io.circe.Json.obj(
          "type" -> "integer".asJson,
          "description" -> "Start line to replace (1-based, inclusive). Required for line_replace mode.".asJson
        ),
        "end_line" -> io.circe.Json.obj(
          "type" -> "integer".asJson,
          "description" -> "End line to replace (1-based, inclusive). Defaults to start_line.".asJson
        ),
        "insert_after_line" -> io.circe.Json.obj(
          "type" -> "integer".asJson,
          "description" -> "Insert content after this line number (1-based). 0=beginning, -1=end. Required for insert mode.".asJson
        ),
        "content" -> io.circe.Json.obj(
          "type" -> "string".asJson,
          "description" -> "Content to insert (used with insert mode; empty string inserts a single blank line)".asJson
        )
      ),
      "required" -> io.circe.Json.arr("file_path".asJson, "mode".asJson)
    )
  )

  def summarize(input: JsonObject): String =
    val path = input("file_path").flatMap(_.asString).getOrElse("")
    val short = path.split("/").lastOption.getOrElse(path)
    val mode = resolveMode(input)
    mode match
      case Right(Mode.LineReplace) =>
        val start = input("start_line").flatMap(_.asNumber).flatMap(_.toInt).getOrElse(0)
        input("end_line").flatMap(_.asNumber).flatMap(_.toInt) match
          case Some(e) if e != start => s"Edit($short:$start-$e)"
          case _ => s"Edit($short:$start)"
      case Right(Mode.Insert) =>
        val line = input("insert_after_line").flatMap(_.asNumber).flatMap(_.toInt).getOrElse(-1)
        s"Insert($short:$line)"
      case Right(Mode.Replace) => s"Edit($short)"
      case Left(_) => s"Edit($short, invalid mode)"

  def summarizeResult(input: JsonObject, result: String): String =
    if result.startsWith(DiffUtil.OkUpdatedPrefix) then
      DiffUtil.parseUpdatedStats(result) match
        case Some((added, removed)) =>
          val parts = List(
            Option.when(added > 0)(s"$added line${if added == 1 then "" else "s"} added"),
            Option.when(removed > 0)(s"$removed line${if removed == 1 then "" else "s"} removed")
          ).flatten
          if parts.nonEmpty then parts.mkString(", ") else "Edited"
        case None => "Edited"
    else if result.contains("not found") then "No match found"
    else result.split("\\n").headOption.getOrElse(result)

  // ---------------------------------------------------------------------------
  // Mode resolution
  // ---------------------------------------------------------------------------

  private enum Mode:
    case Replace, LineReplace, Insert

  /**
   * Resolve the edit mode from the explicit `mode` field.
   * Returns Left for missing or unknown mode values.
   */
  private def resolveMode(input: JsonObject): Either[ToolError, Mode] =
    input("mode").flatMap(_.asString) match
      case Some("replace") => Right(Mode.Replace)
      case Some("line_replace") => Right(Mode.LineReplace)
      case Some("insert") => Right(Mode.Insert)
      case Some(other) =>
        Left(ToolError(s"Unknown mode: '$other'. Allowed values: replace, line_replace, insert."))
      case None =>
        Left(ToolError("mode is required"))

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    val filePathStr = input("file_path").flatMap(_.asString).getOrElse("")
    val filePath =
      if filePathStr.startsWith("/") then Paths.get(filePathStr)
      else Paths.get(ctx.projectRoot, filePathStr)

    resolveMode(input) match
      case Left(err) => IO.pure(Left(err))
      case Right(mode) =>
        if !Files.exists(filePath) then IO.pure(Left(ToolError(s"File does not exist: $filePath")))
        else
          val readCheck: IO[Either[ToolError, Unit]] = ctx.readTracker match
            case Some(rt) =>
              rt.hasBeenRead(filePath).map {
                case true => Right(())
                case false =>
                  Left(ToolError(s"File was not read in this session: $filePath. Read it first with the Read tool."))
              }
            case None => IO.pure(Right(()))

          readCheck.flatMap {
            case Left(err) => IO.pure(Left(err))
            case Right(()) =>
              val editIO = IO.blocking {
                mode match
                  case Mode.LineReplace => doLineReplace(input, filePath)
                  case Mode.Insert => doInsert(input, filePath)
                  case Mode.Replace => doReplace(input, filePath)
              }
              val lockedEdit = ctx.fileLockManager match
                case Some(lm) => lm.withWriteLock(filePath)(editIO)
                case None => editIO
              lockedEdit.flatMap {
                case Right(result) =>
                  ctx.readTracker.traverse_(_.recordRead(filePath)).as(Right(result))
                case left => IO.pure(left)
              }
          }
    end match
  end call

  // ---------------------------------------------------------------------------
  // Mode implementations
  // ---------------------------------------------------------------------------

  private def doLineReplace(input: JsonObject, filePath: Path): Either[ToolError, String] =
    val startLineOpt = input("start_line").flatMap(_.asNumber).flatMap(_.toInt)
    startLineOpt match
      case None => Left(ToolError("line_replace mode requires start_line"))
      case Some(startLine) =>
        if !input.contains("new_string") then
          Left(ToolError("line_replace mode requires new_string (use \"\" to delete the range)"))
        else if input.contains("old_string") || input.contains("replace_all") || input.contains("insert_after_line")
        then
          Left(
            ToolError(
              "line_replace mode does not accept old_string / replace_all / insert_after_line; remove those fields"
            )
          )
        else
          val newString = input("new_string").flatMap(_.asString).getOrElse("")
          val endLineOpt = input("end_line").flatMap(_.asNumber).flatMap(_.toInt)
          try
            val original = DiffUtil.readFile(filePath)
            val lineSep = DiffUtil.detectLineSep(original)
            val mtime = Files.getLastModifiedTime(filePath)
            val lines = DiffUtil.splitLines(original)

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
              // Empty new_string => delete the entire range (no blank line left).
              val newContentLines: List[String] =
                if newString.isEmpty then Nil
                else DiffUtil.splitLines(newString)
              val updated = lines.take(start) ++ newContentLines ++ lines.drop(end + 1)
              val updatedStr = DiffUtil.joinLines(updated, lineSep)
              DiffUtil.writeFile(filePath, updatedStr, lineSep)

              val short = filePath.getFileName.toString
              val diff = DiffUtil.makeDiff(short, original, updatedStr)
              val (added, removed) = DiffUtil.lineStats(original, updatedStr)
              Right(DiffUtil.renderUpdatedResult(short, added, removed, diff))
          catch case e: Exception => Left(ToolError(s"Error editing file: ${e.getMessage}"))
          end try
        end if

    end match

  end doLineReplace

  private def doInsert(input: JsonObject, filePath: Path): Either[ToolError, String] =
    val insertLineOpt = input("insert_after_line").flatMap(_.asNumber).flatMap(_.toInt)
    insertLineOpt match
      case None => Left(ToolError("insert mode requires insert_after_line"))
      case Some(lineNum) =>
        if !input.contains("content") then
          Left(ToolError("insert mode requires content (use \"\" to insert a blank line)"))
        else if input.contains("old_string") || input.contains("new_string") || input.contains("start_line") then
          Left(
            ToolError(
              "insert mode does not accept old_string / new_string / start_line; remove those fields"
            )
          )
        else
          val insertContent = input("content").flatMap(_.asString).getOrElse("")
          try
            val original = DiffUtil.readFile(filePath)
            val lineSep = DiffUtil.detectLineSep(original)
            val mtime = Files.getLastModifiedTime(filePath)
            val lines = DiffUtil.splitLines(original)
            val insertIdx = if lineNum < 0 then lines.length else lineNum
            val clamped = Math.max(0, Math.min(insertIdx, lines.length))
            // splitLines("") returns List(""), i.e. inserting an empty content
            // produces a single blank line — matching the documented semantic.
            val insertLines = DiffUtil.splitLines(insertContent)
            val newLines = lines.take(clamped) ++ insertLines ++ lines.drop(clamped)
            val updated = DiffUtil.joinLines(newLines, lineSep)

            if Files.getLastModifiedTime(filePath) != mtime then
              Left(ToolError("File was modified externally. Please re-read and retry."))
            else
              DiffUtil.writeFile(filePath, updated, lineSep)
              val short = filePath.getFileName.toString
              val diff = DiffUtil.makeDiff(short, original, updated)
              val (added, removed) = DiffUtil.lineStats(original, updated)
              Right(DiffUtil.renderUpdatedResult(short, added, removed, diff))
          catch case e: Exception => Left(ToolError(s"Error inserting: ${e.getMessage}"))

    end match

  end doInsert

  private def doReplace(input: JsonObject, filePath: Path): Either[ToolError, String] =
    val oldString = input("old_string").flatMap(_.asString).getOrElse("")
    val newString = input("new_string").flatMap(_.asString).getOrElse("")
    val replaceAll = input("replace_all").flatMap(_.asBoolean).getOrElse(false)

    if oldString.isEmpty then Left(ToolError("replace mode requires old_string"))
    else if input.contains("start_line") || input.contains("insert_after_line") then
      Left(ToolError("replace mode does not accept start_line / insert_after_line; remove those fields"))
    else if oldString == newString then Right("old_string and new_string are exactly the same. No changes to make.")
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

            val (added, removed) = DiffUtil.lineStats(original, updated)
            val short = filePath.getFileName.toString
            val diff = DiffUtil.makeDiff(short, original, updated)
            Right(DiffUtil.renderUpdatedResult(short, added, removed, diff))
        end if
      catch case e: Exception => Left(ToolError(s"Error editing file: ${e.getMessage}"))
    end if
  end doReplace

end EditTool
