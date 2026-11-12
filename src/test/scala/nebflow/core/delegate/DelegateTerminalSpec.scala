package nebflow.core.delegate

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.CatsEffectSuite
import nebflow.actor.{ActorRef, ActorSystem, AgentCommand, AgentEvent, Behavior, Behaviors}
import nebflow.agent.{SharedResources, SpecResources}
import nebflow.core.GitWorktrees
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, PathUtil, StreamChunk}

import scala.concurrent.duration.*

/**
 * DelegateTerminal 终态判定钩子判读（#566 修复批：blocked 判词退役——判官/预检/
 * 漂移的「没做成」一律 completed＋注记）：
 *
 *  - ① 判定机全败回落链（fail-open）：无自申报 → NudgeIdle（矩阵 complete×idle）；
 *    自申报 finish → completed；自申报 blocked（退役词）→ completed＋注记；
 *  - ④ onTerminalCleanup：completed 腿不重复落判词行；failed/cancelled 补
 *    engine 判词行（verdict=failed/cancelled 真值）。
 *  - ⑥-⑫（A5 步2 漂移闸红验）：确证 → completed＋注记（source=drift）/
 *    delegate 分支落地归属豁免 / 读数不可确证 fail-open / worktree 座位只注记
 *    ——另附分类器纯函数锚（读数显式入参，零 git）。
 *  - ⑬ judged-completed 工作座位 × 分支领先 ⇒ completed 交付 + unmerged-work
 *    注记行（集成走项目 merger 的授权请求，引擎零代并、零入队）。
 *
 * 判定机输入注入 = DelegateJudgePort 注册器（fake Face——M2 先例的测试装配面）。
 */
class DelegateTerminalSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 120.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-delegate-terminal"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  nebflow.ForceRemove.removeAll(tempRoot)
  os.makeDir.all(tempRoot)

  override def afterAll(): Unit = PathUtil.setDataRoot(originalRoot)

  override def beforeEach(context: munit.BeforeEach): Unit =
    nebflow.ForceRemove.removeAll(tempRoot / "chains")
    nebflow.ForceRemove.removeAll(tempRoot / "delegates")
    nebflow.ForceRemove.removeAll(tempRoot / "subagent-tasks")

  private def mkResources(
    system: ActorSystem,
    tmp: os.Path,
    llm: LlmHandle[IO]
  ): IO[SharedResources] =
    SpecResources.mkResources(system, tmp, llm)

  private class SilentLlm extends LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new RuntimeException("send not expected"))

    def sendStream(
      req: LlmRequest,
      onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
    ): fs2.Stream[IO, StreamChunk] =
      fs2.Stream(StreamChunk.TextDelta("ok"), StreamChunk.Done(None, None))

  private def seedRecord(
    id: String,
    taskId: Option[String],
    branch: Option[String] = None,
    cwd: os.Path = tempRoot,
    worktree: Option[os.Path] = None,
    projectWorkspace: Option[os.Path] = None,
    worktreeJudged: Option[Boolean] = None
  ): Unit =
    DelegateRegistry.put(
      DelegateRegistry.Record(
        id = id,
        executor = "nebflow",
        cwd = cwd.toString,
        project = None,
        taskId = taskId,
        parentSessionId = "nebula-root",
        title = "seeded",
        createdAt = 1L,
        branch = branch,
        baseBranch = Some("main"),
        projectWorkspace = Some(projectWorkspace.getOrElse(cwd).toString),
        worktree = worktree.map(_.toString),
        worktreeJudged = worktreeJudged
      )
    )

  private def noopRefs(system: ActorSystem): IO[(ActorRef[AgentCommand], ActorRef[AgentEvent])] =
    for
      child <- system.spawn(
        new Behavior[AgentCommand]:
          def receive(ctx: nebflow.actor.ActorContext[AgentCommand], msg: AgentCommand): IO[Behavior[AgentCommand]] =
            IO.pure(this)
        ,
        s"child-${scala.util.Random.nextInt(100000)}"
      )
      sup <- system.spawn(
        new Behavior[AgentEvent]:
          def receive(ctx: nebflow.actor.ActorContext[AgentEvent], msg: AgentEvent): IO[Behavior[AgentEvent]] =
            IO.pure(this)
        ,
        s"sup-${scala.util.Random.nextInt(100000)}"
      )
    yield (child, sup)

  /** 判定机不可用 = 端口卸载（未注册 → None → 回落链）。 */
  private def withJudgeAbsent[A](io: IO[A]): IO[A] =
    IO { DelegateJudge.DelegateJudgePort.clear() } *> io <* IO { DelegateJudge.DelegateJudgePort.clear() }

  private def verdictLine(delegateId: String): Option[String] =
    ChainStore
      .listSync()
      .flatMap(_.entries)
      .filter(_.kind == "terminal-verdict")
      .flatMap { e =>
        e.payload.hcursor
          .get[String]("delegateId")
          .toOption
          .filter(_ == delegateId)
          .map(_ => e.payload.hcursor.get[String]("verdict").toOption.getOrElse(""))
      }
      .lastOption

  test(
    "① pass-through contract (report channel retired): every turn end releases to the parent — undeclared, finish-slot, retired-blocked-slot, retired-merge-slot all land the same delivery; no verdict line is written"
  ) {
    val system = ActorSystem(s"dt-1-${scala.util.Random.nextInt(100000)}")
    for
      res <- mkResources(system, tempRoot, new SilentLlm)
      (child, sup) <- noopRefs(system)
      _ = seedRecord("delegate-nebflow-f1", Some("41"))
      r1 <- withJudgeAbsent(
        DelegateTerminal.onCompleted(res, "delegate-nebflow-f1", "nebula-root", "t", "brief", "done work", child, sup)
      )
      _ = seedRecord("delegate-nebflow-f2", Some("42"))
      _ = DelegateRegistry.updateReport("delegate-nebflow-f2", "finish", "declared done", "")
      r2 <- withJudgeAbsent(
        DelegateTerminal.onCompleted(res, "delegate-nebflow-f2", "nebula-root", "t", "brief", "result text", child, sup)
      )
      _ = seedRecord("delegate-nebflow-f3", Some("43"))
      _ = DelegateRegistry.updateReport("delegate-nebflow-f3", "blocked", "missing key material", "")
      r3 <- withJudgeAbsent(
        DelegateTerminal.onCompleted(res, "delegate-nebflow-f3", "nebula-root", "t", "brief", "gave up", child, sup)
      )
      _ = seedRecord("delegate-nebflow-f4", Some("44"))
      _ = DelegateRegistry.updateReport("delegate-nebflow-f4", "merge", "left on the branch", "")
      r4 <- withJudgeAbsent(
        DelegateTerminal.onCompleted(res, "delegate-nebflow-f4", "nebula-root", "t", "brief", "left it", child, sup)
      )
      v1 = verdictLine("delegate-nebflow-f1")
      v2 = verdictLine("delegate-nebflow-f2")
      v3 = verdictLine("delegate-nebflow-f3")
      v4 = verdictLine("delegate-nebflow-f4")
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield
      // 申报工具通道退役（T2 批）：回合结束 = 完成交付——四种槽位形态（未申报 /
      // finish / 退役 blocked / 退役 merge）一律 Release 直通，槽位不再改写终局。
      List("r1" -> r1, "r2" -> r2, "r3" -> r3, "r4" -> r4).foreach { (name, r) =>
        assert(
          r.isInstanceOf[DelegateTerminal.Outcome.Release],
          s"$name: every turn end must release (pass-through), got: $r"
        )
      }
      assert(r1.toString.contains("done work"), "the release payload carries the final text")
      assert(r2.toString.contains("result text"), "the declared-slot shape releases the same way")
      // 判词行不再写（判定机/自申报消费随通道退役）。
      List("f1" -> v1, "f2" -> v2, "f3" -> v3, "f4" -> v4).foreach { (name, v) =>
        assertEquals(v, None, s"$name: no terminal-verdict line may be written (retired protocol)")
      }
    end for
  }

  test(
    "④ cleanup: failed/cancelled terminals write the engine verdict line (verdict=failed, true value); the completed leg does NOT double-write"
  ) {
    val system = ActorSystem(s"dt-4-${scala.util.Random.nextInt(100000)}")
    for
      res <- mkResources(system, tempRoot, new SilentLlm)
      _ = seedRecord("delegate-nebflow-fail", Some("71"), branch = Some("delegate/delegate-nebflow-fail"))
      _ <- DelegateTerminal.onTerminalCleanup(res, "delegate-nebflow-fail", "nebula-root", "failed", "failed")
      // completed 腿（onCompleted 已写 judge 行）→ cleanup 不得再补 engine 行
      before = ChainStore
        .listSync()
        .flatMap(_.entries)
        .count(e => e.kind == "terminal-verdict" && e.payload.hcursor.get[String]("source").toOption.contains("engine"))
      _ <- DelegateTerminal.onTerminalCleanup(res, "delegate-nebflow-fail", "nebula-root", "completed", "completed")
      after = ChainStore
        .listSync()
        .flatMap(_.entries)
        .count(e => e.kind == "terminal-verdict" && e.payload.hcursor.get[String]("source").toOption.contains("engine"))
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield
      assertEquals(before, 1, "the failed terminal wrote exactly one engine verdict line")
      assertEquals(after, before, "the completed leg must not double-write an engine verdict line")
      // engine 判词行写真值（verdict=failed），不再冒充 blocked。
      assert(
        ChainStore
          .listSync()
          .flatMap(_.entries)
          .filter(e =>
            e.kind == "terminal-verdict" && e.payload.hcursor.get[String]("source").toOption.contains("engine")
          )
          .forall(_.payload.hcursor.get[String]("verdict").toOption.contains("failed")),
        "the engine verdict line carries verdict=failed"
      )
    end for
  }

  // ── A5 步2 漂移闸（评审 §六步2 红验）─────────────────────────────────

  /** 判词行三元读数：(verdict, source, reason)——payload 承重键直读。 */
  private def verdictRows(delegateId: String): List[(String, String, String)] =
    ChainStore.listSync().flatMap(_.entries).filter(_.kind == "terminal-verdict").flatMap { e =>
      val c = e.payload.hcursor
      c.get[String]("delegateId")
        .toOption
        .filter(_ == delegateId)
        .map(_ =>
          (
            c.get[String]("verdict").toOption.getOrElse(""),
            c.get[String]("source").toOption.getOrElse(""),
            c.get[String]("reason").toOption.getOrElse("")
          )
        )
    }

  /**
   * 漂移真仓夹具：main@init(F) + delegate 分支 park 在 F（只读座位形态）；
   * advance="direct" ⇒ main 直写一笔（不可归属证据）；advance="delegateMerge" ⇒
   * delegate/other 一笔后 `--no-ff` 并回 main（归属候选——delegate 分支的落地只能
   * 经项目 merger 的授权请求）。
   * 返回 (repo, forkSha, baseTipSha)——全 sha，与判词 reason 里的证据直比。
   */
  private def mkDriftRepo(dir: os.Path, delegateBranch: String, advance: String): (os.Path, String, String) =
    nebflow.ForceRemove.removeAll(dir)
    os.makeDir.all(dir)
    os.proc("git", "init", "-b", "main", dir.toString).call(check = true, stdout = os.Pipe, stderr = os.Pipe)
    def g(args: String*): os.CommandResult =
      os.proc(Seq("git", "-C", dir.toString) ++ args).call(check = true, stdout = os.Pipe, stderr = os.Pipe)
    g("config", "user.email", "spec@nebflow.local")
    g("config", "user.name", "spec")
    g("commit", "--allow-empty", "-m", "init")
    val fork = os.proc("git", "-C", dir.toString, "rev-parse", "main").call(check = true).out.trim()
    g("branch", delegateBranch)
    if advance == "direct" then
      os.write(dir / "outside.txt", "third-party write on the base branch")
      g("add", ".")
      g("commit", "-m", "outside direct commit")
    else if advance == "delegateMerge" then
      g("checkout", "-b", "delegate/other")
      os.write(dir / "other.txt", "other delegate deliverable")
      g("add", ".")
      g("commit", "-m", "other delegate deliverable")
      g("checkout", "main")
      g("merge", "--no-ff", "delegate/other", "-m", "merge delegate/other")
    val baseTip = os.proc("git", "-C", dir.toString, "rev-parse", "main").call(check = true).out.trim()
    (dir, fork, baseTip)

  end mkDriftRepo

  /** worktree 座位夹具：为已 park 的分支加挂 worktree（路径在仓外，防 untracked 互染）。 */
  private def addWorktree(repo: os.Path, branch: String, wt: os.Path): os.Path =
    os.proc("git", "-C", repo.toString, "worktree", "add", wt.toString, branch)
      .call(check = true, stdout = os.Pipe, stderr = os.Pipe)
    wt

  test(
    "⑨ classifier Warn branch (pure, zero git): missing anchor reading ⇒ Warn, never Penalty (禁假阻塞); the drift e2e half retired with the declaration channel (T2)"
  ) {
    // 分类器纯函数锚（零 git）：锚读数缺失 ⇒ Warn，绝不 Penalty（🔴 禁假阻塞）
    val rec = DelegateRegistry.Record(
      id = "dg-x",
      executor = "nebflow",
      cwd = "/tmp",
      project = None,
      taskId = None,
      parentSessionId = "p",
      title = "t",
      createdAt = 1L,
      branch = Some("delegate/x"),
      baseBranch = Some("main")
    )
    val warned = DelegateTerminal.classifyDrift(
      rec,
      DelegateTerminal.DriftReadings(
        fork = None,
        baseTip = Some("bt"),
        branchTip = Some("bt"),
        baseAdvances = (_, _) => None,
        branchTips = () => None,
        isAncestorOf = (_, _) => Some(false)
      )
    )
    assert(warned.isInstanceOf[DelegateTerminal.DriftResult.Warn], s"missing anchor reading ⇒ Warn, got: $warned")
  }

  test("⑪ classifier matrix (pure, zero git): penalty/note/clear/attribution rows") {
    val base = DelegateRegistry.Record(
      id = "dg-m",
      executor = "nebflow",
      cwd = "/tmp",
      project = None,
      taskId = None,
      parentSessionId = "p",
      title = "t",
      createdAt = 1L,
      branch = Some("delegate/m"),
      baseBranch = Some("main")
    )
    val shared = DelegateTerminal.DriftReadings(
      fork = Some("f"),
      baseTip = Some("b"),
      branchTip = Some("f"),
      baseAdvances = (_, _) => Some(List(GitWorktrees.CommitInfo("c1", List("f"), "wip"))),
      branchTips = () => Some(Nil),
      isAncestorOf = (_, _) => Some(false)
    )
    // 共享座位 × 不可归属直写 ⇒ Penalty，reason 携证据三件
    val p = DelegateTerminal.classifyDrift(base, shared)
    assert(p.isInstanceOf[DelegateTerminal.DriftResult.Penalty], s"got: $p")
    val reason = p.asInstanceOf[DelegateTerminal.DriftResult.Penalty].reason
    assert(
      reason.contains("f") && reason.contains("b") && reason.contains("c1") && reason.contains("direct commit"),
      reason
    )
    // worktree 座位同读数 ⇒ NoteOnly（只注记不罚）
    val wtRec = base.copy(worktree = Some("/wt"))
    assert(DelegateTerminal.classifyDrift(wtRec, shared).isInstanceOf[DelegateTerminal.DriftResult.NoteOnly])
    // delegate 形 merge（delegate/ 前缀归属）＋其搭载提交（可达被并 tip）⇒ Clear
    // （归属豁免——fork..baseTip 含被并分支自身提交，随并归属）
    val merged = shared.copy(
      baseAdvances = (_, _) =>
        Some(
          List(
            GitWorktrees.CommitInfo("m1", List("f", "t2"), "merge other"),
            GitWorktrees.CommitInfo("d1", List("f"), "other's own deliverable")
          )
        ),
      branchTips = () => Some(List("delegate/other" -> "t2")),
      isAncestorOf = (c, t) => Some(c == "d1" && t == "t2")
    )
    assert(DelegateTerminal.classifyDrift(base, merged) == DelegateTerminal.DriftResult.Clear)
    // 搭载提交不可达（祖先读数不可确证）⇒ Warn（fail-open）
    val brokenAncestry = merged.copy(isAncestorOf = (_, _) => None)
    assert(
      DelegateTerminal.classifyDrift(base, brokenAncestry).isInstanceOf[DelegateTerminal.DriftResult.Warn]
    )
    // 非 delegate ref 的 merge ＋不在任何 attributable 支上的单亲 ⇒ Penalty
    val foreignMerge = merged.copy(
      branchTips = () => Some(List("feat/landing" -> "t2")),
      isAncestorOf = (_, _) => Some(false)
    )
    assert(
      DelegateTerminal
        .classifyDrift(base, foreignMerge)
        .isInstanceOf[DelegateTerminal.DriftResult.Penalty]
    )
  }

  test(
    "⑫ legacy shapes stay Clear: no branch anchor, non-git cwd, zero-ahead with unmoved base (existing regression)"
  ) {
    val base = DelegateRegistry.Record(
      id = "dg-l",
      executor = "nebflow",
      cwd = "/tmp",
      project = None,
      taskId = None,
      parentSessionId = "p",
      title = "t",
      createdAt = 1L,
      branch = None,
      baseBranch = Some("main")
    )
    val readings = DelegateTerminal.DriftReadings(None, None, None, (_, _) => None, () => None, (_, _) => Some(false))
    // 无锚 ⇒ 闸盲（外部执行器腿 / 判否零绑定 legacy 记录同形）
    assert(DelegateTerminal.classifyDrift(base, readings) == DelegateTerminal.DriftResult.Clear)
    // D1∨D2 不成立 ⇒ Clear（delegate 自己有领先提交 / 基未动的零领先）
    val noDrift =
      DelegateTerminal.DriftReadings(Some("f"), Some("f"), Some("f"), (_, _) => None, () => None, (_, _) => Some(false))
    val withBranch = base.copy(branch = Some("delegate/l"))
    assert(DelegateTerminal.classifyDrift(withBranch, noDrift) == DelegateTerminal.DriftResult.Clear)
    val ownWork =
      DelegateTerminal.DriftReadings(Some("f"), Some("b"), Some("w"), (_, _) => None, () => None, (_, _) => Some(false))
    assert(DelegateTerminal.classifyDrift(withBranch, ownWork) == DelegateTerminal.DriftResult.Clear)
  }

  test(
    "⑬ pass-through contract on a working seat with commits ahead: the turn end releases to the parent; the engine never merges anything itself and writes no audit rows (the unmerged-work note channel retired with the declaration channel)"
  ) {
    val system = ActorSystem(s"dt-13-${scala.util.Random.nextInt(100000)}")
    for
      res <- mkResources(system, tempRoot, new SilentLlm)
      (child, sup) <- noopRefs(system)
      // 工作座位形态：分支领先 1。
      workingRepo = mkRealRepoLike(tempRoot / "work-note-repo", branch = "delegate/delegate-nebflow-wn", ahead = 1)
      _ = seedRecord(
        "delegate-nebflow-wn",
        Some("64"),
        branch = Some("delegate/delegate-nebflow-wn"),
        cwd = workingRepo,
        worktree = Some(workingRepo),
        worktreeJudged = Some(true)
      )
      workOutcome <- DelegateTerminal.onCompleted(
        res,
        "delegate-nebflow-wn",
        "nebula-root",
        "t",
        "brief",
        "done",
        child,
        sup
      )
      noteRows = ChainStore.listSync().flatMap(_.entries).filter(_.kind == "unmerged-work")
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield
      assert(
        workOutcome.isInstanceOf[DelegateTerminal.Outcome.Release],
        s"a turn end releases to the parent: $workOutcome"
      )
      assert(workOutcome.toString.contains("done"), "the release payload carries the final text")
      assertEquals(
        verdictLine("delegate-nebflow-wn"),
        None,
        "no terminal-verdict line (the judged-completed protocol retired with the declaration channel)"
      )
      assert(noteRows.isEmpty, "no unmerged-work audit row (the note channel retired)")
      assert(
        !GitWorktrees.isAncestor(workingRepo, "delegate/delegate-nebflow-wn", "main").contains(true),
        "the engine did NOT merge anything itself (integration is a separate authorized merger request)"
      )
    end for
  }

  /**
   * 真仓夹具（⑬ 判据需要 git 面可读）：main 上 init；ahead > 0 ⇒ branch 上造领先
   * 提交（工作座位形态），ahead = 0 ⇒ branch park 在 main HEAD。
   */
  private def mkRealRepoLike(dir: os.Path, branch: String, ahead: Int): os.Path =
    nebflow.ForceRemove.removeAll(dir)
    os.makeDir.all(dir)
    os.proc("git", "init", "-b", "main", dir.toString).call(check = true, stdout = os.Pipe, stderr = os.Pipe)
    os.proc("git", "-C", dir.toString, "config", "user.email", "spec@nebflow.local").call(check = true)
    os.proc("git", "-C", dir.toString, "config", "user.name", "spec").call(check = true)
    os.proc("git", "-C", dir.toString, "commit", "--allow-empty", "-m", "init")
      .call(check = true, stdout = os.Pipe, stderr = os.Pipe)
    if ahead > 0 then
      os.proc("git", "-C", dir.toString, "checkout", "-b", branch)
        .call(check = true, stdout = os.Pipe, stderr = os.Pipe)
      (1 to ahead).foreach { i =>
        os.write(dir / f"deliverable-$i.txt", s"deliverable $i")
        os.proc("git", "-C", dir.toString, "add", ".").call(check = true)
        os.proc("git", "-C", dir.toString, "commit", "-m", s"deliverable $i")
          .call(check = true, stdout = os.Pipe, stderr = os.Pipe)
      }
    else os.proc("git", "-C", dir.toString, "branch", branch).call(check = true, stdout = os.Pipe, stderr = os.Pipe)
    dir

  end mkRealRepoLike

end DelegateTerminalSpec
