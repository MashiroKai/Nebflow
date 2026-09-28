package nebflow.core.project

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import munit.FunSuite
import nebflow.actor.{ActorSystem, Behaviors}
import nebflow.agent.*
import nebflow.core.PathUtil
import nebflow.core.compact.HistoryArchiver
import nebflow.core.FileChangeTracker
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor, ThinkingConfig}

import scala.concurrent.duration.*

/**
 * 缺口1（2026-09-04 投递可靠性批次）：会话死亡 → 节点终态化联动。
 *
 * 假尸根因（09-04 晨立卡实证②：09-03 夜 02:19 两个 node 会话静默死亡，节点滞留
 * running）：NodeEngine.runWithAgent 的 bridge 只等 AgentEvent.{Completed,Failed,
 * Cancelled} 三终态事件——会话无终态事件死亡（processing.onError 静默回 idle 后被
 * Stop、裸崩溃退出 loop、外部 fiber cancel——AgentControl 无记录、主日志零留痕的
 * 形态）时 deferred 永不完成，engine fiber 永久挂在 IO.race 上 → 节点滞留 running。
 *
 * 修复：bridge ctx.watch(agentRef)（EphemeralAgentRunner 先例同款）——Terminated
 * 兜底完成 deferred（failed 语义）→ failNode 既有链终态化 + WARN
 * （sessionId+nodeId+原因）+ deliverFailed。取消路径（NodeCancel→cancelSig、
 * AgentControl/TaskStuckWatcher giveUp→AgentEvent.Cancelled）不经此路径，语义不变。
 *
 * 用例：
 *  - D1 GREEN 会话静默死亡（fiber cancel，无终态事件）→ 节点 failed + 结果含
 *    sessionId+原因 + failed 投递达根会话 + registry 清理
 *  - D2 对照：NodeCancel 正常取消路径仍走 cancelled（语义零改动）
 *  - 变异红基线：去掉 bridge watch/onSignal（Terminated 无人处理）→ D1 超时红
 *
 * teardown 竞态治本（2026-09-10，D2 间歇红）：
 *   现象 = D2 断言全过，但 fixture 的 `finally os.remove.all(tmp)` 偶发
 *   `DirectoryNotEmptyException`（base 0/18 vs 分支 2/14，Fisher≈0.16）。
 *   机理 = 终态化只先写 status；真正的 cancel/fail 尾链在引擎 fiber 里继续跑：
 *   `emitUpdated` / `FlowMapEventLog.append` / `cleanupPendingAsks` /
 *   `checkBarriersNow` / `dispatchNotify.notifyTerminal → markSent → store.mutate`
 *   （重写 flow-map.json + results/<id>.md）——teardown 的删除与这些磁盘写竞态。
 *   治本 = ① teardown 前**确定性同步**等待尾链收敛（[[awaitTerminalTailDrained]]，
 *   锚可观测状态不猜时长）；② system.stopAll 之后**有限次**重试删除
 *   （[[removeTempBounded]]，只吞 DirectoryNotEmptyException）。
 */
