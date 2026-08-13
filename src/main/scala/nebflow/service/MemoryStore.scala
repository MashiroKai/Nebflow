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
 * Levels:
 *   - User:  ~/.nebflow/User.md                              (global, all eligible agents)
 *   - Agent: ~/.nebflow/agents/{name}/memory.md               (Nebula)
 *   - Agent: ~/.nebflow/teams/{team}/agents/{name}/memory.md   (Team agents)
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

  // --- Save (called from WS routes / Edit-Write tools, invalidates cache) ---

  private def saveFile(path: os.Path, content: String, invalidateCache: () => IO[Unit]): IO[Unit] =
    IO.blocking(os.write.over(path, content, createFolders = true)) *> invalidateCache()

  def saveUserMemory(content: String): IO[Unit] =
    saveFile(userMemoryPath, content, () => userCache.invalidate)

  def saveAgentMemory(agentName: String, content: String): IO[Unit] =
    saveFile(agentMemoryPath(agentName), content, () => getAgentCache(agentName).invalidate)

  def saveTeamAgentMemory(teamName: String, agentName: String, content: String): IO[Unit] =
    saveFile(teamAgentMemoryPath(teamName, agentName), content, () => getTeamAgentCache(teamName, agentName).invalidate)

  // --- Cache invalidation ---

  def invalidateUserCache(): Unit =
    userCache.invalidate.unsafeRunSync()

  def invalidateAgentCache(agentName: String): Unit =
    getAgentCache(agentName).invalidate.unsafeRunSync()

  def invalidateTeamAgentCache(teamName: String, agentName: String): Unit =
    getTeamAgentCache(teamName, agentName).invalidate.unsafeRunSync()

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
