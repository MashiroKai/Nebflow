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
   * trees) are constructible with zero real signals.
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
    pollMs: Long = 100L
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
   *   1. pid identity check (NebflowMarkers; anything else = refuse, zero signal)
   *   +. 8080/8097 dev-host hard gate (above --force)
   *   2. ownership gate + optional tree handling (`--tree` unlocks non-launcher
   *      owners; when an sbt ancestor exists the whole chain from it dies,
   *      otherwise the stop degrades to the single pid with a declaration)
   *   3. port-release confirmation (ports reverse-looked-up from the TARGET
   *      pid, never from the caller's port view — F-3)
   *   4. non-zero exit on refusal/failure is the caller's mapping (StopCommand)
   */
  def stopHardened(opts: StopOptions, deps: StopDeps = StopDeps()): IO[StopOutcome] =
    IO.blocking {
      readPid() match
        case None =>
          StopOutcome.NotRunning
        case Some(pid) if !deps.isRunning(pid) =>
          // Stale pid file — idempotent cleanup, still "not running" success.
          removePid()
          StopOutcome.NotRunning
        case Some(pid) =>
          deps.readCmd(pid) match
            case Some(cmd) if StaleProcessGuard.isNebflowCommandLine(cmd) =>
              stopVerified(pid, cmd, opts, deps)
            case other =>
              val detail = other match
                case Some(cmd) => s"command: ${StaleProcessGuard.truncate(cmd)}"
                case None => "command line unreadable"
              StopOutcome.Refused(
                s"pid $pid is not a verified ${nebflow.shared.Branding.productName} process ($detail) — refusing, no signal sent"
              )
    }

  private def stopVerified(
      pid: Long,
      cmd: String,
      opts: StopOptions,
      deps: StopDeps
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

        if deps.isRunning(pid) then
          return StopOutcome.Failed(s"process $pid did not exit after TERM/KILL")

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
          if busy.nonEmpty then
            return StopOutcome.Failed(s"ports still served after stop: ${busy.mkString(", ")}")

        removePid()
        StopOutcome.Stopped(declaration.getOrElse(s"${nebflow.shared.Branding.productName} stopped (pid $pid)"))

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
                Left(s"member $m is not a verified ${nebflow.shared.Branding.productName}/sbt process: ${StaleProcessGuard.truncate(c)}")
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
