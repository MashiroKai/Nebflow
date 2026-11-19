package nebflow.core

import cats.syntax.all.*

import scala.util.Try

/**
 * Read-only Git facts for the engine's seat faces: branch/HEAD readings, dirty
 * sets, commit ancestry and identity, bounded status digests. Every reading is
 * fail-soft (`Option`/`Either` shapes, never throws) — an unreadable seat is a
 * missing reading, not an error path.
 *
 * Worktree/branch CREATION is deliberately absent: delegates that need a
 * worktree create and manage their own with `git worktree` through their shell
 * face; the engine never provisions, reserves or cleans seats. Existing
 * worktrees and branches on disk are plain data this object can observe
 * (`worktreeList`, `branchTips`) but never mutate.
 */
object GitWorktrees:

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
   * HEAD commit's committer time (epoch **seconds**), None when unknown — a
   * "last activity" helper reading for seat watchers. fail-soft 同 [[headSha]].
   */
  def headCommitEpoch(ws: os.Path): Option[Long] =
    Try(git(ws, "log", "-1", "--format=%ct")).toOption
      .flatMap(_.toOption)
      .map(_.trim)
      .filter(_.nonEmpty)
      .flatMap(s => Try(s.toLong).toOption)

  /**
   * All worktrees of the repo in ONE git call（`git worktree list --porcelain`）：
   * 候选枚举的**批量读数**（每条 = 路径 + 检出分支 + HEAD sha）。逐树各跑
   * `rev-parse` 在生产工作区（数百棵树）是数百次进程叉，本方法把这一层压到一次。
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
   * Uncommitted / untracked paths（`git status --porcelain` 原始行，含冲突行）。
   * fail-soft：非 repo / git 失败 = None（与「读到了但为空」可分——None ≠ Some(Nil)）。
   */
  def dirtyPaths(ws: os.Path): Option[List[String]] =
    Try(git(ws, "status", "--porcelain")).toOption
      .flatMap(_.toOption)
      .map(_.linesIterator.toList.filter(_.trim.nonEmpty))

  /**
   * A branch's current tip sha (short form). fail-soft 同 [[headSha]]
   * （ref 不存在/非 repo = None）。
   */
  def headShaOfBranch(ws: os.Path, branch: String): Option[String] =
    Try(git(ws, "rev-parse", "--short", branch)).toOption.flatMap(_.toOption).map(_.trim).filter(_.nonEmpty)

  /**
   * Count of branch commits not reachable from `baseBranch`（`git log --oneline
   * <base>..<branch>` 行数）——「座位上还有未落地提交」的确定性读数。
   * Fail-soft：git 面任何一步不可读（非 repo / ref 不存在 / git 失败）计 0，绝不抛。
   */
  def commitsAhead(ws: os.Path, baseBranch: String, branch: String): Int =
    Try(git(ws, "log", "--oneline", s"$baseBranch..$branch")).toOption
      .flatMap(_.toOption)
      .map(out => out.linesIterator.count(_.trim.nonEmpty))
      .getOrElse(0)

  /**
   * One commit's identity for read-only range enumeration（sha + 全部父 sha +
   * 标题——父数 ≥2 = merge 提交，父[1] = 被合并分支的 tip）。
   */
  final case class CommitInfo(sha: String, parents: List[String], subject: String)

  /**
   * Full commit sha of a ref (`rev-parse --verify --quiet`)，ref 缺失/不可读 = None
   * （fail-soft——「不可确证」通道，与「读到了但值如此」可分）。
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
   * rc 0/1 语义可分）；rc ≥2 = 不可确证 = None。
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
   * 任一行解析失败 = 整体 None（全有或全无——部分真值不进判据面）。
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
   * One commit's identity by ref (`git log -1 <ref>`) — the merge-acceptance
   * parent-structure reading (P1-1①: a merge commit's first parent is the
   * target side). ref 不可读 / 行解析失败 = None（fail-soft）。
   */
  def commitInfo(ws: os.Path, ref: String): Option[CommitInfo] =
    Try(git(ws, "log", "--format=%H%x01%P%x01%s", "-1", ref)).toOption
      .flatMap(_.toOption)
      .flatMap(_.linesIterator.filter(_.trim.nonEmpty).toList.headOption)
      .flatMap(parseCommitInfo)

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

  private def git(ws: os.Path, args: String*): Either[String, String] =
    val res = os.proc(Seq("git", "-C", ws.toString) ++ args).call(cwd = ws, check = false, mergeErrIntoOut = true)
    if res.exitCode != 0 then Left(res.out.trim().take(300)) else Right(res.out.trim())

end GitWorktrees
