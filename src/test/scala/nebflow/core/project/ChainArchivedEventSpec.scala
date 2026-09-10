package nebflow.core.project

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import fs2.Stream
import io.circe.Json
import io.circe.parser.parse as jsonParse
import munit.CatsEffectSuite
import nebflow.actor.ActorSystem
import nebflow.agent.{AgentLibrary, SharedResources}
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ThinkingConfig}
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, StreamChunk}

import scala.concurrent.duration.*

/**
 * 归档联动的**端到端接线**回归（P3 批 2026-09-10，spec §6.2）：ProjectActor TtlTick
 * → `sweepCompletedChainsDetailed` 整链出库 → 同一出库动作后追加 `chain-archived`
 * 审计事件（顶层 chainId + 结构化 summary），且重复 tick 不重复追加（链已出库 →
 * 明细为空）。
 *
 * 单测层（[[DocIndexConsumerSpec]]）覆盖事件契约与消费侧；本 spec 覆盖引擎侧接线
 * ——即「sweep → 事件 → 归档批文件」三个事实在同一次 tick 内同时成立。
 */
class ChainArchivedEventSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 120.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-chain-archived-event"

  private class NoopLlm extends LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new RuntimeException("send not expected"))
    def sendStream(
        req: LlmRequest,
        onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] =
      Stream(StreamChunk.TextDelta("ok"), StreamChunk.Done(None, None))

  private def mkResources(system: ActorSystem, tmp: os.Path, llm: LlmHandle[IO]): IO[SharedResources] =
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
      sessionStore = SessionStore(tmp / "sessions", tmp / "tasks"),
      projectRoot = os.pwd,
      thinkingConfigRef = thinkingRef,
      rateLimiter = rateLimiter,
      fileChangeTracker = tracker,
      contextWindow = 100_000,
      agentLibrary = new AgentLibrary(tmp / "agents"),
      taskStore = FileTaskStore,
      historyArchiver = null,
      fileLockManager = fileLocks,
      sessionModelOverrides = modelOverrides,
      providerRegistry = null,
      healthMonitor = null,
      actorSystem = null,
      voiceMutedRef = voiceMuted
    )

  private def waitUntil(timeout: FiniteDuration, every: FiniteDuration = 50.millis)(cond: IO[Boolean]): IO[Unit] =
    def go(deadline: Long): IO[Unit] =
      cond.flatMap {
        case true => IO.unit
        case false =>
          if System.currentTimeMillis() >= deadline then IO.raiseError(new AssertionError("waitUntil: timeout"))
          else IO.sleep(every) >> go(deadline)
      }
    go(System.currentTimeMillis() + timeout.toMillis)

  test("TtlTick：整链出库 → chain-archived 事件（顶层 chainId + summary）+ 归档批文件；重复 tick 不重复") {
    val ws = tempRoot / s"ws-${System.nanoTime()}"
    os.makeDir.all(ws)
    val system = ActorSystem(s"charch-${scala.util.Random.nextInt(100000)}")
    val base = System.currentTimeMillis() - 100000
    for
      res <- mkResources(system, tempRoot, new NoopLlm)
      store <- FlowMapStore.open("charch", ws.toString)
      engine = new NodeEngine(store, system, res,
        wsSendFn = (_: Json) => IO.unit,
        workspace = ws.toString,
        rootSessionId = "nebula-root",
        projectName = "charch",
        emitEvent = (_: String, _: String, _: Json) => IO.unit)
      pd = ProjectDef(name = "charch", workspace = ws.toString,
        agentFile = (ws / "AGENTS.md").toString, createdAt = System.currentTimeMillis())
      rt = ProjectRuntime(pd, store, engine, system, res, None)
      _ <- ProjectRuntimeRegistry.register(rt)
      _ <- store.mutate(s => s.copy(nodes = Map(
        "n1" -> NodeDef(id = "n1", name = "head", agent = "Backend", status = NodeLifecycle.Completed,
          createdAt = base, completedAt = Some(base + 1), out = List(OutEdge.nebula)),
        "n2" -> NodeDef(id = "n2", name = "tail", agent = "Backend", status = NodeLifecycle.Completed,
          createdAt = base + 10, completedAt = Some(base + 20), in = List("n1"), out = List(OutEdge.nebula))
      )))
      ref <- system.spawn(
        ProjectActor(ProjectActor.ProjectConfig(rt.project, rt.engine, system, res, "nebula-root")),
        s"proj-charch-${scala.util.Random.nextInt(100000)}")
      eventsFile = ws / ".nebflow" / FlowMapEventLog.FileName
      _ <- (ref ! ProjectActor.ProjectCommand.TtlTick).void
      _ <- waitUntil(30.seconds)(IO.blocking(os.exists(eventsFile) && os.read(eventsFile).contains("chain-archived")))
      lines1 <- IO.blocking(os.read.lines(eventsFile).toList)
      batches <- IO.blocking(os.list(ws / ".nebflow" / FlowMapStore.ArchiveDirName).map(_.last).toList.sorted)
      // 重复 tick：链已出库 → 明细为空 → 不重复追加
      _ <- (ref ! ProjectActor.ProjectCommand.TtlTick).void
      _ <- IO.sleep(500.millis)
      lines2 <- IO.blocking(os.read.lines(eventsFile).toList)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(lines1.size, 1, s"每次出库只追加一条事件，got: $lines1")
      val ev = DocIndexConsumer.parseEventLine(lines1.head).getOrElse(fail(s"event must parse: ${lines1.head}"))
      assertEquals(ev.typ, FlowMapEventLog.ChainArchivedType)
      assertEquals(ev.chainId, Some("chain-n1"))
      assertEquals(ev.nodeId, "n1", "nodeId = 分量内 createdAt 最早节点（与链 id 派生同源）")
      assert(ev.summary.matches("""chain=chain-n1 archivedAt=\d+ members=2"""), s"summary: ${ev.summary}")
      // 顶层可选字段链 id 落盘（消费者免反解析）
      val raw = jsonParse(lines1.head).getOrElse(fail("event line must be valid JSON"))
      assertEquals(raw.hcursor.get[String]("chainId").toOption, Some("chain-n1"))
      assertEquals(batches, List("chain-n1.json"), "归档批文件 = 链 id")
      assertEquals(lines2.size, 1, "重复 tick 零重复事件")
  }
