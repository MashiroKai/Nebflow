package nebflow.core.project

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import fs2.Stream
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.ActorSystem
import nebflow.agent.{AgentLibrary, SharedResources, SpecResources}
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.{FileLockManager, NodeEditTool, NodeListTool, NodeTools, ToolContext}
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ThinkingConfig}
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, PathUtil, StreamChunk}

import scala.concurrent.duration.*
import scala.util.Random

/**
 * 节点模型字段改造 spec（2026-09-05 插件架构对齐批）——NodeEdit 新 schema 契约 +
 * Flow Map 载荷收敛 E2E（进程内 wire 级，NodeAcceptanceSpec/NodePluginChainSpec
 * stub-LLM harness 先例）。
 *
 * Schema 层：
 * - description 创建必写（非空 trim、≤200），编辑可 update
 * - agent/skill/mcp 退役 → 拒绝（NODE_AGENT_RETIRED，指向 plugins）
 * - worktree String→Boolean（WORKTREE_NOT_BOOLEAN）；true = 即时派生创建
 *   （fail-fast，失败拒绝建节点）；编辑路径拒绝（WORKTREE_CREATE_ONLY）
 * - plugins 信任门既有逻辑保持（approved 才可分配——2b 断言零弱化由 NodePluginChainSpec 承载）
 * E2E：
 * - 新 schema 建节点（description+task+out=Nebula+plugins[approved]+preset+worktree=true）
 *   → 执行至 completed → 默认载荷只见元数据（无 result）→ NodeList(detail) 取回全文一致
 *   → 落盘拆分（JSON 摘要 + results/<id>.md 全文）→ worktree 派生创建成功
 */
class NodeSchemaSlimSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 180.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-schema-slim"
  private val originalRoot = PathUtil.dataRoot

  // skills-only plugin fixture（真实目录 + 审批走 PluginRegistry 单点，NodePluginChainSpec 同款）
  private val slimPluginDir = tempRoot / "plugins" / "slim-e2e"

  // 2026-09-18 测试面成因级修复（候选①·治本）：夹具建立与**进程级** `PathUtil.setDataRoot`
  // 一律**不在类体构造期**执行，收进 `beforeAll`；且顺序为「**先清树 / 建树，最后换根**」。
  // 旧形态（类体里先 `setDataRoot(tempRoot)` 再 `os.remove.all(tempRoot)`）把「递归删树」
  // 与「本树已是进程级全局根」压在同一个窗口里：同 JVM 内先跑的套件
  // （NodeDeclarationGateSpec）收尾期仍在执行的异步写入按 `PathUtil.dataRoot` 落进
  // **刚被换上的本树** ⇒ 删树遍历中途该目录重新非空 ⇒ 构造期
  // `DirectoryNotEmptyException`（组合跑 + 陈旧测试树在场复现；单跑 / 预清该两树后
  // 组合跑不复现 —— 清树窗口长度随树体量增长，正是「陈旧树在场」的放大作用）。
  // 此处清树时全局根仍指向他处 ⇒ 零写入落进本树（窗口为零）；换根后置 ⇒ 删 / 写不再同树。
  override def beforeAll(): Unit =
    os.remove.all(tempRoot)
    os.makeDir.all(tempRoot / "agents" / "general")
    os.write.over(
      tempRoot / "agents" / "general" / "agent.json",
      """{"name":"general","description":"general executor","tools":[],"category":"standalone"}"""
    )
    os.write.over(tempRoot / "agents" / "general" / "system.md", "# general\n")
    os.write.over(tempRoot / "nebflow.json", "{}")
    // 20260907 修复（历史注记，panelscheme 批后 E2E 已不再传 preset 参数）：当年
    // E2E 用 preset "qa" 但夹具未 seed model-presets.json → §E.3 解析失败 → 节点
    // failed。preset 表 fixture 保留（默认链 health 兜底 +qa 链留作其它断言用）
    // （RecordingLlm 为 stub，模型名不触真实调用）。
    os.write.over(
      tempRoot / "model-presets.json",
      """{"defaultPreset":"general","presets":{"general":{"name":"general","description":"default","preferred":"mock/mock-a","fallbacks":["mock/mock-b"]},"qa":{"name":"qa","description":"qa fixture preset","preferred":"mock/mock-qa","fallbacks":["mock/mock-qa-b"]}}}"""
    )
    os.makeDir.all(slimPluginDir / "skills" / "howto")
    os.write.over(
      slimPluginDir / "plugin.json",
      """{"$schema":"https://agent-plugins.org/schemas/1.0.0/plugin.schema.json","name":"slim-e2e","version":"1.0.0","description":"slim payload e2e fixture"}"""
    )
    os.write.over(
      slimPluginDir / "skills" / "howto" / "SKILL.md",
      """---
        |name: howto
        |description: slim e2e skill
        |---
        |## SlimE2E Marker
        |Body.""".stripMargin
    )
    PathUtil.setDataRoot(tempRoot)

  end beforeAll

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)

  // >500 chars —— E2E 断言「JSON 只带摘要（500+…）不带全文尾部标记」需要全文长于摘要上限
  private val ResultText = ("E2E FULL RESULT BODY。result-line-eight。" * 25) + "marker-slim-e2e-end"

  private class RecordingLlm extends LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new RuntimeException("send not expected"))

    def sendStream(
      req: LlmRequest,
      onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] =
      Stream(StreamChunk.TextDelta(ResultText), StreamChunk.Done(None, None))

  private def mkCtx(res: SharedResources, system: ActorSystem, ws: String): ToolContext =
    ToolContext(
      projectRoot = ws,
      sessionId = Some("slim-sid"),
      rootSessionId = Some("nebula-root"),
      sharedResources = Some(res),
      actorSystem = Some(system)
    )

  private def mountProject(name: String, ws: os.Path, system: ActorSystem, res: SharedResources): IO[ProjectRuntime] =
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
        emitEvent = (_, _, _) => IO.unit,
        // noderpt 批 A 段：本 fixture 主题非 node_report 语义 ⇒ 显式关腿 2（生产默认开；
        // 腿 2 默认开行为由 NodeReportReminderSpec 覆盖）。
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
    yield rt

  private def nodeEdit(input: Json, ctx: ToolContext): IO[Either[String, String]] =
    NodeEditTool.call(input.asObject.get, ctx).map(_.left.map(_.message))

  private def nodeInput(project: String, nodename: String, extra: (String, Json)*): Json =
    Json.obj(
      ("project" -> Json
        .fromString(project)) :: ("nodename" -> Json.fromString(nodename)) :: ("plugins" -> Json.arr()) :: extra.toList*
    )

  private def waitUntil(timeout: FiniteDuration, every: FiniteDuration = 50.millis)(cond: IO[Boolean]): IO[Unit] =
    def go(deadline: Long): IO[Unit] =
      cond.flatMap {
        case true => IO.unit
        case false =>
          if System.currentTimeMillis() >= deadline then
            IO.raiseError(new AssertionError("waitUntil: condition not met in time"))
          else IO.sleep(every) *> go(deadline)
      }
    go(System.currentTimeMillis() + timeout.toMillis)

  /** git 仓 workspace fixture：init + 身份 + 初始空提交（worktree add 需至少一提交）。 */
  private def gitWorkspace(name: String): os.Path =
    val ws = tempRoot / s"ws-git-$name-${Random.nextInt(100000)}"
    os.makeDir.all(ws)
    os.proc("git", "init", ws.toString).call(check = true)
    os.proc("git", "-C", ws.toString, "config", "user.email", "spec@nebflow.local").call(check = true)
    os.proc("git", "-C", ws.toString, "config", "user.name", "spec").call(check = true)
    os.proc("git", "-C", ws.toString, "commit", "--allow-empty", "-m", "init").call(check = true)
    ws

  private def plainWorkspace(name: String): os.Path =
    val ws = tempRoot / s"ws-plain-$name-${Random.nextInt(100000)}"
    os.makeDir.all(ws)
    ws

  // ── 1. description 契约 ─────────────────────────────────

  test(
    "A⓪ tool doc budget: NodeEdit description ≤7000 chars (⑤b 7438→3742；merge语义→4308；E1 out门控→4395；E2 retry 参数行→4700；D2 描述重写→5450；中断恢复批 R2 interrupted 重激活条款→5700；链级抽象 P2 restoreChain 参数行 + 归档编辑语义→6050；nodegate 建位期声明闸批 dangling/verifierRoutePending/plugins-undeclared/out·role·merge 行内标记→6350；chainmodel 批一 chainId 声明参数行 + deps 链引用条款→7000)"
  ) {
    val d = NodeEditTool.description
    // 3800 为 ⑤b 压缩批自钉预算；合并观测面P0P1引擎批时解冲吸收 main 后落语义
    // （failed 重激活条款 / abandon 无 TTL 裁定 / notifyDispatcher completion-only）
    // ——语义不可删，预算放宽至 4400（实测 4308）。E1 out 门控语法行（实测 4395）
    // 仍在预算内；批E2 retry 参数行（新语义，schema retry 属性详注同源）→ 4700。
    // 批D2 描述重写（spec 20260908 §5 批D2，行为基线=E1+E2）：out 门控语法逐段
    // 展开 + out delivery 语义 bullet（D5 零结算/WARNING）+ retry 自动回跳链 +
    // NODE_MERGE_PASS_ONLY 条款（实测 ~5,380）→ 5450。与 E1/E2 同窗合并使前缀
    // 缓存一次性失效（spec §4.2 cache 纪律），增量成本一次性支付。
    // 中断恢复语义批 R1/R2（spec 20260908_interrupt-recovery-semantics §2.4 批 R2，
    // 2026-09-13）：NodeEdit 描述「语义明示」要求新增 interrupted 重激活条款
    // （新生命周期值，非续跑语义必须写进描述否则分发器会误以为 reactivate = 续跑）
    // ——语义不可删，预算 5450→5700（实测 5,633）。单条新增近 200 字符已压到最短
    // 表述（fresh rerun + 非 checkpoint resume + boot recovery 归口三点齐全）。
    // 链级抽象 P2（spec 20260910_flowmap-chain-abstraction-spec §5）：「拉回」是新参数
    // 面（restoreChain 显式旗标）且打开归档节点编辑域——分发器不知道它有这条通道就
    // 只能建重复节点。参数行 + 归档编辑语义行两条（实测 5,991）→ 预算 5700→6050。
    // nodegate 建位期声明闸批（判据真源 20260913_173919_nodegate-create-time-validation-plan
    // §1 + §7 文档一致性联动）：out 行 dangling 标记 / role 行空 out 令牌指引 /
    // plugins 行 NODE_PLUGINS_UNDECLARED 条款 / merge 行 NODE_MERGE_SINK_NEEDS_OUT
    // 条款（两个新参数的完整描述进 schema properties，不占描述预算）→ 预算 6050→6350。
    // chainmodel 批一（定义层 2026-09-19，①显式成员制 + ③跨链依赖原语）：`chainId` 声明
    // 参数行（声明即归属 / 纯元数据不改调度 / null 撤销 / chain-membership-changed 留痕）
    // + `deps` 行尾 chain:<id> 链引用条款（纯调度闸、deps-only、NODE_CHAIN_REF_UNKNOWN）。
    // 基座实测 6,345（距 6350 预算仅 5 字符余量，两条新语义行无处可压）⇒ 预算 6350→7000
    // （本批实测 6,948；两数均为 `description` 字面量内容长度，静态读取，未编译验证）。
    // chainId 的完整值域文本进 schema property（同 nodegate 批先例）。
    // panelscheme 批（2026-09-21，作者令：节点无自有模型方案）：`preset` 参数退役，
    // 退役条与既有 `NODE_AGENT_RETIRED` 条**并成一行**（两条同属「已退役参数」族，
    // 原尾部独立一条为重复）——净增 43 字符，实测 6,991（**未抬预算**，仍守 7,000；
    // 余量 9 字符，下批新增语义须先压缩或按先例抬预算）。
    assert(
      d.length <= 7000,
      s"NodeEdit description must stay ≤7000 chars (⑤b压缩+E1门控+E2 retry+D2重写+R2 interrupted条款+P2 restoreChain条款+nodegate 建位期声明闸+chainmodel 批一 chainId/deps链引用条款+panelscheme 批 retired 行合并), got ${d.length}"
    )
    // 语义锚点抽查：核心参数/错误码/机制关键词不得在压缩中丢失
    for anchor <- List(
        "nodename",
        "descriptionLong",
        "replace-on-provide",
        "NODE_AGENT_RETIRED",
        "EMPTY_NODE_CONNECTION",
        "NODE_MERGE_REQUIRES_UPSTREAM",
        "worktree",
        "abandon",
        "notifyDispatcher",
        "Nebula",
        "NodeList(detail=",
        "retry",
        "restoreChain",
        // chainmodel 批一（定义层）：成员制声明面 + 跨链依赖原语 + 归属变更事件三条契约必须
        // 常驻描述（分发器不读代码，只读描述——丢一条 = 声明面失联）
        "chainId",
        "chain:<id>",
        "chain-membership-changed",
        "NODE_CHAIN_REF_UNKNOWN",
        "NODE_CHAIN_ID_INVALID",
        // panelscheme 批（2026-09-21）：preset 参数退役契约——退役错误码必须常驻描述
        // （分发器不读代码；丢这条 = 它会继续按旧习惯传 preset 吃一次硬拒往返）。
        "NODE_PRESET_RETIRED"
      )
    do assert(d.contains(anchor), s"compressed description must keep '$anchor'")
    end for
  }

  test(
    "A① create without description → NODE_DESCRIPTION_REQUIRED; empty → same; >60 → NODE_DESCRIPTION_TOO_LONG (裁定⑤c)"
  ) {
    val ws = plainWorkspace("desc")
    val system = ActorSystem(s"slim-desc-${Random.nextInt(100000)}")
    for
      res <- SpecResources.mkResources(system, tempRoot, new RecordingLlm)
      rt <- mountProject("slim-desc", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      missing <- nodeEdit(
        nodeInput("slim-desc", "n-missing", "task" -> Json.fromString("t"), "out" -> Json.fromString("Nebula")),
        ctx
      )
      blank <- nodeEdit(
        nodeInput(
          "slim-desc",
          "n-blank",
          "description" -> Json.fromString("   "),
          "task" -> Json.fromString("t"),
          "out" -> Json.fromString("Nebula")
        ),
        ctx
      )
      tooLong <- nodeEdit(
        nodeInput(
          "slim-desc",
          "n-long",
          "description" -> Json.fromString("x" * 61),
          "task" -> Json.fromString("t"),
          "out" -> Json.fromString("Nebula")
        ),
        ctx
      )
      ok60 <- nodeEdit(
        nodeInput(
          "slim-desc",
          "n-ok60",
          "description" -> Json.fromString("x" * 60),
          "task" -> Json.fromString("task-ok60"),
          "out" -> Json.fromString("Nebula")
        ),
        ctx
      )
      longDesc <- nodeEdit(
        nodeInput(
          "slim-desc",
          "n-longd",
          "description" -> Json.fromString("short"),
          "descriptionLong" -> Json.fromString("L" * 201),
          "task" -> Json.fromString("task-longd"),
          "out" -> Json.fromString("Nebula")
        ),
        ctx
      )
      okLong <- nodeEdit(
        nodeInput(
          "slim-desc",
          "n-oklong",
          "description" -> Json.fromString("short"),
          "descriptionLong" -> Json.fromString("详述：" + "L" * 190),
          "task" -> Json.fromString("task-oklong"),
          "out" -> Json.fromString("Nebula")
        ),
        ctx
      )
      snap <- rt.store.snapshot
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(
        missing.isLeft && missing.left.exists(_.contains("NODE_DESCRIPTION_REQUIRED")),
        s"missing description must be rejected, got: $missing"
      )
      assert(
        blank.isLeft && blank.left.exists(_.contains("NODE_DESCRIPTION_REQUIRED")),
        s"blank description must be rejected, got: $blank"
      )
      assert(
        tooLong.isLeft && tooLong.left.exists(_.contains("NODE_DESCRIPTION_TOO_LONG")),
        s">60 must be rejected (裁定⑤c), got: $tooLong"
      )
      assert(ok60.isRight, s"exactly-60 description must pass, got: $ok60")
      assert(
        longDesc.isLeft && longDesc.left.exists(_.contains("NODE_DESCRIPTION_LONG_TOO_LONG")),
        s"descriptionLong >200 must be rejected, got: $longDesc"
      )
      assert(okLong.isRight, s"valid descriptionLong must pass, got: $okLong")
      // 双层落库：短文进 NodeDef.description，长文进 descriptionLong（均 trim 归一）
      assertEquals(
        snap.nodes.values.find(_.name == "n-oklong").map(n => (n.description, n.descriptionLong)),
        Some((Some("short"), Some("详述：" + "L" * 190))),
        "both layers must be stored trimmed"
      )
    end for
  }

  test(
    "A② edit updates description; descriptionLong detail-only (not in default payload, present in detail channel) (裁定⑤c)"
  ) {
    val ws = plainWorkspace("desc-edit")
    val system = ActorSystem(s"slim-de-${Random.nextInt(100000)}")
    for
      res <- SpecResources.mkResources(system, tempRoot, new RecordingLlm)
      rt <- mountProject("slim-de", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      _ <- nodeEdit(
        nodeInput(
          "slim-de",
          "n-e",
          "description" -> Json.fromString("before"),
          "task" -> Json.fromString("t"),
          "out" -> Json.fromString("Nebula")
        ),
        ctx
      )
      upd <- nodeEdit(
        nodeInput(
          "slim-de",
          "n-e",
          "description" -> Json.fromString("after"),
          "descriptionLong" -> Json.fromString("编辑后的长描述")
        ),
        ctx
      )
      snap <- rt.store.snapshot
      payload <- NodeTools.buildNodeListPayload(rt)
      nodeId = snap.nodes.values.find(_.name == "n-e").map(_.id).getOrElse("")
      detailRaw <- NodeListTool.call(
        io.circe.JsonObject
          .fromIterable(List("project" -> Json.fromString("slim-de"), "detail" -> Json.fromString(nodeId))),
        ctx
      )
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(upd.isRight, s"description edit must succeed, got: $upd")
      assertEquals(snap.nodes.values.find(_.name == "n-e").flatMap(_.description), Some("after"))
      assertEquals(snap.nodes.values.find(_.name == "n-e").flatMap(_.descriptionLong), Some("编辑后的长描述"))
      // 默认载荷：不带 descriptionLong 键（键集零漂移）
      val nodes = payload.hcursor.downField("nodes").as[List[Json]].toOption.getOrElse(Nil)
      val mine =
        nodes.find(_.hcursor.get[String]("id").toOption.contains(nodeId)).getOrElse(fail("node missing from payload"))
      assert(
        !mine.asObject.exists(_.contains("descriptionLong")),
        "default payload must NOT carry descriptionLong (裁定⑤c detail-only)"
      )
      // detail 通道：条件键携带
      val detail =
        io.circe.parser.parse(detailRaw.toOption.getOrElse(fail("detail failed"))).getOrElse(fail("detail not json"))
      assertEquals(
        detail.hcursor.get[String]("descriptionLong").toOption,
        Some("编辑后的长描述"),
        "detail channel must carry descriptionLong"
      )
    end for
  }

  // ── 2. agent/skill/mcp 退役 ─────────────────────────────

  test("B① passing agent/skill/mcp → rejected (NODE_AGENT_RETIRED, points at plugins)") {
    val ws = plainWorkspace("retired")
    val system = ActorSystem(s"slim-ret-${Random.nextInt(100000)}")
    for
      res <- SpecResources.mkResources(system, tempRoot, new RecordingLlm)
      rt <- mountProject("slim-ret", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      withAgent <- nodeEdit(
        nodeInput(
          "slim-ret",
          "n-a",
          "agent" -> Json.fromString("test-agent"),
          "description" -> Json.fromString("d"),
          "task" -> Json.fromString("t"),
          "out" -> Json.fromString("Nebula")
        ),
        ctx
      )
      withSkill <- nodeEdit(
        nodeInput(
          "slim-ret",
          "n-s",
          "skill" -> Json.fromString("some-skill"),
          "description" -> Json.fromString("d"),
          "task" -> Json.fromString("t"),
          "out" -> Json.fromString("Nebula")
        ),
        ctx
      )
      withMcp <- nodeEdit(
        nodeInput(
          "slim-ret",
          "n-m",
          "mcp" -> Json.fromString("some-mcp"),
          "description" -> Json.fromString("d"),
          "task" -> Json.fromString("t"),
          "out" -> Json.fromString("Nebula")
        ),
        ctx
      )
      snap <- rt.store.snapshot
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      for (r, label) <- List((withAgent, "agent"), (withSkill, "skill"), (withMcp, "mcp")) do
        assert(r.isLeft, s"$label param must be rejected")
        assert(
          r.left.exists(_.contains("NODE_AGENT_RETIRED")),
          s"$label rejection must carry NODE_AGENT_RETIRED, got: ${r.left.getOrElse("")}"
        )
        assert(r.left.exists(_.contains("plugins")), s"$label rejection must point at plugins")
      assert(snap.nodes.isEmpty, "no node must be created from retired-param calls")
    end for
  }

  // ── 3. worktree 布尔派生（创建时机裁决 a：NodeEdit 即时创建 fail-fast）──

  test("C① worktree non-boolean (legacy string form) → WORKTREE_NOT_BOOLEAN") {
    val ws = gitWorkspace("wtnb")
    val system = ActorSystem(s"slim-wtnb-${Random.nextInt(100000)}")
    for
      res <- SpecResources.mkResources(system, tempRoot, new RecordingLlm)
      rt <- mountProject("slim-wtnb", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      r <- nodeEdit(
        nodeInput(
          "slim-wtnb",
          "n-str",
          "worktree" -> Json.fromString("pre-made"),
          "description" -> Json.fromString("d"),
          "task" -> Json.fromString("t"),
          "out" -> Json.fromString("Nebula")
        ),
        ctx
      )
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield assert(
      r.isLeft && r.left.exists(_.contains("WORKTREE_NOT_BOOLEAN")),
      s"string worktree must be rejected, got: $r"
    )
    end for
  }

  test("C② worktree=true on non-git workspace → fail-fast rejection, NO node created") {
    val ws = plainWorkspace("nogit")
    val system = ActorSystem(s"slim-nogit-${Random.nextInt(100000)}")
    for
      res <- SpecResources.mkResources(system, tempRoot, new RecordingLlm)
      rt <- mountProject("slim-nogit", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      r <- nodeEdit(
        nodeInput(
          "slim-nogit",
          "n-ng",
          "worktree" -> Json.fromBoolean(true),
          "description" -> Json.fromString("d"),
          "task" -> Json.fromString("t"),
          "out" -> Json.fromString("Nebula")
        ),
        ctx
      )
      snap <- rt.store.snapshot
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(
        r.isLeft && r.left.exists(_.contains("git repository")),
        s"non-git workspace must reject worktree=true, got: $r"
      )
      assert(snap.nodes.isEmpty, "fail-fast: node must NOT be created when worktree creation fails")
    end for
  }

  test("C③ worktree=true on git workspace → derived worktree+branch created immediately, node bound to it") {
    val ws = gitWorkspace("wtyes")
    val system = ActorSystem(s"slim-wtyes-${Random.nextInt(100000)}")
    for
      res <- SpecResources.mkResources(system, tempRoot, new RecordingLlm)
      rt <- mountProject("slim-wtyes", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      r <- nodeEdit(
        nodeInput(
          "slim-wtyes",
          "调研-派生一",
          "worktree" -> Json.fromBoolean(true),
          "description" -> Json.fromString("d"),
          "task" -> Json.fromString("t"),
          "out" -> Json.fromString("Nebula")
        ),
        ctx
      )
      snap <- rt.store.snapshot
      // worktrees[] 名单自动纳入（NodeList 载荷 worktree 标记链路）
      payload <- NodeTools.buildNodeListPayload(rt)
      wts = payload.hcursor.downField("worktrees").as[List[String]].toOption.getOrElse(Nil)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(r.isRight, s"worktree=true create must succeed, got: $r")
      val n = snap.nodes.values.find(_.name == "调研-派生一")
      assert(n.isDefined, "node must exist")
      val bare = n.get.worktree
      assert(bare.isDefined, s"node must carry worktree binding, node=$n")
      val derived = bare.get
      assert(os.exists(ws / ".nebflow" / "worktrees" / derived / ".git"), s"worktree dir must exist: $derived")
      val branches = os.proc("git", "-C", ws.toString, "branch", "--list", derived).call(check = true).out.trim()
      assert(branches.nonEmpty, s"same-name branch must exist for worktree '$derived', got: '$branches'")
      assert(wts.contains(derived), s"worktrees[] must include the derived worktree, got: $wts")
    end for
  }

  test("C④ worktree on edit → WORKTREE_CREATE_ONLY (create-time binding)") {
    val ws = plainWorkspace("wtedit")
    val system = ActorSystem(s"slim-wte-${Random.nextInt(100000)}")
    for
      res <- SpecResources.mkResources(system, tempRoot, new RecordingLlm)
      rt <- mountProject("slim-wte", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      _ <- nodeEdit(
        nodeInput(
          "slim-wte",
          "n-w",
          "description" -> Json.fromString("d"),
          "task" -> Json.fromString("t"),
          "out" -> Json.fromString("Nebula")
        ),
        ctx
      )
      r <- nodeEdit(nodeInput("slim-wte", "n-w", "worktree" -> Json.fromBoolean(true)), ctx)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield assert(
      r.isLeft && r.left.exists(_.contains("WORKTREE_CREATE_ONLY")),
      s"worktree on edit must be refused, got: $r"
    )
    end for
  }

  // ── 4. E2E：新 schema 建节点 → 执行至 completed → 载荷收敛 + 按需读取 ──

  test(
    "E2E: plugins[approved]+description+worktree=true → completed; payload metadata-only; detail channel returns full result (panelscheme: preset 参数已退役)"
  ) {
    val ws = gitWorkspace("e2e")
    val system = ActorSystem(s"slim-e2e-${Random.nextInt(100000)}")
    for
      _ <- nebflow.core.plugin.PluginRegistry.approve("slim-e2e").flatMap {
        case Right(_) => IO.unit
        case Left(e) => IO.raiseError(new RuntimeException(s"fixture approve failed: $e"))
      }
      res <- SpecResources.mkResources(system, tempRoot, new RecordingLlm)
      rt <- mountProject("slim-e2e", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      created <- nodeEdit(
        nodeInput(
          "slim-e2e",
          "E2E-主节点",
          "description" -> Json.fromString("端到端载荷收敛验证节点"),
          "task" -> Json.fromString("produce the result"),
          "out" -> Json.fromString("Nebula"),
          "plugins" -> Json.arr(Json.fromString("slim-e2e")),
          "worktree" -> Json.fromBoolean(true)
        ),
        ctx
      )
      _ <- waitUntil(60.seconds)(
        rt.store.snapshot.map(_.nodes.values.exists(n => n.name == "E2E-主节点" && n.status == NodeLifecycle.Completed))
      )
      snap <- rt.store.snapshot
      payload <- NodeTools.buildNodeListPayload(rt)
      detailRaw <- NodeListTool.call(
        io.circe.JsonObject.fromIterable(
          List(
            "project" -> Json.fromString("slim-e2e"),
            "detail" -> Json.fromString(snap.nodes.values.find(_.name == "E2E-主节点").map(_.id).getOrElse(""))
          )
        ),
        ctx
      )
      nodeId = snap.nodes.values.find(_.name == "E2E-主节点").map(_.id).getOrElse("")
      diskJson <- IO.blocking(os.read(ws / ".nebflow" / "flow-map.json"))
      fileFull <- IO.blocking(os.read(ws / ".nebflow" / "results" / s"$nodeId.md"))
      memResult = snap.nodes.values.find(_.name == "E2E-主节点").flatMap(_.result)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(created.isRight, s"E2E create must succeed, got: $created")
      val n = snap.nodes.values.find(_.name == "E2E-主节点").getOrElse(fail("node missing"))
      // 执行统一 general + 配置面
      assertEquals(n.agent, "general", "new node agent must be pinned to general")
      assertEquals(n.plugins, List("slim-e2e"), "approved plugin must be allocated")
      // panelscheme 批（2026-09-21）：preset 参数退役——新建节点不再携带节点级方案
      assertEquals(n.preset, None, "retired preset param must leave new nodes preset-free")
      assert(n.worktree.isDefined, "worktree=true must bind a worktree")
      assert(os.exists(ws / ".nebflow" / "worktrees" / n.worktree.get / ".git"), "derived worktree must exist")
      // 默认载荷：元数据 only —— 无 result 键（全文与摘要都不进）
      val nodes = payload.hcursor.downField("nodes").as[List[Json]].toOption.getOrElse(Nil)
      val mine =
        nodes.find(_.hcursor.get[String]("id").toOption.contains(n.id)).getOrElse(fail("node missing from payload"))
      assert(!mine.asObject.exists(_.keys.exists(_ == "result")), "default payload must NOT carry result")
      assertEquals(mine.hcursor.get[String]("description").toOption, Some("端到端载荷收敛验证节点"))
      assertEquals(mine.hcursor.get[Boolean]("hasResult").toOption, Some(true))
      // plugins 正向钉（Flow Map 卡显示插件分配批 2026-09-06）：带插件节点的载荷必须
      // 携带插件名字数组（条件字段，非空才带；只放名字，禁塞描述全文）。无插件节点的
      // 字段集零漂移由 NodeEventPushSpec NodeListKeys 精确键集断言兜底。
      assertEquals(
        mine.hcursor.get[List[String]]("plugins").toOption,
        Some(List("slim-e2e")),
        "payload must carry plugins name array for plugin-assigned nodes"
      )
      // detail 通道：全文一致（同源 = 内存水合全文 = 落盘文件）
      val detail = detailRaw match
        case Right(raw) => io.circe.parser.parse(raw).getOrElse(fail("detail not json"))
        case Left(e) => fail(s"detail failed: $e")
      assertEquals(
        detail.hcursor.get[String]("result").toOption,
        memResult,
        "detail channel result must equal in-memory full text"
      )
      assertEquals(memResult, Some(ResultText), "in-memory result = stub LLM full output")
      // 落盘拆分：JSON 摘要、文件全文
      assert(!diskJson.contains(ResultText), "flow-map.json must NOT contain the full result text")
      assertEquals(fileFull, ResultText)
    end for
  }

end NodeSchemaSlimSpec
