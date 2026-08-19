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
   * Normalize path separators for the current OS.
   * On Windows (`\\` separator), forward slashes are converted to backslashes.
   * On Unix (`/` separator), this is a no-op.
   *
   * This prevents mixed-separator paths (e.g. `C:\Users\name/Desktop`) that
   * os-lib rejects when parsing path segments — see BUG 1 & 2.
   */
  def normalizeSeparators(s: String): String =
    val sep = java.io.File.separatorChar
    if sep == '\\' then s.replace('/', '\\')
    else s

  /**
   * Expand a leading `~` (or `~/`) to the JVM's `user.home`.
   * Leaves all other strings unchanged. Idempotent.
   *
   * After expansion, separators are normalized for the current OS so that
   * `~/Desktop` on Windows produces `C:\Users\name\Desktop` (all backslashes),
   * not `C:\Users\name/Desktop` (mixed separators that os-lib rejects).
   *
   * For remote-exec, this MUST run on the *receiving* device so `~` resolves
   * to the remote user's home (e.g. `C:\Users\name` on Windows), not the
   * sender's. Expanding on the sender would produce the wrong OS's home path.
   */
  def expandTilde(s: String): String =
    val home = sys.props.getOrElse("user.home", "~")
    val expanded =
      if s == "~" then home
      else if s.startsWith("~/") then home + s.substring(1)
      else s
    normalizeSeparators(expanded)

  /**
   * Expand leading `~` and normalize separators in known path params of a
   * tool-call JsonObject.
   *
   * Used by remote-exec receivers (P2P direct + relay) so path-bearing tools
   * (Read/Write/Edit/Glob/Grep) accept `~` relative to the receiving device,
   * and so Windows-style paths with forward slashes (e.g. `C:/Users/name/x`)
   * are normalized to the OS-native separator.
   *
   * Bash is excluded — its shell expands `~` natively, and rewriting inside
   * `command` strings would be unsafe.
   */
  def expandPathParams(params: JsonObject): JsonObject =
    def normalizeKey(obj: JsonObject, key: String): JsonObject =
      obj(key).flatMap(_.asString) match
        case Some(s) =>
          val expanded = if s.startsWith("~") then expandTilde(s) else normalizeSeparators(s)
          obj.add(key, expanded.asJson)
        case None => obj
    Set("file_path", "path").foldLeft(params)(normalizeKey)

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
   * Override priority: CLI --home flag > <PREFIX>_HOME env var (dual-prefix
   * read, see Branding.env) > default directory (dual-read + one-time
   * migration, see resolveDefaultDataRoot).
   */
  private var _dataRootOverride: Option[os.Path] = None

  def setDataRoot(path: os.Path): Unit = _dataRootOverride = Some(path)

  def dataRoot: os.Path =
    _dataRootOverride.getOrElse(
      Branding.env("HOME") match
        case Some(home) => os.Path(home, os.pwd)
        case None => resolvedDefaultRoot
    )

  /** Marker file placed in the LEGACY directory after a one-time migration,
    * so a later "new dir missing + legacy present" state (user deleted the
    * new dir) resolves to the legacy dir instead of re-migrating. Fixed
    * name — an internal fact, not a brand value. */
  private val MigrationMarker = ".rebrand-migrated"

  /** Resolved default root — lazy so the (side-effecting) migration runs at
    * most once per JVM, on first dataRoot access, thread-safely. */
  private lazy val resolvedDefaultRoot: os.Path =
    resolveDefaultDataRoot(os.home, Branding.homeDirName)

  /**
   * Default data root resolution (L3 rebrand compat). Parameterized over
   * home and the brand directory name so the spec can exercise rename-day
   * behavior; production passes os.home and brand.conf homeDirName.
   *
   * The legacy name ".nebflow" is HARDCODED — it is a historical fact, not
   * a configuration; deriving it from brand.conf would make the fallback
   * unreachable. With the current brand value (homeDirName == ".nebflow")
   * every branch collapses to the same directory: zero behavior change.
   *
   * Resolution order (rename day, homeDirName == ".newbrand"):
   *  1. same name               → use it (current value; nothing to do)
   *  2. ~/.newbrand exists      → use it (already migrated, or fresh install
   *                               that started on the new name)
   *  3. ~/.nebflow missing      → use ~/.newbrand (fresh install)
   *  4. legacy already migrated → fall back to ~/.nebflow and warn (the new
   *    (marker present)           dir disappeared — respect the deletion,
   *                               never re-copy over a user's rollback)
   *  5. legacy present, no      → ONE-TIME MIGRATION: copy (not move — the
   *     marker                    legacy tree stays intact for rollback),
   *                               drop the marker into the legacy dir, then
   *                               use ~/.newbrand. Any copy failure falls
   *                               back to the legacy dir (availability over
   *                               migration).
   */
  private[core] def resolveDefaultDataRoot(home: os.Path, homeDirName: String): os.Path =
    val newDir = home / homeDirName
    val legacyDir = home / ".nebflow"
    if newDir == legacyDir then newDir
    else if os.exists(newDir) then newDir
    else if !os.exists(legacyDir) then newDir
    else if os.exists(legacyDir / MigrationMarker) then
      System.err.println(
        s"[${Branding.productName}] data directory $newDir is missing but $legacyDir was already migrated; using $legacyDir"
      )
      legacyDir
    else
      try
        os.copy(legacyDir, newDir, createFolders = true, mergeFolders = true, replaceExisting = false)
        os.write.over(legacyDir / MigrationMarker, s"migrated to $newDir\n")
        System.err.println(
          s"[${Branding.productName}] migrated data directory $legacyDir -> $newDir (copied; original kept for rollback)"
        )
        newDir
      catch
        case e: Exception =>
          System.err.println(
            s"[${Branding.productName}] data directory migration $legacyDir -> $newDir failed (${e.getMessage}); continuing with $legacyDir"
          )
          legacyDir

  /**
   * Config file path for READS (L3 rebrand compat): prefer the brand name
   * (brand.conf configFileName), fall back to the HARDCODED legacy
   * "nebflow.json" when only the legacy file exists. When neither exists
   * the NEW name is returned — initializers (Main's first-run "{}" write)
   * then create the new name. Current brand value collapses all branches
   * to the same file: zero behavior change.
   */
  def configJsonReadPath(dir: os.Path): os.Path =
    resolveConfigJson(dir, Branding.configFileName)

  /** Pure core of configJsonReadPath (parameterized for the spec). */
  private[core] def resolveConfigJson(dir: os.Path, fileName: String): os.Path =
    if fileName == "nebflow.json" then dir / fileName
    else if os.exists(dir / fileName) then dir / fileName
    else if os.exists(dir / "nebflow.json") then dir / "nebflow.json"
    else dir / fileName

  /** Config file path for WRITES: always the brand name. A read-modify-write
    * cycle (existing from configJsonReadPath, output to here) completes the
    * file rename migration on first write. */
  def configJsonWritePath(dir: os.Path): os.Path =
    dir / Branding.configFileName

end PathUtil
