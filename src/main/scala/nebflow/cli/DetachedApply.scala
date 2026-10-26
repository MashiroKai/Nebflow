package nebflow.cli

import cats.effect.IO
import cats.syntax.all.*
import nebflow.core.AtomicJson
import nebflow.shared.{Branding, PathUtil}

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import scala.util.control.NonFatal

/**
 * 跨平台 **detached apply**（台账 #916：自重启/自更新地基，2026-10-26 作者令）。
 *
 * 今天事故为镜（Mac 12:06）：dispatcher 手搓 double-fork+setsid 分离式重启，
 * TERM 旧引擎→等退场→`~/.stand/bin/stand start`——start 腿失效且**零痕迹**
 * （bg 日志 0 行、daemon.out 未动、stand.log 无 boot 线），16 分钟后作者手动
 * sbt 拉起。根因三线合一：① 重启编排活在引擎外的一把临时脚本里（端口知识
 * 陈旧：NEBFLOW_RESTART_PORT=8080，引擎实听 6138）；② 等退场闸探错端口 →
 * start 腿与垂死旧实例赛跑（guard 撞活口 → 假成功/假失败皆不可见）；③ 产物
 * 全落在无人观测的 stdout。且 launchd/schtasks 这类 **OS 级托管**才真正脱离
 * 进程树/会话维度（树杀/会话回收/ssh 掉线全部免役），手搓 fork 永远差一层。
 *
 * 本件把 apply 阶段**内建化**：`stand restart` / `stand update` 的 apply 统一
 * 生成一段带完整事实（端口/版本/launcher 路径/日志位）的脚本，注册给
 * **OS 托管的一次性任务**执行——macOS = launchd 一次性 agent（bootstrap+
 * kickstart，脚本自清理），Windows = 计划任务（schtasks /Create+/Run，
 * 脚本自删除），Linux = 未内置（Open error 拒绝，不假装分离）。任何调用形态
 * （ssh/console/daemon 内/引擎树内）注册后调用方立即可死，apply 活着跑完。
 *
 * 脚本语义（两平台同构，`renderBash` / `renderPowerShell` 唯二实现）：
 *   stop 腿（launcher stop 硬化停机，TERM→KILL 既有语义）→ 端口空闲有界闸
 *   → start 腿（launcher start，jar 形态 daemonize）→ 健康闸（/api/health
 *   200+status=ok，update 再验 version==target）→ done；
 *   失败 → update 回滚（pointer 切回 / winsort 隔离新包）+ 起回旧版 + 报因；
 *   restart 直接报因退出。全程写 `<dataRoot>/logs/apply-restart.log` +
 *   `<dataRoot>/apply/state.json`（原子 tmp+rename，phase 逐相推进）——
 *   **永不无人观测**（今天事故的直接反面）。
 *
 * 中断安全：选取面只有两个崩不坏的锚——pointer 布局 = 指针文件（原子切），
 * winsort 扁平布局 = 目录内「版本号最大者胜」（新包落位即选定）。任何时刻
 * 断电/被杀，重启后非旧版可用即新版可用，绝无中间态；state.json 只是证据，
 * 不承载选取。
 */
