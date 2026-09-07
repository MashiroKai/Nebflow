package nebflow.core.processor

import cats.effect.IO
import cats.syntax.all.*
import io.circe.syntax.*
import nebflow.agent.{AgentCommand, AgentEvent, AgentKind, AgentRecord, AgentStatus, SharedResources}
import nebflow.core.NebflowLogger
import nebflow.gateway.WsHub

import scala.concurrent.duration.*
import scala.concurrent.duration.FiniteDuration

/**
 * P0 阶段 3（2026-08-18，设计 §4.4）：卡死识别与恢复扫描器。
 *
 * 背景：12:39 现场事故——7 个并发 Delegate 撞 API 限流后各自进入 retry/fallback
 * 循环，turn 长期不完成、零事件输出，BackoffSupervisor 只在 Terminated（崩溃）
 * 时介入，卡死（活着但不动）永不触发。本扫描器补上"Processing 态 + turn 活动
 * 长时间为零"的识别与恢复。
 *
 * 判定（只对 taskKinds = {Delegate, Ephemeral, Flow, SubTask}，与
 * WebSocketRoutes.filterActiveAgents 同集合）：
 *   - AgentRecord.status == Processing 且 now - lastActivityMs > threshold
 *   - idle 态永不判卡死（run_in_background 时 agent 回 Idle 为合法状态）——
 *     防误杀铁律；status/lastActivityMs 由 AgentCore.touchRegistryActivity
 *     在 LLM 流 chunk / 工具完成 / turn 完成时维护，另由 BashTool 活动桥接
 *     （#319）在长前台命令有进展时刷新 lastActivityMs——有进展不判卡死。
 *
 * 恢复：
 *   - 子 agent（有 parentRef）：发 AgentCommand.Stop → AgentActor 的 Stop
 *     handler cancelCurrentTurn（杀挂死的 LLM 流 fiber）→ actorLoop 退出 →
 *     BackoffSupervisor death-watch 收 Terminated → 按现有退避（5s→60s）重启，
 *     不新造恢复机制。重启后若仍卡死，watcher 再次 Stop → 重启计数 +1 →
 *     maxRestarts 熔断（supervisor 现有逻辑）。
 *   - gate-wedge P1-1（2026-08-20）：Stop 是 mailbox 消息，suspended 在 LLM
 *     fiber 上的 agent 永不消费（事故：6.5h 每 30s 重发全部无效）。同一
 *     session 累计 StopAttempts(=2) 次无响应后，watcher 升级为经 inflight
 *     注册表按 session 硬取消在飞 LLM 请求（StuckAbort→Fatal，不 fallback
 *     不重发上下文）——turn 走 llm-fail，mailbox 恢复轮转，Stop 终于被消费。
 *   - issue #31 终极兜底（2026-08-20）：硬取消后仍 stuck（attempts ≥
 *     StopAttempts+2，即 escalate 后约两轮扫描仍无活动）——Stop 与硬取消
 *     双失效（当晚 design-engineer 47 次 Stop + 反复 hard-cancel 全无效，
 *     turn fiber 挂在无取消注册的等待上），supervisor 永远等不到终态 →
 *     父 barrier 留 phantom slot、held 结果永久滞留。此时经 supervisorRef
 *     发 AgentEvent.Cancelled（AgentControl cancel 同链路）：父 barrier 归还、
 *     held 注入触发轮次、taskStore cancelled、registry 移除、child Stop、
 *     supervisor 自停。
 *   - Project flow 会话（node-/dispatcher-，supervisorRef=观察桥）卡死：广播
 *     taskStuck(action=restart) + 第 1 次即硬取消在飞 LLM；StopAttempts+2 轮仍
 *     卡 → 经 supervisorRef 发 AgentEvent.Cancelled（观察桥 → dispatcher 清
 *     registry+停 agent / node 走 engine cancelNode 全链清理）。不发 raw Stop
 *     （单次会话无 supervisor 重启，Stop 只会杀 actor 而不发终态事件）。
 *   - 根 agent（无 parentRef）：不自动重启——广播 taskStuck WS 事件 + 日志，
 *     由用户决定。
 *
 * 阈值协同：默认 10min ≫ llm-fail 退避上限（8s×3 + probe 120s）——重试链每次
 * 动作都 touch 活动戳，不会在重试链完成前误判。
 */
