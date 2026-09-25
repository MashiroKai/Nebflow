/* 从 RestApiRoutes 迁出(行为保持重构,2026-09-24)。 */
package nebflow.gateway

import cats.effect.IO
import cats.effect.std.Queue
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import fs2.{Pipe, Stream}
import io.circe.syntax.*
import io.circe.{Json, JsonObject, parser}
import nebflow.agent.{AgentCore, SharedResources}
import nebflow.core.*
import nebflow.core.daemon.{DaemonConfig, DaemonService, DaemonStore}
import nebflow.core.entity.{EntityLoader, NodeRoute}
import nebflow.core.flow.{FlowTreeRegistry, TreeCommand}
import nebflow.core.hotrestart.HealthPayload
import nebflow.core.presets.{ModelPreset, PresetFile, PresetStore}
import nebflow.core.project.*
import nebflow.core.schedule.FreezeSchedule.given
import nebflow.core.skill.SkillService
import nebflow.core.task.{FileTaskStore, TaskStore}
import nebflow.core.tools.NodeTools
import nebflow.llm.*
import nebflow.llm.providers.ModelListFaces
import nebflow.neblink.*
import nebflow.neblink.FriendCodecs.given
import nebflow.service.ConfigService
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*
import org.http4s.headers.{Authorization, Location, `Content-Type`}
import org.http4s.server.websocket.WebSocketBuilder2
import org.http4s.websocket.WebSocketFrame
import org.typelevel.ci.{CIString, CIStringSyntax}

import scala.concurrent.duration.*

/**
 * 在线状态域(presence):原 RestApiRoutes.presenceWsRoutes 的全部 case 逐字迁入
 * (presence WS 受理臂 + teams/agents/plugins/social/team-rules/presets/provider/daemons
 * 等 REST 臂),行为保持;经 RestApiRoutes.presenceWsRoutes 委托挂载,GatewayMain 零改动。
 */
