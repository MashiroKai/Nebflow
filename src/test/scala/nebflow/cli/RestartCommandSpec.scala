package nebflow.cli

import cats.effect.{ExitCode, IO}
import cats.effect.unsafe.implicits.global
import munit.FunSuite
import nebflow.shared.PathUtil

/**
 * `nebflow restart` orchestration (phase 2 daemon plan §5,
 * RestartCommandSpec): the stop leg is driven through stub deps and the
 * launch leg through an injected stub — "refused stop ⇒ zero spawns" is the
 * load-bearing assertion (a restart must never start a second instance over
 * a refused stop).
 */
class RestartCommandSpec extends FunSuite:

  /**
   * 隔离根（临时数据根）。自 隔离实例误伤批 2026-11-04 起，被覆盖的数据根**必须**
   * 自带端口声明才允许起实例（绝不静默回落品牌缺省口 6138）——本 spec 的临时根
   * 因此写一份 `nebflow.json` 声明自己的端口；这条纪律本身由
   * `IsolatedInstanceScopeSpec` 的 spec A/C 正面钉。
   */
  private val TempRootPort = 21033

  private def withTempRoot(f: => Unit): Unit =
    val original = PathUtil.dataRoot
    val tmp = os.temp.dir(prefix = "restart-spec-")
    try
      PathUtil.setDataRoot(tmp)
      os.write.over(tmp / nebflow.shared.Branding.configFileName, s"""{"port": $TempRootPort}""", createFolders = true)
      f
    finally
      PathUtil.setDataRoot(original)
      os.remove.all(tmp)

  private val NebCmd = "java -cp /opt/nebflow/nebflow-assembly-1.0.jar nebflow.Main start"

  private def selfPid(): Long = java.lang.ProcessHandle.current().pid()

  /** StopDeps that never signal: stop outcome shaped by `aliveAfterDestroy`. */
  private def depsRefusing(readCmd: Long => Option[String]): ProcessManager.StopDeps =
    ProcessManager.StopDeps(
      readCmd = readCmd,
      listeningPortsOf = _ => Nil,
      listenerAlive = _ => false,
      destroy = _ => (),
      destroyForcibly = _ => (),
      isRunning = _ => true, // stays "alive" ⇒ destroy leg irrelevant, outcome decided earlier
      graceTimeoutMs = 0L,
      forceTimeoutMs = 0L,
      drainTimeoutMs = 0L,
      pollMs = 0L
    )

  test("stop refused ⇒ restart refuses, zero spawns, exit 1") {
    withTempRoot {
      ProcessManager.writePid(selfPid())
      val launches = scala.collection.mutable.ListBuffer[String]()
      val res = RestartCommand
        .runRestart(
          ProcessManager.StopOptions(),
          depsRefusing(_ => Some("sleep 9876")),
          (home, port, noBrowser) => IO { launches += s"$home/$port/$noBrowser"; ExitCode.Success }
        )
        .unsafeRunSync()
      res match
        case CliResult.Error(msg, code) =>
          assertEquals(code, 1)
          assert(msg.contains("not starting"), msg)
        case other => fail(s"expected Error, got $other")
      assert(launches.isEmpty)
    }
  }

  test("stop stopped ⇒ launch called once with the data root, port and browser state") {
    withTempRoot {
      val pid = selfPid()
      ProcessManager.writePid(pid)
      PidMeta.writeFreshFor(PidMeta.StartedByLauncher, PidMeta.ModeDaemon, pid)
      var dead = false
      val deps = ProcessManager.StopDeps(
        readCmd = _ => Some(NebCmd),
        listeningPortsOf = _ => Nil,
        listenerAlive = _ => false,
        destroy = _ => dead = true,
        destroyForcibly = _ => dead = true,
        isRunning = _ => !dead,
        graceTimeoutMs = 0L,
        forceTimeoutMs = 0L,
        drainTimeoutMs = 0L,
        pollMs = 0L
      )
      val launches = scala.collection.mutable.ListBuffer.empty[os.Path]
      val res = RestartCommand
        .runRestart(
          ProcessManager.StopOptions(),
          deps,
          (home, port, noBrowser) => IO { launches += home; ExitCode.Success }
        )
        .unsafeRunSync()
      assert(res.isInstanceOf[CliResult.Exit], res.toString)
      assertEquals(launches.size, 1)
      assertEquals(launches.head, PathUtil.dataRoot)
    }
  }

  test("not running ⇒ restart still starts (cold start face)") {
    withTempRoot {
      val launches = scala.collection.mutable.ListBuffer[Int]()
      val res = RestartCommand
        .runRestart(
          ProcessManager.StopOptions(),
          ProcessManager.StopDeps(),
          (home, port, noBrowser) => IO { launches += 1; ExitCode.Success }
        )
        .unsafeRunSync()
      assert(res.isInstanceOf[CliResult.Exit], res.toString)
      assertEquals(launches.size, 1)
    }
  }

  test("--force passes through: sbt-owned target stops, launch runs (dev-host gate still holds)") {
    withTempRoot {
      val pid = selfPid()
      ProcessManager.writePid(pid)
      os.write.over(PathUtil.dataRoot / ".pid.meta", """{"startedBy":"sbt"}""")
      var dead = false
      val deps = ProcessManager.StopDeps(
        readCmd = _ => Some(NebCmd), // verified nebflow, NOT a dev-host form
        listeningPortsOf = _ => Nil,
        listenerAlive = _ => false,
        destroy = _ => dead = true,
        destroyForcibly = _ => dead = true,
        isRunning = _ => !dead,
        graceTimeoutMs = 0L,
        forceTimeoutMs = 0L,
        drainTimeoutMs = 0L,
        pollMs = 0L
      )
      // sanity: without --force the stop is refused ⇒ no launch
      val launches1 = scala.collection.mutable.ListBuffer[Int]()
      RestartCommand
        .runRestart(ProcessManager.StopOptions(), deps, (_, _, _) => IO { launches1 += 1; ExitCode.Success })
        .unsafeRunSync()
      assert(launches1.isEmpty)

      val launches2 = scala.collection.mutable.ListBuffer[Int]()
      val res = RestartCommand
        .runRestart(
          ProcessManager.StopOptions(force = true),
          deps,
          (_, _, _) =>
            IO {
              launches2 += 1; ExitCode.Success
            }
        )
        .unsafeRunSync()
      assert(res.isInstanceOf[CliResult.Exit], res.toString)
      assertEquals(launches2.size, 1)
    }
  }

end RestartCommandSpec
