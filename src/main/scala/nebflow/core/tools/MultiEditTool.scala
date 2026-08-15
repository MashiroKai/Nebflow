package nebflow.core.tools

import cats.effect.IO
import cats.syntax.all.*
import io.circe.{Json, JsonObject}
import io.circe.syntax.*
import nebflow.core.NebflowLogger

import java.nio.file.{Files, Path, Paths}

private val multiEditLogger = NebflowLogger.forName("nebflow.multiedit")

/** A single edit parsed from the MultiEdit edits array. */
private[tools] case class MultiEditOp(oldString: String, newString: String, replaceAll: Boolean)

/**
 * Atomic multi-edit tool for a single file.
 *
 * Edits are applied sequentially inside one write lock, one read and one
 * write — edits[i] is matched against the buffer state after edits[0..i-1]
 * have been applied. If any edit fails, nothing is written (all-or-nothing).
 *
 * Matching semantics are identical to Edit (via EditTool.applyEditToBuffer):
 * 4-level fuzzy matching, uniqueness enforcement, curly-quote preservation.
 */
object MultiEditTool extends Tool:
  val name = "MultiEdit"

  val description = """Applies multiple edits to a single file in one atomic operation.

Semantics:
- Edits are applied sequentially: edits[i] is matched against the content AFTER edits[0..i-1] have been applied.
- Atomic: if any edit fails, NO edits are written — the file stays untouched. The error names the failing index (e.g. edits[2]) and the closest matching location; fix that one edit and retry the whole call.

Usage:
- Use for 2+ changes to the SAME file — one call replaces N serial Edit round-trips.
- For a single change, use Edit instead. For new files, use Write (MultiEdit cannot create files).
- Each edit follows Edit's matching rules: old_string must match exactly, or via quote/whitespace-insensitive fallback; it must be unique unless replace_all is true.
- Max 50 edits per call; split larger refactors into multiple calls.

Example edits[0]: {"old_string": "val a = 1", "new_string": "val a = 2"}"""

  private val MaxEdits = 50
  private val MaxEditFileSize = 1L * 1024 * 1024 * 1024 // 1 GiB, same as Edit

  val inputSchema = JsonObject.fromIterable(
    List(
      "type" -> "object".asJson,
      "properties" -> Json.obj(
        "file_path" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "The absolute path to the file to modify".asJson
        ),
        "edits" -> Json.obj(
          "type" -> "array".asJson,
          "minItems" -> 1.asJson,
          "maxItems" -> MaxEdits.asJson,
          "description" -> "Ordered list of edits, applied sequentially and atomically".asJson,
          "items" -> Json.obj(
            "type" -> "object".asJson,
            "properties" -> Json.obj(
              "old_string" -> Json.obj(
                "type" -> "string".asJson,
                "description" -> "The text to replace (non-empty; must be unique unless replace_all)".asJson
              ),
              "new_string" -> Json.obj(
                "type" -> "string".asJson,
                "description" -> "The replacement text".asJson
              ),
              "replace_all" -> Json.obj(
                "type" -> "boolean".asJson,
                "description" -> "Replace all occurrences of old_string in this edit (default false)".asJson
              )
            ),
            "required" -> Json.arr("old_string".asJson, "new_string".asJson)
          )
        )
      ),
      "required" -> Json.arr("file_path".asJson, "edits".asJson)
    )
  )

  def summarize(input: JsonObject): String =
    val path = input("file_path").flatMap(_.asString).getOrElse("")
    val short = path.split("/").lastOption.getOrElse(path)
    val n = input("edits").flatMap(_.asArray).map(_.size).getOrElse(0)
    s"""MultiEdit($short, $n edit${if n == 1 then "" else "s"})\n  ("$path")"""

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
    else result.split("\\n").headOption.getOrElse(result)

  // ---------------------------------------------------------------------------
  // Input parsing + lock-free validation
  // ---------------------------------------------------------------------------

  private def parseEdits(input: JsonObject): Either[ToolError, List[MultiEditOp]] =
    input("edits") match
      case None =>
        Left(ToolError("edits is required (array of {old_string, new_string, replace_all?})."))
      case Some(editsJson) =>
        editsJson.asArray match
          case None => Left(ToolError(s"edits must be an array, got: ${typeOf(editsJson)}"))
          case Some(arr) =>
            if arr.isEmpty then Left(ToolError("edits must contain at least 1 edit."))
            else if arr.length > MaxEdits then
              Left(ToolError(s"edits exceeds the maximum of $MaxEdits items (got ${arr.length}). Split into multiple MultiEdit calls."))
            else
              arr.zipWithIndex.map { (e, i) =>
                e.asObject match
                  case None => Left(ToolError(s"edits[$i] must be an object with old_string/new_string."))
                  case Some(obj) =>
                    val oldS = obj("old_string").flatMap(_.asString)
                    val newS = obj("new_string").flatMap(_.asString)
                    val all = obj("replace_all").flatMap(_.asBoolean).getOrElse(false)
                    (oldS, newS) match
                      case (None, _) =>
                        Left(ToolError(s"edits[$i]: old_string is required and must be a string."))
                      case (_, None) =>
                        Left(ToolError(s"edits[$i]: new_string is required and must be a string."))
                      case (Some(o), Some(n)) =>
                        if o.isEmpty then
                          Left(ToolError(s"edits[$i]: old_string must be non-empty — MultiEdit cannot create files; use Write."))
                        else if o == n then
                          Left(ToolError(s"edits[$i]: old_string and new_string are identical — remove this edit."))
                        else Right(MultiEditOp(o, n, all))
              }.sequence.map(_.toList)
  end parseEdits

  private def typeOf(j: Json): String =
    if j.isNull then "null"
    else if j.isString then "string"
    else if j.isArray then "array"
    else if j.isObject then "object"
    else if j.isBoolean then "boolean"
    else if j.isNumber then "number"
    else "unknown"

  // ---------------------------------------------------------------------------
  // Main entry point
  // ---------------------------------------------------------------------------

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    val filePathStr = input("file_path").flatMap(_.asString).getOrElse("")
    if !nebflow.core.PathUtil.isAbsolute(filePathStr) then
      IO.pure(Left(ToolError(s"Path must be absolute, got: $filePathStr")))
    else
      val filePath = Paths.get(filePathStr)
      if filePath.toString.endsWith(".ipynb") then
        IO.pure(Left(ToolError("File is a Jupyter Notebook. Use the NotebookEdit tool to edit this file.")))
      else
        parseEdits(input) match
          case Left(err) => IO.pure(Left(err))
          case Right(edits) =>
            // Snapshot file before editing (if it exists, with agent identity)
            val snapshot = ctx.fileHistory.traverse_(_.snapshot(filePath, ctx.mailboxAddress))
            val editIO = (snapshot *> IO.blocking(doMultiEdit(filePath, edits)))
              .handleErrorWith { e =>
                val msg = Option(e.getMessage).getOrElse(e.getClass.getSimpleName)
                multiEditLogger.warn(s"Error multi-editing file $filePath: ${e.getClass.getSimpleName}: $msg") *>
                  IO.pure(Left(ToolError(s"Error editing file: $msg")))
              }
            val lockedEdit = ctx.fileLockManager match
              case Some(lm) => lm.withWriteLock(filePath)(editIO)
              case None => editIO
            lockedEdit.flatMap {
              case Right(result) =>
                val record = ctx.readTracker.traverse_(_.recordRead(filePath)) *>
                  ctx.fileChangeTracker.traverse_(_.recordAgentModification(filePath.toString)) *>
                  MemoryChangeNotifier.notifyIfMemoryFile(filePath.toString, ctx)
                record.as(Right(result))
              case left => IO.pure(left)
            }
  end call

  // ---------------------------------------------------------------------------
  // Core logic (runs inside write lock): single read, sequential apply, single write
  // ---------------------------------------------------------------------------

  private def doMultiEdit(filePath: Path, edits: List[MultiEditOp]): Either[ToolError, String] =
    if !Files.exists(filePath) then
      Left(ToolError(s"File does not exist: $filePath. MultiEdit cannot create files — use Write."))
    else
      val size = Files.size(filePath)
      if size > MaxEditFileSize then
        Left(ToolError(s"File too large to edit (${formatSize(size)}). Maximum is ${formatSize(MaxEditFileSize)}."))
      else
        val rawContent = DiffUtil.readFile(filePath)
        val lineSep = DiffUtil.detectLineSep(rawContent)
        val content = rawContent.replace("\r\n", "\n")
        val mtime = Files.getLastModifiedTime(filePath)

        applyAll(content, edits, 0) match
          case Left(err) => Left(err)
          case Right(updated) =>
            if updated == content then
              Left(ToolError("No changes were produced — every edit matched text identical to its replacement."))
            else
              if externallyModified(filePath, content, mtime) then
                Left(ToolError("File was modified externally. Please re-read and retry."))
              else
                DiffUtil.writeFile(filePath, updated, lineSep)
                val (added, removed) = DiffUtil.lineStats(content, updated)
                val hunks = DiffUtil.makeUnifiedDiff(content, updated)
                val editResult = EditResult(
                  filePath = filePath.toString,
                  addedLines = added,
                  removedLines = removed,
                  hunks = hunks,
                  diffText = EditResult.renderHunks(hunks)
                )
                Right(editResult.toResultString)
  end doMultiEdit

  /**
   * External-modification guard (same semantics as Edit): a file counts as
   * externally modified only when the mtime changed AND the current content
   * no longer equals the content we based our edits on.
   */
  private[tools] def externallyModified(filePath: Path, content: String, mtime: java.nio.file.attribute.FileTime): Boolean =
    val currentMtime = Files.getLastModifiedTime(filePath)
    currentMtime != mtime && {
      DiffUtil.readFile(filePath).replace("\r\n", "\n") != content
    }

  /** Sequential fold with fail-fast; returns the final buffer on success. */
  private def applyAll(buffer: String, edits: List[MultiEditOp], idx: Int): Either[ToolError, String] =
    edits match
      case Nil => Right(buffer)
      case op :: rest =>
        EditTool.applyEditToBuffer(buffer, op.oldString, op.newString, op.replaceAll) match
          case Right(updated) => applyAll(updated, rest, idx + 1)
          case Left(EditFailure.OldStringNotFound) =>
            Left(notFoundError(idx, buffer, op.oldString))
          case Left(EditFailure.NotUnique(matchCount)) =>
            Left(
              ToolError(
                s"ERROR: edits[$idx] failed: old_string matches $matchCount locations. " +
                  s"Provide more context to make it unique, or set replace_all: true on this edit. " +
                  "No changes were written — the file is unmodified (atomic)."
              )
            )
          case Left(EditFailure.EmptyOldString) =>
            Left(
              ToolError(
                s"ERROR: edits[$idx] failed: old_string must be non-empty — MultiEdit cannot create files; use Write. " +
                  "No changes were written — the file is unmodified (atomic)."
              )
            )
  end applyAll

  /**
   * Self-healing not-found error: failing index, atomicity guarantee, closest
   * candidate line with similarity, and a concrete Read(offset, limit) next step.
   */
  private def notFoundError(idx: Int, buffer: String, oldString: String): ToolError =
    val totalLines = DiffUtil.splitLines(buffer).length
    val sb = new StringBuilder
    sb.append(s"ERROR: edits[$idx] failed: old_string not found in the current buffer")
    if idx > 0 then sb.append(s" (after applying edits[0..${idx - 1}])")
    sb.append(".\n")
    sb.append("No changes were written — the file is unmodified (atomic).\n")
    StringMatcher.closestLineHint(buffer, oldString) match
      case Some(hint) =>
        sb.append(s"Closest candidate at line ${hint.line} (similarity ${hint.similarityPct}%):\n")
        sb.append(s"  | ${hint.line}  ${hint.excerpt.take(120)}\n")
        val offset = math.max(1, hint.line - 4)
        val limit = math.max(1, math.min(totalLines, hint.line + 16) - offset + 1)
        sb.append(s"File has $totalLines lines. Suggest Read(offset=$offset, limit=$limit), fix old_string, retry.")
      case None =>
        sb.append(s"File has $totalLines lines. Suggest re-Reading the file and retrying with the exact content.")
    ToolError(sb.toString)
  end notFoundError

  private def formatSize(bytes: Long): String =
    if bytes >= 1024 * 1024 * 1024 then f"${bytes.toDouble / (1024 * 1024 * 1024)}%.1f GiB"
    else if bytes >= 1024 * 1024 then f"${bytes.toDouble / (1024 * 1024)}%.1f MiB"
    else if bytes >= 1024 then f"${bytes.toDouble / 1024}%.1f KiB"
    else s"$bytes B"

end MultiEditTool
