package nebflow.core.processor

import cats.effect.IO
import cats.syntax.all.*
import io.circe.syntax.*
import nebflow.agent.{AgentCommand, AgentKind, AgentRecord, AgentStatus, SharedResources}
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
 *   - 根 agent（无 parentRef）：不自动重启——广播 taskStuck WS 事件 + 日志，
 *     由用户决定。
 *
 * 阈值协同：默认 10min ≫ llm-fail 退避上限（8s×3 + probe 120s）——重试链每次
 * 动作都 touch 活动戳，不会在重试链完成前误判。
 */
object TaskStuckWatcher:

  private val logger = NebflowLogger.forName("nebflow.core.processor.stuck")

  /** 与 WebSocketRoutes.filterActiveAgents 相同的 task 集合（保持单一事实源意识）。 */
  private val taskKinds = Set(AgentKind.Delegate, AgentKind.Ephemeral, AgentKind.Flow, AgentKind.SubTask)

  /**
   * 扫描集合 = taskKinds + Root。Root（Nebula 主窗口）虽不在活跃子代理列表
   * （filterActiveAgents 语义），但其 turn 同样可能卡死（如 12:39 现场 Nebula
   * 自身撞限流）——设计 §4.4 要求根 agent 也通知（不自动重启）。Team 长期驻留
   * 且不在此集合，保持与 filterActiveAgents 一致的边界。
   */
  private val scannedKinds: Set[AgentKind] = taskKinds + AgentKind.Root

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
    def loop: IO[Unit] =
      scan(resources, wsHub, thresholdMs).handleErrorWith(e =>
        logger.warn(s"TaskStuckWatcher scan failed (will retry next cycle): ${e.getMessage}")
      ) *> IO.sleep(interval) >> loop
    loop

  /** 单轮扫描：识别卡死 agent 并执行恢复动作。独立成函数便于单元测试。 */
  def scan(resources: SharedResources, wsHub: WsHub, thresholdMs: Long): IO[Unit] =
    val now = System.currentTimeMillis()
    resources.agentRegistry.get.flatMap { registry =>
      registry.values.toList
        .filter(rec => scannedKinds.contains(rec.kind))
        .filter(rec => rec.status == AgentStatus.Processing)
        .filter(rec => rec.lastActivityMs > 0 && now - rec.lastActivityMs > thresholdMs)
        .traverse_ { rec =>
          recover(resources, wsHub, rec, now)
        }
    }

  /** 恢复动作：子 agent 重启 / 根 agent 通知。 */
  private def recover(resources: SharedResources, wsHub: WsHub, rec: AgentRecord, now: Long): IO[Unit] =
    val idleSecs = (now - rec.lastActivityMs) / 1000
    rec.parentRef match
      case Some(_) =>
        logger.warn(
          s"TaskStuckWatcher: sub-agent ${rec.sessionId} (kind=${rec.kind}) stuck in Processing " +
            s"for ${idleSecs}s > threshold — sending Stop for supervised restart"
        ) *>
          (rec.ref ! AgentCommand.Stop(s"stuck-task-${rec.sessionId}"))
            .handleErrorWith(e => logger.warn(s"TaskStuckWatcher: Stop to stuck sub-agent ${rec.sessionId} failed: ${e.getMessage}"))
      case None =>
        logger.warn(
          s"TaskStuckWatcher: root agent ${rec.sessionId} stuck in Processing for ${idleSecs}s " +
            "— not auto-restarting, broadcast taskStuck for user decision"
        ) *>
          wsHub
            .broadcast(
              io.circe.Json.obj(
                "type" -> "taskStuck".asJson,
                "sessionId" -> rec.sessionId.asJson,
                "kind" -> rec.kind.toString.asJson
              )
            )
            .handleErrorWith(e => logger.warn(s"TaskStuckWatcher: taskStuck WS broadcast failed: ${e.getMessage}"))

end TaskStuckWatcher
