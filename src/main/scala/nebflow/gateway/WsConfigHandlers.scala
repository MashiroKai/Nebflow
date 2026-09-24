/* 从 WebSocketRoutes 迁出(行为保持重构,2026-09-24)。 */
package nebflow.gateway

import cats.effect.std.{Dispatcher, Queue}
import cats.effect.{Deferred, IO, Ref}
import cats.syntax.all.*
import fs2.{Pipe, Stream}
import io.circe.parser.parse
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.actor.ActorSystem as NebulaActorSystem
import nebflow.agent.*
import nebflow.core.entity.EntityLoader
import nebflow.core.flow.{FlowTreeActor, FlowTreeRegistry, TeamSessionRegistry}
import nebflow.core.mcp.McpManager
import nebflow.core.project.{CancelSource as ChainCancelSource, *}
import nebflow.core.schedule.FreezeSchedule.given
import nebflow.core.skill.SkillService
import nebflow.core.tools.{ToolContext, ToolRegistry}
import nebflow.core.{PathUtil, *}
import nebflow.gateway.NfFilePolicy.*
import nebflow.gateway.WsDispatch.{inboundEnvelope, parsedJson}
import nebflow.llm.*
import nebflow.service.*
import nebflow.shared.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*
import org.http4s.headers.`Content-Type`
import org.http4s.server.websocket.WebSocketBuilder2
import org.http4s.websocket.WebSocketFrame

import scala.concurrent.duration.*
import scala.io.Source

