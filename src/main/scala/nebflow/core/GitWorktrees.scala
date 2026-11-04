package nebflow.core

import cats.syntax.all.*
import nebflow.shared.NebflowLogger

import scala.util.Try

/**
 * Git worktree / branch creation machinery — the SHARED single point extracted
 * from `NodeEditTool.createWorktreeFor` (worktree-judge-fifo 批 #1224 增补
 * §3.1「抽 `NodeEditTool.createWorktreeFor`（NodeEditTool.scala:931，private）
 * 为公用 util」）.
 *
 * Two consumers, one implementation:
 *   - `NodeEditTool`（旧引擎 flow 节点的 worktree 面，行为逐字保持——同名分支、
 *     同名目录、同一错误文案）；
 *   - the delegate spawn path（`DelegateTool` / S13 分支纪律）：分支名
 *     `delegate/<id>`、目录名取 id 的 sanitize 形态——本批新增的第二条腿。
 *
 * Rules carried over verbatim from the extracted body (engine-wtbase batch
 * 2026-10-04):
 *   - the DEFAULT base is the repo HEAD at creation time; only an explicit
 *     `baseRef` overrides it — there is NO `main` fallback (a parallel batch
 *     must start on the integration line tip);
 *   - the raw ref is handed to `git worktree add` unresolved (single
 *     dereference point at creation time);
 *   - directory-name candidates `<base>`, `<base>-2` … `<base>-99` — first
 *     free wins; the branch is created with `-b` at the same moment, so for
 *     the NodeEditTool leg branch name == directory name (分支同名, unchanged).
 *
 * The delegate leg deliberately differs in ONE axis: branch name
 * (`delegate/<id>`) is decoupled from directory name (the sanitized id) —
 * `git worktree add -b <branch> <dir> <baseRef>` supports that directly.
 *
 * All operations are best-effort `Either[String, *]`: a git failure returns
 * `Left(diagnostic)` (truncated to 300 chars, the extracted body's contract)
 * and never throws.
 */
