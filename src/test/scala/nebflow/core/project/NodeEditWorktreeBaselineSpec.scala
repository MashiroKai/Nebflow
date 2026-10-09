package nebflow.core.project

import cats.effect.IO
import fs2.Stream
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.ActorSystem
import nebflow.agent.{SharedResources, SpecResources}
import nebflow.core.GitWorktrees
import nebflow.core.tools.{NodeEditTool, ToolContext}
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, PathUtil, StreamChunk}

import scala.concurrent.duration.*

/**
 * worktree 参数面收口 spec（N1 刀2 D1 重写，2026-10-06；前身 = engine-wtbase 批
 * 2026-10-04 的 NodeEdit 代建基线验收）。
 *
 * 语义反转：`worktree` 不再是 NodeEdit 参数——分支/worktree 代建收归 delegate 层
 * 判定机（S13）与 Delegate spawn 腿；NodeEdit 首段 seal（`NODE_WORKTREE_UNSUPPORTED`）
 * 在一切校验/建位/git 探测之前把任何形态的 `worktree` 键拒掉。本 spec 钉两面：
 *
 *  - WTB-1/2/5：NodeEdit seal 面（字符串/布尔/非 git 工作区一视同仁；零节点零
 *    worktree 目录零 git 探测副作用）。
 *  - WTB-3/4：**GitWorktrees 公用 util 本体保留**——`sanitizeRefName` 派生规则与
 *    `createWorktree` 建制能力直测（delegate spawn 腿等其余消费点继续用；util 不随
 *    NodeEdit 参数退役而退役）。
 */
class NodeEditWorktreeBaselineSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 120.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-worktree-baseline"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot / "agents" / "general")

  os.write.over(
    tempRoot / "agents" / "general" / "agent.json",
    """{"name":"general","description":"general executor","tools":[],"category":"standalone"}"""
  )
  os.write.over(tempRoot / "agents" / "general" / "system.md", "# general\n")

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)

  override def beforeEach(context: munit.BeforeEach): Unit =
    ProjectRuntimeRegistry.clear

  override def afterEach(context: munit.AfterEach): Unit = ProjectRuntimeRegistry.clear

  // ── fixture harness（NodeAcceptanceSpec patterns）──────────────────────

  private class RecordingLlm extends LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new RuntimeException("send not expected"))

    def sendStream(
      req: LlmRequest,
      onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] =
      Stream(StreamChunk.TextDelta("ok"), StreamChunk.Done(None, None))

  private def mkCtx(res: SharedResources, system: ActorSystem, ws: String): ToolContext =
    ToolContext(
      projectRoot = ws,
      sessionId = Some("wt-baseline-sid"),
      rootSessionId = Some("nebula-root"),
      sharedResources = Some(res),
      actorSystem = Some(system)
    )

  private def mountProject(
    name: String,
    ws: os.Path,
    system: ActorSystem,
    res: SharedResources
  ): IO[ProjectRuntime] =
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

  /** git with check=true (throws on non-zero exit); output trimmed. */
  private def git(cwd: os.Path, args: String*): String =
    os.proc(Seq("git", "-C", cwd.toString) ++ args)
      .call(cwd = cwd, check = true, mergeErrIntoOut = true)
      .out
      .trim()

  /** Fresh single-branch repo started on `branch`. */
  private def mkRepo(tag: String, branch: String = "main"): os.Path =
    val ws = tempRoot / s"ws-$tag-${scala.util.Random.nextInt(100000)}"
    os.makeDir.all(ws)
    git(ws, "init", "-q", "-b", branch)
    git(ws, "config", "user.email", "spec@nebflow.local")
    git(ws, "config", "user.name", "spec")
    git(ws, "commit", "-q", "--allow-empty", "-m", "init")
    ws

  private def plainWorkspace(tag: String): os.Path =
    val ws = tempRoot / s"ws-plain-$tag-${scala.util.Random.nextInt(100000)}"
    os.makeDir.all(ws)
    ws

  private def harness(tag: String, ws: os.Path): (ActorSystem, SharedResources, ProjectRuntime, ToolContext) =
    val system = ActorSystem(s"wtbase-$tag-${scala.util.Random.nextInt(100000)}")
    val res = SpecResources.mkResources(system, tempRoot, new RecordingLlm).unsafeRunSync()
    val rt = mountProject(s"wtbase-$tag", ws, system, res).unsafeRunSync()
    (system, res, rt, mkCtx(res, system, ws.toString))

  // ── 1. NodeEdit seal 面（D1：键在场即拒，先于一切校验/建位/git 探测）──────

  test("WTB-1 worktree=true on a git repo → NODE_WORKTREE_UNSUPPORTED; no node, no worktree dir, no branch") {
    val ws = mkRepo("seal-git")
    val (system, _, rt, ctx) = harness("seal-git", ws)
    for
      r <- nodeEdit(
        nodeInput(
          "wtbase-seal-git",
          "wt-baseline",
          "description" -> Json.fromString("seal spec node"),
          "task" -> Json.fromString("seal task"),
          "worktree" -> Json.fromBoolean(true),
          "out" -> Json.fromString("Nebula")
        ),
        ctx
      )
      s <- rt.store.snapshot
      noDir <- IO.blocking(!os.exists(ws / ".nebflow" / "worktrees"))
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(
        r.isLeft && r.left.exists(_.contains("NODE_WORKTREE_UNSUPPORTED")),
        s"worktree key must hit the seal, got: $r"
      )
      assert(s.nodes.isEmpty, "seal precedes creation: no node from the sealed param")
      assert(noDir, "NodeEdit must not provision worktrees anymore")
    end for
  }

  test("WTB-2 worktree legacy string form → the same seal (any JSON shape is refused)") {
    val ws = mkRepo("seal-str")
    val (system, _, rt, ctx) = harness("seal-str", ws)
    for
      r <- nodeEdit(
        nodeInput(
          "wtbase-seal-str",
          "n-str",
          "worktree" -> Json.fromString("pre-made"),
          "description" -> Json.fromString("d"),
          "task" -> Json.fromString("t"),
          "out" -> Json.fromString("Nebula")
        ),
        ctx
      )
      s <- rt.store.snapshot
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(
        r.isLeft && r.left.exists(_.contains("NODE_WORKTREE_UNSUPPORTED")),
        s"string worktree must hit the same seal, got: $r"
      )
      assert(s.nodes.isEmpty, "no node from the sealed param")
    end for
  }

  test("WTB-5 non-git workspace → the seal fires with NO git probe (retired before the repo check)") {
    val ws = plainWorkspace("nogit")
    val (system, _, rt, ctx) = harness("nogit", ws)
    for
      r <- nodeEdit(
        nodeInput(
          "wtbase-nogit",
          "wt-baseline",
          "description" -> Json.fromString("seal spec node"),
          "task" -> Json.fromString("seal task"),
          "worktree" -> Json.fromBoolean(true),
          "out" -> Json.fromString("Nebula")
        ),
        ctx
      )
      s <- rt.store.snapshot
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(
        r.isLeft && r.left.exists(_.contains("NODE_WORKTREE_UNSUPPORTED")),
        s"non-git workspace must hit the same seal (no 'git repository' message anymore), got: $r"
      )
      assert(s.nodes.isEmpty, "fail-fast: node must NOT be created")
    end for
  }

  // ── 2. GitWorktrees util 本体保留（delegate spawn 腿的消费面）────────────

  test("WTB-3 sanitizeRefName derivation rules survive (whitespace→'-', CJK kept, leading dots/escapes clipped)") {
    assertEquals(GitWorktrees.sanitizeRefName("wt 同"), "wt-同")
    assertEquals(GitWorktrees.sanitizeRefName("wt-同"), "wt-同")
    // 前导 '.' 剥除（("../escape" → 首点剥除后不再含 ".."；斜杠是 git ref 合法字符，
    // util 原样保留——路径安全由调用方目录基座承载，非本函数职责）
    assert(!GitWorktrees.sanitizeRefName("../escape").contains(".."), "leading-dot escape clipped")
    // 派生同基名（碰撞面判据的输入端）："wt 同" 与 "wt-同" sanitize 到同一 token
    assertEquals(GitWorktrees.sanitizeRefName("wt 同"), GitWorktrees.sanitizeRefName("wt-同"))
  }

  test("WTB-4 createWorktree still provisions worktree+branch (util face intact for the delegate spawn leg)") {
    val ws = mkRepo("util")
    for
      created <- IO.blocking(
        GitWorktrees.createWorktree(ws, dirBase = "util-wt", branch = "util-wt", baseRef = "HEAD", label = "util-wt")
      )
      second <- IO.blocking(
        GitWorktrees.createWorktree(ws, dirBase = "util-wt", branch = "util-wt", baseRef = "HEAD", label = "util-wt-2")
      )
    yield
      val c = created.getOrElse(fail(s"util create must succeed: $created"))
      assert(os.exists(c.path / ".git"), s"worktree dir must exist: ${c.path}")
      assertEquals(git(ws, "branch", "--list", c.name).nonEmpty, true, s"same-name branch must exist: ${c.name}")
      // 碰撞后缀（freeName 单点）：第二个同名请求必须落到 -2 目录名（建制能力不回归）
      val c2 = second.getOrElse(fail(s"collision create must succeed: $second"))
      assertEquals(c2.name, "util-wt-2", "collision candidate must carry the -2 suffix")
    end for
  }

end NodeEditWorktreeBaselineSpec
