package nebflow.core.delegate

import nebflow.core.GitWorktrees

import scala.util.Try

/**
 * DelegateWorkReport —— delegate 完工卡的合并提交 diff 统计（完工卡 diff 统计
 * 恢复批 2026-11-01，作者令：完工卡恢复该批的代码量变化标识）.
 *
 * 本件只承载 **[[mergeDiffStat]]** 读数（+新增 −删除 行数统计）——delegate 终态
 * 交付本体走父回投链的 payload，不经本件。
 */
object DelegateWorkReport:

  /**
   * 合并提交第一亲 diff 的行数统计（`commit` = 7 位短哈希，与卡片摘要哈希段同源
   * 同值——作者看到的两个数字恒指同一提交）。
   */
  final case class FileDiffStat(path: String, added: Long, removed: Long)

  final case class MergeDiffStat(commit: String, added: Long, removed: Long, oid: String = "", files: List[FileDiffStat] = Nil)

  final case class ChildDiffStat(
    delegateId: String,
    parentSessionId: String,
    workspace: String,
    worktree: Option[String],
    merge: Option[MergeDiffStat],
    partial: Boolean
  )

  final case class AggregateDiffStat(
    added: Long,
    removed: Long,
    children: List[ChildDiffStat],
    partial: Boolean
  )

  /** Delegate and DAG-session descendants, rooted in durable ownership edges. */
  def aggregateDiffStat(root: DelegateRegistry.Record): AggregateDiffStat =
    val delegates = DelegateRegistry.list().map(r => r.id -> r).toMap
    val flowEdges = FlowNodeOwnership.list().map(e => e.sessionId -> e.parentSessionId).toMap
    val childrenByParent =
      delegates.valuesIterator.map(r => r.id -> r.parentSessionId).toList ++ flowEdges.toList
    val seen = scala.collection.mutable.Set(root.id)
    var frontier = List(root.id)
    while frontier.nonEmpty do
      val next = childrenByParent.collect { case (child, parent) if frontier.contains(parent) && !seen(child) => child }.distinct
      seen ++= next
      frontier = next
    val descendants = seen.iterator.filterNot(_ == root.id).toList.sortBy(id => (delegates.get(id).map(_.createdAt).getOrElse(Long.MaxValue), id))
    val children = descendants.map { id =>
      delegates.get(id) match
        case Some(rec) =>
          val workspace = rec.projectWorkspace.getOrElse(rec.cwd)
          val uncommitted = Try(GitWorktrees.dirtyPaths(os.Path(workspace))).toOption.flatten
          val merge = mergeDiffStat(rec)
          ChildDiffStat(rec.id, rec.parentSessionId, workspace, rec.worktree, merge,
            partial = rec.reportStatus.isEmpty || merge.isEmpty || merge.exists(_.files.isEmpty) || uncommitted.forall(_.nonEmpty))
        case None =>
          // DAG sessions have no persisted merge/report slot. Keep them visible and
          // explicitly partial rather than silently omitting their unavailable stats.
          ChildDiffStat(id, flowEdges.getOrElse(id, ""), root.projectWorkspace.getOrElse(root.cwd), None, None, partial = true)
    }
    aggregateChildren(children)

  /**
   * Roll up child details without changing the detail rows. First collapse repeated
   * merge OIDs (legacy records fall back to short commit), then count each file path
   * once across distinct commits. For multiwriter overlap, keep the largest single
   * per-file numstat (ties: lexicographically smallest full OID/commit); this avoids
   * summing repeated edits while making the chosen contribution deterministic.
   * Files stay attributed to their original child rows for auditability.
   */
  private[delegate] def aggregateChildren(children: List[ChildDiffStat]): AggregateDiffStat =
    val uniqueCommits = children.flatMap(_.merge)
      .groupBy(s => if s.oid.nonEmpty then s.oid else s.commit)
      .valuesIterator.map(_.head).toList
    val chosenPerFile = uniqueCommits.flatMap { stat =>
      stat.files.map(file => (file, if stat.oid.nonEmpty then stat.oid else stat.commit))
    }.groupBy(_._1.path).valuesIterator.map(_.toList.sortBy { case (file, key) => (-(file.added + file.removed), key) }.head).toList
    // Compatibility fallback for callers/legacy values with no file-level breakdown.
    val withoutFileBreakdown = uniqueCommits.filter(_.files.isEmpty)
    val (added, removed) =
      if uniqueCommits.forall(_.files.isEmpty) then (uniqueCommits.map(_.added).sum, uniqueCommits.map(_.removed).sum)
      else
        (
          chosenPerFile.map(_._1.added).sum + withoutFileBreakdown.map(_.added).sum,
          chosenPerFile.map(_._1.removed).sum + withoutFileBreakdown.map(_.removed).sum
        )
    AggregateDiffStat(
      added,
      removed,
      children,
      children.exists(c => c.partial || c.merge.isEmpty) || withoutFileBreakdown.nonEmpty
    )

  /**
   * 本 delegate 批次**合并**的代码量变化（+新增 −删除）。锚定序：
   *   ① finish 申报（`reportDetail`）尾哈希——7-40 位 hex（可选括号包裹，与前端
   *      摘要哈希段同一提取口径），rev-parse 可解析且为**双亲提交**才认；
   *   ② 无有效申报哈希时不回落到基分支 tip：那可能是别的 delegate 的 merge，
   *      误锚会让未合并批继承他批统计。无本批 merge 锚点时返回 None（卡面无 merge 块）。
   * 统计 = `git diff --numstat <第一亲>..<merge>`（基工作区视角）加删行总和；
   * binary 行（`- -`）与畸形行不计。
   *
   * fail-soft 全包：任一环不可读 = None（卡面无 diff 块，绝不拖垮终态投递）。
   */
  def mergeDiffStat(rec: DelegateRegistry.Record): Option[MergeDiffStat] =
    Try {
      val ws = Try(os.Path(rec.projectWorkspace.getOrElse(rec.cwd))).toOption
      (ws, rec.baseBranch) match
        case (Some(repo), Some(_)) if GitWorktrees.isGitRepo(repo) =>
          // Only a merge hash explicitly tied to this delegate can anchor this
          // batch. Falling back to the base branch tip attributes someone else's
          // latest merge to every delegate that has not merged yet.
          val anchor = declaredMergeSha(repo, rec)
          anchor.flatMap { case (merge, p1) =>
            numstat(repo, p1, merge).map { case (added, removed, files) =>
              MergeDiffStat(commit = merge.take(7), added = added, removed = removed, oid = merge, files = files)
            }
          }
        case _ => None
    }.getOrElse(None)

  /**
   * 申报尾哈希提取（纯读取）：`reportDetail` trim 后的 7-40 位 hex 尾段（可选
   * `(`/`[` 包裹），rev-parse 解析为双亲提交才返回 `(merge, 第一亲)`。
   */
  private def declaredMergeSha(repo: os.Path, rec: DelegateRegistry.Record): Option[(String, String)] =
    rec.reportDetail.flatMap { detail =>
      val m = """[\(\[]?([0-9a-f]{7,40})[\)\]]?\s*$""".r.findFirstMatchIn(detail.trim)
      m.flatMap(mm => resolveTwoParent(repo, mm.group(1)))
    }

  /** sha/ref ⇒ 双亲提交则 `(自身, 第一亲)`，否则 None。 */
  private def resolveTwoParent(repo: os.Path, ref: String): Option[(String, String)] =
    GitWorktrees.commitInfo(repo, ref).collect {
      case ci if ci.parents.lengthCompare(2) >= 0 => (ci.sha, ci.parents.head)
    }

  /** `git diff --numstat from..to` 的逐文件统计与总计（基工作区视角）。fail-soft。 */
  private def numstat(repo: os.Path, from: String, to: String): Option[(Long, Long, List[FileDiffStat])] =
    Try {
      val res = os
        .proc("git", "-C", repo.toString, "diff", "--numstat", s"$from..$to")
        .call(check = false, mergeErrIntoOut = true)
      if res.exitCode != 0 then None
      else
        val files = res.out.trim().linesIterator.flatMap { line =>
          line.split('\t').toList match
            case adds :: dels :: pathParts if pathParts.nonEmpty && adds.forall(_.isDigit) && dels.forall(_.isDigit) =>
              Some(FileDiffStat(pathParts.mkString("\t"), adds.toLong, dels.toLong))
            case _ => None // binary（`- -`）/ 畸形行不计
        }.toList
        Some((files.map(_.added).sum, files.map(_.removed).sum, files))
    }.getOrElse(None)

end DelegateWorkReport
