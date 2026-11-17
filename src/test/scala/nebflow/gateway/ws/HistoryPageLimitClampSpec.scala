package nebflow.gateway.ws

import nebflow.gateway.*

import cats.effect.{IO, Ref}
import munit.CatsEffectSuite
import nebflow.actor.ActorSystem
import nebflow.agent.{SharedResources, SpecResources, StubLlm}
import nebflow.service.ConfigService
import nebflow.shared.{NebflowLogger, PathUtil, UiMessage}

import scala.concurrent.duration.*

/**
 * 刀三·爆炸保护 —— **WS 历史分页单页上限**的入口级反向验红（段D ③）。
 *
 * 裁定口径：**只做爆炸保护，不做流量控制**。`limit` 由客户端传入，荒谬值
 * （`100000`）只会把整段历史一次性序列化进**单个 WS 文本帧**（读盘 + 编码 +
 * 传输 + 前端解析四处同时拉爆）。故在 `getHistory` 入口夹上界
 * [[WsSessionChatHandlers.MaxHistoryPage]]（500），**默认值 50 不变**、`≤0`
 * 与坏值回落默认——无 TTL、无丢弃、无常态限流。
 *
 * ## 断言（走**真实**的 `WsDispatch.handlers("getHistory")`，不测替身）
 *
 * - **H1**：`limit=100000`（荒谬值）⇒ 回包 `messages.size == MaxHistoryPage`，
 *   且**正好是最近 500 条**（`offset = total - 500`，`hasMore = true`）——
 *   夹的是单帧载荷，不是历史内容（老消息一条没丢，仍可翻页取到）。
 * - **H2**：`limit` 缺省 ⇒ 仍是今日的默认 50（常规前端行为逐字节不变）。
 * - **H3**：`limit=0`（坏值）⇒ 回落默认 50，不是空页、不是 0 条。
 *
 * 🔴 非空洞：历史先铺 **600 条**（> 500），所以 H1 的 `== 500` 只能来自夹取
 * （未夹时应为 600）；H2/H3 的 `== 50` 也与 600 条总量区分开。
 *
 * 🔴 只读运行：数据根隔离在 `target/`（`PathUtil.setDataRoot`），不监听端口、
 * 不触 real-HOME；handler 的其余依赖按 test 域既有口径传 `null`。
 */
class HistoryPageLimitClampSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 300.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-history-page-clamp"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot)
  os.makeDir.all(tempRoot / "agents" / "general")
  os.write.over(
    tempRoot / "agents" / "general" / "agent.json",
    """{"name":"general","description":"history clamp spec agent","tools":[],"category":"standalone"}"""
  )
  os.write.over(tempRoot / "agents" / "general" / "system.md", "# general\n")

  override def afterAll(): Unit = PathUtil.setDataRoot(originalRoot)

  /** 只喂给 `getHistory` 处理器的 ctx：真实 `sessionStore` + 空转的待答回放。 */
  private def chatCtx(log: NebflowLogger, res: SharedResources): WsDispatchCtx =
    WsDispatchCtx(
      logger = log,
      sessionService = null,
      agentService = null,
      configService = ConfigService,
      configRef = null,
      sessionStore = res.sessionStore,
      sharedResources = res,
      mcpManager = null,
      sttServiceRef = null,
      textSearch = null,
      sessionTextBuffers = null,
      sessionThinkingBuffers = null,
      sessionTurnStarts = null,
      replayPendingAsksImpl = (_, _) => IO.unit,
      listAllPendingAsksImpl = null,
      applyPermissionUpgradeImpl = null,
      persistGlobalSafetyModeImpl = null,
      removeRootAgentImpl = null,
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
      deviceProvenanceImpl = null,
      skipCurrentFreezeWindowImpl = null,
      executeAskImpl = null,
      executeSkillImpl = null,
      resolveRootSessionIdImpl = null,
      projectCreateBroadcastImpl = null
    )

  /** 经**真实** WS `getHistory` 处理器请求一页；返回它发出的 historyPage 回帧。 */
  private def wsGetHistory(res: SharedResources, sid: String, limitField: String): IO[List[io.circe.Json]] =
    for
      frames <- Ref.of[IO, List[io.circe.Json]](Nil)
      log = NebflowLogger.forName("history.clamp.spec")
      ctx = chatCtx(log, res)
      watch = new ExplorerWatchSession(_ => IO.unit, log, live = false)
      handler = WsDispatch.handlers("getHistory")
      _ <- handler(
        ctx,
        s"""{"type":"getHistory","sessionId":"$sid"$limitField}""",
        (j: io.circe.Json) => frames.update(_ :+ j),
        watch
      )
      out <- frames.get
    yield out.filter(_.hcursor.get[String]("type").toOption.contains("historyPage"))

  private def withStore[A](f: SharedResources => IO[A]): IO[A] =
    val system = ActorSystem(s"history-clamp-${scala.util.Random.nextInt(100000)}")
    for
      res <- SpecResources.mkResources(system, tempRoot, new StubLlm().handle)
      _ <- res.sessionStore.load.handleErrorWith(_ => IO.unit)
      a <- f(res)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield a

  /** 铺 600 条历史（> MaxHistoryPage 500，> 默认 50）——三条断言的分母。 */
  // UI 消息现已写穿落盘（d6bdd7997 持久化族）——套件级 tempRoot 只在 class init 清一次，
  // 同套件内先跑的测试会把种子泄给后跑测试的冷装载。改为每测试独立 sessionId
  // （按键隔离），600 条/会话的断言口径不变。
  private def seed600(res: SharedResources, sid: String): IO[String] =
    val msgs = (1 to 600).toList.map(i => UiMessage.User(s"message $i"): UiMessage)
    res.sessionStore.appendUiMessages(sid, msgs).as(sid)

  private def pageOf(frames: List[io.circe.Json]): (Int, Long, Long, Boolean) =
    val f = frames.headOption.getOrElse(fail(s"the getHistory handler must answer a historyPage frame, got $frames"))
    (
      f.hcursor.downField("messages").as[List[io.circe.Json]].getOrElse(Nil).size,
      f.hcursor.get[Long]("total").getOrElse(-1L),
      f.hcursor.get[Long]("offset").getOrElse(-1L),
      f.hcursor.get[Boolean]("hasMore").getOrElse(false)
    )

  // ── H1：荒谬 limit ⇒ 夹到 MaxHistoryPage ───────────────────────────────

  test("H1: limit=100000 through the REAL WS getHistory handler returns exactly MaxHistoryPage messages (the newest ones, nothing dropped)") {
    withStore { res =>
      for
        sid <- seed600(res, "clamp-h1")
        // 无闸基线：存储层对同一荒谬 limit 会老老实实吐 600 条（offset=0）——
        // 所以下面 handler 回的 500 **只可能来自入口夹取**（去掉夹取即变红）。
        raw <- res.sessionStore.getHistoryPage(sid, 100000, None)
        frames <- wsGetHistory(res, sid, ""","limit":100000""")
      yield
        val (rawSize, rawTotal, rawOffset, _) = (raw._1.size, raw._2, raw._3, raw._4)
        assertEquals(clue(rawSize), 600, "baseline: the store itself is unclamped — 100000 would carry the whole history")
        assertEquals(rawTotal, 600)
        assertEquals(rawOffset, 0, "baseline offset is 0, not the clamped page's offset")
        val (size, total, offset, hasMore) = pageOf(frames)
        assertEquals(size, WsSessionChatHandlers.MaxHistoryPage, "an absurd limit must be clamped to the page cap")
        assert(
          size <= WsSessionChatHandlers.MaxHistoryPage,
          s"the frame must never carry more than MaxHistoryPage messages, got $size"
        )
        assertEquals(total, 600L, "the total count is untouched — clamping is about frame payload, not history")
        assertEquals(offset, 600L - WsSessionChatHandlers.MaxHistoryPage, "the page is the NEWEST 500 (no hole, no drop)")
        assert(hasMore, "older messages are still reachable by paging (hasMore=true)")
    }
  }

  // ── H2：缺省 limit ⇒ 默认 50（今日行为不变）───────────────────────────

  test("H2: omitting limit keeps the historical default of 50 (no behaviour change for the normal frontend)") {
    withStore { res =>
      for
        sid <- seed600(res, "clamp-h2")
        frames <- wsGetHistory(res, sid, "")
      yield
        val (size, total, offset, hasMore) = pageOf(frames)
        assertEquals(size, WsSessionChatHandlers.DefaultHistoryPage, "the default page size must stay 50")
        assertEquals(total, 600L)
        assertEquals(offset, 550L)
        assert(hasMore)
    }
  }

  // ── H3：坏值（0）⇒ 回落默认，不是空页 ────────────────────────────────

  test("H3: limit=0 falls back to the default page size (a bad client value neither empties the page nor enlarges it)") {
    withStore { res =>
      for
        sid <- seed600(res, "clamp-h3")
        frames <- wsGetHistory(res, sid, ""","limit":0""")
      yield
        val (size, _, _, _) = pageOf(frames)
        assertEquals(size, WsSessionChatHandlers.DefaultHistoryPage, "≤0 must fall back to the default, never to an empty page")
    }
  }

end HistoryPageLimitClampSpec
