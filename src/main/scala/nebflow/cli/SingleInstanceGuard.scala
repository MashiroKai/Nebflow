package nebflow.cli

import cats.effect.IO
import nebflow.shared.Branding
import io.circe.parser.parse

import java.net.*

import scala.util.{Try, Using}

/**
 * Single-instance guard (2026-08-27, hard requirement after a real
 * double-launch incident): before the gateway boots, verify nothing else is
 * already serving this port.
 *
 * The legacy PID-file guard (ProcessManager, `<dataRoot>/.pid`) only catches
 * same-home relaunches. Tonight's incident class is wider:
 *  - cross-home collisions (two `--home`s, one port) — different pid files,
 *    no guard at all today;
 *  - a lost/stale pid file (crash cleanup raced, or the first instance was
 *    started by an older build that wrote no pid);
 *  - desktop .app double-click — stdout is discarded, so the old text-only
 *    "already running" message was invisible; the user saw a silently dead
 *    second app.
 *
 * Semantics (user ruling): a second launch must NOT boot a second gateway;
 * it focuses the existing instance (open its URL — the browser IS the app
 * window until the native shell lands) or, when the port is held by a
 * foreign program, fails loudly instead of half-booting and dying at bind.
 */
object SingleInstanceGuard:

  sealed trait PortState
  case object PortFree extends PortState
  final case class NebflowInstance(url: String) extends PortState

  /**
   * 端口被**另一个命令品牌**的实例占着（双实例并存批 2026-10-30 立类；
   * 判据换代为双生分家批 2026-10-31）。
   *
   * 背景：`stand` 与 `nebflow` 两个命令品牌同机并存（默认口 6138 / 6140、数据根
   * `~/.stand` / `~/.nebflow`，见 `ops/bin/stand-cli-lib.sh` 头契约表）。两者跑的是同一
   * 引擎产品线，健康面都长成「自己人」——旧分类会把它判成 `NebflowInstance` 走
   * focus-existing 分支：`stand start` 撞上对家实例会去**聚焦对家**而不是报错。
   * 10-30 批的修法是「品牌词 + 路径证据」分层，10-31 作者裁定收紧为**三元组判据**
   * （品牌+自己的端口+自己的 home）且**绝不跨品牌 focus**：健康面 `product` 是
   * 已知品牌且 ≠ 本构建品牌 ⇒ 本类（拒启 + 点名是哪一实例），零路径证据要求；
   * 路径证据只在同品牌词场景下兜底升级（家腿）。两品牌的引擎兜底口自本批起
   * 错开（6138/6140），撞口只剩显式同配一类，撞了也只会互相点名拒启，永不 focus。
   */
  final case class OtherInstance(brand: String, url: String) extends PortState

  final case class ForeignOccupant(detail: String) extends PortState

  /**
   * Max time checkPort waits for TIME_WAIT sockets to drain before booting
   * anyway (covers Linux's fixed 60s TW; macOS is 30s). Only burns when a
   * kill-and-restart actually left TW sockets — free ports and live
   * occupants classify instantly.
   */
  val MaxDrainWaitMs: Long = 65_000L
  private val DrainPollMs: Long = 1_000L
  private val ConnectProbeTimeoutMs: Int = 1500

  /**
   * Sync variant for callers already inside blocking context (Main's
   * pid-file fast path). None == not a live stand port.
   */
  def checkPortBlocking(host: String, port: Int): Option[String] =
    probeNebflow(port).map(_.url)

  /**
   * Classifier inputs — host/port mirror exactly what EmberServerBuilder
   * will bind, so "free here" == "Ember can bind here".
   *
   * R1 (2026-08-30, restart-script-stability 设计件):
   * a failed strict bind no longer implies a foreign occupant — a killed
   * instance leaves its closed connections in kernel TIME_WAIT (macOS
   * 2×MSL = 30s, Linux fixed 60s), which blocks bind but has NO listener.
   * A TCP connect probe disambiguates: refused → TIME_WAIT only → wait out
   * the drain window and boot (fail-open at the ceiling); accepted → live
   * occupant → identity probe as before. The single-instance red line is
   * untouched: real listeners still classify NebflowInstance /
   * ForeignOccupant and still refuse a double boot.
   */
  def checkPort(host: String, port: Int, drainWaitMs: Long = MaxDrainWaitMs): IO[PortState] =
    IO.blocking {
      probeOnce(host, port) match
        case Right(state) => state
        case Left(()) =>
          // TIME_WAIT drain window: re-probe until the bind frees or a live
          // listener appears (a concurrent watchdog restart during the wait is
          // then classified correctly, not raced past).
          val deadline = System.currentTimeMillis() + drainWaitMs
          var state: Option[PortState] = None
          while state.isEmpty && System.currentTimeMillis() < deadline do
            Thread.sleep(DrainPollMs)
            probeOnce(host, port) match
              case Right(s) => state = Some(s)
              case Left(()) => ()
          // Fail-open at the ceiling: no live listener was ever observed, so
          // booting and letting Ember surface a real bind error beats the
          // false "held by another program" refuse that killed the
          // 2026-08-30 restart.
          state.getOrElse(PortFree)
    }

  /**
   * One-shot classification WITHOUT the drain wait. Right(state) = decided
   * (free or live occupant identified); Left(()) = bind blocked but no live
   * listener — kernel TIME_WAIT sockets only.
   */
  private[cli] def probeOnce(host: String, port: Int): Either[Unit, PortState] =
    if strictBindOk(host, port) then Right(PortFree)
    else if connectAccepted(port) then
      Right(
        probeNebflow(port) match
          case Some(ProbeHit(brand, url)) =>
            // 双生分家批 2026-10-31（作者裁定「品牌+自己的端口+自己的 home」三元组，
            // **绝不跨品牌 focus**）：健康面的品牌词就是权威身份 ——
            //   · 已知**异**品牌（health `product` ≠ 本构建品牌）⇒ [[OtherInstance]]
            //     拒启并点名，**零路径证据要求**。10-18 的 rename 兼容腿（「无路径
            //     证据的 nebflow 词当自家人 focus」）被本裁定退役：双产品世界里
            //     品牌词即身份，跨品牌永不 focus（改名前构建的健康面早已淘汰）。
            //   · 同品牌词 ⇒ 自家实例，但先过一遍路径证据兜底：listener 命令行
            //     落在**另一个**品牌根下（极老构建健康面无品牌词、或混装 shim 的
            //     残留态）仍升级为 [[OtherInstance]] —— 三元组的家腿在此闭合。
            // 端口腿恒成立：被探口就是冲突口本身。
            if brand == OwnBrand then
              otherRootOccupant(port) match
                case Some((_, _, root)) => OtherInstance(root.stripPrefix("."), url)
                case None               => NebflowInstance(url)
            else OtherInstance(brand, url)
          case None => ForeignOccupant(s"port $port is held by another program")
      )
    else Left(())

  /**
   * 端口 listener 里「命令行根落在另一个品牌数据根下」的那一个（pid, cmdline, root）。
   * 复用 [[nebflow.gateway.StaleProcessGuard]] 的 pid 枚举 / 命令行读取 / 根判定 ——
   * 单一实现，不另造第二套 ps 解析。
   */
  private[cli] def otherRootOccupant(port: Int): Option[(Long, String, String)] =
    try
      nebflow.gateway.StaleProcessGuard
        .portListenerPids(port)
        .filterNot(_ == ProcessHandle.current.pid)
        .iterator
        .flatMap(pid =>
          nebflow.gateway.StaleProcessGuard
            .readCommandLine(pid)
            .flatMap(cmd =>
              nebflow.gateway.StaleProcessGuard
                .otherInstanceRoot(cmd, nebflow.shared.PathUtil.dataRoot.toString)
                .map(root => (pid, cmd, root))
            )
        )
        .nextOption()
    catch case _: Exception => None

  /**
   * Strict bind probe — reuse off so a wildcard bind can't pass over a
   * live specific-address listener (the 2026-08-27 half-boot hole).
   */
  private def strictBindOk(host: String, port: Int): Boolean =
    Try {
      val ss = new ServerSocket()
      try
        // JDK ServerSocket defaults SO_REUSEADDR=true on macOS/BSD, where a
        // wildcard (0.0.0.0) bind is then ALLOWED over an active specific-
        // address listener (127.0.0.1:p) that also set reuse — the probe
        // would report "free" while the port is served (E2E-verified bug:
        // a python occupant on 127.0.0.1 let the gateway half-boot beside
        // it). Strict bind: reuse off -> BindException on any live listener.
        ss.setReuseAddress(false)
        ss.bind(new InetSocketAddress(java.net.InetAddress.getByName(host), port), 1)
      finally ss.close()
    }.isSuccess

  /**
   * TCP connect probe. true = a live listener accepted the connection;
   * false = refused/timeout → no listener (TIME_WAIT sockets at most,
   * which refuse connections). Probes the loopback address regardless of
   * the bind host — that is where any real occupant listens.
   */
  private def connectAccepted(port: Int): Boolean =
    Try {
      val s = new Socket()
      try
        s.connect(new InetSocketAddress("127.0.0.1", port), ConnectProbeTimeoutMs)
        true
      finally s.close()
    }.getOrElse(false)

  /**
   * Hot-restart handover probe (SuccessorGate [s4], hot-restart 批): the live-
   * listener check for the successor's port-entry gate. TCP connect ONLY —
   * never binds. true = a listener accepted (foreign-preemption window — the
   * caller must fall back to ensureSingleInstance semantics); false =
   * refused/timeout = no live listener even while TIME_WAIT drain is pending
   * (the real bind and its fail-open ceiling stay owned by the Ember build).
   */
  def connectProbeAccepted(port: Int): Boolean = connectAccepted(port)

  /**
   * 本引擎品牌（`brand.conf lowerName`，今天 = "stand"）—— 健康面 `product` 标记与它
   * 相等即「自己人」（聚焦）；等于另一个已知命令品牌（[[OtherBrands]]）即「对家实例」
   * （拒启点名）；其余一律 unknown（按兼容口径当自己人，见 [[probeNebflow]]）。
   */
  private[cli] val OwnBrand: String = Branding.lowerName

  /**
   * 本产品线的全部已知命令品牌（双生分家批 2026-10-31）：健康面 `product` 命
   * 中本集即「认识」；其中 [[OwnBrand]] 是「自己人」，其余是「对家实例」。
   */
  private[cli] val KnownBrands: Set[String] = Set("stand", "nebflow")

  /** 同机并存的另一个/另一些命令品牌（= [[KnownBrands]] − 本构建品牌）。 */
  private[cli] val OtherBrands: Set[String] = KnownBrands.filterNot(_ == OwnBrand)

  /** 健康面探测结果：品牌标记 + 聚焦 URL。 */
  private[cli] final case class ProbeHit(brand: String, url: String)

  /** Probe health without the system proxy. Recognize the current/legacy
   * product names, or the full legacy health shape, rather than arbitrary
   * responses containing a product/version key.
   *
   * 双实例并存批 2026-10-30：除了「是不是本产品」还要回答「是哪条命令线的实例」——
   * 返回的 `brand` 就是健康面 `product` 的原文（stand / nebflow）。
   * 双生分家批 2026-10-31：缺 `product` 字段的老构建仍归 [[OwnBrand]]（兼容：无品牌词
   * 无从谈跨品牌），但其 focus 资格在 [[probeOnce]] 里还要过路径证据兜底腿——老构建
   * 落在对家品牌根下照样拒启点名，不会跨品牌 focus。
   */
  private def probeNebflow(port: Int): Option[ProbeHit] =
    Try {
      val conn = java.net.URI
        .create(s"http://127.0.0.1:$port/api/health")
        .toURL
        .openConnection(java.net.Proxy.NO_PROXY)
        .asInstanceOf[HttpURLConnection]
      conn.setConnectTimeout(1500)
      conn.setReadTimeout(1500)
      try
        val code = conn.getResponseCode
        if code != 200 then None
        else
          val body = Using.resource(scala.io.Source.fromInputStream(conn.getInputStream))(
            _.mkString
          )
          val hit = parse(body).toOption.flatMap { json =>
            val c = json.hcursor
            c.get[String]("product").toOption match
              case Some(product) =>
                val known = KnownBrands + OwnBrand
                if known.contains(product) then Some(ProbeHit(product, s"http://localhost:$port"))
                else None
              case None =>
                val legacyShape = c.get[String]("status").toOption.contains("ok") &&
                  c.get[String]("version").toOption.exists(_.nonEmpty) &&
                  c.downField("providers").focus.exists(_.isObject)
                if legacyShape then Some(ProbeHit(OwnBrand, s"http://localhost:$port")) else None
          }
          hit
      finally conn.disconnect()
    }.toOption.flatten

  /**
   * The focus action for an already-running instance: visible message +
   * browser open (the current product form — a native window replaces this
   * when the desktop shell lands). Respect --no-browser / headless.
   */
  def focusExisting(url: String, reason: String, openBrowser: Boolean): IO[Unit] =
    IO.println(s"${Branding.productName} is already running ($reason)") *>
      IO.println(s"focusing existing instance: ${url.takeWhile(_ != '?')}") *>
      IO.whenA(openBrowser && !nebflow.core.HeadlessMode.enabled)(openBrowserTo(url))

  private def openBrowserTo(url: String): IO[Unit] = IO.blocking {
    val os = sys.props.getOrElse("os.name", "").toLowerCase
    val cmd =
      if os.contains("mac") then Seq("open", url)
      else if os.contains("win") then Seq("rundll32", "url.dll,FileProtocolHandler", url)
      else Seq("xdg-open", url)
    try Runtime.getRuntime.exec(cmd.toArray)
    catch case _: Exception => ()
  }

end SingleInstanceGuard
