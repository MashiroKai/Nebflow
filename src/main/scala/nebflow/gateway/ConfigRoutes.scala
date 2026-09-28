/* 从 RestApiRoutes 迁出(行为保持重构,2026-09-24)。 */
package nebflow.gateway

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import nebflow.core.*
import nebflow.core.schedule.FreezeSchedule.given
import nebflow.service.ConfigService
import nebflow.shared.PathUtil
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*

/**
 * 配置域(config,F 步 2026-09-24):原 RestApiRoutes.routes 巨型 match 的
 * /config(GET+PATCH)、/safety 全族、/canvas-tabs 全族 case 逐字迁入(域内
 * 保持原 case 相对目录顺序),行为保持;经 RestApiRoutes.routes 级联挂载,
 * 各臂首段路径字面量(config/safety/canvas-tabs)与其余域互不重叠。
 */
private[gateway] object ConfigRoutes:

  def routes(ctx: RestApiCtx): HttpRoutes[IO] =
    import ctx.*
    HttpRoutes.of[IO] {
      // Config
      case req @ GET -> Root / "config" =>
        withAuth(req) {
          ConfigService.getConfig.flatMap { cfg =>
            ConfigService.isConfigured.flatMap { configured =>
              // P0（2026-08-30）：freezeState 进 REST 通道——前端刷新/重连若走
              // REST 拉配置（而非仅依赖 WS serverConfig 推送），也能读到当前
              // 冻结态（含 skip 语义：skipped=true/frozen=false）。
              for
                wsCfg <- sharedResources.freezeScheduleRef.get
                skipUntil <- sharedResources.freezeSkipUntilRef.get
                resp <- Ok(
                  Json.obj(
                    "config" -> cfg.asJson,
                    "configured" -> configured.asJson,
                    "freezeState" -> nebflow.core.schedule.FreezeSchedule
                      .freezeStateNode(wsCfg, skipUntil, System.currentTimeMillis())
                      .asJson
                  )
                )
              yield resp
            }
          }
        }

      // ── 权限模式（应用级全局单一来源；permshield S1 / 2026-09-13 作者重裁）──────
      // GET /api/safety —— 全局权限模式的**权威观测面**（QA/CLI 可 curl 判定）：
      //   defaultMode = 当前生效值（读不到有效值 ⇒ 启动默认顶档，见 GlobalSafety）；
      //   configured  = 配置文件里是否写了**可识别**的显式值（三档之一）。键缺失 /
      //                 类型不符 / 值不可识别 / 文件不可解析 一律 false。
      case req @ GET -> Root / "safety" =>
        withAuth(req) {
          val configPath = PathUtil.configJsonReadPath(PathUtil.dataRoot)
          (for
            mode <- nebflow.core.GlobalSafety.defaultMode
            rawValue <- IO
              .blocking {
                if !os.exists(configPath) then None
                else
                  io.circe.parser
                    .parse(os.read(configPath))
                    .toOption
                    .flatMap(_.hcursor.downField("safety").downField("defaultMode").as[String].toOption)
              }
              .handleErrorWith(_ => IO.pure(None))
          yield Json.obj(
            "defaultMode" -> nebflow.core.SafetyMode.toString(mode).asJson,
            "configured" -> rawValue.exists(v => nebflow.core.SafetyMode.fromWire(v).isDefined).asJson,
            "source" -> configPath.toString.asJson
          )).flatMap(Ok(_))
        }

      // PUT /api/safety/mode —— 全局权限模式的 REST 写入口（定向写，不走
      // `PATCH /api/config` 的全量快照语义，避免陈旧底稿回滚无关键）。
      // permshield S1 起这是**两个写入口之一**：另一个是 WS `setSafetyMode`（盾牌），
      // 二者共用同一个 `ConfigService.setSafetyDefaultMode` ⇒ 同一条持久路径。
      // 非三档显式值 ⇒ 400 且**不落盘**（不静默兜底）。
      case req @ PUT -> Root / "safety" / "mode" =>
        withAuth(req) {
          req.as[Json].flatMap { body =>
            val rawMode = body.hcursor.downField("mode").as[String].toOption.getOrElse("")
            nebflow.core.SafetyMode.fromWire(rawMode) match
              case None =>
                BadRequest(
                  Json.obj(
                    "error" -> s"unknown mode '$rawMode' — valid: confirm-edits, auto-edits, auto-all".asJson
                  )
                )
              case Some(mode) =>
                val modeStr = nebflow.core.SafetyMode.toString(mode)
                ConfigService.setSafetyDefaultMode(modeStr) *>
                  wsHub.broadcast(Json.obj("type" -> "configUpdated".asJson, "success" -> true.asJson)) *>
                  Ok(Json.obj("updated" -> true.asJson, "defaultMode" -> modeStr.asJson))
          }
        }

      // ── Canvas tabs 服务端持久化（F1 根治，2026-08-30）────────────────────
      // 作者报告「JVM 重启后标签页丢失」——浏览器 localStorage 不可靠（Safari
      // 无痕/多窗口 removeItem 竞态/清理）。服务端存档 ~/.nebflow/canvas_tabs.json
      // 与 sessionStore 同生命周期，JVM 重启保留；前端 restoreTabs 优先拉这里。
      // 契约（与 Frontend 同步）：GET → 200 {v:2,tabs:[...]} 或 404（无存档）；
      // PUT body {v:2,tabs:[...]} → 200 {ok:true}；非法输入 400 / 超限 413 不落盘。
      case req @ GET -> Root / "canvas-tabs" =>
        withAuth(req) {
          new CanvasTabStore(PathUtil.dataRoot / "canvas_tabs.json").load().flatMap {
            case Some(json) => Ok(json)
            case None => NotFound(Json.obj("error" -> "no canvas tabs archive".asJson))
          }
        }

      case req @ PUT -> Root / "canvas-tabs" =>
        withAuth(req) {
          // 读 body 有界：take(max+1) 后超限即 413，防大 payload 拉爆内存。
          val maxBytes = CanvasTabs.MaxBodyBytes
          req.body.take(maxBytes.toLong + 1).compile.toVector.flatMap { bytes =>
            CanvasTabs.parseBody(bytes.toArray, maxBytes) match
              case Left((status, msg)) =>
                val st = org.http4s.Status.fromInt(status).getOrElse(Status.BadRequest)
                IO.pure(Response[IO](status = st).withEntity(Json.obj("error" -> msg.asJson)))
              case Right(json) =>
                new CanvasTabStore(PathUtil.dataRoot / "canvas_tabs.json")
                  .save(json) *> Ok(ApiJson.ok)
                  .handleErrorWith { e =>
                    logger.error(s"canvas tabs save failed: ${e.getMessage}") *>
                      IO.pure(
                        Response[IO](status = Status.InternalServerError)
                          .withEntity(Json.obj("error" -> s"save failed: ${e.getMessage}".asJson))
                      )
                  }
          }
        }

      // Update config
      case req @ PATCH -> Root / "config" =>
        withAuth(req) {
          req.as[Json].flatMap { body =>
            val cfgStr = body.hcursor.downField("config").as[String].getOrElse(body.noSpaces)
            // 冻结修复（2026-08-27）：runtime 热更键以内存 ref 为权威（镜像 WS
            // updateConfig 分支）——PATCH 快照陈旧时不得回滚这些键。
            (
              sharedResources.freezeScheduleRef.get,
              sharedResources.thinkingConfigRef.get,
              sharedResources.toolResultTtlRef.get
            ).mapN { (wsCfg, thCfg, ttlCfg) =>
              Map[String, io.circe.Json](
                "workSchedule" -> wsCfg.asJson,
                "thinkingConfig" -> thCfg.asJson,
                "toolResultTtl" -> ttlCfg.asJson
              )
            }.flatMap { runtimeOverrides =>
              ConfigService.updateConfig(cfgStr, runtimeOverrides).flatMap {
                case Left(err) => BadRequest(Json.obj("error" -> err.asJson))
                case Right(_) =>
                  sharedResources.providerRegistry
                    .reloadConfig(Some(sharedResources.sessionModelOverrides))
                    .attempt
                    .flatMap {
                      case Right(staleIds) =>
                        // #33: keep the persisted session meta in sync (see
                        // WebSocketRoutes updateConfig — same cleanup).
                        staleIds.traverse_(id => sessionStore.updateSessionModel(id, None)) *>
                          logger.info("Config hot-reloaded via REST")
                      case Left(e) => logger.warn(s"Config hot-reload failed: ${e.getMessage}")
                    } *> Ok(Json.obj("updated" -> true.asJson))
              }
            }
          }
        }

    }
  end routes

end ConfigRoutes
