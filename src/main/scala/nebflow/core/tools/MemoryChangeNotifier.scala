package nebflow.core.tools

import cats.effect.IO
import io.circe.Json
import io.circe.syntax.*
import nebflow.shared.MemoryPaths

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
    * 基准见 [[nebflow.shared.MemoryPaths.globalBases]]（并认运行时数据根与默认
    * home 根，而不是单一 `user.home`）：隔离实例（`--home`）下判得中，同时不被
    * 进程内不还原的 `setDataRoot` 打成漏判。
    */
  def isMemoryFile(filePath: String): Boolean =
    val normalized = MemoryPaths.normalizePath(filePath)
    val bases = MemoryPaths.globalBases
    bases.exists(b => normalized == s"$b/User.md") ||
    bases.exists(b => normalized == s"$b/Soul.md") ||
    normalized.endsWith("memory.md") && (
      bases.exists(b => normalized.startsWith(s"$b/agents/")) ||
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
