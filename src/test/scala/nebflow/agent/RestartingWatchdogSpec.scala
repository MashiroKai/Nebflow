package nebflow.agent

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import munit.CatsEffectSuite
import nebflow.actor.{ActorSystem, AgentCommand, AgentKind, AgentRecord}
import nebflow.core.SessionStore
import nebflow.shared.SubAgentTask

import scala.concurrent.duration.*

/**
 * #692（网络不可达退避等待修批，2026-10-21 作者令）第 ③④ 腿 spec：
 *
 *  - 等待退避阶梯（5s/15s/45s/…封顶 5min，private[agent] 常量故钉在本包）；
 *  - 重启屏障 watchdog（restarting/running 行 × registry 活体 × statusAt 超龄
 *    的三重判据）——10-21 实测「retryCount=1 卡 restarting 7.5h」的不变量：
 *    无活体行超龄必被诚实终态化，有活体行永不被误杀；
 *  - 启动清扫对 restarting 行的覆盖（respawn 半途引擎重启的僵尸形态）；
 *  - SubAgentTask statusAt 的向后兼容解码（旧盘文件无该键照读）。
 */
class RestartingWatchdogSpec extends CatsEffectSuite:

  private def mkTask(
    parentSid: String,
    childSid: String,
    status: String,
    statusAt: Option[Long],
    retryCount: Int = 0
  ): SubAgentTask =
    SubAgentTask(
      taskId = childSid,
      parentSessionId = parentSid,
      agentName = "Worker",
      prompt = "do the thing",
      description = "watchdog test task",
      status = status,
      retryCount = retryCount,
      spawnedAt = System.currentTimeMillis() - 60_000L,
      completedAt = None,
      lastError = None,
      source = "delegate",
      statusAt = statusAt
    )

  // A child that ignores every command and stays alive — only its REF matters
  // here (registry presence), no message is ever delivered in these tests.
  private lazy val ignoreChild: nebflow.actor.Behavior[AgentCommand] =
    nebflow.actor.Behaviors.receiveMessage[AgentCommand](_ => IO.pure(ignoreChild))

  private def registryWith(
    entries: (String, nebflow.actor.ActorRef[AgentCommand])*
  ): IO[Ref[IO, Map[String, AgentRecord]]] =
    // IO[Ref]：for 推导里 `registry <- registryWith()` 需要被绑定值是 Monad——裸 Ref
    // 无 flatMap（Ref.of 在 IO 内分配，语义同 Ref.unsafe 但测试更干净）。
    Ref.of[IO, Map[String, AgentRecord]](
      entries.map { case (sid, ref) =>
        sid -> AgentRecord(sessionId = sid, ref = ref, kind = AgentKind.Delegate, rootSessionId = "root")
      }.toMap
    )

  // ── ① 等待退避阶梯 ──────────────────────────────────────────────────

  test("waiting backoff ladder: 5s → 15s → 45s → 135s → cap 5min (author spec)") {
    import AgentActor.{WaitingNetworkBackoffBaseMs, WaitingNetworkBackoffMaxMs, waitingNetworkBackoffMs}
    assertEquals(WaitingNetworkBackoffBaseMs, 5_000L)
    assertEquals(waitingNetworkBackoffMs(1), 5_000L)
    assertEquals(waitingNetworkBackoffMs(2), 15_000L)
    assertEquals(waitingNetworkBackoffMs(3), 45_000L)
    assertEquals(waitingNetworkBackoffMs(4), 135_000L)
    assertEquals(waitingNetworkBackoffMs(5), WaitingNetworkBackoffMaxMs, "405s > cap ⇒ capped at 5min")
    assertEquals(waitingNetworkBackoffMs(50), WaitingNetworkBackoffMaxMs, "large counts stay capped (no overflow)")
    assertEquals(waitingNetworkBackoffMs(0), 5_000L, "round < 1 clamps to first round")
    assertEquals(WaitingNetworkBackoffMaxMs, 300_000L, "cap = 5min per author ruling")
  }

  test("nextErrorFreezeCount: same reason accumulates, reason change resets") {
    import nebflow.actor.{AgentState, FreezeReason}
    // withErrorFreezeCount 是 AgentState 的顶层扩展方法（AgentState.scala extension 块），
    // 需显式 import 进作用域（非成员方法）。
    import nebflow.actor.withErrorFreezeCount
    val base = AgentState()
    assertEquals(AgentActor.nextErrorFreezeCount(base, FreezeReason.WaitingNetwork), 1)
    val counted = base.withErrorFreezeCount(2, FreezeReason.WaitingNetwork)
    assertEquals(AgentActor.nextErrorFreezeCount(counted, FreezeReason.WaitingNetwork), 3)
    assertEquals(AgentActor.nextErrorFreezeCount(counted, FreezeReason.Network), 1, "reason change resets the count")
  }

  // ── ② watchdog 三重判据 ─────────────────────────────────────────────

  test("watchdog reaps a restarting row with no live actor past the stall threshold") {
    val store = new SubAgentTaskStore(os.temp.dir() / "subagent-tasks")
    val sessionStore = SessionStore(os.temp.dir(), os.temp.dir())
    val parentSid = "wd-parent-1"
    val childSid = "delegate-wd-child-1"
    val old = System.currentTimeMillis() - 20 * 60_000L
    for
      _ <- store.recordTask(mkTask(parentSid, childSid, "restarting", statusAt = Some(old), retryCount = 1))
      registry <- registryWith() // 无活体
      reaped <- SubAgentStartupRecovery.watchdogSweep(store, registry, sessionStore, stallMs = 10 * 60_000L)
      tasks <- store.loadTasks(parentSid)
    yield
      assertEquals(reaped, List(childSid), "zombie restarting row must be force-terminalized")
      val row = tasks.find(_.taskId == childSid).get
      assertEquals(row.status, "failed")
      assert(row.lastError.exists(_.contains("watchdog")), s"honest watchdog note expected, got ${row.lastError}")
  }

  test("watchdog never touches a row whose registry entry is alive (long-running respawned worker)") {
    val store = new SubAgentTaskStore(os.temp.dir() / "subagent-tasks")
    val sessionStore = SessionStore(os.temp.dir(), os.temp.dir())
    val parentSid = "wd-parent-2"
    val childSid = "delegate-wd-child-2"
    val old = System.currentTimeMillis() - 20 * 60_000L
    val system = ActorSystem("wd-live-test")
    for
      dummy <- system.spawn(ignoreChild, "wd-live-ref-2")
      // 健康形态：respawn 后任务行长期停在 restarting（无回写 running 的历史行为），
      // 但 registry 有活体行 —— 7.5h 也不能杀。
      _ <- store.recordTask(mkTask(parentSid, childSid, "restarting", statusAt = Some(old), retryCount = 1))
      registry <- registryWith(childSid -> dummy)
      reaped <- SubAgentStartupRecovery.watchdogSweep(store, registry, sessionStore, stallMs = 10 * 60_000L)
      tasks <- store.loadTasks(parentSid)
      _ <- system.stopAll
    yield
      assertEquals(reaped, Nil, "live actor ⇒ never a zombie, no matter the row age")
      assertEquals(tasks.find(_.taskId == childSid).get.status, "restarting")
  }

  test("watchdog skips rows still inside the stall window") {
    val store = new SubAgentTaskStore(os.temp.dir() / "subagent-tasks")
    val sessionStore = SessionStore(os.temp.dir(), os.temp.dir())
    val parentSid = "wd-parent-3"
    val childSid = "delegate-wd-child-3"
    for
      // respawn 半途的合法窗口：restarting 刚写 5s
      _ <- store.recordTask(mkTask(parentSid, childSid, "restarting", statusAt = Some(System.currentTimeMillis())))
      registry <- registryWith()
      reaped <- SubAgentStartupRecovery.watchdogSweep(store, registry, sessionStore, stallMs = 10 * 60_000L)
      tasks <- store.loadTasks(parentSid)
    yield
      assertEquals(reaped, Nil, "fresh restarting row = respawn in flight, not a zombie")
      assertEquals(tasks.find(_.taskId == childSid).get.status, "restarting")
  }

  test("watchdog covers legacy rows without statusAt (falls back to spawnedAt) and running orphans too") {
    val dir = os.temp.dir() / "subagent-tasks"
    val store = new SubAgentTaskStore(dir)
    val sessionStore = SessionStore(os.temp.dir(), os.temp.dir())
    val parentSid = "wd-parent-4"
    // 旧盘格式（无 statusAt 键）+ spawnedAt 早已超龄 + running 无活体 = 修复前的存量僵尸。
    // recordTask 会强制补 statusAt=Some(spawnedAt)，构造不出「无 statusAt」的行——
    // 旧盘行必须按原始 JSON 直写文件（与 ④ 的向后兼容解码 spec 同一形状）。
    // 注意 store 文件形态 = **JSON 数组**（一父一文件装 List[SubAgentTask]），非单对象。
    val legacySpawnedAt = System.currentTimeMillis() - 20 * 60_000L
    val raw =
      s"""[{"taskId":"delegate-wd-child-4","parentSessionId":"$parentSid","agentName":"Worker","prompt":"do the thing","description":"watchdog test task","status":"running","retryCount":0,"spawnedAt":$legacySpawnedAt,"completedAt":null,"lastError":null,"source":"delegate"}]"""
    os.makeDir.all(dir)
    os.write(dir / s"$parentSid.json", raw)
    for
      registry <- registryWith()
      reaped <- SubAgentStartupRecovery.watchdogSweep(store, registry, sessionStore, stallMs = 10 * 60_000L)
    yield assertEquals(reaped, List("delegate-wd-child-4"), "legacy orphan rows are reaped via spawnedAt fallback")
    end for
  }

  // ── ③ 启动清扫覆盖 restarting 行 ────────────────────────────────────

  test("boot orphan sweep terminalizes restarting rows (engine restart mid-respawn)") {
    val store = new SubAgentTaskStore(os.temp.dir() / "subagent-tasks")
    val sessionStore = SessionStore(os.temp.dir(), os.temp.dir())
    val parentSid = "wd-parent-5"
    val childSid = "delegate-wd-child-5"
    for
      _ <- store.recordTask(
        mkTask(parentSid, childSid, "restarting", statusAt = Some(System.currentTimeMillis()), retryCount = 1)
      )
      reaped <- SubAgentStartupRecovery.recoverOrphans(store, sessionStore)
      tasks <- store.loadTasks(parentSid)
    yield
      assertEquals(reaped, List(childSid), "boot sweep must include restarting rows (the 7.5h zombie shape)")
      assertEquals(tasks.find(_.taskId == childSid).get.status, "failed")
  }

  // ── ④ statusAt 向后兼容解码 ─────────────────────────────────────────

  test("SubAgentTask decoder tolerates old files without statusAt (no whole-file drop)") {
    import io.circe.parser.decode
    val old = """{"taskId":"d-1","parentSessionId":"p","agentName":"W","prompt":"x","description":"d",
      |"status":"running","retryCount":0,"spawnedAt":123,"completedAt":null,"lastError":null,
      |"source":"delegate"}""".stripMargin
    decode[SubAgentTask](old) match
      case Right(t) =>
        assertEquals(t.taskId, "d-1")
        assertEquals(t.statusAt, None, "missing key = None (watchdog falls back to spawnedAt)")
      case Left(err) => fail(s"old-format task file must still decode, got: $err")
    end match
  }

end RestartingWatchdogSpec
