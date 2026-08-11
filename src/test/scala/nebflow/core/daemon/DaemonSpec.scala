package nebflow.core.daemon

import cats.effect.std.Dispatcher
import cats.effect.{IO, Resource}
import cats.effect.unsafe.implicits.global
import io.circe.parser.decode
import io.circe.syntax.*
import munit.FunSuite

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

end DaemonSpec
