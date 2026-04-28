package nebflow.core.tools

import cats.effect.IO
import io.circe.JsonObject
import io.circe.syntax.*
import nebflow.core.PathSandbox

import java.nio.file.{Files, Path, Paths}

object WriteTool extends Tool:
  val CONTEXT_LINES = 3

  val name = "Write"

  val description = """Writes a file to the local filesystem.

Usage:
- This tool will overwrite the existing file if there is one at the provided path.
- If this is an existing file, you MUST use the Read tool first to read the file's contents. This tool will fail if you did not read the file first.
- Prefer the Edit tool for modifying existing files — it only sends the diff. Only use this tool to create new files or for complete rewrites.
- Only use emojis if the user explicitly requests it. Avoid writing emojis to files unless asked."""

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

  private def makeDiff(fileName: String, oldContent: String, newContent: String): String =
    val oldLines = oldContent.split("\\r?\\n").toList
    val newLines = newContent.split("\\r?\\n").toList
    val sb = new StringBuilder
    var oldIdx = 0
    var newIdx = 0
    while oldIdx < oldLines.length || newIdx < newLines.length do
      if oldIdx < oldLines.length && newIdx < newLines.length && oldLines(oldIdx) == newLines(newIdx) then
        val nn = newIdx + 1
        sb.append(f"$nn%3d |${oldLines(oldIdx)}\n")
        oldIdx += 1
        newIdx += 1
      else
        val matchedOld = oldIdx < oldLines.length && newIdx < newLines.length && oldLines(oldIdx) == newLines(newIdx)
        if !matchedOld && oldIdx < oldLines.length then
          val on = oldIdx + 1
          sb.append(f"$on%3d |-${oldLines(oldIdx)}\n")
          oldIdx += 1
        else
          val matchedNew = oldIdx < oldLines.length && newIdx < newLines.length && oldLines(oldIdx) == newLines(newIdx)
          if !matchedNew && newIdx < newLines.length then
            val nn = newIdx + 1
            sb.append(f"$nn%3d |+${newLines(newIdx)}\n")
            newIdx += 1
          else
            if oldIdx < oldLines.length then oldIdx += 1
            if newIdx < newLines.length then newIdx += 1
    sb.toString().trim

  end makeDiff

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] = IO.blocking {
    val filePathStr = input("file_path").flatMap(_.asString).getOrElse("")
    val content = input("content").flatMap(_.asString).getOrElse("")
    val filePath =
      if filePathStr.startsWith("/") then Paths.get(filePathStr)
      else Paths.get(ctx.projectRoot, filePathStr)

    if !PathSandbox.isAllowed(filePath.toString, ctx.projectRoot) then
      Left(
        ToolError(
          s"Path access denied: $filePath is outside the project root (${ctx.projectRoot}). Use Bash if you need to access files outside the project."
        )
      )
    else

      try
        if Files.exists(filePath) && Files.isDirectory(filePath) then
          Left(ToolError(s"Path is a directory, not a file: $filePath"))
        else
          val isNew = !Files.exists(filePath)
          val dir = filePath.getParent
          if dir != null then Files.createDirectories(dir)

          if isNew then
            Files.writeString(filePath, content)
            Right(s"OK:CREATED $filePath")
          else
            val original = Files.readString(filePath)
            Files.writeString(filePath, content)

            val oldLines = original.split("\\r?\\n").toList
            val newLines = content.split("\\r?\\n").toList
            val added = newLines.count(l => !oldLines.contains(l))
            val removed = oldLines.count(l => !newLines.contains(l))

            val short = filePath.getFileName.toString
            val diff = makeDiff(short, original, content)
            Right(s"OK:UPDATED $short, $added added, $removed removed\n$diff")
      catch case e: Exception => Left(ToolError(s"Error writing file: ${e.getMessage}"))
    end if
  }
end WriteTool
