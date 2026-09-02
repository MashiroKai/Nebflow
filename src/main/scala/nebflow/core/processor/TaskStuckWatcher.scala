package nebflow.core.processor

import cats.effect.IO
import cats.syntax.all.*
import io.circe.syntax.*
import nebflow.agent.{AgentCommand, AgentEvent, AgentKind, AgentRecord, AgentStatus, SharedResources}
import nebflow.core.NebflowLogger
import nebflow.gateway.WsHub

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
    // Project flow 会话（node-/dispatcher-，supervisorRef=观察桥）卡死恢复
    // （let-it-crash）：此前 parentRef=None 落入根 agent 分支只 notice——卡死的
    // 分发器/节点永远滞留为 Processing 幽灵行。恢复走 supervisorRef（观察桥）的
    // Cancelled 通道：dispatcher → 清 registry + 停 agent；node → resultDeferred
    // cancelled → engine cancelNode 全链清理。绝不发 raw Stop——单次会话无
    // supervisor 重启，Stop 杀掉 actor 而不发终态事件（桥收不到 → engine fiber
    // 挂死 / 桥僵尸）。第 1 次即硬取消在飞 LLM（无重启预算可消耗，硬取消是唯一
    // 快速恢复）；StopAttempts+2 次仍卡 → 桥 Cancelled 兜底（覆盖工具挂死等
    // 无在飞 LLM 的形态）。dag- 旧 flow 会话（无 supervisorRef）不进此分支，
    // 维持根 agent notice（其取消走 cancelFlow）。
    else if rec.kind == AgentKind.Flow && rec.supervisorRef.isDefined then
      logger.warn(
        s"TaskStuckWatcher: Project flow session ${rec.sessionId} (kind=Flow) stuck in Processing " +
          s"for ${idleSecs}s > threshold — hard-cancelling in-flight LLM, escalating to bridge Cancelled"
      ) *>
        broadcastStuck(wsHub, rec, idleSecs, "restart") *>
        stopCounts.modify { m =>
          val n = m.getOrElse(rec.sessionId, 0) + 1
          (m.updated(rec.sessionId, n), n)
        }.flatMap { attempts =>
          val escalate = nebflow.llm.LlmInterface.cancelInflightFor(rec.sessionId).flatMap { n =>
            logger.warn(
              s"TaskStuckWatcher: ${rec.sessionId} attempt $attempts — hard-cancelled $n in-flight LLM request(s)"
            )
          }.handleErrorWith(e =>
            logger.warn(s"TaskStuckWatcher: hard-cancel for ${rec.sessionId} failed: ${e.getMessage}")
          )
          val giveUp =
            if attempts >= StopAttempts + 2 then
              rec.supervisorRef match
                case Some(sup) =>
                  logger.warn(
                    s"TaskStuckWatcher: ${rec.sessionId} still stuck after $attempts attempts — " +
                      "releasing via bridge Cancelled (hard-cancel ineffective; single-shot session, no restart)"
                  ) *>
                    (sup ! AgentEvent.Cancelled(
                      rec.sessionId,
                      s"stuck for ${idleSecs}s with in-flight LLM hard-cancel ineffective — " +
                        "released by TaskStuckWatcher; re-trigger the task if it should continue"
                    )).handleErrorWith(e =>
                      logger.warn(s"TaskStuckWatcher: bridge Cancelled for ${rec.sessionId} failed: ${e.getMessage}")
                    ) *>
                    // 面板实时终态帧（Sub-Agents 面板取消实时刷新修复）：giveUp
                    // 取消此前零 WS 出口 → 面板行幽灵滞留到刷新。仅 node-* 在此
                    // 补发——dispatcher-* 由其观察桥拆除点（ProjectActor）统一
                    // 补发，避免双发。
                    (if rec.sessionId.startsWith(nebflow.core.project.NodeEngine.SessionPrefix)
                     then
                       nebflow.core.node.NodeRunner
                         .emitSubagentPanelDone(wsHub.broadcast, rec.sessionId, rec.rootSessionId)
                         .handleErrorWith(e =>
                           logger.warn(s"TaskStuckWatcher: panel done frame for ${rec.sessionId} failed: ${e.getMessage}")
                         )
                     else IO.unit) *>
                    stopCounts.update(_ - rec.sessionId)
                case None => IO.unit
            else IO.unit
          escalate *> giveUp
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
              escalate *> giveUp *> (rec.ref ! AgentCommand.Stop(s"stuck-task-${rec.sessionId}"))
                .handleErrorWith(e => logger.warn(s"TaskStuckWatcher: Stop to stuck sub-agent ${rec.sessionId} failed: ${e.getMessage}"))
            }
        case None =>
          logger.warn(
            s"TaskStuckWatcher: root agent ${rec.sessionId} stuck in Processing for ${idleSecs}s " +
              "— not auto-restarting, broadcast taskStuck for user decision"
          ) *>
            broadcastStuck(wsHub, rec, idleSecs, "attention")

    end if

end TaskStuckWatcher
