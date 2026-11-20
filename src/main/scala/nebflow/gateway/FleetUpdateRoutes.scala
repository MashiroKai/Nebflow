package nebflow.gateway

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import nebflow.core.hotupdate.FleetUpdateCoordinator
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*

/**
 * 全设备统一更新 · REST 面（fleet 批 2026-11-01，作者令「一键把所有设备更新到同一版本」）。
 *
 * 四个端点，全部经既有 `withAuth` 门（同一鉴权面、零新增凭据）：
 *
 * | 端点 | 作用 |
 * |---|---|
 * | `GET  /api/update/fleet` | 计划（版本核对表 + 收口四句）+ 当前任务快照 |
 * | `POST /api/update/fleet` | 发起（**必带 `confirm:true`**；不在飞才能发起） |
 * | `POST /api/update/fleet/cancel` | 中断（不再发起新推送；已发起的如实标注不可撤回） |
 * | `POST /api/update/fleet/retry` | 一键重试失败项（回滚档不自动重试） |
 * | `GET  /api/update/fleet/events` | **状态卡的轻量重连面**（重连/刷新后接续；等价于快照） |
 *
 * 纪律：
 *   · 审计 —— 发起 / 中断 / 重试 / 收口 / 哨兵补推 **每次**都要在
 *     `<dataRoot>/hotupdate/fleet-audit.jsonl` 留一行（协调器内单点写）；本层不加第二处。
 *   · 确认 —— 缺 `confirm` ⇒ 400 + 可行动错误（**不静默、不默认 true**）。
 *   · 一致性 —— 载荷字段名与 WS `fleetUpdate` 帧同源（`FleetUpdateCoordinator#snapshot`
 *     与 `FleetJob#toJson`），**前端只有一套解析**。
 */
private[gateway] object FleetUpdateRoutes:

  def routes(ctx: RestApiCtx): HttpRoutes[IO] =
    import ctx.*
    HttpRoutes.of[IO] {

      // 计划相（版本核对 + 能力门槛 + 分发分支 + 收口状态）：只读，零动作。
      case req @ GET -> Root / "update" / "fleet" =>
        withAuth(req) {
          sharedResources.fleetUpdate match
            case None => ServiceUnavailable(Json.obj("error" -> "fleet update is not wired in this instance".asJson))
            case Some(fleet) =>
              (fleet.restore() *> fleet.plan()).flatMap { plan =>
                fleet.snapshot.map { snap =>
                  // 计划与运行态合成一帧（前端一读即全，禁两次往返）。
                  val merged = snap.deepMerge(Json.obj("plan" -> plan.toJson))
                  Response[IO](Status.Ok).withEntity(merged)
                }
              }
        }

      // 重连面（状态卡断线重连后接续用；与 GET 同源，只是不带计划现算 —— 便宜、可高频）。
      case req @ GET -> Root / "update" / "fleet" / "events" =>
        withAuth(req) {
          sharedResources.fleetUpdate match
            case None => ServiceUnavailable(Json.obj("error" -> "fleet update is not wired in this instance".asJson))
            case Some(fleet) => fleet.snapshot.flatMap(j => Ok(j))
        }

      // 发起：必带确认位（作者令「更新动作要有确认与审计」）。
      case req @ POST -> Root / "update" / "fleet" =>
        withAuth(req) {
          sharedResources.fleetUpdate match
            case None => ServiceUnavailable(Json.obj("error" -> "fleet update is not wired in this instance".asJson))
            case Some(fleet) =>
              req.as[Json].attempt.flatMap { body =>
                val hc = body.toOption.getOrElse(Json.Null).hcursor
                val confirm = hc.downField("confirm").as[Boolean].getOrElse(false)
                val beta = hc.downField("beta").as[Boolean].getOrElse(false)
                val source = hc.downField("source").as[String].toOption.filter(_.trim.nonEmpty).getOrElse("api")
                fleet.start(confirm, source, beta).flatMap {
                  case Left(err) =>
                    val status = if confirm then Status.Conflict else Status.BadRequest
                    IO.pure(Response[IO](status).withEntity(Json.obj("ok" -> false.asJson, "error" -> err.asJson)))
                  case Right(job) =>
                    Ok(Json.obj("ok" -> true.asJson, "jobId" -> job.id.asJson, "job" -> job.summaryJson))
                }
              }
        }

      // 中断：作者可感知、可中断。
      case req @ POST -> Root / "update" / "fleet" / "cancel" =>
        withAuth(req) {
          sharedResources.fleetUpdate match
            case None => ServiceUnavailable(Json.obj("error" -> "fleet update is not wired in this instance".asJson))
            case Some(fleet) =>
              fleet.cancel().flatMap {
                case Left(err) => Conflict(Json.obj("ok" -> false.asJson, "error" -> err.asJson))
                case Right(snap) => Ok(Json.obj("ok" -> true.asJson).deepMerge(snap))
              }
        }

      // 一键重试失败项（不阻塞其他设备 —— 失败隔离的反向面）。
      case req @ POST -> Root / "update" / "fleet" / "retry" =>
        withAuth(req) {
          sharedResources.fleetUpdate match
            case None => ServiceUnavailable(Json.obj("error" -> "fleet update is not wired in this instance".asJson))
            case Some(fleet) =>
              fleet.retryFailed().flatMap {
                case Left(err) => Conflict(Json.obj("ok" -> false.asJson, "error" -> err.asJson))
                case Right(snap) => Ok(Json.obj("ok" -> true.asJson).deepMerge(snap))
              }
        }
    }
  end routes

end FleetUpdateRoutes
