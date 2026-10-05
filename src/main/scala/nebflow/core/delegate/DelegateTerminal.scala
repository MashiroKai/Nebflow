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
   */
  def onCompleted(
      resources: AgentRuntimePort,
      delegateId: String,
      parentSessionId: String,
      description: String,
      initialPrompt: String,
      resultText: String,
      childRef: ActorRef[AgentCommand],
      supervisorRef: ActorRef[AgentEvent]
  ): IO[Outcome] =
    val record = DelegateRegistry.find(delegateId)
    val taskId = record.flatMap(_.taskId)
    val cwd = record.map(_.cwd).getOrElse("")
    val baseBranch = record.flatMap(_.baseBranch)
    val branch = record.flatMap(_.branch)

    val gitDigest = IO.blocking(digestFor(record))
    val mergeRound = MergeQueue.find(delegateId).exists(_.reminderSentAt.isDefined)
    val selfReport = record.flatMap(r => r.reportStatus.map(s => (s, r.reportDetail.getOrElse(""))))

    for
      digest <- gitDigest
      judged <- DelegateJudge.DelegateJudgePort.judgeTerminal(
        DelegateJudge.TerminalInput(
          delegateId = delegateId,
          taskBrief = initialPrompt,
          resultText = resultText,
          gitDigest = digest,
          selfReport = selfReport,
          mergeRound = mergeRound
        )
      )
      verdict = judged
        .orElse(selfReport.flatMap {
          case ("finish", _)  => Some(DelegateJudge.Verdict.Completed)
          case ("blocked", d) => Some(DelegateJudge.Verdict.Blocked(if d.nonEmpty then d else "delegate reported blocked"))
          case _              => None
        })
        .getOrElse(DelegateJudge.Verdict.Completed)
      // 无分支可并的 ready-to-merge = 降级 completed（诚实终态：无可合并产物）。
      finalVerdict = verdict match
        case DelegateJudge.Verdict.ReadyToMerge if branch.isEmpty => DelegateJudge.Verdict.Completed
        case other => other
      _ = logVerdict(delegateId, taskId, finalVerdict)
      _ <- IO.blocking(
        ChainStore.appendSync(
          ChainStore.keyFor(taskId, delegateId),
          "terminal-verdict",
          DelegateJudge.verdictPayload(delegateId, taskId, finalVerdict, source = "judge")
        )
      ).handleErrorWith(e => IO(logger.warn(s"terminal-verdict chain line failed: ${e.getMessage}")))
      outcome <- finalVerdict match
        case DelegateJudge.Verdict.ReadyToMerge =>
          holdForMerge(resources, record, delegateId, parentSessionId, childRef, supervisorRef)
        case DelegateJudge.Verdict.Completed =>
          IO.pure(Outcome.Release("\n\n[delegate-judge] verdict: completed."))
        case DelegateJudge.Verdict.Blocked(reason) =>
          IO.pure(Outcome.Release(s"\n\n[delegate-judge] verdict: blocked — $reason"))
    yield outcome
  end onCompleted

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
    verdictLine *> MergeQueue.onTerminalIO(delegateId)

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

  /** 判词日志（观测面：审计可查的最小行）。 */
  private def logVerdict(delegateId: String, taskId: Option[String], v: DelegateJudge.Verdict): Unit =
    val reason = v match
      case DelegateJudge.Verdict.Blocked(r) => s" — $r"
      case _                                => ""
    logger.info(s"delegate terminal verdict: $delegateId (task=${taskId.getOrElse("-")}) -> ${DelegateJudge.VerdictWire.encode(v)}$reason")

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
