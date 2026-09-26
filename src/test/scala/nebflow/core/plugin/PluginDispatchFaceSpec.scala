package nebflow.core.plugin

import cats.effect.{Deferred, IO, Ref}
import cats.effect.unsafe.implicits.global
import fs2.Stream
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.ActorSystem
import nebflow.agent.{AgentLibrary, SharedResources, SpecResources}
import nebflow.core.project.{
  FlowMapStore,
  NodeEngine,
  NodeLifecycle,
  ProjectDef,
  ProjectRuntime,
  ProjectRuntimeRegistry
}
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.{FileLockManager, NodeEditTool, ToolContext}
import nebflow.core.{RateLimiter, SessionStore}
import nebflow.llm.ModelCandidate
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, PathUtil, StreamChunk, ThinkingConfig}

import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.*

/**
 * 插件开关语义验收（**令 1**，2026-09-12 作者 14:14 原话：「插件的开关，应该只影响
 * 任务分发器对未来节点的派发，而不能影响目前的」；设计件
 * `20260912_142510_plugin-switch-semantics-design__chain-n-718da6b5.md`）。
 *
 * 拆面（引擎侧，本 spec 的验收面）：
 *  - **内容信任面** `plugins.trust.<n>`（形状零改动）= 闸 A 的存在性 + 闸 B/C/E/D；
 *  - **派发许可面** `plugins.dispatch.<n>`（新增，`PluginDispatchPolicy`）= 只喂闸 A。
 *
 * 覆盖：
 *  ① 零迁移：无 dispatch 记录 ⇒ 有效派发许可 == 内容受信（16 个现存包零改动）；
 *  ② 关闭 ⇒ 未来派发被拒（闸 A，`PLUGIN_DISPATCH_DISABLED`）+ 目录输出**零痕迹**
 *     （能力行与点名行皆无：2026-09-14 面板收敛批删除段尾「已关闭·禁派发」点名，
 *     对照面 = 未被关闭的包仍出现在目录里）；内容面**不受影响**（`resolve` 仍 Right
 *     ⇒ 闸 B/C/E/D 零变化）；
 *  ③ **已派发节点不受关闭影响**（核心验收，复现 R2 P0：wiring 节点在关闭前已派发，
 *     关闭后 barrier 归零启动 ⇒ 必须照常跑完 + 提示词注入全文在位）；
 *  ④ 过渡授权只放宽 + 到期自动失效（幂等回落作者意图）；
 *  ⑤ 显式 approve/revoke 语义未变（revoke 仍是内容面动作）。
 */
