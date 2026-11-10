package nebflow.cli

import cats.effect.{ExitCode, IO}
import cats.effect.unsafe.implicits.global
import munit.FunSuite
import nebflow.shared.{InstanceScope, PathUtil}

import scala.collection.mutable.ListBuffer

/**
 * 隔离实例误伤批 2026-11-04 —— 四条机械验收（spec A / B / C / D）的 Scala 面。
 *
 * == 事故（2026-11-03 22:59，已事实核查）==
 * 一个 `STAND_HOME=/tmp/seal-canary-home` 的隔离 canary 批执行了 `stand` 的停机类
 * 动词，把跑在 6138 上的**生产实例**停机了。现场硬证据：
 *   · canary：pid **68701**，home `/tmp/seal-canary-home`，端口 **6411**（canary 的端口
 *     是**真隔离**的 ⇒ 本事故不是「端口没隔离」造成的）；
 *   · 生产：pid **44339**，home `~/.stand`，端口 **6138**；
 *   · 死亡命令的可见前缀：`cat /tmp/seal-canary-home/.pid; echo;
 *     STAND_HOME=/tmp/seal-canary-home …/bin/stand`（截断点正好在动词前，无 `--port`）。
 *
 * **决定性结论（写死在这里，结果正文同口径）**：一次 `STAND_HOME=/tmp/seal-canary-home`
 * 的 `stand` CLI 调用，停掉的是 home=`~/.stand`、端口=6138 的 **44339**，而不是
 * home=`/tmp/seal-canary-home` 的 **68701** —— 它甚至连自己 `.pid` 里的 68701 都没停。
 * 主因是「目标集合只按端口/品牌认实例，**从不看 home**」；端口解析链在 home 被覆盖时
 * 静默回落品牌缺省口（6138）是同一缺口的另一半（防下一次的硬化），不是本事故的主因。
 *
 * 本 spec 的纪律：**零真实信号、零真实 daemon、不用 6138/6140/8080/8097**。pid 只作
 * 符号使用（68701/44339 是事故现场的数字，不是要碰的进程）；每个 destroy/kill 都是
 * 记录器里的一个 ListBuffer 元素。
 */
