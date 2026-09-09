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
   * Expand a leading `~` (or `~/` / `~\`) to the JVM's `user.home`.
   * Leaves all other strings unchanged. Idempotent.
   *
   * `~\x` (Windows separator form) expands to home + `\x` — on a Windows JVM
   * that yields the native `C:\Users\name\x`; on POSIX the backslash stays in
   * the name and the path simply won't exist (same failure class as before,
   * never a wrong-file read).
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
      else if s.startsWith("~/") || s.startsWith("~\\") then home + s.substring(1)
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

  // ── Node worktree 参数归一化与双位置解析（20260903 NodeEdit worktree 参数修复）──
  //
  // 根因（分析报告 20260903_nodeedit-worktree-param-fix.md）：NodeEdit 校验层
  // （NodeTools）与节点运行时 cwd（NodeEngine）各自用 os-lib 单段 `/` 拼接
  // worktree 参数，含 "/" 的入参（分发器 system.md 曾教 "worktrees/<名>"）即抛
  // 面向 Scala 开发者的 os-lib InvalidSegment，LLM 不可自纠（当日 7 崩 / 首试
  // 成功率 0%）；且三方参照系（system.md / NodeList worktrees[] / 校验层）互不
  // 一致。此处收口为单一权威实现，NodeTools 与 NodeEngine 引同一份。

  /** worktree 归一化拒绝文案（轴 2 可行动化）：正例裸名 + 禁形态 + 补救命令 +
    * 错误码（EMPTY_NODE_CONNECTION 同风格，LLM 可自纠）。 */
  val WorktreeFormatError: String =
    "Worktree must be a bare directory name under the project's .nebflow/worktrees/ — " +
      "e.g. \"micorb-config-hide\". Do NOT include the \"worktrees/\" prefix, path separators, " +
      "absolute paths, or \"..\". Create it first via: " +
      "git worktree add <workspace>/.nebflow/worktrees/<name> -b <branch>, " +
      "then pass worktree: \"<name>\". (WORKTREE_FORMAT)"

  /** worktree 参数归一化：任意合理形态 → worktrees/ 下裸段名；不可归一 →
    * Left(可行动报错)。
    *
    * 规则：trim → 剥 leading "./" → 剥 ".nebflow/worktrees/" / "worktrees/" /
    * ".nebflow/" 前缀（各剥一次）。拒绝：绝对路径 / 含 ".." / 剥后仍含 "/" /
    * 剥后为空（含 "."）。
    *
    * 安全边界：只剥已知固定前缀，不做任意路径解析；拒绝面（绝对路径/..）零放宽。
    */
  def normalizeWorktree(raw: String): Either[String, String] =
    val stripped = raw.trim.stripPrefix("./")
    if isAbsolute(stripped) then Left(WorktreeFormatError)
    else
      val bare =
        if stripped.startsWith(".nebflow/worktrees/") then stripped.stripPrefix(".nebflow/worktrees/")
        else if stripped.startsWith("worktrees/") then stripped.stripPrefix("worktrees/")
        else if stripped.startsWith(".nebflow/") then stripped.stripPrefix(".nebflow/")
        else stripped
      // ".." 按段级检查（QC P3 统一，与 GlobTool 同语义）：拒绝 "../x" 段，放行
      // "v2..fix" 这类含连续点的合法目录名（旧公式经 os-lib 本就接受后者）。
      if bare.split('/').contains("..") || bare.contains("/") || bare.isEmpty || bare == "." then
        Left(WorktreeFormatError)
      else Right(bare)

  /** `.nebflow/` 顶层保留名（QC P1）：worktrees/ 容器 + 项目级系统目录
    * （skills/ commands/，见 SkillService.projectSkillPaths/projectCommandPaths）。
    * 顶层 fallback 永不把这些名字解析为 worktree——否则 `worktree: "skills"` 会
    * 因 `.nebflow/skills` 实存而通过校验、节点在系统目录里运行，且 NodeList 会
    * 主动把它们列为候选（参照系污染）。权威位置 worktrees/<名> 不受此限。 */
  val ReservedTopLevelNames: Set[String] = Set("worktrees", "skills", "commands")

  /** worktree 目录双位置实存解析：worktrees/<名>（权威位置）优先，
    * .nebflow/<名>（顶层存量——现网节点全在顶层；今日生产顶层同名条目为指向
    * 权威位置的软链，两分支产出同物理目录，回归零影响）fallback，但保留名
    * （ReservedTopLevelNames）不参与 fallback。两处均不存在 → None。NodeTools
    * 校验与 NodeEngine 运行时 cwd 引同一份（参照系唯一）。os.exists 沿软链
    * （follow-links）语义与现状一致。
    */
  def resolveWorktreeDir(workspace: os.Path, bareName: String): Option[os.Path] =
    val authoritative = workspace / ".nebflow" / "worktrees" / bareName
    if os.exists(authoritative) then Some(authoritative)
    else if ReservedTopLevelNames.contains(bareName) then None
    else
      val legacy = workspace / ".nebflow" / bareName
      if os.exists(legacy) then Some(legacy) else None

  /** 节点运行时 projectRoot（cwd/沙箱根来源）解析——NodeEngine 单一调用点。
    * 存量裸名 / 新前缀形态均先归一再双位置实存：
    *   - 命中 → 实存目录（顶层命中 = 与旧公式 `(os.Path(workspace) / ".nebflow" /
    *     wt).toString` 逐字节一致，回归红线）；
    *   - 两处均不存在 → 旧公式路径（校验期实存、运行期目录被删窗口的字节等价兜底）；
    *   - 归一化拒绝（损坏存储值）→ workspace 兜底（旧实现此处 InvalidSegment
    *     炸 spawn，§5.2 同模式脆点亮）。
    */
  def resolveNodeProjectRoot(workspace: String, worktree: Option[String]): String =
    worktree match
      case None => workspace
      case Some(wt) =>
        normalizeWorktree(wt) match
          case Right(bare) =>
            resolveWorktreeDir(os.Path(workspace), bare)
              .getOrElse(os.Path(workspace) / ".nebflow" / bare).toString
          case Left(_) => workspace

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
