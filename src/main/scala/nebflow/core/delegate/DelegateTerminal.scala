package nebflow.core.delegate

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import nebflow.actor.{ActorRef, AgentCommand, AgentEvent}
import nebflow.core.AgentRuntimePort
import nebflow.core.GitWorktrees
import nebflow.shared.NebflowLogger

import scala.util.Try

/**
 * DelegateTerminal —— delegate 终态判定钩子（状态矩阵批 2026-11-01；开放身份语义批）.
 *
 * 消费点 = `BackoffSupervisor` 的 `AgentEvent.Completed`（gate source="delegate"）。
 * 出口：
 *   - [[Outcome.Release]] —— 判词 completed（含注记形）：报 Nebula（既有父回投链，
 *     payload 携带判词行）；
 *   - [[Outcome.WorkComplete]] —— 开放身份收口：工作轮完成，workspace 与属主身份
 *     保留（不终判、不停会话；集成走项目 merger 的授权请求，delegate 永不自并）；
 *   - [[Outcome.Standby]] —— 待命停靠（显式申报）：任务行保持 running、会话停靠
 *     Waiting，Mail 唤醒续跑；
 *   - [[Outcome.NudgeIdle]] —— 未申报空等：system reminder 提醒其申报 standby 或
 *     finish，不终判。
 *
 * 判定机两层全败的回落（fail-open）：delegate_report 自申报（finish→completed）→
 * 都没有 = 引擎既有完成语义（completed）。判词诚实化：自申报也缺失时，completed
 * 判词行标注 `completed (unjudged — judge unavailable)`（ChainStore 行 source=
 * `unjudged`）；确定性预检（resultText 空＋零工具调用，`sessionToolCalls =
 * Some(0)`）⇒ 判 completed＋注记，不依赖 LLM 判（source=`precheck`）。failed
 * 保留给引擎真失败（`onTerminalCleanup` 的 failed/cancelled 腿，判词行写真值）。
 */
