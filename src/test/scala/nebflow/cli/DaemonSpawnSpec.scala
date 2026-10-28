package nebflow.cli

import munit.FunSuite
import nebflow.{Dispatch, MainDispatch}

/**
 * Phase 2 daemon batch (§5, DaemonSpawnSpec): the pure decision core and
 * the pure spawn-argument builders — zero IO, zero disk, zero processes.
 * The matrices pin:
 *  - MainDispatch.decide: flag x command dispatch (incl. the R2-2b
 *    regression: `--succeed X status` routes to CliRouter, never boots)
 *  - decideStrictPort: PortFree boots, any occupant refuses zero-signal
 *  - filterJvmArgs: blacklist-first order (F-9) — debug flags dropped even
 *    though the -X whitelist prefix contains them
 *  - buildChildArgs / readyLine / shouldRotate / awaitReadyCore
 */
class DaemonSpawnSpec extends FunSuite:

  // ── decide matrix ──────────────────────────────────────────────────────

  private def decide(
    cmdArgs: List[String],
    succeed: Option[os.Path] = None,
    daemonChild: Boolean = false,
    foreground: Boolean = false,
    spawnable: Boolean = true
  ): Dispatch =
    MainDispatch.decide(cmdArgs, succeed, daemonChild, foreground, spawnable)

  test("decide: bare start forms default to the daemon branch") {
    assertEquals(decide(Nil), Dispatch.Daemon)
    assertEquals(decide(List("start")), Dispatch.Daemon)
    assertEquals(decide(List("-s")), Dispatch.Daemon)
    assertEquals(decide(List("--server")), Dispatch.Daemon)
  }

  test("decide: every other command routes to the CLI router") {
    assertEquals(decide(List("status")), Dispatch.CliRoute)
    assertEquals(decide(List("stop")), Dispatch.CliRoute)
    assertEquals(decide(List("help")), Dispatch.CliRoute)
    assertEquals(decide(List("logs", "--follow")), Dispatch.CliRoute)
    assertEquals(decide(List("restart")), Dispatch.CliRoute)
  }

  test("decide: --succeed diverts ONLY on the start path (R2-2b regression)") {
    val intent = Some(os.Path("/tmp/intent.json"))
    // on the start path: successor boot
    assertEquals(decide(Nil, succeed = intent), Dispatch.Succeed(intent.get))
    assertEquals(decide(List("start"), succeed = intent), Dispatch.Succeed(intent.get))
    // with any other command: the command wins, CliRouter — never a boot
    assertEquals(decide(List("status"), succeed = intent), Dispatch.CliRoute)
    assertEquals(decide(List("stop"), succeed = intent), Dispatch.CliRoute)
  }

  test("decide: --daemon-child wins over foreground (child never focuses)") {
    assertEquals(decide(Nil, daemonChild = true, foreground = true), Dispatch.StrictChild)
    assertEquals(decide(List("start"), daemonChild = true), Dispatch.StrictChild)
  }

  test("decide: foreground exemption beats spawnability") {
    assertEquals(decide(Nil, foreground = true), Dispatch.ForegroundLegacy)
    // even when the classpath is empty (jpackage), explicit foreground stays legacy
    assertEquals(decide(Nil, foreground = true, spawnable = false), Dispatch.ForegroundLegacy)
  }

  test("decide: unspawnable classpath degrades to foreground boot") {
    assertEquals(decide(Nil, spawnable = false), Dispatch.ForegroundNoSpawn)
    assertEquals(decide(List("start"), spawnable = false), Dispatch.ForegroundNoSpawn)
  }

  // ── strict-boot port verdict ───────────────────────────────────────────

  test("decideStrictPort: PortFree boots; occupants refuse with Error and zero signals") {
    assertEquals(MainDispatch.decideStrictPort(SingleInstanceGuard.PortFree), Right(()))
    val neb = MainDispatch.decideStrictPort(SingleInstanceGuard.NebflowInstance("http://localhost:8108"))
    assert(neb.isLeft)
    val foreign = MainDispatch.decideStrictPort(SingleInstanceGuard.ForeignOccupant("port held"))
    assert(foreign.isLeft)
  }

  test("strictRefusalMessage names the port for a nebflow instance occupant") {
    val msg = MainDispatch.strictRefusalMessage(
      SingleInstanceGuard.NebflowInstance("http://localhost:8080"),
      8080
    )
    assert(msg.exists(_.contains("daemon child refused")), msg.toString)
    assert(msg.exists(_.contains("8080")), msg.toString)
    assert(msg.exists(_.contains("not killing")), msg.toString)
    assertEquals(MainDispatch.strictRefusalMessage(SingleInstanceGuard.PortFree, 8080), None)
  }

  // ── JVM argument inheritance (blacklist first — F-9) ───────────────────

  test("filterJvmArgs: debug flags are dropped even though -X prefixes would whitelist them") {
    assertEquals(
      DaemonSpawn.filterJvmArgs(List("-Xrunjdwp:transport=dt_socket,server=y,suspend=y")),
      Nil
    )
    assertEquals(DaemonSpawn.filterJvmArgs(List("-Xdebug")), Nil)
    assertEquals(DaemonSpawn.filterJvmArgs(List("-agentlib:jdwp=transport=dt_socket")), Nil)
  }

  test("filterJvmArgs: heap/GC/add-opens tuning is inherited") {
    assertEquals(
      DaemonSpawn.filterJvmArgs(List("-Xmx4g", "-XX:+UseZGC", "-Duser.home=/Users/x")),
      List("-Xmx4g", "-XX:+UseZGC", "-Duser.home=/Users/x")
    )
    assertEquals(
      DaemonSpawn.filterJvmArgs(List("--add-opens", "java.base/java.lang=ALL-UNNAMED")),
      List("--add-opens", "java.base/java.lang=ALL-UNNAMED")
    )
    assertEquals(
      DaemonSpawn.filterJvmArgs(List("--add-exports=java.base/x=y")),
      List("--add-exports=java.base/x=y")
    )
  }

  test("filterJvmArgs: non-whitelisted flags are not forwarded") {
    assertEquals(DaemonSpawn.filterJvmArgs(List("-Dos.name=whatever", "-jar", "x.jar")), Nil)
    // blacklist beats whitelist even inside a longer list
    assertEquals(
      DaemonSpawn.filterJvmArgs(List("-Xmx1g", "-agentlib:jdwp=server=y", "-XX:+UseZGC")),
      List("-Xmx1g", "-XX:+UseZGC")
    )
  }

  // ── child command ──────────────────────────────────────────────────────

  test("buildChildArgs: posix form is nohup java … nebflow.Main --daemon-child") {
    val args = DaemonSpawn.buildChildArgs(
      javaHome = "/jre",
      home = "/tmp/qa-phase2-a",
      port = 8108,
      noBrowser = true,
      inheritedJvmArgs = List("-Xmx4g"),
      classPath = "/repo/nebflow-assembly-1.0.jar",
      posix = true
    )
    assertEquals(args.head, "nohup")
    assertEquals(args(1), "/jre/bin/java")
    assert(args.contains("-Xmx4g"))
    assert(args.containsSlice(List("-cp", "/repo/nebflow-assembly-1.0.jar", "nebflow.Main")))
    assert(args.containsSlice(List("--home", "/tmp/qa-phase2-a")))
    assert(args.containsSlice(List("--port", "8108")))
    assert(args.contains("--daemon-child"))
    assert(args.contains("--no-browser"))
    assert(!args.exists(_.contains("--succeed")))
  }

  test("buildChildArgs: windows form drops nohup and --no-browser flag follows parent state") {
    val windows = DaemonSpawn.buildChildArgs("/jre", "/h", 1, noBrowser = false, Nil, "cp", posix = false)
    assert(!windows.contains("nohup"))
    assert(!windows.contains("--no-browser"))
  }

  // ── confirmation line + rotation threshold ─────────────────────────────

  test("readyLine: exactly one line, ≤ 200 bytes, carries the pid marker") {
    val line = DaemonSpawn.readyLine(4242L, "http://localhost:8108")
    assertEquals(line.count(_ == '\n'), 0)
    assert(line.getBytes("UTF-8").length <= 200)
    assert(line.contains("started (pid 4242)"))
    assert(line.contains("http://localhost:8108"))
  }

  test("shouldRotate flips above the 16MB threshold") {
    assert(!DaemonSpawn.shouldRotate(16L * 1024 * 1024))
    assert(DaemonSpawn.shouldRotate(16L * 1024 * 1024 + 1))
  }

  // ── readiness core ─────────────────────────────────────────────────────

  test("awaitReadyCore: probe passing immediately is Right") {
    assertEquals(
      DaemonSpawn.awaitReadyCore(() => true, () => true, deadlineMs = 100, pollMs = 0),
      Right(())
    )
  }

  test("awaitReadyCore: dead child beats everything") {
    assertEquals(
      DaemonSpawn.awaitReadyCore(() => false, () => false, deadlineMs = 100, pollMs = 0),
      Left(DaemonSpawn.ChildDied: DaemonSpawn.ReadyFailure)
    )
  }

  test("awaitReadyCore: never-ready live child hits the deadline as TimedOut") {
    assertEquals(
      DaemonSpawn.awaitReadyCore(() => false, () => true, deadlineMs = 0, pollMs = 0),
      Left(DaemonSpawn.TimedOut: DaemonSpawn.ReadyFailure)
    )
  }

end DaemonSpawnSpec
