package nebflow.core.project

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import fs2.Stream
import io.circe.Json
import munit.CatsEffectSuite
import nebflow.actor.ActorSystem
import nebflow.agent.{AgentLibrary, InjectionAttribution, SharedResources}
import nebflow.core.PathUtil
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ThinkingConfig}
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, StreamChunk, UiMessage}

import scala.concurrent.duration.*

/**
 * mailbadge 批（2026-09-13，选项 C）**分发器收件面端到端验收**：把
 * `Mail(address="project:<name>")` 的收件形态（`source` 恒 `"task"` + 新判别字段
 * `intake`）在**真实引擎路径**上跑一遍，逐条钉住两处互证：
 *
 *   ① WS 帧（live 呈现面）：`{type:"user", injected:true, source:"task", intake:"mail", …}`
 *   ② `.ui.json` 落盘行（历史恢复面，`SessionStore` 唯一写者 = 同一发射点）
 *   ③ 会话转录（`.json`，`Message.source`）——**桥的消费计数输入**逐字不变
 *      （`ProjectActor:622` 的 `m.source.exists(DispatcherInjectedSources.contains)`）
 *
 * 标签段（`SOURCE` 段）由前端**同一实现**判定：`tests/injected-intake-label.mjs`
 * 用真实 `chat.js#injectedSourceLabel` 断言 `(source='task', intake='mail') ⇒ 'Mail · …'`。
 * 两条读数合起来 = 「段读数 + `.ui.json` 落盘字段」互证。
 *
 * **本 spec 不证明的**（如实标注）：真实 30 min 空闲窗（另由 `DispatcherIdleWindowSpec`
 * 在压缩尺度上等效覆盖；本 spec 用 `dispatcherIdleWindowMs = Some(0L)` = 回退开关，
 * 避免保活语义混入呈现面判定）；真实宿主重启后的浏览器 DOM（需宿主重启才生效）。
 */
class DispatcherIntakeLabelSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 180.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-dispatcher-intake-label"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot / "agents" / "project-dispatcher")
  os.write.over(
    tempRoot / "agents" / "project-dispatcher" / "agent.json",
    """{"name":"project-dispatcher","description":"intake-label test dispatcher","tools":[],"category":"standalone"}"""
  )
  os.write.over(tempRoot / "agents" / "project-dispatcher" / "system.md", "# project-dispatcher\n")

  override def afterAll(): Unit = PathUtil.setDataRoot(originalRoot)

  override def beforeEach(context: munit.BeforeEach): Unit = ProjectRuntimeRegistry.clear
  override def afterEach(context: munit.AfterEach): Unit = ProjectRuntimeRegistry.clear

  /** 记录型 LLM：immediate 收尾（零工具调用），只计数已结束 stream。 */
  private class QuietLlm:
    val streamsDone: Ref[IO, Int] = Ref.unsafe[IO, Int](0)
    val handle: LlmHandle[IO] = new LlmHandle[IO]:
      def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new RuntimeException("send not expected"))
      def sendStream(
          req: LlmRequest,
          onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
      ): Stream[IO, StreamChunk] =
        Stream(StreamChunk.TextDelta("ok"), StreamChunk.Done(None, None)) ++
          Stream.eval(streamsDone.update(_ + 1)).drain

  private def mkResources(system: ActorSystem, llm: LlmHandle[IO]): IO[SharedResources] =
    for
      dispatcher <- cats.effect.std.Dispatcher.parallel[IO].allocated.map(_._1)
      rateLimiter <- RateLimiter.create()
      tracker <- nebflow.core.FileChangeTracker.create(os.pwd.toString)
      fileLocks <- FileLockManager.create
      thinkingRef <- Ref.of[IO, ThinkingConfig](ThinkingConfig())
      modelOverrides <- Ref.of[IO, Map[String, ModelCandidate]](Map.empty)
      voiceMuted <- Ref.of[IO, Boolean](false)
    yield SharedResources(
      llm = llm,
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
      healthMonitor = null,
      actorSystem = system,
      voiceMutedRef = voiceMuted
    )

  private def waitUntil(timeout: FiniteDuration, every: FiniteDuration = 25.millis)(cond: IO[Boolean]): IO[Unit] =
    def go(deadline: Long): IO[Unit] =
      cond.flatMap {
        case true => IO.unit
        case false =>
          if System.currentTimeMillis() >= deadline then
            IO.raiseError(new AssertionError("waitUntil: condition not met in time"))
          else IO.sleep(every) >> go(deadline)
      }
    go(System.currentTimeMillis() + timeout.toMillis)

  private def dispatcherSid(resources: SharedResources): IO[Option[String]] =
    resources.agentRegistry.get.map(_.keys.toList.filter(_.startsWith(ProjectActor.DispatcherSessionPrefix)).headOption)

  private def mount(name: String, system: ActorSystem, res: SharedResources, frames: Ref[IO, List[Json]]): IO[ProjectRuntime] =
    val ws = tempRoot / name
    os.makeDir.all(ws)
    val pd = ProjectDef(
      name = name,
      workspace = ws.toString,
      agentFile = (ws / "AGENTS.md").toString,
      createdAt = System.currentTimeMillis()
    )
    // 长窗（本 spec 不起 ttlScanner ⇒ 无到期扫描腿）：会话在用例期内常驻，
    // 呈现面判定不与被拆竞态；`idleSince` / 到期销毁面由 `DispatcherIdleWindowSpec` 覆盖。
    ProjectRuntimeRegistry.mount(
      pd,
      system,
      res,
      Some((j: Json) => frames.update(_ :+ j)),
      rootSessionId = "nebula-root",
      ttlCheckIntervalSec = 1,
      dispatcherIdleWindowMs = Some(120_000L)
    )

  /** 驱动一次「Mail → project」收件：与 `MailTool#routeToProject` 逐字同形
    * （`SourceTask` + attribution；`intake` 由本用例参数决定 = 腿① 置位 / 无置位）。 */
  private def dispatchMail(
      rt: ProjectRuntime,
      taskText: String,
      intake: Option[String]
  ): IO[Unit] =
    val attribution = Some(
      InjectionAttribution(
        sender = Some("Nebula"),
        senderTeam = None,
        eventType = Some("info"),
        intake = intake
      )
    )
    (rt.actorRef.get ! ProjectActor.ProjectCommand.TriggerDispatcher(taskText, "nebula-root", ProjectActor.SourceTask, attribution)).void

  private def injectedFrames(frames: Ref[IO, List[Json]]): IO[List[Json]] =
    frames.get.map(_.filter(j => j.hcursor.downField("injected").as[Boolean].getOrElse(false)))

  private def uiRows(res: SharedResources, sid: String): IO[List[UiMessage]] =
    res.sessionStore.getUiMessages(sid, 0, 0).map(_._1)

  /** 落盘面是本 spec 的法证：SessionStore 的写盘是**去抖**的（追加先进缓存 + markDirty），
    * 故读盘前显式 flush 两条脏队列（公开 API），再等文件出现。 */
  private def flushWrites(res: SharedResources, sid: String): IO[Unit] =
    res.sessionStore.flushPendingUiWrites *>
      res.sessionStore.flushPendingMessages *>
      waitUntil(15.seconds)(IO.blocking(os.exists(tempRoot / "sessions" / s"$sid.ui.json")))

  private def rawUi(sid: String): String =
    val f = tempRoot / "sessions" / s"$sid.ui.json"
    if os.exists(f) then os.read(f) else ""

  test("正向（验收 1）：Mail(→project:) 收件帧 + .ui.json 落盘字段 = source 'task' + intake 'mail'（两处互证）"):
    val system = ActorSystem(s"intake-label-${scala.util.Random.nextInt(100000)}")
    for
      llm <- IO.pure(new QuietLlm)
      frames <- Ref.of[IO, List[Json]](Nil)
      resources <- mkResources(system, llm.handle)
      rt <- mount("mail-intake", system, resources, frames)
      _ <- dispatchMail(rt, "任务甲-正文", Some(InjectionAttribution.IntakeMail))
      _ <- waitUntil(30.seconds)(injectedFrames(frames).map(_.nonEmpty))
      fs <- injectedFrames(frames)
      sid = fs.head.hcursor.downField("sessionId").as[String].getOrElse(sys.error("frame must carry sessionId"))
      _ <- waitUntil(30.seconds)(llm.streamsDone.get.map(_ >= 1))
      _ <- flushWrites(resources, sid)
      rows <- uiRows(resources, sid)
      raw = rawUi(sid)
      // ③ 转录面（桥的消费计数输入口径未变）：Message.source 恒 'task'（注入源标记），
      //    且转录层**零 intake**（该字段只活在呈现面）。
      // 转录面在 turn 终态后才落（等它出现再断言）。
      _ <- waitUntil(20.seconds)(resources.sessionStore.loadMessagesForSession(sid).map(_.nonEmpty))
      msgs <- resources.sessionStore.loadMessagesForSession(sid)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      // ① WS 帧
      val f = fs.head
      assertEquals(f.hcursor.downField("type").as[String], Right("user"), "注入帧 type")
      assertEquals(f.hcursor.downField("source").as[String], Right("task"), "收件面 source 必须保持 'task'（D-5 值不改名）")
      assertEquals(f.hcursor.downField("intake").as[String], Right("mail"), "注入帧缺 intake 判别字段")
      assertEquals(f.hcursor.downField("sender").as[String], Right("Nebula"), "发信方标注（bluebubble 既有面）")
      assertEquals(f.hcursor.downField("eventType").as[String], Right("info"), "邮件类型段")
      // ② .ui.json 落盘行（历史恢复面）
      val injected = rows.collect { case u: UiMessage.User if u.injected => u }
      assertEquals(injected.size, 1, s"应恰有一条注入行，got $injected")
      assertEquals(injected.head.source, Some("task"), "落盘 source 必须是 'task'")
      assertEquals(injected.head.intake, Some("mail"), "落盘缺 intake（历史恢复面会丢标签判别）")
      assert(raw.contains("\"source\":\"task\""), s"落盘字节缺 source:task：${raw.take(300)}")
      assert(raw.contains("\"intake\":\"mail\""), s"落盘字节缺 intake:mail：${raw.take(300)}")
      val injectedMsgs = msgs.filter(_.source.isDefined)
      assertEquals(
        injectedMsgs.map(_.source),
        List(Some("task")),
        s"转录面注入行 source 必须恰为 'task'（ProjectActor:622 的计数输入）——got $injectedMsgs"
      )
      assert(
        !classOf[nebflow.shared.Message].getDeclaredFields.map(_.getName).contains("intake"),
        "转录层（Message）被塞入 intake 字段 —— 会计面污染（本批禁：该字段只活在呈现面）"
      )

  test("反向（验收 2）：无 intake 的 task 形态收件 ⇒ 帧/落盘均不带该字段（回落路径 ⇒ 标签仍 Task）"):
    val system = ActorSystem(s"intake-absent-${scala.util.Random.nextInt(100000)}")
    for
      llm <- IO.pure(new QuietLlm)
      frames <- Ref.of[IO, List[Json]](Nil)
      resources <- mkResources(system, llm.handle)
      rt <- mount("task-plain", system, resources, frames)
      // 与 Task 入口/重入同形（attribution 存在但无 intake）。
      _ <- dispatchMail(rt, "任务乙-正文", None)
      _ <- waitUntil(30.seconds)(injectedFrames(frames).map(_.nonEmpty))
      fs <- injectedFrames(frames)
      sid = fs.head.hcursor.downField("sessionId").as[String].getOrElse(sys.error("frame must carry sessionId"))
      _ <- waitUntil(30.seconds)(llm.streamsDone.get.map(_ >= 1))
      _ <- flushWrites(resources, sid)
      rows <- uiRows(resources, sid)
      raw = rawUi(sid)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      val f = fs.head
      assertEquals(f.hcursor.downField("source").as[String], Right("task"), "source 不变")
      assert(f.hcursor.downField("intake").focus.isEmpty, "字段缺席时不得落 intake 帧键（旧帧字节形态不变）")
      val injected = rows.collect { case u: UiMessage.User if u.injected => u }
      assertEquals(injected.map(_.intake), List(None), "字段缺席时落盘必须为 None ⇒ 前端走回落表（标签仍 Task）")
      assert(!raw.contains("intake"), s"落盘字节混入 intake：${raw.take(300)}")

end DispatcherIntakeLabelSpec
