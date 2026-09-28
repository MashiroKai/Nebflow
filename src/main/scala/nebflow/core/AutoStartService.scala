package nebflow.core

import cats.effect.IO

import scala.sys.process.*

/**
 * Shared auto-start (start-on-login) logic for the `nebflow autostart` CLI
 * and the settings-panel WS toggle (F2). Extracted from cli/AutoStartCommand
 * so both entry points drive the exact same code path.
 *
 * Run-form detection: when the running JAR sits inside a jpackage app bundle
 * (macOS `.app/Contents`), the LaunchAgent plist must point at the bundle
 * executable directly (no `java -jar` wrapper — the bundle executable already
 * carries the launch config baked by jpackage). Otherwise the classic
 * `java --add-opens ... -jar <jar> start --no-browser` template applies.
 *
 * `supported=false` when no trustworthy packaged JAR can be located
 * (e.g. sbt run / test classpaths): writing a plist that points at a
 * classpath entry would fail silently at next login. See [[resolveRunJar]].
 */
object AutoStartService:

  /** Settings-panel / CLI view of auto-start state. */
  final case class Status(enabled: Boolean, supported: Boolean, reason: Option[String])

  /** One operation (enable/disable) outcome. */
  final case class OpResult(ok: Boolean, message: String, detail: List[String] = Nil)

  // ===== run-form resolution =====

  /**
   * Locate the running JAR, but only trust classpath entries that actually
   * look like a Nebflow distribution: a jar whose file name contains
   * "nebflow" (fat jar) or any jar inside a jpackage `.app/Contents` bundle.
   * RestartHelper.resolveJarPath's generic fallback (first jar on the
   * classpath) would return scala-library.jar under sbt run — that must be
   * filtered out here, not written into a plist.
   */
  def resolveRunJar(): Option[String] =
    RestartHelper.resolveJarPath().filter { p =>
      val fileName = p.substring(math.max(p.lastIndexOf('/'), p.lastIndexOf('\\')) + 1).toLowerCase
      fileName.contains("nebflow") || p.contains(".app/Contents")
    }

  // ===== pure helpers (unit-testable) =====

  /** macOS jpackage app-bundle layout detector. */
  def isBundledApp(jarPath: String): Boolean = jarPath.contains(".app/Contents")

  /**
   * Bundle executable path for a jpackage layout: `<...>/<Name>.app/Contents/MacOS/<Name>`.
   * Returns None when the path does not sit inside an app bundle.
   */
  def bundleExecutable(jarPath: String): Option[String] =
    val idx = jarPath.indexOf(".app/Contents")
    if idx < 0 then None
    else
      val appRoot = jarPath.substring(0, idx + 4) // includes ".app"
      val appName = appRoot.substring(appRoot.lastIndexOf('/') + 1, appRoot.length - 4)
      Some(s"$appRoot/Contents/MacOS/$appName")

  /**
   * LaunchAgent ProgramArguments for the detected run form.
   * Bundled app: `[<bundle-executable>, start, --no-browser]`.
   * Jar form: `[<javaBin>, --add-opens, java.base/java.lang=ALL-UNNAMED, -jar, <jar>, start, --no-browser]`.
   */
  def programArguments(javaBin: String, jarPath: String): List[String] =
    bundleExecutable(jarPath) match
      case Some(exe) => List(exe, "start", "--no-browser")
      case None =>
        List(
          javaBin,
          "--add-opens",
          "java.base/java.lang=ALL-UNNAMED",
          "-jar",
          jarPath,
          "start",
          "--no-browser"
        )

  /** Render the macOS LaunchAgent plist (pure). */
  def renderPlist(label: String, args: List[String], logPath: String, workingDir: String): String =
    val argsXml = args.map(a => s"        <string>${xmlEscape(a)}</string>").mkString("\n")
    s"""<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>${xmlEscape(label)}</string>
    <key>ProgramArguments</key>
    <array>
$argsXml
    </array>
    <key>RunAtLoad</key>
    <true/>
    <key>StandardOutPath</key>
    <string>${xmlEscape(logPath)}</string>
    <key>StandardErrorPath</key>
    <string>${xmlEscape(logPath)}</string>
    <key>WorkingDirectory</key>
    <string>${xmlEscape(workingDir)}</string>
</dict>
</plist>
"""

  /** Render the Linux XDG autostart .desktop entry (pure; jar form only). */
  def renderDesktopEntry(javaBin: String, jarPath: String): String =
    s"""[Desktop Entry]
Type=Application
Name=Nebflow Gateway
Exec=${shellQuote(javaBin)} --add-opens java.base/java.lang=ALL-UNNAMED -jar ${shellQuote(jarPath)} start --no-browser
Hidden=false
NoDisplay=false
X-GNOME-Autostart-enabled=true
"""

  private def xmlEscape(s: String): String =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

  private def shellQuote(s: String): String = s"'$s'"

  // ===== paths =====

  def launchAgentPlist: os.Path =
    os.home / "Library" / "LaunchAgents" / s"${RestartHelper.LaunchAgentLabel}.plist"

  def linuxAutostartFile: os.Path = os.home / ".config" / "autostart" / "nebflow.desktop"

  private def osName: String =
    val n = sys.props.getOrElse("os.name", "").toLowerCase
    if n.contains("mac") then "mac"
    else if n.contains("win") then "win"
    else "linux"

  // ===== effects =====

  def status(): IO[Status] = IO.blocking {
    val runJar = resolveRunJar()
    val supported = runJar.isDefined
    val enabled = osName match
      case "mac"  => os.exists(launchAgentPlist)
      case "win"  => checkWindowsTask()
      case _      => os.exists(linuxAutostartFile)
    val reason =
      if supported then None
      else Some("Development mode (sbt run) — auto-start needs a packaged Nebflow JAR")
    Status(enabled, supported, reason)
  }

  def enable(): IO[OpResult] = IO.blocking {
    val runJar = resolveRunJar()
    runJar match
      case None =>
        OpResult(ok = false, "Cannot determine Nebflow JAR path — auto-start is only available from a packaged instance")
      case Some(jar) =>
        osName match
          case "mac"  => enableMacOS(jar)
          case "win"  => enableWindows(jar)
          case _      => enableLinux(jar)
  }

  def disable(): IO[OpResult] = IO.blocking {
    osName match
      case "mac"  => disableMacOS()
      case "win"  => disableWindows()
      case _      => disableLinux()
  }

  // ===== macOS =====

  private def enableMacOS(jar: String): OpResult =
    val javaBin = RestartHelper.resolveJavaBin()
    val logsDir = PathUtil.dataRoot / "logs"
    if !os.exists(logsDir) then os.makeDir.all(logsDir)
    val logPath = (logsDir / "autostart.log").toString
    val args = programArguments(javaBin, jar)
    val form = if isBundledApp(jar) then "app bundle executable" else "java -jar"
    val plist = renderPlist(RestartHelper.LaunchAgentLabel, args, logPath, sys.props("user.home"))
    val plistDir = launchAgentPlist / os.up
    if !os.exists(plistDir) then os.makeDir.all(plistDir)
    os.write.over(launchAgentPlist, plist)
    try s"launchctl unload ${launchAgentPlist}".!
    catch case _: Exception => ()
    try s"launchctl load ${launchAgentPlist}".!
    catch case _: Exception => ()
    OpResult(
      ok = true,
      "Auto-start enabled (macOS LaunchAgent)",
      List(s"  Plist: ${launchAgentPlist}", s"  Form: $form", "  Nebflow will start automatically on login.", s"  Logs: $logPath")
    )

  private def disableMacOS(): OpResult =
    if os.exists(launchAgentPlist) then
      try s"launchctl unload ${launchAgentPlist}".!
      catch case _: Exception => ()
      os.remove(launchAgentPlist)
      OpResult(ok = true, "Auto-start disabled (LaunchAgent removed)")
    else OpResult(ok = true, "Auto-start was not enabled")

  // ===== Windows =====

  private def enableWindows(jar: String): OpResult =
    val javaBin = RestartHelper.resolveJavaBin()
    val logsDir = PathUtil.dataRoot / "logs"
    if !os.exists(logsDir) then os.makeDir.all(logsDir)
    val cmd = s""""$javaBin" --add-opens java.base/java.lang=ALL-UNNAMED -jar "$jar" start --no-browser"""
    val createCmd = Seq("schtasks", "/create", "/tn", RestartHelper.WinTaskName, "/tr", cmd, "/sc", "onlogon", "/rl", "highest", "/f")
    val exitCode = createCmd.!
    if exitCode == 0 then
      OpResult(
        ok = true,
        "Auto-start enabled (Windows Scheduled Task)",
        List(s"  Task: ${RestartHelper.WinTaskName}", "  Nebflow will start automatically on login.")
      )
    else OpResult(ok = false, s"Failed to create scheduled task (exit code: $exitCode)")

  private def disableWindows(): OpResult =
    val deleteCmd = Seq("schtasks", "/delete", "/tn", RestartHelper.WinTaskName, "/f")
    val exitCode = try deleteCmd.!
    catch case _: Exception => 1
    if exitCode == 0 then OpResult(ok = true, "Auto-start disabled (scheduled task removed)")
    else OpResult(ok = true, "Auto-start was not enabled")

  private def checkWindowsTask(): Boolean =
    try Seq("schtasks", "/query", "/tn", RestartHelper.WinTaskName).!!.nonEmpty
    catch case _: Exception => false

  // ===== Linux =====

  private def enableLinux(jar: String): OpResult =
    val javaBin = RestartHelper.resolveJavaBin()
    val desktop = renderDesktopEntry(javaBin, jar)
    val dir = linuxAutostartFile / os.up
    if !os.exists(dir) then os.makeDir.all(dir)
    os.write.over(linuxAutostartFile, desktop)
    OpResult(
      ok = true,
      "Auto-start enabled (XDG autostart)",
      List(s"  File: ${linuxAutostartFile}", "  Nebflow will start automatically on login.")
    )

  private def disableLinux(): OpResult =
    if os.exists(linuxAutostartFile) then
      os.remove(linuxAutostartFile)
      OpResult(ok = true, "Auto-start disabled (autostart entry removed)")
    else OpResult(ok = true, "Auto-start was not enabled")

end AutoStartService
