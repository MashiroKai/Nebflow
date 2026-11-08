package nebflow.core.tools

import cats.effect.IO
import cats.syntax.all.*
import io.circe.{Json, JsonObject}
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.AgentCommand
import nebflow.core.delegate.{DelegateRegistry, WorkspaceLedger}
import nebflow.core.mail.SessionMailbox
import nebflow.core.project.TaskLedgerStore
import nebflow.shared.PathUtil

import scala.util.Try

/**
 * worktree_context (Priority D delivery): access control, summary sections with
 * explicit missing ranges, and mail-transcript pagination over a seeded fixture
 * (temp git repo + worktree + ledger + registry + mailbox + task ledger).
 */
class WorktreeContextToolSpec extends CatsEffectSuite:
  private val previous = PathUtil.dataRoot
  private var root: os.Path = os.pwd

  override def beforeEach(context: BeforeEach): Unit =
    root = os.temp.dir(prefix = "worktree-context-")
    PathUtil.setDataRoot(root / "data")

  override def afterEach(context: AfterEach): Unit =
    PathUtil.setDataRoot(previous)
    os.remove.all(root)

  private def ctxFor(sessionId: Option[String], agentDef: Option[nebflow.actor.AgentDef] = None, depth: Int = 0) =
    ToolContext(projectRoot = root.toString, sessionId = sessionId, agentDef = agentDef, depth = depth)

  private def rootCtx = ctxFor(Some("root-session"), Some(nebflow.actor.AgentDef(name = nebflow.actor.RootAgentIdentity.Name, description = "")), 0)

  private def fixture: IO[(os.Path, os.Path)] = IO.blocking {
    val repo = root / "repo"
    val tree = root / "task"
    os.makeDir.all(repo)
    def git(args: String*) =
      os.proc(Seq("git") ++ args).call(cwd = repo, stdout = os.Pipe, stderr = os.Pipe).out.text().trim
    git("init", "-b", "main"); git("config", "user.name", "test"); git("config", "user.email", "test@example.invalid")
    git("commit", "--allow-empty", "-m", "base")
    val base = git("rev-parse", "HEAD")
    git("worktree", "add", "-b", "delegate/task", tree.toString, "main")
    os.write(tree / "result.txt", "committed result")
    os.proc("git", "add", "result.txt").call(cwd = tree)
    os.proc("git", "commit", "-m", "result").call(cwd = tree, stdout = os.Pipe)
    WorkspaceLedger.default.register(
      WorkspaceLedger.Workspace(
        "task",
        "example",
        WorkspaceLedger.repositoryKey(repo),
        tree.toNIO.toRealPath().toString,
        "delegate/task",
        "main",
        base,
        "delegate-owner",
        "kernel"
      )
    )
    (repo, tree)
  }

  private def installMerger(repo: os.Path, id: String): Unit = {
    WorkspaceLedger.default.merger("example", WorkspaceLedger.repositoryKey(repo), id)
    DelegateRegistry.put(
      DelegateRegistry.Record(
        id = id,
        executor = "default",
        cwd = repo.toString,
        project = Some("example"),
        taskId = None,
        parentSessionId = "root",
        title = "project merger",
        createdAt = System.currentTimeMillis(),
        mode = Some("merger")
      )
    )
  }

  private def installOwnerWithTask(): Unit = {
    val created = TaskLedgerStore.open().createSyncReturningId(
      title = "integrate the workspace",
      project = Some("example")
    )
    val id = Try(created.toOption.get).getOrElse(fail(s"task ledger create failed: $created"))
    DelegateRegistry.put(
      DelegateRegistry.Record(
        id = "delegate-owner",
        executor = "default",
        cwd = (root / "task").toString,
        project = Some("example"),
        taskId = Some(id),
        parentSessionId = "root",
        title = "owner session",
        createdAt = System.currentTimeMillis(),
        mode = Some("kernel")
      )
    )
  }

  private def seedMail(sid: String, n: Int): IO[Unit] =
    (1 to n).toList.traverse_ { i =>
      SessionMailbox.mailOnce(
        sid,
        AgentCommand.ExternalEvent("mail", "info", s"message $i for $sid"),
        "mail",
        s"context-spec:$sid:$i"
      ).void
    }

  private def parse(result: Either[ToolError, String]): Json =
    io.circe.parser.parse(result.toOption.get).toOption.get

  test("plain development delegates are refused; merger and main agent are allowed") {
    for
      pair <- fixture
      (repo, _) = pair
      _ <- IO.blocking(installMerger(repo, "delegate-spec-merger"))
      plain <- WorktreeContextTool.call(
        JsonObject("workspaceId" -> Json.fromString("task")),
        ctxFor(Some("delegate-owner"))
      )
      merger <- WorktreeContextTool.call(
        JsonObject("workspaceId" -> Json.fromString("task")),
        ctxFor(Some("delegate-spec-merger"))
      )
      main <- WorktreeContextTool.call(
        JsonObject("workspaceId" -> Json.fromString("task")),
        rootCtx
      )
    yield
      assert(plain.isLeft, "a plain delegate must be refused")
      assert(merger.isRight, "the recorded merger may read its repository")
      assert(main.isRight, "the main agent may read any managed workspace")
  }

  test("a recorded merger cannot read another repository's workspaces") {
    for
      pair <- fixture
      (repo, _) = pair
      _ <- IO.blocking {
        installMerger(repo, "delegate-spec-merger")
        // A second, independent repository with its own managed workspace: the
        // merger of the first repository must not read across the boundary.
        val otherRepo = root / "other-repo"
        os.makeDir.all(otherRepo)
        os.proc("git", "init", "-b", "main").call(cwd = otherRepo, stdout = os.Pipe, stderr = os.Pipe)
        os.proc("git", "config", "user.name", "test").call(cwd = otherRepo)
        os.proc("git", "config", "user.email", "test@example.invalid").call(cwd = otherRepo)
        os.proc("git", "commit", "--allow-empty", "-m", "other base").call(cwd = otherRepo, stdout = os.Pipe)
        WorkspaceLedger.default.register(
          WorkspaceLedger.Workspace(
            "other-task",
            "example",
            WorkspaceLedger.repositoryKey(otherRepo),
            otherRepo.toString,
            "main",
            "main",
            os.proc("git", "rev-parse", "HEAD").call(cwd = otherRepo).out.text().trim,
            "delegate-other-owner",
            "kernel"
          )
        )
      }
      outside <- WorktreeContextTool.call(
        JsonObject("workspaceId" -> Json.fromString("other-task")),
        ctxFor(Some("delegate-spec-merger"))
      )
    yield assert(outside.left.exists(_.message.contains("authorized repository")))
  }

  test("sections carry sources and explicit missing ranges over a seeded fixture") {
    for
      pair <- fixture
      (repo, tree) = pair
      _ <- IO.blocking {
        installMerger(repo, "delegate-spec-merger")
        installOwnerWithTask()
      }
      _ <- seedMail("delegate-owner", 2)
      result <- WorktreeContextTool.call(
        JsonObject("workspaceId" -> Json.fromString("task")),
        ctxFor(Some("delegate-spec-merger"))
      )
      json = parse(result)
      sections = json.hcursor.downField("sections")
      _ = assert(sections.downField("workspace").downField("workspace").get[String]("branch").toOption.contains("delegate/task"))
      _ = assert(sections.downField("task").downField("task").get[String]("title").toOption.contains("integrate the workspace"))
      mail = sections.downField("mail")
      _ = assertEquals(mail.downField("missing").as[List[String]].toOption.getOrElse(Nil).length, 1,
        "the merger mailbox has no mail: exactly one explicit missing marker")
      _ = assert(mail.downField("entries").as[List[Json]].toOption.getOrElse(Nil).exists(_.hcursor.get[String]("payload").toOption.contains("message 1 for delegate-owner")))
      _ = assert(sections.downField("nodes").get[String]("missing").toOption.exists(_.contains("no chain/graph record")),
        "a missing host must read as an explicit missing range, not an empty success")
      _ = assert(sections.downField("verification").get[String]("missing").toOption.contains("no verification record for this workspace"))
      _ = assert(sections.downField("history").get[String]("missing").toOption.contains("no recorded merge or decision history for this workspace"))
      commits = sections.downField("commits").downField("commits").as[List[Json]].toOption.getOrElse(Nil)
      _ = assert(commits.nonEmpty, "recent commits summarize the workspace branch")
      _ = assert(commits.exists(_.hcursor.get[String]("subject").toOption.contains("result")))
    yield ()
  }

  test("unknown workspace and missing-owner rows read as explicit missing sections") {
    for
      pair <- fixture
      (repo, _) = pair
      _ <- IO.blocking(installMerger(repo, "delegate-spec-merger"))
      result <- WorktreeContextTool.call(
        JsonObject("workspaceId" -> Json.fromString("task")),
        ctxFor(Some("delegate-spec-merger"))
      )
      sections = parse(result).hcursor.downField("sections")
      _ = assert(sections.downField("task").get[String]("missing").toOption.exists(_.contains("no delegate registry record")))
    yield ()
  }

  test("mail pagination returns bounded pages with a next token") {
    for
      pair <- fixture
      (repo, _) = pair
      _ <- IO.blocking {
        installMerger(repo, "delegate-spec-merger")
        DelegateRegistry.put(
          DelegateRegistry.Record(
            id = "delegate-owner",
            executor = "default",
            cwd = (root / "task").toString,
            project = Some("example"),
            taskId = None,
            parentSessionId = "root",
            title = "owner session",
            createdAt = System.currentTimeMillis(),
            mode = Some("kernel")
          )
        )
      }
      _ <- seedMail("delegate-owner", 5)
      first <- WorktreeContextTool.call(
        JsonObject("workspaceId" -> Json.fromString("task"), "pageSize" -> Json.fromInt(2)),
        ctxFor(Some("delegate-spec-merger"))
      )
      page1 = parse(first)
      entries1 = page1.hcursor.downField("sections").downField("mail").downField("entries").as[List[Json]].toOption.getOrElse(Nil)
      next = page1.hcursor.downField("pagination").get[Int]("nextPageToken").toOption.get
      _ = assertEquals(entries1.size, 2)
      _ = assertEquals(page1.hcursor.downField("pagination").get[Int]("total").toOption.get, 5)
      second <- WorktreeContextTool.call(
        JsonObject("workspaceId" -> Json.fromString("task"), "pageSize" -> Json.fromInt(2), "pageToken" -> Json.fromInt(next)),
        ctxFor(Some("delegate-spec-merger"))
      )
      page2 = parse(second)
      entries2 = page2.hcursor.downField("sections").downField("mail").downField("entries").as[List[Json]].toOption.getOrElse(Nil)
      _ = assertEquals(entries2.size, 2)
      _ = assert(entries1.map(_.hcursor.get[Long]("seq").toOption.get) != entries2.map(_.hcursor.get[Long]("seq").toOption.get),
        "page two must advance the transcript window")
    yield ()
  }

  test("verification and history sections surface recorded merge decisions") {
    for
      pair <- fixture
      (repo, _) = pair
      _ <- IO.blocking {
        installMerger(repo, "delegate-spec-merger")
        val ledger = WorkspaceLedger.default
        def git(args: String*) =
          os.proc(Seq("git") ++ args).call(cwd = repo, stdout = os.Pipe, stderr = os.Pipe).out.text().trim
        val source = git("rev-parse", "refs/heads/delegate/task")
        val accepted = ledger.requestMerge(
          WorkspaceLedger.MergeRequest(
            "context-spec-request",
            WorkspaceLedger.repositoryKey(repo),
            "delegate-spec-merger",
            "worktree context spec",
            List(WorkspaceLedger.MergeItem("task", source, "main"))
          )
        )
        assert(accepted.isRight, s"requestMerge failed: ${accepted.left.getOrElse("")}")
      }
      result <- WorktreeContextTool.call(
        JsonObject("workspaceId" -> Json.fromString("task")),
        ctxFor(Some("delegate-spec-merger"))
      )
      json = parse(result)
      sections = json.hcursor.downField("sections")
      history = sections.downField("history").downField("requests").as[List[Json]].toOption.getOrElse(Nil)
      _ = assertEquals(history.length, 1, "the recorded request appears in the decision history")
      _ = assert(history.headOption.flatMap(_.hcursor.get[String]("requestId").toOption).contains("context-spec-request"))
      _ = assert(sections.downField("verification").get[String]("missing").toOption.exists(_.contains("no verification record")))
    yield ()
  }
end WorktreeContextToolSpec
