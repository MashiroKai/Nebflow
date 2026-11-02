package nebflow.core.tools

import java.io.{BufferedReader, File, InputStreamReader}
import java.util.concurrent.TimeUnit

import scala.collection.mutable.StringBuilder

/**
 * Shared helpers for running ripgrep (rg) subprocesses.
 * Used by GrepTool and GlobTool to avoid duplicating process management and output limiting logic.
 *
 * rg is expected to be installed by the install script (install.sh/ps1) to
 * <home>/<homeDirName>/bin/rg (~/.stand/bin/rg since the data-root switch;
 * legacy installs keep ~/.nebflow/bin/rg — both positions probed, see
 * rgLocalPaths). Falls back to PATH lookup and, after it, to curated
 * well-known prefixes (launchd/systemd service contexts run with a minimal
 * PATH that strips the login shell's entries).
 */
object RgHelper:

  private val MAX_STDOUT_BYTES = 500 * 1024

  class OutputTooLargeException extends Exception("rg output exceeded size limit")

  private def isWindows: Boolean =
    sys.props.getOrElse("os.name", "").toLowerCase.contains("win")

  private val rgBinName: String = if isWindows then "rg.exe" else "rg"

  /**
   * Local install positions of rg, probed in order (数据根指向切换批, 2026-10):
   *   1. <user.home>/<brand.homeDirName>/bin — the default data-root bin
   *      (install.sh HOME_DIR = brand.conf homeDirName, now ~/.stand/bin);
   *   2. <user.home>/.nebflow/bin — the hardcoded LEGACY position (old
   *      installs keep working until they re-run the installer).
   * Both are the literal-home install layout (NOT PathUtil.dataRoot): an
   * isolated instance (--home /tmp/...) has no installer-seeded bin of its
   * own, so it must still find the host's rg here. Equal when the brand dir
   * is the legacy name → single probe (dedup).
   * `def` on purpose: derived at call time like the rest of the render layer.
   */
  private def rgLocalPaths: List[String] =
    List(
      sys.props(
        "user.home"
      ) + File.separator + nebflow.shared.Branding.homeDirName + File.separator + "bin" + File.separator + rgBinName,
      sys.props("user.home") + File.separator + ".nebflow" + File.separator + "bin" + File.separator + rgBinName
    ).distinct

  /**
   * Windows install-dir legs (%LOCALAPPDATA%\<productName>\rg.exe)。
   *
   * 缺陷⑥（2026-11-02）：此处曾硬编码 `Nebflow`——**rebrand 漏网**（install.ps1
   * 自 REBRAND 后写的是 `$env:LOCALAPPDATA\stand\rg.exe`，见 release/install.ps1
   * 的 `ExtractToFile(... Join-Path $InstallDir "rg.exe")` + brand.conf productName）。
   * 后果两条：(a) 新装机的正确位置**从不被探测**；(b) 只有旧名残留物才被选中，
   * 于是一个孤儿二进制反而优先于「找不到」——错误消息还指着 pre-rebrand 路径，
   * 把「找不到 rg」误读成「发行包漏了 rg」。现在按品牌名探（`stand`），旧名
   * `Nebflow` 保留为次选（旧装机不破）。
   */
  private val rgWinInstallPaths: List[String] =
    if isWindows then
      Option(System.getenv("LOCALAPPDATA")).toList.flatMap { local =>
        List(
          local + File.separator + nebflow.shared.Branding.productName + File.separator + rgBinName,
          local + File.separator + "Nebflow" + File.separator + rgBinName
        )
      }.distinct
    else Nil

  /**
   * Well-known rg install prefixes probed after the PATH leg. Service
   * contexts (launchd/systemd) run with a minimal PATH that never contains
   * these, so the PATH leg alone cannot find a package-manager rg there
   * (incident 2026-09-21: launchd PATH=/usr/bin:/bin:/usr/sbin:/sbin, readings
   * .nebflow/evidence/20260921_env-rg-restore/). Author ruling 2026-09-21
   * (envfix batch): ADD resolution positions only — the relative priority of
   * the existing legs is unchanged. Empty on Windows (the msi bundled leg and
   * the %LOCALAPPDATA% leg already cover the Windows install forms).
   */
  private val curatedProbeDirs: List[String] =
    if isWindows then Nil
    else List("/opt/homebrew/bin", "/usr/local/bin", "/snap/bin")

  /**
   * Resolve rg path: bundled app-dir copy → PATH lookup → curated well-known
   * prefixes → local bin (~/.stand/bin, then legacy ~/.nebflow/bin) →
   * Windows install dir. The bundled copy (msi payload <install>\app\rg.exe,
   * staged by packaging/build-msi.sh) wins for determinism — pinned version,
   * tested with the release.
   */
  private def resolveRgPath: Option[String] =
    resolveRgPathFrom(
      pathEnv = sys.env.getOrElse("PATH", ""),
      probeDirs = curatedProbeDirs,
      localPaths = rgLocalPaths,
      winInstall = rgWinInstallPaths
    )

  /**
   * Pure core of [[resolveRgPath]] — parameterized so specs can drive the
   * resolution chain without touching the process environment (a JVM cannot
   * rewrite its own PATH).
   */
  private[tools] def resolveRgPathFrom(
    pathEnv: String,
    probeDirs: List[String],
    localPaths: List[String],
    winInstall: List[String]
  ): Option[String] =
    val fromBundled = nebflow.core.InstallLayout.bundledRg
    // 1. PATH lookup
    val fromPath = pathEnv
      .split(File.pathSeparator)
      .iterator
      .map(d => new File(d, rgBinName))
      .find(_.isFile)
      .map(_.getAbsolutePath)
    // 2. Curated well-known prefixes (see curatedProbeDirs)
    val fromProbe = probeDirs.iterator
      .map(d => new File(d, rgBinName))
      .find(_.isFile)
      .map(_.getAbsolutePath)
    // 3. Local bin (macOS/Linux: ~/.stand/bin/rg, legacy ~/.nebflow/bin/rg)
    val fromCache = localPaths.iterator
      .map(p => new File(p))
      .find(_.isFile)
      .map(_.getAbsolutePath)
    // 4. Windows install dir (%LOCALAPPDATA%\<productName>\rg.exe，旧名 Nebflow 次选)
    val fromWinInstall = winInstall.iterator
      .map(p => new File(p))
      .find(_.isFile)
      .map(_.getAbsolutePath)
    fromBundled.orElse(fromPath).orElse(fromProbe).orElse(fromCache).orElse(fromWinInstall)

  end resolveRgPathFrom

  /**
   * Not-found message enumerates every leg the resolver probed — the error is
   * the self-diagnosis sheet. 路径经 PathUtil.dataRootRenderValue 插值（home
   * 硬编码 → 运行时动态化批 2026-09-11）：默认 home ⇒ `~/.nebflow/bin`，隔离
   * 实例 ⇒ 其实例数据根下的 bin（该实例真正使用的缓存位）。
   */
  private def notFoundMessage: String =
    val probeNote =
      if curatedProbeDirs.nonEmpty then s", well-known prefixes (${curatedProbeDirs.mkString(", ")})" else ""
    s"ripgrep (rg) not found (checked bundled app dir, PATH$probeNote, ${nebflow.shared.PathUtil.dataRootRenderValue}/bin). Run 'nebflow update' or reinstall to get it: https://github.com/BurntSushi/ripgrep"

  /**
   * Resolved rg path — exposed for the boot-time dependency probe
   * (WindowsDepProbe).
   */
  def resolvedPath: Option[String] = resolveRgPath

  /**
   * Read an InputStream line-by-line up to MAX_STDOUT_BYTES. Returns
   * (content, truncated): over-limit reads stop early (the writer gets
   * SIGPIPE once the stream closes) instead of throwing — the caller decides
   * whether truncation is fatal (Grep keeps the hard error) or tolerable
   * (Glob truncates and annotates).
   */
  def readWithLimit(is: java.io.InputStream): (String, Boolean) =
    val reader = new BufferedReader(new InputStreamReader(is, "UTF-8"))
    val sb = new StringBuilder
    var totalBytes = 0
    var truncated = false
    var line = reader.readLine()
    while line != null && !truncated do
      val lineBytes = line.length + 1
      if totalBytes + lineBytes > MAX_STDOUT_BYTES then truncated = true // over-cap line dropped; stream closed below
      else
        if sb.nonEmpty then sb.append('\n')
        sb.append(line)
        totalBytes += lineBytes
        line = reader.readLine()
    reader.close()
    (sb.toString, truncated)
  end readWithLimit

  /**
   * Run rg with the given args. Returns (stdout, stderr, exitCode, stdoutTruncated)
   * or a ToolError. Enforces 60s timeout and 500KB output limit.
   *
   * @param processCwd process working directory for rg (default: workDir). GlobTool
   *                   passes the search root and searches "." so rg's gitignore-style
   *                   glob anchoring resolves against the search root, not the
   *                   absolute-path prefix (single-segment wildcard patterns can
   *                   never match an absolute user-prefix).
   * @param truncateOnOverflow false (Grep): stdout over the limit stays a hard
   *                   ToolError. true (Glob): stdout is truncated at the limit and
   *                   stdoutTruncated=true is returned instead. stderr over the
   *                   limit is always truncated (diagnostic stream only).
   */
  def runRg(
    args: List[String],
    workDir: String,
    processCwd: Option[String] = None,
    truncateOnOverflow: Boolean = false
  ): Either[ToolError, (String, String, Int, Boolean)] =
    resolveRgPath match
      case None => Left(ToolError(notFoundMessage))
      case Some(rgPath) =>
        try
          // 🔴 缺陷⑥（2026-11-02）：子进程 cwd 此前是「用户给什么就传什么」。
          // Windows 上 CreateProcess 的 lpCurrentDirectory 不是目录 ⇒
          // error=267 (ERROR_DIRECTORY)「The directory name is invalid」，而
          // 报错文案会把程序路径顶到前面（`...\Nebflow\rg.exe (in directory ...)`），
          // 极易被读成「rg.exe 缺失/打包漏了」。真正的不变量是：**只有确认是目录
          // 才设 cwd**，否则交给父进程 cwd（rg 的搜索根是绝对参数，不依赖 cwd）。
          val pb = new ProcessBuilder((rgPath :: args)*)
          val cwd = new File(processCwd.getOrElse(workDir))
          if cwd.isDirectory then pb.directory(cwd)
          val proc = pb.start()
          try
            val (stdoutStr, stdoutTruncated) = readWithLimit(proc.getInputStream)
            if stdoutTruncated && !truncateOnOverflow then throw new OutputTooLargeException
            // stderr is diagnostic-only: truncation never fails the call
            val (stderrStr, _) = readWithLimit(proc.getErrorStream)
            val exited = proc.waitFor(60, TimeUnit.SECONDS)
            val exitCode =
              if exited then proc.exitValue()
              else
                proc.destroyForcibly(); -1
            Right((stdoutStr, stderrStr, exitCode, stdoutTruncated))
          finally proc.destroy()
        catch
          case _: OutputTooLargeException =>
            Left(
              ToolError(
                "Search results exceeded size limit (500KB). Use a more specific path, glob, type, or head_limit to narrow the search."
              )
            )
          case e: Exception =>
            Left(ToolError(s"Failed to run rg: ${e.getMessage}"))

end RgHelper
