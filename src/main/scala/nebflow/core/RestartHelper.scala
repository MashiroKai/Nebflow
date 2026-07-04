package nebflow.core

import nebflow.core.NebflowLogger

import java.io.File

/**
 * Cross-platform restart helper for remote updates.
 *
 * When triggered, spawns a detached background process that:
 * 1. Waits `delaySeconds` for the current JVM to exit
 * 2. Starts a fresh Nebflow instance with the updated JAR
 *
 * The current JVM should call `scheduleExit()` immediately after,
 * so the old instance shuts down cleanly before the new one starts.
 */
object RestartHelper:
  private val logger = NebflowLogger.forName("nebflow.restart")

  /** Label / task name used by auto-start configuration. */
  val LaunchAgentLabel = "com.nebflow.gateway"
  val WinTaskName = "NebflowAutoStart"

  /**
   * Spawn a detached process that waits, then starts Nebflow.
   * Call this BEFORE exiting the current JVM.
   *
   * @param delaySeconds how long to wait before starting (must be > shutdown time)
   */
  def spawnRestart(delaySeconds: Int = 3): Unit =
    val isWindows = sys.props.getOrElse("os.name", "").toLowerCase.contains("win")
    val javaBin = resolveJavaBin()
    val jarPath = resolveJarPath()
    val addOpens = "--add-opens java.base/java.lang=ALL-UNNAMED"

    if jarPath.isEmpty then
      logger.warn("Cannot restart: JAR path not found")
      return

    val jar = jarPath.get
    if isWindows then
      // Windows: cmd /c "timeout & java -jar ..."
      val cmd = s"""cmd /c "timeout /t $delaySeconds /nobreak >NUL & \\"$javaBin\\" $addOpens -jar \\"$jar\\" start --no-browser" """
      logger.info(s"Spawning restart helper: $cmd")
      try
        val pb = new ProcessBuilder("cmd", "/c",
          s"timeout /t $delaySeconds /nobreak >NUL & \"$javaBin\" $addOpens -jar \"$jar\" start --no-browser"
        )
        pb.directory(new File(sys.props("user.home")))
        ensureLogsDir()
        val logFile = new File(new File(PathUtil.dataRoot.toString, "logs"), "restart.log")
        pb.redirectOutput(logFile)
        pb.redirectError(logFile)
        pb.start()
      catch case e: Exception => logger.warn(s"Failed to spawn restart helper: ${e.getMessage}")
    else
      // Unix: bash -c "sleep N && exec java -jar ..."
      val script = s"sleep $delaySeconds && exec '$javaBin' $addOpens -jar '$jar' start --no-browser"
      logger.info(s"Spawning restart helper: bash -c '$script'")
      try
        val pb = new ProcessBuilder("bash", "-c", script)
        pb.directory(new File(sys.props("user.home")))
        ensureLogsDir()
        val logFile = new File(new File(PathUtil.dataRoot.toString, "logs"), "restart.log")
        pb.redirectOutput(logFile)
        pb.redirectError(logFile)
        pb.start()
      catch case e: Exception => logger.warn(s"Failed to spawn restart helper: ${e.getMessage}")
    end if
  end spawnRestart

  /** Resolve the absolute path to the java executable. */
  def resolveJavaBin(): String =
    val isWindows = sys.props.getOrElse("os.name", "").toLowerCase.contains("win")
    val javaHome = sys.props.getOrElse("java.home", "")
    val exe = if isWindows then "java.exe" else "java"
    val path = s"$javaHome/bin/$exe"
    if new File(path).exists() then path
    else "java" // fallback to PATH

  /** Resolve the absolute path to the running JAR (from java.class.path). */
  def resolveJarPath(): Option[String] =
    val cp = sys.props.getOrElse("java.class.path", "")
    cp.split(File.pathSeparatorChar)
      .find(p => p.endsWith(".jar") && p.toLowerCase.contains("nebflow"))
      .orElse(cp.split(File.pathSeparatorChar).find(_.endsWith(".jar")))
      .map(p => new File(p).getAbsolutePath)

  private def ensureLogsDir(): Unit =
    val logsDir = new File(PathUtil.dataRoot.toString, "logs")
    if !logsDir.exists() then logsDir.mkdirs()

end RestartHelper
