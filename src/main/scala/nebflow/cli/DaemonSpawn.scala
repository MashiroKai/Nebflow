package nebflow.cli

import cats.effect.{ExitCode, IO}
import cats.syntax.all.*

import java.lang.management.ManagementFactory

/**
 * Daemon launcher (phase 2 daemon plan §1): the `nebflow start` parent
 * process spawns the gateway as a detached child JVM and returns after the
 * child is SERVING, printing exactly one confirmation line.
 *
 * Split of duties:
 *  - parent (this launcher): port gate happens in Main BEFORE launch; here —
 *    rotate `daemon.out`, spawn the child, write a provisional `.pid.meta`
 *    for the child, wait for readiness (the same `/api/health` identity
 *    probe `SingleInstanceGuard` uses), print the single line.
 *  - child JVM (`nebflow.Main --daemon-child`): strict boot — its own port
 *    gate, then `bootGateway` writes the real `.pid` (the pid file belongs
 *    to the gateway process, never to the launcher) and the final meta.
 *
 * The launcher path makes ZERO slf4j calls: logback's init dump (44 status
 * lines, see CliRouter A13) would wreck the one-line contract, so the same
 * NopStatusListener guard is set first. The system property does not cross
 * the process boundary — the child's logback is untouched.
 */
object DaemonSpawn:

  /** jpackage/jlink bundled forms have an empty java.class.path — unspawnable. */
  def classPathSpawnable: Boolean =
    sys.props.get("java.class.path").exists(_.trim.nonEmpty)

  /**
   * Rotate `daemon.out` when it exceeds this size (single-generation
   * `.old` copy; logback only rolls `nebflow.log` — `daemon.out` is the
   * child's raw stdout+stderr face and has no other manager).
   */
  private[cli] val DaemonOutMaxBytes: Long = 16L * 1024 * 1024

  private[cli] val DefaultReadyTimeoutSec: Int = 120
  private[cli] val ReadyTimeoutEnv: String = "NEBFLOW_DAEMON_READY_TIMEOUT_SEC"
  private val ReadyPollMs: Long = 1000L

  // ── JVM argument inheritance (R2-10 + F-9: blacklist FIRST) ───────────

  /** Debug/agent flags are never forwarded — two JVMs cannot share a jdwp port. */
  private[cli] val JvmArgBlacklist: List[String] = List("-agentlib", "-Xrunjdwp", "-Xdebug")

  /** Single-token whitelisted prefixes (heap/GC tuning, user.home pinning). */
  private[cli] val JvmArgWhitelistSingle: List[String] = List("-Duser.home=", "-X", "-XX")

  /** Flags that may carry their value as the NEXT token (`--add-opens mod=pkg`). */
  private[cli] val JvmArgWhitelistPaired: List[String] = List("--add-opens", "--add-exports")

  /**
   * Filter the launcher's own JVM arguments down to what the daemon child
   * should inherit. Order is load-bearing (F-9): the blacklist is checked
   * FIRST because the `-X` whitelist prefix strictly contains `-Xrunjdwp`
   * and `-Xdebug` — whitelist-first would forward debugger flags into the
   * child and the child would die fighting over the jdwp port.
   * Pair-aware: a bare `--add-opens` keeps its following value token.
   */
  private[cli] def filterJvmArgs(args: List[String]): List[String] =
    val kept = List.newBuilder[String]
    var i = 0
    while i < args.length do
      val a = args(i)
      if JvmArgBlacklist.exists(a.startsWith) then i += 1 // skip (blacklist first)
      else if JvmArgWhitelistPaired.contains(a) && i + 1 < args.length && !args(i + 1).startsWith("-") then
        kept += a
        kept += args(i + 1)
        i += 2
      else if JvmArgWhitelistPaired.exists(a.startsWith) then // attached `=value` form
        kept += a
        i += 1
      else if JvmArgWhitelistSingle.exists(a.startsWith) then
        kept += a
        i += 1
      else i += 1
    end while
    kept.result()

  /** JVM arguments of the current process (each token one element). */
  def currentJvmArgs: List[String] =
    import scala.jdk.CollectionConverters.*
    ManagementFactory.getRuntimeMXBean.getInputArguments.asScala.toList

  /**
   * Pure child command builder (spec-driven):
   * `[nohup] <javaHome>/bin/java <inheritedJvmArgs> -cp <classPath>
   *  nebflow.Main --home <home> --port <port> --daemon-child [--no-browser]`.
   * `--succeed` is NEVER forwarded (hot restart is orchestrated, not
   * inherited). `home` is the parent's RESOLVED data root (not the raw argv)
   * so the child rebuilds the identical root regardless of how the parent
   * was invoked.
   */
  private[cli] def buildChildArgs(
    javaHome: String,
    home: String,
    port: Int,
    noBrowser: Boolean,
    inheritedJvmArgs: List[String],
    classPath: String,
    posix: Boolean
  ): List[String] =
    (if posix then List("nohup") else Nil) ++
      List(s"$javaHome/bin/java") ++
      inheritedJvmArgs ++
      List("-cp", classPath, "nebflow.Main", "--home", home, "--port", port.toString, "--daemon-child") ++
      (if noBrowser then List("--no-browser") else Nil)

  /** The single stdout confirmation line (byte budget: one line, ≤ 200 bytes). */
  private[cli] def readyLine(childPid: Long, consoleUrl: String): String =
    s"nebflow started (pid $childPid) — $consoleUrl"

  /** daemon.out rotation predicate (single generation). */
  private[cli] def shouldRotate(size: Long): Boolean = size > DaemonOutMaxBytes

  // ── readiness wait ─────────────────────────────────────────────────────

  sealed trait ReadyFailure
  case object ChildDied extends ReadyFailure
  case object TimedOut extends ReadyFailure

  /**
   * Pure-ish readiness core (spec-driven via injected probe/liveness):
   * poll every `pollMs` until the probe passes (Right), the child dies
   * (Left(ChildDied)), or the deadline passes (Left(TimedOut)). The real
   * probe is the same identity check `SingleInstanceGuard` uses on the port
   * (`/api/health` 200 + product/version marker), which distinguishes "my
   * child is serving" from "some foreign process grabbed the port".
   */
  private[cli] def awaitReadyCore(
    probe: () => Boolean,
    childAlive: () => Boolean,
    deadlineMs: Long,
    pollMs: Long
  ): Either[ReadyFailure, Unit] =
    val deadline = System.currentTimeMillis() + deadlineMs
    while true do
      if !childAlive() then return Left(ChildDied)
      if probe() then return Right(())
      if System.currentTimeMillis() >= deadline then return Left(TimedOut)
      if pollMs > 0 then Thread.sleep(pollMs)
    end while
    Left(TimedOut) // unreachable

  private def readyTimeoutMs(): Long =
    val sec =
      sys.env
        .get(ReadyTimeoutEnv)
        .flatMap(_.trim.toIntOption)
        .filter(_ > 0)
        .getOrElse(DefaultReadyTimeoutSec)
    sec * 1000L

  private def probePort(port: Int): () => Boolean =
    () => SingleInstanceGuard.checkPortBlocking("127.0.0.1", port).isDefined

  // ── spawn + confirm ────────────────────────────────────────────────────

  /**
   * Spawn the gateway child JVM, wait until it serves, print the one-line
   * confirmation. Failure paths print a multi-line explanation to stderr and
   * return a non-zero exit code; the child is destroyed by US only on the
   * timeout path (self-started process self-clean).
   */
  def launch(home: os.Path, port: Int, noBrowser: Boolean): IO[ExitCode] =
    IO
      .blocking {
        spawnAndAwait(home, port, noBrowser)
      }
      .flatMap {
        case Launched(childPid) =>
          nebflow.gateway.PortalUrl
            .focusUrl(s"http://localhost:$port")
            .map(readyLine(childPid, _))
            .flatMap(IO.println)
            .as(ExitCode.Success)
        case DiedBeforeReady(tail) =>
          stderrLines("daemon child exited before becoming ready — last lines of daemon.out:" :: tail)
            .as(ExitCode.Error)
        case ReadinessTimeout(childPid, tail) =>
          stderrLines(
            s"daemon child did not become ready in time — destroyed (pid $childPid); last lines of daemon.out:" :: tail
          ).as(ExitCode.Error)
      }

  private sealed trait LaunchResult
  private case class Launched(childPid: Long) extends LaunchResult
  private case class DiedBeforeReady(tail: List[String]) extends LaunchResult
  private case class ReadinessTimeout(childPid: Long, tail: List[String]) extends LaunchResult

  /** Blocking core: rotate, spawn, provisional meta, readiness wait (may destroy on timeout). */
  private def spawnAndAwait(home: os.Path, port: Int, noBrowser: Boolean): LaunchResult =
    // Same guard as CliRouter A13 — the launcher face must stay one clean
    // line, and logback's init dump would precede it.
    if sys.props.get("logback.statusListenerClass").isEmpty then
      sys.props("logback.statusListenerClass") = "ch.qos.logback.core.status.NopStatusListener"

    val logsDir = home / "logs"
    os.makeDir.all(logsDir)
    val daemonOut = logsDir / "daemon.out"
    if os.exists(daemonOut) && shouldRotate(os.size(daemonOut)) then
      os.move.over(daemonOut, logsDir / "daemon.out.old")

    val posix = !sys.props.getOrElse("os.name", "").toLowerCase.contains("win")
    val classPath = sys.props.getOrElse("java.class.path", "")
    val args = buildChildArgs(
      sys.props.getOrElse("java.home", "java"),
      home.toString,
      port,
      noBrowser,
      filterJvmArgs(currentJvmArgs),
      classPath,
      posix
    )

    val pb = new ProcessBuilder(args*)
    pb.redirectInput(
      ProcessBuilder.Redirect.from(new java.io.File(if posix then "/dev/null" else "NUL"))
    )
    pb.redirectErrorStream(true)
    pb.redirectOutput(
      ProcessBuilder.Redirect.appendTo(new java.io.File(daemonOut.toIO.getPath))
    )
    val child = pb.start()

    // Provisional ownership meta for the child (the child re-writes the
    // same values when it boots). At this point the old `.pid` is absent
    // or dead (the pid fast path focused-and-exited otherwise), so the
    // writeFresh rule always allows this write.
    PidMeta.writeFreshFor(PidMeta.StartedByLauncher, PidMeta.ModeDaemon, child.pid())

    awaitReadyCore(
      probePort(port),
      () => child.isAlive,
      readyTimeoutMs(),
      ReadyPollMs
    ) match
      case Right(()) => Launched(child.pid())
      case Left(ChildDied) => DiedBeforeReady(tailOf(daemonOut, 5))
      case Left(TimedOut) =>
        // Self-started process self-clean: WE spawned it, WE take it down.
        child.destroy()
        ReadinessTimeout(child.pid(), tailOf(daemonOut, 5))

  private def stderrLines(lines: List[String]): IO[Unit] =
    lines.traverse_(l => IO.blocking(System.err.println(l)))

  private def tailOf(f: os.Path, n: Int): List[String] =
    if !os.exists(f) then Nil
    else
      val all = os.read(f).linesIterator.toList
      if all.size <= n then all else all.takeRight(n)

end DaemonSpawn