private[gateway] object PresenceRoutes:

  def routes(wsb: WebSocketBuilder2[IO], ctx: RestApiCtx): HttpRoutes[IO] =
    import ctx.*
    HttpRoutes.of[IO] {
      case req @ GET -> Root / "neblink" / "presence" =>
        neblinkService match
          case None => NotFound(Json.obj("error" -> "NebLink not enabled".asJson))
          case Some(ms) =>
            val remoteIp = req.remoteAddr.fold("")(a => a.toString)
            val peerDeviceId = presencePeerDeviceId(req)
            // Trust: IP list (primary) or device-ID network membership (fallback).
            // A1 (2026-09-20): the claiming id arrives in the handshake header
            // (see presencePeerDeviceId) — the legacy ?deviceId= query param is
            // still accepted for peers built before the change.
            (if ms.isTrustedPeer(remoteIp) then IO.pure(true)
             else isKnownNetworkDevice(ms, peerDeviceId, remoteIp)).flatMap {
              case false => Forbidden(Json.obj("error" -> "Not a trusted peer".asJson))
              case true =>
                if peerDeviceId.isEmpty then BadRequest(Json.obj("error" -> "Missing deviceId".asJson))
                else
                  // R-1b conn-guard：信任闸与 deviceId 校验之后、受理之前——
                  // per-IP WS 并发检查（拒 ⇒ 可见 429，不静默）。环回恒放行；
                  // NebLink 受信对端（如 100.x 组网）照常受 per-IP 上限约束
                  // （昨日灌表对端正是受信对端——信任闸不构成资源面防线）。
                  val wsIpNorm = ConnGuard.normalizeIp(req.remoteAddr)
                  connGuard.checkWs(wsIpNorm).flatMap {
                    case Some(reason) =>
                      logger.warn(s"conn-guard: presence WS upgrade rejected ip=$wsIpNorm reason=$reason") *>
                        TooManyRequests(
                          Json.obj("error" -> s"Connection guard: WebSocket limit reached ($reason)".asJson)
                        )
                    case None => // 大段受理体原缩进零改排（花括号区域经典解析）
                      val peerDeviceName = req.params.getOrElse("deviceName", "Unknown")
                      val peerPlatform = req.params.getOrElse("platform", "")
                      val peerPort = req.params.getOrElse("port", "8080").toIntOption.getOrElse(8080)
                      val capsStr = req.params.getOrElse("capabilities", "{}")
                      val capabilities = parser.decode[Map[String, String]](capsStr).getOrElse(Map.empty)
                      val info = nebflow.neblink.DeviceDiscoveryInfo(
                        peerDeviceId,
                        peerDeviceName,
                        peerPlatform,
                        capabilities
                      )
                      // Silent upsert — the HTTP /neblink/announce endpoint already handles logging.
                      // Calling handleAnnounce here too produces duplicate "Peer announced" logs.
                      val peer = nebflow.neblink.PeerInfo(
                        peerDeviceId,
                        peerDeviceName,
                        peerPlatform,
                        s"http://$remoteIp:$peerPort",
                        capabilities = capabilities
                      )
                      ms.upsertPeer(peer).flatMap { _ =>
                        Queue.unbounded[IO, WebSocketFrame].flatMap { sendQueue =>
                          val heartbeat = Stream
                            .awakeEvery[IO](10.seconds)
                            .map(_ => WebSocketFrame.Text("""{"type":"ping"}"""))
                          val queued = Stream.fromQueueUnterminated(sendQueue)
                          val send = queued.merge(heartbeat)
                          val receive: Pipe[IO, WebSocketFrame, Unit] =
                            _.evalMap {
                              case WebSocketFrame.Text(text, _) =>
                                parser.parse(text).toOption match
                                  case Some(json) =>
                                    json.hcursor.downField("type").as[String].getOrElse("") match
                                      case "ping" =>
                                        sendQueue.offer(WebSocketFrame.Text("""{"type":"pong"}"""))
                                      case "data" =>
                                        val payload = json.hcursor.downField("payload").focus.getOrElse(Json.Null)
                                        ms.handleDataMessage(payload)
                                      case _ => IO.unit
                                  case None => IO.unit
                              case _ => IO.unit
                            }.onFinalize(
                              ms.removePeer(peerDeviceId).handleErrorWith(_ => IO.unit)
                            )
                          // R-1b：入账在 build 前（此后仅剩 build 本身，失败即自然不
                          // build ⇒ 无幽灵计数）；回减挂流 finalizer（与 removePeer 同缝，
                          // 连接关闭必走）。
                          connGuard.acquireWs(wsIpNorm).flatMap { guardHandle =>
                            wsb.build(send, receive.andThen(_.onFinalize(connGuard.releaseWs(guardHandle))))
                          }
                        }
                      }
                    // end conn-guard case None
                  } // end conn-guard checkWs flatMap
            }

      // (2026-09-20 device-face hardening batch) POST /api/flow/event was RETIRED
      // here — the classify pass recorded it as 仓内零调用点 (no FE/CLI/BE caller)
      // and the 11-route takedown was authorised by the author.

      // GET /teams/mounted — return all mounted teams with agent status
      // NOTE: Router mounts this under /api prefix, so full path is /api/teams/mounted
      case req @ GET -> Root / "teams" / "mounted" =>
        withAuth(req) {
          for
            _ <- nebflow.core.flow.FlowTreeRegistry.awaitRestore(3000L)
            teamsJson <- buildMountedTeamsJson()
            result <- Ok(Json.obj("teams" -> teamsJson))
          yield result
        }

      // (2026-09-20 device-face hardening batch) 11-route takedown, authorised by
      // the author: GET /running-flows, GET /flow/dag/:name, GET /teams and
      // GET /teams/status/:sessionId were RETIRED here — each was 仓内零调用点 in
      // the classify pass (no FE/CLI/BE caller). The live siblings
      // (/teams/mounted, /teams/mailbox/*, /teams/def/*, /teams/mail-queue/*,
      // /team/rules/*) are untouched.

      // GET /teams/mailbox/:sessionId/:teamName — mail history for a team
      case req @ GET -> Root / "teams" / "mailbox" / sessionId / flowName =>
        withAuth(req) {
          if sessionId.isEmpty || !sessionId.matches("^[a-zA-Z0-9._-]{1,64}$") then
            BadRequest(Json.obj("error" -> "Invalid sessionId".asJson))
          else
            for
              records <- nebflow.core.flow.FlowMailStore.load(sessionId, flowName)
              result <- Ok(Json.obj("records" -> records.asJson))
            yield result
        }

      // DELETE /teams/mailbox/:sessionId/:teamName — clear mail history
      case req @ DELETE -> Root / "teams" / "mailbox" / sessionId / flowName =>
        withAuth(req) {
          if sessionId.isEmpty || !sessionId.matches("^[a-zA-Z0-9._-]{1,64}$") then
            BadRequest(Json.obj("error" -> "Invalid sessionId".asJson))
          else
            for
              _ <- nebflow.core.flow.FlowMailStore.clear(sessionId, flowName)
              result <- Ok(Json.obj("cleared" -> true.asJson))
            yield result
        }

      // GET /teams/mail-queue/:sessionId — pending queue mails
      case req @ GET -> Root / "teams" / "mail-queue" / sessionId =>
        withAuth(req) {
          if sessionId.isEmpty || !sessionId.matches("^[a-zA-Z0-9._-]{1,64}$") then
            BadRequest(Json.obj("error" -> "Invalid sessionId".asJson))
          else
            for
              items <- nebflow.core.flow.MailQueueStore.load(sessionId)
              result <- Ok(Json.obj("items" -> items.asJson))
            yield result
        }

      // DELETE /teams/mail-queue/:sessionId/:itemId — cancel a pending queue mail
      case req @ DELETE -> Root / "teams" / "mail-queue" / sessionId / itemId =>
        withAuth(req) {
          if sessionId.isEmpty || !sessionId.matches("^[a-zA-Z0-9._-]{1,64}$") then
            BadRequest(Json.obj("error" -> "Invalid sessionId".asJson))
          else
            for
              remaining <- nebflow.core.flow.MailQueueStore.removeById(sessionId, itemId)
              result <- Ok(Json.obj("items" -> remaining.asJson))
            yield result
        }

      // ===== Flow Editor APIs =====

      // GET /teams/def/:name — return team definition for the editor
      case req @ GET -> Root / "teams" / "def" / flowName =>
        withAuth(req) {
          if !isValidFlowName(flowName) then BadRequest(Json.obj("error" -> "Invalid name".asJson))
          else
            for
              teamOpt <- EntityLoader.loadTeam(flowName)
              agents <- EntityLoader.listAgents()
              result <- teamOpt match
                case None => NotFound(Json.obj("error" -> s"Team '$flowName' not found".asJson))
                case Some(team) =>
                  val leadEntry = agents.get(team.lead)
                  val memberEntries = team.members.flatMap(agents.get)
                  val allEntries = (leadEntry.toList ++ memberEntries)
                  val agentsJson = allEntries.map { entry =>
                    Json.obj(
                      "name" -> entry.name.asJson,
                      "description" -> entry.description.asJson,
                      "tools" -> entry.tools.asJson,
                      "systemPrompt" -> entry.systemPrompt.asJson
                    )
                  }
                  Ok(
                    Json.obj(
                      "name" -> team.name.asJson,
                      "manager" -> team.lead.asJson,
                      "description" -> team.description.asJson,
                      "agents" -> agentsJson.asJson,
                      "type" -> "team".asJson
                    )
                  )
            yield result
        }

      // GET /bg-tasks/:jobId/output?offset=N — background task output view
      // (2026-09-09 author request: the bg-tasks panel rows open a detail card).
      // REST GET polling over WS push: stateless byte-offset cursor (line
      // granularity — returns every line whose start offset >= offset), auth via
      // the same withAuth middleware as /api/agents/:name/model, and no 256KB
      // payloads broadcast through WsHub to every connected client. Response:
      // status / totalBytes / totalLines / truncated (tail window dropped the
      // head) / output / nextOffset (+ finishedAtMs / exitCode / errorHint when
      // terminal). 404 = unknown jobId (remote tasks and evicted retention).
      case req @ GET -> Root / "bg-tasks" / jobId / "output" =>
        withAuth(req) {
          val offset = req.uri.params.get("offset").flatMap(_.toLongOption).getOrElse(0L)
          nebflow.core.tools.BgTaskOutputStore.read(jobId, offset).flatMap {
            case None =>
              NotFound(Json.obj("error" -> s"Background task '$jobId' not found or output unavailable".asJson))
            case Some(o) =>
              Ok(
                Json.obj(
                  "taskId" -> o.taskId.asJson,
                  "status" -> o.status.asJson,
                  "totalBytes" -> o.totalBytes.asJson,
                  "totalLines" -> o.totalLines.asJson,
                  "truncated" -> o.truncated.asJson,
                  "output" -> o.output.asJson,
                  "nextOffset" -> o.nextOffset.asJson,
                  "finishedAtMs" -> o.finishedAtMs.asJson,
                  "exitCode" -> o.exitCode.asJson,
                  "errorHint" -> o.errorHint.asJson
                )
              )
          }
        }

      // (2026-09-20 device-face hardening batch) 11-route takedown: GET /agents/list
      // was RETIRED here — 仓内零调用点 in the classify pass (its only in-repo
      // reference was the smoke-spec probe, updated in the same commit).
      // GET /agents/:name and /agents/:name/model below are the live faces.

      // GET /agents/:name — get agent detail (system.md + tools) — searches all three layers
      case req @ GET -> Root / "agents" / agentName =>
        withAuth(req) {
          if !isValidAgentName(agentName) then BadRequest(Json.obj("error" -> "Invalid agent name".asJson))
          else
            for
              agentOpt <- EntityLoader.findAgentByName(agentName)
              result <- agentOpt match
                case None => NotFound(Json.obj("error" -> s"Agent '$agentName' not found".asJson))
                case Some(defn) =>
                  Ok(
                    Json.obj(
                      "name" -> defn.name.asJson,
                      "description" -> defn.description.asJson,
                      "tools" -> defn.tools.asJson,
                      "category" -> defn.category.asJson,
                      "fixedTools" -> AgentCore.fixedToolsFor(defn).toList.asJson,
                      "systemPrompt" -> defn.systemPrompt.asJson,
                      "displayName" -> defn.displayName.getOrElse(defn.name).asJson,
                      "model" -> defn.model.asJson,
                      "skills" -> defn.skills.asJson,
                      "flows" -> defn.flows.asJson
                    )
                  )
            yield result
        }

      // GET /agents/:name/model — get agent's model configuration — searches all three layers
      case req @ GET -> Root / "agents" / agentName / "model" =>
        withAuth(req) {
          if !isValidAgentName(agentName) then BadRequest(Json.obj("error" -> "Invalid agent name".asJson))
          else
            for
              agentOpt <- EntityLoader.findAgentByName(agentName)
              result <- agentOpt match
                case None => NotFound(Json.obj("error" -> s"Agent '$agentName' not found".asJson))
                case Some(defn) =>
                  val modelConfig = defn.model.getOrElse(nebflow.shared.AgentModelConfig.empty)
                  // Determine resolvedFrom: check the raw AgentEntry for preset/legacy.
                  // AgentDef.preset tells us the explicit preset (if any). If absent,
                  // check whether the original agent.json had a non-empty legacy model.
                  val resolvedFrom = computeResolvedFrom(defn.preset, agentName)
                  // Resolve the model this agent would actually use: candidates =
                  // [preferred, ...fallbacks] (or the global chain), filtered by
                  // provider health. Previously this read runtimeModels.values.headOption —
                  // an arbitrary session from a GLOBAL session→model map — which
                  // showed the wrong model for every agent except the first LLM caller.
                  for
                    candidates <- sharedResources.providerRegistry.getCandidatesForAgent(Some(modelConfig))
                    (healthy, _) <- sharedResources.healthMonitor.filterCandidates(candidates)
                    current = healthy.headOption.map(c => s"${c.providerId}/${c.model}")
                    result <- Ok(
                      Json.obj(
                        "model" -> modelConfig.asJson,
                        "current" -> current.asJson,
                        "preferred" -> modelConfig.preferred.asJson,
                        "fallbacks" -> modelConfig.fallbacks.asJson,
                        "default" -> modelConfig.preferred.asJson,
                        "preset" -> defn.preset.asJson,
                        "resolvedFrom" -> resolvedFrom.asJson
                      )
                    )
                  yield result
                  end for
            yield result
        }

      // PUT /agents/:name/model — update agent's model configuration
      // panelscheme 批（2026-09-21）：与 PUT /preset 同闸——仅 Nebula/任务分发器可写
      // （legacy model 引用是两类的自有方案面；其余 agent 的存储引用引擎已忽略）。
      case req @ PUT -> Root / "agents" / agentName / "model" =>
        withAuth(req) {
          if !isValidAgentName(agentName) then BadRequest(Json.obj("error" -> "Invalid agent name".asJson))
          else if !nebflow.core.presets.SchemePolicy.SettableAgents.contains(agentName) then
            BadRequest(
              Json.obj(
                "error" ->
                  (s"Agent '$agentName' does not accept a model config (2026-09-21 panel convergence): only Nebula and " +
                    "project-dispatcher are settable. Existing stored values are kept but ignored by the engine.").asJson
              )
            )
          else
            req.as[Json].flatMap { body =>
              // Parse the model config from request body
              io.circe.parser.decode[nebflow.shared.AgentModelConfig](body.noSpaces) match
                case Right(modelConfig) =>
                  for
                    dirOpt <- EntityLoader.findAgentDir(agentName)
                    result <- dirOpt match
                      case Some(dir) =>
                        IO.blocking {
                          val jsonPath = dir / "agent.json"
                          val json = os.read(jsonPath)
                          io.circe.parser.parse(json) match
                            case Right(parsed) =>
                              val updated = parsed.deepMerge(Json.obj("model" -> modelConfig.asJson))
                              AtomicJson.writeSync(jsonPath, updated.noSpaces)
                              true
                            case Left(_) => false
                        }.flatMap {
                          case true =>
                            Ok(Json.obj("updated" -> true.asJson, "model" -> modelConfig.asJson))
                          case false =>
                            InternalServerError(Json.obj("error" -> "Failed to write agent.json".asJson))
                        }
                      case None =>
                        NotFound(Json.obj("error" -> s"Agent '$agentName' not found".asJson))
                  yield result
                case Left(err) =>
                  BadRequest(Json.obj("error" -> s"Invalid model config: ${err.getMessage}".asJson))
            }
        }

      // PUT /agents/:name/preset — set or remove the agent's preset reference.
      // Body: {"preset": "vision"} or {"preset": null} (removes the field, falls
      // back to default preset). Uses EntityLoader.findAgentDir to locate the
      // agent.json across all three layers.
      // panelscheme 批（2026-09-21，作者令）：面板只有 Nebula 与任务分发器两类可设
      // 模型方案——其余 agent 拒写（引擎侧 SchemePolicy 已忽略其存储引用，写入只会
      // 造死数据）；kernel/general 由继承机制决定、其余回落默认方案，均不可设。
      case req @ PUT -> Root / "agents" / agentName / "preset" =>
        withAuth(req) {
          if !isValidAgentName(agentName) then BadRequest(Json.obj("error" -> "Invalid agent name".asJson))
          else if !nebflow.core.presets.SchemePolicy.SettableAgents.contains(agentName) then
            BadRequest(
              Json.obj(
                "error" ->
                  (s"Agent '$agentName' does not accept a model-scheme setting (2026-09-21 panel convergence): only Nebula and " +
                    "project-dispatcher are settable. kernel inherits Nebula's current scheme; nodes inherit the project " +
                    "dispatcher's; everything else follows the default preset. Existing stored values are kept but ignored by the engine.").asJson
              )
            )
          else
            req.as[Json].flatMap { body =>
              val presetOpt = body.hcursor.downField("preset").as[Option[String]].toOption.flatten
              for
                dirOpt <- EntityLoader.findAgentDir(agentName)
                result <- dirOpt match
                  case Some(dir) =>
                    IO.blocking {
                      val jsonPath = dir / "agent.json"
                      val json = os.read(jsonPath)
                      parser.parse(json) match
                        case Right(parsed) =>
                          val updated = presetOpt match
                            case Some(name) =>
                              parsed.deepMerge(Json.obj("preset" -> name.asJson))
                            case None =>
                              // Remove the preset field entirely
                              parsed.asObject
                                .map(obj => Json.fromFields(obj.toMap.removed("preset")))
                                .getOrElse(parsed)
                          AtomicJson.writeSync(jsonPath, updated.noSpaces)
                          true
                        case Left(_) => false
                    }.flatMap {
                      case true =>
                        Ok(Json.obj("updated" -> true.asJson, "preset" -> presetOpt.asJson))
                      case false =>
                        InternalServerError(Json.obj("error" -> "Failed to write agent.json".asJson))
                    }
                  case None =>
                    NotFound(Json.obj("error" -> s"Agent '$agentName' not found".asJson))
              yield result
              end for
            }
        }

      // ===== Entity API (Team/Flow/Agent management) =====

      // PUT /agents/:name — RETIRED 2026-09-06 (tool-face batch): the skills/
      // flows write-back is closed. Loud 410 so any stale client sees the
      // retirement instead of assuming the edit landed. agent.json skills/flows
      // declarations are still PARSED (decision A① — legacy grants stay live
      // until stage 3); only this write path is retired.
      case req @ PUT -> Root / "agents" / agentName =>
        withAuth(req) {
          if !isValidAgentName(agentName) then BadRequest(Json.obj("error" -> "Invalid agent name".asJson))
          else
            logger.warn(
              s"Rejected PUT /agents/$agentName — skills/flows write-back retired 2026-09-06 (stage 2d tool-face batch)"
            )
            Gone(
              Json.obj(
                "error" -> "agent skills/flows write-back retired 2026-09-06: per-agent capability config is definition/plugin-managed; agent.json is no longer written from the panel".asJson
              )
            )
        }

      // (2026-09-20 device-face hardening batch) 11-route takedown: GET /skills was
      // RETIRED here — 仓内零调用点 (the plugins page has asserted since the unified
      // plugin system that it must NOT call it: tests/sidebar-plugins.spec.mjs:396).

      // ── Plugins（阶段 2b §B.3：面板审批清单 + CLI 对等）─────────────

      // GET /plugins — 注册表全量（含已封禁项 / 拒载原因 + 清单数据；无审批批 2026-09-13
      // 后条目面无「待审」形态，受信与否 = 在位 ∧ 未被封禁）。
      // 审批清单区块（§B.3）：元信息 / skills 摘要（前 20 行）/ mcp（env 只出键名，
      // 值打码）/ org.nebflow/tools 申请 / 信任状态与 digest。
      case req @ GET -> Root / "plugins" =>
        withAuth(req) {
          for
            (plugins, rejected) <- nebflow.core.plugin.PluginRegistry.listWithRejected()
            entries = plugins.sortBy(_.name).map(nebflow.core.plugin.PluginRegistry.approvalManifest)
            rejectedEntries = rejected.sortBy(_._1).map { case (n, r) =>
              Json.obj("name" -> n.asJson, "reason" -> r.asJson)
            }
            result <- Ok(Json.obj("plugins" -> entries.asJson, "rejected" -> rejectedEntries.asJson))
          yield result
        }

      // GET /plugins/catalog — 分发器目录段同源（trusted only；前端调试/预览用）
      case req @ GET -> Root / "plugins" / "catalog" =>
        withAuth(req) {
          nebflow.core.plugin.PluginRegistry.renderCatalog().flatMap { catalog =>
            Ok(Json.obj("catalog" -> catalog.asJson))
          }
        }

      // POST /plugins/:name/approve — **兼容保留**（无审批批 2026-09-13 起 UI 主线不再调用）：
      // 写一条审批审计记录（digest + 逐文件快照）到 `plugins.trust`。⚠️ 该记录**不再决定
      // 装载**（在位即信任），但它是 seed 覆盖的**仲裁基准**（`trustRecordDigest`）⇒ 手动
      // approve 一个用户改过的默认集包会让下次 boot 的种子镜像覆盖视为「干净」。
      // 名字段白名单校验（拒绝路径穿越形态）。
      case req @ POST -> Root / "plugins" / name / "approve" =>
        withAuth(req) {
          if !isValidAgentName(name) then BadRequest(Json.obj("error" -> "Invalid plugin name".asJson))
          else
            nebflow.core.plugin.PluginRegistry.approve(name).flatMap {
              case Right(msg) => Ok(ApiJson.okMessage(msg))
              case Left(err) => BadRequest(Json.obj("error" -> err.asJson))
            }
        }

      // POST /plugins/:name/revoke — **封禁**（deny-list；2026-09-13 无审批批语义变更）：
      // 写 `plugins.revoked.<name> = {at, by, reason}`（**独立命名空间**，不被任何 approve
      // 抹掉）⇒ ① 不进分发器目录 ② 闸 A/B/C/E 拒（resolve Left）③ 在飞 MCP 下个 30s
      // 重验 tick 停掉。路径名保留（零迁移），语义从「撤回审批」翻转为「点名封禁」。
      // body（可选）：{"reason": "…"}。**解封**走下方 /unblock。
      case req @ POST -> Root / "plugins" / name / "revoke" =>
        withAuth(req) {
          if !isValidAgentName(name) then BadRequest(Json.obj("error" -> "Invalid plugin name".asJson))
          else
            req.as[Json].attempt.map(_.getOrElse(Json.obj())).flatMap { body =>
              val reason = body.hcursor.downField("reason").as[String].toOption.getOrElse("")
              nebflow.core.plugin.PluginBlockPolicy.block(name, reason, "panel/rest").flatMap {
                case Right(_) =>
                  Ok(
                    ApiJson.okMessage(
                      s"Plugin '$name' is now BLOCKED (deny-list) — it leaves the catalog, is refused on " +
                        "new dispatches and at node start, and its in-flight MCP servers are stopped within 30s. " +
                        s"Unblock with POST /api/plugins/$name/unblock or CLI 'nebflow plugin unblock $name'."
                    )
                  )
                case Left(err) => BadRequest(Json.obj("error" -> err.asJson))
              }
            }
        }

      // POST /plugins/:name/unblock — **解封**（2026-09-13 新增）：删 `plugins.revoked.<name>`
      // ⇒ 回落「在位即信任」（目录/派发/装载恢复；已停的在飞 MCP 需重新派发才回来）。
      case req @ POST -> Root / "plugins" / name / "unblock" =>
        withAuth(req) {
          if !isValidAgentName(name) then BadRequest(Json.obj("error" -> "Invalid plugin name".asJson))
          else
            nebflow.core.plugin.PluginBlockPolicy.unblock(name, "panel/rest").flatMap {
              case Right(_) =>
                Ok(
                  ApiJson.okMessage(
                    s"Plugin '$name' unblocked — back to presence trust: it re-enters the catalog and is " +
                      "available for new dispatches on the next scan."
                  )
                )
              case Left(err) => BadRequest(Json.obj("error" -> err.asJson))
            }
        }

      // POST /plugins/:name/disable | /enable — **令 1 派发开关**（2026-09-12）：
      // 只写 `plugins.dispatch.<name>.authorEnabled`（durable 作者意图层）⇒ **只影响
      // 未来派发**：新节点拿不到该插件（闸 A 拒），已在飞/已派发节点**零影响**
      // （闸 B/C/E/D 只判内容面），已注入的 `<injected-plugins>` 提示词全文与审计
      // 留存不变。不放宽内容面（未受信的包此路依然无效）。
      case req @ POST -> Root / "plugins" / name / "disable" =>
        withAuth(req) {
          dispatchSwitch(name, enable = false)
        }
      case req @ POST -> Root / "plugins" / name / "enable" =>
        withAuth(req) {
          dispatchSwitch(name, enable = true)
        }

      // POST /plugins/:name/dispatch/grant — **过渡期临时派发授权**（设计 R8：
      // 过渡只能**放宽**、带 TTL 自动失效、不污染作者意图）。body（可选）：
      // {"ttlSecs": 1800, "refs": ["n-…"], "reason": "…"}。缺省 ttlSecs=1800。
      case req @ POST -> Root / "plugins" / name / "dispatch" / "grant" =>
        withAuth(req) {
          if !isValidAgentName(name) then BadRequest(Json.obj("error" -> "Invalid plugin name".asJson))
          else
            req.as[Json].attempt.map(_.getOrElse(Json.obj())).flatMap { body =>
              val c = body.hcursor
              val ttl = c.downField("ttlSecs").as[Long].toOption.getOrElse(1800L)
              val refs = c.downField("refs").as[List[String]].toOption.getOrElse(Nil)
              val reason = c.downField("reason").as[String].toOption.getOrElse("temporary dispatch grant via REST")
              nebflow.core.plugin.PluginDispatchPolicy.grantTransition(name, ttl, refs, reason, "rest").flatMap {
                case Right(_) =>
                  Ok(
                    ApiJson.okMessage(
                      s"Plugin '$name' temporary dispatch grant recorded (ttlSecs=$ttl, refs=${refs.mkString(",")}) — new dispatches may use it until it expires; the author's intent is untouched"
                    )
                  )
                case Left(err) => BadRequest(Json.obj("error" -> err.asJson))
              }
            }
        }

      // (2026-09-20 device-face hardening batch) 11-route takedown, authorised by
      // the author: POST /plugins/:name/dispatch/clear and GET /flows/list were
      // RETIRED here — both 仓内零调用点 in the classify pass. The sibling
      // POST /plugins/:name/dispatch/grant above stays (it has a BE caller).

      // ── Social interface channels (socpanel batch, 2026-09-19) ────────────
      // Design = `socremote-design` (n-5c95367d) arch §7.2/§7.3. Config lives in
      // the top-level `socialChannels` key of nebflow.json; a pasted credential
      // goes through the EXISTING core/CredentialFileAcl narrowing and the config
      // keeps only its path. 🔴 No response ever carries secret content — the
      // read side answers the mechanical triple `{exists, modeOk, readable}`.
      // All three endpoints sit behind the shared auth gate (withAuth).
      case req @ GET -> Root / "social" / "channels" =>
        withAuth(req) {
          IO.blocking(nebflow.social.SocialChannels.channelsJson(PathUtil.dataRoot)).flatMap(json => Ok(json))
        }

      case req @ POST -> Root / "social" / "channels" / channelId =>
        withAuth(req) {
          req.as[Json].attempt.flatMap {
            case Left(_) =>
              BadRequest(
                Json.obj(
                  "error" -> "invalid_field".asJson,
                  "reason" -> "request body must be a JSON object".asJson
                )
              )
            case Right(body) =>
              IO.blocking(nebflow.social.SocialChannels.save(PathUtil.dataRoot, channelId, body)).flatMap {
                case Right(json) => Ok(json)
                case Left(err) => socialErrorResponse(err)
              }
          }
        }

      case req @ GET -> Root / "social" / "probe" =>
        withAuth(req) {
          val channelId = req.params.getOrElse("channel", "")
          IO.blocking(nebflow.social.SocialChannels.probeJson(PathUtil.dataRoot, channelId)).flatMap {
            case Right(json) => Ok(json)
            case Left(err) => socialErrorResponse(err)
          }
        }

      // (r3 merge 2026-09-21) GET /flows/list stays RETIRED per the main-side
      // device-face hardening takedown above — the branch-side copy carried into
      // this conflict from the pre-takedown base was dropped, not resurrected.

      // (2026-09-20 device-face hardening batch) 11-route takedown: GET /teams/:name
      // (team detail) was RETIRED here — 仓内零调用点 in the classify pass. The
      // /team/rules/:name pair below is the live face (FE caller).

      // GET /team/rules/:name — read team rules.md
      case req @ GET -> Root / "team" / "rules" / teamName =>
        withAuth(req) {
          if !isValidAgentName(teamName) then BadRequest(Json.obj("error" -> "Invalid team name".asJson))
          else
            for
              rules <- EntityLoader.loadTeamRules(teamName)
              result <- Ok(Json.obj("content" -> rules.asJson))
            yield result
        }

      // POST /team/rules/:name — save team rules.md + trigger reload
      case req @ POST -> Root / "team" / "rules" / teamName =>
        withAuth(req) {
          if !isValidAgentName(teamName) then BadRequest(Json.obj("error" -> "Invalid team name".asJson))
          else
            for
              body <- req.as[Json]
              content = body.hcursor.downField("content").as[String].getOrElse("")
              rulesDir = PathUtil.dataRoot / "teams" / teamName
              _ <- IO.blocking {
                os.makeDir.all(rulesDir)
                val tmp = rulesDir / ".rules.md.tmp"
                os.write(tmp, content)
                os.move.over(tmp, rulesDir / "rules.md")
              }
              // Trigger reload so changes take effect on next activation
              _ <- FlowTreeRegistry.treesRef.get.flatMap { treeMap =>
                treeMap.values.toList.traverse_ { treeRef =>
                  treeRef ! TreeCommand.ReloadDefinition(teamName)
                }
              }
              result <- Ok(Json.obj("saved" -> true.asJson))
            yield result
        }

      // (2026-09-20 device-face hardening batch) 11-route takedown: GET /entity-agents
      // was RETIRED here — 仓内零调用点 in the classify pass. Live agent faces are
      // /agents/:name, /agents/:name/model and /agents/:name/preset.

      // ===== Model Presets =====

      // GET /presets — list all presets + default name + agent references
      case req @ GET -> Root / "presets" =>
        withAuth(req) {
          val store = new PresetStore()
          for
            file <- IO.blocking(store.load())
            // Build agent → preset mapping by scanning all agent.json files
            agentPresets <- IO.blocking(scanAgentPresets())
            result <- Ok(
              Json.obj(
                "defaultPreset" -> file.defaultPreset.asJson,
                "presets" -> file.presets.values.toList.asJson,
                "agents" -> agentPresets.asJson
              )
            )
          yield result
        }

      // POST /presets — create a new preset (409 on duplicate name)
      case req @ POST -> Root / "presets" =>
        withAuth(req) {
          req.as[Json].flatMap { body =>
            val name = body.hcursor.downField("name").as[String].getOrElse("")
            if name.isEmpty then BadRequest(Json.obj("error" -> "Missing required field: name".asJson))
            else
              val store = new PresetStore()
              IO.blocking(store.load()).flatMap { file =>
                if file.presets.contains(name) then
                  Conflict(Json.obj("error" -> s"Preset '$name' already exists".asJson))
                else
                  val description = body.hcursor.downField("description").as[String].getOrElse("")
                  val preferred = body.hcursor.downField("preferred").as[Option[String]].toOption.flatten
                  val fallbacks = body.hcursor.downField("fallbacks").as[List[String]].getOrElse(Nil)
                  val preset = ModelPreset(name, description, preferred, fallbacks)
                  val updated = file.copy(presets = file.presets + (name -> preset))
                  IO.blocking(store.save(updated)) *>
                    Created(preset.asJson)
              }
            end if
          }
        }

      // PUT /presets/default — set the default preset (must exist)
      case req @ PUT -> Root / "presets" / "default" =>
        withAuth(req) {
          req.as[Json].flatMap { body =>
            val name = body.hcursor.downField("name").as[String].getOrElse("")
            if name.isEmpty then BadRequest(Json.obj("error" -> "Missing required field: name".asJson))
            else
              val store = new PresetStore()
              IO.blocking(store.load()).flatMap { file =>
                if !file.presets.contains(name) then NotFound(Json.obj("error" -> s"Preset '$name' not found".asJson))
                else
                  val updated = file.copy(defaultPreset = name)
                  IO.blocking(store.save(updated)) *>
                    Ok(Json.obj("defaultPreset" -> name.asJson))
              }
          }
        }

      // PUT /presets/:name — update an existing preset (name immutable)
      case req @ PUT -> Root / "presets" / presetName =>
        withAuth(req) {
          req.as[Json].flatMap { body =>
            val store = new PresetStore()
            IO.blocking(store.load()).flatMap { file =>
              file.presets.get(presetName) match
                case None =>
                  NotFound(Json.obj("error" -> s"Preset '$presetName' not found".asJson))
                case Some(existing) =>
                  val description = body.hcursor.downField("description").as[String].getOrElse(existing.description)
                  val preferred = body.hcursor.downField("preferred").as[Option[String]].toOption.flatten
                  val fallbacks = body.hcursor.downField("fallbacks").as[List[String]].getOrElse(existing.fallbacks)
                  val updated = existing.copy(description = description, preferred = preferred, fallbacks = fallbacks)
                  val newFile = file.copy(presets = file.presets + (presetName -> updated))
                  IO.blocking(store.save(newFile)) *>
                    Ok(updated.asJson)
            }
          }
        }

      // DELETE /presets/:name — delete a preset (409 if default; scrub agent refs)
      case req @ DELETE -> Root / "presets" / presetName =>
        withAuth(req) {
          val store = new PresetStore()
          IO.blocking(store.load()).flatMap { file =>
            if file.defaultPreset == presetName then
              Conflict(Json.obj("error" -> "Cannot delete the default preset; set another as default first".asJson))
            else if !file.presets.contains(presetName) then
              NotFound(Json.obj("error" -> s"Preset '$presetName' not found".asJson))
            else
              // 1. Delete preset from file
              val newFile = file.copy(presets = file.presets - presetName)
              // 2. Scrub all agent.json files that reference this preset
              for
                _ <- IO.blocking(store.save(newFile))
                _ <- scrubPresetRefs(presetName)
                result <- Ok(Json.obj("deleted" -> true.asJson))
              yield result
          }
        }

      // POST /presets/migrate-legacy — migrate per-agent model configs to presets
      // Body: {"agentNames": ["Coder", "qa-frontend", ...]}
      case req @ POST -> Root / "presets" / "migrate-legacy" =>
        withAuth(req) {
          req.as[Json].flatMap { body =>
            val agentNames = body.hcursor.downField("agentNames").as[List[String]].getOrElse(Nil)
            if agentNames.isEmpty then BadRequest(Json.obj("error" -> "Missing or empty agentNames".asJson))
            else migrateLegacyModels(agentNames)
          }
        }

      // ===== Provider model discovery =====

      // POST /provider/models — fetch a provider's model list (GET the endpoint
      // its protocol face declares, see `ModelListFaces`) so the settings dialog
      // can auto-populate model ids instead of hand-typing them. Body: {baseUrl,
      // apiKey?, protocol?, name?}. When apiKey is absent or the masked "***"
      // (edit dialog), the stored key of the matched provider is used. The face
      // comes from the body protocol when one is sent, else from the stored
      // provider matched by name or by baseUrl (the dialog posts {baseUrl,
      // apiKey} only), else anthropic — the dialog's own default.
      // Best-effort: any failure returns 502 + message; the frontend falls back to
      // manual entry (失败降级).
      case req @ POST -> Root / "provider" / "models" =>
        withAuth(req) {
          req.as[Json].flatMap { body =>
            val baseUrl = body.hcursor.downField("baseUrl").as[String].getOrElse("").trim
            val protocol = body.hcursor.downField("protocol").as[String].getOrElse("").trim
            val name = body.hcursor.downField("name").as[String].toOption.filter(_.nonEmpty)
            if baseUrl.isEmpty then BadRequest(Json.obj("error" -> "Missing required field: baseUrl".asJson))
            else
              checkHttpBaseUrl(baseUrl) match
                case Left(err) => BadRequest(Json.obj("error" -> err.asJson))
                case Right(base) =>
                  configRef.get.flatMap { cfg =>
                    val stored = name
                      .flatMap(n => cfg.llm.providers.get(n))
                      .orElse(cfg.llm.providers.values.find(_.baseUrl.replaceAll("/+$", "") == base))
                    val face =
                      if protocol == "openai" then LlmProtocol.OpenAI
                      else if protocol == "anthropic" then LlmProtocol.Anthropic
                      else stored.map(_.protocol).getOrElse(LlmProtocol.Anthropic)
                    val rawKey = body.hcursor.downField("apiKey").as[String].toOption.map(_.trim).getOrElse("")
                    val apiKey =
                      // Masked key from the edit dialog — fall back to the stored one.
                      if rawKey.nonEmpty && rawKey != "***" then rawKey
                      else stored.map(_.apiKey).getOrElse("")
                    ModelListFaces.models(face, base) match
                      case Nil => BadGateway(Json.obj("error" -> ModelListFaces.noEndpointMessage(face).asJson))
                      case urls =>
                        ProviderProbe.fetchProviderModels(urls, apiKey, face.name).flatMap {
                          case Right(models) => Ok(Json.obj("models" -> models.asJson))
                          case Left(err) => BadGateway(Json.obj("error" -> err.asJson))
                        }
                  }
            end if
          }
        }

      // ===== Daemon Management =====

      // GET /daemons — list all daemons with runtime state
      case req @ GET -> Root / "daemons" =>
        withAuth(req) {
          sharedResources.daemonService match
            case None => Ok(Json.obj("daemons" -> List.empty[String].asJson))
            case Some(svc) =>
              val store = new DaemonStore()
              store.load().flatMap { configs =>
                // Reconcile first: a daemons.json hot-edit (config removed /
                // changed under a running entry) must stop the stale process NOW,
                // not leave a ghost holding the port until the panel's stop can
                // no longer reach it (id changed/removed → 404).
                svc.reconcile(configs) *> svc.getStates(configs).flatMap { states =>
                  val cfgById = configs.map(c => c.id -> c).toMap
                  val statesJson = states.map { st =>
                    st.asJson.deepMerge(
                      Json.obj(
                        "autoStart" -> cfgById.get(st.id).exists(_.autoStart).asJson,
                        "restartOnExit" -> cfgById.get(st.id).exists(_.restartOnExit).asJson
                      )
                    )
                  }
                  Ok(Json.obj("daemons" -> statesJson.asJson))
                }
              }
        }

      // PUT /daemons/:id — update daemon config (autoStart / restartOnExit)
      case req @ PUT -> Root / "daemons" / daemonId =>
        withAuth(req) {
          sharedResources.daemonService match
            case None => NotFound(Json.obj("error" -> "Daemon service not available".asJson))
            case Some(_) =>
              req.as[Json].flatMap { body =>
                val autoStartOpt = body.hcursor.downField("autoStart").as[Option[Boolean]].toOption.flatten
                val restartOnExitOpt = body.hcursor.downField("restartOnExit").as[Option[Boolean]].toOption.flatten
                if autoStartOpt.isEmpty && restartOnExitOpt.isEmpty then
                  BadRequest(
                    Json.obj("error" -> "No updatable fields provided (expected autoStart or restartOnExit)".asJson)
                  )
                else
                  val store = new DaemonStore()
                  store
                    .update(
                      daemonId,
                      cfg =>
                        cfg.copy(
                          autoStart = autoStartOpt.getOrElse(cfg.autoStart),
                          restartOnExit = restartOnExitOpt.getOrElse(cfg.restartOnExit)
                        )
                    )
                    .flatMap {
                      case None => NotFound(Json.obj("error" -> s"Daemon '$daemonId' not found".asJson))
                      case Some(updated) => Ok(updated.asJson)
                    }
                end if
              }
        }

      // POST /daemons — create a new daemon
      case req @ POST -> Root / "daemons" =>
        withAuth(req) {
          sharedResources.daemonService match
            case None => NotFound(Json.obj("error" -> "Daemon service not available".asJson))
            case Some(svc) =>
              req.as[Json].flatMap { body =>
                val id = body.hcursor.downField("id").as[String].getOrElse("")
                val name = body.hcursor.downField("name").as[String].getOrElse("")
                val command = body.hcursor.downField("command").as[List[String]].getOrElse(List.empty)
                val cwd = body.hcursor.downField("cwd").as[Option[String]].toOption.flatten
                val env = body.hcursor.downField("env").as[Map[String, String]].getOrElse(Map.empty)
                val autoStart = body.hcursor.downField("autoStart").as[Boolean].getOrElse(false)
                val restartOnExit = body.hcursor.downField("restartOnExit").as[Boolean].getOrElse(false)
                val port = body.hcursor.downField("port").as[Option[Int]].toOption.flatten

                if id.isEmpty || name.isEmpty || command.isEmpty then
                  BadRequest(Json.obj("error" -> "Missing required fields: id, name, command".asJson))
                else
                  val config = DaemonConfig(
                    id = id,
                    name = name,
                    command = command,
                    cwd = cwd,
                    env = env,
                    autoStart = autoStart,
                    restartOnExit = restartOnExit,
                    port = port
                  )
                  val store = new DaemonStore()
                  store.add(config).flatMap { updated =>
                    // Reconcile: adding with an existing id replaces the config —
                    // a running process of the OLD config must be stopped so the
                    // port is free for the new one.
                    svc.reconcile(updated) *> {
                      // Auto-start if requested
                      val startIO = if autoStart then svc.start(config).void.handleErrorWith(_ => IO.unit) else IO.unit
                      startIO *> svc.getState(id).flatMap {
                        case Some(state) => Ok(state.asJson)
                        case None => Ok(config.asJson)
                      }
                    }
                  }
                end if
              }
        }

      // DELETE /daemons/:id — remove a daemon (stops if running)
      case req @ DELETE -> Root / "daemons" / daemonId =>
        withAuth(req) {
          sharedResources.daemonService match
            case None => NotFound(Json.obj("error" -> "Daemon service not available".asJson))
            case Some(svc) =>
              // Decouple stop from remove: a failure OR hang while stopping the
              // process must NOT block removal of the config entry. doStop can
              // raise (fiber-cancel / kill edge case) or hang (readFiber blocked
              // on a stdout pipe held open by an orphaned grandchild process), so
              // we bound it with a timeout and recover any error. The user asked
              // to delete the daemon, so daemons.json is updated regardless —
              // otherwise the entry reappears on the next GET /daemons.
              val store = new DaemonStore()
              // svc.remove (not stop): also drops the entry so the takeover
              // fiber exits — stop alone would leave it probing and resurrect
              // the deleted daemon once the port freed.
              svc.remove(daemonId).timeout(15.seconds).handleErrorWith { e =>
                logger.warn(
                  s"Stop failed/timed out for daemon '$daemonId' during delete; removing config anyway: ${e.getMessage}"
                )
              } *> store.remove(daemonId) *> Ok(Json.obj("deleted" -> true.asJson))
        }

      // POST /daemons/:id/start — start a daemon
      case req @ POST -> Root / "daemons" / daemonId / "start" =>
        withAuth(req) {
          sharedResources.daemonService match
            case None => NotFound(Json.obj("error" -> "Daemon service not available".asJson))
            case Some(svc) =>
              val store = new DaemonStore()
              svc.startById(store, daemonId).flatMap {
                case Some(state) => Ok(state.asJson)
                case None => NotFound(Json.obj("error" -> s"Daemon '$daemonId' not found".asJson))
              }
        }

      // POST /daemons/:id/stop — stop a daemon
      case req @ POST -> Root / "daemons" / daemonId / "stop" =>
        withAuth(req) {
          sharedResources.daemonService match
            case None => NotFound(Json.obj("error" -> "Daemon service not available".asJson))
            case Some(svc) =>
              svc.stop(daemonId).flatMap {
                case Some(state) => Ok(state.asJson)
                case None => NotFound(Json.obj("error" -> s"Daemon '$daemonId' not found".asJson))
              }
        }

      // POST /daemons/:id/restart — restart a daemon
      case req @ POST -> Root / "daemons" / daemonId / "restart" =>
        withAuth(req) {
          sharedResources.daemonService match
            case None => NotFound(Json.obj("error" -> "Daemon service not available".asJson))
            case Some(svc) =>
              val store = new DaemonStore()
              svc.restart(store, daemonId).flatMap {
                case Some(state) => Ok(state.asJson)
                case None => NotFound(Json.obj("error" -> s"Daemon '$daemonId' not found".asJson))
              }
        }
    }
  end routes

  // 以下助手(F 步 2026-09-24 自 RestApiRoutes 类内逐字迁入,调用面全部在本
  // object 的 routes 内):preset/teams/plugins 校验与写回族 + social 渠道错误
  // 映射 + provider baseUrl SSRF 闸。可见性 private 原样(迁入 object 后仅本
  // 域可见,与原类内 private 等价)。

  // ── Preset helpers ──────────────────────────────────────

  /** All agent.json paths across the three layers. */
  private def allAgentJsonFiles(): List[os.Path] =
    val root = PathUtil.dataRoot
    def agentJsons(parent: os.Path): List[os.Path] =
      if !os.exists(parent) then Nil
      else os.list(parent).filter(os.isDir).map(_ / "agent.json").filter(os.exists).toList
    val standalone = agentJsons(root / "agents")
    val teamAgents =
      if !os.exists(root / "teams") then Nil
      else os.list(root / "teams").filter(os.isDir).flatMap(t => agentJsons(t / "agents")).toList
    val flowAgents =
      if !os.exists(root / "flows") then Nil
      else os.list(root / "flows").filter(os.isDir).flatMap(f => agentJsons(f / "agents")).toList
    standalone ++ teamAgents ++ flowAgents

  /**
   * Scan all agent.json files and build a map of agentName → presetName (or null
   * if no preset field). Used by GET /presets to show which agents reference
   * which presets.
   *
   * panelscheme 批（2026-09-21）：映射的是**有效**引用（SchemePolicy 名称策略）——
   * 可设两类（Nebula/任务分发器）= 自有原始引用；kernel/general = 继承根
   * （Nebula/project-dispatcher）的当前引用；其余 agent 引擎已忽略其存储引用 →
   * null（usedBy 计数不再把「死数据」算进引用者）。
   */
  private def scanAgentPresets(): Map[String, Option[String]] =
    val raw: Map[String, Option[String]] = allAgentJsonFiles().flatMap { path =>
      parser.parse(os.read(path)).toOption.flatMap { json =>
        val name = json.hcursor
          .downField("name")
          .as[String]
          .toOption
          .getOrElse((path / os.up).last) // fall back to directory name
        val preset = json.hcursor.downField("preset").as[Option[String]].toOption.flatten
        Some(name -> preset)
      }
    }.toMap
    raw.map { (name, own) =>
      val (effPreset, _) = nebflow.core.presets.SchemePolicy.effectiveRefs(name, own, None)
      name -> effPreset
    }

  end scanAgentPresets

  /**
   * Determine the resolvedFrom value for an agent by reading the raw agent.json.
   * Returns "preset" | "legacy-model" | "default-preset" | "global".
   *
   * panelscheme 批（2026-09-21）名称策略感知：非可设两类（kernel/general/其余）
   * 的存储 preset/model 引用引擎已忽略——kernel/general 的 AgentDef.preset 携带
   * 继承根（Nebula/project-dispatcher）的引用名，按引用是否存在如实报告；其余
   * 不再做 legacy-model 探测（那会把「已忽略的死数据」误报为生效来源，误触发
   * 前端迁移横幅）。可设两类走既有逻辑逐字不变（回归红线）。
   */
  private def computeResolvedFrom(preset: Option[String], agentName: String): String =
    val store = new PresetStore()
    def defaultOrGlobal: String =
      val file = store.load()
      if file.presets.get(file.defaultPreset).exists(p => p.preferred.isDefined || p.fallbacks.nonEmpty) then
        "default-preset"
      else "global"
    if !nebflow.core.presets.SchemePolicy.SettableAgents.contains(agentName) then
      preset match
        case Some(p) =>
          if store.load().presets.contains(p) then "preset" else defaultOrGlobal
        case None => defaultOrGlobal
    else
      // If AgentDef has a preset, it was resolved from preset (or dangling → fallback)
      if preset.isDefined then
        val file = store.load()
        if file.presets.contains(preset.get) then "preset"
        else
          // Dangling preset — check if there's a legacy model
          EntityLoader.findAgentDir(agentName).unsafeRunSync() match
            case Some(dir) =>
              val json = parser.parse(os.read(dir / "agent.json")).toOption.getOrElse(Json.obj())
              val model = json.hcursor.downField("model").as[Option[nebflow.shared.AgentModelConfig]].toOption.flatten
              if model.exists(m => m.preferred.isDefined || m.fallbacks.nonEmpty) then "legacy-model"
              else defaultOrGlobal
            case None => "global"
      else
        // No preset — check legacy model
        EntityLoader.findAgentDir(agentName).unsafeRunSync() match
          case Some(dir) =>
            val json = parser.parse(os.read(dir / "agent.json")).toOption.getOrElse(Json.obj())
            val model = json.hcursor.downField("model").as[Option[nebflow.shared.AgentModelConfig]].toOption.flatten
            if model.exists(m => m.preferred.isDefined || m.fallbacks.nonEmpty) then "legacy-model"
            else defaultOrGlobal
          case None => "global"

    end if

  end computeResolvedFrom

  /**
   * Remove the `preset` field from all agent.json files that reference the given
   * preset name. Called when a preset is deleted so agents fall back to the
   * default preset instead of holding a dangling reference.
   */
  private def scrubPresetRefs(presetName: String): IO[Unit] =
    IO.blocking {
      allAgentJsonFiles().foreach { path =>
        val content = os.read(path)
        parser.parse(content) match
          case Right(json) =>
            json.hcursor.downField("preset").as[Option[String]].toOption.flatten match
              case Some(p) if p == presetName =>
                val updated = json.asObject
                  .map(obj => Json.fromFields(obj.toMap.removed("preset")))
                  .getOrElse(json)
                if updated != json then AtomicJson.writeSync(path, updated.noSpaces)
              case _ => ()
          case Left(_) => () // skip unparseable file
      }
    }

  /**
   * Migrate per-agent legacy model configs to named presets.
   * Groups agents by model-config fingerprint, creates a preset per group
   * (mig-<n>), writes the preset reference, and removes the legacy model field.
   * Agents with empty model configs ({preferred: null, fallbacks: []}) are
   * skipped (treated as "no config" — they already use the default preset).
   */
  private def migrateLegacyModels(agentNames: List[String]): IO[Response[IO]] =
    IO.blocking {
      val store = new PresetStore()
      val file = store.load()
      // Load each agent's raw model config
      val agentsWithConfig = agentNames.flatMap { name =>
        EntityLoader.findAgentDir(name).unsafeRunSync() match
          case None => None
          case Some(dir) =>
            parser.parse(os.read(dir / "agent.json")).toOption.flatMap { json =>
              val model = json.hcursor.downField("model").as[Option[nebflow.shared.AgentModelConfig]].toOption.flatten
              // Only migrate non-empty configs
              if model.exists(m => m.preferred.isDefined || m.fallbacks.nonEmpty) then Some((name, dir, model.get))
              else None
            }
      }
      // Group by fingerprint (preferred + fallbacks)
      def fingerprint(m: nebflow.shared.AgentModelConfig): String =
        s"${m.preferred.getOrElse("")}|${m.fallbacks.mkString(",")}"
      val groups = agentsWithConfig.groupBy { case (_, _, m) => fingerprint(m) }
      // Generate preset names (mig-<n>, avoiding collisions with existing)
      var migN = 1
      val existingNames = file.presets.keySet
      val newPresets = scala.collection.mutable.Map.empty[String, ModelPreset]
      val agentToPreset = scala.collection.mutable.Map.empty[String, String]
      groups.toList.sortBy(_._1).foreach { (fp, agents) =>
        val model = agents.head._3
        // Skip if this fingerprint already matches an existing preset
        val existingMatch =
          file.presets.values.find(p => p.preferred == model.preferred && p.fallbacks == model.fallbacks)
        val presetName = existingMatch match
          case Some(p) => p.name
          case None =>
            var name = s"mig-$migN"
            while existingNames.contains(name) || newPresets.contains(name) do
              migN += 1
              name = s"mig-$migN"
            migN += 1
            val agentList = agents.map(_._1).mkString(", ")
            val preset = ModelPreset(
              name = name,
              description = s"Auto-migrated from: $agentList",
              preferred = model.preferred,
              fallbacks = model.fallbacks
            )
            newPresets += (name -> preset)
            name
        agents.foreach { (name, _, _) => agentToPreset += (name -> presetName) }
      }
      // Write: update presets file + update each agent.json
      val updatedFile = file.copy(presets = file.presets ++ newPresets)
      store.save(updatedFile)
      agentToPreset.toList.foreach { (name, presetName) =>
        EntityLoader.findAgentDir(name).unsafeRunSync() match
          case Some(dir) =>
            val jsonPath = dir / "agent.json"
            parser.parse(os.read(jsonPath)) match
              case Right(json) =>
                // Remove model, add preset
                val withoutModel = json.asObject
                  .map(obj => Json.fromFields(obj.toMap.removed("model")))
                  .getOrElse(json)
                val updated = withoutModel.deepMerge(Json.obj("preset" -> presetName.asJson))
                AtomicJson.writeSync(jsonPath, updated.noSpaces)
              case Left(_) => ()
          case None => ()
      }
      // Build response data (plain values, not IO)
      (agentToPreset.toList, newPresets.values.toList)
    }.flatMap { (migrated, createdPresets) =>
      val migratedAgents = migrated.map { (name, preset) =>
        Json.obj("agent" -> name.asJson, "preset" -> preset.asJson)
      }
      Ok(
        Json.obj(
          "migratedAgents" -> migratedAgents.asJson,
          "createdPresets" -> createdPresets.map(_.asJson).asJson
        )
      )
    }.handleErrorWith(e => InternalServerError(Json.obj("error" -> s"Migration failed: ${e.getMessage}".asJson)))

  /**
   * Build mounted teams JSON for the frontend (GET /api/teams/mounted).
   *  Reads from live TeamSessionRegistry runtime state. Each team is a card with
   *  agent tiles showing status.
   */
  private def buildMountedTeamsJson(): IO[Json] =
    for
      teamsMap <- nebflow.core.flow.TeamSessionRegistry.listMountedTeams
      teams <- EntityLoader.listTeams()
      teamsList <- teamsMap.toList.sortBy(_._1).traverse { (instanceName, agents) =>
        val teamDefOpt = teams.get(instanceName)
        // Only render agents declared in team.json (lead + members). Delegate /
        // SubTask sub-agents are no longer registered in TeamSessionRegistry
        // (no Mail identity), so no ghost tiles can appear here; the filter
        // remains as defense-in-depth. Fall back to all agents when the team
        // definition is missing (legacy behavior).
        val memberNames = teamDefOpt.map(td => (td.lead :: td.members).toSet)
        for
          agentsJson <- agents
            .filter((name, _) => memberNames.forall(_.contains(name)))
            .traverse { (agentName, sid) =>
              nebflow.core.flow.TeamSessionRegistry.isBusy(sid).map { busy =>
                Json.obj(
                  "name" -> agentName.asJson,
                  "sessionId" -> sid.asJson,
                  "status" -> (if busy then "running" else "idle").asJson,
                  "manager" -> teamDefOpt.exists(_.lead == agentName).asJson
                )
              }
            }
          // Team Manager task tool (#D 2026-08-25): the team panel renders a
          // Tasks section from this array (flowTeams.js buildTasksSection,
          // frontend phase-2 contract A1: id/subject/status/blockedBy/blocks).
          // Read straight from the team task store directory (scope key
          // "team:<name>") — empty array for teams with no tasks yet.
          tasks <- FileTaskStore.list(TaskStore.teamScopeKey(instanceName))
        yield Json.obj(
          "name" -> instanceName.asJson,
          "type" -> "team".asJson,
          "agents" -> agentsJson.asJson,
          "tasks" -> tasks.asJson
        )
        end for
      }
    yield teamsList.asJson

  // ── Flow editor helpers ──────────────────────────────────

  private def isValidFlowName(name: String): Boolean =
    name.nonEmpty && name.matches("^[a-zA-Z0-9][a-zA-Z0-9._-]*$") && !name.contains("..")

  private def isValidAgentName(name: String): Boolean =
    name.nonEmpty && name.matches("^[a-zA-Z0-9][a-zA-Z0-9._-]*$") && !name.contains("..")

  /**
   * 令 1 派发开关的 REST 实现单点（`/plugins/:name/enable|disable`）。写 `plugins
   * .dispatch.<name>.authorEnabled`（作者意图层，durable）+ 一条 append-only 审计；
   * **不影响内容信任面** ⇒ 在飞节点零影响。
   */
  private def dispatchSwitch(name: String, enable: Boolean): IO[Response[IO]] =
    if !isValidAgentName(name) then BadRequest(Json.obj("error" -> "Invalid plugin name".asJson))
    else
      nebflow.core.plugin.PluginDispatchPolicy.setAuthorEnabled(name, enable, "panel/rest").flatMap {
        case Right(_) =>
          Ok(
            ApiJson.okMessage(
              s"Plugin '$name' dispatch ${if enable then "enabled" else "disabled"} — " +
                "affects FUTURE dispatches only; nodes already dispatched keep their plugin grant " +
                "(content trust is untouched; use /revoke to withdraw content trust)."
            )
          )
        case Left(err) => BadRequest(Json.obj("error" -> err.asJson))
      }

  /**
   * Error-code mapping for the social-channel endpoints (arch §7.3):
   * `400 invalid_field` / `400 unknown_channel` / `403 secret_mode` / `500 io`.
   * A credential-storage failure is a 403 with the field named — it is never
   * folded into a generic 500, and never reported as a success.
   *
   * Returns the response in effect (`IO`), not a bare value: the http4s dsl
   * constructors already produce `F[Response[F]]`, so wrapping them here and
   * de-wrapping at the call site would be a pointless round trip.
   */
  private def socialErrorResponse(err: nebflow.social.SocialChannels.Failure): IO[Response[IO]] =
    import nebflow.social.SocialChannels.Failure
    err match
      case Failure.UnknownChannel(id) =>
        BadRequest(Json.obj("error" -> "unknown_channel".asJson, "channel" -> id.asJson))
      case Failure.InvalidField(field, reason) =>
        BadRequest(Json.obj("error" -> "invalid_field".asJson, "field" -> field.asJson, "reason" -> reason.asJson))
      case Failure.SecretMode(field, reason) =>
        Forbidden(Json.obj("error" -> "secret_mode".asJson, "field" -> field.asJson, "reason" -> reason.asJson))
      case Failure.Io(reason) =>
        InternalServerError(Json.obj("error" -> "io".asJson, "reason" -> reason.asJson))

  /**
   * SSRF guard for provider model discovery: only absolute http(s) URLs with a
   * non-empty host are fetchable. Blocks other schemes (file:, jar:, ftp:...)
   * and URLs the JDK client would resolve to something unexpected. Returns the
   * validated base URL with trailing slashes trimmed.
   *
   * The model-list PATH is deliberately NOT built here: `baseUrl` is a chat
   * prefix and the two protocol faces place the version segment differently, so
   * each face declares its own list endpoint (`ModelListFaces`, whose
   * declarations carry the measurements that pin the paths).
   */
  private def checkHttpBaseUrl(baseUrl: String): Either[String, String] =
    try
      val normalized = baseUrl.trim.replaceAll("/+$", "")
      val uri = java.net.URI.create(normalized)
      val scheme = Option(uri.getScheme).map(_.toLowerCase).getOrElse("")
      val host = Option(uri.getHost).map(_.trim).getOrElse("")
      if (scheme == "http" || scheme == "https") && host.nonEmpty then Right(normalized)
      else Left("baseUrl must be an absolute http(s) URL with a host")
    catch case _: Exception => Left("Invalid baseUrl")

end PresenceRoutes
