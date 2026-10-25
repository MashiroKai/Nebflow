package nebflow.core

import io.circe.syntax.*
import munit.FunSuite
import nebflow.shared.PathUtil

import scala.util.Try

/**
 * GitWorktrees 公用 util 判读（worktree-judge-fifo 批 #1224 增补 §3.1「抽
 * NodeEditTool.createWorktreeFor 为公用 util」）：
 *
 *  - 抽取等价：NodeEditTool 腿的派生规则（sanitize / 候选 -2..-99 / 分支同名 /
 *    HEAD 基点）在 util 上逐字可复现；
 *  - delegate 腿的差异轴：分支名（delegate/<id>）与目录名解耦；
 *  - S13 判否腿：createBranch 建分支不动共享 HEAD（currentBranch 不变）；
 *  - projectKeyOf：worktree 与主仓同键（合并竞争键语义，旧引擎 keyOf 同判据）；
 *  - statusDigest：judge 输入面（branch/base/脏文件/领先提交四节）。
 */
class GitWorktreesSpec extends FunSuite:

  private val tempRoot: os.Path = os.pwd / "target" / "test-git-worktrees"

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

  test("sanitizeRefName: git-ref illegal chars fold to '-', CJK/Unicode preserved, edges trimmed, 40-char cap") {
    assertEquals(GitWorktrees.sanitizeRefName("  a b:c~d  "), "a-b-c-d")
    // 折叠类 = 原 createWorktreeFor 的 `[\s~^:?*\[\\]+`（ASCII 冒号/波浪线在类内，
    // **全角 `：` 与 `/` 不在**——逐字行为；`/` 在 ref 与目录名中均合法）。
    assertEquals(GitWorktrees.sanitizeRefName("节点 A：主干/同步"), "节点-A：主干/同步")
    assertEquals(GitWorktrees.sanitizeRefName("--x--"), "x")
    assertEquals(GitWorktrees.sanitizeRefName("x" * 50).length, 40)
    assertEquals(GitWorktrees.sanitizeRefName(""), "")
  }

  test("createWorktree: delegate leg decouples branch (delegate/<id>) from dir name; HEAD is the default base") {
    if !gitAvailable then () // 环境无 git = 跳过（CI 侧 git 前置同既有 MergeMutexGateSpec 先例）
    else
      val repo = tempRoot / "wt-repo"
      mkRepo(repo)
      val baseSha = GitWorktrees.headSha(repo)
      val created = GitWorktrees.createWorktree(
        repo,
        dirBase = GitWorktrees.sanitizeRefName("delegate-nebflow-abc123"),
        branch = "delegate/delegate-nebflow-abc123",
        baseRef = "HEAD",
        label = "delegate delegate-nebflow-abc123",
        // delegate 腿的调用形态（= DelegateWorkspace.prepare）：分支固定不随目录候选
        branchFollowsDir = false
      )
      assert(created.isRight, s"worktree creation must succeed: $created")
      val c = created.toOption.get
      assertEquals(c.path.toString, (repo / ".nebflow" / "worktrees" / "delegate-nebflow-abc123").toString)
      assertEquals(GitWorktrees.currentBranch(c.path), Some("delegate/delegate-nebflow-abc123"))
      assertEquals(
        GitWorktrees.headSha(c.path),
        baseSha,
        "the worktree starts at the creating repo's HEAD (no main fallback)"
      )
      // 主仓 HEAD 不动（worktree 是旁支检出）
      assertEquals(GitWorktrees.currentBranch(repo), Some("main"))
  }

  test(
    "createWorktree: NodeEditTool legacy parity — branch travels WITH the dir candidate (`-b <name>` verbatim), empty base falls back to 'node'"
  ) {
    if !gitAvailable then ()
    else
      val repo = tempRoot / "legacy-repo"
      mkRepo(repo)
      val n1 = GitWorktrees.createWorktree(repo, dirBase = "节点任务", branch = "节点任务")
      assert(n1.isRight)
      assertEquals(n1.toOption.get.name, "节点任务")
      assertEquals(GitWorktrees.currentBranch(n1.toOption.get.path), Some("节点任务"))
      // 同名冲突 → -2 候选（原候选环语义；分支随候选挪位——原 `-b name` 逐字）
      val n2 = GitWorktrees.createWorktree(repo, dirBase = "节点任务", branch = "节点任务")
      assert(n2.isRight, s"candidate -2 must succeed: ${n2.left.toOption.get.take(200)}")
      assertEquals(n2.toOption.get.name, "节点任务-2")
      assertEquals(
        GitWorktrees.currentBranch(n2.toOption.get.path),
        Some("节点任务-2"),
        "legacy leg: branch follows the candidate dir name"
      )
      // 空 base → "node" 回落（抽取前原行为）
      val n3 = GitWorktrees.createWorktree(repo, dirBase = "", branch = "")
      assert(n3.isRight)
      assertEquals(n3.toOption.get.name, "node")
  }

  test("createBranch (S13 no-worktree leg): the branch ref exists while the shared HEAD stays untouched") {
    if !gitAvailable then ()
    else
      val repo = tempRoot / "branch-repo"
      mkRepo(repo)
      val r = GitWorktrees.createBranch(repo, "delegate/delegate-nebflow-xyz", "HEAD")
      assert(r.isRight, s"branch creation must succeed: $r")
      assertEquals(
        GitWorktrees.currentBranch(repo),
        Some("main"),
        "shared working tree HEAD must NOT move (option (b) checkout is out)"
      )
      assert(GitWorktrees.branchExists(repo, "delegate/delegate-nebflow-xyz"))
      // 基点 = HEAD（与 main 同 commit）
      val baseSha = GitWorktrees.headSha(repo)
      val branchSha =
        os.proc("git", "-C", repo.toString, "rev-parse", "--short", "delegate/delegate-nebflow-xyz")
          .call(check = true, stdout = os.Pipe)
          .out
          .trim()
      assertEquals(branchSha, baseSha.getOrElse(fail("base sha must resolve")))
  }

  test("projectKeyOf: a worktree and its main repo share ONE key; a non-git dir keys to itself") {
    if !gitAvailable then ()
    else
      val repo = tempRoot / "key-repo"
      mkRepo(repo)
      val _ = GitWorktrees.createWorktree(repo, dirBase = "kwt", branch = "kwt-branch")
      val kMain = GitWorktrees.projectKeyOf(repo)
      val kWt = GitWorktrees.projectKeyOf(repo / ".nebflow" / "worktrees" / "kwt")
      assertEquals(kWt, kMain, "worktree and main repo share the merge-contention key (git-common-dir)")
      // 非 git 目录自键（须在任何仓库之外——tempRoot 处于本 worktree 仓库内会被
      // git 向上行走捕获）
      val plain = os.temp.dir(prefix = "key-plain")
      val kPlain = GitWorktrees.projectKeyOf(plain)
      assert(kPlain.nonEmpty)
      assert(kPlain != kMain, "a non-git dir must not collide with a repo key")
  }

  test("statusDigest: judge input carries branch/base/dirty/ahead sections and survives non-git dirs") {
    if !gitAvailable then ()
    else
      val repo = tempRoot / "digest-repo"
      mkRepo(repo)
      os.write.over(repo / "tracked.txt", "dirty")
      val digest = GitWorktrees.statusDigest(repo, Some("main"))
      assert(digest.contains("branch: main"))
      assert(digest.contains("base: main"))
      assert(digest.contains("tracked.txt"), "uncommitted paths must be visible to the judge")
      assert(!digest.contains("commits ahead"), "HEAD == base ⇒ no ahead section")
      // 分支检出 + 提交 ⇒ ahead 节（delegate worktree 的真实形态：自己的分支、
      // 领先基分支——HEAD 是 symref，动 refs/heads/main 会连 HEAD 一起挪，故用
      // checkout -b 造「领先」场景）
      os.proc("git", "-C", repo.toString, "checkout", "-b", "wt").call(check = true, stdout = os.Pipe, stderr = os.Pipe)
      os.proc("git", "-C", repo.toString, "add", ".").call(check = true)
      os.proc("git", "-C", repo.toString, "commit", "-m", "wip").call(check = true, stdout = os.Pipe, stderr = os.Pipe)
      val digest2 = GitWorktrees.statusDigest(repo, Some("main"))
      assert(digest2.contains("branch: wt"))
      assert(digest2.contains("commits ahead"), "a commit ahead of base must surface (ready-to-merge signal input)")
      // 非 git 目录 = 空串化容错（不抛）。放 /tmp（tempRoot 在本 worktree 仓库内，
      // git 会向上行走找到父 repo——「非 git」场景必须在任何仓库之外）。
      val plain = os.temp.dir(prefix = "digest-plain")
      val d = GitWorktrees.statusDigest(plain, None)
      assert(d.contains("branch: (detached or unknown)"))
  }

end GitWorktreesSpec
