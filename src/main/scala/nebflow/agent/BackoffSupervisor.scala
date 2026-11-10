package nebflow.agent

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.actor.*
import nebflow.core.mail.SessionMailbox
import nebflow.shared.*

import scala.concurrent.duration.*

/**
 * Supervises a child agent actor. On crash (fiber termination), restarts the
 * child with exponential backoff. Mirrors Pekko's BackoffSupervisor pattern
 * but built on Nebflow's self-contained Actor framework.
 *
 * The supervisor is an enhanced adapter: it receives AgentEvent messages from
 * the child (via replyTo), forwards completion/failure to the parent, and
 * auto-restarts the child on unexpected termination (crash).
 *
 * - Watches the pre-spawned child via death watch
 * - On Completed/Failed: forwards ExternalEvent to parent, cleans up (like adapter)
 * - On `Terminated` (crash): waits backoff, respawns child, re-injects prompt
 * - After maxRestarts exceeded: notifies parent via non-retryable ExternalEvent
 *
 * Circuit breaker: if maxRestarts are exceeded within withinTimeRange, the
 * supervisor gives up and notifies the parent agent.
 */
object BackoffSupervisor:

  private val logger = NebflowLogger.forName("nebflow.agent.supervisor")

  /**
   * @param childRef    the pre-spawned child actor (initial spawn done by caller)
   * @param childSpawnFn used to re-spawn the child on restart; receives the
   *                     recovered messages (if any) so the child can resume
   *                     from the crash checkpoint instead of starting fresh
   * @param childName   child actor name (for logging)
   * @param parentRef   parent agent to notify on completion / failure
   * @param description human-readable task description
   * @param agentName   child agent name
   * @param subagentId  session id
   * @param parentSessionId the parent (root) session that owns this task —
   *                        SubAgentTaskStore keys task files by it; must match
   *                        the id used at recordTask time or status updates
   *                        silently write nowhere
   * @param resources   shared resources (for agentRegistry cleanup + session
   *                    store to recover persisted messages on restart)
   * @param initialPrompt the original prompt to re-inject after restart (used
   *                      as fallback when no persisted messages are found)
   * @param source       ExternalEvent source string ("delegate" or "subtask")
   * @param extraMetadata extra metadata for ExternalEvent (e.g. "kind" -> "SubTask")
   */
  def apply(
    childRef: ActorRef[AgentCommand],
    childSpawnFn: (ActorSystem, List[Message]) => IO[ActorRef[AgentCommand]],
    childName: String,
    parentRef: Option[ActorRef[AgentCommand]],
    description: String,
    agentName: String,
    subagentId: String,
    parentSessionId: String,
    resources: SharedResources,
    initialPrompt: String,
    source: String,
    extraMetadata: JsonObject = JsonObject.empty,
    wsSend: Option[Json => IO[Unit]] = None,
    minBackoff: FiniteDuration = 5.seconds,
    maxBackoff: FiniteDuration = 60.seconds,
    maxRestarts: Int = 2,
    withinTimeRange: FiniteDuration = 5.minutes,
    /**
     * A 段等待屏罩兜底计时上限（spec 注入缝——测试 JVM 下 sys.props 不可靠，
     * NodeEngine.bgWaitCapMs 同款构造器缝先例）。
     */
    bgWaitCapMs: Long = Defaults.BgGateWaitTimeoutMs,
    /**
     * **R5（bg-turn-end barrier idle 上限放行批 2026-11-04）**：等待屏罩的**空闲**
     * 上限（ms）——等待集里每个后台作业都「一段时间没有新输出」且已达此上限 ⇒
     * 放行回合收尾（不再等那个永不结束的作业）。0/负数 = 关闭本腿（旧行为）。
     * 默认 [[Defaults.BgTurnEndIdleCapMs]]（15min）；构造器缝 = spec 注入点。
     */
    bgIdleCapMs: Long = Defaults.BgTurnEndIdleCapMs
  ): Behavior[AgentEvent] =
    Behaviors.setup { ctx =>
      // R11 第 4 层（作者裁定 U1=C-a / U8=(ii)）：**仅 Delegate 轨**装配 3600s
      // wall-clock 预算（SubTask/其它 source 零行为变化）。超时走既有 cancel 链
      // ——向本监督者投 AgentEvent.Cancelled(reason="timeout")，其余（父通知 /
      // barrier 释放 / registry 清理 / child Stop）全部复用既有终态路径。
      // 计时生命周期：register（此处）→ pause/resume（ask 两个既有单点）→
      // release（notifyParentAndStop / respawn）。
      // 🔴 2026-10-09 作者裁定拆除（追加式注释，原块保持原样）：上块的 3600s
      // wall-clock 预算已整体拆除——DelegateBudget.register/pause/resume/release
      // 现为墓碑壳 no-op（见 DelegateBudget.scala），下方三处调用仅作调用点兼容
      // 保留、永不产生 "timeout"/"merge-window-timeout" 取消。delegate 治理 =
      // TaskStuckWatcher 无进展判据（与 node 节点同哲学：无超时、只治真卡死）。
      val budgetArmedIO: IO[Unit] =
        if source == "delegate" then
          DelegateBudget.register(subagentId)(ctx.self ! AgentEvent.Cancelled(subagentId, "timeout"))
        else IO.unit
      // sleep-poll 禁令批 A 段（等待屏障）：hold 期状态——首 hold 单发标记 +
      // 兜底计时器句柄（每等待期重臂，NodeStarter 桥 bg-wait 同款形态）+ cap
      // 触发闩（到点强制终判的复检回合路由）。
      val bgWaitHeld = Ref.unsafe[IO, Boolean](false)
      val bgWaitCapArm = Ref.unsafe[IO, Long](0L)
      val bgWaitCapFired = Ref.unsafe[IO, Boolean](false)
      budgetArmedIO *> ctx.watch(childRef) *>
        logger
          .info(s"BackoffSupervisor: watching $childName for crash recovery (maxRestarts=$maxRestarts)")
          .as(
            active(
              childRef,
              childSpawnFn,
              childName,
              parentRef,
              description,
              agentName,
              subagentId,
              parentSessionId,
              resources,
              initialPrompt,
              source,
              extraMetadata,
              wsSend,
              restartCount = 0,
              restartHistory = Nil,
              minBackoff,
              maxBackoff,
              maxRestarts,
              withinTimeRange,
              bgWaitCapMs,
              bgIdleCapMs,
              bgWaitHeld,
              bgWaitCapArm,
              bgWaitCapFired
            )
          )
    }

  /**
   * R5 空闲上限判据（纯函数，spec 判红锚；零 IO）：等待集里**每一个**作业的最近
   * 活动时刻（`max(启动时刻, 最近输出行时刻)`）距今都 ≥ `idleCapMs` ⇒ true。
   *
   * 为什么「全部空闲」而不是「任一空闲」：只要还有一个作业在产出，等它就有意义
   * （编译/测试的长尾），此时不该放行；只有当整集**都**不动了，继续等下去只是
   * 让回合与父会话干等（79c40e17 实证形态）。
   * `idleCapMs <= 0` = 本腿关闭（false，回到墙钟总额唯一兜底）。
   */
  private[agent] def bgIdleCapReached(
    activities: List[(Long, Option[Long])], // (startedAtMs, lastOutputAtMs)
    nowMs: Long,
    idleCapMs: Long
  ): Boolean =
    idleCapMs > 0L && activities.nonEmpty && activities.forall { case (startedAtMs, lastOutputAt) =>
      val lastActivity = math.max(startedAtMs, lastOutputAt.getOrElse(0L))
      nowMs - lastActivity >= idleCapMs
    }

  /** Active state: forwards completion/failure to parent, restarts on Terminated. */
  private def active(
    childRef: ActorRef[AgentCommand],
    childSpawnFn: (ActorSystem, List[Message]) => IO[ActorRef[AgentCommand]],
    childName: String,
    parentRef: Option[ActorRef[AgentCommand]],
    description: String,
    agentName: String,
    subagentId: String,
    parentSessionId: String,
    resources: SharedResources,
    initialPrompt: String,
    source: String,
    extraMetadata: JsonObject,
    wsSend: Option[Json => IO[Unit]],
    restartCount: Int,
    restartHistory: List[Long],
    minBackoff: FiniteDuration,
    maxBackoff: FiniteDuration,
    maxRestarts: Int,
    withinTimeRange: FiniteDuration,
    bgWaitCapMs: Long,
    bgIdleCapMs: Long,
    bgWaitHeld: Ref[IO, Boolean],
    bgWaitCapArm: Ref[IO, Long],
    bgWaitCapFired: Ref[IO, Boolean]
  ): Behavior[AgentEvent] =
    new Behavior[AgentEvent]:
      def receive(ctx: ActorContext[AgentEvent], event: AgentEvent): IO[Behavior[AgentEvent]] =
        // 四修①（2026-10-07 修复令「空轮≠完成」）的正常完成体（空轮判据不中时
        // 原样走——逐字保持既有行为）。
        def completeNormally(messages: List[Message], bgWaitNote: Option[String] = None): IO[Behavior[AgentEvent]] =
          val text = extractLastAssistantText(messages)
          // trim guard: a whitespace-only tail ("\n\n") is NOT a real output —
          // render "(no text output)" instead of an empty-shell payload
          val payload =
            if text.trim.nonEmpty then s""""$description":\n${text.trim}"""
            else s""""$description" (no text output)"""
          // delegate 轨的 Completed 先过终态判定钩子（DelegateTerminal.onCompleted
          // ——状态矩阵批）：finish ⇒ Release 报父（payload 携带判词行）；standby
          // ⇒ 停靠（零报父零提醒）；未申报 ⇒ Idle 提醒不终判；WorkComplete ⇒
          // 开放身份收口（工作轮完成、workspace/属主保留）。subtask 轨零变化。
          // 四修②：全会话工具调用计数随行——终态确定性预检（resultText 空＋零工具
          // ⇒ 直接判未完成）的输入。
          if source == "delegate" then
            val note = bgWaitNote.fold("")(n => s"\n[delegate-bg-wait] $n")
            nebflow.core.delegate.DelegateTerminal
              .onCompleted(
                resources = resources,
                delegateId = subagentId,
                parentSessionId = parentSessionId,
                description = description,
                initialPrompt = initialPrompt,
                resultText = text,
                childRef = childRef,
                supervisorRef = ctx.self,
                sessionToolCalls = Some(sessionToolCalls(messages))
              )
              .flatMap {
                case nebflow.core.delegate.DelegateTerminal.Outcome.Release(suffix) =>
                  terminalDiffMetadata().flatMap(diff => notifyParentAndStop("completed", suffix + note, diff))
                case other =>
                  IO.raiseError(new IllegalStateException(s"completed delegate returned obsolete outcome: $other"))
              }
          else notifyParentAndStop("completed", payload, JsonObject.empty)
          end if
        end completeNormally

        // ═══ sleep-poll 禁令批 A 段：delegate 等待屏障（wait barrier）═══
        // 作者令 2026-10-20（实撞案例 delegate-nebflow-747cd36a）：delegate 会话
        // 存在未结等待型后台作业时结束回合**不再终结会话**——进入 hold，作业完成
        // 通知（makeNotifyCallback → ExternalEvent(source="background-task") 注入
        // 仍活着的 child）唤醒续跑轮 → 又一次 Completed → 复检。语义对齐
        // NodeStarter 桥的节点完成闸（BgTaskRegistry.waitingFor 同一判据权威：
        // 自有任务 sessionId==subagentId ∪ 子代理任务 rootSessionId==subagentId；
        // persistent 服务型不在等待集）。会话终结只由显式终判（等待集清空后的
        // 判定机/自申报）或作者侧取消（AgentControl/Cancelled 链）触发。
        // 兜底面（后台任务不能把 delegate 悬挂成僵尸）：每个等待期武装
        // bgWaitCapMs 兜底计时器（NodeStarter 桥同款「每等待期重臂」）；到点仍
        // 非空 ⇒ 置 capFired 闩并重发 Completed——复检回合不再 hold，带诚实注记
        // 强制终判（fail-open 交付，绝不静默丢失；注记说明未结任务及其通知将随
        // 会话终结丢失）。默认 bgWaitCapMs = BgGateWaitTimeoutMs（2h，与节点闸
        // 同值）。
        def bgWaitCapNote(waiting: List[nebflow.core.tools.BgTaskRegistry.ActiveTask]): String =
          s"background wait cap hit: completion delivered while ${waiting.size} background task(s) still pending " +
            s"(${waiting.map(t => s"'${t.description}' (${t.jobId})").mkString(", ")}) — their completion " +
            "notifications will be lost with this session (honest forced delivery; re-dispatch or re-attach if the work matters)"

        // R5（bg-turn-end barrier idle 上限放行批 2026-11-04 · 停顿审计 §4-R5）：
        // 墙钟总额兜底（bgWaitCapMs，2h）之外再加一条**空闲**上限（bgIdleCapMs，
        // 默认 15min）——等待集里每个作业都「长时间没有任何新输出」时，回合不再被
        // 一个永不结束的作业吊着（实证：79c40e17 被 `Find stand-website dir`
        // 吊了 1h49m，未到 2h 总额 ⇒ 回合不收尾 ⇒ 无 Completed ⇒ 无 leg ⇒ 合并队
        // 头不可投递）。放行语义与墙钟 cap 完全一致：置 fired 闩 + 诚实注记 + 自投
        // Completed，绝不静默丢结果。
        def bgIdleCapNote(waiting: List[nebflow.core.tools.BgTaskRegistry.ActiveTask], idleCapMs: Long): String =
          s"background wait IDLE cap hit (${idleCapMs / 60000L}min without any new output): completion delivered while " +
            s"${waiting.size} background task(s) are still pending but stalled " +
            s"(${waiting.map(t => s"'${t.description}' (${t.jobId})").mkString(", ")}) — their completion " +
            "notifications will be lost with this session (honest forced delivery; re-dispatch or re-attach if the work matters)"

        def disarmBgWaitCap: IO[Unit] = bgWaitCapArm.update(_ + 1L)

        /**
         * 下一次**真实截止时刻**的毫秒延迟 = min(墙钟腿剩余, idle 腿最早剩余)，
         * 下限 1ms。idle 腿的要害：截止时刻会随新输出**向后移**——所以每次到点
         * 都重算，而不是固定小拍去猜（这正是轮询 vs 到点消息的差别）。
         */
        def capWakeDelayMs(
          heldSinceMs: Long,
          waiting: List[nebflow.core.tools.BgTaskRegistry.ActiveTask],
          now: Long
        ): IO[Long] =
          val wallAt = heldSinceMs + bgWaitCapMs
          if bgIdleCapMs <= 0L then IO.pure(math.max(1L, wallAt - now))
          else
            waiting
              .traverse(t =>
                nebflow.core.tools.BgTaskOutputStore
                  .lastOutputAt(t.jobId)
                  .handleErrorWith(_ => IO.pure(Option.empty[Long]))
                  .map(at => (t.startedAtMs, at))
              )
              .map { activities =>
                val idleAt = activities
                  .map { case (startedAtMs, lastOutputAt) =>
                    math.max(startedAtMs, lastOutputAt.getOrElse(0L)) + bgIdleCapMs
                  }
                  .minOption
                  .getOrElse(wallAt)
                math.max(1L, math.min(wallAt, idleAt) - now)
              }
          end if
        end capWakeDelayMs

        /**
         * 武装兜底计时器（**刀一：到点消息，不轮询**）。
         *
         * 一次武装 = 臂号 +1 + 一条 `scheduleOnce` 自投的
         * [[nebflow.actor.AgentEvent.BgWaitCapTick]]。臂号唯一的用途是**作废旧
         * Tick**（`scheduleOnce` 无法撤销）：等待期结束、重臂、强制终判都会让旧
         * Tick 到点时臂号不符而被丢弃——因此「多臂并存」永远是安全的。
         *
         * 等待集已空 ⇒ 直接不武装（竞态：唤醒轮在飞，它的 Completed 会走干净
         * 放行腿）。
         */
        def armBgWaitCap(messages: List[Message]): IO[Unit] =
          val heldSince = System.currentTimeMillis()
          nebflow.core.tools.BgTaskRegistry.waitingFor(subagentId).flatMap { waiting =>
            if waiting.isEmpty then disarmBgWaitCap
            else
              capWakeDelayMs(heldSince, waiting, heldSince).flatMap { delayMs =>
                bgWaitCapArm.updateAndGet(_ + 1L).flatMap { arm =>
                  logger.info(
                    s"BackoffSupervisor: delegate $subagentId bg-wait cap armed as a TIMED MESSAGE (arm=$arm, " +
                      s"wake in ${delayMs}ms, ${waiting.size} task(s) pending) — no polling loop"
                  ) *> ctx.scheduleOnce(
                    delayMs.millis,
                    AgentEvent.BgWaitCapTick(subagentId, heldSince, arm, messages)
                  )
                }
              }
          }
        end armBgWaitCap

        def holdForBgWait(
          waiting: List[nebflow.core.tools.BgTaskRegistry.ActiveTask],
          messages: List[Message]
        ): IO[Unit] =
          bgWaitHeld.get.flatMap { already =>
            val emitOnce =
              IO.whenA(!already) {
                logger.info(
                  s"BackoffSupervisor: delegate $subagentId turn completed with ${waiting.size} background " +
                    "task(s) still running — holding completion until they finish (wait barrier, sleep-poll fix batch A)"
                ) *>
                  bgWaitHeld.set(true) *>
                  (wsSend match
                    case Some(send) =>
                      send(
                        Json.obj(
                          "type" -> "delegateBgWait".asJson,
                          "sessionId" -> subagentId.asJson,
                          "rootSessionId" -> parentSessionId.asJson,
                          "waitingCount" -> waiting.size.asJson,
                          "waiting" -> waiting.map(t => s"${t.description} (${t.jobId})").asJson
                        )
                      ).handleErrorWith(e => logger.warn(s"delegateBgWait WS frame failed: ${e.getMessage}"))
                    case None => IO.unit)
              }
            emitOnce *> armBgWaitCap(messages)
          }

        /**
         * 兜底计时器（每等待期重臂）。两条放行腿，判据都是**事件事实**而非猜测：
         *   - 墙钟总额：等待期 ≥ [[bgWaitCapMs]]（既有 2h 腿，零行为变化）；
         *   - **空闲上限（R5）**：等待集里每个作业的最近活动（max(启动时刻, 最近
         *     输出行时刻)）距今都 ≥ [[bgIdleCapMs]] ⇒ 没有哪个作业还在产出，等它
         *     也等不出东西（`bgIdleCapMs <= 0` = 关闭本腿，回到旧行为）。
         * 拍粒度 = 已拆除（刀一：**到点消息**，不是拍）。到点时刻 = min(墙钟腿截止,
         * idle 腿最早截止)，只在截止时刻**变化**时重臂一次。
         */
        /**
         * 到点 Tick 的处理（**刀一**，替代原 `capLoop` 的 `IO.sleep` 轮询）：
         *   - 臂号不符 ⇒ 陈旧 Tick，丢弃（等待期已结束/已重臂/已强制终判）；
         *   - 等待集已空 ⇒ 计时器自然终结（唤醒轮的 Completed 走干净放行腿）；
         *   - 墙钟腿或 idle 腿到点 ⇒ 既有强制终判语义**逐字不变**；
         *   - 都未到点（新输出把 idle 截止时刻推后了）⇒ 按**新的真实截止时刻**
         *     重臂（同一臂号延续本等待期——墙钟腿的 `heldSinceMs` 不变）。
         */
        def onBgWaitCapTick(heldSinceMs: Long, arm: Long, snapshot: List[Message]): IO[Behavior[AgentEvent]] =
          bgWaitCapArm.get.flatMap { cur =>
            if cur != arm then
              logger
                .info(s"BackoffSupervisor: stale bg-wait cap tick for $subagentId (arm $arm vs current $cur) — dropped")
                .as(this)
            else
              nebflow.core.tools.BgTaskRegistry.waitingFor(subagentId).flatMap {
                case Nil => disarmBgWaitCap.as(this) // 竞态：唤醒轮在飞，其 Completed 会正常放行
                case still =>
                  val now = System.currentTimeMillis()
                  val wallHit = now - heldSinceMs >= bgWaitCapMs
                  for
                    idleHit <-
                      if wallHit || bgIdleCapMs <= 0L then IO.pure(false)
                      else
                        still
                          .traverse(t =>
                            nebflow.core.tools.BgTaskOutputStore
                              .lastOutputAt(t.jobId)
                              .handleErrorWith(_ => IO.pure(Option.empty[Long]))
                              .map(at => (t.startedAtMs, at))
                          )
                          .map(activities => bgIdleCapReached(activities, now, bgIdleCapMs))
                    _ <-
                      if wallHit then
                        logger.warn(
                          s"BackoffSupervisor: delegate $subagentId bg-wait cap (${bgWaitCapMs}ms) hit with " +
                            s"${still.size} task(s) pending — forcing honest terminal delivery (timed message, arm=$arm)"
                        ) *> forceTerminalDelivery(still, snapshot)
                      else if idleHit then
                        logger.warn(
                          s"BackoffSupervisor: delegate $subagentId bg-wait IDLE cap (${bgIdleCapMs}ms) hit with " +
                            s"${still.size} task(s) pending and no new output — releasing the turn end (R5: a never-finishing " +
                            s"background job must not hold the completion forever) (timed message, arm=$arm)"
                        ) *> forceTerminalDelivery(still, snapshot)
                      else
                        // 未到点：截止时刻被新输出推后 ⇒ 按新截止时刻**重臂**同一等待期。
                        capWakeDelayMs(heldSinceMs, still, now).flatMap { delayMs =>
                          logger.info(
                            s"BackoffSupervisor: delegate $subagentId bg-wait cap re-armed (arm=$arm) — " +
                              s"deadline moved, next wake in ${delayMs}ms"
                          ) *> ctx.scheduleOnce(
                            delayMs.millis,
                            AgentEvent.BgWaitCapTick(subagentId, heldSinceMs, arm, snapshot)
                          )
                        }
                  yield this
                  end for
              }
          }

        /** 强制终判（两腿共用）：孤儿登记 → 置闩 → 自投 Completed（带诚实注记）。 */
        def forceTerminalDelivery(
          still: List[nebflow.core.tools.BgTaskRegistry.ActiveTask],
          messages: List[Message]
        ): IO[Unit] = // ── 孤儿登记（修复批 2026-10-24 fix 3「两种死法统一收口」）──
          // 强制终判 = 本会话终结、这些任务的完成通知将无处投递。逐条写入持久
          // 账本（含当时输出尾段快照）：会话若被复活/重派拾取，账本条目即完整
          // 证据链；completion 事件此后照常经 BashTool recordCompletion 原位升级
          // orphan 条目（kind 不降级）。fail-soft：账本故障绝不阻断强制终判。
          still
            .traverse { t =>
              nebflow.core.tools.BgTaskOutputStore
                .read(t.jobId, 0L)
                .map(out => out.map(o => o.output.takeRight(2000)).filter(_.trim.nonEmpty))
                .handleErrorWith(_ => IO.pure(Option.empty[String]))
                .map(tail => (t.jobId, t.description, tail))
            }
            .flatMap(rows => nebflow.core.tools.BgTaskLedger.markOrphaned(subagentId, rows)) *>
            bgWaitCapFired.set(true) *>
            bgWaitHeld.set(false) *>
            // **刀一**：定时器已改为「到点消息」，撤不掉在途 Tick —— 用臂号让它在
            // 到点时自认陈旧（处理器臂号不符即丢弃），因此这里只推进臂号。
            disarmBgWaitCap *>
            // 诊断修复批 2026-10-24：cap 触发时**重载会话最新 transcript**——
            // hold 时武装捕获的 messages 是旧轮快照（hold 期唤醒轮的新产出
            // 不在其中），直接强投会把过期 payload 交付父会话；重载失败/
            // 空读回落旧快照（既有行为，绝不静默丢失复检事件本身）。
            latestMessages(subagentId, messages)
              .flatMap(latest => (ctx.self ! AgentEvent.Completed(subagentId, latest)).void)

        /**
         * cap 触发的强制终判用消息快照：优先会话持久层的最新 transcript
         * （断点续跑同款读面），空读/读失败回落调用方给的 hold 期快照。
         */
        def latestMessages(sessionId: String, fallback: List[Message]): IO[List[Message]] =
          resources.sessionStore
            .loadMessagesForSession(sessionId)
            .map(latest => if latest.nonEmpty then latest else fallback)
            .handleErrorWith(e =>
              logger
                .warn(
                  s"bg-wait cap: fresh transcript reload failed for $sessionId (${e.getMessage}) — using the held snapshot"
                )
                .as(fallback)
            )

        /**
         * 等待屏障门（Completed 先过此门再进终判）。三态裁决：
         *   - Held ⇒ 已 hold（不进终判，调用方保持 active 行为等唤醒轮）；
         *   - Proceed ⇒ 正常进终判（从未 hold / 等待集已清空的释放腿——干净放行）；
         *   - ForceWithNote ⇒ cap 兜底触发的强制终判，注记并入交付 payload。
         */
        sealed trait BgWaitDecision
        case object BgWaitHeld extends BgWaitDecision
        case object BgWaitProceed extends BgWaitDecision
        final case class BgWaitForce(note: String) extends BgWaitDecision

        def delegateBgWaitGate(messages: List[Message]): IO[BgWaitDecision] =
          if source != "delegate" || !Defaults.DelegateBgWaitHoldEnabled then IO.pure(BgWaitProceed)
          else
            bgWaitCapFired.get.flatMap { fired =>
              if fired then
                // cap 触发后的复检回合：不再 hold，带注记强制终判（fail-open 交付）。
                bgWaitCapFired.set(false) *> bgWaitHeld.set(false) *>
                  nebflow.core.tools.BgTaskRegistry.waitingFor(subagentId).flatMap {
                    case Nil => IO.pure(BgWaitProceed: BgWaitDecision) // 兜底触发与唤醒轮竞态：实际已清空 ⇒ 干净放行
                    case still =>
                      // R5：「注记指哪条腿」按当下事实判——等待集整体空闲（无新输出）
                      // 已达 idle 上限 ⇒ idle 注记（说明等的是「不动的作业」）；否则
                      // 走既有墙钟总额注记。两注记都是诚实面（任务/描述/jobId 全带）。
                      if bgIdleCapMs <= 0L then IO.pure(BgWaitForce(bgWaitCapNote(still)): BgWaitDecision)
                      else
                        still
                          .traverse(t =>
                            nebflow.core.tools.BgTaskOutputStore
                              .lastOutputAt(t.jobId)
                              .handleErrorWith(_ => IO.pure(None))
                              .map(at => (t.startedAtMs, at))
                          )
                          .map { activities =>
                            val now = System.currentTimeMillis()
                            val note =
                              if bgIdleCapReached(activities, now, bgIdleCapMs) then bgIdleCapNote(still, bgIdleCapMs)
                              else bgWaitCapNote(still)
                            BgWaitForce(note): BgWaitDecision
                          }
                  }
              else
                // P2 death-watch 活性复检（10-30 合并闸卡死 3 小时病灶收口：
                // e0478867 进程已死 + 行残留 ⇒ 每回合 Completed 被 hold 到 2h cap）：
                // hold 前探活等待集——进程已死/属主会话已殁的行即收割（注销 + 代行
                // 终态记账 + 沿等待链注入终态通知），复检等待集再决 hold/放行。
                // 零墙钟：活性 = proc.isAlive/会话在册的内核事实。
                nebflow.core.tools.BgTaskReaper
                  .reapDead(Some(resources.actorSystem), Some(resources.agentRegistry))
                  .flatTap { r =>
                    IO.whenA(r.reaped.nonEmpty)(
                      logger.info(
                        s"BackoffSupervisor: delegate $subagentId bg-wait recheck reaped " +
                          r.reaped.map { case (id, reason) => s"$id($reason)" }.mkString(", ") +
                          " — re-evaluating hold"
                      )
                    )
                  } *>
                  nebflow.core.tools.BgTaskRegistry.waitingFor(subagentId).flatMap {
                    case Nil =>
                      bgWaitHeld.get.flatMap { held =>
                        (disarmBgWaitCap *>
                          IO.whenA(held) {
                            bgWaitHeld.set(false) *>
                              logger.info(
                                s"BackoffSupervisor: delegate $subagentId background task(s) all finished — releasing held completion"
                              )
                          }).as(BgWaitProceed)
                      }
                    case waiting => holdForBgWait(waiting, messages).as(BgWaitHeld)
                  }
            }

        event match
          case AgentEvent.Completed(_, messages) =>
            // 四修①两级处置（作者令「有重启预算→自动重启续推；预算耗尽→终判
            // blocked」）：全会话零工具调用＋（终文空 或 止于 max_tokens 空烧
            // stub）= 空烧形态，NOT a completion。
            //   - 预算未尽 → 走既有 respawn 链（断点续跑＋continue 注入＋
            //     subagentRetry WS 帧＋"restarting" 库态——maxRestarts=2 在挂）；
            //   - 预算耗尽 → delegate 轨照常过终态判定机：确定性预检
            //     （DelegateTerminal，四修②）据「零工具＋空文」直接判 blocked，
            //     不依赖 LLM 判；subtask 轨 failed 终态（failureType=empty-burn）。
            if !isEmptyBurnCompletion(messages) then
              // A 段等待屏障先于终判：有未结等待型后台作业 ⇒ hold 唤醒复检；
              // cap 兜底触发 ⇒ 带注记强制终判。
              delegateBgWaitGate(messages).flatMap {
                case BgWaitHeld =>
                  IO.pure(
                    active(
                      childRef,
                      childSpawnFn,
                      childName,
                      parentRef,
                      description,
                      agentName,
                      subagentId,
                      parentSessionId,
                      resources,
                      initialPrompt,
                      source,
                      extraMetadata,
                      wsSend,
                      restartCount,
                      restartHistory,
                      minBackoff,
                      maxBackoff,
                      maxRestarts,
                      withinTimeRange,
                      bgWaitCapMs,
                      bgIdleCapMs,
                      bgWaitHeld,
                      bgWaitCapArm,
                      bgWaitCapFired
                    )
                  )
                case BgWaitProceed => completeNormally(messages)
                case BgWaitForce(note) => completeNormally(messages, Some(note))
              }
            else
              val now = System.currentTimeMillis()
              val recent = restartHistory.filter(t => now - t < withinTimeRange.toMillis)
              if recent.length < maxRestarts then
                logger.warn(
                  s"BackoffSupervisor: child $childName completed EMPTY (no text, zero tool calls — empty burn), " +
                    s"restarting #${recent.length + 1}/$maxRestarts instead of reporting completion"
                )
                respawnSafely(ctx, "empty-completion", now, recent)
              else if source == "delegate" then
                logger.warn(
                  s"BackoffSupervisor: child $childName completed EMPTY with restart budget exhausted ($maxRestarts) — terminal verdict left to the deterministic precheck (blocked)"
                )
                completeNormally(messages)
              else
                logger.warn(
                  s"BackoffSupervisor: child $childName completed EMPTY with restart budget exhausted ($maxRestarts) — honest failed terminal"
                )
                notifyParentAndStop(
                  "failed",
                  s""""$description": empty completion — the worker produced no text and made no tool calls (empty burn), unresolved after $maxRestarts restarts""",
                  JsonObject(
                    "failedSessionId" -> subagentId.asJson,
                    "retryable" -> false.asJson,
                    "failureType" -> "empty-burn".asJson
                  )
                )
              end if

          case AgentEvent.Failed(sessionId, error) =>
            val sessionInfo =
              if sessionId.nonEmpty then s" [session=$sessionId]" else ""
            // ═══ 错误冻结腿（错误冻结批 2026-11-01 · 作者令逐字「所有因错误状态的
            // delegate 应该上报之后 要能保留状态 冻结态 方便被唤醒 接着做」）═══
            // delegate 轨的回合级失败不再走 failed 终止：同一会话（同 id、同历史、
            // 同 worktree、同合并队列位）**就地冻结**——registry 置 Frozen + 冻结槽落
            // 盘 + 台账行 status=frozen + 一条**冻结回执**（eventType="frozen"，不是
            // failed）入父会话持久邮箱 + 面板 delegateFrozen 帧；actor 保持 active
            // 行为等待唤醒（唤醒才是重试，引擎不做无限重试）。判词诚实：引擎/上游
            // 故障绝不写成 agent 的失败。subtask 轨逐字保持既有 failed 终态（重派成本
            // 低，无留态价值）。
            if source == "delegate" && nebflow.shared.Defaults.DelegateFreezeEnabled then
              val fzReason = nebflow.core.delegate.DelegateFreeze.classify(
                error.errorType.toString,
                error.message
              )
              freezeAndHold(s"${error.message}$sessionInfo", fzReason)
            else
              val retryable = error.errorType match
                case AgentErrorType.LlmFailed => true
                case AgentErrorType.Timeout => true
                case _ => false
              val failureMeta = JsonObject(
                "failedSessionId" -> subagentId.asJson,
                "retryable" -> retryable.asJson,
                "failureType" -> error.errorType.toString.asJson
              )
              // file-diff → 委托结果行（同完成腿）：失败前已写入的文件同样出 diff
              // 载荷（用户预期 = 任何改了文件的委托批；失败批看「写到哪里了」同价）；
              // 合并提交统计同面随行（合并轮夭折形态下锚定多半不可读 ⇒ None = 不带键）。
              val withFc =
                if source == "delegate" then terminalDiffMetadata().map(_.deepMerge(failureMeta))
                else IO.pure(failureMeta)
              withFc.flatMap(fc =>
                notifyParentAndStop("failed", s""""$description": ${error.message}$sessionInfo""", fc)
              )
            end if

          // 刀一：等待屏障兜底计时器的**到点消息**（`ctx.scheduleOnce` 自投）。
          // 凡不是本子会话的 Tick 一律不认（错投防护）。
          case tick: AgentEvent.BgWaitCapTick if tick.sessionId == subagentId =>
            onBgWaitCapTick(tick.heldSinceMs, tick.arm, tick.snapshot)

          case _: AgentEvent.BgWaitCapTick =>
            IO.pure(this) // 别家子会话的 Tick（本监督者是 per-child 实例，正常到不了这里）

          case AgentEvent.Cancelled(_, reason) =>
            // 错误冻结批（2026-11-01）：**配额/额度类取消**是「外部条件不足」而非作者
            // 意图——delegate 轨就地冻结（可等额度恢复后唤醒续做），显式取消（作者
            // AgentControl cancel / 项目卸载 / 父会话删除 / 卡死硬取消）仍走 cancelled
            // 终态逐字不变（取消意图必须落地成终态，绝不被冻结漂移）。空依赖
            // （resource-empty / 孤儿空烧）同归冻结腿——它们是「转不动了」而非
            // 「不要了」。
            if source == "delegate" && nebflow.shared.Defaults.DelegateFreezeEnabled &&
              isQuotaLikeCancel(reason)
            then
              freezeAndHold(
                s"cancelled by the engine: ${if reason.nonEmpty then reason else "quota/budget exhausted"}",
                nebflow.core.delegate.DelegateFreeze.Reason.CancelledByQuota
              )
            else
              // AgentControl cancel（spec §3.2）：与自然终态同路清理——父
              // ExternalEvent(source 保持 delegate/subtask → barrier 正确释放)
              // + taskStore cancelled + registry 移除 + child Stop + 自停。
              // 与 Completed/Failed 竞态：mailbox 串行，首个终态胜出，supervisor
              // 停止后其余消息被丢弃（actor 语义），无双重通知。
              val reasonSuffix = if reason.nonEmpty then s" — $reason" else ""
              notifyParentAndStop(
                "cancelled",
                s""""$description": cancelled by Nebula via AgentControl$reasonSuffix""",
                JsonObject(
                  "failedSessionId" -> subagentId.asJson,
                  "retryable" -> false.asJson,
                  "failureType" -> "cancelled".asJson,
                  "cancelled" -> true.asJson,
                  "reason" -> reason.asJson
                ),
                taskStatusOverride = Some("cancelled")
              )
            end if
        end match
      end receive

      override def onSignal(ctx: ActorContext[AgentEvent], signal: SystemSignal): IO[Behavior[AgentEvent]] =
        lazy val workspacePolicy = scala.util.Try {
          val snapshot = nebflow.core.delegate.WorkspaceLedger.default.snapshot
          val workspace = snapshot.workspaces.find(_.owner == subagentId)
          (
            workspace.exists(_.lifecycle.startsWith("closed-")),
            workspace.nonEmpty || snapshot.mergers.exists(_.delegateId == subagentId)
          )
        }
        signal match
          case SystemSignal.Terminated(_) if workspacePolicy.toOption.exists(_._1) =>
            resources.agentRegistry.update(_ - subagentId) *>
              nebflow.core.project.DelegateFlowHosts.unmount(subagentId) *>
              logger.info(s"Workspace owner $subagentId closed; supervisor will not restart it") *>
              IO.pure(Behaviors.stopped)
          case SystemSignal.Terminated(_) if workspacePolicy.isFailure || workspacePolicy.toOption.exists(_._2) =>
            freezeAndHold(
              "Managed workspace execution stopped; explicit manual recovery is required",
              nebflow.core.delegate.DelegateFreeze.Reason.EngineFault,
              childAlive = false
            )
          case SystemSignal.Terminated(_) =>
            val now = System.currentTimeMillis()
            val recentRestarts = restartHistory.filter(t => now - t < withinTimeRange.toMillis)

            if recentRestarts.length < maxRestarts then
              logger.info(
                s"BackoffSupervisor: child $childName crashed, restarting #${recentRestarts.length + 1}/$maxRestarts"
              )
              respawnSafely(ctx, "crashed", now, recentRestarts)
            else
              logger.warn(
                s"BackoffSupervisor: child $childName exceeded maxRestarts " +
                  s"($maxRestarts in ${withinTimeRange}), giving up"
              ) *>
                (if source == "delegate" && Defaults.DelegateFreezeEnabled then
                   // 错误冻结批（2026-11-01）：监督耗尽 = **引擎侧故障**——delegate 轨
                   // 不再落 failed（工作现场/分支/worktree 全在，重派即整段白干），
                   // 就地冻结等唤醒（唤醒 = 同 id 复活，见 DelegateRevival 冻结腿）。
                   // child 已死（Terminated 触发）⇒ 冻结后本监督者自停（registry 记录
                   // 保留 = 面板与 Mail 唤醒的地址锚）。
                   freezeAndHold(
                     s"the agent crashed and could not recover after $maxRestarts restarts",
                     nebflow.core.delegate.DelegateFreeze.Reason.EngineFault,
                     childAlive = false
                   )
                 else
                   notifyParentAndStop(
                     "failed",
                     s""""$description": agent crashed and could not recover after $maxRestarts restarts""",
                     JsonObject(
                       "failedSessionId" -> subagentId.asJson,
                       "retryable" -> false.asJson,
                       "failureType" -> "supervision-exhausted".asJson
                     )
                   ))
            end if
        end match
      end onSignal

      /**
       * 错误冻结腿（错误冻结批 2026-11-01 · 作者令「所有因错误状态的 delegate 应该
       * 上报之后 要能保留状态 冻结态 方便被唤醒 接着做」）。
       *
       * 语义（判词诚实铁律）：错误 = **冻结态**，不是终止态。本腿做四件事、四件都
       * 不出终态判词：
       *   ① 冻结落盘 —— [[nebflow.core.delegate.DelegateFreeze.park]]：registry 冻结槽
       *      （frozenSince/frozenReason/frozenDetail）+ 台账行 status=frozen（不进 boot
       *      收殓视野，见 SubAgentStartupRecovery.InFlightStatuses）；
       *   ② registry 状态 = `AgentStatus.Frozen`（面板徽标/`freezeReason` 面；与
       *      standby 的 Waiting 显式区分）+ 合并队列处置（持窗成员 requeueForLane 退回
       *      waiting、**保留 FIFO 位**）；
       *   ③ 冻结回执入父会话**持久邮箱**（`eventType="frozen"` —— **不是**
       *      failed/completed/cancelled：不抢终态幂等键、不与后续真终态互吞）+
       *      `delegateFrozen` WS 帧（面板实时徽标 + 原因 tooltip）；
       *   ④ **不**调 notifyParentAndStop —— 不写判词行、不关合并队列账、不拆 registry
       *      行、不发 child Stop（child 若已死则本监督者自停，registry 记录保留 = 唤醒
       *      地址锚）。
       *
       * @param childAlive child actor 是否仍在（Failed/Cancelled 事件 = 在；Terminated /
       *                   respawn 失败 = 不在——后者冻结后本监督者自停）
       */
      private def freezeAndHold(
        detail: String,
        reason: nebflow.core.delegate.DelegateFreeze.Reason,
        childAlive: Boolean = true
      ): IO[Behavior[AgentEvent]] =
        val d = nebflow.core.delegate.DelegateFreeze.compactDetail(detail)
        val workspace = nebflow.core.delegate.DelegateRegistry
          .find(subagentId)
          .map(r => r.worktree.getOrElse(r.cwd))
          .getOrElse("")
        val receipt =
          nebflow.core.delegate.DelegateFreeze.receiptText(
            delegateId = subagentId,
            description = description,
            reason = reason,
            detail = d,
            workspace = workspace
          )
        // 冻结通知策略批（2026-11-05 作者令两连：「然后frozen这种可恢复的，不需要通知，」
        // + 10:36「不论是节点还是delegate，由于provider 故障，都不需要向上通知，冻结等待
        // 恢复就行…因为provider 故障上级也基本死了，汇报给上级没用」）：
        // 分类单点 = `FreezeDisplay`（经 `FreezeNotice`）——**provider 故障族**
        // （llm-failure / cancelled-by-quota）⇒ **零向上投递**（父会话收不到任何东西），
        // 面板标「已冻结 · 待你处理」，引擎自己探测恢复后续跑（次数上限 + 递增间隔）；
        // **确定性缺陷**（tool-failure / engine-fault）⇒ 也不逐条推，交给同波聚合
        // （`FreezeAlertSweep` 一拍内合并成一条）。静默总闸关闭（回滚开关）时保留旧回执。
        val tier = nebflow.core.delegate.FreezeNotice.tierOfReason(reason, d)
        val quiet = nebflow.shared.Defaults.DelegateFreezeSilence
        val frozenEvent = AgentCommand.ExternalEvent(
          source = "delegate",
          eventType = "frozen",
          payload = receipt,
          metadata = JsonObject(
            "description" -> description.asJson,
            "agentName" -> agentName.asJson,
            "frozenDelegateId" -> subagentId.asJson,
            "frozenReason" -> nebflow.core.delegate.DelegateFreeze.wire(reason).asJson,
            "frozenReasonLabel" -> nebflow.core.delegate.DelegateFreeze.reasonLabelZh(reason).asJson,
            "noticeTier" -> nebflow.core.delegate.FreezeNotice.tierWire(tier).asJson,
            "frozenDetail" -> d.asJson,
            "wakeAddress" -> s"delegate:$subagentId".asJson,
            "retryable" -> true.asJson,
            "frozen" -> true.asJson
          ).deepMerge(extraMetadata),
          // 身份键带冻结序号（`restartHistory.size`）：同一 delegate 二次冻结是**另
          // 一份回执**（唤醒后又冻了一次必须让父会话看见），若沿用裸 id 会被
          // `DeliveredReceiptLedger` 的不过时 intake 闸当重复件吞掉，父会话永远
          // 不知道第二次冻结。seq=0（首冻）与既有形态同长。
          correlationId = Some(s"delegate-frozen-$subagentId-${restartHistory.size}")
        )
        val persistAndAnnounce =
          nebflow.core.delegate.DelegateFreeze
            .park(resources, subagentId, parentSessionId, reason, d)
            .flatMap { frozenAt =>
              nebflow.core.delegate.DelegateFreeze.markRegistryState(resources, subagentId, reason) *>
                DelegateBudget.release(subagentId) *>
                // 通知面：静默总闸开 ⇒ 监督者**一条都不推**（provider 故障族靠面板 +
                // 引擎自愈；确定性缺陷由同波聚合发一条）；只记一行 INFO（静默不等于
                // 无声，日志与面板可查）。总闸关（回滚开关）⇒ 逐字节回旧回执行为。
                (if quiet then
                   logger.info(
                     s"[delegate-freeze] $subagentId frozen (${nebflow.core.delegate.DelegateFreeze.wire(reason)}, " +
                       s"tier=${nebflow.core.delegate.FreezeNotice.tierWire(tier)}) — no upward notification by design " +
                       "(provider faults are infrastructure faults, not task failures); the panel badge + panel digest carry it"
                   )
                 else
                   MailboxProducer
                     .deliver(
                       resources,
                       parentSessionId,
                       frozenEvent,
                       identity = s"frozen-$subagentId-${frozenAt.getOrElse(0L)}",
                       kind = "delegate-frozen",
                       coalesce = false,
                       producer = "backoff-supervisor"
                     )
                     .handleErrorWith(e =>
                       logger.warn(s"[delegate-freeze] frozen receipt delivery failed for $subagentId: ${e.getMessage}")
                     )) *>
                // 面板即时读数（静默统计帧）：冻结一落盘就刷新「N 条已冻结（可唤醒）」。
                FreezeAlertSweep
                  .digestTick(resources, wsSend)
                  .handleErrorWith(e =>
                    logger.warn(s"[delegate-freeze] digest refresh failed for $subagentId: ${e.getMessage}")
                  ) *>
                wsSend.fold(IO.unit) { send =>
                  send(
                    Json.obj(
                      "type" -> "delegateFrozen".asJson,
                      "sessionId" -> subagentId.asJson,
                      "rootSessionId" -> parentSessionId.asJson,
                      "agentId" -> subagentId.asJson,
                      "frozenSince" -> frozenAt.getOrElse(System.currentTimeMillis()).asJson,
                      "frozenReason" -> nebflow.core.delegate.DelegateFreeze.wire(reason).asJson,
                      "frozenReasonLabel" -> nebflow.core.delegate.DelegateFreeze.reasonLabelZh(reason).asJson,
                      "noticeTier" -> nebflow.core.delegate.FreezeNotice.tierWire(tier).asJson,
                      "tierLabel" -> nebflow.core.delegate.FreezeNotice.tierLabelZh(tier).asJson,
                      "notified" -> (!quiet).asJson,
                      "frozenDetail" -> d.asJson,
                      "wakeAddress" -> s"delegate:$subagentId".asJson,
                      "childAlive" -> childAlive.asJson
                    )
                  ).handleErrorWith(e => logger.warn(s"delegateFrozen WS frame failed: ${e.getMessage}"))
                }
            }
        persistAndAnnounce *>
          logger
            .warn(
              s"[delegate-freeze] delegate $subagentId FROZEN (reason=${nebflow.core.delegate.DelegateFreeze
                  .wire(reason)}, childAlive=$childAlive) — no failure verdict; wake via `delegate:$subagentId` or the panel: $d"
            )
            .as(
              if !childAlive then Behaviors.stopped[AgentEvent]
              else
                active(
                  childRef,
                  childSpawnFn,
                  childName,
                  parentRef,
                  description,
                  agentName,
                  subagentId,
                  parentSessionId,
                  resources,
                  initialPrompt,
                  source,
                  extraMetadata,
                  wsSend,
                  restartCount,
                  restartHistory,
                  minBackoff,
                  maxBackoff,
                  maxRestarts,
                  withinTimeRange,
                  bgWaitCapMs,
                  bgIdleCapMs,
                  bgWaitHeld,
                  bgWaitCapArm,
                  bgWaitCapFired
                )
            )
      end freezeAndHold

      /**
       * #692 修批（重启屏障健壮化）：respawn 链的崩溃安全壳。respawn 本体是长
       * for-comprehension（写库态 → 退避 sleep → spawn → registry 回写 → 注入
       * continue → 预算重挂），中途任何一步失败（spawn 工厂异常 / session store
       * 读炸 / registry 写失败）原来会**静默炸掉本 actor**——"restarting" 任务行
       * 已写、无人终态化，正是 10-21 实测 7.5h 僵尸（retryCount=1 卡 restarting）
       * 的源头形态之一。兜底 = 失败转诚实 failed 终态（照常走 notifyParentAndStop
       * 的全套清理链），宁可响亮失败、绝不留无主行。行内已有局部
       * handleErrorWith 的步骤（库态写/消息加载）不受影响；本壳只接真正的意外。
       */
      private def respawnSafely(
        ctx: ActorContext[AgentEvent],
        trigger: String,
        now: Long,
        recentRestarts: List[Long]
      ): IO[Behavior[AgentEvent]] =
        respawn(ctx, trigger, now, recentRestarts).handleErrorWith { e =>
          logger
            .error(
              s"BackoffSupervisor: respawn chain FAILED for $childName ($trigger): ${e.getMessage} — " +
                "falling back to an honest terminal (a stuck 'restarting' row is never left behind)"
            )
            .as(()) *>
            (if source == "delegate" && Defaults.DelegateFreezeEnabled then
               // 错误冻结批（2026-11-01）：respawn 链中途失败 = **引擎侧故障**（spawn
               // 工厂异常 / session store 读炸 / registry 写失败）——delegate 轨就地
               // 冻结（可唤醒续做），绝不把引擎故障写成 agent 的 failed；subtask 轨
               // 逐字保持既有 failed 终态。
               freezeAndHold(
                 s"supervisor respawn failed mid-restart ($trigger): ${Option(e.getMessage)
                     .getOrElse(e.getClass.getSimpleName)}",
                 nebflow.core.delegate.DelegateFreeze.Reason.EngineFault,
                 childAlive = false
               )
             else
               notifyParentAndStop(
                 "failed",
                 s""""$description": supervisor respawn failed mid-restart ($trigger): ${Option(e.getMessage)
                     .getOrElse(e.getClass.getSimpleName)} — task force-failed instead of stalling in 'restarting'""",
                 JsonObject(
                   "failedSessionId" -> subagentId.asJson,
                   "retryable" -> true.asJson,
                   "failureType" -> "respawn-failed".asJson
                 )
               ))
        }

      /**
       * 共享 respawn 链（四修① 提取；原 Terminated 腿逐字迁移，行为保持）：
       * 崩溃腿（trigger="crashed"）与空烧续推腿（trigger="empty-completion"，
       * 四修①）共用——指数退避＋断点续跑＋registry 回写＋continue 注入＋预算
       * 重挂，仅日志/审计措辞与续推 nudge 文案随 trigger 分叉。
       *
       * @param trigger "crashed" | "empty-completion"（四修① 空轮≠完成的续推腿）
       */
      private def respawn(
        ctx: ActorContext[AgentEvent],
        trigger: String,
        now: Long,
        recentRestarts: List[Long]
      ): IO[Behavior[AgentEvent]] =
        // R1 缝隙 C 修收口（进程内 respawn 腿，2026-10-29 三路径批 · 报告二 §1.3
        // 硬验收）：**取消墓碑在场的 delegate 永不 respawn**。runbook：doCancel 的
        // 降级兜底（回执超窗）只 Stop child——监督者若只是慢（未死），child 的
        // Terminated 原样走进本链，把已 cancelled 的行翻回 restarting 并 spawn
        // 新内核 = 墓碑被进程内复活腿无视（DelegateRevival 七闸管不到本腿）。闸在
        // respawn 头部（crashed 与 empty-completion 两腿共用本单点）读
        // DelegateRegistry 墓碑：在场 ⇒ 不写 restarting、不 spawn，直接落
        // cancelled 终态（父通知照常——兜底路径本就欠一次诚实的取消通知）。判据与
        // DelegateRevival.eligibility 同源：墓碑在 = 永不复活。读失败/无记录 =
        // fail-open 照旧 respawn（读面故障绝不阻断崩溃恢复；delegate 无 registry
        // 记录的形态本就没有墓碑）。
        IO
          .blocking(nebflow.core.delegate.DelegateRegistry.find(subagentId))
          .handleErrorWith(e =>
            logger
              .warn(
                s"BackoffSupervisor: cancel-tombstone probe failed for $subagentId (respawn proceeds): ${e.getMessage}"
              )
              .as(None)
          )
          .flatMap {
            case Some(rec) if rec.cancelRequestedAt.isDefined =>
              logger
                .warn(
                  s"BackoffSupervisor: respawn of $childName ($trigger) REFUSED — cancel tombstone on record " +
                    s"(requested by ${rec.cancelRequestedBy.getOrElse("?")} at ${rec.cancelRequestedAt.get}); " +
                    "a cancelled delegate is never revived — settling cancelled instead"
                )
                .as(()) *>
                notifyParentAndStop(
                  "cancelled",
                  s""""$description": respawn refused — cancel tombstone on record (a cancelled delegate is never revived)""",
                  JsonObject(
                    "failedSessionId" -> subagentId.asJson,
                    "retryable" -> false.asJson,
                    "failureType" -> "cancelled".asJson,
                    "cancelled" -> true.asJson,
                    "reason" -> "cancel tombstone present — respawn refused".asJson
                  ),
                  taskStatusOverride = Some("cancelled")
                )
            case _ => respawnBody(ctx, trigger, now, recentRestarts)
          }

      /** respawn 链本体（R1-C 收口批自 respawn 抽出，逐字未改——墓碑闸在其上游）。 */
      private def respawnBody(
        ctx: ActorContext[AgentEvent],
        trigger: String,
        now: Long,
        recentRestarts: List[Long]
      ): IO[Behavior[AgentEvent]] =
        // Phase 3 去重(行为保持重构,2026-09-25):原三行手写延迟计算
        // (min(minBackoff×2^n, maxBackoff) + 抖动[0,1000)ms)收口为
        // restartDelayMs 纯函数——指数+封顶复用 shared Retry.delayAfter,
        // 事后抖动留站点。delay 语义/单位(ms)不变。
        val delay = restartDelayMs(recentRestarts.length, minBackoff, maxBackoff)
        val newRestartCount = restartCount + 1
        val newHistory = now :: recentRestarts

        val triggerLine =
          if trigger == "crashed" then
            s"child $childName crashed, restarting #$newRestartCount/$maxRestarts after ${delay}ms backoff"
          else
            s"child $childName ended empty (no text, zero tool calls — empty burn), pushing again #$newRestartCount/$maxRestarts after ${delay}ms backoff"
        val nudgeText =
          if trigger == "crashed" then
            "[system] Your previous turn was interrupted by a crash. " +
              "Please continue your task from where you left off."
          else
            "[system] Your previous turn ended with NO output and NO tool calls " +
              "(the output budget was likely burned on thinking). Continue the task now: " +
              "use tools to make real progress and produce the deliverable as text."

        // P3.3: emit observability WS event so the frontend can show
        // "sub-agent restarting (1/2)…" in the UI.
        val retryEvent = Json.obj(
          "type" -> "subagentRetry".asJson,
          "agentName" -> agentName.asJson,
          "childSessionId" -> subagentId.asJson,
          "restartCount" -> newRestartCount.asJson,
          "maxRestarts" -> maxRestarts.asJson,
          "backoffMs" -> delay.asJson,
          "description" -> description.asJson
        )
        val notifyIO = wsSend match
          case Some(send) =>
            send(retryEvent).handleErrorWith(e => logger.warn(s"subagentRetry WS event failed: ${e.getMessage}"))
          case None => IO.unit

        for
          _ <- logger.info(s"BackoffSupervisor: $triggerLine")
          // A 段等待屏障：respawn 后旧 hold 期作废（兜底计时器持有的 messages
          // 是崩溃前旧轮快照）——撤计时器与 hold 状态，续跑轮的 Completed 重新
          // 评估等待集。（刀一：在途 Tick 靠臂号作废，不能 cancel。）
          _ <- bgWaitCapArm.update(_ + 1L) *> bgWaitHeld.set(false) *> bgWaitCapFired.set(false)
          _ <- notifyIO
          _ <- resources.subAgentTaskStore
            .updateStatus(
              parentSessionId,
              subagentId,
              "restarting",
              retryCount = Some(newRestartCount)
            )
            .handleErrorWith(e => logger.warn(s"subAgentTaskStore update failed: ${e.getMessage}"))
          _ <- IO.sleep(delay.millis)
          // Spec C2 (restart): registry 记录跨 respawn 刷新——先取崩溃前
          // 记录，spawn 后以新 ref 回写。缺了这步 respawn 的 child 会从
          // agentRegistry 消失（AgentControl list / TaskStuckWatcher 全盲）。
          // 本设计新增项（R2-c §2.3 / §7#4）：崩溃前的槽位清理——旧内核的
          // pending ask 卡是僵尸卡（答了也没人接），respawn 后新 child 会
          // 重新提问（新 requestId）。清理 = 移除全部 sourceSession 命中的
          // hub 槽 + 广播 askUserClosed（前端待办条行消失，重连不再重放）。
          _ <- cleanupPendingAsks(resources, subagentId)
          // 预算：旧通道释放（respawn 视为一次新的执行窗口 → 重新计时）。
          _ <- DelegateBudget.release(subagentId)
          oldRecord <- resources.agentRegistry.get.map(_.get(subagentId))
          // Crash recovery: load persisted messages from the session store
          // so the child can resume from where it left off (断点续跑).
          // If no messages are found (e.g. first turn crashed before any
          // persist), fall back to re-injecting the original prompt.
          recoveredMessages <- resources.sessionStore
            .loadMessagesForSession(subagentId)
            .handleErrorWith(e =>
              logger.warn(s"BackoffSupervisor: failed to load messages for $subagentId: ${e.getMessage}").as(Nil)
            )
          // ── 缺口②（子代理等待三缺口批 2026-10-25）：恢复点的在飞工具调用终结面──
          // 990bd848 实证：checkpoint 断点里无 result 的 tool_use 原样重进（自毒
          // 根因）+ 幽灵工具相位让 watcher 掐杀恢复轮 → 重启配额烧尽。respawn 三件：
          //   ① 孤儿子会话处置（唯一等它的人已死——Stop + 结算，防 token 僵尸）；
          //   ② transcript 修复：悬挂 tool_use 补 recovered-after-crash 错误
          //      result（恢复会话看见失败事实，自行重试该步或绕行）；
          //   ③ registry 恢复记录清幽灵工具相位（下方 refreshed 处）。
          _ <- SubagentCrashRecovery.disposeOrphanChildren(resources, subagentId)
          // ② 续跑交接单（无感恢复批 2026-11-07，单点 [[nebflow.core.ResumeHandoff]]）：
          //    断点停在工具调用中途时，模型收到的是**那次调用的 tool_result**（无感续跑，
          //    作者令「把那一轮的工具调用结果发过去」）；中段悬挂就地修补；无悬挂 ⇒
          //    逐字回落改前文案（`nudgeText`）。改写前此处手写的
          //    `terminateDanglingToolCalls` + 尾部提示注入两段。
          handoff = nebflow.core.ResumeHandoff.plan(
            recoveredMessages,
            nebflow.core.ResumeHandoff.Cause.Crash,
            legacyText = nudgeText
          )
          _ <- IO(nebflow.core.ResumeHandoff.logPlan(subagentId, handoff, nebflow.core.ResumeHandoff.Cause.Crash))
          newChild <- childSpawnFn(ctx.system, handoff.transcript)
          _ <- ctx.watch(newChild)
          // Spec C2: 回写 registry（新 ref + supervisor 指向自己 + 活动时
          // 间刷新）。无旧记录（ghost respawn）时按 source 重建最小条目。
          _ <- resources.agentRegistry.update { registry =>
            val fallback = AgentRecord(
              sessionId = subagentId,
              ref = newChild,
              kind = if source == "subtask" then AgentKind.SubTask else AgentKind.Delegate,
              rootSessionId = parentSessionId,
              supervisorRef = Some(ctx.self),
              parentSessionId = parentSessionId
            )
            val refreshed = oldRecord
              .getOrElse(fallback)
              .copy(
                ref = newChild,
                supervisorRef = Some(ctx.self),
                status = AgentStatus.Idle,
                lastActivityMs = System.currentTimeMillis(),
                // v2 反推送风暴批（2026-10-28，修复面①销账）：respawn = 旧代终结——
                // 旧代 actor 的升级链随死亡消失（内存态），registry 快照同步清零，
                // 防陈旧 escalateAt（已过期）让扫描器对新代第一次冻结即刻 Escalate
                // （击穿 10min 升级窗）。新代失败走全新 L1 链（enterFrozen 正常判定）。
                escalation = None,
                frozenReason = None
              )
            // 缺口② ③（子代理等待三缺口批 2026-10-25）：落 Idle 必须同步清工具相位
            // ——「离开 Processing 一律清工具相位」不变式（AgentRegistryEmit
            // .touchRegistryActivity 头注）的恢复点同款。旧写法绕过该不变式，崩溃前
            // 的 currentToolStartedAt 残留成幽灵相位：watcher 拿它反复杂死刚 respawn
            // 的会话，把健康的续跑轮当卡死掐杀（990bd848 实证 "tool 'Subagent'
            // running 655s" 跨两代 respawn 追杀）。
            registry.updated(subagentId, SubagentCrashRecovery.staleToolPhaseCleared(refreshed))
          }
          // If we recovered messages, hand the round back at the tool boundary
          // (crash: the interrupted call's result; empty burn: the four-fix ①
          // nudge — act with tools, produce output). Otherwise re-inject the
          // original prompt (fresh start fallback).
          _ <-
            if recoveredMessages.nonEmpty then
              newChild ! nebflow.core.ResumeHandoff.userInput(handoff, replyTo = Some(ctx.self))
            else newChild ! AgentCommand.UserInput(initialPrompt, Some(ctx.self))
          _ <- logger.info(
            s"BackoffSupervisor: respawned $childName ($trigger), " +
              s"recovered ${recoveredMessages.size} messages" +
              (if recoveredMessages.nonEmpty then " (resuming from checkpoint)"
               else " (re-injected original prompt)")
          )
          // respawn 后重新装配预算（Delegate 轨）——上一条已在释放时退出。
          _ <-
            if source == "delegate" then
              DelegateBudget.register(subagentId)(ctx.self ! AgentEvent.Cancelled(subagentId, "timeout"))
            else IO.unit
        yield active(
          newChild,
          childSpawnFn,
          childName,
          parentRef,
          description,
          agentName,
          subagentId,
          parentSessionId,
          resources,
          initialPrompt,
          source,
          extraMetadata,
          wsSend,
          newRestartCount,
          newHistory,
          minBackoff,
          maxBackoff,
          maxRestarts,
          withinTimeRange,
          bgWaitCapMs,
          bgIdleCapMs,
          bgWaitHeld,
          bgWaitCapArm,
          bgWaitCapFired
        )
        end for
      end respawnBody

      /**
       * file-diff → 委托结果行（bug #58 修，2026-10-09）：本 delegate 批次的
       * 改动文件报告，作为终态 ExternalEvent 的 `fileChanges` metadata 随行——
       * 父会话注入行的产物卡展开即 diff 视图（git 仓内 git diff 优先、非 git 走
       * 工具捕获快照，口径同 1ef741ee 原设计）。数据源 = DelegateRegistry 行的
       * cwd/worktree（spawn 时落盘，终态仍可读）+ FileChangeLedger 的会话桶。
       * 空报告/任何失败 = 空 JsonObject（metadata 不带键，行字节形态不变）——
       * 生成是 best-effort，绝不拖垮终态交付。
       */
      private def fileChangesMetadata(): IO[JsonObject] =
        IO.blocking(nebflow.core.delegate.DelegateRegistry.find(subagentId)).flatMap {
          case Some(rec) =>
            nebflow.core.project.FileChangeReport
              .delegateBatchMetadata(
                workspace = rec.cwd,
                worktree = rec.worktree,
                delegateId = subagentId,
                ledger = nebflow.core.FileChangeLedger.unsafe
              )
              .handleErrorWith(e =>
                logger.warn(s"delegate file-change metadata failed for $subagentId: ${e.getMessage}").as(None)
              )
              .map {
                case Some(json) => JsonObject("fileChanges" -> json)
                case None => JsonObject.empty
              }
          case None => IO.pure(JsonObject.empty)
        }

      /**
       * 合并提交 diff 统计（完工卡 diff 统计恢复批 2026-11-01，作者令：完工卡显示
       * 该批**合并**的代码量变化 +新增 −删除）：`DelegateWorkReport.mergeDiffStat`
       * 现算——锚定合并提交第一亲 diff（与卡片摘要哈希段同源同值），修旧数据链的
       * 两处盲区（worktree 提交后 git status 面看不见已提交工作；Bash 侧写盘 ledger
       * 记不到）。None = 非合并轮 / 锚定不可读（fail-soft，绝不拖垮终态投递）。
       */
      private def mergeDiffStatJson(): IO[Option[Json]] =
        IO.blocking(nebflow.core.delegate.DelegateRegistry.find(subagentId)).flatMap {
          case Some(rec) =>
            IO
              .blocking(nebflow.core.delegate.DelegateWorkReport.mergeDiffStat(rec))
              .handleErrorWith(e =>
                logger.warn(s"delegate merge diff-stat failed for $subagentId: ${e.getMessage}").as(None)
              )
              .map(
                _.map(s =>
                  Json.obj("commit" -> s.commit.asJson, "added" -> s.added.asJson, "removed" -> s.removed.asJson)
                )
              )
          case None => IO.pure(None)
        }

      /**
       * 终态 diff 载荷 = 既有 fileChanges（worktree/ledger 面——diff 面板数据源）
       * + 合并提交统计（`fileChanges.merge` 键内嵌——线形状纯增量，引擎不解释内部
       * 字段原样落盘原样下发，UiMessageFileChangesSpec 契约不破；消费面/diff 面板/
       * 历史行零改动）。载荷整体缺席而统计在场 ⇒ 合成最小载荷 `{merge}` 单键——
       * 卡面按 merge 键独立渲染「+新增 −删除」（该批合并的代码量），diff 面板入口
       * 仅在 files 面在场时保持可点。
       */
      private def terminalDiffMetadata(): IO[JsonObject] =
        fileChangesMetadata().flatMap { fc =>
          IO.blocking(nebflow.core.delegate.DelegateRegistry.find(subagentId)).flatMap {
            case None =>
              mergeDiffStatJson().map { stat =>
                stat.fold(fc) { s =>
                  val existing = fc("fileChanges").getOrElse(Json.obj())
                  fc.remove("fileChanges").add("fileChanges", existing.deepMerge(Json.obj("merge" -> s)))
                }
              }
            case Some(rec) =>
              (IO.blocking(nebflow.core.delegate.DelegateWorkReport.mergeDiffStat(rec)),
                IO.blocking(nebflow.core.delegate.DelegateWorkReport.aggregateDiffStat(rec))).mapN { (merge, aggregate) =>
                val withOwn = merge.fold(fc) { stat =>
                  fc.remove("fileChanges").add("fileChanges", fc("fileChanges").getOrElse(Json.obj()).deepMerge(Json.obj("merge" -> Json.obj(
                    "commit" -> stat.commit.asJson, "added" -> stat.added.asJson, "removed" -> stat.removed.asJson
                  ))))
                }
                val children = aggregate.children.map { child =>
                  Json.obj(
                    "delegateId" -> child.delegateId.asJson,
                    "parentSessionId" -> child.parentSessionId.asJson,
                    "workspace" -> child.workspace.asJson,
                    "worktree" -> child.worktree.getOrElse("").asJson,
                    "partial" -> child.partial.asJson,
                    "merge" -> child.merge.fold(Json.Null)(s => Json.obj(
                      "commit" -> s.commit.asJson,
                      "oid" -> s.oid.asJson,
                      "added" -> s.added.asJson,
                      "removed" -> s.removed.asJson,
                      "files" -> s.files.map(f => Json.obj(
                        "path" -> f.path.asJson, "added" -> f.added.asJson, "removed" -> f.removed.asJson
                      )).asJson
                    ))
                  )
                }
                val rollup = Json.obj(
                  "added" -> aggregate.added.asJson,
                  "removed" -> aggregate.removed.asJson,
                  "partial" -> aggregate.partial.asJson,
                  "children" -> children.asJson
                )
                val existing = withOwn("fileChanges").getOrElse(Json.obj())
                withOwn.remove("fileChanges").add("fileChanges", existing.deepMerge(Json.obj("aggregate" -> rollup)))
              }
          }
        }

      /** Notify parent via ExternalEvent, clean up registry, stop self. */
      private def notifyParentAndStop(
        eventType: String,
        payload: String,
        failureMetadata: JsonObject,
        taskStatusOverride: Option[String] = None
      ): IO[Behavior[AgentEvent]] =
        notifyParentAndStopImpl(eventType, payload, failureMetadata, taskStatusOverride)

      private def notifyParentAndStopImpl(
        eventType: String,
        payload: String,
        failureMetadata: JsonObject,
        taskStatusOverride: Option[String] = None
      ): IO[Behavior[AgentEvent]] =
        val metadata = JsonObject(
          "description" -> description.asJson,
          "agentName" -> agentName.asJson
        ).deepMerge(extraMetadata).deepMerge(failureMetadata)

        // P3.1: update task store status. taskStatusOverride supports terminal
        // states beyond completed/failed (AgentControl 的 "cancelled")。
        val taskStatus = taskStatusOverride.getOrElse(
          if eventType == "completed" then "completed" else "failed"
        )
        // delegate 轨终态统一清理（幂等）：failed/cancelled 的补判词行
        // （completed 腿的判词行由 DelegateTerminal.onCompleted 写，此处不重复）。
        // subtask 轨零变化。
        val delegateCleanup =
          if source == "delegate" then
            nebflow.core.delegate.DelegateTerminal
              .onTerminalCleanup(resources, subagentId, parentSessionId, taskStatus, eventType)
              .handleErrorWith(e => logger.warn(s"delegate terminal cleanup failed: ${e.getMessage}").void)
          else IO.unit

        val taskUpdate = resources.subAgentTaskStore
          .updateStatus(
            parentSessionId,
            subagentId,
            taskStatus,
            completedAt = Some(System.currentTimeMillis()),
            lastError = if taskStatus != "completed" then Some(payload) else None
          )
          .handleErrorWith(e => logger.warn(s"subAgentTaskStore update failed: ${e.getMessage}"))

        val terminalEvent = AgentCommand.ExternalEvent(
          source = source,
          eventType = eventType,
          payload = payload,
          metadata = metadata,
          correlationId = Some(subagentId)
        )
        // ═══ P2 邮箱化（终态通知 actor 邮箱化批，作者 11-01 批准实施）═════════
        // delegate 轨终态（completed/failed/cancelled）改走**收件方持久邮箱**：
        // mailOnce 幂等入队（写成功=必达）+ 门铃 best-effort——磁盘邮箱是唯一真源，
        // actor 消息降为门铃。旧 push 机器三件随迁停用（本件对 delegate 轨零新写）：
        //   ① `ref ! ExternalEvent` 直投（mailbox offer 从不失败也从不保证消费——
        //      10-31 断链首环：父冻结/忙窗内 offer 从不失败也从不保证消费，12 次重投
        //      0 次进门的判红现场）；
        //   ② parentRef=None 腿的 F2 持久兜底 + delegateResultUndelivered 帧
        //      （「终态无处投」形态随邮箱消亡——条目落在父会话盘上，父任何唤醒点
        //      必扫出，与父此刻是否 live 无关）；
        //   ③ DelegateDeliveryReceipts 回执（上方 receiptWrite 随迁拆除——「标记
        //      已投/父未确认」整个状态类物理不存在，「已投递」= 条目已删除这一
        //      物理事实；delivered-receipts 标记面照旧只服务旧链在飞件，P3 退役）。
        // 双发红线：delegate 轨此后**不得**再走旧链（旧 push + 邮箱 = 事故）；
        // subtask 轨逐字保持旧链（P3 全通知面迁移另批）。
        val notify: IO[Unit] =
          if source == "delegate" then
            MailboxProducer
              .deliver(
                resources,
                parentSessionId,
                terminalEvent,
                identity = subagentId, // 入队幂等键 = 子会话 id（每终态独立事件，永不合条）
                kind = "delegate-terminal",
                coalesce = false,
                producer = "backoff-supervisor"
              )
              .flatMap {
                case SessionMailbox.MailResult.AlreadyPresent =>
                  logger.warn(
                    s"[mailbox-producer] duplicate terminal for '$subagentId' already pending in parent '$parentSessionId' — idempotent no-op (single copy on disk)"
                  )
                case SessionMailbox.MailResult.Failed(cause) if cause == "parent-session-gone" =>
                  // 交付腿已在生产者侧响亮留痕（不产垃圾文件）；终态三清照常。
                  IO.unit
                case SessionMailbox.MailResult.Failed(cause) =>
                  logger.warn(
                    s"[mailbox-producer] terminal for '$subagentId' NOT on disk after retries (parent='$parentSessionId' cause=$cause) — " +
                      s"boot reconcile will synthesize a replacement entry from the task ledger"
                  )
                case _ => IO.unit // Enqueued / Coalesced：必达成立（门铃已发）
              }
          else
            parentRef match
              case Some(ref) => ref ! terminalEvent
              case None =>
                // mail-continuation delivery backstop（2026-10-09）：parentRef=None ⇒
                // 本终态结果**没有任何投递对象**——历史分支是裸 IO.unit，结果在回传
                // 环节静默蒸发（实测两起：Mail 续接的会话正常跑完、父会话零消息）。
                // 兜底 = 绝不静默：响亮 WARN（带 payload 头部）＋ wsSend 在场时补一帧
                // 显式终态事件。taskStore 状态写入（上方）照常落盘 ⇒ 面板仍见终态。
                // 正常派发腿恒传活父引用（ctx.agentActorRef），本分支只在「Mail 续接
                // 时父已不 live」或未来调用方漏传时触发——低频、且正是必须出声的形态。
                // 监督链自动恢复批（2026-10-22）：WARN 之后追加**F2 注入队持久兜底**
                // （correlationId 幂等，SubAgentStartupRecovery.notifyOnce 同机制）——
                // 终态事件进父会话持久注入队，父会话下次 spawn 时
                // RecoverPersistedQueues 重放。「结果不可投递」升级为「结果延迟可达」：
                // boot 复活腿（DelegateRevival）重建的 delegate 在父会话尚未 live 时
                // 完成工作，结果不再丢失。父会话已删 = 跳过（不产生无人读的垃圾文件，
                // V2 收殓同款守卫）。
                logger.warn(
                  s"[terminal-result-undelivered] $source session '$subagentId' reached '$eventType' but no parent ref exists " +
                    s"(parent session '$parentSessionId' not live at spawn) — the result was NOT delivered to any session. " +
                    s"Payload head: ${payload.take(500)}"
                ) *>
                  (wsSend match
                    case Some(send) =>
                      send(
                        Json.obj(
                          "type" -> "delegateResultUndelivered".asJson,
                          "sessionId" -> subagentId.asJson,
                          "rootSessionId" -> parentSessionId.asJson,
                          "source" -> source.asJson,
                          "eventType" -> eventType.asJson,
                          "description" -> description.asJson
                        )
                      ).handleErrorWith(e => logger.warn(s"delegateResultUndelivered WS frame failed: ${e.getMessage}"))
                    case None => IO.unit) *>
                  // F2 持久兜底（监督链自动恢复批 2026-10-22，见上注）——WARN 之后
                  // 事件照常持久投递；append 失败只 WARN（父队列面故障不拖垮终态三清）。
                  f2DeliveryBackstop(terminalEvent)

        // 终态三清（R2-c §7#4 + 本设计）：① hub 槽（僵尸 ask 卡：内核已死，
        // 卡片再没人能接——全仓唯一的其它清理入口是项目引擎，触及不到内核这种
        // 非项目会话）② 预算通道（不再计时）③ registry 行（既有）。
        // #1224：前置 delegate 清理（队列释放先行，再拆 actor）。
        // P2 邮箱化：receiptWrite 腿随迁拆除（delegate 终态零新回执——*.receipt.json
        // 对新终态零新写，验收红线②）。
        (delegateCleanup *> notify *> taskUpdate *> cleanupPendingAsks(resources, subagentId) *>
          DelegateBudget.release(subagentId) *>
          resources.agentRegistry.update(_ - subagentId) *>
          (childRef ! AgentCommand.Stop(s"$source-complete")) *>
          IO.pure(Behaviors.stopped[AgentEvent]))
          .handleErrorWith(_ => IO.pure(Behaviors.stopped[AgentEvent]))

      end notifyParentAndStopImpl

      /**
       * 终态事件 F2 注入队持久兜底（监督链自动恢复批 2026-10-22）：parentRef=None
       * 时把终态 ExternalEvent append 进父会话持久注入队文件（correlationId=subagentId
       * 幂等——[[SubAgentStartupRecovery.notifyOnce]] 同机制），父会话下次 spawn 时
       * RecoverPersistedQueues 重放。父会话已删 = 跳过（不产生无人读的垃圾文件）。
       * fail-soft：append 失败只 WARN——投递兜底绝不拖垮终态三清链。
       */
      private def f2DeliveryBackstop(event: AgentCommand.ExternalEvent): IO[Unit] =
        if parentSessionId.isEmpty then IO.unit
        else
          CompactionQueueStore
            .load(parentSessionId)
            .flatMap { existing =>
              val already =
                existing.exists(_.events.exists(_.correlationId.contains(event.correlationId.getOrElse(""))))
              if already then IO.unit
              else
                resources.sessionStore.listSessions.flatMap { sessions =>
                  if !sessions.exists(_.id == parentSessionId) then IO.unit
                  else
                    val q = existing.getOrElse(CompactionQueueStore.PersistedQueues())
                    CompactionQueueStore.save(parentSessionId, q.copy(events = q.events :+ event))
                }
            }
            .handleErrorWith(e =>
              logger.warn(s"[terminal-f2-backstop] append failed for parent '$parentSessionId': ${e.getMessage}")
            )

      /**
       * source-death 清理（InteractionHub.CleanupForSession 的第二个调用点；
       * 第一个是项目引擎 NodeEngine）：终态 / respawn 时清掉该会话的 pending
       * ask 槽并广播 askUserClosed。hub 未装配（早期 boot / 测试）或无槽 = no-op。
       */
      private def cleanupPendingAsks(resources: SharedResources, sessionId: String): IO[Unit] =
        resources.interactionHubRef.get.flatMap {
          case Some(hub) =>
            (hub ! InteractionHubCommand.CleanupForSession(sessionId)).void
              .handleErrorWith(e => logger.warn(s"CleanupForSession($sessionId) failed: ${e.getMessage}"))
          case None => IO.unit
        }

  /**
   * Crash 重启退避延迟(纯函数,单位 ms)。
   *
   * Phase 3 去重(行为保持重构,2026-09-25):指数部分 min(minBackoff ×
   * 2^currentRestarts, maxBackoff) 复用 shared Retry.delayAfter(初值 minBackoff、
   * ×2、封顶 maxBackoff、无抖动);抖动 [0,1000)ms 在封顶**之后**相加——总延迟
   * 可超 maxBackoff 至多 999ms,与公共层 Jitter.Additive「先加抖动再封顶」
   * (llm/fallback 口径)不同,故抖动留在本站点。监督循环本体(watch/Terminated、
   * 重启计数、消息重注入、maxRestarts 放弃)是 actor 监督语义、IO 重试组合子的
   * 超集,保持原样。jitterMs 可注入做确定性测试
   * (BackoffSupervisorRestartDelaySpec)。
   */
  private[agent] def restartDelayMs(
    currentRestarts: Int,
    minBackoff: FiniteDuration,
    maxBackoff: FiniteDuration,
    jitterMs: () => Long = () => java.util.concurrent.ThreadLocalRandom.current().nextLong(0, 1000)
  ): Long =
    Retry
      .delayAfter(
        initialDelay = minBackoff,
        failedAttemptNo = currentRestarts + 1,
        multiplier = 2.0,
        maxDelay = maxBackoff
      )
      .toMillis + jitterMs()

  private def extractLastAssistantText(messages: List[Message]): String =
    messages.reverse
      .collectFirst {
        case msg if msg.role == MessageRole.Assistant => msg.textContent
      }
      .filter(_.trim.nonEmpty)
      .getOrElse("")

  // ── 四修①（2026-10-07 修复令「空轮≠完成」）判据纯函数（spec 判红锚） ──

  /** 全会话工具调用计数（Completed 事件的 messages = 全会话历史；spec 锚）。 */
  private[agent] def sessionToolCalls(messages: List[Message]): Int =
    messages.flatMap(_.content.toOption).flatten.count(_.isInstanceOf[ContentBlock.ToolUse])

  /**
   * 空烧完成判据：全会话零工具调用 **且** 终文不可用——终文为空（thinking-only
   * / 静默空轮，`turn-ended-empty` 形态）或止于 max_tokens 空烧 stub
   * （[[Defaults.MaxTokensStubText]]，与 handleEmptyResponse 收尾文案同串）。
   * 有真实终文（哪怕零工具）不算空烧——那是判定机的正常输入。
   */
  private[agent] def isEmptyBurnCompletion(messages: List[Message]): Boolean =
    if sessionToolCalls(messages) > 0 then false
    else
      val last = extractLastAssistantText(messages)
      last.isEmpty || last.trim == Defaults.MaxTokensStubText

  /**
   * 配额/额度类取消判据（错误冻结批 2026-11-01，纯函数判红锚）：`AgentEvent.Cancelled`
   * 的 reason 属「外部条件不足」而非「不要了」⇒ delegate 轨就地冻结（可等额度恢复
   * 后唤醒续做）。显式取消（作者 AgentControl cancel / 父会话删除 / 项目卸载 /
   * 卡死硬取消）**不在**此列——取消意图必须落地成终态。
   */
  private[agent] def isQuotaLikeCancel(reason: String): Boolean =
    val r = Option(reason).getOrElse("").toLowerCase
    r.contains("quota") || r.contains("额度") || r.contains("budget") || r.contains("rate limit") ||
    r.contains("insufficient_quota")

end BackoffSupervisor
