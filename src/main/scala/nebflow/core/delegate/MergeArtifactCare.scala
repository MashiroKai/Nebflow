package nebflow.core.delegate

import nebflow.shared.NebflowLogger

import io.circe.syntax.*
import scala.collection.mutable.ListBuffer
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
 * 台账 #943（2026-10-26 作者裁定「合并队列回纯排队+通知」）追加**判性面**
 * （[[triageUntracked]]）——untracked 工件按 blob 判性，不再堵队：
 *   - **同 blob = 已在册**：untracked 文件与 delegate 分支 tip 的某个 blob 完全
 *     相同（`git hash-object` ∈ `git ls-tree -r` 集合）⇒ 内容已在 delegate 分支
 *     保真，本地拷贝是陈旧复本 ⇒ 直接清理（合并落地后同内容自然回来）；
 *   - **路径外无关 untracked**：白名单（evidence/、reports/）外、分支上也没有
 *     同内容的 `??` 文件 ⇒ **自动移隔离区**（dataRoot 下 merge-quarantine/，
 *     保相对路径；旁挂 PROVENANCE.json 记录来源——工作区、原路径、delegate/
 *     分支、时间戳），树转洁且零丢失，不再堵队；
 *   - 白名单内、分支上无同内容的 untracked 仍走**收编**（交付物语义不变）。
 *
 * 消费点：[[nebflow.core.delegate.MergeQueue]] 放行净空闸（授窗前清障——#943
 * 起**余脏不再 fail-closed 拦截**：hygiene 尽力清、只 WARN，delegate 的
 * `git merge` 诚实撞脏进 failed 可见关账；引擎侧 base 净空尽力维护，非合并
 * 执行）。全 fail-soft：git 面任何一步失败只 Left/空结果，绝不抛。
 */
