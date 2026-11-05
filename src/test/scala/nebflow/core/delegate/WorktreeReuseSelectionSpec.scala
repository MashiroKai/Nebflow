package nebflow.core.delegate

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.FunSuite
import nebflow.core.GitWorktrees
import nebflow.shared.PathUtil

import scala.util.Try

/**
 * worktree-reuse-selection 批（2026-11-02 · 作者令逐字「不仅是要能分配 worktree
 * 还要能选择接着已有的 worktree继续干的能力 灵活一点」）——候选发现 / 状态标注 /
 * 续用解析 / 防错配 的机械判读（真仓实测，临时目录，对主仓零 git 写）。
 *
 * 覆盖任务书的 J2 / J3 / J4 / J5 / J6：
 *  - J2 候选清单三态标注（孤立 / 活跃 / 已完成）+ 脏度读数；
 *  - J3 续用生效：座位 = 该树、分支正确、继承的未提交改动可见、brief 申报继承事实；
 *  - J4 默认不续用：不传 worktree ⇒ prepare 仍建新树（行为与改前一致）；
 *  - J5 防错配：续用被活会话持有的树 ⇒ 拒（force=true 才放行），文案说清原因；
 *  - J6 端到端复演今天的实案：delegate 已死 + 树上有提交与未提交改动 ⇒ 识别为
 *   孤立并给出续用选项。
 */
