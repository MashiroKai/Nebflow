package nebflow.core.delegate

import cats.effect.{IO, Ref}
import cats.effect.std.Dispatcher
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import io.circe.JsonObject
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.{ActorSystem, AgentDef}
import nebflow.core.FileChangeTracker
import nebflow.core.compact.HistoryArchiver
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.{FileLockManager, ToolContext}
import nebflow.core.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor}
import nebflow.shared.{PathUtil, SubAgentTask, ThinkingConfig}

import scala.concurrent.duration.*

/**
 * 台账 ↔ delegate 面板两层状态打通（监督链自动恢复批增补，2026-10-22 作者补充
 * 需求）spec。设计 = docs/design/delegate-supervision-revival-design.zh-CN.md 增补节。
 *
 * 用例：
 *  - L1a 腿 1 直调：台账 complete + 任务行终态带中断旗 → 记录归档折叠；在飞行不碰。
 *  - L1b 腿 1 接线：TaskTool(action=complete) 成功 → 关联记录归档（工具面真通路）。
 *  - L2  腿 2：终态交付自动台账记账（revivalCount 注记随行）。
 *  - L3a 腿 3：旗在 + 台账 completed → 归档（AutoArchive 窗口延伸到 sweep 收殓行）。
 *  - L3b 腿 3：旗在 + 台账 open + 无合并证据 → 旗保留（真中断保守）。
 *  - L3c 腿 3：旗在 + 台账 open + 分支已并入基分支 → 归档 + 台账 note（合并佐证）。
 */
