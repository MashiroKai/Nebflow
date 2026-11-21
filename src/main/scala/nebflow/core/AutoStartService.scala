package nebflow.core

import cats.effect.IO
import nebflow.core.root.{RootLadder, RootLayer}
import nebflow.shared.{Branding, PathUtil}

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
   * look like a Stand distribution: a jar whose file name carries the brand
   * (`Branding.lowerName`, i.e. `stand-assembly-*.jar` today; legacy
   * `nebflow-assembly-*` checkouts dual-accepted — same contract as the
   * installers' dual-glob) or any jar inside a jpackage `.app/Contents`
   * bundle. RestartHelper.resolveJarPath's generic fallback (first jar on the
   * classpath) would return scala-library.jar under sbt run — that must be
   * filtered out here, not written into a plist.
   *
   * 重启窗热修批 2026-10-18: the bare `fileName.contains("nebflow")` filter
   * went blind under the stand artifact (stand-assembly-<v>.jar carries no
   * "nebflow") ⇒ packaged-mode detection missed and auto-start degraded.
   */
  def resolveRunJar(): Option[String] =
    RestartHelper.resolveJarPath().filter(isDistributionJar)

  /** Pure core of [[resolveRunJar]]'s distribution-shape filter (spec-facing). */
  def isDistributionJar(jarPath: String): Boolean =
    val fileName = jarPath.substring(math.max(jarPath.lastIndexOf('/'), jarPath.lastIndexOf('\\')) + 1).toLowerCase
    fileName.contains(Branding.lowerName.toLowerCase) || fileName.contains("nebflow") || jarPath.contains(
      ".app/Contents"
    )

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
   *
   * 三层数据根批（B1，契约 `design-api.md` §8.2 / A12）：`accountHome = Some(<账号根>)` ⇒
   * **尾部追加** `--home <账号根>`（作者裁定逐字：autostart 启动命令带账号根）；`None` ⇒ 逐字节
   * 等于今日输出（老 plist / 未登录 autostart 一个字节都不变 —— `AutoStartServiceSpec` 的钉死
   * 断言不受本次加参影响）。`--home` 同时接受机器根与账号根，由阶梯第 0 档（`profile.json`
   * 在场）判别，故**不新增旗标**。
   */
  def programArguments(
    javaBin: String,
    jarPath: String,
    accountHome: Option[os.Path] = None,
    port: Option[Int] = None
  ): List[String] =
    val base = bundleExecutable(jarPath) match
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
    val withHome = accountHome match
      case None => base
      case Some(home) => base ++ List("--home", home.toString)
    port match
      case None => withHome
      case Some(p) => withHome ++ List("--port", p.toString)

  end programArguments

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

  end renderPlist

  /**
   * Render the Linux XDG autostart .desktop entry (pure; jar form only).
   *
   * `accountHome = Some(<账号根>)` ⇒ `Exec=` 行尾部追加 `--home '<账号根>'`（契约 §8.2：三平台
   * 产物同规）；`None` ⇒ 逐字节等于今日（老产物 / 未登录 autostart 不变）。
   */
  def renderDesktopEntry(
    javaBin: String,
    jarPath: String,
    accountHome: Option[os.Path] = None,
    port: Option[Int] = None
  ): String =
    val execLine =
      s"Exec=${shellQuote(javaBin)} --add-opens java.base/java.lang=ALL-UNNAMED -jar ${shellQuote(jarPath)} start --no-browser" +
        accountHome.map(h => s" --home ${shellQuote(h.toString)}").getOrElse("") +
        port.map(p => s" --port $p").getOrElse("")
    s"""[Desktop Entry]
Type=Application
Name=${nebflow.shared.Branding.productName} Gateway
$execLine
Hidden=false
NoDisplay=false
X-GNOME-Autostart-enabled=true
"""

  end renderDesktopEntry

  /**
   * Render the Windows `schtasks /tr` command string (pure).
   *
   * 契约 §8.2 只写了「`enableWindows` 的 `schtasks /tr` cmd 同步追加」——今日该 cmd 是私有分支里的
   * 内联字符串，无法作纯函数断言。本函数把它提出来：`enableWindows` 改为调用它（行为不变），
   * 平台不可用侧（本机 macOS）也能用纯渲染断言 V6 的 `--home <账号根>` 形态。
   * `None` ⇒ **逐字节**等于今日内联串（A12 后半）。
   */
  def renderWindowsTaskCmd(
    javaBin: String,
    jarPath: String,
    accountHome: Option[os.Path] = None,
    port: Option[Int] = None
  ): String =
    val base =
      s""""$javaBin" --add-opens java.base/java.lang=ALL-UNNAMED -jar "$jarPath" start --no-browser"""
    val withHome = accountHome match
      case None => base
      case Some(home) => base + " --home \"" + home.toString + "\""
    port match
      case None => withHome
      case Some(p) => withHome + s" --port $p"

  private def xmlEscape(s: String): String =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

  private def shellQuote(s: String): String = s"'$s'"

  // ===== paths =====

  /**
   * 开机自启的诊断日志目录 —— **机器层**（契约 §8.2/§9.1：autostart.log 是生命周期日志，
   * 账号根不可解析时也必须可写）。平铺档下 `machineRoot == dataRoot` ⇒ 字节不变。
   */
  private def logsDir: os.Path = PathUtil.machineRoot / "logs"

  /** 账号档时 autostart 命令要带的 `--home <账号根>`；平铺/未登录档不追加（老产物不变）。 */
  private def accountHomeIfAny: Option[os.Path] =
    RootLadder.current match
      case RootLayer.Account(_, _, root) => Some(root)
      case _ => None

  /**
   * 开机自启必须钉死的两个事实（2026-11-21 跨机 E2E 缺陷 ⑤）：
   *
   * `accountHomeIfAny` 只覆盖**账号档**——平铺档/未登录档返回 `None`，于是隔离实例
   * （`--home <隔离根>` 起出来的引擎）执行 `stand autostart enable` 时生成的计划任务
   * **不带任何 home**，登录自启会静默去起**默认数据根**那一份。隔离实例最要紧的属性
   * 就是「绝不碰默认根」，这条腿恰好反过来。
   *
   * 故自启命令一律钉**本进程解析出的机器层根**（隔离实例 = 它的隔离根；默认实例 =
   * 默认根 ⇒ 与今日字节相同，不引入回归）与**本实例实际监听的端口**（同样不能靠
   * 缺省口回落：隔离实例的缺省口会被评成品牌缺省 6138，那正是生产口）。
   */
  private def autostartHome: Option[os.Path] = Some(PathUtil.machineRoot)

  /** 端口：与 CLI `GatewayClient.readPort` / 网关 `GatewayConfig` 同一**单一实现**
   *  `InstanceScope.decide`（不另抄一份判定链）。隔离根拿不到合法端口（Refused）⇒
   *  `None`，命令里就不写 `--port`，由引擎自己按同一链再判一次（绝不猜缺省口）。 */
  private def autostartPort: Option[Int] =
    nebflow.shared.InstanceScope.decide(
      nebflow.shared.InstanceScope.PortInputs(
        // 自启腿不接 CLI 的 `--port` 覆盖（该快照 private[cli]）：真正要钉住的是
        // 「这个 home 该用哪个口」，隔离实例把它写在 config 的 port 键里即可。
        cliPort = None,
        envPortName = s"${nebflow.shared.Branding.envPrefix}_GATEWAY_PORT",
        envPort = nebflow.shared.Branding.env("GATEWAY_PORT"),
        configPort = nebflow.gateway.GatewayConfig.configFilePort(),
        sourceName = nebflow.shared.Branding.configFileName,
        brandDefaultPort = nebflow.shared.Defaults.brandDefaultPort,
        root = PathUtil.dataRoot,
        overridden = nebflow.shared.InstanceScope.liveCaller.overridden
      )
    ) match
      case nebflow.shared.InstanceScope.PortVerdict.Resolved(p, _, _) => Some(p)
      case nebflow.shared.InstanceScope.PortVerdict.Refused(_) => None

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
      case "mac" => os.exists(launchAgentPlist)
      case "win" => checkWindowsTask()
      case _ => os.exists(linuxAutostartFile)
    val reason =
      if supported then None
      else Some(s"Development mode (sbt run) — auto-start needs a packaged ${nebflow.shared.Branding.productName} JAR")
    Status(enabled, supported, reason)
  }

  def enable(): IO[OpResult] = IO.blocking {
    val runJar = resolveRunJar()
    runJar match
      case None =>
        OpResult(
          ok = false,
          s"Cannot determine ${nebflow.shared.Branding.productName} JAR path — auto-start is only available from a packaged instance"
        )
      case Some(jar) =>
        osName match
          case "mac" => enableMacOS(jar)
          case "win" => enableWindows(jar)
          case _ => enableLinux(jar)
  }

  def disable(): IO[OpResult] = IO.blocking {
    osName match
      case "mac" => disableMacOS()
      case "win" => disableWindows()
      case _ => disableLinux()
  }

  // ===== macOS =====

  private def enableMacOS(jar: String): OpResult =
    val javaBin = RestartHelper.resolveJavaBin()
    val dir = logsDir
    if !os.exists(dir) then os.makeDir.all(dir)
    val logPath = (dir / "autostart.log").toString
    val args = programArguments(javaBin, jar, autostartHome, autostartPort)
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
      List(
        s"  Plist: ${launchAgentPlist}",
        s"  Form: $form",
        s"  ${nebflow.shared.Branding.productName} will start automatically on login.",
        s"  Logs: $logPath"
      )
    )

  end enableMacOS

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
    val dir = logsDir
    if !os.exists(dir) then os.makeDir.all(dir)
    val cmd = renderWindowsTaskCmd(javaBin, jar, autostartHome, autostartPort)
    // `/tr` 是**纯字符串**接口：命令行里给再多引号，schtasks 解析 /tr 时会剥掉一层，
    // 于是含空格的 java 路径（%ProgramFiles%\Eclipse Adoptium\…\java.exe —— 本机
    // Windows 的默认装法）被按空白切开 ⇒ 任务建不出来（实测 exit -2147467259 /
    // "SCHTASKS /CREATE /?"；2026-11-21 跨机 E2E 缺陷 ④）。detached-apply 面早已因
    // 同一根因改走 XML（DetachedApply.registerWindows 注释逐字写明「会剥掉内层引号
    // （带空格路径即碎）」），这里复用同一手法：XML 逐字面存参 + 显式关断电池闸。
    val xmlPath = dir / s"${RestartHelper.WinTaskName}.xml"
    val writeXml = try
      writeWindowsTaskXml(xmlPath, renderWindowsTaskXml(cmd))
      Right(())
    catch case e: Exception => Left(Option(e.getMessage).getOrElse(e.toString))
    writeXml match
      case Left(err) => OpResult(ok = false, s"Failed to write task XML (${xmlPath}): $err")
      case Right(_) =>
        val createCmd =
          Seq("schtasks", "/create", "/tn", RestartHelper.WinTaskName, "/xml", xmlPath.toString, "/f")
        val exitCode = createCmd.!
        if exitCode == 0 then
          OpResult(
            ok = true,
            "Auto-start enabled (Windows Scheduled Task)",
            List(
              s"  Task: ${RestartHelper.WinTaskName}",
              s"  ${nebflow.shared.Branding.productName} will start automatically on login."
            )
          )
        else OpResult(ok = false, s"Failed to create scheduled task (exit code: $exitCode)")

  end enableWindows

  /**
   * 计划任务 XML（`schtasks /create /xml`）—— 唯一能**逐字面**保存带空格命令行的
   * Windows 面（`/tr` 会剥引号）。UTF-16LE + BOM：schtasks 只认它自己写出的 LE 形态，
   * Java 的 `UTF_16` 默认 BE BOM ⇒ 直接判 malformed（同 DetachedApply 的实测结论）。
   *
   * 纯函数、平台无关 ⇒ 本机（macOS）也能直测 `/tr` 面最易碎的那一段。
   */
  def renderWindowsTaskXml(cmd: String, psFull: String = AutoStartService.defaultPowershell): String =
    val args = ("-NoProfile -ExecutionPolicy Bypass -Command \"" + xmlEscape(cmd) + "\"")
    s"""<?xml version="1.0" encoding="UTF-16"?>
       |<Task version="1.2" xmlns="http://schemas.microsoft.com/windows/2004/02/mit/task">
       |  <Principals>
       |    <Principal id="Author">
       |      <LogonType>InteractiveToken</LogonType>
       |    </Principal>
       |  </Principals>
       |  <Settings>
       |    <DisallowStartIfOnBatteries>false</DisallowStartIfOnBatteries>
       |    <StopIfGoingOnBatteries>false</StopIfGoingOnBatteries>
       |    <MultipleInstancesPolicy>IgnoreNew</MultipleInstancesPolicy>
       |    <ExecutionTimeLimit>PT0S</ExecutionTimeLimit>
       |  </Settings>
       |  <Triggers>
       |    <LogonTrigger>
       |      <Enabled>true</Enabled>
       |    </LogonTrigger>
       |  </Triggers>
       |  <Actions Context="Author">
       |    <Exec>
       |      <Command>${xmlEscape(psFull)}</Command>
       |      <Arguments>$args</Arguments>
       |    </Exec>
       |  </Actions>
       |</Task>
       |""".stripMargin

  /** PowerShell 全路径（任务计划服务上下文里裸 `powershell.exe` 解析不到 ⇒ 0x80070002 假成功）。 */
  private def defaultPowershell: String =
    val root = sys.env.get("SystemRoot").orElse(sys.env.get("SYSTEMROOT")).getOrElse("C:\\Windows")
    s"$root\\System32\\WindowsPowerShell\\v1.0\\powershell.exe"

  /** 跑步点：XML 文件落位（UTF-16LE + BOM）。 */
  private def writeWindowsTaskXml(path: os.Path, xml: String): Unit =
    val le = xml.getBytes(java.nio.charset.StandardCharsets.UTF_16LE)
    os.write.over(path, Array(0xff.toByte, 0xfe.toByte) ++ le, createFolders = true)

  private def disableWindows(): OpResult =
    val deleteCmd = Seq("schtasks", "/delete", "/tn", RestartHelper.WinTaskName, "/f")
    val exitCode =
      try deleteCmd.!
      catch case _: Exception => 1
    if exitCode == 0 then OpResult(ok = true, "Auto-start disabled (scheduled task removed)")
    else OpResult(ok = true, "Auto-start was not enabled")

  private def checkWindowsTask(): Boolean =
    try Seq("schtasks", "/query", "/tn", RestartHelper.WinTaskName).!!.nonEmpty
    catch case _: Exception => false

  // ===== Linux =====

  private def enableLinux(jar: String): OpResult =
    val javaBin = RestartHelper.resolveJavaBin()
    val desktop = renderDesktopEntry(javaBin, jar, autostartHome, autostartPort)
    val dir = linuxAutostartFile / os.up
    if !os.exists(dir) then os.makeDir.all(dir)
    os.write.over(linuxAutostartFile, desktop)
    OpResult(
      ok = true,
      "Auto-start enabled (XDG autostart)",
      List(
        s"  File: ${linuxAutostartFile}",
        s"  ${nebflow.shared.Branding.productName} will start automatically on login."
      )
    )

  private def disableLinux(): OpResult =
    if os.exists(linuxAutostartFile) then
      os.remove(linuxAutostartFile)
      OpResult(ok = true, "Auto-start disabled (autostart entry removed)")
    else OpResult(ok = true, "Auto-start was not enabled")

end AutoStartService
