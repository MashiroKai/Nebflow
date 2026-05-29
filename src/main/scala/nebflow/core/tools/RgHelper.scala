package nebflow.core.tools

import java.io.{BufferedReader, File, InputStreamReader}
import java.util.concurrent.TimeUnit

import scala.collection.mutable.StringBuilder

/**
 * Shared helpers for running ripgrep (rg) subprocesses.
 * Used by GrepTool and GlobTool to avoid duplicating process management and output limiting logic.
 */
object RgHelper:

  private val MAX_STDOUT_BYTES = 500 * 1024

  class OutputTooLargeException extends Exception("rg output exceeded size limit")

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
    try
      val proc = new ProcessBuilder(("rg" :: args)*)
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
        val msg = e.getMessage
        if msg != null && msg.contains("Cannot run program") then
          Left(
            ToolError("rg (ripgrep) not found. Install it to enable Glob/Grep: https://github.com/BurntSushi/ripgrep")
          )
        else Left(ToolError(s"Failed to run rg: ${e.getMessage}"))

end RgHelper