class IsolatedInstanceScopeSpec extends FunSuite:

  private val CanaryHome = "/tmp/seal-canary-home"
  private val CanaryPid = 68701L
  private val ProdHome = "/Users/kaiyu/.stand"
  private val ProdPid = 44339L

  /** 事故现场的命令行形状：引擎 daemon 子进程命令行恒带 `--home`。 */
  private def daemonCmd(home: String): String =
    s"java --add-opens java.base/java.lang=ALL-UNNAMED -jar /opt/stand/bin/stand-assembly-2026.10.jar start --home $home"

  private def withTempRoot(f: => Unit): Unit =
    val original = PathUtil.dataRoot
    val tmp = os.temp.dir(prefix = "iso-scope-spec-")
    try
      PathUtil.setDataRoot(tmp)
      f
    finally
      PathUtil.setDataRoot(original)
      os.remove.all(tmp)

  private val NebCmd = "java -jar /opt/stand/bin/stand-assembly-2026.10.jar start"

  private final class Rec:
    val destroyed = ListBuffer[Long]()
    val probes = ListBuffer[Int]()

  /** 零信号 StopDeps：一切 destroy/kill 只进记录器。 */
  private def deps(
    rec: Rec,
    readCmd: Long => Option[String] = _ => Some(NebCmd),
    homeProof: (Long, Option[String]) => InstanceScope.HomeProof,
    gatewayPortOverride: Option[Int] = None
  ): ProcessManager.StopDeps =
    ProcessManager.StopDeps(
      readCmd = readCmd,
      listeningPortsOf = _ => Nil,
      listenerAlive = p =>
        rec.probes += p; false
      ,
      destroy = p => rec.destroyed += p,
      isRunning = pid => !rec.destroyed.contains(pid),
      graceTimeoutMs = 0L,
      drainTimeoutMs = 0L,
      pollMs = 0L,
      gatewayPortOverride = gatewayPortOverride,
      homeProof = homeProof
    )

  private def portInputs(
    cliPort: Option[Int] = None,
    envPort: Option[String] = None,
    configPort: Option[Int] = None,
    root: String = "/tmp/seal-canary-home",
    brandDefault: Int = 6138,
    overridden: Boolean = true
  ): InstanceScope.PortInputs =
    InstanceScope.PortInputs(
      cliPort = cliPort,
      envPortName = "STAND_GATEWAY_PORT",
      envPort = envPort,
      configPort = configPort,
      sourceName = "nebflow.json",
      brandDefaultPort = brandDefault,
      root = os.Path(root),
      overridden = overridden
    )

  // ─────────────────────────────────────────────────────────────────────
  // spec A — 隔离根没有实例也没有端口 ⇒ 「未找到实例/not running」，且
  //          对 6138 上的监听进程 kill/destroy 调用次数 == 0（注入探针）
  // ─────────────────────────────────────────────────────────────────────

  test("spec A: isolated root without its own port ⇒ port resolution REFUSED (never the brand default 6138)") {
    InstanceScope.decide(portInputs()) match
      case InstanceScope.PortVerdict.Refused(reason) =>
        assert(reason.contains("6138"), reason)
        assert(reason.contains(CanaryHome), reason)
      case other => fail(s"expected Refused, got $other")
  }

  test("spec A: isolated root, no pid file, no listener on its port ⇒ NotRunning with zero kill/destroy on 6138") {
    withTempRoot { // dataRoot = 临时目录 = 被覆盖的根（隔离根）
      val rec = new Rec
      assert(InstanceScope.liveCaller.overridden, "spec 前提: 临时根不是默认根")
      val outcome = ProcessManager
        .stopHardened(
          ProcessManager.StopOptions(),
          deps(rec, homeProof = (p, c) => InstanceScope.proveHome(c, p, PathUtil.dataRoot.toString, None))
        )
        .unsafeRunSync()
      assertEquals(outcome, ProcessManager.StopOutcome.NotRunning)
      // 注入探针：6138 上的任何监听进程都没有被 kill/destroy 过一次
      assertEquals(rec.destroyed.toList, Nil)
      assertEquals(rec.probes.count(_ == 6138), 0, "6138 绝不进入探测/停机目标集合")
    }
  }

  // ─────────────────────────────────────────────────────────────────────
  // spec B — 目标实例的 home 与调用 home 不一致 ⇒ 拒绝 + 点名两个 home（不是停、
  //          不是 0 退出）；且要覆盖「目标 pid ≠ 调用方 .pid 内容」的真实现场
  // ─────────────────────────────────────────────────────────────────────

  test("spec B: target home differs from caller home ⇒ REFUSED naming both homes, zero signal") {
    withTempRoot {
      // 目标集合来自本根的 .pid（事故现场：隔离 canary 的停机面按端口/品牌认目标），
      // 但那个 pid 的 home 是别人的 ⇒ 必须拒绝而不是停。
      ProcessManager.writePid(ProdPid)
      val rec = new Rec
      val callerHome = PathUtil.dataRoot.toString // 调用者 = 被覆盖的隔离根（临时目录）
      val outcome = ProcessManager
        .stopHardened(
          ProcessManager.StopOptions(),
          deps(
            rec,
            readCmd = _ => Some(daemonCmd(ProdHome)),
            homeProof = (p, c) => InstanceScope.proveHome(c, p, callerHome, Some(ProdPid))
          )
        )
        .unsafeRunSync()
      outcome match
        case ProcessManager.StopOutcome.Refused(reason) =>
          assert(reason.contains(ProdHome), reason) // 目标 home（点名）
          assert(reason.contains(callerHome), reason) // 调用 home（点名）
          assert(reason.contains("zero signal"), reason)
        case other => fail(s"expected Refused, got $other")
      assertEquals(rec.destroyed.toList, Nil)
    }
  }

  test(
    "spec B: target pid != caller's .pid (44339 vs 68701) ⇒ prod shape always REFUSED, canary shape still stoppable"
  ) {
    withTempRoot {
      // 调用方 = canary 视图：home /tmp/seal-canary-home，.pid 记的是 68701。
      // meta 记 launcher 启动（否则停止面的所有权门先拒，测不到 home 门）。
      ProcessManager.writePid(CanaryPid)
      PidMeta.writeFreshFor(PidMeta.StartedByLauncher, PidMeta.ModeDaemon, CanaryPid)
      val proofFor: (Long, Option[String]) => InstanceScope.HomeProof =
        (pid, cmd) => InstanceScope.proveHome(cmd, pid, CanaryHome, Some(CanaryPid))
      // ① 生产形态（home=~/.stand）⇒ Other，点名两个 home
      proofFor(ProdPid, Some(daemonCmd(ProdHome))) match
        case InstanceScope.HomeProof.Other(home, _) => assertEquals(home, ProdHome)
        case other => fail(s"expected Other, got $other")
      InstanceScope.homeRefusal(
        proofFor(ProdPid, Some(daemonCmd(ProdHome))),
        CanaryHome,
        failClosedWhenUnknown = true
      ) match
        case Some(refusal) =>
          assert(refusal.contains(ProdHome), refusal)
          assert(refusal.contains(CanaryHome), refusal)
        case None => fail("must refuse a target of another home")
      // ② canary 形态（home=/tmp/seal-canary-home）⇒ Same（自家 ⇒ 可停）
      InstanceScope.homeRefusal(
        proofFor(CanaryPid, Some(daemonCmd(CanaryHome))),
        CanaryHome,
        failClosedWhenUnknown = true
      ) match
        case Some(r) => fail(s"own home must be stoppable, got $r")
        case None => ()
      // ③ 停机面只可能作用于 A（.pid 里那一个），对 B 一律 Refused、零信号
      val rec = new Rec
      val readCmd: Long => Option[String] = {
        case `CanaryPid` => Some(daemonCmd(CanaryHome))
        case `ProdPid` => Some(daemonCmd(ProdHome))
        case _ => Some(NebCmd)
      }
      val outcome = ProcessManager
        .stopHardened(ProcessManager.StopOptions(), deps(rec, readCmd, proofFor))
        .unsafeRunSync()
      assert(outcome.isInstanceOf[ProcessManager.StopOutcome.Stopped], outcome.toString)
      assertEquals(rec.destroyed.toList, List(CanaryPid))
      assert(!rec.destroyed.contains(ProdPid), "生产形态 pid 永不出现在信号集合里")
    }
  }

  test(
    "spec B: restart shares the same guard — a refused stop never enters the start leg (no launch, no detached apply)"
  ) {
    withTempRoot {
      ProcessManager.writePid(ProdPid)
      val launches = ListBuffer[Int]()
      val res = RestartCommand
        .runRestart(
          ProcessManager.StopOptions(),
          deps(
            new Rec,
            readCmd = _ => Some(daemonCmd(ProdHome)),
            homeProof = (p, c) => InstanceScope.proveHome(c, p, CanaryHome, Some(CanaryPid))
          ),
          (_, _, _) => IO { launches += 1; ExitCode.Success }
        )
        .unsafeRunSync()
      res match
        case CliResult.Error(msg, code) =>
          assertEquals(code, 1)
          assert(msg.contains("not starting"), msg)
          assert(msg.contains(ProdHome) || msg.contains(CanaryHome), msg)
        case other => fail(s"expected Error, got $other")
      assertEquals(launches.toList, Nil, "拒绝的 stop 之后绝不点火")
    }
  }

  // ─────────────────────────────────────────────────────────────────────
  // spec C — canary 启动（R 被覆盖、端口未显式指定）⇒ 不占生产默认口
  //          （按 R 的配置取，或直接失败告警）；继承来的 env 端口不算显式
  // ─────────────────────────────────────────────────────────────────────

  test("spec C: isolated root with its own config port ⇒ that port, never 6138") {
    InstanceScope.decide(portInputs(configPort = Some(21011))) match
      case InstanceScope.PortVerdict.Resolved(port, via, _) =>
        assertEquals(port, 21011)
        assert(via.contains("21011") || via.contains("config"), via)
      case other => fail(s"expected Resolved, got $other")
  }

  test("spec C: inherited env port is NOT explicit for an overridden root (WARN names it) and never becomes 6138") {
    InstanceScope.decide(portInputs(envPort = Some("6138"), configPort = Some(21012))) match
      case InstanceScope.PortVerdict.Resolved(port, _, warnings) =>
        assertEquals(port, 21012)
        assert(warnings.exists(_.contains("STAND_GATEWAY_PORT=6138")), warnings.toString)
      case other => fail(s"expected Resolved via config, got $other")
    // 无 config 且只有继承 env ⇒ 拒绝（不是静默继承 6138）
    InstanceScope.decide(portInputs(envPort = Some("6138"))) match
      case InstanceScope.PortVerdict.Refused(reason) =>
        assert(reason.contains("6138"), reason)
        assert(reason.contains("--port"), reason)
      case other => fail(s"expected Refused, got $other")
  }

  test("spec C: default root keeps the historical chain (env then brand default) — zero behavior change") {
    InstanceScope.decide(portInputs(envPort = Some("6139"), root = ProdHome, overridden = false)) match
      case InstanceScope.PortVerdict.Resolved(p, _, w) =>
        assertEquals(p, 6139); assertEquals(w, Nil)
      case other => fail(s"expected Resolved, got $other")
    InstanceScope.decide(portInputs(root = ProdHome, overridden = false)) match
      case InstanceScope.PortVerdict.Resolved(p, _, _) => assertEquals(p, 6138)
      case other => fail(s"expected Resolved, got $other")
  }

  // ─────────────────────────────────────────────────────────────────────
  // spec D — SingleInstanceGuard：端口被另一 home 的活实例占用 ⇒
  //          OtherHomeInstance、不 focus、不 kill；只有 --takeover 才放行
  // ─────────────────────────────────────────────────────────────────────

  test("spec D: occupant of another home classifies OtherHomeInstance (never focused)") {
    val url = "http://localhost:6138"
    SingleInstanceGuard.classifyOccupant("stand", url, CanaryHome, Some((ProdPid, ProdHome))) match
      case SingleInstanceGuard.OtherHomeInstance(home, u, pid) =>
        assertEquals(home, ProdHome)
        assertEquals(u, url)
        assertEquals(pid, ProdPid)
      case other => fail(s"expected OtherHomeInstance, got $other")
  }

  test("spec D: same-home occupant keeps focus-existing; unprovable home is NOT reclassified as another home") {
    assertEquals(
      SingleInstanceGuard.classifyOccupant("stand", "http://localhost:6411", CanaryHome, Some((CanaryPid, CanaryHome))),
      SingleInstanceGuard.NebflowInstance("http://localhost:6411")
    )
    assertEquals(
      SingleInstanceGuard.classifyOccupant("stand", "http://localhost:6411", CanaryHome, None),
      SingleInstanceGuard.NebflowInstance("http://localhost:6411")
    )
  }

  test("spec D: strict boot refuses OtherHomeInstance (no focus, zero signal); --takeover is the only unlock") {
    val st = SingleInstanceGuard.OtherHomeInstance(ProdHome, "http://localhost:6138", ProdPid)
    assertEquals(nebflow.MainDispatch.decideStrictPort(st), Left(ExitCode.Error))
    val msg = nebflow.MainDispatch.strictRefusalMessage(st, 6138).getOrElse(fail("must have a refusal message"))
    assert(msg.contains(ProdHome), msg)
    assert(msg.contains("never"), msg)
    // 默认（无 --takeover）不接管
    nebflow.MainDispatch.setTakeover(false)
    assert(!nebflow.MainDispatch.takeover, "默认必须是不接管")
  }

  test("spec D: preemptOtherHome signals ONLY the provably-foreign listener — own home and unprovable pids untouched") {
    val killed = ListBuffer[Long]()
    val pdeps = SingleInstanceGuard.PreemptDeps(
      listeners = _ => List(ProdPid, CanaryPid, 99999L),
      readCmd = {
        case `ProdPid` => Some(daemonCmd(ProdHome))
        case `CanaryPid` => Some(daemonCmd(CanaryHome))
        case 99999L => Some("python3 -m http.server 6139") // 无 home 证据
      },
      destroyForcibly = p => killed += p
    )
    val res = SingleInstanceGuard.preemptOtherHome(6139, CanaryHome, pdeps)
    assertEquals(res.killed, List(ProdPid))
    assertEquals(killed.toList, List(ProdPid))
    assert(res.refused.exists(_.contains(CanaryPid.toString)), res.refused.toString)
    assert(res.refused.exists(_.contains("99999")), res.refused.toString)
  }

end IsolatedInstanceScopeSpec
