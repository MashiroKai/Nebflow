package nebflow.gateway

import scala.collection.mutable.ListBuffer

/**
 * Windows process-info legs for the identity/stop faces (新设备首用批 2026-10-22).
 *
 * WHY this exists — the Windows command-line hole, measured on the author's
 * machine (DESKTOP-IJBBEOT, Temurin 21, PidProbe against live pids):
 * `ProcessHandle.Info.commandLine()` returns `Optional.empty` for every
 * process the current JVM did not spawn (`command` — the bare exe path — IS
 * readable; the full command line is not). The JDK Windows implementation
 * only fills the command line for the current process and its own children.
 * `stand start`'s daemon child is therefore always a "foreign" process to a
 * later `stand stop` JVM ⇒ piece-1 identity verification read `None` and
 * refused to stop the user's own instance ("起得来停不掉").
 *
 * Three legs land here, all plain-Windows tools (no external deps):
 *   1. PowerShell CIM — `Get-CimInstance Win32_Process` reads the real
 *      command line of any same-visibility process (verified live: the
 *      author's pid 9112 full command line came back).
 *   2. `netstat -ano -p tcp` — listener rows carry the owning pid, giving
 *      the pid→port and port→pid lookups `lsof` provides on POSIX.
 *   3. The parsers are pure functions over captured output shapes so the
 *      spec pins them cross-platform (fixtures are real machine samples).
 *
 * Cost note: each leg spawns a process (PowerShell ≈ 1–2s, netstat ≈
 * 100–300ms). Both run only on cold operations (stop / startup guard), never
 * on request paths.
 */
object WindowsProcs:

  /** Cached once — os.name never changes inside a JVM. */
  lazy val isWindows: Boolean =
    sys.props.getOrElse("os.name", "").toLowerCase.contains("win")

  /** Hard cap for every spawned probe (PowerShell cold start can exceed 2s). */
  private[gateway] val ProbeTimeoutMs: Long = 8_000L

  /**
   * Full command line of `pid` via PowerShell CIM. None on any failure
   * (not Windows / powershell missing / timeout / access denied / empty
   * output). The filter value is a Long — no injection face.
   */
  def commandLineViaCim(pid: Long): Option[String] =
    if !isWindows then None
    else
      runCapturing(
        Seq(
          "powershell",
          "-NoProfile",
          "-NonInteractive",
          "-Command",
          s"(Get-CimInstance Win32_Process -Filter 'ProcessId=$pid').CommandLine"
        )
      ).map(_.trim).filter(_.nonEmpty)

  /**
   * Raw output lines of `netstat -ano -p tcp` (the always-present Windows
   * listener table). None on any failure / non-Windows.
   */
  def netstatListeningRows(): Option[Vector[String]] =
    if !isWindows then None
    else
      runCapturing(Seq("netstat", "-ano", "-p", "tcp")).map { out =>
        out.linesIterator.toVector
      }

  /**
   * TCP ports `pid` is LISTENING on, from captured netstat output.
   *
   * Row shape (state column is always English, independent of OS locale):
   * `  TCP    0.0.0.0:8080           0.0.0.0:0                LISTENING       9112`
   * Accepts both the leading-proto form above and rows whose local address
   * is `[::]:8080` / `127.0.0.1:8080`. The owning pid is the LAST column.
   */
  private[gateway] val ListenerState = "LISTENING"

  def parseNetstatListenerPorts(rows: Vector[String], pid: Long): List[Int] =
    val ports = ListBuffer.empty[Int]
    rows.foreach { line =>
      val cols = line.trim.split("\\s+")
      if cols.length >= 5 && cols(0).equalsIgnoreCase("TCP") && cols(3) == ListenerState
      then
        val localAddr = cols(1)
        val owner = cols(cols.length - 1)
        if owner == pid.toString then
          val idx = localAddr.lastIndexOf(':')
          if idx > 0 then
            val p = localAddr.substring(idx + 1)
            val port = p.toIntOption
            port.foreach(ports += _)
    }
    ports.distinct.toList

  end parseNetstatListenerPorts

  /** Pids LISTENING on `port`, the netstat counterpart of the above. */
  def parseNetstatListenerPids(rows: Vector[String], port: Int): List[Long] =
    val pids = ListBuffer.empty[Long]
    rows.foreach { line =>
      val cols = line.trim.split("\\s+")
      if cols.length >= 5 && cols(0).equalsIgnoreCase("TCP") && cols(3) == ListenerState
      then
        val localAddr = cols(1)
        val idx = localAddr.lastIndexOf(':')
        if idx > 0 && localAddr.substring(idx + 1) == port.toString then
          cols(cols.length - 1).toLongOption.foreach(pids += _)
    }
    pids.distinct.toList

  /**
   * Run a command capturing merged stdout, with a hard timeout. None on
   * spawn failure / timeout (the timed-out process is destroyed first).
   * stderr is merged in but every consumer trims + filters — diagnostics
   * never become a command line (same discipline as `viaPs`).
   */
  private def runCapturing(cmd: Seq[String]): Option[String] =
    try
      val pb = new ProcessBuilder(cmd*)
      pb.redirectErrorStream(true)
      val p = pb.start()
      val finished = p.waitFor(ProbeTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
      if !finished then
        p.destroyForcibly()
        None
      else
        val out = new String(p.getInputStream.readAllBytes(), "UTF-8")
        Some(out)
    catch case _: Exception => None

  /** Exposed for tests: run + parse in one step (the live face). */
  def listenerPortsOf(pid: Long): List[Int] =
    netstatListeningRows().map(parseNetstatListenerPorts(_, pid)).getOrElse(List.empty)

  def listenerPidsOf(port: Int): List[Long] =
    netstatListeningRows().map(parseNetstatListenerPids(_, port)).getOrElse(List.empty)

end WindowsProcs