object GitWorktrees:

  private val logger = NebflowLogger.forName("nebflow.core.git")

  /** A successfully created worktree: its derived directory name and path. */
  final case class Created(name: String, path: os.Path)

  /**
   * Directory/branch-name sanitize — moved verbatim from
   * `NodeEditTool.createWorktreeFor`（空白与 git-ref 非法字符 → '-'、连续 '-'
   * 折叠、首尾 '-.' 剥除、截 40、空则 "node"；CJK/Unicode 字母数字保留）.
   */
  def sanitizeRefName(raw: String): String =
    raw.trim
      .replaceAll("[\\s~^:?*\\[\\\\]+", "-")
      .replaceAll("-{2,}", "-")
      .stripPrefix("-")
      .stripSuffix("-")
      .stripPrefix(".")
      .take(40)
      .stripSuffix("-")
      .stripSuffix(".lock")
      .stripSuffix(".")

  private def git(ws: os.Path, args: String*): Either[String, String] =
    val res = os.proc(Seq("git", "-C", ws.toString) ++ args).call(cwd = ws, check = false, mergeErrIntoOut = true)
    if res.exitCode != 0 then Left(res.out.trim().take(300)) else Right(res.out.trim())

  /** True when the directory is inside a git work tree (`git rev-parse` succeeds). */
  def isGitRepo(ws: os.Path): Boolean =
    Try(git(ws, "rev-parse", "--is-inside-work-tree")).toOption.exists(_.exists(_.trim == "true"))

  /** Current checked-out branch (`--show-branch`), None when detached/unknown. */
  def currentBranch(ws: os.Path): Option[String] =
    Try(git(ws, "rev-parse", "--abbrev-ref", "HEAD")).toOption
      .flatMap(_.toOption)
      .map(_.trim)
      .filter(b => b.nonEmpty && b != "HEAD")

  /** Current HEAD sha (short form), None when unknown. */
  def headSha(ws: os.Path): Option[String] =
    Try(git(ws, "rev-parse", "--short", "HEAD")).toOption.flatMap(_.toOption).map(_.trim).filter(_.nonEmpty)

  /**
   * HEAD commit's committer time (epoch **seconds**), None when unknown —
   * worktree-reuse-selection 批（2026-11-02）候选「最后活动时间」的辅助读数。
   * fail-soft 同 [[headSha]]。
   */
  def headCommitEpoch(ws: os.Path): Option[Long] =
    Try(git(ws, "log", "-1", "--format=%ct")).toOption
      .flatMap(_.toOption)
      .map(_.trim)
      .filter(_.nonEmpty)
      .flatMap(s => Try(s.toLong).toOption)

  /**
   * All worktrees of the repo in ONE git call（`git worktree list --porcelain`）——
   * worktree-reuse-selection 批（2026-11-02）：候选枚举的**批量读数**（每条 = 路径 +
   * 检出分支 + HEAD sha）。逐树各跑 `rev-parse` 在生产工作区（数百棵树）是数百次
   * 进程叉，本方法把这一层压到一次。
   *
   * 返回顺序 = git 的输出顺序；解析失败/非 repo = Nil（fail-soft）。
   */
  def worktreeList(ws: os.Path): List[(String, Option[String], Option[String])] =
    Try(git(ws, "worktree", "list", "--porcelain")).toOption.flatMap(_.toOption) match
      case None => Nil
      case Some(out) =>
        // 记录块以空行分隔；键 = worktree / HEAD / branch / detached / bare / locked …
        val blocks = out.split("\n\n").toList.map(_.trim).filter(_.nonEmpty)
        blocks.flatMap { b =>
          val fields = b.linesIterator.toList
          def field(k: String): Option[String] =
            fields.collectFirst { case l if l.startsWith(k + " ") => l.stripPrefix(k + " ").trim }.filter(_.nonEmpty)
          field("worktree").map { path =>
            val branch = field("branch").map(_.stripPrefix("refs/heads/"))
            (path, branch, field("HEAD").map(_.take(9)))
          }
        }

  /**
   * Uncommitted / untracked paths（`git status --porcelain` 原始行，含冲突行）——
   * worktree-reuse-selection 批（2026-11-02）：候选 worktree 的「脏度」读数（接续
   * 前必须让模型知道树上有前任的进行中工作）。fail-soft：非 repo / git 失败 = None
   * （与 [[statusDigest]] 的「读不到不算脏」同族，但保真——None ≠ Some(Nil)）。
   */
  def dirtyPaths(ws: os.Path): Option[List[String]] =
    Try(git(ws, "status", "--porcelain")).toOption
      .flatMap(_.toOption)
      .map(_.linesIterator.toList.filter(_.trim.nonEmpty))

  /**
   * 合并道读数（投递可靠性批 2026-10-29 修复 A/B/②）：`.git/MERGE_HEAD` 在场 =
   * 该工作区有一笔**进行中的 merge**（未 commit 未 abort）。返回其 commit 全 sha。
   * 文件缺席 = None（道净）；读失败 = None（fail-open——读数缺失不判占道）。
   */
  def mergeHead(ws: os.Path): Option[String] =
    Try(os.read(ws / ".git" / "MERGE_HEAD").trim).toOption.filter(_.nonEmpty)

  /**
   * 合并道回滚（孤儿合并收场，作者令 2026-10-29 23:55 范围补充 A：倾向=
   * `git merge --abort`）。**只允许对已判死属主残留的合并态调用**——活属主/人的
   * 手工合并绝不可 abort（调用方闸门负责）。fail-soft：失败返回 false 并 WARN。
   */
  def abortMerge(ws: os.Path): Boolean =
    try
      git(ws, "merge", "--abort") match
        case Right(_) => true
        case Left(err) =>
          logger.warn(s"git merge --abort failed in $ws: $err")
          false
    catch
      case e: Throwable =>
        logger.warn(s"git merge --abort errored in $ws: ${Option(e.getMessage).getOrElse(e.toString)}")
        false

  /**
   * First free worktree directory name under `<ws>/.nebflow/worktrees/` —
   * the candidates loop moved verbatim (`<base>`, `<base>-2` … `<base>-99`).
   */
  private def freeName(ws: os.Path, base: String): Option[String] =
    val wtRoot = ws / ".nebflow" / "worktrees"
    val candidates = (1 to 99).map(i => if i == 1 then base else s"$base-$i")
    candidates.find { n => !os.exists(wtRoot / n) && !os.exists(ws / ".nebflow" / n) }

  /**
   * Create a worktree with an explicitly-named branch.
   *
   * @param dirBase the pre-sanitized directory-name seed (candidates are derived)
   * @param branch  the branch to create (`-b`); NodeEditTool passes the same
   *                value as `dirBase` (分支同名 legacy), the delegate leg passes
   *                `delegate/<id>`
   * @param label   human label used in the exhausted-candidates error text
   *                (legacy text says "for node '<nodename>'" — NodeEditTool
   *                passes the nodename, byte-identical)
   * @param branchFollowsDir true (legacy verbatim) = the created branch is the
   *                CANDIDATE directory name (original body: `git worktree add
   *                -b <name> <path> <baseRef>` — on a directory collision the
   *                branch travels to `<base>-2` with the dir); false (delegate
   *                leg) = the branch stays exactly `branch` regardless of the
   *                directory candidate (branch/dir decoupling is the one axis
   *                the delegate leg deliberately differs on).
   */
  def createWorktree(
    ws: os.Path,
    dirBase: String,
    branch: String,
    baseRef: String = "HEAD",
    label: String = "",
    branchFollowsDir: Boolean = true
  ): Either[String, Created] =
    // 空 sanitize 的回落与抽取前逐字同源（原 `if sanitized.isEmpty then "node"`）。
    val base = if dirBase.isEmpty then "node" else dirBase
    freeName(ws, base) match
      case None =>
        val shown = if label.isEmpty then base else label
        Left(
          s"Cannot derive a free worktree name for node '$shown' (tried '$base', '$base-2'…'$base-99') — pass a shorter/unique nodename"
        )
      case Some(name) =>
        val path = ws / ".nebflow" / "worktrees" / name
        val usedBranch = if branchFollowsDir then name else branch
        os.makeDir.all(ws / ".nebflow" / "worktrees")
        git(ws, "worktree", "add", "-b", usedBranch, path.toString, baseRef)
          .flatMap { _ =>
            // 分配后验收（引擎会话治理专项 · 缺陷二，2026-11-03 作者令）：`git
            // worktree add` 返回 0 不等于座位真的可用（半写残场/目录被外部清空
            // 后透传过）。四查全过才把座位交出去；不过 = 显式报错（可重试），
            // 绝不把坏座位交给任务。验收失败的现场不自动清（留给人/调用方判）。
            val problems = validateAllocation(ws, path.toString)
            if problems.isEmpty then Right(Created(name, path))
            else
              val msg = s"worktree allocation post-validation failed for '$path': ${problems.mkString("; ")}"
              logger.warn(msg)
              Left(msg)
          }
          .leftMap { err =>
            logger.warn(s"git worktree add failed for branch '$usedBranch' at '$path': $err")
            err
          }

    end match

  end createWorktree

  /**
   * Create a worktree at an **exactly named** directory（delegate-param-decisions 批
   * 2026-11-04 · 作者令「填的内容就是 worktree 的 name」）。
   *
   * 与 [[createWorktree]] 的唯一差异 = **不做目录候选派生**：路径恒为
   * `<ws>/.nebflow/worktrees/<name>`（不试 `<name>-2`…）。名字被占就是错——
   * 调用方本来就要先按 name 查「已存在 ⇒ 复用」，走到这里还撞名 = 现场不干净，
   * 必须显式报出来而不是悄悄换个名字建第二棵树。
   *
   * 建后照走 [[validateAllocation]] 四查（同 [[createWorktree]]：`git worktree add`
   * 返回 0 不等于座位可用）。
   */
  def createNamedWorktree(
    ws: os.Path,
    name: String,
    branch: String,
    baseRef: String = "HEAD"
  ): Either[String, Created] =
    val root = ws / ".nebflow" / "worktrees"
    val path = root / name
    if os.exists(path) then
      Left(s"worktree path already exists: $path (a name hit must reuse that tree, never create a second one)")
    else
      os.makeDir.all(root)
      git(ws, "worktree", "add", "-b", branch, path.toString, baseRef)
        .flatMap { _ =>
          val problems = validateAllocation(ws, path.toString)
          if problems.isEmpty then Right(Created(name, path))
          else
            val msg = s"worktree allocation post-validation failed for '$path': ${problems.mkString("; ")}"
            logger.warn(msg)
            Left(msg)
        }
        .leftMap { err =>
          logger.warn(s"git worktree add failed for branch '$branch' at '$path': $err")
          err
        }

    end if

  end createNamedWorktree

  /**
   * Worktree 座位可用性四查（引擎会话治理专项 · 缺陷二；分配腿与续用腿共用）。
   * 返回问题清单（空 = 可用座位）。判据全部是**机械读数**：
   *   ① `git worktree list`（以 ws 为仓）里能看到该目录 —— 未登记的目录不是座位；
   * ② `rev-parse --show-toplevel` 恰好等于该目录 —— 防空目录沿父级仓解析被骗
   *    （`.nebflow/worktrees/<name>` 在主工作区内时 isGitRepo 会误报 true）；
   *   ③ HEAD 可解（checkout 完成）；
   *   ④ `status --porcelain` 可执行（git 语义面在座位内工作）。
   */
  def validateAllocation(ws: os.Path, path: String): List[String] =
    val p = os.Path(path)
    val problems = scala.collection.mutable.ListBuffer[String]()
    if !os.isDir(p) then problems += s"directory does not exist: $path"
    else
      val registered = worktreeList(ws).exists(_._1.stripSuffix("/") == path.stripSuffix("/"))
      if !registered then problems += "not registered in `git worktree list` (not a worktree of this repo)"
      Try(git(p, "rev-parse", "--show-toplevel")).toOption.flatMap(_.toOption).map(_.trim) match
        case None =>
          problems += "rev-parse --show-toplevel unreadable (no git checkout at this path)"
        case Some(top)
            if java.nio.file.Paths.get(top).toRealPath() != java.nio.file.Paths.get(p.toString).toRealPath() =>
          problems += s"toplevel '$top' is not this directory (not a checkout — parent repo resolution)"
        case _ => ()
      if headSha(p).isEmpty then problems += "HEAD does not resolve (checkout incomplete)"
      if dirtyPaths(p).isEmpty then problems += "git status not runnable in this directory"
    problems.toList

  end validateAllocation

  /**
   * Create a plain branch (no checkout, no worktree) — the S13 no-worktree
   * leg: `git branch <branch> <baseRef>` in the project itself; the shared
   * working tree's HEAD is untouched.
   */
  def createBranch(ws: os.Path, branch: String, baseRef: String = "HEAD"): Either[String, String] =
    git(ws, "branch", branch, baseRef).leftMap { err =>
      logger.warn(s"git branch '$branch' failed: $err")
      err
    }

  /** True when the branch ref exists locally. */
  def branchExists(ws: os.Path, branch: String): Boolean =
    Try(git(ws, "rev-parse", "--verify", "--quiet", branch)).toOption.exists(_.isRight)

  /**
   * A branch's current tip sha (short form) — base-drift 批（缺陷四）：在飞 delegate
   * 的基分支 tip 漂移读数。fail-soft 同 [[headSha]]（ref 不存在/非 repo = None）。
   */
  def headShaOfBranch(ws: os.Path, branch: String): Option[String] =
    Try(git(ws, "rev-parse", "--short", branch)).toOption.flatMap(_.toOption).map(_.trim).filter(_.nonEmpty)

  /**
   * Files touched between two revisions（`git diff --name-only from to`）——base-drift
   * 通知的「涉及文件列表」读数。任一 rev 不可解/非 repo = Nil（fail-soft）。
   */
  def changedFilesBetween(ws: os.Path, from: String, to: String): List[String] =
    Try(git(ws, "diff", "--name-only", from, to)).toOption
      .flatMap(_.toOption)
      .map(_.linesIterator.map(_.trim).filter(_.nonEmpty).toList)
      .getOrElse(Nil)

  /**
   * Count of branch commits not reachable from `baseBranch`（`git log --oneline
   * <base>..<branch>` 行数）—— ready-to-merge 资格的确定性读数（空分支不得进
   * 合并轮：`git merge --no-ff` 对祖先分支只会 Already up-to-date，空轮无产物）。
   * Fail-soft 形态同 [[statusDigest]]：git 面任何一步不可读（非 repo / ref 不
   * 存在 / git 失败）计 0，绝不抛。
   */
  def commitsAhead(ws: os.Path, baseBranch: String, branch: String): Int =
    Try(git(ws, "log", "--oneline", s"$baseBranch..$branch")).toOption
      .flatMap(_.toOption)
      .map(out => out.linesIterator.count(_.trim.nonEmpty))
      .getOrElse(0)

  /**
   * One commit's identity for read-only range enumeration（漂移闸归因读数：sha +
   * 全部父 sha + 标题——父数 ≥2 = merge 提交，父[1] = 被合并分支的 tip）。
   */
  final case class CommitInfo(sha: String, parents: List[String], subject: String)

  /**
   * Full commit sha of a ref (`rev-parse --verify --quiet`)，ref 缺失/不可读 = None
   * （fail-soft——漂移闸的「不可确证」通道，与 [[commitsAhead]] 的计 0 同族但保真：
   * 这里 None 必须与「读到了但值如此」可分）。
   */
  def resolveRef(ws: os.Path, ref: String): Option[String] =
    Try(git(ws, "rev-parse", "--verify", "--quiet", s"$ref^{commit}")).toOption
      .flatMap(_.toOption)
      .map(_.trim)
      .filter(_.nonEmpty)

  /** Merge-base of two refs (full sha)，任一 ref 不可读 = None（fail-soft 同上）。 */
  def mergeBase(ws: os.Path, a: String, b: String): Option[String] =
    Try(git(ws, "merge-base", a, b)).toOption
      .flatMap(_.toOption)
      .map(_.trim)
      .filter(_.nonEmpty)

  /**
   * True when `a` is an ancestor of (or equal to) `b`（`merge-base --is-ancestor`，
   * rc 0/1 语义可分）——漂移闸搭载提交随并归属的判据；rc ≥2 = 不可确证 = None。
   */
  def isAncestor(ws: os.Path, a: String, b: String): Option[Boolean] =
    Try {
      val res = os
        .proc("git", "-C", ws.toString, "merge-base", "--is-ancestor", a, b)
        .call(cwd = ws, check = false, mergeErrIntoOut = true)
      res.exitCode match
        case 0 => Some(true)
        case 1 => Some(false)
        case _ => None
    }.toOption.flatten

  /**
   * Commits reachable from `to` but not from `from`（`git log from..to`，新→旧）；
   * 任一行解析失败 = 整体 None（全有或全无——部分真值不进漂移闸证据）。
   */
  def rangeCommits(ws: os.Path, from: String, to: String): Option[List[CommitInfo]] =
    Try(git(ws, "log", "--format=%H%x01%P%x01%s", s"$from..$to")).toOption
      .flatMap(_.toOption)
      .map(_.linesIterator.filter(_.trim.nonEmpty).toList)
      .flatMap(lines => lines.traverse(parseCommitInfo))

  // unit-separated log line: sha ␁ parents(space-joined) ␁ subject
  private def parseCommitInfo(line: String): Option[CommitInfo] =
    line.split('\u0001') match
      case Array(sha, parents, subject) =>
        Some(CommitInfo(sha.trim, parents.trim.split(" ").toList.filter(_.nonEmpty), subject))
      case _ => None

  /** Local branch tips as `(shortName, fullSha)`，任一行解析失败 = None（fail-soft 同上）。 */
  def branchTips(ws: os.Path): Option[List[(String, String)]] =
    Try(git(ws, "for-each-ref", "refs/heads", "--format=%(objectname) %(refname:short)")).toOption
      .flatMap(_.toOption)
      .map(_.linesIterator.filter(_.trim.nonEmpty).toList)
      .flatMap(lines => lines.traverse(parseRefLine))

  private def parseRefLine(line: String): Option[(String, String)] =
    line.trim.split(" ", 2) match
      case Array(sha, name) if sha.nonEmpty && name.nonEmpty => Some((name, sha))
      case _ => None

  /**
   * One commit's identity by ref (`git log -1 <ref>`) — the mechanical merge
   * acceptance gate's parent-structure reading (P1-1①: base HEAD must be a
   * two-parent merge whose 2nd parent is the delegate branch tip). ref 不可读 /
   * 行解析失败 = None（fail-soft 同 [[resolveRef]] 家族）。
   */
  def commitInfo(ws: os.Path, ref: String): Option[CommitInfo] =
    Try(git(ws, "log", "--format=%H%x01%P%x01%s", "-1", ref)).toOption
      .flatMap(_.toOption)
      .flatMap(_.linesIterator.filter(_.trim.nonEmpty).toList.headOption)
      .flatMap(parseCommitInfo)

  /**
   * Remove a worktree (`git worktree remove <path>`) — NO force. Post-merge
   * cleanup (P2-4) only: a dirty/locked worktree is refused by git itself and
   * the caller keeps the scene (WARN only, never force).
   */
  def removeWorktree(ws: os.Path, path: String): Either[String, String] =
    git(ws, "worktree", "remove", path).leftMap { err =>
      logger.warn(s"git worktree remove '$path' failed (kept in place): $err")
      err
    }

  /**
   * Delete a branch (`git branch -d <branch>`) — lowercase -d ONLY (git refuses
   * unmerged branches; the zero-ahead guard). Never -D (P2-4 守卫：绝不 force).
   */
  def deleteBranch(ws: os.Path, branch: String): Either[String, String] =
    git(ws, "branch", "-d", branch).leftMap { err =>
      logger.warn(s"git branch -d '$branch' failed (kept): $err")
      err
    }

  /**
   * Per-project merge-contention key — the same semantics as the old engine's
   * merge mutex key: `realpath(git-common-dir)` (a worktree and its main repo
   * share ONE key), falling back to the canonical workspace path when the
   * directory is not inside a git repo. Owned here (not imported from the
   * `core.project` engine family) so the delegate family carries zero
   * dependency on surfaces scheduled for #1229 removal.
   */
  def projectKeyOf(ws: os.Path): String =
    val common = Try(git(ws, "rev-parse", "--git-common-dir")).toOption.flatMap(_.toOption).map(_.trim)
    common match
      case Some(d) if d.nonEmpty =>
        val p = os.Path(d, ws)
        try p.toNIO.toAbsolutePath.toString
        catch case _: Throwable => p.toString
      case _ =>
        try ws.toNIO.toAbsolutePath.toString
        catch case _: Throwable => ws.toString

  /**
   * The git status digest fed to the terminal judge（任务书：judge（任务书＋结果＋
   * git status 摘要））— bounded, read-only, never throws. Sections: current
   * branch, base-vs-branch commits (up to 5), uncommitted paths (up to 10),
   * unmerged (conflict) paths (up to 10).
   */
  def statusDigest(ws: os.Path, baseBranch: Option[String]): String =
    def section(title: String, lines: List[String]): Option[String] =
      if lines.isEmpty then None
      else Some(s"$title:\n${lines.map(l => s"  $l").mkString("\n")}")

    val branchLine = currentBranch(ws) match
      case Some(b) => s"branch: $b"
      case None => "branch: (detached or unknown)"
    val base = baseBranch.filter(_.nonEmpty)
    val commitLines =
      base match
        case None => Nil
        case Some(b) =>
          Try(git(ws, "log", "--oneline", "--max-count=5", s"$b..HEAD")).toOption
            .flatMap(_.toOption)
            .map(_.linesIterator.toList.filter(_.nonEmpty))
            .getOrElse(Nil)
    val dirtyLines =
      Try(git(ws, "status", "--porcelain")).toOption
        .flatMap(_.toOption)
        .map(_.linesIterator.toList.filter(_.nonEmpty))
        .getOrElse(Nil)
    val unmergedLines = dirtyLines.filter(l => l.startsWith("UU") || l.startsWith("AA") || l.startsWith("DD"))
    val plainDirty = dirtyLines.filterNot(unmergedLines.contains)

    val sections =
      List(
        Some(branchLine) ++ base.map(b => s"base: $b"),
        section("commits ahead of base (max 5)", commitLines),
        section("uncommitted changes (max 10)", plainDirty.take(10)).map(s =>
          s + (if plainDirty.size > 10 then s"\n  … (+${plainDirty.size - 10} more)" else "")
        ),
        section("unmerged (conflict) paths", unmergedLines.take(10))
      ).flatten
    sections.mkString("\n")
  end statusDigest

end GitWorktrees
