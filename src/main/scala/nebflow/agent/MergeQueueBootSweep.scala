package nebflow.agent

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import io.circe.JsonObject
import io.circe.syntax.*
import nebflow.actor.{ActorRef, ActorSystem, AgentCommand}
import nebflow.core.AgentRuntimePort
import nebflow.core.GitWorktrees
import nebflow.core.delegate.{ChainStore, DelegateRegistry, MergeQueue, PostMergeCleanup}
import nebflow.core.executor.{ExecutorRegistry, ExternalSessionRunner}
import nebflow.core.tools.DelegateTool
import nebflow.shared.NebflowLogger

import scala.util.Try

/**
 * MergeQueueBootSweep —— 合并队列**跨重启恢复** boot sweep（引擎加固批
 * 2026-10-09，上游审计 #31 结论 P2-5）.
 *
 * == 关闭的缺口 ==
 * MergeQueue 的进程内队列随重启消亡（既有头注明言「已知限制」），而
 * ready-to-merge delegate 的**父回报权在合并轮**——不恢复 = 分支上的交付永远
 * 悬置：父没等到通知、没人在飞合并轮、分支没人收。本 sweep 在引擎启动时把
 * 中断的队列成员从持久账本（ChainStore `merge-queue` 行）＋
 * [[DelegateRegistry]] 记录**按 readyAt 原值重挂** [[MergeQueue]]（FIFO 序跨
 * 重启保真），挂不进的 WARN + 标 blocked + 报父。
 *
 * == 判据（单点 = 账本行终态）==
 * 每 delegate 的 `merge-queue` 行序列：最后一行 action ∈ {enqueued,
 * reminder-sent} = 中断成员（无后继 dequeued = 没走完出队）；= dequeued /
 * recovered-* / inject-failed = 干净退出，跳过。
 *
 * == 处置（每中断成员）==
 *   - **已并**（`isAncestor(branch→base) == true`）：合并已落地——残留现场按
 *     [[PostMergeCleanup]] 同守卫回收（零领先 + 绝不 force），账本行
 *     `recovered-merged`，父收 completed 通知（correlationId 幂等）。
 *   - **无可并物**（分支缺失 / 零领先）：账本行 `recovered-dropped-empty`，
 *     父收 completed（诚实说明无可并之物）。
 *   - **可重挂**（分支 + 基分支锚齐 + 有领先提交）：
 *     - 外部执行器腿 → 条目 `recovered` 通道 = CLI resume（同 id 续聊）；
 *     - 内置执行器腿 → `reviveInternal`（[[internalRevival]]——续起一个新会话
 *       执行合并，`MergeQueue.rekey` 换键；原任务行标 cancelled-superseded）；
 *       未注入 reviveInternal（测试/无 ActorSystem 场）= 按挂不进处置。
 *   - **挂不进**（记录缺失 / 锚不全 / 非在册执行器 / 内置腿无复活通道）：WARN +
 *     账本行 `recovery-blocked` + 任务行翻 failed（blocked 语义）+ 父收 failed
 *     通知（F2 注入队文件，correlationId=`mq-recovery-<id>` 幂等——
 *     SubAgentStartupRecovery 同机制）。
 *
 * == 排线 ==
 * GatewayMain boot 链（startupMount/projectBootWake 之后——bootRoot 与父会话
 * 注册表已就绪；单次执行、失败只 WARN 绝不阻塞启动）。无候选 = 零动作（一次
 * 账本扫描的成本）。
 */
