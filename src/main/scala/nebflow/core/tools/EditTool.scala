package nebflow.core.tools

import cats.effect.IO
import io.circe.JsonObject
import io.circe.syntax.*
import java.nio.file.{Files, Path, Paths}

object EditTool extends Tool:
  val name = "Edit"

  val description = """Performs exact string replacements in files.

Usage:
- You must use your Read tool at least once in the conversation before editing. This tool will error if you attempt to edit without reading the file first.
- When editing text from Read tool output, ensure you preserve the exact indentation (tabs/spaces) as it appears AFTER the line number prefix. The line number prefix format is: line number + tab. Everything after that is the actual file content to match. Never include any part of the line number prefix in the old_string or new_string.
- ALWAYS prefer editing existing files in the codebase. NEVER write new files unless explicitly required.
- The edit will FAIL if old_string is not unique in the file. Either provide a larger string with more surrounding context to make it unique or use replace_all to change every instance of old_string.
- Use replace_all for replacing and renaming strings across the file."""

  val inputSchema = JsonObject.fromIterable(List(
    "type" -> "object".asJson,
    "properties" -> io.circe.Json.obj(
      "file_path" -> io.circe.Json.obj("type" -> "string".asJson, "description" -> "The absolute path to the file to modify".asJson),
      "old_string" -> io.circe.Json.obj("type" -> "string".asJson, "description" -> "The text to replace".asJson),
      "new_string" -> io.circe.Json.obj("type" -> "string".asJson, "description" -> "The text to replace it with (must be different from old_string)".asJson),
      "replace_all" -> io.circe.Json.obj("type" -> "boolean".asJson, "description" -> "Replace all occurences of old_string (default false)".asJson)
    ),
    "required" -> io.circe.Json.arr("file_path".asJson, "old_string".asJson, "new_string".asJson)
  ))

  def summarize(input: JsonObject): String =
    val path = input("file_path").flatMap(_.asString).getOrElse("")
    val short = path.split("/").lastOption.getOrElse(path)
    s"Edit($short)"

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
    val oldString = input("old_string").flatMap(_.asString).getOrElse("")
    val newString = input("new_string").flatMap(_.asString).getOrElse("")
    val replaceAll = input("replace_all").flatMap(_.asBoolean).getOrElse(false)
    val filePath = if filePathStr.startsWith("/") then Paths.get(filePathStr)
      else Paths.get(ctx.projectRoot, filePathStr)

    if !Files.exists(filePath) then
      Right(s"File does not exist: $filePath")
    else if oldString == newString then
      Right("old_string and new_string are exactly the same. No changes to make.")
    else
      try
        val original = Files.readString(filePath)
        if !original.contains(oldString) then
          Right("old_string not found in file. Ensure the string matches exactly, including whitespace and indentation.")
        else
          val matchCount = original.sliding(oldString.length).count(_ == oldString)
          if matchCount > 1 && !replaceAll then
            Right(s"Found $matchCount matches of old_string. Either provide more context to make it unique, or set replace_all to true.")
          else
            val updated = if replaceAll then original.replace(oldString, newString)
              else original.replaceFirst(java.util.regex.Pattern.quote(oldString), java.util.regex.Matcher.quoteReplacement(newString))
            Files.writeString(filePath, updated)

            val oldLines = original.split("\\r?\\n").toList
            val newLines = updated.split("\\r?\\n").toList
            val added = newLines.count(l => !oldLines.contains(l))
            val removed = oldLines.count(l => !newLines.contains(l))

            val short = filePath.getFileName.toString
            Right(s"OK: $short updated, $added added, $removed removed")
      catch
        case e: Exception => Right(s"Error editing file: ${e.getMessage}")
  }