object TaskStuckWatcher:

  private val logger = NebflowLogger.forName("nebflow.core.processor.stuck")

  /** gate-wedge P1-1: after this many ignored Stops, escalate to hard-cancelling
    * the stuck agent's in-flight LLM fiber via the inflight registry. */
  val StopAttempts: Int = 2

  /** 与 WebSocketRoutes.filterActiveAgents 相同的 task 集合（保持单一事实源意识）。 */
  private val taskKinds = Set(AgentKind.Delegate, AgentKind.Ephemeral, AgentKind.Flow, AgentKind.SubTask)

  /**
   * 扫描集合 = taskKinds + Root + Team。Root（Nebula 主窗口）虽不在活跃子代理列表
   * （filterActiveAgents 语义），但其 turn 同样可能卡死（如 12:39 现场 Nebula
   * 自身撞限流）——设计 §4.4 要求根 agent 也通知（不自动重启）。
   *
   * #22 (2026-08-19)：Team 加入扫描——Mail 激活的 team agent turn 静默挂死
   * 2 小时（Frontend 12:28-13:45 三案之一），因 Team 不在扫描集合而零可见
   * 性、零恢复。Team 是 AgentControl §4 只读 kind（用户可见的长驻会话），
   * 不自动 Stop——只广播 taskStuck(action=attention) 留痕，由用户/Nebula
   * 决策（AgentControl restart / 手动干预）。
   */
  private val scannedKinds: Set[AgentKind] = taskKinds + AgentKind.Root + AgentKind.Team

  /**
   * 周期扫描循环：scan → sleep(interval) → 递归。由 GatewayMain 以 fiber 启动
   * （.start），错误被 handleErrorWith 吞掉防止 fiber 崩溃——扫描器必须自愈。
   */
  def run(resources: SharedResources, wsHub: WsHub, interval: FiniteDuration, thresholdMs: Long): IO[Unit] =
    // NOTE: must use `>>` (by-name) for the recursion, NOT `*>` — `*>` evaluates
    // its right operand strictly, so `*> loop` would recurse infinitely while
    // BUILDING the IO description (StackOverflowError at startup, caught by
    // the s3 smoke test). `>>` defers `loop` until the sleep completes, so the
    // recursion crosses the async sleep boundary and stays stack-safe.
    //
    // gate-wedge P1-1 (2026-08-20): stopCounts tracks how many Stop mailbox
    // messages each stuck session has ignored. Stop is consumed by the actor
    // loop, which never turns while the agent is suspended on its LLM fiber —
    // the incident showed 6.5h of 30s-interval Stop resends with zero effect.
    // After StopAttempts ineffective Sends we escalate to cancelling the
    // in-flight LLM fiber itself (LlmInterface.cancelInflightFor): the turn
    // fails, the queued Stops are finally consumed, recovery proceeds.
    def loop: IO[Unit] =
      for
        stopCounts <- cats.effect.Ref.of[IO, Map[String, Int]](Map.empty)
        _ <- scanLoop(stopCounts)
      yield ()

    def scanLoop(stopCounts: cats.effect.Ref[IO, Map[String, Int]]): IO[Unit] =
      scan(resources, wsHub, thresholdMs, stopCounts).handleErrorWith(e =>
        logger.warn(s"TaskStuckWatcher scan failed (will retry next cycle): ${e.getMessage}")
      ) *> IO.sleep(interval) >> scanLoop(stopCounts)
    loop

  /** 单轮扫描：识别卡死 agent 并执行恢复动作。独立成函数便于单元测试。 */
  def scan(
    resources: SharedResources,
    wsHub: WsHub,
    thresholdMs: Long,
    stopCounts: cats.effect.Ref[IO, Map[String, Int]] = cats.effect.Ref.unsafe(Map.empty)
  ): IO[Unit] =
    val now = System.currentTimeMillis()
    resources.agentRegistry.get.flatMap { registry =>
      val stuck = registry.values.toList
        .filter(rec => scannedKinds.contains(rec.kind))
        .filter(rec => rec.status == AgentStatus.Processing)
        // R2 (wait-timeout-fix, 2026-09-03 作者裁定): WaitingForUser is a
        // first-class human-in-the-loop wait (AskUser pending / permission
        // card) — a person, not the process, is the progress driver. Never a
        // stuck candidate: this exclusion (paired with the status wiring)
        // kills all 116/day taskStuck false positives (audit 20260903) and
        // the destructive Stop→hard-cancel chain that killed sub-agents'
        // pending questions. Behaviorally subsumed by the Processing filter
        // above; kept as an explicit guard so a future widening of the scan
        // condition can never silently re-include human-in-the-loop waits.
        // Coverage resumes the moment the answer lands (paired restore to
        // Processing with a fresh lastActivityMs — AgentActor AskUser handler
        // / AgentCore.askUserPermission), so true hangs stay reachable.
        .filter(rec => rec.status != AgentStatus.WaitingForUser)
        .filter(rec => rec.lastActivityMs > 0 && now - rec.lastActivityMs > thresholdMs)
      val stuckIds = stuck.map(_.sessionId).toSet
      // Drop counters for sessions that recovered (fresh activity / different
      // status / gone) so a future stuck episode starts from Stop attempt 1.
      stopCounts.modify(m => (m.view.filterKeys(stuckIds.contains).toMap, ())) *>
        stuck.traverse_ { rec =>
          recover(resources, wsHub, rec, now, stopCounts)
        }
    }

  /** taskStuck WS 广播（统一 payload：sessionId/kind/idleSecs/action）。 */
  private def broadcastStuck(wsHub: WsHub, rec: AgentRecord, idleSecs: Long, action: String): IO[Unit] =
    wsHub
      .broadcast(
        io.circe.Json.obj(
          "type" -> "taskStuck".asJson,
          "sessionId" -> rec.sessionId.asJson,
          "kind" -> rec.kind.toString.asJson,
          "idleSecs" -> idleSecs.asJson,
          "action" -> action.asJson
        )
      )
      .handleErrorWith(e => logger.warn(s"TaskStuckWatcher: taskStuck WS broadcast failed: ${e.getMessage}"))

  /** 恢复动作：Team 只读通知 / Project flow 会话取消 / 子 agent 重启 / 根 agent 通知。 */
  private def recover(
    resources: SharedResources,
    wsHub: WsHub,
    rec: AgentRecord,
    now: Long,
    stopCounts: cats.effect.Ref[IO, Map[String, Int]]
  ): IO[Unit] =
    val idleSecs = (now - rec.lastActivityMs) / 1000
    // #22: Team kind 只读——长驻用户可见会话，绝不自动 Stop（AgentControl §4）。
    // 2026-08-19 Frontend 僵尸 turn 若有此广播，2 小时静默会变成即时可见。
    if rec.kind == AgentKind.Team then
      logger.warn(
        s"TaskStuckWatcher: team agent ${rec.sessionId} stuck in Processing for ${idleSecs}s " +
          "— the actor is never auto-stopped (let-it-crash: crash+recover beats chronic hang), " +
          "but a looping turn may be terminated by loop guard; the team Manager or Nebula can " +
          "cancel/restart it via AgentControl"
      ) *>
        broadcastStuck(wsHub, rec, idleSecs, "attention")
    // Project flow 会话（node-/dispatcher-，supervisorRef=观察桥）卡死：分级接管
    // L1→L4（hard-recovery P5，2026-09-07 设计 §2.6/§9）。回验 = 下一轮扫描的
    // stuck 复查（恢复则计数器随 filterKeys 复位，不再升级）。绝不发 raw Stop——
    // 单次会话无 supervisor 重启，Stop 杀掉 actor 而不发终态事件（桥收不到 →
    // engine fiber 挂死 / 桥僵尸）。dag- 旧 flow 会话（无 supervisorRef）不进此
    // 分支，走根 agent 分级（其取消走 cancelFlow）。
    else if rec.kind == AgentKind.Flow && rec.supervisorRef.isDefined then
      val hard = nebflow.shared.Defaults.HardRecoveryEnabled
      logger.warn(
        s"TaskStuckWatcher: Project flow session ${rec.sessionId} (kind=Flow) stuck in Processing " +
          s"for ${idleSecs}s > threshold — escalating (L1 halt → L2 transport abort → L3 resume)"
      ) *>
        // P7 诚实帧：硬分级时每扫描一帧**真实** action（在下述 match 内逐拍广播）；
        // 只有回滚形态（!hard）沿用旧语义在此统一广播 restart。
        (if hard then IO.unit else broadcastStuck(wsHub, rec, idleSecs, "restart")) *>
        stopCounts.modify { m =>
          val n = m.getOrElse(rec.sessionId, 0) + 1
          (m.updated(rec.sessionId, n), n)
        }.flatMap { attempts =>
          def hardCancel() =
            nebflow.llm.LlmInterface.cancelInflightFor(rec.sessionId).flatMap { n =>
              logger.warn(
                s"TaskStuckWatcher: ${rec.sessionId} attempt $attempts — hard-cancelled $n in-flight LLM request(s)"
              )
            }.handleErrorWith(e =>
              logger.warn(s"TaskStuckWatcher: hard-cancel for ${rec.sessionId} failed: ${e.getMessage}")
            )
          def transportAbort() =
            // L2 双管（裁定① tier-2）：transport abort 解 LLM 流楔死 + 会话自有
            // 进程组 kill 解工具挂死（按自有 PGID 击杀=强进程验身，会话 actor 保留）。
            // 护栏（证据 #5 ① 双条件容器 CPU 盲区）：进程 kill（reclaimSession）仅在
            // transport abort 命中 ≥1 在飞请求（LLM 楔死形态成立——流中 parked / intake-
            // first-chunk park 均有在飞请求）时执行；命中 0 = 工具执行相位（docker/cargo
            // 容器负载的 CPU 在容器内，宿主侧观测为零——活动桥接 sees 仅直接子进程
            // CPU），收回进程 kill，升级链走 tier-3。kill 复用收殓支 reclaimSession
            // 原语（Nebula 2026-09-07 12:41：killSessionProcesses + unregisterSession +
            // WS 帧，幂等，不另造平行 kill 机制）。
            nebflow.llm.LlmInterface.transportAbortFor(rec.sessionId).flatMap { n =>
              logger.warn(
                s"TaskStuckWatcher: ${rec.sessionId} attempt $attempts (L2) — transport-aborted $n in-flight LLM request(s)"
              ) *>
                (if n > 0 then
                   nebflow.core.tools.BgTaskRegistry.reclaimSession(Some(rec.sessionId), wsHub.broadcast, rec.rootSessionId)
                     .handleErrorWith(e =>
                       logger.warn(s"TaskStuckWatcher: session reclaim for ${rec.sessionId} failed: ${e.getMessage}"))
                 else
                   logger.warn(
                     s"TaskStuckWatcher: ${rec.sessionId} attempt $attempts (L2) — no in-flight LLM request (tool-phase); " +
                       "process kill withheld [evidence #5 container-blindness guard]"
                   ))
            }.handleErrorWith(e =>
              logger.warn(s"TaskStuckWatcher: transport abort for ${rec.sessionId} failed: ${e.getMessage}")
            )
          def bridgeCancelled(reason: String) =
            rec.supervisorRef match
              case Some(sup) =>
                (sup ! AgentEvent.Cancelled(
                  rec.sessionId,
                  s"stuck for ${idleSecs}s ($reason) — released by TaskStuckWatcher"
                )).handleErrorWith(e =>
                  logger.warn(s"TaskStuckWatcher: bridge Cancelled for ${rec.sessionId} failed: ${e.getMessage}")
                )
              case None => IO.unit
          if !hard then
            // 回滚形态（HardRecoveryEnabled=false）：维持本批前行为——每轮
            // hard-cancel，StopAttempts+2 轮后 bridge Cancelled 终态收殓。
            hardCancel() *> {
              if attempts >= StopAttempts + 2 then
                logger.warn(
                  s"TaskStuckWatcher: ${rec.sessionId} still stuck after $attempts attempts — " +
                    "releasing via bridge Cancelled (hard-cancel ineffective; single-shot session, no restart)"
                ) *>
                  bridgeCancelled("hard-cancel ineffective") *>
                  // 面板实时终态帧（Sub-Agents 面板取消实时刷新修复）：仅 node-*
                  // 补发——dispatcher-* 由其观察桥拆除点（ProjectActor）统一补发。
                  (if rec.sessionId.startsWith(nebflow.core.project.NodeEngine.SessionPrefix)
                   then
                     nebflow.core.node.NodeRunner
                       .emitSubagentPanelDone(wsHub.broadcast, rec.sessionId, rec.rootSessionId)
                       .handleErrorWith(e =>
                         logger.warn(s"TaskStuckWatcher: panel done frame for ${rec.sessionId} failed: ${e.getMessage}")
                       )
                   else IO.unit) *>
                  stopCounts.update(_ - rec.sessionId)
              else IO.unit
            }
          else
            attempts match
              case 1 =>
                // L1 软恢复：halt 在飞 LLM——turn 若卡死在 LLM 流上，StuckAbort 浮出
                // → 有界重试 / turn-end 注入接管。action=halt（真实动作，P7 诚实帧）。
                logger.warn(
                  s"TaskStuckWatcher: ${rec.sessionId} attempt $attempts (L1) — halting in-flight LLM"
                ) *> broadcastStuck(wsHub, rec, idleSecs, "halt") *> hardCancel()
              case 2 =>
                // L2 硬中断（action=hard-abort）：transport abort + reclaimSession（见
                // transportAbort 的 evidence #5 护栏——非 LLM 楔死形态收回进程 kill）。
                logger.warn(
                  s"TaskStuckWatcher: ${rec.sessionId} attempt $attempts (L2) — transport abort + session process reclaim"
                ) *> broadcastStuck(wsHub, rec, idleSecs, "hard-abort") *> transportAbort()
              case 3 =>
                // L3 真重启（设计 D-5）：bridge Cancelled 清场（actor 停止 + 节点终
                // 态）→ 5s 让清场链走完 → hardResumeNode 从 transcript 断点续跑
                // （复用 boot-recovery 底座）。fork：扫描循环不等。action=restart
                // 此时为真（P7 诚实语义）。resume 成功才清计数（会话状态翻转/Running
                // → watcher 不再见其 stuck）；失败保留计数 → 第 4 拍 L4 failed 可达。
                logger.warn(
                  s"TaskStuckWatcher: ${rec.sessionId} attempt $attempts (L3) — releasing via bridge Cancelled, then hard-resume from transcript breakpoint"
                ) *> broadcastStuck(wsHub, rec, idleSecs, "restart") *>
                  bridgeCancelled("L3 hard-recovery: true resume from transcript breakpoint") *>
                  // 面板实时终态帧（Sub-Agents 面板取消实时刷新修复；beta.57 CI
                  // ProjectSessionCancelPanelFrameSpec 暴露的 hard 分支遗漏）：L3 桥
                  // Cancelled 是分级链上 node-* 旧会话的释放点，不补发则面板行幽灵
                  // 滞留到刷新。仅 node-* 补发——dispatcher-* 由其观察桥拆除点
                  // （ProjectActor Failed|Cancelled 分支）统一补发，与 !hard giveUp
                  // 分支同语义同护栏。
                  (if rec.sessionId.startsWith(nebflow.core.project.NodeEngine.SessionPrefix)
                   then
                     nebflow.core.node.NodeRunner
                       .emitSubagentPanelDone(wsHub.broadcast, rec.sessionId, rec.rootSessionId)
                       .handleErrorWith(e =>
                         logger.warn(s"TaskStuckWatcher: panel done frame for ${rec.sessionId} failed: ${e.getMessage}")
                       )
                   else IO.unit) *>
                  (IO.sleep(5.seconds) *>
                    hardResumeFlowNode(resources, rec).flatMap {
                      case true => stopCounts.update(_ - rec.sessionId)
                      case false => IO.unit
                    }).start.void
              case 4 =>
                // L4 响亮失败（诚实失败原则）：明确上报需人工处理、进度已落盘。
                logger.error(
                  s"TaskStuckWatcher: ${rec.sessionId} stuck for ${idleSecs}s — L1/L2/L3 all ineffective. " +
                    "This session needs MANUAL attention; progress is persisted (transcript + queues on disk)."
                ) *> broadcastStuck(wsHub, rec, idleSecs, "failed")
              case _ =>
                broadcastStuck(wsHub, rec, idleSecs, "failed")
            end match
        }
    else
        rec.parentRef match
        case Some(_) =>
          logger.warn(
            s"TaskStuckWatcher: sub-agent ${rec.sessionId} (kind=${rec.kind}) stuck in Processing " +
              s"for ${idleSecs}s > threshold — sending Stop for supervised restart"
          ) *>
            // AgentControl spec §3.5：子 agent 自动重启也广播 taskStuck（原先只有
            // 根 agent 广播）——前端可见「后台 agent 卡死，正在自动重启」，Nebula
            // 事后用 AgentControl(list) 能看到 retryCount。
            broadcastStuck(wsHub, rec, idleSecs, "restart") *>
            // gate-wedge P1-1: count ineffective Stops. A suspended agent never
            // consumes mailbox messages, so resending forever is useless (the
            // incident: thousands of resends over 6.5h). At the Nth attempt we
            // ALSO hard-cancel the in-flight LLM fiber — queued
            // or streaming — so the turn fails and the mailbox finally turns.
            stopCounts.modify { m =>
              val n = m.getOrElse(rec.sessionId, 0) + 1
              (m.updated(rec.sessionId, n), n)
            }.flatMap { attempts =>
              val escalate =
                if attempts >= StopAttempts then
                  nebflow.llm.LlmInterface.cancelInflightFor(rec.sessionId).flatMap { n =>
                    logger.warn(
                      s"TaskStuckWatcher: ${rec.sessionId} ignored $attempts Stops — hard-cancelled $n in-flight LLM request(s) (agent was suspended on its LLM fiber)"
                    )
                  }.handleErrorWith(e =>
                    logger.warn(s"TaskStuckWatcher: hard-cancel for ${rec.sessionId} failed: ${e.getMessage}")
                  )
                else IO.unit
              // Hard-recovery P5（2026-09-07）：Stop + halt 双失效后补 L2 transport
              // abort——parked read 的唯一解法（取证 §1.3），让 StuckAbort 真正浮出、
              // mailbox 恢复轮转、排队的 Stop 被消费、BackoffSupervisor 重启。
              val transportEscalate =
                if nebflow.shared.Defaults.HardRecoveryEnabled && attempts >= StopAttempts + 1 then
                  // L2 双管（裁定① tier-2）：LLM 流 transport abort + 会话自有进程组
                  // kill（工具挂死形态；按自有 PGID 击杀，会话保留）。护栏（证据 #5 ①）：
                  // 仅 transport abort 命中 ≥1 在飞请求（LLM 楔死形态）才 reclaimSession；
                  // 工具相位（容器 CPU 盲区）收回 kill，升级链走 supervisor Cancelled。
                  nebflow.llm.LlmInterface.transportAbortFor(rec.sessionId).flatMap { n =>
                    logger.warn(
                      s"TaskStuckWatcher: ${rec.sessionId} attempt $attempts (L2) — transport-aborted $n in-flight LLM request(s)"
                    ) *>
                      (if n > 0 then
                         nebflow.core.tools.BgTaskRegistry.reclaimSession(Some(rec.sessionId), wsHub.broadcast, rec.rootSessionId)
                           .handleErrorWith(e => logger.warn(s"TaskStuckWatcher: session reclaim for ${rec.sessionId} failed: ${e.getMessage}"))
                       else
                         logger.warn(
                           s"TaskStuckWatcher: ${rec.sessionId} attempt $attempts (L2) — no in-flight LLM request (tool-phase); " +
                             "process kill withheld [evidence #5 container-blindness guard]"))
                  }.handleErrorWith(e =>
                    logger.warn(s"TaskStuckWatcher: transport abort for ${rec.sessionId} failed: ${e.getMessage}")
                  )
                else IO.unit
              // issue #31 (2026-08-20): escalate 后两轮扫描仍 stuck = Stop
              // (mailbox，suspended 不消费) 与 hard-cancel (halt Deferred，
              // 当晚实证 turn fiber 挂在无取消注册的等待上、complete 无效) 双失效
              // ——supervisor 永远等不到终态，父的 outstandingSubagentResults 留下
              // phantom slot，held 结果永久滞留（root 永不触发轮次，直到重启）。
              // 兜底：经 supervisor 的 Cancelled 分支走完整清理链（与 AgentControl
              // cancel 同一路径，当晚 Nebula 手动 cancel 已实证可释放 barrier）：
              // 父 ExternalEvent(source=delegate/subtask → barrier 精确递减一次) +
              // taskStore cancelled + registry 移除 + child Stop + supervisor 自停。
              // reason 文本随 payload 注入父 LLM，指引重新派发。
              // 无 supervisorRef 的 Ephemeral/Flow 不进 barrier、无 phantom 风险
              // ——维持现状（Stop 循环），不发兜底。
              val giveUp =
                if attempts >= StopAttempts + 2 then
                  rec.supervisorRef match
                    case Some(sup) =>
                      logger.warn(
                        s"TaskStuckWatcher: ${rec.sessionId} still stuck after hard-cancel (attempt $attempts) — " +
                          "releasing parent barrier via supervisor Cancelled (Stop and hard-cancel both ineffective)"
                      ) *>
                        (sup ! AgentEvent.Cancelled(
                          rec.sessionId,
                          s"stuck for ${idleSecs}s with Stop and hard-cancel both ineffective — " +
                            "released by TaskStuckWatcher; consider re-delegating this task"
                        )).handleErrorWith(e =>
                          logger.warn(s"TaskStuckWatcher: supervisor Cancelled for ${rec.sessionId} failed: ${e.getMessage}")
                        ) *>
                        // 清掉本 session 的 Stop 计数：supervisor 自停后 registry 记录
                        // 被移除、下轮 scan 不再命中，此处手动清是双保险（避免同
                        // sessionId 未来回合继承旧计数提前触发 giveUp）。
                        stopCounts.update(_ - rec.sessionId)
                    case None => IO.unit
                else IO.unit
              escalate *> transportEscalate *> giveUp *> (rec.ref ! AgentCommand.Stop(s"stuck-task-${rec.sessionId}"))
                .handleErrorWith(e => logger.warn(s"TaskStuckWatcher: Stop to stuck sub-agent ${rec.sessionId} failed: ${e.getMessage}"))
            }
        case None =>
          // 根 agent（无 parentRef；含 dag- 旧 flow 会话）：分级接管 L1-L4（hard-
          // recovery P5 扩列，2026-09-07——此前只广播 attention，「卡死」的唯一
          // 出口 = 重启宿主，取证 §1.1）。回验同 flow 分支 = 下一轮扫描复查。
          // dag- 旧 flow 会话（kind=Flow，无 supervisorRef 也无 parentRef）EXEMPT：
          // 其取消走 cancelFlow / RunningFlowRegistry（TaskStuckWatcherSpec
          // :651 notice-only 铁律）——分级只适用于真 root（General/Team 根会话），
          // 不得对 dag- 施加任何硬取消/kill 动作。
          if !nebflow.shared.Defaults.HardRecoveryEnabled || rec.kind == nebflow.agent.AgentKind.Flow then
            logger.warn(
              s"TaskStuckWatcher: root agent ${rec.sessionId} (kind=${rec.kind}) stuck in Processing for ${idleSecs}s " +
                "— not auto-restarting, broadcast taskStuck for user decision"
            ) *>
              broadcastStuck(wsHub, rec, idleSecs, "attention")
          else
            stopCounts.modify { m =>
              val n = m.getOrElse(rec.sessionId, 0) + 1
              (m.updated(rec.sessionId, n), n)
            }.flatMap { attempts =>
              val (action, io) = attempts match
                case 1 =>
                  ("halt",
                    logger.warn(
                      s"TaskStuckWatcher: root agent ${rec.sessionId} stuck for ${idleSecs}s — L1: halting in-flight LLM"
                    ) *> nebflow.llm.LlmInterface.cancelInflightFor(rec.sessionId).void
                      .handleErrorWith(e => logger.warn(s"TaskStuckWatcher: L1 halt for ${rec.sessionId} failed: ${e.getMessage}")))
                case 2 =>
                  ("hard-abort",
                    logger.warn(
                      s"TaskStuckWatcher: root agent ${rec.sessionId} still stuck — L2: transport abort + session process reclaim"
                    ) *> nebflow.llm.LlmInterface.transportAbortFor(rec.sessionId).flatMap { n =>
                      logger.warn(s"TaskStuckWatcher: L2 aborted $n in-flight LLM request(s) of ${rec.sessionId}") *>
                        // 证据 #5 ① 护栏（同 flow transportAbort）：仅 LLM 楔死形态
                        // （n>0）才 reclaimSession 进程 kill；工具相位（容器 CPU 盲区）
                        // 收回，升级链走 tier-3。
                        (if n > 0 then
                           nebflow.core.tools.BgTaskRegistry.reclaimSession(Some(rec.sessionId), wsHub.broadcast, rec.rootSessionId)
                             .handleErrorWith(e => logger.warn(s"TaskStuckWatcher: L2 reclaim for ${rec.sessionId} failed: ${e.getMessage}"))
                         else
                           logger.warn(
                             s"TaskStuckWatcher: root agent ${rec.sessionId} L2 — no in-flight LLM request (tool-phase); " +
                               "process kill withheld [evidence #5 container-blindness guard]"))
                    }.handleErrorWith(e => logger.warn(s"TaskStuckWatcher: L2 abort for ${rec.sessionId} failed: ${e.getMessage}")))
                case 3 =>
                  ("restart",
                    logger.warn(
                      s"TaskStuckWatcher: root agent ${rec.sessionId} still stuck — L3: full actor restart " +
                        "(transcript reload from disk, queued injections preserved)"
                    ) *> (rec.ref ! nebflow.agent.AgentCommand.RestartAgent(nebflow.agent.RestartLevel.Full))
                      .handleErrorWith(e => logger.warn(s"TaskStuckWatcher: L3 restart for ${rec.sessionId} failed: ${e.getMessage}"))
                      *> stopCounts.update(_ - rec.sessionId))
                case 4 =>
                  ("failed",
                    logger.error(
                      s"TaskStuckWatcher: root agent ${rec.sessionId} stuck for ${idleSecs}s — L1/L2/L3 all ineffective. " +
                        "This session needs MANUAL attention (progress persisted on disk: transcript + injection queues)."
                    ))
                case _ =>
                  ("failed", IO.unit)
              broadcastStuck(wsHub, rec, idleSecs, action) *> io
            }

    end if

  /** L3 for Flow 节点：定位 session 所属项目的 runtime 并 hardResumeNode（跨项目
    * 按 rootSessionId 匹配 AgentRecord.rootSessionId = 项目 root 会话）。 */
  private def hardResumeFlowNode(resources: SharedResources, rec: AgentRecord): IO[Boolean] =
    nebflow.core.project.ProjectRuntimeRegistry.all
      .flatMap { rts =>
        rts.find(rt => rt.engine.rootSessionId.nonEmpty && rt.engine.rootSessionId == rec.rootSessionId) match
          case Some(rt) =>
            rt.engine.hardResumeNode(rec.sessionId).map(_.isDefined)
              .handleErrorWith(e =>
                logger.error(
                  s"TaskStuckWatcher: hard-resume failed for ${rec.sessionId}: ${Option(e.getMessage).getOrElse(e.toString)}"
                ).as(false))
          case None =>
            logger.warn(
              s"TaskStuckWatcher: no project runtime for ${rec.sessionId} (root=${rec.rootSessionId}) — L3 resume unavailable"
            ).as(false)
      }
      .handleErrorWith(e =>
        logger.error(s"TaskStuckWatcher: project runtime lookup failed: ${Option(e.getMessage).getOrElse(e.toString)}")
          .as(false))

end TaskStuckWatcher
