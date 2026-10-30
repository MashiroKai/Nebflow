package nebflow.core.tools

import cats.effect.IO
import io.circe.Json
import io.circe.syntax.*
import nebflow.actor.*
import nebflow.shared.NebflowLogger

/**
 * BgTaskCancel —— 单任务精确取消的统一面（后台任务生命周 P2 · 设计稿 §4.1
 * 「单任务取消是精确手术」）：N 条在册任务杀指定 1 条，其余存活且通知到位。
 *
 * == 通道齐平 ==
 *
 *  - **local**：`ShellSession.cancelBackgroundJob`（既有本地腿——杀树 + 收 fiber +
 *    出任务图），随后输出留存区翻 cancelled（既有幂等面）；
 *  - **remote**：`RemoteExecutor.requestPeerCancel`（bg-cancel 动作：本侧等待 fiber
 *    精确打断 + 对端进程树按 bgJobId 精确杀，1/N 判据）——对端确认失败也收本地
 *    终态（等待腿已绝，回执如实带原因）。
 *
 * == 终态语义（first-terminal-wins）==
 *
 * 杀伤成功（或 remote 的本侧腿已打断）后**取走行**（unregisterReturning，原子
 * 判空）：取不到 = 真完成/取消/收割已先落终态 ⇒ 本调用如实回「已终态」，零双注；
 * 取到 ⇒ 本调用落簿记（输出留存区）+ 通知（agent ExternalEvent + WS 帧），迟到
 * 的真完成只补输出留存区（BashTool/RemoteExecutor 的 P2 守卫）。
 *
 * == 与会话取消的边界 ==
 *
 * 本会话语义逐字不变（`reclaimSession` 仍是终态收殓）——本面只碰**一条任务行**，
 * 不碰会话/actor/兄弟任务。
 */
object BgTaskCancel:

  private val logger = NebflowLogger.forName("nebflow.bg-cancel")

  /**
   * 取消单条在册后台任务。
   *
   * @param jobId    目标任务（host 侧 jobId）
   * @param by       取消者注记（面板 / AgentControl 调用者标签——进留存区与载荷）
   * @param wsSend   调用方 WS 出口（None = 不发帧——spec 桩面）
   * @param agentRef 目标属主会话的 actor ref（None = 不投 agent 事件）
   * @return Right(详情) = 已落取消终态；Left(可读原因) = 未落（未找到/已终态/传输失败且本地未动）
   */
  def cancelOne(
    jobId: String,
    by: String,
    wsSend: Option[Json => IO[Unit]],
    agentRef: Option[ActorRef[AgentCommand]],
    remoteCancel: (String, String) => IO[Either[String, String]] =
      (device, id) => RemoteExecutor.current match
        case None    => IO.pure(Left("remote executor not initialized on this instance"))
        case Some(e) => e.requestPeerCancel(device, id)
  ): IO[Either[String, String]] =
    BgTaskRegistry.find(jobId).flatMap {
      case None => IO.pure(Left(s"Background task $jobId not found or already completed"))
      case Some(row) =>
        val killIO =
          if row.deviceName.nonEmpty then remoteCancel(row.deviceName, jobId)
          else
            ShellSession
              .forSession(row.sessionId)
              .flatMap(_.cancelBackgroundJob(jobId))
              .flatMap {
                case true  => IO.pure(Right("local process tree killed"))
                case false => IO.pure(Left(s"Background task $jobId not found or already completed"))
              }
              .handleErrorWith(e => IO.pure(Left(s"local cancel failed: ${e.getMessage}")))
        killIO.flatMap {
          case Left(err) => IO.pure(Left(err))
          case Right(killNote) =>
            // remote 腿：本侧等待已打断 ⇒ 本地终态照收（first-terminal-wins 由
            // 行取走判读）；killNote 携带对端确认详情。
            BgTaskRegistry.unregisterReturning(jobId).flatMap {
              case None =>
                // 竞速：真完成/显式取消先取走 —— 杀伤已发生（无害幂等），终态不归本调用。
                IO.pure(Left(s"Background task $jobId reached terminal state concurrently ($killNote)"))
              case Some(taken) =>
                val notifyAgent = agentRef.fold(IO.unit) { ref =>
                  (ref ! AgentCommand.ExternalEvent(
                    source = "background-task",
                    eventType = "cancelled",
                    payload = s"[Background task cancelled] Job ID: $jobId (Cancelled by $by)",
                    metadata = io.circe.JsonObject("jobId" -> jobId.asJson),
                    correlationId = Some(jobId)
                  )).handleErrorWith(e => logger.warn(s"bg-cancel agent notify failed for $jobId: ${e.getMessage}"))
                }
                val frame = wsSend.fold(IO.unit) { send =>
                  send(
                    Json.obj(
                      "type"          -> "backgroundTaskUpdate".asJson,
                      "sessionId"     -> taken.sessionId.asJson,
                      "rootSessionId" -> taken.rootSessionId.asJson,
                      "taskId"        -> jobId.asJson,
                      "description"   -> taken.description.asJson,
                      "status"        -> "completed".asJson
                    )
                  ).handleErrorWith(_ => IO.unit)
                }
                BgTaskOutputStore
                  .finalizeTask(jobId, "cancelled", None, Some(s"Cancelled by $by"))
                  .handleErrorWith(_ => IO.unit) *>
                  notifyAgent *>
                  frame *>
                  IO.pure(Right(s"Cancelled background task $jobId ($killNote)"))
            }
        }
    }

end BgTaskCancel
