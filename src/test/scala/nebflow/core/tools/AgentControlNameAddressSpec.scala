package nebflow.core.tools

import cats.effect.std.Dispatcher
import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.{ActorSystem, Behavior, Behaviors}
import nebflow.actor.{AgentCommand, AgentKind, AgentRecord, AgentStatus}
import nebflow.agent.*
import nebflow.core.FileChangeTracker
import nebflow.core.compact.HistoryArchiver
import nebflow.core.delegate.{DelegateRegistry, MailAddress}
import nebflow.core.task.FileTaskStore
import nebflow.core.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor}
import nebflow.shared.{SubAgentTask, ThinkingConfig}

import scala.concurrent.duration.*

/**
 * AgentControl list/status 的 name + address 字段（name/address 批 2026-11-05，
 * 作者令「agentcontrol 工具应该要能返回在跑的 delegate 的 name 和地址，方便投递」）。
 *
 * 造三类真实记录（同一 agentRegistry + 同一 DelegateRegistry 地址簿）：
 *  - Delegate 批次（`delegate-*`，地址簿有 name = "alpha"）；
 *  - kernel 实例（`delegate-kernel-*`，name = "kern"）；
 *  - Project 节点会话（`node-*`，displayName = 节点名 → `node:<名>`）；
 * 外加：一个 Team 行（无 Mail 面 ⇒ 两列都占位）与一个 orphan 任务行（无 live
 * actor ⇒ 占位，且**列数必须与表头齐**——表头加 address 列时 orphan 行少了一格
 * 的历史错位在本批一并钉死）。
 *
 * 判据（不止「字符串出现过」）：
 *  ① 表头含 address 与 name 两列；
 *  ② 每行单元格数 == 表头单元格数（表格不错位）；
 *  ③ 每行的 address 值能被 Mail 自己的分层面闸解析（形态可原样粘进 Mail）；
 *  ④ status 返回同两个字段，值与 list 逐字一致。
 */
class AgentControlNameAddressSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 60.seconds

  private def mkRecordingCmd: Behavior[AgentCommand] =
    def loop: Behavior[AgentCommand] = Behaviors.receiveMessage(_ => IO.pure(loop))
    loop

  private def mkResources(system: ActorSystem, tmp: os.Path): IO[SharedResources] =
    for
      dispatcher <- Dispatcher.parallel[IO].allocated.map(_._1)
      rateLimiter <- RateLimiter.create()
      tracker <- FileChangeTracker.create(os.pwd.toString)
      fileLocks <- FileLockManager.create
      thinkingRef <- Ref.of[IO, ThinkingConfig](ThinkingConfig())
      modelOverrides <- Ref.of[IO, Map[String, ModelCandidate]](Map.empty)
      voiceMuted <- Ref.of[IO, Boolean](false)
    yield SharedResources(
      llm = null,
      dispatcher = dispatcher,
      sessionStore = SessionStore(tmp / "sessions", tmp / "tasks"),
      projectRoot = os.pwd,
      thinkingConfigRef = thinkingRef,
      rateLimiter = rateLimiter,
      fileChangeTracker = tracker,
      contextWindow = 100_000,
      agentLibrary = new AgentLibrary(tmp / "agents"),
      taskStore = FileTaskStore,
      historyArchiver = HistoryArchiver.fileSystem(tmp / "archives"),
      fileLockManager = fileLocks,
      sessionModelOverrides = modelOverrides,
      providerRegistry = null,
      healthMonitor = ProviderHealthMonitor(null),
      actorSystem = system,
      subAgentTaskStore = new SubAgentTaskStore(tmp / "subagent-tasks"),
      voiceMutedRef = voiceMuted
    )

  private def ctx(resources: SharedResources, sessionId: String): ToolContext =
    ToolContext(projectRoot = os.pwd.toString, sessionId = Some(sessionId), sharedResources = Some(resources))

  private def callAction(
    resources: SharedResources,
    callerSid: String,
    action: String,
    target: String = ""
  ): Either[ToolError, String] =
    AgentControlTool
      .call(
        io.circe.JsonObject("action" -> action.asJson, "sessionId" -> target.asJson),
        ctx(resources, callerSid)
      )
      .unsafeRunSync()

  private def mkTask(parentSid: String, childSid: String, agent: String = "Worker"): SubAgentTask =
    SubAgentTask(
      taskId = childSid,
      parentSessionId = parentSid,
      agentName = agent,
      prompt = "spec prompt",
      description = "spec task",
      status = "running",
      retryCount = 0,
      spawnedAt = System.currentTimeMillis(),
      completedAt = None,
      lastError = None,
      source = "delegate"
    )

  private def delegateRecord(id: String, parentSid: String, name: String): DelegateRegistry.Record =
    DelegateRegistry.Record(
      id = id,
      executor = nebflow.core.executor.ExecutorRegistry.DefaultId,
      cwd = os.pwd.toString,
      project = None,
      taskId = None,
      parentSessionId = parentSid,
      title = s"batch $name",
      createdAt = System.currentTimeMillis(),
      name = Some(name)
    )

  /** 表格行 → 单元格（markdown 管表；首尾空串丢弃）。 */
  private def cells(line: String): List[String] =
    line.stripPrefix("|").stripSuffix("|").split("\\|", -1).map(_.trim).toList

  private def rowFor(out: String, sid: String): List[String] =
    out.linesIterator.toList
      .find(l => l.startsWith("| ") && l.contains(s"| $sid |"))
      .map(cells)
      .getOrElse(fail(s"no table row for '$sid' in:\n$out"))

  // ── list：两列在场 + 值正确 + 不错位 ──────────────────────────

  test("list returns name + address per row (3 kinds) and the table stays aligned") {
    val system = ActorSystem("ac-name-address-list")
    val tmp = os.temp.dir()
    val prevRoot = nebflow.shared.PathUtil.dataRoot
    nebflow.shared.PathUtil.setDataRoot(tmp / "data")
    val parentSid = "root-na"
    val delegateSid = "delegate-Explorer-aaaa1111"
    val kernelSid = "delegate-kernel-1f2e3d4c"
    val nodeSid = "node-819"
    val orphanSid = "delegate-Ghost-cccc3333"
    val teamSid = "team-nebflow-project-Backend"
    val program = for
      resources <- mkResources(system, tmp)
      _ <- IO.blocking {
        DelegateRegistry.put(delegateRecord(delegateSid, parentSid, "alpha"))
        DelegateRegistry.put(delegateRecord(kernelSid, parentSid, "kern"))
      }
      _ <- resources.subAgentTaskStore.recordTask(mkTask(parentSid, delegateSid, "Explorer"))
      _ <- resources.subAgentTaskStore.recordTask(mkTask(parentSid, orphanSid, "Ghost"))
      delRef <- system.spawn(mkRecordingCmd, "na-del")
      kerRef <- system.spawn(mkRecordingCmd, "na-ker")
      nodeRef <- system.spawn(mkRecordingCmd, "na-node")
      teamRef <- system.spawn(mkRecordingCmd, "na-team")
      now = System.currentTimeMillis()
      _ <- resources.agentRegistry.set(
        Map(
          delegateSid -> AgentRecord(
            delegateSid,
            delRef,
            AgentKind.Delegate,
            parentSid,
            startedAt = now - 120_000,
            status = AgentStatus.Processing,
            lastActivityMs = now - 30_000
          ),
          kernelSid -> AgentRecord(
            kernelSid,
            kerRef,
            AgentKind.Delegate,
            parentSid,
            startedAt = now - 60_000,
            status = AgentStatus.Processing,
            lastActivityMs = now - 10_000
          ),
          nodeSid -> AgentRecord(
            nodeSid,
            nodeRef,
            AgentKind.Flow,
            parentSid,
            startedAt = now - 30_000,
            status = AgentStatus.Processing,
            lastActivityMs = now - 5_000,
            project = Some("stand"),
            displayName = Some("worker-1")
          ),
          teamSid -> AgentRecord(teamSid, teamRef, AgentKind.Team, teamSid)
        )
      )
      out <- IO.fromEither(
        callAction(resources, parentSid, "list").left.map(e => new AssertionError(e.message))
      )
    yield
      val lines = out.linesIterator.toList.filter(_.startsWith("| "))
      assert(lines.nonEmpty, s"table must render:\n$out")
      val header = cells(lines.head)
      // ① 两列在场（表头顺序 = sessionId | address | name | …）
      assert(header.contains("address"), s"header must carry 'address':\n${lines.head}")
      assert(header.contains("name"), s"header must carry 'name':\n${lines.head}")
      assertEquals(header.take(3), List("sessionId", "address", "name"))
      // ② 不错位：每一行单元格数 == 表头单元格数（含 orphan 行——历史错位点）
      lines.foreach { l =>
        assertEquals(cells(l).size, header.size, s"row width must match header:\n$l")
      }
      // ③ 三 kind 的地址值与短名
      val delRow = rowFor(out, delegateSid)
      assertEquals(delRow(1), s"delegate:$delegateSid", s"delegate address cell:\n$out")
      assertEquals(delRow(2), "alpha", s"delegate name cell (DelegateRegistry.Record.name):\n$out")
      val kerRow = rowFor(out, kernelSid)
      assertEquals(kerRow(1), s"kernel:$kernelSid", s"kernel address cell:\n$out")
      assertEquals(kerRow(2), "kern", s"kernel name cell:\n$out")
      val nodeRow = rowFor(out, nodeSid)
      assertEquals(nodeRow(1), "node:worker-1", s"node address cell (displayName = node name):\n$out")
      assertEquals(nodeRow(2), "-", s"node row has no batch short name — placeholder, not invented:\n$out")
      // 无 Mail 面的 kind（Team）与无 live actor 的 orphan：两列都是占位
      val teamRow = rowFor(out, teamSid)
      assertEquals(teamRow(1), "-", s"Team row has no Mail face:\n$out")
      assertEquals(teamRow(2), "-", s"Team row has no batch short name:\n$out")
      val orphanRow = rowFor(out, orphanSid)
      assertEquals(orphanRow(1), "-", s"orphan row is not addressable:\n$out")
      assertEquals(orphanRow(2), "-", s"orphan row has no batch short name:\n$out")
      // ④ 地址形态可被 Mail 的同一个分层面闸解析（= 可原样粘进 Mail）
      val expectedForms = List(
        (delRow(1), "delegate"),
        (kerRow(1), "kernel"),
        (nodeRow(1), "node")
      )
      expectedForms.foreach { case (addr, form) =>
        assertEquals(MailTool.layeredAddressFormOf(addr), Some(form), s"$addr must be a valid Mail address")
        // 派生/解析互逆：解析回来还是同一个字符串（读面给出的就是可投递令牌）
        assertEquals(MailAddress.parse(addr).map(_.wire), Some(addr))
      }
      // 取样钩子（批报告/评审要看的真实输出）：AGENTCONTROL_SPEC_DUMP=1 时把表格
      // 打到 stdout（夹具 id 全部是合成值，天然脱敏）。
      if sys.env.get("AGENTCONTROL_SPEC_DUMP").contains("1") then println(lines.mkString("\n"))
    program.guarantee(system.stopAll.attempt.void).guarantee(IO(nebflow.shared.PathUtil.setDataRoot(prevRoot)))
  }

  // ── status：同两字段，值与 list 逐字一致 ──────────────────────

  test("status returns the same name + address for each kind") {
    val system = ActorSystem("ac-name-address-status")
    val tmp = os.temp.dir()
    val prevRoot = nebflow.shared.PathUtil.dataRoot
    nebflow.shared.PathUtil.setDataRoot(tmp / "data")
    val parentSid = "root-na2"
    val delegateSid = "delegate-Worker-bbbb2222"
    val kernelSid = "delegate-kernel-9f9e9d9c"
    val nodeSid = "node-820"
    val program = for
      resources <- mkResources(system, tmp)
      _ <- IO.blocking {
        DelegateRegistry.put(delegateRecord(delegateSid, parentSid, "beta"))
        DelegateRegistry.put(delegateRecord(kernelSid, parentSid, "gamma"))
      }
      delRef <- system.spawn(mkRecordingCmd, "na2-del")
      kerRef <- system.spawn(mkRecordingCmd, "na2-ker")
      nodeRef <- system.spawn(mkRecordingCmd, "na2-node")
      _ <- resources.agentRegistry.set(
        Map(
          delegateSid -> AgentRecord(delegateSid, delRef, AgentKind.Delegate, parentSid),
          kernelSid -> AgentRecord(kernelSid, kerRef, AgentKind.Delegate, parentSid),
          nodeSid -> AgentRecord(
            nodeSid,
            nodeRef,
            AgentKind.Flow,
            parentSid,
            displayName = Some("worker-2")
          )
        )
      )
      delOut <- IO.fromEither(
        callAction(resources, parentSid, "status", delegateSid).left.map(e => new AssertionError(e.message))
      )
      kerOut <- IO.fromEither(
        callAction(resources, parentSid, "status", kernelSid).left.map(e => new AssertionError(e.message))
      )
      nodeOut <- IO.fromEither(
        callAction(resources, parentSid, "status", nodeSid).left.map(e => new AssertionError(e.message))
      )
    yield
      assert(delOut.contains("name: beta"), s"delegate status name line:\n$delOut")
      assert(delOut.contains(s"address: delegate:$delegateSid"), s"delegate status address line:\n$delOut")
      assert(kerOut.contains("name: gamma"), s"kernel status name line:\n$kerOut")
      assert(kerOut.contains(s"address: kernel:$kernelSid"), s"kernel status address line:\n$kerOut")
      assert(nodeOut.contains("name: -"), s"node status has no batch name (placeholder):\n$nodeOut")
      assert(nodeOut.contains("address: node:worker-2"), s"node status address line:\n$nodeOut")
      // name/address 是详情卡的**前两行**（粘地址不用翻到卡尾）
      assertEquals(
        nodeOut.linesIterator.toList.take(3).exists(_.startsWith("address: ")),
        true,
        nodeOut
      )
      // 同一地址既能过 Mail 的闸，也与派生单点互逆
      val addr = s"kernel:$kernelSid"
      assertEquals(MailTool.layeredAddressFormOf(addr), Some("kernel"))
      assertEquals(MailAddress.parse(addr).map(_.wire), Some(addr))
      if sys.env.get("AGENTCONTROL_SPEC_DUMP").contains("1") then
        println("--- status(delegate) ---\n" + delOut)
        println("--- status(node) ---\n" + nodeOut)
    program.guarantee(system.stopAll.attempt.void).guarantee(IO(nebflow.shared.PathUtil.setDataRoot(prevRoot)))
  }

  // ── 与面板同源：快照行携带同一个地址（chip 的唯一数据源）────────

  test("activeAgents snapshot row carries the same mailAddress (panel chip data source)") {
    val sid = "delegate-Explorer-aaaa1111"
    val rec = AgentRecord(
      sid,
      null.asInstanceOf[nebflow.actor.ActorRef[AgentCommand]],
      AgentKind.Delegate,
      "root-na3"
    )
    val expected = MailAddress.of(rec.kind, rec.sessionId, rec.displayName).get
    val json =
      nebflow.gateway.WebSocketRoutes.activeAgentEntryJson(rec, None, mailAddress = Some(expected))
    assertEquals(json.hcursor.downField("mailAddress").as[String].toOption, Some(expected))
    assertEquals(expected, s"delegate:$sid")
    // 缺省（旧调用方）⇒ 空串（falsy 契约：前端零 chip，不抛）
    val legacy = nebflow.gateway.WebSocketRoutes.activeAgentEntryJson(rec, None)
    assertEquals(legacy.hcursor.downField("mailAddress").as[String].toOption, Some(""))
  }
