package nebflow.service

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import nebflow.shared.{MtimeCache, MtimeFileCache}

/**
 * Three-level memory store backed by Markdown files.
 *
 * Levels:
 *   - User Preference:  ~/.nebflow/NEBFLOW.md           (global, all agents)
 *   - Agent:            ~/.nebflow/agents/{name}/memory.md  (per agent)
 *   - Session:          ~/.nebflow/sessions/{sid}.memory.md (per session)
 *
 * All reads use mtime-based caching: files are only re-read from disk
 * when their modification time changes. Edits to memory files take
 * effect on the next LLM call without needing a new session.
 */
object MemoryStore:

  // --- Paths ---

  def userMemoryPath: os.Path = os.home / ".nebflow" / "NEBFLOW.md"

  def agentMemoryPath(agentName: String): os.Path =
    os.home / ".nebflow" / "agents" / agentName / "memory.md"

  def sessionMemoryPath(sessionId: String): os.Path =
    os.home / ".nebflow" / "sessions" / s"$sessionId.memory.md"

  // --- Mtime-cached file reads ---

  private def parseMemory(content: String): Option[String] =
    val trimmed = content.trim
    if trimmed.nonEmpty then Some(trimmed) else None

  private val userCache = MtimeCache.file[Option[String]](userMemoryPath, parseMemory)

  private val agentCaches = scala.collection.mutable.Map[String, MtimeFileCache[Option[String]]]()

  private val sessionCaches = scala.collection.mutable.Map[String, MtimeFileCache[Option[String]]]()

  private def getAgentCache(agentName: String): MtimeFileCache[Option[String]] =
    agentCaches.getOrElseUpdate(agentName, MtimeCache.file(agentMemoryPath(agentName), parseMemory))

  private def getSessionCache(sessionId: String): MtimeFileCache[Option[String]] =
    sessionCaches.getOrElseUpdate(sessionId, MtimeCache.file(sessionMemoryPath(sessionId), parseMemory))

  // --- Load (mtime-cached — called during system prompt build) ---

  def loadUserMemory: Option[String] =
    userCache.get.unsafeRunSync().flatten

  def loadAgentMemory(agentName: String): Option[String] =
    getAgentCache(agentName).get.unsafeRunSync().flatten

  def loadSessionMemory(sessionId: String): Option[String] =
    getSessionCache(sessionId).get.unsafeRunSync().flatten

  // --- Save (async — called from WS routes / tools, invalidates cache) ---

  private def saveFile(path: os.Path, content: String, invalidateCache: () => IO[Unit]): IO[Unit] =
    IO.blocking(os.write.over(path, content, createFolders = true)) *> invalidateCache()

  def saveUserMemory(content: String): IO[Unit] =
    saveFile(userMemoryPath, content, () => userCache.invalidate)

  def saveAgentMemory(agentName: String, content: String): IO[Unit] =
    saveFile(agentMemoryPath(agentName), content, () => getAgentCache(agentName).invalidate)

  def saveSessionMemory(sessionId: String, content: String): IO[Unit] =
    saveFile(sessionMemoryPath(sessionId), content, () => getSessionCache(sessionId).invalidate)

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
  def sessionPreview(sessionId: String): Option[String] = preview(sessionMemoryPath(sessionId))

  // --- Exists check ---

  def fileExists(path: os.Path): Boolean =
    os.exists(path) && os.stat(path).size > 0

  def userExists: Boolean = fileExists(userMemoryPath)
  def agentExists(agentName: String): Boolean = fileExists(agentMemoryPath(agentName))
  def sessionExists(sessionId: String): Boolean = fileExists(sessionMemoryPath(sessionId))

end MemoryStore
