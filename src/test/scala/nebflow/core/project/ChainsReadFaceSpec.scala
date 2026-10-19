package nebflow.core.project

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import fs2.Stream
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.{ActorSystem, AgentKind, AgentRecord, AgentCommand}
import nebflow.agent.SharedResources
import nebflow.core.{RateLimiter, SessionStore}
import nebflow.core.delegate.{ChainStore, DelegateRegistry}
import nebflow.core.project.DelegateFlowHosts
import nebflow.core.tools.FileLockManager
import nebflow.llm.ModelCandidate
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, PathUtil, StreamChunk, ThinkingConfig}
import nebflow.shared.given

import scala.concurrent.duration.*

/**
 * A8 per-task 只读读面验收（N1 刀3 · 设计 v3 §3 A8 / §9；#1228 stub §四.3/.4 的唯一
 * 硬依赖落位）。HTTP 臂在 PresenceRoutes（`/api/chains*`），本 spec 直驱投影单点：
 *
 *  - C1 行投影：Record 原样键 + `live` 活性提示（agentRegistry 快照在册 = running）
 *    + 最新 `terminal-verdict` 判词（ready-to-merge 供给源——v3 §9 逐字，前端既有
 *    rowFromRegistry 推导不抢）；无在册会话/无判词 = 对应键缺席。
 *  - C2 链投影：已挂载宿主现读（mounted=true + 节点经 v3 §9 映射表 + ChainNode 形）；
 *    未挂载但盘面作用域在 = 探针只读（mounted=false，不落 hosts）；作用域目录不存在
 *    （外部执行器形态）= chain=null 降级；三行族 record 原样透传（适配层直接输入）。
 *  - C3 查无/非法键 = None（HTTP 404/400 面）；读面零写盘（探针不建目录）。
 */
class ChainsReadFaceSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 60.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-chains-read-face"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot / "sessions")

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)

  override def beforeEach(context: munit.BeforeEach): Unit =
    (ProjectRuntimeRegistry.clear *> DelegateFlowHosts.clear).unsafeRunSync()
    if os.exists(tempRoot / "delegates") then os.remove.all(tempRoot / "delegates")
    if os.exists(tempRoot / "chains") then os.remove.all(tempRoot / "chains")
    if os.exists(tempRoot / "projects") then os.remove.all(tempRoot / "projects")
    if os.exists(tempRoot / "c4b-real-ws") then os.remove.all(tempRoot / "c4b-real-ws")
    if os.exists(tempRoot / "c4b-alias-root") then os.remove.all(tempRoot / "c4b-alias-root")

  override def afterEach(context: munit.AfterEach): Unit =
    (ProjectRuntimeRegistry.clear *> DelegateFlowHosts.clear).unsafeRunSync()

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
      agentLibrary = new nebflow.agent.AgentLibrary(tempRoot / "agents"),
      taskStore = nebflow.core.task.FileTaskStore,
      historyArchiver = null,
      fileLockManager = fileLocks,
      sessionModelOverrides = modelOverrides,
      providerRegistry = null,
      healthMonitor = null,
      actorSystem = null,
      voiceMutedRef = voiceMuted
    )

  private class NullLlm:

    def handle: LlmHandle[IO] = new LlmHandle[IO]:
      def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new RuntimeException("send not expected"))
      def sendStream(
        req: LlmRequest,
        onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]]
      ): Stream[IO, StreamChunk] = Stream.empty

  private def record(
    id: String,
    taskId: Option[String],
    projectWorkspace: Option[String] = None
  ): DelegateRegistry.Record =
    DelegateRegistry.Record(
      id = id,
      executor = "nebflow",
      cwd = (tempRoot / "general").toString,
      project = None,
      taskId = taskId,
      parentSessionId = "nebula-root",
      title = "spec chain",
      createdAt = System.currentTimeMillis(),
      projectWorkspace = projectWorkspace.orElse(Some(tempRoot.toString))
    )

  private def agentRecord(sid: String): AgentRecord =
    AgentRecord(
      sessionId = sid,
      ref = null.asInstanceOf[nebflow.actor.ActorRef[AgentCommand]], // 读面只查键在册，零取用
      kind = AgentKind.Delegate,
      rootSessionId = sid
    )

  // ── C1 行投影 ───────────────────────────────────────────────────────────

  test(
    "C1: rows project the registry record with the live hint and the latest terminal-verdict; absent sources leave the keys out"
  ) {
    val liveRec = record("delegate-general-11aa22bb", Some("42"))
    val deadRec = record("delegate-general-22bb33cc", None)
    DelegateRegistry.put(liveRec)
    DelegateRegistry.put(deadRec)
    // 台账键 = keyFor（台账号优先）：verdict 行挂在 42 下。
    ChainStore.appendSync(
      "42",
      "terminal-verdict",
      io.circe.Json
        .obj("verdict" -> "ready-to-merge".asJson, "reason" -> "branch ready".asJson, "source" -> "self-report".asJson)
    )
    val registry = Map("delegate-general-11aa22bb" -> agentRecord("delegate-general-11aa22bb"))
    ChainsReadFace.rowsJson(registry).map { body =>
      val rows = body.hcursor.downField("rows").as[List[io.circe.Json]].toOption.getOrElse(Nil)
      assertEquals(rows.size, 2, "one row per registry record")
      val live =
        rows.find(_.hcursor.get[String]("id").toOption.contains(liveRec.id)).getOrElse(fail("live row missing"))
      assertEquals(
        live.hcursor.get[String]("title").toOption,
        Some("spec chain"),
        "record fields pass through verbatim"
      )
      assertEquals(
        live.hcursor.downField("live").get[String]("status").toOption,
        Some("running"),
        "registry presence ⇒ live.status hint (stub §四.4, A8 same face)"
      )
      assertEquals(
        live.hcursor.downField("verdict").get[String]("verdict").toOption,
        Some("ready-to-merge"),
        "latest terminal-verdict row supplies ready-to-merge (v3 §9 verbatim)"
      )
      assertEquals(live.hcursor.downField("verdict").get[String]("reason").toOption, Some("branch ready"))
      val dead =
        rows.find(_.hcursor.get[String]("id").toOption.contains(deadRec.id)).getOrElse(fail("dead row missing"))
      assert(
        dead.hcursor.downField("live").focus.isEmpty,
        "no registry presence ⇒ no live key (adapter keeps authority)"
      )
      // 无判词键：deadRec 的键 = delegateId 回落（keyFor）——42 行不属它。
      assert(dead.hcursor.downField("verdict").focus.isEmpty, "no verdict row on its own key ⇒ no verdict key")
      // 无 taskStatus 供给 = 键缺席（旧投影，缺键容忍）。
      assert(dead.hcursor.downField("taskStatus").focus.isEmpty, "no taskStatus supply ⇒ no taskStatus key")
    }
  }

  test(
    "C1b (#566): taskStatus supply lands verbatim on the matching row — the engine terminal authority for stale-row retirement"
  ) {
    val rec = record("delegate-general-33cc44dd", Some("52"))
    DelegateRegistry.put(rec)
    val statuses = Map(
      rec.id -> "failed",
      "delegate-unknown-ghost" -> "completed" // 查无行 = 零投影（不臆造）
    )
    ChainsReadFace.rowsJson(Map.empty, archived = false, taskStatuses = statuses).map { body =>
      val rows = body.hcursor.downField("rows").as[List[io.circe.Json]].toOption.getOrElse(Nil)
      val row = rows.find(_.hcursor.get[String]("id").toOption.contains(rec.id)).getOrElse(fail("row missing"))
      assertEquals(
        row.hcursor.get[String]("taskStatus").toOption,
        Some("failed"),
        "the engine task-row status rides the row verbatim (UI terminal authority)"
      )
    }
  }

  // ── C2 链投影 ───────────────────────────────────────────────────────────

  test(
    "C2: mounted scope projects FlowMap nodes through the v3 §9 status map; unmounted probes the disk read-only; missing dir degrades to null"
  ) {
    val ws = os.temp.dir(prefix = "nb-crf-c2-", deleteOnExit = false)
    val system = ActorSystem(s"crf-c2-${scala.util.Random.nextInt(1000000)}")
    val mountedId = "delegate-general-33cc44dd"
    val ghostId = "delegate-general-44dd55ee"
    // noDir：记录在册但作用域目录不存在（外部执行器形态）——null 降级腿。
    val noDirId = "delegate-general-55ee66ff"
    for
      res <- mkResources(system, NullLlm().handle)
      _ <- DelegateFlowHosts
        .mount(
          delegateId = mountedId,
          project = None,
          baseWorkspace = ws.toString,
          engineCwd = ws.toString,
          system = system,
          resources = res,
          wsSend = None,
          reportGateHold = Some(false),
          rootNotifyQuietMs = Some(0),
          destroyWindowMs = Some(0L)
        )
        .flatMap {
          case None => IO.raiseError(new AssertionError("host must mount"))
          case Some(h) =>
            h.store.mutate { s =>
              s.copy(nodes =
                s.nodes ++ Map(
                  "n-a" -> NodeDef(
                    id = "n-a",
                    name = "alpha",
                    agent = "general",
                    task = Some("first line of the task\nsecond"),
                    description = Some("short desc"),
                    deps = List("n-b"),
                    status = NodeLifecycle.Pending,
                    createdAt = 1L
                  ),
                  "n-b" -> NodeDef(
                    id = "n-b",
                    name = "beta",
                    agent = "general",
                    status = NodeLifecycle.Running,
                    createdAt = 2L
                  ),
                  "n-c" -> NodeDef(
                    id = "n-c",
                    name = "gamma",
                    agent = "general",
                    status = NodeLifecycle.Completed,
                    createdAt = 3L
                  ),
                  "n-d" -> NodeDef(
                    id = "n-d",
                    name = "delta",
                    agent = "general",
                    status = NodeLifecycle.Failed,
                    createdAt = 4L
                  )
                )
              )
            }.void
        }
      _ <- IO(DelegateRegistry.put(record(mountedId, None)))
      _ <- IO(DelegateRegistry.put(record(ghostId, None, projectWorkspace = Some(ws.toString))))
      _ <- IO(DelegateRegistry.put(record(noDirId, None, projectWorkspace = Some(ws.toString))))
      // ghost：盘面有作用域目录但宿主未挂载（探针腿）——最小 flow-map.json。
      _ <- IO.blocking {
        val dir = ws / ".nebflow" / "flow-maps" / ghostId
        os.makeDir.all(dir)
        os.write.over(
          dir / "flow-map.json",
          FlowMapState(
            project = ghostId,
            updatedAt = 0L,
            nodes = Map(
              "g-1" -> NodeDef(
                id = "g-1",
                name = "ghost-node",
                agent = "general",
                status = NodeLifecycle.Wiring,
                createdAt = 1L
              )
            )
          ).asJson.noSpaces
        )
      }
      registry = Map(
        mountedId -> agentRecord(mountedId),
        ghostId -> agentRecord(ghostId),
        noDirId -> agentRecord(noDirId)
      )
      mountedBody <- ChainsReadFace.chainJson(mountedId, registry)
      ghostBody <- ChainsReadFace.chainJson(ghostId, registry)
      missingBody <- ChainsReadFace.chainJson("delegate-general-no-such-scope-here", registry)
      noDirBody <- ChainsReadFace.chainJson(noDirId, registry)
      badBody <- ChainsReadFace.chainJson("../escape", registry)
      ghostMounted <- DelegateFlowHosts.isMounted(ghostId)
      ghostDirCreated <- IO.blocking(os.exists(ws / ".nebflow" / "flow-maps" / "delegate-general-no-such-scope-here"))
      _ <- DelegateFlowHosts.unmount(mountedId)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      val m = mountedBody.getOrElse(fail("mounted scope must project"))
      val nodes = m.hcursor.downField("chain").downField("nodes").as[List[io.circe.Json]].toOption.getOrElse(Nil)
      assertEquals(nodes.size, 4, "active-zone members project in createdAt order")
      val a = nodes.head
      assertEquals(a.hcursor.get[String]("id").toOption, Some("n-a"))
      assertEquals(a.hcursor.get[String]("kind").toOption, Some("flow-step"), "ChainNode kind contract")
      assertEquals(
        a.hcursor.get[String]("label").toOption,
        Some("first line of the task"),
        "label = task first line (≤80)"
      )
      assertEquals(a.hcursor.get[String]("description").toOption, Some("short desc"))
      assertEquals(a.hcursor.get[String]("status").toOption, Some("pending"), "pending/wiring ⇒ pending")
      assertEquals(a.hcursor.downField("deps").as[List[String]].toOption, Some(List("n-b")))
      val statuses = nodes.flatMap(_.hcursor.get[String]("status").toOption)
      assertEquals(statuses, List("pending", "running", "completed", "failed"), "v3 §9 mapping table single point")
      assertEquals(m.hcursor.downField("chain").get[Boolean]("mounted").toOption, Some(true))
      // record = 三行族原样（适配层直接输入；此处为空记录形）。
      assertEquals(
        m.hcursor.downField("record").downField("entries").as[List[io.circe.Json]].toOption,
        Some(Nil),
        "record carries the entries array verbatim"
      )
      // 探针腿：未挂载但盘面在。
      val g = ghostBody.getOrElse(fail("on-disk scope must project via probe"))
      assertEquals(g.hcursor.downField("chain").get[Boolean]("mounted").toOption, Some(false))
      val gnodes = g.hcursor.downField("chain").downField("nodes").as[List[io.circe.Json]].toOption.getOrElse(Nil)
      assertEquals(
        gnodes.flatMap(_.hcursor.get[String]("status").toOption),
        List("pending"),
        "wiring ⇒ pending through the probe"
      )
      assert(!ghostMounted, "the probe never mounts into the hosts registry (read-only)")
      // 无目录（记录在册）= chain null 降级；查无记录 = None（HTTP 404 面）；
      // 非法键 = None（HTTP 400 面）；探针零建目录。
      assertEquals(
        noDirBody.map(_.hcursor.downField("chain").focus),
        Some(Some(io.circe.Json.Null)),
        "missing scope dir ⇒ chain null (three-row-family degraded view)"
      )
      assertEquals(missingBody, None, "unknown key ⇒ None (route 404s)")
      assertEquals(badBody, None, "invalid key ⇒ None (route 400s before, defense in depth)")
      assert(!ghostDirCreated, "the read face never creates scope directories")
    end for
  }

  // ── C4 现役窗世界座位闸（面板退役台账泄漏微批 #506，2026-10-18）──────────────

  /** 项目定义夹具（ProjectStore.list() 的世界腿数据源；project.json 手写最小形）。 */
  private def putProjectDef(name: String, workspace: os.Path): Unit =
    val dir = tempRoot / "projects" / name
    os.makeDir.all(dir)
    os.write(
      dir / "project.json",
      io.circe.Json
        .obj(
          "name" -> name.asJson,
          "workspace" -> workspace.toString.asJson,
          "agentFile" -> (workspace / "AGENTS.md").toString.asJson,
          "createdAt" -> System.currentTimeMillis().asJson
        )
        .noSpaces
    )

  /** 外来隔离实例（coldstart 腿）形态的记录：座位在实例世界之外。 */
  private def foreignRecord(id: String, taskId: Option[String], createdAt: Long): DelegateRegistry.Record =
    DelegateRegistry.Record(
      id = id,
      executor = "nebflow",
      cwd = s"/tmp/nb-spec-coldstart-$id/general",
      project = None,
      taskId = taskId,
      parentSessionId = "coldstart-root",
      title = "retired-ledger leftover",
      createdAt = createdAt
    )

  test(
    "C4: the active rows window drops foreign-seat (retired-instance) records; the archived window keeps them (zero deletion)"
  ) {
    // 世界两腿：dataRoot 子树（缺省 general 座位）+ 项目定义 workspace（dataRoot 之外）。
    val projectWs = os.temp.dir(prefix = "nb-crf-c4-projws-", deleteOnExit = false)
    putProjectDef("spec-proj", projectWs)
    val defaultSeat = DelegateRegistry.Record(
      id = "delegate-general-66aa77bb",
      executor = "nebflow",
      cwd = (tempRoot / "general").toString, // dataRoot 子树（缺省座位）
      project = None,
      taskId = Some("900"),
      parentSessionId = "nebula-root",
      title = "in-world default seat",
      createdAt = System.currentTimeMillis(),
      projectWorkspace = None
    )
    val projectSeat = record("delegate-general-77bb88cc", Some("901")).copy(
      project = Some("spec-proj"),
      projectWorkspace = Some(projectWs.toString),
      cwd = (projectWs / ".nebflow" / "worktrees" / "wt-901").toString // worktree 座位 ⊂ 项目 workspace
    )
    val staleActive = foreignRecord("delegate-nebflow-88cc99dd", Some("33"), createdAt = 1L)
    val staleArchived = foreignRecord("delegate-nebflow-99dd00ee", Some("36"), createdAt = 2L)
      .copy(archivedAt = Some(3L))
    List(defaultSeat, projectSeat, staleActive, staleArchived).foreach(DelegateRegistry.put)

    for
      activeBody <- ChainsReadFace.rowsJson(Map.empty)
      archivedBody <- ChainsReadFace.rowsJson(Map.empty, archived = true)
    yield
      val activeIds = activeBody.hcursor
        .downField("rows")
        .as[List[io.circe.Json]]
        .toOption
        .getOrElse(Nil)
        .map(
          _.hcursor.get[String]("id").toOption.getOrElse("")
        )
      val archivedIds = archivedBody.hcursor
        .downField("rows")
        .as[List[io.circe.Json]]
        .toOption
        .getOrElse(Nil)
        .map(
          _.hcursor.get[String]("id").toOption.getOrElse("")
        )
      assertEquals(
        activeIds.toSet,
        Set(defaultSeat.id, projectSeat.id),
        "active window = this instance's world only (default seat + project-workspace seat); retired-instance rows never leak"
      )
      assert(
        !activeIds.contains(staleActive.id),
        "a /tmp coldstart-seat record (retired ledger taskNo) is not panel-visible"
      )
      assertEquals(
        archivedIds.toSet,
        Set(staleArchived.id),
        "the archived window stays complete (zero deletion): archived foreign record still inspectable"
      )
    end for
  }

  test("C4b: seat scoping is canonical-path stable — a symlinked workspace alias still counts as in-world") {
    // 真实形态：projects 目录 nebflow→stand 改名后，旧记录 cwd 仍走旧名（symlink 别名）。
    // canonical 判定必须吃掉别名差异（否则改名即丢现役行）。夹具落 tempRoot 内（生产
    // 形态 = /Users 下稳定路径的改名 symlink，非系统 tmpdir 形态）。
    val realWs = tempRoot / "c4b-real-ws"
    val aliasDir = tempRoot / "c4b-alias-root"
    val aliasWs = aliasDir / "AliasName"
    os.makeDir.all(realWs)
    os.makeDir.all(aliasDir)
    java.nio.file.Files.createSymbolicLink(
      aliasWs.toNIO,
      realWs.toNIO
    )
    putProjectDef("spec-alias-proj", realWs) // 世界登记 = 真名
    val aliasedSeat = record("delegate-general-aabb0011", Some("902")).copy(
      projectWorkspace = Some((aliasWs / ".nebflow" / "worktrees" / "wt-902").toString) // 座位走别名
    )
    DelegateRegistry.put(aliasedSeat)
    val foreign = foreignRecord("delegate-nebflow-bbcc0022", Some("64"), createdAt = 4L)
    DelegateRegistry.put(foreign)
    for body <- ChainsReadFace.rowsJson(Map.empty)
    yield
      val ids = body.hcursor
        .downField("rows")
        .as[List[io.circe.Json]]
        .toOption
        .getOrElse(Nil)
        .map(
          _.hcursor.get[String]("id").toOption.getOrElse("")
        )
      assertEquals(
        ids.toSet,
        Set(aliasedSeat.id),
        "symlink-aliased project seat resolves into the world (canonical compare); foreign seat filtered"
      )
    end for
  }

end ChainsReadFaceSpec
