package nebflow.core

import os.Path

/**
 * Cross-platform path utilities.
 * On Windows, os.Path("D:\\foo") fails when os.pwd is on C: drive
 * because os-lib treats it as a relative path.
 * This object provides safe absolute path construction.
 */
object PathUtil:

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
    else base / pathStr

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
        case None       => os.home / ".nebflow"
    )

end PathUtil
