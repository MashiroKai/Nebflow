package nebflow.core.hotupdate

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import io.circe.parser
import munit.CatsEffectSuite
import nebflow.shared.PathUtil

import scala.concurrent.duration.*

/**
 * 全设备统一更新定向验收（fleet 批 2026-11-01，作者令「一键把所有设备更新到同一版本」）。
 *
 * 全部**进程内**：不 spawn 进程、不装包、不真重启、不碰端口、**不发网络**（`probe` 与
 * `pushRemote` 全部注入 fake）。覆盖作者令的五条验收面：
 *
 *  1. **版本核对 + 能力门槛**：自报优先 → 版本门槛 → 过旧拒推 → 未知保守拒推；
 *  2. **分发分支**：在线×能力 四格 ⇒ push / manual / queued / manual；
 *  3. **收口状态**：全部一致 / 有待手动 / 有待上线（三档 + 数量四元组永不缺项）；
 *  4. **单机失败隔离**：一台失败不打断其他设备，收口 = partial；
 *  5. **状态机阶段流转 + 断线/重启恢复**：落盘恢复把「在飞」标成失联（绝不假装还在跑）。
 *
 * 另钉两条纪律面：缺确认位 ⇒ 拒（不执行、不写台账）；审计行只增。
 */
class FleetUpdateSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 60.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-fleet-update"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot)

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)

  override def beforeEach(context: munit.BeforeEach): Unit =
    os.remove.all(tempRoot / "hotupdate")

  // ── ① 能力门槛（纯函数）──────────────────────────────────────────────────

  test("capability: self-report wins over the version threshold (a new build may still declare no)") {
    assertEquals(
      UpdateCapability.verdict(Some("2026.10.26"), selfReported = true),
      UpdateCapability.Verdict.SelfReported
    )
    assertEquals(
      UpdateCapability.verdict(Some("2025.01.01"), selfReported = true),
      UpdateCapability.Verdict.SelfReported
    )
  }

  test("capability: version threshold — at/above the floor is supported, below is too old") {
    assertEquals(
      UpdateCapability.verdict(Some(UpdateCapability.MinRemoteVersion), selfReported = false),
      UpdateCapability.Verdict.VersionThreshold
    )
    assertEquals(
      UpdateCapability.verdict(Some("2027.01.01"), selfReported = false),
      UpdateCapability.Verdict.VersionThreshold
    )
    assertEquals(
      UpdateCapability.verdict(Some("2026.10.25"), selfReported = false),
      UpdateCapability.Verdict.VersionTooOld
    )
    assertEquals(
      UpdateCapability.verdict(Some("2025.09.30-beta.4"), selfReported = false),
      UpdateCapability.Verdict.VersionTooOld
    )
  }

  test("capability: unknown version ⇒ conservative 'not supported' (never guessed as updatable)") {
    assertEquals(
      UpdateCapability.verdict(None, selfReported = false),
      UpdateCapability.Verdict.Unknown
    )
    assertEquals(
      UpdateCapability.verdict(Some("   "), selfReported = false),
      UpdateCapability.Verdict.Unknown
    )
    assert(!UpdateCapability.Verdict.Unknown.supported)
    assert(UpdateCapability.reasonOf(UpdateCapability.Verdict.Unknown, None).isDefined)
  }

  // ── ② 分发决策树（作者令四格）──────────────────────────────────────────────

  test("dispatch: the four cells — push / manual / queued / manual") {
    assertEquals(FleetDispatch.branch(online = true, supported = true), FleetDispatch.Branch.Push)
    assertEquals(FleetDispatch.branch(online = true, supported = false), FleetDispatch.Branch.Manual)
    assertEquals(FleetDispatch.branch(online = false, supported = true), FleetDispatch.Branch.Queued)
    assertEquals(FleetDispatch.branch(online = false, supported = false), FleetDispatch.Branch.Manual)
  }

  test("plan: three-device roster (in-sync / outdated / too old) yields the closing status + all counts") {
    val target = Some("2026.11.01")
    val devs = List(
      view("d1", "in-sync", "2026.11.01", UpdateCapability.Verdict.VersionThreshold) -> true,
      view("d2", "outdated", "2026.10.26", UpdateCapability.Verdict.VersionThreshold) -> true,
      view("d3", "old", "2026.09.01", UpdateCapability.Verdict.VersionTooOld) -> true
    )
    val plan = FleetPlan.build(devs, target)
    assertEquals(plan.devices.map(_.branch), List(FleetDispatch.Branch.Push, FleetDispatch.Branch.Push, FleetDispatch.Branch.Manual))
    assertEquals(plan.devices.map(_.versionState), List("in-sync", "outdated", "outdated"))
    // `pushCount` = **本轮真会被推送**的台数（执行体对 `in-sync` 行直接判完成、不推），
    // 不是「分支落在 Push 列」的台数：d1 已同版 ⇒ 不推。判据单点在 `FleetSummary.of`。
    assertEquals(plan.summary.pushCount, 1)
    assertEquals(plan.devices.count(_.branch == FleetDispatch.Branch.Push), 2)
    assertEquals(plan.summary.manualCount, 1)
    assertEquals(plan.summary.pendingOnlineCount, 0)
    assertEquals(plan.summary.totalDevices, 3)
    assertEquals(plan.summary.status, "manual-required")
    assert(!plan.summary.allConsistent)
    // 帧面：判据键存在且与 status 同源（前端禁硬编码文案）
    assertEquals(
      plan.summary.toJson.hcursor.downField("messageKey").as[String].toOption,
      Some("fleet.summary.manual-required")
    )
  }

  test("plan: offline + supported ⇒ queued (pending-online), offline + unsupported ⇒ manual") {
    val plan = FleetPlan.build(
      List(
        view("d1", "offline-new", "2026.11.01", UpdateCapability.Verdict.VersionThreshold) -> false,
        view("d2", "offline-old", "2026.08.01", UpdateCapability.Verdict.VersionTooOld) -> false
      ),
      Some("2026.11.01")
    )
    assertEquals(plan.devices.head.branch, FleetDispatch.Branch.Queued)
    assertEquals(plan.devices(1).branch, FleetDispatch.Branch.Manual)
    assertEquals(plan.summary.pendingOnlineCount, 1)
    assertEquals(plan.summary.manualCount, 1)
    assertEquals(plan.summary.status, "manual-required")
  }

  test("plan: everything reachable and in sync ⇒ all-consistent") {
    val plan = FleetPlan.build(
      List(view("d1", "a", "2026.11.01", UpdateCapability.Verdict.SelfReported) -> true),
      Some("2026.11.01")
    )
    assertEquals(plan.summary.status, "all-consistent")
    assert(plan.summary.allConsistent)
    // 不变量（`FleetSummary.of` 注释钉住）：该档 pushCount 恒为 0 —— 已经同版就没什么
    // 可推的。按「分支落在 Push 列」计数会把这里读成 1，本条即该回归的钉子。
    assertEquals(plan.summary.pushCount, 0)
    assertEquals(plan.devices.head.branch, FleetDispatch.Branch.Push)
  }

  // 🔴 e2e 实跑抓到的缺陷（2026-11-02）：一台**可达且可推但版本落后**的设备，计划相
  // 曾读成「全部一致 / pushCount=1」——面板在动手之前就宣布已经一致了。收口句只能说
  // 它真实知道的事：「可推」≠「已一致」。
  test("plan: an outdated (but pushable) device is NOT 'all-consistent' — pushing ≠ consistent") {
    val plan = FleetPlan.build(
      List(view("d1", "a", "2026.10.26", UpdateCapability.Verdict.VersionThreshold) -> true),
      Some("2026.11.01")
    )
    assertEquals(plan.devices.map(_.branch), List(FleetDispatch.Branch.Push))
    assertEquals(plan.devices.map(_.versionState), List("outdated"))
    assertEquals(plan.summary.status, "out-of-sync")
    assert(!plan.summary.allConsistent)
    assertEquals(plan.summary.pushCount, 1)
    assertEquals(
      plan.summary.toJson.hcursor.downField("messageKey").as[String].toOption,
      Some("fleet.summary.out-of-sync")
    )
  }

  test("plan: unknown target version can never read as 'all-consistent' either") {
    val plan = FleetPlan.build(
      List(view("d1", "a", "2026.11.01", UpdateCapability.Verdict.SelfReported) -> true),
      None
    )
    assertEquals(plan.devices.map(_.versionState), List("unknown"))
    assertEquals(plan.summary.status, "out-of-sync")
    assert(!plan.summary.allConsistent)
  }

  // ── ③ 协调器：确认位 / 失败隔离 / 收口 / 审计 ─────────────────────────────

  test("coordinator: missing confirm ⇒ Left, nothing executed, no audit row written") {
    val program = for
      pushes <- IO.ref(0)
      fleet <- mkFleet(pushes, okDeviceIds = Set("remote-a"))
      res <- fleet.start(confirm = false, source = "test")
      _ <- IO.sleep(150.millis)
      count <- pushes.get
      rows <- auditRows
    yield (res, count, rows)
    program.map { case (res, count, rows) =>
      assert(res.isLeft, s"expected a refusal, got $res")
      assert(res.left.exists(_.contains("confirm=true")))
      assertEquals(count, 0)
      assertEquals(rows, Nil)
    }
  }

  test("coordinator: one device failing does not block the others; the job closes as partial") {
    val program = for
      pushes <- IO.ref(0)
      // remote-b 的推送一律失败；remote-a 成功
      fleet <- mkFleet(pushes, okDeviceIds = Set("remote-a"))
      started <- fleet.start(confirm = true, source = "test")
      _ <- waitDone(fleet)
      snap <- fleet.snapshot
    yield (started, snap)
    program.map { case (started, snap) =>
      assert(started.isRight, s"start expected, got $started")
      val status = snap.hcursor.downField("job").downField("job").downField("status").as[String].toOption
      assertEquals(status, Some("partial"))
      val runs = snap.hcursor.downField("job").downField("runs").as[List[io.circe.Json]].toOption.getOrElse(Nil)
      val byId = runs.map(j => j.hcursor.downField("deviceId").as[String].toOption.getOrElse("") -> j).toMap
      assertEquals(byId("remote-a").hcursor.downField("stage").as[String].toOption, Some("done"))
      assertEquals(byId("remote-b").hcursor.downField("stage").as[String].toOption, Some("failed"))
      // 失败分类进帧（前端按 failureKey 取建议文案）
      assertEquals(
        byId("remote-b").hcursor.downField("failure").as[String].toOption,
        Some("device-refused")
      )
      // 收口四句的数量面必须齐全（作者令：不许只报「已发起」）
      val job = snap.hcursor.downField("job").downField("job")
      assert(job.downField("updatedCount").as[Int].toOption.getOrElse(0) >= 1)
      // 失败行 = remote-b（推送被拒）+ 本机（夹具未装配编排器）⇒ ≥1，且失败清单逐条
      // 带设备名与失败分类（面板按此渲染「Y 台失败（原因）」）。
      assert(job.downField("failedCount").as[Int].toOption.getOrElse(0) >= 1)
      val failedList = job.downField("failed").as[List[io.circe.Json]].toOption.getOrElse(Nil)
      assert(failedList.exists(_.hcursor.downField("deviceId").as[String].toOption.contains("remote-b")))
      assert(failedList.forall(_.hcursor.downField("failureKey").as[String].toOption.isDefined))
      assert(job.downField("manualCount").as[Int].toOption.isDefined)
      assert(job.downField("pendingOnlineCount").as[Int].toOption.isDefined)
    }
  }

  test("coordinator: an offline-but-capable device is queued, and the sentinel pushes it when it appears") {
    val program = for
      pushes <- IO.ref(0)
      onlineFlag <- IO.ref(false)
      fleet <- IO.pure(
        new FleetUpdateCoordinator(
          orchestrator = None,
          broadcast = _ => IO.unit,
          listDevices = () =>
            onlineFlag.get.map { on =>
              List(
                FleetUpdateCoordinator.Peer("local", "this-mac", "darwin", "", online = true,
                  Some(nebflow.Version.string), isLocal = true),
                FleetUpdateCoordinator.Peer("remote-c", "win-box", "win32", "http://127.0.0.1:1", on,
                  Some("2026.10.26"), isLocal = false)
              )
            },
          probe = p =>
            // 本机行由协调器自己判（不调 probe）；远端报版本达标 ⇒ 可远控
            IO.pure(
              if p.isLocal then FleetUpdateCoordinator.ProbeResult(Some(nebflow.Version.string), false)
              else FleetUpdateCoordinator.ProbeResult(Some("2026.10.26"), false)
            ),
          pushRemote = (peer, _, _) =>
            pushes.update(_ + 1).as(
              if peer.deviceId == "remote-c" then Right("accepted")
              else Left("unexpected device")
            ),
          latestVersion = () => IO.pure(Some("2026.11.01")),
          currentVersion = () => "2026.10.26",
          verifyWindow = 2.seconds,
          pollInterval = 100.millis
        )
      )
      started <- fleet.start(confirm = true, source = "test")
      atStart <- pushes.get
      queuedSnap <- fleet.snapshot
      // 设备上线 ⇒ 哨兵补推
      _ <- onlineFlag.set(true)
      _ <- fleet.onPeerOnline("remote-c")
      atEnd <- pushes.get
      snap <- fleet.snapshot
    yield (started, atStart, queuedSnap, atEnd, snap)
    program.map { case (started, atStart, queuedSnap, atEnd, snap) =>
      assert(started.isRight)
      assertEquals(atStart, 0) // 离线 ⇒ 本轮不推
      val pending = queuedSnap.hcursor.downField("job").downField("pending").as[List[io.circe.Json]]
        .toOption.getOrElse(Nil)
      assertEquals(pending.length, 1)
      assertEquals(pending.head.hcursor.downField("deviceId").as[String].toOption, Some("remote-c"))
      assertEquals(atEnd, 1) // 上线即补推
      val runs = snap.hcursor.downField("job").downField("runs").as[List[io.circe.Json]]
        .toOption.getOrElse(Nil)
      val row = runs.find(_.hcursor.downField("deviceId").as[String].toOption.contains("remote-c"))
      assert(row.isDefined, s"remote-c row missing: ${runs.mkString}")
    }
  }

  test("coordinator: audit log is append-only and records start + finish") {
    val program = for
      pushes <- IO.ref(0)
      fleet <- mkFleet(pushes, okDeviceIds = Set("remote-a"))
      _ <- fleet.start(confirm = true, source = "test")
      _ <- waitDone(fleet)
      rows <- auditRows
    yield rows
    program.map { rows =>
      val actions = rows.flatMap(l => parser.parse(l).toOption)
        .flatMap(j => j.hcursor.downField("action").as[String].toOption)
      assert(actions.contains("fleet-start"), s"missing fleet-start in $actions")
      assert(actions.contains("fleet-finish"), s"missing fleet-finish in $actions")
    }
  }

  // 🔴 e2e ⑦ 实跑抓到的缺陷（2026-11-02）：一台离线排队待补推的设备（`waiting-online`
  // 是本轮终态）曾让任务读成 `completed` —— 收口句写着「1 台待上线」而状态是「已完成」，
  // 自相矛盾。作者令的收口三档里只有「全部一致」能读成 completed。
  test("coordinator: a job with a queued (waiting-online) device closes as partial, never completed") {
    val program = for
      pushes <- IO.ref(0)
      fleet <- IO.pure(
        new FleetUpdateCoordinator(
          orchestrator = None,
          broadcast = _ => IO.unit,
          listDevices = () =>
            IO.pure(
              List(
                FleetUpdateCoordinator.Peer("local", "this-mac", "darwin", "", online = true,
                  Some("2026.11.01"), isLocal = true),
                FleetUpdateCoordinator.Peer("remote-d", "offline-box", "linux", "http://127.0.0.1:9",
                  online = false, Some("2026.11.01"), isLocal = false)
              )
            ),
          probe = p => IO.pure(FleetUpdateCoordinator.ProbeResult(if p.isLocal then Some("2026.11.01") else Some("2026.11.01"), true)),
          pushRemote = (_, _, _) => pushes.update(_ + 1).as(Right("accepted")),
          latestVersion = () => IO.pure(Some("2026.11.01")),
          currentVersion = () => "2026.11.01",
          verifyWindow = 500.millis,
          pollInterval = 50.millis
        )
      )
      started <- fleet.start(confirm = true, source = "test")
      _ <- waitDone(fleet)
      snap <- fleet.snapshot
      pushed <- pushes.get
    yield (started, snap, pushed)
    program.map { case (started, snap, pushed) =>
      assert(started.isRight, s"start expected, got $started")
      val job = snap.hcursor.downField("job").downField("job")
      assertEquals(job.downField("pendingOnlineCount").as[Int].toOption, Some(1))
      assertEquals(job.downField("updatedCount").as[Int].toOption, Some(1)) // 本机已同版
      assertEquals(job.downField("status").as[String].toOption, Some("partial"))
      assertEquals(pushed, 0) // 离线 ⇒ 一次推送都没发（不假跑）
    }
  }

  // ── ④ 状态机 / 恢复（断线重连与重启恢复）──────────────────────────────────

  test("restore: a job persisted as running comes back with in-flight rows marked device-lost, never faked") {
    val jobPath = tempRoot / "hotupdate" / "fleet-job.json"
    val stale = FleetJob(
      id = "fleet-stale-1",
      startedAt = System.currentTimeMillis() - 60_000,
      finishedAt = 0L,
      source = "test",
      targetVersion = Some("2026.11.01"),
      status = FleetJobStatus.Running,
      runs = List(
        FleetDeviceRun("local", "this-mac", isLocal = true, stage = FleetStage.Restarting,
          detail = "in flight when the process died", updatedAt = System.currentTimeMillis() - 60_000),
        FleetDeviceRun("remote-a", "mac-mini", isLocal = false, stage = FleetStage.Completed,
          detail = "done before the restart", updatedAt = System.currentTimeMillis() - 60_000)
      ),
      manual = Nil,
      pending = Nil
    )
    val program = for
      _ <- IO.blocking {
        os.makeDir.all(jobPath / os.up)
        os.write.over(jobPath, stale.toJson.noSpaces)
      }
      pushes <- IO.ref(0)
      fleet <- mkFleet(pushes, okDeviceIds = Set("remote-a"))
      _ <- fleet.restore()
      snap <- fleet.snapshot
    yield snap
    program.map { snap =>
      val job = snap.hcursor.downField("job").downField("job")
      assertEquals(job.downField("jobId").as[String].toOption, Some("fleet-stale-1"))
      assertEquals(job.downField("status").as[String].toOption, Some("partial"))
      val runs = snap.hcursor.downField("job").downField("runs").as[List[io.circe.Json]].toOption.getOrElse(Nil)
      val local = runs.find(_.hcursor.downField("deviceId").as[String].toOption.contains("local")).get
      assertEquals(local.hcursor.downField("stage").as[String].toOption, Some("failed"))
      assertEquals(local.hcursor.downField("failure").as[String].toOption, Some("device-lost"))
      // 已完成行原样保留（不重跑、不篡改）
      val done = runs.find(_.hcursor.downField("deviceId").as[String].toOption.contains("remote-a")).get
      assertEquals(done.hcursor.downField("stage").as[String].toOption, Some("done"))
    }
  }

  test("stage vocabulary: every FleetStage carries a fleet.stage.* key and a state token; terminals are frozen") {
    FleetStage.values.foreach { s =>
      assert(s.messageKey.startsWith("fleet.stage."), s"bad key for ${s.wire}: ${s.messageKey}")
      assert(Set("pending", "in-progress", "completed", "failed", "manual", "cancelled").contains(s.stateToken),
        s"bad state token for ${s.wire}: ${s.stateToken}")
    }
    assert(FleetStage.Completed.terminal)
    assert(FleetStage.Failed.terminal)
    assert(FleetStage.Manual.terminal)
    assert(FleetStage.WaitingOnline.terminal)
    assert(!FleetStage.Restarting.terminal)
    // 回滚档不自动重试（需人工），其余可重试
    assert(!FleetFailure.SwitchRolledBack.retryable)
    assert(FleetFailure.DownloadFailed.retryable)
    assert(FleetFailure.DeviceLost.retryable)
  }

  // ── 夹具 ──────────────────────────────────────────────────────────────────

  /**
   * 一台本机 + 两台远端；`okDeviceIds` 之外的推送一律失败（分类 = device-refused）。
   * 推送成功后该设备的**探测版本**随即变新（模拟真实设备装完重启回来）——否则健康
   * 自检腿会一直等到窗口尽头判 `healthcheck-failed`（那是另一条验收路径，本夹具不测）。
   */
  private def mkFleet(
    pushes: cats.effect.Ref[IO, Int],
    okDeviceIds: Set[String]
  ): IO[FleetUpdateCoordinator] =
    IO.ref(Set.empty[String]).flatMap { updated =>
      IO.pure(
        new FleetUpdateCoordinator(
          orchestrator = None, // 本机腿无编排器 ⇒ 记 device-refused（不假成功）
          broadcast = _ => IO.unit,
          listDevices = () =>
            IO.pure(
              List(
                FleetUpdateCoordinator.Peer("local", "this-mac", "darwin", "", online = true,
                  Some(nebflow.Version.string), isLocal = true),
                FleetUpdateCoordinator.Peer("remote-a", "mac-mini", "darwin", "http://127.0.0.1:1", true,
                  Some("2026.10.26"), isLocal = false),
                FleetUpdateCoordinator.Peer("remote-b", "win-box", "win32", "http://127.0.0.1:2", true,
                  Some("2026.10.26"), isLocal = false)
              )
            ),
          probe = p =>
            updated.get.map { done =>
              val v =
                if p.isLocal then Some(nebflow.Version.string)
                else if done.contains(p.deviceId) then Some("2026.11.01")
                else Some("2026.10.26")
              FleetUpdateCoordinator.ProbeResult(v, false)
            },
          pushRemote = (peer, _, _) =>
            pushes.update(_ + 1) *>
              (if okDeviceIds.contains(peer.deviceId) then
                 updated.update(_ + peer.deviceId).as(Right("accepted"))
               else IO.pure(Left("device refused the update (test fixture)"))),
          latestVersion = () => IO.pure(Some("2026.11.01")),
          currentVersion = () => "2026.10.26",
          verifyWindow = 1.second,
          pollInterval = 50.millis
        )
      )
    }

  private def waitDone(fleet: FleetUpdateCoordinator): IO[Unit] =
    def statusOnce: IO[Option[String]] =
      fleet.snapshot.map(_.hcursor.downField("job").downField("job").downField("status").as[String].toOption)
    def spin(remaining: Int): IO[Unit] =
      statusOnce.flatMap {
        case Some(s) if s != "running" => IO.unit
        case _ if remaining <= 0 =>
          fleet.snapshot.flatMap(s => IO.raiseError(new AssertionError(s"job never closed: ${s.noSpaces}")))
        case _ => IO.sleep(50.millis) *> spin(remaining - 1)
      }
    spin(400) // 400 × 50ms = 20s 上限

  private def auditRows: IO[List[String]] =
    IO.blocking {
      val p = tempRoot / "hotupdate" / "fleet-audit.jsonl"
      if os.exists(p) then os.read.lines(p).toList else Nil
    }

  private def view(
    id: String,
    name: String,
    version: String,
    cap: UpdateCapability.Verdict
  ): FleetDeviceView =
    FleetDeviceView(id, name, "darwin", isLocal = false, online = true, Some(version), cap,
      FleetDispatch.Branch.Manual, None)

end FleetUpdateSpec
