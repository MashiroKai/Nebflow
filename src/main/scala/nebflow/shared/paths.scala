package nebflow.shared

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

  /**
   * worktree 归一化拒绝文案（轴 2 可行动化）：正例裸名 + 禁形态 + 补救命令 +
   * 错误码（EMPTY_NODE_CONNECTION 同风格，LLM 可自纠）。
   */
  val WorktreeFormatError: String =
    "Worktree must be a bare directory name under the project's .nebflow/worktrees/ — " +
      "e.g. \"micorb-config-hide\". Do NOT include the \"worktrees/\" prefix, path separators, " +
      "absolute paths, or \"..\". Create it first via: " +
      "git worktree add <workspace>/.nebflow/worktrees/<name> -b <branch>, " +
      "then pass worktree: \"<name>\". (WORKTREE_FORMAT)"

  /**
   * worktree 参数归一化：任意合理形态 → worktrees/ 下裸段名；不可归一 →
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

  /**
   * `.nebflow/` 顶层保留名（QC P1）：worktrees/ 容器 + 项目级系统目录
   * （skills/ commands/，见 SkillService.projectSkillPaths/projectCommandPaths）。
   * 顶层 fallback 永不把这些名字解析为 worktree——否则 `worktree: "skills"` 会
   * 因 `.nebflow/skills` 实存而通过校验、节点在系统目录里运行，且 NodeList 会
   * 主动把它们列为候选（参照系污染）。权威位置 worktrees/<名> 不受此限。
   */
  val ReservedTopLevelNames: Set[String] = Set("worktrees", "skills", "commands")

  /**
   * worktree 目录双位置实存解析：worktrees/<名>（权威位置）优先，
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

  /**
   * 节点运行时 projectRoot（cwd/沙箱根来源）解析——NodeEngine 单一调用点。
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
              .getOrElse(os.Path(workspace) / ".nebflow" / bare)
              .toString
          case Left(_) => workspace

  /**
   * The root data directory for Stand state (sessions, tasks, memory, config, etc.).
   * Override priority (数据根指向切换批, 2026-10):
   *   1. CLI --home flag          ( setDataRoot — isolation / canary )
   *   2. STAND_HOME env           ( first-priority env, brand prefix )
   *   3. NEBFLOW_HOME env         ( legacy compat — old scripts / launchd
   *                                 units keep working, read second )
   *   4. default directory        ( ~/.stand; legacy-only machines fall back
   *                                 to ~/.nebflow + a one-line hint, see
   *                                 resolveDefaultDataRoot )
   * Single point: every data-root derivation must go through this value or
   * the Branding.homeDirName default — no scattered literals.
   */
  // ── 三层数据根（B1 批，契约 reports-evidence/20261104_b1-dataroot/design-api.md §1/§2）──
  //
  // 语义自本批起分两层：
  //   · **机器层根**（machineRoot）= 全机共享、与账号无关的根（bin/ jar、生命周期 logs/、.pid、
  //     daemons*.json、auth.json、layout.json、active-account）。解析链与今日 dataRoot 逐字相同；
  //   · **有效根**（dataRoot）= 账号层根 / 未登录层根 / 平铺根，由 `RootLadder` 阶梯从机器层根派生。
  //
  // 三条不可动的总则：
  //   1. 无 `layout.json` 的平铺根，一切字节不变（既有 713 处 `setDataRoot` 引用与隔离实例的全部前提）；
  //   2. 进程级单值、禁热切 —— 登录成功后写指针 + 重启进程，进程内不二次换根；
  //   3. 机器层先于账号层可写 —— 即使账号根不存在/不可读/未登录，引擎也必须起得来并说清原因。
  //
  // 数据根覆盖优先级（数据根指向切换批, 2026-10）不变：
  //   1. CLI --home flag          ( setDataRoot / setMachineRoot — isolation / canary )
  //   2. STAND_HOME env           ( first-priority env, brand prefix )
  //   3. NEBFLOW_HOME env         ( legacy compat — old scripts / launchd units keep working )
  //   4. default directory        ( ~/.stand; legacy-only machines fall back to ~/.nebflow
  //                                 + a one-line hint, see resolveDefaultDataRoot )
  // Single point: every data-root derivation must go through this value or the
  // Branding.homeDirName default — no scattered literals.

  // 两个覆盖位都在 setter 的 `synchronized` 里写、在无锁读路径上读 ⇒ 用 `@volatile` 保证可见性
  // （与今日语义同：换根不是并发热切换机制，只是不许读线程看到撕裂/陈旧值）。
  @volatile private var _machineRootOverride: Option[os.Path] = None
  @volatile private var _effectiveOverride: Option[os.Path] = None

  /**
   * 有效根分档的进程级快照（`@volatile`：定案后是无锁读 —— `dataRoot` 是热路径，
   * 全仓 490 处引用，不能为了一次性定档让每次取根都过锁）。`None` = 尚未定档。
   */
  @volatile private var _layerCache: Option[nebflow.core.root.RootLayer] = None

  /**
   * 平铺钉死语义（契约 §1）：一个值同时当机器层与有效根。**签名与可重复赋值语义不变** ——
   * 713 处测试引用、`--home` 隔离实例、canary 全部零字节变化（它们从来没有账号层）。
   */
  def setDataRoot(path: os.Path): Unit = synchronized {
    _machineRootOverride = Some(path)
    _effectiveOverride = Some(path)
    _layerCache = None
  }

  /**
   * 生产启动序唯一调用点（契约 §1/§8.1）：只钉机器层根，有效根交给 `nebflow.core.root.RootLadder`
   * 阶梯派生。两个 setter 都允许重复调用（测试面需要）；「禁热切」是生产启动面的约束，由
   * `nebflow.core.root.LoginGuard.checkHotSwitch` 在 CLI / 网关入口判，不在这里硬拦。
   */
  def setMachineRoot(path: os.Path): Unit = synchronized {
    _machineRootOverride = Some(path)
    _effectiveOverride = None
    _layerCache = None
  }

  /** 机器层根 = `--home` / `STAND_HOME` / `NEBFLOW_HOME` / 默认 `~/.stand`（今日 dataRoot 的解析链原样搬来）。 */
  def machineRoot: os.Path =
    _machineRootOverride.getOrElse(envOrDefaultRoot)

  private def envOrDefaultRoot: os.Path =
    Branding.env("HOME") match
      case Some(home) => os.Path(home, os.pwd)
      case None => resolvedDefaultRoot

  /**
   * 有效根的分档（进程级单值，定案一次；`setDataRoot` / `setMachineRoot` 是唯一的搬手）：
   *  - 显式换根（`setDataRoot`）⇒ 平铺档，有效根 = 那个值（钉死语义，不探盘）；
   *  - 否则 ⇒ `RootLadder.resolve(machineRoot)` 的阶梯判定（account / unauthenticated / flat）。
   *
   * 单一事实来源：`dataRoot` 与 `RootLadder.current` 都读这里，避免两处缓存各自漂移。
   */
  private[nebflow] def resolvedLayer: nebflow.core.root.RootLayer =
    _layerCache match
      case Some(layer) => layer // 快照命中：无锁（热路径）
      case None => synchronized {
          _layerCache match
            case Some(layer) => layer
            case None =>
              val layer = _effectiveOverride match
                case Some(pinned) => nebflow.core.root.RootLayer.Flat(machineRoot, pinned)
                case None => nebflow.core.root.RootLadder.resolve(machineRoot)
              _layerCache = Some(layer)
              layer
        }

  /**
   * 有效根（语义自本批起 = 有效根，契约 §1）：账号层根 / 未登录层根 / 平铺根。
   * 平铺档恒等于 [[machineRoot]] ⇒ 无布局标记的既有机器字节不变。
   */
  def dataRoot: os.Path = resolvedLayer.root

  // ── 数据根渲染层（home 硬编码 → 运行时动态化批，2026-09-11）─────────────
  //
  // 背景：提示词 / 插件 SKILL.md / 工具描述里散落 `~/.nebflow` 字面 —— 隔离实例
  // （`--home /tmp/...`）下这些字面指向**另一个** home：教错路径、BashTool 危险
  // 命令正则护错对象。收口在**渲染层**（磁盘字节零变 ⇒ 插件 digest / 信任门零
  // 影响），单一实现供三处调用（勿复制多份）：AgentCore.buildSystemPrompt（agent
  // system.md 文本面）、NodeEngine.injectedPluginBlock（<injected-plugins> 注入
  // 块）、DispatcherContextCatalog.render（分发器双目录段）。

  /**
   * 提示词文本里的数据根占位符 —— 与既有 `{{working_dir}}` / `{{pid}}` 同源
   * 语法（data.sh JSON 替换通道）。
   */
  val DataRootPlaceholder: String = "{{data_root}}"

  /**
   * `{{data_root}}` 渲染值 / 工具描述插值值（P2-b 回归守卫）：
   *   - 默认 home ⇒ 字面 `~/<homeDirName>`（当前品牌 = `~/.nebflow`）—— 主实例
   *     输出逐字节不变；
   *   - 非默认（隔离实例 / `--home` / 测试换根）⇒ dataRoot 绝对路径 —— 渲染出的
   *     路径指向**本实例自己的** home。
   * `def` on purpose：dataRoot 可在对象初始化后被换根（setDataRoot / --home）。
   */
  def dataRootRenderValue: String = renderDataRootValue(dataRoot)

  /**
   * Pure core of [[dataRootRenderValue]] — parameterized so the spec can verify
   * both rendering tiers without swapping the process-wide data root.
   */
  private[shared] def renderDataRootValue(root: os.Path): String =
    if root == os.home / Branding.homeDirName then "~/" + Branding.homeDirName
    else root.toString

  /**
   * 纯文本变换：`{{data_root}}` → [[dataRootRenderValue]]，其余字节原样透传。
   * 渲染层单点（播种时替换 / 脚本 sed 两条路线已否决——它们改的是磁盘字节，
   * 会让插件 digest 漂移并触发信任门重审）。
   */
  def substituteDataRoot(text: String): String =
    if text.contains(DataRootPlaceholder) then text.replace(DataRootPlaceholder, dataRootRenderValue)
    else text

  /**
   * Inverse of the data-root rendering for stored `_ref` values (数据根指向
   * 切换批, 2026-10): a ref spelled in a data-root token form maps back under
   * `root`; an absolute path stays as-is; anything else is relative to `root`.
   *
   * Recognized token forms (prefix + "/" only):
   *   - `~/<LegacyHomeDirName>` — the legacy spelling (`~/.nebflow/...`):
   *     stored configs written before the switch must keep resolving onto the
   *     CURRENT root (legacy machine: root IS ~/.nebflow, byte-equal path;
   *     migrated machine: the ref follows the secrets to the new root — the
   *     ref is a data-root TOKEN, not a literal path);
   *   - `~/<brand.homeDirName>` — the rendered form on a default install
   *     (`~/.stand` since this batch);
   *   - [[renderDataRootValue]] — an isolated instance's absolute root
   *     (dedups with the second form on a default install).
   * Single implementation — FeishuCredentials / SocialChannels `_ref` faces
   * both consume this (no second spelling of the legacy prefix).
   */
  def resolveRootRef(root: os.Path, ref: String): os.Path =
    val normalized = ref.replace('\\', '/')
    // token 前缀比对也走正斜杠形态（勿删）：Windows root.toString 为反斜杠形态，
    // 裸比对恒失配 ⇒ 绝对 ref 落进兜底拼接出「root + / + C:/…」双路径 →
    // InvalidPathException（Windows 测试批 2026-10-25，social 簇 17 例的根因）。
    // Mac 上 rootNorm == root.toString，逐行为零变化。
    val rootNorm = root.toString.replace('\\', '/')
    val tokenPrefixes = List(
      s"~/$LegacyHomeDirName",
      s"~/${Branding.homeDirName}",
      rootNorm
    ).distinct
    tokenPrefixes.find(p => normalized.startsWith(p + "/")) match
      case Some(p) => root / os.SubPath(normalized.drop(p.length).stripPrefix("/"))
      case None =>
        // 绝对 ref（POSIX 前导 / 或 Windows 盘符斜杠形态）按契约原样返回。
        if normalized.startsWith("/") || normalized.matches("[A-Za-z]:/.*") then os.Path(normalized, os.pwd)
        else root / os.SubPath(normalized)
  end resolveRootRef

  /**
   * The legacy data-root directory name, HARDCODED — it is a historical
   * fact, not a configuration (deriving it from brand.conf would make the
   * fallback unreachable). Single spelling source: resolveDefaultDataRoot's
   * fallback and MemoryPaths' recognized bases both consume this — never
   * re-spell ".nebflow" at a new call site.
   */
  val LegacyHomeDirName: String = ".nebflow"

  /**
   * Resolved default root — lazy so the default-resolution (existence
   * probes + the possible legacy fallback hint) runs at most once per JVM,
   * on first dataRoot access, thread-safely.
   */
  private lazy val resolvedDefaultRoot: os.Path =
    resolveDefaultDataRoot(os.home, Branding.homeDirName)

  /**
   * Default data root resolution (数据根指向切换批, 2026-10). Parameterized
   * over home and the brand directory name so the spec can exercise the
   * switch-day behavior; production passes os.home and brand.conf
   * homeDirName (= ".stand" since this batch).
   *
   * The legacy name ".nebflow" is HARDCODED — it is a historical fact, not
   * a configuration; deriving it from brand.conf would make the fallback
   * unreachable. With the legacy brand value (homeDirName == ".nebflow")
   * every branch collapses to the same directory: zero behavior change.
   *
   * NO in-process data migration happens here — the real migration is the
   * external toolbox scripts/data-root-migration/ (segment 2, downtime
   * window). The engine only picks a root; it never copies trees.
   *
   * Resolution order (switch day, homeDirName == ".stand"):
   *  1. same name                → use it (legacy brand value; nothing to do)
   *  2. ~/.stand exists          → use it (already migrated/cloned by the
   *                                 toolbox, or a fresh install that started
   *                                 on the new root)
   *  3. ~/.nebflow missing       → use ~/.stand (fresh machine: no data
   *                                 anywhere, new installs land here)
   *  4. only legacy ~/.nebflow   → use ~/.nebflow + ONE-LINE stderr hint
   *                                 (老机器无痛: keep serving the old root
   *                                 until the toolbox migrates it; never
   *                                 strand the data by silently switching
   *                                 to an empty ~/.stand)
   */
  private[shared] def resolveDefaultDataRoot(home: os.Path, homeDirName: String): os.Path =
    val newDir = home / homeDirName
    val legacyDir = home / LegacyHomeDirName
    if newDir == legacyDir then newDir
    else if os.exists(newDir) then newDir
    else if !os.exists(legacyDir) then newDir
    else
      System.err.println(
        s"[${Branding.productName}] data root: $newDir not found; using legacy $legacyDir " +
          "(migrate with scripts/data-root-migration/ or set STAND_HOME to pin a root)"
      )
      legacyDir

    end if

  end resolveDefaultDataRoot

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
  private[shared] def resolveConfigJson(dir: os.Path, fileName: String): os.Path =
    if fileName == "nebflow.json" then dir / fileName
    else if os.exists(dir / fileName) then dir / fileName
    else if os.exists(dir / "nebflow.json") then dir / "nebflow.json"
    else dir / fileName

  /**
   * Config file path for WRITES: always the brand name. A read-modify-write
   * cycle (existing from configJsonReadPath, output to here) completes the
   * file rename migration on first write.
   */
  def configJsonWritePath(dir: os.Path): os.Path =
    dir / Branding.configFileName

end PathUtil
