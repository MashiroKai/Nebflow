package nebflow.core.tools

import cats.effect.IO
import io.circe.JsonObject
import io.circe.syntax.*

import java.nio.file.{Files, Path, Paths}

object WriteTool extends Tool:
  val CONTEXT_LINES = 3

  val name = "Write"

  val description = """Writes a file to the local filesystem.

Usage:
- This tool will overwrite the existing file if there is one at the provided path.
- If this is an existing file, you MUST use the Read tool first to read the file's contents. This tool will fail if you did not read the file first.
- ALWAYS prefer editing existing files in the codebase. NEVER write new files unless explicitly required.
- Prefer the Edit tool for modifying existing files — it only sends the diff. Only use this tool to create new files or for complete rewrites.
- Only use emojis if the user explicitly requests it. Avoid writing emojis to files unless asked.
- Do not use Bash (echo, cat heredoc) to create files — use this tool instead."""

  val inputSchema = JsonObject.fromIterable(
    List(
      "type" -> "object".asJson,
      "properties" -> io.circe.Json.obj(
        "file_path" -> io.circe.Json.obj(
          "type" -> "string".asJson,
          "description" -> "The absolute path to the file to write (must be absolute, not relative)".asJson
        ),
        "content" -> io.circe.Json
          .obj("type" -> "string".asJson, "description" -> "The content to write to the file".asJson)
      ),
      "required" -> io.circe.Json.arr("file_path".asJson, "content".asJson)
    )
  )

  def summarize(input: JsonObject): String =
    val path = input("file_path").flatMap(_.asString).getOrElse("")
    val short = path.split("/").lastOption.getOrElse(path)
    s"Write($short)"

  def summarizeResult(input: JsonObject, result: String): String =
    if result.startsWith("OK:CREATED") then "File created"
    else if result.startsWith("OK:UPDATED") then
      val added = "(\\d+) added".r.findFirstMatchIn(result).map(_.group(1))
      val removed = "(\\d+) removed".r.findFirstMatchIn(result).map(_.group(1))
      val parts = List(
        added.map(a => s"$a line${if a == "1" then "" else "s"} added"),
        removed.map(r => s"$r line${if r == "1" then "" else "s"} removed")
      ).flatten
      if parts.nonEmpty then parts.mkString(", ") else "File updated"
    else result.split("\\n").headOption.getOrElse(result)

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] = IO.blocking {
    val filePathStr = input("file_path").flatMap(_.asString).getOrElse("")
    val content = input("content").flatMap(_.asString).getOrElse("")
    val filePath =
      if filePathStr.startsWith("/") then Paths.get(filePathStr)
      else Paths.get(ctx.projectRoot, filePathStr)

    try
      if Files.exists(filePath) && Files.isDirectory(filePath) then
        Left(ToolError(s"Path is a directory, not a file: $filePath"))
      else
        val isNew = !Files.exists(filePath)
        val dir = filePath.getParent
        if dir != null then Files.createDirectories(dir)

        if isNew then
          DiffUtil.writeFile(filePath, content, "\n")
          Right(s"OK:CREATED $filePath")
        else
          val original = DiffUtil.readFile(filePath)
          val lineSep = DiffUtil.detectLineSep(original)
          DiffUtil.writeFile(filePath, content, lineSep)

          val oldLines = original.split("\\r?\\n").toList
          val newLines = content.split("\\r?\\n").toList
          val (added, removed) = DiffUtil.lineStats(oldLines, newLines)

          val short = filePath.getFileName.toString
          val diff = DiffUtil.makeDiff(short, original, content)
          Right(s"OK:UPDATED $short, $added added, $removed removed\n$diff")
    catch case e: Exception => Left(ToolError(s"Error writing file: ${e.getMessage}"))
  }
end WriteTool
