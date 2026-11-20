package nebflow.core.util

import cats.effect.IO
import nebflow.shared.NebflowLogger

import java.util.concurrent.TimeUnit

import scala.jdk.StreamConverters.*

object ProcessTree:

  private val log: NebflowLogger = NebflowLogger.forName("nebflow.core.util.ProcessTree")

  /** Host OS name, read once — the single input of [[platformPlan]]. */
  private val hostOsName: String = System.getProperty("os.name", "")

  /**
   * Tree-kill plan. Pure function of the OS name so every leg is testable off
   * platform (T6 red spec pins all three).
   */
  private[util] sealed trait Plan

  private[util] object Plan:
    /**
     * Windows: `taskkill /F /T /PID <pid>`.
     *
     * `ProcessHandle.destroy()/destroyForcibly()` are `TerminateProcess` on a
     * single pid — a reparented grandchild escapes, and `ps` does not exist so
     * the ppid-walk enumeration is unavailable. `taskkill /T` is the in-box
     * mechanism that walks the parent chain in kernel space.
     */
    case object TaskKill extends Plan

    /**
     * macOS/darwin: the historical TERM → 5s → KILL escalation, with a
     * SIGSTOP freeze inserted between the "decide to hard-kill" moment and the
     * SIGKILL so nothing can fork while the tree is being resolved.
     */
    case object SignalStopThenKill extends Plan

    /** POSIX default: the historical TERM → 5s → KILL → resweep, unchanged. */
    case object TermThenKill extends Plan

  end Plan

  private[util] def platformPlan(osName: String): Plan =
    val n = Option(osName).getOrElse("").toLowerCase(java.util.Locale.ROOT)
    if n.contains("windows") then Plan.TaskKill
    else if n.contains("mac") || n.contains("darwin") then Plan.SignalStopThenKill
    else Plan.TermThenKill

  /** Single point of Windows detection — everything else asks this. */
  private[util] val isWindows: Boolean = platformPlan(hostOsName) == Plan.TaskKill

  private val hostPlan: Plan = platformPlan(hostOsName)

  /**
   * Absolute path of the trusted `taskkill.exe`. Never resolved through PATH:
   * an earlier PATH entry could shadow `taskkill.exe` and we would execute
   * whatever it points at with our privileges (pi `utils/shell.ts` / Cline
   * convention: "use the trusted System32 executable so cleanup does not
   * depend on PATH").
   */
  private[util] def taskkillPath: String =
    val systemRoot = sys.env.getOrElse("SystemRoot", "C:\\Windows")
    val exe = new java.io.File(new java.io.File(systemRoot, "System32"), "taskkill.exe")
    // SystemRoot is drive-absolute on Windows; the fallback keeps it absolute
    // even if some environment reported a relative root.
    if exe.isAbsolute then exe.getPath else exe.getAbsolutePath

  /**
   * `taskkill` argument vector: `/F` (force) + `/T` (whole tree — the point:
   * reparented descendants die with the root) + `/PID`.
   */
  private[util] def taskkillArgs(pid: Long): Seq[String] =
    Seq(taskkillPath, "/F", "/T", "/PID", pid.toString)

  /**
   * Kill a process and all its descendants.
   *
   * #22 (2026-08-19, 20:35 Backend incident): the old single-snapshot kill
   * missed grandchildren — `descendants()` is unreliable for deep trees on
   * macOS (sbt → sh → java, as shell.scala's own CPU sampler notes), and any
   * child forked AFTER the snapshot (or surviving SIGTERM while the parent
   * exits and it gets reparented to launchd) escaped both kill phases holding
   * the stdout/stderr pipes, blocking the caller's stream drains for as long
   * as the orphan lived (37 minutes in the incident).
   *
   * Hardened strategy (unchanged core):
   *  - enumerate descendants via ProcessHandle AND a `ps`-based ppid walk
   *    (union), so deep trees are found — on Windows the `ps` leg is absent by
   *    design (see [[descendantsViaPs]]), so the platform plan carries the
   *    tree reach instead (`taskkill /T`);
   *  - remember every pid ever seen — a reparented orphan is unreachable by
   *    tree walks afterwards, but still killable BY PID from the remembered
   *    set;
   *  - SIGTERM all → wait 5s → SIGKILL all (fresh enumeration ∪ remembered)
   *    → wait 2s → one more SIGKILL sweep for late forkers.
   *
   * T6 (author order 2026-11-04): the kill phases are dispatched by
   * [[Plan]] — `TaskKill` on Windows, `SignalStopThenKill` on macOS,
   * `TermThenKill` (the historical behaviour, verbatim) everywhere else.
   */
  def killProcessTree(p: Process): IO[Unit] = killProcessTree(p.toHandle)

  /**
   * PID-based variant of [[killProcessTree]] — for stale-process reclaim when
   * only the pid is known (daemon marker file from a previous instance).
   * Same hardened strategy; the final force sweep also runs when the ROOT
   * exits quickly but a descendant survived SIGTERM (reparented orphan holding
   * the port — the ghost-process shape this reclaim exists to remove).
   */
  def killProcessTree(root: ProcessHandle): IO[Unit] =
    IO.blocking {
      try
        var known: List[ProcessHandle] = Nil

        def sweep(force: Boolean): Unit =
          val fresh = unionDescendants(root)
          val targets = (fresh ++ known).distinct.filter(_.isAlive)
          known = targets
          targets.foreach(ph => if force then ph.destroyForcibly() else ph.destroy())
          if force then root.destroyForcibly() else root.destroy()

        def waitExit(ms: Long): Unit =
          var waited = 0L
          while root.isAlive && waited < ms do
            Thread.sleep(100)
            waited += 100

        def survivorsAlive: Boolean = root.isAlive || known.exists(_.isAlive)

        /** Freeze the still-alive members (root included) with SIGSTOP. */
        def freezeSurvivors(): Unit =
          val alive = (unionDescendants(root) ++ known).filter(_.isAlive)
          val pids =
            (alive.map(_.pid()) ++ (if root.isAlive then List(root.pid()) else Nil)).distinct
          signalStopAll(pids)

        hostPlan match
          case Plan.TermThenKill =>
            // Historical behaviour, verbatim: SIGTERM all → 5s graceful window
            // → if anything survived, SIGKILL (fresh snapshot catches late
            // forkers) → 2s → one more SIGKILL sweep.
            sweep(force = false)
            waitExit(5000)
            // Kill survivors regardless of root state: a descendant that
            // ignored SIGTERM survives even when the root exits within the
            // window (the old Process.waitFor gating skipped the force sweep
            // in that case, leaving reparented orphans — the #22 incident).
            if survivorsAlive then
              sweep(force = true)
              waitExit(2000)
              sweep(force = true)

          case Plan.SignalStopThenKill =>
            // macOS. ORDER MATTERS: a stopped process cannot handle SIGTERM,
            // so freezing FIRST would destroy the graceful-exit window we
            // promise callers (and that many tools rely on for cleanup).
            // Therefore: TERM round → 5s window (unchanged contract) → then
            // freeze the survivors and SIGKILL them.
            //
            // Why the freeze at all: without it, the "enumerate → SIGKILL"
            // gap is a race — a live child can be woken by, or can wake, the
            // parent shell, and the rest of `a; b` starts while we are still
            // resolving the tree (upstream pi issue #11252). Once frozen
            // nobody can fork, so the enumeration used for the freeze is
            // still valid when the SIGKILL lands. SIGKILL itself is
            // delivered to stopped processes normally (it is unhandleable),
            // and the final force sweep catches anything the freeze missed.
            //
            // BEST-EFFORT, not atomic: a child forked between the last
            // enumeration and the STOP signal escapes the freeze; a pid
            // recycled in that instant would freeze a stranger (the window is
            // sub-millisecond and the subsequent SIGKILL only targets pids
            // that are still in our remembered set). This mitigation narrows
            // the race, it does not close it.
            sweep(force = false)
            waitExit(5000)
            if survivorsAlive then
              freezeSurvivors()
              sweep(force = true)
              waitExit(2000)
              sweep(force = true)

          case Plan.TaskKill =>
            // Windows. There is no graceful phase on this platform:
            // destroy()/destroyForcibly() are TerminateProcess on one pid, so
            // the only tree-reach we have is `taskkill /F /T` (and it is
            // already forced). Run it FIRST, then keep the historical snapshot
            // sweeps as a safety net for the case taskkill itself failed
            // (missing binary, group-policy block, pid vanished mid-call).
            runTaskkill(root.pid())
            waitExit(5000)
            // Safety net — never skips the force sweep: after taskkill the
            // `known` set is empty, so a conditional guard would be untested
            // here; unconditional force sweeps are cheap no-ops when the tree
            // is already gone.
            sweep(force = true)
            waitExit(2000)
            sweep(force = true)
        end match
      catch case _: Exception => ()
    }

  /**
   * Run `taskkill /F /T /PID <pid>` with suppressed IO and a 10s watchdog.
   * Failures are swallowed (the caller's snapshot sweeps still run) but
   * logged, because a silent failure here would look like a successful kill.
   */
  private def runTaskkill(pid: Long): Unit =
    try
      val pb = new java.lang.ProcessBuilder(taskkillArgs(pid)*)
      pb.redirectErrorStream(true)
      pb.redirectOutput(java.lang.ProcessBuilder.Redirect.DISCARD)
      val proc = pb.start()
      if !proc.waitFor(10, TimeUnit.SECONDS) then
        proc.destroyForcibly()
        log.warnSync(
          s"taskkill /F /T /PID $pid did not finish within 10s (watchdog killed it) — falling back to snapshot sweeps"
        )
      else if proc.exitValue() != 0 then
        // taskkill exits 128 for "process not found": benign, but log it so a
        // real failure (policy block) is not invisible.
        log.warnSync(
          s"taskkill /F /T /PID $pid exited ${proc.exitValue()} — falling back to snapshot sweeps"
        )
    catch
      case e: Exception =>
        log.warnSync(
          s"taskkill /F /T /PID $pid failed (${e.getClass.getSimpleName}: ${e.getMessage}) — falling back to snapshot sweeps"
        )

  /**
   * SIGSTOP every pid in one `/bin/kill -STOP p1 p2 …` (absolute path, no PATH
   * reliance; `kill` is also a shell builtin, which we deliberately do not
   * want). Best-effort: a failure only logs — the SIGKILL phase follows.
   */
  private def signalStopAll(pids: List[Long]): Unit =
    if pids.nonEmpty then
      try
        val killCmd = List("/bin/kill", "-STOP") ++ pids.map(_.toString)
        val pb = new java.lang.ProcessBuilder(killCmd*)
        pb.redirectErrorStream(true)
        pb.redirectOutput(java.lang.ProcessBuilder.Redirect.DISCARD)
        val proc = pb.start()
        if !proc.waitFor(2, TimeUnit.SECONDS) then proc.destroyForcibly()
      catch
        case e: Exception =>
          log.warnSync(
            s"SIGSTOP freeze of ${pids.size} pid(s) failed (${e.getClass.getSimpleName}: ${e.getMessage}) — best-effort, continuing with SIGKILL"
          )

  /** Union of ProcessHandle descendants and the ps-ppid-walk descendants. */
  private def unionDescendants(root: ProcessHandle): List[ProcessHandle] =
    (descendantsViaHandle(root) ++ descendantsViaPs(root.pid())).distinct

  private def descendantsViaHandle(root: ProcessHandle): List[ProcessHandle] =
    try root.descendants().toScala(List)
    catch case _: Exception => Nil

  /**
   * Enumerate descendants via `ps -A -o pid=,ppid=` + ppid-tree walk. Catches
   * deeply nested / freshly forked processes that ProcessHandle.descendants()
   * misses on macOS. Best-effort: on any failure returns Nil (the handle walk
   * still runs).
   *
   * T6: on Windows there is no `ps` at all. The old code let the missing
   * binary throw and swallowed the exception into Nil, so the "dual
   * enumeration" silently degraded to a single snapshot. We now short-circuit
   * explicitly — the degradation is a declared property, not an accident, and
   * the tree reach comes from `taskkill /T` instead.
   */
  private def descendantsViaPs(rootPid: Long): List[ProcessHandle] =
    if isWindows then Nil
    else
      try
        val pb = new java.lang.ProcessBuilder("ps", "-A", "-o", "pid=,ppid=")
        pb.redirectInput(new java.io.File("/dev/null"))
        pb.redirectOutput(java.lang.ProcessBuilder.Redirect.PIPE)
        pb.redirectErrorStream(true)
        val psProc = pb.start()
        if !psProc.waitFor(2, TimeUnit.SECONDS) then
          psProc.destroyForcibly()
          Nil
        else
          val out =
            new String(psProc.getInputStream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
          psProc.getInputStream.close()
          val children = scala.collection.mutable.Map.empty[Long, List[Long]]
          out.linesIterator.foreach { line =>
            val parts = line.trim.split("\\s+")
            if parts.length >= 2 then
              parts(0).toLongOption.foreach { pid =>
                parts(1).toLongOption.foreach { ppid =>
                  children(ppid) = pid :: children.getOrElse(ppid, Nil)
                }
              }
          }
          def collect(pid: Long): List[Long] =
            children.getOrElse(pid, Nil).flatMap(child => child :: collect(child))
          collect(rootPid).flatMap { pid =>
            val opt = ProcessHandle.of(pid)
            if opt.isPresent then List(opt.get()) else Nil
          }
        end if
      catch case _: Exception => Nil
end ProcessTree
