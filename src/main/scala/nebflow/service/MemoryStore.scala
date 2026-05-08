package nebflow.service

import cats.effect.IO

/**
 * Three-level memory store backed by Markdown files.
 *
 * Levels:
 *   - User Preference:  ~/.nebflow/NEBFLOW.md           (global, all agents)
 *   - Agent:            ~/.nebflow/agents/{name}/memory.md  (per agent)
 *   - Session:          ~/.nebflow/sessions/{sid}.memory.md (per session)
 */
object MemoryStore:

  // --- Paths ---

  def userMemoryPath: os.Path = os.home / ".nebflow" / "NEBFLOW.md"

  def agentMemoryPath(agentName: String): os.Path =
    os.home / ".nebflow" / "agents" / agentName / "memory.md"

  def sessionMemoryPath(sessionId: String): os.Path =
    os.home / ".nebflow" / "sessions" / s"$sessionId.memory.md"

  // --- Load (synchronous — called during system prompt build) ---

  private def loadFile(path: os.Path): Option[String] =
    if os.exists(path) then
      val c = os.read(path).trim
      if c.nonEmpty then Some(c) else None
    else None

  def loadUserMemory: Option[String] = loadFile(userMemoryPath)

  def loadAgentMemory(agentName: String): Option[String] =
    loadFile(agentMemoryPath(agentName))

  def loadSessionMemory(sessionId: String): Option[String] =
    loadFile(sessionMemoryPath(sessionId))

  // --- Save (async — called from WS routes / tools) ---

  private def saveFile(path: os.Path, content: String): IO[Unit] =
    IO.blocking(os.write.over(path, content, createFolders = true))

  def saveUserMemory(content: String): IO[Unit] =
    saveFile(userMemoryPath, content)

  def saveAgentMemory(agentName: String, content: String): IO[Unit] =
    saveFile(agentMemoryPath(agentName), content)

  def saveSessionMemory(sessionId: String, content: String): IO[Unit] =
    saveFile(sessionMemoryPath(sessionId), content)

  // --- Preview (first non-heading, non-empty line, max 80 chars) ---

  def preview(path: os.Path): Option[String] =
    loadFile(path).map { content =>
      content.linesIterator
        .dropWhile(l => l.trim.isEmpty || l.trim.startsWith("#"))
        .find(_.trim.nonEmpty)
        .map(_.trim.take(80))
        .getOrElse("(empty)")
    }

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
