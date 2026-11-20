/* C-2 · node 消息持久化 + 节点上下文恢复（2026-11-04）·
 *
 * 被测腿：`node:` 消息**投递前先落盘**（append-only 台账）+ 重启可回放 + 回放幂等 +
 * terminal 仍拒收。四条断言全部跑**生产同一函数**：
 *   - `NodeEngine.sendNodeMessage`（三态投递入口）
 *   - `NodeEngine.bootRecoveryClaim`（boot 快段认领 + resume prompt 回放腿）
 *   - `NodeEngine.replayPendingNodeMessages`（队列兜底回放腿）
 * 「重启」= 盘面摆成崩溃后形态 + 再跑同一 boot 函数（与 InterruptRecoveryLegsSpec 同纪律）。
 *
 * 零进程启动、不触碰生产实例（pid 41715）、不调 stand stop/restart。
 */
package nebflow.core.project

import cats.effect.{IO, Ref}
import cats.effect.std.Dispatcher
import cats.effect.unsafe.implicits.global
import io.circe.Json
import munit.CatsEffectSuite
import nebflow.actor.{ActorSystem, AgentCommand, AgentKind, AgentRecord, Behaviors}
import nebflow.agent.SharedResources
import nebflow.core.FileChangeTracker
import nebflow.core.compact.HistoryArchiver
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.core.{RateLimiter, SessionStore}
import nebflow.llm.ProviderHealthMonitor
import nebflow.shared.{Message, MessageRole, PathUtil, ThinkingConfig}

import scala.concurrent.duration.*

class NodeMessagePersistenceSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 120.seconds

  private val originalRoot = PathUtil.dataRoot
  nebflow.core.LlmLogWriter.setEnabled(false)

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)
    nebflow.core.LlmLogWriter.setEnabled(true)

  // ── 夹具：真 ActorSystem / 真 SessionStore / 真 FlowMapStore；LLM 不参与 ──
  private final case class Fx(
    resources: SharedResources,
    sessionStore: SessionStore,
    system: ActorSystem,
    tmp: os.Path
  )

  private def withFx(name: String)(body: Fx => IO[Unit]): Unit =
    val tmp = os.temp.dir(prefix = s"nmp-$name")
    PathUtil.setDataRoot(tmp / "data")
    val system = ActorSystem(s"nmp-$name-${java.util.UUID.randomUUID().toString.take(6)}")
    val io =
      for
        dispatcher <- Dispatcher.parallel[IO].allocated.map(_._1)
        rateLimiter <- RateLimiter.create()
        tracker <- FileChangeTracker.create(os.pwd.toString)
        fileLocks <- FileLockManager.create
        thinkingRef <- Ref.of[IO, ThinkingConfig](ThinkingConfig())
        modelOverrides <- Ref.of[IO, Map[String, nebflow.llm.ModelCandidate]](Map.empty)
        voiceMuted <- Ref.of[IO, Boolean](false)
        sessionStore = SessionStore(tmp / "sessions", tmp / "tasks")
        resources = SharedResources(
          llm = null,
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
          subAgentTaskStore = new nebflow.agent.SubAgentTaskStore(tmp / "subagent-tasks"),
          voiceMutedRef = voiceMuted
        )
        _ <- body(Fx(resources, sessionStore, system, tmp))
      yield ()
    try io.unsafeRunSync()
    finally
      system.stopAll.attempt.void.unsafeRunSync()
      PathUtil.setDataRoot(originalRoot)
      os.remove.all(tmp)

  end withFx

  private def probeBehavior(seen: Ref[IO, List[String]]): nebflow.actor.Behavior[AgentCommand] =
    lazy val b: nebflow.actor.Behavior[AgentCommand] =
      Behaviors.receiveMessage[AgentCommand] {
        case m: AgentCommand.ImmediateInput => seen.update(_ :+ m.text) *> IO.pure(b)
        case _ => IO.unit.as(b)
      }
    b

  /** 引擎构造（workspace = 本腿临时的 flow map 工作区；事件出口 = no-op 桩）。 */
  private def mkEngine(store: FlowMapStore, fx: Fx, ws: os.Path, project: String): NodeEngine =
    new NodeEngine(
      store,
      fx.system,
      fx.resources,
      wsSendFn = (_: Json) => IO.unit,
      workspace = ws.toString,
      rootSessionId = "nebula-root",
      projectName = project,
      emitEvent = (_: String, _: String, _: Json) => IO.unit
    )

  private def setStatus(store: FlowMapStore, nodeId: String, status: String): IO[Unit] =
    store.mutate(s => s.copy(nodes = s.nodes.updated(nodeId, s.nodes(nodeId).copy(status = status)))).void

  private def occurrences(hay: String, needle: String): Int =
    if needle.isEmpty then 0 else hay.sliding(needle.length).count(_ == needle)

  private def evidence(tag: String)(lines: String*): IO[Unit] =
    IO(println(lines.map(l => s"[NMP $tag] $l").mkString("\n")))

  private def mkNode(id: String, name: String, status: String, sid: String, task: Option[String] = None): NodeDef =
    NodeDef(
      id = id,
      name = name,
      agent = "general",
      status = status,
      sessionRef = Some(sid),
      task = task,
      createdAt = System.currentTimeMillis() - 1000L
    )

  // ══════════════════════════════════════════════════════════════════════════
  // S1 — 落盘前置：running 投递成功 ⇒ 台账已有该消息（queued→injected），
  //      且幂等标记随正文同投（探针 actor 收到的文本里带 `消息号`）
  // ══════════════════════════════════════════════════════════════════════════
  test("S1 落盘前置: sendNodeMessage 成功即台账有账（queued/injected）+ 幂等标记随正文") {
    withFx("s1") { fx =>
      val sid = "node-s1-session"
      val nodeId = "n-s1"
      for
        ws <- IO.blocking { val d = fx.tmp / "ws-s1"; os.makeDir.all(d); d }
        store <- FlowMapStore.open("nmp-s1", ws.toString)
        seen <- Ref.of[IO, List[String]](Nil)
        ref <- fx.system.spawn(probeBehavior(seen), "nmp-s1-probe")
        _ <- fx.resources.agentRegistry.update(_ + (sid -> AgentRecord(sid, ref, AgentKind.Delegate, sid)))
        _ <- store.mutate(s => s.copy(nodes = s.nodes + (nodeId -> mkNode(nodeId, "n-s1", NodeLifecycle.Running, sid))))
        engine = mkEngine(store, fx, ws, "nmp-s1")
        res <- engine.sendNodeMessage(nodeId, "S1_BODY keep going")
        rows <- NodeMessageLedger.load(ws.toString)
        entries = NodeMessageLedger.fold(rows)
        delivered <- seen.get
        id = entries.headOption.map(_.id).getOrElse("")
        _ <- evidence("S1")(
          s"sendNodeMessage = ${res.map(_.take(60))}",
          s"ledger rows = ${rows.size} : ${rows.map(r => s"${r.event}/${r.id}").mkString(", ")}",
          s"folding state = ${entries.map(e => s"${e.id}=${e.event}").mkString(", ")}",
          s"probe received with marker = ${delivered.exists(_.contains(NodeMessageLedger.markerOf(id)))}",
          s"ledger path = ${NodeMessageLedger.path(ws.toString)}"
        )
        _ = assert(res.isRight, s"S1: delivery to a live running node must succeed, got: $res")
        _ = assert(
          rows.nonEmpty,
          "S1: a successful delivery must leave ledger rows on disk (the loss window is closed)"
        )
        _ = assertEquals(entries.size, 1, s"S1: exactly one ledgered message expected, got ${entries.size}")
        _ = assertEquals(
          entries.head.event,
          NodeMessageLedger.Injected,
          "S1: the tell accepted ⇒ state advances to injected"
        )
        _ = assertEquals(entries.head.text, "S1_BODY keep going", "S1: the ledger carries the resolved message body")
        _ = assert(
          delivered.exists(_.contains(NodeMessageLedger.markerOf(entries.head.id))),
          "S1: the idempotency marker must travel WITH the injected text (that is what the consumption check scans for)"
        )
        _ = assert(
          NodeMessageLedger.inMessages(delivered.map(t => Message(MessageRole.User, Left(t))), entries.head.id),
          "S1: the marker must be detectable from a transcript-shaped surface"
        )
      yield ()
      end for
    }
  }

  // ══════════════════════════════════════════════════════════════════════════
  // S2 — 重启回放 + 幂等：未消费 ⇒ 进 resume prompt；已消费 ⇒ 不再注入（两次 boot 恰一次）
  // ══════════════════════════════════════════════════════════════════════════
  test("S2 重放幂等: 未消费消息进 resume prompt 恰一次；转录已含标记 ⇒ 终态 consumed 且零重放") {
    withFx("s2") { fx =>
      val sid = "node-s2-session"
      val nodeId = "n-s2"
      for
        ws <- IO.blocking { val d = fx.tmp / "ws-s2"; os.makeDir.all(d); d }
        store <- FlowMapStore.open("nmp-s2", ws.toString)
        seen <- Ref.of[IO, List[String]](Nil)
        ref <- fx.system.spawn(probeBehavior(seen), "nmp-s2-probe")
        _ <- fx.resources.agentRegistry.update(_ + (sid -> AgentRecord(sid, ref, AgentKind.Delegate, sid)))
        _ <- store.mutate(s => s.copy(nodes = s.nodes + (nodeId -> mkNode(nodeId, "n-s2", NodeLifecycle.Running, sid))))
        // 崩溃前转录（节点上下文）：一条 brief + 一条已完成步骤
        _ <- fx.sessionStore.saveMessagesForSession(
          sid,
          List(Message(MessageRole.User, Left("S2_BRIEF")), Message(MessageRole.Assistant, Left("S2_DONE_STEP_1")))
        )
        engine = mkEngine(store, fx, ws, "nmp-s2")
        // 运行中注入一条：投递成功但「接收方已死在落盘前」——探针不落盘，正是被修补的窗口
        _ <- engine.sendNodeMessage(nodeId, "S2_INFLIGHT survive the restart")
        ledgerAfterSend <- NodeMessageLedger.entries(ws.toString)
        msgId = ledgerAfterSend.headOption.map(_.id).getOrElse("")
        // ── 重启 #1：boot 快段认领 ⇒ resume prompt 必须带这条消息 ──
        node0 <- store.getNode(nodeId).map(_.get)
        engine1 = mkEngine(store, fx, ws, "nmp-s2")
        claim1 <- engine1.bootRecoveryClaim(node0)
        prompt1 = claim1.flatMap(_.toOption).map(_.resumePrompt).getOrElse("")
        ledger1 <- NodeMessageLedger.entries(ws.toString)
        // 「节点消费了回放」：会话把 resume prompt 写进转录（真实 resume 的第一条 user 消息）
        _ <- fx.sessionStore.saveMessagesForSession(
          sid,
          List(
            Message(MessageRole.User, Left("S2_BRIEF")),
            Message(MessageRole.Assistant, Left("S2_DONE_STEP_1")),
            Message(MessageRole.User, Left(prompt1))
          )
        )
        // ── 重启 #2：节点又崩了一次（状态回 Running）⇒ 已消费 ⇒ 绝不重放 ──
        _ <- setStatus(store, nodeId, NodeLifecycle.Running)
        node1 <- store.getNode(nodeId).map(_.get)
        engine2 = mkEngine(store, fx, ws, "nmp-s2")
        claim2 <- engine2.bootRecoveryClaim(node1)
        prompt2 = claim2.flatMap(_.toOption).map(_.resumePrompt).getOrElse("")
        ledger2 <- NodeMessageLedger.entries(ws.toString)
        turns <- seen.get
        _ <- evidence("S2")(
          s"ledger after send = ${ledgerAfterSend.map(e => s"${e.id}=${e.event}").mkString(", ")}",
          s"boot#1 claim = ${claim1.map(_.map(_ => "resume"))} ; prompt carries marker = ${prompt1.contains(NodeMessageLedger.markerOf(msgId))}",
          s"ledger after boot#1 = ${ledger1.map(e => s"${e.id}=${e.event}").mkString(", ")}",
          s"boot#2 claim = ${claim2.map(_.map(_ => "resume"))} ; prompt carries marker = ${prompt2.contains(NodeMessageLedger.markerOf(msgId))}",
          s"ledger after boot#2 = ${ledger2.map(e => s"${e.id}=${e.event}").mkString(", ")}",
          s"pre-crash context restored in prompt#1 = ${prompt1.contains("history restored from disk (2 messages")}",
          s"probe tells (must stay 1 — the ledger never talks to the actor) = ${turns.size}"
        )
        _ = assert(claim1.exists(_.isRight), s"S2: boot#1 must claim the running node for resume, got $claim1")
        _ = assert(
          prompt1.contains(NodeMessageLedger.markerOf(msgId)) && prompt1.contains("S2_INFLIGHT survive the restart"),
          "S2: the unconsumed message must be replayed into the resume prompt (verbatim body + marker)"
        )
        _ = assert(
          prompt1.contains("history restored from disk (2 messages"),
          "S2: node context recovery — the resume prompt still restores the pre-crash transcript"
        )
        _ = assertEquals(
          ledger1.find(_.id == msgId).map(_.event),
          Some(NodeMessageLedger.Replayed),
          "S2: replay is recorded"
        )
        _ = assert(claim2.isDefined, "S2: boot#2 also claims (the node crashed again after the replay)")
        _ = assert(
          !prompt2.contains(NodeMessageLedger.markerOf(msgId)),
          s"S2 IDEMPOTENCY: a consumed message must never be replayed twice (prompt#2 still carried ${NodeMessageLedger.markerOf(msgId)})"
        )
        _ = assertEquals(
          ledger2.find(_.id == msgId).map(_.event),
          Some(NodeMessageLedger.Consumed),
          "S2: the transcript is authoritative — consumed closes the ledger permanently"
        )
        _ = assertEquals(
          turns.size,
          1,
          "S2: the live-session route fired exactly once (replay goes through the resume prompt)"
        )
      yield ()
      end for
    }
  }

  // ══════════════════════════════════════════════════════════════════════════
  // S3 — terminal 仍拒收（裁定④不改）：零投递、零台账、零注入
  // ══════════════════════════════════════════════════════════════════════════
  test("S3 terminal 仍拒绝: completed 节点拒收 ⇒ 零投递、零台账、零注入") {
    withFx("s3") { fx =>
      val sid = "node-s3-session"
      val nodeId = "n-s3"
      for
        ws <- IO.blocking { val d = fx.tmp / "ws-s3"; os.makeDir.all(d); d }
        store <- FlowMapStore.open("nmp-s3", ws.toString)
        seen <- Ref.of[IO, List[String]](Nil)
        ref <- fx.system.spawn(probeBehavior(seen), "nmp-s3-probe")
        _ <- fx.resources.agentRegistry.update(_ + (sid -> AgentRecord(sid, ref, AgentKind.Delegate, sid)))
        _ <- store.mutate(s =>
          s.copy(nodes = s.nodes + (nodeId -> mkNode(nodeId, "n-s3", NodeLifecycle.Completed, sid)))
        )
        engine = mkEngine(store, fx, ws, "nmp-s3")
        res <- engine.sendNodeMessage(nodeId, "S3_MUST_NOT_LAND")
        rows <- NodeMessageLedger.load(ws.toString)
        delivered <- seen.get
        node <- store.getNode(nodeId)
        _ <- evidence("S3")(
          s"sendNodeMessage = ${res.left.map(_.take(90))}",
          s"ledger rows = ${rows.size}",
          s"probe received = ${delivered.size}",
          s"node.task carries the body = ${node.exists(_.task.exists(_.contains("S3_MUST_NOT_LAND")))}"
        )
        _ = assert(res.isLeft, "S3: a terminal node must refuse node messages")
        _ = assert(
          res.left.exists(_.contains("NODE_TERMINAL_NO_MESSAGE")),
          s"S3: refusal must keep the established code, got: $res"
        )
        _ = assertEquals(rows.size, 0, "S3: a refused message never reaches the ledger (refusal precedes persistence)")
        _ = assertEquals(delivered.size, 0, "S3: nothing is injected into a terminal node's session")
        _ = assert(
          !node.exists(_.task.exists(_.contains("S3_MUST_NOT_LAND"))),
          "S3: nothing is appended to a terminal node's task"
        )
      yield ()
      end for
    }
  }

end NodeMessagePersistenceSpec
