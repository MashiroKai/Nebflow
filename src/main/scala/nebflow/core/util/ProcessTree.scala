package nebflow.core.util

import cats.effect.IO
import scala.jdk.StreamConverters.*
import java.util.concurrent.TimeUnit

object ProcessTree:

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
   * Hardened strategy:
   *  - enumerate descendants via ProcessHandle AND a `ps`-based ppid walk
   *    (union), so deep trees are found;
   *  - remember every pid ever seen — a reparented orphan is unreachable by
   *    tree walks afterwards, but still killable BY PID from the remembered
   *    set;
   *  - SIGTERM all → wait 5s → SIGKILL all (fresh enumeration ∪ remembered)
   *    → wait 2s → one more SIGKILL sweep for late forkers.
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

        sweep(force = false) // SIGTERM
        waitExit(5000)
        // Kill survivors regardless of root state: a descendant that ignored
        // SIGTERM survives even when the root exits within the window (the
        // old Process.waitFor gating skipped the force sweep in that case,
        // leaving reparented orphans — the #22 incident shape).
        if root.isAlive || known.exists(_.isAlive) then
          sweep(force = true) // SIGKILL — fresh snapshot catches late forkers
          waitExit(2000)
          sweep(force = true) // final sweep: TERM survivors / stragglers
      catch case _: Exception => ()
    }

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
   */
  private def descendantsViaPs(rootPid: Long): List[ProcessHandle] =
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
        val out = new String(psProc.getInputStream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
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
    catch case _: Exception => Nil
end ProcessTree
