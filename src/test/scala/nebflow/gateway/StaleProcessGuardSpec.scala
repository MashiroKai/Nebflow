package nebflow.gateway

import munit.FunSuite

import java.net.{InetSocketAddress, ServerSocket}

/** P0 2026-09-06 host-kill hardening — the kill-side policy of
  * GatewayMain.ensureSingleInstance:
  *
  * Pure contract (fake ps):
  *  - every REAL nebflow launch shape      → stale, killable (restart flows)
  *  - foreign cmdline (incl. bare "Main"!) → foreign, refuse, kill nothing
  *  - self excluded; unreadable (dead) skipped; mixed → refuse entirely
  *
  * Live pipeline (macOS/Linux + python3): REAL listener processes through the
  * REAL lsof → ps → classify path:
  *  - python http.server on the port  → Left(foreign) and the process is
  *    still ALIVE afterwards (refuse-not-kill — the 09:07 incident contract)
  *  - a nebflow-marked listener        → Right(exactly that pid), and the
  *    destroy step (as ensureSingleInstance does) actually clears it
  */
class StaleProcessGuardSpec extends FunSuite:

  // Value = the cmdline ps/ProcessHandle would return; ABSENT key (300L) =
  // unreadable — the process died in the lsof→ps race window.
  private val fakeCmds: Map[Long, String] = Map(
    // Real nebflow launch shapes (restart-flow compatibility set)
    100L -> "java -cp /x/nebflow-assembly-1.4.1.jar nebflow.Main --home /tmp/a --port 8095 start",
    101L -> "java -Xmx2g -cp … nebflow.gateway.GatewayMain", // sbt run / dev fork
    102L -> "java -jar /opt/nebflow/nebflow-assembly-1.4.1.jar start --no-browser", // RestartHelper / autostart
    103L -> "java --add-opens java.base/java.lang=ALL-UNNAMED -jar /Users/x/.local/bin/nebflow.jar start --no-browser", // Makefile install form
    104L -> "/Applications/Nebflow.app/Contents/MacOS/Nebflow --server", // macOS desktop (jpackage)
    // Foreign occupants
    200L -> "python3 -m http.server 8080",
    201L -> "node main.js", // bare "Main" must NOT match — the incident hole reopened
    202L -> "nginx: worker process",
    203L -> "/usr/local/bin/main --serve" // another bare-Main foreign shape
    // 300L intentionally absent — unreadable (died in the race window)
  )
  private def fakeReader: Long => Option[String] = fakeCmds.lift

  test("every real nebflow launch shape classifies stale (restart flows keep clearing)"):
    List(100L, 101L, 102L, 103L, 104L).foreach { pid =>
      StaleProcessGuard.classify(List(pid), 1L, fakeReader) match
        case Right(stale) => assertEquals(stale, List(pid))
        case Left(f)      => fail(s"pid $pid is a nebflow launch shape — must be killable, got foreign: ${f.detail}")
    }

  test("foreign occupants are refused, never classified killable"):
    List(200L, 201L, 202L, 203L).foreach { pid =>
      StaleProcessGuard.classify(List(pid), 1L, fakeReader) match
        case Left(f)  => assert(f.pid == pid)
        case Right(s) => fail(s"pid $pid is foreign — must refuse, got kill list $s")
    }

  test("self pid is excluded from the kill list"):
    val v = StaleProcessGuard.classify(List(1L, 100L), 1L, fakeReader)
    assertEquals(v, Right(List(100L)))

  test("unreadable pid (died in the lsof→ps window) is skipped, not killed, not foreign"):
    assertEquals(StaleProcessGuard.classify(List(300L), 1L, fakeReader), Right(Nil))

  test("mixed stale + foreign occupants → refuse ENTIRELY (kill nothing)"):
    StaleProcessGuard.classify(List(100L, 200L), 1L, fakeReader) match
      case Left(f)  => assertEquals(f.pid, 200L)
      case Right(s) => fail(s"mixed occupants must refuse even the stale ones, got $s")

  test("empty occupant list is a no-op Right"):
    assertEquals(StaleProcessGuard.classify(Nil, 1L, fakeReader), Right(Nil))

  test("foreign detail truncates the cmdline at 200 chars"):
    val long = "x" * 250
    val d = StaleProcessGuard.ForeignOccupant(1L, long).detail
    assert(d.length < long.length + 50, "detail must be truncated")
    assert(d.contains("…(truncated)"))
    assert(StaleProcessGuard.truncate("short") == "short")

  // ============================================================
  // Live pipeline: real processes, real lsof + ps — exactly the
  // path GatewayMain.ensureSingleInstance runs at startup.
  // ============================================================

  private lazy val isUnix: Boolean =
    val os = sys.props.getOrElse("os.name", "").toLowerCase
    os.contains("mac") || os.contains("linux")

  private def freePort(): Int =
    val ss = new ServerSocket()
    ss.bind(new InetSocketAddress("127.0.0.1", 0))
    val p = ss.getLocalPort
    ss.close()
    p

  private def pythonAvailable(): Boolean =
    if !isUnix then false
    else
      try
        val p = new ProcessBuilder("python3", "--version").start()
        p.waitFor() == 0
      catch case _: Exception => false

  /** The live pipeline needs lsof (see our own in-JVM listener) AND a
    * readable command line (ProcessHandle sysctl / procfs — exec-restricted
    * sandboxes that deny `ps` still pass via ProcessHandle). Unavailable →
    * live tests skip; the classify contract stays covered by the pure tests. */
  private def pipelineAvailable(): Boolean =
    val ss = new ServerSocket()
    try
      ss.bind(new InetSocketAddress("127.0.0.1", 0), 1)
      val seesSelf = StaleProcessGuard.portListenerPids(ss.getLocalPort)
        .contains(ProcessHandle.current.pid)
      val readsSelf = StaleProcessGuard.readCommandLine(ProcessHandle.current.pid).isDefined
      seesSelf && readsSelf
    catch case _: Exception => false
    finally ss.close()

  /** Poll until lsof sees a LISTENer (spawned servers take a moment). */
  private def waitListening(port: Int, timeoutMs: Long = 8000): List[Long] =
    val deadline = System.currentTimeMillis() + timeoutMs
    var found: List[Long] = Nil
    while found.isEmpty && System.currentTimeMillis() < deadline do
      found = StaleProcessGuard.portListenerPids(port)
      if found.isEmpty then Thread.sleep(100)
    found

  test("LIVE foreign listener (python http.server) → classify refuses, process NOT killed"):
    if pythonAvailable() && pipelineAvailable() then
      val port = freePort()
      val p = new ProcessBuilder("python3", "-m", "http.server", port.toString, "--bind", "127.0.0.1")
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start()
      try
        val occupants = waitListening(port)
        assert(occupants.nonEmpty, "python http.server never showed up in lsof")
        StaleProcessGuard.classify(occupants, 1L) match // real reader (ProcessHandle/ps)
          case Left(f) =>
            assert(f.cmdline.contains("http.server"), s"unexpected cmdline: ${f.cmdline}")
          case Right(s) => fail(s"foreign http.server must be refused, got kill list $s")
        // The contract of the incident fix: refusal leaves the foreign occupant alive.
        assert(p.isAlive, "foreign occupant must NOT be killed by the refusal path")
      finally
        p.destroy()
        p.waitFor()

  test("LIVE stale nebflow-marked listener → classified stale and the kill clears it"):
    if pythonAvailable() && pipelineAvailable() then
      val port = freePort()
      // argv carries "nebflow.Main" — a real LISTENER with a nebflow-marker
      // cmdline, exactly what a leftover gateway looks like to the guard.
      val script =
        "import socket,sys,time;s=socket.socket();s.bind(('127.0.0.1',int(sys.argv[1])));s.listen(8);time.sleep(600)"
      val p = new ProcessBuilder("python3", "-c", script, port.toString, "nebflow.Main")
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start()
      try
        val occupants = waitListening(port)
        assert(occupants.nonEmpty, "marked listener never showed up in lsof")
        StaleProcessGuard.classify(occupants, 1L) match // real reader (ProcessHandle/ps)
          case Right(stale) =>
            assertEquals(stale, List(p.pid.toLong), "kill list must be exactly the marked listener")
            // The restart-flow step ensureSingleInstance performs after Right:
            stale.foreach(pid => ProcessHandle.of(pid).ifPresent(_.destroyForcibly()))
            val gone = p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
            assert(gone, "stale nebflow instance must be cleared by the kill step")
          case Left(f) => fail(s"nebflow-marked listener must classify stale, got foreign: ${f.detail}")
      finally
        p.destroyForcibly()
        p.waitFor()

end StaleProcessGuardSpec
