package nebflow.core.project

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.Json
import io.circe.parser.parse as jsonParse
import munit.CatsEffectSuite
import nebflow.actor.{ActorSystem, AgentCommand, AgentKind, AgentRecord, Behaviors}
import nebflow.agent.{SpecResources, StubLlm}
import nebflow.shared.PathUtil

import scala.concurrent.duration.*

/**
 * P1 **链级终态回执**（P0 dispatch-notify 批 2026-11-02，RCA §7-P1）验红/验绿夹具。
 *
 * 契约三面：
 *   ① 有 `chainId` ⇒ 恰一条 [[NodeEngine.ChainWedgedEventType]] 事件，summary 逐字含
 *      `chain=<id>` / `node=<被闸死的下游 id>` / `wedgedBy=<终态上游 id>`，顶层带
 *      `chainId` 键；**且** root 通道收到一条含 chainId 的回执（eventType=notice）。
 *   ② 同一 chainId 单发（第二次终态写点不再发）。
 *   ③ 无 `chainId` ⇒ 零 chain-wedged / 零新回执，既有 `barrier-blocked` 照旧。
 */
class ChainWedgedReceiptSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 180.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-chain-wedged"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot / "agents" / "general")
  os.write.over(
    tempRoot / "agents" / "general" / "agent.json",
    """{"name":"general","description":"chain-wedged spec agent","tools":[],"category":"standalone"}"""
  )
  os.write.over(tempRoot / "agents" / "general" / "system.md", "# general\n")

  override def afterAll(): Unit = PathUtil.setDataRoot(originalRoot)

  private def recorderBehavior(recorded: Ref[IO, List[AgentCommand]]): nebflow.actor.Behavior[AgentCommand] =
    lazy val b: nebflow.actor.Behavior[AgentCommand] =
      Behaviors.receiveMessage[AgentCommand](msg => recorded.update(_ :+ msg).as(b))
    b

  /** 挂载真实 NodeEngine + 一个假根会话（root 通道落点，捕获 ImmediateInput）。 */
  private def mountProject(
    name: String,
    ws: os.Path,
    system: ActorSystem,
    recorded: Ref[IO, List[AgentCommand]]
  ): IO[(ProjectRuntime, String)] =
    for
      res <- SpecResources.mkResources(system, tempRoot, new StubLlm().handle)
      store <- FlowMapStore.open(name, ws.toString)
      rootSid = s"cw-root-$name"
      rootRef <- system.spawn(recorderBehavior(recorded), s"cw-root-rec-$name")
      engine = new NodeEngine(
        store,
        system,
        res,
        wsSendFn = (_: Json) => IO.unit,
        workspace = ws.toString,
        rootSessionId = rootSid,
        projectName = name,
        emitEvent = (_: String, _: String, _: Json) => IO.unit,
        // 分发器通道在源头关窗（本 spec 主题 = root 通道回执）
        notifyTriggerOverride = Some((_: String) => IO.unit),
        reportGateHold = Some(false),
        // root 通道直投（不进打包窗 ⇒ 读数确定性）
        rootNotifyQuietMs = Some(0L)
      )
      _ <- res.agentRegistry.update(_ + (rootSid -> AgentRecord(rootSid, rootRef, AgentKind.Root, rootSid)))
      pd = ProjectDef(
        name = name,
        workspace = ws.toString,
        agentFile = (ws / "AGENTS.md").toString,
        createdAt = System.currentTimeMillis()
      )
      rt = ProjectRuntime(pd, store, engine, system, res, None)
      _ <- ProjectRuntimeRegistry.register(rt)
    yield (rt, rootSid)

  private def seed(store: FlowMapStore, n: NodeDef): IO[Unit] =
    store.mutate(s => s.copy(nodes = s.nodes + (n.id -> n))).void

  private def readEvents(ws: os.Path): IO[List[Json]] =
    IO.blocking(os.read(ws / ".nebflow" / FlowMapEventLog.FileName))
      .map(_.linesIterator.toList.filter(_.trim.nonEmpty).flatMap(l => jsonParse(l).toOption))
      .handleError(_ => Nil)

  private def typ(j: Json): String = j.hcursor.get[String]("type").getOrElse("")
  private def summary(j: Json): String = j.hcursor.get[String]("summary").getOrElse("")

  /** 有界轮询等 root 注入到达（`ref ! ImmediateInput` 是 fire-and-forget）。 */
  private def awaitImms(recorded: Ref[IO, List[AgentCommand]], min: Int, timeoutMs: Long = 10_000L) =
    def go(deadline: Long): IO[List[AgentCommand.ImmediateInput]] =
      recorded.get.map(_.collect { case m: AgentCommand.ImmediateInput => m }).flatMap { ms =>
        if ms.size >= min || System.currentTimeMillis() >= deadline then IO.pure(ms)
        else IO.sleep(25.millis) >> go(deadline)
      }
    go(System.currentTimeMillis() + timeoutMs)

  test("chain-wedged: a chain-owned downstream wedged by a terminal upstream emits ONE chain-wedged event (chain=/node=/wedgedBy=, top-level chainId) AND ONE root receipt carrying the chainId — the second terminal write point on the same chain emits nothing more") {
    val ws = tempRoot / "ws-wedged"
    os.makeDir.all(ws)
    val system = ActorSystem(s"cw-${scala.util.Random.nextInt(100000)}")
    val now = System.currentTimeMillis()
    for
      recorded <- Ref.of[IO, List[AgentCommand]](Nil)
      (rt, rootSid) <- mountProject("cw-wedged", ws, system, recorded)
      _ <- seed(
        rt.store,
        NodeDef(
          id = "n-a",
          name = "A-up",
          agent = "general",
          status = NodeLifecycle.Failed,
          result = Some("boom"),
          out = List(OutEdge("n-b"), OutEdge("n-c")),
          createdAt = now - 900_000L,
          completedAt = Some(now - 800_000L)
        )
      )
      _ <- seed(
        rt.store,
        NodeDef(
          id = "n-b",
          name = "B-down",
          agent = "general",
          status = NodeLifecycle.Pending,
          in = List("n-a"),
          chainId = Some("c-77"),
          createdAt = now - 800_000L
        )
      )
      _ <- seed(
        rt.store,
        NodeDef(
          id = "n-c",
          name = "C-down",
          agent = "general",
          status = NodeLifecycle.Pending,
          in = List("n-a"),
          chainId = Some("c-77"),
          createdAt = now - 800_000L
        )
      )
      // 第一次终态写点：两个同链下游同时被闸死 ⇒ 仍只发一条（按 chainId 单发）
      _ <- rt.engine.checkBarriersNow("n-a", cause = "failed")
      // 第二次终态写点（同链）：不得再发
      _ <- rt.engine.checkBarriersNow("n-a", cause = "failed")
      events <- readEvents(ws)
      imms <- awaitImms(recorded, 1)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      val wedged = events.filter(j => typ(j) == NodeEngine.ChainWedgedEventType)
      assertEquals(wedged.size, 1, s"exactly one chain-wedged event per chainId, got ${events.map(typ)}")
      val s0 = summary(wedged.head)
      assert(s0.contains("chain=c-77"), s"summary must carry chain=<id>: $s0")
      assert(s0.contains("node=n-b") || s0.contains("node=n-c"), s"summary must carry node=<wedged downstream id>: $s0")
      assert(s0.contains("wedgedBy=n-a"), s"summary must carry wedgedBy=<terminal upstream id>: $s0")
      assert(
        wedged.head.hcursor.get[String]("chainId").toOption.contains("c-77"),
        s"top-level chainId key expected, got ${wedged.head.noSpaces.take(300)}"
      )
      // 零回归面：barrier-blocked 照旧（每个被闸死下游一条，与改前一致）
      assertEquals(
        events.count(j => typ(j) == "barrier-blocked"),
        2,
        "barrier-blocked emission is unchanged (one per wedged downstream)"
      )
      // 上层回执：一条 ImmediateInput 到 root 会话，eventType=notice（监督通报档——
      // blocked 终态已退役，BLOCKED 标签不再产生），文本三要素齐备
      assertEquals(imms.size, 1, s"exactly one root receipt expected, got ${imms.map(_.eventType)}")
      val receipt = imms.head
      assertEquals(receipt.eventType, Some(DispatchNotify.NoticeEventType), "receipt status must be `notice` (supervision receipt)")
      assert(receipt.text.contains("chain-wedged"), s"receipt must say chain-wedged: ${receipt.text.take(300)}")
      assert(receipt.text.contains("c-77"), "receipt must carry the chainId")
      assert(receipt.text.contains("n-b") || receipt.text.contains("n-c"), "receipt must carry the wedged node id")
      assert(receipt.text.contains("A-up") || receipt.text.contains("n-a"), "receipt must name the terminal upstream")
      assert(receipt.text.contains("hand over"), "receipt must state the hand-over action")
      assert(receipt.text.contains("rewire"), "receipt must state the rewire action")
      assert(receipt.text.contains("abandon"), "receipt must state the abandon action")
      assert(rootSid.nonEmpty)
  }

  test("chain-wedged: a downstream with NO chainId produces zero chain-wedged events and zero root receipts (behaviour identical to before)") {
    val ws = tempRoot / "ws-nochain"
    os.makeDir.all(ws)
    val system = ActorSystem(s"cw-nc-${scala.util.Random.nextInt(100000)}")
    val now = System.currentTimeMillis()
    for
      recorded <- Ref.of[IO, List[AgentCommand]](Nil)
      (rt, _) <- mountProject("cw-nochain", ws, system, recorded)
      _ <- seed(
        rt.store,
        NodeDef(
          id = "n-a",
          name = "A-up",
          agent = "general",
          status = NodeLifecycle.Failed,
          result = Some("boom"),
          out = List(OutEdge("n-b")),
          createdAt = now - 900_000L,
          completedAt = Some(now - 800_000L)
        )
      )
      _ <- seed(
        rt.store,
        NodeDef(
          id = "n-b",
          name = "B-down",
          agent = "general",
          status = NodeLifecycle.Pending,
          in = List("n-a"),
          createdAt = now - 800_000L
        )
      )
      _ <- rt.engine.checkBarriersNow("n-a", cause = "failed")
      events <- readEvents(ws)
      imms <- recorded.get.map(_.collect { case m: AgentCommand.ImmediateInput => m })
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(
        events.count(j => typ(j) == NodeEngine.ChainWedgedEventType),
        0,
        s"no chain attribution ⇒ no chain-wedged event, got ${events.map(typ)}"
      )
      assertEquals(events.count(j => typ(j) == "barrier-blocked"), 1, "the pre-existing barrier-blocked line is unchanged")
      assertEquals(imms.size, 0, s"no chain attribution ⇒ no root receipt, got ${imms.map(_.eventType)}")
  }
