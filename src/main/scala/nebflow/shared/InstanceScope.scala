package nebflow.shared

import os.Path

/**
 * 有效实例口径（isolated-instance-kill 事故批 2026-11-04 §1/§1反/§2）—— **单一实现**。
 *
 * == 事故（2026-11-03 22:59）==
 * 一个 `STAND_HOME=/tmp/seal-canary-home` 的隔离 canary 批执行了 `stand` 的停机类动词，
 * 把跑在 6138 上的**生产实例**停机了。两条病根：
 *   ① stop/restart 的**端口腿**按「品牌全局端口」解析（端口链完全不看 home）——
 *      隔离调用者解析出 6138，把生产实例当成自家实例停掉；
 *   ② 端口解析在 home 被覆盖时**静默回落品牌缺省端口**（6138/6140），
 *      隔离实例于是抢生产口。
 *
 * == 口径（本文件 = 唯一来源；shell 侧 `ops/bin/stand-cli-lib.sh` 的
 *    `stand_resolve_port` / `cli_stop_impl` 是同一口径的 bash 镜像）==
 * 「有效实例」= 有效 home R（`--home` > `STAND_HOME` > `NEBFLOW_HOME` > `~/.stand`，
 * 见 [[PathUtil.dataRoot]]）+ **R 自己的端口**：
 *   `--port` > R 的配置 `port` 键 > （**仅当 R 是默认根**）品牌缺省端口。
 * R 被覆盖（≠ 默认根）时：继承来的 `*_GATEWAY_PORT` 不算显式（忽略并 WARN 点名）；
 * 配置也没有 ⇒ **拒绝**并要求显式 `--port`。
 *
 * 停机面（[[homeRefusal]]）：目标 pid 的 home 必须先证明是 R；
 *   · 一致 ⇒ 可停；
 *   · 不一致 ⇒ 拒绝并点名两个 home（零信号）；
 *   · 无法判定 ⇒ 拒绝（fail-closed，把今日的 fail-open 反过来）。
 * home 证明腿（按可信度）：`--home <root>`（引擎 daemon 子进程命令行恒带，见
 * `DaemonSpawn.buildChildArgs`）、`-Duser.home=`、命令行里出现调用者自己的根路径、
 * 以及「pid 就是调用者自己 `.pid` 记账的那一个」（来源即证明）。
 */