object DetachedApply:

  /** apply 面种类：冷重启（restart）/ 更新换装（update，多一层版本断言+回滚腿）。 */
  enum Kind(val wire: String):
    case Restart extends Kind("restart")
    case Update extends Kind("update")

  /** 新包选取面（回滚腿据此分叉）：指针布局 / 目录扁平 winsort 布局。 */
  enum Layout(val wire: String):
    case Pointer extends Layout("pointer")
    case WinsortFlat extends Layout("winsort")

  /** 注册时注入脚本的全部事实（缺一个脚本就是瞎的——今天事故的教训）。 */
  final case class Plan(
    kind: Kind,
    dataRoot: os.Path,
    installDir: os.Path,
    launcher: String,
    port: Int,
    layout: Layout,
    /** update：apply 后健康闸必须应答的版本；restart = None。 */
    targetVersion: Option[String] = None,
    /** 旧实例 pid（有则进日志作证据；脚本以端口为准，不赌 pid 文件新鲜度）。 */
    oldPid: Option[Long] = None,
    /** Windows 计划任务名（固定名 + /F 覆盖；同名并发由 /Run 报错拦下）。 */
    winTask: String = "StandApplyRestart",
    /** macOS launchd label 基名（实际 label 追加 generation 后缀）。 */
    macLabelBase: String = "space.mystand.stand-apply",
    stopDeadlineSec: Int = 90,
    healthDeadlineSec: Int = 180,
    generation: Long = System.currentTimeMillis()
  )

  val ApplyDirName = "apply"
  val StateFileName = "state.json"
  val LogFileName = "apply-restart.log"
  val ScriptNameBash = "apply-restart.sh"
  val ScriptNamePs1 = "apply-restart.ps1"
  /** 终态前的全部相——boot 时发现这些 = 上一个 apply 死在中途（reconcile 报 interrupted）。 */
  val InFlightPhases: Set[String] = Set("registered", "running", "stopped", "starting", "health", "rolling-back")

  def applyDir(dataRoot: os.Path): os.Path = dataRoot / ApplyDirName
  def statePath(dataRoot: os.Path): os.Path = applyDir(dataRoot) / StateFileName
  def logPath(dataRoot: os.Path): os.Path = dataRoot / "logs" / LogFileName

  /** mac launchd label（与 generation 绑定，重装不撞旧尸）。 */
  def macLabel(plan: Plan): String = s"${plan.macLabelBase}-${plan.generation}"

  /** mac plist 落位（与 [[macLabel]] 同一公式——脚本内自清理引用同一路径）。 */
  def macPlist(plan: Plan): os.Path =
    os.Path(sys.props.getOrElse("user.home", "")) / "Library" / "LaunchAgents" / s"${macLabel(plan)}.plist"

  /** 版本/端口等注入脚本的字面必须过字符闸（防 quote 注入进生成脚本）。 */
  def safeToken(s: String): Boolean = s.nonEmpty && s.matches("[A-Za-z0-9._\\-]+")

  // ── 状态落盘（Scala 侧注册时写 registered；脚本侧逐相推进）─────────────

  def renderStateJson(
    kind: String,
    phase: String,
    port: Int,
    targetVersion: Option[String],
    cause: Option[String],
    ts: String
  ): String =
    val tv = targetVersion.map(v => ",\"targetVersion\":\"" + v + "\"").getOrElse("")
    val cs = cause
      .map(c => c.replace("\\", "\\\\").replace("\"", "\\\""))
      .map(c => "\"" + c + "\"")
      .getOrElse("null")
    s"""{"kind":"$kind","phase":"$phase","port":$port$tv,"cause":$cs,"ts":"$ts"}"""

  /** 注册点写 registered 相（脚本随后接管推进；崩溃在注册后立刻发生也有证据）。 */
  def writeRegisteredState(plan: Plan): IO[Unit] =
    IO.blocking(os.makeDir.all(applyDir(plan.dataRoot))) *>
      AtomicJson.write(
        statePath(plan.dataRoot),
        renderStateJson(plan.kind.wire, "registered", plan.port, plan.targetVersion, None, isoNow())
      ) *>
      IO.blocking(os.makeDir.all(logPath(plan.dataRoot) / os.up))

  private def isoNow(): String =
    java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))

  /**
   * boot 对账（只读证据 + 一行 WARN）：发现上一 apply 死在中途 → 改写
   * interrupted 相（AtomicJson，崩不坏）并返回告警行；没有在途相 → None。
   * 引擎**绝不**据 state 自启/自停任何进程——选取面只认 pointer/winsort。
   */
  def reconcileOnBoot(dataRoot: os.Path): IO[Option[String]] =
    AtomicJson.read(statePath(dataRoot)).flatMap {
      case None => IO.pure(None)
      case Some(raw) =>
        io.circe.parser.parse(raw) match
          case Right(json) =>
            val phase = json.hcursor.downField("phase").as[String].getOrElse("")
            if InFlightPhases.contains(phase) then
              val kind = json.hcursor.downField("kind").as[String].getOrElse("?")
              val cause = json.hcursor.downField("cause").as[String].toOption
              val line =
                s"[detached-apply] previous $kind apply died mid-flight (phase=$phase${cause.map(c => s", cause=$c").getOrElse("")}) — selection face (pointer/winsort) is authoritative, this record is evidence only"
              AtomicJson.write(
                statePath(dataRoot),
                renderStateJson(
                  kind,
                  "interrupted",
                  json.hcursor.downField("port").as[Int].getOrElse(0),
                  json.hcursor.downField("targetVersion").as[String].toOption,
                  Some(s"interrupted at phase=$phase"),
                  isoNow()
                )
              ).as(Some(line))
            else IO.pure(None)
          case Left(_) => IO.pure(None) // 坏行不挡 boot；脚本侧下次覆写
    }

  // ── launcher / 安装根解析（两平台生产形态的既定布局）───────────────────

  /** 平台 launcher 解析：posix = `~/.stand/bin/stand`；windows = `%LOCALAPPDATA%\stand\stand.cmd`。 */
  def resolveLauncher(env: Map[String, String], home: String, isWindows: Boolean): Option[String] =
    env.get("STAND_LAUNCHER").map(_.trim).filter(_.nonEmpty).orElse(
      if isWindows then
        env.get("LOCALAPPDATA").map(l => os.Path(l) / "stand" / "stand.cmd").filter(os.exists).map(_.toString)
      else
        Some((os.Path(home) / Branding.homeDirName / "bin" / "stand").toString)
          .filter(p => os.exists(os.Path(p)))
    )

  /** 安装根 = launcher 所在目录（Mac: ~/.stand/bin 指针布局；KAI: AppData 扁平布局）。 */
  def installDirOf(launcher: String): os.Path = os.Path(launcher) / os.up

  /** 布局判别：目录含 current-version 指针 → Pointer；否则扁平 winsort（KAI 现状）。 */
  def detectLayout(installDir: os.Path): Layout =
    if os.exists(installDir / "current-version") then Layout.Pointer else Layout.WinsortFlat

  // ── 注册（OS 托管一次性任务）───────────────────────────────────────────

  /** 命令执行注入口（spec 注入 fake；生产 = ProcessBuilder 直执行，零 shell 解析）。 */
  type Run = List[String] => IO[Either[String, String]]

  /** 生产 runner：argv 直执行（无 shell），stdout+stderr 合并截断回传。 */
  val liveRun: Run = cmd =>
    IO.interruptible {
      val p = new ProcessBuilder(cmd*).redirectErrorStream(true)
      val proc = p.start()
      val out = scala.io.Source.fromInputStream(proc.getInputStream, StandardCharsets.UTF_8.name).mkString
      val code = proc.waitFor()
      if code == 0 then Right(out.take(2000))
      else Left(s"rc=$code: ${out.take(2000)}")
    }.attempt.map {
      case Right(r) => r
      case Left(NonFatal(e)) => Left(s"${e.getClass.getSimpleName}: ${Option(e.getMessage).getOrElse(e.toString)}")
      case Left(e) => Left(e.toString)
    }

  /**
   * 注册 apply 作业。写脚本（tmp+rename）→ 写 registered 相 → OS 注册 →
   * OS 立即触发。返回 Right(一句话确认，含日志位) / Left(原因，引擎零动作)。
   * 注册成功后调用方进程**立即可死**——这正是本件存在的意义。
   * `forcePosix` = spec 注入口（Windows 上验证 launchd 面的注册命令序列）。
   */
  def register(plan: Plan, run: Run = liveRun, forcePosix: Option[Boolean] = None): IO[Either[String, String]] =
    if !safePort(plan.port) then IO.pure(Left(s"invalid port ${plan.port}"))
    else if plan.targetVersion.exists(v => !safeToken(v)) then
      IO.pure(Left(s"invalid target version literal: ${plan.targetVersion.getOrElse("")}"))
    else
      val isWindows = !forcePosix.getOrElse(!sys.props.getOrElse("os.name", "").toLowerCase.contains("win"))
      writeScriptAndState(plan, isWindows).flatMap {
        case Left(err) => IO.pure(Left(err))
        case Right(scriptPath) =>
          if isWindows then registerWindows(plan, scriptPath, run)
          else registerPosix(plan, scriptPath, run)
      }

  private def safePort(p: Int): Boolean = p > 0 && p < 65536

  private def writeScriptAndState(plan: Plan, isWindows: Boolean): IO[Either[String, os.Path]] =
    val dir = applyDir(plan.dataRoot)
    val name = if isWindows then ScriptNamePs1 else ScriptNameBash
    val script = dir / name
    val content = if isWindows then renderPowerShell(plan) else renderBash(plan)
    IO.blocking {
      os.makeDir.all(dir)
      val tmp = dir / s"$name.tmp.${plan.generation}"
      Files.write(tmp.toNIO, content.getBytes(StandardCharsets.UTF_8))
      Files.move(
        tmp.toNIO,
        script.toNIO,
        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
        java.nio.file.StandardCopyOption.ATOMIC_MOVE
      )
      if !isWindows then try os.perms.set(script, "rwxr-xr-x")
      catch case _: Throwable => () // 非 POSIX 文件系统（spec 注入面）尽力即可
      Right(script)
    }.attempt.map {
      case Right(r) => r
      case Left(NonFatal(e)) => Left(s"apply script write failed: ${Option(e.getMessage).getOrElse(e.toString)}")
      case Left(e) => Left(e.toString)
    }

  /** Windows：schtasks /Create /XML（/F 覆盖固定名）→ /Run 立即触发；脚本自删除任务。
    *
    * 为什么走 XML 而不是 /TR：<c>/TR</c> 会剥掉内层引号（带空格路径即碎）且默认
    * DisallowStartIfOnBatteries=true（电池机上 /Run 假成功不执行）。XML 逐字面存储
    * 参数、显式关断电池闸；trigger = now+1min 作 /Run 失败时的兜底自动触发。
    */
  private def registerWindows(plan: Plan, script: os.Path, run: Run): IO[Either[String, String]] =
    val xmlPath = plan.dataRoot / "apply" / s"${plan.winTask}.xml"
    val triggerAt = java.time.LocalDateTime.now().plusMinutes(1).truncatedTo(java.time.temporal.ChronoUnit.MINUTES)
    val triggerFmt = triggerAt.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))
    val argsXml = ("-NoProfile -ExecutionPolicy Bypass -File \"" + script.toString + "\"")
      .replace("&", "&amp;")
      .replace("<", "&lt;")
      .replace(">", "&gt;")
    val xml =
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
         |    <ExecutionTimeLimit>PT1H</ExecutionTimeLimit>
         |  </Settings>
         |  <Triggers>
         |    <TimeTrigger>
         |      <StartBoundary>$triggerFmt</StartBoundary>
         |    </TimeTrigger>
         |  </Triggers>
         |  <Actions Context="Author">
         |    <Exec>
         |      <Command>powershell.exe</Command>
         |      <Arguments>$argsXml</Arguments>
         |    </Exec>
         |  </Actions>
         |</Task>
         |""".stripMargin
    IO.blocking {
      os.write.over(xmlPath, xml.getBytes(java.nio.charset.StandardCharsets.UTF_16), createFolders = true)
    }.attempt.flatMap {
      case Left(e) => IO.pure(Left(s"apply task xml write failed: ${Option(e.getMessage).getOrElse(e.toString)}"))
      case Right(_) =>
        val create = List("schtasks", "/Create", "/F", "/TN", plan.winTask, "/XML", xmlPath.toString)
        val fire = List("schtasks", "/Run", "/TN", plan.winTask)
        run(create).flatMap {
          case Left(err) => IO.pure(Left(s"schtasks create failed: $err"))
          case Right(_) =>
            run(fire).flatMap {
              case Left(err) =>
                IO.pure(
                  Left(
                    s"schtasks run failed (task created but not fired; will auto-fire at $triggerFmt — remove manually: schtasks /Delete /TN ${plan.winTask} /F): $err"
                  )
                )
              case Right(_) =>
                writeRegisteredState(plan).as(
                  Right(
                    s"detached apply registered via scheduled task '${plan.winTask}' (survives any session/tree death) — log: ${logPath(plan.dataRoot)}"
                  )
                )
            }
        }
    }

  /** macOS：launchd 一次性 agent（bootstrap+kickstart；旧版回落 load+start）；脚本自清理。 */
  private def registerPosix(plan: Plan, script: os.Path, run: Run): IO[Either[String, String]] =
    // ProcessBuilder 无 shell —— uid 必须先真实解析，不能把 $(id -u) 塞进 argv
    run(List("id", "-u")).flatMap {
      case Left(err) => IO.pure(Left(s"cannot resolve uid for launchctl: $err"))
      case Right(out) =>
        out.trim.filter(_.isDigit) match
          case uid if uid.nonEmpty =>
            val label = macLabel(plan)
            IO.blocking {
              val plist = macPlist(plan)
              os.makeDir.all(plist / os.up)
              os.write.over(plist, renderPlist(label, script), createFolders = true)
              plist
            }.flatMap { plist =>
              val guiTarget = s"gui/$uid"
              fireLaunchd(guiTarget, label, plist, run).flatMap {
                case Right(_) =>
                  writeRegisteredState(plan).as(
                    Right(
                      s"detached apply registered via launchd agent '$label' (survives any session/tree death) — log: ${logPath(plan.dataRoot)}"
                    )
                  )
                case Left(err) => IO.pure(Left(err))
              }
            }
          case other => IO.pure(Left(s"cannot resolve uid for launchctl (got '$other')"))
    }

  /** bootstrap → kickstart；bootstrap 撞已装载/旧 macOS 时 bootout 重试一次，再败回落 load+start。 */
  private def fireLaunchd(guiTarget: String, label: String, plist: os.Path, run: Run): IO[Either[String, Unit]] =
    def kick: IO[Either[String, Unit]] =
      run(List("/bin/launchctl", "kickstart", s"$guiTarget/$label")).map {
        case Right(_) => Right(())
        case Left(e) => Left(s"launchctl kickstart failed: $e")
      }
    run(List("/bin/launchctl", "bootstrap", guiTarget, plist.toString)).flatMap {
      case Right(_) => kick
      case Left(_) =>
        run(List("/bin/launchctl", "bootout", s"$guiTarget/$label")).flatMap { _ =>
          run(List("/bin/launchctl", "bootstrap", guiTarget, plist.toString)).flatMap {
            case Right(_) => kick
            case Left(_) =>
              // 旧 macOS 面：load -w + start
              run(List("/bin/launchctl", "load", "-w", plist.toString)).flatMap {
                case Left(e2) => IO.pure(Left(s"launchctl bootstrap and load both failed (plist: $plist)"))
                case Right(_) =>
                  run(List("/bin/launchctl", "start", label)).map {
                    case Right(_) => Right(())
                    case Left(e3) => Left(s"launchctl start failed: $e3")
                  }
              }
          }
        }
    }

  /** launchd plist 模板（一次性：RunAtLoad=false，kickstart 触发；日志直落 apply log）。 */
  def renderPlist(label: String, script: os.Path): String =
    s"""<?xml version="1.0" encoding="UTF-8"?>
       |<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
       |<plist version="1.0">
       |<dict>
       |  <key>Label</key><string>$label</string>
       |  <key>ProgramArguments</key>
       |  <array>
       |    <string>/bin/bash</string>
       |    <string>${script.toString}</string>
       |  </array>
       |  <key>RunAtLoad</key><false/>
       |  <key>KeepAlive</key><false/>
       |  <key>ProcessType</key><string>Background</string>
       |</dict>
       |</plist>
       |""".stripMargin

  // ── 脚本渲染（POSIX bash 3.2 兼容面；语义与 ps1 面逐条同构）────────────
  // 注意：三引号字符串不做反斜杠转义处理——此处所有 \n \" \\t 均为字面单反斜杠。

  /** shell 单引号安全化（路径注入唯一通道）。 */
  private def sh(s: String): String = "'" + s.replace("'", "'\\''") + "'"

  def renderBash(plan: Plan): String =
    val oldPidNote = plan.oldPid.map(p => s" oldPid=$p").getOrElse("")
    val tv = plan.targetVersion.map(v => s"TARGET_VERSION=${sh(v)}").getOrElse("TARGET_VERSION=''")
    val plistPath = if plan.layout != null then sh(macPlist(plan).toString) else ""
    s"""#!/bin/bash
