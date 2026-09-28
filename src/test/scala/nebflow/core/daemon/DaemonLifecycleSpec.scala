package nebflow.core.daemon

import cats.effect.std.Dispatcher
import cats.effect.unsafe.implicits.global
import cats.effect.{IO, Resource}
import io.circe.parser.decode
import io.circe.syntax.*
import munit.FunSuite

import java.nio.charset.StandardCharsets
import scala.jdk.StreamConverters.*

/**
 * Daemon lifecycle hardening tests (⑥ Dev Server daemon lifecycle, 2026-08-24):
 *   - spawn-marker persistence (stale-process reclaim at boot);
 *   - boot reclaim kills ONLY stale processes of a previous instance
 *     (pid-reuse guard: an unrelated live process is never touched);
 *   - reconcile stops processes whose config was removed/changed in daemons.json
 *     (hot-edit → no ghost process holding the port);
 *   - remove() drops the entry (takeover fiber exits — no deleted-daemon
 *     resurrection).
 */
class DaemonLifecycleSpec extends FunSuite:

  private def withDispatcher[A](f: Dispatcher[IO] => A): A =
    val (disp, release) = Dispatcher.parallel[IO].allocated.unsafeRunSync()
    try f(disp)
    finally release.unsafeRunSync()

  private def tmpDir(name: String): os.Path =
    os.pwd / "target" / "daemon-lifecycle" / s"$name-${System.nanoTime()}"

  private def await(pred: => Boolean, timeoutMs: Long = 15000, intervalMs: Long = 100): Boolean =
    val deadline = System.currentTimeMillis() + timeoutMs
    var ok = false
    while System.currentTimeMillis() < deadline && !ok do
      if pred then ok = true
      else Thread.sleep(intervalMs)
    ok

  private def isAlive(pid: Long): Boolean =
    Option(ProcessHandle.of(pid).orElse(null)).exists(_.isAlive)

  private def markerMap(path: os.Path): Map[String, DaemonPidMarker] =
    if !os.exists(path) then Map.empty
    else
      decode[DaemonPidMarkerFile](os.read(path)) match
        case Right(f) => f.markers.map(m => m.id -> m).toMap
        case Left(err) => fail(s"marker file unreadable: $err")

  // ── Spawn-marker lifecycle ─────────────────────────────
  test("marker is written on start and removed on clean stop") {
    withDispatcher { disp =>
      val dir = tmpDir("marker-lifecycle")
      val markerFile = dir / "daemon-pids.json"
      val svc = new DaemonService(disp, markerFile)
      val cfg = DaemonConfig("sleeper", "Sleeper", List("sleep", "30"))
      val state = svc.start(cfg).unsafeRunSync()
      assertEquals(state.status, DaemonStatus.Running)
      val pid = state.pid.getOrElse(fail("expected a pid"))

      assert(
        await(markerMap(markerFile).contains("sleeper")),
        "marker must be persisted after start"
      )
      assertEquals(markerMap(markerFile)("sleeper").pid, pid)

      svc.stop("sleeper").unsafeRunSync()
      assert(await(markerMap(markerFile).isEmpty), "marker must be removed after stop")
      assert(!isAlive(pid), s"process $pid must be dead after stop")
    }
  }

  test("marker survives abnormal process death (crash) and is dropped on remove") {
    withDispatcher { disp =>
      val dir = tmpDir("marker-crash")
      val markerFile = dir / "daemon-pids.json"
      val svc = new DaemonService(disp, markerFile)
      val cfg = DaemonConfig("crashy", "Crashy", List("sleep", "60"))
      val state = svc.start(cfg).unsafeRunSync()
      assertEquals(state.status, DaemonStatus.Running)
      assert(await(markerMap(markerFile).contains("crashy")))

      // Simulate an abnormal crash: force-kill the process out from under us
      // (no stopRequested, no doStop — like a SIGKILL'd child).
      val pid = state.pid.get
      Option(ProcessHandle.of(pid).orElse(null)).foreach(_.destroyForcibly())
      assert(await(!isAlive(pid)), "process should be dead after force kill")

      // Marker file still holds the (now-dead) pid — harmless, cleaned at boot.
      assert(markerMap(markerFile).contains("crashy"), "marker must survive a crash")

      // remove() clears it.
      svc.remove("crashy").unsafeRunSync()
      assert(await(markerMap(markerFile).isEmpty), "remove must clear the marker")
    }
  }

  // ── Boot reclaim (stale process of a previous instance) ─

  test("autoStart reclaims a stale process recorded in the marker file, then starts fresh") {
    withDispatcher { disp =>
      val dir = tmpDir("reclaim")
      val markerFile = dir / "daemon-pids.json"
      val store = new DaemonStore(dir / "daemons.json")

      // Simulate the previous instance: a live daemon process it spawned and
      // recorded, then died without running its shutdown hook (SIGKILL).
      // No port configured — the reclaim works on pid+start-time alone and the
      // subsequent autoStart spawn is deterministic (no port-probe race).
      val staleProc = new ProcessBuilder("sleep", "60").start()
      val stalePid = staleProc.pid()
      val staleStart = staleProc.toHandle.info.startInstant().get().toEpochMilli
      val marker = DaemonPidMarker("web", stalePid, "Stale Web", None, staleStart)
      os.write(markerFile, DaemonPidMarkerFile(List(marker)).asJson.noSpaces, createFolders = true)
      assert(isAlive(stalePid), "precondition: stale process alive")

      store.add(DaemonConfig("web", "Web", List("sleep", "30"), autoStart = true)).unsafeRunSync()

      val svc = new DaemonService(disp, markerFile)
      svc.autoStart(store).unsafeRunSync()

      assert(await(!isAlive(stalePid)), s"stale process $stalePid must be reclaimed (killed)")

      // autoStart then spawned a fresh process under the same id; the marker
      // now records the FRESH pid (never the reclaimed stale one).
      val fresh = svc.getState("web").unsafeRunSync()
      assert(fresh.exists(_.status == DaemonStatus.Running), s"fresh daemon should run, got $fresh")
      val freshPid = fresh.flatMap(_.pid).getOrElse(fail("fresh process must have a pid"))
      assert(freshPid != stalePid && isAlive(freshPid), "fresh process must be alive and new")
      assert(markerMap(markerFile).get("web").map(_.pid).contains(freshPid), "marker must record the fresh pid")

      svc.stopAll().unsafeRunSync()
    }
  }

  test("autoStart does NOT kill a pid-reused / foreign live process (safety guard)") {
    withDispatcher { disp =>
      val dir = tmpDir("reclaim-guard")
      val markerFile = dir / "daemon-pids.json"
      val store = new DaemonStore(dir / "daemons.json")

      // A live process the marker WRONGLY points at (pid reused / foreign):
      // startedAt is deliberately 60s in the past → start-time mismatch.
      val foreign = new ProcessBuilder("sleep", "60").start()
      val foreignPid = foreign.pid()
      val wrongStart = System.currentTimeMillis() - 60000
      os.write(markerFile, DaemonPidMarkerFile(List(DaemonPidMarker("web", foreignPid, "Stale Web", None, wrongStart))).asJson.noSpaces, createFolders = true)

      store.add(DaemonConfig("web", "Web", List("sleep", "30"), autoStart = true)).unsafeRunSync()

      val svc = new DaemonService(disp, markerFile)
      svc.autoStart(store).unsafeRunSync()

      assert(isAlive(foreignPid), s"foreign process $foreignPid must NOT be killed (pid-reuse guard)")

      // The daemon was started fresh (autoStart); the marker now records the
      // fresh pid, not the foreign one.
      val fresh = svc.getState("web").unsafeRunSync()
      assert(fresh.exists(_.status == DaemonStatus.Running))
      val freshPid = fresh.flatMap(_.pid).getOrElse(fail("fresh process must have a pid"))
      assert(markerMap(markerFile).get("web").map(_.pid).contains(freshPid), "marker must record the fresh pid")
      assert(freshPid != foreignPid, "marker must not record the foreign process")
      foreign.destroyForcibly()
      svc.stopAll().unsafeRunSync()
    }
  }

  // ── Reconcile (hot-edit of daemons.json) ───────────────

  test("reconcile stops a running daemon whose config was removed from the store") {
    withDispatcher { disp =>
      val dir = tmpDir("reconcile-removed")
      val markerFile = dir / "daemon-pids.json"
      val store = new DaemonStore(dir / "daemons.json")
      val cfg = DaemonConfig("web", "Web", List("sleep", "30"), autoStart = true)
      store.add(cfg).unsafeRunSync()

      val svc = new DaemonService(disp, markerFile)
      val state = svc.start(cfg).unsafeRunSync()
      assertEquals(state.status, DaemonStatus.Running)
      val pid = state.pid.get
      assert(await(markerMap(markerFile).contains("web")))

      // Hot-edit: daemon removed from daemons.json while still running.
      store.remove("web").unsafeRunSync()
      svc.reconcile(store.load().unsafeRunSync()).unsafeRunSync()

      assert(!isAlive(pid), s"process $pid must be stopped after config removal")
      assert(svc.getState("web").unsafeRunSync().isEmpty, "entry must be dropped")
      assert(markerMap(markerFile).isEmpty, "marker must be cleared")
    }
  }

  test("reconcile stops a running daemon whose config changed (same id, new command)") {
    withDispatcher { disp =>
      val dir = tmpDir("reconcile-changed")
      val markerFile = dir / "daemon-pids.json"
      val store = new DaemonStore(dir / "daemons.json")
      val oldCfg = DaemonConfig("web", "Web", List("sleep", "30"), autoStart = true)
      store.add(oldCfg).unsafeRunSync()

      val svc = new DaemonService(disp, markerFile)
      val state = svc.start(oldCfg).unsafeRunSync()
      val pid = state.pid.get
      assert(await(markerMap(markerFile).contains("web")))

      // Hot-edit: same id, different command.
      val newCfg = oldCfg.copy(command = List("sleep", "45"))
      store.update("web", _ => newCfg).unsafeRunSync()
      svc.reconcile(store.load().unsafeRunSync()).unsafeRunSync()

      assert(!isAlive(pid), s"old-config process $pid must be stopped")
      assert(svc.getState("web").unsafeRunSync().isEmpty, "old entry must be dropped")
      assert(markerMap(markerFile).isEmpty, "marker must be cleared")

      // New config starts fresh on demand.
      val restarted = svc.startById(store, "web").unsafeRunSync()
      assert(restarted.exists(_.status == DaemonStatus.Running))
      svc.stopAll().unsafeRunSync()
    }
  }

  test("remove() stops and drops the entry (deleted daemon cannot be resurrected by takeover)") {
    withDispatcher { disp =>
      val dir = tmpDir("remove")
      val markerFile = dir / "daemon-pids.json"
      val store = new DaemonStore(dir / "daemons.json")
      val cfg = DaemonConfig("web", "Web", List("sleep", "30"), autoStart = true)
      store.add(cfg).unsafeRunSync()

      val svc = new DaemonService(disp, markerFile)
      val state = svc.start(cfg).unsafeRunSync()
      val pid = state.pid.get

      svc.remove("web").unsafeRunSync()
      assert(!isAlive(pid), s"process $pid must be stopped by remove")
      assert(svc.getState("web").unsafeRunSync().isEmpty, "entry must be gone")
      assert(markerMap(markerFile).isEmpty, "marker must be gone")
      assert(svc.stop("web").unsafeRunSync().isEmpty, "stop on a removed id returns None")
    }
  }

  // ── probePort correctness (phantom ::1 regression, 2026-08-24) ─────

  test("daemon start: real listener on the port → Stopped+portOpen (no duplicate spawn)") {
    withDispatcher { disp =>
      val dir = tmpDir("probe-real")
      val svc = new DaemonService(disp, dir / "daemon-pids.json")
      val ss = new java.net.ServerSocket()
      ss.bind(new java.net.InetSocketAddress("127.0.0.1", 0))
      val port = ss.getLocalPort
      try
        val state = svc.start(DaemonConfig("web", "Web", List("sleep", "30"), port = Some(port))).unsafeRunSync()
        assertEquals(state.status, DaemonStatus.Stopped, s"port $port is held by a real listener — must not spawn")
        assertEquals(state.portOpen, Some(true))
      finally ss.close()
      svc.stopAll().unsafeRunSync()
    }
  }

  test("daemon start: freed port → Running (probePort has NO phantom ::1 false-positive)") {
    withDispatcher { disp =>
      val dir = tmpDir("probe-free")
      val svc = new DaemonService(disp, dir / "daemon-pids.json")
      // Bind then release: the port is free for the start that follows.
      // Pre-fix, `new Socket().connect(::1:port)` phantom-succeeded on
      // macOS+JDK23 → EVERY free port looked open → daemon stayed
      // Stopped+portOpen forever ("Dev Server 无法打开").
      val port = {
        val ss = new java.net.ServerSocket()
        try
          ss.bind(new java.net.InetSocketAddress("127.0.0.1", 0))
          ss.getLocalPort
        finally ss.close()
      }
      val state = svc.start(DaemonConfig("web", "Web", List("sleep", "30"), port = Some(port))).unsafeRunSync()
      assertEquals(state.status, DaemonStatus.Running, s"port $port is free — daemon must start (got $state)")
      svc.stopAll().unsafeRunSync()
    }
  }

  // ── ProcessTree PID-based kill ─────────────────────────

  test("ProcessTree.killProcessTree(ProcessHandle) kills the whole tree") {
    assume(!sys.props.getOrElse("os.name", "").toLowerCase.contains("win"), "requires POSIX sh")
    val proc = new ProcessBuilder("sh", "-c", "sleep 30 & sleep 30 & wait").start()
    val rootPid = proc.pid()
    Thread.sleep(500)
    val children =
      Option(ProcessHandle.of(rootPid).orElse(null))
        .map(_.descendants().toScala(List))
        .getOrElse(Nil)
    assert(children.nonEmpty, "expected sh to have spawned sleep children")

    nebflow.core.util.ProcessTree.killProcessTree(proc.toHandle).unsafeRunSync()
    Thread.sleep(400)

    assert(!isAlive(rootPid), s"root $rootPid still alive")
    val survivors = children.filter(ph => isAlive(ph.pid))
    assertEquals(survivors.map(_.pid), Nil, s"descendants still alive: ${survivors.map(_.pid).mkString(", ")}")
  }

end DaemonLifecycleSpec