object MergeQueueBootSweep:

  private val logger = NebflowLogger.forName("nebflow.agent.merge-queue-recovery")

  /** sweep 汇总（boot 日志行 + 测试断言面）。 */
  final case class Report(
    reMounted: List[String] = Nil,
    alreadyMerged: List[String] = Nil,
    droppedEmpty: List[String] = Nil,
    blocked: List[(String, String)] = Nil
  ):

    def summary: String =
      s"reMounted=${reMounted.size} (${reMounted.mkString(",")}) alreadyMerged=${alreadyMerged.size} " +
        s"droppedEmpty=${droppedEmpty.size} blocked=${blocked.size}"

  /**
   * 内部腿复活通道工厂：GatewayMain boot 装配点传入（None = 内置腿候选按挂不进
   * 处置——测试/无 ActorSystem 场的诚实缺省）。续起会话（基工作区座位）执行
   * 合并 → `MergeQueue.rekey` 换键 → 原任务行收口（cancelled-superseded）。
   */
  def internalRevival(
    resources: AgentRuntimePort,
    system: ActorSystem,
    wsSend: Option[Json => IO[Unit]]
  ): DelegateRegistry.Record => String => IO[Either[String, Unit]] = rec =>
    brief =>
      resolveParentRef(resources, rec).flatMap { parentRef =>
        // 座位 = 基工作区（不在旧 worktree 里嵌套建权；合并指令钉死 `git -C <baseWs>`
        // ——方向由 brief 保证，座位只影响 delegate 自己的读面）。
        val seatWs = rec.projectWorkspace.getOrElse(rec.cwd)
        DelegateTool
          .spawnBackground(
            agentDef =
              nebflow.core.entity.BuiltinAgents.delegateEntry(DelegateTool.KernelAgentName).map(_.toAgentDef).get,
            task = s"""[continuation of delegate ${rec.id} — merge round recovery after an engine restart]
[workspace] $seatWs (file tools need absolute paths; Bash cwd is not guaranteed)

$brief""",
            description = s"merge round recovery: ${rec.title}",
            workspace = seatWs,
            project = rec.project,
            taskId = rec.taskId,
            system = system,
            resources = resources,
            parentDepth = 0,
            parentRef = parentRef,
            wsSend = wsSend,
            parentSessionId = Some(rec.parentSessionId),
            rootSessionId = rec.parentSessionId
          )
          .flatMap {
            case Left(err) => IO.pure(Left(err.message))
            case Right((newId, _)) =>
              // 换键（合并轮终态由新会话驱动）＋旧任务行收口（原行 ready-to-merge
              // 永不终态 = 幽灵行）。
              IO(MergeQueue.rekey(rec.id, newId)) *>
                resources.subAgentTaskStore
                  .updateStatus(
                    rec.parentSessionId,
                    rec.id,
                    status = "cancelled",
                    lastError = Some(s"superseded by merge-round boot recovery → $newId"),
                    completedAt = Some(System.currentTimeMillis())
                  )
                  .handleErrorWith(e =>
                    logger.warn(s"recovery task-row close-out failed for ${rec.id}: ${e.getMessage}")
                  )
                  .as(Either.right[String, Unit](()))
          }
      }

  /** Run the boot sweep. Returns the report (for the boot log line). */
  def sweep(
    resources: AgentRuntimePort,
    reviveInternal: Option[DelegateRegistry.Record => String => IO[Either[String, Unit]]] = None,
    wsSend: Option[Json => IO[Unit]] = None
  ): IO[Report] =
    IO.blocking(interruptedMembers()).flatMap {
      case Nil => IO.pure(Report())
      case members =>
        logger.warn(
          s"[merge-queue-recovery] ${members.size} interrupted merge-queue membership(s) survived a restart — re-mounting by readyAt"
        ) *>
          members.foldLeftM(Report())((acc, m) =>
            recoverOne(resources, reviveInternal, wsSend, m).map(r => combine(acc, r))
          )
    }

  /** 一条中断成员的处置（分类 + 重挂/回收/阻断，逐条 handleError 不串扰）。 */
  private def recoverOne(
    resources: AgentRuntimePort,
    reviveInternal: Option[DelegateRegistry.Record => String => IO[Either[String, Unit]]],
    wsSend: Option[Json => IO[Unit]],
    m: Interrupted
  ): IO[Report] =
    recoverOneImpl(resources, reviveInternal, wsSend, m)
      .handleErrorWith(e =>
        logger
          .warn(s"[merge-queue-recovery] recovery failed for ${m.delegateId}: ${e.getMessage}")
          .as(
            Report(blocked = List(m.delegateId -> s"recovery error: ${e.getMessage}"))
          )
      )

  private def recoverOneImpl(
    resources: AgentRuntimePort,
    reviveInternal: Option[DelegateRegistry.Record => String => IO[Either[String, Unit]]],
    wsSend: Option[Json => IO[Unit]],
    m: Interrupted
  ): IO[Report] =
    DelegateRegistry.find(m.delegateId) match
      case None =>
        blocked(resources, m, s"no DelegateRegistry record for '${m.delegateId}' — cannot rebuild the merge target")
      case Some(rec) =>
        (rec.branch, rec.baseBranch) match
          case (Some(branch), Some(baseBranch)) =>
            Try(os.Path(rec.projectWorkspace.getOrElse(rec.cwd))).toOption match
              case None =>
                blocked(resources, m, s"workspace path unresolvable (${rec.projectWorkspace.getOrElse(rec.cwd)})")
              case Some(ws) =>
                val ancestor = GitWorktrees.isAncestor(ws, branch, baseBranch)
                val ahead = GitWorktrees.commitsAhead(ws, baseBranch, branch)
                if ancestor.contains(true) then
                  // 已并：合并落地、队列成员中断在出队前——收残留现场（同守卫），报父 completed。
                  for
                    _ <- PostMergeCleanup.run(Some(rec)).void
                    _ <- IO(
                      chainRow(
                        m,
                        "recovered-merged",
                        Json.obj("branch" -> branch.asJson, "baseBranch" -> baseBranch.asJson)
                      )
                    )
                    _ <- notifyParent(
                      resources,
                      rec,
                      "completed",
                      s"""[merge-queue recovery] "${rec.title}" (delegate ${rec.id}): the merge round interrupted by the restart had already LANDED — branch `$branch` is merged into `$baseBranch`; leftover worktree/branch cleaned under the zero-ahead guard.""",
                      blockedStyle = false
                    )
                  yield Report(alreadyMerged = List(m.delegateId))
                else if !GitWorktrees.branchExists(ws, branch) || ahead == 0 then
                  for
                    _ <- IO(
                      chainRow(
                        m,
                        "recovered-dropped-empty",
                        Json.obj("branch" -> branch.asJson, "commitsAhead" -> ahead.asJson)
                      )
                    )
                    _ <- notifyParent(
                      resources,
                      rec,
                      "completed",
                      s"""[merge-queue recovery] "${rec.title}" (delegate ${rec.id}): the restart interrupted its merge round, but branch `$branch` now has $ahead commit(s) ahead of `$baseBranch` (missing or already merged) — nothing to merge; the membership is closed.""",
                      blockedStyle = false
                    )
                  yield Report(droppedEmpty = List(m.delegateId))
                else mountable(resources, reviveInternal, wsSend, m, rec, branch, baseBranch)
                end if
          case _ =>
            blocked(
              resources,
              m,
              s"incomplete spawn binding (branch=${rec.branch.getOrElse("-")}, baseBranch=${rec.baseBranch.getOrElse("-")}) — merge target unknown"
            )

  /** 可重挂成员：外部腿 CLI resume（同 id）；内置腿续起会话 + rekey。 */
  private def mountable(
    resources: AgentRuntimePort,
    reviveInternal: Option[DelegateRegistry.Record => String => IO[Either[String, Unit]]],
    wsSend: Option[Json => IO[Unit]],
    m: Interrupted,
    rec: DelegateRegistry.Record,
    branch: String,
    baseBranch: String
  ): IO[Report] =
    val baseWs = rec.projectWorkspace.getOrElse(rec.cwd)
    val isExternal = rec.executor != ExecutorRegistry.DefaultId
    val channel: Either[String, String => IO[Either[String, Unit]]] =
      if isExternal then
        // 外部腿：执行器仍在册才可续——CLI 被卸载 = 挂不进（fail-closed 同 runner 口径）。
        ExecutorRegistry.resolveRequested(Some(rec.executor)) match
          case Left(err) => Left(s"executor '${rec.executor}' unresolvable: $err")
          case Right(_) =>
            Right(msg =>
              ExternalSessionRunner.noteMergeRound(rec.id) *>
                resolveParentRef(resources, rec).flatMap(parentRef =>
                  ExternalSessionRunner
                    .continueExternal(rec, msg, parentRef = parentRef, wsSend = wsSend, resources = resources)
                    .map(_.map(_ => ()))
                )
            )
      else
        reviveInternal match
          case Some(revive) => Right(revive(rec))
          case None =>
            Left("internal executor leg has no revival channel wired at this call site (reviveInternal=None)")

    channel match
      case Left(err) => blocked(resources, m, err)
      case Right(send) =>
        val baseKey = Try(os.Path(baseWs)).map(GitWorktrees.projectKeyOf).getOrElse(baseWs)
        val entry = MergeQueue.Entry(
          delegateId = rec.id,
          branch = branch,
          baseWs = baseWs,
          baseBranch = baseBranch,
          worktree = rec.worktree.isDefined,
          projectKey = baseKey,
          taskId = rec.taskId,
          parentSessionId = rec.parentSessionId,
          description = rec.title,
          readyAt = m.readyAt, // readyAt 原值回挂——FIFO 序跨重启保真（S11 锚不漂移）
          childRef = MergeQueue.NoopChild,
          supervisorRef = MergeQueue.NoopSupervisor,
          recovered = Some(MergeQueue.Recovered(rec.id, send))
        )
        MergeQueue.enqueueIO(entry).as(Report(reMounted = List(rec.id)))

    end match

  end mountable

  /** 挂不进处置：WARN + 账本行 + 任务行翻 failed + 父收 blocked 通知。 */
  private def blocked(resources: AgentRuntimePort, m: Interrupted, reason: String): IO[Report] =
    logger.warn(s"[merge-queue-recovery] BLOCKED ${m.delegateId}: $reason") *>
      IO(chainRow(m, "recovery-blocked", Json.obj("reason" -> reason.asJson))) *>
      DelegateRegistry
        .find(m.delegateId)
        .traverse_ { rec =>
          resources.subAgentTaskStore
            .updateStatus(
              rec.parentSessionId,
              rec.id,
              status = "failed",
              lastError = Some(s"merge-queue recovery blocked: $reason"),
              completedAt = Some(System.currentTimeMillis())
            )
            .handleErrorWith(e => logger.warn(s"recovery task-row failed write for ${rec.id}: ${e.getMessage}")) *>
            notifyParent(
              resources,
              rec,
              "failed",
              s"""[merge-queue recovery] "${rec.title}" (delegate ${rec.id}): its merge round was interrupted by the restart and CANNOT be re-mounted — $reason. The branch and worktree are KEPT as-is for manual merge or re-dispatch.""",
              blockedStyle = true
            )
        }
        .as(Report(blocked = List(m.delegateId -> reason)))

  // ── 账本判据（单点）──────────────────────────────────────────────────

  /** 一条中断的队列成员（delegateId + readyAt 原值）。 */
  final case class Interrupted(delegateId: String, readyAt: Long)

  /**
   * 扫 ChainStore 全量：每 delegate 的 merge-queue 行序列，末行 action ∈
   * {enqueued, reminder-sent, reminder-deferred, refused-keep-queued} = 中断成员
   * （无后继 dequeued = 未出队；steward 批 D3-②③ 2026-10-23 起新增两词——提醒
   * 不可投与合并轮被拒的条目**留队待重试**，重启后同样按中断成员重挂，提醒经
   * revive 通道重投 = 「重启后提醒仍达」）。readyAt 取末行 payload 的 readyAt
   * （缺失回落行时间戳）。同 delegate 跨键去重（取末行——重挂一次）。
   */
  private[agent] def interruptedMembers(): List[Interrupted] =
    ChainStore
      .listSync()
      .flatMap(_.entries.filter(_.kind == MergeQueue.EntryKind))
      .flatMap { e =>
        val c = e.payload.hcursor
        for
          delegateId <- c.get[String]("delegateId").toOption
          action <- c.get[String]("action").toOption
        yield (delegateId, action, c.get[Long]("readyAt").toOption.getOrElse(e.at))
      }
      .groupBy(_._1)
      .flatMap { (delegateId, rows) =>
        rows.lastOption.collect {
          case (_, action, readyAt) if InterruptedActions.contains(action) =>
            Interrupted(delegateId, readyAt)
        }
      }
      .toList
      .sortBy(_.readyAt)

  /** 中断成员的末行 action 词表（含 steward 批新增的留队待重试两词）。 */
  private val InterruptedActions: Set[String] =
    Set("enqueued", "reminder-sent", "reminder-deferred", "refused-keep-queued")

  private def combine(a: Report, b: Report): Report =
    Report(
      reMounted = a.reMounted ++ b.reMounted,
      alreadyMerged = a.alreadyMerged ++ b.alreadyMerged,
      droppedEmpty = a.droppedEmpty ++ b.droppedEmpty,
      blocked = a.blocked ++ b.blocked
    )

  private def chainRow(m: Interrupted, action: String, extra: Json): Unit =
    try
      ChainStore.appendSync(
        ChainStore.keyFor(None, m.delegateId),
        MergeQueue.EntryKind,
        Json
          .obj(
            "action" -> action.asJson,
            "delegateId" -> m.delegateId.asJson,
            "phase" -> "boot-recovery".asJson,
            "readyAt" -> m.readyAt.asJson
          )
          .deepMerge(extra)
      )
    catch case e: Throwable => logger.warn(s"merge-queue recovery ChainStore line failed ($action): ${e.getMessage}")

  /**
   * 父通知（F2 注入队文件，correlationId 幂等——SubAgentStartupRecovery 同机制；
   * 父会话已删 = 跳过通知，账本行仍在）。
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
          CompactionQueueStore.load(rec.parentSessionId).flatMap { existing =>
            val correlationId = s"mq-recovery-${rec.id}"
            val already = existing.exists(_.events.exists(_.correlationId.contains(correlationId)))
            if already then IO.unit
            else
              val metadata =
                if blockedStyle then
                  JsonObject(
                    "description" -> rec.title.asJson,
                    "agentName" -> rec.executor.asJson,
                    "failureType" -> "blocked".asJson,
                    "recoveredBy" -> "merge-queue-boot-sweep".asJson
                  )
                else
                  JsonObject(
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
              val q = existing.getOrElse(CompactionQueueStore.PersistedQueues())
              CompactionQueueStore.save(rec.parentSessionId, q.copy(events = q.events :+ event))
            end if
          }
      }
      .handleErrorWith(e => logger.warn(s"merge-queue recovery parent notify failed for ${rec.id}: ${e.getMessage}"))

  private def resolveParentRef(
    resources: AgentRuntimePort,
    rec: DelegateRegistry.Record
  ): IO[Option[ActorRef[AgentCommand]]] =
    if rec.parentSessionId.isEmpty then IO.pure(None)
    else resources.agentRegistry.get.map(_.get(rec.parentSessionId).map(_.ref))

end MergeQueueBootSweep