object InstanceScope:

  // ── 路径同一性（不做符号链接解析：字节口径与 shell 侧 `[ a = b ]` 同判据）──
  def normalizePath(p: String): String =
    val t = p.trim.replace('\\', '/')
    if t.length > 1 && t.endsWith("/") then t.dropRight(1) else t

  def samePath(a: String, b: String): Boolean = normalizePath(a) == normalizePath(b)

  /**
   * 调用者视角的有效 home。`overridden = R 不是任一默认根`
   * （默认根 = `~/<brand homeDirName>` 与硬编码 legacy `~/.nebflow`，与
   * [[PathUtil.resolveDefaultDataRoot]] 的候选集同源）。
   */
  final case class Caller(root: Path, defaultRoot: Path, overridden: Boolean)

  def callerOf(root: Path, home: Path, homeDirName: String, legacyDirName: String): Caller =
    val brandDefault = home / homeDirName
    val legacy = home / legacyDirName
    val overridden =
      !samePath(brandDefault.toString, root.toString) && !samePath(legacy.toString, root.toString)
    Caller(root, brandDefault, overridden)

  /** 生产面（读 env 的真实解析）。spec 一律走 [[callerOf]]，不读进程 env。 */
  def liveCaller: Caller =
    callerOf(PathUtil.dataRoot, os.home, Branding.homeDirName, PathUtil.LegacyHomeDirName)

  // ── 端口口径 ───────────────────────────────────────────────────────────

  /** 端口解析输入。`envPortName` 只用于**报错点名**（「继承来的 X=Y 被忽略」）。 */
  final case class PortInputs(
    cliPort: Option[Int],
    envPortName: String,
    envPort: Option[String],
    configPort: Option[Int],
    sourceName: String, // 配置文件名，仅用于文案（nebflow.json）
    brandDefaultPort: Int,
    root: Path,
    overridden: Boolean
  )

  sealed trait PortVerdict

  object PortVerdict:
    /** 解析成功；`warnings` 是必须打出去的 WARN 行（被忽略的继承 env）。 */
    final case class Resolved(port: Int, via: String, warnings: List[String]) extends PortVerdict

    /** 明确拒绝（配一句能直接给用户看的原因）。 */
    final case class Refused(reason: String) extends PortVerdict

  private def bindable(p: Int): Boolean = p > 0 && p <= 65535

  /**
   * 端口解析链（唯一实现）：
   *   `--port` > [R 未被覆盖时: `GATEWAY_PORT` env] > R 配置 `port` 键 >
   *   [R 未被覆盖时: 品牌缺省端口] / [R 被覆盖时: **拒绝**]
   *
   * R 被覆盖时 env 端口被忽略（不是「显式」——它可能就是父 shell 给生产实例的那一个），
   * 忽略时返回一行 WARN 点名它。
   */
  def decide(i: PortInputs): PortVerdict =
    val envLeg = i.envPort.flatMap(_.trim.toIntOption).filter(bindable)
    val envIgnored =
      if i.overridden then
        envLeg.map { p =>
          "WARN: ignoring inherited " + i.envPortName + "=" + p + " — this invocation's data root is " +
            i.root + " (an isolated root, not the default root), so an inherited port is not " +
            "treated as explicit"
        }
      else None
    i.cliPort.filter(bindable) match
      case Some(p) => PortVerdict.Resolved(p, "--port", Nil)
      case None =>
        if !i.overridden then
          envLeg match
            case Some(p) => PortVerdict.Resolved(p, i.envPortName, Nil)
            case None =>
              i.configPort.filter(bindable) match
                case Some(p) => PortVerdict.Resolved(p, "config 'port' key", Nil)
                case None => PortVerdict.Resolved(i.brandDefaultPort, "brand default port", Nil)
        else
          i.configPort.filter(bindable) match
            case Some(p) => PortVerdict.Resolved(p, "config 'port' key of " + i.root, envIgnored.toList)
            case None =>
              PortVerdict.Refused(
                "refusing to inherit the brand default port (" + i.brandDefaultPort + ") for data root " +
                  i.root + ": it is an ISOLATED root (not the default root), and an instance in an " +
                  "isolated root must name its own port. Pass --port <N> explicitly, or set the " +
                  "\"port\" key in " + i.root + "/" + i.sourceName + "." +
                  envIgnored.fold("")(w => " (also " + w.replaceFirst("^WARN: ", "") + ")")
              )
    end match
  end decide

  // ── 目标 pid 的 home 归属证明 ───────────────────────────────────────────

  sealed trait HomeProof:
    def home: String
    def via: String

  object HomeProof:
    final case class Same(home: String, via: String) extends HomeProof
    final case class Other(home: String, via: String) extends HomeProof

    final case class Unknown(home: String, detail: String) extends HomeProof:
      def via: String = detail

  /** 命令行里的 `--home <p>` / `--home=<p>`（引擎 daemon 子进程恒带）。 */
  def homeFromCommandLine(cmd: String): Option[(String, String)] =
    val toks = cmd.replace('\u0000', ' ').split("\\s+").toList.filter(_.nonEmpty)
    def flagLoop(rest: List[String]): Option[String] =
      rest match
        case Nil => None
        case tok :: tail =>
          if tok == "--home" then tail.headOption.filterNot(_.startsWith("-"))
          else if tok.startsWith("--home=") then Some(tok.drop("--home=".length))
          else flagLoop(tail)
    val homeFlag = flagLoop(toks).map(h => (h, "--home flag in command line"))
    val userHome = toks
      .collectFirst { case t if t.startsWith("-Duser.home=") => t.drop("-Duser.home=".length) }
      .map(h => (h, "-Duser.home in command line"))
    homeFlag.orElse(userHome)

  /**
   * 目标 pid 的 home 归属证明。纯函数（每条腿的原料都由调用方注入）。
   *
   * @param cmd           目标 pid 的命令行（读不到 = None）
   * @param pid           目标 pid
   * @param ownRoot       调用者的有效 home R
   * @param ownPidFilePid 调用者自己 `<R>/.pid` 里记的 pid（来源即归属证明）
   */
  def proveHome(
    cmd: Option[String],
    pid: Long,
    ownRoot: String,
    ownPidFilePid: Option[Long]
  ): HomeProof =
    val own = normalizePath(ownRoot)
    homeFromCommandLine(cmd.getOrElse("")) match
      case Some((h, via)) =>
        if samePath(h, own) then HomeProof.Same(own, via) else HomeProof.Other(normalizePath(h), via)
      case None =>
        val c = cmd.map(normalizePath).getOrElse("")
        if c.nonEmpty && (c.contains(own + "/") || c.endsWith(own)) then
          HomeProof.Same(own, "own data root path in command line")
        else if ownPidFilePid.contains(pid) then HomeProof.Same(own, "own .pid accounting")
        else
          HomeProof.Unknown(
            own,
            "no --home/-Duser.home evidence in the command line and the pid is not this root's .pid record"
          )

  end proveHome

  /**
   * 停机面的归属裁决（停 / 不停）。`failClosedWhenUnknown` = 端口腿语义
   * （候选 pid 是**探出来的**，来源不证明归属 ⇒ 无法判定必须停手）。
   */
  def homeRefusal(
    proof: HomeProof,
    ownRoot: String,
    failClosedWhenUnknown: Boolean
  ): Option[String] =
    val own = normalizePath(ownRoot)
    proof match
      case HomeProof.Same(_, _) => None
      case HomeProof.Other(h, via) =>
        Some(
          "refusing to stop pid: the target instance's home is " + h + " (" + via +
            ") but this invocation's home is " + own +
            " — different data roots are different instances; zero signal sent. " +
            "Run the stop from THAT home, or pass the --port of your own instance."
        )
      case HomeProof.Unknown(_, detail) =>
        if failClosedWhenUnknown then
          Some(
            "refusing to stop: cannot prove the target pid belongs to this invocation's home " + own +
              " (" + detail + ") — fail-closed, zero signal sent"
          )
        else None
    end match
  end homeRefusal

end InstanceScope

/**
 * 端口口径拒绝（隔离实例误伤批 2026-11-04 §2）：数据根被覆盖却没有自己的端口声明
 * ⇒ 拒绝而不是静默回落品牌缺省口。调用面把它翻成一行人话后非零退出。
 */
final class PortRefusal(message: String) extends RuntimeException(message)
