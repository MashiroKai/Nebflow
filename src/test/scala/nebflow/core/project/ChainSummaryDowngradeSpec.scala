package nebflow.core.project

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import fs2.Stream
import io.circe.Json
import munit.CatsEffectSuite
import nebflow.actor.{ActorSystem, Behaviors, Behavior}
import nebflow.actor.{AgentCommand, AgentKind, AgentRecord}
import nebflow.agent.{AgentLibrary, SharedResources}
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ThinkingConfig}
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, PathUtil, StreamChunk}

import scala.concurrent.duration.*

/**
 * 全降级列表态批（2026-09-16，作者裁定「**全部降级列表态**」）· 链级摘要
 * **零投主对话**的红绿腿与台账腿。
 *
 * 判据（写死到可机械核，改前腿同文件可跑 —— 把本文件原样放进改前树跑，J2 应**红**）：
 *
 *  - **J2 零横幅投递**：`ProjectActor.TtlTick` 走完生产归档腿（sweep → 批账本 →
 *    降级登记）后，root 会话收到的 `AgentCommand.ImmediateInput` 中
 *    `source == Some("chain")` 的条数 == **0**。root 会话用**记录式 sink actor** 表示
 *    （`agentRegistry("nebula-root")` 指向它）——它是 `AgentActor` 的替身：链件若仍投根，
 *    必然作为一条 `ImmediateInput` 落进 sink（改前树读数 >0，见批报告 J7 节）。
 *    「不进 LLM 上下文」同源成立：`ImmediateInput` 是 `AgentActor.pendingImmediateInputs`
 *    → `immediateMessages` → LLM 请求 messages 的**唯一**入口，0 条即 0 上下文行。
 *  - **J5/R-9 台账**：降级登记后批文件 `summarySentAt` **置位**（`summaryLedgerOn` 照旧
 *    为真）⇒ 二次 tick 候选为空（恰一次，无每拍重算 / 无重复降级）。
 *  - **J4 状态段三元**（`FlowMapStore.renderChainSummary`）：全 cancelled（无 failed）链
 *    的 `eventType == cancelled`（改前二值口径 ⇒ `completed`，**本用例在改前树红**）。
 *
 * 🔴 **打桩面申报**：本 spec 不起 gateway、不跑真 LLM、不物化真 `AgentActor`
 * （sink 替身 + `NoopLlm`）；端到端读数（隔离实例 + 真 UI）另见批证据
 * `.nebflow/evidence/20260916_chainlist/impl/`。本 spec 的结论面 = **机制**（投递腿是否
 * 产生 root 注入 / 台账是否置位 / 状态段取值），不含呈现形态。
 */
class ChainSummaryDowngradeSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 180.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-chainlist-downgrade"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot / "agents" / "general")

  os.write.over(
    tempRoot / "agents" / "general" / "agent.json",
    """{"name":"general","description":"chainlist downgrade spec agent","tools":[],"category":"standalone"}"""
  )
  os.write.over(tempRoot / "agents" / "general" / "system.md", "# general\n")

  override def afterAll(): Unit = PathUtil.setDataRoot(originalRoot)

  private val RootSid = "nebula-root"

  private class NoopLlm extends LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new RuntimeException("send not expected"))

    def sendStream(
      req: LlmRequest,
      onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] =
      Stream(StreamChunk.TextDelta("ok"), StreamChunk.Done(None, None))

  private def recordingActor(record: Ref[IO, List[AgentCommand]]): Behavior[AgentCommand] =
    def loop: Behavior[AgentCommand] =
      Behaviors.receiveMessage[AgentCommand](cmd => record.update(_ :+ cmd).as(loop))
    loop

  private def mkResources(
    system: ActorSystem,
    tmp: os.Path,
    llm: LlmHandle[IO],
    registry: Ref[IO, Map[String, AgentRecord]]
  ): IO[SharedResources] =
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
      voiceMutedRef = voiceMuted,
      agentRegistry = registry
    )

  /** 挂项目 + 起生产 `ProjectActor`（TtlTick 入口同生产）；root 会话 = 记录式 sink。 */
  private def fixture(
    tag: String,
    nodes: Map[String, NodeDef]
  ): IO[
    (
      os.Path,
      ActorSystem,
      nebflow.actor.ActorRef[ProjectActor.ProjectCommand],
      Ref[IO, List[AgentCommand]],
      FlowMapStore
    )
  ] =
    val ws = tempRoot / s"ws-$tag-${System.nanoTime()}"
    os.makeDir.all(ws)
    val system = ActorSystem(s"chainlist-$tag-${scala.util.Random.nextInt(100000)}")
    for
      received <- Ref.of[IO, List[AgentCommand]](Nil)
      sink <- system.spawn(recordingActor(received), s"sink-$tag-${scala.util.Random.nextInt(100000)}")
      registry <- Ref.of[IO, Map[String, AgentRecord]](
        Map(RootSid -> AgentRecord(RootSid, sink, AgentKind.Root, RootSid, None))
      )
      res <- mkResources(system, tempRoot, new NoopLlm, registry)
      store <- FlowMapStore.open(s"chainlist-$tag", ws.toString)
      engine = new NodeEngine(
        store,
        system,
        res,
        wsSendFn = (_: Json) => IO.unit,
        workspace = ws.toString,
        rootSessionId = RootSid,
        projectName = s"chainlist-$tag",
        emitEvent = (_: String, _: String, _: Json) => IO.unit,
        reportGateHold = Some(false)
      )
      pd = ProjectDef(
        name = s"chainlist-$tag",
        workspace = ws.toString,
        agentFile = (ws / "AGENTS.md").toString,
        createdAt = System.currentTimeMillis()
      )
      rt = ProjectRuntime(pd, store, engine, system, res, None)
      _ <- ProjectRuntimeRegistry.register(rt)
      _ <- store.mutate(s => s.copy(nodes = nodes))
      ref <- system.spawn(
        ProjectActor(ProjectActor.ProjectConfig(rt.project, rt.engine, system, res, RootSid)),
        s"proj-chainlist-$tag-${scala.util.Random.nextInt(100000)}"
      )
    yield (ws, system, ref, received, store)

    end for

  end fixture

  private def waitUntil(timeout: FiniteDuration, every: FiniteDuration = 100.millis)(cond: IO[Boolean]): IO[Unit] =
    def go(deadline: Long): IO[Unit] =
      cond.flatMap {
        case true => IO.unit
        case false =>
          if System.currentTimeMillis() >= deadline then IO.raiseError(new AssertionError("waitUntil: timeout"))
          else IO.sleep(every) >> go(deadline)
      }
    go(System.currentTimeMillis() + timeout.toMillis)

  /**
   * 投给 sink 的链级注入计数（判据 = `source == Some("chain")`；与
   * `FlowMapStore.ChainSummarySource` 同值，此处逐字写死防同源误判）。
   */
  private def chainInjections(received: Ref[IO, List[AgentCommand]]): IO[List[AgentCommand.ImmediateInput]] =
    received.get.map(_.collect {
      case i: AgentCommand.ImmediateInput if i.source.contains("chain") => i
    })

  /** 注入面全量普查（逐条 `source` + 文本首段）——供断言消息与证据落盘。 */
  private def injectionCensus(received: Ref[IO, List[AgentCommand]]): IO[List[String]] =
    received.get.map(_.collect { case i: AgentCommand.ImmediateInput =>
      s"${i.source.getOrElse("-")}|${i.text.take(60).replace('\n', ' ')}"
    })

  /** 链横幅**内容**判据（防「改 source 名」型假绿）：正文头行 `[Chain '…' · N nodes` 不得出现。 */
  private def chainByContent(received: Ref[IO, List[AgentCommand]]): IO[List[String]] =
    received.get.map(_.collect {
      case i: AgentCommand.ImmediateInput if i.text.contains("[Chain ") => i.text.take(60)
    })

  // ── J2/J7 零横幅投递（改前树同文件应红）─────────────────────────────

  test("J2 零投主对话：TtlTick 出库 ≥2 成员链后 root 会话 0 条 source=chain 注入（改前树基线 >0）") {
    val base = System.currentTimeMillis() - 100_000
    val nodes = Map(
      // 链 ④′ 形态（今晚实战）：head → tail 同分量、全终态 ⇒ 可归档；≥2 成员 ⇒ 入摘要面
      "n-dg-a" -> NodeDef(
        id = "n-dg-a",
        name = "downgrade-head",
        agent = "general",
        status = NodeLifecycle.Completed,
        createdAt = base,
        completedAt = Some(base + 1),
        out = List(OutEdge("n-dg-b", Set(OutEdge.Pass), OutEdge.Result)),
        result = Some("head result line")
      ),
      "n-dg-b" -> NodeDef(
        id = "n-dg-b",
        name = "downgrade-tail",
        agent = "general",
        status = NodeLifecycle.Completed,
        createdAt = base + 10,
        completedAt = Some(base + 20),
        in = List("n-dg-a"),
        out = List(OutEdge.root),
        result = Some("tail result line")
      ),
      // 单成员孤立链（M2 负控）：不得产生任何投递
      "n-dg-lone" -> NodeDef(
        id = "n-dg-lone",
        name = "downgrade-lone",
        agent = "general",
        status = NodeLifecycle.Completed,
        createdAt = base + 30,
        completedAt = Some(base + 31),
        result = Some("lone result line")
      )
    )
    for
      (ws, system, ref, received, store) <- fixture("j2", nodes)
      _ <- (ref ! ProjectActor.ProjectCommand.TtlTick).void
      // 两拍：第一拍 sweep + 降级登记；第二拍走「候选为空」的幂等路径
      _ <- waitUntil(30.seconds)(store.archiveBatches.map(m => m.values.exists(_.summarySentAt.isDefined)))
      _ <- (ref ! ProjectActor.ProjectCommand.TtlTick).void
      _ <- IO.sleep(700.millis)
      injections <- chainInjections(received)
      byContent <- chainByContent(received)
      census <- injectionCensus(received)
      // 读数落盘（证据面）：root 会话（sink 替身）收到的注入面全量普查——本批后
      // 应为「零链横幅 + 节点级腿照旧」。
      _ <- IO(println(s"[chainlist-probe] root-injection census (source|text-head) = $census"))
      _ <- IO(
        println(
          s"[chainlist-probe] chain-flavored injections by source = ${injections.size}, by content = ${byContent.size}"
        )
      )
      metas <- store.archiveBatches
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
      _ <- IO {
        // ── J2 绿锚 ──
        assertEquals(injections, Nil, s"链级摘要必须零投主对话（实得 ${injections.size} 条 source=chain 注入；改前树此处 = 1 条）；注入面普查=$census")
        assertEquals(byContent, Nil, s"链横幅正文（`[Chain '…' · N nodes`）不得以任何 source 名达到 root（防改 source 型假绿）：$byContent")
        // 节点级腿**不受本批影响**（J3 绿锚：非链源的注入照旧存在/照旧缺席，本 fixture 上报全量普查）
        assert(census.forall(!_.contains("[Chain ")), s"普查中不得出现链横幅：$census")
        // ── J5/R-9 台账（≥2 成员链的批账本照记；单成员链不入摘要面）──
        val chainMeta = metas.getOrElse("chain-n-dg-a", fail(s"归档批缺链 chain-n-dg-a：${metas.keys.toList}"))
        assert(chainMeta.summaryLedgerOn, "summaryLedgerOn 照旧为真（账本启用）")
        assert(chainMeta.summarySentAt.isDefined, "R-9：降级登记 ⇒ 批文件 summarySentAt 置位")
        // M2 负控：单成员孤立链**照常归档**（批账本条目在），但**不进摘要面**
        // ⇒ 其 summarySentAt 恒缺席（从未被降级登记腿选中）
        val loneMeta = metas.getOrElse("chain-n-dg-lone", fail("M2：单成员链照常出库归档（批账本条目应在）"))
        assertEquals(loneMeta.summarySentAt, None, "M2：单成员链不入摘要面 ⇒ 无 summarySentAt（降级登记腿只处理 ≥2 成员链）")
        // ── 归档事实照旧（降级 ≠ 删除）：批文件 + 归档区实存 ──
        assert(os.exists(ws / ".nebflow" / FlowMapStore.ArchiveDirName / "chain-n-dg-a.json"), "归档批文件照写（承载面数据源仍在）")
        assert(ws.toString.nonEmpty)
      }
    yield ()
    end for
  }

  // ── J4 状态段三元（改前树同文件应红）────────────────────────────────

  test("J4 状态段三元：全 cancelled（无 failed）链 eventType=cancelled（改前二值口径 = completed）") {
    val base = System.currentTimeMillis() - 50_000
    val storeName = s"chainlist-j4-${System.nanoTime()}"
    val ws = tempRoot / s"ws-j4-${System.nanoTime()}"
    os.makeDir.all(ws)
    for
      store <- FlowMapStore.open(storeName, ws.toString)
      _ <- store.mutate { s =>
        s.copy(nodes =
          Map(
            "n-cx-a" -> NodeDef(
              id = "n-cx-a",
              name = "cancel-a",
              agent = "general",
              status = NodeLifecycle.Cancelled,
              createdAt = base,
              completedAt = Some(base + 1),
              out = List(OutEdge("n-cx-b", Set(OutEdge.Pass), OutEdge.Result)),
              result = Some("cancelled a")
            ),
            "n-cx-b" -> NodeDef(
              id = "n-cx-b",
              name = "cancel-b",
              agent = "general",
              status = NodeLifecycle.Cancelled,
              createdAt = base + 10,
              completedAt = Some(base + 20),
              in = List("n-cx-a"),
              out = List(OutEdge.root),
              result = Some("cancelled b")
            )
          )
        )
      }
      _ <- store.sweepCompletedChainsDetailed(System.currentTimeMillis())
      batch <- store.chainSummaryBatch(FlowMapStore.ChainSummaryMaxPerRound)
      _ <- IO {
        val c = batch._1.headOption.getOrElse(fail("全 cancelled ≥2 成员链必须入摘要候选（M2 只看成员数）"))
        assertEquals(c.cancelled, 2, s"两成员全 cancelled：$c")
        assertEquals(c.failed, 0)
        assertEquals(
          c.eventType,
          FlowMapStore.ChainSummaryEventCancelled,
          s"J4 根因修复：全 cancelled 链不得标 completed（改前值 = completed）"
        )
        // 状态段在引擎四段式里的取值（保留面读数；列表态另有 chainStatusOf 最坏态口径）
        assertEquals(
          NotificationHeader
            .header("chain", None, Some("NEBFLOW"), Some("NEBFLOW/chain-n-cx-a"), None, Some(c.eventType)),
          Some("CHAIN · NEBFLOW · CHAIN-N-CX-A · CANCELED"),
          "状态段 = CANCELED（非 COMPLETED）"
        )
        // 对照：含 failed 时仍优先标 failed（三元顺序 failed > cancelled）
        assert(c.text.contains("cancelled"), "摘要正文照旧含 cancelled 计数")
      }
    yield ()
    end for
  }

  test("J4 三元顺序：failed ∧ cancelled 混合 ⇒ failed（强提醒优先）") {
    val base = System.currentTimeMillis() - 40_000
    val ws = tempRoot / s"ws-j4b-${System.nanoTime()}"
    os.makeDir.all(ws)
    for
      store <- FlowMapStore.open(s"chainlist-j4b-${System.nanoTime()}", ws.toString)
      _ <- store.mutate { s =>
        s.copy(nodes =
          Map(
            "n-mx-a" -> NodeDef(
              id = "n-mx-a",
              name = "mixed-a",
              agent = "general",
              status = NodeLifecycle.Failed,
              createdAt = base,
              completedAt = Some(base + 1),
              // failed 成员需 `notifySentAt` 才满足归档资格（chainArchivable：
              // `Failed => n.notifySentAt.isDefined`，即分发器腿已上报）
              notifySentAt = Some(base + 2),
              result = Some("failed a")
            ),
            "n-mx-b" -> NodeDef(
              id = "n-mx-b",
              name = "mixed-b",
              agent = "general",
              status = NodeLifecycle.Cancelled,
              createdAt = base + 10,
              completedAt = Some(base + 20),
              in = List("n-mx-a"),
              result = Some("cancelled b")
            )
          )
        )
      }
      _ <- store.sweepCompletedChainsDetailed(System.currentTimeMillis())
      batch <- store.chainSummaryBatch(FlowMapStore.ChainSummaryMaxPerRound)
      _ <- IO {
        val c = batch._1.headOption.getOrElse(fail("混合终态 ≥2 成员链必须入摘要候选"))
        assertEquals(c.failed, 1)
        assertEquals(c.cancelled, 1)
        assertEquals(
          c.eventType,
          FlowMapStore.ChainSummaryEventFailed,
          "failed 优先（三元顺序 failed > cancelled > completed）"
        )
      }
    yield ()
    end for
  }

end ChainSummaryDowngradeSpec
