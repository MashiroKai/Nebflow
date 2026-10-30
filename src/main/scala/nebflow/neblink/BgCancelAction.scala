package nebflow.neblink

import cats.effect.IO
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.core.tools.RemoteExecCancelRegistry

/**
 * Handler for the "BgCancel" action — 对端 remote-exec 后台进程的按 jobId 精确
 * 取消（后台任务生命周 P2 批 · 单任务取消的远端腿接收端）。
 *
 * 由两条通道同表分发（与 FileTransfer/Notify/RemoteUpdate 同型）：
 *   - relay 腿：`NeblinkRelayTunnel.handleRelayRequestLive` 的 `case "BgCancel"`；
 *   - P2P 腿：`NeblinkRoutes` /neblink/remote-exec 的 `case "BgCancel"`。
 *
 * == 安全面 ==
 *
 * 本动作与「下发远端 Bash」走**同一条已鉴权 remote-exec 通道**（P2P =
 * verifyPeerAccess 设备鉴权；relay = 隧道会话鉴权）——能下发即能取消，零新
 * 权限口子。请求/响应本体即确认帧：响应 output 带 cancelled 标记与 jobId。
 * 载荷只含 host 派发时生成的 bgJobId（无路径/无命令文本），对端不据此解析
 * 任何用户数据。
 *
 * == 语义 ==
 *
 *   - jobId 在册（对端正有该 remote-exec 进程）⇒ 杀整树（
 *     [[RemoteExecCancelRegistry.cancel]] 取走式，并发取消幂等），回执
 *     `{jobId, cancelled: true, aliveBeforeCancel: true}`；
 *   - 不在册 ⇒ 进程已退出/未登记（版本偏斜、重启、自然终态、**或同 jobId 的
 *     二次取消**）⇒ Left 带可读原因——「不在册」是唯一事实态，host 侧据此如实
 *     申报「对端进程已不在」，本地终态照收（first-terminal-wins，见
 *     [[nebflow.core.tools.BgTaskCancel]]）。
 *
 * 零墙钟：判定只有「登记表内核进程事实」一个依据，不设任何到点判死。
 */
object BgCancelAction:

  def handle(params: JsonObject): IO[Either[String, Json]] =
    val jobId = params("jobId").flatMap(_.asString).getOrElse("")
    if jobId.trim.isEmpty then
      IO.pure(Left("BgCancel requires a non-empty 'jobId' parameter (host-issued bgJobId)"))
    else
      RemoteExecCancelRegistry.isLive(jobId).flatMap { aliveBefore =>
        RemoteExecCancelRegistry.cancel(jobId).map {
          case true =>
            Right(
              Json.obj(
                "jobId"          -> jobId.asJson,
                "cancelled"      -> true.asJson,
                "aliveBeforeCancel" -> aliveBefore.asJson
              )
            )
          case false =>
            Left(
              s"no live remote-exec process registered for jobId '$jobId' " +
                "(already exited, never registered on this device, or this device restarted)"
            )
        }
      }
