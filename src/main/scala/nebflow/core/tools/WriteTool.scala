package nebflow.core.tools

import cats.effect.IO
import cats.syntax.all.*
import io.circe.JsonObject
import io.circe.syntax.*

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

object WriteTool extends Tool:
  val CONTEXT_LINES = 3

  /**
   * Maximum content size accepted by Write, in UTF-8 encoded bytes.
   * Aligned with ReadTool.MAX_FILE_BYTES so anything Read can return is
   * also writable in a single Write call.
   */
  val MAX_WRITE_BYTES: Int = 512 * 1024

  val name = "Write"

  val description = """Writes a file to the local filesystem.

Usage:
- This tool will overwrite the existing file if there is one at the provided path.
- Recommended: read the existing file first so the new content is informed by current state.
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
    if result.startsWith(DiffUtil.OkCreatedPrefix) then "File created"
    else if result.startsWith(DiffUtil.OkUpdatedPrefix) then
      DiffUtil.parseUpdatedStats(result) match
        case Some((added, removed)) =>
          val parts = List(
            Option.when(added > 0)(s"$added line${if added == 1 then "" else "s"} added"),
            Option.when(removed > 0)(s"$removed line${if removed == 1 then "" else "s"} removed")
          ).flatten
          if parts.nonEmpty then parts.mkString(", ") else "File updated"
        case None => "File updated"
    else result.split("\\n").headOption.getOrElse(result)

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    val filePathStr = input("file_path").flatMap(_.asString).getOrElse("")
    val content = input("content").flatMap(_.asString).getOrElse("")
    val filePath =
      if filePathStr.startsWith("/") then Paths.get(filePathStr)
      else Paths.get(ctx.projectRoot, filePathStr)

    val contentBytes = content.getBytes(StandardCharsets.UTF_8).length
    if contentBytes > MAX_WRITE_BYTES then
      IO.pure(
        Left(
          ToolError(
            s"Content too large: $contentBytes bytes (limit $MAX_WRITE_BYTES). Split into multiple Edit calls."
          )
        )
      )
    else if Files.exists(filePath) && Files.isDirectory(filePath) then
      IO.pure(Left(ToolError(s"Path is a directory, not a file: $filePath")))
    else
      val isNew = !Files.exists(filePath)
      val readCheck: IO[Either[ToolError, Unit]] =
        if !isNew then
          ctx.readTracker match
            case Some(rt) =>
              rt.hasBeenRead(filePath).map {
                case true => Right(())
                case false =>
                  Left(ToolError(s"File was not read in this session: $filePath. Read it first with the Read tool."))
              }
            case None => IO.pure(Right(()))
        else IO.pure(Right(()))

      readCheck.flatMap {
        case Left(err) => IO.pure(Left(err))
        case Right(()) =>
          val writeIO = IO.blocking {
            try
              val dir = filePath.getParent
              if dir != null then Files.createDirectories(dir)

              if isNew then
                DiffUtil.writeFile(filePath, content, "\n")
                Right(DiffUtil.renderCreatedResult(filePath))
              else
                val original = DiffUtil.readFile(filePath)
                val lineSep = DiffUtil.detectLineSep(original)
                val mtime = Files.getLastModifiedTime(filePath)

                // Compute the diff/stats before re-checking mtime so any work
                // between read and write is bracketed by the mtime guard.
                val short = filePath.getFileName.toString
                val hunks = DiffUtil.makeUnifiedDiff(original, content)
                val editResult = EditResult(
                  filePath = filePath.toString,
                  addedLines = hunks.map(_.lines.count(_.startsWith("+"))).sum,
                  removedLines = hunks.map(_.lines.count(_.startsWith("-"))).sum,
                  hunks = hunks,
                  diffText = EditResult.renderHunks(hunks)
                )

                if Files.getLastModifiedTime(filePath) != mtime then
                  // mtime changed — only fail if content actually differs
                  val current = DiffUtil.readFile(filePath)
                  if current != original then
                    Left(ToolError("File was modified externally between read and write. Please re-check and retry."))
                  else
                    DiffUtil.writeFile(filePath, content, lineSep)
                    Right(editResult.toResultString)
                else
                  DiffUtil.writeFile(filePath, content, lineSep)
                  Right(editResult.toResultString)
              end if
            catch case e: Exception => Left(ToolError(s"Error writing file: ${e.getMessage}"))
          }
          val lockedWrite = ctx.fileLockManager match
            case Some(lm) => lm.withWriteLock(filePath)(writeIO)
            case None => writeIO
          lockedWrite.flatMap {
            case Right(result) =>
              val record = ctx.readTracker.traverse_(_.recordRead(filePath)) *>
                ctx.fileChangeTracker.traverse_(_.recordAgentModification(filePath.toString))
              record.as(Right(result))
            case left => IO.pure(left)
          }
      }
    end if
  end call
end WriteTool
