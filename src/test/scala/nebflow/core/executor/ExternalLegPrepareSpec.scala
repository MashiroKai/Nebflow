package nebflow.core.executor

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.CatsEffectSuite
import nebflow.actor.ActorSystem
import nebflow.core.delegate.{ChainStore, DelegateJudge, DelegateRegistry, MergeQueue}
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, MessageRole, PathUtil, StreamChunk}

import scala.concurrent.duration.*

/**
 * 外部执行器腿接纪律判读（引擎加固批 2026-10-09，P1-2）：
 *
 *  - ① spawn 前同一 [[nebflow.core.delegate.DelegateWorkspace.prepare]]：git 工作区
 *    = 分支 `delegate/<id>` ＋ worktree 座位（进程 cwd = worktree）；记录四键 +
 *    worktreeJudged 落库；spawn 行审计键（worktreeJudged/judgeLeg）在链上；brief
 *    行进会话历史。判定机缺席 = fail-open 反转（judgeLeg=fail-open）——分支纪律
 *    不因判定机缺席而丢；
 *  - ② 判定机判 ready-to-merge ⇒ 真并入 MergeQueue（外部通道条目）→ 注入走续聊
 *    通道（CLI resume——夹具用 /bin/echo 假 CLI）→ 合并轮 settle 走机械验收闸
 *    （假 CLI 不会真并 ⇒ blocked + 证据——绝不凭自申报放行）；
 *  - ③ MergeQueue 通道注入单元锚：external 条目注入走 send 闭包（零 actor）；
 *    recovered 条目注入走 revive 闭包 + rekey 换键（新 id 持窗、终态出队放窗）。
 */
class ExternalLegPrepareSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 180.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-external-leg"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot)

  override def afterAll(): Unit = PathUtil.setDataRoot(originalRoot)

  override def beforeEach(context: munit.BeforeEach): Unit =
    os.remove.all(tempRoot / "chains")
    os.remove.all(tempRoot / "delegates")
    os.remove.all(tempRoot / "subagent-tasks")

  override def afterEach(context: munit.AfterEach): Unit =
    DelegateJudge.DelegateJudgePort.clear()

  private def mkRepo(dir: os.Path): os.Path =
    os.remove.all(dir)
    os.makeDir.all(dir)
    os.proc("git", "init", "-b", "main", dir.toString).call(check = true, stdout = os.Pipe, stderr = os.Pipe)
    os.proc("git", "-C", dir.toString, "config", "user.email", "spec@nebflow.local").call(check = true)
    os.proc("git", "-C", dir.toString, "config", "user.name", "spec").call(check = true)
    os.proc("git", "-C", dir.toString, "commit", "--allow-empty", "-m", "init")
      .call(check = true, stdout = os.Pipe, stderr = os.Pipe)
    dir

  private class SilentLlm extends LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new RuntimeException("send not expected"))

    def sendStream(
      req: LlmRequest,
      onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
    ): fs2.Stream[IO, StreamChunk] =
      fs2.Stream(StreamChunk.TextDelta("ok"), StreamChunk.Done(None, None))

  private def spec(
    repo: os.Path,
    resources: nebflow.core.AgentRuntimePort,
    task: String
  ): ExternalSessionRunner.SpawnSpec =
    ExternalSessionRunner.SpawnSpec(
      executorId = "claude-code",
      binPath = "/bin/echo", // 假 CLI：打印 argv 即退（exit 0，零帧）——真通路的进程面
      task = task,
      title = "external leg task",
      workspace = repo.toString,
      project = None,
      taskId = Some("91"),
      parentSessionId = "nebula-root",
      rootSessionId = "nebula-root",
      parentRef = None,
      wsSend = None,
      resources = resources
    )

  /** 轮询直到条件成立（外部腿 settle 是异步 fiber——轮询锚）。 */
  private def pollUntil(label: String, maxMs: Long = 30000)(cond: IO[Boolean]): IO[Unit] =
    val deadline = System.currentTimeMillis() + maxMs
    def loop(): IO[Unit] =
      cond.flatMap {
        case true => IO.unit
        case false =>
          IO(System.currentTimeMillis()).flatMap { now =>
            if now > deadline then IO.raiseError(new RuntimeException(s"poll timeout: $label"))
            else IO.sleep(200.millis) *> loop()
          }
      }
    loop()

  test("① external spawn prepares the SAME branch+worktree seat; record + spawn-row audit keys + brief line land") {
    val system = ActorSystem(s"el-1-${scala.util.Random.nextInt(100000)}")
    for
      res <- nebflow.agent.SpecResources.mkResources(system, tempRoot, new SilentLlm)
      _ = DelegateJudge.DelegateJudgePort.clear() // 判定机缺席 = fail-open 反转腿
      repo = mkRepo(tempRoot / "ext-repo")
      spawned <- ExternalSessionRunner.spawn(spec(repo, res, "read the code and report"))
      (sessionId, ack) = spawned.fold(err => fail(s"spawn failed: $err"), identity)
      _ <- pollUntil("task row terminal (settle ran)")(
        res.subAgentTaskStore.findByTaskId(sessionId).map(_.map(_.status).contains("completed"))
      )
      record = DelegateRegistry.find(sessionId)
      spawnRows = ChainStore.listSync().flatMap(_.entries).filter(_.kind == "delegate-spawn")
      userText <- res.sessionStore
        .loadMessagesForSession(sessionId)
        .map(
          _.filter(_.role == MessageRole.User).map(_.textContent).mkString("\n")
        )
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield
      val r = record.getOrElse(fail("no registry record for the external session"))
      assertEquals(r.branch, Some(s"delegate/$sessionId"), "the external leg gets the same delegate/<id> branch")
      assert(r.worktree.isDefined, "a worktree seat is created")
      assertEquals(r.cwd, r.worktree.get, "the process cwd is the worktree seat")
      assertEquals(r.baseBranch, Some("main"))
      assertEquals(r.projectWorkspace, Some(repo.toString))
      assertEquals(r.worktreeJudged, Some(true), "judge absent ⇒ fail-open reversal recorded as judged-true")
      assert(os.exists(os.Path(r.worktree.get)), "the worktree directory exists")
      val branchOut = os
        .proc("git", "-C", repo.toString, "rev-parse", "--abbrev-ref", s"delegate/$sessionId")
        .call(check = true)
        .out
        .trim()
      assertEquals(branchOut, s"delegate/$sessionId", "the branch exists in the base repo")
      // spawn 行审计键（worktreeJudged / judgeLeg —— additive）
      val row = spawnRows.find(_.payload.hcursor.get[String]("delegateId").toOption.contains(sessionId))
      assert(row.isDefined, "the external leg writes a delegate-spawn ChainStore row")
      val c = row.get.payload.hcursor
      assertEquals(c.get[Boolean]("worktreeJudged").toOption, Some(true))
      assertEquals(c.get[String]("judgeLeg").toOption, Some("fail-open"))
      assertEquals(c.get[String]("executor").toOption, Some("claude-code"))
      // brief 申报行进会话历史（外部零平台注入——任务文本即全部上下文）
      assert(
        userText.contains("[working branch]"),
        s"the working-branch line must precede the task text: ${userText.take(200)}"
      )
      assert(userText.contains("read the code and report"), "the original task text is preserved after the brief line")
    end for
  }

  test(
    "② ready-to-merge verdict enqueues the EXTERNAL channel entry; the merge round runs through CLI resume and the mechanical gate refuses a fake merge"
  ) {
    val system = ActorSystem(s"el-2-${scala.util.Random.nextInt(100000)}")
    try
      for
        res <- nebflow.agent.SpecResources.mkResources(system, tempRoot, new SilentLlm)
        // fake 判定机（后装覆盖真机）：终态腿恒 ready-to-merge；合并轮 resume 终态
        // 仍回 ready-to-merge 不影响——merge round 的 Release 由机械验收闸定夺。
        _ = DelegateJudge.DelegateJudgePort.install(
          new DelegateJudge.DelegateJudgePort.Face:
            def decideWorktree(task: String): IO[Option[Boolean]] = IO.pure(Some(false))
            def judgeTerminal(input: DelegateJudge.TerminalInput): IO[Option[DelegateJudge.Verdict]] =
              IO.pure(Some(DelegateJudge.Verdict.ReadyToMerge))
        )
        repo = mkRepo(tempRoot / "ext-rtm")
        spawned <- ExternalSessionRunner.spawn(spec(repo, res, "produce a deliverable"))
        (sessionId, ack) = spawned.fold(err => fail(s"spawn failed: $err"), identity)
        _ <- pollUntil("entry enqueued (settle ran, judge said ready-to-merge)")(
          IO(MergeQueue.find(sessionId).isDefined)
        )
        queued0 = MergeQueue.find(sessionId)
        // 注入即续聊（假 CLI resume）→ 合并轮 settle（mergeRound=true）→ 机械验收闸：
        // 假 CLI 从不真并 ⇒ 拒 Release 判 blocked（任务行 failed + 证据在 lastError），
        // 条目出队、单飞行窗释放。
        //
        // 🔴 密封性注记（P1-2 实现的 fail-closed 面所然）：resume 腿经
        // `ExecutorRegistry.resolveRequested` **重新解析执行器二进制**（PATH 腿优先），
        // spawn 侧的 `binPath=/bin/echo` 假不进 resume。密封跑法 = 把本套件跑在
        // 带假 `claude` 的 PATH 前（`PATH=<dir-with-fake-claude>:$PATH sbt testOnly …`，
        // 假件只回一行 stream-json result 帧即退）。若机器 PATH 上有真 claude，
        // resume 会真起一次 LLM 轮（brief=合并指令）——闸照常拒绝（没真并），只是
        // 轮时长≈LLM 时延 ⇒ 轮询窗给足 120s。
        _ <- pollUntil("merge round refused by the mechanical gate + entry dequeued", maxMs = 120000)(
          res.subAgentTaskStore.findByTaskId(sessionId).flatMap {
            case Some(t) if t.status == "failed" =>
              IO(
                t.lastError
                  .exists(_.contains("mechanical merge acceptance refused")) && MergeQueue.find(sessionId).isEmpty
              )
            case _ => IO.pure(false)
          }
        )
        rows = ChainStore
          .listSync()
          .flatMap(_.entries)
          .filter(_.kind == MergeQueue.EntryKind)
          .flatMap(_.payload.hcursor.get[String]("action").toOption)
        _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
      yield
        assert(queued0.isDefined, "the ready-to-merge verdict must enqueue the external entry")
        assertEquals(queued0.map(_.branch), Some(s"delegate/$sessionId"))
        assert(rows.contains("enqueued"), s"enqueued audited: $rows")
        assert(rows.contains("reminder-sent"), s"reminder-sent (external channel injection) audited: $rows")
        assert(rows.contains("dequeued"), s"the entry must exit the queue (the resume turn settled): $rows")
      end for
    finally DelegateJudge.DelegateJudgePort.clear()
    end try
  }

  test("③ MergeQueue channel units: external entry injects via send (zero actor); recovered entry rekeys on revive") {
    val system = ActorSystem(s"el-3-${scala.util.Random.nextInt(100000)}")
    val sent = new java.util.concurrent.ConcurrentLinkedQueue[String]()
    for
      _ <- IO {
        MergeQueue.enqueue(
          MergeQueue.Entry(
            delegateId = "delegate-ext-chan",
            branch = "delegate/delegate-ext-chan",
            baseWs = tempRoot.toString,
            baseBranch = "main",
            worktree = false,
            projectKey = "p-chan",
            taskId = Some("71"),
            parentSessionId = "nebula-root",
            description = "t",
            readyAt = 1L,
            childRef = MergeQueue.NoopChild,
            supervisorRef = MergeQueue.NoopSupervisor,
            external = Some(MergeQueue.ExternalLeg(msg => IO { sent.add(msg); Right(()) }))
          )
        )
      }
      _ <- IO.sleep(100.millis)
      _ = MergeQueue.onTerminal("delegate-ext-chan")
      // recovered + rekey：revive 成功即换键——新 id 持窗，旧 id 消失；新 id 终态出队放窗。
      _ <- IO {
        MergeQueue.enqueue(
          MergeQueue.Entry(
            delegateId = "delegate-old",
            branch = "delegate/delegate-old",
            baseWs = tempRoot.toString,
            baseBranch = "main",
            worktree = false,
            projectKey = "p-rekey",
            taskId = Some("72"),
            parentSessionId = "nebula-root",
            description = "t",
            readyAt = 1L,
            childRef = MergeQueue.NoopChild,
            supervisorRef = MergeQueue.NoopSupervisor,
            recovered = Some(
              MergeQueue.Recovered(
                "delegate-old",
                msg => IO { MergeQueue.rekey("delegate-old", "delegate-new"); Right(()) }
              )
            )
          )
        )
      }
      _ <- IO.sleep(100.millis)
      afterRekey = MergeQueue.find("delegate-new")
      oldGone = MergeQueue.find("delegate-old").isEmpty
      _ = MergeQueue.onTerminal("delegate-new")
      _ <- IO.sleep(50.millis)
      released = MergeQueue.queueViewOf("delegate-new").isEmpty && !MergeQueue.isInFlight("p-rekey")
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield
      assertEquals(sent.size(), 1, "the external channel got exactly one merge brief (zero actor injection)")
      assert(
        sent.peek().contains("<system-reminder>") && sent.peek().contains("merge --no-ff"),
        "the injected text is the merge brief"
      )
      assert(afterRekey.isDefined, "the rekeyed entry lives under the new session id")
      assert(oldGone, "the old id is gone after the rekey")
      assert(released, "the merge-round terminal (new id) released the single-flight window")
    end for
  }

end ExternalLegPrepareSpec
