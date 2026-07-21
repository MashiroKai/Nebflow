package nebflow.service

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.syntax.*
import nebflow.core.PathUtil
import nebflow.shared.{MtimeCache, MtimeFileCache}

import java.util.concurrent.ConcurrentHashMap

import scala.jdk.CollectionConverters.*

/**
 * Four-level memory store backed by Markdown files.
 *
 * Levels:
 *   - User:    ~/.nebflow/NEBFLOW.md                    (global, all agents)
 *   - Agent:   ~/.nebflow/agents/{name}/memory.md       (per agent)
 *   - Folder:  ~/.nebflow/folders/{fid}.memory.md       (per folder)
 *   - Session: ~/.nebflow/sessions/{sid}.memory.md      (per session)
 *
 * Memory files are injected into the system prompt every turn by
 * ContextRefresher.buildMemoryBlock. Agents update them directly using Edit/Write.
 *
 * All reads use mtime-based caching.
 */
object MemoryStore:

  // --- Paths ---

  def userMemoryPath: os.Path = PathUtil.dataRoot / "NEBFLOW.md"

  def agentMemoryPath(agentName: String): os.Path =
    PathUtil.dataRoot / "agents" / agentName / "memory.md"

  def folderMemoryPath(folderId: String): os.Path =
    PathUtil.dataRoot / "folders" / s"$folderId.memory.md"

  def sessionMemoryPath(sessionId: String): os.Path =
    PathUtil.dataRoot / "sessions" / s"$sessionId.memory.md"

  /** List all folder memory files that exist on disk. */
  def allFolderMemoryPaths: Seq[os.Path] =
    val dir = PathUtil.dataRoot / "folders"
    if !os.exists(dir) then Seq.empty
    else os.list(dir).filter(_.last.endsWith(".memory.md")).toSeq

  // --- Mtime-cached file reads ---

  private def parseMemory(content: String): Option[String] =
    val trimmed = content.trim
    if trimmed.nonEmpty then Some(trimmed) else None

  private val userCache = MtimeCache.file[Option[String]](userMemoryPath, parseMemory)

  private val agentCaches = new ConcurrentHashMap[String, MtimeFileCache[Option[String]]]()

  private val folderCaches = new ConcurrentHashMap[String, MtimeFileCache[Option[String]]]()

  private val sessionCaches = new ConcurrentHashMap[String, MtimeFileCache[Option[String]]]()

  private def getAgentCache(agentName: String): MtimeFileCache[Option[String]] =
    agentCaches.asScala.getOrElseUpdate(agentName, MtimeCache.file(agentMemoryPath(agentName), parseMemory))

  private def getFolderCache(folderId: String): MtimeFileCache[Option[String]] =
    folderCaches.asScala.getOrElseUpdate(folderId, MtimeCache.file(folderMemoryPath(folderId), parseMemory))

  private def getSessionCache(sessionId: String): MtimeFileCache[Option[String]] =
    sessionCaches.asScala.getOrElseUpdate(sessionId, MtimeCache.file(sessionMemoryPath(sessionId), parseMemory))

  // --- Load (mtime-cached) — injected into system prompts ---

  def loadUserMemory: Option[String] =
    userCache.get.unsafeRunSync().flatten

  def loadAgentMemory(agentName: String): Option[String] =
    getAgentCache(agentName).get.unsafeRunSync().flatten

  def loadFolderMemory(folderId: String): Option[String] =
    getFolderCache(folderId).get.unsafeRunSync().flatten

  def loadSessionMemory(sessionId: String): Option[String] =
    getSessionCache(sessionId).get.unsafeRunSync().flatten

  // --- Save (called from WS routes / Edit-Write tools, invalidates cache) ---

  private def saveFile(path: os.Path, content: String, invalidateCache: () => IO[Unit]): IO[Unit] =
    IO.blocking(os.write.over(path, content, createFolders = true)) *> invalidateCache()

  def saveUserMemory(content: String): IO[Unit] =
    saveFile(userMemoryPath, content, () => userCache.invalidate)

  def saveAgentMemory(agentName: String, content: String): IO[Unit] =
    saveFile(agentMemoryPath(agentName), content, () => getAgentCache(agentName).invalidate)

  def saveFolderMemory(folderId: String, content: String): IO[Unit] =
    saveFile(folderMemoryPath(folderId), content, () => getFolderCache(folderId).invalidate)

  def saveSessionMemory(sessionId: String, content: String): IO[Unit] =
    saveFile(sessionMemoryPath(sessionId), content, () => getSessionCache(sessionId).invalidate)

  // --- Cache invalidation ---

  def invalidateUserCache(): Unit =
    userCache.invalidate.unsafeRunSync()

  def invalidateAgentCache(agentName: String): Unit =
    getAgentCache(agentName).invalidate.unsafeRunSync()

  def invalidateFolderCache(folderId: String): Unit =
    getFolderCache(folderId).invalidate.unsafeRunSync()

  def invalidateSessionCache(sessionId: String): Unit =
    getSessionCache(sessionId).invalidate.unsafeRunSync()

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
  def folderPreview(folderId: String): Option[String] = preview(folderMemoryPath(folderId))

  // --- Exists check ---

  def fileExists(path: os.Path): Boolean =
    os.exists(path) && os.stat(path).size > 0

  def userExists: Boolean = fileExists(userMemoryPath)
  def agentExists(agentName: String): Boolean = fileExists(agentMemoryPath(agentName))
  def folderExists(folderId: String): Boolean = fileExists(folderMemoryPath(folderId))

end MemoryStore
