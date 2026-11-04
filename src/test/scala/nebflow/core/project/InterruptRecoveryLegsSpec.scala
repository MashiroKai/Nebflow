/* 中断恢复验收台架（9.19 版中断恢复批 · 2026-11-04）。
 *
 * 用途：在**未修复代码**上为 V1–V5 五条验收腿跑出红证；同一份断言在修复后应转绿，
 * 判词节点可独立复跑（每腿一条命令，见 reports-evidence/20261104_interrupt-recovery/README.md）。
 *
 * 台架选型（为什么用 spec 缝而不是真 JVM 隔离实例）：
 *  - 断言必须**确定性**成立。真实例端到端要跨进程 kill/重启 + 真实 LLM 回合，
 *    「会话是否到达 Processing」受网络/模型/启动竞态影响，无法稳定复现红绿。
 *  - spec 缝驱动的是**生产同一函数**：`SubAgentStartupRecovery.sweep` 就是 boot 链上
 *    唯一的中断恢复入口（GatewayMain.scala:834-856 只负责把 RevivalDeps 装配进去），
 *    spawn/恢复/落盘全是真实现，只有 LLM 换成 stub（便于断言「下一 turn 已启动」）。
 *  - 因此「重启」在台架里 = 「把盘面摆成崩溃后的形态 + 跑 boot sweep」，与真实重启
 *    的可观测语义一致：sweep 是唯一决策点。
 *
 * 安全：本 spec 不启动任何引擎进程、不触碰生产实例（pid 41715）、不调用 stand stop/restart。
 */
package nebflow.core.project

import cats.effect.{IO, Ref}
import cats.effect.std.Dispatcher
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import io.circe.Json
import munit.CatsEffectSuite
import nebflow.actor.{ActorSystem, AgentCommand, AgentKind, AgentRecord, Behaviors}
import nebflow.agent.{SharedResources, SubAgentStartupRecovery, SubAgentTaskStore}
import nebflow.core.FileChangeTracker
import nebflow.core.compact.HistoryArchiver
import nebflow.core.delegate.DelegateRegistry
import nebflow.core.executor.ExecutorRegistry
import nebflow.core.mail.SessionMailbox
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.core.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor}
import nebflow.shared.{LlmHandle, LlmRequest, Message, MessageRole, PathUtil, StreamChunk, SubAgentTask, ThinkingConfig}

import scala.concurrent.duration.*

class InterruptRecoveryLegsSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 180.seconds

  /**
   * 腿筛选：`IR_LEG=V1` ⇒ 只跑 V1（其余用例 assume 跳过 = ignored，不影响退出码）；
   * 缺省（无 IR_LEG）= 五腿全跑。用环境变量而不是框架过滤参数——sbt fork 与非 fork
   * 两种形态都能读到，且每腿一条命令的退出码精确反映该腿红绿。
   */
  private def legEnabled(tag: String): Boolean =
    sys.env.get("IR_LEG").map(_.split(",").map(_.trim.toUpperCase).contains(tag.toUpperCase)).getOrElse(true)

  private val originalRoot = PathUtil.dataRoot

  nebflow.core.LlmLogWriter.setEnabled(false)

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)
    nebflow.core.LlmLogWriter.setEnabled(true)

  // ── stub LLM：记录每个请求的正文（「下一 turn 已启动」+「上下文已回放」的观测面） ──

  private final class RecordingLlm:
    val requests: Ref[IO, List[String]] = Ref.unsafe[IO, List[String]](Nil)
    val handle: LlmHandle[IO] = new LlmHandle[IO]:
      def send(req: LlmRequest): IO[nebflow.shared.LlmResponse] =
        IO.raiseError(new RuntimeException("send not expected (streaming leg only)"))
      def sendStream(
        req: LlmRequest,
        onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
      ) =
        fs2.Stream.eval(
          requests.update(_ :+ req.messages.map(m => m.textContent).mkString("\n---\n"))
        ) >> fs2.Stream(StreamChunk.TextDelta("INTERRUPT_RECOVERY_STUB_TURN_OK"), StreamChunk.Done(None, None))

  // ── 夹具（盘面 = 崩溃后的现场；真 SessionStore / 真 SubAgentTaskStore / 真 ActorSystem） ──

  private final case class Fx(
    store: SubAgentTaskStore,
    sessionStore: SessionStore,
    resources: SharedResources,
    system: ActorSystem,
    tmp: os.Path,
    seat: os.Path,
    llm: RecordingLlm
  )

  private def withFx(name: String)(body: Fx => IO[Unit]): Unit =
    val tmp = os.temp.dir(prefix = s"ir-leg-$name")
    val seat = os.temp.dir(prefix = s"ir-seat-$name")
    PathUtil.setDataRoot(tmp / "data")
    val system = ActorSystem(s"ir-leg-$name-${java.util.UUID.randomUUID().toString.take(6)}")
    val llm = new RecordingLlm
    val io =
      for
        dispatcher <- Dispatcher.parallel[IO].allocated.map(_._1)
        rateLimiter <- RateLimiter.create()
        tracker <- FileChangeTracker.create(os.pwd.toString)
        fileLocks <- FileLockManager.create
        thinkingRef <- Ref.of[IO, ThinkingConfig](ThinkingConfig())
        modelOverrides <- Ref.of[IO, Map[String, ModelCandidate]](Map.empty)
        voiceMuted <- Ref.of[IO, Boolean](false)
        sessionStore = SessionStore(tmp / "sessions", tmp / "tasks")
        store = new SubAgentTaskStore(tmp / "subagent-tasks")
        resources = SharedResources(
          llm = llm.handle,
          dispatcher = dispatcher,
          sessionStore = sessionStore,
          projectRoot = os.pwd,
          thinkingConfigRef = thinkingRef,
          rateLimiter = rateLimiter,
          fileChangeTracker = tracker,
          contextWindow = 100_000,
          agentLibrary = new nebflow.agent.AgentLibrary(tmp / "agents"),
          taskStore = FileTaskStore,
          historyArchiver = HistoryArchiver.fileSystem(tmp / "archives"),
          fileLockManager = fileLocks,
          sessionModelOverrides = modelOverrides,
          providerRegistry = null,
          healthMonitor = ProviderHealthMonitor(null),
          actorSystem = system,
          subAgentTaskStore = store,
          voiceMutedRef = voiceMuted
        )
        _ <- body(Fx(store, sessionStore, resources, system, tmp, seat, llm))
      yield ()
    try io.unsafeRunSync()
    finally
      system.stopAll.attempt.void.unsafeRunSync()
      PathUtil.setDataRoot(originalRoot)
      os.remove.all(tmp)
      os.remove.all(seat)

  private def mkTask(id: String, parent: String, status: String = "running"): SubAgentTask =
    SubAgentTask(
      taskId = id,
      parentSessionId = parent,
      agentName = "nebflow",
      prompt = "do the thing",
      description = s"ir-leg-$id",
      status = status,
      retryCount = 0,
      spawnedAt = System.currentTimeMillis() - 60_000L,
      completedAt = None,
      lastError = None,
      source = "delegate"
    )

  private def mkRecord(id: String, parent: String, cwd: String) =
    DelegateRegistry.Record(
      id = id,
      executor = ExecutorRegistry.DefaultId,
      cwd = cwd,
      project = None,
      taskId = None,
      parentSessionId = parent,
      title = s"ir leg record $id",
      createdAt = System.currentTimeMillis() - 120_000L
    )

  /** boot sweep = 重启的唯一恢复决策点（与 GatewayMain.scala:834-856 同一调用）。 */
  private def sweepWith(fx: Fx) =
    SubAgentStartupRecovery.sweep(
      fx.store,
      fx.sessionStore,
      Some(SubAgentStartupRecovery.RevivalDeps(fx.resources, fx.system, None))
    )

  private def waitUntil(timeout: FiniteDuration, what: String)(cond: IO[Boolean]): IO[Unit] =
    def go(deadline: Long): IO[Unit] =
      cond.flatMap {
        case true => IO.unit
        case false =>
          if System.currentTimeMillis() >= deadline then
            IO.raiseError(new AssertionError(s"[waitUntil] $what — condition not met within ${timeout.toSeconds}s"))
          else IO.sleep(100.millis) >> go(deadline)
      }
    go(System.currentTimeMillis() + timeout.toMillis)

  private def mailboxEvents(fx: Fx, parentId: String): IO[List[AgentCommand.ExternalEvent]] =
    for
      pending <- SessionMailbox.scanPending(parentId)
      taken <- SessionMailbox.scanTaken(parentId)
    yield (pending ++ taken).map(_.event)

  private def evidence(tag: String)(lines: String*): IO[Unit] =
    IO(println(lines.map(l => s"[LEG $tag] $l").mkString("\n")))

  // ══════════════════════════════════════════════════════════════════════════════
  // V1 — 自动复活：重启后**无需任何人工 Mail**，会话自动到达 Processing 态并继续干活
  //
  // 形态（作者 2026-11-04 11:27 裁定 + BB1 实证）：delegate 跑到一半、为自己的作用域
  // 链节点等（standby 停靠），引擎被中断重启。目标语义：sweep 自动原位复活 —— 同
  // sessionId、actor 注册、下一 turn 启动、任务行继续 running。现状：standby 闸
  // （DelegateRevival.scala:107-116）判死 ⇒ 收殓 failed + 父通知（BB1 09:09 实证）。
  // 控制组：普通在飞孤儿（无 standby）现状即可复活 —— 证明夹具/接线本身是通的，
  // 让「红」只归因于 standby 闸本身。
  // ══════════════════════════════════════════════════════════════════════════════

  test(
    "V1 自动复活（无人工 Mail）: 跑到一半（standby 等自家链节点）的 delegate 重启后必须原位复活并继续干活 —— 现状停在 failed"
  ) {
    assume(legEnabled("V1"), "IR_LEG filter: V1 not selected")
    // 控制组：普通在飞孤儿（现状已绿）——夹具自证 + 归因锚点。
    withFx("v1-control") { fx =>
      for
        parent <- fx.sessionStore.createSession("Nebula", agentName = Some("Nebula"))
        child = "delegate-nebflow-v1-control"
        _ <- fx.store.recordTask(mkTask(child, parent.id))
        _ <- IO(DelegateRegistry.put(mkRecord(child, parent.id, fx.seat.toString)))
        _ <- fx.sessionStore.saveMessagesForSession(
          child,
          List(
            Message(MessageRole.User, Left("V1_CONTROL_BRIEF")),
            Message(MessageRole.Assistant, Left("V1_CONTROL_PRECRASH_WORK done step 1"))
          )
        )
        report <- sweepWith(fx)
        _ <- evidence("V1/control")(
          s"report: ${report.summary}",
          s"revivalSkipped: ${report.revivalSkipped}"
        )
        _ = assert(
          report.revived.contains(child),
          s"CONTROL BROKEN — a plain in-flight orphan must revive today (report=${report.summary}); " +
            "if this fails the harness itself is not wired, do not read V1 as red"
        )
        row <- fx.store.loadTasks(parent.id).map(_.find(_.taskId == child).get)
        _ = assertEquals(row.status, "running", "control: revived row keeps running")
        _ <- waitUntil(20.seconds, "control: next turn started") {
          fx.llm.requests.get.map(_.nonEmpty)
        }
        seen <- fx.llm.requests.get
        _ = assert(
          seen.exists(_.contains("V1_CONTROL_PRECRASH_WORK")),
          s"control: pre-crash transcript must be replayed into the model context; saw:\n${seen.mkString("\n====")}"
        )
      yield ()
    }

    // 被测形态（BB1）：跑到一半 + 等自家链节点（standby 停靠 + 作用域图有非终态节点）。
    withFx("v1-bb1") { fx =>
      val child = "delegate-nebflow-v1-bb1"
      for
        parent <- fx.sessionStore.createSession("Nebula", agentName = Some("Nebula"))
        _ <- fx.store.recordTask(mkTask(child, parent.id))
        // BB1 现场：待命停靠（回合收尾采纳 standby）+ 座位 + 作用域图仍有在飞节点。
        _ <- IO(
          DelegateRegistry.put(
            mkRecord(child, parent.id, fx.seat.toString).copy(standbySince = Some(System.currentTimeMillis()))
          )
        )
        scopeDir = ScopeHandover.scopeDir(fx.seat.toString, child)
        _ <- IO(os.makeDir.all(scopeDir))
        _ <- IO(
          os.write(
            scopeDir / "flow-map.json",
            """{"nodes":{"n-inflight":{"id":"n-inflight","name":"n-inflight","agent":"general","status":"running"}}}"""
          )
        )
        _ <- fx.sessionStore.saveMessagesForSession(
          child,
          List(
            Message(MessageRole.User, Left("V1_BB1_BRIEF: 8-node batch, orchestrate")),
            Message(MessageRole.Assistant, Left("V1_BB1_PRECRASH_WORK spawned n-inflight, waiting on chain"))
          )
        )
        openNodes = nebflow.core.delegate.SessionResumption.openNodeCount(scopeDir)
        report <- sweepWith(fx)
        row <- fx.store.loadTasks(parent.id).map(_.find(_.taskId == child).get)
        live <- fx.resources.agentRegistry.get.map(_.contains(child))
        mailbox <- mailboxEvents(fx, parent.id)
        turns <- fx.llm.requests.get
        _ <- evidence("V1/bb1")(
          s"scope open non-terminal nodes = $openNodes (BB1 形态: 链上还有活)",
          s"report: ${report.summary}",
          s"revivalSkipped: ${report.revivalSkipped}",
          s"task row status after restart = ${row.status} (作者要的是 running/Processing)",
          s"live actor registered = $live",
          s"next turn started (LLM requests) = ${turns.size}",
          s"parent mailbox events for child = ${mailbox.count(_.correlationId.contains(child))}",
          "today: standby 闸判死 → 收殓 failed（recoverOne 写 [task lost: crash]），无活体、零 turn"
        )
        // ── 目标语义断言（无修复必红） ──
        _ = assert(
          report.revived.contains(child),
          s"RED(V1): interrupted-mid-work delegate (standby-while-chain-in-flight) must be auto-revived by the " +
            s"restart sweep — no manual Mail may be required. Observed: report=${report.summary}; " +
            s"skip=${report.revivalSkipped}; row=${row.status}; live=$live; turns=${turns.size}. " +
            s"Root cause: DelegateRevival.scala:107-116 (standby gate) + SubAgentStartupRecovery.scala:266-296 " +
            s"(ineligible ⇒ salvage chain ⇒ failed)."
        )
        _ = assertEquals(
          row.status,
          "running",
          s"RED(V1): after an automatic revival the task row must still be running (Processing), not terminalized. " +
            s"row=${row.status}, lastError=${row.lastError}"
        )
        _ = assert(
          live,
          "RED(V1): the revived session must be registered live (actor registered) — today the sweep never spawns it"
        )
        _ <- waitUntil(20.seconds, "V1: next turn started after auto-revival") {
          fx.llm.requests.get.map(_.nonEmpty)
        }
        seen <- fx.llm.requests.get
        _ = assert(
          seen.exists(_.contains("V1_BB1_PRECRASH_WORK")),
          s"RED(V1): the revived session must continue from its own transcript (context replayed); saw:\n" +
            seen.mkString("\n====")
        )
      yield ()
    }
  }

  // ══════════════════════════════════════════════════════════════════════════════
  // V2 — 墓碑：人工 cancel 过（cancelRequestedAt）的 delegate，重启后**不得**复活
  //
  // 现状：已满足（墓碑闸 DelegateRevival.scala:100-106 + SessionResumption.judge:173-177，
  // 2026-10-28 R1 缝隙 C 修）。本腿按验收要求加固：墓碑必须在**每一条同 id 复活腿**上
  // 都成立（boot sweep 腿 + 原位续命判定腿 + 二次 boot 幂等），防止修复批新增的
  // 自动复活腿绕过墓碑。
  // ══════════════════════════════════════════════════════════════════════════════

  test(
    "V2 墓碑: 人工 cancel 过的 delegate 重启后不得复活（boot sweep / 原位续命判定 / 二次 boot 三条腿都守住墓碑）"
  ) {
    assume(legEnabled("V2"), "IR_LEG filter: V2 not selected")
    withFx("v2") { fx =>
      val child = "delegate-nebflow-v2-cancelled"
      for
        parent <- fx.sessionStore.createSession("Nebula", agentName = Some("Nebula"))
        _ <- fx.store.recordTask(mkTask(child, parent.id))
        // 现场 = 实证僵尸形态（e25cd0c2/b5646a58）：取消发生在重启前、任务行仍是 running。
        _ <- IO(DelegateRegistry.put(mkRecord(child, parent.id, fx.seat.toString)))
        _ <- IO(DelegateRegistry.markCancelRequested(child, "the user panel"))
        _ <- fx.sessionStore.saveMessagesForSession(
          child,
          List(Message(MessageRole.User, Left("V2_BRIEF")), Message(MessageRole.Assistant, Left("V2_WORK before cancel")))
        )
        report1 <- sweepWith(fx)
        row <- fx.store.loadTasks(parent.id).map(_.find(_.taskId == child).get)
        live <- fx.resources.agentRegistry.get.map(_.contains(child))
        events1 <- mailboxEvents(fx, parent.id)
        turns <- fx.llm.requests.get
        // 二次 boot 幂等（不许「第一次收殓、第二次复活」这种回归）。
        report2 <- sweepWith(fx)
        live2 <- fx.resources.agentRegistry.get.map(_.contains(child))
        events2 <- mailboxEvents(fx, parent.id)
        rec <- IO(DelegateRegistry.find(child))
        // 加固腿：原位续命判定单点（Mail/面板唤醒腿的判定面）必须判 Derive，绝不同 id 复活。
        verdict = nebflow.core.delegate.SessionResumption.judge(rec.get, None, 2)
        _ <- evidence("V2")(
          s"boot#1: ${report1.summary} skip=${report1.revivalSkipped}",
          s"boot#2: ${report2.summary}",
          s"row status after boots = ${row.status}",
          s"live actor = $live / $live2 ; turns started = ${turns.size}",
          s"cancel tombstone on record = ${rec.flatMap(_.cancelRequestedAt)}",
          s"SessionResumption.judge = $verdict (must NOT be InPlace)"
        )
        _ = assert(
          !report1.revived.contains(child) && !report2.revived.contains(child),
          s"V2: a tombstoned delegate must never be revived (boot#1=${report1.revived}, boot#2=${report2.revived})"
        )
        _ = assert(
          report1.revivalSkipped.exists((id, r) => id == child && r.contains("cancel tombstone")),
          s"V2: the refusal must be attributed to the tombstone gate, not an incidental gate: ${report1.revivalSkipped}"
        )
        _ = assertEquals(row.status, "failed", "V2: salvage settles the honest terminal (never left running)")
        _ = assert(!live && !live2, "V2: no live actor may exist for a cancelled delegate")
        _ = assertEquals(turns.size, 0, "V2: no turn may be started for a cancelled delegate")
        _ = assert(
          events1.count(_.correlationId.contains(child)) == 1 &&
            events2.count(_.correlationId.contains(child)) == 1,
          s"V2: exactly one honest loss notice across two boots (idempotent): ${events1.size}/${events2.size}"
        )
        _ = assert(
          !nebflow.core.delegate.SessionResumption.Verdict.isInPlace(verdict),
          s"V2 HARDENED: the in-place resume decision point must refuse a cancelled record (got $verdict) — " +
            "any new revival leg must consult the same tombstone"
        )
      yield ()
    }
  }

  // ══════════════════════════════════════════════════════════════════════════════
  // V3 — 节点消息：`node:` 消息投递后中断/重启，消息必须仍在盘上且能被重放
  //
  // V3a（现状已绿，护栏）：已落盘的注入队列**确实**在重启后重放（AgentIdle.scala:738
  //   RecoverPersistedQueues + AgentProcessing.scala:1636-1650 接收方落盘）。
  // V3b（现状红，被测腿）：`NodeDelivery.sendNodeMessage` 对 running 节点是
  //   fire-and-forget `ref ! ImmediateInput`（NodeDelivery.scala:317），返回 Right
  //   时**盘上零字节**；进程在接收方 persistQueues 之前死，消息只剩事件流一行
  //   （NodeDelivery.scala:332-338），重启无人回放 → 永久丢件（审计 D5）。
  //   探针 actor 就是把「接收方还没落盘就被杀」这一现实窗口钉成确定性：投递成功
  //   ⇒ 消息必须已经落在可重放的持久面上（修复方向：先写 task/队列再投，或收方
  //   回执后才算投递成功）。
  // ══════════════════════════════════════════════════════════════════════════════

  test(
    "V3 节点消息: node 消息投递成功即可重启重放（已落盘的确实重放；fire-and-forget 窗口必须补持久化）"
  ) {
    assume(legEnabled("V3"), "IR_LEG filter: V3 not selected")
    // ── V3a：落盘的注入队列在重启（同 id 重spawn）后被重放 ──
    withFx("v3a") { fx =>
      val child = "delegate-nebflow-v3-persisted"
      for
        parent <- fx.sessionStore.createSession("Nebula", agentName = Some("Nebula"))
        _ <- fx.store.recordTask(mkTask(child, parent.id))
        _ <- IO(DelegateRegistry.put(mkRecord(child, parent.id, fx.seat.toString)))
        _ <- fx.sessionStore.saveMessagesForSession(
          child,
          List(Message(MessageRole.User, Left("V3_NODE_BRIEF")), Message(MessageRole.Assistant, Left("V3_PRECRASH_DONE step")))
        )
        // 崩溃前的盘面：接收方已把消息落进 injection-queues.json（AgentProcessing:1650）。
        _ <- nebflow.agent.CompactionQueueStore.save(
          child,
          nebflow.agent.CompactionQueueStore.PersistedQueues(imms =
            List(
              AgentCommand.ImmediateInput(
                text = "[NODE-MESSAGE] V3_QUEUED_INSTRUCTION please continue",
                source = Some("system")
              )
            )
          )
        )
        report <- sweepWith(fx)
        _ = assert(report.revived.contains(child), s"V3a fixture must revive: ${report.summary}")
        _ <- waitUntil(25.seconds, "V3a: persisted node message replayed into the session") {
          fx.sessionStore.loadMessagesForSession(child).map(_.exists(_.textContent.contains("V3_QUEUED_INSTRUCTION")))
        }
        transcript <- fx.sessionStore.loadMessagesForSession(child)
        _ <- evidence("V3a")(
          s"persisted queue replayed after restart = ${transcript.exists(_.textContent.contains("V3_QUEUED_INSTRUCTION"))}",
          s"pre-crash context intact = ${transcript.exists(_.textContent.contains("V3_PRECRASH_DONE"))}",
          s"transcript messages = ${transcript.size}"
        )
        _ = assert(
          transcript.exists(_.textContent.contains("V3_PRECRASH_DONE")),
          "V3a: node context must be complete after restart (pre-crash transcript intact)"
        )
      yield ()
    }

    // ── V3b：fire-and-forget 窗口 —— 投递成功但盘上零字节 ──
    withFx("v3b") { fx =>
      val sid = "node-v3b-session"
      val nodeId = "n-v3b"
      val text = "[NODE-MESSAGE] V3_INFLIGHT_INSTRUCTION do not lose me"
      for
        ws <- IO.blocking { val d = fx.tmp / "ws-v3b"; os.makeDir.all(d); d }
        store <- FlowMapStore.open("v3b", ws.toString)
        seen <- Ref.of[IO, List[String]](Nil)
        ref <- fx.system.spawn(probeBehavior(seen), "v3b-node-probe")
        _ <- fx.resources.agentRegistry.update(
          _ + (sid -> AgentRecord(sid, ref, AgentKind.Delegate, sid))
        )
        _ <- store.mutate(s =>
          s.copy(nodes = s.nodes + (nodeId -> NodeDef(
            id = nodeId,
            name = "n-v3b",
            agent = "general",
            status = NodeLifecycle.Running,
            sessionRef = Some(sid),
            createdAt = System.currentTimeMillis() - 1000L
          )))
        )
        engine = new NodeEngine(
          store,
          fx.system,
          fx.resources,
          wsSendFn = (_: Json) => IO.unit,
          workspace = ws.toString,
          rootSessionId = "nebula-root",
          projectName = "v3b",
          emitEvent = (_: String, _: String, _: Json) => IO.unit
        )
        res <- engine.sendNodeMessage(nodeId, text)
        // 投递后的持久面（重启会消费的三处：注入队列 / 转录 / 节点 task）
        queue <- nebflow.agent.CompactionQueueStore.load(sid)
        transcript <- fx.sessionStore.loadMessagesForSession(sid).attempt.map(_.getOrElse(Nil))
        node <- store.getNode(nodeId)
        probeSeen <- seen.get
        durable =
          queue.exists(q => q.imms.exists(_.text.contains("V3_INFLIGHT_INSTRUCTION"))) ||
            transcript.exists(_.textContent.contains("V3_INFLIGHT_INSTRUCTION")) ||
            node.exists(_.task.exists(_.contains("V3_INFLIGHT_INSTRUCTION")))
        eventLines <- IO
          .blocking {
            val f = ws / ".nebflow" / FlowMapEventLog.FileName
            if os.exists(f) then os.read(f).linesIterator.filter(_.contains("V3_INFLIGHT_INSTRUCTION")).toList else Nil
          }
          .handleError(_ => Nil)
        _ <- evidence("V3b")(
          (Seq(
            s"sendNodeMessage result = $res",
            s"probe actor received = ${probeSeen.size} message(s) (stands for: the receiving process died before persisting)",
            s"durable replay surfaces after a SUCCESSFUL delivery: injection-queues=${queue.exists(q => q.imms.exists(_.text.contains("V3_INFLIGHT_INSTRUCTION")))}, transcript=${transcript.exists(_.textContent.contains("V3_INFLIGHT_INSTRUCTION"))}, node.task=${node.exists(_.task.exists(_.contains("V3_INFLIGHT_INSTRUCTION")))}",
            s"flow-map-events trace lines (NOT replayed by anyone — audit D5) = ${eventLines.size}"
          ) ++ eventLines.map(l => s"  event: $l"))*
        )
        _ = assert(
          res.isRight,
          s"V3b: delivery to a live running node must report success (unexpected refusal: $res)"
        )
        _ = assert(
          durable,
          "RED(V3): a successful node-message delivery must be durable on a restart-consumed surface " +
            "(injection-queues.json / transcript / node task). Today NodeDelivery.scala:317 tells the actor and " +
            "returns Right with ZERO bytes on disk — a crash before the receiver's persistQueues " +
            "(AgentProcessing.scala:1650) loses the message forever; only the flow-map event line remains " +
            "(NodeDelivery.scala:332-338) and nothing replays it."
        )
      yield ()
    }
  }

  // ══════════════════════════════════════════════════════════════════════════════
  // V4 — 幂等：复活后已完成步骤不重复执行（已完成 turn 数/断点标记不得重置或重放）
  //
  // 现状：转录逐字节回放（DelegateRevival.scala:241 initialMessages=transcript）✔，
  // 但**没有任何断点台账**：TurnStateStore 的 save/load 检查点链在审计中被判定为
  // 假恢复链并整棵移除（TurnStateStore.scala:6-18，「save 无生产调用点」）；复活
  // 注入的 nudge 只是一句人话「continue, do not redo」（SessionResumption.scala:358-362
  // 同款），模型无法机械区分「已完成/待做」——已完成步骤可被重做（审计 D6）。
  // 本腿断言：① 转录/计数不变（护栏）② 复活必须携带机器可读的已完成步骤断点。
  // ══════════════════════════════════════════════════════════════════════════════

  test(
    "V4 幂等: 复活后已完成 turn 数不变，且必须存在机器可读的已完成步骤断点（现状无断点台账）"
  ) {
    assume(legEnabled("V4"), "IR_LEG filter: V4 not selected")
    withFx("v4") { fx =>
      val child = "delegate-nebflow-v4"
      val preCrash = List(
        Message(MessageRole.User, Left("V4_BRIEF: three steps")),
        Message(MessageRole.Assistant, Left("V4_ROUND_1_DONE wrote file A")),
        Message(MessageRole.Assistant, Left("V4_ROUND_2_DONE wrote file B")),
        Message(MessageRole.Assistant, Left("V4_ROUND_3_DONE wrote file C"))
      )
      for
        parent <- fx.sessionStore.createSession("Nebula", agentName = Some("Nebula"))
        _ <- fx.store.recordTask(mkTask(child, parent.id))
        _ <- IO(DelegateRegistry.put(mkRecord(child, parent.id, fx.seat.toString)))
        _ <- fx.sessionStore.saveMessagesForSession(child, preCrash)
        report <- sweepWith(fx)
        _ = assert(report.revived.contains(child), s"V4 fixture must revive: ${report.summary}")
        _ <- waitUntil(25.seconds, "V4: revival nudge landed") {
          fx.sessionStore.loadMessagesForSession(child).map(_.exists(_.textContent.contains("engine process restarted")))
        }
        transcript <- fx.sessionStore.loadMessagesForSession(child)
        row <- fx.store.loadTasks(parent.id).map(_.find(_.taskId == child).get)
        nudge = transcript.find(_.textContent.contains("engine process restarted")).map(_.textContent).getOrElse("<none>")
        // 断点探测：会话持久目录里是否存在列举已完成 round 的机器可读台账。
        artifacts <- checkpointArtifacts(child)
        _ <- evidence("V4")(
          s"pre-crash completed turns = 3 (V4_ROUND_1..3_DONE)",
          s"after-revival transcript contains all 3 rounds = ${List("V4_ROUND_1_DONE", "V4_ROUND_2_DONE", "V4_ROUND_3_DONE").forall(m => transcript.exists(_.textContent.contains(m)))}",
          s"task row = status=${row.status} revivalCount=${row.revivalCount}",
          s"revival nudge (the ONLY hand-off the model gets) = ${nudge.take(300)}",
          s"machine-readable checkpoint artifacts under sessions/$child/ = ${artifacts.size} ${artifacts.mkString(", ")}"
        )
        _ = assertEquals(
          List("V4_ROUND_1_DONE", "V4_ROUND_2_DONE", "V4_ROUND_3_DONE").count(m => transcript.exists(_.textContent.contains(m))),
          3,
          "V4 guard: the completed turns must survive the revival verbatim (no truncation/reset — today green)"
        )
        _ = assertEquals(row.revivalCount, Some(1), "V4 guard: revival accounting must not be double-incremented")
        _ = assert(
          artifacts.nonEmpty,
          "RED(V4): nothing on disk tells the revived session (or the engine) which steps are already done — " +
            "completed work can be redone. Today: no checkpoint ledger exists at all (TurnStateStore's " +
            "save/load chain was removed as a fake recovery chain, TurnStateStore.scala:6-18; the revival " +
            "hand-off is prose only, DelegateRevival.scala:317-328). A machine-readable completed-steps " +
            "checkpoint must exist for the session and be carried into the revival."
        )
      yield ()
    }
  }

  // ══════════════════════════════════════════════════════════════════════════════
  // V5 — 收尾：活干完、只差收尾报告的会话（cb580112 形态）重启后必须自动收尾或
  //      进入「已交付待报告」态并通知父会话 —— 绝不静静地既不活也不报
  //
  // 机械判据（盘上可求值，与 fixsession 口径）：任务行 status=completed ∧
  // registry.workReport 非空 ∧ registry.reportStatus 缺席（+ 已归档，cb580112 实录）。
  // 现状：sweep 的在飞视野只收 running/restarting/ready-to-merge/frozen
  // （SubAgentStartupRecovery.scala:313-316）⇒ 这类会话连视野都不进，重启后零动作
  // 零通知（cb580112 11:25 合并先落地 → finish 无裁定 → idle）。
  // ══════════════════════════════════════════════════════════════════════════════

  test(
    "V5 收尾: 干完只缺报告的会话（workReport 在 / reportStatus 缺）重启后必须自动收尾并通知父会话"
  ) {
    assume(legEnabled("V5"), "IR_LEG filter: V5 not selected")
    withFx("v5") { fx =>
      val child = "delegate-nebflow-v5-done"
      val marker = "V5_WORKREPORT_MARKER: branch merged, all checks green"
      for
        parent <- fx.sessionStore.createSession("Nebula", agentName = Some("Nebula"))
        _ <- fx.store.recordTask(
          mkTask(child, parent.id, status = "completed").copy(completedAt = Some(System.currentTimeMillis() - 30_000L))
        )
        _ <- IO(
          DelegateRegistry.put(
            mkRecord(child, parent.id, fx.seat.toString).copy(
              workReport = Some(marker),
              reportStatus = None,
              archivedAt = Some(System.currentTimeMillis() - 10_000L)
            )
          )
        )
        _ <- fx.sessionStore.saveMessagesForSession(
          child,
          List(Message(MessageRole.User, Left("V5_BRIEF")), Message(MessageRole.Assistant, Left(s"work finished; $marker")))
        )
        // 「重启」= boot 链上的两条 delegate 面清扫腿（GatewayMain 装配）。
        report <- sweepWith(fx)
        _ <- nebflow.agent.MailboxBootSweep.run(fx.store, fx.sessionStore).attempt
        rec <- IO(DelegateRegistry.find(child))
        events <- mailboxEvents(fx, parent.id).map(_.filter(_.correlationId.contains(child)))
        _ <- evidence("V5")(
          s"sweep report: ${report.summary}",
          s"task row status = ${fx.store.loadTasks(parent.id).unsafeRunSync().find(_.taskId == child).map(_.status)} (completed = 活已干完)",
          s"registry workReport present = ${rec.flatMap(_.workReport).isDefined}, reportStatus = ${rec.flatMap(_.reportStatus)}",
          s"parent mailbox events for child after restart = ${events.size}",
          "today: completed 行不在 sweep 视野（:313-316）⇒ 既不终态化也不通知（cb580112 形态）"
        )
        _ = assert(
          events.nonEmpty,
          "RED(V5): a delegate that finished its work but never delivered its closing report must NOT stay " +
            "silently dead across a restart — the boot legs must either auto-finalize it or put it into a " +
            "delivered-pending-report state AND notify the parent. Today the completed row is outside the " +
            "sweep's in-flight scan (SubAgentStartupRecovery.scala:313-316) and no leg reads " +
            "workReport/reportStatus, so the parent's inbox stays empty forever."
        )
        _ = assert(
          events.exists(e =>
            e.payload.contains("V5_WORKREPORT_MARKER") || rec.flatMap(_.reportStatus).exists(_.nonEmpty)
          ),
          "RED(V5): the notification must carry the finished work (workReport) or the record must be marked " +
            s"as delivered-pending-report (reportStatus). events=${events.map(_.payload.take(160))}, " +
            s"reportStatus=${rec.flatMap(_.reportStatus)}"
        )
      yield ()
    }
  }

  // ══════════════════════════════════════════════════════════════════════════
  // V5 反向用例（分发器 2026-11-04 11:29 补充令）：**没干完**的会话仍走复活腿，
  // 绝不进「完成未报告」收尾腿、绝不向父会话投「已完成」通知。
  // 判据同源：收尾腿的机械判据 = 任务行 completed ∧ registry.workReport 非空 ∧
  // reportStatus 缺席；在飞行（running）不满足第一条 ⇒ 只可能被复活。
  // ══════════════════════════════════════════════════════════════════════════

  test(
    "V5 反向: 没干完（在飞 running 行）的会话仍走复活腿， 收尾腿不得误吞、不得投已完成通知"
  ) {
    assume(legEnabled("V5"), "IR_LEG filter: V5 not selected")
    withFx("v5-negative") { fx =>
      val child = "delegate-nebflow-v5-open"
      for
        parent <- fx.sessionStore.createSession("Nebula", agentName = Some("Nebula"))
        _ <- fx.store.recordTask(mkTask(child, parent.id, status = "running"))
        _ <- IO(
          DelegateRegistry.put(
            mkRecord(child, parent.id, fx.seat.toString)
              .copy(workReport = Some("V5_PARTIAL_NOTE_MUST_NOT_BE_FINALIZED"), reportStatus = None)
          )
        )
        _ <- fx.sessionStore.saveMessagesForSession(
          child,
          List(Message(MessageRole.User, Left("V5_OPEN_BRIEF")), Message(MessageRole.Assistant, Left("started, no result yet")))
        )
        report <- sweepWith(fx)
        row <- fx.store.loadTasks(parent.id).map(_.find(_.taskId == child).get)
        events <- mailboxEvents(fx, parent.id).map(_.filter(_.correlationId.contains(child)))
        rec <- IO(DelegateRegistry.find(child))
        _ <- evidence("V5/negative")(
          s"sweep report: ${report.summary}",
          s"task row status = ${row.status} (revival keeps it running = 没干完仍在干)",
          s"parent mailbox events for child = ${events.size} (must be 0: 收尾腿不得对在飞工作投已完成)",
          s"registry reportStatus = ${rec.flatMap(_.reportStatus)} (must be None)"
        )
        _ = assert(
          report.revived.contains(child),
          s"RED(V5-neg): an in-flight (unfinished) delegate must still go through the revival leg — report=${report.summary}"
        )
        _ = assertEquals(row.status, "running", "V5-neg: the revived row is Processing, never finalized")
        _ = assertEquals(rec.flatMap(_.reportStatus), None, "V5-neg: no delivered-report state for unfinished work")
        _ = assertEquals(events, Nil, s"V5-neg: no completion notice may reach the parent: $events")
      yield ()
    }
  }

  // ── 辅助 ──────────────────────────────────────────────────────────────────

  /** 探针 actor：收下消息即「被中断」——不落盘（钉死 send→persist 崩溃窗口）。 */
  private def probeBehavior(seen: Ref[IO, List[String]]): nebflow.actor.Behavior[AgentCommand] =
    lazy val b: nebflow.actor.Behavior[AgentCommand] =
      Behaviors.receiveMessage[AgentCommand] {
        case m: AgentCommand.ImmediateInput => seen.update(_ :+ m.text) *> IO.pure(b)
        case _                              => IO.pure(b)
      }
    b

  /**
   * 已完成步骤断点探测：扫描该会话的持久目录（PathUtil.dataRoot/sessions/<sid>/），
   * 找**机器可读**台账（不是转录、不是待注入队列）中列举已完成 round 的件。
   *
   * 接受族（修复批可任选其一，README 有说明）：任何 .json/.jsonl，内容里出现
   * ≥2 个已完成 round 标记（V4_ROUND_*），或带 checkpoint/completedTurns/cursor/
   * turn 数值槽且值 ≥3。今日一族都不存在（TurnStateStore.scala:6-18 已删）。
   */
  private def checkpointArtifacts(sid: String): IO[List[String]] =
    IO.blocking {
      val dir = PathUtil.dataRoot / "sessions" / sid
      if !os.exists(dir) then Nil
      else
        os.walk(dir)
          .filter(p => os.isFile(p) && (p.ext == "json" || p.ext == "jsonl"))
          .filterNot(p => p.last == "injection-queues.json") // 待注入队列 ≠ 已完成台账
          .toList
          .filter { p =>
            val txt =
              try os.read(p)
              catch case _: Throwable => ""
            val rounds = List("V4_ROUND_1_DONE", "V4_ROUND_2_DONE", "V4_ROUND_3_DONE").count(txt.contains)
            // 接受族②：cursor/checkpoint 形态的数值台账（含 ledgerish 关键词且出现 ≥3 的数）。
            val lower = txt.toLowerCase
            val ledgerish = List("checkpoint", "completedturns", "completed_turns", "turncursor", "cursor").exists(lower.contains)
            rounds >= 2 || (ledgerish && List("3", "4", "5", "6", "7", "8", "9").exists(txt.contains))
          }
          .map(_.toString)
    }

end InterruptRecoveryLegsSpec
