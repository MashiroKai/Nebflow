package nebflow.agent

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import nebflow.actor.AgentCommand
import nebflow.core.AgentRuntimePort
import nebflow.core.GitWorktrees
import nebflow.core.delegate.{DelegateRegistry, MergeQueue, MergeQueueChannel, PostMergeCleanup}
import nebflow.shared.NebflowLogger

import scala.util.Try

/**
 * MergeQueueBootSweep —— 合并队列（交通引导员制）的**重启对账** boot sweep
 * （台账 #896 · 2026-10-26 作者裁定；上游前身 = 引擎加固批 2026-10-09 的
 * 「重挂 + 复活」sweep——重挂/复活/换键腿已整体退役：重启后不再续起任何合并
 * 回合，属主失联诚实关账，处置归 dispatcher）。
 *
 * == 判据（单点 = 持久账本）==
 * [[MergeQueue]] 的账本落盘持久（`<dataRoot>/merge-ledger.json`）——重启不清零。
 * 本 sweep 只对**开放成员**（waiting / turn-granted）做一次对账：
 *
 *   - **已并**（`isAncestor(branch→base) == true`）：合并已落地——关账 merged +
 *     残留现场按 [[PostMergeCleanup]] 同守卫回收（零领先 + 绝不 force）+ 父收
 *     completed 通知（correlationId 幂等）。重启前后「分支已并仍在账上罚站」
 *     的 should-not-happen 在此消除。
 *   - **无可并物**（分支缺失 / 零领先）：关账 merged（诚实注记「无可并之物」），
 *     父收 completed 通知。
 *   - **可并但属主失联**：进程重启 = 属主会话（actor / 外部 CLI 子进程）随进程
 *     消亡——合并轮通知不可达 ⇒ **owner-gone** 关账 + 父收 blocked 通知；面板
 *     显式「需 dispatcher 处置」，绝不静默 park，绝不自动续起合并回合。
 *     **R5 前提反转**：owner-gone 以「write-ahead 通道无未关账镜像」为前提——
 *     通道还有该成员的未关账记录 = 成员资格在盘上，**不凭进程内存缺席关账**：
 *     窗让回（waiting）+ 保留成员（[[MergeQueue.preserveAfterRestart]]），可行动
 *     通知照发；属主续活后下拍自然放行，人工并入后下拍账实核对自动关账 merged。
 *
 * == 排线 ==
 * GatewayMain boot 链（startupMount/projectBootWake 之后；单次执行、失败只 WARN
 * 绝不阻塞启动）。无开放成员 = 零动作。收尾批量压缩通道终态镜像（关账压缩）。
 */
