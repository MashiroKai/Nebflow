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
 * DelegateTerminal —— delegate 终态判定钩子（R6 终态三态 + S12 合并轮；worktree
 * -judge-fifo 批 #1224 增补 §3.1/§3.3）.
 *
 * 消费点 = `BackoffSupervisor` 的 `AgentEvent.Completed`（gate source="delegate"）。
 * 三个出口：
 *   - [[Outcome.Release]] —— 判词 completed / blocked：报 Nebula（既有父回投链，
 *     payload 携带判词行）+ MergeQueue 出队（notifyParentAndStop 的清理面统一跑）；
 *   - [[Outcome.HoldForMerge]] —— 判词 ready-to-merge：**不报 Nebula、不停
 *     actor**（合并轮需要 delegate 活着自执行合并）：任务库状态改
 *     `ready-to-merge`（completedAt = FIFO 排序锚）→ MergeQueue 入队轮转 →
 *     监督者保活（预算重挂——合并窗的 3600s 新窗口，超时走既有 Cancelled 链）。
 *
 * 判定机两层全败的回落（fail-open）：delegate_report 自申报（finish→completed /
 * blocked→blocked(detail)）→ 都没有 = 引擎既有完成语义（completed）。判
 * ready-to-merge 但无分支可并（非 git 工作区 / 分支缺失）= 降级 completed（无可
 * 合并产物，诚实终态）。
 *
 * 四修②（2026-10-07 修复令「判词诚实化」）：自申报也缺失时，completed 判词行
 * 诚实标注 `completed (unjudged — judge unavailable)`（ChainStore 行 source=
 * `unjudged`）——「判过」与「没判成」审计可分；确定性预检（resultText 空＋
 * 零工具调用，`sessionToolCalls = Some(0)`）⇒ 直接判 blocked，不依赖 LLM 判
 * （source=`precheck`）。
 */
