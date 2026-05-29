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

  /** Resolve rg path: PATH lookup first, then ~/.nebflow/bin/rg */
  private def resolveRgPath: Option[String] =
    val pathEnv = sys.env.getOrElse("PATH", "")
    // 1. PATH lookup
    val fromPath = pathEnv
      .split(File.pathSeparator)
      .iterator
      .map(d => new File(d, rgBinName))
      .find(_.isFile)
      .map(_.getAbsolutePath)
    // 2. Local nebflow bin
    val fromCache = Some(new File(rgLocalPath)).filter(_.isFile).map(_.getAbsolutePath)
    fromPath.orElse(fromCache)

  /** Read an InputStream line-by-line, throwing OutputTooLargeException if it exceeds 500KB. */
  def readWithLimit(is: java.io.InputStream): String =
    val reader = new BufferedReader(new InputStreamReader(is, "UTF-8"))
    val sb = new StringBuilder
    var totalBytes = 0
    var line = reader.readLine()
    while line != null do
      val lineBytes = line.length + 1
      if totalBytes + lineBytes > MAX_STDOUT_BYTES then
        reader.close()
        throw new OutputTooLargeException
      if sb.nonEmpty then sb.append('\n')
      sb.append(line)
      totalBytes += lineBytes
      line = reader.readLine()
    reader.close()
    sb.toString
  end readWithLimit

  /**
   * Run rg with the given args. Returns (stdout, stderr, exitCode) or a ToolError.
   * Enforces 60s timeout and 500KB output limit.
   */
  def runRg(args: List[String], workDir: String): Either[ToolError, (String, String, Int)] =
    resolveRgPath match
      case None =>
        Left(
          ToolError(
            "ripgrep (rg) not found. Run 'nebflow update' or reinstall to get it: https://github.com/BurntSushi/ripgrep"
          )
        )
      case Some(rgPath) =>
        try
          val proc = new ProcessBuilder((rgPath :: args)*)
            .directory(new File(workDir))
            .start()
          try
            val stdoutStr = readWithLimit(proc.getInputStream)
            val stderrStr = readWithLimit(proc.getErrorStream)
            val exited = proc.waitFor(60, TimeUnit.SECONDS)
            val exitCode =
              if exited then proc.exitValue()
              else
                proc.destroyForcibly(); -1
            Right((stdoutStr, stderrStr, exitCode))
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
