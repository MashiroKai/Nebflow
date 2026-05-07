package nebflow.core.tools

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import io.circe.JsonObject
import io.circe.syntax.*
import nebflow.core.NebflowLogger

import java.nio.file.{Files, Path, Paths}

private val logger = NebflowLogger.forName("nebflow.edit")

object EditTool extends Tool:
  val name = "Edit"

  val description = """Performs exact string replacements in files.

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
- Large refactors: If a change affects more than 3-4 files, consider whether the scope matches what the user asked for."""

  val inputSchema = JsonObject.fromIterable(
    List(
      "type" -> "object".asJson,
      "properties" -> io.circe.Json.obj(
        "file_path" -> io.circe.Json
          .obj("type" -> "string".asJson, "description" -> "The absolute path to the file to modify".asJson),
        "old_string" -> io.circe.Json.obj(
          "type" -> "string".asJson,
          "description" -> "The text to replace".asJson
        ),
        "new_string" -> io.circe.Json.obj(
          "type" -> "string".asJson,
          "description" -> "The replacement text".asJson
        ),
        "replace_all" -> io.circe.Json.obj(
          "type" -> "boolean".asJson,
          "description" -> "Replace all occurences of old_string (default false)".asJson
        )
      ),
      "required" -> io.circe.Json.arr("file_path".asJson, "old_string".asJson, "new_string".asJson)
    )
  )

  // 1 GiB — checked BEFORE reading file content to prevent OOM
  private val MaxEditFileSize = 1L * 1024 * 1024 * 1024

  def summarize(input: JsonObject): String =
    val path = input("file_path").flatMap(_.asString).getOrElse("")
    val short = path.split("/").lastOption.getOrElse(path)
    input("replace_all").flatMap(_.asBoolean).getOrElse(false) match
      case true => s"""Edit($short, "$path", replace_all)"""
      case false => s"""Edit($short, "$path")"""

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
    else if result.startsWith(DiffUtil.OkCreatedPrefix) then "Created"
    else result.split("\\n").headOption.getOrElse(result)

  // ---------------------------------------------------------------------------
  // Lock-free pre-validation (does not read file content)
  // ---------------------------------------------------------------------------

  private def validateInput(filePath: Path, input: JsonObject): Either[ToolError, Unit] =
    val oldString = input("old_string").flatMap(_.asString).getOrElse("")
    val newString = input("new_string").flatMap(_.asString).getOrElse("")
    val replaceAll = input("replace_all").flatMap(_.asBoolean).getOrElse(false)

    if filePath.toString.endsWith(".ipynb") then
      Left(ToolError("File is a Jupyter Notebook. Use the NotebookEdit tool to edit this file."))
    else if oldString.isEmpty && replaceAll then Left(ToolError("replace_all cannot be used with empty old_string."))
    else if oldString.isEmpty && newString.isEmpty then Left(ToolError("Cannot create file with empty content."))
    else if oldString == newString then Left(ToolError("old_string and new_string are identical — no change needed."))
    else Right(())

  // ---------------------------------------------------------------------------
  // Lock-internal validation (needs file size / content)
  // ---------------------------------------------------------------------------

  private def validateInLock(filePath: Path, content: String, oldString: String): Either[ToolError, Unit] =
    val size = Files.size(filePath)
    if size > MaxEditFileSize then
      Left(ToolError(s"File too large to edit (${formatSize(size)}). Maximum is ${formatSize(MaxEditFileSize)}."))
    else if oldString.isEmpty && content.trim.nonEmpty then
      Left(ToolError("Cannot create new file — file already exists and has content. Use the Write tool to overwrite."))
    else Right(())

  private def formatSize(bytes: Long): String =
    if bytes >= 1024 * 1024 * 1024 then f"${bytes.toDouble / (1024 * 1024 * 1024)}%.1f GiB"
    else if bytes >= 1024 * 1024 then f"${bytes.toDouble / (1024 * 1024)}%.1f MiB"
    else if bytes >= 1024 then f"${bytes.toDouble / 1024}%.1f KiB"
    else s"$bytes B"

  // ---------------------------------------------------------------------------
  // Main entry point
  // ---------------------------------------------------------------------------

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    val filePathStr = input("file_path").flatMap(_.asString).getOrElse("")
    val filePath =
      if filePathStr.startsWith("/") then Paths.get(filePathStr)
      else Paths.get(ctx.projectRoot, filePathStr)

    val oldString = input("old_string").flatMap(_.asString).getOrElse("")
    val newString = input("new_string").flatMap(_.asString).getOrElse("")
    val replaceAll = input("replace_all").flatMap(_.asBoolean).getOrElse(false)

    // Phase 1: lock-free pre-validation
    validateInput(filePath, input) match
      case Left(err) => IO.pure(Left(err))
      case Right(()) =>
        // readTracker check (lock-free)
        val readCheck: IO[Either[ToolError, Unit]] =
          if oldString.isEmpty then IO.pure(Right(())) // new file creation doesn't require read
          else
            ctx.readTracker match
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
            val editIO = IO.blocking { doEdit(filePath, oldString, newString, replaceAll) }
            val lockedEdit = ctx.fileLockManager match
              case Some(lm) => lm.withWriteLock(filePath)(editIO)
              case None => editIO
            lockedEdit.flatMap {
              case Right(result) =>
                val record = ctx.readTracker.traverse_(_.recordRead(filePath)) *>
                  ctx.fileChangeTracker.traverse_(_.recordAgentModification(filePath.toString))
                record.as(Right(result))
              case left => IO.pure(left)
            }
        }
    end match
  end call

  // ---------------------------------------------------------------------------
  // Core edit logic (runs inside write lock)
  // ---------------------------------------------------------------------------

  private def doEdit(
    filePath: Path,
    oldString: String,
    newString: String,
    replaceAll: Boolean
  ): Either[ToolError, String] =
    try
      // --- New file creation branch ---
      if oldString.isEmpty then
        if Files.exists(filePath) then
          val content = DiffUtil.readFile(filePath)
          validateInLock(filePath, content, oldString) match
            case Left(err) => Left(err)
            case Right(()) =>
              DiffUtil.writeFile(filePath, newString, "\n")
              Right(DiffUtil.renderCreatedResult(filePath))
        else
          val parent = filePath.getParent
          if parent != null && !Files.exists(parent) then Files.createDirectories(parent)
          DiffUtil.writeFile(filePath, newString, "\n")
          Right(DiffUtil.renderCreatedResult(filePath))
      else
        // --- Existing file edit branch ---
        if !Files.exists(filePath) then Left(ToolError(s"File does not exist: $filePath"))
        else
          // File size check BEFORE reading content
          val size = Files.size(filePath)
          if size > MaxEditFileSize then
            Left(ToolError(s"File too large to edit (${formatSize(size)}). Maximum is ${formatSize(MaxEditFileSize)}."))
          else
            val content = DiffUtil.readFile(filePath)
            val lineSep = DiffUtil.detectLineSep(content)
            val mtime = Files.getLastModifiedTime(filePath)

            // Fuzzy matching
            StringMatcher.findActualString(content, oldString) match
              case None =>
                Left(
                  ToolError(
                    "old_string not found in file. Ensure the string matches exactly, including whitespace and indentation."
                  )
                )
              case Some(actualOld) =>
                // Uniqueness check
                val matchCount = DiffUtil.countMatches(content, actualOld)
                if matchCount > 1 && !replaceAll then
                  Left(
                    ToolError(
                      s"Found $matchCount matches of old_string. Either provide more context to make it unique, or set replace_all to true."
                    )
                  )
                else
                  // Apply preserveQuoteStyle when fuzzy match was used
                  val effectiveNew =
                    if actualOld != oldString then StringMatcher.preserveQuoteStyle(oldString, actualOld, newString)
                    else newString

                  // Double-check concurrency: mtime + content comparison
                  val currentMtime = Files.getLastModifiedTime(filePath)
                  if currentMtime != mtime then
                    val currentContent = DiffUtil.readFile(filePath)
                    if currentContent != content then
                      Left(ToolError("File was modified externally. Please re-read and retry."))
                    else performReplace(filePath.toString, content, actualOld, effectiveNew, replaceAll, lineSep)
                  else performReplace(filePath.toString, content, actualOld, effectiveNew, replaceAll, lineSep)
            end match
          end if
    catch
      case e: Exception =>
        logger.warn(s"Error editing file ${filePath}: ${e.getClass.getSimpleName}: ${e.getMessage}").unsafeRunSync()
        Left(ToolError(s"Error editing file: ${e.getMessage}"))

  private def performReplace(
    filePathStr: String,
    content: String,
    actualOld: String,
    newString: String,
    replaceAll: Boolean,
    lineSep: String
  ): Either[ToolError, String] =
    val updated =
      if replaceAll then content.replace(actualOld, newString)
      else
        content.replaceFirst(
          java.util.regex.Pattern.quote(actualOld),
          java.util.regex.Matcher.quoteReplacement(newString)
        )

    DiffUtil.writeFile(Paths.get(filePathStr), updated, lineSep)

    val (added, removed) = DiffUtil.lineStats(content, updated)
    val hunks = DiffUtil.makeUnifiedDiff(content, updated)
    val editResult = EditResult(
      filePath = filePathStr,
      addedLines = added,
      removedLines = removed,
      hunks = hunks,
      diffText = EditResult.renderHunks(hunks)
    )
    Right(editResult.toResultString)
  end performReplace

end EditTool