object MergeArtifactCare:

  private val logger = NebflowLogger.forName("nebflow.core.delegate.merge-artifact-care")

  /**
   * 生成工件目录白名单——**严格面**：只这两类目录（引擎测试/任务覆写与产出
   * delegates 报告/证据的固定落点），源码路径绝不碰。目录本体与 `目录/` 前缀
   * 都命中。（10-23 批原词表；原 DelegateAutoMerge 单点随该件退役收编于此。）
   */
  val Dirs: List[String] = List("evidence", "reports")

  /** 白名单命中判（路径 = 目录本体或目录/ 前缀）。 */
  def whitelisted(path: String): Boolean =
    Dirs.exists(d => path == d || path.startsWith(s"$d/"))

  /** 一笔收编（absorb）结果：收编路径 + chore 提交 sha。 */
  final case class Absorption(paths: List[String], commit: String)

  /** 判性上下文（PROVENANCE 归因；由调用方 MergeQueue 传入，测试可 None）。 */
  final case class CareContext(delegateId: String, branch: String, baseBranch: String, projectKey: String)

  /** 一拍判性（triage）结果：清理的复本 + 隔离的路径 + 隔离批次目录。 */
  final case class Triage(removedCopies: List[String], quarantined: List[String], quarantineDir: Option[os.Path])

  /** 一拍处置（[[hygiene]]）报告；`error` = 处置面自身故障（fail-soft 承载）。 */
  final case class Report(
      restored: List[String],
      absorbed: List[String],
      absorbCommit: Option[String],
      /** 判性清理的同 blob 陈旧复本（内容已在 delegate 分支保真）。 */
      triagedRemoved: List[String] = Nil,
      /** 判性移隔离区的路径外无关 untracked（PROVENANCE 随行）。 */
      triagedQuarantined: List[String] = Nil,
      /** 隔离批次目录（PROVENANCE.json 所在）。 */
      quarantineDir: Option[String] = None,
      /** 处置后仍脏的路径（如实回报——#943 起只 WARN 不拦截，delegate 的合并回合诚实面）。 */
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

  /** 同 [[gitRaw]] 但前置可变 git 参数（`-c` 覆写等）。 */
  private def gitRaw2(ws: os.Path, preAndArgs: List[String]): Either[String, String] =
    try
      val res = os.proc(Seq("git", "-C", ws.toString) ++ preAndArgs).call(cwd = ws, check = false, mergeErrIntoOut = true)
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

  // ── 判性面（#943：untracked 按 blob 判性，不再堵队） ──────────────────

  /** 全量 `??` 未跟踪路径（不设白名单；整目录未跟踪折叠成 `?? dir/` 形）。 */
  def allUntrackedPaths(ws: os.Path): List[String] =
    if !isRepo(ws) then Nil
    else
      gitRaw2(ws, List("-c", "core.quotepath=off", "status", "--porcelain")).getOrElse("").linesIterator.flatMap { line =>
        if line.length <= 3 then None
        else if line.take(2) == "??" then Some(line.drop(3).trim) else None
      }.toList.distinct

  /** delegate 分支 tip 的 blob 集合（判性基准；不可读 = 空集——一律不判同）。 */
  private def branchBlobIds(ws: os.Path, branch: String): Set[String] =
    gitRaw(ws, "ls-tree", "-r", branch).getOrElse("").linesIterator.flatMap { line =>
      val tab = line.indexOf('\t')
      if tab <= 0 then None
      else
        val meta = line.take(tab).trim.split("\\s+")
        if meta.length >= 3 && meta(1) == "blob" then Some(meta(2)) else None
    }.toSet

  /** 隔离区根（repo 外——隔离件绝不回流成树脏）。 */
  def quarantineRoot: os.Path = nebflow.shared.PathUtil.dataRoot / "merge-quarantine"

  /** 工作区名 slug（隔离目录一级；只留安全字符）。 */
  private def slug(ws: os.Path): String =
    ws.last.replaceAll("[^A-Za-z0-9._-]", "_").take(80)

  /**
   * untracked 判性（一拍）：
   *   - **同 blob**（`git hash-object` 命中 delegate 分支 tip 的 blob 集）⇒ 内容
   *     已在分支保真，本地是陈旧复本 ⇒ 清理（`removed`）；
   *   - **路径外无关**（白名单外且分支上无同内容）⇒ 整体移隔离区（`quarantined`
   *     ——保相对路径；PROVENANCE.json 记录来源）；
   *   - 白名单内且分支上无同内容 ⇒ 不动（留给收编腿——交付物语义）。
   * 全 fail-soft：单件失败跳过不炸，返回实际处置清单。
   */
  def triageUntracked(ws: os.Path, branch: String, context: Option[CareContext] = None): Triage =
    if !isRepo(ws) then Triage(Nil, Nil, None)
    else
      val blobs = branchBlobIds(ws, branch)
      val removed = ListBuffer.empty[String]
      val toQuarantine = ListBuffer.empty[String]
      allUntrackedPaths(ws).foreach { raw =>
        val p = raw.stripSuffix("/") // porcelain 整目录折叠形 `?? dir/`——归一为无尾斜杠
        val isDirEntry = raw.endsWith("/")
        if isDirEntry then
          // 整目录折叠：未跟踪目录绝无同 blob 可言，路径外 ⇒ 整体移隔离区。
          if !whitelisted(p) then toQuarantine += p
        else if git(ws, "hash-object", "--", p).isRight then // 文件面才可哈希
          val blob = git(ws, "hash-object", "--", p).getOrElse("")
          if blob.nonEmpty && blobs.contains(blob) then
            val abs = ws / os.RelPath(p.replace('\\', '/'))
            val removedOk = Try(os.remove.all(abs)).isSuccess
            if removedOk then
              removed += p
              logger.info(s"[merge-artifact-care] triaged: untracked copy of '$p' is blob-identical to delegate branch '$branch' — removed (content preserved on the branch)")
          else if !whitelisted(p) then toQuarantine += p
        // 其余异常形态一律不动——fail-soft。
      }
      val qdir =
        if toQuarantine.isEmpty then None
        else
          quarantineInto(ws, toQuarantine.toList, context) match
            case Some((dir, moved)) =>
              moved.foreach(p => logger.info(s"[merge-artifact-care] quarantined out-of-path untracked '$p' → $dir (PROVENANCE recorded)"))
              Some((dir, moved))
            case None => None // 移动失败：文件原地不动（绝不丢），不报已隔离
      Triage(removedCopies = removed.toList, quarantined = qdir.map(_._2).getOrElse(Nil), quarantineDir = qdir.map(_._1))
  end triageUntracked

  /**
   * 把 `paths`（工作区相对）移入隔离批次目录（保相对结构），旁挂 PROVENANCE.json。
   * 返回（批次目录, 实际移动清单）；任何一步失败 = None（原文件原地不动——绝不丢）。
   */
  private def quarantineInto(ws: os.Path, paths: List[String], context: Option[CareContext]): Option[(os.Path, List[String])] =
    try
      val batch = quarantineRoot / slug(ws) / java.time.LocalDateTime
        .now(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
      os.makeDir.all(batch)
      val moved = ListBuffer.empty[String]
      paths.foreach { p =>
        val rel = os.RelPath(p.replace('\\', '/'))
        val src = ws / rel
        if os.exists(src) then
          val dst = batch / rel
          os.makeDir.all(dst / os.up)
          os.move(src, dst)
          moved += p
      }
      if moved.isEmpty then None
      else
        val provenance =
          s"""{
           |  "what": "out-of-path untracked file(s) moved aside by merge-artifact-care at the merge gate — the merge queue is never blocked by them, and nothing is deleted",
           |  "originWs": "${ws.toString.replace("\\", "\\\\")}",
           |  "paths": ${moved.toList.asJson.noSpaces},
           |  "delegateId": "${context.fold("")( _.delegateId)}",
           |  "branch": "${context.fold("")( _.branch)}",
           |  "baseBranch": "${context.fold("")( _.baseBranch)}",
           |  "projectKey": "${context.fold("")( _.projectKey)}",
           |  "quarantinedAtEpochMs": ${System.currentTimeMillis()},
           |  "restoreHint": "move the files back under the originWs paths above"
           |}
           |""".stripMargin
        os.write.over(batch / "PROVENANCE.json", provenance)
        Some((batch, moved.toList))
    catch
      case e: Throwable =>
        logger.warn(s"[merge-artifact-care] quarantine move failed (files left in place): ${Option(e.getMessage).getOrElse(e.toString).take(200)}")
        None
  end quarantineInto

  /**
   * 一拍全量处置（合并闸前的单点）：先还原 tracked 覆写、再**判性 untracked**
   * （同 blob 复本清理 / 路径外无关件移隔离区），后收编白名单剩余工件，最后
   * 如实回报剩余脏（#943 起**只回报不拦截**——调用方 WARN 放行，合并回合诚实
   * 面）。全 fail-soft。
   */
  def hygiene(ws: os.Path, context: Option[CareContext] = None): Report =
    if !isRepo(ws) then Report(Nil, Nil, None, remainingDirty = Nil, error = Some(s"'$ws' is not a git repo"))
    else
      val restored = restoreTrackedOverwrites(ws)
      val branch = context.map(_.branch).getOrElse("HEAD")
      val triage = triageUntracked(ws, branch, context)
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
        triagedRemoved = triage.removedCopies,
        triagedQuarantined = triage.quarantined,
        quarantineDir = triage.quarantineDir.map(_.toString),
        remainingDirty = dirtyPaths(ws),
        error = None
      )

end MergeArtifactCare
