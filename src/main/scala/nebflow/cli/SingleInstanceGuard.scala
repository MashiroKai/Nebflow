package nebflow.cli

import cats.effect.IO
import nebflow.core.PathUtil

import java.net.{HttpURLConnection, InetSocketAddress, ServerSocket}
import scala.util.{Try, Using}

/** Single-instance guard (2026-08-27, hard requirement after a real
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
  case object PortFree                      extends PortState
  final case class NebflowInstance(url: String) extends PortState
  final case class ForeignOccupant(detail: String) extends PortState

  /** Sync variant for callers already inside blocking context (Main's
    * pid-file fast path). None == not a live nebflow port.
    */
  def checkPortBlocking(host: String, port: Int): Option[String] =
    probeNebflow(port)

  /** Classifier inputs — host/port mirror exactly what EmberServerBuilder
    * will bind, so "free here" == "Ember can bind here". Fail-open on
    * unexpected errors (let Ember surface the real failure later).
    */
  def checkPort(host: String, port: Int): IO[PortState] = IO.blocking {
    val free = Try {
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
    if free then PortFree
    else
      probeNebflow(port) match
        case Some(url) => NebflowInstance(url)
        case None      => ForeignOccupant(s"port $port is held by another program")
  }

  /** HTTP probe of the occupant's /api/health. MUST bypass the JVM system
    * proxy (local proxy 7890 otherwise swallows localhost, slow-failing the
    * probe). Identification accepts EITHER the new `product:"nebflow"` marker
    * OR the legacy `version` field — a running older build (pre-product-field)
    * is still our instance and must still be focused, not fought.
    */
  private def probeNebflow(port: Int): Option[String] =
    Try {
      val conn = java.net.URI.create(s"http://127.0.0.1:$port/api/health").toURL
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
          val isOurs = body.contains("\"product\"") || body.contains("\"version\"")
          if isOurs then Some(s"http://localhost:$port") else None
      finally conn.disconnect()
    }.toOption.flatten

  /** The focus action for an already-running instance: visible message +
    * browser open (the current product form — a native window replaces this
    * when the desktop shell lands). Respect --no-browser / headless.
    */
  def focusExisting(url: String, reason: String, openBrowser: Boolean): IO[Unit] =
    IO.println(s"nebflow is already running ($reason)") *>
      IO.println(s"focusing existing instance: $url") *>
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
