package nebflow.core.delegate

import cats.effect.unsafe.implicits.global
import munit.FunSuite
import nebflow.shared.PathUtil

import scala.util.Try

/**
 * 就地写盘护栏 spec（delegate-param-decisions 批 2026-11-04 · **作者补充令第 2/3 条**）：
 *
 * 「不填 worktree 的任务是适合于只读型任务 工作还是要默认创建worktree」——所以
 * 「没分配树却动手」必须有东西叫出来，不靠编排方自觉。这里把那声「叫」做成
 * 可反向验红的机械用例：
 *
 *  - **A（标红）**：零分配就地腿（`prepareInPlace` 记快照）→ 该任务写了文件 ⇒
 *    [[DelegateTerminal.inPlaceWriteAlarm]] 返回标红行，**含改动路径清单**。
 *  - **B（不误报）**：同一快照、只读（不写）⇒ `None`（只读任务不许被冤枉）。
 *  - **C（有树不检）**：有 worktree 绑定的记录 ⇒ `None`（在自家树里写是本分）。
 *  - **D（回执位形）**：标红行缀进 `Outcome.Release` 的**正文首行**（不许埋在尾部）。
 *  - **E（提交也算）**：不写文件但产生了提交（HEAD 移动）⇒ 同样标红。
 */
