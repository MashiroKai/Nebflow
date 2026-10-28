package nebflow.agent

import cats.effect.{Fiber, IO, Ref}
import cats.syntax.all.*
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.actor.*
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
    bgWaitCapMs: Long = Defaults.BgGateWaitTimeoutMs
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
      val bgWaitCapFiber = Ref.unsafe[IO, Option[Fiber[IO, Throwable, Unit]]](None)
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
              bgWaitHeld,
              bgWaitCapFiber,
              bgWaitCapFired
            )
          )
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
    bgWaitHeld: Ref[IO, Boolean],
    bgWaitCapFiber: Ref[IO, Option[Fiber[IO, Throwable, Unit]]],
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
          // worktree-judge-fifo 批（#1224）：delegate 轨的 Completed 先过终态
          // 判定机（三态判词）——completed/blocked 照常报父（payload 携带判词
          // 行）；ready-to-merge **停靠**：不报父、不停 actor（合并轮需要
          // delegate 活着自执行合并），预算重挂合并窗新 3600s，超时走下方既有
          // Cancelled 链兜底。subtask 轨零变化。四修②：全会话工具调用计数随行
          // ——终态确定性预检（resultText 空＋零工具 ⇒ 直接判未完成）的输入。
          if source == "delegate" then
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
              .attempt
              .flatMap {
                case Right(nebflow.core.delegate.DelegateTerminal.Outcome.Release(suffix, mergeOutcome)) =>
                  // file-diff → 委托结果行（bug #58 修 2026-10-09）：完成批次携带
                  // 改动文件 diff 载荷（metadata.fileChanges）——父会话注入行的
                  // 产物卡展开即 diff 视图。空报告 = 不带键（行字节形态不变）。
                  // A 段等待屏罩：兜底交付注记（bg-wait cap 到期强制终判时附带
                  // ——诚实标注「交付时仍有未结后台任务，其完成通知将丢失」）。
                  // 台账 #896（交通引导员制 2026-10-26）：合并轮终态结论随行关账
                  // （merged / failed；回合外 = None → 账实核对回落 owner-gone）
                  // ——无留队待重试语义。
                  val note = bgWaitNote.fold("")(n => s"\n[delegate-bg-wait] $n")
                  fileChangesMetadata().flatMap(fc =>
                    notifyParentAndStop(
                      "completed",
                      payload + suffix + note,
                      fc,
                      mergeOutcome = mergeOutcome
                    )
                  )
                case Right(nebflow.core.delegate.DelegateTerminal.Outcome.HoldForMerge) =>
                  // 合并窗预算重挂（旧窗口随工作相终结释放；DelegateBudget ∈
                  // agent 层——core 钩子只回 Outcome，重挂留在本层，分层纪律）。
                  DelegateBudget
                    .release(subagentId)
                    .*>(
                      DelegateBudget
                        .register(subagentId)(ctx.self ! AgentEvent.Cancelled(subagentId, "merge-window-timeout"))
                    )
                    .*>(
                      logger.info(
                        s"BackoffSupervisor: delegate $subagentId held for merge round (fresh merge-window budget armed)"
                      )
                    )
                    // 显式等待停靠态（平台修复批 fix 4，2026-10-21）：ready-to-merge
                    // 停靠不再貌似 Idle 完成——registry 状态快照置 Waiting（等待屏障
                    // 语义：TaskStuckWatcher 豁免 / MailIdleGate 视同可投递 / 面板
                    // 快照透出 "Waiting"），父会话与作者都看得见「在等合并轮」。
                    // 清理面零新增：终态腿 notifyParentAndStop 本就整行移除 registry。
                    .*>(
                      resources.agentRegistry.modify { m =>
                        m.get(subagentId) match
                          case Some(rec) if rec.status != AgentStatus.Waiting =>
                            (m.updated(subagentId, rec.copy(status = AgentStatus.Waiting)), ())
                          case _ => (m, ())
                      }
                    )
                    .*>(
                      wsSend match
                        case Some(send) =>
                          send(
                            Json.obj(
                              "type" -> "delegateWaiting".asJson,
                              "sessionId" -> subagentId.asJson,
                              "rootSessionId" -> parentSessionId.asJson,
                              "mode" -> "merge".asJson
                            )
                          ).handleErrorWith(e => logger.warn(s"delegateWaiting WS frame failed: ${e.getMessage}"))
                        case None => IO.unit
                    )
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
                        restartCount,
                        restartHistory,
                        minBackoff,
                        maxBackoff,
                        maxRestarts,
                        withinTimeRange,
                        bgWaitCapMs,
                        bgWaitHeld,
                        bgWaitCapFiber,
                        bgWaitCapFired
                      )
                    )
                case Left(err) =>
                  // 判定钩子自身缺陷绝不吞终态：按既有完成语义报父（fail-open）。
                  logger.warn(
                    s"BackoffSupervisor: terminal judge hook failed for $subagentId: ${err.getMessage} — legacy completed path"
                  )
                  fileChangesMetadata().flatMap(fc => notifyParentAndStop("completed", payload, fc))
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

        def disarmBgWaitCap: IO[Unit] =
          bgWaitCapFiber.get.flatMap(_.traverse_(_.cancel).void) *> bgWaitCapFiber.set(None)

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

        def armBgWaitCap(messages: List[Message]): IO[Unit] =
          disarmBgWaitCap *>
            (IO.sleep(bgWaitCapMs.millis) *>
              nebflow.core.tools.BgTaskRegistry.waitingFor(subagentId).flatMap {
                case Nil => IO.unit // 竞态：唤醒轮在飞，其 Completed 会正常放行
                case still =>
                  logger.warn(
                    s"BackoffSupervisor: delegate $subagentId bg-wait cap (${bgWaitCapMs}ms) hit with " +
                      s"${still.size} task(s) pending — forcing honest terminal delivery"
                  ) *>
                    // ── 孤儿登记（修复批 2026-10-24 fix 3「两种死法统一收口」）──
                    // cap 强制终判 = 本会话终结、这些任务的完成通知将无处投递。
                    // 逐条写入持久账本（含当时输出尾段快照——「完成事件+输出尾段
                    // 供续会话拾取，现在直接丢」的收口）：会话若被复活/重派拾取，
                    // 账本条目即完整证据链；completion 事件此后照常经 BashTool
                    // recordCompletion 原位升级 orphan 条目（kind 不降级）。
                    // fail-soft：账本故障绝不阻断强制终判。
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
                    // 🔴 只清句柄不 cancel——此刻正在本 fiber 体内，cancel 自己
                    // 会让 self-message 永远发不出去（A-4 实测）。句柄清空后外部
                    // disarm 调用对其 cancel 为 no-op。
                    bgWaitCapFiber.set(None) *>
                    // 诊断修复批 2026-10-24：cap 触发时**重载会话最新 transcript**——
                    // hold 时武装捕获的 messages 是旧轮快照（hold 期唤醒轮的新产出
                    // 不在其中），直接强投会把过期 payload 交付父会话；重载失败/
                    // 空读回落旧快照（既有行为，绝不静默丢失复检事件本身）。
                    latestMessages(subagentId, messages)
                      .flatMap(latest => (ctx.self ! AgentEvent.Completed(subagentId, latest)).void)
              }).start.flatMap(f => bgWaitCapFiber.set(Some(f)))

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
                  nebflow.core.tools.BgTaskRegistry.waitingFor(subagentId).map {
                    case Nil => BgWaitProceed // 兜底触发与唤醒轮竞态：实际已清空 ⇒ 干净放行
                    case still => BgWaitForce(bgWaitCapNote(still))
                  }
              else
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
                      bgWaitHeld,
                      bgWaitCapFiber,
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
            // 载荷（用户预期 = 任何改了文件的委托批；失败批看「写到哪里了」同价）。
            val withFc =
              if source == "delegate" then fileChangesMetadata().map(_.deepMerge(failureMeta))
              else IO.pure(failureMeta)
            withFc.flatMap(fc => notifyParentAndStop("failed", s""""$description": ${error.message}$sessionInfo""", fc))

          case AgentEvent.Cancelled(_, reason) =>
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
        end match
      end receive

      override def onSignal(ctx: ActorContext[AgentEvent], signal: SystemSignal): IO[Behavior[AgentEvent]] =
        signal match
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
                notifyParentAndStop(
                  "failed",
                  s""""$description": agent crashed and could not recover after $maxRestarts restarts""",
                  JsonObject(
                    "failedSessionId" -> subagentId.asJson,
                    "retryable" -> false.asJson,
                    "failureType" -> "supervision-exhausted".asJson
                  )
                )
            end if

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
                "falling back to an honest failed terminal (a stuck 'restarting' row is never left behind)"
            )
            .as(()) *>
            notifyParentAndStop(
              "failed",
              s""""$description": supervisor respawn failed mid-restart ($trigger): ${Option(e.getMessage)
                  .getOrElse(e.getClass.getSimpleName)} — task force-failed instead of stalling in 'restarting'""",
              JsonObject(
                "failedSessionId" -> subagentId.asJson,
                "retryable" -> true.asJson,
                "failureType" -> "respawn-failed".asJson
              )
            )
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
          // 评估等待集。
          _ <- bgWaitCapFiber.get.flatMap(_.traverse_(_.cancel).void) *>
            bgWaitCapFiber.set(None) *> bgWaitHeld.set(false) *> bgWaitCapFired.set(false)
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
          (repairedMessages, terminatedCalls) = SubagentCrashRecovery.terminateDanglingToolCalls(recoveredMessages)
          _ <-
            if terminatedCalls.nonEmpty then
              logger
                .info(
                  s"BackoffSupervisor: $subagentId checkpoint repair — terminated ${terminatedCalls.size} dangling " +
                    s"tool call(s) at the recovery point [${terminatedCalls.map(_._1).mkString(", ")}] " +
                    s"(${SubagentCrashRecovery.RecoveredAfterCrashToken})"
                )
                .void
            else IO.unit
          newChild <- childSpawnFn(ctx.system, repairedMessages)
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
          // If we recovered messages, send a "continue" instruction so the
          // child picks up where it left off (crash: plain continue; empty
          // burn: the four-fix ① nudge — act with tools, produce output).
          // Otherwise re-inject the original prompt (fresh start fallback).
          _ <-
            if recoveredMessages.nonEmpty then newChild ! AgentCommand.UserInput(nudgeText, Some(ctx.self))
            else newChild ! AgentCommand.UserInput(initialPrompt, Some(ctx.self))
          // D3-②（steward 批 2026-10-23）：合并队列条目不再绑活体引用——崩溃重启
          // 换新子 actor 后把该 delegate 的排队条目原地换绑（事件驱动），待重试
          // 资格恢复；提醒未发过的条目正巧队首且窗空闲 ⇒ 立即重投。查无条目 = no-op。
          _ <-
            if source == "delegate" then
              nebflow.core.delegate.MergeQueue
                .reattachIO(subagentId, newChild)
                .handleErrorWith(e => logger.warn(s"MergeQueue reattach failed for $subagentId: ${e.getMessage}"))
            else IO.unit
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
          bgWaitHeld,
          bgWaitCapFiber,
          bgWaitCapFired
        )
        end for
      end respawn

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

      /** Notify parent via ExternalEvent, clean up registry, stop self. */
      private def notifyParentAndStop(
        eventType: String,
        payload: String,
        failureMetadata: JsonObject,
        taskStatusOverride: Option[String] = None,
        mergeOutcome: Option[nebflow.core.delegate.MergeQueue.RoundOutcome] = None
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
        // worktree-judge-fifo 批（#1224）：delegate 轨终态统一清理（幂等）——
        // MergeQueue 关账（合并轮结论随行：merged / failed / 回合外账实核对回落；
        // 台账 #896 交通引导员制——无留队待重试语义）+ failed/cancelled 的补判词行
        // （completed 腿的判词行由 DelegateTerminal.onCompleted 写，此处不重复）。
        // subtask 轨零变化。
        val delegateCleanup =
          if source == "delegate" then
            nebflow.core.delegate.DelegateTerminal
              .onTerminalCleanup(resources, subagentId, parentSessionId, taskStatus, eventType, mergeOutcome)
              .handleErrorWith(e => logger.warn(s"delegate terminal cleanup failed: ${e.getMessage}").void)
          else IO.unit

        // 诊断修复批 2026-10-24（delegate-nebflow-52308ee4 投递丢失）：终态交付
        // 回执——`ref !` 是 mailbox offer 从不失败，父会话是否真注入回合此前零
        // 确认。notify（直投 / parentRef=None 的 F2 持久兜底腿）过后落回执；
        // 父注入时 ack（AgentIdle/AgentFinishTurn 挂点），超龄未确认由
        // TaskStuckWatcher 扫描拍重投。fail-soft 绝不拖终态三清。
        val receiptWrite: IO[Unit] =
          if source == "delegate" then
            nebflow.core.delegate.DelegateDeliveryReceipts
              .record(
                nebflow.core.delegate.DelegateDeliveryReceipts.Receipt(
                  delegateId = subagentId,
                  parentSessionId = parentSessionId,
                  eventType = eventType,
                  payload = payload,
                  metadata = metadata,
                  deliveredAt = System.currentTimeMillis()
                )
              )
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
        val notify = parentRef match
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
        (delegateCleanup *> notify *> receiptWrite *> taskUpdate *> cleanupPendingAsks(resources, subagentId) *>
          DelegateBudget.release(subagentId) *>
          resources.agentRegistry.update(_ - subagentId) *>
          (childRef ! AgentCommand.Stop(s"$source-complete")) *>
          IO.pure(Behaviors.stopped[AgentEvent]))
          .handleErrorWith(_ => IO.pure(Behaviors.stopped[AgentEvent]))

      end notifyParentAndStop

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

end BackoffSupervisor