|# stand detached apply — GENERATED by nebflow.cli.DetachedApply (台账 #916); do not edit
|# kind=${plan.kind.wire} port=${plan.port} target=${plan.targetVersion.getOrElse("-")} layout=${plan.layout.wire} gen=${plan.generation}$oldPidNote
|# 语义: stop(硬化)→端口空闲闸→start→健康闸→done; 失败→update回滚起旧版, restart报因.
|# 全程落日志+state.json(原子写)——永不无人观测. 选择面只有 pointer/winsort, 崩不坏.
|set -u
|KIND=${sh(plan.kind.wire)}
|PORT='${plan.port}'
|$tv
|LAYOUT=${sh(plan.layout.wire)}
|LOWER_NAME=${sh(Branding.lowerName)}
|INSTALL_DIR=${sh(plan.installDir.toString)}
|LAUNCHER=${sh(plan.launcher)}
|APPLY_DIR=${sh(applyDir(plan.dataRoot).toString)}
|LOG=${sh(logPath(plan.dataRoot).toString)}
|STATE='$${APPLY_DIR}/state.json'
|APPLY_LABEL=${sh(macLabel(plan))}
|APPLY_PLIST=$plistPath
|STOP_DEADLINE=${plan.stopDeadlineSec}
|HEALTH_DEADLINE=${plan.healthDeadlineSec}
|HEALTH_URL="http://127.0.0.1:$${PORT}/api/health"
|# OS 托管作业的 env 是全新的——引擎事实必须在这里显式重建，否则 launcher/引擎
|# 会静默落回默认根/默认口（生产 = 恰好同值而侥幸，沙箱/自定义根 = 直接打错家）。
|export STAND_HOME=${sh(plan.dataRoot.toString)}
|export STAND_GATEWAY_PORT='${plan.port}'
|export NEBFLOW_GATEWAY_PORT='${plan.port}'
|
|log() { printf '%s apply: %s\\n' "$$(date '+%Y-%m-%d %H:%M:%S')" "$$*" >> "$$LOG"; }
|set_phase() {
|  __p="$${1:-}"; __c="$${2:-}"
|  __tmp="$$STATE.tmp.$$"
|  { printf '{"kind":"%s","phase":"%s","port":%s' "$$KIND" "$$__p" "$$PORT"
|    [ -n "$$TARGET_VERSION" ] && printf ',"targetVersion":"%s"' "$$TARGET_VERSION"
|    printf ',"cause":'
|    if [ -n "$$__c" ]; then printf '"%s"' "$$__c"; else printf null; fi
|    printf ',"ts":"%s"}\\n' "$$(date '+%Y-%m-%d %H:%M:%S')"
|  } > "$$__tmp" && mv -f "$$__tmp" "$$STATE"
|}
|port_pids() { lsof -ti tcp:"$$PORT" -sTCP:LISTEN 2>/dev/null || true; }
|port_free() { [ -z "$$(port_pids)" ]; }
|health_payload() { curl -fsS -m 3 "$$HEALTH_URL" 2>/dev/null; }
|health_ok() {
|  __h="$$(health_payload)" || return 1
|  printf '%s' "$$__h" | grep -q '"status"[[:space:]]*:[[:space:]]*"ok"' || return 1
|  if [ -n "$$TARGET_VERSION" ]; then
|    printf '%s' "$$__h" | grep -q "\\\"version\\\"[[:space:]]*:[[:space:]]*\\\"$$TARGET_VERSION\\\"" || return 1
|  fi
|  return 0
|}
|wait_health() { # <deadline-sec> <label>
|  __dl=$$(($$(date +%s) + $$1 ))
|  while [ "$$(date +%s)" -lt "$$__dl" ]; do
|    if health_ok; then log "$$2: health OK"; return 0; fi
|    sleep 1
|  done
|  log "$$2: health deadline ($$1s) exceeded — last payload: $$(health_payload | head -c 300)"
|  return 1
|}
|stop_old() {
|  log "stop leg: $$LAUNCHER stop (hardened TERM→KILL inside launcher)$oldPidNote"
|  "$$LAUNCHER" stop >> "$$LOG" 2>&1 || log "stop leg rc=$$? (port-free gate below is the authority)"
|  __dl=$$(($$(date +%s) + STOP_DEADLINE ))
|  while [ "$$(date +%s)" -lt "$$__dl" ]; do
|    if port_free; then log "stop leg: port $$PORT free"; return 0; fi
|    sleep 1
|  done
|  log "stop leg: port $$PORT still held after $${STOP_DEADLINE}s (pids: $$(port_pids | tr '\\n' ' '))"
|  return 1
|}
|start_new() {
|  log "start leg: $$LAUNCHER start"
|  "$$LAUNCHER" start >> "$$LOG" 2>&1 || log "start leg rc=$$? (readiness judged by health gate)"
|  wait_health "$$HEALTH_DEADLINE" "start leg"
|}
|rollback() { # <cause> — update only
|  log "ROLLBACK: $$1"
|  set_phase rolling-back "$$1"
|  if [ "$$LAYOUT" = "pointer" ]; then
|    __cur="$$INSTALL_DIR/current-version"; __prev="$$INSTALL_DIR/previous-version"
|    if [ -f "$$__prev" ]; then
|      __p="$$(tr -d '\\t\\r\\n' < "$$__prev")"
|      printf '%s\\n' "$$__p" > "$$__cur.tmp.$$" && mv -f "$$__cur.tmp.$$" "$$__cur"
|      log "pointer rolled back to $$__p"
|    else log "no previous-version pointer — rollback limited to stopping the new instance"; fi
|  else
|    __q="$$INSTALL_DIR/$${LOWER_NAME}-assembly-$$TARGET_VERSION.jar"
|    if [ -f "$$__q" ]; then
|      mv -f "$$__q" "$$__q.bak-$$(date '+%Y%m%d-%H%M%S')" && log "quarantined staged jar: $$__q"
|    else log "staged jar not found for quarantine ($$__q)"; fi
|  fi
|  "$$LAUNCHER" stop >> "$$LOG" 2>&1 || true
|  sleep 1
|  "$$LAUNCHER" start >> "$$LOG" 2>&1 || true
|  if wait_health "$$HEALTH_DEADLINE" "rollback leg"; then
|    set_phase rolled-back "$$1"
|    log "rollback complete — previous version serving"
|    return 0
|  fi
|  set_phase failed "rollback failed: $$1"
|  log "FATAL: rollback failed — selection face may name a bad version; inspect $$LOG"
|  return 1
|}
|cleanup_registration() {
|  case "$$(uname -s)" in
|    Darwin)
|      /bin/launchctl bootout "gui/$$(id -u)/$$APPLY_LABEL" >/dev/null 2>&1 || true
|      [ -n "$$APPLY_PLIST" ] && rm -f "$$APPLY_PLIST"
|      ;;
|  esac
|}
|mkdir -p "$$APPLY_DIR" "$$(dirname "$$LOG")"
|log "=== apply begin (kind=$$KIND port=$$PORT target=$${TARGET_VERSION:-none} layout=$$LAYOUT pid=$$$$ ppid=$$PPID) ==="
|set_phase running
|if ! stop_old; then
|  if [ "$$KIND" = "update" ]; then
|    rollback "stop leg failed (port $$PORT still held)" || true
|    exit 3
|  fi
|  set_phase failed "stop leg failed (port $$PORT still held)"
|  cleanup_registration
|  exit 3
|fi
|set_phase stopped
|if ! start_new; then
|  if [ "$$KIND" = "update" ]; then
|    rollback "new version failed the health gate" || true
|    exit 4
|  fi
|  set_phase failed "start leg failed the health gate"
|  cleanup_registration
|  exit 4
|fi
|set_phase done
|log "=== apply done ==="
|cleanup_registration
|exit 0
|""".stripMargin

  // ── Windows PowerShell 面（与 bash 面同构）─────────────────────────────

  /** PS 单引号安全化。 */
  private def ps(s: String): String = s.replace("'", "''")

  def renderPowerShell(plan: Plan): String =
    val oldPidNote = plan.oldPid.map(p => s" oldPid=$p").getOrElse("")
    val tv = plan.targetVersion.map(v => s"""$$TargetVersion = '${ps(v)}'""").getOrElse("$TargetVersion = $null")
    s"""# stand detached apply (Windows) — GENERATED by nebflow.cli.DetachedApply (台账 #916); do not edit
