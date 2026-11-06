package nebflow.core.project

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import fs2.Stream
import io.circe.Json
import io.circe.syntax.*
import io.circe.JsonObject
import munit.CatsEffectSuite
import nebflow.actor.{ActorSystem, AgentDef, Behavior, Behaviors}
import nebflow.actor.{AgentCommand, AgentKind, AgentRecord}
import nebflow.agent.{AgentLibrary, SharedResources, SubAgentTaskStore}
import nebflow.core.delegate.DelegateRegistry
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.{FileLockManager, MailTool, ToolContext}
import nebflow.core.{FileChangeTracker, RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor}
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, PathUtil, StreamChunk, ThinkingConfig}

import java.nio.file.Files
import scala.concurrent.duration.*

/**
 * 消息身份牌「发给 agent 就展开全文」批（msgcard-agent-expand，2026-11-05 作者裁定）
 * 的**投递腿实证**：不打桩解析器，走真实生产入口，把**注入到收件 agent 上下文里的
 * 原文**抓出来。
 *
 *  1. Mail 腿：真实 `MailTool.call(JsonObject("address" -> "delegate:<id>", …))`
 *     → 收件 delegate 的活 actor 收到 `AgentCommand.UserInput`，正文即注入原文；
 *  2. node 腿（running 注入）：真实 `NodeEngine.sendNodeMessage` → 节点活会话收到
 *     `ImmediateInput`，正文即注入原文；
 *  3. node 腿（pending 追加）：真实 `sendNodeMessage` → 节点 task 的「分发器补充」段，
 *     该段是 `NodeStarter.buildInput` 启动时读到的同一段正文。
 *
 * 三条腿各自断言：完整原文块在场（`[引用 · …]` 抬头 + 逐字正文 + `[引用结束 …]`）、
 * 长引用字数前后一致（零截断）、残缺牌/缺失牌变成 `[引用未能解析：…]` 且**裸牌零残留**。
 */
class MsgTokenAgentExpandLegSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 120.seconds

  private val tempRoot: os.Path = os.Path(Files.createTempDirectory("nb-msgexpand-leg"))
  private val originalRoot = PathUtil.dataRoot

  override def beforeAll(): Unit =
    PathUtil.setDataRoot(tempRoot)
    os.makeDir.all(tempRoot / "agents" / "test-agent")
    os.write.over(
      tempRoot / "agents" / "test-agent" / "agent.json",
      """{"name":"test-agent","description":"msgexpand leg suite agent","tools":[],"category":"standalone"}"""
    )
    os.write.over(tempRoot / "agents" / "test-agent" / "system.md", "# test-agent\n")

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)
    os.remove.all(tempRoot)

  // ── 装配（NodeMessageSpec / MailDelegateContinuationDeliverySpec 同款）────────

  private object NoLlm extends LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] =
      IO.raiseError(new RuntimeException("send not expected"))
    def sendStream(
      req: LlmRequest,
      onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] =
      Stream.raiseError[IO](new RuntimeException("sendStream not expected"))

  private def mkResources(system: ActorSystem): IO[SharedResources] =
    for
      dispatcher <- cats.effect.std.Dispatcher.parallel[IO].allocated.map(_._1)
      rateLimiter <- RateLimiter.create()
      tracker <- FileChangeTracker.create(os.pwd.toString)
      fileLocks <- FileLockManager.create
      thinkingRef <- Ref.of[IO, ThinkingConfig](ThinkingConfig())
      modelOverrides <- Ref.of[IO, Map[String, ModelCandidate]](Map.empty)
      voiceMuted <- Ref.of[IO, Boolean](false)
    yield SharedResources(
      llm = NoLlm,
      dispatcher = dispatcher,
      sessionStore = SessionStore(tempRoot / "sessions", tempRoot / "tasks"),
      projectRoot = os.pwd,
      thinkingConfigRef = thinkingRef,
      rateLimiter = rateLimiter,
      fileChangeTracker = tracker,
      contextWindow = 100_000,
      agentLibrary = new AgentLibrary(tempRoot / "agents"),
      taskStore = FileTaskStore,
      historyArchiver = null,
      fileLockManager = fileLocks,
      sessionModelOverrides = modelOverrides,
      providerRegistry = null,
      healthMonitor = ProviderHealthMonitor(null),
      actorSystem = system,
      subAgentTaskStore = new SubAgentTaskStore(tempRoot / s"subagent-tasks-${java.util.UUID.randomUUID().toString.take(6)}"),
      voiceMutedRef = voiceMuted
    )

  private def recorder(record: Ref[IO, List[AgentCommand]]): Behavior[AgentCommand] =
    def loop: Behavior[AgentCommand] = Behaviors.receiveMessage(cmd => record.update(_ :+ cmd).as(loop))
    loop

  /** actor `!` 是 tell（fire-and-forget）⇒ 读录制板前必须等到件真正入板。 */
  private def waitUntil(timeout: FiniteDuration, every: FiniteDuration = 20.millis)(
    cond: IO[Boolean]
  ): IO[Unit] =
    def go(deadline: Long): IO[Unit] =
      cond.flatMap {
        case true => IO.unit
        case false =>
          if System.currentTimeMillis() >= deadline then
            IO.raiseError(new AssertionError(s"waitUntil: condition not met within $timeout"))
          else IO.sleep(every) >> go(deadline)
      }
    go(System.currentTimeMillis() + timeout.toMillis)

  private val SenderSid = "expand-sender-session"

  private def ctx(res: SharedResources, system: ActorSystem, root: os.Path): ToolContext =
    ToolContext(
      projectRoot = root.toString,
      sessionId = Some(SenderSid),
      rootSessionId = Some(SenderSid),
      agentDef = Some(AgentDef(name = "Nebula", description = "msgexpand leg fixture")),
      actorSystem = Some(system),
      sharedResources = Some(res)
    )

  /** 长原文（> 超长门槛）：用来验证「长引用不丢字」。 */
  private def longText(n: Int): String =
    (1 to n).map(i => s"第${i}段：这段原文必须逐字到达收信方的上下文，不允许任何静默截断。").mkString

  // ── 1. Mail 腿：MailTool.call → delegate 活体收件 ─────────────────────────

  test("Mail 腿：发给 delegate 的 Mail 中，牌在注入前展开为完整原文块（收件 agent 读到的就是全文）") {
    val system = ActorSystem(s"msgexpand-mail-${scala.util.Random.nextInt(100000)}")
    val delegateId = "delegate-msgexpand0001"
    val body = "原始长消息：" + longText(6)
    for
      res <- mkResources(system)
      m <- res.msgTokens.mint(Some(SenderSid), body)
      token = m.getOrElse(fail("mint must succeed")).token
      core = token.stripPrefix("{{msg:").stripSuffix("}}")
      _ <- IO.blocking(
        DelegateRegistry.put(
          DelegateRegistry.Record(
            id = delegateId,
            executor = nebflow.core.executor.ExecutorRegistry.DefaultId,
            cwd = tempRoot.toString,
            project = None,
            taskId = None,
            parentSessionId = SenderSid,
            title = "msgexpand mail leg",
            createdAt = System.currentTimeMillis()
          )
        )
      )
      recorded <- Ref.of[IO, List[AgentCommand]](Nil)
      probe <- system.spawn(recorder(recorded), "msgexpand-mail-probe")
      _ <- res.agentRegistry.update(
        _ + (delegateId -> AgentRecord(delegateId, probe, AgentKind.Delegate, SenderSid))
      )
      // 正向：带短名牌
      r1 <- MailTool.call(
        JsonObject(
          "address" -> s"delegate:$delegateId".asJson,
          "message" -> s"补充：以这条为准 {{msg:$core|输入统一令}} 其余照旧".asJson
        ),
        ctx(res, system, tempRoot)
      )
      // 负向①：映射缺失（id 不存在 / 跨实例不可达）
      r2 <- MailTool.call(
        JsonObject(
          "address" -> s"delegate:$delegateId".asJson,
          "message" -> "这条牌是坏的 {{msg:deadbeef-000001}} 请忽略".asJson
        ),
        ctx(res, system, tempRoot)
      )
      // 负向②：格式残缺（少了 msg: 前缀 —— 作者现场写法）
      r3 <- MailTool.call(
        JsonObject(
          "address" -> s"delegate:$delegateId".asJson,
          "message" -> s"这条也坏 {{$core}} 请忽略".asJson
        ),
        ctx(res, system, tempRoot)
      )
      _ <- waitUntil(15.seconds)(
        recorded.get.map(_.count(_.isInstanceOf[AgentCommand.UserInput]) >= 3)
      )
      cmds <- recorded.get
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(r1.isRight, s"mail must succeed: $r1")
      val in1 = cmds.collect { case u: AgentCommand.UserInput => u.text }.headOption
        .getOrElse(fail(s"the delegate must receive a UserInput, got $cmds"))
      // 收件 agent 侧原文（这就是进 LLM 上下文的那段）
      assert(in1.contains("[引用 · 输入统一令"), s"展开抬头必须在场：${in1.take(300)}")
      assert(in1.contains("[引用结束 · "), s"展开结束标记必须在场：${in1.take(300)}")
      assert(in1.contains(body), "正文必须逐字 = 源消息全文（不是 token、不是摘要）")
      assert(!in1.contains(token), "牌 token 不许出现在注入原文里")
      assert(in1.contains(s"共 ${body.length} 字"), "字数必须与源消息一致（零截断的读数面）")
      assert(in1.contains("其余照旧"), "牌外文本原样保留")
      val in2 = cmds.collect { case u: AgentCommand.UserInput => u.text }.lift(1)
        .getOrElse(fail("second mail must be injected"))
      assert(in2.contains("[引用未能解析：{{msg:deadbeef-000001}}]"), s"映射缺失必须显式：$in2")
      assert(in2.contains("映射缺失"), s"原因须可读：$in2")
      assert(!in2.contains("{{msg:deadbeef-000001}} 请忽略"), "原串不许作为普通文本漏给收件方")
      val in3 = cmds.collect { case u: AgentCommand.UserInput => u.text }.lift(2)
        .getOrElse(fail("third mail must be injected"))
      assert(in3.contains(s"[引用未能解析：{{$core}}]"), s"格式残缺必须显式：$in3")
      assert(in3.contains("`msg:` 前缀"), s"诊断必须点名缺 msg: 前缀：$in3")
      assert(!in3.replace(s"[引用未能解析：{{$core}}]", "").contains(s"{{$core}}"), s"残缺原串只允许出现在标记里：$in3")
      println("[EVIDENCE-MAIL-INJECTED-START]\n" + in1 + "\n[EVIDENCE-MAIL-INJECTED-END]")
      println("[EVIDENCE-MAIL-MISS-START]\n" + in2 + "\n[EVIDENCE-MAIL-MISS-END]")
      println("[EVIDENCE-MAIL-FMT-START]\n" + in3 + "\n[EVIDENCE-MAIL-FMT-END]")
  }

  test("Mail 腿（长引用）：正文前后字数一致，超长注记在场，零截断") {
    val system = ActorSystem(s"msgexpand-mail-long-${scala.util.Random.nextInt(100000)}")
    val delegateId = "delegate-msgexpand0002"
    val huge = longText(120) // 远大于 LongQuoteThreshold
    for
      res <- mkResources(system)
      m <- res.msgTokens.mint(Some(SenderSid), huge)
      token = m.getOrElse(fail("mint must succeed")).token
      _ <- IO.blocking(
        DelegateRegistry.put(
          DelegateRegistry.Record(
            id = delegateId,
            executor = nebflow.core.executor.ExecutorRegistry.DefaultId,
            cwd = tempRoot.toString,
            project = None,
            taskId = None,
            parentSessionId = SenderSid,
            title = "msgexpand long leg",
            createdAt = System.currentTimeMillis()
          )
        )
      )
      recorded <- Ref.of[IO, List[AgentCommand]](Nil)
      probe <- system.spawn(recorder(recorded), "msgexpand-mail-long-probe")
      _ <- res.agentRegistry.update(
        _ + (delegateId -> AgentRecord(delegateId, probe, AgentKind.Delegate, SenderSid))
      )
      r <- MailTool.call(
        JsonObject("address" -> s"delegate:$delegateId".asJson, "message" -> s"按这条执行：$token".asJson),
        ctx(res, system, tempRoot)
      )
      _ <- waitUntil(15.seconds)(recorded.get.map(_.exists(_.isInstanceOf[AgentCommand.UserInput])))
      cmds <- recorded.get
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(r.isRight, s"mail must succeed: $r")
      val injected = cmds.collect { case u: AgentCommand.UserInput => u.text }
        .headOption
        .getOrElse(fail("the delegate must receive the mail"))
      val start = injected.indexOf('\n')
      val end = injected.lastIndexOf("\n[引用结束")
      assert(start >= 0 && end > start, s"展开块必须成对：${injected.take(200)}")
      val quoted = injected.substring(start + 1, end)
      assertEquals(quoted.length, huge.length, "注入正文长度 = 源消息长度（未截断）")
      assertEquals(quoted, huge, "注入正文逐字等于源消息")
      assert(injected.contains(s"超长引用 ${huge.length} 字（全文保留，未截断）"), "超长注记在场")
      println(
        s"[EVIDENCE-LONG] 源消息长度=${huge.length} 注入正文长度=${quoted.length} " +
          s"逐字相等=${quoted == huge} 注入全文长度=${injected.length}"
      )
      assert(!injected.contains(token), "牌 token 零残留")
  }

  // ── 2/3. node 腿：sendNodeMessage → running 注入 / pending 追加 ──────────────

  private def mountProject(name: String, ws: os.Path, system: ActorSystem, res: SharedResources): IO[ProjectRuntime] =
    for
      store <- FlowMapStore.open(name, ws.toString)
      engine = new NodeEngine(
        store,
        system,
        res,
        wsSendFn = (_: Json) => IO.unit,
        workspace = ws.toString,
        rootSessionId = SenderSid,
        projectName = name,
        feedbackMode = FeedbackRouter.ModeAuto,
        emitEvent = (_, _, _) => IO.unit
      )
      pd = ProjectDef(
        name = name,
        workspace = ws.toString,
        agentFile = (ws / "AGENTS.md").toString,
        createdAt = System.currentTimeMillis()
      )
      rt = ProjectRuntime(pd, store, engine, system, res, None)
      _ <- ProjectRuntimeRegistry.register(rt)
    yield rt

  private def seedNode(rt: ProjectRuntime, id: String, name: String, status: String, task: String): IO[Unit] =
    rt.store
      .mutate(s =>
        s.copy(nodes =
          s.nodes ++ Map(
            id -> NodeDef(
              id = id,
              name = name,
              agent = "test-agent",
              task = Some(task),
              result = None,
              status = status,
              out = List(OutEdge.root),
              createdAt = System.currentTimeMillis()
            )
          )
        )
      )
      .void

  override def beforeEach(context: munit.BeforeEach): Unit = ProjectRuntimeRegistry.clear
  override def afterEach(context: munit.AfterEach): Unit = ProjectRuntimeRegistry.clear

  test("node 腿（running 注入）：节点活会话收到的 ImmediateInput 正文里是完整原文块") {
    val ws = tempRoot / "ws-msgexpand-node-run"
    os.makeDir.all(ws)
    val system = ActorSystem(s"msgexpand-node-${scala.util.Random.nextInt(100000)}")
    val body = "节点补充原文：" + longText(3)
    for
      res <- mkResources(system)
      m <- res.msgTokens.mint(Some(SenderSid), body)
      token = m.getOrElse(fail("mint must succeed")).token
      core = token.stripPrefix("{{msg:").stripSuffix("}}")
      rt <- mountProject("msgexpand-node-run", ws, system, res)
      _ <- seedNode(rt, "n-run", "runner", NodeLifecycle.Running, "original task")
      recorded <- Ref.of[IO, List[AgentCommand]](Nil)
      nodeRef <- system.spawn(recorder(recorded), "msgexpand-node-probe")
      _ <- rt.resources.agentRegistry.update(
        _ + ("node-fake-session" -> AgentRecord("node-fake-session", nodeRef, AgentKind.Flow, SenderSid))
      )
      _ <- rt.engine.nodeSessions.update(_ + ("n-run" -> "node-fake-session"))
      r <- rt.engine.sendNodeMessage("n-run", s"补充指示：{{msg:$core|节点补充}} 照此执行")
      _ <- waitUntil(15.seconds)(recorded.get.map(_.exists(_.isInstanceOf[AgentCommand.ImmediateInput])))
      cmds <- recorded.get
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(r.isRight, s"node inject must succeed: $r")
      val injected = cmds.collect { case i: AgentCommand.ImmediateInput => i.text }
        .headOption
        .getOrElse(fail(s"the node session must receive an ImmediateInput, got $cmds"))
      assert(injected.startsWith("[NODE-MESSAGE]"), s"node header intact: ${injected.take(80)}")
      assert(injected.contains("[引用 · 节点补充"), s"展开抬头在场：${injected.take(300)}")
      assert(injected.contains(body), "正文逐字 = 源消息全文")
      assert(!injected.contains(token), "牌 token 零残留")
      assert(injected.contains(s"共 ${body.length} 字"), "字数读数在场")
  }

  test("node 腿（pending 追加）：task 的「分发器补充」段里是完整原文块；坏牌变显式标记") {
    val ws = tempRoot / "ws-msgexpand-node-pend"
    os.makeDir.all(ws)
    val system = ActorSystem(s"msgexpand-node-pend-${scala.util.Random.nextInt(100000)}")
    val body = "待启动节点的原文：" + longText(4)
    for
      res <- mkResources(system)
      m <- res.msgTokens.mint(Some(SenderSid), body)
      token = m.getOrElse(fail("mint must succeed")).token
      core = token.stripPrefix("{{msg:").stripSuffix("}}")
      rt <- mountProject("msgexpand-node-pend", ws, system, res)
      _ <- seedNode(rt, "n-pend", "pending", NodeLifecycle.Pending, "base task")
      r1 <- rt.engine.sendNodeMessage("n-pend", s"补充：$token 为准")
      r2 <- rt.engine.sendNodeMessage("n-pend", s"另：{{$core}} 这条写坏了")
      node <- rt.store.getNode("n-pend").map(_.getOrElse(fail("n-pend must exist")))
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(r1.isRight && r2.isRight, s"appends must succeed: $r1 / $r2")
      val task = node.task.getOrElse(fail("task must be defined"))
      assert(task.contains("== 分发器补充（NodeMessage"), "append section marker intact")
      assert(task.contains("[引用 · 消息 " + core), s"裸牌抬头用核心：${task.take(400)}")
      assert(task.contains(body), "正文逐字 = 源消息全文")
      assert(!task.contains(token), "牌 token 零残留")
      assert(task.contains(s"[引用未能解析：{{$core}}]"), s"坏牌必须显式：${task.take(600)}")
      assert(task.contains("`msg:` 前缀"), "诊断点名缺 msg: 前缀")
  }

end MsgTokenAgentExpandLegSpec