class InPlaceWriteGuardSpec extends FunSuite:

  private val originalRoot = PathUtil.dataRoot
  private var tmp: os.Path = os.root / "nonexistent"

  /**
   * 仓目录放项目 target 下（**不用 os.temp.dir**：macOS 的 /var → /private/var 符号
   * 链接会让 `git worktree list` 的路径与本地拼串不等，四查误判「not registered」）。
   */
  private val tempRoot: os.Path = os.pwd / "target" / "test-inplace-guard"

  private def gitAvailable: Boolean =
    Try(os.proc("git", "--version").call(check = false, stdout = os.Pipe, stderr = os.Pipe).exitCode == 0)
      .getOrElse(false)

  private def mkRepo(dir: os.Path): Unit =
    nebflow.ForceRemove.removeAll(dir)
    os.makeDir.all(dir)
    os.proc("git", "init", "-b", "main", dir.toString).call(check = true, stdout = os.Pipe, stderr = os.Pipe)
    os.proc("git", "-C", dir.toString, "config", "user.email", "spec@nebflow.local").call(check = true)
    os.proc("git", "-C", dir.toString, "config", "user.name", "spec").call(check = true)
    os.proc("git", "-C", dir.toString, "commit", "--allow-empty", "-m", "init")
      .call(check = true, stdout = os.Pipe, stderr = os.Pipe)

  override def beforeEach(context: munit.BeforeEach): Unit =
    tmp = os.temp.dir(prefix = "inplace-guard")
    PathUtil.setDataRoot(tmp / "data")

  override def afterEach(context: munit.AfterEach): Unit =
    PathUtil.setDataRoot(originalRoot)
    nebflow.ForceRemove.removeAll(tmp)

  /** 落一条记录（spawn 侧就地腿的真实落盘形状：无 worktree + 快照）。 */
  private def putInPlaceRecord(id: String, repo: os.Path, binding: DelegateWorkspace.Binding): Unit =
    DelegateRegistry.put(
      DelegateRegistry.Record(
        id = id,
        executor = "nebflow",
        cwd = binding.cwd,
        project = None,
        taskId = None,
        parentSessionId = "parent-guard",
        title = s"in-place $id",
        createdAt = System.currentTimeMillis(),
        branch = Some(binding.branch),
        worktree = binding.worktree,
        baseBranch = binding.baseBranch,
        projectWorkspace = Some(repo.toString),
        mode = Some(DualModeConfig.ModeKernel),
        seatSnapshotSha = binding.snapshotSha,
        seatSnapshotDirty = binding.snapshotDirty
      )
    )

  test("A 标红：没分配 worktree 却写文件 ⇒ 回执行点名改动路径") {
    if !gitAvailable then ()
    else
      val repo = tempRoot / "repo-a"
      mkRepo(repo)
      val id = "delegate-nebflow-guardA"
      val binding = DelegateWorkspace.prepareInPlace(repo.toString, id).unsafeRunSync().getOrElse(fail("binding"))
      putInPlaceRecord(id, repo, binding)
      // 只读任务不该有的动作：在共享工作区里写文件。
      os.write.over(repo / "sneaky.txt", "written by a worktree-less task\n")
      val alarm = DelegateTerminal.inPlaceWriteAlarm(DelegateRegistry.find(id)).unsafeRunSync()
      val text = alarm.getOrElse(fail("a write by a worktree-less task MUST be flagged"))
      // 原始输出（作者令：回执里贴「写入」用例的原始输出）——贴的就是这三行。
      println("── [用例A · 写入] inPlaceWriteAlarm ⇒ Some(")
      println(text)
      println("── [用例A · 写入] )")
      assert(text.startsWith(DelegateTerminal.InPlaceWriteAlarmPrefix), text)
      assert(text.contains("sneaky.txt"), s"the changed path list must be named: $text")
      assert(text.contains("本任务未分配 worktree 但改动了项目工作区"), text)
  }

  test("B 不误报：同一快照、确实只读 ⇒ None") {
    if !gitAvailable then ()
    else
      val repo = tempRoot / "repo-b"
      mkRepo(repo)
      val id = "delegate-nebflow-guardB"
      val binding = DelegateWorkspace.prepareInPlace(repo.toString, id).unsafeRunSync().getOrElse(fail("binding"))
      putInPlaceRecord(id, repo, binding)
      // 只读动作：读文件、跑 git status（不写盘）。
      os.proc("git", "-C", repo.toString, "status", "--porcelain").call(check = true, stdout = os.Pipe)
      val readOnly = DelegateTerminal.inPlaceWriteAlarm(DelegateRegistry.find(id)).unsafeRunSync()
      println(s"── [用例B · 只读] inPlaceWriteAlarm ⇒ $readOnly")
      assertEquals(readOnly, None, "a read-only task must never be flagged")
  }

  test("C 有树不检：绑定了 worktree 的记录（在自家树里写是本分）⇒ None") {
    if !gitAvailable then ()
    else
      val repo = tempRoot / "repo-c"
      mkRepo(repo)
      val id = "delegate-nebflow-guardC"
      val created0 = DelegateWorkspace.createNamed(repo.toString, "guarded-tree", id).unsafeRunSync()
      val hasTree = created0.getOrElse(fail(s"create: $created0"))
      DelegateRegistry.put(
        DelegateRegistry.Record(
          id = id,
          executor = "nebflow",
          cwd = hasTree.cwd,
          project = None,
          taskId = None,
          parentSessionId = "parent-guard",
          title = "with tree",
          createdAt = System.currentTimeMillis(),
          branch = Some(hasTree.branch),
          worktree = hasTree.worktree,
          baseBranch = hasTree.baseBranch,
          projectWorkspace = Some(repo.toString),
          seatSnapshotSha = hasTree.snapshotSha,
          seatSnapshotDirty = hasTree.snapshotDirty
        )
      )
      os.write.over(os.Path(hasTree.cwd) / "inside-the-tree.txt", "normal work\n")
      os.write.over(repo / "workspace-drift.txt", "also fine: the seat is the tree\n")
      assertEquals(DelegateTerminal.inPlaceWriteAlarm(DelegateRegistry.find(id)).unsafeRunSync(), None)
  }

  test("D 回执位形：标红行是交付正文的第一行（不许埋尾部）") {
    val alarm = "🔴 [IN-PLACE WRITE ALARM] 本任务未分配 worktree 但改动了项目工作区，路径清单如下"
    val released =
      DelegateTerminal.withInPlaceAlarm(DelegateTerminal.Outcome.Release("verdict line\nwork appendix"), Some(alarm))
    released match
      case DelegateTerminal.Outcome.Release(text) =>
        assert(text.startsWith(alarm), s"the alarm must lead the receipt body, got: $text")
        assert(text.contains("verdict line"), "the original body is preserved below it")
      case other => fail(s"only Release receipts are rewrapped, got $other")
    // 无命中 = 正文逐字节不变
    val plain = DelegateTerminal.Outcome.Release("verdict line")
    assertEquals(DelegateTerminal.withInPlaceAlarm(plain, None), plain)
  }

  test("E 提交也算：没写文件但产生了提交（HEAD 移动）⇒ 标红") {
    if !gitAvailable then ()
    else
      val repo = tempRoot / "repo-e"
      mkRepo(repo)
      val id = "delegate-nebflow-guardE"
      val binding = DelegateWorkspace.prepareInPlace(repo.toString, id).unsafeRunSync().getOrElse(fail("binding"))
      putInPlaceRecord(id, repo, binding)
      os.proc("git", "-C", repo.toString, "commit", "--allow-empty", "-m", "unauthorized commit")
        .call(check = true, stdout = os.Pipe, stderr = os.Pipe)
      val alarm = DelegateTerminal.inPlaceWriteAlarm(DelegateRegistry.find(id)).unsafeRunSync()
      val text = alarm.getOrElse(fail("a commit by a worktree-less task MUST be flagged"))
      println(s"── [用例E · 只提交] inPlaceWriteAlarm ⇒ ${alarm.map(_.replace("\n", " ⏎ "))}")
      assert(text.contains("HEAD moved") || text.contains("commits were created"), text)
  }

end InPlaceWriteGuardSpec