object DelegateTerminal:

  private val logger = NebflowLogger.forName("nebflow.core.delegate.terminal")

  sealed trait Outcome

  object Outcome:

    /** 判词完成（payload 后缀携带判词行——🔴 模型/用户可见文案，§16 待过目）。 */
    final case class Release(verdictSuffix: String) extends Outcome

    /** A completed development round retains its workspace owner and session. */
    case object WorkComplete extends Outcome

    /**
     * 待命挂起（状态矩阵组合 **complete × standby**）：回合收尾**不终判**
     * ——无判词、无父投递；任务行保持 running、会话停靠 Waiting
     * （监督者执行，UI 显「待命」），registry 落 standbySince；Mail 到达唤醒后
     * 续跑照常走矩阵。`declared` = true 显式申报腿。
     */
    final case class Standby(detail: String, declared: Boolean) extends Outcome

    /**
     * 未申报空等提醒（矩阵组合 complete × idle（未申报）⇒ system reminder 提醒其
     * 调整状态（报 standby 或 finish），不终判——DelegateStandbyCare 的 IdleNudge
     * 型承担）：零判词、零父投递、零 terminal-verdict 行、不停 actor；任务行诚实回
     * running。`chainsInFlight`/`emptyTurn` = 提醒文案的形态锚（等上游链 / 空烧轮
     * ——链在飞也必须申报，不许裸等）。
     */
    final case class NudgeIdle(chainsInFlight: Boolean, emptyTurn: Boolean) extends Outcome
  end Outcome

  /**
   * Completed 终态的判定与分流（状态矩阵批改序）。签名全部取自监督者现场（无第二份
   * 状态源）。
   *
   * **判定序（作者令状态矩阵）**：状态槽闸（矩阵三组合分流——standby ⇒ 待命停靠 /
   * 未申报 ⇒ 提醒不终判 / finish ⇒ 唯一终判组合）＞ [[verdictChain]]
   * 全序（漂移闸＞确定性预检＞判定机＞自申报＞默认 completed——仅在 finish 申报
   * 组合时运行）。
   *
   * 四修②（2026-10-07 修复令「判词诚实化」）：
   *   - **确定性预检**（不依赖 LLM 判）：`sessionToolCalls = Some(0)` 且终文
   *     空/空烧 stub（[[nebflow.shared.Defaults.MaxTokensStubText]]）⇒ 判
   *     completed＋注记；状态矩阵批：未申报空烧被槽位闸先行接住（提醒腿），预检在
   *     finish 申报组合内继续承担诚实判；
   *   - **诚实未判**：判定机两层全败＋自申报缺失 ⇒ 仍落既有 completed 语义
   *     （fail-open 不变——矩阵批注：仅 finish 组合可达），但判词行诚实标注
   *     `completed (unjudged — judge unavailable)`，ChainStore 行 source=`unjudged`
   *     （审计可分「判过」与「没判成」）。
   *
   * A5 步2：判词链内的**确定性漂移闸**（[[driftPrecheck]]）——确证基分支漂移
   * （支零 × 基动 × 不可归属 × 共享检出区座位）⇒ completed＋注记
   * （source=`drift`）；漂移信号成立但座位为 worktree ⇒ 只注记并入 gitDigest 判词
   * 输入不罚；读数不可确证 ⇒ fail-open 不罚只 WARN。standby 申报腿在停靠前另过同一
   * 漂移闸（保护性闸不 defer——确证漂移的证据不因待命延后交付）。
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
    if record.exists(r => r.worktree.isDefined || r.mode.contains(DualModeConfig.ModeMerger)) then
      val declared = record.flatMap(_.reportStatus)
      DelegateInflightGate.check(delegateId).flatMap { inflight =>
        if declared.contains(nebflow.core.tools.DelegateReportTool.StandbyValue) then
          parkStandbyOutcome(delegateId, record.flatMap(_.taskId), record.flatMap(_.reportDetail).getOrElse(""))
            .flatTap(_ =>
              if inflight.isEmpty then IO.blocking(WorkspaceLedger.default.settle(delegateId, frozen = false))
              else IO.unit
            )
        else if declared.contains(nebflow.core.tools.DelegateReportTool.FinishValue) && inflight.isEmpty then
          val clean =
            record.flatMap(_.worktree).forall(path => nebflow.core.GitWorktrees.dirtyPaths(os.Path(path)).contains(Nil))
          if !clean then IO.pure(Outcome.NudgeIdle(chainsInFlight = false, emptyTurn = true))
          else IO.pure(Outcome.WorkComplete)
        else IO.pure(Outcome.NudgeIdle(chainsInFlight = inflight.nonEmpty, emptyTurn = resultText.trim.isEmpty))
      }
    end if
    val taskId = record.flatMap(_.taskId)
    val branch = record.flatMap(_.branch)
    val selfReport = record.flatMap(r => r.reportStatus.map(s => (s, r.reportDetail.getOrElse(""))))
    // N1 刀2 ⑸：在飞链计数（fail-soft 0——宿主/快照任何一步失败都不拖判词）。
    // 判据 = 该 delegate 作用域图内「存在非终态成员」的链数（chainIdView 值集合去重
    // 后逐链看成员终态）。兼作未申报提醒腿的文案锚（链在飞 × 未申报也被矩阵接住
    // ——提醒补报 standby，绝不裸等）。
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

    // ── 完成判定状态矩阵（作者令 · delegate 状态矩阵批）─────────────────────────
    // 引擎侧 complete（回合结束）只是**触发判定的事件**，绝不直接等于完成——
    // 先查 delegate_report 状态槽，按矩阵取交集分流（判定序：状态槽闸 ＞ 漂移闸 ＞
    // 确定性预检 ＞ 判定机 ＞ 自申报 ＞ 默认 completed；「兜底 completed」只允许在
    // finish 组合里出现）：
    //
    //   | complete | standby（申报） | 等待：跳过终判、保活、任务行显「待命」；Mail/续聊唤醒续跑 |
    //   | complete | idle（未申报）  | system reminder 提醒其调整状态（报 standby 或 finish），不终判 |
    //   | complete | finish        | **唯一允许判完成的组合**（判词链全序在此运行） |
    //
    // 两个活体旧病灶（作者 Mail 停手回执被判 completed / 停手回执自发触发合并轮）
    // 分别被 standby 组与未申报组的槽位门槛挡下。集成面 = 项目 merger 的授权请求
    // （MergeRequests/merger_report）——delegate 永不自并，未并提交不构成合并义务。
    inFlightChains.flatMap { chains0 =>
      val chain: IO[Outcome] =
        verdictChain(
          resources = resources,
          delegateId = delegateId,
          parentSessionId = parentSessionId,
          initialPrompt = initialPrompt,
          resultText = resultText,
          childRef = childRef,
          supervisorRef = supervisorRef,
          sessionToolCalls = sessionToolCalls,
          record = record,
          taskId = taskId,
          branch = branch,
          selfReport = selfReport,
          chains = chains0
        )

      // 防退化闸读数：分支领先提交数（fail-soft——无分支/缺锚/读失败 = None ⇒ 采纳）。
      // 工作分支上有未并交付提交时，停靠/提醒两腿都不接——落判词链做诚实终判
      // （completed ＋ unmerged-work 注记随行；集成走项目 merger 的授权请求，
      // 交付绝不静默丢失）。
      def unmergedAheadOf(): Option[Int] =
        record.flatMap(rec =>
          branch.flatMap(b =>
            rec.baseBranch
              .flatMap(bb => Try(os.Path(rec.cwd)).toOption.map(ws => GitWorktrees.commitsAhead(ws, bb, b)))
          )
        )

      // 显式申报腿（矩阵组合 complete × standby）：申报槽 = standby。
      // detail = 模型申报的「在等什么」（等作者令、等上游都算）。
      val standbyDeclared: Option[String] =
        selfReport
          .filter(_._1 == nebflow.core.tools.DelegateReportTool.StandbyValue)
          .map(_._2)

      val standbyDecision: IO[Option[Outcome]] =
        standbyDeclared match
          case None => IO.pure(None)
          case Some(detail) =>
            if unmergedAheadOf().exists(_ > 0) then
              IO(
                logger.warn(
                  s"delegate $delegateId: standby declared but " +
                    s"${unmergedAheadOf().getOrElse("?")} deliverable commit(s) sit unmerged on the working branch — " +
                    "standby NOT honored (the honest verdict settles first); falling through to the verdict chain"
                )
              ).as(None)
            else
              // 漂移闸确证优先：保护性确定性闸不 defer——证据随注记立即交付（回落链）。
              driftPrecheck(record).flatMap { earlyDrift =>
                logDrift(delegateId, earlyDrift)
                DriftResult.verdictOf(earlyDrift) match
                  case Some(_) => IO.pure(None)
                  case None => parkStandbyOutcome(delegateId, taskId, detail).map(Some(_))
              }

      // 未申报腿（矩阵组合 complete × idle）：system reminder 提醒其调整状态
      // （报 standby 或 finish），不终判——DelegateStandbyCare.IdleNudge 承担提醒。
      // 链在飞 × 未申报也归本腿接住（dispatcher 断供场景的引擎侧兜底）；未申报 ×
      // 工作分支有未并交付提交 = 防退化回落——落判词链诚实终判（交付绝不静默丢失）。
      val idleDecision: IO[Option[Outcome]] =
        if selfReport.isDefined || unmergedAheadOf().exists(_ > 0) then IO.pure(None)
        else
          IO.pure(
            Some(
              Outcome.NudgeIdle(
                chainsInFlight = chains0 > 0,
                emptyTurn = sessionToolCalls.contains(0) && !hasRealOutput(resultText)
              )
            )
          )

      // 就地写盘护栏（补充令第 2 条）：先检后交付——命中即回执标红（Release 腿），
      // 只读任务零误报（判据见 [[inPlaceWriteAlarm]]）。命中同时写三处：WARN 日志、
      // ChainStore `in-place-write-alarm` 行、交付正文首行（「不许只在日志里留一行」）。
      val inPlaceAlarm: IO[Option[String]] =
        inPlaceWriteAlarm(record).flatTap {
          case None => IO.unit
          case Some(a) =>
            IO.blocking(
              ChainStore.appendSync(
                ChainStore.keyFor(taskId, delegateId),
                "in-place-write-alarm",
                Json.obj(
                  "delegateId" -> Json.fromString(delegateId),
                  "taskId" -> taskId.map(Json.fromString).getOrElse(Json.Null),
                  "alarm" -> Json.fromString(a)
                )
              )
            ).handleErrorWith(e => logger.warn(s"in-place-write-alarm chain line failed: ${e.getMessage}")) *>
              IO(logger.warn(s"delegate $delegateId: in-place write alarm — $a"))
        }

      inPlaceAlarm.flatMap { alarm =>
        standbyDecision.flatMap {
          case Some(outcome) => IO.pure(withInPlaceAlarm(outcome, alarm))
          case None =>
            idleDecision.flatMap {
              case Some(outcome) => IO.pure(withInPlaceAlarm(outcome, alarm))
              case None => chain.map(o => withInPlaceAlarm(o, alarm))
            }
        }
      }
    }
  end onCompleted

  /**
   * 判词链本体（状态矩阵批拆分；仅 finish 申报组合在此运行——原 [[onCompleted]]
   * for-体迁入，全序：漂移闸＞确定性预检＞判定机＞自申报＞默认 completed。`chains`
   * 由矩阵前置段单次求值传入）。
   */
  private def verdictChain(
    resources: AgentRuntimePort,
    delegateId: String,
    parentSessionId: String,
    initialPrompt: String,
    resultText: String,
    childRef: ActorRef[AgentCommand],
    supervisorRef: ActorRef[AgentEvent],
    sessionToolCalls: Option[Int],
    record: Option[DelegateRegistry.Record],
    taskId: Option[String],
    branch: Option[String],
    selfReport: Option[(String, String)],
    chains: Int
  ): IO[Outcome] =
    val gitDigest = IO.blocking(digestFor(record))
    for
      // A5 步2 确定性漂移闸（先于判定机：确证 = 注记形；不罚但有信号 =
      // 注记并入判词输入；不可确证 = fail-open 只 WARN）。
      drift <- driftPrecheck(record)
      _ = logDrift(delegateId, drift)
      driftVerdict = DriftResult.verdictOf(drift)
      digest0 <- gitDigest
      digest = DriftResult.noteOf(drift).map(n => if digest0.isEmpty then n else s"$digest0\n$n").getOrElse(digest0)
      // 四修② 确定性预检（纯函数，先于判定机——空烧形态零 LLM 消耗）。预检产出 =
      // completed＋注记。
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
              inFlightChains = chains,
              sessionToolCalls = sessionToolCalls
            )
          )
      // 四修② 诚实未判标记：判定机 None＋自申报 None＋预检未中 ⇒ 判词行标注。
      // （漂移闸确证 = 确定性判过，非「没判成」——不计入 unjudged。）
      unjudged = driftVerdict.isEmpty && precheck.isEmpty && judged.isEmpty && selfReport.isEmpty
      // 判词序：漂移闸（确证——注记形）＞ 确定性预检 ＞ 判定机 ＞ 自申报回落。
      // 自申报 `merge` = 退役值（旧盘/旧 brief 在飞会话残留）→ 折 completed＋注记
      // （集成是项目 merger 的授权任务，不是 delegate 的状态）；`blocked` = 退役值，
      // 同折 completed＋注记。
      verdict = driftVerdict
        .orElse(precheck)
        .orElse(judged)
        .orElse(selfReport.flatMap {
          case ("finish", _) => Some(DelegateJudge.Verdict.Completed)
          case ("merge", d) =>
            Some(
              DelegateJudge.Verdict.CompletedNote(
                if d.nonEmpty then s"delegate self-reported the retired 'merge' state (reported as completed): $d"
                else "delegate self-reported the retired 'merge' state (reported as completed)"
              )
            )
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
      // 工作座位注记（不阻断）：completed 判词 + 工作分支确有领先提交——诚实化
      // 注记（判词归判定机，引擎只记账；集成走项目 merger 的授权请求，交付不会
      // 因注记丢失——分支与 workspace 原样保留）。
      aheadOfBase <-
        if record.flatMap(_.worktree).isDefined then
          IO.blocking {
            record.flatMap(rec =>
              branch.flatMap(b =>
                rec.baseBranch
                  .flatMap(bb => Try(os.Path(rec.cwd)).toOption.map(ws => GitWorktrees.commitsAhead(ws, bb, b)))
              )
            )
          }
        else IO.pure(None)
      _ <- IO
        .blocking {
          if verdict == DelegateJudge.Verdict.Completed && aheadOfBase.exists(_ > 0)
          then noteUnmergedWork(delegateId, taskId, branch, aheadOfBase)
          else ()
        }
        .handleErrorWith(e => IO(logger.warn(s"unmerged-work note failed: ${e.getMessage}")))
      // ChainStore 行 source：drift（漂移闸确证——注记形）/ precheck（确定性
      // 预检——注记形）/ unjudged（两层全败诚实态）/ judge（判定机或自申报回落
      // ——既有口径）。
      source =
        if driftVerdict.isDefined then "drift"
        else if precheck.isDefined then "precheck"
        else if unjudged then "unjudged"
        else "judge"
      // 终态判词行（审计）。
      _ <- IO
        .blocking(
          ChainStore.appendSync(
            ChainStore.keyFor(taskId, delegateId),
            "terminal-verdict",
            DelegateJudge.verdictPayload(delegateId, taskId, verdict, source = source)
          )
        )
        .handleErrorWith(e => IO(logger.warn(s"terminal-verdict chain line failed: ${e.getMessage}")))
      _ = logVerdict(delegateId, taskId, verdict, unjudged)
      outcome <- verdict match
        case DelegateJudge.Verdict.Completed =>
          IO.pure(Outcome.Release(verdictLineCompleted(unjudged)))
        case DelegateJudge.Verdict.CompletedNote(note) =>
          // 注记形 = completed 判词＋注记交付（注记 = 报告内容，不是状态）。
          IO.pure(
            Outcome.Release(
              s"${verdictLineCompleted(unjudged = false)}\n[delegate-judge] note: $note"
            )
          )
    yield outcome
    end for
  end verdictChain

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
        s"judge said completed; noting only (integration is a separate authorized merger request)"
    )
    ChainStore.appendSync(
      ChainStore.keyFor(taskId, delegateId),
      "unmerged-work",
      io.circe.Json.obj(
        "delegateId" -> io.circe.Json.fromString(delegateId),
        "branch" -> io.circe.Json.fromString(branch.getOrElse("")),
        "commitsAhead" -> io.circe.Json.fromInt(aheadOfBase.getOrElse(0)),
        "note" -> io.circe.Json.fromString(
          "completed verdict with unmerged commits on the working branch — noted, not blocked; " +
            "integration requires a separately authorized merger request"
        )
      )
    )

  end noteUnmergedWork

  /**
   * 四修② 确定性预检（纯函数——spec 判红锚）：全会话零工具调用（`Some(0)`；
   * `None` = 调用方未计数，预检跳过）且终文为空/空烧 stub ⇒ completed＋注记
   * （「判未完成」是交付注记，不是状态）。与监督者空轮判据同串
   * （[[nebflow.shared.Defaults.MaxTokensStubText]]）。
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

  // ── A5 步2 确定性漂移闸（评审 20261008 §三候选② / §六步2 · 罚则=注记形）──

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

  /** 漂移闸结论（spec 判红锚四态；注记形）。 */
  enum DriftResult:
    /** 确证（支零 × 基动 × 不可归属 × 共享检出区座位）⇒ completed＋注记。 */
    case Penalty(reason: String)

    /** 漂移信号成立但座位为 worktree ⇒ 只注记不罚（并入 gitDigest 判词输入）。 */
    case NoteOnly(note: String)

    /** 读数不可确证 ⇒ fail-open 不罚只 WARN（🔴 禁假阻塞）。 */
    case Warn(detail: String)

    /** 无信号 / 闸盲（无锚、非 git）。 */
    case Clear
  end DriftResult

  object DriftResult:

    /** Penalty 形态的判词（completed＋注记），其余形态 None。 */
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
   * 基 tip）；D1 基动 `rev-parse(baseBranch) ≠ fork`；D2 支零 `rev-parse(branch) ==
   * fork`；归因排除 = `fork..baseTip` 全为「合并自其他 `delegate/<id>` 分支的 merge
   * 提交」（delegate 分支并入基分支只能由项目 merger 的授权请求产生——结构性判据，
   * 无需账本行；命中非 delegate ref ⇒ 证据，无 ref 对应 ⇒ 不可确证）；单亲提交可达
   * 某 attributable tip = 随并搭载归属，否则 = 直写证据。任一环不可确证压过一切证据
   * （fail-open）。
   *
   * 座位判据：worktree=None（共享检出区/建败回落腿）⇒ 可罚；worktree 座位 ⇒
   * 自然形态机械不可直改基分支（git 拒绝同分支双检出），漂移只注记不罚；无绑定
   * /非 git ⇒ 闸盲不罚。fail-open：git 读数任何一步不可确证 ⇒ 不罚只 WARN
   * （🔴 禁假阻塞）。确证 ⇒ CompletedNote(reason 携证据三件：baseTip / fork /
   * 不可归属提交列表)，判词 payload source=`drift`。
   *
   * 🔴 覆盖面注记：外部执行器腿不在本闸覆盖面（externalSpawn 不经 prepare、无分
   * 支锚；外部腿只注记，见 [[externalDriftNote]]）。
   *
   * 残余误伤类（评审 §三诚实登记）：「共享检出区座位 × 窗口内基分支第三方直写
   * （人/根会话/其他非 delegate 会话）」交集——git 形态学无法区分作者，误伤概率低
   * 但非零。
   */
  private def driftPrecheck(record: Option[DelegateRegistry.Record]): IO[DriftResult] =
    IO.blocking {
      record match
        case None => DriftResult.Clear
        case Some(rec) if rec.branch.isEmpty || rec.baseBranch.isEmpty => DriftResult.Clear
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
              classifyDrift(rec, readings)
    }

  /**
   * 纯归类（spec 判红锚——读数显式入参，自身零 git 调用）。判据序：锚缺失闸盲 →
   * D1∧D2 漂移信号 → 区间两遍归因（第一遍：merge 提交——被并 tip 命中 `delegate/`
   * 前缀分支 ⇒ attributable tip（delegate 分支的落地只能经项目 merger 的授权请求），
   * 命中非 delegate ref ⇒ 证据，无 ref 对应 ⇒ 不可确证；第二遍：单亲提交——可达某
   * attributable tip = 随并搭载归属，否则 = 直写证据）——任一环不可确证压过一切证据
   * （fail-open，短路）。
   */
  private[delegate] def classifyDrift(
    rec: DelegateRegistry.Record,
    r: DriftReadings
  ): DriftResult =
    if rec.branch.isEmpty || rec.baseBranch.isEmpty then DriftResult.Clear
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
                            attribTips += mergedTip // 归属其他 delegate 的合法落地（merger 授权请求）⇒ 豁免
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
                          s"to this delegate; noted as a delivery annotation (no separate blocked state)"
                      )
              end match
        case (Some(_), Some(_), Some(_)) => DriftResult.Clear // D1 ∨ D2 不成立 ⇒ 无漂移信号
        case _ => DriftResult.Warn("fork/baseTip/branchTip unreadable — attribution impossible (fail-open, no penalty)")

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
   * [[driftPrecheck]] 覆盖——本 helper 复用同一套漂移读数，把结论**降级为
   * 纯注记文本**（Penalty/NoteOnly → `[base-drift note]` 行；Warn/Clear → None）
   * 并入判定输入的 git 摘要。零罚则面（保护等级注记级，交付报告显式标注）。
   * fail-open：git 读数任何一步不可确证 / 读数 IO 失败 = None。
   */
  def externalDriftNote(rec: Option[DelegateRegistry.Record]): IO[Option[String]] =
    driftPrecheck(rec).attempt.map {
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
   * source="delegate"）：failed/cancelled 的补判词行（completed 腿的判词行已由
   * [[onCompleted]] 写，此处不重复）。
   */
  def onTerminalCleanup(
    resources: AgentRuntimePort,
    delegateId: String,
    parentSessionId: String,
    taskStatus: String,
    eventType: String
  ): IO[Unit] =
    val record = DelegateRegistry.find(delegateId)
    val taskId = record.flatMap(_.taskId)
    val verdictLine =
      if eventType == "completed" then IO.unit
      else
        // failed/cancelled 终态判词行写真值（verdict=failed/cancelled，
        // source=engine）。真实失败归 failed（作者令）。
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
    // / 未挂载 id = 零动作。
    // 双模式批（2026-10-28 · 设计 §6 第 4 步）：dispatcher 腿追加速度摘要先行——
    // 宿主仍在册时收口三段（sweep 出库 + chain-archived 审计落定；R3 批增：在飞链
    // 台账 cancelled 处置 + chain-cancelled 审计——取消链走同一收口；台账收敛拍），
    // 随后才 unmount；unmount 之后 fork 宽限删除 fiber（graceMs 后双闸校验：未重挂 ∧
    // 已归档 ⇒ 删作用域目录）。kernel/闸关 = 本段全部分叉失效（现状逐字节）。
    // R3 批（2026-10-29 · 审计二 P3/P10）：终态屏障单点不变——completed/failed/
    // cancelled 三种终态都经本序列，取消链（NodeCancel 链级腿 / 会话终止遗留在飞链）
    // 与自然完成链收敛到同一条「摘要→台账→unmount→宽限删」路径。
    val dispatcherLifecycle = DelegateFlowMapLifecycle.applies(record)
    val summaryFlush = if dispatcherLifecycle then DelegateFlowMapLifecycle.flushBeforeUnmount(delegateId) else IO.unit
    val hostUnmount =
      (summaryFlush *> nebflow.core.project.DelegateFlowHosts
        .unmount(delegateId))
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
    // 图 L2 的唯一归档面从「手动」变「自动+抽屉管理」。delegate 面已无 blocked 状态
    // （判官注记只是报告内容）。completed 终态的判词读数 = ChainStore 最新
    // terminal-verdict 行（onCompleted 已在本序列前落行，现读即得；engine 判词行的
    // failed/cancelled 腿同面）。幂等：已归档零重写；查无记录（外部腿/旧盘）= 零动作。
    // 已推渠道通知（上方 verdictLine/notify）等旁路面**零触碰**——旗写是清理序列最后
    // 一环，fail-soft 只 WARN（绝不拖垮终态交付）。
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
    val graceRemoval: IO[Unit] =
      if dispatcherLifecycle then
        DelegateFlowMapLifecycle
          .scheduleScopeRemoval(delegateId)
          .start
          .as(())
          .handleErrorWith(e =>
            IO(logger.warn(s"[flowmap-lifecycle] grace removal fiber failed to start: ${e.getMessage}"))
          )
      else IO.unit
    // 案A 批 2026-10-31（状态矩阵批收编）：终态 ⇒ 待命槽清算（standbySince +
    // 申报槽——终态会话不再待命；caretaker 读不到 Waiting 活体亦自取消，双保险）。
    // 幂等：非待命记录 = 零写。fail-soft 只 WARN，绝不拖垮终态清理序列。
    val standbyClear: IO[Unit] =
      IO.blocking(DelegateRegistry.clearStandby(delegateId))
        .attempt
        .flatMap {
          case Left(e) => IO(logger.warn(s"standby clear failed for $delegateId: ${e.getMessage}"))
          case Right(_) => IO.unit
        }
    verdictLine *> ledgerNote *> hostUnmount *> pluginRelease *> standbyClear *> autoArchive *> graceRemoval
  end onTerminalCleanup

  // ── 就地写盘护栏（delegate-param-decisions 批 2026-11-04 · 作者补充令第 2 条）──

  /** 回执标红行的前缀（机械判据锚：spec 与运维 grep 共用）。 */
  val InPlaceWriteAlarmPrefix: String =
    "🔴 [IN-PLACE WRITE ALARM] 本任务未分配 worktree 但改动了项目工作区，路径清单如下 / this task was dispatched WITHOUT a worktree (the read-only channel) yet it changed the project workspace:"

  /**
   * 「没分配 tree 却动了工作区」的机械护栏（作者补充令：「不填 worktree 的任务是
   * 适合于只读型任务 工作还是要默认创建worktree ⇒ 必须有不依赖人记性的东西能在
   * 没树却动手时叫出来」）。
   *
   * 判据（全部机械读数，对比 spawn 时快照 [[DelegateRegistry.Record.seatSnapshotSha]] /
   * `seatSnapshotDirty`）：
   *   - **新增脏路径**：`git status --porcelain` 现读 − spawn 快照（集合差）；
   *   - **产生了提交**：工作区 HEAD 与 spawn 快照不同；
   *   - 任一命中 ⇒ 返回**标红回执行**（含完整路径清单）；都不中 ⇒ `None`（只读任务
   *     不误报）。
   *
   * 判据适用面：只有「无 worktree 绑定」且「有快照」（= 走的是零分配就地腿）的
   * 记录才检——有树的 delegate 在自己的树里写是本分，不是越界。
   */
  private[delegate] def inPlaceWriteAlarm(record: Option[DelegateRegistry.Record]): IO[Option[String]] =
    record match
      case None => IO.pure(None)
      case Some(rec) if rec.worktree.isDefined => IO.pure(None)
      case Some(rec) if rec.seatSnapshotSha.isEmpty && rec.seatSnapshotDirty.isEmpty => IO.pure(None)
      case Some(rec) =>
        IO.blocking {
          val ws = Try(os.Path(rec.projectWorkspace.getOrElse(rec.cwd))).toOption
          ws.flatMap { p =>
            if !GitWorktrees.isGitRepo(p) then None
            else
              val before = rec.seatSnapshotDirty.getOrElse(Nil).map(_.trim).filter(_.nonEmpty).toSet
              val now = GitWorktrees.dirtyPaths(p).getOrElse(Nil).map(_.trim).filter(_.nonEmpty)
              val added = now.filterNot(before.contains)
              val headMoved = rec.seatSnapshotSha.exists(sha => GitWorktrees.headSha(p).exists(_ != sha))
              val branchAhead = rec.branch
                .flatMap(b => rec.baseBranch.map(bb => GitWorktrees.commitsAhead(p, bb, b)))
                .exists(_ > 0)
              if added.isEmpty && !headMoved && !branchAhead then None
              else
                val facts = List(
                  Option.when(added.nonEmpty)(s"${added.size} new path(s): ${added.take(20).mkString(", ")}${
                      if added.size > 20 then " …" else ""
                    }"),
                  Option.when(headMoved)(
                    s"HEAD moved (was ${rec.seatSnapshotSha.getOrElse("?").take(9)}, now ${GitWorktrees.headSha(p).getOrElse("?").take(9)}) — commits were created"
                  ),
                  Option.when(branchAhead)(
                    s"branch ${rec.branch.getOrElse("?")} is ahead of ${rec.baseBranch.getOrElse("?")}"
                  )
                ).flatten
                Some(s"$InPlaceWriteAlarmPrefix\n  - ${facts.mkString("\n  - ")}")
          }
        }.handleErrorWith(_ => IO.pure(None))

  /**
   * 把标红行缀进交付正文（Release 腿单点）。纯函数——spec 判红锚：命中时**正文
   * 首行**就是标红行（不许埋在尾部），无命中时正文逐字节不变。
   */
  private[delegate] def withInPlaceAlarm(outcome: Outcome, alarm: Option[String]): Outcome =
    (outcome, alarm) match
      case (Outcome.Release(text), Some(a)) => Outcome.Release(s"$a\n\n$text")
      case _ => outcome

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
   * 待命采纳落点（standby 组）：parkStandby 落停靠时刻 → ChainStore `standby`
   * 审计行（非终态——terminal-verdict 行只在真终态写）→ Outcome.Standby 交监督者
   * 停靠（registry Waiting + WS「待命」帧 + caretaker 武装）。
   */
  private def parkStandbyOutcome(
    delegateId: String,
    taskId: Option[String],
    detail: String
  ): IO[Outcome] =
    val now = System.currentTimeMillis()
    IO.blocking(DelegateRegistry.parkStandby(delegateId))
      .attempt
      .flatMap {
        case Left(e) =>
          IO(
            logger.warn(
              s"delegate $delegateId: parkStandby failed (standby proceeds without the dock timestamp): ${e.getMessage}"
            )
          )
        case Right(_) => IO.unit
      } *>
      IO
        .blocking(
          ChainStore.appendSync(
            ChainStore.keyFor(taskId, delegateId),
            "standby",
            Json.obj(
              "delegateId" -> Json.fromString(delegateId),
              "taskId" -> taskId.map(Json.fromString).getOrElse(Json.Null),
              "detail" -> Json.fromString(detail),
              "declared" -> Json.fromBoolean(true),
              "at" -> Json.fromLong(now)
            )
          )
        )
        .handleErrorWith(e => IO(logger.warn(s"standby chain line failed for $delegateId: ${e.getMessage}"))) *>
      IO(
        logger.info(
          s"delegate standby: $delegateId (task=${taskId.getOrElse("-")}) parked — self-declared (matrix complete×standby): ${detail.take(200)}"
        )
      ).as(Outcome.Standby(detail, declared = true))

  end parkStandbyOutcome

end DelegateTerminal