class PluginDispatchFaceSpec extends CatsEffectSuite:

  override val munitIOTimeout: FiniteDuration = 120.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-plugin-dispatch-face"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)

  for agent <- List("test-agent", "general") do
    os.makeDir.all(tempRoot / "agents" / agent)
    os.write.over(
      tempRoot / "agents" / agent / "agent.json",
      s"""{"name":"$agent","description":"dispatch-face spec agent","tools":[],"category":"standalone"}"""
    )
    os.write.over(tempRoot / "agents" / agent / "system.md", s"# $agent\n")
  os.write.over(tempRoot / "nebflow.json", "{}")

  private val skillDir = tempRoot / "plugins" / "inject-skill"
  os.makeDir.all(skillDir / "skills" / "howto")

  os.write.over(
    skillDir / "plugin.json",
    s"""{"$$schema":"${PluginRegistry.CanonicalSchema}","name":"inject-skill","version":"1.0.0","description":"dispatch face fixture"}"""
  )

  os.write.over(
    skillDir / "skills" / "howto" / "SKILL.md",
    """---
      |name: howto
      |description: dispatch face skill
      |---
      |## DispatchFaceBodyMarker
      |body""".stripMargin
  )

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)

  override def beforeEach(context: munit.BeforeEach): Unit =
    // 每用例回到「零 dispatch 记录」的干净面（trust 记录保留）
    os.write.over(tempRoot / "nebflow.json", "{}")
    PluginRegistry.invalidateCache()
    ProjectRuntimeRegistry.clear

  // ── 基建（NodePluginChainSpec / NodeAcceptanceSpec 同款）──────────────

  private class GatedLlm(gate: Deferred[IO, Unit]) extends LlmHandle[IO]:
    val requests: Ref[IO, List[String]] = Ref.unsafe[IO, List[String]](Nil)
    def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new RuntimeException("send not expected"))

    def sendStream(
      req: LlmRequest,
      onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] =
      val text = req.messages.map(_.textContent).mkString("\n")
      Stream.eval(requests.update(_ :+ text)).flatMap { _ =>
        val body = Stream(StreamChunk.TextDelta("ok"), StreamChunk.Done(None, None))
        if text.contains("GATE-UP") then Stream.eval(gate.get).flatMap(_ => body) else body
      }

  private def mkCtx(res: SharedResources, system: ActorSystem, ws: String): ToolContext =
    ToolContext(
      projectRoot = ws,
      sessionId = Some("dispatch-face-sid"),
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

  private def waitUntil(timeout: FiniteDuration, every: FiniteDuration = 25.millis)(cond: IO[Boolean]): IO[Unit] =
    def go(deadline: Long): IO[Unit] =
      cond.flatMap {
        case true => IO.unit
        case false =>
          if System.currentTimeMillis() >= deadline then
            IO.raiseError(new AssertionError("waitUntil: condition not met in time"))
          else IO.sleep(every) >> go(deadline)
      }
    go(System.currentTimeMillis() + timeout.toMillis)

  private def waitStatus(rt: ProjectRuntime, name: String, statuses: Set[String]): IO[Unit] =
    waitUntil(30.seconds) {
      rt.store.snapshot.map(_.nodes.values.find(_.name == name)).flatMap {
        case Some(n) => IO.pure(statuses.contains(n.status))
        case None => IO.pure(false)
      }
    }

  /** 按显示名取真实节点 id（`in`/`deps` 是纯 id 契约；NodeTools.scala:1290 = `n-<8hex>`）。 */
  private def waitNodeId(rt: ProjectRuntime, name: String): IO[String] =
    def go(deadline: Long): IO[String] =
      rt.store.snapshot.map(_.nodes.values.find(_.name == name).map(_.id)).flatMap {
        case Some(id) => IO.pure(id)
        case None =>
          if System.currentTimeMillis() >= deadline then
            IO.raiseError(new AssertionError(s"waitNodeId: node '$name' not found in time"))
          else IO.sleep(25.millis) >> go(deadline)
      }
    go(System.currentTimeMillis() + 30_000L)

  /**
   * fixture 卫生（无审批批）：记录 + **确保未封禁**——封禁写入独立命名空间，前序用例
   * 留下的 deny-list 不会因 approve 自动消失，须显式解封；`PluginBlockPolicy.unblock`
   * 对未封禁**不幂等**而是返回 `Left("Plugin '<name>' is not blocked — nothing to unblock")`
   * （零静默纪律），此处 `.void` 丢弃返回值、该 Left 不作为判据。
   */
  private def approveFixture: IO[Unit] =
    PluginRegistry.approve("inject-skill").flatMap {
      case Right(_) => IO.unit
      case Left(e) => IO.raiseError(new AssertionError(s"fixture approve failed: $e"))
    } *> PluginBlockPolicy.unblock("inject-skill", "spec-fixture").void

  // ── ① 零迁移 + ⑤ 内容面动作语义未变 ────────────────────────────────

  test("① 零迁移：无 dispatch 记录 ⇒ 有效派发许可 == 内容面可用；⑤ 封禁（deny-list）语义") {
    for
      _ <- approveFixture // = approve + unblock（fixture 卫生：封禁跨用例不残留）
      trusted <- PluginRegistry.contentTrusted("inject-skill")
      eff = PluginDispatchPolicy.effective("inject-skill", trusted)
      catalog <- PluginRegistry.renderCatalog()
      // ⑤ 封禁（无审批批 2026-09-13：`/revoke` 语义 = deny-list）⇒ 任何路径都拿不到
      _ <- PluginBlockPolicy.block("inject-skill", "spec", "spec")
      trustedAfter <- PluginRegistry.contentTrusted("inject-skill")
      effAfter = PluginDispatchPolicy.effective("inject-skill", trustedAfter)
      catalogAfter <- PluginRegistry.renderCatalog()
      real <- PluginRegistry.resolve("inject-skill")
      _ <- PluginBlockPolicy.unblock("inject-skill", "spec")
      restored <- PluginRegistry.contentTrusted("inject-skill")
    yield
      assert(trusted, "fixture 内容面可用")
      assert(eff, "无 dispatch 记录时必须跟随内容面（零迁移：现存包行为逐字不变）")
      assert(catalog.contains("- inject-skill:"), "内容面可用且未被关闭 ⇒ 目录行必须出现")
      assert(!catalog.contains("已关闭·禁派发"), "关闭注记字面量已退场 ⇒ 任何目录输出里都不得出现（零残留哨兵）")
      assert(!trustedAfter, "封禁后内容面不可用（deny-list）")
      assert(!effAfter, "内容面不可用 ⇒ 任何路径都拿不到（安全不降级）")
      assert(!catalogAfter.contains("- inject-skill:"), "封禁 ⇒ 目录行消失")
      assert(real.isLeft, "封禁后 resolve 必须 Left（闸 B/C/E 的内容面判定）")
      assert(restored, "解封后回落「在位即信任」")
  }

  // ── ② 关闭 ⇒ 未来派发被拒 + 目录零痕迹；内容面不受影响 ──────────────────

  test("② 关闭派发 ⇒ 新派发被拒（PLUGIN_DISPATCH_DISABLED）+ 目录**零痕迹**（能力行与点名行皆无）；内容面 resolve 仍 Right") {
    // 对照面夹具：另建一个**未被关闭**的包（仅本条用例内建、用例内删除 ⇒ 不影响
    // 其他用例）。判据「关闭的包零痕迹 / 未关闭的包仍出现」必须**同一份目录输出**
    // 里同时成立，否则「消失」可能只是段空了。
    val keepDir = tempRoot / "plugins" / "keep-skill"
    os.makeDir.all(keepDir / "skills" / "contrast")
    os.write.over(
      keepDir / "plugin.json",
      s"""{"$$schema":"${PluginRegistry.CanonicalSchema}","name":"keep-skill","version":"1.0.0","description":"contrast face fixture"}"""
    )
    os.write.over(
      keepDir / "skills" / "contrast" / "SKILL.md",
      """---
        |name: contrast
        |description: contrast face skill
        |---
        |body""".stripMargin
    )
    PluginRegistry.invalidateCache()
    for
      _ <- approveFixture
      _ <- PluginDispatchPolicy.setAuthorEnabled("inject-skill", false, "spec")
      res <- PluginRegistry.resolve("inject-skill")
      trusted <- PluginRegistry.contentTrusted("inject-skill")
      eff = PluginDispatchPolicy.effective("inject-skill", trusted)
      catalog <- PluginRegistry.renderCatalog()
      auditRaw <- IO.blocking {
        val f = tempRoot / "logs" / "plugin-dispatch.jsonl"
        if os.exists(f) then os.read(f) else ""
      }
      _ <- IO.blocking(os.remove.all(keepDir))
      _ <- IO(PluginRegistry.invalidateCache())
    yield
      assert(!eff, "关闭后有效派发许可必须为 false（将来派发被挡）")
      assert(trusted, "关闭**不得**动内容信任面（trusted 仍 true）")
      assert(
        res.isRight,
        "内容面 resolve 必须仍 Right ⇒ 闸 B/C/E（spawn/resume/loop 装载门）与闸 D（30s 重验）" +
          "对已派发节点零影响——这正是「不影响目前的」的结构保证"
      )
      assert(!catalog.contains("- inject-skill:"), "已关闭 ⇒ 能力行消失（S5）")
      assert(
        !catalog.contains("inject-skill"),
        s"已关闭 ⇒ 目录输出**零痕迹**（能力行与点名行皆无；2026-09-14 面板收敛批删除段尾点名）。catalog=\n$catalog"
      )
      assert(!catalog.contains("已关闭·禁派发"), s"关闭注记字面量不得再出现（零残留哨兵）。catalog=\n$catalog")
      assert(catalog.contains("- keep-skill:"), s"对照面：未被关闭的包仍出现在同一份目录输出里。catalog=\n$catalog")
      assert(
        auditRaw.contains("\"event\":\"authorEnabled\"") && auditRaw.contains("\"name\":\"inject-skill\""),
        s"写侧动作必须落 append-only 审计（设计 R8-C），got: $auditRaw"
      )
    end for
  }

  // ── ③ 已派发节点不受关闭影响（核心验收：复现并关闭 R2 P0 通道）──────────

  test("③ 已派发节点不受关闭影响：关闭后 barrier 归零启动 ⇒ 节点照常跑完 + 提示词注入全文在位") {
    val ws = tempRoot / "ws-dispatched"
    os.makeDir.all(ws)
    val system = ActorSystem(s"dispatch-face-${scala.util.Random.nextInt(100000)}")
    for
      _ <- approveFixture
      gate <- Deferred[IO, Unit]
      llm = new GatedLlm(gate)
      resources <- SpecResources.mkResources(system, tempRoot, llm)
      rt <- mountProject("dispatch-face", ws, system, resources)
      ctx = mkCtx(resources, system, ws.toString)
      // 上游节点（GATE-UP 任务）→ 挂在 gate 上，保持 Running ⇒ 下游 barrier 未归零
      up <- nodeEdit(
        nodeInput(
          "dispatch-face",
          "up",
          "description" -> Json.fromString("upstream gate node"),
          "task" -> Json.fromString("GATE-UP produce something"),
          "out" -> Json.fromString("Nebula")
        ),
        ctx
      )
      _ = assert(up.isRight, s"上游建立必须成功，got $up")
      _ <- waitStatus(rt, "up", Set(NodeLifecycle.Running))
      // `in` 是**纯 id 契约**（NodeTools.ensureNodeExists:67-71；nodename 是显示名，
      // 真实 id = `n-<8hex>`，见 NodeTools.scala:1290）⇒ 先按名取 id 再接线。
      upId <- waitNodeId(rt, "up")
      // 下游节点：**已做出派发承诺**（NodeEdit 落库时刻插件仍可派发），但尚未启动
      down <- nodeEdit(
        nodeInput(
          "dispatch-face",
          "down",
          "description" -> Json.fromString("downstream plugin node"),
          "task" -> Json.fromString("down work"),
          "plugins" -> Json.arr(Json.fromString("inject-skill")),
          "in" -> Json.fromString(upId),
          "out" -> Json.fromString("Nebula")
        ),
        ctx
      )
      _ = assert(down.isRight, s"下游派发必须成功（此刻插件仍可派发），got $down")
      // ── 作者关闭该插件（派发面）──
      _ <- PluginDispatchPolicy.setAuthorEnabled("inject-skill", false, "author")
      // ── 关闭已生效的**同刻读数**（顺序性证明的锚点）：up 仍被 gate 挂在 Running
      //    （未终态）⇒ 下游 barrier 未归零 ⇒ down 必未启动。此后才放行 gate，
      //    因此「down 跑到 Completed + 注入全文在位」严格发生在关闭之后。──
      atClose <- rt.store.snapshot.map { s =>
        (s.nodes.values.find(_.name == "up").map(_.status), s.nodes.values.find(_.name == "down").map(_.status))
      }
      // ── 关闭后新派发被拒（闸 A 对照：同一插件此刻拿不到）──
      rejected <- nodeEdit(
        nodeInput(
          "dispatch-face",
          "late",
          "description" -> Json.fromString("must be rejected"),
          "task" -> Json.fromString("late work"),
          "plugins" -> Json.arr(Json.fromString("inject-skill")),
          "out" -> Json.fromString("Nebula")
        ),
        ctx
      )
      // ── 上游放行 ⇒ 已派发的下游启动（闸 B 在此刻跑）──
      _ <- gate.complete(()).void
      _ <- waitStatus(rt, "down", Set(NodeLifecycle.Completed))
      ins <- llm.requests.get
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(
        atClose._1.contains(NodeLifecycle.Running),
        s"关闭生效那一刻上游必须仍被 gate 挂在 running（barrier 未归零），got ${atClose._1}"
      )
      assert(
        atClose._2.exists(st => st == NodeLifecycle.Wiring || st == NodeLifecycle.Pending),
        s"下游在关闭那一刻必须尚未启动（wiring/pending），got ${atClose._2}"
      )
      assert(rejected.isLeft, "关闭后**新派发**必须被拒（闸 A）")
      assert(rejected.left.exists(_.contains("PLUGIN_DISPATCH_DISABLED")), s"拒绝必须带可行动错误码 + 指引，got $rejected")
      val downReq = ins.find(_.contains("down work")).getOrElse("")
      assert(downReq.nonEmpty, s"已派发节点必须在关闭后仍能启动并跑到 LLM，got ${ins.size} request(s)")
      assert(downReq.contains("<injected-plugins>"), "已派发节点的插件注入块必须完整在位（关闭只影响未来派发；已启用提示词的审计面不变）")
      assert(downReq.contains("DispatchFaceBodyMarker"), "SKILL 全文照注入（内容面未撤）")
    end for
  }

  // ── ④ 过渡授权：只放宽 + 到期自动失效 ──────────────────────────────

  test("④ 过渡授权只放宽：关闭状态下 grant ⇒ 新派发放行；到期 ⇒ 自动回落作者意图") {
    for
      _ <- approveFixture
      _ <- PluginDispatchPolicy.setAuthorEnabled("inject-skill", false, "spec")
      closedEff = PluginDispatchPolicy.effective("inject-skill", trusted = true)
      _ <- PluginDispatchPolicy.grantTransition(
        "inject-skill",
        ttlSecs = 1,
        refs = Nil,
        reason = "unit spec",
        by = "spec"
      )
      granted <- IO(PluginDispatchPolicy.effective("inject-skill", trusted = true))
      // 到期后自动回落（不写任何东西）
      _ <- IO.sleep(1300.millis)
      expired <- IO(PluginDispatchPolicy.effective("inject-skill", trusted = true))
      // 过渡**不能**绕过内容面：未受信时仍不许可
      bypass <- IO(PluginDispatchPolicy.effective("inject-skill", trusted = false))
      // 幂等：重复 clear 是 no-op（有效值仍 = 作者意图）
      _ <- PluginDispatchPolicy.clearTransition("inject-skill", "spec")
      _ <- PluginDispatchPolicy.clearTransition("inject-skill", "spec")
      afterClear <- IO(PluginDispatchPolicy.effective("inject-skill", trusted = true))
      author <- IO(PluginDispatchPolicy.authorEnabled("inject-skill"))
    yield
      assert(!closedEff, "关闭状态下无过渡 ⇒ 不许可")
      assert(granted, "过渡授权必须放宽（新派发可用）")
      assert(!expired, "过渡到期 ⇒ **自动**回落作者意图（零人工复关，幂等）")
      assert(!bypass, "过渡永远不能绕过内容信任面（不变量 2）")
      assert(!afterClear, "clear 幂等：有效值回落作者意图 = 关闭")
      assert(!author, "过渡与 clear 均**不得**污染作者意图层（写侧两条通路互不覆盖）")
  }

end PluginDispatchFaceSpec
