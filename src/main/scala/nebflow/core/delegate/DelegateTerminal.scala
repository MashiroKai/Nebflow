package nebflow.core.delegate

import cats.effect.IO
import cats.syntax.all.*
import nebflow.actor.{ActorRef, AgentCommand, AgentEvent}
import nebflow.core.{AgentRuntimePort, GitWorktrees}
import nebflow.shared.NebflowLogger

import scala.util.Try

/**
 * DelegateTerminal —— delegate 终态判定钩子（状态矩阵批 2026-11-01；开放身份语义批）.
 *
 * 消费点 = `BackoffSupervisor` 的 `AgentEvent.Completed`（gate source="delegate"）。
 * 出口：
 *   - [[Outcome.Release]] —— 判词 completed（含注记形）：报 Nebula（既有父回投链，
 *     payload 携带判词行）；
 *   - [[Outcome.Standby]] —— 待命停靠（显式申报）：任务行保持 running、会话停靠
 *     Waiting，Mail 唤醒续跑；
 *   - [[Outcome.NudgeIdle]] —— 未申报空等：system reminder 提醒其申报 standby 或
 *     finish，不终判。
 *
 * 交付语义：末条 assistant 文本 = 交付本体；空文/空烧的诚实化（silent-completion
 * WARN、判词注记）在监督者侧。failed 保留给引擎真失败（[[onTerminalCleanup]] 的
 * failed/cancelled 腿，判词行写真值）。
 */
