package nebflow.cli

import cats.effect.unsafe.implicits.global
import munit.FunSuite
import nebflow.shared.PathUtil

import scala.collection.mutable.ListBuffer

/**
 * The hardened stop face (phase 2 daemon plan §5, ProcessManagerSpec) —
 * driven entirely through injected StopDeps: every readCmd / port lookup /
 * destroy is a stub, so red cases (foreign pids, dev-host 8080/8097 targets,
 * sbt-owned instances) are constructed with ZERO real signals.
 *
 * Real-process surface used: the test JVM's own pid (alive) and one
 * spec-owned already-exited process (dead pid fixture); the destroy stub
 * records calls instead of signalling anything.
 */
class ProcessManagerSpec extends FunSuite:

  // A verified nebflow command line for the identity check (NebflowMarkers).
  private val NebCmd = "java -cp /opt/nebflow/bin/nebflow-assembly-1.0.jar nebflow.Main start"
  // Dev-host forms (DevHostMarkers): sbt dev fork + assembly jar from target/.
  private val SbtDevCmd = "java -cp /repo/target/scala-3.5.2/classes nebflow.gateway.GatewayMain"
  private val TargetJarCmd =
    "java -cp /repo/target/scala-3.5.2/nebflow-assembly-1.0.jar nebflow.Main start"

  private def withTempRoot(f: => Unit): Unit =
    val original = PathUtil.dataRoot
    val tmp = os.temp.dir(prefix = "stop-spec-")
    try
      PathUtil.setDataRoot(tmp)
      f
    finally
      PathUtil.setDataRoot(original)
      os.remove.all(tmp)

  private def deadPid(): Long =
    val p = new ProcessBuilder("true").start()
    p.waitFor()
    p.pid()

  /** Records every signal and probe; `dead` pids flip on destroy (graceful leg). */
  private final class Recorder:
    val destroyed = ListBuffer[Long]()
    val destroyedForcibly = ListBuffer[Long]()
    val probedPorts = ListBuffer[Int]()
    var deadPids = Set[Long]()

  /**
   * Build stub deps + its recorder: destroy RECORDS and flips death (never
   * signals); every port probe is recorded; `isAliveOverride` shapes stubborn
   * targets.
   */
  private def stubDeps(
      readCmd: Long => Option[String] = _ => Some(NebCmd),
      portsOf: Long => List[Int] = _ => Nil,
      listenerAliveResult: Boolean = false,
      isAliveOverride: Option[Long => Boolean] = None
  ): (ProcessManager.StopDeps, Recorder) =
    val rec = new Recorder
    val deps = ProcessManager.StopDeps(
      readCmd = readCmd,
      listeningPortsOf = portsOf,
      listenerAlive = port => { rec.probedPorts += port; listenerAliveResult },
      destroy = pid => { rec.destroyed += pid; rec.deadPids += pid },
      destroyForcibly = pid => { rec.destroyedForcibly += pid; rec.deadPids += pid },
      isRunning = isAliveOverride.getOrElse(pid =>
        !rec.deadPids.contains(pid) && ProcessManager.isRunning(pid)
      ),
      graceTimeoutMs = 0L,
      forceTimeoutMs = 0L,
      drainTimeoutMs = 200L,
      pollMs = 0L
    )
    (deps, rec)

  private def runStop(
      opts: ProcessManager.StopOptions,
      deps: ProcessManager.StopDeps
  ): ProcessManager.StopOutcome =
    ProcessManager.stopHardened(opts, deps).unsafeRunSync()

  private def selfPid(): Long = java.lang.ProcessHandle.current().pid()

  // ── piece 1: identity ──────────────────────────────────────────────────

  test("not running: no pid file ⇒ NotRunning (idempotent, exit 0 face)") {
    withTempRoot {
      val (deps, rec) = stubDeps()
      assertEquals(runStop(ProcessManager.StopOptions(), deps), ProcessManager.StopOutcome.NotRunning)
      assert(rec.destroyed.isEmpty)
    }
  }

  test("not running: stale pid file is cleaned and still NotRunning") {
    withTempRoot {
      ProcessManager.writePid(deadPid())
      val (deps, rec) = stubDeps()
      assertEquals(runStop(ProcessManager.StopOptions(), deps), ProcessManager.StopOutcome.NotRunning)
      assert(!os.exists(PathUtil.dataRoot / ".pid"))
      assert(rec.destroyed.isEmpty)
    }
  }

  test("foreign pid (sleep-like cmdline) ⇒ Refused with zero destroy") {
    withTempRoot {
      ProcessManager.writePid(selfPid())
      val (deps, rec) = stubDeps(readCmd = _ => Some("sleep 9876"))
      runStop(ProcessManager.StopOptions(), deps) match
        case ProcessManager.StopOutcome.Refused(reason) =>
          assert(reason.contains(s"not a verified ${nebflow.shared.Branding.productName} process"), reason)
          assert(reason.contains("sleep 9876"), reason)
        case other => fail(s"expected Refused, got $other")
      assert(rec.destroyed.isEmpty)
      assert(rec.destroyedForcibly.isEmpty)
      assert(os.exists(PathUtil.dataRoot / ".pid")) // refused ⇒ nothing cleaned
    }
  }

  test("unreadable command line ⇒ Refused with zero destroy") {
    withTempRoot {
      ProcessManager.writePid(selfPid())
      val (deps, rec) = stubDeps(readCmd = _ => None)
      val out = runStop(ProcessManager.StopOptions(), deps)
      assert(out.isInstanceOf[ProcessManager.StopOutcome.Refused])
      assert(rec.destroyed.isEmpty)
    }
  }

  // ── 8080/8097 hard gate (above --force) ────────────────────────────────

  test("dev-host target on 8080 (GatewayMain cmdline) ⇒ Refused, --force does not bypass, zero destroy") {
    withTempRoot {
      ProcessManager.writePid(selfPid())
      val (deps, rec) = stubDeps(readCmd = _ => Some(SbtDevCmd), portsOf = _ => List(8080))
      runStop(ProcessManager.StopOptions(force = true), deps) match
        case ProcessManager.StopOutcome.Refused(reason) =>
          assert(reason.contains("8080"), reason)
          assert(reason.contains("not bypassable"), reason)
        case other => fail(s"expected Refused, got $other")
      assert(rec.destroyed.isEmpty)
      assert(rec.destroyedForcibly.isEmpty)
    }
  }

  test("dev-host target on 8097 (assembly jar from /target/) ⇒ Refused even with --force --tree") {
    withTempRoot {
      ProcessManager.writePid(selfPid())
      val (deps, rec) = stubDeps(readCmd = _ => Some(TargetJarCmd), portsOf = _ => List(8097))
      val out = runStop(ProcessManager.StopOptions(force = true, tree = true), deps)
      assert(out.isInstanceOf[ProcessManager.StopOutcome.Refused])
      assert(rec.destroyed.isEmpty)
    }
  }

  // ── hard-gate boundary pins (plan §3.2 R2-5 ruling — review 2026-10-10) ─
  //
  // The gate is DOUBLE-conditioned by design (spec §4.4 :227 / plan R2-5):
  // protected port ∧ dev-host command line. The two pins below make the
  // ruled tradeoff executable instead of silent — they are boundary
  // documentation, not an endorsement; the absolute-red-line question
  // ("never stop ANY nebflow on 8080/8097") is an upper-layer ruling,
  // because absolute gating would permanently brick `nebflow stop` for the
  // legitimate bare-jar default-port install form (GatewayConfig default
  // port IS 8080; `java -jar nebflow.jar start` is an install form listed
  // in StaleProcessGuard's compatibility table).

  test("boundary pin: product-form instance on 8080 without dev-host markers is not hard-gated") {
    withTempRoot {
      val pid = selfPid()
      ProcessManager.writePid(pid)
      PidMeta.writeFreshFor(PidMeta.StartedByLauncher, PidMeta.ModeDaemon, pid)
      val productCmd = "java -jar /Users/x/.local/bin/nebflow.jar start --port 8080" // no /target/, no GatewayMain
      val (deps, rec) = stubDeps(readCmd = _ => Some(productCmd), portsOf = _ => List(8080))
      val out = runStop(ProcessManager.StopOptions(), deps)
      assert(out.isInstanceOf[ProcessManager.StopOutcome.Stopped], out.toString)
      assert(rec.destroyed.toList == List(pid))
    }
  }

  test("boundary pin: lsof unavailable (port probe fails open) does not block a dev-host-form stop") {
    withTempRoot {
      val pid = selfPid()
      ProcessManager.writePid(pid)
      PidMeta.writeFreshFor(PidMeta.StartedByLauncher, PidMeta.ModeDaemon, pid)
      val (deps, rec) = stubDeps(readCmd = _ => Some(SbtDevCmd), portsOf = _ => Nil) // lsof missing ⇒ Nil
      val out = runStop(ProcessManager.StopOptions(), deps)
      assert(out.isInstanceOf[ProcessManager.StopOutcome.Stopped], out.toString)
      assert(rec.destroyed.toList == List(pid))
    }
  }

  test("same dev-host cmdline on an isolated port is NOT hard-gated (double condition)") {
    withTempRoot {
      val pid = selfPid()
      ProcessManager.writePid(pid)
      PidMeta.writeFreshFor(PidMeta.StartedByLauncher, PidMeta.ModeDaemon, pid)
      val (deps, rec) = stubDeps(readCmd = _ => Some(TargetJarCmd), portsOf = _ => List(8110))
      val out = runStop(ProcessManager.StopOptions(), deps)
      assert(out.isInstanceOf[ProcessManager.StopOutcome.Stopped], out.toString)
      assert(rec.destroyed.toList == List(pid))
    }
  }

  // ── piece 2: ownership gate ────────────────────────────────────────────

  test("meta=sbt ⇒ default Refused (sbt owns the process)") {
    withTempRoot {
      ProcessManager.writePid(selfPid())
      os.write.over(PathUtil.dataRoot / ".pid.meta", """{"startedBy":"sbt","mode":"foreground"}""")
      val (deps, rec) = stubDeps()
      runStop(ProcessManager.StopOptions(), deps) match
        case ProcessManager.StopOutcome.Refused(reason) =>
          assert(reason.contains("sbt"), reason)
          assert(reason.contains("--tree"), reason)
        case other => fail(s"expected Refused, got $other")
      assert(rec.destroyed.isEmpty)
    }
  }

  test("meta=restart-script ⇒ default Refused") {
    withTempRoot {
      ProcessManager.writePid(selfPid())
      os.write.over(PathUtil.dataRoot / ".pid.meta", """{"startedBy":"restart-script"}""")
      val (deps, rec) = stubDeps()
      val out = runStop(ProcessManager.StopOptions(), deps)
      assert(out.isInstanceOf[ProcessManager.StopOutcome.Refused])
      assert(rec.destroyed.isEmpty)
    }
  }

  test("no meta ⇒ unknown owner ⇒ default Refused; --force unlocks the stop") {
    withTempRoot {
      val pid = selfPid()
      ProcessManager.writePid(pid)
      val (deps, rec) = stubDeps()
      assert(runStop(ProcessManager.StopOptions(), deps).isInstanceOf[ProcessManager.StopOutcome.Refused])
      assert(rec.destroyed.isEmpty)
      // --force skips ONLY the ownership gate — the stop now proceeds
      val (deps2, rec2) = stubDeps()
      assert(runStop(ProcessManager.StopOptions(force = true), deps2).isInstanceOf[ProcessManager.StopOutcome.Stopped])
      assert(rec2.destroyed.toList == List(pid))
    }
  }

  // ── happy path (launcher-owned) ────────────────────────────────────────

  test("launcher-owned instance stops cleanly and .pid + .pid.meta are both removed") {
    withTempRoot {
      val pid = selfPid()
      ProcessManager.writePid(pid)
      PidMeta.writeFreshFor(PidMeta.StartedByLauncher, PidMeta.ModeDaemon, pid)
      val (deps, rec) = stubDeps()
      runStop(ProcessManager.StopOptions(), deps) match
        case ProcessManager.StopOutcome.Stopped(msg) =>
          assert(msg.contains("stopped"), msg)
        case other => fail(s"expected Stopped, got $other")
      assertEquals(rec.destroyed.toList, List(pid))
      assert(rec.destroyedForcibly.isEmpty) // graceful destroy flipped death
      assert(!os.exists(PathUtil.dataRoot / ".pid"))
      assert(!os.exists(PathUtil.dataRoot / ".pid.meta"))
    }
  }

  test("process not dying after TERM+KILL ⇒ Failed") {
    withTempRoot {
      val pid = selfPid()
      ProcessManager.writePid(pid)
      PidMeta.writeFreshFor(PidMeta.StartedByLauncher, PidMeta.ModeDaemon, pid)
      val (deps, rec) = stubDeps(isAliveOverride = Some(_ => true))
      runStop(ProcessManager.StopOptions(), deps) match
        case ProcessManager.StopOutcome.Failed(reason) => assert(reason.contains("did not exit"), reason)
        case other                                     => fail(s"expected Failed, got $other")
      assert(rec.destroyed.contains(pid))     // graceful leg attempted
      assert(rec.destroyedForcibly.contains(pid)) // force leg attempted too
    }
  }

  // ── piece 3: port source = target pid's own listeners (F-3) ────────────

  test("port confirmation polls the TARGET's ports (8110), never the caller's 8080") {
    withTempRoot {
      val pid = selfPid()
      ProcessManager.writePid(pid)
      PidMeta.writeFreshFor(PidMeta.StartedByLauncher, PidMeta.ModeDaemon, pid)
      // ports still reported after death (stub) ⇒ piece 3 must poll THEM
      val (deps, rec) = stubDeps(portsOf = _ => List(8110))
      val out = runStop(ProcessManager.StopOptions(), deps)
      assert(out.isInstanceOf[ProcessManager.StopOutcome.Stopped], out.toString)
      assert(rec.probedPorts.nonEmpty && rec.probedPorts.forall(_ == 8110), rec.probedPorts.toString)
      assert(!rec.probedPorts.contains(8080))
    }
  }

  test("port still served after death past the drain budget ⇒ Failed") {
    withTempRoot {
      val pid = selfPid()
      ProcessManager.writePid(pid)
      PidMeta.writeFreshFor(PidMeta.StartedByLauncher, PidMeta.ModeDaemon, pid)
      val (deps, rec) = stubDeps(
        portsOf = _ => List(8110),
        listenerAliveResult = true // never releases
      )
      val out = runStop(ProcessManager.StopOptions(), deps)
      assert(out.isInstanceOf[ProcessManager.StopOutcome.Failed], out.toString)
      assert(rec.probedPorts.contains(8110))
    }
  }

  // ── --tree (R2-7) ──────────────────────────────────────────────────────

  test("--tree with no sbt ancestor degrades to a single-process stop with declaration") {
    withTempRoot {
      val pid = selfPid()
      ProcessManager.writePid(pid)
      os.write.over(PathUtil.dataRoot / ".pid.meta", """{"startedBy":"sbt"}""")
      // readCmd: nebflow cmdline for EVERY pid — no sbt marker in the chain
      val (deps, rec) = stubDeps()
      runStop(ProcessManager.StopOptions(tree = true), deps) match
        case ProcessManager.StopOutcome.Stopped(msg) =>
          assert(msg.contains("no sbt ancestor"), msg)
          assert(msg.contains("single process"), msg)
        case other => fail(s"expected Stopped, got $other")
      assertEquals(rec.destroyed.toList, List(pid)) // exactly the single pid
    }
  }

  test("treeKillSet: sbt root found ⇒ chain + descendants, verified, top-down") {
    val chain = List(100L, 90L, 80L) // pid-first upward
    val readCmd: Long => Option[String] = {
      case 90  => Some("java -jar sbt-launch.jar run")
      case 80  => Some("zsh -l")
      case 100 => Some(NebCmd)
      case 101 => Some(NebCmd)
      case 91  => Some("java -jar sbt-launch.jar")
      case 92  => Some(NebCmd)
    }
    val descendants: Long => List[Long] = {
      case 100 => List(101L)
      case 90  => List(91L, 92L)
      case _   => Nil
    }
    assertEquals(ProcessManager.sbtRootFromChain(chain, readCmd), Some(90L))
    ProcessManager.treeKillSet(chain, Some(90L), descendants, readCmd) match
      case Right(targets) =>
        // subchain pid..root = [100, 90]; members + descendants = 100, 90, 101, 91, 92
        // (80 sits ABOVE the sbt root — outside the managed tree)
        assertEquals(targets.toSet, Set(90L, 91L, 92L, 100L, 101L))
        // top-down: root side first (90 before its child 100)
        assert(targets.indexOf(90L) < targets.indexOf(100L), targets.toString)
      case Left(e) => fail(s"unexpected refusal: $e")
  }

  test("treeKillSet: unverified member refuses the whole tree") {
    val chain = List(100L, 90L)
    val readCmd: Long => Option[String] = {
      case 90  => Some("java -jar sbt-launch.jar")
      case 100 => Some(NebCmd)
      case 91  => Some("python3 -m http.server") // foreign descendant
    }
    ProcessManager.treeKillSet(chain, Some(90L), _ => List(91L), readCmd) match
      case Left(refusal) => assert(refusal.contains("91"), refusal)
      case Right(ts)     => fail(s"expected refusal, got $ts")
  }

  test("treeKillSet: no sbt root ⇒ single pid") {
    val readCmd: Long => Option[String] = _ => Some(NebCmd)
    assertEquals(
      ProcessManager.treeKillSet(List(100L, 90L), None, _ => Nil, readCmd),
      Right(List(100L))
    )
  }

end ProcessManagerSpec
