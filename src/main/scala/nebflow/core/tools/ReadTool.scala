package nebflow.core.tools

import cats.effect.IO
import cats.syntax.all.*
import io.circe.JsonObject
import io.circe.syntax.*

import java.nio.file.{Files, Path, Paths}

object ReadTool extends Tool:
  val MAX_LINE_COUNT = 2000
  val MAX_FILE_BYTES = 512 * 1024 // 512KB — approx 128k tokens, well within context
  // Context pressure check is handled centrally by ToolResultGuard in executeTool

  val name = "Read"

  val description = """Reads a file from the local filesystem. You can access any file directly by using this tool.
Assume this tool is able to read all files on the machine. If the User provides a path to a file assume that path is valid. It is okay to read a file that does not exist; an error will be returned.

Usage:
- The file_path parameter must be an absolute path, not a relative path.
- By default, it reads up to 2000 lines starting from the beginning of the file.
- You can optionally specify a line offset and limit (especially handy for long files), but it's recommended to read the whole file when the file is of reasonable size.
- Results are returned using cat -n format, with line numbers starting at 1.
- ALWAYS use Read (not Bash with cat/head/tail) to read files.
- Always read a file before editing it."""

  val inputSchema = JsonObject.fromIterable(
    List(
      "type" -> io.circe.Json.fromString("object"),
      "properties" -> io.circe.Json.obj(
        "file_path" -> io.circe.Json
          .obj("type" -> "string".asJson, "description" -> "The absolute path to the file to read".asJson),
        "offset" -> io.circe.Json
          .obj("type" -> "number".asJson, "description" -> "The line number to start reading from (1-based)".asJson),
        "limit" -> io.circe.Json.obj("type" -> "number".asJson, "description" -> "The number of lines to read".asJson)
      ),
      "required" -> io.circe.Json.arr("file_path".asJson)
    )
  )

  def summarize(input: JsonObject): String =
    val path = input("file_path").flatMap(_.asString).getOrElse("")
    val short = path.split("/").lastOption.getOrElse(path)
    val offset = input("offset").flatMap(_.asNumber).flatMap(_.toInt)
    val limit = input("limit").flatMap(_.asNumber).flatMap(_.toInt)
    val params = List(
      offset.map(o => s"offset=$o"),
      limit.map(l => s"limit=$l")
    ).flatten.mkString(", ")
    val paramStr = if params.nonEmpty then s", $params" else ""
    s"""Read($short$paramStr)\n  ("$path")"""

  def summarizeResult(input: JsonObject, result: String): String =
    if result.startsWith("File does not exist") || result.startsWith("Error") then result
    else if result.contains("showing") then
      val m = "showing (\\d+) of (\\d+) lines".r.findFirstMatchIn(result)
      m.map(m => s"${m.group(1)} of ${m.group(2)} lines").getOrElse(s"${result.split("\\n").length} lines")
    else s"${result.split("\\n").length} lines"

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    val filePathStr = input("file_path").flatMap(_.asString).getOrElse("")
    val filePath =
      if filePathStr.startsWith("/") then Paths.get(filePathStr)
      else Paths.get(ctx.projectRoot, filePathStr)

    IO.blocking {
      if !Files.exists(filePath) then Left(ToolError(s"File does not exist: $filePath"))
      else if Files.isDirectory(filePath) then
        Left(ToolError(s"Path is a directory, not a file: $filePath. Use Bash with ls to list directory contents."))
      else if Files.size(filePath) > MAX_FILE_BYTES then
        val sizeMb = Files.size(filePath).toDouble / 1024 / 1024
        val shortName = filePath.getFileName.toString
        Left(
          ToolError(
            s"File too large to read safely: $shortName (${f"$sizeMb%.1f"}MB, limit ${MAX_FILE_BYTES / 1024 / 1024}MB). " +
              s"Use offset/limit to read specific sections, or Bash with head/tail."
          )
        )
      else
        try
          val content = Files.readString(filePath)
          val lines = content.split("\\r?\\n").toList
          val start = input("offset").flatMap(_.asNumber).flatMap(_.toInt).map(_ - 1).getOrElse(0)
          val end = input("limit").flatMap(_.asNumber).flatMap(_.toInt) match
            case Some(limit) => start + limit
            case None => Math.min(lines.length, start + MAX_LINE_COUNT)
          val selected = lines.slice(start, end)

          val result = selected.zipWithIndex
            .map { case (line, i) =>
              s"${start + i + 1}\t$line"
            }
            .mkString("\n")

          val totalLines = lines.length
          val showedLines = selected.length
          val suffix = if showedLines < totalLines then s"\n\n(showing $showedLines of $totalLines lines)" else ""
          Right(result + suffix)
        catch case e: Exception => Left(ToolError(s"Error reading file: ${e.getMessage}"))
    }.flatMap {
      case Right(output) =>
        ctx.readTracker.traverse_(_.recordRead(filePath)).as(Right(output))
      case left => IO.pure(left)
    }
  end call
end ReadTool
