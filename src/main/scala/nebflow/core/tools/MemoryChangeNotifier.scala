package nebflow.core.tools

import cats.effect.IO
import io.circe.Json
import io.circe.syntax.*

/**
 * Detects when agent modifies memory files via Write/Edit and pushes a
 * `memoryChanged` WebSocket event so the frontend can refresh its memory panel.
 *
 * Memory files are (2026-08-31 裁定① — Nebula-only):
 *   - ~/.nebflow/User.md                                    (User)
 *   - ~/.nebflow/agents/Nebula/memory.md                     (Nebula)
 *
 * Team agent memory was removed; the team path pattern below is kept only
 * to remain a no-op guard — nothing writes those files anymore.
 */
object MemoryChangeNotifier:

  /** Check if a file path is a memory file. */
  def isMemoryFile(filePath: String): Boolean =
    val home = sys.props("user.home")
    val normalized = filePath.replace("\\", "/")
    normalized.startsWith(s"$home/.nebflow/User.md") ||
    normalized.endsWith("memory.md") && (
      normalized.contains(s"$home/.nebflow/agents/") ||
        normalized.contains(s"/.nebflow/teams/") && normalized.contains("/agents/")
    )

  /**
   * If the path is a memory file, push `memoryChanged` via wsSend.
   * No-op if not a memory file or no wsSend available.
   */
  def notifyIfMemoryFile(filePath: String, ctx: ToolContext): IO[Unit] =
    if isMemoryFile(filePath) then
      ctx.wsSend match
        case Some(send) =>
          send(
            Json.obj(
              "type" -> "memoryChanged".asJson,
              "path" -> filePath.asJson
            )
          )
        case None => IO.unit
    else IO.unit

end MemoryChangeNotifier
