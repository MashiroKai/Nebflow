package nebflow.core.delegate

import cats.effect.IO
import munit.CatsEffectSuite
import nebflow.core.mail.SessionMailbox
import nebflow.shared.PathUtil

import WorkspaceLedger.*

import scala.concurrent.duration.*

/**
 * WorkspaceAdvisor 验收（worktree-merger delivery · Priority E · 设计 §九
 * 「默认主动建议，未授权自动合并」行）：
 *   - #21 默认主动建议：候选条件全满足 ⇒ 恰一条结构化建议邮件，且明示「只是建议、
 *     不建合并请求」——建议本身永不创建请求；
 *   - #23 仅空闲/领先单独出现不足以构成候选（无 finish 报告 ⇒ 非候选）；
 *   - #24 同一 (workspace, headCommit, baseBranch) 键永不重复建议；HEAD 前进 = 真变化
 *     ⇒ 重新武装；
 *   - #25 建议后用户继续开发 ⇒ 行衰减为 stale，零新增骚扰（只经读面可见）；
 *   - #26 dismiss 只动建议行，绝不触碰真实合并请求，dismissed 键不再重发；
 *   - #27 找不到负责主会话 ⇒ 行落 pending-coordination，事实留盘、零广播、零投递；
 *   - fail-closed：占用读数不可读 ⇒ 不建议；坏形建议库 ⇒ 整轮拒绝、读显式 Left。
 */
class WorkspaceAdvisorSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 120.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-workspace-advisor"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  nebflow.ForceRemove.removeAll(tempRoot)
  os.makeDir.all(tempRoot / "workspaces")
  os.makeDir.all(tempRoot / "sessions")
  os.makeDir.all(tempRoot / "delegates")

  override def afterAll(): Unit = PathUtil.setDataRoot(originalRoot)

  override def beforeEach(context: munit.BeforeEach): Unit =
    // Per-test isolation: the ledger, the suggestion store, the delegate registry
    // files and the session mailboxes all live under the data root.
    nebflow.ForceRemove.removeAll(tempRoot / "workspaces")
    nebflow.ForceRemove.removeAll(tempRoot / "delegates")
    nebflow.ForceRemove.removeAll(tempRoot / "sessions")
    os.makeDir.all(tempRoot / "workspaces")

  private def store = PathUtil.dataRoot / "workspaces" / "suggestions.json"

  /** Real temp Git repo + worktree: one committed deliverable ahead of main, tree clean. */
  private def fixture(prefix: String): (os.Path, String, String) =
    val root = os.temp.dir(prefix = s"advisor-$prefix-")
    val repo = root / "repo"
    val tree = root / "task"
    os.makeDir.all(repo)
    def git(args: String*) =
      os.proc(Seq("git") ++ args).call(cwd = repo, stdout = os.Pipe, stderr = os.Pipe).out.text().trim
    git("init", "-b", "main")
    git("config", "user.name", "test")
    git("config", "user.email", "test@example.invalid")
    git("commit", "--allow-empty", "-m", "base")
    val base = git("rev-parse", "HEAD")
    git("worktree", "add", "-b", "delegate/task", tree.toString, "main")
    os.write(tree / "result.txt", "committed result")
    os.proc("git", "add", "result.txt").call(cwd = tree)
    os.proc("git", "commit", "-m", "result").call(cwd = tree, stdout = os.Pipe)
    val head = git("rev-parse", "delegate/task")
    val owner = s"delegate-worker-$prefix"
    WorkspaceLedger.default.register(
      Workspace(
        "task",
        "example",
        WorkspaceLedger.repositoryKey(repo),
        tree.toNIO.toRealPath().toString,
        "delegate/task",
        "main",
        base,
        owner,
        "dispatcher"
      )
    )
    (tree, head, owner)
  end fixture

  private def putRecord(owner: String, parentSessionId: String, finish: Boolean, title: String = "ship the feature"): Unit =
    DelegateRegistry.put(
      DelegateRegistry.Record(
        id = owner,
        executor = "default",
        cwd = "/tmp",
        project = Some("example"),
        taskId = None,
        parentSessionId = parentSessionId,
        title = title,
        createdAt = System.currentTimeMillis(),
        reportStatus = if finish then Some(WorkspaceAdvisor.FinishReport) else None
      )
    )

  private def advisor(
    occupancy: WorkspaceOccupancy = new WorkspaceOccupancy(
      _ => IO.pure(DelegateInflightGate.Reading(Nil, readable = true)),
      _ => IO.pure(Nil)
    ),
    records: String => Option[DelegateRegistry.Record] = DelegateRegistry.find
  ): WorkspaceAdvisor =
    new WorkspaceAdvisor(store, occupancy, records)

  /** Pending mailbox entries of one session (the advisor's delivery channel). */
  private def mailbox(sid: String): List[(String, String)] =
    SessionMailbox.scanPendingSync(sid).map(e => (e.identity, e.event.payload))

  test("full candidate path: finish report + idle + ahead/clean suggests exactly once with an honest message (#21)") {
    val (_, head, owner) = fixture("full")
    putRecord(owner, "root-main", finish = true)
    val adv = advisor()
    for reading <- adv.evaluate(WorkspaceLedger.default)
    yield
      val ws = reading.workspaces.find(_.workspaceId == "task").getOrElse(fail("no reading for the workspace"))
      assertEquals(ws.classification, WorkspaceAdvisor.ClassSuggestable, s"reasons: ${ws.reasons}")
      val rows = adv.rows().toOption.getOrElse(Nil)
      assertEquals(rows.map(_.state), List(WorkspaceAdvisor.StateSuggested), "one suggested row")
      assertEquals(rows.headOption.flatMap(_.deliveredTo), Some("root-main"), "delivery recorded on the row")
      assertEquals(rows.headOption.map(_.origin), Some(WorkspaceAdvisor.Origin), "advisor origin")
      // Exactly one structured mail reached the responsible main session.
      val mail = mailbox("root-main")
      assertEquals(mail.map(_._1), List(WorkspaceAdvisor.mailIdentity("task", head)), "one identity-keyed entry")
      val payload = mail.headOption.map(_._2).getOrElse("")
      assert(payload.contains("SUGGESTION only"), "the message states its suggestion nature")
      assert(payload.contains("no merge request was created"), "#21: nothing was auto-created")
      assert(payload.contains("ship the feature"), "the task title rides the message")
      assert(payload.contains("ahead of main"), "commits ahead ride the message")
      assert(payload.contains("no verification evidence"), "verification-evidence presence rides the message")
      // The ledger is untouched: no request, no reservation.
      val snapshot = WorkspaceLedger.default.snapshot
      assertEquals(snapshot.requests, Nil, "the advisor never creates merge requests")
      assertEquals(snapshot.workspaces.find(_.id == "task").get.lifecycle, "open")
    end for
  }

  test("idle + ahead WITHOUT a finish report is not a candidate and never notifies (#23)") {
    val (_, _, owner) = fixture("nofinish")
    putRecord(owner, "root-main", finish = false)
    val adv = advisor()
    for reading <- adv.evaluate(WorkspaceLedger.default)
    yield
      val ws = reading.workspaces.find(_.workspaceId == "task").getOrElse(fail("no reading"))
      assertEquals(ws.classification, WorkspaceAdvisor.ClassNotCandidate)
      assert(ws.reasons.exists(_.contains("finish report")), s"the missing finish report is the reason: ${ws.reasons}")
      assertEquals(adv.rows().toOption.getOrElse(Nil), Nil, "no row for a non-candidate")
      assertEquals(mailbox("root-main"), Nil, "no mail")
    end for
  }

  test("the same key is never re-suggested; a new HEAD re-arms evaluation (#24)") {
    val (tree, head, owner) = fixture("dup")
    putRecord(owner, "root-main", finish = true)
    val adv = advisor()
    for
      _ <- adv.evaluate(WorkspaceLedger.default)
      _ <- adv.evaluate(WorkspaceLedger.default) // replay over identical state
      replayMail = mailbox("root-main")
      _ <- IO {
        os.write.over(tree / "result.txt", "committed result v2")
        os.proc("git", "add", "result.txt").call(cwd = tree, stdout = os.Pipe, stderr = os.Pipe)
        os.proc("git", "commit", "-m", "result v2").call(cwd = tree, stdout = os.Pipe, stderr = os.Pipe)
        ()
      }
      head2 <- IO(os.proc("git", "rev-parse", "delegate/task").call(cwd = tree, stdout = os.Pipe).out.text().trim)
      _ <- adv.evaluate(WorkspaceLedger.default)
    yield
      assertEquals(replayMail.length, 1, "#24: exactly one suggestion per key despite the replay")
      val rows = adv.rows().toOption.getOrElse(Nil)
      assertEquals(rows.map(_.headCommit).toSet, Set(head, head2), "the new HEAD created a new row")
      assertEquals(
        mailbox("root-main").map(_._1).toSet,
        Set(WorkspaceAdvisor.mailIdentity("task", head), WorkspaceAdvisor.mailIdentity("task", head2)),
        "each deliverable is suggested exactly once"
      )
      assertEquals(rows.find(_.headCommit == head).map(_.state), Some(WorkspaceAdvisor.StateStale), "the old row decayed")
      assertEquals(rows.find(_.headCommit == head2).map(_.state), Some(WorkspaceAdvisor.StateSuggested), "the new row is live")
    end for
  }

  test("development resumed after a suggestion decays the row to stale with zero extra mail (#25)") {
    val (_, _, owner) = fixture("stale")
    putRecord(owner, "root-main", finish = true)
    val adv = advisor()
    for
      _ <- adv.evaluate(WorkspaceLedger.default)
      ws <- IO(WorkspaceLedger.default.snapshot.workspaces.find(_.id == "task").get)
      _ <- IO(assert(WorkspaceLedger.default.claimDevelopment("task", owner, ws.revision).isRight))
      reading <- adv.evaluate(WorkspaceLedger.default)
    yield
      val rows = adv.rows().toOption.getOrElse(Nil)
      assertEquals(rows.map(_.state), List(WorkspaceAdvisor.StateStale), "#25: the stale suggestion dies")
      assert(rows.headOption.flatMap(_.note).exists(_.contains("running")), s"the decay reason is recorded: $rows")
      assertEquals(mailbox("root-main").length, 1, "no re-notification spam; the change is read-face only")
      assertEquals(reading.workspaces.find(_.workspaceId == "task").get.classification, WorkspaceAdvisor.ClassStale)
    end for
  }

  test("an unresolvable main session parks the row as pending-coordination with zero sends (#27)") {
    val (_, _, owner) = fixture("orphan")
    putRecord(owner, "", finish = true) // no parent session recorded
    val adv = advisor()
    for reading <- adv.evaluate(WorkspaceLedger.default)
    yield
      val rows = adv.rows().toOption.getOrElse(Nil)
      assertEquals(rows.map(_.state), List(WorkspaceAdvisor.StatePending), "#27: facts kept, nothing broadcast")
      assert(rows.headOption.flatMap(_.note).exists(_.contains("unknown")), s"the park reason is recorded: $rows")
      assertEquals(mailbox("root-main"), Nil, "no mail to any session")
      assert(!os.exists(PathUtil.dataRoot / "sessions" / "root-main" / "mailbox"), "nothing was enqueued anywhere")
      assertEquals(reading.errors, Nil, "this is a normal outcome, not a failure")
    end for
  }

  test("dismiss works, never touches a real merge request, and suppresses re-suggestion (#26)") {
    val (tree, head, owner) = fixture("dismiss")
    putRecord(owner, "root-main", finish = true)
    val repo = tree / os.up / "repo"
    val repository = WorkspaceLedger.repositoryKey(repo)
    val ledger = WorkspaceLedger.default
    val adv = advisor()
    for
      _ <- adv.evaluate(WorkspaceLedger.default)
      // A real manual merge request enters the ledger (user action, not the advisor).
      source <- IO(os.proc("git", "rev-parse", "delegate/task").call(cwd = repo, stdout = os.Pipe).out.text().trim)
      _ <- IO(assert(ledger.requestMerge(MergeRequest("manual", repository, source, "user authorized", List(MergeItem("task", source, "main")))).isLeft),
        "a request without a recorded merger is refused") // fixture sanity, no merger yet
      _ <- IO(ledger.merger("example", repository, "delegate-merger"))
      _ <- IO(assert(ledger.requestMerge(MergeRequest("manual", repository, "delegate-merger", "user authorized", List(MergeItem("task", source, "main")))).isRight))
      beforeRequest <- IO(ledger.snapshot.requests)
      dismissed <- IO.blocking(adv.dismiss("task", head))
      _ <- adv.evaluate(WorkspaceLedger.default) // workspace now reserved: still must not touch the request
    yield
      val dismissedRow = dismissed.toOption.getOrElse(fail(s"dismiss failed: $dismissed"))
      assertEquals(dismissedRow.state, WorkspaceAdvisor.StateDismissed, "the row is dismissed")
      val rows = adv.rows().toOption.getOrElse(Nil)
      assertEquals(rows.map(_.state), List(WorkspaceAdvisor.StateDismissed), "dismissed survives evaluation")
      assert(rows.headOption.flatMap(_.note).exists(_.contains("dismissed")), s"the dismissal note is kept: $rows")
      assertEquals(ledger.snapshot.requests, beforeRequest, "#26: explicit manual requests are never touched")
      assertEquals(mailbox("root-main").length, 1, "no second mail after dismissal (#24 holds for dismissed keys)")
      // Dismissing again without a matching row fails loudly.
      assert(adv.dismiss("task", "f" * 40).isLeft, "dismiss is anchored to the exact head commit")
    end for
  }

  test("an unreadable occupancy reading is fail-closed: no suggestion, nothing persisted") {
    val (_, _, owner) = fixture("uncertain")
    putRecord(owner, "root-main", finish = true)
    val uncertain = new WorkspaceOccupancy(
      _ => IO.raiseError(new RuntimeException("scope graph exploded")),
      _ => IO.pure(Nil)
    )
    val adv = advisor(occupancy = uncertain)
    for
      first <- adv.evaluate(WorkspaceLedger.default)
      second <- adv.evaluate(WorkspaceLedger.default)
    yield
      val ws = first.workspaces.find(_.workspaceId == "task").get
      assertEquals(ws.classification, WorkspaceAdvisor.ClassNotCandidate)
      assert(ws.reasons.exists(_.contains("unreadable")), s"the uncertainty is named: ${ws.reasons}")
      assertEquals(adv.rows().toOption.getOrElse(Nil), Nil, "fail-closed: nothing was suggested or persisted")
      assertEquals(mailbox("root-main"), Nil, "no mail under uncertainty")
      assertEquals(second.workspaces.find(_.workspaceId == "task").get.classification, WorkspaceAdvisor.ClassNotCandidate,
        "the second evaluation stays fail-closed")
    end for
  }

  test("a corrupt suggestion store is fail-closed: reads fail explicitly and nothing is suggested") {
    val (_, _, owner) = fixture("corrupt")
    putRecord(owner, "root-main", finish = true)
    os.write(store, "{not json")
    val adv = advisor()
    for reading <- adv.evaluate(WorkspaceLedger.default)
    yield
      assert(adv.rows().isLeft, "corrupt rows are an explicit Left, never a silent empty list")
      assertEquals(reading.errors.length, 1, "the evaluation reports the fail-closed refusal")
      assert(reading.errors.head.contains("unreadable"), s"the reason names the store: ${reading.errors.head}")
      assertEquals(mailbox("root-main"), Nil, "no suggestion without the dedupe history")
      assertEquals(reading.suggestions, Nil, "no rows were returned or written")
    end for
  }

  test("a manually merged deliverable decays its row to integrated") {
    val (tree, head, owner) = fixture("integrated")
    putRecord(owner, "root-main", finish = true)
    val repo = tree / os.up / "repo"
    val repository = WorkspaceLedger.repositoryKey(repo)
    val ledger = WorkspaceLedger.default
    val adv = advisor()
    for
      _ <- adv.evaluate(WorkspaceLedger.default)
      _ <- IO {
        // Manual integration path (user/agent action, not the advisor): reserve, merge, complete.
        ledger.merger("example", repository, "delegate-merger")
        assert(
          ledger.requestMerge(MergeRequest("manual", repository, "delegate-merger", "user authorized", List(MergeItem("task", head, "main")))).isRight
        )
        def report(event: MergeReport): Unit =
          val request = ledger.snapshot.requests.find(_.id == "manual").get
          assert(ledger.reportMerge("manual", "delegate-merger", event)((_, _, _) => Right(())).isRight, s"report $event")
        report(MergeReport("m1", 0L, "task", "checking"))
        report(MergeReport("m2", 1L, "task", "merging"))
        report(MergeReport("m3", 2L, "task", "merged-cleanup-pending", Some(head)))
        report(MergeReport("m4", 3L, "task", "completed"))
        ()
      }
      reading <- adv.evaluate(WorkspaceLedger.default)
    yield
      val rows = adv.rows().toOption.getOrElse(Nil)
      assertEquals(rows.map(_.state), List(WorkspaceAdvisor.StateIntegrated), "merged proof decays the row to integrated")
      assert(rows.headOption.flatMap(_.note).exists(_.contains("merged")), s"the integration note is kept: $rows")
      assertEquals(reading.errors, Nil)
      assertEquals(reading.workspaces.find(_.workspaceId == "task").get.lifecycle, "closed-merged", "fixture sanity")
    end for
  }

end WorkspaceAdvisorSpec