object MergeQueueBootSweep:

  private val logger = NebflowLogger.forName("nebflow.agent.merge-queue-recovery")

  /** sweep 汇总（boot 日志行 + 测试断言面）。 */
  final case class Report(
    alreadyMerged: List[String] = Nil,
    droppedEmpty: List[String] = Nil,
    ownerGone: List[String] = Nil,
    /** R5：通道镜像未关账 ⇒ 保留（窗让回留队）而非 owner-gone 的成员。 */
    preserved: List[String] = Nil
  ):

    def summary: String =
      s"alreadyMerged=${alreadyMerged.size} (${alreadyMerged.mkString(",")}) " +
        s"droppedEmpty=${droppedEmpty.size} ownerGone=${ownerGone.size} (${ownerGone.mkString(",")}) " +
        s"preserved=${preserved.size} (${preserved.mkString(",")})"

  /** Run the boot reconcile sweep. Returns the report (for the boot log line). */
  def sweep(resources: AgentRuntimePort, wsSend: Option[Json => IO[Unit]] = None): IO[Report] =
    IO.blocking(MergeQueue.openSnapshot).flatMap {
      case Nil => IO.pure(Report())
      case open =>
        logger.warn(
          s"[merge-queue-recovery] ${open.size} open merge-ledger membership(s) survived a restart — reconciling (no merge rounds are revived)"
        ) *>
          open
            .foldLeftM(Report())((acc, m) =>
              reconcileOne(resources, m)
                .map(combine(acc, _))
                .handleErrorWith { e =>
                  logger
                    .warn(s"[merge-queue-recovery] reconcile failed for ${m.delegateId}: ${e.getMessage}")
                    .as(Report(ownerGone = List(m.delegateId)))
                }
            )
            .flatMap { report =>
              // 关账压缩（R5）：对账落定后批量移除通道终态镜像（账本持终态审计行，
              // 副本冗余）；未关账镜像（preserved/open）永不入压缩面。fail-soft。
              IO
                .blocking(MergeQueueChannel.compact(MergeQueue.State.Terminal))
                .flatMap(n =>
                  if n > 0 then
                    logger.info(s"[merge-queue-recovery] write-ahead channel compacted $n closed mirror(s)").void
                  else IO.unit
                )
                .handleErrorWith(e =>
                  logger.warn(s"[merge-queue-recovery] channel compact failed: ${e.getMessage}").void
                )
                .as(report)
            }
    }

  /** 一条开放成员的对账（分类 + 关账 + 报父，逐条不串扰）。 */
  private def reconcileOne(resources: AgentRuntimePort, m: MergeQueue.Member): IO[Report] =
    IO.blocking(DelegateRegistry.find(m.delegateId)).flatMap {
      case None =>
        // R5 前提反转：registry 记录缺席但 write-ahead 通道还有未关账镜像 = 成员
        // 资格在盘上——不凭记录缺席判 owner-gone。以成员自锚做账实核对：已并 ⇒
        // 关账 merged（无记录 ⇒ cleanup 无目标，零动作）；可并 ⇒ 保留留队。
        if MergeQueueChannel.hasOpenRecord(m.delegateId, MergeQueue.State.Open) then
          factCheckOrPreserve(
            m,
            s"no delegate registry record after restart, but the write-ahead merge-channel holds an un-closed record — membership preserved; continue/revive the owner session or merge the branch manually (the queue fact-checks and closes it as merged)"
          )
        else
          // 通道无未关账镜像（本批前存量账面/直写账本）——前提成立，照旧关账。
          ownerGone(
            resources,
            m,
            s"no delegate registry record for '${m.delegateId}' — the owner session did not survive the restart"
          )
      case Some(rec) =>
        (rec.branch, rec.baseBranch) match
          case (Some(branch), Some(baseBranch)) =>
            IO.blocking {
              Try(os.Path(rec.projectWorkspace.getOrElse(rec.cwd))).toOption
                .filter(GitWorktrees.isGitRepo)
                .map(ws =>
                  (
                    ws,
                    GitWorktrees.isAncestor(ws, branch, baseBranch),
                    GitWorktrees.commitsAhead(ws, baseBranch, branch)
                  )
                )
            }.flatMap {
              case None =>
                ownerGone(
                  resources,
                  m,
                  s"base workspace '${rec.projectWorkspace.getOrElse(rec.cwd)}' unreadable after restart — needs dispatcher handling"
                )
              case Some((ws, ancestor, ahead)) =>
                if ancestor.contains(true) then
                  // 已并：合并落地、成员中断在关账前——收残留现场（同守卫），报父 completed。
                  for
                    _ <- PostMergeCleanup.run(Some(rec)).void
                    _ <- IO(
                      MergeQueue.closeMerged(
                        m.delegateId,
                        via = "boot-reconcile",
                        closeReason = Some("branch already merged (fact-check at boot)"),
                        cleanup = false
                      )
                    )
                    _ <- notifyParent(
                      resources,
                      rec,
                      "completed",
                      s"""[merge-queue recovery] "${rec.title}" (delegate ${rec.id}): the merge membership interrupted by the restart had ALREADY LANDED — branch `$branch` is merged into `$baseBranch`; leftover worktree/branch cleaned under the zero-ahead guard. Membership closed as merged.""",
                      blockedStyle = false
                    )
                  yield Report(alreadyMerged = List(m.delegateId))
                else if !GitWorktrees.branchExists(ws, branch) || ahead == 0 then
                  for
                    _ <- IO(
                      MergeQueue.closeMerged(
                        m.delegateId,
                        via = "boot-reconcile",
                        closeReason = Some(
                          s"nothing to merge (branch exists=${GitWorktrees.branchExists(ws, branch)}, commitsAhead=$ahead)"
                        ),
                        cleanup = false
                      )
                    )
                    _ <- notifyParent(
                      resources,
                      rec,
                      "completed",
                      s"""[merge-queue recovery] "${rec.title}" (delegate ${rec.id}): the restart interrupted its merge membership, but branch `$branch` now has $ahead commit(s) ahead of `$baseBranch` (missing or already merged) — nothing to merge; the membership is closed.""",
                      blockedStyle = false
                    )
                  yield Report(droppedEmpty = List(m.delegateId))
                else if MergeQueueChannel.hasOpenRecord(m.delegateId, MergeQueue.State.Open) then
                  // R5 前提反转：可并但属主失联 + 通道未关账镜像在盘 = 成员资格持久——
                  // 保留（窗让回留队），绝不凭重启事实伪造 owner-gone。
                  preserve(
                    m,
                    s"branch `$branch` still has $ahead commit(s) ahead of `$baseBranch` and the owner session did not survive the restart — membership PRESERVED by the write-ahead channel (window returned, stays queued); continue/revive the owner session or merge the branch manually (the queue fact-checks and closes it as merged)"
                  )
                else
                  ownerGone(
                    resources,
                    m,
                    s"branch `$branch` still has $ahead commit(s) ahead of `$baseBranch`, but the owner session did not survive the restart — needs dispatcher handling (manual merge or re-dispatch)"
                  )
                end if
            }
          case _ =>
            if MergeQueueChannel.hasOpenRecord(m.delegateId, MergeQueue.State.Open) then
              preserve(
                m,
                s"incomplete spawn binding on the registry record (branch=${rec.branch.getOrElse("-")}, baseBranch=${rec.baseBranch.getOrElse("-")}) but the write-ahead channel holds an un-closed record — membership preserved; needs dispatcher handling"
              )
            else
              ownerGone(
                resources,
                m,
                s"incomplete spawn binding (branch=${rec.branch.getOrElse("-")}, baseBranch=${rec.baseBranch.getOrElse("-")}) — merge target unknown, needs dispatcher handling"
              )
    }
  end reconcileOne

  /**
   * R5 前提腿（registry 记录缺席 + 通道镜像在盘）：以成员自锚账实核对——已并关账
   * merged，可并保留留队。
   */
  private def factCheckOrPreserve(m: MergeQueue.Member, preserveReason: String): IO[Report] =
    IO.blocking {
      Try(os.Path(m.baseWs)).toOption
        .filter(GitWorktrees.isGitRepo)
        .map(ws => GitWorktrees.isAncestor(ws, m.branch, m.baseBranch))
    }.flatMap {
      case Some(Some(true)) =>
        IO(
          MergeQueue.closeMerged(
            m.delegateId,
            via = "boot-reconcile",
            closeReason = Some("branch already merged (fact-check at boot; registry record gone, channel record open)"),
            cleanup = false
          )
        ).as(Report(alreadyMerged = List(m.delegateId)))
      case _ =>
        preserve(m, preserveReason)
    }

  /**
   * 保留腿（R5）：窗让回 + 留队 + 可行动通知（BlockNotifyPort 去重/升级同面）。
   * 任务行保持 ready-to-merge（合并轮仍在队列手上）——绝不静默 park（通知必达）。
   */
  private def preserve(m: MergeQueue.Member, reason: String): IO[Report] =
    logger.warn(s"[merge-queue-recovery] PRESERVED ${m.delegateId}: $reason") *>
      IO(MergeQueue.preserveAfterRestart(m.delegateId, reason)).as(Report(preserved = List(m.delegateId)))

  /** owner-gone 关账（队列单点落账 + 面板呈现）+ 父收 blocked 通知。 */
  private def ownerGone(resources: AgentRuntimePort, m: MergeQueue.Member, reason: String): IO[Report] =
    logger.warn(s"[merge-queue-recovery] OWNER-GONE ${m.delegateId}: $reason") *>
      IO(MergeQueue.markOwnerGone(m.delegateId, reason)) *>
      DelegateRegistry
        .find(m.delegateId)
        .traverse_ { rec =>
          resources.subAgentTaskStore
            .updateStatus(
              rec.parentSessionId,
              rec.id,
              status = "failed",
              lastError = Some(s"merge-queue owner-gone after restart: $reason"),
              completedAt = Some(System.currentTimeMillis())
            )
            .handleErrorWith(e => logger.warn(s"recovery task-row failed write for ${rec.id}: ${e.getMessage}")) *>
            notifyParent(
              resources,
              rec,
              "failed",
              s"""[merge-queue recovery] "${rec.title}" (delegate ${rec.id}): its merge membership was interrupted by the restart and the owner session is GONE — no merge round is revived (traffic-controller model). The membership is closed as owner-gone; the branch and worktree are KEPT as-is for manual merge or re-dispatch.""",
              blockedStyle = true
            )
        }
        .as(Report(ownerGone = List(m.delegateId)))
  end ownerGone

  private def combine(a: Report, b: Report): Report =
    Report(
      alreadyMerged = a.alreadyMerged ++ b.alreadyMerged,
      droppedEmpty = a.droppedEmpty ++ b.droppedEmpty,
      ownerGone = a.ownerGone ++ b.ownerGone,
      preserved = a.preserved ++ b.preserved
    )

  /**
   * 父通知（P2 邮箱化批：delegate 轨改走**收件方持久邮箱**——mailOnce 幂等入队
   * + 门铃；写成功=必达，不再依赖父会话 live。identity = 本通知的 correlationId
   * （`mq-recovery-<id>` 等专用键，与旧 F2 队列的幂等键同串——迁移期两 surface
   * 判重互不误伤）。父会话已删 = 跳过通知，账本行仍在）。
   */
  private def notifyParent(
    resources: AgentRuntimePort,
    rec: DelegateRegistry.Record,
    eventType: String,
    payload: String,
    blockedStyle: Boolean
  ): IO[Unit] =
    resources.sessionStore.listSessions
      .flatMap { sessions =>
        if !sessions.exists(_.id == rec.parentSessionId) then IO.unit
        else
          val correlationId = s"mq-recovery-${rec.id}"
          val metadata =
            if blockedStyle then
              io.circe.JsonObject(
                "description" -> rec.title.asJson,
                "agentName" -> rec.executor.asJson,
                "failureType" -> "blocked".asJson,
                "recoveredBy" -> "merge-queue-boot-sweep".asJson
              )
            else
              io.circe.JsonObject(
                "description" -> rec.title.asJson,
                "agentName" -> rec.executor.asJson,
                "recoveredBy" -> "merge-queue-boot-sweep".asJson
              )
          val event = AgentCommand.ExternalEvent(
            source = "delegate",
            eventType = eventType,
            payload = payload,
            metadata = metadata,
            correlationId = Some(correlationId)
          )
          MailboxProducer
            .deliver(
              resources,
              rec.parentSessionId,
              event,
              identity = correlationId,
              kind = "delegate-terminal",
              coalesce = false,
              producer = "merge-queue-boot-sweep"
            )
            .void
      }
      .handleErrorWith(e => logger.warn(s"merge-queue recovery parent notify failed for ${rec.id}: ${e.getMessage}"))

end MergeQueueBootSweep
