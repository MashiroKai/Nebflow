package nebflow.gateway

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.Json
import munit.CatsEffectSuite
import nebflow.actor.ActorSystem
import nebflow.agent.{SharedResources, SpecResources, StubLlm}
import nebflow.core.project.*
import nebflow.service.ConfigService
import nebflow.shared.{NebflowLogger, PathUtil}

import scala.concurrent.duration.*

/**
 * 链控取消 —— **入口级**自证判据（chainview r3 · 复核打回项 R1 的收口）。
 *
 * ## 为什么需要这条 spec（它挡的是什么缝隙）
 *
 * r2 的 `ChainViewEngineSpec` 把「取消不可逆」验在**引擎原语**上（C4 直接调
 * `cancelChainAndRecord` ⇒ `resumeChain` 报 `CHAIN_CANCELLED`）。但**现役入口有两条**：
 *
 * | 入口 | 调用 | 台账记账 |
 * |---|---|---|
 * | REST `POST …/chains/<id>/cancel` | `engine.cancelChainAndRecord` | **有** |
 * | WS `{type:'chainCancel'}`（Flow Map 面板） | `engine.cancelChain`（r2 时） | **无** |
 *
 * 于是 r2 出货的产品语义是：从面板点取消 ⇒ 级联照跑（成员全 `cancelled`），但**链控台账零记**
 * ⇒ `chainControlOf` 回读 `active`（或先前暂停态 `paused`），且 `resumeChain` 返回语义错位的
 * `CHAIN_SINGLE_MEMBER`（而不是契约 §5.2 承诺的 `CHAIN_CANCELLED`）。复核位以自建探针实测复现
 * （PROBE-A/B/D，`.nebflow/evidence/20261001_111815_chainview-engine-verify-r2/`）。
 *
 * 根因是**判据面错位**：契约把 `cancelled` 表述为**产品级**恒真语义，而实现只在 REST 腿记账。
 * 本 spec 因此把验证面上移一层 —— 不再问「原语记不记账」，而是问「**经 WS 入口**取消之后，
 * 链控投影与 resume 的答案是不是契约承诺的那个」。
 *
 * ## 本 spec 断言什么
 *
 * - **W1**：经**真实** WS `chainCancel` 处理器取消 ⇒ 成员全终态 + 台账投影 `cancelled`；
 * - **W2**：同一入口下 `resumeChain` 必须报 `CHAIN_CANCELLED`（**不是** `CHAIN_SINGLE_MEMBER`）；
 * - **W3**：先 REST 暂停再经 WS 取消 ⇒ 投影必须是 `cancelled`（**不是** `paused`）——
 *   即三态投影优先级 `cancelled > paused > active` 在入口级也成立。
 *
 * 🔴 非空洞：W1 先钉住回帧 `ok=true` 且取消清单非空 ⇒ 处理器**确实跑了级联**；否则
 * 「什么都没做」的处理器会让 W2/W3 意外通过（单成员错误码恰好也是 Left）。
 * W2 因此额外断言错误码**逐字**为 `CHAIN_CANCELLED`。
 *
 * 🔴 只读运行：零 push / 零 tag / 零 VERSION；数据根隔离在 `target/`（`PathUtil.setDataRoot`），
 * 不触 real-HOME；不监听任何端口。
 */
class ChainViewWsEntrySpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 120.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-chainview-ws-entry"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot / "agents" / "general")
  os.write.over(
    tempRoot / "agents" / "general" / "agent.json",
    """{"name":"general","description":"chainview ws entry spec agent","tools":[],"category":"standalone"}"""
  )
  os.write.over(tempRoot / "agents" / "general" / "system.md", "# general\n")

  override def afterAll(): Unit = PathUtil.setDataRoot(originalRoot)

  override def beforeEach(context: munit.BeforeEach): Unit = ProjectRuntimeRegistry.clear
  override def afterEach(context: munit.AfterEach): Unit = ProjectRuntimeRegistry.clear

  // ── 席位（与 ChainViewEngineSpec 同款骨架）──────────────────────────

  private final case class Rig(rt: ProjectRuntime, ws: os.Path, res: SharedResources)

  private def mount(name: String, system: ActorSystem, res: SharedResources): IO[Rig] =
    val ws = tempRoot / s"ws-$name-${scala.util.Random.nextInt(100000)}"
    os.makeDir.all(ws)
    for
      store <- FlowMapStore.open(name, ws.toString)
      engine = new NodeEngine(
        store,
        system,
        res,
        wsSendFn = (_: Json) => IO.unit,
        workspace = ws.toString,
        rootSessionId = "nebula-root",
        projectName = name,
        emitEvent = (_: String, _: String, _: Json) => IO.unit,
        reportGateHold = Some(false)
      )
      pd = ProjectDef(
        name = name,
        workspace = ws.toString,
        agentFile = (ws / "AGENTS.md").toString,
        createdAt = System.currentTimeMillis()
      )
      rt = ProjectRuntime(pd, store, engine, system, res, None)
      _ <- ProjectRuntimeRegistry.register(rt)
    yield Rig(rt, ws, res)

  private def withRig[A](name: String)(f: Rig => IO[A]): IO[A] =
    val system = ActorSystem(s"chainview-ws-$name-${scala.util.Random.nextInt(100000)}")
    for
      res <- SpecResources.mkResources(system, tempRoot, new StubLlm().handle)
      rig <- mount(name, system, res)
      a <- f(rig)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
      _ <- ProjectRuntimeRegistry.clear
    yield a

  /** A → B 两成员链（chainId = `chain-n-a`）；链级原语要求 ≥2 成员。 */
  private def linearChain(rig: Rig): IO[Unit] =
    rig.rt.store
      .mutate(s =>
        s.copy(nodes = s.nodes ++ List(
          "n-a" -> NodeDef(
            id = "n-a",
            name = "n-a",
            agent = "general",
            status = NodeLifecycle.Pending,
            out = List(OutEdge("n-b")),
            createdAt = 1000L
          ),
          "n-b" -> NodeDef(
            id = "n-b",
            name = "n-b",
            agent = "general",
            status = NodeLifecycle.Pending,
            in = List("n-a"),
            createdAt = 2000L
          )
        ).toMap))
      .void

  private def statusOf(rig: Rig, id: String): IO[String] =
    rig.rt.store.getNode(id).map(_.map(_.status).getOrElse("<missing>"))

  // ── 真实 WS 入口的分发面 ────────────────────────────────────────────

  /**
   * 构造一个**只喂给 `chainCancel` 处理器**的 `WsDispatchCtx`。
   *
   * 该处理器实际只取 `ctx.logger`（成员解析 / 状态判定 / 级联闭包全在引擎单点，见
   * `WsSessionChatHandlers.handleChainCancel` 头注）；其余 47 个依赖它是**碰不到**的。
   * 故此处按 test 域既有口径（`SpecResources` 的 `historyArchiver = null` 等）以 `null`
   * 占位 —— 🔴 这是**刻意的**：若将来有人让该腿去读 ctx 的其他成员，本测试会立刻 NPE
   * 报警，而不是静默用一个假依赖蒙混过去。
   */
  private def chatCtx(log: NebflowLogger): WsDispatchCtx =
    WsDispatchCtx(
      logger = log,
      sessionService = null,
      agentService = null,
      configService = ConfigService,
      configRef = null,
      sessionStore = null,
      sharedResources = null,
      mcpManager = null,
      sttServiceRef = null,
      textSearch = null,
      sessionTextBuffers = null,
      sessionThinkingBuffers = null,
      sessionTurnStarts = null,
      replayPendingAsksImpl = null,
      listAllPendingAsksImpl = null,
      applyPermissionUpgradeImpl = null,
      persistGlobalSafetyModeImpl = null,
      removeRootAgentImpl = null,
      stopTeamSessionActorsImpl = null,
      stopChildDelegateActorsImpl = null,
      forwardInteractionAnswerImpl = null,
      isRootScopeSessionImpl = null,
      compactThresholdInfoImpl = null,
      ensureAgentImpl = null,
      maybeSessionKickImpl = null,
      broadcastServerConfigImpl = null,
      persistThinkingConfigImpl = null,
      persistWorkScheduleImpl = null,
      persistMcpServerEnabledImpl = null,
      broadcastMcpServersUpdateImpl = null,
      sendAgentSessionListImpl = null,
      sendAgentSessionListByNameImpl = null,
      sendMemoryStatusImpl = null,
      sendToolsListImpl = null,
      expandTildeImpl = null,
      wsBrowseEventImpl = null,
      browseResultFrameImpl = null,
      browseErrorFrameImpl = null,
      browseEntryCapImpl = 0,
      resolveExplorerBaseRootImpl = null,
      admitWorkOrRefuseImpl = null,
      handleUserTextImpl = null,
      skipCurrentFreezeWindowImpl = null,
      executeAskImpl = null,
      executeSkillImpl = null,
      resolveRootSessionIdImpl = null,
      projectCreateBroadcastImpl = null
    )

  /** 经**真实** WS `chainCancel` 处理器取消链；返回它发出的全部回帧。 */
  private def wsChainCancel(rig: Rig, chainId: String): IO[List[Json]] =
    for
      frames <- Ref.of[IO, List[Json]](Nil)
      log = NebflowLogger.forName("chainview.ws.entry.spec")
      ctx = chatCtx(log)
      watch = new ExplorerWatchSession(_ => IO.unit, log, live = false)
      handler = WsDispatch.handlers("chainCancel")
      _ <- handler(
        ctx,
        s"""{"type":"chainCancel","chainId":"$chainId"}""",
        (j: Json) => frames.update(_ :+ j),
        watch
      )
      out <- frames.get
    yield out

  private def replyErrors(frames: List[Json]): List[String] =
    frames.collect {
      case f if f.hcursor.get[String]("type").toOption.contains("chainCancelResult") =>
        f.hcursor.get[String]("error").toOption.getOrElse("<none>")
    }

  // ── W1 + W2：WS 入口取消 ⇒ 台账记账 + resume 报 CHAIN_CANCELLED ─────

  test(
    "W1/W2 (R1): cancelling through the REAL WS chainCancel handler records the ledger — the control projection reads `cancelled` and resume is refused with CHAIN_CANCELLED"
  ) {
    withRig("w12") { rig =>
      for
        _ <- linearChain(rig)
        frames <- wsChainCancel(rig, "chain-n-a")
        sts <- List("n-a", "n-b").traverse(statusOf(rig, _))
        ctl <- rig.rt.engine.chainControlOf("chain-n-a")
        resumed <- rig.rt.engine.resumeChain("chain-n-a")
      yield
        // 非空洞前置：处理器**确实**跑了级联（否则下面的断言会因「什么都没做」而失真）
        val okFlags = frames.map(_.hcursor.get[Boolean]("ok").toOption)
        assertEquals(okFlags, List(Some(true)), s"the WS leg must report ok=true, got $frames")
        val cancelledIds = frames
          .flatMap(f => f.hcursor.get[List[Json]]("cancelled").toOption.getOrElse(Nil))
          .flatMap(_.hcursor.get[String]("id").toOption)
        assertEquals(
          cancelledIds.sorted,
          List("n-a", "n-b"),
          s"the WS leg must report the cascaded members as cancelled, got $frames"
        )
        assertEquals(
          sts,
          List(NodeLifecycle.Cancelled, NodeLifecycle.Cancelled),
          "cancelling via the WS entry must still leave every live member terminal"
        )
        // 🔴 R1 的核心：台账必须记账（r2 时这里回读 `active`）
        assertEquals(
          ctl.status,
          ChainLedger.StatusCancelled,
          s"the WS cancel entry must record the ledger (R1) — projection read '${ctl.status}', expected cancelled"
        )
        assert(
          ctl.cancelledAt.exists(_ > 0L),
          s"cancelledAt must be a positive epoch ms, got ${ctl.cancelledAt}"
        )
        // 🔴 R1 的对外可观测后果：契约 §5.2 承诺 CHAIN_CANCELLED（不是 CHAIN_SINGLE_MEMBER）
        assert(
          resumed.left.exists(_.startsWith(ChainCancelErrors.AlreadyCancelled)),
          s"resume after a WS-entry cancel must be refused with ${ChainCancelErrors.AlreadyCancelled} " +
            s"(contract §5.2), got $resumed"
        )
    }
  }

  // ── W3：先暂停再经 WS 取消 ⇒ 投影优先级 cancelled > paused ──────────

  test(
    "W3 (R1): cancelling via the WS entry after a REST pause must project `cancelled`, not the stale `paused`"
  ) {
    withRig("w3") { rig =>
      for
        _ <- linearChain(rig)
        paused <- rig.rt.engine.pauseChain("chain-n-a")
        frames <- wsChainCancel(rig, "chain-n-a")
        ctl <- rig.rt.engine.chainControlOf("chain-n-a")
        resumed <- rig.rt.engine.resumeChain("chain-n-a")
      yield
        assertEquals(paused.map(_.status), Right(ChainLedger.StatusPaused), "precondition: the chain is paused")
        assertEquals(
          frames.map(_.hcursor.get[Boolean]("ok").toOption),
          List(Some(true)),
          s"the WS leg must still succeed on a paused chain, got $frames"
        )
        assertEquals(
          ctl.status,
          ChainLedger.StatusCancelled,
          s"the projection must move to cancelled (not stay at the stale `paused`), got '${ctl.status}'"
        )
        assert(
          resumed.left.exists(_.startsWith(ChainCancelErrors.AlreadyCancelled)),
          s"resume must be refused with ${ChainCancelErrors.AlreadyCancelled}, got $resumed"
        )
    }
  }

  // ── W4：错误面不回归（未知链仍是可行动拒绝）────────────────────────

  test(
    "W4: an unknown chain still gets the actionable CHAIN_NOT_FOUND refusal through the WS entry (no regression)"
  ) {
    withRig("w4") { rig =>
      for
        _ <- linearChain(rig)
        frames <- wsChainCancel(rig, "chain-n-nothing")
      yield
        assertEquals(frames.map(_.hcursor.get[Boolean]("ok").toOption), List(Some(false)), s"got $frames")
        assert(
          replyErrors(frames).exists(_.startsWith(ChainCancelErrors.NotFound)),
          s"an unknown chain must answer ${ChainCancelErrors.NotFound}, got $frames"
        )
    }
  }

end ChainViewWsEntrySpec