object DelegateTerminal:

  private val logger = NebflowLogger.forName("nebflow.core.delegate.terminal")

  /** 任务库的 ready-to-merge 状态值（SubAgentTask.status；"restarting" 先例：自定义值合法）。 */
  val ReadyToMergeStatus: String = "ready-to-merge"

  sealed trait Outcome
  object Outcome:
    /** 判词完成（payload 后缀携带判词行——🔴 模型/用户可见文案，§16 待过目）。 */
    final case class Release(verdictSuffix: String) extends Outcome
    /** ready-to-merge：保活等合并轮（预算重挂由监督者执行）。 */
    case object HoldForMerge extends Outcome
  end Outcome

  /**
   * Completed 终态的判定与分流。签名全部取自监督者现场（无第二份状态源）。
   *
   * 四修②（2026-10-07 修复令「判词诚实化」）：
   *   - **确定性预检**（不依赖 LLM 判）：`sessionToolCalls = Some(0)` 且终文
   *     空/空烧 stub（[[nebflow.shared.Defaults.MaxTokensStubText]]）⇒ 直接判
   *     blocked（判定机两层连问都不问——空烧形态没有可判之物）；
   *   - **诚实未判**：判定机两层全败＋自申报缺失 ⇒ 仍落既有 completed 语义
   *     （fail-open 不变），但判词行诚实标注 `completed (unjudged — judge
   *     unavailable)`，ChainStore 行 source=`unjudged`（审计可分「判过」与
   *     「没判成」）。
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
              val byChain = s.nodes.values.groupBy(n => nebflow.core.project.FlowMapStore.chainIdView(s.nodes).get(n.id))
              byChain.collect {
                case (Some(_), members) if members.exists(m => !nebflow.core.project.NodeLifecycle.Terminal.contains(m.status)) => 1
              }.sum
            }
        }
        .handleErrorWith(_ => IO.pure(0))

    for
      digest <- gitDigest
      chains <- inFlightChains
      // 四修② 确定性预检（纯函数，先于判定机——空烧形态零 LLM 消耗）。
      precheck = deterministicPrecheck(resultText, sessionToolCalls)
      judged <-
        if precheck.isDefined then IO.pure(None)
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
      unjudged = precheck.isEmpty && judged.isEmpty && selfReport.isEmpty
      verdict = precheck
        .orElse(judged)
        .orElse(selfReport.flatMap {
          case ("merge", _)   => Some(DelegateJudge.Verdict.ReadyToMerge) // ③：merge 态自申报 → 入 MergeQueue（无分支由下方降级兜住）
          case ("finish", _)  => Some(DelegateJudge.Verdict.Completed)
          case ("blocked", d) => Some(DelegateJudge.Verdict.Blocked(if d.nonEmpty then d else "delegate reported blocked"))
          case _              => None
        })
        .getOrElse(DelegateJudge.Verdict.Completed)
      // MergeQueue 入队条件（②）：ready-to-merge 且确有工作分支（只有会改文件的
      // delegate 才会有）。无分支（纯读腿/非 git）的 ready-to-merge = 降级 completed
      // ——纯读不入队、完成即 finish（诚实终态：无可合并产物）。
      finalVerdict = verdict match
        case DelegateJudge.Verdict.ReadyToMerge if branch.isEmpty => DelegateJudge.Verdict.Completed
        case other => other
      // ChainStore 行 source：precheck（确定性预检）/ unjudged（两层全败诚实态）
      // / judge（判定机或自申报回落——既有口径）。
      source = if precheck.isDefined then "precheck" else if unjudged then "unjudged" else "judge"
      _ = logVerdict(delegateId, taskId, finalVerdict, unjudged)
      _ <- IO.blocking(
        ChainStore.appendSync(
          ChainStore.keyFor(taskId, delegateId),
          "terminal-verdict",
          DelegateJudge.verdictPayload(delegateId, taskId, finalVerdict, source = source)
        )
      ).handleErrorWith(e => IO(logger.warn(s"terminal-verdict chain line failed: ${e.getMessage}")))
      outcome <- finalVerdict match
        case DelegateJudge.Verdict.ReadyToMerge =>
          holdForMerge(resources, record, delegateId, parentSessionId, childRef, supervisorRef)
        case DelegateJudge.Verdict.Completed =>
          IO.pure(Outcome.Release(verdictLineCompleted(unjudged)))
        case DelegateJudge.Verdict.Blocked(reason) =>
          IO.pure(Outcome.Release(s"\n\n[delegate-judge] verdict: blocked — $reason"))
    yield outcome
  end onCompleted

  /**
   * 四修② 确定性预检（纯函数——spec 判红锚）：全会话零工具调用（`Some(0)`；
   * `None` = 调用方未计数，预检跳过）且终文为空/空烧 stub ⇒ blocked。与监督者
   * 空轮判据同串（[[nebflow.shared.Defaults.MaxTokensStubText]]）。
   */
  private[delegate] def deterministicPrecheck(resultText: String, sessionToolCalls: Option[Int]): Option[DelegateJudge.Verdict] =
    sessionToolCalls.filter(_ == 0).filterNot(_ => hasRealOutput(resultText)).map { _ =>
      DelegateJudge.Verdict.Blocked(
        "deterministic precheck: session ended with no text output and zero tool calls (empty burn) — judged incomplete without the LLM judge"
      )
    }

  /** 终文可用性：非空且不是 max_tokens 空烧 stub（空烧 stub = 无可判产出）。 */
  private[delegate] def hasRealOutput(resultText: String): Boolean =
    val t = resultText.trim
    t.nonEmpty && t != nebflow.shared.Defaults.MaxTokensStubText

  /** completed 判词行（四修②：未判诚实标注——🔴 模型/用户可见文案，作者令原文措辞）。 */
  private[delegate] def verdictLineCompleted(unjudged: Boolean): String =
    if unjudged then "\n\n[delegate-judge] verdict: completed (unjudged — judge unavailable)."
    else "\n\n[delegate-judge] verdict: completed."

  /**
   * 任意终态的统一清理（BackoffSupervisor.notifyParentAndStop 内，gate
   * source="delegate"）：MergeQueue 出队（幂等）＋ failed/cancelled 的补判词行
   * （completed 腿的判词行已由 [[onCompleted]] 写，此处不重复）。
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
        IO.blocking(
          ChainStore.appendSync(
            ChainStore.keyFor(taskId, delegateId),
            "terminal-verdict",
            DelegateJudge.verdictPayload(
              delegateId,
              taskId,
              DelegateJudge.Verdict.Blocked(s"engine terminal: $taskStatus"),
              source = "engine"
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
    verdictLine *> hostUnmount *> pluginRelease *> MergeQueue.onTerminalIO(delegateId)

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
  private def logVerdict(delegateId: String, taskId: Option[String], v: DelegateJudge.Verdict, unjudged: Boolean = false): Unit =
    val reason = v match
      case DelegateJudge.Verdict.Blocked(r) => s" — $r"
      case _                                => ""
    val marker = if unjudged && v == DelegateJudge.Verdict.Completed then " (unjudged — judge unavailable)" else ""
    logger.info(s"delegate terminal verdict: $delegateId (task=${taskId.getOrElse("-")}) -> ${DelegateJudge.VerdictWire.encode(v)}$marker$reason")

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
    val rec = record.getOrElse(DelegateRegistry.Record(
      id = delegateId, executor = "", cwd = "", project = None, taskId = None,
      parentSessionId = parentSessionId, title = "", createdAt = 0L
    ))
    val now = System.currentTimeMillis()
    val baseWs = rec.projectWorkspace.getOrElse(rec.cwd)
    val projectKey = Try(os.Path(baseWs)).map(GitWorktrees.projectKeyOf).getOrElse(baseWs)
    val entry = MergeQueue.Entry(
      delegateId = delegateId,
      branch = rec.branch.getOrElse(s"delegate/$delegateId"),
      baseWs = baseWs,
      baseBranch = rec.baseBranch.getOrElse("main"),
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

end DelegateTerminal
