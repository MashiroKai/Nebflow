package nebflow.service

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.syntax.*
import nebflow.core.PathUtil
import nebflow.shared.{MtimeCache, MtimeFileCache}

import java.util.concurrent.ConcurrentHashMap

import scala.jdk.CollectionConverters.*

/**
 * Two-level memory store backed by Markdown files.
 *
 * Levels (2026-08-31 裁定① — memory is Nebula-only):
 *   - User:  ~/.nebflow/User.md                      (global)
 *   - Agent: ~/.nebflow/agents/Nebula/memory.md       (Nebula)
 *
 * Team agent memory (~/.nebflow/teams/<team>/agents/<name>/memory.md) has been
 * removed; the read helpers below still exist so the memory modal degrades to
 * empty for team sessions, but nothing writes team memory anymore.
 *
 * Memory files are injected into the system prompt every turn by
 * ContextRefresher.buildMemoryBlock. Agents update them directly using Edit/Write.
 *
 * All reads use mtime-based caching.
 */
object MemoryStore:

  // --- Paths ---

  def userMemoryPath: os.Path = PathUtil.dataRoot / "User.md"

  def agentMemoryPath(agentName: String): os.Path =
    PathUtil.dataRoot / "agents" / agentName / "memory.md"

  def teamAgentMemoryPath(teamName: String, agentName: String): os.Path =
    PathUtil.dataRoot / "teams" / teamName / "agents" / agentName / "memory.md"

  // --- Mtime-cached file reads ---

  private def parseMemory(content: String): Option[String] =
    val trimmed = content.trim
    if trimmed.nonEmpty then Some(trimmed) else None

  private val userCache = MtimeCache.file[Option[String]](userMemoryPath, parseMemory)

  private val agentCaches = new ConcurrentHashMap[String, MtimeFileCache[Option[String]]]()

  private def getAgentCache(agentName: String): MtimeFileCache[Option[String]] =
    agentCaches.asScala.getOrElseUpdate(agentName, MtimeCache.file(agentMemoryPath(agentName), parseMemory))

  private val teamAgentCaches = new ConcurrentHashMap[String, MtimeFileCache[Option[String]]]()

  private def teamCacheKey(teamName: String, agentName: String): String = s"$teamName/$agentName"

  private def getTeamAgentCache(teamName: String, agentName: String): MtimeFileCache[Option[String]] =
    val key = teamCacheKey(teamName, agentName)
    teamAgentCaches.asScala.getOrElseUpdate(key, MtimeCache.file(teamAgentMemoryPath(teamName, agentName), parseMemory))

  // --- Load (mtime-cached) — injected into system prompts ---

  def loadUserMemory: Option[String] =
    userCache.get.unsafeRunSync().flatten

  def loadAgentMemory(agentName: String): Option[String] =
    getAgentCache(agentName).get.unsafeRunSync().flatten

  def loadTeamAgentMemory(teamName: String, agentName: String): Option[String] =
    getTeamAgentCache(teamName, agentName).get.unsafeRunSync().flatten

  // --- Save (落盘单点：过闸 → 写 → 失效缓存) ---
  //
  // M4（2026-09-13 作者立项）：预算闸 + 写前快照闸**下沉到本单点**（[[MemoryWriteGate]]），
  // 故「过闸」是落盘的必要条件而不取决于调用方。今天唯一的生产调用方 = WS `saveMemory`
  // 旁路（`WebSocketRoutes`）。**不覆盖** Write / Edit / Bash 直写路径（边界与理由见
  // MemoryWriteGate 头注）——本单点只管经它落盘的写入。
  // 拒绝/失败走 IO 错误通道（`MemoryWriteGate.Rejected`），**零写入**且必须由调用方暴露。

  private def saveFile(path: os.Path, target: String, content: String, invalidateCache: () => IO[Unit]): IO[Unit] =
    MemoryWriteGate.guard(target, path, content) *>
      IO.blocking(os.write.over(path, content, createFolders = true)) *> invalidateCache()

  def saveUserMemory(content: String): IO[Unit] =
    saveFile(userMemoryPath, "user", content, () => userCache.invalidate)

  def saveAgentMemory(agentName: String, content: String): IO[Unit] =
    saveFile(agentMemoryPath(agentName), "agent", content, () => getAgentCache(agentName).invalidate)

  // --- Cache invalidation ---

  def invalidateUserCache(): Unit =
    userCache.invalidate.unsafeRunSync()

  def invalidateAgentCache(agentName: String): Unit =
    getAgentCache(agentName).invalidate.unsafeRunSync()

  // --- Preview (first non-heading, non-empty line, max 80 chars) ---

  def preview(path: os.Path): Option[String] =
    if !os.exists(path) then None
    else
      val content = os.read(path).trim
      if content.isEmpty then None
      else
        Some(
          content.linesIterator
            .dropWhile(l => l.trim.isEmpty || l.trim.startsWith("#"))
            .find(_.trim.nonEmpty)
            .map(_.trim.take(80))
            .getOrElse("(empty)")
        )

  def userPreview: Option[String] = preview(userMemoryPath)
  def agentPreview(agentName: String): Option[String] = preview(agentMemoryPath(agentName))

  def teamAgentPreview(teamName: String, agentName: String): Option[String] =
    preview(teamAgentMemoryPath(teamName, agentName))

  // --- Exists check ---

  def fileExists(path: os.Path): Boolean =
    os.exists(path) && os.stat(path).size > 0

  def userExists: Boolean = fileExists(userMemoryPath)
  def agentExists(agentName: String): Boolean = fileExists(agentMemoryPath(agentName))

  def teamAgentExists(teamName: String, agentName: String): Boolean =
    fileExists(teamAgentMemoryPath(teamName, agentName))

end MemoryStore
