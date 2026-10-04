package nebflow.core.project

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import fs2.Stream
import io.circe.Json
import munit.CatsEffectSuite
import nebflow.actor.ActorSystem
import nebflow.agent.{SharedResources, SpecResources}
import nebflow.core.flow.TeamSessionRegistry
import nebflow.core.tools.{NodeEditTool, ToolContext}
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, PathUtil, StreamChunk}

import scala.concurrent.duration.*

/**
 * worktree auto-creation baseline spec (engine-wtbase batch, 2026-10-04).
 *
 * Defect being pinned: `NodeEditTool.createWorktreeFor` used to pick its baseline with
 * `git rev-parse --verify --quiet main` and fall back to `HEAD` only when `main` was
 * absent -- so every `worktree=true` node started on MAIN even when the repo HEAD was
 * on an integration line ahead of it. Measured production shape: three parallel nodes
 * were created on main (a5b716d55) while the integration line tip was e3e784ede, i.e.
 * a parallel batch silently started 27 commits behind the integration line.
 *
 * All assertions here are BEHAVIOURAL: the baseline is read back with
 * `git rev-parse HEAD` inside the worktree that the tool actually created, never by
 * asserting on a source-level string constant. The fixture repos give a deterministic
 * `main`-vs-HEAD separation, so the spec does not depend on the ambient ref layout of
 * whatever worktree the suite happens to run in.
 *
 * Scope note: a model-visible parameter for choosing the baseline (e.g. a new
 * `worktreeBase` field in the Tool schema) is intentionally NOT part of this batch --
 * it would change the model-visible tool description and therefore needs the author's
 * line-by-line review (AGENTS.md §16). This spec covers only the tool-visible
 * behaviour; the engine-internal `baseRef` parameter of `createWorktreeFor` cannot be
 * exercised from a spec (the method is object-private and its single call site pins
 * `"HEAD"`), so its presence is evidenced by the diff, not here.
 */
class NodeEditWorktreeBaselineSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 180.seconds

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
    TeamSessionRegistry.clear.unsafeRunSync()

  override def afterEach(context: munit.AfterEach): Unit = ProjectRuntimeRegistry.clear

  // ── fixture harness (NodeAcceptanceSpec patterns) ───────────────────

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

  /** git exit code (no throw) -- for `rev-parse --verify --quiet` / `merge-base --is-ancestor`. */
  private def gitExit(cwd: os.Path, args: String*): Int =
    os.proc(Seq("git", "-C", cwd.toString) ++ args)
      .call(cwd = cwd, check = false, mergeErrIntoOut = true)
      .exitCode

  /** Fresh single-branch repo started on `branch`. */
  private def mkRepo(tag: String, branch: String): os.Path =
    val ws = tempRoot / s"ws-$tag-${scala.util.Random.nextInt(100000)}"
    os.makeDir.all(ws)
    git(ws, "init", "-q", "-b", branch)
    git(ws, "config", "user.email", "spec@nebflow.local")
    git(ws, "config", "user.name", "spec")
    ws

  private def commit(ws: os.Path, msg: String): Unit =
    git(ws, "commit", "-q", "--allow-empty", "-m", msg)

  /** Fixture repo + runtime + ctx; `build` shapes the commit graph. */
  private def wtProject(
    tag: String,
    branch: String = "main"
  )(build: os.Path => Unit): (os.Path, ActorSystem, SharedResources, ProjectRuntime, ToolContext) =
    val ws = mkRepo(tag, branch)
    build(ws)
    val system = ActorSystem(s"wtbase-$tag-${scala.util.Random.nextInt(100000)}")
    val res = SpecResources.mkResources(system, tempRoot, new RecordingLlm).unsafeRunSync()
    val rt = mountProject(s"wtbase-$tag", ws, system, res).unsafeRunSync()
    (ws, system, res, rt, mkCtx(res, system, ws.toString))

  private def storeOf(rt: ProjectRuntime): IO[FlowMapState] = rt.store.snapshot

  private def derivedWorktree(s: FlowMapState, nodeName: String): Option[String] =
    s.nodes.values.find(_.name == nodeName).flatMap(_.worktree)

  private val NodeName = "wt-baseline"

  /** Unique task text per create: NodeEdit's duplicate-dispatch guard rejects a second
   *  dispatch whose task text is identical to a completed node's in the same project. */
  private def taskText(tag: String) = s"wt-base-$tag"

  private def createWtNode(
    project: String,
    name: String,
    ctx: ToolContext,
    taskTag: String
  ): IO[Either[String, String]] =
    nodeEdit(
      nodeInput(
        project,
        name,
        "description" -> Json.fromString("worktree baseline spec node"),
        "task" -> Json.fromString(taskText(taskTag)),
        "worktree" -> Json.fromBoolean(true),
        "out" -> Json.fromString("Nebula")
      ),
      ctx
    )

  // ── 1. default baseline = repo HEAD at creation time ────────────────

  test("WTB-1 default baseline = repo HEAD when main sits BEHIND the checked-out line") {
    val (ws, system, _, rt, ctx) = wtProject("behind") { ws =>
      commit(ws, "main-base") // main = A
      git(ws, "branch", "exec-line")
      git(ws, "checkout", "-q", "exec-line")
      commit(ws, "integration-1")
      commit(ws, "integration-2") // HEAD = C; main is 2 commits behind
    }
    val mainSha = git(ws, "rev-parse", "main")
    val headSha = git(ws, "rev-parse", "HEAD")
    assert(mainSha != headSha, s"fixture must separate main from HEAD (main=$mainSha head=$headSha)")
    for
      r <- createWtNode("wtbase-behind", NodeName, ctx, "behind")
      s <- storeOf(rt)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(r.isRight, s"worktree=true create must succeed, got: $r")
      val wt = derivedWorktree(s, NodeName)
      assert(wt.isDefined, "NodeDef.worktree must store the derived bare name")
      val wtPath = ws / ".nebflow" / "worktrees" / wt.get
      assert(os.exists(wtPath / ".git"), s"derived worktree dir must exist at $wtPath")
      val baseSha = git(wtPath, "rev-parse", "HEAD")
      assertEquals(
        baseSha,
        headSha,
        s"new worktree must be based on the repo HEAD at creation time (head=$headSha), got $baseSha"
      )
      assert(baseSha != mainSha, s"new worktree must NOT be based on main ($mainSha) when HEAD is ahead of it")
      assertEquals(
        gitExit(wtPath, "merge-base", "--is-ancestor", mainSha, baseSha),
        0,
        s"fixture shape: main ($mainSha) should be an ancestor of the base ($baseSha)"
      )
    end for
  }

  // ── 2. main is not required and not consulted ───────────────────────

  test("WTB-2 repo with NO 'main' branch: worktree still created, based on HEAD") {
    val (ws, system, _, rt, ctx) = wtProject("nomain", "trunk") { ws =>
      commit(ws, "trunk-base")
      commit(ws, "trunk-tip")
    }
    // Fixture sanity: this repo has no `main` ref at all.
    assert(
      gitExit(ws, "rev-parse", "--verify", "--quiet", "main") != 0,
      "fixture must not carry a `main` ref"
    )
    val headSha = git(ws, "rev-parse", "HEAD")
    for
      r <- createWtNode("wtbase-nomain", NodeName, ctx, "nomain")
      s <- storeOf(rt)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(r.isRight, s"worktree=true must not require a `main` branch, got: $r")
      val wtPath = ws / ".nebflow" / "worktrees" / derivedWorktree(s, NodeName).get
      assertEquals(git(wtPath, "rev-parse", "HEAD"), headSha, "baseline must be HEAD when no main exists")
    end for
  }

  test("WTB-3 'main' ahead of HEAD is NOT consulted: baseline stays HEAD") {
    val (ws, system, _, rt, ctx) = wtProject("mainahead") { ws =>
      commit(ws, "main-base") // main = A
      git(ws, "branch", "exec-line")
      git(ws, "checkout", "-q", "exec-line")
      commit(ws, "integration-1") // HEAD = B
      git(ws, "checkout", "-q", "main")
      commit(ws, "main-ahead") // main = C (diverged, ahead)
      git(ws, "checkout", "-q", "exec-line")
    }
    val mainSha = git(ws, "rev-parse", "main")
    val headSha = git(ws, "rev-parse", "HEAD")
    assert(mainSha != headSha, s"fixture must diverge main from HEAD (main=$mainSha head=$headSha)")
    for
      r <- createWtNode("wtbase-mainahead", NodeName, ctx, "mainahead")
      s <- storeOf(rt)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(r.isRight, s"worktree=true create must succeed, got: $r")
      val wtPath = ws / ".nebflow" / "worktrees" / derivedWorktree(s, NodeName).get
      val baseSha = git(wtPath, "rev-parse", "HEAD")
      assertEquals(baseSha, headSha, s"baseline must be HEAD ($headSha), got $baseSha")
      assert(baseSha != mainSha, s"the `main` ref ($mainSha) must not be read as the baseline")
    end for
  }

  // ── 3. pre-existing behaviour must not regress ──────────────────────

  test("WTB-4 regression: branch-name derivation + collision suffix survive") {
    val (ws, system, _, rt, ctx) = wtProject("regress") { ws => commit(ws, "base") }
    for
      // "wt 同" (space) and "wt-同" sanitize to the same base name => -2 suffix on the second
      r1 <- createWtNode("wtbase-regress", "wt 同", ctx, "regress-1")
      r2 <- createWtNode("wtbase-regress", "wt-同", ctx, "regress-2")
      s <- storeOf(rt)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(r1.isRight && r2.isRight, s"both creates must succeed, got: $r1 / $r2")
      val wt1 = derivedWorktree(s, "wt 同")
      val wt2 = derivedWorktree(s, "wt-同")
      assert(wt1.isDefined && wt2.isDefined, s"both nodes must store a derived worktree, got: $wt1 / $wt2")
      assertEquals(wt1.get, "wt-同", "sanitize must map whitespace to '-' and keep CJK (derivation intact)")
      assert(wt1.get != wt2.get, s"derived names must be unique, got: $wt1 / $wt2")
      assert(wt2.get.endsWith("-2"), s"collision candidate must carry the -2 suffix, got: ${wt2.get}")
      // same-name branch, derived from the same sanitized name
      assertEquals(git(ws, "branch", "--show-current"), "main", "fixture repo branch sanity")
      assert(
        git(ws, "branch", "--list", wt1.get).nonEmpty,
        s"a branch named '${wt1.get}' must exist in the workspace repo"
      )
      assert(
        git(ws, "branch", "--list", wt2.get).nonEmpty,
        s"a branch named '${wt2.get}' must exist in the workspace repo"
      )
      assert(os.exists(ws / ".nebflow" / "worktrees" / wt2.get / ".git"), "second worktree dir must exist")
    end for
  }

  test("WTB-5 regression: non-git workspace still fails fast, no node created") {
    val ws = tempRoot / s"ws-nogit-${scala.util.Random.nextInt(100000)}"
    os.makeDir.all(ws) // plain dir (no .git; show-toplevel != ws)
    val system = ActorSystem(s"wtbase-nogit-${scala.util.Random.nextInt(100000)}")
    val res = SpecResources.mkResources(system, tempRoot, new RecordingLlm).unsafeRunSync()
    val rt = mountProject("wtbase-nogit", ws, system, res).unsafeRunSync()
    val ctx = mkCtx(res, system, ws.toString)
    for
      r <- createWtNode("wtbase-nogit", NodeName, ctx, "nogit")
      s <- storeOf(rt)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(
        r.isLeft && r.left.exists(_.contains("git repository")),
        s"non-git workspace must fail fast with an actionable message, got: $r"
      )
      assert(s.nodes.isEmpty, "fail-fast: node must NOT be created")
    end for
  }

end NodeEditWorktreeBaselineSpec
