package nebflow.core.delegate

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import nebflow.core.GitWorktrees
import nebflow.shared.{NebflowLogger, PathUtil}

import scala.collection.mutable
import scala.util.Try

/**
 * DelegateAutoMerge —— delegate 分支 worktree 的**引擎原生自动合并**（#524 ·
 * 2026-10-04 作者裁定「合并节点引擎化」落地批）。
 *
 * 现状（本批前）：delegate 判词 completed 且分支上确有领先提交 ⇒ 引擎只写
 * `unmerged-work` WARN 注记（P2-3）——交付滞留分支，靠批内人肉 `git merge`。
 * 本批把合并引擎化：delegate 终态（verdict=completed）× 有分支锚 × 非合并轮 ⇒
 * 引擎自己按**完成序（FIFO）**执行 `git merge --no-ff` 回集成分支：
 *
 *   - ② 零冲突 ⇒ 自动合并（--no-ff，落地后 [[MergeAcceptance]] 同判据构造性
 *     校验双亲 merge + 祖先真）；
 *   - ③ 冲突 ⇒ **绝不强推**：`git merge --abort` 原地复原（分支/worktree/基
 *     工作区全保留），冲突文件清单 + 挂起状态写 delegate 终态 payload 尾注 +
 *     任务台账 note + ChainStore `auto-merge` 行，候人/候智能裁决；
 *   - ④ 幂等：合并前查目标分支是否已含支头（`commitsAhead(target, branch)==0`
 *     ⟺ 支头可达）——重复完成/引擎重试零重复合并（批内已自行合并的场景天然
 *     兜住）；
 *   - ⑤ 可配置：`nebflow.json` `delegateAutoMerge.enabled`（缺省 true，热读，
 *     false = 一键回既有注记路径）＋ `delegateAutoMerge.targetBranch`（显式
 *     覆盖；缺省 = spawn 绑定 baseBranch——标准流程下即集成分支 main。缺绑定
 *     且无配置 ⇒ 跳过不猜，holdForMerge misc② 同纪律）；
 *   - ⑥ 审计：每条评估路径（merged / conflict-pending / refused / already-
 *     merged / skipped / failed）都写 ChainStore `auto-merge` 行（delegateId、
 *     branch、target、merge commit、结果）。
 *
 * 调度：per projectKey 单飞行 FIFO（完成序 = 钩子入队序，[[submit]] 单点），
 * 与 MergeQueue（delegate 自执行合并轮）互不相干、零引用。合并目标工作区 =
 * 基工作区（worktree 腿 `git merge` 方向必须钉死在基工作区——MergeQueue 同判
 * 据）；基工作区脏（uncommitted state）或目标非检出分支 ⇒ 拒并挂起（refused，
 * 不混不猜）。
 *
 * 覆盖面：内部腿 [[nebflow.core.delegate.DelegateTerminal.onCompleted]] 与外部
 * 执行器腿 [[nebflow.core.executor.ExternalSessionRunner.settle]] 同闸——外部
 * 腿经 DelegateWorkspace.prepare 同样有分支锚，能拿到分支就纳入；锚缺失 =
 * 注记跳过。队列状态为进程内运行态（与 MergeQueue 同口径：重启即消亡——条目
 * 全部在单次终态钩子内产生并消费，无跨重启悬挂面）。
 */
