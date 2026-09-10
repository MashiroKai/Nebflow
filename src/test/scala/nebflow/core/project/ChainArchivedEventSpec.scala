package nebflow.core.project

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import fs2.Stream
import io.circe.Json
import io.circe.parser.parse as jsonParse
import munit.CatsEffectSuite
import nebflow.actor.ActorSystem
import nebflow.agent.{AgentLibrary, SharedResources}
import nebflow.core.PathUtil
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ThinkingConfig}
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, StreamChunk}

import java.nio.file.Files as NFiles
import java.nio.file.Paths as NPaths
import java.nio.file.attribute.PosixFilePermission as Perm
import java.util.Set as JSet

import scala.concurrent.duration.*

/**
 * 归档联动的**端到端接线**回归（P3 批 2026-09-10，spec §6.2；写路径接线 = 缺口归口
 * 补线批 2026-09-10）：ProjectActor TtlTick → `sweepCompletedChainsDetailed` 整链出库
 * → 同一出库动作后 ① 追加 `chain-archived` 审计事件（顶层 chainId + 结构化 summary）
 * ② **翻转两域 INDEX.md**（`<dataRoot>/docs` home 域 + `<workspace>/.nebflow/Spec` ws
 * 域，roots 单点 [[DocIndexConsumer.indexRootsFor]]）——条目 state `active → archived` +
 * 链块迁入「已归档链分区」，**走生产调用方（TtlTick）而非直接调 applyChainEvents**。
 *
 * 单测层（[[DocIndexConsumerSpec]]）覆盖事件契约与消费侧纯变换；本 spec 覆盖引擎侧
 * 接线——「sweep → 事件 → 归档批文件 → 索引翻转」四个事实在同一次 tick 内成立，且
 * 二次 tick / 兜底 catch-up 幂等（内容与 mtime 均不变）、被索引文档零触碰。
 *
 * 隔离：本套件把 `PathUtil.dataRoot` 钉到临时 home（beforeAll/afterAll 保存还原，
 * setDataRoot 先例同 [[nebflow.core.seed.SeedPluginReconcileSpec]]）——绝不写真实
 * `~/.nebflow/docs`。
 */
class ChainArchivedEventSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 120.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-chain-archived-event"

  private var prevRoot: os.Path = null
  private var home: os.Path = null

  override def beforeAll(): Unit =
    prevRoot = PathUtil.dataRoot
    home = os.Path(java.nio.file.Files.createTempDirectory("nb-chain-archived-home"))
    PathUtil.setDataRoot(home)

  override def afterAll(): Unit =
    PathUtil.setDataRoot(prevRoot)
    os.remove.all(home)

  private val ChainId = "chain-n1"

  /** 一域索引 fixture：活跃链分区（含 chain-n1 块 + 两条 state=active 条目）+
   * 空「已归档链分区」+ 无归属表（无链行，翻动不得波及其他行）。 */
  private def domainIndex(domain: String): String =
    s"""# $domain 文档索引
       |
       |绪言行。
       |
       |## 活跃链分区（active）
       |
       |### $ChainId · 链标题一 · completed · 2 节点
       |
       || time | doc | class | chain | role | source | state | confidence |
       ||---|---|---|---|---|---|---|---|
       || 2026-09-10T11:00:00+08:00 | ./20260910_110000_a.md | stage | $ChainId | head | engine | active | |
       || 2026-09-10T11:05:00+08:00 | ./20260910_110500_b.md | stage | $ChainId | tail | engine | active | |
       |
       |## 已归档链分区（archived）
       |
       |## 无归属（unattributed）
       |
       || time | doc | class | chain | state | root |
       ||---|---|---|---|---|---|
       || 2026-09-10T12:00:00+08:00 | ./20260910_120000_note.md | live |  | active | $domain |
       |""".stripMargin

  /** 造 `<root>/<域>/INDEX.md` + 两份被索引文档（返索引路径 + 其目录）。 */
  private def writeDomain(root: os.Path, domain: String): IO[(os.Path, os.Path)] =
    IO.blocking {
      val dir = root / domain
      os.makeDir.all(dir)
      os.write(dir / "20260910_110000_a.md", "doc a body\n")
      os.write(dir / "20260910_110500_b.md", "doc b body\n")
      val idx = dir / DocIndexConsumer.IndexFileName
      os.write(idx, domainIndex(domain))
      (idx, dir)
    }

  private def namesOf(dir: os.Path): IO[List[String]] =
    IO.blocking(os.list(dir).map(_.last).toList.sorted)

  /** 被索引文档的 mtime 与内容（零触碰断言用；不含 INDEX.md——它本来就该被改写）。 */
  private def docsOf(dirs: List[os.Path]): IO[List[(String, Long)]] =
    IO.blocking(dirs.flatMap(d => List(
      os.read(d / "20260910_110000_a.md"), os.read(d / "20260910_110500_b.md"))).zip(
      dirs.flatMap(d => List(os.mtime(d / "20260910_110000_a.md"), os.mtime(d / "20260910_110500_b.md")))))

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

  /** 起一个已挂载项目 + 已出库前态的 store/engine（两 Completed 节点成链 chain-n1）。 */
  private def fixture(tag: String): IO[(os.Path, ActorSystem, nebflow.actor.ActorRef[ProjectActor.ProjectCommand])] =
    val ws = tempRoot / s"ws-$tag-${System.nanoTime()}"
    os.makeDir.all(ws)
    val system = ActorSystem(s"charch-$tag-${scala.util.Random.nextInt(100000)}")
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
        s"proj-charch-$tag-${scala.util.Random.nextInt(100000)}")
    yield (ws, system, ref)

  test("TtlTick：整链出库 → chain-archived 事件 + 归档批文件 + 两域 INDEX.md 自动翻转（幂等）") {
    val homeDomain = "链路域A"
    val wsDomain = "链路域B"
    for
      (ws, system, ref) <- fixture("flip")
      // 两域 fixture：home 域 `<dataRoot>/docs/<域>/INDEX.md`，ws 域 `<ws>/.nebflow/Spec/INDEX.md`
      (homeIdx, homeDir) <- writeDomain(home / "docs", homeDomain)
      (wsIdx, wsDir) <- writeDomain(ws / ".nebflow" / "Spec", wsDomain)
      // roots 单点：两域固定计算（home 域 + ws 域）——前置断言（缺 ws 域即刻红，不靠超时）
      roots <- IO(DocIndexConsumer.indexRootsFor(ws.toString))
      _ <- IO(assertEquals(roots,
        List((home / "docs").toString, (ws / ".nebflow" / "Spec").toString),
        "roots 单点 = home 域 + ws 域（固定计算，无配置文件）"))
      homeBefore <- IO.blocking(os.read(homeIdx))
      wsBefore <- IO.blocking(os.read(wsIdx))
      docsBefore <- docsOf(List(homeDir, wsDir))
      namesBefore <- namesOf(homeDir) <* namesOf(wsDir)
      eventsFile = ws / ".nebflow" / FlowMapEventLog.FileName
      // 调用点隔离：先占掉本 workspace 的 10min 对账窗（minIntervalMs = 0 即用即claim），
      // 使下面 TtlTick 内的 tick 被节流 → 本用例的翻转只能来自「TtlTick 出库后 flip」
      // 这一条生产路径（两条路径都接线，不禁用会让变异验红失真）。占用发生在 fixture
      // 之后、出库之前：catch-up 此刻无链族事件 → 零扫描，对 fixture 无副作用。
      _ <- DocIndexConsumer.tick(ws.toString, minIntervalMs = 0).void
      // ── 生产路径：TtlTick（非直接调 applyChainEvents）──
      _ <- (ref ! ProjectActor.ProjectCommand.TtlTick).void
      _ <- waitUntil(30.seconds)(IO.blocking(os.exists(eventsFile) && os.read(eventsFile).contains("chain-archived")))
      _ <- waitUntil(30.seconds)(IO.blocking(os.read(homeIdx).contains("| archived |")))
      _ <- waitUntil(30.seconds)(IO.blocking(os.read(wsIdx).contains("| archived |")))
      lines1 <- IO.blocking(os.read.lines(eventsFile).toList)
      batches <- IO.blocking(os.list(ws / ".nebflow" / FlowMapStore.ArchiveDirName).map(_.last).toList.sorted)
      homeIdx1 <- IO.blocking(os.read(homeIdx))
      wsIdx1 <- IO.blocking(os.read(wsIdx))
      homeMtime1 <- IO.blocking(os.mtime(homeIdx))
      wsMtime1 <- IO.blocking(os.mtime(wsIdx))
      docsAfter <- docsOf(List(homeDir, wsDir))
      namesAfter <- namesOf(homeDir) <* namesOf(wsDir)
      // 对账报表：翻转落地 + 归档事实与索引一致 → clean → 不写报表（零 diff 不写盘）
      reportExists <- IO.blocking(os.exists(ws / ".nebflow" / "tmp" / DocIndexConsumer.ReportFileName))
      // ── 幂等 ①：二次 tick（链已出库 → swept 空 → 零调用）内容与 mtime 均不变 ──
      _ <- (ref ! ProjectActor.ProjectCommand.TtlTick).void
      _ <- IO.sleep(500.millis)
      homeIdx2 <- IO.blocking(os.read(homeIdx))
      homeMtime2 <- IO.blocking(os.mtime(homeIdx))
      // ── 幂等 ②：强制兜底 catch-up 窗（同一 applyChainEvents 重放）仍零 diff ──
      _ <- DocIndexConsumer.tick(ws.toString, minIntervalMs = 0).void
      homeIdx3 <- IO.blocking(os.read(homeIdx))
      homeMtime3 <- IO.blocking(os.mtime(homeIdx))
      wsIdx3 <- IO.blocking(os.read(wsIdx))
      wsMtime3 <- IO.blocking(os.mtime(wsIdx))
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(roots.size, 2, "roots 单点前置断言已在上方 for 内校验（缺 ws 域立刻红）")
      assertEquals(lines1.size, 1, s"每次出库只追加一条事件，got: $lines1")
      val ev = DocIndexConsumer.parseEventLine(lines1.head).getOrElse(fail(s"event must parse: ${lines1.head}"))
      assertEquals(ev.typ, FlowMapEventLog.ChainArchivedType)
      assertEquals(ev.chainId, Some(ChainId))
      assertEquals(ev.nodeId, "n1", "nodeId = 分量内 createdAt 最早节点（与链 id 派生同源）")
      assert(ev.summary.matches("""chain=chain-n1 archivedAt=\d+ members=2"""), s"summary: ${ev.summary}")
      // 顶层可选字段链 id 落盘（消费者免反解析）
      val raw = jsonParse(lines1.head).getOrElse(fail("event line must be valid JSON"))
      assertEquals(raw.hcursor.get[String]("chainId").toOption, Some(ChainId))
      assertEquals(batches, List(s"$ChainId.json"), "归档批文件 = 链 id")

      // ── 两域 INDEX.md 均由生产 tick 翻转（ws 域 = 本批新增根）──
      def assertFlipped(domain: String, content: String): Unit =
        val archSec = content.indexOf("## 已归档链分区（archived）")
        val blockPos = content.indexOf(s"### $ChainId")
        val liveSec = content.indexOf("## 无归属（unattributed）")
        assert(archSec > 0 && blockPos > archSec && blockPos < liveSec,
          s"[$domain] 链块必须迁入「已归档链分区」且在无归属分区之前:\n$content")
        assert(!content.substring(0, archSec).contains(s"### $ChainId"),
          s"[$domain] 活跃链分区不得再留链块:\n$content")
        val heading = content.linesIterator.find(_.startsWith(s"### $ChainId")).getOrElse(fail(s"[$domain] 链标题缺失"))
        assert(heading.matches("""\Q### chain-n1\E · .* · archived \d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}[+-]\d{2}:\d{2}$"""),
          s"[$domain] 归档分区标题须带归档时间戳，got: $heading")
        assertEquals(content.sliding("| archived |".length).count(_ == "| archived |"), 2,
          s"[$domain] 两条链条目 state 均翻 archived:\n$content")
        assert(content.contains("|  | active | "), s"[$domain] 无归属行不得被波及:\n$content")
      assertFlipped(homeDomain, homeIdx1)
      assertFlipped(wsDomain, wsIdx1)
      // 被索引文档与目录文件集零触碰（未新建分区/未自建索引/未改写文档）
      assertEquals(docsAfter, docsBefore, "被索引文档内容与 mtime 必须零改动")
      assertEquals(namesAfter, namesBefore, "目录文件集零变化（无新建/删除）")
      // 幂等：内容逐字节 + mtime 均不变（二次 TtlTick；链已出库 → 零调用）
      assertEquals(homeIdx2, homeIdx1, "二次 tick 内容零变化")
      assertEquals(homeMtime2, homeMtime1, "二次 tick mtime 不变（未写盘）")
      assertEquals(homeIdx3, homeIdx2, "兜底 catch-up 重放内容零变化")
      assertEquals(homeMtime3, homeMtime2, "兜底 catch-up 重放 mtime 不变（零 diff 不写盘）")
      assertEquals(wsIdx3, wsIdx1, "ws 域兜底重放内容零变化")
      assertEquals(wsMtime3, wsMtime1, "ws 域兜底重放 mtime 不变")
      assertEquals(reportExists, false, "翻转落地 + 事实一致 → 对账 clean → 不写报表（零 diff 不写盘）")
      assertEquals(lines1.size, 1, "重复 tick 零重复事件")
      // 原始 fixture 与翻转后内容确不相同（防「翻转没发生」假绿）
      assert(homeBefore != homeIdx1 && wsBefore != wsIdx1)
  }

  test("容错：INDEX.md 写盘失败（只读）→ 仅 WARN + 不炸 TtlTick + 归档不回滚 + 索引零变化") {
    val homeDomain = "链路域RO"
    val roPerms = JSet.of(Perm.OWNER_READ)
    val rwPerms = JSet.of(Perm.OWNER_READ, Perm.OWNER_WRITE, Perm.GROUP_READ, Perm.OTHERS_READ)
    for
      (ws, system, ref) <- fixture("ro")
      (homeIdx, _) <- writeDomain(home / "docs", homeDomain)
      // 写盘失败注入：INDEX.md 只读（os.write.over = open(WRITE)+TRUNCATE → EACCES）
      _ <- IO.blocking(NFiles.setPosixFilePermissions(NPaths.get(homeIdx.toString), roPerms))
      writable <- IO.blocking(NFiles.isWritable(NPaths.get(homeIdx.toString)))
      idxBefore <- IO.blocking(os.read(homeIdx))
      eventsFile = ws / ".nebflow" / FlowMapEventLog.FileName
      report = ws / ".nebflow" / "tmp" / DocIndexConsumer.ReportFileName
      _ <- IO(assertEquals(writable, false, "只读注入必须生效（非 root 环境）"))
      _ <- (ref ! ProjectActor.ProjectCommand.TtlTick).void
      // 对账在失败的翻转之后仍写出报表 = 失败被 fail-soft 吞掉、tick 链继续（不炸）
      _ <- waitUntil(30.seconds)(IO.blocking(os.exists(report)))
      idxAfter <- IO.blocking(os.read(homeIdx))
      lines <- IO.blocking(os.read.lines(eventsFile).toList)
      batches <- IO.blocking(os.list(ws / ".nebflow" / FlowMapStore.ArchiveDirName).map(_.last).toList.sorted)
      reportRaw <- IO.blocking(os.read(report))
      reportJson <- IO.fromEither(jsonParse(reportRaw))
      // 还原权限（临时 home 清理不受影响，但防残留只读件）
      _ <- IO.blocking(NFiles.setPosixFilePermissions(NPaths.get(homeIdx.toString), rwPerms))
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(idxAfter, idxBefore, "写盘失败 → 索引内容零变化（不写坏、不新建）")
      assertEquals(batches, List(s"$ChainId.json"), "归档不回滚（批文件已落盘）")
      assertEquals(lines.size, 1, "审计事件照常（事件追加先于翻转，best-effort 互不影响）")
      assert(reportRaw.contains("\"staleInIndex\""), reportRaw)
      assert(reportJson.hcursor.get[List[String]]("staleInIndex").toOption.exists(_.nonEmpty),
        s"未落地的翻转由对账检出 stale（人工可见）: $reportRaw")
      assertEquals(reportJson.hcursor.get[List[String]]("missingInIndex").toOption, Some(Nil),
        "链在索引里有条目 → 非 missing")
  }
