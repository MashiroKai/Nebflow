package nebflow.core.delegate

import nebflow.shared.NebflowLogger

import scala.util.Try

/**
 * MergeArtifactCare —— 合并闸前**生成工件处置面**（merge-queue 滞留两病灶修复批
 * 2026-10-25，作者令腿①「工件处置兑现」）。
 *
 * 病灶（作者 2026-10-25 实证）：DelegateAutoMerge 拒并文案声称「generated
 * artifacts under evidence/ and reports/ are auto-restored by the engine」，但
 * 还原腿（[[DelegateAutoMerge.restoreArtifacts]]，10-23 批）只还原**已跟踪文件
 * 的未提交修改**——`??` 未跟踪工件（reports/20261023_182241_panels-mobile-ux/
 * 等）既不还原也不收编，滞留主树两天多、两次挡死合并队列。
 *
 * 处置（作者令逐字：未跟踪生成工件「要么真还原、要么自动收编进待合并批的提交
 * （收编优先——报告/证据是批交付物，删/还原反而丢东西）；tracked 修改仍
 * fail-closed 拒绝不变」）：
 *   - **收编（首选）**：evidence/、reports/ 下的 `??` 未跟踪文件 `git add` +
 *     `git commit` 成一笔 chore 提交（[[absorbUntracked]]）——交付物入册不丢，
 *     树转洁合并放行；提交前守卫：暂存区若混入白名单外的路径（他人 staged 的
 *     源码）⇒ 撤销暂存放弃收编（fail-closed，绝不把别人的 staged 工作卷走）；
 *   - **还原**：evidence/、reports/ 下已跟踪文件的未提交修改 `git checkout
 *     HEAD --`（[[restoreTrackedOverwrites]]——10-23 批语义，staged 连索引一并
 *     复位）；
 *   - **白名单外一切脏**（源码等）：一概不碰——调用方 fail-closed 拒绝
 *     （[[hygiene]] 的 `remainingDirty` 面如实回报）。
 *
 * 消费点：DelegateAutoMerge 评估闸（脏树先处置后拒）、MergeQueue 合并轮注入前
 * （[[nebflow.core.delegate.MergeQueue]] reminder 面——轮前清障，delegate 的
 * `git merge` 不再被工件挡死）、MergeStallCare 重试拍（「工件已清理」条件）。
 * 全 fail-soft：git 面任何一步失败只 Left/空结果，绝不抛（调用方走既有拒绝面）。
 */
