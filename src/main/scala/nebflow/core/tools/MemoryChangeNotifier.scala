package nebflow.core.tools

import cats.effect.IO
import io.circe.Json
import io.circe.syntax.*
import nebflow.shared.PathUtil

/**
 * Detects when agent modifies memory files via Write/Edit and pushes a
 * `memoryChanged` WebSocket event so the frontend can refresh its memory panel.
 *
 * Memory files (personal-agent 批 2026-10-04): `User.md`, the new root-layer
 * `Soul.md`, and the legacy `agents/<root>/memory.md` (still monitored while the
 * dual-read transition is in force).
 *
 * Team agent memory was removed; the team path pattern below is kept only
 * to remain a no-op guard — nothing writes those files anymore.
 */
object MemoryChangeNotifier:

  /** Check if a file path is a memory file.
    *
    * 基准 = `PathUtil.dataRoot`（不是 `user.home`）：隔离实例（`--home` /
    * `setDataRoot`）下同样判得中——原实现的 home 基准在隔离实例里会让记忆面板
    * 不刷新（静默失联）。
    */
  def isMemoryFile(filePath: String): Boolean =
    val root = PathUtil.dataRoot.toString.replace("\\", "/").replaceAll("/+$", "")
    val normalized = filePath.replace("\\", "/")
    normalized == s"$root/User.md" ||
    normalized == s"$root/Soul.md" ||
    normalized.endsWith("memory.md") && (
      normalized.contains(s"$root/agents/") ||
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
