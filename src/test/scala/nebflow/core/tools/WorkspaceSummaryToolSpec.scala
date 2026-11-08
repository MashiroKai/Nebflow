package nebflow.core.tools

import cats.effect.IO
import io.circe.JsonObject
import io.circe.parser.parse
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.{AgentDef, RootAgentIdentity}
import nebflow.core.delegate.WorkspaceAdvisor
import nebflow.core.delegate.WorkspaceLedger
import nebflow.core.delegate.WorkspaceLedger.Workspace
import nebflow.core.project.ProjectStore
import nebflow.shared.PathUtil

import scala.concurrent.duration.*

/**
 * workspace_summary 验收（worktree-merger delivery · Priority E）：
 *   - 授权 = root 专属（mount face RootOrchestrationTools + 防声明逃逸
 *     RootExclusiveTools + 工具内 isRootAgent 身份闸双保险，MergerRequestTool 同款）；
 *   - 读面 = 一个仓库的协调全景：工作区事实、pending 请求与 origin、advisor 建议行
 *     （含状态与 origin）、merger 初始化状态；
 *   - dismiss-suggestion = 只动建议行、锚定精确 headCommit；查无此行显式报错。
 */
class WorkspaceSummaryToolSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 120.seconds

  private val previous = PathUtil.dataRoot
  private var root: os.Path = os.pwd

  override def beforeAll(): Unit =
    root = os.temp.dir(prefix = "workspace-summary-tool-")
    PathUtil.setDataRoot(root)

  override def afterAll(): Unit =
    PathUtil.setDataRoot(previous)
    os.remove.all(root)

  private def rootCtx: ToolContext =
    ToolContext(projectRoot = root.toString, agentDef = Some(AgentDef(name = RootAgentIdentity.Name, description = "root face")))

  private def fixture(): os.Path =
    val repo = os.temp.dir(prefix = "workspace-summary-repo-") / "repo"
    os.makeDir.all(repo)
    def git(args: String*) =
      os.proc(Seq("git") ++ args).call(cwd = repo, stdout = os.Pipe, stderr = os.Pipe).out.text().trim
    git("init", "-b", "main")
    git("config", "user.name", "test")
    git("config", "user.email", "test@example.invalid")
    git("commit", "--allow-empty", "-m", "base")
    val base = git("rev-parse", "HEAD")
    val tree = repo / os.up / "task"
    git("worktree", "add", "-b", "delegate/task", tree.toString, "main")
    os.write(tree / "result.txt", "committed result")
    os.proc("git", "add", "result.txt").call(cwd = tree)
    os.proc("git", "commit", "-m", "result").call(cwd = tree, stdout = os.Pipe)
    val head = git("rev-parse", "delegate/task")
    val ledger = WorkspaceLedger.default
    ledger.register(
      Workspace("task", "example", WorkspaceLedger.repositoryKey(repo), tree.toNIO.toRealPath().toString,
        "delegate/task", "main", base, "delegate-worker", "dispatcher")
    )
    ledger.merger("example", WorkspaceLedger.repositoryKey(repo), "delegate-merger")
    import nebflow.core.delegate.DelegateRegistry
    DelegateRegistry.put(
      DelegateRegistry.Record(
        id = "delegate-worker",
        executor = "default",
        cwd = "/tmp",
        project = Some("example"),
        taskId = None,
        parentSessionId = "root-main",
        title = "ship the feature",
        createdAt = System.currentTimeMillis(),
        reportStatus = Some(WorkspaceAdvisor.FinishReport)
      )
    )
    ProjectStore.create("example", repo.toString, None, "# Project instructions").unsafeRunSync()
    tree
  end fixture

  test("non-root sessions are refused; the tool is a root-only read face") {
    val ctx = ToolContext(projectRoot = root.toString, sessionId = Some("delegate-worker"))
    for summary <- WorkspaceSummaryTool.call(JsonObject("project" -> io.circe.Json.fromString("example")), ctx)
    yield assert(summary.isLeft, s"a plain delegate must be refused: $summary")
    end for
  }

  test("the root face reads suggestions, requests with origins and merger initialization; dismissal is anchored") {
    fixture()
    // Seed one advisor suggestion through the advisor itself.
    val advisor = WorkspaceAdvisor.default
    advisor.evaluate(WorkspaceLedger.default).unsafeRunSync()
    assert(advisor.rows().toOption.getOrElse(Nil).nonEmpty, "fixture sanity: one suggestion row")
    for
      raw <- WorkspaceSummaryTool.call(
        JsonObject("project" -> io.circe.Json.fromString("example")),
        rootCtx
      )
      dismissed <- WorkspaceSummaryTool.call(
        JsonObject(
          "project" -> io.circe.Json.fromString("example"),
          "action" -> io.circe.Json.fromString("dismiss-suggestion"),
          "workspaceId" -> io.circe.Json.fromString("task"),
          "headCommit" -> io.circe.Json.fromString(advisor.rows().toOption.get.head.headCommit)
        ),
        rootCtx
      )
      unknown <- WorkspaceSummaryTool.call(
        JsonObject(
          "project" -> io.circe.Json.fromString("example"),
          "action" -> io.circe.Json.fromString("dismiss-suggestion"),
          "workspaceId" -> io.circe.Json.fromString("task"),
          "headCommit" -> io.circe.Json.fromString("f" * 40)
        ),
        rootCtx
      )
    yield
      val json = parse(raw.toOption.getOrElse(fail("summary must succeed"))).toOption.getOrElse(fail("payload is JSON"))
      val suggestions = json.hcursor.downField("suggestions").focus.getOrElse(io.circe.Json.Null)
      assert(suggestions.asArray.exists(_.nonEmpty), s"suggestions ride the summary: $json")
      assert(suggestions.asArray.get.head.hcursor.get[String]("state").toOption.contains("suggested"), "row state is surfaced")
      assert(json.hcursor.downField("mergeRequests").focus.exists(_.isArray), "requests ride the summary")
      assert(json.hcursor.downField("mergeRequestOrigins").focus.exists(_.isObject), "origins ride the summary")
      assert(json.hcursor.downField("workspaces").focus.exists(_.asArray.get.head.hcursor.downField("facts").focus.isDefined),
        "per-workspace facts ride the summary")
      assert(json.hcursor.downField("merger").focus.isDefined, "merger rides the summary")
      assert(json.hcursor.downField("mergerInitialization").focus.exists(_.isNull), "no bootstrap means an explicit null")
      assert(dismissed.isRight, s"dismissal succeeds for the exact row: $dismissed")
      assert(dismissed.toOption.get.contains("dismissed"))
      assert(unknown.isLeft, "dismissal is anchored to the exact head commit")
      val rows = advisor.rows().toOption.getOrElse(Nil)
      assertEquals(rows.map(_.state), List(WorkspaceAdvisor.StateDismissed), "the row is dismissed in the store")
    end for
  }

  test("panel-submitted requests read as origin=panel; agent-submitted as origin=agent") {
    import nebflow.core.delegate.WorkspaceLedger.MergeRequest
    val panel = MergeRequest("p", "repo", "m", "authenticated project panel selection: task", Nil)
    val agent = MergeRequest("a", "repo", "m", "user authorized via merger_request in session", Nil)
    assertEquals(WorkspaceSummaryTool.requestOrigin(panel), "panel")
    assertEquals(WorkspaceSummaryTool.requestOrigin(agent), "agent")
  }

  test("the tool name is a root-face member and blocks declaration escape") {
    import nebflow.agent.AgentCore
    assert(AgentCore.RootOrchestrationTools.contains(WorkspaceSummaryTool.Name), "root face carries workspace_summary")
    assert(AgentCore.RootExclusiveTools.contains(WorkspaceSummaryTool.Name), "declaration escape is blocked")
    assert(nebflow.core.tools.ToolRegistry.TOOL_MAP.contains(WorkspaceSummaryTool.Name), "the tool is registered")
  }

end WorkspaceSummaryToolSpec
