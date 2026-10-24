package nebflow.cli

import cats.effect.IO
import nebflow.gateway.StaleProcessGuard
import nebflow.shared.PathUtil

import scala.collection.mutable.ListBuffer

object ProcessManager:
  private def pidFile: os.Path = PathUtil.dataRoot / ".pid"

  def readPid(): Option[Long] =
    if os.exists(pidFile) then
      try Some(os.read(pidFile).trim.toLong)
      catch case _: Exception => None
    else None

  def writePid(pid: Long): Unit =
    os.write.over(pidFile, pid.toString, createFolders = true)

  /**
   * Remove the pid file AND its ownership sidecar — the single cleanup point
   * covering stop, shutdown hooks, doctor --fix and stale clears (phase 2
   * daemon plan §2.2: one deletion path, no orphaned meta).
   */
  def removePid(): Unit =
    if os.exists(pidFile) then os.remove(pidFile)
    PidMeta.remove()

  def isRunning(pid: Long): Boolean =
    java.lang.ProcessHandle.of(pid).map(_.isAlive).orElse(false)

  // ── hardened stop (phase 2 daemon plan §3.2) ───────────────────────────

  final case class StopOptions(force: Boolean = false, tree: Boolean = false)

  /**
   * Injection face for the stop specs (R2-8): every OS-touching probe is a
   * function so red cases (8080/8097 dev-host targets, foreign pids, sbt
   * trees) are constructible with zero real signals. The degraded-chain
   * probes (metaRead / startInstantMs / gatewayPortOverride) follow the same
   * discipline — specs inject all three instead of touching the real home
   * or a real process start instant.
   */
  final case class StopDeps(
    readCmd: Long => Option[String] = StaleProcessGuard.readCommandLine,
    listeningPortsOf: Long => List[Int] = StopSupport.pidListeningPorts,
    listenerAlive: Int => Boolean = port => SingleInstanceGuard.connectProbeAccepted(port),
    destroy: Long => Unit = pid => ProcessHandle.of(pid).ifPresent(_.destroy()),
    destroyForcibly: Long => Unit = pid => ProcessHandle.of(pid).ifPresent(_.destroyForcibly()),
    isRunning: Long => Boolean = ProcessManager.isRunning,
    graceTimeoutMs: Long = 5_000L,
    forceTimeoutMs: Long = 5_000L,
    drainTimeoutMs: Long = SingleInstanceGuard.MaxDrainWaitMs,
    pollMs: Long = 100L,
    metaRead: () => Option[PidMeta.Meta] = () => PidMeta.read(),
    startInstantMs: Long => Option[Long] = ProcessManager.processStartInstantMs,
    gatewayPortOverride: Option[Int] = None
  )

  sealed trait StopOutcome

  object StopOutcome:
    case object NotRunning extends StopOutcome
    final case class Stopped(message: String) extends StopOutcome
    final case class Refused(reason: String) extends StopOutcome
    final case class Failed(reason: String) extends StopOutcome

  /**
   * Dev-host refusal markers (phase 2 daemon plan §3.2): command lines that
   * mark a LIVE listener on 8080/8097 as a dev host (sbt dev fork, an
   * assembly jar run straight from the repo's target/). Distinct from
   * [[StaleProcessGuard.NebflowMarkers]] (the "our instance, may manage"
   * face) — opposite semantics, two single authorities, never merged.
   * Deliberately conservative: it also catches `java -cp target/.../nebflow-
   * assembly-*.jar` runs, which are dev-plane by definition (product jars
   * install outside the repo).
   */
  val DevHostMarkers: List[String] = List("GatewayMain", "sbt-launch", "/target/")

  /** Ancestor-chain markers identifying an sbt-managed process tree. */
  val SbtMarkers: List[String] = List("sbt-launch", "xsbt", "/target/")

  private[cli] def isSbtCommandLine(cmdline: String): Boolean =
    SbtMarkers.exists(cmdline.contains)

  /** Hard gate ports — never stoppable through this CLI, not even with --force. */
  val ProtectedPorts: List[Int] = List(8080, 8097)

  /**
   * The hardened stop face — four pieces, in order:
   *   1. pid identity check (NebflowMarkers; anything else = refuse, zero
   *      signal) — EXCEPT the command-line-unreadable case, which now runs
   *      the degraded identity chain ([[DegradedIdentity]], 新设备首用批
   *      2026-10-22): Windows JDKs return an empty command line for every
   *      process the JVM did not spawn (the `stand start` daemon child is
   *      always one of those to a later `stand stop` JVM), so a plain
   *      refusal there meant the user's own legal instance could never be
   *      stopped. The chain only ever PASSES on pid-meta + start-time match
   *      or a same-source port-listener proof; a READABLE foreign command
   *      line still refuses exactly as before.
   *   +. 8080/8097 dev-host hard gate (above --force)
   *   2. ownership gate + optional tree handling (`--tree` unlocks non-launcher
   *      owners; when an sbt ancestor exists the whole chain from it dies,
   *      otherwise the stop degrades to the single pid with a declaration)
   *   3. port-release confirmation (ports reverse-looked-up from the TARGET
   *      pid, never from the caller's port view — F-3)
   *   4. non-zero exit on refusal/failure is the caller's mapping (StopCommand)
   */
  def stopHardened(opts: StopOptions, deps: StopDeps = StopDeps()): IO[StopOutcome] =
    IO
      .blocking {
        readPid() match
          case None =>
            Left(StopOutcome.NotRunning)
          case Some(pid) if !deps.isRunning(pid) =>
            // Stale pid file — idempotent cleanup, still "not running" success.
            removePid()
            Left(StopOutcome.NotRunning)
          case Some(pid) => Right(pid)
      }
      .flatMap {
        case Left(early) => IO.pure(early)
        case Right(pid) =>
          deps.readCmd(pid) match
            case Some(cmd) if StaleProcessGuard.isNebflowCommandLine(cmd) =>
              IO.blocking(stopVerified(pid, cmd, opts, deps))
            case Some(cmd) =>
              IO.pure(StopOutcome.Refused(refusalLine(pid, Some(cmd))))
            case None =>
              // Command line unreadable on every leg (Windows foreign-pid
              // shape, or a hardened sandbox) — degraded identity chain.
              degradedOutcome(pid, opts, deps)
      }

  /** The piece-1 refusal line — single spelling, reused by both branches. */
  private def refusalLine(pid: Long, cmd: Option[String]): String =
    val detail = cmd match
      case Some(c) => s"command: ${StaleProcessGuard.truncate(c)}"
      case None => "command line unreadable"
    s"pid $pid is not a verified ${nebflow.shared.Branding.productName} process ($detail) — refusing, no signal sent"

  // ── degraded identity chain (新设备首用批 2026-10-22) ─────────────────────

  /** Start time of `pid` in epoch ms (Windows-readable even when the command line is not). */
  def processStartInstantMs(pid: Long): Option[Long] =
    try
      import scala.jdk.OptionConverters.*
      ProcessHandle
        .of(pid)
        .toScala
        .flatMap(_.info.startInstant.toScala)
        .map(i => i.toEpochMilli)
    catch case _: Exception => None

  sealed trait DegradedIdentity

  /** A PASSING layer — carries the name surfaced in the stop declaration line. */
  sealed trait DegradedPass extends DegradedIdentity:
    def layer: String

  object DegradedIdentity:

    /** layer names surface in the stop declaration line. */
    val LayerMetaTime = "pid-meta + start-time match"
    val LayerPortSameSource = "port listener (same source as pid)"

    /** Layer A passed: launcher-written meta names this pid and its start time matches. */
    final case class MetaTime(layer: String = LayerMetaTime) extends DegradedPass

    /** Layer B passed: the pid listens on the CLI's own resolved gateway port, off the protected set. */
    final case class PortSameSource(layer: String = LayerPortSameSource) extends DegradedPass

    /** Every layer failed — stop stays refused (this is the safety floor). */
    final case class Refused(reason: String) extends DegradedIdentity

  end DegradedIdentity

  /**
   * Degraded identity chain for a pid whose command line is UNREADABLE.
   * Pure — every input is probed by the caller (spec-injectable).
   *
   * Layer A (meta + start time): `.pid.meta` was written BY our boot
   * machinery for exactly this pid, and the live process's start instant
   * matches the meta's `startedAt` (written seconds after spawn). A reused
   * pid has a start instant days/weeks after `startedAt` — rejected.
   * `startedBy` must be launcher / restart-script: instances our own
   * machinery spawned (never a dev host). A `sbt`/unknown owner NEVER
   * passes — the dev-host gate cannot read markers, so only spawn shapes
   * that cannot be dev hosts may proceed. With layer A the dev-host gate is
   * analytically satisfied (launcher children never carry dev-host markers),
   * so a default-port (8080) instance is stoppable — the exact author case.
   *
   * Layer B (port same source): the pid listens on the gateway port the CLI
   * itself resolves (--port > GATEWAY_PORT > 8080) — the port and the pid
   * come from our own state, high confidence it is our instance. Refused
   * when the pid holds a PROTECTED port (8080/8097): there the dev-host
   * gate cannot be evaluated (no readable markers), and 8080 is precisely
   * where a dev fork may sit — those must go through the readable-cmdline
   * face or --tree.
   */
  private[cli] def degradedIdentity(
    pid: Long,
    meta: Option[PidMeta.Meta],
    startInstantMs: Option[Long],
    listeningPorts: List[Int],
    gatewayPort: Int,
    protectedPorts: List[Int] = ProtectedPorts
  ): DegradedIdentity =
    // ── layer A ─────────────────────────────────────────────────────────
    val metaOk = meta.exists { m =>
      val ownerOk =
        m.startedBy == PidMeta.StartedByLauncher || m.startedBy == PidMeta.StartedByRestartScript
      val pidOk = m.pid.contains(pid)
      val timeOk = (m.startedAt, startInstantMs) match
        case (Some(iso), Some(actualMs)) =>
          val tolerance = DegradedToleranceMs
          scala.util.Try(java.time.Instant.parse(iso)).toOption match
            case Some(writtenAt) => math.abs(actualMs - writtenAt.toEpochMilli) <= tolerance
            case None => false
        case _ => false
      ownerOk && pidOk && timeOk
    }
    if metaOk then DegradedIdentity.MetaTime()
    else
      // ── layer B ───────────────────────────────────────────────────────
      val holdsProtected = listeningPorts.exists(protectedPorts.contains)
      val portOk = listeningPorts.contains(gatewayPort) && !holdsProtected
      if portOk then DegradedIdentity.PortSameSource()
      else
        val why =
          if holdsProtected then
            s"no pid-meta/start-time identity and the process listens on a protected port " +
              s"(${listeningPorts.intersect(protectedPorts).sorted.mkString("/")}) where the " +
              "dev-host gate cannot be evaluated without a readable command line"
          else
            val metaState = meta match
              case None => "no .pid.meta"
              case Some(m) =>
                s".pid.meta startedBy=${m.startedBy}${
                    if m.pid.contains(pid) then "" else s" names pid ${m.pid.getOrElse("-")}"
                  }" +
                  s"${if m.startedAt.isEmpty then " without startedAt" else ""}"
            s"no pid-meta/start-time identity ($metaState) and the pid does not listen on the " +
              s"gateway port $gatewayPort (listening: ${
                  if listeningPorts.isEmpty then "none" else listeningPorts.sorted.mkString(",")
                })"
        DegradedIdentity.Refused(why)
      end if
    end if
  end degradedIdentity

  /** Start-time match tolerance: the meta is written seconds after spawn; 10 min absorbs slow boots. */
  private[cli] val DegradedToleranceMs: Long = 600_000L

  /**
   * The degraded face of piece 1: resolve the gateway port (same source as
   * every gateway-facing command), run [[degradedIdentity]], and on a pass
   * enter [[stopVerified]] with a declaration naming the layer. On a fail
   * the refusal carries the piece-1 line plus the chain's failure detail.
   */
  private def degradedOutcome(pid: Long, opts: StopOptions, deps: StopDeps): IO[StopOutcome] =
    resolveGatewayPort(deps).flatMap { port =>
      IO.blocking {
        val verdict = degradedIdentity(
          pid,
          deps.metaRead(),
          deps.startInstantMs(pid),
          deps.listeningPortsOf(pid),
          port
        )
        verdict match
          case refused: DegradedIdentity.Refused =>
            StopOutcome.Refused(refusalLine(pid, None) + s" (degraded checks: ${refused.reason})")
          case passed: DegradedPass =>
            // Synthetic marker-free command line: the dev-host gate inside
            // stopVerified must NOT pattern-match a fake string. It is safe
            // by construction here — MetaTime only passes for
            // launcher/restart-script children (never dev hosts) and
            // PortSameSource never passes on a protected port.
            stopVerified(pid, s"(identity via degraded chain: ${passed.layer})", opts, deps, Some(passed.layer))
      }
    }

  /**
   * Gateway port resolution — the SAME source the CLI's gateway-facing
   * commands use (GatewayClient.readPort: --port > GATEWAY_PORT > 8080).
   * Specs inject `gatewayPortOverride`; the live face resolves lazily (only
   * when the degraded face is actually entered). Resolution failure falls
   * back to 8080 (readPort's own default).
   */
  private def resolveGatewayPort(deps: StopDeps): IO[Int] =
    deps.gatewayPortOverride match
      case Some(p) => IO.pure(p)
      case None =>
        GatewayClient.readPort
          .handleError(_ => 8080)

  private def stopVerified(
    pid: Long,
    cmd: String,
    opts: StopOptions,
    deps: StopDeps,
    degradedVia: Option[String] = None
  ): StopOutcome =
    // Hard gate: a dev-host-marked process listening on 8080/8097 is never
    // stoppable through this face — --force does not bypass it. The ports
    // come from the target pid's own listeners (F-3), never the caller's view.
    val ports = deps.listeningPortsOf(pid)
    val hardGate =
      ports.exists(ProtectedPorts.contains) && DevHostMarkers.exists(cmd.contains)
    if hardGate then
      return StopOutcome.Refused(
        s"pid $pid listens on ${ports.intersect(ProtectedPorts).sorted.mkString("/")} with a dev-host " +
          s"command line (${StaleProcessGuard.truncate(cmd)}) — refusing; this gate is not bypassable " +
          "with --force (dev host instances are managed by their own session)"
      )

    // Ownership gate (piece 2): only launcher-started instances are managed
    // by default; --force skips this gate only, --tree also unlocks it and
    // extends the kill set. Piece 1 and the hard gate are never bypassed.
    val startedBy = PidMeta.read().map(_.startedBy).getOrElse(PidMeta.StartedByUnknown)
    if startedBy != PidMeta.StartedByLauncher && !opts.force && !opts.tree then
      val hint = startedBy match
        case PidMeta.StartedBySbt =>
          "instance was started by an sbt session — stop it there, or rerun with --tree (kills the sbt chain) or --force"
        case PidMeta.StartedByRestartScript =>
          "instance was started by nebflow-restart.sh — use that script to manage it, or rerun with --tree/--force"
        case _ =>
          "instance ownership is unknown — rerun with --tree or --force if you really own this process"
      return StopOutcome.Refused(s"refusing to stop pid $pid: $hint")

    val targetsEither =
      if opts.tree then treeTargets(pid, deps)
      else Right((List(pid), None))
    targetsEither match
      case Left(refusal) =>
        StopOutcome.Refused(s"refusing tree stop of pid $pid: $refusal")
      case Right((targets, declaration)) =>
        targets.foreach(deps.destroy)
        val gracefulDied = waitUntil(deps, pid, deps.graceTimeoutMs)
        if !gracefulDied then
          targets.foreach(deps.destroyForcibly)
          waitUntil(deps, pid, deps.forceTimeoutMs)

        if deps.isRunning(pid) then return StopOutcome.Failed(s"process $pid did not exit after TERM/KILL")

        // Piece 3: port-release confirmation from the TARGET pid's own
        // listener set. Empty after death (or lsof unavailable) = released /
        // fail-open — a broken probe never blocks a stop.
        val remaining = deps.listeningPortsOf(pid)
        if remaining.nonEmpty then
          val deadline = System.currentTimeMillis() + deps.drainTimeoutMs
          var busy = remaining.filter(deps.listenerAlive)
          while busy.nonEmpty && System.currentTimeMillis() < deadline do
            if deps.pollMs > 0 then Thread.sleep(deps.pollMs)
            busy = remaining.filter(deps.listenerAlive)
          if busy.nonEmpty then return StopOutcome.Failed(s"ports still served after stop: ${busy.mkString(", ")}")

        removePid()
        val base = declaration.getOrElse(s"${nebflow.shared.Branding.productName} stopped (pid $pid)")
        StopOutcome.Stopped(degradedVia.fold(base)(via => s"$base — identity via degraded chain: $via"))

    end match

  end stopVerified

  private def waitUntil(deps: StopDeps, pid: Long, timeoutMs: Long): Boolean =
    val deadline = System.currentTimeMillis() + timeoutMs
    var alive = deps.isRunning(pid)
    while alive && System.currentTimeMillis() < deadline do
      if deps.pollMs > 0 then Thread.sleep(deps.pollMs)
      alive = deps.isRunning(pid)
    !alive

  /** Parent chain of `pid`, pid-first, at most `maxDepth` hops, stopping before pid ≤ 1. */
  private def parentChain(pid: Long, maxDepth: Int = 16): List[Long] =
    val chain = ListBuffer.empty[Long]
    chain += pid
    var cur = ProcessHandle.of(pid).flatMap[ProcessHandle](_.parent).orElse(null)
    var depth = 0
    while cur != null && depth < maxDepth && cur.pid > 1 do
      chain += cur.pid
      cur = cur.parent.orElse(null)
      depth += 1
    chain.toList

  /**
   * Pure: the first member of `chain` (pid-first, upward) whose command line
   * carries an sbt marker — the root of a managed sbt tree.
   */
  private[cli] def sbtRootFromChain(
    chain: List[Long],
    readCmd: Long => Option[String]
  ): Option[Long] =
    // nearest sbt ancestor = closest to the target, i.e. first found walking up
    chain.find(pid => readCmd(pid).exists(isSbtCommandLine))

  /**
   * Pure: kill set for `--tree`. With `sbtRoot` inside the chain: the chain
   * from root down to pid plus every member's descendants — each verified
   * (stand or sbt marker) first; one unverifiable member refuses the whole
   * tree. With no sbt root: the single pid (degraded mode, R2-7) — `--tree`
   * on a daemon tree / orphan means "unlock + kill exactly the target".
   * Result ordered top-down (root side first).
   */
  private[cli] def treeKillSet(
    chain: List[Long],
    sbtRoot: Option[Long],
    descendantsOf: Long => List[Long],
    readCmd: Long => Option[String]
  ): Either[String, List[Long]] =
    sbtRoot match
      case Some(root) if chain.contains(root) =>
        val subchain = chain.take(chain.indexOf(root) + 1) // pid..root inclusive
        val members = (subchain ++ subchain.flatMap(descendantsOf)).distinct
        members
          .map { m =>
            readCmd(m) match
              case Some(c) if StaleProcessGuard.isNebflowCommandLine(c) || isSbtCommandLine(c) =>
                Right(m)
              case Some(c) =>
                Left(
                  s"member $m is not a verified ${nebflow.shared.Branding.productName}/sbt process: ${StaleProcessGuard.truncate(c)}"
                )
              case None =>
                Left(s"member $m command line unreadable — refusing the whole tree")
          }
          .collectFirst { case Left(e) => e }
          .toLeft(members.reverse) // top-down: root first, target last
      case _ =>
        Right(List(chain.head))

  private def treeTargets(pid: Long, deps: StopDeps): Either[String, (List[Long], Option[String])] =
    val chain = parentChain(pid)
    val root = sbtRootFromChain(chain, deps.readCmd)
    treeKillSet(chain, root, descendantsOfReal, deps.readCmd).map { targets =>
      if root.isDefined then (targets, Some(s"stopping sbt-managed tree: ${targets.mkString(", ")}"))
      else (targets, Some("no sbt ancestor in process chain; stopping single process"))
    }

  private def descendantsOfReal(pid: Long): List[Long] =
    try
      import scala.jdk.OptionConverters.*
      import scala.jdk.CollectionConverters.*
      ProcessHandle
        .of(pid)
        .toScala
        .map(_.descendants().iterator().asScala.map(_.pid).toList)
        .getOrElse(Nil)
    catch case _: Exception => Nil

end ProcessManager
