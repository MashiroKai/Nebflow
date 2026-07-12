package nebflow.agent

import cats.effect.IO
import cats.syntax.all.*
import io.circe.JsonObject
import io.circe.syntax.*
import nebflow.core.tools.LiveFileTracker
import nebflow.service.MemoryStore
import nebflow.shared.*

/**
 * Constructs synthetic Read(live=true) tool_use/tool_result messages for
 * the four memory levels: User, Agent, Folder, Session.
 *
 * These messages are prepended to the conversation on every LLM call so the
 * agent always has its memory in context. The LiveFileTracker keeps the
 * tool_result content fresh — when a memory file changes on disk, the
 * tracker patches the historical tool_result in-place.
 *
 * Fixed toolCallIds (mem-*) allow the tracker to locate and update the
 * tool_result blocks across turns.
 */
object MemoryAutoRead:

  val MemUser = "mem-user"
  val MemAgent = "mem-agent"
  val MemFolder = "mem-folder"
  val MemSession = "mem-session"

  /**
   * Build synthetic (assistant tool_use, user tool_result) messages reading
   * all available memory files. Returns Nil if no memory files exist.
   */
  def buildMessages(
    agentName: String,
    folderId: Option[String],
    sessionId: Option[String]
  ): List[Message] =
    val files = collectFiles(agentName, folderId, sessionId)
    if files.isEmpty then Nil
    else
      val reads = files.map { (id, path) =>
        (id, path, readFileContent(path))
      }
      val toolUses = reads.map { (id, path, _) =>
        ContentBlock.ToolUse(
          id,
          "Read",
          JsonObject(
            "file_path" -> path.asJson,
            "live" -> true.asJson
          )
        )
      }
      val toolResults = reads.map { (id, _, content) =>
        ContentBlock.ToolResult(id, content, None)
      }
      List(
        Message(MessageRole.Assistant, Right(toolUses)),
        Message(MessageRole.User, Right(toolResults))
      )

    end if

  end buildMessages

  /**
   * Register all memory files in the LiveFileTracker with forceLive=true
   * (originalMtime=0) so they are always re-read and kept fresh.
   */
  def register(
    tracker: LiveFileTracker,
    agentName: String,
    folderId: Option[String],
    sessionId: Option[String]
  ): IO[Unit] =
    val files = collectFiles(agentName, folderId, sessionId)
    files.traverse_ { (id, path) => tracker.register(path, id, forceLive = true) }

  // --- helpers ---

  private def collectFiles(
    agentName: String,
    folderId: Option[String],
    sessionId: Option[String]
  ): List[(String, String)] =
    List(
      Some((MemUser, MemoryStore.userMemoryPath.toString)),
      Some((MemAgent, MemoryStore.agentMemoryPath(agentName).toString)),
      folderId.map(fid => (MemFolder, MemoryStore.folderMemoryPath(fid).toString)),
      sessionId.map(sid => (MemSession, MemoryStore.sessionMemoryPath(sid).toString))
    ).flatten.filter { (_, path) =>
      os.exists(os.Path(path))
    }

  /** Read file and format as cat -n (same as ReadTool output). */
  private def readFileContent(path: String): String =
    try
      val raw = os.read(os.Path(path))
      if raw.isEmpty then ""
      else
        raw
          .split("\\r?\\n")
          .zipWithIndex
          .map { case (line, i) =>
            s"${i + 1}\t$line"
          }
          .mkString("\n")
    catch case _: Exception => s"Error reading: $path"

end MemoryAutoRead