/** 配置域(config setters):思考/冻结/STT/TTL/模型档/安全档/MCP 等配置读写臂。 */
private[gateway] object WsConfigHandlers:

  private[gateway] val handlers: Map[String, WsDispatch.WsHandler] = Map(
    "skipFreeze" -> handleSkipFreeze,
    "setThinking" -> handleSetThinking,
    "setWorkSchedule" -> handleSetWorkSchedule,
    "setSttConfig" -> handleSetSttConfig,
    "getToolResultTtl" -> handleGetToolResultTtl,
    "setToolResultTtl" -> handleSetToolResultTtl,
    "setVoiceMuted" -> handleSetVoiceMuted,
    "setLlmLog" -> handleSetLlmLog,
    "getLlmLog" -> handleGetLlmLog,
    "getModelOptions" -> handleGetModelOptions,
    "setSessionModel" -> handleSetSessionModel,
    "getCompactThreshold" -> handleGetCompactThreshold,
    "setCompactThreshold" -> handleSetCompactThreshold,
    "setSafetyMode" -> handleSetSafetyMode,
    "setBypass" -> handleSetBypass,
    "getConfig" -> handleGetConfig,
    "setOnboardingState" -> handleSetOnboardingState,
    "probeLlm" -> handleProbeLlm,
    "updateConfig" -> handleUpdateConfig,
    "toggleMcpServer" -> handleToggleMcpServer
  )

  private def handleSkipFreeze(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    // 冻结「跳过本次」按钮（2026-08-25 前端联调）：无文本跳过命令——
    // 不注入假消息气泡污染上下文，直调 skipCurrentFreezeWindow（置
    // freezeSkipUntilRef = 当前窗口结束时刻 + FreezeScheduler.scan 全局
    // 解冻，语义与用户消息触发完全一致；窗口结束后 skip 自然过期，下一
    // 冻结段照常冻结）。无 payload、无回执（fire-and-forget）。
    skipCurrentFreezeWindow

  private def handleSetThinking(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    val json = parsedJson(text)
    val hc = json.hcursor
    val thinkingOpt = hc.downField("thinking").as[Option[io.circe.Json]].toOption.flatten
    // null/absent means toggled off; {enabled: false} also means off; otherwise default true
    val enabled = thinkingOpt match
      case None | Some(io.circe.Json.Null) => false
      case Some(v) => v.hcursor.downField("enabled").as[Boolean].getOrElse(true)
    val budgetTokens = thinkingOpt match
      case None | Some(io.circe.Json.Null) => 32000
      case Some(v) => v.hcursor.downField("budgetTokens").as[Int].getOrElse(32000)
    val tc = ThinkingConfig(enabled, budgetTokens)
    logger.info(s"Thinking mode set to: enabled=$enabled budgetTokens=$budgetTokens") *>
      // 冻结修复（2026-08-27）：同族排序——persist 成功才热更，失败回执。
      nebflow.service.ConfigService.writeLocked(persistThinkingConfig(tc)).attempt.flatMap {
        case Left(e) =>
          logger.warn(s"Failed to persist thinking config: ${e.getMessage}") *>
            wsSend(
              io.circe.Json
                .obj("type" -> "configUpdateFailed".asJson, "message" -> s"思考模式保存失败: ${e.getMessage}".asJson)
            )
        case Right(_) =>
          sharedResources.thinkingConfigRef.set(tc) *> broadcastServerConfig
      }
  end handleSetThinking

  private def handleSetWorkSchedule(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    // 冻结调度（freeze-schedule spec ⑦ + 2026-08-25 裁定「设置关闭保留
    // 配置」）：payload {type, workSchedule:{enabled?, segments?}}——
    // 缺键从现有配置继承（toggle off 只置 disabled、segments 不清空不
    // 重置；显式 segments:[] 仍可清空）。校验失败拒绝保存（配置不变）
    // 并回 configUpdateFailed；成功 → ref 热更 + targeted write + 广播 +
    // 立即让所有 Frozen agent 重评估（不用等 30s 轮询——改配置关功能即恢复）。
    val json = parsedJson(text)
    val payload = json.hcursor
      .downField("workSchedule")
      .as[Option[io.circe.Json]]
      .toOption
      .flatten
      .getOrElse(json)
    for
      current <- sharedResources.freezeScheduleRef.get
      merged <- IO.pure(nebflow.core.schedule.FreezeSchedule.mergeValidate(current, payload))
      _ <- merged match
        case Right(cfg) =>
          // 冻结修复（2026-08-27）排序：写盘成功才热更 ref——persist 失败时
          // 内存与盘保持一致并回执 failed（此前 persist 静默吞错 + ref 先行，
          // 会造成「UI 已关、重启后又冻」的假保存）。写盘段持 config 写锁。
          logger.info(s"Freeze schedule set: enabled=${cfg.enabled} segments=${cfg.segments.size}") *>
            nebflow.service.ConfigService.writeLocked(persistWorkSchedule(cfg)).attempt.flatMap {
              case Left(e) =>
                logger.warn(s"Failed to persist work schedule: ${e.getMessage}") *>
                  wsSend(
                    io.circe.Json.obj(
                      "type" -> "configUpdateFailed".asJson,
                      "message" -> s"冻结配置保存失败: ${e.getMessage}".asJson
                    )
                  )
              case Right(_) =>
                sharedResources.freezeScheduleRef.set(cfg) *>
                  broadcastServerConfig *>
                  // 立即让所有 Frozen agent 重评估（不用等 30s 轮询——改配置
                  // 关功能即恢复）。
                  nebflow.core.processor.FreezeScheduler.scan(sharedResources)
            }
        case Left(err) =>
          logger.warn(s"Invalid workSchedule payload rejected: $err") *>
            wsSend(io.circe.Json.obj("type" -> "configUpdateFailed".asJson, "message" -> err.asJson))
    yield ()
    end for
  end handleSetWorkSchedule

  private def handleSetSttConfig(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    // #295 STT 可配置（用户 2026-08-18 拍板）→ A2 部分更新语义
    // （2026-08-20）：payload {type, sttConfig: {endpoint?, apiKey?,
    // model?}}。字段省略=保留旧值（前端空 key 输入框省略字段——旧
    // 整文件替换语义会把已存 apiKey 覆盖丢失，此为 #295 A2 根因）；
    // 字段显式空串=清除该字段；合并后无任何字段=删配置文件（回退
    // 免费浏览器 Web Speech）。endpoint 设值须 http(s)://，model
    // 非空，apiKey 原样存（key 不落地前端——serverConfig 广播只含
    // sttConfigured/endpoint/model，永不回传 apiKey）。写盘走
    // AtomicJson 原子写；写后 SttService.create() 重建热更
    // （transcribe 立即走新配置）。
    // 未走信封助手:字段级 Option 链(parse 后即取 sttConfig,回退 Json.obj()),非裸 parse-or-Null 同形,保持原样(2026-09-24)
    val payload = parse(text).toOption
      .flatMap(_.hcursor.downField("sttConfig").as[Option[Json]].toOption.flatten)
      .getOrElse(Json.obj())
    SttService.parsePatch(payload) match
      case Left(err) =>
        logger.warn(s"Invalid sttConfig payload rejected: $err") *>
          wsSend(io.circe.Json.obj("type" -> "configUpdateFailed".asJson, "message" -> err.asJson))
      case Right(patch) =>
        val oldCfgOpt = IO.blocking {
          if os.exists(SttService.configPath) then parse(os.read(SttService.configPath)).toOption
          else None
        }
        oldCfgOpt.flatMap { oldCfg =>
          SttService.mergeConfig(oldCfg, patch) match
            case Some(cfgJson) =>
              AtomicJson.write(SttService.configPath, cfgJson.noSpaces) *>
                SttService.create().flatMap { svc =>
                  sttServiceRef.set(svc) *> logger.info(
                    s"STT config set: endpoint=${svc.map(_.endpoint).getOrElse("(defaults)")}"
                  )
                } *>
                broadcastServerConfig *>
                wsSend(io.circe.Json.obj("type" -> "configUpdated".asJson, "success" -> true.asJson))
            case None =>
              (if os.exists(SttService.configPath) then IO.blocking(os.remove(SttService.configPath))
               else IO.unit) *>
                sttServiceRef.set(None) *>
                logger.info("STT config cleared (fallback to browser Web Speech)") *>
                broadcastServerConfig *>
                wsSend(io.circe.Json.obj("type" -> "configUpdated".asJson, "success" -> true.asJson))
        }
    end match
  end handleSetSttConfig

  private def handleGetToolResultTtl(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    // #341 WS 尾巴：TTL 设置面板读当前生效配置（Ref 是权威——含未
    // 重启的热更值）。无敏感字段，全量回显。
    sharedResources.toolResultTtlRef.get.flatMap { cfg =>
      wsSend(
        io.circe.Json.obj(
          "type" -> "toolResultTtl".asJson,
          "config" -> cfg.asJson
        )
      )
    }
  end handleGetToolResultTtl

  private def handleSetToolResultTtl(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    // #341 WS 尾巴：payload {type, config:{enabled,ttlMinutes,
    // keepRecent}}（全量替换，三字段必填；minChars 已移除，旧负载
    // 携带该字段静默忽略）。STRICT 校验（负数/非整数/超界/缺字段 →
    // 数/非整数/超界/缺字段 → 拒绝并 warn，回 configUpdateFailed——与
    // boot 时 fail-safe load 不同：交互面必须把错误亮给用户）。
    // 成功 → nebflow.json toolResultTtl 节 read-merge + AtomicJson
    // 原子写 + Ref 热更（下个 LLM 请求生效，镜像 freezeScheduleRef）
    // → 回 toolResultTtlSaved。
    // 未走信封助手:字段级 Option 链(parse 后即取 config,回退 Json.Null),非裸 parse-or-Null 同形,保持原样(2026-09-24)
    val payload = parse(text).toOption
      .flatMap(_.hcursor.downField("config").as[Option[Json]].toOption.flatten)
      .getOrElse(Json.Null)
    nebflow.core.compact.ToolResultTtlConfig.parseStrict(payload) match
      case Left(err) =>
        logger.warn(s"Invalid toolResultTtl payload rejected: $err") *>
          wsSend(io.circe.Json.obj("type" -> "configUpdateFailed".asJson, "message" -> err.asJson))
      case Right(cfg) =>
        val persist = IO.blocking {
          val existing =
            if os.exists(nebflow.llm.Config.DefaultConfigPath) then os.read(nebflow.llm.Config.DefaultConfigPath)
            else "{}"
          val path = PathUtil.configJsonWritePath(PathUtil.dataRoot)
          parse(existing).foreach { json =>
            val updated = json.mapObject(_.add("toolResultTtl", cfg.asJson))
            // writeSync (not write): we are already inside IO.blocking —
            // the IO-returning variant would be built, not run.
            AtomicJson.writeSync(path, updated.noSpaces)
          }
        }
        // 冻结修复（2026-08-27）：同族排序——persist 成功才热更 ref，
        // 失败回执 failed（写盘段持 config 写锁）。
        nebflow.service.ConfigService.writeLocked(persist).attempt.flatMap {
          case Left(e) =>
            logger.warn(s"Failed to persist toolResultTtl: ${e.getMessage}") *>
              wsSend(
                io.circe.Json.obj(
                  "type" -> "configUpdateFailed".asJson,
                  "message" -> s"TTL 配置保存失败: ${e.getMessage}".asJson
                )
              )
          case Right(_) =>
            sharedResources.toolResultTtlRef.set(cfg) *>
              logger.info(
                s"Tool result TTL set: enabled=${cfg.enabled} ttlMinutes=${cfg.ttlMinutes} " +
                  s"keepRecent=${cfg.keepRecent}"
              ) *>
              wsSend(
                io.circe.Json.obj(
                  "type" -> "toolResultTtlSaved".asJson,
                  "config" -> cfg.asJson
                )
              )
        }
    end match
  end handleSetToolResultTtl

  private def handleSetVoiceMuted(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    // 未走信封助手:字段级 Option 链(parse 后即取 muted,回退 false),非裸 parse-or-Null 同形,保持原样(2026-09-24)
    val muted = parse(text).toOption
      .flatMap(_.hcursor.downField("muted").as[Boolean].toOption)
      .getOrElse(false)
    sharedResources.voiceMutedRef.set(muted)

  private def handleSetLlmLog(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    // 默认关批（2026-09-13）：缺字段不再视为「开」——与新默认态一致
    // （fail-safe：无明确指令不改状态；UI 恒带 enabled，见 sidebar.js）。
    // 未走信封助手:字段级 Option 链(parse 后即取 enabled,回退 false),非裸 parse-or-Null 同形,保持原样(2026-09-24)
    val enabled = parse(text).toOption
      .flatMap(_.hcursor.downField("enabled").as[Boolean].toOption)
      .getOrElse(false)
    // 持久化优先（D-A：显式改动过的值须跨重启保持）：落盘成功才热更
    // 内存态，镜像 setToolResultTtl 的「persist 成功才热更」排序——
    // 不产生「内存已改、盘上未改」的窗口。落盘失败 = fail-loud：
    // WARN + configUpdateFailed，并回**权威现值**让 UI 与实际一致。
    nebflow.service.ConfigService.setLlmLogEnabled(enabled).attempt.flatMap {
      case Right(_) =>
        LlmLogWriter.setEnabled(enabled)
        logger.info(s"LLM log set to: $enabled") *>
          wsSend(io.circe.Json.obj("type" -> "llmLogState".asJson, "enabled" -> enabled.asJson))
      case Left(e) =>
        logger.warn(s"Failed to persist llmLog.enabled=$enabled: ${e.getMessage}") *>
          wsSend(
            io.circe.Json.obj(
              "type" -> "configUpdateFailed".asJson,
              "message" -> s"LLM 日志开关保存失败: ${e.getMessage}".asJson
            )
          ) *>
          wsSend(io.circe.Json.obj("type" -> "llmLogState".asJson, "enabled" -> LlmLogWriter.isEnabled.asJson))
    }
  end handleSetLlmLog

  private def handleGetLlmLog(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    wsSend(io.circe.Json.obj("type" -> "llmLogState".asJson, "enabled" -> LlmLogWriter.isEnabled.asJson))

  private def handleGetModelOptions(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    val sessionId = inboundEnvelope(text).sessionId
    sharedResources.providerRegistry.getAllModelsDetailed().flatMap { models =>
      sharedResources.sessionModelOverrides.get.flatMap { overrides =>
        val currentOpt = overrides.get(sessionId).map(c => s"${c.providerId}/${c.model}")
        wsSend(
          io.circe.Json.obj(
            "type" -> "modelOptions".asJson,
            "sessionId" -> sessionId.asJson,
            "models" -> models.map { case (ref, label, desc) =>
              io.circe.Json.obj(
                "ref" -> ref.asJson,
                "label" -> label.asJson,
                "description" -> desc.asJson
              )
            }.asJson,
            "current" -> currentOpt.asJson
          )
        )
      }
    }
  end handleGetModelOptions

  private def handleSetSessionModel(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    val json = parsedJson(text)
    val sessionId = json.hcursor.downField("sessionId").as[String].getOrElse("")
    val modelRef = json.hcursor.downField("modelRef").as[Option[String]].getOrElse(None)
    if sessionId.nonEmpty then
      (modelRef match
        case Some(ref) =>
          sharedResources.providerRegistry.getCandidateForRef(ref).flatMap {
            case Some(candidate) =>
              sharedResources.sessionModelOverrides.update(_ + (sessionId -> candidate)) *>
                sessionStore
                  .updateSessionModel(sessionId, Some(ref))
                  .as(Right(s"${candidate.providerId}/${candidate.model}"))
            case None =>
              IO.pure(Left(s"Unknown model: $ref"))
          }
        case None =>
          sharedResources.sessionModelOverrides.update(_ - sessionId) *>
            sessionStore.updateSessionModel(sessionId, None).as(Right("default"))
      ).flatMap {
        case Right(ref) =>
          // Notify agent actor of context window change for compaction threshold
          val notifyAgent = sharedResources.sessionModelOverrides.get.flatMap { overrides =>
            overrides.get(sessionId) match
              case Some(candidate) =>
                ensureAgent(sessionId)(ref => ref ! AgentCommand.UpdateContextWindow(candidate.contextWindow))
              case None => IO.unit
          }
          notifyAgent *> wsSend(io.circe.Json.obj("type" -> "sessionModelSet".asJson, "modelRef" -> ref.asJson))
        case Left(err) =>
          wsSend(io.circe.Json.obj("type" -> "error".asJson, "message" -> err.asJson))
      }
    else IO.unit
    end if
  end handleSetSessionModel

  private def handleGetCompactThreshold(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    // ctxthresh 批（2026-09-15 方案 A）：面板打开时读**权威值**——Ref 含未
    // 重启的热更值，盘上 meta 含跨重启保留值（先例 getToolResultTtl）。
    // 未走信封助手:回退写法为非限定 Json.Null,与 io.circe.Json.Null 家族形态不一致,按边界保持原样(2026-09-24)
    val json = parse(text).toOption.getOrElse(Json.Null)
    val sid = json.hcursor.downField("sessionId").as[String].getOrElse("")
    if sid.nonEmpty then compactThresholdInfo(sid).flatMap(wsSend) else IO.unit

  private def handleSetCompactThreshold(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    // ctxthresh 批：payload {sessionId, ratio: number|null}（ratio=null ⇒
    // 恢复默认 = 清覆盖）。STRICT 校验（镜像 setToolResultTtl 的交互面纪律）：
    // 值域逐字 = 作者卡答「上限90%，下限…大于15%」⇒ `15% < r ≤ 90%`
    // （CompactThresholdOverride.isValid）。越界 ⇒ **拒绝并亮错**，不做静默
    // 钳制——「钳回」是 UI 侧滑杆的动态下限职责（js/ctxthresh.js）。
    // 成功路径 = persist-then-hot 三步（盘上 SessionMeta → 内存 Ref → 通知
    // 活体 root agent）+ 回权威回显帧。
    // 🔴 作用域闸（首行）= fail-closed + Nebula 身份门（2026-09-15 返工，见
    // `isRootScopeSession`）——非 Nebula 会话 / 幽灵 id / 未注册且非活跃者
    // 一律走 reject 分支，**绝不落盘**。
    // 未走信封助手:回退写法为非限定 Json.Null,与 io.circe.Json.Null 家族形态不一致,按边界保持原样(2026-09-24)
    val json = parse(text).toOption.getOrElse(Json.Null)
    val sid = json.hcursor.downField("sessionId").as[String].getOrElse("")
    val rawRatio = json.hcursor.downField("ratio").as[Option[Double]].toOption.flatten
    if sid.isEmpty then IO.unit
    else
      val reject = (msg: String) =>
        logger.warn(s"Rejected setCompactThreshold for $sid: $msg") *>
          wsSend(
            Json.obj(
              "type" -> Json.fromString("compactThresholdError"),
              "sessionId" -> Json.fromString(sid),
              "message" -> Json.fromString(msg)
            )
          )
      isRootScopeSession(sid).flatMap { isRoot =>
        if !isRoot then reject("scope: only the root session (Nebula window) may override")
        else if rawRatio.exists(r => !CompactThresholdOverride.isValid(r)) then
          reject(
            s"ratio must satisfy 15% < r <= 90% (got ${rawRatio.getOrElse(Double.NaN)})"
          )
        else
          val persist = sessionStore.updateSessionCompactThreshold(sid, rawRatio) *>
            sharedResources.sessionCompactThreshold.update { m =>
              rawRatio.fold(m - sid)(r => m + (sid -> r))
            } *>
            ensureAgent(sid)(ref => ref ! AgentCommand.SetCompactThresholdRatio(rawRatio))
          persist.handleErrorWith { e =>
            logger.warn(s"setCompactThreshold failed for $sid: ${e.getMessage}") *>
              wsSend(
                Json.obj(
                  "type" -> Json.fromString("compactThresholdError"),
                  "sessionId" -> Json.fromString(sid),
                  "message" -> Json.fromString(e.getMessage)
                )
              )
          } *> compactThresholdInfo(sid).flatMap(wsSend)
      }
    end if
  end handleSetCompactThreshold

  private def handleSetSafetyMode(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    // ── 通道处置（permshield S1，2026-09-13 作者重裁「候选 B」）────────────
    // **改造为写全局持久**（不退役），理由：
    //   ① 盾牌是作者指定的唯一 UI 调整入口（「就使用 header 的盾牌来调整」），
    //      它发的就是这个帧；退役该帧 ⇒ 盾牌在 F1 落地前完全失效（功能回归）。
    //   ② 作者口径「落全局持久（跟盾牌走同一条路，重启后仍生效）」要的正是
    //      改这条通道的**写入目标**，不是删掉它。
    // 处置后语义：写 `nebflow.json` 的 `safety.defaultMode`（与 REST
    // `PUT /api/safety/mode` 同一个持久函数 ⇒ 同一路），广播 `configUpdated`
    // （所有客户端同步），再推本连接会话列表。
    // 🔴 旧行为「本会话临时覆盖、不落盘」**已消失**：档位不再有会话维度。
    // `sessionId` 仅用于回推列表（档位本身是应用级的，对所有会话一致）。
    val json = parsedJson(text)
    val sid = json.hcursor.downField("sessionId").as[String].getOrElse("")
    val rawMode = json.hcursor.downField("safetyMode").as[String].toOption
    // 三档显式白名单：未知值**拒绝**（旧行为是静默 `getOrElse("confirm-edits")`
    // ——把拼错/缺失的值悄悄变成最严档，用户无感且不可诊断）。拒绝 ⇒ 不落盘。
    val modeOpt = rawMode.flatMap(nebflow.core.SafetyMode.fromWire)
    if sid.nonEmpty then
      modeOpt match
        case None =>
          wsSend(
            io.circe.Json.obj(
              "type" -> "error".asJson,
              "message" -> s"unknown safetyMode '${rawMode.getOrElse("")}' — valid: confirm-edits, auto-edits, auto-all".asJson
            )
          )
        case Some(mode) =>
          persistGlobalSafetyMode(nebflow.core.SafetyMode.toString(mode))
            .flatMap(_ => sendAgentSessionList(wsSend, sid))
            .handleErrorWith { e =>
              // 落盘失败 = 档位**没有**改变（fail-loud，不静默回滚）
              logger.error(s"setSafetyMode persist failed: ${e.getMessage}", e) *>
                wsSend(
                  io.circe.Json.obj(
                    "type" -> "error".asJson,
                    "message" -> s"failed to persist safety mode '${nebflow.core.SafetyMode.toString(mode)}': ${e.getMessage}".asJson
                  )
                )
            }
    else IO.unit
    end if
  end handleSetSafetyMode

  private def handleSetBypass(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    // Backward compat: old clients send { bypass: Boolean }（等价于
    // setSafetyMode 的两档特例）——permshield S1 起同样**写全局持久**；
    // 该帧已无任何前端调用点（`grep -rn setBypass src/main/resources/web` = 0），
    // 保留仅因 wire 向后兼容（老客户端 / 外部脚本）。
    val json = parsedJson(text)
    val sid = json.hcursor.downField("sessionId").as[String].getOrElse("")
    val bypass = json.hcursor.downField("bypass").as[Boolean].getOrElse(false)
    val mode = if bypass then "auto-all" else "confirm-edits"
    if sid.nonEmpty then
      persistGlobalSafetyMode(mode)
        .flatMap(_ => sendAgentSessionList(wsSend, sid))
        .handleErrorWith { e =>
          logger.error(s"setBypass persist failed: ${e.getMessage}", e) *>
            wsSend(
              io.circe.Json.obj(
                "type" -> "error".asJson,
                "message" -> s"failed to persist safety mode '$mode': ${e.getMessage}".asJson
              )
            )
        }
    else IO.unit
  end handleSetBypass

  private def handleGetConfig(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    configService.isConfigured.flatMap { configured =>
      configService.getConfig.flatMap { cfg =>
        nebflow.core.OnboardingService.readState().flatMap { onboarding =>
          wsSend(
            io.circe.Json.obj(
              "type" -> "configData".asJson,
              "config" -> cfg.asJson,
              "configured" -> configured.asJson,
              // null = no marker yet (fresh install); frontend shows the wizard
              "onboarding" -> onboarding.map(_.name).asJson
            )
          )
        }
      }
    }
  end handleGetConfig

  private def handleSetOnboardingState(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    // F3 onboarding state machine: pending | done | skipped.
    // HARD GATE (server-side, user ruling 2026-08-15): done is
    // rejected unless a successful probeLlm is on record — the WS
    // surface can no longer bypass the gate the frontend enforces.
    val stJson = parsedJson(text)
    val stStr = stJson.hcursor.downField("state").as[String].getOrElse("")
    nebflow.core.OnboardingService.OnboardingState.fromString(stStr) match
      case Some(st) =>
        nebflow.core.OnboardingService.setState(st).flatMap {
          case Right(applied) =>
            wsSend(io.circe.Json.obj("type" -> "onboardingStateSet".asJson, "state" -> applied.name.asJson))
          case Left(reason) =>
            wsSend(
              io.circe.Json.obj(
                "type" -> "error".asJson,
                "code" -> "probe_required".asJson,
                "message" -> reason.asJson
              )
            )
        }
      case None =>
        wsSend(
          io.circe.Json.obj("type" -> "error".asJson, "message" -> s"invalid onboarding state: $stStr".asJson)
        )
    end match
  end handleSetOnboardingState

  private def handleProbeLlm(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    // Onboarding HARD GATE (user ruling 2026-08-15): one real LLM call
    // through the global chain. The welcome message may only be sent
    // after this returns ok=true.
    nebflow.core.OnboardingService.probeLlm(sharedResources.llm).flatMap { pr =>
      wsSend(
        io.circe.Json.obj(
          "type" -> "probeResult".asJson,
          "ok" -> pr.ok.asJson,
          "provider" -> pr.provider.asJson,
          "error" -> pr.error.asJson
        )
      )
    }
  end handleProbeLlm

  private def handleUpdateConfig(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    val cfg = parse(text).flatMap(_.hcursor.downField("config").as[String]).getOrElse("")
    if cfg.nonEmpty then
      // 冻结修复（2026-08-27）：runtime 热更键以内存 ref 为权威——调用方的
      // config 快照可能陈旧（页面加载时刻底稿），不覆写则任意 provider 类
      // 保存会把 workSchedule/thinkingConfig/toolResultTtl 回滚到快照值。
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
        configService.updateConfig(cfg, runtimeOverrides).flatMap {
          case Left(err) =>
            wsSend(io.circe.Json.obj("type" -> "error".asJson, "message" -> err.asJson))
          case Right(_) =>
            // #311: the first provider/model save seeds/repairs the default
            // preset (from the just-saved llm.model chain) so agent model
            // resolution never silently falls to a hidden global chain.
            IO
              .delay(nebflow.core.presets.PresetStore().ensureDefaultPreset())
              .attempt
              .flatMap {
                case Right(_) => IO.unit
                case Left(e) => logger.warn(s"Default preset seeding failed: ${e.getMessage}")
              } *>
              // Hot-reload: update in-memory config and clear adapter cache
              sharedResources.providerRegistry
                .reloadConfig(Some(sharedResources.sessionModelOverrides))
                .attempt
                .flatMap {
                  case Right(staleIds) =>
                    // #33: keep the persisted session meta in sync — dropped
                    // overrides also clear their modelRef on disk so the UI
                    // state matches the in-memory session overrides.
                    staleIds.traverse_(id => sessionStore.updateSessionModel(id, None)) *>
                      logger.info("Config hot-reloaded successfully")
                  case Left(e) =>
                    logger.warn(s"Config hot-reload failed: ${e.getMessage}")
                } *> wsSend(io.circe.Json.obj("type" -> "configUpdated".asJson, "success" -> true.asJson))
        }
      }
    else IO.unit
    end if
  end handleUpdateConfig

  private def handleToggleMcpServer(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    // 未走信封助手:此处产出 HCursor(失败回退 Json.Null.hcursor),非 Json 回退同形,保持原样(2026-09-24)
    val hc = parse(text).toOption.map(_.hcursor).getOrElse(io.circe.Json.Null.hcursor)
    val serverId = hc.downField("serverId").as[String].getOrElse("")
    val enabled = hc.downField("enabled").as[Boolean].getOrElse(false)
    if serverId.nonEmpty then
      configRef.get.flatMap { cfg =>
        cfg.mcpServers.getOrElse(Map.empty).get(serverId) match
          case Some(mcpCfg) =>
            val action =
              if enabled then mcpManager.enableServer(serverId, mcpCfg) else mcpManager.disableServer(serverId)
            // 冻结修复（2026-08-27）：persist 持锁 + 失败回执（不再静默）。
            action *>
              nebflow.service.ConfigService
                .writeLocked(persistMcpServerEnabled(serverId, enabled))
                .attempt
                .flatMap {
                  case Left(e) =>
                    logger.warn(s"Failed to persist MCP server state: ${e.getMessage}") *>
                      wsSend(
                        io.circe.Json.obj(
                          "type" -> "configUpdateFailed".asJson,
                          "message" -> s"MCP 状态保存失败: ${e.getMessage}".asJson
                        )
                      ) *>
                      broadcastMcpServersUpdate.handleErrorWith(_ => IO.unit)
                  case Right(_) => broadcastMcpServersUpdate.handleErrorWith(_ => IO.unit)
                }
          case None =>
            logger.warn(s"toggleMcpServer: server '$serverId' not found in config") *> IO.unit
      }
    else IO.unit
    end if
  end handleToggleMcpServer

end WsConfigHandlers