object DelegateTerminal:

  private val logger = NebflowLogger.forName("nebflow.core.delegate.terminal")

  sealed trait Outcome

  object Outcome:

    /** Completion payload is returned directly through the parent delivery path. */
    final case class Release(verdictSuffix: String) extends Outcome

    // Kept temporarily as source compatibility for legacy specs; no production path emits these.
    case object WorkComplete extends Outcome
    final case class Standby(detail: String, declared: Boolean) extends Outcome
    final case class NudgeIdle(chainsInFlight: Boolean, emptyTurn: Boolean) extends Outcome

  end Outcome

  /** Completed 终态的判定与分流。签名全部取自监督者现场（无第二份状态源）。 */
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
    val body = if resultText.trim.nonEmpty then resultText.trim else "(no text output)"
    IO.pure(Outcome.Release(s"\"$description\":\n$body"))
  end onCompleted

  // ── 基线漂移注记（annotation only）────────────────────────────────────

  /**
   * Base-drift annotation for a record: the working branch is zero-ahead while
   * the recorded base branch advanced past the spawn fork with commits that
   * cannot be attributed to other delegates' merges. This is an honest note
   * for the final report — never a penalty, never a blocker; the seat
   * baseline lives in the round ledger and the diff face already annotates
   * beyond-baseline writes. Any unreadable reading yields None (fail-open,
   * never fabricated).
   */
  def externalDriftNote(rec: Option[DelegateRegistry.Record]): IO[Option[String]] =
    IO.blocking(driftNoteOf(rec)).handleError(_ => None)

  /** Pure drift reading (None on any unreadable step). */
  private def driftNoteOf(record: Option[DelegateRegistry.Record]): Option[String] =
    record match
      case None => None
      case Some(rec) if rec.branch.isEmpty || rec.baseBranch.isEmpty => None
      case Some(rec) =>
        val repo = Try(os.Path(rec.projectWorkspace.getOrElse(rec.cwd))).toOption
        repo
          .filter(GitWorktrees.isGitRepo)
          .flatMap { ws =>
            val branch = rec.branch.get
            val base = rec.baseBranch.get
            for
              fork <- GitWorktrees.mergeBase(ws, base, branch)
              baseTip <- GitWorktrees.resolveRef(ws, base)
              branchTip <- GitWorktrees.resolveRef(ws, branch)
              if baseTip != fork && branchTip == fork
              commits <- GitWorktrees.rangeCommits(ws, fork, baseTip)
              if commits.nonEmpty
              tips <- GitWorktrees.branchTips(ws)
            yield
              // Attribution: merge commits whose merged side tips at another
              // delegate's branch are legal landings; everything else in the
              // advance range is a direct write onto the base branch.
              val merges = commits.filter(_.parents.lengthCompare(2) >= 0)
              val attributable = merges
                .flatMap(_.parents.drop(1).toSet)
                .flatMap { tip =>
                  tips.collectFirst { case (name, sha) if sha == tip && name.startsWith("delegate/") => tip }
                }
                .toSet
              val unattributable = commits
                .filterNot(c => (c.parents.lengthCompare(2) >= 0 && c.parents.drop(1).exists(attributable.contains)))
              if unattributable.isEmpty then None
              else
                Some(
                  s"[base-drift note] branch '$branch' is zero-ahead while base '$base' advanced from $fork to " +
                    s"$baseTip with ${unattributable.size} commit(s) not attributable to delegate merges " +
                    s"(${unattributable.map(_.sha.take(9)).take(5).mkString(", ")}${
                        if unattributable.size > 5 then " …"
                        else ""
                      }) — noted for review, no penalty"
                )
            end for
          }
          .flatten

  // ── 未落地提交注记（settlement annotation）────────────────────────────

  /**
   * Settlement annotation for a completed delegate whose recorded seat still
   * carries commits ahead of its recorded base (legacy records that bound a
   * branch; new records carry no binding and read zero). Honest note only —
   * landing is the author's decision; nothing is merged, cleaned or moved.
   */
  def noteUnmergedWork(delegateId: String, record: Option[DelegateRegistry.Record]): Unit =
    record match
      case Some(rec) =>
        (rec.branch, rec.baseBranch) match
          case (Some(branch), Some(base)) if branch.nonEmpty && base.nonEmpty =>
            Try(os.Path(rec.projectWorkspace.getOrElse(rec.cwd))).toOption
              .filter(GitWorktrees.isGitRepo)
              .foreach { ws =>
                val ahead = GitWorktrees.commitsAhead(ws, base, branch)
                if ahead > 0 then
                  logger.warn(
                    s"delegate $delegateId settled with $ahead commit(s) still ahead on '$branch' — commits remain " +
                      s"on the agent-managed branch; landing is the author's decision"
                  )
                  ChainStore.appendSync(
                    ChainStore.keyFor(rec.taskId, delegateId),
                    "unmerged-work",
                    io.circe.Json.obj(
                      "delegateId" -> io.circe.Json.fromString(delegateId),
                      "branch" -> io.circe.Json.fromString(branch),
                      "commitsAhead" -> io.circe.Json.fromInt(ahead),
                      "note" -> io.circe.Json.fromString(
                        "completed settlement with commits ahead on the agent-managed branch — noted, not blocked; " +
                          "commits remain on the branch; landing is the author's decision"
                      )
                    )
                  )
                end if
              }
          case _ => ()
      case None => ()

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
        ).handleErrorWith(e => logger.warn(s"terminal-verdict chain line (engine) failed: ${e.getMessage}"))
    // N1 刀1（v3 §6/A3 宿主生命周期）：delegate 终态 ⇒ 作用域宿主实例灭（盘面状态存，
    // write-through 已持久——「会话终→引擎实例灭＋落盘状态存」）。幂等：外部执行器腿
    // / 未挂载 id = 零动作。
    // dispatcher 腿追加速度摘要先行（sweep 出库 + chain-archived 审计落定；在飞链
    // 台账 cancelled 处置只在明确取消墓碑时进行），随后才 unmount；unmount 之后
    // scope 退役走迁移通道（flow-map-archive，不删除）。
    val dispatcherLifecycle = DelegateFlowMapLifecycle.applies(record)
    val cancelAuthorized = record.flatMap(_.cancelRequestedAt).isDefined
    val summaryFlush =
      if dispatcherLifecycle then DelegateFlowMapLifecycle.flushBeforeUnmount(delegateId, cancelAuthorized)
      else IO.unit
    val hostUnmount =
      (summaryFlush *> nebflow.core.project.DelegateFlowHosts
        .unmount(delegateId))
        .handleErrorWith(e => logger.warn(s"delegate flow-host unmount failed: ${e.getMessage}"))
    // jev-plugins 批 #1226：分配插件的 MCP 引用回收（pluginMcp.acquire/release 随
    // delegate 生命周期——release 是全终态汇合点；幂等 no-op：未分配/外部执行器腿
    // 恒零持有）。
    val pluginRelease =
      resources.pluginMcp
        .release(delegateId)
        .handleErrorWith(e => logger.warn(s"delegate plugin MCP release failed: ${e.getMessage}"))
    // 台账↔面板对账批：终态交付自动台账记账——record.taskId 定义时 append 结算 note
    // （复活注记读任务行 revivalCount随行）。fail-soft。
    val ledgerNote =
      DelegateLedgerReconcile
        .ledgerSettlementNote(resources.subAgentTaskStore, record, delegateId, taskStatus)
        .handleErrorWith(e => logger.warn(s"delegate settlement note failed: ${e.getMessage}"))
    val graceRemoval: IO[Unit] =
      if dispatcherLifecycle then
        DelegateFlowMapLifecycle
          .scheduleScopeRemoval(delegateId)
          .start
          .as(())
          .handleErrorWith(e =>
            logger.warn(s"[flowmap-lifecycle] grace removal fiber failed to start: ${e.getMessage}")
          )
      else IO.unit
    // 案A 批 2026-10-31（状态矩阵批收编）：终态 ⇒ 待命槽清算（standbySince +
    // 申报槽——终态会话不再待命；caretaker 读不到 Waiting 活体亦自取消，双保险）。
    // 幂等：非待命记录 = 零写。fail-soft 只 WARN，绝不拖垮终态清理序列。
    val standbyClear: IO[Unit] =
      IO.blocking(DelegateRegistry.clearStandby(delegateId))
        .attempt
        .flatMap {
          case Left(e) => logger.warn(s"standby clear failed for $delegateId: ${e.getMessage}")
          case Right(_) => IO.unit
        }
    verdictLine *> ledgerNote *> hostUnmount *> pluginRelease *> standbyClear *> graceRemoval
  end onTerminalCleanup

end DelegateTerminal
