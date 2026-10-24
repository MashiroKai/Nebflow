package nebflow.core.delegate

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import nebflow.actor.{ActorRef, AgentCommand, AgentEvent}
import nebflow.core.{AgentRuntimePort, GitWorktrees}
import nebflow.shared.NebflowLogger

import scala.util.Try

/**
 * DelegateTerminal —— delegate 终态判定钩子（R6 终态判定 + S12 合并轮；worktree
 * -judge-fifo 批 #1224 增补 §3.1/§3.3；#566 修复批 2026-10-19：blocked 判词退役）.
 *
 * 消费点 = `BackoffSupervisor` 的 `AgentEvent.Completed`（gate source="delegate"）。
 * 三个出口：
 *   - [[Outcome.Release]] —— 判词 completed（含注记形）：报 Nebula（既有父回投链，
 *     payload 携带判词行）+ MergeQueue 出队（notifyParentAndStop 的清理面统一跑）；
 *   - [[Outcome.HoldForMerge]] —— 判词 ready-to-merge：**不报 Nebula、不停
 *     actor**（合并轮需要 delegate 活着自执行合并）：任务库状态改
 *     `ready-to-merge`（completedAt = FIFO 排序锚）→ MergeQueue 入队轮转 →
 *     监督者保活（预算重挂——合并窗的 3600s 新窗口，超时走既有 Cancelled 链）。
 *
 * 判定机两层全败的回落（fail-open）：delegate_report 自申报（finish→completed /
 * 旧 blocked 申报→completed＋注记）→ 都没有 = 引擎既有完成语义（completed）。判
 * ready-to-merge 但无可合并产物（非 git 工作区 / 分支缺失 / 零领先提交——A5
 * 步1：判否腿改只读座位后全员有分支，空分支凭 commitsAhead==0 降级，防空合并
 * 轮）= 降级 completed（诚实终态）。
 *
 * 四修②（2026-10-07 修复令「判词诚实化」）：自申报也缺失时，completed 判词行
 * 诚实标注 `completed (unjudged — judge unavailable)`（ChainStore 行 source=
 * `unjudged`）——「判过」与「没判成」审计可分；确定性预检（resultText 空＋
 * 零工具调用，`sessionToolCalls = Some(0)`）⇒ 判 completed＋注记（#566：原
 * blocked），不依赖 LLM 判（source=`precheck`）。
 *
 * #566 修复批（作者令逐字：「delegate 是不应该有 blocked 型状态的，直接上报就
 * 行了，blocked 是给 agentflow 用的」）：判官/预检/漂移闸/机械验收的「没做成」
 * 判词一律折 `CompletedNote`（wire 仍编 completed）——注记随交付尾注走，delegate
 * 面零 blocked 状态；failed 保留给引擎真失败（`onTerminalCleanup` 的 failed/
 * cancelled 腿，判词行写真值）。终态自动归档豁免随之整条退役（陈旧行根因）。
 */