object MergeArtifactCare:

  private val logger = NebflowLogger.forName("nebflow.core.delegate.merge-artifact-care")

  /**
   * 生成工件目录白名单——**严格面**：只这两类目录（引擎测试/任务覆写与产出
   * delegates 报告/证据的固定落点），源码路径绝不碰。目录本体与 `目录/` 前缀
   * 都命中。单点 = [[DelegateAutoMerge.ArtifactRestoreDirs]]（10-23 批原词表）。
   */
  val Dirs: List[String] = DelegateAutoMerge.ArtifactRestoreDirs

  /** 白名单命中判（路径 = 目录本体或目录/ 前缀）。 */
  def whitelisted(path: String): Boolean =
    Dirs.exists(d => path == d || path.startsWith(s"$d/"))

  /** 一笔收编（absorb）结果：收编路径 + chore 提交 sha。 */
  final case class Absorption(paths: List[String], commit: String)

  /** 一拍处置（[[hygiene]]）报告；`error` = 处置面自身故障（fail-soft 承载）。 */
  final case class Report(
      restored: List[String],
      absorbed: List[String],
      absorbCommit: Option[String],
      /** 处置后仍脏的路径（白名单外——fail-closed 拒绝面的可行动清单）。 */
      remainingDirty: List[String],
      error: Option[String]
  )

  // ── git 面（私有薄封装，全 fail-soft） ────────────────────────────────

  private def git(ws: os.Path, args: String*): Either[String, String] = gitList(ws, args.toList)

  private def gitList(ws: os.Path, args: List[String]): Either[String, String] =
    try
      val res = os.proc(Seq("git", "-C", ws.toString) ++ args).call(cwd = ws, check = false, mergeErrIntoOut = true)
      if res.exitCode != 0 then Left(res.out.trim().take(400)) else Right(res.out.trim())
    catch case e: Throwable => Left(Option(e.getMessage).getOrElse(e.toString).take(400))

  /** 同 [[git]] 但保留输出原貌（porcelain 首行状态位不被 trim 吃掉——DelegateAutoMerge 同判据）。 */
  private def gitRaw(ws: os.Path, args: String*): Either[String, String] =
    try
      val res = os.proc(Seq("git", "-C", ws.toString) ++ args).call(cwd = ws, check = false, mergeErrIntoOut = true)
      if res.exitCode != 0 then Left(res.out.trim().take(400))
      else Right(String(res.out.bytes, java.nio.charset.StandardCharsets.UTF_8).stripTrailing())
    catch case e: Throwable => Left(Option(e.getMessage).getOrElse(e.toString).take(400))

  def isRepo(ws: os.Path): Boolean = Try(os.exists(ws) && nebflow.core.GitWorktrees.isGitRepo(ws)).getOrElse(false)

  // ── 读数面 ────────────────────────────────────────────────────────────

  /**
   * 白名单内 `??` 未跟踪路径（porcelain 首两列 `??`；整目录未跟踪折叠成
   * `?? reports/` 形——`git add` 整目录收编，目录内逐文件归属照常）。
   */
  def untrackedArtifactPaths(ws: os.Path): List[String] =
    if !isRepo(ws) then Nil
    else
      gitRaw(ws, "status", "--porcelain").getOrElse("").linesIterator.flatMap { line =>
        if line.length <= 3 then None
        else
          val path = line.drop(3).trim
          if line.take(2) == "??" && whitelisted(path) then Some(path) else None
      }.toList.distinct

  /** 脏路径清单（porcelain 全量；cap 条后折「+N more」；不可读 = Nil）。 */
  def dirtyPaths(ws: os.Path, cap: Int = 10): List[String] =
    if !isRepo(ws) then Nil
    else
      val all = gitRaw(ws, "status", "--porcelain").getOrElse("")
        .linesIterator.filter(_.nonEmpty).map(_.drop(3).trim).filter(_.nonEmpty).toList.distinct
      if all.length <= cap then all else all.take(cap) :+ s"+${all.length - cap} more"

  def dirty(ws: os.Path): Boolean =
    !isRepo(ws) || git(ws, "status", "--porcelain").map(_.nonEmpty).getOrElse(true)

  // ── 处置面 ────────────────────────────────────────────────────────────

  /**
   * 收编白名单内 `??` 未跟踪工件进待合并批：`git add -- <paths>` + `git commit`
   * （chore 一笔）。守卫：提交前读暂存区全量，白名单外的 staged 路径（他人
   * staged 的源码）⇒ `git reset` 撤销本次暂存并放弃收编（Left——fail-closed，
     * 绝不卷走别人的 staged 工作）。无可收编 = Right(空)。收编成功后树净了一类。
   */
  def absorbUntracked(ws: os.Path): Either[String, Absorption] =
    if !isRepo(ws) then Right(Absorption(Nil, ""))
    else
      untrackedArtifactPaths(ws) match
        case Nil => Right(Absorption(Nil, ""))
        case paths =>
          gitList(ws, List("add", "--") ++ paths).flatMap { _ =>
            val staged = git(ws, "diff", "--cached", "--name-only")
              .map(_.linesIterator.map(_.trim).filter(_.nonEmpty).toList).getOrElse(Nil)
            val foreign = staged.filterNot(whitelisted)
            if foreign.nonEmpty then
              gitList(ws, List("reset", "--") ++ paths) // 撤销本次暂存（不动他人 staged 面——reset 按路径收窄）
              val err = s"absorb aborted: non-artifact paths staged by someone else (${foreign.take(5).mkString(", ")})"
              logger.warn(s"[merge-artifact-care] $err")
              Left(err)
            else
              git(ws, "commit", "-m", "chore(merge-hygiene): absorb untracked generated artifacts (evidence/, reports/) into the merge batch").flatMap { _ =>
                git(ws, "rev-parse", "HEAD").map(sha => Absorption(paths, sha))
              }
          }

  /**
   * 白名单内「已跟踪文件的未提交修改」还原到 HEAD（`git checkout HEAD --`——
   * staged/unstaged 皆算 M，连索引一并复位；10-23 批 DelegateAutoMerge 同语义
     * 的 rec 解耦版，供合并轮/重试拍消费）。返回实际还原的路径。
   */
  def restoreTrackedOverwrites(ws: os.Path): List[String] =
    if !isRepo(ws) then Nil
    else
      val candidates = gitRaw(ws, "status", "--porcelain").getOrElse("").linesIterator.flatMap { line =>
        if line.length <= 3 then None
        else
          val xy = line.take(2)
          val path = line.drop(3).trim
          val modified = xy.charAt(0) == 'M' || xy.charAt(1) == 'M'
          if modified && whitelisted(path) then Some(path) else None
      }.toList.distinct
      candidates.filter(p => git(ws, "checkout", "HEAD", "--", p).isRight)

  /**
   * 一拍全量处置（合并闸前的单点）：先还原 tracked 覆写、再收编未跟踪工件，
   * 最后如实回报剩余脏（白名单外——调用方 fail-closed）。全 fail-soft。
   */
  def hygiene(ws: os.Path): Report =
    if !isRepo(ws) then Report(Nil, Nil, None, Nil, Some(s"'$ws' is not a git repo"))
    else
      val restored = restoreTrackedOverwrites(ws)
      val absorption = absorbUntracked(ws) match
        case Right(a) if a.paths.nonEmpty =>
          logger.info(
            s"[merge-artifact-care] absorbed ${a.paths.size} untracked generated artifact(s) into the merge batch " +
              s"(${a.paths.mkString(", ")}) as ${a.commit.take(9)}"
          )
          Some(a)
        case Right(_) => None
        case Left(err) =>
          logger.warn(s"[merge-artifact-care] absorb failed (tree may stay dirty): ${err.take(200)}")
          None
      Report(
        restored = restored,
        absorbed = absorption.map(_.paths).getOrElse(Nil),
        absorbCommit = absorption.map(_.commit),
        remainingDirty = dirtyPaths(ws),
        error = None
      )

end MergeArtifactCare
