package nebflow.core.project

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.parser.parse as jsonParse
import munit.CatsEffectSuite
import nebflow.actor.{ActorRef, ActorSystem, AgentCommand, AgentEvent, AgentKind, AgentRecord, Behavior, Behaviors}
import nebflow.agent.SharedResources
import nebflow.core.delegate.DelegateRegistry
import nebflow.core.tools.{ProjectTool, ToolContext}
import nebflow.llm.ModelCandidate
import nebflow.shared.{PathUtil, ThinkingConfig}

import java.security.MessageDigest
import scala.concurrent.duration.*

/**
 * Project unmount 三步序回归（N2 project-tool 批 2026-10-06 · S17「先停任务、文件保留」
 * + ⑩ 软硬分档）。
 *
 * == 验收面对照（本 spec 即逐条落点） ==
 *  1. '''unmount 三步序（停→移→零删逐断言）'''——test "unmount three-step …"：
 *    ①停（链级取消 / 死会话 running 收殓 / dispatcher 会话 / 归属 delegate 会话
 *    四腿 + 排空）②移（list 源头过滤 + registry 摘除 + project.json 两键）
 *    ③零删（无删除无移动：文件集不减、.nebflow 外逐字节不变——注意零删约束是
 *    「零删除零移动」，stop 步对 flow-map/台账/审计的**终态化写入**是合法副作用，
 *    且只发生在 `<ws>/.nebflow/` 内）。
 *  2. '''archive/unmount 语义分档'''——test "tiering …"：archive＝仅打标**不停任务**
 *    （节点仍 Running、运行时仍挂载、零删除零移动）；unmount＝先停后卸。
 *  3. '''startupMount 跳过'''——test "startup auto-mount skips …"：unmount 打标后
 *    `ProjectStore.list()` 源头过滤 ⇒ mountAll 零挂载（与面板 API 同源单点）。
 *  4. '''stop-before-remove（次序硬约束）'''——test "refuses to unmount …"：会话不灭
 *    ⇒ 排空超时 ⇒ Left 中止，project.json 零写、运行时仍在挂——「未停完不卸」。
 *  5. '''幂等 + 拒挂'''——test "idempotent …" / test "create face refuses …"：
 *    已卸载再卸 = no-op 回读；create 面对卸载项目显式拒绝（可行动指路两键）。
 *
 * == #1229 排除件核对锚点 ==
 * 项目管理/挂载面（本 spec 全部被测面：ProjectStore.list/archive/markUnmounted、
 * ProjectRuntimeRegistry.mountAll/unregister、Project 工具面）属**常轨能力**，
 * 不随 #1229 引擎族拆除（spec §2.3 #1229 排除件条款）——#1229 执行时以本头注 +
 * 本 spec 全绿为「勿误删」核对锚点。
 */
class ProjectUnmountSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 120.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-project-unmount"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot)

  override def afterAll(): Unit =
    ProjectRuntimeRegistry.clear
    PathUtil.setDataRoot(originalRoot)

  override def beforeEach(context: munit.BeforeEach): Unit = ProjectRuntimeRegistry.clear
  override def afterEach(context: munit.AfterEach): Unit = ProjectRuntimeRegistry.clear

  // ============================================================
  // Harness
  // ============================================================

  private def minimalResources(projectRoot: os.Path): SharedResources =
    new SharedResources(
      llm = null,
      dispatcher = null,
      sessionStore = null,
      projectRoot = projectRoot,
      thinkingConfigRef = Ref.unsafe[IO, ThinkingConfig](ThinkingConfig()),
      rateLimiter = null,
      fileChangeTracker = null,
      contextWindow = 100_000,
      agentLibrary = null,
      taskStore = null,
      historyArchiver = null,
      fileLockManager = null,
      sessionModelOverrides = Ref.unsafe[IO, Map[String, ModelCandidate]](Map.empty),
      providerRegistry = null,
      healthMonitor = null,
      actorSystem = null,
      voiceMutedRef = Ref.unsafe[IO, Boolean](false)
    )

  /** 建项目 + 挂载（rootSessionId 恒顶层根，与 startupMount 同口径）。 */
  private def mountProject(name: String, ws: os.Path, system: ActorSystem, res: SharedResources): IO[ProjectRuntime] =
    for
      _ <- ProjectStore.create(name, ws.toString, None, "")
      pd <- ProjectStore.load(name).map(_.getOrElse(throw new IllegalStateException(s"load $name failed")))
      rt <- ProjectRuntimeRegistry.mount(pd, system, res, None, "root-unmount")
    yield rt

  /** 直写 store 播一个节点（status 可指定；无在飞 fiber = 死会话形态）。 */
  private def seedNode(
    rt: ProjectRuntime,
    id: String,
    name: String,
    status: String,
    in: List[String] = Nil,
    createdAt: Long = System.currentTimeMillis()
  ): IO[Unit] =
    rt.store
      .mutate(s =>
        s.copy(nodes =
          s.nodes + (id -> NodeDef(
            id = id,
            name = name,
            agent = "general",
            task = None,
            in = in,
            status = status,
            createdAt = createdAt
          ))
        )
      )
      .void

  /** 用户内容件（零删断言面）：脚手架之外手造的文件（.nebflow 外逐字节比对用）。 */
  private def seedUserFiles(ws: os.Path): IO[Unit] =
    IO.blocking {
      os.makeDir.all(ws / "notes")
      os.write(ws / "notes" / "a.md", "keep me")
    }

  /** 相对路径 → sha256（文件集不减 + .nebflow 外逐字节不变的断言源）。 */
  private def shaTree(root: os.Path): Map[String, String] =
    if !os.exists(root) then Map.empty
    else os.walk(root).filter(os.isFile).map(p => p.relativeTo(root).toString -> sha256(p)).toMap

  private def sha256(p: os.Path): String =
    MessageDigest.getInstance("SHA-256").digest(os.read.bytes(p)).map("%02x" format _).mkString

  /** AgentRecord.ref 的哑会话 actor（unmount 停步只走 supervisorRef 通道，ref 不被触）。 */
  private def silentAgentRef(system: ActorSystem, name: String): IO[ActorRef[AgentCommand]] =
    def loop: Behavior[AgentCommand] = Behaviors.receiveMessage[AgentCommand](_ => IO.pure(loop))
    system.spawn(loop, s"silent-$name")

  /**
   * 登记一条会话（AgentRecord + supervisor actor）。`mkSupervisor` 收「留痕 Ref」造
   * supervisor 行为；返回该 Ref 供断言取消信号到达。
   */
  private def registerSession(
    agentRegistry: Ref[IO, Map[String, AgentRecord]],
    system: ActorSystem,
    sessionId: String,
    kind: AgentKind,
    project: Option[String],
    mkSupervisor: Ref[IO, List[String]] => Behavior[AgentEvent]
  ): IO[Ref[IO, List[String]]] =
    for
      received <- IO.ref(List.empty[String])
      sup <- system.spawn(mkSupervisor(received), s"sup-$sessionId")
      ref <- silentAgentRef(system, sessionId)
      now <- IO(System.currentTimeMillis())
      _ <- agentRegistry.update(
        _ + (sessionId -> AgentRecord(
          sessionId = sessionId,
          ref = ref,
          kind = kind,
          rootSessionId = "root-unmount",
          startedAt = now,
          supervisorRef = Some(sup),
          project = project
        ))
      )
    yield received

  /**
   * 幂等停会话的桩 supervisor：收 Cancelled → 留痕 + 清 registry 行（镜像真桥
   * 「Cancelled → 清 registry + 停 agent」的最小闭环）。
   */
  private def drainingSupervisor(
    agentRegistry: Ref[IO, Map[String, AgentRecord]],
    sessionId: String
  )(received: Ref[IO, List[String]]): Behavior[AgentEvent] =
    def loop: Behavior[AgentEvent] =
      Behaviors.receiveMessage[AgentEvent] {
        case AgentEvent.Cancelled(sid, reason) =>
          received.update(_ :+ s"$sid:$reason") *> agentRegistry
            .update(_ - sessionId)
            .as(Behaviors.stopped[AgentEvent])
        case _ => IO.pure(loop)
      }
    loop

  /** 永不退场的桩 supervisor：只留痕（stop-before-remove 中止用例的「会话不灭」）。 */
  private def undyingSupervisor(received: Ref[IO, List[String]]): Behavior[AgentEvent] =
    def loop: Behavior[AgentEvent] =
      Behaviors.receiveMessage[AgentEvent] {
        case AgentEvent.Cancelled(sid, reason) => received.update(_ :+ s"$sid:$reason").as(loop)
        case _ => IO.pure(loop)
      }
    loop

  private def projectJson(name: String): io.circe.Json =
    jsonParse(os.read(ProjectStore.projectJsonPath(name)))
      .getOrElse(throw new IllegalStateException("corrupt test project.json"))

  /** 摘掉两枚卸载键后的 project.json（手术式插键的对账基准）。 */
  private def stripUnmountKeys(j: io.circe.Json): io.circe.Json =
    j.mapObject(o => o.remove("unmounted").remove("unmountedAt"))

  // ============================================================
  // 1) 三步序：停（四腿）→ 移 → 零删（逐断言）
  // ============================================================

  test("unmount three-step: stop (chain/node/dispatcher/delegate) → remove (filter+registry) → zero delete") {
    val ws = tempRoot / "ws-3step"
    val system = ActorSystem(s"um-3step-${scala.util.Random.nextInt(100000)}")
    val res = minimalResources(ws)
    val t0 = System.currentTimeMillis()
    for
      rt <- mountProject("p-3step", ws, system, res)
      // 用户内容件（零删断言面）：AGENTS.md（脚手架）+ 手造文件。
      _ <- seedUserFiles(ws)
      // ①停的靶子：
      //  - 链：n-c1（Running 死会话）→ n-c2（pending，in=n-c1）⇒ 2 成员链 chain-n-c1
      _ <- seedNode(rt, "n-c1", "chain-head", NodeLifecycle.Running, createdAt = t0)
      _ <- seedNode(rt, "n-c2", "chain-tail", NodeLifecycle.Pending, in = List("n-c1"), createdAt = t0 + 1)
      //  - 孤立死会话 Running 节点（链腿 CHAIN_SINGLE_MEMBER → 节点腿 reap 兜底）
      _ <- seedNode(rt, "n-solo", "solo", NodeLifecycle.Running, createdAt = t0 + 2)
      //  - dispatcher 会话 + 归属 delegate 会话（桩桥收 Cancelled 即清行）
      _ <- registerSession(
        res.agentRegistry,
        system,
        "dispatcher-um01",
        AgentKind.Flow,
        Some("p-3step"),
        drainingSupervisor(res.agentRegistry, "dispatcher-um01")
      )
      _ <- IO.blocking {
        DelegateRegistry.put(
          DelegateRegistry.Record(
            id = "delegate-um01",
            executor = "nebflow",
            cwd = ws.toString,
            project = Some("p-3step"),
            taskId = None,
            parentSessionId = "root-unmount",
            title = "um fixture",
            createdAt = t0
          )
        )
      }
      delReceived <- registerSession(
        res.agentRegistry,
        system,
        "delegate-um01",
        AgentKind.Delegate,
        None,
        drainingSupervisor(res.agentRegistry, "delegate-um01")
      )
      dispRegistered <- res.agentRegistry.get.map(_.contains("dispatcher-um01"))
      _ = assert(dispRegistered, "fixture: dispatcher session must be registered before unmount")
      // 零删基线（挂载/播种之后的文件集合）：
      wsBefore = shaTree(ws)
      projBefore = shaTree(tempRoot / "projects" / "p-3step")
      jsonBefore = projectJson("p-3step")
      // ── 执行三步序 ──
      report <- ProjectUnmount.unmount("p-3step", res.agentRegistry, _ => IO.unit)
      snapAfter <- rt.store.snapshot
      listAfter <- ProjectStore.list()
      listAllAfter <- ProjectStore.listAll()
      registryAfter <- ProjectRuntimeRegistry.get("p-3step")
      regRowsAfter <- res.agentRegistry.get
      delGot <- delReceived.get
      wsAfter = shaTree(ws)
      projAfter = shaTree(tempRoot / "projects" / "p-3step")
      jsonAfter = projectJson("p-3step")
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      val r = report.getOrElse(throw new AssertionError(s"unmount must succeed: $report"))
      // ── ①停 ──
      assert(r.chainsCancelled.contains("chain-n-c1"), s"chain leg must cancel the active chain: ${r.chainsCancelled}")
      assertEquals(
        r.nodesCancelled.toSet,
        Set("n-solo"),
        "node leg sweeps only unchained Running (chain members n-c1 ride the chain leg's cascade — finality asserted below): " + r.nodesCancelled
      )
      // 🔴 重锚（2026-10-14 redfix 批，报告 #21）：03a23259c（2026-09-16）起链取消
      // = **一条聚合 dispatcher 注入**（chain-cancel leg spawn 新分发器会话
      // `dispatcher-<hex>`）+ 令 3 分发器空闲保活窗（ProjectActor.scala:124）使其
      // 活到 stop 步枚举 ⇒ 被一并取消入报告（本批实测 obtained =
      // List("dispatcher-um01","dispatcher-27fe04d0")）。行为链自洽
      // （spawn→stop 步收殓），严格单元素钉未随「注入 + 保活」两笔行为演化更新。
      // 重锚 = fixture 会话必在场 + 其余成员只允许注入 spawn 的 dispatcher-* 形态
      // （若未来注入腿撤除、单元素回归，本断言仍绿——过滤后断言防再脆）。
      assert(
        r.sessionsCancelled.contains("dispatcher-um01"),
        s"dispatcher leg must cancel the fixture dispatcher session: ${r.sessionsCancelled}"
      )
      assert(
        r.sessionsCancelled.forall(s => s == "dispatcher-um01" || s.startsWith("dispatcher-")),
        s"dispatcher leg may only carry the fixture session + chaincancel-injected dispatcher-* spawns: ${r.sessionsCancelled}"
      )
      assertEquals(r.delegatesCancelled, List("delegate-um01"), "delegate leg")
      assertEquals(snapAfter.nodes("n-c1").status, NodeLifecycle.Cancelled, "chain head finalized cancelled")
      assertEquals(snapAfter.nodes("n-c2").status, NodeLifecycle.Cancelled, "chain tail cascade-cancelled")
      assertEquals(snapAfter.nodes("n-solo").status, NodeLifecycle.Cancelled, "dead running reaped to cancelled")
      assert(delGot.exists(_.startsWith("delegate-um01:")), s"delegate supervisor must receive Cancelled: $delGot")
      // ── ②移 ──
      assert(
        !listAfter.exists(_.name == "p-3step"),
        "unmounted project must vanish from the list face (GET /projects + startupMount 同源)"
      )
      assert(listAllAfter.exists(_.name == "p-3step"), "definition stays on disk (listAll unchanged)")
      assert(registryAfter.isEmpty, "runtime must be unregistered")
      assertEquals(jsonAfter.hcursor.downField("unmounted").as[Boolean].toOption, Some(true), "marker key written")
      assert(jsonAfter.hcursor.downField("unmountedAt").as[Long].toOption.exists(_ > 0), "timestamp written")
      assertEquals(
        r.unmountedAt,
        jsonAfter.hcursor.downField("unmountedAt").as[Long].toOption.getOrElse(0L),
        "report carries the written timestamp"
      )
      assert(
        !regRowsAfter.contains("dispatcher-um01") && !regRowsAfter.contains("delegate-um01"),
        "sessions drained from the registry"
      )
      // ── ③零删（无删除无移动）──
      assertEquals(
        wsBefore.keySet -- wsAfter.keySet,
        Set.empty[String],
        "hard constraint: zero file deletions in the workspace"
      )
      val outsideBefore = wsBefore.filterNot(k => k._1.startsWith(".nebflow"))
      val outsideAfter = wsAfter.filterNot(k => k._1.startsWith(".nebflow"))
      assertEquals(
        outsideAfter,
        outsideBefore,
        "files outside .nebflow/ must be byte-identical (flow-map/ledger/audit writes are the stop step's legal inside-.nebflow side effects)"
      )
      assertEquals(
        projBefore.keySet,
        projAfter.keySet,
        "projects/<name>/ file set unchanged (marker written in place, nothing added/removed)"
      )
      // project.json = 原文 + 恰两键（其余字段逐字节语义稳定）：手术式插键的对账面。
      // 两侧同剥：create 序列化按 archived 先例为 Option.None 写显式 null（jsonBefore
      // 自带 unmounted/unmountedAt:null 位），剥键对账只关心「除两键外无漂移」。
      assertEquals(
        stripUnmountKeys(jsonAfter),
        stripUnmountKeys(jsonBefore),
        "project.json diff = exactly the two unmount keys"
      )
    end for
  }

  // ============================================================
  // 2) archive / unmount 语义分档（⑩ 软硬分档）
  // ============================================================

  test("tiering: archive = tag only (tasks keep running, runtime stays); unmount = stop first, then remove") {
    val wsSoft = tempRoot / "ws-tier-soft"
    val wsHard = tempRoot / "ws-tier-hard"
    val system = ActorSystem(s"um-tier-${scala.util.Random.nextInt(100000)}")
    val res = minimalResources(wsSoft)
    for
      rtSoft <- mountProject("p-tier-soft", wsSoft, system, res)
      rtHard <- mountProject("p-tier-hard", wsHard, system, res)
      _ <- seedNode(rtSoft, "n-soft", "soft", NodeLifecycle.Running)
      _ <- seedNode(rtHard, "n-hard", "hard", NodeLifecycle.Running)
      softBefore = shaTree(wsSoft)
      hardBefore = shaTree(wsHard)
      // 软档：archive 现语义不动——仅打标，**不停任务**、运行时**不拆**、零删除零移动。
      archivedSoft <- ProjectStore.archive("p-tier-soft")
      softSnap <- rtSoft.store.snapshot
      softMounted <- ProjectRuntimeRegistry.get("p-tier-soft")
      softList <- ProjectStore.list()
      softAfter = shaTree(wsSoft)
      // 硬档：unmount——先停后卸。
      hardReport <- ProjectUnmount.unmount("p-tier-hard", res.agentRegistry, _ => IO.unit)
      hardSnap <- rtHard.store.snapshot
      hardMounted <- ProjectRuntimeRegistry.get("p-tier-hard")
      hardList <- ProjectStore.list()
      hardAfter = shaTree(wsHard)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      val at = archivedSoft.getOrElse(throw new AssertionError(s"archive must succeed: $archivedSoft"))
      assert(at > 0, "archive returns a timestamp")
      assertEquals(
        softSnap.nodes("n-soft").status,
        NodeLifecycle.Running,
        "archive must NOT stop in-flight tasks (soft tier)"
      )
      assert(softMounted.isDefined, "archive must NOT tear down the runtime")
      assert(!softList.exists(_.name == "p-tier-soft"), "archive filters the list face (existing semantics)")
      assertEquals(softAfter, softBefore, "archive is zero-deletion zero-move (byte-for-byte)")
      val hard = hardReport.getOrElse(throw new AssertionError(s"unmount must succeed: $hardReport"))
      assert(hard.nodesCancelled.contains("n-hard"), "unmount stops the running node first (hard tier)")
      assertEquals(
        hardSnap.nodes("n-hard").status,
        NodeLifecycle.Cancelled,
        "node finalized before the mount face moved"
      )
      assert(hardMounted.isEmpty, "unmount removes the runtime")
      assert(!hardList.exists(_.name == "p-tier-hard"), "unmount filters the list face")
      assertEquals(hardBefore.keySet -- hardAfter.keySet, Set.empty[String], "unmount is zero-deletion (files kept)")
    end for
  }

  // ============================================================
  // 3) startupMount 跳过（列表过滤单点）
  // ============================================================

  test("startup auto-mount skips unmounted projects (list source filter shared with the panel)") {
    val ws = tempRoot / "ws-skip"
    val system = ActorSystem(s"um-skip-${scala.util.Random.nextInt(100000)}")
    val res = minimalResources(ws)
    for
      _ <- ProjectStore.create("p-skip", ws.toString, None, "")
      _ <- ProjectStore.markUnmounted("p-skip")
      projects <- ProjectStore.list()
      mounted <- ProjectRuntimeRegistry.mountAll(projects, "root-unmount", system, res)
      inRegistry <- ProjectRuntimeRegistry.get("p-skip")
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(!projects.exists(_.name == "p-skip"), "unmounted project must not reach mountAll (source filter)")
      assertEquals(mounted, 0, "startup auto-mount must skip unmounted projects")
      assert(inRegistry.isEmpty, "nothing mounted")
    end for
  }

  // ============================================================
  // 4) stop-before-remove：未停完不卸（次序硬约束的失败路径回归）
  // ============================================================

  test("refuses to unmount while a session survives the drain (mount face left untouched)") {
    val ws = tempRoot / "ws-abort"
    val system = ActorSystem(s"um-abort-${scala.util.Random.nextInt(100000)}")
    val res = minimalResources(ws)
    for
      rt <- mountProject("p-abort", ws, system, res)
      received <- registerSession(
        res.agentRegistry,
        system,
        "dispatcher-abort",
        AgentKind.Flow,
        Some("p-abort"),
        undyingSupervisor
      )
      projBefore = projectJson("p-abort")
      result <- ProjectUnmount.unmount("p-abort", res.agentRegistry, _ => IO.unit)
      stillMounted <- ProjectRuntimeRegistry.get("p-abort")
      listAfter <- ProjectStore.list()
      projAfter = projectJson("p-abort")
      got <- received.get
      _ <- res.agentRegistry.update(_ - "dispatcher-abort")
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(result.isLeft, s"an undying session must abort the unmount: $result")
      val err = result.swap.toOption.get
      assert(
        err.contains("drain") && err.contains("dispatcher-abort"),
        s"abort must name the drain and the survivor: $err"
      )
      assert(
        got.exists(_.startsWith("dispatcher-abort:")),
        s"stop step must have sent the cancel BEFORE removing: $got"
      )
      assert(stillMounted.isDefined, "abort keeps the runtime mounted (step 2 never ran)")
      assert(listAfter.exists(_.name == "p-abort"), "abort keeps the project on the list face")
      assertEquals(projAfter, projBefore, "abort writes nothing to project.json (no marker)")
    end for
  }

  // ============================================================
  // 5) 幂等重卸 + create 面拒挂（单程语义）
  // ============================================================

  test("idempotent re-unmount: already-unmounted definition returns a no-op report at the original timestamp") {
    val ws = tempRoot / "ws-idem"
    val system = ActorSystem(s"um-idem-${scala.util.Random.nextInt(100000)}")
    val res = minimalResources(ws)
    for
      _ <- ProjectStore.create("p-idem", ws.toString, None, "")
      markIdem <- ProjectStore.markUnmounted("p-idem")
      before = shaTree(ws)
      report <- ProjectUnmount.unmount("p-idem", res.agentRegistry, _ => IO.unit)
      after = shaTree(ws)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      val at = markIdem.getOrElse(throw new AssertionError(s"markUnmounted must succeed: $markIdem"))
      val r = report.getOrElse(throw new AssertionError(s"re-unmount must succeed: $report"))
      assert(r.alreadyUnmounted, "report must declare the no-op")
      assertEquals(r.unmountedAt, at, "idempotent read-back of the original timestamp")
      assertEquals(r.nodesCancelled, Nil, "no-op has zero side effects")
      assertEquals(before, after, "no-op touches nothing")
    end for
  }

  test("unmounted but untagged definition is refused without changing definition or workspace") {
    val ws = tempRoot / "ws-defined-not-mounted"
    val system = ActorSystem(s"um-defined-${scala.util.Random.nextInt(100000)}")
    val res = minimalResources(ws)
    for
      _ <- ProjectStore.create("p-defined-not-mounted", ws.toString, None, "")
      _ <- seedUserFiles(ws)
      beforeJson = projectJson("p-defined-not-mounted")
      beforeWs = shaTree(ws)
      beforeProjectDir = shaTree(tempRoot / "projects" / "p-defined-not-mounted")
      result <- ProjectUnmount.unmount("p-defined-not-mounted", res.agentRegistry, _ => IO.unit)
      afterJson = projectJson("p-defined-not-mounted")
      afterWs = shaTree(ws)
      afterProjectDir = shaTree(tempRoot / "projects" / "p-defined-not-mounted")
      mounted <- ProjectRuntimeRegistry.get("p-defined-not-mounted")
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(result.isLeft, "a definition without a runtime must not be reported as successfully unmounted")
      val err = result.swap.toOption.get
      assert(err.contains("defined but not mounted"), s"error explains the stale state: $err")
      assert(err.contains("nothing was changed"), s"error confirms no mutation: $err")
      assert(err.contains("remount"), s"error gives an actionable recovery route: $err")
      assertEquals(afterJson, beforeJson, "untagged definition remains unchanged; do not invent unmounted markers")
      assertEquals(afterWs, beforeWs, "workspace files remain byte-identical")
      assertEquals(afterProjectDir, beforeProjectDir, "registration directory files remain unchanged")
      assert(mounted.isEmpty, "no runtime is created")
    end for
  }

  test("create face refuses an unmounted project with the two-key pointer (single-way, archive-shape)") {
    val ws = tempRoot / "ws-refuse"
    val system = ActorSystem(s"um-refuse-${scala.util.Random.nextInt(100000)}")
    val res = minimalResources(ws)
    for
      _ <- mountProject("p-refuse", ws, system, res)
      _ <- ProjectUnmount.unmount("p-refuse", res.agentRegistry, _ => IO.unit)
      // 同 name + 同 workspace 幂等重挂路径 → 命中 unmounted 拒绝分支。
      result <- ProjectTool.call(
        io.circe.JsonObject(
          "name" -> io.circe.Json.fromString("p-refuse"),
          "workspace" -> io.circe.Json.fromString(ws.toString)
        ),
        ToolContext(
          projectRoot = tempRoot.toString,
          sharedResources = Some(res),
          actorSystem = Some(system),
          rootSessionId = Some("root-unmount"),
          sessionId = Some("root-unmount")
        )
      )
      stillGone <- ProjectStore.list().map(!_.exists(_.name == "p-refuse"))
      mountedStill <- ProjectRuntimeRegistry.get("p-refuse")
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(result.isLeft, s"create face must refuse an unmounted project: $result")
      val err = result.swap.toOption.get.message
      assert(err.contains("is unmounted"), s"refusal must name the state: $err")
      assert(err.contains("'unmounted'/'unmountedAt'"), s"refusal must point at the two keys: $err")
      assert(stillGone, "refusal leaves the list face untouched")
      assert(mountedStill.isEmpty, "refusal must not silently remount")
    end for
  }

  // ============================================================
  // 6) remount 往返 + 卸载态可见性 + 审计行（unmount-console 面板批 2026-11-02）
  // ============================================================

  /** 读 flow-map-events.jsonl 全行（审计断言面）。 */
  private def eventLines(ws: os.Path): List[String] =
    val f = ws / ".nebflow" / "flow-map-events.jsonl"
    if os.exists(f) then os.read.lines(f).toList else Nil

  test("remount roundtrip: unmount → remount clears the keys and mounts again (list + registry + auto-mount eligible)") {
    val ws = tempRoot / "ws-remount"
    val system = ActorSystem(s"um-remount-${scala.util.Random.nextInt(100000)}")
    val res = minimalResources(ws)
    val frames = Ref.unsafe[IO, List[io.circe.Json]](List.empty)
    for
      _ <- mountProject("p-remount", ws, system, res)
      _ <- seedUserFiles(ws)
      // 卸载（控制台语义：actor=console）
      report <- ProjectUnmount.unmount("p-remount", res.agentRegistry, _ => IO.unit, actor = "console")
      goneFromList <- ProjectStore.list().map(!_.exists(_.name == "p-remount"))
      unmountLines = eventLines(ws)
      // —— 往返：remount（校验定义 → 摘两键 → 挂载 → 帧 → 审计）——
      remount <- ProjectRemount.remount(
        "p-remount",
        system,
        res,
        "root-unmount",
        Some(json => frames.update(_ :+ json)),
        actor = "console"
      )
      backOnList <- ProjectStore.list().map(_.exists(_.name == "p-remount"))
      remounted <- ProjectRuntimeRegistry.get("p-remount")
      jsonAfter = projectJson("p-remount")
      remountLines = eventLines(ws)
      framesGot <- frames.get
      listNow <- ProjectStore.list()
      // 再自动挂载一遍（等价 startupMount 对 list() 的消费）= 幂等零新增
      mountedAgain <- ProjectRuntimeRegistry.mountAll(listNow, "root-unmount", system, res)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      val r = report.getOrElse(throw new AssertionError(s"unmount must succeed: $report"))
      assert(!r.alreadyUnmounted, "fixture: first unmount is the real one")
      assert(goneFromList, "unmount removed it from the list face")
      val m = remount.getOrElse(throw new AssertionError(s"remount must succeed: $remount"))
      assert(!m.alreadyMounted, "fresh remount is not the idempotent leg")
      assert(m.unmountedAt.isDefined, "report carries the original unmountedAt")
      assert(backOnList, "remount restores the list face (panel + startup auto-mount 同源)")
      assert(remounted.isDefined, "remount re-registers the runtime")
      assertEquals(
        jsonAfter.hcursor.downField("unmounted").as[Option[Boolean]].toOption.flatten,
        None,
        "unmount marker key removed"
      )
      assertEquals(
        jsonAfter.hcursor.downField("unmountedAt").as[Option[Long]].toOption.flatten,
        None,
        "unmount timestamp key removed"
      )
      // 审计两行：project-unmounted（actor=console）→ project-remounted（actor=console）
      assert(
        unmountLines.exists(l => l.contains("\"project-unmounted\"") && l.contains("unmounted by console")),
        s"unmount audit line with actor expected, got: ${unmountLines.mkString("|")}"
      )
      assert(
        remountLines.exists(l => l.contains("\"project-remounted\"") && l.contains("remounted by console")),
        s"remount audit line with actor expected, got: ${remountLines.mkString("|")}"
      )
      // 帧单点：projectRemounted 且 row 与 GET /api/projects 行同构（含 device 存储值）
      assert(framesGot.exists(_.hcursor.downField("type").as[String].toOption.contains("projectRemounted")), s"remount frame expected: $framesGot")
      assert(
        framesGot.exists(f =>
          f.hcursor.downField("row").downField("name").as[String].toOption.contains("p-remount") &&
            f.hcursor.downField("row").downField("device").as[String].toOption.contains("self")
        ),
        "remount frame row must carry the GET /projects row shape"
      )
      assertEquals(mountedAgain, 0, "remount already mounted it — startupMount over list() is idempotent")
  }

  test("remount refuses archived and unknown names (archive stays single-way; zero writes)") {
    val wsArch = tempRoot / "ws-remount-arch"
    val system = ActorSystem(s"um-remarch-${scala.util.Random.nextInt(100000)}")
    val res = minimalResources(wsArch)
    for
      _ <- ProjectStore.create("p-remarch", wsArch.toString, None, "")
      _ <- ProjectStore.archive("p-remarch")
      before = projectJson("p-remarch")
      arch <- ProjectRemount.remount("p-remarch", system, res, "root-unmount", None, actor = "console")
      unknown <- ProjectRemount.remount("p-nope", system, res, "root-unmount", None, actor = "console")
      mounted <- ProjectRuntimeRegistry.get("p-remarch")
      after = projectJson("p-remarch")
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(arch.isLeft, "archived project must not be revived by remount")
      assert(arch.swap.toOption.get.contains("archived"), s"refusal must name the state: $arch")
      assert(unknown.isLeft, "unknown name must be refused")
      assert(unknown.swap.toOption.get.contains("not found"), s"refusal must name the miss: $unknown")
      assert(mounted.isEmpty, "refused remount mounts nothing")
      assertEquals(after, before, "refusals write nothing")
  }

  test("remount is idempotent when the project is already mounted (no-op, keys untouched, no frame)") {
    val ws = tempRoot / "ws-remount-idem"
    val system = ActorSystem(s"um-remidem-${scala.util.Random.nextInt(100000)}")
    val res = minimalResources(ws)
    val frames = Ref.unsafe[IO, List[io.circe.Json]](List.empty)
    for
      _ <- mountProject("p-remidem", ws, system, res)
      before = projectJson("p-remidem")
      remount <- ProjectRemount.remount(
        "p-remidem",
        system,
        res,
        "root-unmount",
        Some(json => frames.update(_ :+ json)),
        actor = "console"
      )
      after = projectJson("p-remidem")
      framesGot <- frames.get
      stillMounted <- ProjectRuntimeRegistry.get("p-remidem")
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      val m = remount.getOrElse(throw new AssertionError(s"remount must succeed: $remount"))
      assert(m.alreadyMounted, "mounted project = idempotent no-op leg")
      assertEquals(after, before, "no-op writes nothing (marker keys absent both sides)")
      assertEquals(framesGot, Nil, "no-op emits no frame")
      assert(stillMounted.isDefined, "no-op keeps the runtime")
  }

  test("clearUnmounted is surgical: only the two keys disappear, everything else byte-stable") {
    val ws = tempRoot / "ws-clear"
    for
      _ <- ProjectStore.create("p-clear", ws.toString, None, "")
      _ <- ProjectStore.markUnmounted("p-clear")
      marked = projectJson("p-clear")
      unknown <- ProjectStore.clearUnmounted("p-clear2") // 不存在的项目 = Left（零写）
      cleared <- ProjectStore.clearUnmounted("p-clear")
      after = projectJson("p-clear")
      second <- ProjectStore.clearUnmounted("p-clear")
      stillEligible <- ProjectStore.list().map(_.exists(_.name == "p-clear"))
      _ <- ProjectStore.markUnmounted("p-clear") // 打标面在摘键后照常可用（对称往返）
      remarked = projectJson("p-clear")
    yield
      val at = cleared.getOrElse(throw new AssertionError(s"clear must succeed: $cleared")).getOrElse(
        throw new AssertionError("marked project must clear with a timestamp")
      )
      assert(at > 0, "clear returns a timestamp")
      assert(unknown.isLeft, "clearing an unknown project is a Left")
      // 摘键后 = 打标态 − 恰两键（strip 对账面与本文件 unmount 断言同款）
      assertEquals(after, stripUnmountKeys(marked), "clear removes exactly the two keys")
      val s2 = second.getOrElse(throw new AssertionError("second clear must succeed"))
      assert(s2.isEmpty, "second clear = Right(None) idempotent zero-write leg")
      assert(stillEligible, "cleared project is back on the list face")
      assertEquals(remarked.hcursor.downField("unmounted").as[Boolean].toOption, Some(true), "re-mark works after clear")
  }

  test("unmount appends a project-unmounted audit line naming the actor (tool leg default)") {
    val ws = tempRoot / "ws-audit"
    val system = ActorSystem(s"um-audit-${scala.util.Random.nextInt(100000)}")
    val res = minimalResources(ws)
    for
      _ <- mountProject("p-audit", ws, system, res)
      linesBefore = eventLines(ws).count(_.contains("\"project-unmounted\""))
      _ <- ProjectUnmount.unmount("p-audit", res.agentRegistry, _ => IO.unit) // actor 缺省 = tool
      linesAfter = eventLines(ws)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      val fresh = linesAfter.count(_.contains("\"project-unmounted\"")) - linesBefore
      assertEquals(fresh, 1, "exactly one project-unmounted audit line appended")
      assert(
        linesAfter.exists(l => l.contains("unmounted by tool") && l.contains("p-audit")),
        s"audit line must name actor + project: ${linesAfter.mkString("|")}"
      )
  }

  test("inFlight counts chains / running nodes / dispatcher+delegate sessions (read-only, stop-target parity)") {
    val ws = tempRoot / "ws-inflight"
    val system = ActorSystem(s"um-inflight-${scala.util.Random.nextInt(100000)}")
    val res = minimalResources(ws)
    val t0 = System.currentTimeMillis()
    for
      rt <- mountProject("p-inflight", ws, system, res)
      // 2 成员活动链 + 1 孤立 Running
      _ <- seedNode(rt, "n-f1", "chain-head", NodeLifecycle.Running, createdAt = t0)
      _ <- seedNode(rt, "n-f2", "chain-tail", NodeLifecycle.Pending, in = List("n-f1"), createdAt = t0 + 1)
      _ <- seedNode(rt, "n-solo", "solo", NodeLifecycle.Running, createdAt = t0 + 2)
      _ <- registerSession(
        res.agentRegistry,
        system,
        "dispatcher-infl",
        AgentKind.Flow,
        Some("p-inflight"),
        undyingSupervisor
      )
      _ <- IO.blocking {
        DelegateRegistry.put(
          DelegateRegistry.Record(
            id = "delegate-infl",
            executor = "nebflow",
            cwd = ws.toString,
            project = Some("p-inflight"),
            taskId = None,
            parentSessionId = "root-unmount",
            title = "infl fixture",
            createdAt = t0
          )
        )
      }
      _ <- registerSession(res.agentRegistry, system, "delegate-infl", AgentKind.Delegate, None, undyingSupervisor)
      counted <- ProjectUnmount.inFlight("p-inflight", res.agentRegistry)
      unknown <- ProjectUnmount.inFlight("p-never", res.agentRegistry)
      // 只读：计数后快照/会话登记零变化
      snapAfter <- rt.store.snapshot
      regAfter <- res.agentRegistry.get.map(_.keySet)
      _ <- res.agentRegistry.update(_ - "dispatcher-infl" - "delegate-infl")
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      val f = counted.getOrElse(throw new AssertionError("mounted project must count"))
      assertEquals(f.chains, List("chain-n-f1"), "active chain counted")
      assertEquals(f.runningNodes.toSet, Set("n-solo"), "unchained Running counted (chain member rides the chain leg)")
      assert(f.dispatcherSessions.contains("dispatcher-infl"), "dispatcher session counted")
      assertEquals(f.delegateSessions, List("delegate-infl"), "attributed delegate session counted")
      assertEquals(f.total, 4, "total = chains + unchained nodes + sessions (chain member rides the chain leg, no double count)")
      assert(unknown.isEmpty, "unmounted/unknown project = None (REST 404 leg)")
      assertEquals(
        snapAfter.nodes("n-f1").status,
        NodeLifecycle.Running,
        "inFlight is read-only: node untouched"
      )
      assert(regAfter.contains("dispatcher-infl") && regAfter.contains("delegate-infl"), "inFlight is read-only: sessions untouched")
  }

  // 归档态卸载定义的拒挂（create 面）不受 remount 引入影响 —— 单程语义的回归锚点
  test("create face still refuses an archived project after remount introduction (unchanged semantics)") {
    val ws = tempRoot / "ws-arch-refuse"
    val system = ActorSystem(s"um-archref-${scala.util.Random.nextInt(100000)}")
    val res = minimalResources(ws)
    for
      _ <- ProjectStore.create("p-archref", ws.toString, None, "")
      _ <- ProjectStore.archive("p-archref")
      result <- ProjectTool.call(
        io.circe.JsonObject(
          "name" -> io.circe.Json.fromString("p-archref"),
          "workspace" -> io.circe.Json.fromString(ws.toString)
        ),
        ToolContext(
          projectRoot = tempRoot.toString,
          sharedResources = Some(res),
          actorSystem = Some(system),
          rootSessionId = Some("root-unmount"),
          sessionId = Some("root-unmount")
        )
      )
      mounted <- ProjectRuntimeRegistry.get("p-archref")
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(result.isLeft, "archived create/remount-via-create must stay refused")
      assert(result.swap.toOption.get.message.contains("archived"), s"refusal names the state: $result")
      assert(mounted.isEmpty, "refusal mounts nothing")
  }

end ProjectUnmountSpec