object DelegateTerminal:

  private val logger = NebflowLogger.forName("nebflow.core.delegate.terminal")

  /** 任务库的 ready-to-merge 状态值（SubAgentTask.status；"restarting" 先例：自定义值合法）。 */
  val ReadyToMergeStatus: String = "ready-to-merge"

  sealed trait Outcome

  object Outcome:
    /**
     * 判词完成（payload 后缀携带判词行——🔴 模型/用户可见文案，§16 待过目）。
     * `mergeRoundRefused`（steward 批 D3-③，2026-10-23）：合并轮被拒（机械验收
     * 拒绝等 CompletedNote 形态）= true ⇒ 清理面把队列条目**留队待重试**而非出队
     * （`MergeQueue.onTerminal(keepForRetry=true)`——队头被拒不再堵塞后续）。默认
     * false = 既有出队语义零变化。
     */
    final case class Release(verdictSuffix: String, mergeRoundRefused: Boolean = false) extends Outcome

    /** ready-to-merge：保活等合并轮（预算重挂由监督者执行）。 */
    case object HoldForMerge extends Outcome
  end Outcome

  /**
   * Completed 终态的判定与分流。签名全部取自监督者现场（无第二份状态源）。
   *
   * 四修②（2026-10-07 修复令「判词诚实化」）：
   *   - **确定性预检**（不依赖 LLM 判）：`sessionToolCalls = Some(0)` 且终文
   *     空/空烧 stub（[[nebflow.shared.Defaults.MaxTokensStubText]]）⇒ 判
   *     completed＋注记（#566：原 blocked——判定机两层连问都不问，空烧形态没有
   *     可判之物）；
   *   - **诚实未判**：判定机两层全败＋自申报缺失 ⇒ 仍落既有 completed 语义
   *     （fail-open 不变），但判词行诚实标注 `completed (unjudged — judge
   *     unavailable)`，ChainStore 行 source=`unjudged`（审计可分「判过」与
   *     「没判成」）。
   *
   * A5 步2（2026-10-08）：判定机之前的**确定性漂移闸**（[[driftPrecheck]]）——
   * 确证基分支漂移（支零 × 基动 × 不可归属 × 共享检出区座位）⇒ completed＋注记
   * （source=`drift`；#566：原硬阻断 Blocked 退役——证据随注记交付）；漂移信号
   * 成立但座位为 worktree ⇒ 只注记并入 gitDigest 判词输入不罚；读数不可确证 ⇒
   * fail-open 不罚只 WARN。
   */
  def onCompleted(
    resources: AgentRuntimePort,
    delegateId: String,
    parentSessionId: String,
    description: String,
    initialPrompt: String,
    resultText: String,
    childRef: ActorRef[AgentCommand],
    supervisorRef: ActorRef[AgentEvent],
    sessionToolCalls: Option[Int] = None
  ): IO[Outcome] =
    val record = DelegateRegistry.find(delegateId)
    val taskId = record.flatMap(_.taskId)
    val cwd = record.map(_.cwd).getOrElse("")
    val baseBranch = record.flatMap(_.baseBranch)
    val branch = record.flatMap(_.branch)

    val gitDigest = IO.blocking(digestFor(record))
    val mergeRound = MergeQueue.find(delegateId).exists(_.reminderSentAt.isDefined)
    val selfReport = record.flatMap(r => r.reportStatus.map(s => (s, r.reportDetail.getOrElse(""))))
    // N1 刀2 ⑸：在飞链计数（fail-soft 0——宿主/快照任何一步失败都不拖判词）。
    // 判据 = 该 delegate 作用域图内「存在非终态成员」的链数（chainIdView 值集合去重
    // 后逐链看成员终态）。
    val inFlightChains: IO[Int] =
      nebflow.core.project.DelegateFlowHosts
        .get(delegateId)
        .flatMap {
          case None => IO.pure(0)
          case Some(h) =>
            h.store.snapshot.map { s =>
              val byChain =
                s.nodes.values.groupBy(n => nebflow.core.project.FlowMapStore.chainIdView(s.nodes).get(n.id))
              byChain.collect {
                case (Some(_), members)
                    if members.exists(m => !nebflow.core.project.NodeLifecycle.Terminal.contains(m.status)) =>
                  1
              }.sum
            }
        }
        .handleErrorWith(_ => IO.pure(0))

    for
      // A5 步2 确定性漂移闸（先于判定机：确证 = 硬阻断零 LLM；不罚但有信号 =
      // 注记并入判词输入；不可确证 = fail-open 只 WARN）。#566：Penalty 判词退役
      // blocked——确证漂移 = completed＋注记（source=drift），不再是独立状态。
      drift <- driftPrecheck(record, mergeRound)
      _ = logDrift(delegateId, drift)
      driftVerdict = DriftResult.verdictOf(drift)
      digest0 <- gitDigest
      digest = DriftResult.noteOf(drift).map(n => if digest0.isEmpty then n else s"$digest0\n$n").getOrElse(digest0)
      chains <- inFlightChains
      // 四修② 确定性预检（纯函数，先于判定机——空烧形态零 LLM 消耗）。#566：
      // 预检产出 = completed＋注记（不再是 blocked 状态）。
      precheck = deterministicPrecheck(resultText, sessionToolCalls)
      judged <-
        if precheck.isDefined || driftVerdict.isDefined then IO.pure(None)
        else
          DelegateJudge.DelegateJudgePort.judgeTerminal(
            DelegateJudge.TerminalInput(
              delegateId = delegateId,
              taskBrief = initialPrompt,
              resultText = resultText,
              gitDigest = digest,
              selfReport = selfReport,
              mergeRound = mergeRound,
              inFlightChains = chains,
              sessionToolCalls = sessionToolCalls
            )
          )
      // 四修② 诚实未判标记：判定机 None＋自申报 None＋预检未中 ⇒ 判词行标注。
      // （漂移闸确证 = 确定性判过，非「没判成」——不计入 unjudged。）
      unjudged = driftVerdict.isEmpty && precheck.isEmpty && judged.isEmpty && selfReport.isEmpty
      // 判词序：漂移闸（A5 步2 确证——#566 起为注记形）＞ 确定性预检 ＞ 判定机 ＞
      // 自申报回落。#566：自申报 blocked（退役前旧值域）折 completed＋注记——
      // 「直接上报就行了」，申报内容是报告注记，不是状态。
      verdict = driftVerdict
        .orElse(precheck)
        .orElse(judged)
        .orElse(selfReport.flatMap {
          case ("merge", _) => Some(DelegateJudge.Verdict.ReadyToMerge) // ③：merge 态自申报 → 入 MergeQueue（无分支由下方降级兜住）
          case ("finish", _) => Some(DelegateJudge.Verdict.Completed)
          case ("blocked", d) =>
            Some(
              DelegateJudge.Verdict.CompletedNote(
                if d.nonEmpty then s"delegate self-reported blocked (retired state, reported as completed): $d"
                else "delegate self-reported blocked (retired state, reported as completed)"
              )
            )
          case _ => None
        })
        .getOrElse(DelegateJudge.Verdict.Completed)
      // MergeQueue 入队条件（② + A5 步1 扩展 + 引擎加固批 P2-3）：ready-to-merge
      // 且确有可合并产物。
      //   - 既有：无分支（非 git / 建败降级）⇒ 降级 completed；
      //   - 步1：判否腿也建分支+worktree（只读座位）后全员有分支 ⇒ 增「领先提交
      //     数==0」判（[[GitWorktrees.commitsAhead]]，fail-soft——git 面任何一步
      //     不可读也计 0）：零领先提交的 ready-to-merge 只会产出空合并轮（`git
      //     merge --no-ff` 对祖先分支 = Already up-to-date，无产物）⇒ 降级
      //     completed（诚实终态：无可合并产物）；
      //   - P2-3（2026-10-09 只读座位诚实化）：**判否（只读座位）终态 completed
      //     但分支上确有领先提交 ⇒ 判词翻转 ready-to-merge 自动入队**——交付绝不
      //     静默丢失；工作座位同形态 = 只注记不阻断（判词归判定机）。
      aheadOfBase <-
        if verdict == DelegateJudge.Verdict.ReadyToMerge || record.flatMap(_.worktree).isDefined then
          IO.blocking {
            record.flatMap(rec =>
              branch.flatMap(b =>
                rec.baseBranch
                  .flatMap(bb => Try(os.Path(rec.cwd)).toOption.map(ws => GitWorktrees.commitsAhead(ws, bb, b)))
              )
            )
          }
        else IO.pure(None)
      // P2-3 只读座位诚实化：判否 + worktree 座位 + 领先提交 + completed ⇒ 翻转
      // ready-to-merge（自动入队，交付不静默丢失）。非 worktree 座位不翻（就地腿
      // 归漂移闸管辖面）。
      readOnlySeatFlip = verdict == DelegateJudge.Verdict.Completed &&
        record.flatMap(_.worktree).isDefined &&
        record.flatMap(_.worktreeJudged).contains(false) &&
        aheadOfBase.exists(_ > 0)
      verdict1 = if readOnlySeatFlip then DelegateJudge.Verdict.ReadyToMerge else verdict
      finalVerdict0 = verdict1 match
        case DelegateJudge.Verdict.ReadyToMerge if branch.isEmpty || aheadOfBase.contains(0) =>
          DelegateJudge.Verdict.Completed
        case other => other
      // P1-1① 机械验收闸（2026-10-09）：合并轮 completed 判词的 Release 前置闸
      // ——确定性 git 校验（双亲 merge ＋祖先真），不满足 ⇒ 拒绝 Release 回落
      // 注记形 completed（#566：不再是 blocked 状态——拒绝证据随注记交付）。
      // 绝不凭自申报放行；读数不可确证 = fail-closed（留现场）。
      acceptance <-
        if mergeRound && finalVerdict0.isCompletedShaped then
          IO.blocking(MergeAcceptance.verifyRecord(record)).map(Some(_): Option[MergeAcceptance.Result])
        else IO.pure(None: Option[MergeAcceptance.Result])
      finalVerdict = acceptance match
        case Some(r) if !r.ok =>
          DelegateJudge.Verdict.CompletedNote(
            s"mechanical merge acceptance refused the release (the delegate/verdict claimed a completed merge): ${r.evidence}"
          )
        case _ => finalVerdict0
      // 引擎原生 delegate worktree 自动合并批（#524 · 2026-10-04 裁定引擎化）：
      // completed 判词 × 有分支锚 × 非合并轮 ⇒ 引擎侧 FIFO 自动合并（开关
      // `delegateAutoMerge.enabled` 热读；目标 = config targetBranch ∥ spawn 绑定
      // baseBranch）。尾注并入终态 payload（冲突挂起 = 文件清单候人/候智能裁决）。
      // 开关 off / 不可评（无锚）= None ⇒ 下方 P2-3 既有注记路径逐字保留。
      autoMergeSuffix <-
        if finalVerdict.isCompletedShaped && !mergeRound then
          DelegateAutoMerge
            .onCompletedHook(record)
            .handleErrorWith(e =>
              IO(
                Some(
                  s"\n\n[auto-merge] hook error: ${Option(e.getMessage).getOrElse(e.toString).take(200)} — branch left untouched."
                )
              )
            )
        else IO.pure(None)
      // P2-3 工作座位注记（不阻断）：completed 判词 + worktree 座位 + 领先提交
      // ——判词是判定机的「无可并之物」裁定，引擎只注记（非合并轮；合并轮的
      // 同形态已被上方验收闸拦下）。#566：注记形判词（CompletedNote）不重复注记
      // ——判官已留注记，引擎不再叠加。#524：钩子已产出尾注（含开关 on 的全部评估
      // 路径）时不再重复注记——审计行/台账行已由 DelegateAutoMerge 落。
      _ <- IO
        .blocking {
          if finalVerdict == DelegateJudge.Verdict.Completed && !mergeRound &&
            aheadOfBase.exists(_ > 0) && record.flatMap(_.worktree).isDefined &&
            autoMergeSuffix.isEmpty
          then noteUnmergedWork(delegateId, taskId, branch, aheadOfBase)
          else ()
        }
        .handleErrorWith(e => IO(logger.warn(s"unmerged-work note failed: ${e.getMessage}")))
      // ChainStore 行 source：drift（A5 步2 漂移闸确证——#566 起注记形）/ precheck（确定性
      // 预检——#566 起注记形）/ merge-verify（P1-1① 机械验收拒绝——#566 起注记形）/
      // unjudged（两层全败诚实态）/ judge（判定机或自申报回落——既有口径）。
      source =
        if driftVerdict.isDefined then "drift"
        else if precheck.isDefined then "precheck"
        else if acceptance.exists(!_.ok) then "merge-verify"
        else if unjudged then "unjudged"
        else "judge"
      _ = logVerdict(delegateId, taskId, finalVerdict, unjudged)
      _ <- IO
        .blocking(
          ChainStore.appendSync(
            ChainStore.keyFor(taskId, delegateId),
            "terminal-verdict",
            DelegateJudge.verdictPayload(delegateId, taskId, finalVerdict, source = source)
          )
        )
        .handleErrorWith(e => IO(logger.warn(s"terminal-verdict chain line failed: ${e.getMessage}")))
      outcome <- finalVerdict match
        case DelegateJudge.Verdict.ReadyToMerge =>
          holdForMerge(resources, record, delegateId, parentSessionId, childRef, supervisorRef)
        case DelegateJudge.Verdict.Completed =>
          // P2-4 合并后清理：机械验收通过的合并 Release 前回收现场（守卫在
          // [[PostMergeCleanup.run]]——零领先 + 祖先真 + 绝不 force；失败仅 WARN
          // 保留现场）。非合并完成绝不清理（防丢工作）。
          val cleanup =
            if mergeRound && acceptance.exists(_.ok) then PostMergeCleanup.run(record)
            else IO.pure(false)
          // #524：auto-merge 尾注（merged / conflict-pending / refused / already-
          // merged / failed 五态文案）并入判词行——delegate 终态 payload 即冲突
          // 挂起状态的承载面之一。
          cleanup *> IO.pure(Outcome.Release(verdictLineCompleted(unjudged) + autoMergeSuffix.getOrElse("")))
        case DelegateJudge.Verdict.CompletedNote(note) =>
          // #566：退役 blocked 的唯一残留出口——completed 判词＋注记交付（注记 =
          // 报告内容，不是状态）。合并轮上的注记形 = 合并未干净落地（机械验收
          // 拒绝等）⇒ 队列条目留队待重试（D3-③ 队头被拒不堵塞后续）；非合并轮
          // 注记 = 普通完成注记，语义不变。
          IO.pure(
            Outcome.Release(
              s"${verdictLineCompleted(unjudged = false)}\n[delegate-judge] note: $note",
              mergeRoundRefused = mergeRound && acceptance.exists(!_.ok)
            )
          )
    yield outcome
    end for
  end onCompleted

  /**
   * P2-3 工作座位注记行（ChainStore `unmerged-work` 行 + WARN——不阻断）：completed
   * 判词与「分支上确有领先提交」并存的诚实化注记（判词归判定机，引擎只记账）。
   */
  private def noteUnmergedWork(
    delegateId: String,
    taskId: Option[String],
    branch: Option[String],
    aheadOfBase: Option[Int]
  ): Unit =
    logger.warn(
      s"delegate $delegateId completed with $aheadOfBase commit(s) still ahead on '${branch.getOrElse("-")}' — " +
        s"judge said completed; noting only (merge queue NOT engaged)"
    )
    ChainStore.appendSync(
      ChainStore.keyFor(taskId, delegateId),
      "unmerged-work",
      io.circe.Json.obj(
        "delegateId" -> io.circe.Json.fromString(delegateId),
        "branch" -> io.circe.Json.fromString(branch.getOrElse("")),
        "commitsAhead" -> io.circe.Json.fromInt(aheadOfBase.getOrElse(0)),
        "note" -> io.circe.Json.fromString(
          "completed verdict with unmerged commits on the working branch — noted, not blocked"
        )
      )
    )

  end noteUnmergedWork

  /**
   * 四修② 确定性预检（纯函数——spec 判红锚）：全会话零工具调用（`Some(0)`；
   * `None` = 调用方未计数，预检跳过）且终文为空/空烧 stub ⇒ completed＋注记
   * （#566：原 blocked 判词退役——「判未完成」是交付注记，不是状态）。与监督者
   * 空轮判据同串（[[nebflow.shared.Defaults.MaxTokensStubText]]）。
   */
  private[delegate] def deterministicPrecheck(
    resultText: String,
    sessionToolCalls: Option[Int]
  ): Option[DelegateJudge.Verdict] =
    sessionToolCalls.filter(_ == 0).filterNot(_ => hasRealOutput(resultText)).map { _ =>
      DelegateJudge.Verdict.CompletedNote(
        "deterministic precheck: session ended with no text output and zero tool calls (empty burn) — judged incomplete without the LLM judge"
      )
    }

  /** 终文可用性：非空且不是 max_tokens 空烧 stub（空烧 stub = 无可判产出）。 */
  private[delegate] def hasRealOutput(resultText: String): Boolean =
    val t = resultText.trim
    t.nonEmpty && t != nebflow.shared.Defaults.MaxTokensStubText

  // ── A5 步2 确定性漂移闸（评审 20261008 §三候选② / §六步2 · 罚则=硬阻断，作者裁定 2026-10-08）──

  /**
   * 漂移闸只读读数。每槽 None = 该步不可确证（fail-open 通道）；`baseAdvances` /
   * `branchTips` / `isAncestorOf` 惰性（按需才花 git 读——普通完成只花 merge-base
   * ＋两次 rev-parse 三笔，漂移信号不成立时区间/ref 面/祖先面零开销）。
   */
  final case class DriftReadings(
    fork: Option[String],
    baseTip: Option[String],
    branchTip: Option[String],
    baseAdvances: (String, String) => Option[List[GitWorktrees.CommitInfo]],
    branchTips: () => Option[List[(String, String)]],
    /** (commit, tip) ⇒ commit 是否为 tip 的祖先（含相等）——搭载提交随并归属的判据；None = 不可确证。 */
    isAncestorOf: (String, String) => Option[Boolean]
  )

  /** 漂移闸结论（spec 判红锚四态；#566：Penalty 判词退役 blocked——注记形）。 */
  enum DriftResult:
    /**
     * 确证（支零 × 基动 × 不可归属 × 共享检出区座位 × 非合并轮）⇒ completed＋注记
     *  （原「硬阻断」——作者令 #566：delegate 面无 blocked 状态，证据随注记交付）。
     */
    case Penalty(reason: String)

    /** 漂移信号成立但座位为 worktree ⇒ 只注记不罚（并入 gitDigest 判词输入）。 */
    case NoteOnly(note: String)

    /** 读数不可确证 ⇒ fail-open 不罚只 WARN（🔴 禁假阻塞）。 */
    case Warn(detail: String)

    /** 无信号 / 闸盲（无锚、非 git）/ 合并轮豁免。 */
    case Clear
  end DriftResult

  object DriftResult:

    /** Penalty 形态的判词（completed＋注记——#566 起 blocked 判词退役），其余形态 None。 */
    def verdictOf(d: DriftResult): Option[DelegateJudge.Verdict] = d match
      case DriftResult.Penalty(reason) => Some(DelegateJudge.Verdict.CompletedNote(reason))
      case _ => None

    /** NoteOnly 的注记文本（并入判词输入），其余形态 None。 */
    def noteOf(d: DriftResult): Option[String] = d match
      case DriftResult.NoteOnly(note) => Some(note)
      case _ => None
  end DriftResult

  /**
   * A5 步2 确定性漂移闸（评审 §六步2；只读读数＋纯归类，零 LLM，先于判定机）。
   *
   * 判据（评审 §三候选②）：锚 `fork = merge-base(baseBranch, branch)`（≈spawn 时
   * 基 tip；A5 步1 全员有分支后免费获得——🔴 不为此扩 Record schema）；D1 基动
   * `rev-parse(baseBranch) ≠ fork`；D2 支零 `rev-parse(branch) == fork`；归因排除
   * = `fork..baseTip` 全为「合并自其他 `delegate/<id>` 分支的 merge 提交」且有
   * ChainStore `merge-queue` 账本行对应（进程内队列条目合并轮终态即出队，落地
   * 合并只能查账本；账本行缺失 = 不可确证，不罚）；合并轮豁免 = `mergeRound`
   * （本 delegate 自己的 A4 合并轮，先于一切读数豁免）。
   *
   * 座位判据：worktree=None（共享检出区/建败回落腿）⇒ 可罚；worktree 座位 ⇒
   * 自然形态机械不可直改基分支（git 拒绝同分支双检出），漂移只注记不罚；无绑定
   * /非 git ⇒ 闸盲不罚。fail-open：git 读数任何一步不可确证 ⇒ 不罚只 WARN
   * （🔴 禁假阻塞，与合并窗协议「无法确证禁假阻塞」同族姿态）。确证 ⇒
   * CompletedNote(reason 携证据三件：baseTip / fork / 不可归属提交列表——#566：
   * 原 Blocked 判词退役)，判词 payload source=`drift`。
   *
   * 🔴 覆盖面注记：外部执行器腿不在本闸覆盖面（externalSpawn 不经 prepare、无分
   * 支锚；作者裁定步3只注记，本批不实现）。
   *
   * 残余误伤类（评审 §三诚实登记、作者硬阻断裁定接受）：「共享检出区座位 × 窗口
   * 内基分支第三方直写（人/根会话/其他非 delegate 会话）」交集——git 形态学无法
   * 区分作者，误伤概率低但非零。
   */
  private def driftPrecheck(record: Option[DelegateRegistry.Record], mergeRound: Boolean): IO[DriftResult] =
    IO.blocking {
      record match
        case None => DriftResult.Clear
        case Some(rec) if mergeRound || rec.branch.isEmpty || rec.baseBranch.isEmpty => DriftResult.Clear
        case Some(rec) =>
          // 漂移读数取基工作区视角（worktree 腿 = projectWorkspace；共享座位 = cwd）
          val repo = Try(os.Path(rec.projectWorkspace.getOrElse(rec.cwd))).toOption
          repo.filter(GitWorktrees.isGitRepo) match
            case None => DriftResult.Clear // 路径不可解析 / 非 git ⇒ 闸盲
            case Some(ws) =>
              val readings = DriftReadings(
                fork = GitWorktrees.mergeBase(ws, rec.baseBranch.get, rec.branch.get),
                baseTip = GitWorktrees.resolveRef(ws, rec.baseBranch.get),
                branchTip = GitWorktrees.resolveRef(ws, rec.branch.get),
                baseAdvances = (from, to) => GitWorktrees.rangeCommits(ws, from, to),
                branchTips = () => GitWorktrees.branchTips(ws),
                isAncestorOf = (c, t) => GitWorktrees.isAncestor(ws, c, t)
              )
              classifyDrift(rec, mergeRound, readings, mergeQueueFootprint)
    }

  /**
   * 纯归类（spec 判红锚——读数显式入参，自身零 git 调用）。判据序：合并轮豁免 →
   * 锚缺失闸盲 → D1∧D2 漂移信号 → 区间两遍归因（第一遍：merge 提交——被并 tip 命中
   * delegate/ 前缀分支且有账本行 ⇒ attributable tip，命中非 delegate ref ⇒ 证据，
   * 无 ref 对应/无账本行 ⇒ 不可确证；第二遍：单亲提交——可达某 attributable tip =
   * 随并搭载归属，否则 = 直写证据）——任一环不可确证压过一切证据（fail-open）。
   */
  private[delegate] def classifyDrift(
    rec: DelegateRegistry.Record,
    mergeRound: Boolean,
    r: DriftReadings,
    queueFootprint: String => Boolean
  ): DriftResult =
    if mergeRound || rec.branch.isEmpty || rec.baseBranch.isEmpty then DriftResult.Clear
    else
      (r.fork, r.baseTip, r.branchTip) match
        case (Some(fork), Some(baseTip), Some(branchTip)) if baseTip != fork && branchTip == fork =>
          r.baseAdvances(fork, baseTip) match
            case None => DriftResult.Warn(s"base advance range $fork..$baseTip unreadable")
            case Some(Nil) =>
              DriftResult.Warn(s"base advance range $fork..$baseTip read empty (inconsistent git state)")
            case Some(commits) =>
              // 两遍归类（🔴 区间含被并分支自身的搭载提交——`fork..baseTip` 是
              // 「基分支净增」不是「merge 提交清单」）：先归类 merge 收集 attributable
              // tips，单亲提交凡可达某 attributable tip（祖先判据）即随并归属；否则
              // = 直写证据。任一环不可确证压过一切证据（fail-open，短路）。
              val merges = commits.filter(_.parents.lengthCompare(2) >= 0)
              val directs = commits.filter(_.parents.lengthCompare(2) < 0)
              val evidence = List.newBuilder[String]
              val attribTips = List.newBuilder[String]
              var unverifiable: Option[String] = None
              if merges.nonEmpty then
                r.branchTips() match
                  case None => unverifiable = Some("local branch refs unreadable")
                  case Some(tips) =>
                    merges.foreach { m =>
                      if unverifiable.isEmpty then
                        val mergedTip = m.parents(1)
                        tips.find(_._2 == mergedTip) match
                          case Some((name, _)) if name.startsWith("delegate/") =>
                            if queueFootprint(name) then attribTips += mergedTip // 归属其他 delegate 合法合并轮 ⇒ 豁免
                            else unverifiable = Some(s"merge of delegate branch '$name' has no merge-queue ledger row")
                          case Some((name, _)) =>
                            evidence += s"""${m.sha} (merge of non-delegate ref '$name': "${m.subject}")"""
                          case None =>
                            unverifiable = Some(s"merged tip $mergedTip matches no local branch ref")
                    }
              end if
              if unverifiable.isEmpty then
                directs.foreach { c =>
                  if unverifiable.isEmpty then
                    var via: Boolean = false
                    var broken: Boolean = false
                    attribTips.result().foreach { t =>
                      if !via && !broken then
                        r.isAncestorOf(c.sha, t) match
                          case Some(true) => via = true // 搭载提交：随 attributable merge 进的 delegate 自有工作
                          case Some(false) => ()
                          case None => broken = true
                    }
                    if broken then unverifiable = Some(s"ancestry check unreadable for ${c.sha}")
                    else if !via then evidence += s"""${c.sha} (direct commit "${c.subject}")"""
                }
              end if
              (unverifiable, evidence.result()) match
                case (Some(detail), _) =>
                  DriftResult.Warn(s"cannot confirm base drift attribution (fail-open, no penalty): $detail")
                case (None, Nil) => DriftResult.Clear
                case (None, list) =>
                  val facts =
                    s"base branch '${rec.baseBranch.get}' advanced from fork $fork to $baseTip; non-attributable commits: ${list.mkString(", ")}"
                  rec.worktree match
                    case Some(_) =>
                      DriftResult.NoteOnly(
                        s"[base-drift note] branch '${rec.branch.get}' is zero-ahead while $facts — note only, no penalty: " +
                          s"this delegate sits in an isolated worktree where direct base-branch writes are mechanically refused; " +
                          s"deliberate plumbing would show here for human review"
                      )
                    case None =>
                      DriftResult.Penalty(
                        s"deterministic drift gate: delegate branch '${rec.branch.get}' has zero commits ahead of its spawn fork while $facts — " +
                          s"this delegate's seat was the shared checkout (no worktree), so the unattributable advance is attributed " +
                          s"to this delegate; noted before the merge queue (no separate blocked state)"
                      )
              end match
        case (Some(_), Some(_), Some(_)) => DriftResult.Clear // D1 ∨ D2 不成立 ⇒ 无漂移信号
        case _ => DriftResult.Warn("fork/baseTip/branchTip unreadable — attribution impossible (fail-open, no penalty)")

  /**
   * ChainStore `merge-queue` 账本面按分支查询（跨键全量扫——归因行落在被合并
   * delegate 自己的键下，漂移侧无键可直达；fail-soft：listSync 自吞 IO 故障）。
   */
  private def mergeQueueFootprint(branch: String): Boolean =
    ChainStore
      .listSync()
      .exists(rec =>
        rec.entries.exists(e =>
          e.kind == MergeQueue.EntryKind && e.payload.hcursor.get[String]("branch").toOption.contains(branch)
        )
      )

  /** 漂移闸观测面（WARN 通道：罚则确证 / 注记 / fail-open 三态全过 log）。 */
  private def logDrift(delegateId: String, d: DriftResult): Unit = d match
    case DriftResult.Penalty(reason) => logger.warn(s"drift gate BLOCK $delegateId: $reason")
    case DriftResult.NoteOnly(note) => logger.warn(s"drift gate note (no penalty, worktree seat) $delegateId: $note")
    case DriftResult.Warn(detail) =>
      logger.warn(s"drift gate unverifiable (fail-open, no penalty) $delegateId: $detail")
    case DriftResult.Clear => ()

  /**
   * 外部执行器腿的漂移**注记级**覆盖（引擎加固批 2026-10-09，P1-2）：外部腿终态
   * 判定（ExternalSessionRunner.settle → ExternalJudgeHook）没有内部腿的
   * [[driftPrecheck]] 硬阻断面——本 helper 复用同一套漂移读数，把结论**降级为
   * 纯注记文本**（Penalty/NoteOnly → `[base-drift note]` 行；Warn/Clear → None）
   * 并入判定输入的 git 摘要。零罚则面（外部腿终态罚则不在本批范围——保护等级
   * 注记级，交付报告显式标注）。fail-open：git 读数任何一步不可确证 / 读数 IO
   * 失败 = None。
   */
  def externalDriftNote(rec: Option[DelegateRegistry.Record]): IO[Option[String]] =
    driftPrecheck(rec, mergeRound = false).attempt.map {
      case Right(DriftResult.Penalty(reason)) =>
        Some(
          s"[base-drift note] (annotation only — external executor leg has no drift PENALTY face this batch) $reason"
        )
      case Right(DriftResult.NoteOnly(note)) =>
        Some(s"[base-drift note] $note")
      case _ => None
    }

  /** completed 判词行（四修②：未判诚实标注——🔴 模型/用户可见文案，作者令原文措辞）。 */
  private[delegate] def verdictLineCompleted(unjudged: Boolean): String =
    if unjudged then "\n\n[delegate-judge] verdict: completed (unjudged — judge unavailable)."
    else "\n\n[delegate-judge] verdict: completed."

  /**
   * 任意终态的统一清理（BackoffSupervisor.notifyParentAndStop 内，gate
   * source="delegate"）：MergeQueue 出队（幂等）＋ failed/cancelled 的补判词行
   * （completed 腿的判词行已由 [[onCompleted]] 写，此处不重复）。
   * `keepForRetry`（steward 批 D3-③）：合并轮被拒的 Release 腿传 true——条目
   * 留队待重试而非出队（队头被拒不堵塞后续，见 [[MergeQueue.onTerminal]]）。
   */
  def onTerminalCleanup(
    resources: AgentRuntimePort,
    delegateId: String,
    parentSessionId: String,
    taskStatus: String,
    eventType: String,
    keepForRetry: Boolean = false
  ): IO[Unit] =
    val record = DelegateRegistry.find(delegateId)
    val taskId = record.flatMap(_.taskId)
    val verdictLine =
      if eventType == "completed" then IO.unit
      else
        // #566：failed/cancelled 终态判词行写真值（verdict=failed/cancelled，
        // source=engine——原 blocked 冒充退役）。真实失败归 failed（作者令）。
        IO.blocking(
          ChainStore.appendSync(
            ChainStore.keyFor(taskId, delegateId),
            "terminal-verdict",
            DelegateJudge.engineTerminalPayload(
              delegateId,
              taskId,
              status = taskStatus,
              detail = s"engine terminal: $taskStatus"
            )
          )
        ).handleErrorWith(e => IO(logger.warn(s"terminal-verdict chain line (engine) failed: ${e.getMessage}")))
    // N1 刀1（v3 §6/A3 宿主生命周期）：delegate 终态 ⇒ 作用域宿主实例灭（盘面状态存，
    // write-through 已持久——「会话终→引擎实例灭＋落盘状态存」）。幂等：外部执行器腿
    // / 未挂载 id = 零动作。ready-to-merge 停靠（HoldForMerge）不经本清理面 ⇒ 宿主照常
    // 存活等合并轮，合并轮终态仍走这里统一卸载。
    val hostUnmount =
      nebflow.core.project.DelegateFlowHosts
        .unmount(delegateId)
        .handleErrorWith(e => IO(logger.warn(s"delegate flow-host unmount failed: ${e.getMessage}")))
    // jev-plugins 批 #1226：分配插件的 MCP 引用回收（pluginMcp.acquire/release 随
    // delegate 生命周期——release 是全终态汇合点；幂等 no-op：未分配/外部执行器腿
    // 恒零持有）。
    val pluginRelease =
      resources.pluginMcp
        .release(delegateId)
        .handleErrorWith(e => IO(logger.warn(s"delegate plugin MCP release failed: ${e.getMessage}")))
    // delegate/flowmap 两层架构批（2026-10-09 · 规格 §D 自动归档 + H.1 缺口 G4 收口，
    // 引擎单点）：终态（completed/failed/cancelled）自动写 `archivedAt` 旗——项目流程
    // 图 L2 的唯一归档面从「手动」变「自动+抽屉管理」。**#566 修复批：blocked 判词
    // 豁免整条退役**——delegate 面已无 blocked 状态（判官注记只是报告内容），原豁免
    // 正是「任务面板陈旧行」的根因（豁免行滞留现役窗 × reportStatus/live 双缺席 ⇒
    // 前端回落 running 渲染 ⇒ 终态行永远「进行中」）；#566 起一切引擎终态照归。
    // completed 终态的判词读数 = ChainStore 最新 terminal-verdict 行（onCompleted 已
    // 在本序列前落行，现读即得；engine 判词行的 failed/cancelled 腿同面）。幂等：已
    // 归档零重写；查无记录（外部腿/旧盘）= 零动作。已推渠道通知（上方 verdictLine/
    // notify）等旁路面**零触碰**——旗写是清理序列最后一环，fail-soft 只 WARN（绝不
    // 拖垮终态交付）。
    val autoArchive: IO[Unit] =
      if record.isEmpty then IO.unit
      else
        IO
          .blocking(DelegateRegistry.archive(delegateId))
          .void
          .handleErrorWith(e => IO(logger.warn(s"delegate auto-archive failed: ${e.getMessage}")))
    // 台账↔面板对账批（2026-10-22 作者补充需求 · 腿 2）：终态交付自动台账记账——
    // record.taskId 定义时 append 结算 note（复活注记读任务行 revivalCount随行）。
    // fail-soft：记账失败只 WARN，绝不拖垮终态清理序列（与上方各环同款纪律）。
    val ledgerNote =
      DelegateLedgerReconcile
        .ledgerSettlementNote(resources.subAgentTaskStore, record, delegateId, taskStatus)
        .handleErrorWith(e => IO(logger.warn(s"delegate settlement note failed: ${e.getMessage}")))
    verdictLine *> ledgerNote *> hostUnmount *> pluginRelease *> MergeQueue.onTerminalIO(
      delegateId,
      keepForRetry
    ) *> autoArchive
  end onTerminalCleanup

  // ── 内部 ──────────────────────────────────────────────────────────────

  /** git 摘要：delegate 工作座（worktree 腿 = worktree）＋基工作区（存在且不同时）。 */
  private def digestFor(record: Option[DelegateRegistry.Record]): String =
    record match
      case None => ""
      case Some(rec) =>
        val parts = scala.collection.mutable.ListBuffer.empty[String]
        Try(os.Path(rec.cwd)).toOption.foreach { ws =>
          if GitWorktrees.isGitRepo(ws) then parts += GitWorktrees.statusDigest(ws, rec.baseBranch)
        }
        rec.projectWorkspace.foreach { pws =>
          Try(os.Path(pws)).toOption.foreach { ws =>
            if rec.worktree.exists(w => os.Path(w) != ws) && GitWorktrees.isGitRepo(ws) then
              parts += s"[base workspace]\n${GitWorktrees.statusDigest(ws, rec.baseBranch)}"
          }
        }
        parts.mkString("\n")

  /** 判词日志（观测面：审计可查的最小行；四修② 未判诚实标注随行）。 */
  private def logVerdict(
    delegateId: String,
    taskId: Option[String],
    v: DelegateJudge.Verdict,
    unjudged: Boolean = false
  ): Unit =
    val reason = v match
      case DelegateJudge.Verdict.CompletedNote(r) => s" — $r"
      case _ => ""
    val marker = if unjudged && v == DelegateJudge.Verdict.Completed then " (unjudged — judge unavailable)" else ""
    logger.info(
      s"delegate terminal verdict: $delegateId (task=${taskId.getOrElse("-")}) -> ${DelegateJudge.VerdictWire.encode(v)}$marker$reason"
    )

  /**
   * ready-to-merge 停靠：任务库状态 + 入队轮转。**不通知父、不停 actor**——
   * 预算重挂（合并窗新 3600s）由监督者在收到 HoldForMerge 后执行（DelegateBudget
   * ∈ agent 层，core 不 import——分层纪律）。
   */
  private def holdForMerge(
    resources: AgentRuntimePort,
    record: Option[DelegateRegistry.Record],
    delegateId: String,
    parentSessionId: String,
    childRef: ActorRef[AgentCommand],
    supervisorRef: ActorRef[AgentEvent]
  ): IO[Outcome] =
    val rec = record.getOrElse(
      DelegateRegistry.Record(
        id = delegateId,
        executor = "",
        cwd = "",
        project = None,
        taskId = None,
        parentSessionId = parentSessionId,
        title = "",
        createdAt = 0L
      )
    )
    val now = System.currentTimeMillis()
    val baseWs = rec.projectWorkspace.getOrElse(rec.cwd)
    val projectKey = Try(os.Path(baseWs)).map(GitWorktrees.projectKeyOf).getOrElse(baseWs)
    // 引擎加固批 2026-10-09（misc②）：无记录/无基分支锚时**不猜 main**——基分支
    // 只能来自 spawn 时的真实绑定（detached HEAD 等形态 baseBranch=None，猜 main
    // 会把合并轮指向错误目标）。缺锚 = 判词回落 completed＋注记（#566：原 blocked
    // 退役——「无合并目标可归」是交付注记，不是状态；报父交付，分支原样保留），
    // 并补一条注记判词行（source=engine——既有 ready-to-merge 行由本注记行收口，
    // 审计可分）。
    rec.baseBranch match
      case None =>
        val reason =
          s"ready-to-merge without a recorded base branch (spawn binding incomplete for '$delegateId') — refusing to guess 'main'; merge target unknown"
        logger.warn(s"delegate $delegateId: $reason")
        IO.blocking(
          ChainStore.appendSync(
            ChainStore.keyFor(rec.taskId, delegateId),
            "terminal-verdict",
            DelegateJudge.verdictPayload(
              delegateId,
              rec.taskId,
              DelegateJudge.Verdict.CompletedNote(reason),
              source = "engine"
            )
          )
        ).handleErrorWith(e => IO(logger.warn(s"hold-degraded verdict line failed: ${e.getMessage}"))) *>
          IO.pure(
            Outcome.Release(
              s"\n\n[delegate-judge] verdict: completed.\n[delegate-judge] note: $reason"
            )
          )
      case Some(resolvedBaseBranch) =>
        val entry = MergeQueue.Entry(
          delegateId = delegateId,
          branch = rec.branch.getOrElse(s"delegate/$delegateId"),
          baseWs = baseWs,
          baseBranch = resolvedBaseBranch,
          worktree = rec.worktree.isDefined,
          projectKey = projectKey,
          taskId = rec.taskId,
          parentSessionId = parentSessionId,
          description = rec.title,
          readyAt = now,
          childRef = childRef,
          supervisorRef = supervisorRef
        )
        for
          _ <- resources.subAgentTaskStore
            .updateStatus(parentSessionId, delegateId, ReadyToMergeStatus, completedAt = Some(now))
            .handleErrorWith(e => IO(logger.warn(s"ready-to-merge status write failed: ${e.getMessage}")))
          _ <- MergeQueue.enqueueIO(entry)
          _ = logger.info(
            s"delegate $delegateId held for merge (project=$projectKey, branch=${entry.branch}, readyAt=$now) — parent notified after the merge round"
          )
        yield Outcome.HoldForMerge
    end match
  end holdForMerge

end DelegateTerminal
