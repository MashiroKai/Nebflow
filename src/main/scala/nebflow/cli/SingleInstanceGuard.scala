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
   * 端口被**另一个命令品牌**的实例占着（双实例并存批 2026-10-30）。
   *
   * 背景：`stand` 与 `nebflow` 两个命令品牌同机并存（默认口 6138 / 8080、数据根
   * `~/.stand` / `~/.nebflow`，见 `ops/bin/stand-cli-lib.sh` 头契约表）。两者跑的是同一
   * 引擎产品线，健康面都长成「自己人」——旧分类会把它判成 `NebflowInstance` 走
   * focus-existing 分支：`stand start` 撞上 legacy 8080 实例会去**聚焦对家**而不是
   * 报错。故按健康面的 `product` 标记再分一层：同品牌 → [[NebflowInstance]]（聚焦），
   * 异品牌 → 本类（拒启 + 点名是哪一实例）。
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
            // 判据 = **路径归属**（命令行里的品牌数据根），不是品牌词。
            // 理由（rename 兼容契约，2026-10-18 批文）：改名当天之前构建的本产品实例
            // 健康面 product 仍写 `nebflow`，它其实是**自己人**，必须照旧 focus 而不是拒启。
            // 反过来 legacy `nebflow` 命令线（9.19 自有根）实打实跑在 `~/.nebflow/bin`
            // 下 —— 那条路径就是「不是本实例」的铁证。故只有路径证据才升级为
            // [[OtherInstance]]；品牌词只进拒绝文案（让人看清占口的是哪条线）。
            otherRootOccupant(port) match
              case Some((_, _, _)) => OtherInstance(if brand == OwnBrand then OtherBrandBrandName else brand, url)
              case None            => NebflowInstance(url)
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

  /** 同机并存的另一个命令品牌（legacy `nebflow` 线）。 */
  private[cli] val OtherBrands: Set[String] = Set("nebflow").filterNot(_ == OwnBrand)

  /** 品牌词读不出来、只凭路径归属判出的对家实例的名字（legacy 线固定名）。 */
  private[cli] val OtherBrandBrandName: String = OtherBrands.headOption.getOrElse("nebflow")

  /** 健康面探测结果：品牌标记 + 聚焦 URL。 */
  private[cli] final case class ProbeHit(brand: String, url: String)

  /** Probe health without the system proxy. Recognize the current/legacy
   * product names, or the full legacy health shape, rather than arbitrary
   * responses containing a product/version key.
   *
   * 双实例并存批 2026-10-30：除了「是不是本产品」还要回答「是哪条命令线的实例」——
   * 返回的 `brand` 就是健康面 `product` 的原文（stand / nebflow），缺 `product`
   * 字段的老构建归 [[OwnBrand]]（兼容：宁可聚焦，不与老实例开战）。
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
                val known = Set("stand", OwnBrand) ++ OtherBrands
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