|# kind=${plan.kind.wire} port=${plan.port} target=${plan.targetVersion.getOrElse("-")} layout=${plan.layout.wire} gen=${plan.generation}$oldPidNote
|$$ErrorActionPreference = 'Continue'
|$$Kind = '${ps(plan.kind.wire)}'
|$$Port = ${plan.port}
|$tv
|$$Layout = '${ps(plan.layout.wire)}'
|$$LowerName = '${ps(Branding.lowerName)}'
|$$InstallDir = '${ps(plan.installDir.toString)}'
|$$Launcher = '${ps(plan.launcher)}'
|$$DataRoot = '${ps(plan.dataRoot.toString)}'
|$$ApplyDir = Join-Path $$DataRoot 'apply'
|$$Log = Join-Path $$DataRoot 'logs\\apply-restart.log'
|$$State = Join-Path $$ApplyDir 'state.json'
|$$TaskName = '${ps(plan.winTask)}'
|$$StopDeadlineSec = ${plan.stopDeadlineSec}
|$$HealthDeadlineSec = ${plan.healthDeadlineSec}
|$$HealthUrl = "http://127.0.0.1:$$Port/api/health"
|# OS 托管作业的 env 是全新的——引擎事实必须在这里显式重建，否则 launcher/引擎
|# 会静默落回默认根/默认口（生产 = 恰好同值而侥幸，沙箱/自定义根 = 直接打错家）。
|$$env:STAND_HOME = '${ps(plan.dataRoot.toString)}'
|$$env:STAND_GATEWAY_PORT = '${plan.port}'
|$$env:NEBFLOW_GATEWAY_PORT = '${plan.port}'
|
|function Log([string]$$m) {
|  Add-Content -Path $$Log -Value ("{0} apply: {1}" -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $$m)
|}
|function SetPhase([string]$$p, [string]$$cause) {
|  $$o = [ordered]@{ kind = $$Kind; phase = $$p; port = $$Port }
|  if ($$TargetVersion) { $$o['targetVersion'] = $$TargetVersion }
|  $$o['cause'] = $$cause
|  $$o['ts'] = (Get-Date -Format 'yyyy-MM-dd HH:mm:ss')
|  $$tmp = "$$State.tmp.$$PID"
|  $$o | ConvertTo-Json -Compress | Set-Content -Path $$tmp -Encoding UTF8
|  Move-Item -Force $$tmp $$State
|}
|function PortFree {
|  try {
|    $$c = New-Object Net.Sockets.TcpClient
|    $$r = $$c.ConnectAsync('127.0.0.1', $$Port).Wait(500)
|    $$c.Close()
|    return -not $$r
|  } catch { return $$true }
|}
|function HealthOk {
|  try { $$h = Invoke-RestMethod -Uri $$HealthUrl -TimeoutSec 3 } catch { return $$false }
|  if ("$$($$h.status)" -ne 'ok') { return $$false }
|  if ($$TargetVersion -and "$$($$h.version)" -ne $$TargetVersion) { return $$false }
|  return $$true
|}
|function WaitHealth([int]$$sec, [string]$$label) {
|  $$dl = (Get-Date).AddSeconds($$sec)
|  while ((Get-Date) -lt $$dl) {
|    if (HealthOk) { Log "$${label}: health OK"; return $$true }
|    Start-Sleep -Seconds 1
|  }
|  Log "$${label}: health deadline ($$sec s) exceeded"
|  return $$false
|}
|function StopOld {
|  Log "stop leg: $$Launcher stop$oldPidNote"
|  & $$Launcher stop 2>&1 | ForEach-Object { Log "stop: $$_" }
|  $$dl = (Get-Date).AddSeconds($$StopDeadlineSec)
|  while ((Get-Date) -lt $$dl) {
|    if (PortFree) { Log "stop leg: port $$Port free"; return $$true }
|    Start-Sleep -Seconds 1
|  }
|  Log "stop leg: port $$Port still held after $$StopDeadlineSec s"
|  return $$false
|}
|function StartNew {
|  Log "start leg: $$Launcher start"
|  & $$Launcher start 2>&1 | ForEach-Object { Log "start: $$_" }
|  return (WaitHealth $$HealthDeadlineSec 'start leg')
|}
|function Rollback([string]$$cause) {
|  Log "ROLLBACK: $$cause"
|  SetPhase 'rolling-back' $$cause
|  if ($$Layout -eq 'pointer') {
|    $$cur = Join-Path $$InstallDir 'current-version'
|    $$prev = Join-Path $$InstallDir 'previous-version'
|    if (Test-Path $$prev) {
|      $$p = (Get-Content $$prev -Raw).Trim()
|      $$tmp = "$$cur.tmp.$$PID"
|      Set-Content -Path $$tmp -Value $$p -Encoding ASCII
|      Move-Item -Force $$tmp $$cur
|      Log "pointer rolled back to $$p"
|    } else { Log 'no previous-version pointer — rollback limited to stopping the new instance' }
|  } else {
|    $$q = Join-Path $$InstallDir ($$LowerName + '-assembly-' + $$TargetVersion + '.jar')
|    if (Test-Path $$q) {
|      Move-Item -Force $$q ($$q + '.bak-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
|      Log "quarantined staged jar: $$q"
|    } else { Log "staged jar not found for quarantine ($$q)" }
|  }
|  try { & $$Launcher stop 2>&1 | Out-Null } catch {}
|  Start-Sleep -Seconds 1
|  try { & $$Launcher start 2>&1 | Out-Null } catch {}
|  if (WaitHealth $$HealthDeadlineSec 'rollback leg') {
|    SetPhase 'rolled-back' $$cause
|    Log 'rollback complete — previous version serving'
|    return $$true
|  }
|  SetPhase 'failed' "rollback failed: $$cause"
|  Log 'FATAL: rollback failed — inspect log'
|  return $$false
|}
|New-Item -ItemType Directory -Force -Path $$ApplyDir, (Split-Path $$Log) | Out-Null
|Log "=== apply begin (kind=$$Kind port=$$Port target=$$TargetVersion layout=$$Layout pid=$$PID) ==="
|SetPhase 'running' ''
|if (-not (StopOld)) {
|  if ($$Kind -eq 'update') { [void](Rollback 'stop leg failed (port still held)'); exit 3 }
|  SetPhase 'failed' 'stop leg failed (port still held)'
|  schtasks /Delete /F /TN $$TaskName 2>&1 | Out-Null
|  exit 3
|}
|SetPhase 'stopped' ''
|if (-not (StartNew)) {
|  if ($$Kind -eq 'update') { [void](Rollback 'new version failed the health gate'); exit 4 }
|  SetPhase 'failed' 'start leg failed the health gate'
|  schtasks /Delete /F /TN $$TaskName 2>&1 | Out-Null
|  exit 4
|}
|SetPhase 'done' ''
|Log '=== apply done ==='
|schtasks /Delete /F /TN $$TaskName 2>&1 | Out-Null
|exit 0
|""".stripMargin

  /** 便利装配：从当前进程环境推断 Plan（launcher/安装根/布局/端口/数据根全解析）。 */
  def defaultPlan(kind: Kind, port: Int, targetVersion: Option[String], oldPid: Option[Long]): Either[String, Plan] =
    val env = sys.env
    val isWindows = sys.props.getOrElse("os.name", "").toLowerCase.contains("win")
    resolveLauncher(env, sys.props.getOrElse("user.home", ""), isWindows) match
      case None =>
        Left(
          "stand launcher not found (posix: ~/.stand/bin/stand; windows: %LOCALAPPDATA%\\stand\\stand.cmd; override: STAND_LAUNCHER)"
        )
      case Some(l) =>
        val installDir = installDirOf(l)
        Right(
          Plan(
            kind = kind,
            dataRoot = PathUtil.dataRoot,
            installDir = installDir,
            launcher = l,
            port = port,
            layout = detectLayout(installDir),
            targetVersion = targetVersion,
            oldPid = oldPid
          )
        )
end DetachedApply
