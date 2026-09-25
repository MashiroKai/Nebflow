/* 从 WebSocketRoutes 迁出(行为保持重构,2026-09-24)。 */
package nebflow.gateway

import cats.effect.std.{Dispatcher, Queue}
import cats.effect.{Deferred, IO, Ref}
import cats.syntax.all.*
import fs2.{Pipe, Stream}
import io.circe.parser.parse
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.actor.ActorSystem as RootActorSystem
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

/** 系统域(system):ping/转写/热重启/更新/自启/后台任务/智能体提示词等。 */
private[gateway] object WsSystemHandlers:

  private[gateway] val handlers: Map[String, WsDispatch.WsHandler] = Map(
    "restart" -> handleRestart,
    "transcribe" -> handleTranscribe,
    "ping" -> handlePing,
    "getActiveBgTasks" -> handleGetActiveBgTasks,
    "cancelBackgroundJob" -> handleCancelBackgroundJob,
    "cancelFlow" -> handleCancelFlow,
    "getAgentSystemPrompt" -> handleGetAgentSystemPrompt,
    "updateAgentSystemPrompt" -> handleUpdateAgentSystemPrompt,
    "updateAgentTools" -> handleUpdateAgentTools,
    "checkUpdate" -> handleCheckUpdate,
    "doUpdate" -> handleDoUpdate,
    "remoteUpdate" -> handleRemoteUpdate,
    "autostartStatus" -> handleAutostartStatus,
    "autostartSet" -> handleAutostartSet
  )

  private def handleRestart(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    // ── 热重启触发面（P1，hot-restart 批设计 §3.2）：Web UI 按钮的 WS 命令。
    // confirm=true 必带（前端确认对话框「将等待当前工作完成后重启」的引擎侧
    // 强制位）；waitIdle 默认 true（§7 拍板项 2 建议：UI 默认排队可取消语义
    // 的后端形态——等待期工作照常准入）。进度经 restartStatus 帧 wsHub 广播
    // （quiesce/draining/spawning/handing-over/completed/failed）。编排器与
    // 触发源解耦——桌面菜单/REST API（P2）后续接同一 HotRestart.requestRestart。
    val rc = parsedJson(text).hcursor
    val confirmed = rc.downField("confirm").as[Boolean].getOrElse(false)
    val waitIdle = rc.downField("waitIdle").as[Boolean].getOrElse(true)
    val waitTimeoutMs = rc.downField("waitTimeoutMs").as[Long].getOrElse(600000L)
    def restartReply(ok: Boolean, extra: (String, Json)*): IO[Unit] =
      wsSend(
        io.circe.Json.obj(
          Seq(("type", "restartResult".asJson), ("ok", ok.asJson)) ++ extra*
        )
      )
    if !confirmed then
      restartReply(
        ok = false,
        "error" -> "restart requires confirm=true — the gateway restarts in place once current work finishes".asJson
      )
    else
      sharedResources.hotRestart match
        case None =>
          restartReply(ok = false, "error" -> "hot restart is not available in this instance".asJson)
        case Some(hr) =>
          val mode =
            if waitIdle then nebflow.core.hotrestart.RestartMode.WaitIdle(waitTimeoutMs)
            else nebflow.core.hotrestart.RestartMode.RejectIfBusy
          hr.requestRestart(source = "web-ui", mode = mode).flatMap {
            case Right(()) =>
              restartReply(
                ok = true,
                "message" -> "hot restart underway — the UI will reconnect automatically when the new instance is up".asJson
              )
            case Left(err) => restartReply(ok = false, "error" -> err.asJson)
          }
    end if
  end handleRestart

  private def handleTranscribe(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    val json = parsedJson(text)
    val hc = json.hcursor
    val audioB64 = hc.downField("audio").as[String].getOrElse("")
    val language = hc.downField("language").as[String].toOption.filter(_.nonEmpty)
    if audioB64.nonEmpty then
      sttServiceRef.get.flatMap { sttService =>
        sttService match
          case None =>
            wsSend(
              io.circe.Json.obj(
                "type" -> "transcription".asJson,
                "error" -> "STT not configured — set it up in Settings".asJson
              )
            )
          case Some(svc) =>
            IO.blocking {
              java.util.Base64.getDecoder.decode(audioB64)
            }.flatMap { wavBytes =>
              svc.transcribe(wavBytes, language).flatMap {
                case Right(text) =>
                  wsSend(
                    io.circe.Json.obj(
                      "type" -> "transcription".asJson,
                      "text" -> text.asJson
                    )
                  )
                case Left(err) =>
                  wsSend(
                    io.circe.Json.obj(
                      "type" -> "transcription".asJson,
                      "error" -> err.asJson
                    )
                  )
              }
            }.handleErrorWith { e =>
              logger.warn(s"Transcribe failed: ${e.getMessage}")
              wsSend(
                io.circe.Json.obj(
                  "type" -> "transcription".asJson,
                  "error" -> e.getMessage.asJson
                )
              )
            }
      }
    else IO.unit
    end if
  end handleTranscribe

  private def handlePing(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    wsSend(io.circe.Json.obj("type" -> "pong".asJson))

  private def handleGetActiveBgTasks(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    nebflow.core.tools.BgTaskRegistry.activeTasksJson.flatMap { tasksJson =>
      wsSend(
        io.circe.Json.obj(
          "type" -> "activeBgTasks".asJson,
          "tasks" -> tasksJson
        )
      )
    }
  end handleGetActiveBgTasks

  private def handleCancelBackgroundJob(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    val json = parsedJson(text)
    val cancelSessionId = json.hcursor.downField("sessionId").as[String].getOrElse("")
    val jobId = json.hcursor.downField("jobId").as[String].getOrElse("")
    if cancelSessionId.nonEmpty && jobId.nonEmpty then
      nebflow.core.tools.ShellSession
        .forSession(cancelSessionId)
        .flatMap { shell =>
          shell.cancelBackgroundJob(jobId).flatMap { cancelled =>
            val logMsg =
              if cancelled then s"Cancelled background job $jobId"
              else s"Background job $jobId not found or already completed"
            logger.info(logMsg, "sessionId" -> cancelSessionId, "jobId" -> jobId) *>
              // 输出查看批（2026-09-09）：显式取消也是终态——输出留存区
              // 翻转为 cancelled，详情卡回看「取消时刻为止」的全部输出。
              // 幂等：若完成回调已先 finalize（竞态），此处 no-op。
              (if cancelled then
                 nebflow.core.tools.BgTaskOutputStore
                   .finalizeTask(jobId, "cancelled", None, Some("Cancelled by user"))
                   .handleErrorWith(e =>
                     logger.warn(s"bg-output finalize (cancel) failed for job $jobId: ${e.getMessage}")
                   )
               else IO.unit) *>
              // Notify agent so it can process cancellation
              ensureAgent(cancelSessionId) { ref =>
                ref ! AgentCommand.ExternalEvent(
                  source = "background-task",
                  eventType = "cancelled",
                  payload = s"[Background task cancelled] Job ID: $jobId",
                  metadata = io.circe.JsonObject(
                    "jobId" -> jobId.asJson
                  ),
                  correlationId = Some(jobId)
                )
              } *>
              // Send completion update to frontend so the task is removed from the dropdown
              wsSend(
                io.circe.Json.obj(
                  "type" -> "backgroundTaskUpdate".asJson,
                  "sessionId" -> cancelSessionId.asJson,
                  // 权威分键（2026-09-05）：前端按 rootSessionId 分桶，回显
                  // 取消请求携带的会话（即前端桶键），保证移除帧落同一桶。
                  "rootSessionId" -> cancelSessionId.asJson,
                  "taskId" -> jobId.asJson,
                  "description" -> "".asJson,
                  "status" -> "completed".asJson
                )
              )
          }
        }
        .handleErrorWith { e =>
          logger.warn(s"cancelBackgroundJob failed for session=$cancelSessionId job=$jobId: ${e.getMessage}")
          // Send a completion update even on error, so the frontend removes the task
          wsSend(
            io.circe.Json.obj(
              "type" -> "backgroundTaskUpdate".asJson,
              "sessionId" -> cancelSessionId.asJson,
              "rootSessionId" -> cancelSessionId.asJson,
              "taskId" -> jobId.asJson,
              "description" -> "".asJson,
              "status" -> "failed".asJson
            )
          ).handleErrorWith(_ => IO.unit)
        }
    else IO.unit
    end if
  end handleCancelBackgroundJob

  private def handleCancelFlow(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    val json = parsedJson(text)
    val flowName = json.hcursor.downField("name").as[String].getOrElse("")
    val cfSessionId = json.hcursor.downField("sessionId").as[String].getOrElse("")
    val instanceId = json.hcursor.downField("instanceId").as[String].getOrElse("")
    if instanceId.nonEmpty then
      // Cancel a DAG flow instance directly
      nebflow.core.flow.RunningFlowRegistry.cancel(instanceId) *>
        logger.info(s"Cancel DAG flow '$instanceId' requested by user via WS")
    else if flowName.nonEmpty && cfSessionId.nonEmpty then
      // Cancel a team pipeline flow via FlowTreeActor
      nebflow.core.flow.FlowTreeRegistry.get(cfSessionId).flatMap {
        case Some(treeRef) =>
          treeRef ! nebflow.core.flow.TreeCommand.CancelPipeline(flowName)
          logger.info(s"Cancel flow '$flowName' requested by user via WS")
        case None =>
          logger.warn(s"Cannot cancel flow '$flowName': no FlowTreeActor for session")
      }
    else IO.unit
  end handleCancelFlow

  private def handleGetAgentSystemPrompt(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    val agentName = parse(text).flatMap(_.hcursor.downField("name").as[String]).getOrElse("")
    if agentName.nonEmpty then
      agentService.getSystemPrompt(agentName).flatMap { mdOpt =>
        wsSend(
          io.circe.Json.obj(
            "type" -> "agentSystemPrompt".asJson,
            "name" -> agentName.asJson,
            "systemMd" -> mdOpt.getOrElse("").asJson
          )
        )
      }
    else IO.unit
  end handleGetAgentSystemPrompt

  private def handleUpdateAgentSystemPrompt(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    val json = parsedJson(text)
    val agentName = json.hcursor.downField("name").as[String].getOrElse("")
    val systemMd = json.hcursor.downField("systemMd").as[String].getOrElse("")
    if agentName.nonEmpty then
      agentService.updateSystemPrompt(agentName, systemMd) *>
        wsSend(io.circe.Json.obj("type" -> "agentSystemPromptSaved".asJson, "name" -> agentName.asJson))
    else IO.unit

  private def handleUpdateAgentTools(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    // Retired 2026-09-06 (tool-face batch): agent.json tools write-back
    // is closed. Loud rejection (no silent no-op) so any stale client
    // sees the retirement instead of assuming the edit landed.
    // 未走信封助手:字段级 Option 链(parse 后即取 name,回退空串),非裸 parse-or-Null 同形,保持原样(2026-09-24)
    val agentName = parse(text).toOption
      .flatMap(_.hcursor.downField("name").as[String].toOption)
      .getOrElse("")
    logger.warn(
      s"Rejected updateAgentTools for '$agentName' — write-back retired 2026-09-06 (stage 2d tool-face batch)"
    )
    wsSend(
      io.circe.Json.obj(
        "type" -> "error".asJson,
        "message" -> s"updateAgentTools retired: per-agent tools are mechanism/plugin-managed since 2026-09-06; agent.json is no longer written from the panel".asJson
      )
    )
  end handleUpdateAgentTools

  private def handleCheckUpdate(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    val currentVer = nebflow.Version.string
    // #29: 仓库 private 后 GH API 未认证不可用——版本检查读发布镜像
    // latest-version.txt（stable 通道，与旧 releases/latest 同语义）。
    // COS→OSS 切仓（2026-09-14）：桶名走 Branding.cosBucket 派生
    // （brand.conf 为唯一事实源，禁再硬编码桶名）；端点 = 阿里云 OSS 杭州。
    // hotupdate 批 1：读取与比对本体已抽到 VersionCheck（单一实现）——更新
    // 编排器的「检查」相位与本分支同源，禁第二套比对。帧形状逐字段不变。
    val result = nebflow.core.hotupdate.VersionCheck
      .fetchRaw()
      .flatMap {
        case Some(tag) =>
          val latestVer = tag.stripPrefix("v")
          val hasUpdate = nebflow.core.hotupdate.VersionCheck.hasUpdate(currentVer, latestVer)
          wsSend(
            io.circe.Json.obj(
              "type" -> "updateCheckResult".asJson,
              "currentVersion" -> currentVer.asJson,
              "latestVersion" -> latestVer.asJson,
              "hasUpdate" -> hasUpdate.asJson,
              "releaseName" -> tag.asJson
            )
          )
        case None =>
          wsSend(
            io.circe.Json.obj(
              "type" -> "updateCheckResult".asJson,
              "currentVersion" -> currentVer.asJson,
              "error" -> "Failed to check for updates".asJson
            )
          )
      }
    result
  end handleCheckUpdate

  private def handleDoUpdate(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    // ── 一键更新（设置页）：接统一更新编排器（hotupdate 批 1 · D3 · 闭 G1）。
    // 本批前：本段**只装不重启**（内联 curl|sh，整段零重启调用）⇒ 点完仍是旧版在跑。
    // 本批后：发**更新请求**给编排器 ⇒ 检查 → 准备 → 冻结 → 更新（安装动作本体
    // #26）→ 重启（**委托既有热重启编排器**）→ 恢复（后继进程自己走既有开机链）。
    // 向后兼容：既有 updateStarted / updateCompleted 两帧保留不删（安装相位回执经
    // onInstallOutcome 发出，形状与字段语义不变）；相位进度走统一 updateProgress 帧。
    // 确认位：沿用既有 restart 命令的强制位语义——缺 confirm 直接拒绝 + 可行动错误。
    val rc = parsedJson(text).hcursor
    val beta = rc.downField("beta").as[Boolean].getOrElse(false)
    val confirmed = rc.downField("confirm").as[Boolean].getOrElse(false)
    val idemKey = rc.downField("idempotencyKey").as[String].toOption
    sharedResources.updateOrchestrator match
      case None =>
        wsSend(
          io.circe.Json.obj(
            "type" -> "updateCompleted".asJson,
            "success" -> false.asJson,
            "error" -> "update orchestrator is not available in this instance".asJson
          )
        )
      case Some(orchestrator) =>
        val updateReq = nebflow.core.hotupdate.UpdateRequest(
          source = nebflow.core.hotupdate.UpdateSource.Settings,
          confirm = confirmed,
          channel =
            if beta then nebflow.core.hotupdate.UpdateChannel.Beta
            else nebflow.core.hotupdate.UpdateChannel.Stable,
          // 裁定 7：更新场景等待上限独立值 300s（界面重启命令那一处的 600s
          // 默认本批零改动——两值互不影响，见 UpdateDefaults 注释）。
          mode = nebflow.core.hotrestart.RestartMode.WaitIdle(nebflow.core.hotupdate.UpdateDefaults.awaitIdleMs),
          idempotencyKey = idemKey
        )
        // 安装相位回执 → 既有完成帧（向后兼容；失败分支的文案来自安装动作本体 #26）
        val onInstallOutcome: Either[String, String] => IO[Unit] =
          case Right(_) =>
            wsSend(io.circe.Json.obj("type" -> "updateCompleted".asJson, "success" -> true.asJson))
          case Left(err) =>
            wsSend(
              io.circe.Json.obj(
                "type" -> "updateCompleted".asJson,
                "success" -> false.asJson,
                "error" -> err.asJson
              )
            )
        orchestrator.request(updateReq, onInstallOutcome).flatMap { admission =>
          nebflow.core.hotupdate.UpdateOrchestrator.admissionFrame(admission) match
            case None =>
              // 受理 → 既有开始帧（向后兼容）
              wsSend(io.circe.Json.obj("type" -> "updateStarted".asJson))
            case Some(frame) =>
              // 已在途 / 更新中 / 拒绝 —— 立即回报（更新是排他动作、不排队）
              wsSend(frame)
        }
    end match
  end handleDoUpdate

  private def handleRemoteUpdate(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    // hotupdate 批 3 · G8：请求可带**可选**幂等键 `clientRequestId`（界面设备行
    // 的触发点生成，见 resources/web/js/contacts.js）。语义（逐条）：
    //   · 缺席 / 空串 ⇒ 本分支的载荷与回帧与改前**逐字节相同**（老端路径不变）；
    //   · 带键 ⇒ 只在既有帧与既有 P2P 载荷上**加**一个字段（不新造消息类型、
    //     不改既有字段语义、不删既有字段——设计 §9:173 逐字），并在每条
    //     `remoteUpdateResult` 里**回显**该键，使界面能把结果对回它那一次点击。
    //   · 中继腿的隧道参数面保持 `{beta}`：动作 `RemoteUpdate` 的参数集由跨仓
    //     契约钉死（契约 §B.1.3），本批零越仓。
    // 未走信封助手:此处产出 HCursor(失败回退 Json.Null.hcursor),非 Json 回退同形,保持原样(2026-09-24)
    val hc = parse(text).toOption.map(_.hcursor).getOrElse(io.circe.Json.Null.hcursor)
    val targetDevice = hc.downField("device").as[String].getOrElse("")
    val beta = hc.downField("beta").as[Boolean].getOrElse(false)
    val clientRequestId = hc
      .downField("clientRequestId")
      .as[String]
      .toOption
      .map(_.trim)
      .filter(_.nonEmpty)

    /**
     * `remoteUpdateResult` 的唯一构造点（本分支内单点）：既有字段原样 +
     * 带键时追加 `clientRequestId`（缺席时不追加 ⇒ 老端回帧形状不变）。
     */
    def resultFrame(fields: (String, io.circe.Json)*): io.circe.Json =
      val all: Seq[(String, io.circe.Json)] =
        Seq("type" -> io.circe.Json.fromString("remoteUpdateResult")) ++ fields ++
          clientRequestId.map(id => "clientRequestId" -> io.circe.Json.fromString(id))
      io.circe.Json.obj(all*)

    def tryRelayUpdate(
      ns: nebflow.neblink.NeblinkService,
      peer: nebflow.neblink.PeerInfo,
      beta: Boolean,
      p2pError: String
    ): IO[Unit] =
      ns.relayClientOpt match
        case Some(client) =>
          logger.info(s"P2P update failed ($p2pError), trying relay to ${peer.deviceName}") *>
            client.relayUpdate(peer.deviceId, beta).flatMap {
              case Right(msg) =>
                wsSend(
                  resultFrame(
                    "success" -> true.asJson,
                    "device" -> peer.deviceName.asJson,
                    "message" -> msg.asJson
                  )
                )
              case Left(err) =>
                wsSend(
                  resultFrame(
                    "success" -> false.asJson,
                    "error" -> s"P2P: $p2pError; Relay: $err".asJson
                  )
                )
            }
        case None =>
          wsSend(
            resultFrame(
              "success" -> false.asJson,
              "error" -> p2pError.asJson
            )
          )

    if targetDevice.isEmpty then
      wsSend(
        resultFrame(
          "success" -> false.asJson,
          "error" -> "Missing device name".asJson
        )
      )
    else
      sharedResources.neblinkService match
        case None =>
          wsSend(
            resultFrame(
              "success" -> false.asJson,
              "error" -> "NebLink not enabled".asJson
            )
          )
        case Some(neblinkService) =>
          neblinkService.peers.flatMap { peers =>
            peers.find(p =>
              p.deviceName.equalsIgnoreCase(targetDevice) ||
                p.deviceName.toLowerCase.contains(targetDevice.toLowerCase)
            ) match
              case None =>
                wsSend(
                  resultFrame(
                    "success" -> false.asJson,
                    "error" -> s"Device '$targetDevice' not found".asJson
                  )
                )
              case Some(peer) =>
                if peer.address.isEmpty then
                  wsSend(
                    resultFrame(
                      "success" -> false.asJson,
                      "error" -> s"Device '$targetDevice' has no address".asJson
                    )
                  )
                else
                  logger.info(
                    s"Remote update: sending update request to ${peer.deviceName} at ${peer.address} (beta=$beta)"
                  ) *>
                    IO.blocking {
                      import sttp.client4.*
                      val fields = List("beta" -> beta.asJson)
                        ++ clientRequestId.map(id => "clientRequestId" -> id.asJson)
                      val body = io.circe.Json.obj(fields*).noSpaces
                      val resp = basicRequest
                        .post(sttp.model.Uri.unsafeParse(s"${peer.address}/api/neblink/update"))
                        .contentType("application/json")
                        .body(body)
                        .readTimeout(180.seconds)
                        .response(asStringAlways)
                        .send(neblinkService.httpBackend)
                      resp
                    }.flatMap { resp =>
                      if resp.code.isSuccess then
                        wsSend(
                          resultFrame(
                            "success" -> true.asJson,
                            "device" -> peer.deviceName.asJson,
                            "message" -> "Update installed, device is restarting...".asJson
                          )
                        )
                      else
                        // P2P returned an HTTP error — try relay before failing
                        tryRelayUpdate(neblinkService, peer, beta, s"Remote returned HTTP ${resp.code}")
                    }.handleErrorWith { e =>
                      tryRelayUpdate(
                        neblinkService,
                        peer,
                        beta,
                        s"Cannot reach ${peer.deviceName}: ${e.getMessage}"
                      )
                    }
            end match
          }
    end if
  end handleRemoteUpdate

  private def handleAutostartStatus(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    // Settings panel "start on login" toggle (F2) — shared logic with
    // the `nebflow autostart` CLI via AutoStartService.
    nebflow.core.AutoStartService.status().flatMap { st =>
      wsSend(
        io.circe.Json.obj(
          "type" -> "autostartStatusResult".asJson,
          "enabled" -> st.enabled.asJson,
          "supported" -> st.supported.asJson,
          "reason" -> st.reason.asJson
        )
      )
    }
  end handleAutostartStatus

  private def handleAutostartSet(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    val asJson = parsedJson(text)
    val enable = asJson.hcursor.downField("enabled").as[Boolean].getOrElse(false)
    val op = if enable then nebflow.core.AutoStartService.enable() else nebflow.core.AutoStartService.disable()
    op.flatMap { res =>
      // Always answer with the authoritative post-op status; attach
      // the op message on failure so the UI can toast + revert the toggle.
      nebflow.core.AutoStartService.status().flatMap { st =>
        wsSend(
          io.circe.Json.obj(
            "type" -> "autostartStatusResult".asJson,
            "enabled" -> st.enabled.asJson,
            "supported" -> st.supported.asJson,
            "reason" -> st.reason.asJson,
            "error" -> (if res.ok then None else Some(res.message)).asJson
          )
        )
      }
    }
  end handleAutostartSet

end WsSystemHandlers