class NodeSessionDeathFinalizeSpec extends FunSuite:

  private val originalRoot = PathUtil.dataRoot

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)
    nebflow.core.LlmLogWriter.setEnabled(true)

  nebflow.core.LlmLogWriter.setEnabled(false)

  /** 挂死 LLM：turn 永不完成（模拟会话运行中悬挂——死亡发生在进程存活期间）。 */
  private def hangingLlm = new nebflow.shared.LlmHandle[IO]:
    def send(req: nebflow.shared.LlmRequest): IO[nebflow.shared.LlmResponse] =
      IO.raiseError(new RuntimeException("send not expected"))
    def sendStream(req: nebflow.shared.LlmRequest, onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None) =
      fs2.Stream.eval(IO.never)

  private def withFixture(name: String, notifySeam: Option[Ref[IO, List[String]]] = None)(body: (FlowMapStore, NodeEngine, SharedResources, ActorSystem, Ref[IO, List[AgentCommand]], String) => Unit): Unit =
    val tmp = os.temp.dir(prefix = s"nodedeath-$name")
    PathUtil.setDataRoot(tmp / "data")
    // EntityLoader.agentsDir = PathUtil.dataRoot/agents —— agent 库种在 dataRoot 下
    os.makeDir.all(tmp / "data" / "agents" / "test-agent")
    os.write.over(tmp / "data" / "agents" / "test-agent" / "agent.json",
      """{"name":"test-agent","description":"session-death spec agent","tools":[],"category":"standalone"}""")
    os.write.over(tmp / "data" / "agents" / "test-agent" / "system.md", "# test-agent\n")
    val system = ActorSystem(s"nodedeath-$name")
    try
      // workspace 是 project store 的根（`FlowMapStore.open` → os.Path(workspace,
      // dataRoot)/.nebflow/…）——修既有写法 `workspace <- IO(os.makeDir.all(tmp/"ws"))`
      // 返回 Unit 导致 `FlowMapStore.open("ddproj", "()")`（store 落到
      // `<dataRoot>/()`、且 `()/` 目录被 os 语义当相对名）。改为真实路径：store 与
      // 事件审计日志都落在本 fixture 自己的 tmp/ws 下（断言不读 store 路径，
      // 零断言影响；同时消除跨 spec 共享 `<dataRoot>/()` 目录的串扰面）。
      val ws = tmp / "ws"
      val io = for
        _ <- IO(os.makeDir.all(ws))
        store <- FlowMapStore.open("ddproj", ws.toString)
        dispatcher <- cats.effect.std.Dispatcher.parallel[IO].allocated.map(_._1)
        rateLimiter <- RateLimiter.create()
        tracker <- FileChangeTracker.create(os.pwd.toString)
        fileLocks <- FileLockManager.create
        thinkingRef <- Ref.of[IO, ThinkingConfig](ThinkingConfig())
        modelOverrides <- Ref.of[IO, Map[String, ModelCandidate]](Map.empty)
        voiceMuted <- Ref.of[IO, Boolean](false)
        resources = SharedResources(
          llm = hangingLlm,
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
        recorded <- Ref.of[IO, List[AgentCommand]](Nil)
        rootSid = "dd-root-sid"
        rootRef <- system.spawn(recorderBehavior(recorded), "dd-root-recorder")
        engine = new NodeEngine(
          store,
          system,
          resources,
          _ => IO.unit,
          ws.toString,
          rootSid,
          "ddproj",
          FeedbackRouter.ModeAuto,
          (_, _, _) => IO.unit,
          notifyTriggerOverride = notifySeam.map(ref => (text: String) => ref.update(_ :+ text))
        )
      yield (store, engine, resources, recorded, rootSid, rootRef)
      val (store, engine, resources, recorded, rootSid, rootRef) = io.unsafeRunSync()
      // 根会话注册（failed 投递的断言终点）
      resources.agentRegistry
        .update(_ + (rootSid -> AgentRecord(rootSid, rootRef, AgentKind.Root, rootSid)))
        .unsafeRunSync()
      body(store, engine, resources, system, recorded, rootSid)
      // 确定性尾链收敛（teardown 竞态治本①）——必须在 finally 清目录之前
      awaitTerminalTailDrained(store, ws).unsafeRunSync()
    finally
      PathUtil.setDataRoot(originalRoot)
      system.stopAll.attempt.void.unsafeRunSync()
      // 兜底（治本②）：仅在 stopAll 之后清理，且只对 DirectoryNotEmptyException
      // 做有限次（3 次 / 200ms 退避）重试——防「尾链最后一步（延迟入队的磁盘写）
      // 仍在飞」的残余窗口。
      removeTempBounded(tmp)

  /** teardown 竞态治本①：确定性尾链收敛等待（**锚可观测状态，不猜时长**）。
    *
    * 收敛判据 = **磁盘级**（不是内存快照）：`<ws>/.nebflow/flow-map.json` 里该终态节点
    * `notifySentAt` 已落盘。
    *
    *   ① 为什么是 `notifySentAt`：cancelNode 尾链顺序 =
    *      `emitUpdated` → `FlowMapEventLog.append(cancelled)` → `cleanupPendingAsks`
    *      → `checkBarriersNow` → `notifyTerminal(Cancelled)` → `markSent`
    *      → `store.mutate`（重写 flow-map.json + results/<id>.md）；failNode 尾链 =
    *      `store.mutate(failed)` → `emitWithChain` → `deliverFailed`
    *      （→ `retryOrNotify` → `notifyTerminal(Failed)` → `markSent` → `store.mutate`）
    *      → `checkBarriersNow`。两条链的 **markSent** 都是最后一步 store 写；其后仅剩
    *      「可能写事件日志的 checkBarriersNow」，而本 fixture 两个节点 `out` 均为
    *      Nebula（无下游 barrier）⇒ checkBarriersNow 零写。
    *   ② 为什么读磁盘而不是读内存快照：`mutate` = **Ref 更新 → persistState 落盘**，
    *      内存里可见标记时磁盘写**可能仍在飞**（实测：紧轮询抓「status 可见」时刻，
    *      8/8 次内存 notifySentAt 尚未落库——即本用例 teardown 竞态的窗口本体）。
    *      磁盘侧 `AtomicJson.writeSync` 为原子写 ⇒ 并发读到「旧内容」或「新内容」，
    *      永不半截；解析失败/键缺失 = 正在被重写 ⇒ 视为未收敛并重试。
    *   ③ 覆盖不到的形态（通知被窗口抑制 ⇒ 永不 markSent）：10s 上限 + 打印诊断行后
    *      继续，删除由 [[removeTempBounded]] 的有限次重试兜底（不静默、不把 fixture
    *      卫生伪装成断言失败）。
    *
    * **不 sleep 猜时长**：等待条件是上述可观测磁盘状态，不是时间。 */
  private def awaitTerminalTailDrained(store: FlowMapStore, ws: os.Path): IO[Unit] =
    val stateFile = ws / ".nebflow" / "flow-map.json"
    val startedAt = System.currentTimeMillis()
    def terminalIds: IO[List[String]] =
      store.snapshot.map(_.nodes.values.filter(n => NodeLifecycle.Terminal.contains(n.status)).map(_.id).toList)
    def diskHasNotifyStamp(id: String): Boolean =
      try
        io.circe.parser.parse(os.read(stateFile)).toOption
          .flatMap(_.hcursor.downField("nodes").downField(id).get[Option[Long]]("notifySentAt").toOption.flatten)
          .exists(_ > 0L)
      catch case _: Throwable => false // 正在被原子重写/文件未建 → 未收敛，重试
    def converged: IO[Boolean] = terminalIds.map(_.forall(diskHasNotifyStamp))
    def go(deadline: Long, waited: Boolean): IO[Unit] =
      converged.flatMap {
        case true =>
          if waited then
            IO.println(s"[spec] awaitTerminalTailDrained: 尾链磁盘写在 status 可见后 " +
              s"+${System.currentTimeMillis() - startedAt}ms 才收敛（本等待即为覆盖该窗口）")
          else IO.unit
        case false if System.currentTimeMillis() >= deadline =>
          IO.println("[spec] awaitTerminalTailDrained: 10s 内未见尾链磁盘收敛信号 —— " +
            "交由 removeTempBounded 的有限次重试兜底（teardown 卫生，非断言）")
        case false => IO.sleep(50.millis) >> go(deadline, waited = true)
      }
    go(startedAt + 10_000L, waited = false)

  /** teardown 竞态治本②：有限次删除重试——仅对 `DirectoryNotEmptyException`
    * 重试（真实错误照旧上抛，不掩盖）。 */
  private def removeTempBounded(tmp: os.Path, attempts: Int = 3): Unit =
    def go(n: Int): Unit =
      try os.remove.all(tmp)
      catch
        case e: java.nio.file.DirectoryNotEmptyException if n > 1 =>
          println(s"[spec] remove-all retry ($n left) after DirectoryNotEmptyException: ${e.getMessage}")
          Thread.sleep(200L)
          go(n - 1)
    go(attempts)

  private def recorderBehavior(recorded: Ref[IO, List[AgentCommand]]): nebflow.actor.Behavior[AgentCommand] =
    lazy val b: nebflow.actor.Behavior[AgentCommand] =
      Behaviors.receiveMessage[AgentCommand](msg => recorded.update(_ :+ msg).as(b))
    b

  private def seedRunningCandidate(store: FlowMapStore, id: String, name: String, task: String): IO[Unit] =
    store.mutate(s => s.copy(nodes = s.nodes + (id -> NodeDef(
      id = id, name = name, agent = "test-agent", task = Some(task),
      out = List(OutEdge.nebula), status = NodeLifecycle.Wiring, createdAt = System.currentTimeMillis())))).void

  private def waitUntil(timeout: FiniteDuration, every: FiniteDuration = 50.millis)(cond: IO[Boolean]): IO[Unit] =
    def go(deadline: Long): IO[Unit] = cond.flatMap {
      case true => IO.unit
      case _ if System.currentTimeMillis() >= deadline =>
        IO.raiseError(new AssertionError("waitUntil: condition not met in time"))
      case false => IO.sleep(every) >> go(deadline)
    }
    go(System.currentTimeMillis() + timeout.toMillis)

  private def nodeSession(resources: SharedResources): IO[Option[AgentRecord]] =
    resources.agentRegistry.get.map(_.values.find(r => r.kind == AgentKind.Flow && r.sessionId.startsWith("node-")))

  test("D1 GREEN: silent session death (no terminal event) finalizes node as failed with WARN trail") {
    withFixture("d1") { (store, engine, resources, system, recorded, rootSid) =>
      val io = for
        _ <- seedRunningCandidate(store, "n-dead", "node-doomed", "long running task that will never finish")
        _ <- engine.startNode("n-dead").start
        // 等节点翻 running（spawn 完成、race 挂起——假尸窗口形成）
        _ <- waitUntil(30.seconds)(store.getNode("n-dead").map(_.exists(_.status == NodeLifecycle.Running)))
        // 等会话在 agentRegistry 登记（Running 翻转先于 registry 登记——竞态防）
        _ <- waitUntil(30.seconds)(nodeSession(resources).map(_.isDefined))
        rec0 <- nodeSession(resources)
        _ <- IO.println(s"[spec] stopping node session: ${rec0.map(_.sessionId)}")
        // 静默死亡：外部 cancel 会话 fiber（无任何终态事件——AgentControl 无记录、
        // 零日志留痕的 02:19 形态；ActorSystem.stop = fiber cancel → loop guarantee
        // → death watch Terminated → bridge 兜底）
        _ <- rec0.traverse_(rec => system.stop(rec.ref))
        // 修复生效点：Terminated → failNode（节点不滞留 running）
        _ <- waitUntil(30.seconds)(store.getNode("n-dead").map(_.exists(_.status == NodeLifecycle.Failed)))
        // 确定性同步：Failed 状态翻转先于根会话 mailbox 消费投递——等投递实际到达
        // recorded（等副作用本身，而非等前置 Failed 状态后立即读）
        _ <- waitUntil(30.seconds)(recorded.get.map(_.collectFirst {
          case m: AgentCommand.ImmediateInput if m.text.contains("[Node 'node-doomed' failed]") => m
        }.isDefined))
        node <- store.getNode("n-dead")
        msgs <- recorded.get
        reg <- nodeSession(resources)
      yield (node, msgs, reg)
      val (node, msgs, reg) = io.unsafeRunSync()
      val n = node.getOrElse(fail("node must exist"))
      assertEquals(clue(n.status), NodeLifecycle.Failed, "dead session must NOT leave node running")
      val result = n.result.getOrElse("")
      assert(clue(result).contains("terminated without a terminal event"), s"failure reason must be recorded: $result")
      assert(clue(result).contains("node-"), s"sessionId must ride the failure reason: $result")
      assert(clue(reg).isEmpty, "agent registry entry must be cleaned up")
      val imms = msgs.collect { case m: AgentCommand.ImmediateInput => m }
      assert(clue(imms.nonEmpty), "failed delivery must reach the root session (deliverFailed)")
      assert(clue(imms.head.text).contains("[Node 'node-doomed' failed]"), s"failed envelope: ${imms.head.text}")
      assert(clue(imms.head.eventType).contains("failed"), "failed status rides the bubble header")
    }
  }

  test("D2 control: NodeCancel cancel path still finalizes as cancelled (semantics unchanged)") {
    withFixture("d2") { (store, engine, resources, system, recorded, rootSid) =>
      val io = for
        _ <- seedRunningCandidate(store, "n-cancel", "node-cancellable", "long running task")
        _ <- engine.startNode("n-cancel").start
        _ <- waitUntil(15.seconds)(store.getNode("n-cancel").map(_.exists(_.status == NodeLifecycle.Running)))
        _ <- engine.cancelNodeById("n-cancel")
        _ <- waitUntil(15.seconds)(store.getNode("n-cancel").map(_.exists(_.status == NodeLifecycle.Cancelled)))
        node <- store.getNode("n-cancel")
      yield node
      val node = io.unsafeRunSync()
      assertEquals(clue(node.map(_.status)), Some(NodeLifecycle.Cancelled), "cancel semantics untouched by gap-1 fix")
    }
  }

  test("R5-方案4 INTEGRATION (real engine + real bridge): the L3 bridge cancel (deferDetach + deferNotify) holds the node with ZERO outward flow; the resume leg fails with 'no readable transcript' and the re-judge lands as failed with exactly ONE failed notification") {
    val triggered = Ref.unsafe[IO, List[String]](Nil)
    withFixture("r5-l3", notifySeam = Some(triggered)) { (store, engine, resources, system, recorded, rootSid) =>
      val io = for
        // 上游节点（将被 L3 取消）+ 下游 Pending（D5 停等观察面）
        _ <- store.mutate(s => s.copy(nodes = s.nodes + ("n-l3" -> NodeDef(
          id = "n-l3", name = "node-l3", agent = "test-agent", task = Some("long running task that will never finish"),
          out = List(OutEdge("n-l3d")), status = NodeLifecycle.Wiring,
          createdAt = System.currentTimeMillis())))).void
        _ <- store.mutate(s => s.copy(nodes = s.nodes + ("n-l3d" -> NodeDef(
          id = "n-l3d", name = "node-l3-downstream", agent = "test-agent", task = Some("downstream"),
          in = List("n-l3"), out = List(OutEdge.nebula), status = NodeLifecycle.Pending,
          createdAt = System.currentTimeMillis())))).void
        _ <- engine.startNode("n-l3").start
        _ <- waitUntil(30.seconds)(store.getNode("n-l3").map(_.exists(_.status == NodeLifecycle.Running)))
        _ <- waitUntil(30.seconds)(nodeSession(resources).map(_.isDefined))
        rec0 <- nodeSession(resources)
        rec = rec0.getOrElse(fail("node session must exist"))
        sup = rec.supervisorRef.getOrElse(fail("bridge supervisorRef must be wired"))
        // 生产 L3 文本（TaskStuckWatcher.bridgeCancelled 逐字同源：含 "(L3 hard-recovery:"）
        l3Reason =
          "stuck for 1500s (L3 hard-recovery: true resume from transcript breakpoint) — released by TaskStuckWatcher"
        _ <- (sup ! AgentEvent.Cancelled(rec.sessionId, l3Reason)).void
        // 确定性等待中间态的**两个写点**都可见（不猜时长）：status=Cancelled 是桥的终态写点，
        // 而回流占位（holdTerminalNotify）在取消尾链**末尾**（emit → 审计事件 → cleanupPendingAsks
        // → checkBarriersNow → 占位）——只等 status 会采样到「尾链未走完 ⇒ 无占位」的假象
        // （实测：status 可见后 ~200ms 才见 barrier-blocked 与占位，见 impl-r3/23_r3_ddspec_postfix.log）。
        _ <- waitUntil(30.seconds)(store.getNode("n-l3").map(_.exists(n =>
          n.status == NodeLifecycle.Cancelled && n.notifySentAt.isDefined)))
        held <- store.getNode("n-l3").map(_.get)
        fires0 <- triggered.get
        // resume 腿：本 fixture 的挂死会话无 transcript ⇒ 引擎侧 "no readable transcript" ⇒ None。
        // ⚠ **有界化**（防「一条用例挂死整轮」，见成功腿用例的挂死机制 + 证据
        //   impl-r3/22_threaddump_*.txt）：`hardResumeNode` 尾部 `startNode` 的语义是
        //   「同步等到被启动节点整个会话终态」（NodeEngine.forkStart 头注），而本 fixture 的
        //   stub LLM 流是 `IO.never` ⇒ 一旦 resume 真成功，那个续跑 turn 永不结束、无界 await
        //   永久挂死。加 30s 上界：**断言语义不变**（仍要求返回 None），仅把无界等待换成
        //   「超时即红」——超时（raced 为 Right）不得被当作通过（见 returnedInTime 断言）。
        raced <- IO.race(engine.hardResumeNode(rec.sessionId), IO.sleep(30.seconds))
        returnedInTime = raced.isLeft
        resumed = raced.fold(identity, _ => None) // Left = 引擎返回值；Right = 30s 超时 ⇒ None 使断言红
        // 改判腿（引擎侧唯一收口）
        _ <- engine.settleFailedHardResume(rec.sessionId)
        _ <- waitUntil(30.seconds)(store.getNode("n-l3").map(_.exists(_.status == NodeLifecycle.Failed)))
        judged <- store.getNode("n-l3").map(_.get)
        down <- store.getNode("n-l3d").map(_.get)
        fires1 <- triggered.get
      yield (held, fires0, returnedInTime, resumed, judged, down, fires1, rec.sessionId)
      val (held, fires0, returnedInTime, resumed, judged, down, fires1, sid) = io.unsafeRunSync()
      // ① L3 中间态：Cancelled + 回流占位（deferNotify）——**对外零回流**（既不 cancelled 也不 failed）
      assertEquals(clue(held.status), NodeLifecycle.Cancelled, "the L3 bridge cancel must land as cancelled")
      assert(clue(held.result).exists(_.contains("source=engine")), s"R2: the cancel reason must be recorded: ${held.result}")
      assert(clue(held.notifySentAt).isDefined, "the L3 intermediate state must hold the notify marker (deferred flow-back)")
      assertEquals(clue(fires0), Nil, "the L3 intermediate Cancelled state must produce ZERO outward flow")
      // ② resume 失败形态 = no readable transcript（与隔离实例 e2e 同一条出口）
      assert(clue(returnedInTime),
        "hardResumeNode must RETURN within the 30s bound — a timeout means the resume leg hangs (unbounded await on the resumed turn)")
      assertEquals(clue(resumed), None, "resume must fail (the hanging session left no readable transcript)")
      // ③ 改判 failed：状态 / 原因 / 边保留 / D5 停等 / 恰好一条 failed 回流
      assertEquals(clue(judged.status), NodeLifecycle.Failed, "the L3 resume failure must re-judge the node as failed")
      assert(clue(judged.result).exists(_.contains("L3 hard-recovery resume failed")), s"reason: ${judged.result}")
      assert(clue(judged.result).exists(_.contains(sid)), "the sessionId must ride the failure reason")
      assertEquals(clue(judged.out), List(OutEdge("n-l3d")), "failed side must NOT detach the out edge")
      assert(clue(down.in).contains("n-l3"), s"failed side must NOT prune the downstream in mirror: ${down.in}")
      assertEquals(clue(down.pendingSuccession), Nil, "failed side must NOT write the 待承接 marker")
      assertEquals(clue(fires1).size, 1, s"exactly one dispatcher notification expected, got ${fires1.size}")
      assert(clue(fires1.head).contains("reason=failed"), s"failed wording expected: ${fires1.head.take(200)}")
      assert(clue(fires1.head).contains("L3 hard-recovery resume failed"), "the notification must carry the L3 reason")
      assert(!clue(fires1.head).contains("已被**取消**"), "the cancelled notification variant must NEVER go out on the L3 leg")
    }
  }

  test("R-1=B 成功腿（2026-09-11 P2 口径更新）: 挂起腿的中断**不终态化、不占回流位**；CAS resume 成功后节点复活且 notifySentAt 仍为空（其真实终态仍恰好一次回流）") {
    val triggered = Ref.unsafe[IO, List[String]](Nil)
    withFixture("r5-l3sus", notifySeam = Some(triggered)) { (store, engine, resources, system, recorded, rootSid) =>
      val io = for
        _ <- seedRunningCandidate(store, "n-l3sus", "node-l3sus", "long running task that will never finish")
        _ <- engine.startNode("n-l3sus").start
        _ <- waitUntil(30.seconds)(store.getNode("n-l3sus").map(_.exists(_.status == NodeLifecycle.Running)))
        _ <- waitUntil(30.seconds)(nodeSession(resources).map(_.isDefined))
        rec0 <- nodeSession(resources)
        rec = rec0.getOrElse(fail("node session must exist"))
        sup = rec.supervisorRef.getOrElse(fail("bridge supervisorRef must be wired"))
        // A1 恢复锚先就位（生产链里 transcript 在判 stuck 之前已由 2s 去抖写出）
        _ <- resources.sessionStore.saveMessagesForSession(rec.sessionId,
          List(nebflow.shared.Message(nebflow.shared.MessageRole.User, Left("resume from breakpoint"))))
        // 挂起腿 = **生产形态**（`NodeEngine.suspendNode` 逐字同源）：带 `(node-suspend` 哨兵
        // 的 `AgentEvent.Cancelled`。⚠ 旧形态（`(L3 hard-recovery:` 文本）已随 R-1=B 退役——
        // 恢复动作改在终态**之前**，watcher 不再产生「先 cancelled 再救援」的中间态；
        // `NodeEngine:1703` 的 `deferDetach/deferNotify` 分支因此无生产调用方（失败腿用例
        // 仍以旧形态覆盖该分支的占位归还，见上一个用例）。
        _ <- (sup ! AgentEvent.Cancelled(rec.sessionId,
          s"${NodeEngine.SuspendReasonPrefix}): stuck 1500s (agent-stale)")).void
        // 确定性等挂起腿的**可观测写点**：三表清理 = 会话从 agentRegistry 摘除（不猜时长）
        _ <- waitUntil(30.seconds)(nodeSession(resources).map(_.isEmpty))
        suspended <- store.getNode("n-l3sus").map(_.get)
        fires0 <- triggered.get
        anchor <- engine.probeRecoveryAnchors(rec.sessionId)
        // resume 腿：fork（**不得 await** —— `hardResumeNode` 尾部 `startNode` 同步等到被启动
        // 节点**整个会话终态**（NodeEngine.forkStart 头注），本 fixture 的 stub LLM 流是
        // `IO.never` ⇒ 续跑 turn 永不结束 ⇒ await 会挂死整轮，见 impl-r3/22_threaddump_*.txt）。
        // 生产侧不受影响：`TaskStuckWatcher.runRecoveryLeg` 整体 `.start`（扫描循环不等）。
        _ <- engine.hardResumeNode(rec.sessionId, Some(anchor)).start
        // 可观测写点 = CAS（Running→Pending）之后的 startNode 重开会话 ⇒ registry 重新登记
        _ <- waitUntil(30.seconds)(nodeSession(resources).map(_.isDefined))
        revived <- store.getNode("n-l3sus").map(_.get)
        fires <- triggered.get
      yield (suspended, fires0, revived, fires)
      val (suspended, fires0, revived, fires) = io.unsafeRunSync()
      // ① 挂起腿：**不终态化**（节点留 Running）+ **不占回流位**（notifySentAt 为空）
      assertEquals(clue(suspended.status), NodeLifecycle.Running,
        "R-1=B: the suspend leg must NOT finalize the node — it never enters Cancelled on this path")
      assertEquals(clue(suspended.notifySentAt), None,
        "the suspend leg places NO notify hold — R-1=B removed the Cancelled intermediate state, so there is no marker to hand back")
      assertEquals(clue(fires0), Nil, "the suspend leg must emit ZERO outward flow")
      // ② resume 成功（新会话重新登记 = CAS 之后的 startNode 已跑）：节点复活且**仍未被标记为已通知**
      assert(clue(revived.status) == NodeLifecycle.Pending || clue(revived.status) == NodeLifecycle.Running,
        s"the node must be revived into a live state (CAS writes Pending; the resumed run flips it to Running), got ${clue(revived.status)}")
      assertEquals(clue(revived.notifySentAt), None,
        "the revived node must NOT be left flagged as already-notified — otherwise its real terminal state would be silenced by dedup")
      assertEquals(clue(fires), Nil, "neither leg may emit an outward flow before the node's real terminal state")
    }
  }

end NodeSessionDeathFinalizeSpec
