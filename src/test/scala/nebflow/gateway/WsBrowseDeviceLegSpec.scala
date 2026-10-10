package nebflow.gateway

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.Json
import munit.CatsEffectSuite
import nebflow.actor.ActorSystem
import nebflow.agent.{SharedResources, SpecResources, StubLlm}
import nebflow.service.ConfigService
import nebflow.shared.{NebflowLogger, PathUtil}

import java.nio.file.Files

/**
 * `wsBrowse.list` additive `device` 键（v2.1 §C/开放题⑤ · UI 面A 远端只读路径选择器
 * 的取数腿）**入口级契约钉**：
 *
 *   - W1 存量零回归：不带 `device` 的帧走既有本机腿（dirs-only + 排序 + wsBrowseList
 *     帧形），**无** `device` 回显键 —— 逐字节现状；
 *   - W2 `device` 帧 + 本机未启用 NebLink ⇒ **显式失败帧**（fail-closed，G.12 同款）：
 *     `error` 带可读原因 + `device` 回显；🔴 绝不静默落本机（断言错误帧带 device 键
 *     且错误语出自 NebLink 未启用，而非本机 "invalid path"）；
 *   - W3 缺 path 的 `device` 帧照旧零帧（入参门在 device 分派之前）。
 *
 * 🔴 只读运行：零端口、零 HOME 写（temp 目录隔离）；远端成功腿需真实对端，不在本
 * spec 射程（该腿由 RemoteExecutor 族 spec + 前端 mock 用例覆盖）。
 */
class WsBrowseDeviceLegSpec extends CatsEffectSuite:

  private val tempRoot: os.Path = os.Path(Files.createTempDirectory("nb-wsbrowse-device"))
  private val originalRoot = PathUtil.dataRoot

  private val browseDir: os.Path =
    val d = tempRoot / "browse-root"
    os.makeDir.all(d / "b-dir")
    os.makeDir.all(d / "a-dir")
    os.write(d / "f.txt", "x")
    d

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)
    os.remove.all(tempRoot)

  /** 只喂 `wsBrowse.list` 的 ctx（ChainViewWsEntrySpec.chatCtx 同款 null 占位纪律：
   *  腿读不到的依赖刻意置 null，越界即 NPE 报警，不静默蒙混）。 */
  private def browseCtx(res: SharedResources): WsDispatchCtx =
    WsDispatchCtx(
      logger = NebflowLogger.forName("wsbrowse.device.leg.spec"),
      sessionService = null,
      agentService = null,
      configService = ConfigService,
      configRef = null,
      sessionStore = null,
      sharedResources = res,
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
      expandTildeImpl = PathUtil.expandTilde,
      wsBrowseEventImpl = (
        evType: String,
        path: String,
        parent: Option[String],
        entries: List[String],
        err: Option[String]
      ) =>
        val fields = scala.collection.mutable.ListBuffer(
          "type" -> Json.fromString(evType),
          "path" -> Json.fromString(path),
          "home" -> Json.fromString(sys.props("user.home")),
          "entries" -> Json.arr(entries.map(Json.fromString)*)
        )
        parent.foreach(p => fields += "parent" -> Json.fromString(p))
        err.foreach(e => fields += "error" -> Json.fromString(e))
        Json.obj(fields.toList*),
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

  private def wsBrowseList(ctx: WsDispatchCtx, frame: String): IO[List[Json]] =
    for
      frames <- Ref.of[IO, List[Json]](Nil)
      handler = WsDispatch.handlers("wsBrowse.list")
      watch = new ExplorerWatchSession(_ => IO.unit, ctx.logger, live = false)
      _ <- handler(ctx, frame, (j: Json) => frames.update(_ :+ j), watch)
      out <- frames.get
    yield out

  test("W1 存量零回归：无 device 键 = 本机腿逐字节（dirs-only 排序，无 device 回显）") {
    for
      system <- IO(ActorSystem(s"wsbrowse-w1-${scala.util.Random.nextInt(100000)}"))
      res <- SpecResources.mkResources(system, tempRoot, new StubLlm().handle)
      frames <- wsBrowseList(
        browseCtx(res),
        s"""{"type":"wsBrowse.list","path":"${browseDir.toString}"}"""
      )
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(frames.length, 1, "本机腿恰好回一帧")
      val f = frames.head
      assertEquals(f.hcursor.get[String]("type").toOption, Some("wsBrowseList"))
      assertEquals(
        f.hcursor.downField("entries").as[List[String]].toOption,
        Some(List("a-dir", "b-dir")),
        "本机腿语义：仅目录、排序（文件不列）"
      )
      assert(
        f.hcursor.downField("device").focus.isEmpty,
        "存量帧不得新增 device 键（老前端零感知）"
      )
      assertEquals(f.hcursor.downField("error").focus.isEmpty, true)
  }

  test("W2 device 帧 + NebLink 未启用 ⇒ 显式失败帧（fail-closed，绝不静默落本机）") {
    for
      system <- IO(ActorSystem(s"wsbrowse-w2-${scala.util.Random.nextInt(100000)}"))
      res <- SpecResources.mkResources(system, tempRoot, new StubLlm().handle)
      frames <- wsBrowseList(
        browseCtx(res.copy(neblinkService = None)),
        """{"type":"wsBrowse.list","path":"/definitely/not/a/real/dir","sessionId":"s1","device":"dev-x"}"""
      )
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(frames.length, 1, "远端腿恰好回一帧（显式失败，不是零响应）")
      val f = frames.head
      assertEquals(f.hcursor.get[String]("type").toOption, Some("wsBrowseList"))
      val err = f.hcursor.get[String]("error").toOption.getOrElse("<missing>")
      assert(
        err.contains("NebLink"),
        s"失败原因必须出自 NebLink 门（fail-closed），而非本机路径腿；got: $err"
      )
      assertEquals(
        f.hcursor.get[String]("device").toOption,
        Some("dev-x"),
        "错误帧回显 device（前端定位来源设备）"
      )
      assert(
        f.hcursor.downField("entries").as[List[String]].toOption.getOrElse(Nil).isEmpty,
        "失败帧零条目"
      )
  }

  test("W3 缺 path 的 device 帧照旧零帧（path 门在 device 分派之前，行为不变）") {
    for
      system <- IO(ActorSystem(s"wsbrowse-w3-${scala.util.Random.nextInt(100000)}"))
      res <- SpecResources.mkResources(system, tempRoot, new StubLlm().handle)
      frames <- wsBrowseList(
        browseCtx(res),
        """{"type":"wsBrowse.list","sessionId":"s1","device":"dev-x"}"""
      )
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield assertEquals(frames, Nil, "缺 path ⇒ 静默丢弃（现状语义）")
  }

end WsBrowseDeviceLegSpec
