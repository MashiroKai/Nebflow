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
  def killProcessTree(p: Process): IO[Unit] =
    IO.blocking {
      try
        val root = p.toHandle
        var known: List[ProcessHandle] = Nil

        def sweep(force: Boolean): Unit =
          val fresh = unionDescendants(root)
          val targets = (fresh ++ known).distinct.filter(_.isAlive)
          known = targets
          targets.foreach(ph => if force then ph.destroyForcibly() else ph.destroy())
          if force then root.destroyForcibly() else root.destroy()

        sweep(force = false) // SIGTERM
        if !p.waitFor(5, TimeUnit.SECONDS) then
          sweep(force = true) // SIGKILL — fresh snapshot catches late forkers
          p.waitFor(2, TimeUnit.SECONDS)
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
