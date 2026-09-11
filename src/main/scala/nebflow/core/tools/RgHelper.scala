package nebflow.core.tools

import java.io.{BufferedReader, File, InputStreamReader}
import java.util.concurrent.TimeUnit

import scala.collection.mutable.StringBuilder

/**
 * Shared helpers for running ripgrep (rg) subprocesses.
 * Used by GrepTool and GlobTool to avoid duplicating process management and output limiting logic.
 *
 * rg is expected to be installed by the nebflow install script (install.sh/ps1)
 * to ~/.nebflow/bin/rg (or rg.exe on Windows). Falls back to PATH lookup.
 */
object RgHelper:

  private val MAX_STDOUT_BYTES = 500 * 1024

  class OutputTooLargeException extends Exception("rg output exceeded size limit")

  private def isWindows: Boolean =
    sys.props.getOrElse("os.name", "").toLowerCase.contains("win")

  private val rgBinName: String = if isWindows then "rg.exe" else "rg"

  private val rgLocalPath: String =
    sys.props("user.home") + File.separator + ".nebflow" + File.separator + "bin" + File.separator + rgBinName

  private val rgWinInstallPath: Option[String] =
    if isWindows then
      Option(System.getenv("LOCALAPPDATA")).map(_ + File.separator + "Nebflow" + File.separator + rgBinName)
    else None

  /** Resolve rg path: bundled app-dir copy → PATH lookup → local nebflow bin
    * (~/.nebflow/bin) → Windows install dir. The bundled copy (msi payload
    * <install>\app\rg.exe, staged by packaging/build-msi.sh) wins for
    * determinism — pinned version, tested with the release. */
  private def resolveRgPath: Option[String] =
    val fromBundled = nebflow.core.InstallLayout.bundledRg
    val pathEnv = sys.env.getOrElse("PATH", "")
    // 1. PATH lookup
    val fromPath = pathEnv
      .split(File.pathSeparator)
      .iterator
      .map(d => new File(d, rgBinName))
      .find(_.isFile)
      .map(_.getAbsolutePath)
    // 2. Local nebflow bin (macOS/Linux: ~/.nebflow/bin/rg)
    val fromCache = Some(new File(rgLocalPath)).filter(_.isFile).map(_.getAbsolutePath)
    // 3. Windows install dir (%LOCALAPPDATA%\Nebflow\rg.exe)
    val fromWinInstall = rgWinInstallPath.flatMap(p => Some(new File(p)).filter(_.isFile).map(_.getAbsolutePath))
    fromBundled.orElse(fromPath).orElse(fromCache).orElse(fromWinInstall)

  /** Resolved rg path — exposed for the boot-time dependency probe
    * (WindowsDepProbe). */
  def resolvedPath: Option[String] = resolveRgPath

  /** Read an InputStream line-by-line up to MAX_STDOUT_BYTES. Returns
    * (content, truncated): over-limit reads stop early (the writer gets
    * SIGPIPE once the stream closes) instead of throwing — the caller decides
    * whether truncation is fatal (Grep keeps the hard error) or tolerable
    * (Glob truncates and annotates). */
  def readWithLimit(is: java.io.InputStream): (String, Boolean) =
    val reader = new BufferedReader(new InputStreamReader(is, "UTF-8"))
    val sb = new StringBuilder
    var totalBytes = 0
    var truncated = false
    var line = reader.readLine()
    while line != null && !truncated do
      val lineBytes = line.length + 1
      if totalBytes + lineBytes > MAX_STDOUT_BYTES then
        truncated = true // over-cap line dropped; stream closed below
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
      case None =>
        Left(
          ToolError(
            // 路径经 PathUtil.dataRootRenderValue 插值（home 硬编码 → 运行时动态化
            // 批 2026-09-11）：默认 home ⇒ `~/.nebflow/bin`，隔离实例 ⇒ 其实例
            // 数据根下的 bin（该实例真正使用的缓存位）。
            s"ripgrep (rg) not found (checked bundled app dir, PATH, ${nebflow.core.PathUtil.dataRootRenderValue}/bin). Run 'nebflow update' or reinstall to get it: https://github.com/BurntSushi/ripgrep"
          )
        )
      case Some(rgPath) =>
        try
          val proc = new ProcessBuilder((rgPath :: args)*)
            .directory(new File(processCwd.getOrElse(workDir)))
            .start()
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
