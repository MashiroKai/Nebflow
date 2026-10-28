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
 *  - ② 判定机判 ready-to-merge ⇒ 真并入 MergeQueue（外部通道条目，channelKind=
 *    external）→ 放行时账实核对命中（分支头已是基分支祖先）⇒ 关账 merged +「已落地」
 *    收尾通知走续聊通道（CLI resume——夹具用 /bin/echo 假 CLI spawn）；合并轮 settle
 *    走机械验收闸（无真 merge commit ⇒ 拒 + 证据——绝不凭自申报放行；账已关，队列零损）；
 *  - ③ MergeQueue 通道注入单元锚（台账 #896 交通引导员制）：external 成员注入走
 *    send 闭包（零 actor）；无通道成员（重启后通道闭包丢失形态）⇒ owner-gone 关账 +
 *    OwnerGonePort 升级（绝不静默 park）。
 */
class ExternalLegPrepareSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 300.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-external-leg"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  nebflow.ForceRemove.removeAll(tempRoot)
  os.makeDir.all(tempRoot)

  override def afterAll(): Unit = PathUtil.setDataRoot(originalRoot)

  override def beforeEach(context: munit.BeforeEach): Unit =
    nebflow.ForceRemove.removeAll(tempRoot / "chains")
    nebflow.ForceRemove.removeAll(tempRoot / "delegates")
    nebflow.ForceRemove.removeAll(tempRoot / "subagent-tasks")

  override def afterEach(context: munit.AfterEach): Unit =
    DelegateJudge.DelegateJudgePort.clear()
    MergeQueue.reset()
    MergeQueue.installLivenessProbe(_ => IO.pure(true))
    MergeQueue.OwnerGonePort.clear()

  private def mkRepo(dir: os.Path): os.Path =
    nebflow.ForceRemove.removeAll(dir)
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
    "② ready-to-merge verdict enqueues the EXTERNAL channel entry; the grant-time fact-check closes an already-landed branch as merged and the close-out notice rides the continuation channel"
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
        // spawn 造的分支停在 main HEAD（echo 不产提交）⇒ `is-ancestor` 已真 = 「已落地」
        // 形态：放行闸的账实核对（先于一切）必须直接关账 merged——零合并轮通知、零重试
        // （今晚「已落定仍罚站」的消除闸在放行路径的直证）。
        _ <- pollUntil("entry enqueued + grant-time fact-check closed it (settle ran, judge said ready-to-merge)")(
          IO(MergeQueue.find(sessionId).exists(_.state == MergeQueue.State.Merged))
        )
        member = MergeQueue.find(sessionId)
        // 「已落地」收尾通知走续聊通道（CLI resume）。🔴 密封性注记：resume 腿经
        // `ExecutorRegistry.resolveRequested` 重新解析执行器二进制（PATH 腿优先），
        // spawn 侧的 `binPath=/bin/echo` 假不进 resume。密封跑法 = 把本套件跑在带假
        // `claude` 的 PATH 前（`PATH=<dir-with-fake-claude>:$PATH sbt testOnly …`，
        // 假件只回一行 stream-json result 帧即退）。机器 PATH 上有真 claude ⇒ resume
        // 真起一轮（brief = 已落地收尾令）——轮时长≈LLM 时延 ⇒ 轮询窗给足 150s。
        _ <- pollUntil(
          "the already-landed close-out notice reached the session via the continuation channel",
          maxMs = 150000
        ) {
          res.sessionStore.loadMessagesForSession(sessionId).map { msgs =>
            val userText = msgs.filter(_.role == MessageRole.User).map(_.textContent).mkString("\n")
            userText.contains("ALREADY merged into")
          }
        }
        userText <- res.sessionStore
          .loadMessagesForSession(sessionId)
          .map(
            _.filter(_.role == MessageRole.User).map(_.textContent).mkString("\n")
          )
        // resume 轮 settle（引擎侧唯一的 CLI 依赖腿；其成败不回改账本）：
        // 真机 = 机械验收闸拒假并 → failed；机器无 claude / 座位已清 = 启动失败 →
        // failed。两条腿都终态 failed——只断言终态，不断言原因文案（CLI 侧结果）。
        _ <- pollUntil("the resumed merge round settled (task row terminal)", maxMs = 150000)(
          res.subAgentTaskStore.findByTaskId(sessionId).map(_.exists(_.status == "failed"))
        )
        member2 = MergeQueue.find(sessionId)
        rows = ChainStore
          .listSync()
          .flatMap(_.entries)
          .filter(_.kind == MergeQueue.EntryKind)
          .flatMap(_.payload.hcursor.get[String]("action").toOption)
        _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
      yield
        assert(member.isDefined, "the ready-to-merge verdict must enqueue the external entry")
        assertEquals(member.map(_.branch), Some(s"delegate/$sessionId"))
        assertEquals(member.map(_.channelKind), Some("external"), "the entry rides the external continuation channel")
        assert(
          member.flatMap(_.closeReason).exists(_.contains("already merged into base")),
          s"the close reason names the grant-time fact-check: ${member.flatMap(_.closeReason)}"
        )
        assert(rows.contains("enqueued"), s"enqueued audited: $rows")
        assert(rows.contains("merge-merged"), s"the fact-check close is audited (merge-merged): $rows")
        assert(!rows.contains("granted"), "an already-landed branch never gets a merge-turn notification")
        assert(!rows.contains("reminder-sent") && !rows.contains("dequeued"), "retired queue vocabulary is gone")
        assert(
          userText.contains("ALREADY merged into"),
          "the close-out notice rode the continuation channel into the session history"
        )
        assert(!userText.contains("merge --no-ff"), "the merge brief is never sent for an already-landed branch")
        assertEquals(
          member2.map(_.state),
          Some(MergeQueue.State.Merged),
          "a late refused round cannot reopen or re-close the membership"
        )
      end for
    finally DelegateJudge.DelegateJudgePort.clear()
    end try
  }

  test(
    "③ MergeQueue channel units: external entry injects via send (zero actor); a channel-less entry closes owner-gone with escalation"
  ) {
    val sent = new java.util.concurrent.ConcurrentLinkedQueue[String]()
    val escalations = new java.util.concurrent.ConcurrentLinkedQueue[(String, String)]()
    MergeQueue.OwnerGonePort.install((m, reason) => IO { escalations.add((m.delegateId, reason)) })
    // 真仓基座（净空闸/账实核对的真 git 面；分支未在仓 ⇒ 账实核对不命中）。
    val chanRepo = mkRepo(tempRoot / "ext-chan-repo")
    for
      // ③a 外通道注入单元：external 成员放行 = send 闭包收单（零 actor、零 replyTo）。
      _ <- IO {
        MergeQueue.enqueue(
          MergeQueue.Member(
            delegateId = "delegate-ext-chan",
            branch = "delegate/delegate-ext-chan",
            baseWs = chanRepo.toString,
            baseBranch = "main",
            worktree = false,
            projectKey = "p-chan",
            taskId = Some("71"),
            parentSessionId = "nebula-root",
            description = "t",
            readyAt = 1L,
            channelKind = "external",
            external = Some(MergeQueue.ExternalLeg(msg => IO { sent.add(msg); Right(()) }))
          )
        )
      }
      granted = MergeQueue.find("delegate-ext-chan")
      _ <- IO.sleep(100.millis)
      // 合并轮终态回报（Some(Merged)）= 关账 merged（幂等、放窗）。
      _ = MergeQueue.onTerminal("delegate-ext-chan", Some(MergeQueue.RoundOutcome.Merged))
      closed = MergeQueue.find("delegate-ext-chan")
      // ③b 无通道成员（重启后通道闭包丢失形态）：放行时通知不可达 ⇒ owner-gone 关账
      // + OwnerGonePort 升级——绝不静默 park，窗即时放给后继。
      _ <- IO {
        MergeQueue.enqueue(
          MergeQueue.Member(
            delegateId = "delegate-nochan",
            branch = "delegate/delegate-nochan",
            baseWs = chanRepo.toString,
            baseBranch = "main",
            worktree = false,
            projectKey = "p-nochan",
            taskId = Some("72"),
            parentSessionId = "nebula-root",
            description = "t",
            readyAt = 1L,
            channelKind = "external" // 通道闭包不持久——重启回载后 external/internal 皆 None
          )
        )
      }
      gone = MergeQueue.find("delegate-nochan")
      released = MergeQueue.queueViewOf("delegate-nochan")
    yield
      assertEquals(sent.size(), 1, "the external channel got exactly one merge brief (zero actor injection)")
      assert(
        sent.peek().contains("<system-reminder>") && sent.peek().contains("merge --no-ff"),
        "the injected text is the merge brief"
      )
      assertEquals(
        granted.map(_.state),
        Some(MergeQueue.State.TurnGranted),
        "the external member holds the window after the send"
      )
      assertEquals(
        closed.map(_.state),
        Some(MergeQueue.State.Merged),
        "the merge-round terminal closes the membership merged"
      )
      assertEquals(
        gone.map(_.state),
        Some(MergeQueue.State.OwnerGone),
        "a channel-less member is closed owner-gone at grant time"
      )
      assert(
        gone.flatMap(_.closeReason).exists(_.contains("no delivery channel on record")),
        s"the close reason names the undeliverable notification: ${gone.flatMap(_.closeReason)}"
      )
      assertEquals(
        escalations.size(),
        1,
        "exactly one owner-gone escalation (needs-dispatcher-handling is never silent)"
      )
      assert(escalations.peek()._2.contains("no delivery channel"), "the escalation carries the reason")
      assert(released.isEmpty, "the owner-gone close released the window (terminal members have no queue view)")
    end for
  }

end ExternalLegPrepareSpec
