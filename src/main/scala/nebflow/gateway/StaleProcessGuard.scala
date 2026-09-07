package nebflow.gateway

import scala.collection.mutable.ListBuffer

/** P0 2026-09-06 (09:07 host-kill incident) — identity check BEFORE any kill.
  *
  * Incident: a node script exec'd `java nebflow.gateway.GatewayMain --port 8097`;
  * GatewayMain never parsed argv, the port fell back to the default 8080, and
  * ensureSingleInstance then destroyed EVERY listener on 8080 — including the
  * user's live host instance — without ever checking who it was killing.
  *
  * This guard is the kill-side policy: a process found listening on the
  * gateway port may be destroyed ONLY when its command line verifiably marks
  * it as a Nebflow gateway (a stale instance left over from a restart).
  * Anything else — python/node http servers, dev tools, ANY unidentifiable
  * process — is foreign: refuse startup loudly, kill nothing.
  *
  * Restart-flow compatibility (must keep working — every launch shape that
  * legitimately owns the port carries a marker):
  *   java -cp … nebflow.Main start                      → "nebflow.Main"
  *   java … nebflow.gateway.GatewayMain (sbt run / dev) → "GatewayMain"
  *   java -jar …/nebflow-assembly-<v>.jar start         → "nebflow-assembly"
  *   java -jar ~/.local/bin/nebflow.jar start           → "nebflow.jar"
  *   /Applications/Nebflow.app/Contents/MacOS/Nebflow   → "Nebflow.app/Contents/MacOS"
  * so `stop && start`, RestartHelper self-restart (`exec java -jar … start`)
  * and launchd/desktop auto-start still get their stale instance cleared.
  */
object StaleProcessGuard:

  /** Command-line fragments that identify a Nebflow gateway process. A bare
    * "Main" would match foreign processes (`node main.js`, `SomeMain.py`) and
    * reintroduce the kill-anything hole — only full tokens qualify. */
  val NebflowMarkers: List[String] = List(
    "nebflow.Main", // classpath launches + jpackage exec form
    "GatewayMain", // nebflow.gateway.GatewayMain (sbt run / dev forks)
    "nebflow-assembly", // java -jar …/nebflow-assembly-<version>.jar
    "nebflow.jar", // ~/.local/bin/nebflow.jar install form (Makefile install)
    "Nebflow.app/Contents/MacOS" // macOS desktop launcher binary (jpackage)
  )

  def isNebflowCommandLine(cmdline: String): Boolean =
    NebflowMarkers.exists(cmdline.contains)

  /** Max length of an occupant command line in error output. */
  val CmdlineDetailLimit: Int = 200

  def truncate(s: String, limit: Int = CmdlineDetailLimit): String =
    if s.length <= limit then s else s.substring(0, limit) + "…(truncated)"

  /** A process on the gateway port that is NOT ours: refuse startup, never kill. */
  final case class ForeignOccupant(pid: Long, cmdline: String):
    def detail: String = s"pid $pid, command: ${truncate(cmdline)}"

  /** Full command line of a pid. Primary: JDK ProcessHandle (sysctl on
    * macOS / procfs on Linux — no binary exec, so it still works inside
    * exec-restricted sandboxes that deny spawning `ps`); fallback: `ps -p`.
    * None when neither can read it — an unreadable process is never killed
    * and never blocks startup on its own. */
  def readCommandLine(pid: Long): Option[String] =
    viaProcessHandle(pid).orElse(viaPs(pid))

  private def viaProcessHandle(pid: Long): Option[String] =
    try
      import scala.jdk.OptionConverters.*
      ProcessHandle.of(pid).toScala
        .flatMap(_.info.commandLine.toScala)
        .map(_.trim)
        .filter(_.nonEmpty)
    catch case _: Exception => None

  private def viaPs(pid: Long): Option[String] =
    try
      val pb = new ProcessBuilder("ps", "-p", pid.toString, "-o", "command=")
      // stderr DISCARD — ps diagnostics ("no such process") must never be
      // mistaken for a command line and classify a corpse as foreign.
      pb.redirectError(ProcessBuilder.Redirect.DISCARD)
      val p = pb.start()
      val out = new String(p.getInputStream.readAllBytes(), "UTF-8").trim
      p.waitFor()
      if out.isEmpty then None else Some(out)
    catch case _: Exception => None

  /** Pids LISTENING on the port (lsof). Empty on any failure — the caller
    * then boots and lets the bind surface a real error instead of guessing. */
  def portListenerPids(port: Int): List[Long] =
    try
      val pb = new ProcessBuilder("lsof", "-i", s":$port", "-t", "-sTCP:LISTEN")
      val p = pb.redirectErrorStream(true).start()
      val out = new String(p.getInputStream.readAllBytes(), "UTF-8").trim
      p.waitFor()
      out.split("\\s+").filter(_.matches("\\d+")).map(_.toLong).toList
    catch case _: Exception => List.empty

  /** The kill-side verdict for the processes listening on the gateway port.
    *
    * Right(stale)  — verified Nebflow pids (self excluded): safe to destroy.
    * Left(foreign) — at least one listener is NOT Nebflow: refuse startup and
    *                 destroy NOTHING (not even verified stale pids — mixed
    *                 occupants mean the port is contested; booting into it
    *                 half-killed is worse than failing loudly).
    *
    * Pids whose command line cannot be read (died in the race window) are
    * skipped: a corpse holds no port, and unverifiable is not killable.
    */
  def classify(
      occupants: List[Long],
      currentPid: Long,
      readCmd: Long => Option[String] = readCommandLine
  ): Either[ForeignOccupant, List[Long]] =
    val stale = ListBuffer.empty[Long]
    var foreign: Option[ForeignOccupant] = None
    val it = occupants.filter(_ != currentPid).iterator
    while foreign.isEmpty && it.hasNext do
      val pid = it.next()
      readCmd(pid) match
        case Some(cmd) if isNebflowCommandLine(cmd) => stale += pid
        case Some(cmd)                              => foreign = Some(ForeignOccupant(pid, cmd))
        case None                                   => () // vanished between lsof and ps — skip
    foreign.toLeft(stale.toList)

end StaleProcessGuard