class DelegateLedgerReconcileSpec extends CatsEffectSuite:

  private val originalRoot = PathUtil.dataRoot

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)
    nebflow.core.LlmLogWriter.setEnabled(true)

  nebflow.core.LlmLogWriter.setEnabled(false)

  private final case class Fx(
    store: nebflow.agent.SubAgentTaskStore,
    system: ActorSystem,
    tmp: os.Path
  ):
    def ledger: nebflow.core.project.TaskLedgerStore = nebflow.core.project.TaskLedgerStore.open()

  private def withFx(name: String)(body: Fx => IO[Unit]): Unit =
    val tmp = os.temp.dir(prefix = s"recon-$name")
    PathUtil.setDataRoot(tmp / "data")
    val system = ActorSystem(s"recon-$name-${java.util.UUID.randomUUID().toString.take(6)}")
    val io =
      for
        dispatcher <- Dispatcher.parallel[IO].allocated.map(_._1)
        rateLimiter <- RateLimiter.create()
        tracker <- FileChangeTracker.create(os.pwd.toString)
        fileLocks <- FileLockManager.create
        thinkingRef <- Ref.of[IO, ThinkingConfig](ThinkingConfig())
        modelOverrides <- Ref.of[IO, Map[String, ModelCandidate]](Map.empty)
        voiceMuted <- Ref.of[IO, Boolean](false)
        _ <- nebflow.agent
          .SharedResources(
            llm = new nebflow.shared.LlmHandle[IO]:
              def send(req: nebflow.shared.LlmRequest): IO[nebflow.shared.LlmResponse] =
                IO.raiseError(new RuntimeException("not expected"))
              def sendStream(
                req: nebflow.shared.LlmRequest,
                onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
              ) = fs2.Stream.raiseError[IO](new RuntimeException("not expected"))
            ,
            dispatcher = dispatcher,
            sessionStore = SessionStore(tmp / "sessions", tmp / "tasks"),
            projectRoot = os.pwd,
            thinkingConfigRef = thinkingRef,
            rateLimiter = rateLimiter,
            fileChangeTracker = tracker,
            contextWindow = 100_000,
            agentLibrary = new nebflow.agent.AgentLibrary(tmp / "agents"),
            taskStore = FileTaskStore,
            historyArchiver = HistoryArchiver.fileSystem(tmp / "archives"),
            fileLockManager = fileLocks,
            sessionModelOverrides = modelOverrides,
            providerRegistry = null,
            healthMonitor = ProviderHealthMonitor(null),
            actorSystem = system,
            subAgentTaskStore = new nebflow.agent.SubAgentTaskStore(tmp / "subagent-tasks"),
            voiceMutedRef = voiceMuted
          )
          .pure[IO]
        store = new nebflow.agent.SubAgentTaskStore(tmp / "subagent-tasks")
        _ <- body(Fx(store, system, tmp))
      yield ()
    try io.unsafeRunSync()
    finally
      system.stopAll.attempt.void.unsafeRunSync()
      PathUtil.setDataRoot(originalRoot)
      os.remove.all(tmp)

  end withFx

  private def mkTask(id: String, parent: String, status: String): SubAgentTask =
    SubAgentTask(
      taskId = id,
      parentSessionId = parent,
      agentName = "nebflow",
      prompt = "p",
      description = s"recon-$id",
      status = status,
      retryCount = 0,
      spawnedAt = System.currentTimeMillis() - 60_000L,
      completedAt = if status == "running" then None else Some(System.currentTimeMillis()),
      lastError = None,
      source = "delegate"
    )

  private def mkRecord(
    id: String,
    cwd: String,
    taskId: Option[String],
    flag: Boolean = true,
    branch: Option[String] = None,
    baseBranch: Option[String] = None
  ): DelegateRegistry.Record =
    DelegateRegistry.Record(
      id = id,
      executor = "nebflow",
      cwd = cwd,
      project = None,
      taskId = taskId,
      parentSessionId = "recon-parent",
      title = s"recon $id",
      createdAt = System.currentTimeMillis() - 120_000L,
      restartInterruptedAt = if flag then Some(System.currentTimeMillis() - 30_000L) else None,
      branch = branch,
      baseBranch = baseBranch
    )

  private def newLedgerEntry(fx: Fx, title: String): String =
    fx.ledger.createSyncReturningId(title).getOrElse(fail(s"ledger create failed: $title"))

  test("L1a: onLedgerTerminal archives terminal flagged rows, leaves in-flight rows alone") {
    withFx("l1a") { fx =>
      val seat = os.temp.dir(prefix = "recon-seat-l1a")
      try
        for
          entryDone <- IO(newLedgerEntry(fx, "recon done task"))
          entryLive <- IO(newLedgerEntry(fx, "recon live task"))
          parent = "recon-parent-l1a"
          // 行 A：终态 failed + 中断旗（作者 complaint #2 的现场形态）。
          _ <- fx.store.recordTask(mkTask("delegate-nebflow-a1", parent, "failed"))
          _ <- IO(DelegateRegistry.put(mkRecord("delegate-nebflow-a1", seat.toString, Some(entryDone))))
          // 行 B：任务行仍在飞（delegate 还活着）——台账 complete 也不替它收尸。
          _ <- fx.store.recordTask(mkTask("delegate-nebflow-a2", parent, "running"))
          _ <- IO(DelegateRegistry.put(mkRecord("delegate-nebflow-a2", seat.toString, Some(entryLive))))
          _ <- IO(fx.ledger.completeSync(entryDone))
          _ <- IO(fx.ledger.completeSync(entryLive))
          _ <- DelegateLedgerReconcile.onLedgerTerminal(fx.store, entryDone)
          _ <- DelegateLedgerReconcile.onLedgerTerminal(fx.store, entryLive)
          a1 <- IO(DelegateRegistry.find("delegate-nebflow-a1"))
          a2 <- IO(DelegateRegistry.find("delegate-nebflow-a2"))
          _ = assert(
            a1.flatMap(_.archivedAt).isDefined,
            "terminal flagged row folded (archived) once the ledger adjudicates"
          )
          _ = assert(a2.flatMap(_.archivedAt).isEmpty, "in-flight row untouched — panel stays truthful")
        yield ()
      finally os.remove.all(seat)
      end try
    }
  }

  test("L1b: TaskTool(action=complete) folds the linked delegate row through the tool face") {
    withFx("l1b") { fx =>
      val seat = os.temp.dir(prefix = "recon-seat-l1b")
      try
        for
          entry <- IO(newLedgerEntry(fx, "recon tool task"))
          parent = "recon-parent-l1b"
          _ <- fx.store.recordTask(mkTask("delegate-nebflow-b1", parent, "failed"))
          _ <- IO(DelegateRegistry.put(mkRecord("delegate-nebflow-b1", seat.toString, Some(entry))))
          res <- mkSharedResources(fx)
          ctx = ToolContext(
            projectRoot = os.pwd.toString,
            sessionId = Some("nebula-root"),
            agentDef = Some(
              AgentDef(name = "Nebula", description = "", tools = List("Task"), systemPrompt = "")
            ),
            depth = 0,
            sharedResources = Some(res)
          )
          out <- nebflow.core.tools.TaskToolDef
            .call(JsonObject("action" -> "complete".asJson, "id" -> entry.asJson), ctx)
          _ = assert(out.isRight, s"complete must succeed: $out")
          rec <- IO(DelegateRegistry.find("delegate-nebflow-b1"))
          _ = assert(
            rec.flatMap(_.archivedAt).isDefined,
            "TaskTool complete folds the panel row (ledger → panel wiring)"
          )
        yield ()
      finally os.remove.all(seat)
      end try
    }
  }

  /** 独立装配一份 SharedResources（L1b 用——与 store 同 tmp）。 */
  private def mkSharedResources(fx: Fx): IO[nebflow.agent.SharedResources] =
    for
      dispatcher <- Dispatcher.parallel[IO].allocated.map(_._1)
      rateLimiter <- RateLimiter.create()
      tracker <- FileChangeTracker.create(os.pwd.toString)
      fileLocks <- FileLockManager.create
      thinkingRef <- Ref.of[IO, ThinkingConfig](ThinkingConfig())
      modelOverrides <- Ref.of[IO, Map[String, ModelCandidate]](Map.empty)
      voiceMuted <- Ref.of[IO, Boolean](false)
    yield nebflow.agent.SharedResources(
      llm = new nebflow.shared.LlmHandle[IO]:
        def send(req: nebflow.shared.LlmRequest): IO[nebflow.shared.LlmResponse] =
          IO.raiseError(new RuntimeException("not expected"))
        def sendStream(
          req: nebflow.shared.LlmRequest,
          onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
        ) = fs2.Stream.raiseError[IO](new RuntimeException("not expected"))
      ,
      dispatcher = dispatcher,
      sessionStore = SessionStore(fx.tmp / "sessions2", fx.tmp / "tasks2"),
      projectRoot = os.pwd,
      thinkingConfigRef = thinkingRef,
      rateLimiter = rateLimiter,
      fileChangeTracker = tracker,
      contextWindow = 100_000,
      agentLibrary = new nebflow.agent.AgentLibrary(fx.tmp / "agents2"),
      taskStore = FileTaskStore,
      historyArchiver = HistoryArchiver.fileSystem(fx.tmp / "archives2"),
      fileLockManager = fileLocks,
      sessionModelOverrides = modelOverrides,
      providerRegistry = null,
      healthMonitor = ProviderHealthMonitor(null),
      actorSystem = fx.system,
      subAgentTaskStore = fx.store,
      voiceMutedRef = voiceMuted
    )

  test("L2: terminal settlement appends a ledger note carrying the revival annotation") {
    withFx("l2") { fx =>
      val seat = os.temp.dir(prefix = "recon-seat-l2")
      try
        for
          entry <- IO(newLedgerEntry(fx, "recon settle task"))
          parent = "recon-parent-l2"
          _ <- fx.store.recordTask(mkTask("delegate-nebflow-c1", parent, "completed"))
          // 复活注记：行带 revivalCount=1（markRevived 写预算，再翻回 completed 模拟交付）。
          _ <- fx.store.markRevived(parent, "delegate-nebflow-c1", 1, System.currentTimeMillis())
          _ <- fx.store.updateStatus(parent, "delegate-nebflow-c1", "completed")
          _ <- IO(DelegateRegistry.put(mkRecord("delegate-nebflow-c1", seat.toString, Some(entry))))
          rec <- IO(DelegateRegistry.find("delegate-nebflow-c1"))
          _ <- DelegateLedgerReconcile.ledgerSettlementNote(fx.store, rec, "delegate-nebflow-c1", "completed")
          note <- IO(fx.ledger.showSync(entry, Map.empty))
          _ = assert(note.isRight)
          _ = assert(
            note.getOrElse("").contains("经 1 次重启复活续作"),
            s"settlement note must carry the revival annotation:\n${note.getOrElse("")}"
          )
        yield ()
      finally os.remove.all(seat)
      end try
    }
  }

  test("L3a: boot reconcile archives a flagged row whose ledger entry is completed") {
    withFx("l3a") { fx =>
      val seat = os.temp.dir(prefix = "recon-seat-l3a")
      try
        for
          entry <- IO(newLedgerEntry(fx, "recon adjudicated task"))
          _ <- IO(fx.ledger.completeSync(entry))
          _ <- IO(DelegateRegistry.put(mkRecord("delegate-nebflow-d1", seat.toString, Some(entry))))
          report <- DelegateLedgerReconcile.reconcileInterruptedRecords(fx.store)
          _ = assertEquals(report.archivedByLedger, List("delegate-nebflow-d1"))
          rec <- IO(DelegateRegistry.find("delegate-nebflow-d1"))
          _ = assert(rec.flatMap(_.archivedAt).isDefined, "flagged row folded by ledger adjudication")
        yield ()
      finally os.remove.all(seat)
    }
  }

  test("L3b: boot reconcile KEEPS the flag when the ledger is open with no merge evidence") {
    withFx("l3b") { fx =>
      val seat = os.temp.dir(prefix = "recon-seat-l3b")
      try
        for
          entry <- IO(newLedgerEntry(fx, "recon genuinely interrupted task"))
          _ <- IO(DelegateRegistry.put(mkRecord("delegate-nebflow-e1", seat.toString, Some(entry))))
          report <- DelegateLedgerReconcile.reconcileInterruptedRecords(fx.store)
          _ = assertEquals(report.kept, List("delegate-nebflow-e1"))
          rec <- IO(DelegateRegistry.find("delegate-nebflow-e1"))
          _ = assert(rec.flatMap(_.restartInterruptedAt).isDefined, "true interruption keeps its badge (conservative)")
          _ = assert(rec.flatMap(_.archivedAt).isEmpty)
        yield ()
      finally os.remove.all(seat)
    }
  }

  test("L3c: boot reconcile archives on merge evidence (branch merged into base) + ledger note") {
    withFx("l3c") { fx =>
      // git 现场放在 worktree 的 target/ 下（sbt scratch，gitignored），不进 TMPDIR——
      // 观测批注 2026-10-22：TMPDIR 内的现场目录在本套件运行中会被环境间歇清走/使
      // git spawn 间歇报 ENOENT（bisect + watch 取证，删除者不明、疑为宿主侧清扫），
      // target/ 与被测代码零耦合且随 finally 确定性清理；搭建紧贴 reconcile（IO 内）。
      val repo = os.pwd / "target" / s"recon-repo-l3c-${java.util.UUID.randomUUID().toString.take(8)}"
      os.makeDir.all(repo)
      def buildGitFixture(): Unit =
        // 重建式重试（观测批注 2026-10-22）：宿主侧对临时目录/git spawn 有间歇干扰
        // （目录被清 / exec("git") 间歇 ENOENT——forkAndExec 栈实证）。清场→重建→
        // 验 .git，5×300ms 吸收抖动；被测对象（mergeEvidence 判定）本身确定。
        var attempt = 0
        var done = false
        while !done && attempt < 5 do
          attempt += 1
          try
            os.remove.all(repo)
            os.makeDir.all(repo)
            os.proc("git", "init", "-b", "main").call(cwd = repo)
            os.write(repo / "base.txt", "base")
            os.proc("git", "add", ".").call(cwd = repo)
            os.proc("git", "-c", "user.email=spec@nebflow", "-c", "user.name=spec", "commit", "-m", "base")
              .call(cwd = repo)
            os.proc("git", "checkout", "-b", "delegate/x").call(cwd = repo)
            os.write(repo / "work.txt", "work")
            os.proc("git", "add", ".").call(cwd = repo)
            os.proc("git", "-c", "user.email=spec@nebflow", "-c", "user.name=spec", "commit", "-m", "delegate work")
              .call(cwd = repo)
            os.proc("git", "checkout", "main").call(cwd = repo)
            // merge 也产生 commit——runner 的 fresh HOME 无全局 git 身份，必须像上方
            // commit 一样显式 -c（CI run 40063846949 实测 empty ident name → 128）。
            os.proc(
              "git",
              "-c",
              "user.email=spec@nebflow",
              "-c",
              "user.name=spec",
              "merge",
              "--no-ff",
              "-m",
              "merge delegate/x",
              "delegate/x"
            ).call(cwd = repo)
            done = os.exists(repo / ".git")
          catch case e: java.io.IOException if attempt < 5 => Thread.sleep(300)
          end try
        end while
        if !done then throw new java.io.IOException(s"git fixture did not survive $attempt attempts")
      end buildGitFixture
      try
        for
          entry <- IO(newLedgerEntry(fx, "recon merged task"))
          _ <- IO(
            DelegateRegistry.put(
              mkRecord(
                "delegate-nebflow-f1",
                repo.toString,
                Some(entry),
                branch = Some("delegate/x"),
                baseBranch = Some("main")
              )
            )
          )
          _ <- IO.blocking(buildGitFixture())
          _ = assert(os.exists(repo / ".git"), "git fixture must survive until the reconcile step")
          report <- DelegateLedgerReconcile.reconcileInterruptedRecords(fx.store)
          _ = assertEquals(report.archivedByMerge, List("delegate-nebflow-f1"))
          rec <- IO(DelegateRegistry.find("delegate-nebflow-f1"))
          _ = assert(rec.flatMap(_.archivedAt).isDefined, "merge evidence folds the flagged row")
          note <- IO(fx.ledger.showSync(entry, Map.empty))
          _ = assert(
            note.getOrElse("").contains("已并入基分支"),
            s"ledger note must record the merge-evidence adjudication:\n${note.getOrElse("")}"
          )
        yield ()
      finally os.remove.all(repo)
      end try
    }
  }

end DelegateLedgerReconcileSpec
