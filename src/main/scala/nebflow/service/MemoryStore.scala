package nebflow.service

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.syntax.*
import nebflow.shared.{MtimeCache, MtimeFileCache}

import java.util.concurrent.ConcurrentHashMap

import scala.jdk.CollectionConverters.*

/**
 * Three-level memory store backed by Markdown files.
 *
 * Levels:
 *   - User:    ~/.nebflow/NEBFLOW.md                    (global, all agents)
 *   - Agent:   ~/.nebflow/agents/{name}/memory.md       (per agent)
 *   - Folder:  ~/.nebflow/folders/{fid}.memory.md       (per folder)
 *
 * Detail files: ~/.nebflow/memory/{hash}.md
 *   - When an entry has detail content, MemoryAgent stores it in a separate file
 *     named by the SHA-256 hash of the entry content (first 12 hex chars).
 *   - The index entry carries a →hash reference so agents can find it.
 *
 * Write flow:
 *   WriteMemoryTool → staging area (~/.nebflow/memory-staging.jsonl)
 *   Dream cycle → MemoryAgent processes staging → final memory files
 *
 * All reads use mtime-based caching.
 */
object MemoryStore:

  // --- Paths ---

  def userMemoryPath: os.Path = os.home / ".nebflow" / "NEBFLOW.md"

  def agentMemoryPath(agentName: String): os.Path =
    os.home / ".nebflow" / "agents" / agentName / "memory.md"

  def folderMemoryPath(folderId: String): os.Path =
    os.home / ".nebflow" / "folders" / s"$folderId.memory.md"

  /** Directory for detail files. */
  def detailDir: os.Path = os.home / ".nebflow" / "memory"

  /** Path for a detail file given its hash. */
  def detailPath(hash: String): os.Path = detailDir / s"$hash.md"

  /** Staging area path. */
  def stagingPath: os.Path = os.home / ".nebflow" / "memory-staging.jsonl"

  /** List all folder memory files that exist on disk. */
  def allFolderMemoryPaths: Seq[os.Path] =
    val dir = os.home / ".nebflow" / "folders"
    if !os.exists(dir) then Seq.empty
    else os.list(dir).filter(_.last.endsWith(".memory.md")).toSeq

  // --- Hash utility ---

  /** Compute a 12-char hex hash from content. */
  def contentHash(content: String): String =
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    digest.update(content.getBytes("UTF-8"))
    digest.digest().take(6).map(b => String.format("%02x", b)).mkString

  // --- Staging area ---

  /** Append an entry to the staging area (called by WriteMemoryTool). */
  def appendStaging(
    scope: String,
    content: String,
    detail: Option[String],
    source: String,
    folderId: Option[String]
  ): IO[Unit] =
    IO.blocking {
      val hash = detail.filter(_.trim.nonEmpty).map(_ => contentHash(content))
      val entry = Json.obj(
        "ts" -> java.time.LocalDateTime.now().toString.asJson,
        "scope" -> scope.asJson,
        "content" -> content.asJson,
        "detail" -> detail.asJson,
        "hash" -> hash.asJson,
        "source" -> source.asJson,
        "folder" -> folderId.asJson
      )
      val line = entry.noSpaces + "\n"
      os.write.append(stagingPath, line, createFolders = true)
    }

  /** Load all staging entries. Returns empty string if no staging file. */
  def loadStaging: IO[String] = IO.blocking {
    if !os.exists(stagingPath) then ""
    else os.read(stagingPath).trim
  }

  /** Clear the staging area after processing. */
  def clearStaging: IO[Unit] = IO.blocking {
    if os.exists(stagingPath) then os.remove(stagingPath)
  }

  // --- Mtime-cached file reads ---

  private def parseMemory(content: String): Option[String] =
    val trimmed = content.trim
    if trimmed.nonEmpty then Some(trimmed) else None

  private val userCache = MtimeCache.file[Option[String]](userMemoryPath, parseMemory)

  private val agentCaches = new ConcurrentHashMap[String, MtimeFileCache[Option[String]]]()

  private val folderCaches = new ConcurrentHashMap[String, MtimeFileCache[Option[String]]]()

  private def getAgentCache(agentName: String): MtimeFileCache[Option[String]] =
    agentCaches.asScala.getOrElseUpdate(agentName, MtimeCache.file(agentMemoryPath(agentName), parseMemory))

  private def getFolderCache(folderId: String): MtimeFileCache[Option[String]] =
    folderCaches.asScala.getOrElseUpdate(folderId, MtimeCache.file(folderMemoryPath(folderId), parseMemory))

  // --- Load (mtime-cached) — injected into system prompts ---

  def loadUserMemory: Option[String] =
    userCache.get.unsafeRunSync().flatten

  def loadAgentMemory(agentName: String): Option[String] =
    getAgentCache(agentName).get.unsafeRunSync().flatten

  def loadFolderMemory(folderId: String): Option[String] =
    getFolderCache(folderId).get.unsafeRunSync().flatten

  // --- Save (called from WS routes / Dream agent, invalidates cache) ---

  private def saveFile(path: os.Path, content: String, invalidateCache: () => IO[Unit]): IO[Unit] =
    IO.blocking(os.write.over(path, content, createFolders = true)) *> invalidateCache()

  def saveUserMemory(content: String): IO[Unit] =
    saveFile(userMemoryPath, content, () => userCache.invalidate)

  def saveAgentMemory(agentName: String, content: String): IO[Unit] =
    saveFile(agentMemoryPath(agentName), content, () => getAgentCache(agentName).invalidate)

  def saveFolderMemory(folderId: String, content: String): IO[Unit] =
    saveFile(folderMemoryPath(folderId), content, () => getFolderCache(folderId).invalidate)

  // --- Detail files (called by Dream agent via Write tool) ---

  /** Write a detail file to ~/.nebflow/memory/{hash}.md. */
  def writeDetailFile(hash: String, content: String): IO[Unit] =
    IO.blocking {
      os.write.over(detailPath(hash), content, createFolders = true)
    }

  // --- Cache invalidation ---

  def invalidateUserCache(): Unit =
    userCache.invalidate.unsafeRunSync()

  def invalidateAgentCache(agentName: String): Unit =
    getAgentCache(agentName).invalidate.unsafeRunSync()

  def invalidateFolderCache(folderId: String): Unit =
    getFolderCache(folderId).invalidate.unsafeRunSync()

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
