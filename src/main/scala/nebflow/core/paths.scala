package nebflow.core

import io.circe.JsonObject
import io.circe.syntax.*
import os.Path

/**
 * Cross-platform path utilities.
 * On Windows, os.Path("D:\\foo") fails when os.pwd is on C: drive
 * because os-lib treats it as a relative path.
 * This object provides safe absolute path construction.
 */
object PathUtil:

  /**
   * Cross-platform absolute path check.
   * Unlike `java.nio.file.Paths.get(s).isAbsolute`, this works regardless of
   * which OS the JVM runs on — so a Mac JVM correctly recognizes `C:\foo`
   * and a Windows JVM correctly recognizes `/foo`.
   */
  def isAbsolute(s: String): Boolean =
    s.startsWith("/") || // Unix absolute
      s.startsWith("\\\\") || // UNC path (\\server\share)
      (s.length >= 2 && s.charAt(1) == ':') // Windows drive (C:\...)

  /**
   * Expand a leading `~` (or `~/`) to the JVM's `user.home`.
   * Leaves all other strings unchanged. Idempotent.
   *
   * For remote-exec, this MUST run on the *receiving* device so `~` resolves
   * to the remote user's home (e.g. `C:\Users\kai` on Windows), not the
   * sender's. Expanding on the sender would produce the wrong OS's home path.
   */
  def expandTilde(s: String): String =
    val home = sys.props.getOrElse("user.home", "~")
    if s == "~" then home
    else if s.startsWith("~/") then home + s.substring(1)
    else s

  /**
   * Expand leading `~` in known path params of a tool-call JsonObject.
   * Used by remote-exec receivers (P2P direct + relay) so path-bearing tools
   * (Read/Write/Edit/Glob/Grep) accept `~` relative to the receiving device.
   *
   * Bash is excluded — its shell expands `~` natively, and rewriting inside
   * `command` strings would be unsafe.
   */
  def expandPathParams(params: JsonObject): JsonObject =
    def expandKey(obj: JsonObject, key: String): JsonObject =
      obj(key).flatMap(_.asString) match
        case Some(s) if s.startsWith("~") => obj.add(key, expandTilde(s).asJson)
        case _ => obj
    Set("file_path", "path").foldLeft(params)(expandKey)

  /** Construct an os.Path from a string, handling Windows cross-drive paths. */
  def resolvePath(s: String): Path =
    try os.Path(s, os.pwd)
    catch
      case _: Exception =>
        // On Windows, absolute paths on a different drive need special handling
        // java.nio.file.Paths handles this correctly
        Path(java.nio.file.Paths.get(s))

  /** Resolve a path relative to a base, handling Windows cross-drive cases. */
  def resolvePath(pathStr: String, base: Path): Path =
    val osName = sys.props.getOrElse("os.name", "").toLowerCase
    if osName.contains("win") && pathStr.length >= 2 && pathStr.charAt(1) == ':' then
      // Windows absolute path (e.g. "D:\foo") — don't resolve against base
      Path(java.nio.file.Paths.get(pathStr))
    else if pathStr.startsWith("/") || pathStr.startsWith("\\\\") then
      // Unix absolute or UNC path
      try os.Path(pathStr, base)
      catch case _: Exception => Path(java.nio.file.Paths.get(pathStr))
    else base / os.RelPath(pathStr)

  /**
   * The root data directory for Nebflow state (sessions, tasks, memory, config, etc.).
   * Override priority: CLI --home flag > NEBFLOW_HOME env var > ~/.nebflow default.
   */
  private var _dataRootOverride: Option[os.Path] = None

  def setDataRoot(path: os.Path): Unit = _dataRootOverride = Some(path)

  def dataRoot: os.Path =
    _dataRootOverride.getOrElse(
      sys.env.get("NEBFLOW_HOME") match
        case Some(home) => os.Path(home, os.pwd)
        case None => os.home / ".nebflow"
    )

end PathUtil
