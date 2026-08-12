package nebflow.core.daemon

import cats.effect.std.Dispatcher
import cats.effect.{IO, Resource}
import cats.effect.unsafe.implicits.global
import io.circe.parser.decode
import io.circe.syntax.*
import munit.FunSuite

import scala.jdk.StreamConverters.*

class DaemonSpec extends FunSuite:

  private def dispatcherResource: Resource[IO, Dispatcher[IO]] =
    Dispatcher.parallel[IO]

  private def withDispatcher[A](f: Dispatcher[IO] => A): A =
    val (disp, release) = Dispatcher.parallel[IO].allocated.unsafeRunSync()
    try f(disp)
    finally release.unsafeRunSync()

  // ── Model JSON codec tests ──────────────────────────────

  test("DaemonConfig encode/decode round-trip") {
    val cfg = DaemonConfig(
      id = "web",
      name = "Web Server",
      command = List("npm", "run", "dev"),
      cwd = Some("/home/project"),
      env = Map("PORT" -> "3000"),
      autoStart = true,
      restartOnExit = false
    )
    val json = cfg.asJson.noSpaces
    assertEquals(decode[DaemonConfig](json), Right(cfg))
  }

  test("DaemonState encode/decode round-trip") {
    val state = DaemonState(
      id = "web",
      name = "Web Server",
      status = DaemonStatus.Running,
      pid = Some(12345),
      startedAt = Some(999L),
      exitCode = None,
      recentOutput = "hello\nworld"
    )
    val json = state.asJson.noSpaces
    assertEquals(decode[DaemonState](json), Right(state))
  }

  test("DaemonConfigFile encode/decode round-trip") {
    val file = DaemonConfigFile(
      List(
        DaemonConfig("a", "A", List("echo", "hi")),
        DaemonConfig("b", "B", List("ls"), autoStart = true)
      )
    )
    val json = file.asJson.noSpaces
    assertEquals(decode[DaemonConfigFile](json), Right(file))
  }

  test("DaemonStatus encodes as lowercase string") {
    assert(DaemonStatus.Running.asJson.noSpaces.contains(""""running""""))
    assert(DaemonStatus.Stopped.asJson.noSpaces.contains(""""stopped""""))
    assert(DaemonStatus.Crashed.asJson.noSpaces.contains(""""crashed""""))
    assert(DaemonStatus.Starting.asJson.noSpaces.contains(""""starting""""))
    // must NOT be the Scala-3 derived object format {"Running": {}}
    assert(!DaemonStatus.Running.asJson.noSpaces.contains(""""Running""""))
  }

  test("DaemonStatus decode accepts lowercase and case-insensitive input") {
    assertEquals(decode[DaemonStatus](""""running""""), Right(DaemonStatus.Running))
    assertEquals(decode[DaemonStatus](""""RUNNING""""), Right(DaemonStatus.Running))
    assertEquals(decode[DaemonStatus](""""stopped""""), Right(DaemonStatus.Stopped))
    assert(decode[DaemonStatus](""""bogus"""").isLeft)
  }

  test("DaemonState serializes status as lowercase string") {
    val state = DaemonState(id = "web", name = "Web Server", status = DaemonStatus.Running)
    assertEquals(state.asJson.hcursor.downField("status").as[String], Right("running"))
    assertEquals(decode[DaemonState](state.asJson.noSpaces), Right(state))
  }

  // ── DaemonStore tests ──────────────────────────────────

  test("DaemonStore: load returns empty when file does not exist") {
    val tmpDir = os.pwd / "target" / "daemon-test" / s"store-${System.nanoTime()}"
    val store = new DaemonStore(tmpDir / "daemons.json")
    assertEquals(store.load().unsafeRunSync(), Nil)
  }

  test("DaemonStore: save and load round-trip") {
    val tmpDir = os.pwd / "target" / "daemon-test" / s"store-${System.nanoTime()}"
    val store = new DaemonStore(tmpDir / "daemons.json")
    store.add(DaemonConfig("test", "Test", List("echo", "hello"))).unsafeRunSync()
    val loaded = store.load().unsafeRunSync()
    assertEquals(loaded.length, 1)
    assertEquals(loaded.head.id, "test")
    assertEquals(loaded.head.command, List("echo", "hello"))
  }

  test("DaemonStore: add replaces by id") {
    val tmpDir = os.pwd / "target" / "daemon-test" / s"store-${System.nanoTime()}"
    val store = new DaemonStore(tmpDir / "daemons.json")
    store.add(DaemonConfig("a", "A", List("cmd1"))).unsafeRunSync()
    store.add(DaemonConfig("a", "A Updated", List("cmd2"))).unsafeRunSync()
    val loaded = store.load().unsafeRunSync()
    assertEquals(loaded.length, 1)
    assertEquals(loaded.head.name, "A Updated")
    assertEquals(loaded.head.command, List("cmd2"))
  }

  test("DaemonStore: remove deletes by id") {
    val tmpDir = os.pwd / "target" / "daemon-test" / s"store-${System.nanoTime()}"
    val store = new DaemonStore(tmpDir / "daemons.json")
    store.add(DaemonConfig("a", "A", List("cmd"))).unsafeRunSync()
    store.add(DaemonConfig("b", "B", List("cmd"))).unsafeRunSync()
    store.remove("a").unsafeRunSync()
    val loaded = store.load().unsafeRunSync()
    assertEquals(loaded.length, 1)
    assertEquals(loaded.head.id, "b")
  }

  test("DaemonStore: update modifies by id") {
    val tmpDir = os.pwd / "target" / "daemon-test" / s"store-${System.nanoTime()}"
    val store = new DaemonStore(tmpDir / "daemons.json")
    store.add(DaemonConfig("a", "A", List("cmd"), autoStart = false)).unsafeRunSync()
    val updated = store.update("a", _.copy(autoStart = true)).unsafeRunSync()
    assertEquals(updated.map(_.autoStart), Some(true))
    assert(store.load().unsafeRunSync().head.autoStart)
  }

  // ── DaemonService tests ────────────────────────────────

  test("DaemonService: getStates returns Stopped for unknown daemons") {
    withDispatcher { disp =>
      val svc = new DaemonService(disp)
      val configs = List(DaemonConfig("unknown", "Unknown", List("echo")))
      val states = svc.getStates(configs).unsafeRunSync()
      assertEquals(states.length, 1)
      assertEquals(states.head.id, "unknown")
      assertEquals(states.head.status, DaemonStatus.Stopped)
    }
  }

  test("DaemonService: start and stop a simple process") {
    withDispatcher { disp =>
      val svc = new DaemonService(disp)
      val cfg = DaemonConfig("echo-test", "Echo Test", List("echo", "hello-from-daemon"))
      val state = svc.start(cfg).unsafeRunSync()
      assertEquals(state.status, DaemonStatus.Running)

      Thread.sleep(500)

      // Process has likely exited; status should be Running (race) or Crashed
      val stateAfter = svc.getState("echo-test").unsafeRunSync().get
      assert(
        stateAfter.status == DaemonStatus.Running ||
          stateAfter.status == DaemonStatus.Crashed,
        s"Unexpected status: ${stateAfter.status}"
      )

      svc.stop("echo-test").unsafeRunSync()
    }
  }

  test("DaemonService: stop returns None for unknown daemon") {
    withDispatcher { disp =>
      val svc = new DaemonService(disp)
      assertEquals(svc.stop("nonexistent").unsafeRunSync(), None)
    }
  }

  test("DaemonService: startById returns None for unknown id") {
    val tmpDir = os.pwd / "target" / "daemon-test" / s"startbyid-${System.nanoTime()}"
    val store = new DaemonStore(tmpDir / "daemons.json")
    withDispatcher { disp =>
      val svc = new DaemonService(disp)
      assertEquals(svc.startById(store, "nonexistent").unsafeRunSync(), None)
    }
  }

  test("DaemonService: stopAll stops running daemons") {
    withDispatcher { disp =>
      val svc = new DaemonService(disp)
      val cfg = DaemonConfig("sleeper", "Sleeper", List("sleep", "60"))
      svc.start(cfg).unsafeRunSync()
      Thread.sleep(200)
      svc.stopAll().unsafeRunSync()
      Thread.sleep(200)
      val state = svc.getState("sleeper").unsafeRunSync().get
      assertEquals(state.status, DaemonStatus.Stopped)
    }
  }

  test("DaemonService: stop kills the whole process tree (parent + descendants)") {
    assume(!sys.props.getOrElse("os.name", "").toLowerCase.contains("win"), "requires POSIX sh")
    withDispatcher { disp =>
      val svc = new DaemonService(disp)
      // `sh -c` with backgrounded sleeps mimics the npm → node astro dev tree.
      // The trailing `wait` keeps the parent shell alive so the children remain
      // its descendants at snapshot time (a bare `&` shell exits immediately).
      val cfg = DaemonConfig("tree-test", "Tree Test", List("sh", "-c", "sleep 30 & sleep 30 & wait"))
      val state = svc.start(cfg).unsafeRunSync()
      assertEquals(state.status, DaemonStatus.Running)
      val parentPid = state.pid.getOrElse(fail("expected a pid"))

      // Wait for children to spawn, then snapshot the tree BEFORE stopping.
      Thread.sleep(600)
      val children =
        Option(ProcessHandle.of(parentPid).orElse(null))
          .map(_.descendants().toScala(List))
          .getOrElse(Nil)
      assert(children.nonEmpty, "expected sh to have spawned sleep children")

      svc.stop("tree-test").unsafeRunSync()
      Thread.sleep(400)

      assert(!ProcessHandle.of(parentPid).isPresent, s"parent pid $parentPid still alive")
      val survivors = children.filter(ph => ProcessHandle.of(ph.pid).isPresent)
      assertEquals(
        survivors.map(_.pid),
        Nil,
        s"descendant processes still alive after stop: ${survivors.map(_.pid).mkString(", ")}"
      )
    }
  }

  test("DaemonService: autoStart starts only autoStart=true daemons") {
    val tmpDir = os.pwd / "target" / "daemon-test" / s"autostart-${System.nanoTime()}"
    val store = new DaemonStore(tmpDir / "daemons.json")
    store.add(DaemonConfig("auto1", "Auto1", List("echo", "ok"), autoStart = true)).unsafeRunSync()
    store.add(DaemonConfig("manual1", "Manual1", List("echo", "ok"), autoStart = false)).unsafeRunSync()

    withDispatcher { disp =>
      val svc = new DaemonService(disp)
      svc.autoStart(store).unsafeRunSync()
      Thread.sleep(300)

      val states = svc.getStates(store.load().unsafeRunSync()).unsafeRunSync()

      // auto1 should have been started (echo exits fast, so may already be Crashed)
      val auto1State = states.find(_.id == "auto1").get
      assert(
        auto1State.status != DaemonStatus.Stopped || auto1State.exitCode.isDefined,
        s"auto1 should have been started, got: ${auto1State.status}"
      )

      // manual1 should remain stopped
      val manual1State = states.find(_.id == "manual1").get
      assertEquals(manual1State.status, DaemonStatus.Stopped)
    }
  }

  // ── Crash detection & auto-restart ─────────────────────

  private def await(pred: => Boolean, timeoutMs: Long = 15000, intervalMs: Long = 100): Boolean =
    val deadline = System.currentTimeMillis() + timeoutMs
    var ok = false
    while System.currentTimeMillis() < deadline && !ok do
      if pred then ok = true
      else Thread.sleep(intervalMs)
    ok

  test("DaemonService: autoStart daemon auto-restarts on crash, gives up after restartMaxAttempts (crash loop protection)") {
    withDispatcher { disp =>
      val svc = new DaemonService(disp)
      val cfg = DaemonConfig(
        "crashloop", "Crash Loop", List("sh", "-c", "exit 7"),
        autoStart = true,
        restartBackoffSec = 1,
        restartMaxAttempts = 2
      )
      assertEquals(svc.start(cfg).unsafeRunSync().status, DaemonStatus.Running)

      // Chain: crash(0s) -> backoff 1s -> restart#1 -> crash -> backoff 2s -> restart#2 -> crash -> give up.
      assert(
        await(svc.getState("crashloop").unsafeRunSync().exists(_.status == DaemonStatus.Crashed)),
        "daemon should crash after start"
      )
      // After the final give-up (~3s) no further restarts are scheduled: the
      // state must be Crashed continuously for a window longer than the chain.
      val deadline = System.currentTimeMillis() + 5000
      var stayedCrashed = true
      while System.currentTimeMillis() < deadline && stayedCrashed do
        stayedCrashed = svc.getState("crashloop").unsafeRunSync().exists(_.status == DaemonStatus.Crashed)
        Thread.sleep(200)
      assert(stayedCrashed, "daemon must not restart after exhausting restartMaxAttempts (crash loop)")
    }
  }

  test("DaemonService: explicit stop does not trigger auto-restart (JVM shutdown coordination)") {
    withDispatcher { disp =>
      val svc = new DaemonService(disp)
      val cfg = DaemonConfig("stopper", "Stopper", List("sleep", "30"), autoStart = true, restartBackoffSec = 1)
      assertEquals(svc.start(cfg).unsafeRunSync().status, DaemonStatus.Running)
      svc.stop("stopper").unsafeRunSync()

      // backoff is 1s: a wrongly-scheduled restart would fire within this window.
      Thread.sleep(1500)
      assertEquals(svc.getState("stopper").unsafeRunSync().map(_.status), Some(DaemonStatus.Stopped))
      Thread.sleep(1200) // extra margin — still must not come back
      assertEquals(svc.getState("stopper").unsafeRunSync().map(_.status), Some(DaemonStatus.Stopped))
    }
  }

  test("DaemonService: active health check kills a live-but-port-closed process (zombie detection)") {
    withDispatcher { disp =>
      val svc = new DaemonService(disp)
      val freePort =
        val ss = new java.net.ServerSocket(0)
        try ss.getLocalPort
        finally ss.close()
      // Process stays alive (sleep 60) but never opens the port → zombie case.
      val cfg = DaemonConfig("zombie", "Zombie", List("sleep", "60"), port = Some(freePort), healthCheckSec = 1)
      val state = svc.start(cfg).unsafeRunSync()
      assertEquals(state.status, DaemonStatus.Running)

      // Threshold is 4 consecutive probe failures × 1s interval → killed ~4-5s,
      // then monitorExit marks it Crashed (no auto-restart: autoStart=false).
      assert(
        await(svc.getState("zombie").unsafeRunSync().exists(_.status == DaemonStatus.Crashed), timeoutMs = 20000),
        "zombie daemon should be declared crashed by the active health check"
      )
      val pid = state.pid.getOrElse(fail("expected a pid"))
      assert(!ProcessHandle.of(pid).isPresent, s"wedged process $pid should have been killed")
    }
  }

end DaemonSpec
