package nebflow.core.tools

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import nebflow.shared.*

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

/**
 * Tracks files read with `live: true` and patches their tool_result content
 * in the conversation history when the file changes on disk.
 *
 * Each entry stores the original mtime (from when the file was first read).
 * On each `patchMessages` call, entries whose current mtime exceeds the original
 * are re-read and their tool_result blocks updated in-place.
 *
 * This means the LLM always sees the latest file content without any extra
 * system-reminder noise — the historical tool_result simply reflects the
 * current state of the file.
 */
case class LiveFileEntry(path: String, toolCallId: String, originalMtime: Long)

class LiveFileTracker private (ref: Ref[IO, Map[String, LiveFileEntry]]):

  /**
   * Register a file for live tracking. Called by ReadTool when `live: true`.
   * When forceLive=true, originalMtime is set to 0 so the patcher always
   * re-reads the file (used for memory auto-read files).
   */
  def register(path: String, toolCallId: String, forceLive: Boolean = false): IO[Unit] =
    val mtime = if forceLive then 0L else LiveFileTracker.currentMtime(path)
    ref.update(_ + (toolCallId -> LiveFileEntry(path, toolCallId, mtime)))

  /**
   * Check all tracked live files. If any changed since original read,
   * re-read and patch their tool_result content in the message list.
   * Returns the (possibly patched) messages.
   */
  def patchMessages(messages: List[Message]): IO[List[Message]] =
    ref.get.flatMap { entries =>
      if entries.isEmpty then IO.pure(messages)
      else
        val changed = entries.values.filter(e => LiveFileTracker.currentMtime(e.path) > e.originalMtime).toList
        if changed.isEmpty then IO.pure(messages)
        else
          changed
            .traverse { entry =>
              IO.blocking {
                val path = Paths.get(entry.path)
                val content =
                  if Files.exists(path) && !Files.isDirectory(path) then
                    new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
                  else s"File does not exist: ${entry.path}"
                entry.toolCallId -> LiveFileTracker.formatWithLineNumbers(content)
              }
            }
            .map(_.toMap)
            .map { patches =>
              LiveFileTracker.applyPatches(messages, patches)
            }
        end if
    }

  def clear(): IO[Unit] = ref.set(Map.empty)

end LiveFileTracker

object LiveFileTracker:

  def create: IO[LiveFileTracker] =
    Ref.of[IO, Map[String, LiveFileEntry]](Map.empty).map(new LiveFileTracker(_))

  // --- helpers ---

  private def currentMtime(path: String): Long =
    val p = Paths.get(path)
    if Files.exists(p) then
      try Files.getLastModifiedTime(p).toMillis
      catch case _: Exception => 0L
    else 0L

  /** Format content the same way ReadTool does: `cat -n` style with line numbers. */
  private def formatWithLineNumbers(content: String): String =
    if content.isEmpty then ""
    else
      content
        .split("\\r?\\n")
        .zipWithIndex
        .map { case (line, i) =>
          s"${i + 1}\t$line"
        }
        .mkString("\n")

  /** Replace tool_result content for matching toolUseIds in the message list. */
  private def applyPatches(messages: List[Message], patches: Map[String, String]): List[Message] =
    messages.map { msg =>
      msg.content match
        case Right(blocks) =>
          val hasPatch = blocks.exists {
            case tr: ContentBlock.ToolResult => patches.contains(tr.toolUseId)
            case _ => false
          }
          if !hasPatch then msg
          else
            val newBlocks = blocks.map {
              case tr: ContentBlock.ToolResult if patches.contains(tr.toolUseId) =>
                tr.copy(content = patches(tr.toolUseId))
              case other => other
            }
            msg.copy(content = Right(newBlocks))
        case _ => msg
    }

end LiveFileTracker
