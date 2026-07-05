package nebflow.cli

import cats.effect.IO
import io.circe.syntax.given
import nebflow.core.PathUtil
import nebflow.core.RestartHelper

import java.io.File
import scala.sys.process.*

object AutoStartCommand extends CliCommand:
  def name = "autostart"
  def description = "Enable or disable auto-start on boot"
  def subcommands = List(AutoStartEnable, AutoStartDisable, AutoStartStatus)
  def examples = List("nebflow autostart enable", "nebflow autostart disable", "nebflow autostart status")

  // macOS LaunchAgent plist path
  private def launchAgentPlist: os.Path =
    os.home / "Library" / "LaunchAgents" / s"${RestartHelper.LaunchAgentLabel}.plist"

  // Windows scheduled task name
  private val winTaskName = RestartHelper.WinTaskName

  private object AutoStartEnable extends CliSubcommand:
    def name = "enable"
    def description = "Enable auto-start on boot"
    def params = Nil

    def run(ctx: CliContext): IO[CliResult] =
      IO.blocking {
        val osName = sys.props.getOrElse("os.name", "").toLowerCase
        if osName.contains("mac") then enableMacOS()
        else if osName.contains("win") then enableWindows()
        else enableLinux()
      }

  private object AutoStartDisable extends CliSubcommand:
    def name = "disable"
    def description = "Disable auto-start on boot"
    def params = Nil

    def run(ctx: CliContext): IO[CliResult] =
      IO.blocking {
        val osName = sys.props.getOrElse("os.name", "").toLowerCase
        if osName.contains("mac") then disableMacOS()
        else if osName.contains("win") then disableWindows()
        else disableLinux()
      }

  private object AutoStartStatus extends CliSubcommand:
    def name = "status"
    def description = "Check if auto-start is enabled"
    def params = Nil

    def run(ctx: CliContext): IO[CliResult] =
      IO.blocking {
        val osName = sys.props.getOrElse("os.name", "").toLowerCase
        val enabled =
          if osName.contains("mac") then os.exists(launchAgentPlist)
          else if osName.contains("win") then checkWindowsTask()
          else os.exists(linuxAutostartFile)
        if ctx.json then CliResult.Json(io.circe.Json.obj("enabled" -> enabled.asJson))
        else
          CliResult.text(
            if enabled then "Auto-start: enabled"
            else "Auto-start: disabled"
          )
      }
  end AutoStartStatus

  // ===== macOS (LaunchAgent) =====

  private def enableMacOS(): CliResult =
    val javaBin = RestartHelper.resolveJavaBin()
    val jarPath = RestartHelper.resolveJarPath()
    jarPath match
      case None =>
        CliResult.Error("Cannot determine JAR path — run 'nebflow autostart enable' from a running Nebflow instance")
      case Some(jar) =>
        val logsDir = PathUtil.dataRoot / "logs"
        if !os.exists(logsDir) then os.makeDir.all(logsDir)
        val logPath = (logsDir / "autostart.log").toString

        val plist = s"""<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>${RestartHelper.LaunchAgentLabel}</string>
    <key>ProgramArguments</key>
    <array>
        <string>$javaBin</string>
        <string>--add-opens</string>
        <string>java.base/java.lang=ALL-UNNAMED</string>
        <string>-jar</string>
        <string>$jar</string>
        <string>start</string>
        <string>--no-browser</string>
    </array>
    <key>RunAtLoad</key>
    <true/>
    <key>StandardOutPath</key>
    <string>$logPath</string>
    <key>StandardErrorPath</key>
    <string>$logPath</string>
    <key>WorkingDirectory</key>
    <string>${sys.props("user.home")}</string>
</dict>
</plist>
"""
        val plistDir = launchAgentPlist / os.up
        if !os.exists(plistDir) then os.makeDir.all(plistDir)
        os.write.over(launchAgentPlist, plist)
        // Unload if already loaded, then load
        try s"launchctl unload ${launchAgentPlist}".!
        catch case _: Exception => ()
        try s"launchctl load ${launchAgentPlist}".!
        catch case _: Exception => ()
        CliResult.text(
          "Auto-start enabled (macOS LaunchAgent)",
          s"  Plist: ${launchAgentPlist}",
          "  Nebflow will start automatically on login.",
          s"  Logs: $logPath"
        )

    end match

  end enableMacOS

  private def disableMacOS(): CliResult =
    if os.exists(launchAgentPlist) then
      try s"launchctl unload ${launchAgentPlist}".!
      catch case _: Exception => ()
      os.remove(launchAgentPlist)
      CliResult.text("Auto-start disabled (LaunchAgent removed)")
    else CliResult.text("Auto-start was not enabled")

  // ===== Windows (Scheduled Task) =====

  private def enableWindows(): CliResult =
    val javaBin = RestartHelper.resolveJavaBin()
    val jarPath = RestartHelper.resolveJarPath()
    jarPath match
      case None => CliResult.Error("Cannot determine JAR path")
      case Some(jar) =>
        val logsDir = PathUtil.dataRoot / "logs"
        if !os.exists(logsDir) then os.makeDir.all(logsDir)
        // Command for scheduled task
        val cmd = s""""$javaBin" --add-opens java.base/java.lang=ALL-UNNAMED -jar "$jar" start --no-browser"""
        val createCmd = Seq(
          "schtasks",
          "/create",
          "/tn",
          winTaskName,
          "/tr",
          cmd,
          "/sc",
          "onlogon",
          "/rl",
          "highest",
          "/f"
        )
        val exitCode = createCmd.!
        if exitCode == 0 then
          CliResult.text(
            "Auto-start enabled (Windows Scheduled Task)",
            s"  Task: $winTaskName",
            "  Nebflow will start automatically on login."
          )
        else CliResult.Error(s"Failed to create scheduled task (exit code: $exitCode)")

    end match

  end enableWindows

  private def disableWindows(): CliResult =
    val deleteCmd = Seq("schtasks", "/delete", "/tn", winTaskName, "/f")
    val exitCode =
      try deleteCmd.!
      catch case _: Exception => 1
    if exitCode == 0 then CliResult.text("Auto-start disabled (scheduled task removed)")
    else CliResult.text("Auto-start was not enabled")

  private def checkWindowsTask(): Boolean =
    try
      val output = Seq("schtasks", "/query", "/tn", winTaskName).!!
      output.nonEmpty
    catch case _: Exception => false

  // ===== Linux (systemd user service) =====

  private def linuxAutostartFile: os.Path =
    os.home / ".config" / "autostart" / "nebflow.desktop"

  private def enableLinux(): CliResult =
    val javaBin = RestartHelper.resolveJavaBin()
    val jarPath = RestartHelper.resolveJarPath()
    jarPath match
      case None => CliResult.Error("Cannot determine JAR path")
      case Some(jar) =>
        val desktopFile = s"""[Desktop Entry]
Type=Application
Name=Nebflow Gateway
Exec=$javaBin --add-opens java.base/java.lang=ALL-UNNAMED -jar '$jar' start --no-browser
Hidden=false
NoDisplay=false
X-GNOME-Autostart-enabled=true
"""
        val dir = linuxAutostartFile / os.up
        if !os.exists(dir) then os.makeDir.all(dir)
        os.write.over(linuxAutostartFile, desktopFile)
        CliResult.text(
          "Auto-start enabled (XDG autostart)",
          s"  File: ${linuxAutostartFile}",
          "  Nebflow will start automatically on login."
        )

    end match

  end enableLinux

  private def disableLinux(): CliResult =
    if os.exists(linuxAutostartFile) then
      os.remove(linuxAutostartFile)
      CliResult.text("Auto-start disabled (autostart entry removed)")
    else CliResult.text("Auto-start was not enabled")

end AutoStartCommand
