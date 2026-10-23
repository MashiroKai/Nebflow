package nebflow.cli

import nebflow.gateway.WindowsProcs

/**
 * Process/port reverse lookup shared by the hardened stop face (phase 2
 * daemon plan §3.2, F-3).
 *
 * The old stop face derived "the instance's port" from the CALLER's view
 * (`GatewayClient.readPort`: --port override > GATEWAY_PORT env > 8080).
 * That resolver answers "which port would I talk to", not "which port does
 * the target process serve" — a bare `stand stop` on a machine whose dev
 * host listens on 8080 would then poll 8080 for a foreign process and fail
 * after 65s although the target was already gone. The stop face therefore
 * asks the process itself: which TCP ports does THIS pid listen on.
 */
object StopSupport:

  /** lsof NAME column forms: `*:8080`, `127.0.0.1:8108`, `[::1]:8080`. */
  private val NamePort = """^(?:\[[^\]]+\]|[^:\s]+):(\d+)$""".r

  /**
   * TCP ports the given pid is LISTENING on. POSIX: lsof reverse lookup.
   * Windows: `netstat -ano -p tcp` rows parsed by
   * `WindowsProcs.parseNetstatListenerPorts` (lsof is absent there — before
   * 新设备首用批 2026-10-22 this face silently returned Nil on Windows,
   * fail-opening the port-confirmation piece and starving the degraded
   * identity chain of its port-same-source leg).
   * Empty when the probe fails or the process has no listener (dead pids
   * return no rows); callers treat empty as fail-open, never as "port X".
   */
  def pidListeningPorts(pid: Long): List[Int] =
    if WindowsProcs.isWindows then WindowsProcs.listenerPortsOf(pid)
    else
      try
        val pb = new ProcessBuilder(
          "lsof",
          "-nP",
          "-a",
          "-p",
          pid.toString,
          "-iTCP",
          "-sTCP:LISTEN"
        )
        pb.redirectErrorStream(true)
        val p = pb.start()
        val out = new String(p.getInputStream.readAllBytes(), "UTF-8")
        p.waitFor()
        out
          .linesIterator
          .toList
          .drop(1) // header row
          .flatMap(_.trim.split("\\s+").collect { case NamePort(port) => port.toInt })
          .distinct
      catch case _: Exception => List.empty

end StopSupport
