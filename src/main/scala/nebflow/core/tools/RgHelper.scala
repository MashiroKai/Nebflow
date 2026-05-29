package nebflow.core.tools

import java.io.{BufferedReader, File, FileOutputStream, InputStreamReader}
import java.net.URL
import java.nio.channels.Channels
import java.nio.file.{Files, Paths}
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

import scala.collection.mutable.StringBuilder

/**
 * Shared helpers for running ripgrep (rg) subprocesses.
 * Used by GrepTool and GlobTool to avoid duplicating process management and output limiting logic.
 *
 * Auto-installs rg on first use if not found in PATH:
 *  - Downloads to ~/.nebflow/bin/rg (or rg.exe on Windows)
 *  - Reuses the cached binary on subsequent calls
 */
object RgHelper:

  private val MAX_STDOUT_BYTES = 500 * 1024

  class OutputTooLargeException extends Exception("rg output exceeded size limit")

  private def isWindows: Boolean =
    sys.props.getOrElse("os.name", "").toLowerCase.contains("win")

  private val rgBinName: String = if isWindows then "rg.exe" else "rg"
  private val nebflowBinDir: File = new File(sys.props("user.home"), ".nebflow" + File.separator + "bin")
  private val rgLocalPath: File = new File(nebflowBinDir, rgBinName)
  private val RgVersion = "14.1.1"

  /** Download archive name for the current platform. */
  private def archiveInfo: Option[(String, String)] = // (downloadUrl, extension: "zip"|"tar.gz")
    val os = sys.props.getOrElse("os.name", "").toLowerCase
    val arch = sys.props.getOrElse("os.arch", "").toLowerCase
    val base = s"https://github.com/BurntSushi/ripgrep/releases/download/$RgVersion/ripgrep-$RgVersion"
    (os, arch) match
      case (o, _) if o.contains("win") =>
        Some((s"$base-x86_64-pc-windows-msvc.zip", "zip"))
      case (o, a) if o.contains("mac") && (a.contains("aarch64") || a.contains("arm64")) =>
        Some((s"$base-aarch64-apple-darwin.tar.gz", "tar.gz"))
      case (o, _) if o.contains("mac") =>
        Some((s"$base-x86_64-apple-darwin.tar.gz", "tar.gz"))
      case (o, _) if o.contains("linux") =>
        Some((s"$base-x86_64-unknown-linux-musl.tar.gz", "tar.gz"))
      case _ => None

  /** Resolve rg path: PATH → local cache → auto-download */
  private def resolveRgPath: Either[String, String] =
    // 1. PATH lookup
    val pathEnv = sys.env.getOrElse("PATH", "")
    val inPath = pathEnv
      .split(File.pathSeparator)
      .iterator
      .map(d => new File(d, rgBinName))
      .find(_.isFile)
      .map(_.getAbsolutePath)
    if inPath.isDefined then return Right(inPath.get)

    // 2. Local cache
    if rgLocalPath.isFile then return Right(rgLocalPath.getAbsolutePath)

    // 3. Auto-download
    downloadRg() match
      case Right(_) => Right(rgLocalPath.getAbsolutePath)
      case Left(err) => Left(err)

  end resolveRgPath

  /** Download and extract rg binary to nebflow bin dir. */
  private def downloadRg(): Either[String, Unit] =
    archiveInfo match
      case None =>
        Left(s"Unsupported platform: ${sys.props("os.name")} ${sys.props("os.arch")}")
      case Some((url, ext)) =>
        try
          nebflowBinDir.mkdirs()
          println(s"[rg] Downloading ripgrep $RgVersion (~5MB)...")
          val archive = new File(nebflowBinDir, s"rg-archive.$ext")
          downloadFile(url, archive)

          println(s"[rg] Extracting...")
          ext match
            case "zip" => extractZip(archive, rgBinName, rgLocalPath)
            case "tar.gz" => extractTarGzUsingTar(archive, rgBinName, rgLocalPath)
            case _ => return Left(s"Unknown archive format: $ext")

          archive.delete()
          rgLocalPath.setExecutable(true)
          println(s"[rg] Installed to $rgLocalPath")
          Right(())
        catch case e: Exception => Left(s"Failed to install rg: ${e.getMessage}")

  /** Download a file from URL to local path. */
  private def downloadFile(url: String, dest: File): Unit =
    val conn = new URL(url).openConnection()
    conn.setConnectTimeout(10000)
    conn.setReadTimeout(120000)
    val in = conn.getInputStream
    try
      val out = new FileOutputStream(dest)
      try
        val buf = new Array[Byte](8192)
        var read = in.read(buf)
        while read != -1 do
          out.write(buf, 0, read)
          read = in.read(buf)
      finally out.close()
    finally in.close()

  end downloadFile

  /** Extract a single file from a zip archive. */
  private def extractZip(archive: File, targetName: String, dest: File): Unit =
    val zis = new ZipInputStream(new java.io.FileInputStream(archive))
    try
      var entry = zis.getNextEntry
      while entry != null do
        if entry.getName.replace('\\', '/').endsWith(targetName) then
          val out = new FileOutputStream(dest)
          try
            val buf = new Array[Byte](8192)
            var read = zis.read(buf)
            while read != -1 do
              out.write(buf, 0, read)
              read = zis.read(buf)
          finally out.close()
        entry = zis.getNextEntry
    finally zis.close()

  end extractZip

  /** Extract a single file from a tar.gz using system `tar` command (available on macOS/Linux). */
  private def extractTarGzUsingTar(archive: File, targetName: String, dest: File): Unit =
    val isWindows = sys.props.getOrElse("os.name", "").toLowerCase.contains("win")
    if isWindows then
      // Should not reach here — Windows uses zip
      throw new RuntimeException("tar.gz not supported on Windows")
    val pb = new ProcessBuilder("tar", "xzf", archive.getAbsolutePath, "--to-stdout", "--wildcards", s"*$targetName")
    pb.directory(nebflowBinDir)
    pb.redirectErrorStream(true)
    val proc = pb.start()
    val out = new FileOutputStream(dest)
    try
      val buf = new Array[Byte](8192)
      var read = proc.getInputStream.read(buf)
      while read != -1 do
        out.write(buf, 0, read)
        read = proc.getInputStream.read(buf)
    finally out.close()
    val exitCode = proc.waitFor()
    if exitCode != 0 then
      dest.delete()
      throw new RuntimeException(s"tar extraction failed with exit code $exitCode")

  end extractTarGzUsingTar

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
   * Auto-installs rg if not found.
   */
  def runRg(args: List[String], workDir: String): Either[ToolError, (String, String, Int)] =
    resolveRgPath match
      case Left(err) =>
        Left(ToolError(s"Ripgrep unavailable: $err. Install manually: https://github.com/BurntSushi/ripgrep"))
      case Right(rgPath) =>
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