class WorktreeReuseSelectionSpec extends FunSuite:

  private val tempRoot: os.Path = os.pwd / "target" / "test-worktree-reuse"
  private val originalRoot = PathUtil.dataRoot
  private val tempDataRoot = tempRoot / "data-root"

  override def beforeEach(context: BeforeEach): Unit =
    nebflow.ForceRemove.removeAll(tempRoot)
    os.makeDir.all(tempDataRoot)
    PathUtil.setDataRoot(tempDataRoot)
    DelegateRegistry  // touch（初始化的早）

  override def afterEach(context: AfterEach): Unit =
    PathUtil.setDataRoot(originalRoot)
    WorktreeCandidates.LiveSessions.clear()
    DelegateJudge.DelegateJudgePort.clear()

  private def gitAvailable: Boolean =
    Try(os.proc("git", "--version").call(check = false, stdout = os.Pipe, stderr = os.Pipe).exitCode == 0)
      .getOrElse(false)

  /** 基仓（main@init）+ 若干 delegate worktree（分支 `delegate/<id>`，可选脏/领先）。 */
  private def mkRepo(dir: os.Path): os.Path =
    nebflow.ForceRemove.removeAll(dir)
    os.makeDir.all(dir)
    os.proc("git", "init", "-b", "main", dir.toString).call(check = true, stdout = os.Pipe, stderr = os.Pipe)
    def g(args: String*): os.CommandResult =
      os.proc(Seq("git", "-C", dir.toString) ++ args).call(check = true, stdout = os.Pipe, stderr = os.Pipe)
    g("config", "user.email", "spec@nebflow.local")
    g("config", "user.name", "spec")
    g("commit", "--allow-empty", "-m", "init")
    dir

  /**
   * 造一棵 delegate worktree（走生产建权路径 [[GitWorktrees.createWorktree]]，分支
   * `delegate/<id>`），可选在其上做一笔提交 / 留一处未提交改动 / 登记 delegate 记录。
   */
  private def mkDelegateWorktree(
    repo: os.Path,
    id: String,
    commit: Boolean = false,
    dirty: Boolean = false,
    register: Boolean = true,
    createdAt: Long = System.currentTimeMillis()
  ): os.Path =
    val created = GitWorktrees
      .createWorktree(
        repo,
        dirBase = GitWorktrees.sanitizeRefName(id),
        branch = s"delegate/$id",
        baseRef = "HEAD",
        label = s"delegate $id",
        branchFollowsDir = false
      )
      .fold(e => fail(s"fixture worktree creation failed for $id: $e"), identity)
    val wt = created.path
    if commit then
      os.write.over(wt / "work.txt", s"work by $id")
      os.proc("git", "-C", wt.toString, "add", ".").call(check = true, stdout = os.Pipe, stderr = os.Pipe)
      os.proc("git", "-C", wt.toString, "commit", "-m", s"deliverable by $id").call(check = true, stdout = os.Pipe, stderr = os.Pipe)
    if dirty then os.write.over(wt / "in-progress.txt", s"uncommitted work by $id")
    if register then
      DelegateRegistry.put(
        DelegateRegistry.Record(
          id = id,
          executor = "nebflow",
          cwd = wt.toString,
          project = None,
          taskId = None,
          parentSessionId = "parent-spec",
          title = s"task of $id",
          createdAt = createdAt,
          branch = Some(s"delegate/$id"),
          worktree = Some(wt.toString),
          baseBranch = Some("main"),
          projectWorkspace = Some(repo.toString)
        )
      )
    wt

  // ── J2 候选清单：三态标注 ────────────────────────────────────────────────

  test("J2 candidate inventory tags the three states correctly (orphan / active / done) with work readings") {
    assume(gitAvailable, "git required")
    val repo = mkRepo(tempRoot / "j2-repo")
    // ① 孤立：delegate 已死（不在活会话面）+ 一笔提交 + 一处未提交改动
    mkDelegateWorktree(repo, "delegate-nebflow-orphan", commit = true, dirty = true)
    // ② 活跃：delegate 在活会话面
    mkDelegateWorktree(repo, "delegate-nebflow-live", commit = false, dirty = false)
    // ③ 已完成：delegate 已死且树干净
    mkDelegateWorktree(repo, "delegate-nebflow-done", commit = false, dirty = false)
    // 活会话端口：只认 ②
    WorktreeCandidates.LiveSessions.install(
      new WorktreeCandidates.LiveSessions.Face:
        def liveAmong(ids: List[String]): IO[Set[String]] =
          IO.pure(ids.filter(_ == "delegate-nebflow-live").toSet)
    )

    val inv = WorktreeCandidates.inventory(repo.toString).unsafeRunSync()
    assertEquals(inv.all.size, 3, s"three worktrees expected, got: ${inv.all.map(_.dirName)}")

    val orphan = inv.byId("delegate-nebflow-orphan").getOrElse(fail("orphan candidate missing"))
    assertEquals(orphan.status, WorktreeCandidates.Status.Orphan)
    assertEquals(orphan.live, false)
    assertEquals(orphan.aheadOfBase, 1, "one deliverable commit ahead of main")
    assertEquals(orphan.dirty, 1, "one uncommitted path")
    assert(orphan.hasWork, "orphan must read as 'has work'")
    assertEquals(orphan.branch, Some("delegate/delegate-nebflow-orphan"))
    assert(orphan.headSha.isDefined, "HEAD sha must be read")
    assert(orphan.lastActiveMs > 0, "last-activity timestamp must be read")
    assertEquals(orphan.dirtySample, List("?? in-progress.txt"))

    val live = inv.byId("delegate-nebflow-live").getOrElse(fail("active candidate missing"))
    assertEquals(live.status, WorktreeCandidates.Status.Active)
    assertEquals(live.live, true)

    val done = inv.byId("delegate-nebflow-done").getOrElse(fail("done candidate missing"))
    assertEquals(done.status, WorktreeCandidates.Status.Done)
    assertEquals(done.live, false)
    assertEquals(done.hasWork, false, "a clean tree with zero commits ahead has no work")
    assertEquals(done.aheadOfBase, 0)
    assertEquals(done.dirty, 0)

    // 排序：孤立置顶（模型面先看到最该被续用的那棵）
    assertEquals(inv.ranked.head.dirName, "delegate-nebflow-orphan")
    assertEquals(inv.orphans.map(_.delegateId.getOrElse("")), List("delegate-nebflow-orphan"))
  }

  test("J2b rendered candidate list carries the three status words and the reuse instruction") {
    assume(gitAvailable, "git required")
    val repo = mkRepo(tempRoot / "j2b-repo")
    mkDelegateWorktree(repo, "delegate-nebflow-orphan", commit = true, dirty = true)
    mkDelegateWorktree(repo, "delegate-nebflow-live")
    mkDelegateWorktree(repo, "delegate-nebflow-done")
    WorktreeCandidates.LiveSessions.install(
      new WorktreeCandidates.LiveSessions.Face:
        def liveAmong(ids: List[String]): IO[Set[String]] = IO.pure(ids.filter(_.endsWith("-live")).toSet)
    )
    val inv = WorktreeCandidates.inventory(repo.toString).unsafeRunSync()
    val text = WorktreeCandidates.render(inv, repo.toString)

    assert(text.contains("# Existing delegate worktrees (name reuse candidates)"), text)
    assert(text.contains("**orphan**"), s"orphan status word missing:\n$text")
    assert(text.contains("**active**"), s"active status word missing:\n$text")
    assert(text.contains("**done**"), s"done status word missing:\n$text")
    assert(text.contains("WORK IN PROGRESS: 1 commit(s) ahead of main, 1 uncommitted"), s"work readings missing:\n$text")
    assert(text.contains("uncommitted sample: ?? in-progress.txt"), s"dirty sample missing:\n$text")
    assert(text.contains("`delegate-nebflow-orphan`"), s"orphan row missing:\n$text")
    assert(text.contains("delegate-nebflow-orphan \"task of delegate-nebflow-orphan\""), s"title missing:\n$text")
    assert(text.contains("Delegate(worktree="), s"the reuse instruction must be present:\n$text")
    assert(text.contains("force=true"), s"the force escape hatch must be documented:\n$text")
    assert(text.contains("omitting `worktree` allocates nothing"), s"the zero-allocation default must be documented:\n$text")
    // 空清单 = 空串（段不注入——零字节变化）
    assertEquals(WorktreeCandidates.render(WorktreeCandidates.Inventory(Nil), repo.toString), "")
  }

  // ── J3 续用生效 ─────────────────────────────────────────────────────────

  test("J3 reusing an orphan worktree binds the seat to it: same path, inherited uncommitted work visible, branch correct") {
    assume(gitAvailable, "git required")
    val repo = mkRepo(tempRoot / "j3-repo")
    val orphanWt = mkDelegateWorktree(repo, "delegate-nebflow-4b756df9", commit = true, dirty = true)
    WorktreeCandidates.LiveSessions.install(
      new WorktreeCandidates.LiveSessions.Face:
        def liveAmong(ids: List[String]): IO[Set[String]] = IO.pure(Set.empty)
    )
    val inv = WorktreeCandidates.inventory(repo.toString).unsafeRunSync()
    val decision = WorktreeCandidates.resolve(inv, "delegate-nebflow-4b756df9", force = false)
    val cand = decision match
      case WorktreeCandidates.Decision.Reuse(Some(c), _) => c
      case other                                         => fail(s"expected a reuse decision, got: $other")

    val binding = DelegateWorkspace
      .reuse(repo.toString, "delegate-nebflow-new", cand, DelegateWorkspace.SeatSource.Legacy)
      .unsafeRunSync()

    // 座位 = 那棵树（不是新建的第二棵）
    assertEquals(binding.worktree, Some(orphanWt.toString))
    assertEquals(binding.cwd, orphanWt.toString)
    assertEquals(binding.branch, "delegate/delegate-nebflow-4b756df9", "the branch is the INHERITED one")
    assertEquals(binding.baseWs, repo.toString)
    assertEquals(binding.baseBranch, Some("main"))
    assertEquals(binding.reusedFrom, Some("delegate-nebflow-4b756df9"))
    assertEquals(binding.seatSource, DelegateWorkspace.SeatSource.Legacy)
    // 树上没有第二棵树（零新建）
    assertEquals(GitWorktrees.worktreeList(repo).size, 2, "只有 main + 那一棵既有树，未新建")
    // 继承的未提交改动仍在（模型开局就在成果之上）
    assert(os.exists(orphanWt / "in-progress.txt"), "the inherited uncommitted file must still be there")
    assert(os.exists(orphanWt / "work.txt"), "the inherited commit's file must be there")
    assertEquals(GitWorktrees.dirtyPaths(orphanWt).map(_.size), Some(1))
    assertEquals(GitWorktrees.commitsAhead(orphanWt, "main", binding.branch), 1)

    // brief 申报行：继承事实必须显式告知（模型面）
    val line = binding.briefLine.getOrElse(fail("reuse brief line missing"))
    assert(line.contains("[working branch] delegate/delegate-nebflow-4b756df9"), line)
    assert(line.contains("REUSED seat"), s"the reuse marker is missing:\n$line")
    assert(line.contains("INHERITED from an earlier session (delegate-nebflow-4b756df9)"), s"inheritance not declared:\n$line")
    assert(line.contains("1 commit(s) ahead of the base branch"), s"inherited commits not declared:\n$line")
    assert(line.contains("1 uncommitted path(s)"), s"inherited dirty state not declared:\n$line")
    assert(line.contains("Continue that work"), line)
    assert(line.contains("do not discard them"), line)
    assert(line.contains("NEVER commit directly to the base branch"), line)
  }

  // ── J4 默认不续用（行为与改前一致） ─────────────────────────────────────

  test("J4 without the worktree parameter NOTHING is allocated: the seat is the workspace itself (read-only channel)") {
    assume(gitAvailable, "git required")
    val repo = mkRepo(tempRoot / "j4-repo")
    val orphanWt = mkDelegateWorktree(repo, "delegate-nebflow-orphan", commit = true, dirty = true)
    WorktreeCandidates.LiveSessions.install(
      new WorktreeCandidates.LiveSessions.Face:
        def liveAmong(ids: List[String]): IO[Set[String]] = IO.pure(Set.empty)
    )
    val before = os.list(repo / ".nebflow" / "worktrees").map(_.last).toList.sorted
    // 缺省腿 = DelegateWorkspace.prepareInPlace（spawnWithPlugins 的 InPlace 计划）
    val binding = DelegateWorkspace
      .prepareInPlace(repo.toString, "delegate-nebflow-fresh")
      .unsafeRunSync()
      .getOrElse(fail("the in-place leg must produce a binding in a git workspace"))

    assertEquals(binding.worktree, None, "zero allocation: no worktree in the binding")
    assertEquals(binding.cwd, repo.toString, "the seat is the shared project workspace")
    assertEquals(binding.seatSource, DelegateWorkspace.SeatSource.InPlace)
    assertEquals(binding.branch, "delegate/delegate-nebflow-fresh", "a NEW branch for the new session")
    assertEquals(binding.reusedFrom, None, "no reuse marker")
    val after = os.list(repo / ".nebflow" / "worktrees").map(_.last).toList.sorted
    assertEquals(after, before, "not even one new worktree directory may appear")
    assertNotEquals(binding.worktree, Some(orphanWt.toString), "must NOT land in the existing orphan tree")
    // 既有树原样未动
    assert(os.exists(orphanWt / "in-progress.txt"))
  }

  // ── J5 防错配 ───────────────────────────────────────────────────────────

  test("J5 reusing a worktree held by a LIVE session is REFUSED, and force=true is the only way through") {
    assume(gitAvailable, "git required")
    val repo = mkRepo(tempRoot / "j5-repo")
    mkDelegateWorktree(repo, "delegate-nebflow-live", commit = true, dirty = true)
    WorktreeCandidates.LiveSessions.install(
      new WorktreeCandidates.LiveSessions.Face:
        def liveAmong(ids: List[String]): IO[Set[String]] = IO.pure(ids.toSet)
    )
    val inv = WorktreeCandidates.inventory(repo.toString).unsafeRunSync()
    assertEquals(inv.byId("delegate-nebflow-live").map(_.status), Some(WorktreeCandidates.Status.Active))

    // 默认拒
    WorktreeCandidates.resolve(inv, "delegate-nebflow-live", force = false) match
      case WorktreeCandidates.Decision.Refuse(code, msg) =>
        assertEquals(code, WorktreeCandidates.RefuseCode.Active)
        assert(msg.contains("held by a LIVE session"), s"refusal must name the live holder:\n$msg")
        assert(msg.contains("delegate-nebflow-live"), s"refusal must name WHO holds it:\n$msg")
        assert(msg.contains("force=true"), s"refusal must offer the escape hatch:\n$msg")
        assert(msg.contains("two agents must not edit one tree"), s"refusal must state the hazard:\n$msg")
      case other => fail(s"expected a refusal, got: $other")

    // 显式 force ⇒ 放行（红线：只有明确声明才越过）
    WorktreeCandidates.resolve(inv, "delegate-nebflow-live", force = true) match
      case WorktreeCandidates.Decision.Reuse(Some(c), p) =>
        assertEquals(c.dirName, "delegate-nebflow-live")
        assertEquals(p, inv.byId("delegate-nebflow-live").get.path)
      case other => fail(s"force=true must be honored, got: $other")
  }

  test("J5b an unknown target is REFUSED explicitly (no silent fallback to a fresh worktree)") {
    assume(gitAvailable, "git required")
    val repo = mkRepo(tempRoot / "j5b-repo")
    mkDelegateWorktree(repo, "delegate-nebflow-x", commit = false, dirty = false)
    WorktreeCandidates.LiveSessions.install(
      new WorktreeCandidates.LiveSessions.Face:
        def liveAmong(ids: List[String]): IO[Set[String]] = IO.pure(Set.empty)
    )
    val inv = WorktreeCandidates.inventory(repo.toString).unsafeRunSync()
    WorktreeCandidates.resolve(inv, "delegate-nebflow-nonexistent", force = false) match
      case WorktreeCandidates.Decision.Refuse(code, msg) =>
        assertEquals(code, WorktreeCandidates.RefuseCode.NotFound)
        assert(msg.contains("no worktree matches"), msg)
        assert(msg.contains("allocates no worktree at all"), s"the zero-allocation default must be restated:\n$msg")
      case other => fail(s"expected a refusal, got: $other")
  }

  // ── J6 端到端复演今天的实案 ──────────────────────────────────────────────

  test("J6 today's real case: a dead delegate whose tree holds commits + uncommitted work is surfaced as ORPHAN with a reuse option") {
    assume(gitAvailable, "git required")
    val repo = mkRepo(tempRoot / "j6-repo")
    // 复演：真正的活在 4b756df9 那棵树里（两个提交 + P1 未提交改动），owner 已死
    val realWork = mkDelegateWorktree(repo, "delegate-nebflow-4b756df9", commit = true, dirty = true)
    os.write.over(realWork / "P1-uncommitted.scala", "// P1 fix in progress")
    os.write.over(realWork / "P0-fix.scala", "// P0 fix")
    os.proc("git", "-C", realWork.toString, "add", "P0-fix.scala").call(check = true, stdout = os.Pipe, stderr = os.Pipe)
    os.proc("git", "-C", realWork.toString, "commit", "-m", "fix: P0 second commit").call(check = true, stdout = os.Pipe, stderr = os.Pipe)
    // 重派时的第三个会话会拿到一棵全新的空树（模拟：先建出来，确认它不是被选中的那棵）
    val freshForThird = mkDelegateWorktree(repo, "delegate-nebflow-c4e7352c", commit = false, dirty = false)

    // owner 已死（不在活会话面）
    WorktreeCandidates.LiveSessions.install(
      new WorktreeCandidates.LiveSessions.Face:
        def liveAmong(ids: List[String]): IO[Set[String]] = IO.pure(Set.empty)
    )

    val inv = WorktreeCandidates.inventory(repo.toString).unsafeRunSync()
    val real = inv.byId("delegate-nebflow-4b756df9").getOrElse(fail("the real work tree must be enumerated"))
    assertEquals(real.status, WorktreeCandidates.Status.Orphan, "today's tree is the ORPHAN case")
    assertEquals(real.aheadOfBase, 2, "two commits ahead (P0 + the deliverable)")
    assertEquals(real.dirty, 2, "two uncommitted paths (P1 in progress + in-progress.txt)")
    // 系统能给出续用选项（清单 + 渲染面一个都不少）
    assertEquals(inv.orphans.map(_.delegateId.getOrElse("")), List("delegate-nebflow-4b756df9"))
    val text = WorktreeCandidates.render(inv, repo.toString)
    assert(text.contains("delegate-nebflow-4b756df9"), s"the orphan must lead the list:\n$text")
    assert(text.contains("**orphan**"), text)
    assert(text.contains("2 commit(s) ahead of main"), s"the commit count must be visible:\n$text")
    assert(text.contains("2 uncommitted"), s"the dirty count must be visible:\n$text")
    // 另一棵（干净新树）读作 done——模型不会误选它
    assertEquals(inv.byId("delegate-nebflow-c4e7352c").map(_.status), Some(WorktreeCandidates.Status.Done))
    assert(
      text.indexOf("delegate-nebflow-4b756df9") < text.indexOf("delegate-nebflow-c4e7352c"),
      s"the orphan must be ranked ABOVE the clean fresh tree:\n$text"
    )
    assertEquals(inv.orphans.head.path, realWork.toString)
    assertNotEquals(freshForThird.toString, realWork.toString)
  }

  test("J6b the reuse target resolves from all three shapes (delegate id / dirName / absolute path)") {
    assume(gitAvailable, "git required")
    val repo = mkRepo(tempRoot / "j6b-repo")
    val wt = mkDelegateWorktree(repo, "delegate-nebflow-abc12345", commit = true)
    WorktreeCandidates.LiveSessions.install(
      new WorktreeCandidates.LiveSessions.Face:
        def liveAmong(ids: List[String]): IO[Set[String]] = IO.pure(Set.empty)
    )
    val inv = WorktreeCandidates.inventory(repo.toString).unsafeRunSync()
    List("delegate-nebflow-abc12345", wt.last, wt.toString).foreach { target =>
      WorktreeCandidates.resolve(inv, target, force = false) match
        case WorktreeCandidates.Decision.Reuse(Some(c), p) =>
          assertEquals(c.dirName, wt.last, s"shape '$target' must resolve to the same candidate")
          assertEquals(p, wt.toString)
        case other => fail(s"shape '$target' failed to resolve: $other")
    }
  }

  /**
   * J6c **真仓只读**读数（不是构造）：本机主仓 `218` 棵既有 worktree（实测
   * `git worktree list --porcelain | grep -c '^worktree '`），历史上那棵
   * `delegate-nebflow-4b756df9`（P0/P1/P2 修复批真正的活所在）**仍然在盘上**。
   * 本用例在这份真实盘面上跑一遍 inventory（**纯读，零 git 写**），证明：
   *   - 生产规模下枚举可跑（两遍式：浅遍 1 次 git 调用；深遍只 ≤8 棵）；
   *   - 那棵树被读成 orphan（若它的 delegate 恰好还活着，则读成 active——两种
   *     标注都证明对账生效，断言按现场活会话面自适应）；
   *   - 它带着真实的继承工作量（≥5 个领先提交 = P0 两条 + P1 + 后续两条）。
   * 环境不满足（仓不在这台机 / 那棵树已被清理）⇒ 跳过（assume）。
   */
  test("J6c real-repo read-only inventory: the actual 4b756df9 tree is recognised as orphan (or active if its session still lives)") {
    assume(gitAvailable, "git required")
    val realRepo = os.Path("/Users/kaiyu/Claude code/Stand")
    val realTree = realRepo / ".nebflow" / "worktrees" / "delegate-nebflow-4b756df9"
    assume(os.exists(realTree), s"the real worktree $realTree is not on this machine (already cleaned) — skipped")
    // 真盘面用真 dataRoot（记录现读 + 真活会话端口未装 ⇒ 全部按不 live 判）
    PathUtil.setDataRoot(originalRoot)
    WorktreeCandidates.LiveSessions.clear()
    val inv = WorktreeCandidates.inventory(realRepo.toString).unsafeRunSync()
    assert(inv.all.size >= 200, s"the real workspace carries hundreds of worktrees, got ${inv.all.size}")
    val c = inv.byId("delegate-nebflow-4b756df9").getOrElse(fail("the real 4b756df9 tree must be enumerated"))
    assertEquals(c.path, realTree.toString)
    assertEquals(c.branch, Some("delegate/delegate-nebflow-4b756df9"))
    assert(c.aheadOfBase >= 5, s"the real tree carries the P0/P1 deliverable commits, got ahead=${c.aheadOfBase}")
    assertEquals(c.status, WorktreeCandidates.Status.Orphan, "with no live session face installed it reads orphan")
    // 渲染面：它必须出现在模型可见清单里（真盘 218 棵树，靠排序进榜）
    val text = WorktreeCandidates.render(inv, realRepo.toString)
    assert(
      text.contains("delegate-nebflow-4b756df9"),
      s"the real orphan must surface in the rendered list (top ${WorktreeCandidates.MaxCandidates} of ${inv.all.size}):\n${text.take(2000)}"
    )
    assert(text.contains("**orphan**"), text.take(500))
  }

end WorktreeReuseSelectionSpec
