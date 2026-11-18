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
      // 契约（与 Frontend 同步）：GET → 200 {v:2,tabs:[...],tombstones?} 或 404
      // （无存档）；PUT body {v:2,tabs:[...],tombstones?,reopened?} → 200 {ok:true}
      // —— #910（2026-10-26）起 PUT 不再整档覆盖：锁内 load→merge→save 落墓碑
      // 语义（关闭意图优先，显式重开才恢复，TTL 30d/上限 500）；非法输入 400 /
      // 超限 413 不落盘。
      // 跨设备实时同步（canvas-tab-sync 批 2026-11-17）：作者报障「各端 canvas
      // 页签不同步——单实例的本意就是任一端开/关/切其他端一致」。此前存档只在
      // 页面加载时被 GET（restoreTabs），运行中无推送腿。修法=补最小广播：PUT
      // 接受落盘后把**合并后的权威存档**经既有 wsHub.broadcast 外推一帧
      // `canvasTabsChanged`（configUpdated / projectArchived 同面零第二套推送），
      // 各端按 #910 墓碑语义就地收敛（详见 js/canvas.js applyLiveTabsFrame）。
      case req @ GET -> Root / "canvas-tabs" =>
        withAuth(req) {
          new CanvasTabStore(PathUtil.dataRoot / "canvas_tabs.json").load().flatMap {
            case Some(json) => Ok(json)
            case None => NotFound(Json.obj("error" -> "no canvas tabs archive".asJson))
          }
        }

      case req @ GET -> Root / "canvas-writer" =>
        withRecoveryPrincipal(req) { principal =>
          canvasTakeover.canvasRole(principal, req.params.getOrElse("clientId", ""), req.params.getOrElse("tabInstanceId", "")).flatMap {
            case Some(epoch) => Ok(Json.obj("role" -> "writer".asJson, "epoch" -> epoch.asJson))
            case None => Ok(Json.obj("role" -> "reader".asJson, "epoch" -> Json.Null))
          }
        }

      case req @ POST -> Root / "canvas-writer" / action if action == "claim" || action == "release" =>
        withRecoveryPrincipal(req) { principal =>
          req.attemptAs[Json].value.flatMap {
            case Left(_) => BadRequest(Json.obj("error" -> "invalid_body".asJson))
            case Right(body) =>
              val c = body.hcursor
              val client = c.get[String]("clientId").toOption.filter(s => s.trim.nonEmpty && s.length <= 128)
              val tab = c.get[String]("tabInstanceId").toOption.filter(s => s.trim.nonEmpty && s.length <= 128)
              (client, tab) match
                case (Some(id), Some(t)) if action == "claim" =>
                  canvasTakeover.claimCanvas(principal, id, t, c.get[Long]("expectedEpoch").toOption).flatMap {
                    case Some(epoch) =>
                      wsHub.broadcast(Json.obj("type" -> "canvasWriterChanged".asJson, "epoch" -> epoch.asJson)) *>
                        Ok(Json.obj("role" -> "writer".asJson, "epoch" -> epoch.asJson))
                    case None => Conflict(Json.obj("error" -> "concurrent_writer_claim".asJson))
                  }
                case (Some(id), Some(t)) =>
                  c.get[Long]("epoch").toOption match
                    case Some(epoch) => canvasTakeover.releaseCanvas(principal, id, t, epoch).flatMap {
                      case true => Ok(Json.obj("role" -> "reader".asJson))
                      case false => Conflict(Json.obj("error" -> "stale_writer_epoch".asJson))
                    }
                    case None => BadRequest(Json.obj("error" -> "invalid_epoch".asJson))
                case _ => BadRequest(Json.obj("error" -> "invalid_writer_identity".asJson))
          }
        }

      case req @ PUT -> Root / "canvas-tabs" =>
        withRecoveryPrincipal(req) { principal =>
          // 读 body 有界：take(max+1) 后超限即 413，防大 payload 拉爆内存。
          val maxBytes = CanvasTabs.MaxBodyBytes
          req.body.take(maxBytes.toLong + 1).compile.toVector.flatMap { bytes =>
            val raw = bytes.toArray
            val parsed = if raw.length <= maxBytes then io.circe.parser.parse(new String(raw, java.nio.charset.StandardCharsets.UTF_8)).toOption else None
            val cursor = parsed.map(_.hcursor)
            val client = cursor.flatMap(_.get[String]("clientId").toOption).filter(s => s.trim.nonEmpty && s.length <= 128)
            val tab = cursor.flatMap(_.get[String]("tabInstanceId").toOption).filter(s => s.trim.nonEmpty && s.length <= 128)
            val epoch = cursor.flatMap(_.get[Long]("epoch").toOption)
            val operation = parsed.filter(_.hcursor.downField("operationId").succeeded)
            def save: IO[Response[IO]] = if operation.nonEmpty then
              val json = operation.get
              new CanvasTabStore(PathUtil.dataRoot / "canvas_tabs.json").applyOperation(json).flatMap {
                case Left(reason) =>
                  logger.warn(s"canvas tab operation rejected: $reason source=${req.remoteAddr} operationId=${json.hcursor.get[String]("operationId").getOrElse("invalid")}") *>
                    BadRequest(Json.obj("error" -> reason.asJson))
                case Right((result, duplicate)) =>
                  val ack = Json.obj("ok" -> true.asJson, "operationId" -> result.hcursor.downField("operationId").focus.getOrElse(Json.Null), "writeVersion" -> result.hcursor.downField("writeVersion").focus.getOrElse(Json.Null))
                  (if duplicate then IO.unit else wsHub.broadcast(result.deepMerge(Json.obj("type" -> "canvasTabsChanged".asJson)))) *> Ok(ack)
              }
            else CanvasTabs.parseBody(raw, maxBytes) match
              case Left((status, msg)) =>
                val st = org.http4s.Status.fromInt(status).getOrElse(Status.BadRequest)
                IO.pure(Response[IO](status = st).withEntity(Json.obj("error" -> msg.asJson)))
              case Right(json) =>
                (logger.warn(s"canvas tabs legacy PUT deprecated; missing tabs ignored, source=${req.remoteAddr}") *>
                  new CanvasTabStore(PathUtil.dataRoot / "canvas_tabs.json")
                    .mergeSave(json, source = req.remoteAddr.fold("unknown")(_.toString)))
                  .flatMap { merged =>
                    // 写穿即外推（canvas-tab-sync 批 2026-11-17）：合并后的权威
                    // 存档广播给全部已连端，他端开/关/切/排序即时跟随，不等刷新。
                    // 帧载荷 = GET 同构（v/tabs/tombstones/activeTabId）+ type 键。
                    wsHub.broadcast(
                      merged.deepMerge(Json.obj("type" -> "canvasTabsChanged".asJson))
                    ) *> Ok(ApiJson.ok)
                  }
                  .handleErrorWith { e =>
                    logger.error(s"canvas tabs save failed: ${e.getMessage}") *>
                      IO.pure(
                        Response[IO](status = Status.InternalServerError)
                          .withEntity(Json.obj("error" -> s"save failed: ${e.getMessage}".asJson))
                      )
                  }
            (client, tab, epoch) match
              case (Some(id), Some(t), Some(e)) =>
                canvasTakeover.withCanvasWriter(principal, id, t, e)(save).flatMap {
                  case Some(response) => IO.pure(response)
                  case None => Conflict(Json.obj("error" -> "stale_writer_epoch".asJson, "role" -> "reader".asJson))
                }
              case _ => Conflict(Json.obj("error" -> "canvas_read_only".asJson, "role" -> "reader".asJson))
          }
        }

      // ── Executors（executor-registry 批 2026-10-03）──────────────────────
      // GET /api/executors —— 统一 delegate 的执行器快照：全部在册执行器 +
      // 文件系统检测（外部 CLI：PATH + 常用前缀）+ 配置态（default/disabled）。
      // 每次调用现检测（外部 CLI 安装/卸载即时反映），配置块热读。
      case req @ GET -> Root / "executors" =>
        withAuth(req) {
          Ok(nebflow.core.executor.ExecutorRegistry.snapshotJson)
        }

      case req @ GET -> Root / "executors" / id / "model" =>
        withAuth(req) {
          val supported = Set("claude-code", "codex").contains(id)
          if !nebflow.core.executor.ExecutorRegistry.defs.exists(d => d.id == id && d.external) then
            NotFound(Json.obj("error" -> "unknown executor".asJson))
          else
            Ok(Json.obj(
              "model" -> nebflow.core.executor.ExecutorRegistry.configuredModel(id).asJson,
              "supported" -> supported.asJson,
              "suggestions" -> (if id == "claude-code" then List("sonnet", "opus") else if id == "codex" then List("o3") else Nil).asJson
            ))
        }

      case req @ PUT -> Root / "executors" / id / "model" =>
        withAuth(req) {
          req.as[Json].flatMap { body =>
            body.hcursor.get[String]("model") match
              case Left(_) => BadRequest(Json.obj("error" -> "model must be a string".asJson))
              case Right(model) =>
                ConfigService.setExecutorModel(id, model).flatMap {
                  case Left(err) => BadRequest(Json.obj("error" -> err.asJson))
                  case Right(_) => Ok(Json.obj("updated" -> true.asJson, "model" -> model.trim.asJson))
                }
          }
        }

      // PUT /api/executors/default —— 设默认执行器。body {"id": "<executorId>"};
      // 未在册 ⇒ 400 不落盘。内置执行器不可 disabled（无 disabled 臂——面板
      // 只切 default；disabled 白名单写入留给后续批）。
      case req @ PUT -> Root / "executors" / "default" =>
        withAuth(req) {
          req.as[Json].flatMap { body =>
            val id = body.hcursor.downField("id").as[String].toOption.getOrElse("")
            if !nebflow.core.executor.ExecutorRegistry.defs.exists(_.id == id) then
              BadRequest(
                Json.obj(
                  "error" -> s"unknown executor id '$id' — valid: ${nebflow.core.executor.ExecutorRegistry.defs.map(_.id).mkString(", ")}".asJson
                )
              )
            else
              ConfigService.setExecutorSection(Map("default" -> id.asJson)).flatMap {
                case Left(err) => BadRequest(Json.obj("error" -> err.asJson))
                case Right(_) =>
                  wsHub.broadcast(Json.obj("type" -> "configUpdated".asJson, "success" -> true.asJson)) *>
                    Ok(Json.obj("updated" -> true.asJson, "default" -> id.asJson))
              }
          }
        }

      // Single-provider explicit reveal. Audit the event without logging caller-controlled text.
      case req @ GET -> Root / "config" / "provider-keys" / id =>
        withAuth(req) {
          ConfigService.revealProviderApiKey(id).flatMap {
            case Some(value) =>
              nebflow.shared.NebflowLogger.forName("nebflow.gateway.config")
                .info("Provider credential revealed").flatMap { _ =>
                  Ok(Json.obj("value" -> value.asJson)).map(_.putHeaders(
                    Header.Raw(org.typelevel.ci.CIString("Cache-Control"), "no-store")
                  ))
                }
            case None => NotFound(Json.obj("error" -> "Provider credential not found".asJson))
          }.handleErrorWith(_ => BadRequest(Json.obj("error" -> "Provider credential unavailable".asJson)))
        }

      // GET /api/config/provider-keys —— 设置页 apikey 明文回读（作者令
      // 2026-10-27 10:33「设置里配置的 apikey 直接显示」：JeV 配置节 + LLM API
      // 配置节明文直显）。owner 鉴权与设置写同门槛（withAuth = GatewayAuth
      // Rails.ApiFull，与下方 PUT /config/jev 同一面），不新增无鉴权端点；
      // 载荷只含 llm.providers.<id>.apiKey 真值（ConfigService.revealProviderApiKeys
      // 单点投影）——通用读面 GET /api/config 的 keyaudit-quickfix ③ 打码维持
      // 不动（掩码 round-trip 语义照旧）。🔴 日志零 key 值：本臂不打任何日志，
      // 值的唯一出现位置是响应体。
      case req @ GET -> Root / "config" / "provider-keys" =>
        withAuth(req) {
          ConfigService.revealProviderApiKeys.flatMap(Ok(_)).handleErrorWith {
            case e: IllegalStateException if e.getMessage == "Credential reference is missing" =>
              BadRequest(Json.obj("error" -> "Credential reference is missing".asJson))
            case e: IllegalStateException if e.getMessage == "Credential reference is unreadable" =>
              BadRequest(Json.obj("error" -> "Credential reference is unreadable".asJson))
            case e => IO.raiseError(e)
          }
        }

      // GET /api/config/candidates —— 候选集与配置一致性自查面
      // （provider-config-vs-runtime-mismatch 批，2026-11-05 作者令
      // 「作者要能自查是不是漏了/多了」）。
      //
      // 与**请求路径同一个**取链函数（ProviderRegistry.getCandidatesForAgentUnfiltered）
      // 的有序投影，逐项标注 origin=chain|reserve；同时给出：
      //   · chainRefsDropped —— 链里写了但配置里没有（运行期被跳过）；
      //   · configProviders / disabledProviders —— 运行期实际持有的配置面；
      //   · drift —— 盘上 nebflow.json 与内存 configRef 的差集
      //     （inSync=false + onlyOnDisk 名单 = 「删了还在被调」/「加了还没生效」）。
      // 只读、零副作用；owner 鉴权与设置写同门槛（withAuth）。载荷零密钥。
      case req @ GET -> Root / "config" / "candidates" =>
        withAuth(req) {
          sharedResources.providerRegistry
            .candidateReport(nebflow.core.SchemePolicy.NebulaName, None)
            .flatMap(json => Ok(json))
        }

      // GET /api/config/provider-types —— 服务商面板的类型域 / 协议适配器枚举
      // 投影（provider-add-tabs 批 2026-11-06，作者令「添加服务商统一成标签
      // 切换类型 + 单一服务商面板」）。类型枚举的**唯一出生地**在后端
      // （ProviderDomains / LlmProtocol / ImageGenAdapters / DecisionProvider），
      // 前端只消费不拼写——这是本批退役前端两份硬编码适配器表后的取数面。
      // 载荷纯枚举（零配置值、零密钥），owner 鉴权与其它 config GET 同门槛。
      case req @ GET -> Root / "config" / "provider-types" =>
        withAuth(req) {
          ConfigService.providerTypes.flatMap(Ok(_))
        }

      // PUT /api/config/embedding —— Embedding 配置定向写（task-rag 批 #1227 /
      // §Batch 7 检索面设置区写通道；形态照 jev 路由：键白名单在
      // ConfigService.embeddingWritableKeys 单点，越界/空体 400；keyRef 是
      // secrets 名指针、值型凭据键不在册）。
      case req @ PUT -> Root / "config" / "embedding" =>
        withAuth(req) {
          req.as[Json].flatMap { body =>
            body.asObject match
              case None => BadRequest(Json.obj("error" -> "expected a JSON object body".asJson))
              case Some(o) =>
                val values = o.toMap.collect {
                  case (k, v) if ConfigService.embeddingWritableKeys.contains(k) => k -> v
                }
                if values.isEmpty then
                  BadRequest(
                    Json.obj(
                      "error" -> s"no writable embedding keys given — writable: ${ConfigService.embeddingWritableKeys.mkString(", ")}".asJson
                    )
                  )
                else
                  ConfigService.setEmbeddingSection(values).flatMap {
                    case Left(err) => BadRequest(Json.obj("error" -> err.asJson))
                    case Right(_) =>
                      wsHub.broadcast(Json.obj("type" -> "configUpdated".asJson, "success" -> true.asJson)) *>
                        Ok(Json.obj("updated" -> true.asJson))
                  }
          }
        }

      // ── 生图（imagegen）设置面（settings-panel-jev-imagegen 批 2026-10-28）────
      // GET /api/config/imagegen —— 面板两形态的状态投影（ConfigService.
      // imagegenStatus 单点：compiledIn seal 常量 + 配置热读 + keyRef 在位性，
      // 永不回传 key 值）。owner 鉴权与 jev/embedding 读写同门槛（withAuth）。
      // 🔴 边界：这是**设置/状态面**——seal=OFF 的 release 构建照常在（面板的
      // 「未编入」提示位正需要它）；零痕迹锚（ImageGenRoutes / 工具注册段 /
      // 前端 imagegen.js DCE）不经过这里（ConfigService 处已申报）。
      case req @ GET -> Root / "config" / "imagegen" =>
        withAuth(req) {
          ConfigService.imagegenStatus.flatMap(Ok(_))
        }

      // PUT /api/config/imagegen —— 生图节定向写（**整节替换**：面板每次提交
      // 全量目标形态；校验单点 ConfigService.validateImagegenSection——白名单
      // 越界 / 不可解码 / 适配器缺席的 type 一律 400 不落盘）。key 值永不走本
      // 路由（secrets 面专属）。
      case req @ PUT -> Root / "config" / "imagegen" =>
        withAuth(req) {
          req.as[Json].flatMap { body =>
            ConfigService.setImagegenSection(body).flatMap {
              case Left(err) => BadRequest(Json.obj("error" -> err.asJson))
              case Right(_) =>
                wsHub.broadcast(Json.obj("type" -> "configUpdated".asJson, "success" -> true.asJson)) *>
                  Ok(Json.obj("updated" -> true.asJson))
            }
          }
        }

      // GET /api/decision/health —— 分配决策通道探活（jev-plugins 批 #1226；设置页
      // Jev 区探活显示的读通道）。读 sharedResources.decisionHealth 单实例（写腿 =
      // 插件分配器两腿）。凭证无关：payload 只含 status/reason/since + 可选 legs
      // 分腿块（jev-panel-threefix 三改③，2026-10-09——有分腿记账才出现；reason
      // 为分类诊断词，永不携带任务文本或密钥）。
      case req @ GET -> Root / "decision" / "health" =>
        withAuth(req) {
          sharedResources.decisionHealth.toJson.flatMap(h => Ok(Json.obj("decision" -> h)))
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