object DelegateAutoMerge:

  private val logger = NebflowLogger.forName("nebflow.core.delegate.auto-merge")

  /** ChainStore 行种类（审计 ⑥——新行种类零迁移，ChainStore 通用行形态）。 */
  val EntryKind: String = "auto-merge"

  // ── ⑤ 配置（nebflow.json `delegateAutoMerge.*`，热读——PluginsConfig 先例） ──

  final case class Config(
    /** 总开关：false = 引擎不代并（一键回既有 P2-3 注记路径）。缺省 true。 */
    enabled: Boolean,
    /** 显式合并目标覆盖；None = 用 spawn 绑定 baseBranch（标准流程 = main）。 */
    targetBranch: Option[String],
    /**
     * 生成物自动还原开关（合并静默搁浅修复批 2026-10-23 三腿之腿③）：评估遇脏
     * 树先还原 evidence/、reports/ 下「已跟踪文件的未提交修改」（= 测试覆写的
     * 生成物，非人写代码——去堵源），还原后仍脏才拒。缺省 true；false = 一键回
     * 「脏即拒」原语义（还原面零动作）。
     */
    restoreArtifacts: Boolean
  )

  object Config:
    val Default: Config = Config(enabled = true, targetBranch = None, restoreArtifacts = true)

    /** 热读（每次现读盘，零缓存）；absent / 非法 / garbage ⇒ 缺省。 */
    def read: Config =
      try
        val configPath = PathUtil.configJsonReadPath(PathUtil.dataRoot)
        if !os.exists(configPath) then Default
        else
          io.circe.parser.parse(os.read(configPath)).toOption match
            case None => Default
            case Some(json) =>
              val cur = json.hcursor.downField("delegateAutoMerge")
              val enabled = cur.downField("enabled").as[Option[Boolean]].toOption.flatten.getOrElse(true)
              val target =
                cur.downField("targetBranch").as[Option[String]].toOption.flatten
                  .map(_.trim).filter(_.nonEmpty)
              val restoreArtifacts =
                cur.downField("restoreArtifacts").as[Option[Boolean]].toOption.flatten.getOrElse(true)
              Config(enabled, target, restoreArtifacts)
      catch case _: Throwable => Default
  end Config

  // ── 评估结论（suffix = delegate 终态 payload 尾注——🔴 模型/用户可见文案） ──

  /** 单次终态自动合并评估结论；`action` = ChainStore 审计行动作词。 */
  sealed trait Result:
    def action: String
    def suffix: Option[String]

  object Result:

    /** ② 零冲突自动合并落地。 */
    final case class Merged(target: String, commit: String, ahead: Int) extends Result:
      val action = "merged"
      val suffix = Some(
        s"\n\n[auto-merge] branch merged into '$target' by the engine (FIFO, --no-ff, merge commit ${commit.take(9)}, $ahead commit(s)) — audit row: ChainStore '$EntryKind'."
      )

    /** ④ 幂等：目标已含支头（或无可并提交）——含批内已自行合并的场景。 */
    final case class AlreadyMerged(target: String) extends Result:
      val action = "already-merged"
      val suffix = Some(
        s"\n\n[auto-merge] nothing to merge — '$target' already contains the branch head (idempotent skip)."
      )

    /**
     * ③ 冲突挂起：merge 已 `--abort` 干净复原，分支/worktree/基工作区原样；
     * 文件清单候人/候智能裁决。
     */
    final case class ConflictPending(target: String, files: List[String], abortErr: Option[String])
        extends Result:
      val action = "conflict-pending"
      val abortNote = abortErr.map(e => s" NOTE: 'git merge --abort' itself failed ($e) — inspect the base workspace manually.").getOrElse("")
      val suffix = Some(
        s"\n\n[auto-merge] CONFLICT — merge of the branch into '$target' was aborted cleanly; branch, worktree and base workspace are preserved untouched. " +
          s"Conflicting files: ${if files.isEmpty then "<unreadable>" else files.mkString(", ")}. " +
          s"The merge stays PENDING for human/agent adjudication (state + file list in the task ledger and ChainStore '$EntryKind' rows).$abortNote"
      )

    /**
     * 拒并挂起（脏基工作区 / 目标非检出分支 / git 拒绝且无冲突标记）——不动手。
     * `dirtyPaths` = 拒并时的脏路径清单（脏树拒绝时携带——升级通知与审计行的
     * 可行动信息面；合并静默搁浅修复批 2026-10-23）。
     */
    final case class Refused(target: String, reason: String, dirtyPaths: List[String] = Nil) extends Result:
      val action = "refused"
      val suffix = Some(
        s"\n\n[auto-merge] branch NOT merged into '$target': $reason Branch preserved — pending adjudication; " +
          "the delegate has been notified to clear the obstacle and retry the merge on its own (the platform never runs merges for delegates)."
      )

    /** 跳过（无锚 / 非 git / 分支 ref 消失）——suffix None（调用方回既有路径）。 */
    final case class Skipped(reason: String) extends Result:
      val action = "skipped"
      val suffix = None

    /** 异常态（落地后构造性校验不通过等）——诚实报 failed，分支保留。 */
    final case class Failed(reason: String) extends Result:
      val action = "failed"
      val suffix = Some(
        s"\n\n[auto-merge] merge attempt FAILED: $reason Branch preserved — inspect the base workspace before any retry."
      )
  end Result

  // ── 终态钩子（① 生命周期挂点；两腿同闸） ──────────────────────────────

  /**
   * delegate 终态（verdict=completed）的引擎自动合并入口。开关 off / 无记录 /
   * 无分支锚 = None（调用方回落既有路径；外部腿的锚缺失跳过注记由调用方自
   * 组）。评估在 per-projectKey 的 FIFO 单飞行窗内执行（完成序 = 入队序），
   * 返回值 = 终态 payload 尾注（None = 无尾注）。
   */
  def onCompletedHook(
    rec: Option[DelegateRegistry.Record],
    cfgOverride: Option[Config] = None
  ): IO[Option[String]] =
    rec.filter(_.branch.nonEmpty) match
      case None => IO.pure(None)
      case Some(r) =>
        val cfg = cfgOverride.getOrElse(Config.read)
        if !cfg.enabled then IO.pure(None)
        else
          val baseWs = r.projectWorkspace.getOrElse(r.cwd)
          val projectKey = Try(os.Path(baseWs)).map(GitWorktrees.projectKeyOf).getOrElse(baseWs)
          IO
            .blocking(submit[Result](projectKey, () => evaluate(r, cfg)))
            // 合并静默搁浅修复批（2026-10-23 作者令腿①，同日裁定纠正版）：refused
            // ⇒ 通知 delegate 本人（可行动诊断，持久注入）+ 父会话知会一行。平台
            // 零代跑——重试合并是 delegate 自己的责任（清障后自行重试）。self-
            // guarded——通知故障绝不污染终态尾注。
            .flatTap(res => postSettle(r, res).handleErrorWith(e =>
              logger.warn(s"auto-merge post-settle failed for ${r.id}: ${e.getMessage}")
            ))
            .map(_.suffix)
            .handleErrorWith(e =>
              IO(Some(s"\n\n[auto-merge] hook error: ${Option(e.getMessage).getOrElse(e.toString).take(200)} — branch left untouched."))
            )

  // ── 评估本体（同步；全 git 面快操作；绝不抛——异常折进 Result.Failed） ──

  private def evaluate(rec: DelegateRegistry.Record, cfg: Config): Result =
    Try(attempt(rec, cfg)) match
      case scala.util.Success(r) => r
      case scala.util.Failure(e) =>
        val reason = s"unexpected evaluation error: ${Option(e.getMessage).getOrElse(e.toString).take(300)}"
        logger.warn(s"auto-merge evaluation threw for ${rec.id} — branch kept: $reason")
        row(rec, "failed", Json.obj("reason" -> reason.asJson))
        Result.Failed(s"$reason.")

  private def attempt(rec: DelegateRegistry.Record, cfg: Config): Result =
    val branch = rec.branch.getOrElse("")
    val wsOpt = Try(os.Path(rec.projectWorkspace.getOrElse(rec.cwd))).toOption.filter(GitWorktrees.isGitRepo)
    if branch.isEmpty || wsOpt.isEmpty then
      val reason =
        if branch.isEmpty then "no branch anchor on record"
        else s"base workspace '${rec.projectWorkspace.getOrElse(rec.cwd)}' is not a git repo"
      row(rec, "skipped", Json.obj("reason" -> reason.asJson))
      Result.Skipped(reason)
    else
      val ws = wsOpt.get
      cfg.targetBranch.orElse(rec.baseBranch) match
        case None =>
          val reason =
            "no merge target (delegateAutoMerge.targetBranch unset, spawn binding has no base branch) — refusing to guess 'main'"
          row(rec, "skipped", Json.obj("reason" -> reason.asJson))
          Result.Skipped(reason)
        case Some(target) =>
          if !GitWorktrees.branchExists(ws, branch) then
            val reason = s"branch ref '$branch' no longer exists locally"
            row(rec, "skipped", Json.obj("reason" -> reason.asJson, "target" -> target.asJson))
            Result.Skipped(reason)
          else if GitWorktrees.currentBranch(ws) != Some(target) then
            val reason =
              s"target '$target' is not the checked-out branch ('${GitWorktrees.currentBranch(ws).getOrElse("detached HEAD")}') of the base workspace — engine-native merge refuses a non-checked-out target"
            row(rec, "refused", Json.obj("reason" -> reason.asJson, "target" -> target.asJson))
            Result.Refused(target, s"$reason.")
          else
            // 腿③（生成物自动还原·去堵源，2026-10-23）：树脏先做白名单还原
            // （evidence/、reports/ 下已跟踪文件的未提交修改 = 测试覆写的生成物；
            // 源码路径绝不碰），还原后仍脏才拒——拒绝理由携脏路径清单（可行动）。
            val dirtyNow = dirty(ws)
            val restored = if dirtyNow then restoreArtifacts(rec, ws, cfg) else Nil
            if dirtyNow && dirty(ws) then
              val paths = dirtyPaths(ws)
              val reason =
                "base workspace has uncommitted changes — refusing to auto-merge over an unclean tree " +
                  "(fail-closed, nothing staged)" +
                  (if paths.nonEmpty then s". Dirty paths: ${paths.mkString(", ")}" else "") +
                  ". Clean them (commit or restore) to unblock — generated artifacts under evidence/ and " +
                  "reports/ are auto-restored by the engine."
              row(
                rec,
                "refused",
                Json.obj(
                  "reason" -> reason.asJson,
                  "target" -> target.asJson,
                  "dirtyPaths" -> paths.asJson,
                  "artifactsRestored" -> restored.map(_.path).asJson
                )
              )
              Result.Refused(target, s"$reason.", paths)
            else
              // ④ 幂等闸：commitsAhead==0 ⟺ 目标已含支头（重复完成/引擎重试/批内
              // 已自行合并——一律零重复合并）。
              val ahead = GitWorktrees.commitsAhead(ws, target, branch)
              if ahead == 0 then
                row(rec, "already-merged", Json.obj("target" -> target.asJson, "commitsAhead" -> 0.asJson))
                Result.AlreadyMerged(target)
              else
                val msg =
                  s"auto-merge: delegate ${rec.id} — merge branch '$branch' into '$target' (engine-native FIFO, #524)"
                runMerge(ws, rec, target, branch, ahead, msg)
  end attempt

  /** ②/③ 合并执行：成功走构造性校验；失败读冲突清单后 --abort 复原（绝不强推）。 */
  private def runMerge(
    ws: os.Path,
    rec: DelegateRegistry.Record,
    target: String,
    branch: String,
    ahead: Int,
    message: String
  ): Result =
    git(ws, "merge", "--no-ff", "-m", message, branch) match
      case Right(_) =>
        val commit = GitWorktrees.resolveRef(ws, target).getOrElse("")
        // 落地后构造性校验（MergeAcceptance 同判据：双亲 merge ＋祖先真）——
        // 引擎自己动手也自证，异常态诚实报 failed。
        if MergeAcceptance.verify(ws, target, branch).ok then
          row(
            rec,
            "merged",
            Json.obj(
              "target" -> target.asJson,
              "mergeCommit" -> commit.asJson,
              "commitsAhead" -> ahead.asJson,
              "mode" -> "no-ff".asJson
            )
          )
          logger.info(s"auto-merge: ${rec.id} branch '$branch' merged into '$target' ($commit)")
          Result.Merged(target, commit, ahead)
        else
          val reason =
            s"post-merge acceptance verification failed (target tip=$commit) — unexpected state after a reported-clean merge"
          row(rec, "failed", Json.obj("target" -> target.asJson, "reason" -> reason.asJson))
          Result.Failed(s"$reason.")
      case Left(err) =>
        val files = conflictFiles(ws)
        if files.nonEmpty then
          // ③ 冲突：绝不强推——abort 原地复原（分支/worktree/基工作区全保留），
          // 挂起状态 + 冲突文件清单进 delegate 终态尾注 + 任务台账 + 审计行。
          val abortErr = git(ws, "merge", "--abort").left.toOption
          row(
            rec,
            "conflict-pending",
            Json.obj(
              "target" -> target.asJson,
              "conflictFiles" -> files.asJson,
              "mergeAborted" -> abortErr.isEmpty.asJson,
              "abortError" -> abortErr.map(_.take(300)).asJson
            )
          )
          ledgerNote(
            rec,
            s"[auto-merge] conflict pending — branch '$branch' NOT merged into '$target' (merge aborted cleanly, branch preserved). " +
              s"Conflicting files: ${files.mkString(", ")}. Awaiting human/agent adjudication."
          )
          logger.warn(s"auto-merge: ${rec.id} conflict on '$branch' -> '$target' (${files.size} file(s)) — merge aborted, PENDING")
          Result.ConflictPending(target, files, abortErr)
        else
          val reason = s"git refused the merge (no conflict markers): ${err.take(300)}"
          row(rec, "refused", Json.obj("target" -> target.asJson, "reason" -> reason.asJson))
          Result.Refused(target, s"$reason.")
    end match
  end runMerge

  // ── git 面（私有薄封装——GitWorktrees.git 不外露；全 fail-soft） ──────────

  private def git(ws: os.Path, args: String*): Either[String, String] =
    try
      val res = os.proc(Seq("git", "-C", ws.toString) ++ args).call(cwd = ws, check = false, mergeErrIntoOut = true)
      if res.exitCode != 0 then Left(res.out.trim().take(400)) else Right(res.out.trim())
    catch case e: Throwable => Left(Option(e.getMessage).getOrElse(e.toString).take(400))

  /**
   * 同 [[git]] 但保留输出原貌（仅剥行尾换行）——porcelain 列对齐解析面专用：
   * 首行的前导空白是 X/Y 状态位（unstaged 形 ` M path` 的 X 列是空格），
   * `trim` 会把整段输出的首个字符连同状态位一起吃掉，drop(3) 后路径掉首字。
   */
  private def gitRaw(ws: os.Path, args: String*): Either[String, String] =
    try
      val res = os.proc(Seq("git", "-C", ws.toString) ++ args).call(cwd = ws, check = false, mergeErrIntoOut = true)
      if res.exitCode != 0 then Left(res.out.trim().take(400))
      else Right(String(res.out.bytes, java.nio.charset.StandardCharsets.UTF_8).stripTrailing())
    catch case e: Throwable => Left(Option(e.getMessage).getOrElse(e.toString).take(400))

  /** 基工作区干净判（uncommitted state 零容忍；不可读 = 视同脏，fail-closed 拒并）。 */
  private def dirty(ws: os.Path): Boolean =
    git(ws, "status", "--porcelain").map(_.nonEmpty).getOrElse(true)

  /** 冲突文件清单（merge 失败现场读取；不可读 = Nil——清单缺失在行里可分）。 */
  private def conflictFiles(ws: os.Path): List[String] =
    git(ws, "diff", "--name-only", "--diff-filter=U")
      .map(_.linesIterator.map(_.trim).filter(_.nonEmpty).toList)
      .getOrElse(Nil)

  // ── 腿③ 生成物自动还原（2026-10-23 作者令：去堵源） ─────────────────────

  /** 单条还原记录（审计行承载：路径 + 还原前 diff 摘要）。 */
  final case class RestoredArtifact(path: String, diff: String)

  /**
   * 生成物目录白名单——**严格面**：只这两类目录（引擎测试覆写 delegates 报告/
   * 证据文件的固定落点），源码路径绝不碰。目录本体与 `目录/` 前缀都命中。
   */
  val ArtifactRestoreDirs: List[String] = List("evidence", "reports")

  /**
   * 白名单内「已跟踪文件的未提交修改」（porcelain X/Y 列 = M，staged/unstaged
   * 皆算；`??` untracked / D / R 非「修改」语义，一概不碰）自动还原到 HEAD：
   * `git checkout HEAD -- <path>`（HEAD 形——staged 修改连索引一并复位，比裸
   * `checkout --` 严格）。还原前逐条备 diff 摘要（`--stat HEAD`）并**先**落
   * 审计行（还原意图有据），后动手；fail-soft——单条失败只 WARN，绝不阻断
   * 评估链（还原失败 = 树仍脏 = 走既有 refused 面）。
   */
  private def restoreArtifacts(rec: DelegateRegistry.Record, ws: os.Path, cfg: Config): List[RestoredArtifact] =
    if !cfg.restoreArtifacts then Nil
    else
      val candidates = gitRaw(ws, "status", "--porcelain").getOrElse("").linesIterator.flatMap { line =>
        if line.length <= 3 then None
        else
          val xy = line.take(2)
          val path = line.drop(3).trim
          val modified = xy.charAt(0) == 'M' || xy.charAt(1) == 'M'
          val whitelisted =
            ArtifactRestoreDirs.exists(d => path == d || path.startsWith(s"$d/"))
          if modified && whitelisted then Some(path) else None
      }.toList.distinct
      if candidates.isEmpty then Nil
      else
        // 还原前证据：每条路径的 diff 摘要（对 HEAD 的 --stat；二进制 = Bin 字节摘要）。
        val planned = candidates.map { p =>
          val diff = git(ws, "diff", "--stat", "HEAD", "--", p).getOrElse("").trim.take(300)
          p -> diff
        }
        // 审计行先落（还原前——任务令「还原前记录审计行（路径+diff 摘要）」）。
        row(
          rec,
          "artifact-restore",
          Json.obj(
            "baseWorkspace" -> ws.toString.asJson,
            "plannedRestores" -> Json.fromValues(planned.map { case (p, d) =>
              Json.obj("path" -> p.asJson, "diff" -> d.asJson)
            })
          )
        )
        val restored = planned.flatMap { case (p, d) =>
          git(ws, "checkout", "HEAD", "--", p) match
            case Right(_) =>
              logger.info(s"auto-merge: restored generated artifact to HEAD before merge evaluation: $p")
              Some(RestoredArtifact(p, d))
            case Left(e) =>
              logger.warn(s"auto-merge: artifact restore failed for '$p' (tree stays dirty, refused path): $e")
              None
        }
        if restored.nonEmpty then
          logger.info(
            s"auto-merge: ${restored.size} generated artifact(s) auto-restored in the base workspace " +
              s"(${restored.map(_.path).mkString(", ")})"
          )
        restored
  end restoreArtifacts

  /** 脏路径清单（porcelain `XY<sp>PATH` 形——首行 X 列空白是状态位，必须走
    * [[gitRaw]] 保内容解析；cap 条后折 「+N more」；不可读 = Nil）。 */
  private def dirtyPaths(ws: os.Path, cap: Int = 10): List[String] =
    val all = gitRaw(ws, "status", "--porcelain").getOrElse("")
      .linesIterator.filter(_.nonEmpty).map(_.drop(3).trim).filter(_.nonEmpty).toList.distinct
    if all.length <= cap then all else all.take(cap) :+ s"+${all.length - cap} more"

  // ── 审计面（⑥ 每条评估路径一行 + 台账 note） ────────────────────────────

  private def row(rec: DelegateRegistry.Record, action: String, extra: Json): Unit =
    try
      ChainStore.appendSync(
        ChainStore.keyFor(rec.taskId, rec.id),
        EntryKind,
        Json
          .obj(
            "action" -> action.asJson,
            "delegateId" -> rec.id.asJson,
            "branch" -> rec.branch.getOrElse("").asJson,
            "baseWorkspace" -> rec.projectWorkspace.getOrElse(rec.cwd).asJson
          )
          .deepMerge(extra)
      )
    catch case e: Throwable => logger.warn(s"auto-merge ChainStore row failed ($action): ${e.getMessage}")

  /** 任务台账 note（③ 冲突挂起等候裁面的台账承载；fail-soft——台账失败只 WARN）。 */
  private def ledgerNote(rec: DelegateRegistry.Record, note: String): Unit =
    rec.taskId.foreach { tid =>
      try
        nebflow.core.project.TaskLedgerStore
          .open()
          .appendNoteSync(
            tid,
            note,
            from = nebflow.core.project.TaskLedgerHistory.Origins.Nebula,
            actor = nebflow.core.project.TaskLedgerHistory.Actors.Nebula
          )
      catch case e: Throwable => logger.warn(s"auto-merge task-ledger note failed: ${e.getMessage}")
    }

  // ── per-projectKey 单飞行 FIFO（完成序 = 入队序；spec 判红锚） ───────────

  private final class OneShot:
    @volatile private var done: Boolean = false
    def await(): Unit = synchronized { while !done do wait() }
    def complete(): Unit = synchronized { done = true; notifyAll() }

  private val lock = new Object
  private val lines = mutable.Map.empty[String, mutable.ListBuffer[(OneShot, () => Unit)]]
  private val running = mutable.Set.empty[String]

  /**
   * 排队等窗并执行（严格 FIFO = 入队序；同 projectKey 单飞行——并发完成的
   * delegate 逐个合并，绝不并发写同一基工作区）。返回本 job 的产出。job 体
   * 必须自吞异常（[[evaluate]] 已包）；drain 再兜一层，绝不楔死后续排队者。
   */
  private def submit[A](projectKey: String, job: () => A): A =
    val latch = new OneShot
    val box = new java.util.concurrent.atomic.AtomicReference[Option[A]](None)
    val slot: () => Unit = () => box.set(Some(job()))
    val isRunner = lock.synchronized {
      lines.getOrElseUpdate(projectKey, mutable.ListBuffer.empty) += ((latch, slot))
      claimLocked(projectKey)
    }
    if isRunner then drainLoop(projectKey)
    latch.await()
    box.get().getOrElse(throw new IllegalStateException("auto-merge slot drained without a result"))

  // lock held; true = caller becomes the runner for this key
  private def claimLocked(projectKey: String): Boolean =
    if running.contains(projectKey) then false
    else
      lines.get(projectKey).flatMap(_.headOption) match
        case None => false
        case Some(_) =>
          running += projectKey
          true

  private def drainLoop(projectKey: String): Unit =
    var go = true
    while go do
      val head = lock.synchronized {
        lines.get(projectKey).flatMap(_.headOption).map { entry =>
          lines(projectKey).remove(0)
          entry
        }
      }
      head match
        case Some((latch, slot)) =>
          try slot()
          catch case _: Throwable => () // slot 自吞为主；兜底绝不楔死队列
          finally latch.complete()
        case None =>
          go = lock.synchronized {
            if lines.get(projectKey).exists(_.nonEmpty) then true // 刚排队的新 job
            else
              running -= projectKey
              lines.remove(projectKey)
              false
          }
    end while
  end drainLoop

  // ── 腿① 拒绝通知（合并静默搁浅修复批 2026-10-23；同日作者裁定纠正版） ────
  //
  // 根因：refused 后拒绝只落在终态尾注 + 审计行里，无人被通知——分支搁浅
  // （#775 / c2b7661f / a65aba03 三例实锤，作者面板观感 = 「空等」）。
  // **设计裁定（2026-10-23 作者）：合并不自动化——delegate 自己负责合并，平台只
  // 把「排队 + 通知」做自动化**。故本段是纯通知面，零代跑语义：
  //   - refused ⇒ [[MergeRefusalCare]] 双通知：delegate 本人（可行动诊断：哪条
  //     路径脏、什么原因、怎么清、重试合并是 delegate 自己的责任）持久注入 +
  //     父会话知会一行。平台不登记、不代跑、不自动重试——delegate 清障后自行
  //     重试（自己的下一拍重评，本钩子在重评终态再次正常评估）。
  //   - 同脏重评不重复轰炸：correlationId 按 reason 摘要幂等（持久注入队列挡
  //     重；脏面变化 = 新诊断照发）。曾落地的「树转洁事件驱动代跑重试」腿已按
  //     作者裁定撤销（平台不做代跑合并的任何腿）。

  /**
   * 终态后结算（onCompletedHook 的 submit 返回后调用；self-guarded 由调用方兜）：
   * 仅 refused 有通知动作，其余终态（merged / already-merged / skipped / failed /
   * conflict-pending）各自的承载面（尾注、审计行、台账行）已足，零额外动作。
   */
  private def postSettle(rec: DelegateRegistry.Record, res: Result): IO[Unit] =
    res match
      case ref: Result.Refused =>
        MergeRefusalCare
          .escalate(
            MergeRefusalCare.RefusalNotice(
              delegateId = rec.id,
              parentSessionId = rec.parentSessionId,
              title = rec.title,
              branch = rec.branch.getOrElse(""),
              target = ref.target,
              baseWorkspace = rec.projectWorkspace.getOrElse(rec.cwd),
              reason = ref.reason,
              dirtyPaths = ref.dirtyPaths
            )
          )
          .handleErrorWith(e => logger.warn(s"auto-merge refusal notification failed for ${rec.id}: ${e.getMessage}"))
      case _ => IO.unit
  end postSettle

end DelegateAutoMerge
