package nebflow.gateway

import cats.effect.IO
import cats.effect.std.Queue
import cats.syntax.all.*
import fs2.{Pipe, Stream}
import io.circe.syntax.*
import io.circe.{Json, JsonObject, parser}
import nebflow.agent.AgentCore
import nebflow.agent.SharedResources
import nebflow.core.AtomicJson
import nebflow.core.Branding
import nebflow.core.PathUtil
import nebflow.core.daemon.{DaemonConfig, DaemonService, DaemonStore}
import nebflow.core.entity.{EntityLoader, NodeRoute}
import nebflow.core.flow.{FlowTreeRegistry, TreeCommand}
import nebflow.core.presets.{ModelPreset, PresetFile, PresetStore}
import nebflow.core.skill.SkillService
// FreezeScheduleConfig encoder givens (workSchedule runtime-authoritative PATCH)
import nebflow.core.schedule.FreezeSchedule.given
import nebflow.core.task.{FileTaskStore, TaskStore}
import cats.effect.unsafe.implicits.global
import nebflow.llm.{HealthState, NebflowServiceConfig, SearchApiHealth}
import nebflow.neblink.*
import nebflow.neblink.FriendCodecs.given
import nebflow.service.ConfigService
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*
import org.http4s.headers.{Authorization, `Content-Type`}
import org.http4s.server.websocket.WebSocketBuilder2
import org.http4s.websocket.WebSocketFrame
import org.typelevel.ci.{CIString, CIStringSyntax}

import scala.concurrent.duration.*

/**
 * REST API routes for CLI consumption.
 * These endpoints mirror the WebSocket message handlers but over HTTP.
 */
class RestApiRoutes(
  token: String,
  configRef: cats.effect.Ref[IO, NebflowServiceConfig],
  sharedResources: SharedResources,
  sessionStore: SessionStore,
  wsRoutes: WebSocketRoutes,
  neblinkService: Option[NeblinkService] = None,
  ttsService: Option[TtsService] = None,
  neblinkDiscovery: Option[nebflow.neblink.NeblinkDiscovery] = None,
  gatewayPort: Int = 8080,
  wsHub: WsHub = new WsHub
):
  private val logger = nebflow.core.NebflowLogger.forName("nebflow.rest-api")

  /** Logto AC+PKCE login single-flight state (stage 2, 2026-08-28). */
  private val pkceLogin = PkceLoginSession.unsafe

  def routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    // Health check (P2-6 layered, 2026-08-25): `providers` = per-model health
    // (up / down:<reason>), `search` = Tier 2a standalone search API health
    // (unconfigured/up/down) — INDEPENDENT of model health, so "模型配额 DOWN
    // 但搜索 API 正常" is visible at a glance. `status` stays "ok" while the
    // gateway serves (the watchdog keys on HTTP 200).
    case GET -> Root / "health" =>
      for
        modelStates <- sharedResources.healthMonitor.getStates
        searchHealth <- sharedResources.healthMonitor.getSearchHealth
        providers = modelStates.map { case (k, st) =>
          k -> (st match
            case HealthState.Up              => "up"
            case HealthState.Down(reason, _) => s"down: $reason")
        }
        search = searchHealth match
          case SearchApiHealth.Unconfigured => Json.obj("status" -> "unconfigured".asJson)
          case SearchApiHealth.Up           => Json.obj("status" -> "up".asJson)
          case SearchApiHealth.Down(reason, since) =>
            Json.obj("status" -> "down".asJson, "reason" -> reason.asJson, "since" -> since.asJson)
        resp <- Ok(
          Json.obj(
            "product" -> "nebflow".asJson, // single-instance guard identification
            "status" -> "ok".asJson,
            "version" -> nebflow.Version.string.asJson,
            "providers" -> providers.asJson,
            "search" -> search
          )
        )
      yield resp

    // Token consumption dashboard aggregate (2026-08-18): structured LLM usage
    // telemetry with dimension slicing.
    //   dim=provider|model|agent|hour|day (absent = totals only)
    //   from/to = epoch millis, inclusive lower / exclusive upper (both optional)
    //   provider/model/agent = exact-match filters, applied before grouping
    //     (dashboard D1: orthogonal to dim — all absent = unfiltered, and an
    //     empty value is treated as absent for backward compatibility)
    case req @ GET -> Root / "usage" / "aggregate" =>
      withAuth(req) {
        val params = req.uri.multiParams
        val dim = params.get("dim").flatMap(_.headOption)
        val from = params.get("from").flatMap(_.headOption).flatMap(_.toLongOption)
        val to = params.get("to").flatMap(_.headOption).flatMap(_.toLongOption)
        val provider = params.get("provider").flatMap(_.headOption).filter(_.nonEmpty)
        val model = params.get("model").flatMap(_.headOption).filter(_.nonEmpty)
        val agent = params.get("agent").flatMap(_.headOption).filter(_.nonEmpty)
        sharedResources.usageRecordStore
          .aggregate(dim, from, to, provider, model, agent)
          .flatMap(agg => Ok(agg.asJson))
      }

    // TTS 语音合成（无需 auth，内部调用）
    case req @ POST -> Root / "tts" =>
      ttsService match
        case None => NotFound(Json.obj("error" -> "TTS not configured".asJson))
        case Some(svc) =>
          req.as[Json].flatMap { body =>
            val text = body.hcursor.downField("text").as[String].getOrElse("")
            svc.synthesize(text).flatMap {
              case Some(bytes) =>
                IO.pure(
                  Response[IO](
                    status = Status.Ok,
                    headers = Headers(`Content-Type`(MediaType.audio.wav)),
                    body = Stream.emits(bytes).covary[IO]
                  )
                )
              case None => NotFound(Json.obj("error" -> "TTS synthesis failed".asJson))
            }
          }

    // Generic command endpoint — mirrors WS messages
    case req @ POST -> Root / "command" =>
      withAuth(req) {
        req.as[Json].flatMap { payload =>
          val responseRef = cats.effect.Ref.unsafe[IO, Option[Json]](None)
          val wsSend = (json: Json) => responseRef.set(Some(json))
          wsRoutes.handleMessagePublic(payload.noSpaces, wsSend).flatMap { _ =>
            responseRef.get.flatMap {
              case Some(resp) => Ok(resp)
              case None => Ok(Json.obj("status" -> "ok".asJson))
            }
          }
        }
      }

    // Headless synchronous turn (P0 benchmark): send user text into a session
    // and block until the turn completes, returning the final assistant
    // message and tool trace. Logic lives in TurnEndpoint (testable);
    // this route adds auth + session validation.
    case req @ POST -> Root / "sessions" / sessionId / "turn" =>
      withAuth(req) {
        req.as[Json].flatMap { body =>
          val content = body.hcursor.downField("content").as[String].getOrElse("")
          val timeoutSec =
            body.hcursor.downField("timeoutSec").as[Int].getOrElse(1800).min(7200).max(1)
          if content.isEmpty then BadRequest(Json.obj("error" -> "content is required".asJson))
          else
            sessionStore.getSessionMeta(sessionId).flatMap {
              case None => NotFound(Json.obj("error" -> s"session not found: $sessionId".asJson))
              case Some(_) =>
                TurnEndpoint.gated(sessionId) {
                  TurnEndpoint.runTurn(
                    wsHub, sessionStore, wsRoutes.dispatchHeadlessTurn, sessionId, content, timeoutSec)
                }
            }
        }
      }

    // Session list. `includeUnindexed=1` (search scope) unions in on-disk-only
    // .ui.json sessions (delegate/subtask/dag sub-agents) that never enter the
    // index. The sidebar consumes this endpoint WITHOUT the param and must stay
    // index-only — that isolation is the reason this is a query param.
    case req @ GET -> Root / "sessions" =>
      withAuth(req) {
        val list =
          if req.params.get("includeUnindexed").contains("1") then sessionStore.listSessionsIncludeUnindexed
          else sessionStore.listSessions
        list.flatMap { sessions =>
          sessionStore.getActiveId.flatMap { activeId =>
            Ok(Json.obj("sessions" -> sessions.asJson, "activeId" -> activeId.asJson))
          }
        }
      }

    // Session history
    case req @ GET -> Root / "sessions" / sessionId / "history" =>
      withAuth(req) {
        sessionStore.getUiMessages(sessionId, 0, 0).flatMap { case (messages, total) =>
          Ok(Json.obj("messages" -> messages.asJson, "total" -> total.asJson, "sessionId" -> sessionId.asJson))
        }
      }

    // Create session
    case req @ POST -> Root / "sessions" =>
      withAuth(req) {
        req.as[Json].flatMap { body =>
          val name = body.hcursor.downField("name").as[String].getOrElse("New Session")
          val agentName = body.hcursor.downField("agentName").as[Option[String]].getOrElse(None)
          val folderId = body.hcursor.downField("folderId").as[Option[String]].getOrElse(None)
          // F1 (#433): new sessions inherit the GLOBAL safety mode instead of
          // the hardcoded confirm-edits — a global auto-all must reach e2e/
          // temp/secondary sessions too, otherwise their permission buckets
          // seed restrictive and background agents hit invisible walls.
          nebflow.core.GlobalSafety.defaultMode.flatMap { mode =>
            sessionStore
              .createSession(name, agentName = agentName, folderId = folderId,
                safetyMode = nebflow.core.SafetyMode.toString(mode))
              .flatMap { meta =>
                Ok(meta.asJson)
              }
          }
        }
      }

    // Delete session
    case req @ DELETE -> Root / "sessions" / sessionId =>
      withAuth(req) {
        sessionStore.deleteSession(sessionId) *> Ok(Json.obj("deleted" -> true.asJson))
      }

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

    // Update config
    case req @ PATCH -> Root / "config" =>
      withAuth(req) {
        req.as[Json].flatMap { body =>
          val cfgStr = body.hcursor.downField("config").as[String].getOrElse(body.noSpaces)
          // 冻结修复（2026-08-27）：runtime 热更键以内存 ref 为权威（镜像 WS
          // updateConfig 分支）——PATCH 快照陈旧时不得回滚这些键。
          (sharedResources.freezeScheduleRef.get,
           sharedResources.thinkingConfigRef.get,
           sharedResources.toolResultTtlRef.get).mapN { (wsCfg, thCfg, ttlCfg) =>
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

    // Models
    case req @ GET -> Root / "models" =>
      withAuth(req) {
        sharedResources.providerRegistry.getAllModels().flatMap { models =>
          Ok(Json.obj("models" -> models.map { case (ref, label) =>
            Json.obj("ref" -> ref.asJson, "label" -> label.asJson)
          }.asJson))
        }
      }

    // GET /models/capability-tags — list predefined capability tags
    case req @ GET -> Root / "models" / "capability-tags" =>
      withAuth(req) {
        Ok(
          Json.obj(
            "tags" -> List(
              Json.obj("key" -> "vision".asJson, "label" -> "图片理解".asJson, "description" -> "支持 image_url 图片输入".asJson)
            ).asJson
          )
        )
      }

    // GET /models/capabilities — list all models with their capability tags
    case req @ GET -> Root / "models" / "capabilities" =>
      withAuth(req) {
        Ok(nebflow.llm.ModelRegistry.loadForApi.asJson)
      }

    // PUT /models/capabilities — update a single model's capability tags
    case req @ PUT -> Root / "models" / "capabilities" =>
      withAuth(req) {
        req.as[Json].flatMap { body =>
          val providerId = body.hcursor.downField("providerId").as[String].getOrElse("")
          val modelId = body.hcursor.downField("modelId").as[String].getOrElse("")
          // B3 Phase 2: vision is tri-state — absent in the request body keeps
          // the existing annotation instead of collapsing to false.
          val visionOpt = body.hcursor.downField("vision").as[Option[Boolean]].toOption.flatten
          val capabilities = body.hcursor.downField("capabilities").as[List[String]].getOrElse(Nil)
          if providerId.nonEmpty && modelId.nonEmpty then
            val key = s"$providerId/$modelId"
            val current = nebflow.llm.ModelRegistry.loadForApi
            val updatedEntry = current.models.get(key) match
              case Some(existing) =>
                existing.copy(
                  vision = visionOpt.orElse(existing.vision),
                  capabilities = capabilities
                )
              case None =>
                nebflow.llm.ModelRegistry.ModelEntry(
                  vision = visionOpt,
                  capabilities = capabilities
                )
            val updatedModels = current.models + (key -> updatedEntry)
            nebflow.llm.ModelRegistry.save(updatedModels)
            // B3 restore semantics: an explicit vision=true annotation is user
            // intent and outranks runtime auto-demotion — clear the in-memory
            // override (and counters) too, otherwise effectiveVision stays
            // false until restart (stripImages blocks any image-bearing
            // success, so resetOnSuccess can never lift it).
            val clearRuntime =
              if visionOpt.contains(true) then nebflow.llm.EmptyCompletionTracker.shared.clearOverride(providerId, modelId)
              else IO.unit
            clearRuntime *> Ok(Json.obj("status" -> "ok".asJson))
          else BadRequest(Json.obj("error" -> "providerId and modelId required".asJson))
        }
      }

    // Agents — three-layer aggregation (global + team + flow)
    case req @ GET -> Root / "agents" =>
      withAuth(req) {
        for
          globalAgents <- EntityLoader.listAgents()
          teams <- EntityLoader.listTeams()
          teamAgentEntries <- teams.toList.traverse { (teamName, _) =>
            IO.blocking {
              val dir = PathUtil.dataRoot / "teams" / teamName / "agents"
              if os.exists(dir) then
                os.list(dir)
                  .filter(os.isDir)
                  .flatMap(d => EntityLoader.loadAgentFromDir(d))
                  .map(a => (teamName, a))
                  .toList
              else Nil
            }
          }
          flows <- EntityLoader.listFlows()
          flowAgentEntries <- flows.toList.traverse { (flowName, _) =>
            IO.blocking {
              val dir = PathUtil.dataRoot / "flows" / flowName / "agents"
              if os.exists(dir) then
                os.list(dir)
                  .filter(os.isDir)
                  .flatMap(d => EntityLoader.loadAgentFromDir(d))
                  .map(a => (flowName, a))
                  .toList
              else Nil
            }
          }
          globalList = globalAgents.values.toList.map { a =>
            Json.obj(
              "name" -> a.name.asJson,
              "description" -> a.description.asJson,
              "displayName" -> a.name.asJson,
              "category" -> a.category.asJson,
              "layer" -> "global".asJson
            )
          }
          teamList = teamAgentEntries.flatten.map { (scope, a) =>
            Json.obj(
              "name" -> a.name.asJson,
              "description" -> a.description.asJson,
              "displayName" -> a.name.asJson,
              "category" -> "team".asJson,
              "layer" -> "team".asJson,
              "scope" -> scope.asJson
            )
          }
          flowList = flowAgentEntries.flatten.map { (scope, a) =>
            Json.obj(
              "name" -> a.name.asJson,
              "description" -> a.description.asJson,
              "displayName" -> a.name.asJson,
              "category" -> "flow".asJson,
              "layer" -> "flow".asJson,
              "scope" -> scope.asJson
            )
          }
          all = globalList ++ teamList ++ flowList
          result <- Ok(Json.obj("agents" -> all.asJson))
        yield result
      }

    // Folders
    case req @ GET -> Root / "folders" =>
      withAuth(req) {
        val agentName = req.params.get("agent").getOrElse("Nebula")
        sessionStore.listFolders(agentName).flatMap { folders =>
          Ok(Json.obj("folders" -> folders.asJson))
        }
      }

    // MCP servers
    case req @ GET -> Root / "mcp" =>
      withAuth(req) {
        IO.blocking {
          val configPath = nebflow.llm.Config.DefaultConfigPath
          if os.exists(configPath) then
            parser
              .parse(os.read(configPath))
              .toOption
              .flatMap(_.hcursor.downField("mcpServers").as[Map[String, Json]].toOption)
          else None
        }.flatMap {
          case Some(servers) => Ok(Json.obj("mcpServers" -> io.circe.Json.fromFields(servers)))
          case None => Ok(Json.obj("mcpServers" -> Json.obj()))
        }
      }

    // Memory
    case req @ GET -> Root / "memory" =>
      withAuth(req) {
        val scope = req.params.get("scope").getOrElse("agent")
        sessionStore.getActiveMeta.flatMap { metaOpt =>
          val agentName = metaOpt.flatMap(_.agentName).getOrElse("Nebula")
          val folderId = metaOpt.flatMap(_.folderId).getOrElse("")
          val content = scope match
            case "user" => nebflow.service.MemoryStore.loadUserMemory.getOrElse("")
            case "agent" => nebflow.service.MemoryStore.loadAgentMemory(agentName).getOrElse("")
            case _ => ""
          Ok(Json.obj("scope" -> scope.asJson, "content" -> content.asJson))
        }
      }

    // ===== NebLink P2P Discovery (no gateway auth — used by other Nebflow instances) =====

    // Return local device info for NebLink discovery probes
    case GET -> Root / "neblink" / "discover" =>
      neblinkService match
        case None => NotFound(Json.obj("error" -> "NebLink not enabled".asJson))
        case Some(ms) =>
          ms.identity.flatMap { id =>
            Ok(
              Json.obj(
                "deviceId" -> id.deviceId.asJson,
                "deviceName" -> id.deviceName.asJson,
                "platform" -> id.platform.asJson,
                "capabilities" -> id.capabilities.asJson
              )
            )
          }

    // Receive a peer's announcement ("I'm online, here's my info")
    case req @ POST -> Root / "neblink" / "announce" =>
      neblinkService match
        case None => NotFound(Json.obj("error" -> "NebLink not enabled".asJson))
        case Some(ms) =>
          val remoteIp = req.remoteAddr.fold("")(a => a.toString)
          // Parse first so we can read the claimed deviceId for the device-ID
          // trust fallback. Malformed bodies from untrusted callers are still
          // rejected as "not a trusted peer" rather than leaking a 400.
          req.as[Json].flatMap { body =>
            io.circe.parser.decode[nebflow.neblink.DeviceDiscoveryInfo](body.noSpaces) match
              case Right(info) =>
                val port = body.hcursor.downField("port").as[Int].getOrElse(8080)
                isKnownNetworkDevice(ms, info.deviceId, remoteIp).flatMap { known =>
                  if ms.isTrustedPeer(remoteIp) || known then
                    ms.handleAnnounce(info, remoteIp, port) *>
                      Ok(Json.obj("ok" -> true.asJson))
                  else Forbidden(Json.obj("error" -> "Not a trusted peer".asJson))
                }
              case Left(_) =>
                if ms.isTrustedPeer(remoteIp) then BadRequest(Json.obj("error" -> "Invalid device info".asJson))
                else Forbidden(Json.obj("error" -> "Not a trusted peer".asJson))
          }

    // ===== NebLink API (gateway auth required — for frontend) =====

    // NebLink scan — trigger NebLink discovery immediately, return updated peers
    case req @ POST -> Root / "neblink" / "scan" =>
      withNeblink(req) { ms =>
        ms.scanNow.flatMap { peersList =>
          Ok(
            Json.obj(
              "peers" -> peersList
                .map(p =>
                  Json.obj(
                    "deviceId" -> p.deviceId.asJson,
                    "deviceName" -> p.deviceName.asJson,
                    "platform" -> p.platform.asJson,
                    "capabilities" -> p.capabilities.asJson,
                    "userDescription" -> p.userDescription.asJson
                  )
                )
                .asJson,
              "peerCount" -> peersList.length.asJson
            )
          )
        }
      }

    // NebLink status — identity, peers, login state
    case req @ GET -> Root / "neblink" / "status" =>
      withNeblink(req) { ms =>
        for
          id <- ms.identity
          peersList <- ms.peers
          cred <- DeviceCredential.load
          cfg <- ms.neblinkConfig
          // Logged in = device credential exists AND NebLink is enabled.
          loggedIn = cred.isDefined && cfg.enabled
          // P1-2: accurate per-peer connectivity. serverOnline (heartbeat) alone
          // doesn't mean the peer is reachable — cross-network peers need relay.
          directOnline = (deviceId: String) => ms.presenceServiceOpt.exists(_.isConnected(deviceId))
          relayAvailable = ms.relayTunnelOpt.exists(_.isAlive)
          r <- Ok(
            Json.obj(
              "loggedIn" -> loggedIn.asJson,
              "device" -> Json.obj(
                "id" -> id.deviceId.asJson,
                "name" -> id.deviceName.asJson,
                "platform" -> id.platform.asJson,
                "capabilities" -> id.capabilities.asJson,
                "userDescription" -> id.userDescription.asJson,
                "avatarUrl" -> id.avatarUrl.asJson,
                "githubLogin" -> id.githubLogin.asJson
              ),
              "peers" -> peersList
                .map(p =>
                  Json.obj(
                    "deviceId" -> p.deviceId.asJson,
                    "deviceName" -> p.deviceName.asJson,
                    "platform" -> p.platform.asJson,
                    "address" -> p.address.asJson,
                    "capabilities" -> p.capabilities.asJson,
                    "userDescription" -> p.userDescription.asJson,
                    "lastSeen" -> p.lastSeen.asJson,
                    "online" -> true.asJson, // server heartbeat: device is registered
                    "directOnline" -> directOnline(p.deviceId).asJson, // P2P WS reachable
                    "relayAvailable" -> relayAvailable.asJson // our relay tunnel is up
                  )
                )
                .asJson
            )
          )
        yield r
      }

    // Update neblink config (e.g. syncIntervalSec)
    case req @ PATCH -> Root / "neblink" / "config" =>
      withNeblink(req) { ms =>
        req.as[Json].flatMap { body =>
          val syncInterval = body.hcursor.downField("syncIntervalSec").as[Option[Int]].toOption.flatten
          ms.updateConfig { cfg =>
            cfg.copy(
              syncIntervalSec = syncInterval.getOrElse(cfg.syncIntervalSec)
            )
          } *> Ok(Json.obj("ok" -> true.asJson))
        }
      }

    // Logout — clear device credential, disable NebLink (keep server address),
    // stop the client, clear user info and peers. Reverses the device-flow login.
    case req @ POST -> Root / "neblink" / "logout" =>
      withNeblink(req) { ms =>
        for
          // 1. Delete the device credential file (~/.nebflow/neblink/device.json)
          _ <- DeviceCredential.clear
          // 2. Disable NebLink in config (keep neblinkServer address for next login).
          //    updateConfig refreshes the in-memory ref AND persists to disk, so
          //    /status reflects the change immediately without a restart.
          _ <- ms.updateConfig(_.copy(enabled = false))
          // 3. Stop the NebLink client (hot-swap to None).
          _ <- neblinkDiscovery.fold(IO.unit)(d => d.setClient(None))
          // 4. Clear user info (avatar, github login) from the device identity.
          _ <- ms.updateDeviceInfo(avatarUrl = Some(""), githubLogin = Some(""))
          // 5. Clear all discovered peers.
          _ <- ms.clearPeers
          r <- Ok(Json.obj("ok" -> true.asJson))
        yield r
      }

    // Enroll device via pairing code — calls the NebLink Server's
    // /api/device/enroll, receives a long-lived device credential, persists it,
    // and writes the neblink config so the device joins on next start.
    case req @ POST -> Root / "neblink" / "enroll" =>
      if !checkAuth(req) then Forbidden(Json.obj("error" -> "Unauthorized".asJson))
      else
        req.as[Json].flatMap { body =>
          val serverOpt = body.hcursor
            .downField("server")
            .as[Option[String]]
            .toOption
            .flatten
            .map(_.stripSuffix("/"))
          val pairCodeOpt = body.hcursor.downField("pairCode").as[Option[String]].toOption.flatten
          (serverOpt, pairCodeOpt) match
            case (Some(server), Some(pairCode)) =>
              neblinkService match
                case None => BadRequest(Json.obj("error" -> "NebLink service not initialized".asJson))
                case Some(ms) =>
                  for
                    identity <- ms.identity
                    enrollBody = Json
                      .obj(
                        "pairCode" -> pairCode.asJson,
                        "deviceId" -> identity.deviceId.asJson,
                        "deviceName" -> identity.deviceName.asJson,
                        "platform" -> identity.platform.asJson
                      )
                      .noSpaces
                    result <- enrollWithServer(server, enrollBody)
                    resp <- result match
                      case Right(credJson) =>
                        val deviceToken = credJson.hcursor.downField("deviceToken").as[String].toOption
                        val networkId = credJson.hcursor.downField("networkId").as[String].toOption.getOrElse("")
                        val deviceId =
                          credJson.hcursor.downField("deviceId").as[String].toOption.getOrElse(identity.deviceId)
                        deviceToken match
                          case Some(token) =>
                            val credential = DeviceCredential(server, networkId, deviceId, token)
                            val newConfig = NeblinkServerConfig(
                              url = server,
                              networkId = networkId,
                              secret = "",
                              deviceToken = Some(token)
                            )
                            for
                              _ <- DeviceCredential.save(credential)
                              current <- NeblinkConfig.load
                              updated = current.copy(enabled = true, neblinkServer = Some(newConfig))
                              _ <- NeblinkConfig.save(updated)
                              r <- Ok(
                                Json.obj(
                                  "ok" -> true.asJson,
                                  "message" -> "Enrolled. Please restart Nebflow to connect.".asJson,
                                  "networkId" -> networkId.asJson
                                )
                              )
                            yield r
                          case None =>
                            BadRequest(Json.obj("error" -> "Server did not return a device token".asJson))
                        end match
                      case Left(err) =>
                        BadRequest(Json.obj("error" -> s"Enrollment failed: $err".asJson))
                  yield resp
              end match
            case _ =>
              BadRequest(Json.obj("error" -> "Missing server or pairCode".asJson))
          end match
        }

    // ===== Device authorization flow (Tailscale-style) =====

    // Start device flow. Dual-mode (Logto stage 1): with a provider
    // configured, start its RFC 8628 /oidc/device/auth; otherwise proxy to
    // neblink-server's /api/device/code. Both map onto the same frontend
    // contract {deviceCode, userCode, verificationUri, interval, expiresIn}.
    case req @ POST -> Root / "neblink" / "device-flow" / "start" =>
      if !checkAuth(req) then Forbidden(Json.obj("error" -> "Unauthorized".asJson))
      else
        neblinkService match
          case None => BadRequest(Json.obj("error" -> "NebLink service not initialized".asJson))
          case Some(ms) =>
            for
              logto <- ms.neblinkConfig.map(_.logto)
              result <- logto match
                case Some(lc) =>
                  LogtoDeviceFlow
                    .start(LogtoDeviceFlow.jdkSend)(lc.endpoint, lc.clientId)
                case None =>
                  for
                    identity <- ms.identity
                    // The neblink-server URL: read from existing config, or use the
                    // public default. For device flow the server must be reachable
                    // from both the browser (for OAuth) and the device (for polling).
                    serverUrl <- neblinkServerUrl(None)
                    body = Json
                      .obj(
                        "deviceId" -> identity.deviceId.asJson,
                        "deviceName" -> identity.deviceName.asJson,
                        "platform" -> identity.platform.asJson
                      )
                      .noSpaces
                    result <- proxyPost(serverUrl, nebflow.neblink.Protocol.DeviceApi.code, body)
                  yield result
              resp <- result match
                case Right(json) => Ok(json)
                case Left(err) => BadRequest(Json.obj("error" -> s"Failed to start device flow: $err".asJson))
            yield resp

    // Poll device flow: proxy to neblink-server's /api/device/token.
    // On success, persist the device credential + update config + hot-swap client.
    // Poll device flow. Dual-mode (Logto stage 1): with a provider
    // configured, poll its /oidc/token (RFC 8628 grant) and on success
    // exchange the access token via /api/device/register; otherwise proxy to
    // neblink-server's /api/device/token. Both success paths converge on the
    // same persist-credential + hot-swap completion.
    case req @ POST -> Root / "neblink" / "device-flow" / "poll" =>
      if !checkAuth(req) then Forbidden(Json.obj("error" -> "Unauthorized".asJson))
      else
        req.as[Json].flatMap { body =>
          val deviceCode = body.hcursor.downField("deviceCode").as[String].getOrElse("")
          val serverUrl = body.hcursor
            .downField("serverUrl")
            .as[Option[String]]
            .toOption
            .flatten
            .map(_.stripSuffix("/"))
          if deviceCode.isEmpty then BadRequest(Json.obj("error" -> "Missing deviceCode".asJson))
          else
            neblinkService match
              case None => BadRequest(Json.obj("error" -> "NebLink service not initialized".asJson))
              case Some(ms) =>
                for
                  resolvedUrl <- neblinkServerUrl(serverUrl)
                  logto <- ms.neblinkConfig.map(_.logto)
                  result <- logto match
                    case Some(lc) =>
                      LogtoDeviceFlow
                        .pollOnce(LogtoDeviceFlow.jdkSend)(lc.endpoint, lc.clientId, deviceCode)
                        .flatMap {
                          case LogtoDeviceFlow.PollOutcome.Success(accessToken) =>
                            ms.identity.flatMap { identity =>
                              LogtoDeviceFlow
                                .register(LogtoDeviceFlow.jdkSend)(
                                  resolvedUrl,
                                  accessToken,
                                  identity.deviceId,
                                  identity.deviceName,
                                  identity.platform
                                )
                                .map {
                                  case Right(json) => Right(json)
                                  case Left(err)   => Left(err)
                                }
                            }
                          // Pending (incl. slow_down, normalized) and terminal
                          // failures surface as error strings exactly like the
                          // legacy proxy path — the frontend's
                          // authorization_pending polling contract is unchanged.
                          case LogtoDeviceFlow.PollOutcome.Pending(err) =>
                            IO.pure(Left(err))
                          case LogtoDeviceFlow.PollOutcome.Failed(err) =>
                            IO.pure(Left(err))
                        }
                    case None =>
                      val pollBody = Json.obj("deviceCode" -> deviceCode.asJson).noSpaces
                      proxyPost(resolvedUrl, nebflow.neblink.Protocol.DeviceApi.token, pollBody)
                  resp <- result match
                    case Right(json) =>
                      // Success — persist credential + update config + hot-swap.
                      completeDeviceEnrollment(ms, resolvedUrl, json)
                    case Left(err) =>
                      // authorization_pending is expected during polling — pass through.
                      BadRequest(Json.obj("error" -> err.asJson))
                yield resp
            end match
          end if
        }

    // ===== Logto AC+PKCE login (stage 2, 2026-08-28) =====
    // Contract (Manager-frozen): start 200 = {authorizeUrl}; logto not
    // configured = 404 {error:"logto-not-configured"}; PKCE state machine
    // exposed via /auth/state {status: idle|pending|success|error, error?}.

    // Start a PKCE login: build verifier/challenge + state (in-memory,
    // single-flight), return the hosted authorize URL for window.open.
    case req @ POST -> Root / "neblink" / "auth" / "start" =>
      if !checkAuth(req) then Forbidden(Json.obj("error" -> "Unauthorized".asJson))
      else
        neblinkService match
          case None => BadRequest(Json.obj("error" -> "NebLink service not initialized".asJson))
          case Some(ms) =>
            for
              // Embedded-default fallback: missing logto block resolves to the
              // product's hosted auth service (fresh installs get PKCE login).
              logto <- ms.neblinkConfig.map(_.effectiveLogto)
              resp <- logto match
                case Some(lc) if lc.pkceClientId.isDefined =>
                  val pkceClientId = lc.pkceClientId.getOrElse("")
                  for
                    verifier <- LogtoAuthCode.generateVerifier
                    challenge = LogtoAuthCode.challengeS256(verifier)
                    state <- LogtoAuthCode.generateState
                    _ <- pkceLogin.start(verifier, state)
                    redirectUri = loopbackCallbackUri
                    authorizeUrl = LogtoAuthCode.authorizeUrl(lc.endpoint, pkceClientId, redirectUri, challenge, state)
                    r <- Ok(Json.obj("authorizeUrl" -> authorizeUrl.asJson))
                  yield r
                // Logto unconfigured, or configured without the AC app id —
                // the PKCE login surface treats both as "not configured".
                // (Defensive: effectiveLogto always resolves via the embedded
                // default, so this arm only fires if that invariant changes.)
                case _ => NotFound(Json.obj("error" -> "logto-not-configured".asJson))
            yield resp

    // Login-state poll for the frontend (pending while the hosted page is
    // open; success/error sticky until the next start).
    case req @ GET -> Root / "neblink" / "auth" / "state" =>
      if !checkAuth(req) then Forbidden(Json.obj("error" -> "Unauthorized".asJson))
      else pkceLogin.statusJson.flatMap(Ok(_))

    // Cloud session sync toggle — removed (session sync deleted)

    // Update device capabilities / user description
    case req @ PUT -> Root / "neblink" / "device-info" =>
      withNeblink(req) { ms =>
        req.as[Json].flatMap { body =>
          val userDesc = body.hcursor.downField("userDescription").as[Option[String]].toOption.flatten
          val caps = body.hcursor.downField("capabilities").as[Option[Map[String, String]]].toOption.flatten
          ms.updateDeviceInfo(userDescription = userDesc, capabilities = caps) *>
            Ok(Json.obj("ok" -> true.asJson))
        }
      }

    // Update a peer device's description (local override)
    case req @ PUT -> Root / "neblink" / "peer-description" =>
      withNeblink(req) { ms =>
        req.as[Json].flatMap { body =>
          val deviceId = body.hcursor.downField("deviceId").as[String].toOption
          val desc = body.hcursor.downField("userDescription").as[String].toOption.getOrElse("")
          deviceId match
            case Some(did) => ms.updatePeerDescription(did, desc) *> Ok(Json.obj("ok" -> true.asJson))
            case None => BadRequest(Json.obj("error" -> "missing deviceId".asJson))
        }
      }

    // File sync endpoints (fingerprints, file GET/PUT) — removed

    // Peer notification — lightweight ping to trigger immediate sync
    case req @ POST -> Root / "neblink" / "remote-exec" =>
      verifyPeerAccess(req).flatMap {
        case Left(resp) => IO.pure(resp)
        case Right(ms) =>
          req.as[Json].flatMap { body =>
            val hc = body.hcursor
            val action = hc.downField("action").as[String].getOrElse("")
            // Expand ~ to this device's user.home — same rationale as the relay
            // path: the path must resolve on the local (receiving) filesystem.
            val params = PathUtil.expandPathParams(
              hc.downField("params").as[io.circe.JsonObject].getOrElse(io.circe.JsonObject.empty)
            )
            val projectRoot = hc.downField("projectRoot").as[String].getOrElse(System.getProperty("user.dir", "."))

            // Extended actions (FileTransfer, Notify, RemoteUpdate) share handlers
            // with the relay tunnel so P2P and relay paths behave identically.
            action match
              case "FileTransfer" =>
                nebflow.neblink.FileTransferAction.handle(params).flatMap {
                  case Right(json) => Ok(Json.obj("output" -> json.noSpaces.asJson))
                  case Left(err)   => Ok(Json.obj("error" -> err.asJson, "output" -> "".asJson))
                }
              case _ =>
                val toolOpt = nebflow.core.tools.ToolRegistry.TOOL_MAP.get(action)
                toolOpt match
                  case Some(tool) =>
                    val ctx = nebflow.core.tools.ToolContext(
                      projectRoot = projectRoot,
                      isRemoteExec = true
                    )
                    tool.call(params, ctx).attempt.flatMap {
                      case Right(Right(result)) => Ok(Json.obj("output" -> result.asJson))
                      case Right(Left(err))     => Ok(Json.obj("error" -> err.message.asJson, "output" -> "".asJson))
                      case Left(e) =>
                        Ok(Json.obj("error" -> s"Tool execution failed: ${e.getMessage}".asJson, "output" -> "".asJson))
                    }
                  case None =>
                    BadRequest(Json.obj("error" -> s"Unknown tool: $action".asJson))
            end match
          }
      }

    // ===== Remote Update (P2P — triggered by another Nebflow instance) =====
    // Downloads and installs the latest JAR, then restarts Nebflow.
    // The caller must be a trusted peer (verified by IP).
    case req @ POST -> Root / "neblink" / "update" =>
      verifyPeerAccess(req).flatMap {
        case Left(resp) => IO.pure(resp)
        case Right(_) =>
          req.as[Json].flatMap { body =>
            val beta = body.hcursor.downField("beta").as[Boolean].getOrElse(false)
            logger.info(s"[neblink] Remote update requested (beta=$beta), running install script...") *>
              nebflow.neblink.RemoteUpdateAction.runInstallScript(beta).flatMap {
                case Right(msg) =>
                  logger.info("[neblink] Install succeeded, spawning restart helper and shutting down...") *>
                    IO.blocking(nebflow.core.RestartHelper.spawnRestart()) *>
                    IO.delay {
                      sharedResources.dispatcher.unsafeRunAndForget(
                        IO.sleep(1.second) *> IO(System.exit(0))
                      )
                    } *>
                    Ok(Json.obj("ok" -> true.asJson, "message" -> msg.asJson))
                case Left(err) =>
                  Ok(Json.obj("ok" -> false.asJson, "error" -> err.asJson))
              }
          }
      }

    // ===== NebLink File Transfer (P2P — peer IP auth) =====

    // Push a file to this device
    case req @ POST -> Root / "neblink" / "transfer" =>
      verifyPeerAccess(req).flatMap {
        case Left(resp) => IO.pure(resp)
        case Right(ms) =>
          req.as[Json].flatMap { body =>
            val path = body.hcursor.downField("path").as[String].getOrElse("")
            val contentB64 = body.hcursor.downField("content").as[String].getOrElse("")
            val overwrite = body.hcursor.downField("overwrite").as[Boolean].getOrElse(false)
            if path.isEmpty then BadRequest(Json.obj("error" -> "Missing path".asJson))
            else if contentB64.isEmpty then BadRequest(Json.obj("error" -> "Missing content".asJson))
            else
              val content = java.util.Base64.getDecoder.decode(contentB64)
              ms.receiveFile(path, content, overwrite)
                .flatMap(size => Ok(Json.obj("ok" -> true.asJson, "path" -> path.asJson, "size" -> size.asJson)))
                .handleErrorWith(e => Ok(Json.obj("ok" -> false.asJson, "error" -> e.getMessage.asJson)))
          }
      }

    // Pull a file from this device
    case req @ GET -> Root / "neblink" / "transfer" =>
      verifyPeerAccess(req).flatMap {
        case Left(resp) => IO.pure(resp)
        case Right(ms) =>
          val path = req.params.getOrElse("path", "")
          if path.isEmpty then BadRequest(Json.obj("error" -> "Missing path parameter".asJson))
          else
            ms.sendFile(path).flatMap {
              case Some(content) =>
                val b64 = java.util.Base64.getEncoder.encodeToString(content)
                Ok(
                  Json.obj(
                    "path" -> path.asJson,
                    "content" -> b64.asJson,
                    "size" -> content.length.asJson
                  )
                )
              case None => NotFound(Json.obj("error" -> s"File not found: $path".asJson))
            }
      }

    // ===== Dropbox: cross-device file transfer =====

    // Frontend uploads a file to send to a peer (gateway token auth)
    case req @ POST -> Root / "neblink" / "dropbox" / "upload" / transferId =>
      withAuth(req) {
        sharedResources.dropboxService match
          case None => NotFound(Json.obj("error" -> "Dropbox not enabled".asJson))
          case Some(svc) =>
            svc.uploadAndRelay(transferId, req.body).flatMap {
              case Right(_) => Ok(Json.obj("ok" -> true.asJson))
              case Left(err) => Ok(Json.obj("ok" -> false.asJson, "error" -> err.asJson))
            }
      }

    // Peer pushes a file via HTTP (peer IP auth)
    case req @ POST -> Root / "neblink" / "dropbox" / "transfer" / transferId =>
      verifyPeerAccess(req).flatMap {
        case Left(resp) => IO.pure(resp)
        case Right(_) =>
          sharedResources.dropboxService match
            case None => NotFound(Json.obj("error" -> "Dropbox not enabled".asJson))
            case Some(svc) =>
              svc.receiveFromPeer(transferId, req.body).flatMap {
                case Right(hash) => Ok(Json.obj("sha256" -> hash.asJson))
                case Left(err) =>
                  val status =
                    if err.contains("not accepted") || err.contains("not found")
                    then Status.NotFound
                    else Status.InternalServerError
                  Response[IO](status).withEntity(Json.obj("error" -> err.asJson)).pure[IO]
              }
      }

    // Gateway-mediated remote update: CLI sends this, gateway does P2P first then relay
    case req @ POST -> Root / "neblink" / "remote-update" =>
      withAuth(req) {
        neblinkService match
          case None => Ok(Json.obj("success" -> false.asJson, "error" -> "NebLink not enabled".asJson))
          case Some(ns) =>
            req.as[Json].flatMap { body =>
              val targetDevice = body.hcursor.downField("device").as[String].getOrElse("")
              val beta = body.hcursor.downField("beta").as[Boolean].getOrElse(false)
              doRemoteUpdate(ns, targetDevice, beta).flatMap {
                case Right(msg) => Ok(Json.obj("success" -> true.asJson, "message" -> msg.asJson))
                case Left(err)  => Ok(Json.obj("success" -> false.asJson, "error" -> err.asJson))
              }
            }
      }

    // ===== A2A 好友与消息端点（spec §6.1 客户端 UI 代理层） =====
    // 全部经 FriendService → NeblinkClient 代理到 neblink-server（Bearer device
    // session token）。friendService 仅在 NebLink Server 配置时存在。

    /** 好友列表 + 双向 pending 请求。 */
    case req @ GET -> Root / "friends" =>
      withAuth(req) {
        sharedResources.friendService match
          case None => NotFound(Json.obj("error" -> "NebLink not enabled".asJson))
          case Some(fs) =>
            fs.refreshFriends().flatMap(resp => Ok(resp.asJson))
      }

    /** 待处理请求分组（incoming / outgoing）。 */
    case req @ GET -> Root / "friends" / "requests" =>
      withAuth(req) {
        sharedResources.friendService match
          case None => NotFound(Json.obj("error" -> "NebLink not enabled".asJson))
          case Some(fs) =>
            fs.refreshFriends().flatMap { resp =>
              Ok(Json.obj(
                "incoming" -> resp.incoming.asJson,
                "outgoing" -> resp.outgoing.asJson
              ))
            }
      }

    /** 发好友请求（按 NebLink 号寻址）。body: {query, note?} */
    case req @ POST -> Root / "friends" / "requests" =>
      withAuth(req) {
        sharedResources.friendService match
          case None => NotFound(Json.obj("error" -> "NebLink not enabled".asJson))
          case Some(fs) =>
            req.as[Json].flatMap { body =>
              val query = body.hcursor.downField("query").as[String].getOrElse("")
              val note = body.hcursor.downField("note").as[Option[String]].toOption.flatten
              if query.isEmpty then BadRequest(Json.obj("error" -> "Missing query".asJson))
              else
                fs.sendFriendRequest(query, note).flatMap(friendResult)
            }
      }

    case req @ POST -> Root / "friends" / "requests" / requestId / "accept" =>
      withAuth(req) {
        sharedResources.friendService match
          case None => NotFound(Json.obj("error" -> "NebLink not enabled".asJson))
          case Some(fs) => fs.acceptFriendRequest(requestId).flatMap(friendResult)
      }

    case req @ POST -> Root / "friends" / "requests" / requestId / "decline" =>
      withAuth(req) {
        sharedResources.friendService match
          case None => NotFound(Json.obj("error" -> "NebLink not enabled".asJson))
          case Some(fs) => fs.declineFriendRequest(requestId).flatMap(friendResultRaw)
      }

    /** 发消息给好友（用户身份——UI 输入框直发，无 agent 权限档位）。body: {body} */
    case req @ POST -> Root / "friends" / friendUserId / "messages" =>
      withAuth(req) {
        sharedResources.friendService match
          case None => NotFound(Json.obj("error" -> "NebLink not enabled".asJson))
          case Some(fs) =>
            req.as[Json].flatMap { body =>
              val text = body.hcursor.downField("body").as[String].getOrElse("")
              if text.isEmpty then BadRequest(Json.obj("error" -> "Missing body".asJson))
              else fs.sendAsUser(friendUserId, text).flatMap(friendResult)
            }
      }

    /** 删除好友。 */
    case req @ DELETE -> Root / "friends" / friendUserId =>
      withAuth(req) {
        sharedResources.friendService match
          case None => NotFound(Json.obj("error" -> "NebLink not enabled".asJson))
          case Some(fs) => fs.removeFriend(friendUserId).flatMap(friendResultRaw)
      }

    /** 拉黑好友（#290 §1.2 WeChat 式黑名单）。 */
    case req @ POST -> Root / "friends" / friendUserId / "block" =>
      withAuth(req) {
        sharedResources.friendService match
          case None => NotFound(Json.obj("error" -> "NebLink not enabled".asJson))
          case Some(fs) => fs.blockFriend(friendUserId).flatMap(friendResultRaw)
      }

    /** 移出黑名单（仅拉黑方；上游非拉黑方 403 not_blocker → BadGateway 透传错误）。 */
    case req @ POST -> Root / "friends" / friendUserId / "unblock" =>
      withAuth(req) {
        sharedResources.friendService match
          case None => NotFound(Json.obj("error" -> "NebLink not enabled".asJson))
          case Some(fs) => fs.unblockFriend(friendUserId).flatMap(friendResultRaw)
      }

    /** 会话列表（按 last_message_id 倒序，含 unreadCount）。 */
    case req @ GET -> Root / "conversations" =>
      withAuth(req) {
        sharedResources.friendService match
          case None => NotFound(Json.obj("error" -> "NebLink not enabled".asJson))
          case Some(fs) =>
            fs.refreshConversations().flatMap(convs => Ok(convs.asJson))
      }

    /** keyset 分页拉消息。?after=N&limit=N */
    case req @ GET -> Root / "conversations" / conversationId / "messages" =>
      withAuth(req) {
        sharedResources.friendService match
          case None => NotFound(Json.obj("error" -> "NebLink not enabled".asJson))
          case Some(fs) =>
            val after = req.params.get("after").flatMap(_.toLongOption).getOrElse(0L)
            val limit = req.params.get("limit").flatMap(_.toIntOption).getOrElse(50)
            fs.listMessages(conversationId, after, limit).flatMap(r => friendResult(r.map(_.asJson)))
      }

    /** 标记已读。body: {lastReadMessageId} */
    case req @ POST -> Root / "conversations" / conversationId / "read" =>
      withAuth(req) {
        sharedResources.friendService match
          case None => NotFound(Json.obj("error" -> "NebLink not enabled".asJson))
          case Some(fs) =>
            req.as[Json].flatMap { body =>
              val lastRead = body.hcursor.downField("lastReadMessageId").as[Long].getOrElse(-1L)
              if lastRead < 0 then BadRequest(Json.obj("error" -> "Missing lastReadMessageId".asJson))
              else fs.markRead(conversationId, lastRead).flatMap(friendResultRaw)
            }
      }

    /** 查号（精确匹配 neblink_id，大小写不敏感）。?q=... */
    case req @ GET -> Root / "users" / "lookup" =>
      withAuth(req) {
        sharedResources.friendService match
          case None => NotFound(Json.obj("error" -> "NebLink not enabled".asJson))
          case Some(fs) =>
            req.params.get("q") match
              case None | Some("") => BadRequest(Json.obj("error" -> "Missing q".asJson))
              case Some(q)         => fs.lookupUser(q).flatMap(friendResult)
      }

  }

  /** Uniform A2A endpoint result mapping: upstream Left → 502 with error body. */
  private def friendResult(result: Either[String, io.circe.Json]): IO[Response[IO]] =
    result match
      case Right(json) => Ok(json)
      case Left(err)   => BadGateway(Json.obj("error" -> err.asJson))

  /** Raw-string upstream results (decline/remove/read): parse the body as JSON
    * when possible, else wrap as {ok, message}. */
  private def friendResultRaw(result: Either[String, String]): IO[Response[IO]] =
    result match
      case Right(body) =>
        parser.parse(body) match
          case Right(json) => Ok(json)
          case Left(_)     => Ok(Json.obj("ok" -> true.asJson, "message" -> body.asJson))
      case Left(err) => BadGateway(Json.obj("error" -> err.asJson))

  /** Shared remote-update logic: P2P HTTP first, relay fallback. Used by REST + WS handlers. */
  private def doRemoteUpdate(
    ns: nebflow.neblink.NeblinkService,
    targetDevice: String,
    beta: Boolean
  ): IO[Either[String, String]] =
    ns.peers.flatMap { peers =>
      peers.find(p =>
        p.deviceName.equalsIgnoreCase(targetDevice) ||
        p.deviceName.toLowerCase.contains(targetDevice.toLowerCase)
      ) match
        case None => IO.pure(Left(s"Device '$targetDevice' not found"))
        case Some(peer) =>
          if peer.address.isEmpty then IO.pure(Left(s"Device '$targetDevice' has no address"))
          else
            logger.info(s"Remote update via P2P: ${peer.deviceName} at ${peer.address} (beta=$beta)") *>
              IO.blocking {
                import sttp.client4.*
                val body = Json.obj("beta" -> beta.asJson).noSpaces
                val resp = basicRequest
                  .post(sttp.model.Uri.unsafeParse(s"${peer.address}/api/neblink/update"))
                  .contentType("application/json")
                  .body(body)
                  .readTimeout(180.seconds)
                  .response(asStringAlways)
                  .send(ns.httpBackend)
                resp
              }.flatMap { resp =>
                if resp.code.isSuccess then IO.pure(Right("Update installed, device is restarting..."))
                else relayUpdateFallback(ns, peer, beta, s"P2P HTTP ${resp.code}")
              }.handleErrorWith { e =>
                relayUpdateFallback(ns, peer, beta, s"P2P unreachable: ${e.getMessage}")
              }
    }

  private def relayUpdateFallback(
    ns: nebflow.neblink.NeblinkService,
    peer: nebflow.neblink.PeerInfo,
    beta: Boolean,
    p2pError: String
  ): IO[Either[String, String]] =
    ns.relayClientOpt match
      case Some(client) =>
        logger.info(s"P2P update failed ($p2pError), trying relay to ${peer.deviceName}") *>
          client.relayUpdate(peer.deviceId, beta)
      case None => IO.pure(Left(p2pError))

  // ===== WebSocket Presence Server Endpoint =====

  /**
   * Accepts incoming WS presence connections from NebLink peers.
   *
   * The peer's device info arrives as query params on the WS upgrade request.
   * Once the WS is established:
   *   - Server sends heartbeat pings every 10s.
   *   - Server responds to client pings with pongs.
   *   - On disconnect, the peer is removed from the peer list.
   *
   * This is a separate HttpRoutes value because it needs WebSocketBuilder2,
   * which is only available inside `withHttpWebSocketApp` in GatewayMain.
   */
  def presenceWsRoutes(wsb: WebSocketBuilder2[IO]): HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ GET -> Root / "neblink" / "presence" =>
      neblinkService match
        case None => NotFound(Json.obj("error" -> "NebLink not enabled".asJson))
        case Some(ms) =>
          val remoteIp = req.remoteAddr.fold("")(a => a.toString)
          val peerDeviceId = req.params.getOrElse("deviceId", "")
          // Trust: IP list (primary) or device-ID network membership (fallback).
          // peerDeviceId is already a query param on the WS upgrade, so the
          // fallback needs no extra header here.
          (if ms.isTrustedPeer(remoteIp) then IO.pure(true)
           else isKnownNetworkDevice(ms, peerDeviceId, remoteIp)).flatMap {
            case false => Forbidden(Json.obj("error" -> "Not a trusted peer".asJson))
            case true =>
              if peerDeviceId.isEmpty then BadRequest(Json.obj("error" -> "Missing deviceId".asJson))
              else
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
                    wsb.build(send, receive)
                  }
                }
          }

    // POST /api/flow/event — Source scripts inject external events (no auth, local only)
    case req @ POST -> Root / "flow" / "event" =>
      req.as[Json].flatMap { body =>
        val sessionId = body.hcursor.downField("sessionId").as[String].getOrElse("")
        val eventType = body.hcursor.downField("type").as[String].getOrElse("")
        val data = body.hcursor.downField("data").as[JsonObject].getOrElse(JsonObject.empty)

        if sessionId.isEmpty || eventType.isEmpty then
          BadRequest(Json.obj("error" -> "Missing 'sessionId' or 'type'".asJson))
        else
          FlowTreeRegistry.get(sessionId).flatMap {
            case Some(_) =>
              // EventFired removed — pipelines don't subscribe to external events
              Ok(Json.obj("status" -> "ok".asJson, "event" -> eventType.asJson))
            case None =>
              NotFound(Json.obj("error" -> s"No FlowTree for session '$sessionId'".asJson))
          }
      }

    // GET /teams/mounted — return all mounted teams with agent status
    // NOTE: Router mounts this under /api prefix, so full path is /api/teams/mounted
    case GET -> Root / "teams" / "mounted" =>
      for
        _ <- nebflow.core.flow.FlowTreeRegistry.awaitRestore(3000L)
        teamsJson <- buildMountedTeamsJson()
        result <- Ok(Json.obj("teams" -> teamsJson))
      yield result

    // GET /running-flows — list currently running DAG flow instances
    case GET -> Root / "running-flows" =>
      nebflow.core.flow.RunningFlowRegistry.listJson.flatMap(json => Ok(json))

    // GET /flow/dag/:name — return flow DAG structure (nodes, edges, routing)
    case GET -> Root / "flow" / "dag" / flowName =>
      if !isValidFlowName(flowName) then BadRequest(Json.obj("error" -> "Invalid flow name".asJson))
      else
        nebflow.core.entity.EntityLoader.loadFlow(flowName).flatMap {
          case Some(dag) =>
            val nodesJson = dag.nodes.map { (nodeId, node) =>
              val route: Json = node.onComplete match
                case nebflow.core.entity.NodeRoute.Goto(t) => Json.fromString(t)
                case nebflow.core.entity.NodeRoute.Return => Json.fromString("$return")
                case p: nebflow.core.entity.NodeRoute.Parallel =>
                  Json.obj(
                    "parallel" -> p.fan.asJson,
                    "onFail" -> (p.onFail match
                      case nebflow.core.entity.NodeRoute.OnFailMode.Collect => Json.fromString("collect")
                      case _ => Json.fromString("abort")
                    )
                  )
                case p: nebflow.core.entity.NodeRoute.ParallelDynamic =>
                  Json.obj(
                    "parallel" -> Json.obj(
                      "slots" -> Json.fromString(p.slotField),
                      "template" -> Json.fromString(p.template)
                    ),
                    "onFail" -> (p.onFail match
                      case nebflow.core.entity.NodeRoute.OnFailMode.Collect => Json.fromString("collect")
                      case _ => Json.fromString("abort")
                    )
                  )
                case nebflow.core.entity.NodeRoute.Switch(expr, cases, _, _) =>
                  val casesObj = io.circe.JsonObject.fromIterable(cases.map { (k, v) =>
                    val target: String = v match
                      case nebflow.core.entity.NodeRoute.Goto(t) => t
                      case nebflow.core.entity.NodeRoute.Return => "$return"
                      case _ => "?"
                    k -> Json.fromString(target)
                  })
                  Json.obj("switch" -> Json.fromString(expr), "cases" -> casesObj.asJson)
              nodeId -> Json.obj(
                "agent" -> node.agent.asJson,
                "input" -> node.input.asJson,
                "onComplete" -> route,
                "onError" -> node.onError.map(_.toString.toLowerCase).asJson,
                "maxRetries" -> node.maxRetries.asJson
              )
            }
            Ok(
              Json.obj(
                "name" -> dag.name.asJson,
                "description" -> dag.description.asJson,
                "entry" -> dag.entry.asJson,
                "maxLoop" -> dag.maxLoop.asJson,
                "nodes" -> nodesJson.asJson
              )
            )
          case None =>
            NotFound(Json.obj("error" -> s"Flow '$flowName' not found".asJson))
        }

    // GET /teams — list all defined teams (from team.json files)
    case GET -> Root / "teams" =>
      for
        teams <- nebflow.core.entity.EntityLoader.listTeams()
        teamsJson = teams.values.toList.sortBy(_.name).map { t =>
          io.circe.Json.obj(
            "name" -> t.name.asJson,
            "description" -> t.description.asJson,
            "lead" -> t.lead.asJson,
            "members" -> t.members.asJson
          )
        }
        result <- Ok(io.circe.Json.obj("teams" -> teamsJson.asJson))
      yield result

    // GET /teams/status/:sessionId — return mounted teams and agent status for frontend
    // NOTE: Router mounts this under /api prefix, so full path is /api/teams/status/:sessionId
    case GET -> Root / "teams" / "status" / sessionId =>
      // Validate sessionId: alphanumerics, dot, underscore, hyphen (covers UUID
      // and flow-node ids like dag-git-merge-scanner-405090), no path traversal
      if sessionId.isEmpty || !sessionId.matches("^[a-zA-Z0-9._-]{1,64}$") then
        BadRequest(Json.obj("error" -> "Invalid sessionId".asJson))
      else
        for
          teamsJson <- buildMountedTeamsJson()
          result <- Ok(Json.obj("sessionId" -> sessionId.asJson, "teams" -> teamsJson))
        yield result

    // GET /teams/mailbox/:sessionId/:teamName — mail history for a team
    case GET -> Root / "teams" / "mailbox" / sessionId / flowName =>
      if sessionId.isEmpty || !sessionId.matches("^[a-zA-Z0-9._-]{1,64}$") then
        BadRequest(Json.obj("error" -> "Invalid sessionId".asJson))
      else
        for
          records <- nebflow.core.flow.FlowMailStore.load(sessionId, flowName)
          result <- Ok(Json.obj("records" -> records.asJson))
        yield result

    // DELETE /teams/mailbox/:sessionId/:teamName — clear mail history
    case DELETE -> Root / "teams" / "mailbox" / sessionId / flowName =>
      if sessionId.isEmpty || !sessionId.matches("^[a-zA-Z0-9._-]{1,64}$") then
        BadRequest(Json.obj("error" -> "Invalid sessionId".asJson))
      else
        for
          _ <- nebflow.core.flow.FlowMailStore.clear(sessionId, flowName)
          result <- Ok(Json.obj("cleared" -> true.asJson))
        yield result

    // GET /teams/mail-queue/:sessionId — pending queue mails
    case GET -> Root / "teams" / "mail-queue" / sessionId =>
      if sessionId.isEmpty || !sessionId.matches("^[a-zA-Z0-9._-]{1,64}$") then
        BadRequest(Json.obj("error" -> "Invalid sessionId".asJson))
      else
        for
          items <- nebflow.core.flow.MailQueueStore.load(sessionId)
          result <- Ok(Json.obj("items" -> items.asJson))
        yield result

    // DELETE /teams/mail-queue/:sessionId/:itemId — cancel a pending queue mail
    case DELETE -> Root / "teams" / "mail-queue" / sessionId / itemId =>
      if sessionId.isEmpty || !sessionId.matches("^[a-zA-Z0-9._-]{1,64}$") then
        BadRequest(Json.obj("error" -> "Invalid sessionId".asJson))
      else
        for
          remaining <- nebflow.core.flow.MailQueueStore.removeById(sessionId, itemId)
          result <- Ok(Json.obj("items" -> remaining.asJson))
        yield result

    // ===== Flow Editor APIs =====

    // GET /teams/def/:name — return team definition for the editor
    case GET -> Root / "teams" / "def" / flowName =>
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

    // GET /agents/list — list all global agents (for extends dropdown)
    // GET /agents/list — list all agents (all three layers)
    case GET -> Root / "agents" / "list" =>
      for
        globalAgents <- EntityLoader.listAgents()
        teams <- EntityLoader.listTeams()
        teamAgentEntries <- teams.toList.traverse { (teamName, _) =>
          IO.blocking {
            val dir = PathUtil.dataRoot / "teams" / teamName / "agents"
            if os.exists(dir) then
              os.list(dir)
                .filter(os.isDir)
                .flatMap(d => EntityLoader.loadAgentFromDir(d))
                .toList
            else Nil
          }
        }
        flows <- EntityLoader.listFlows()
        flowAgentEntries <- flows.toList.traverse { (flowName, _) =>
          IO.blocking {
            val dir = PathUtil.dataRoot / "flows" / flowName / "agents"
            if os.exists(dir) then
              os.list(dir)
                .filter(os.isDir)
                .flatMap(d => EntityLoader.loadAgentFromDir(d))
                .toList
            else Nil
          }
        }
        globalList = globalAgents.values.map { a =>
          Json.obj(
            "name" -> a.name.asJson,
            "description" -> a.description.asJson,
            "tools" -> a.tools.asJson,
            "displayName" -> a.name.asJson,
            "systemPrompt" -> a.systemPrompt.asJson,
            "category" -> a.category.asJson
          )
        }
        teamList = teamAgentEntries.flatten.map { a =>
          Json.obj(
            "name" -> a.name.asJson,
            "description" -> a.description.asJson,
            "tools" -> a.tools.asJson,
            "displayName" -> a.name.asJson,
            "systemPrompt" -> a.systemPrompt.asJson,
            "category" -> "team".asJson
          )
        }
        flowList = flowAgentEntries.flatten.map { a =>
          Json.obj(
            "name" -> a.name.asJson,
            "description" -> a.description.asJson,
            "tools" -> a.tools.asJson,
            "displayName" -> a.name.asJson,
            "systemPrompt" -> a.systemPrompt.asJson,
            "category" -> "flow".asJson
          )
        }
        all = globalList ++ teamList ++ flowList
        result <- Ok(Json.obj("agents" -> all.asJson))
      yield result

    // GET /agents/:name — get agent detail (system.md + tools) — searches all three layers
    case GET -> Root / "agents" / agentName =>
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

    // GET /agents/:name/model — get agent's model configuration — searches all three layers
    case GET -> Root / "agents" / agentName / "model" =>
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

    // PUT /agents/:name/model — update agent's model configuration
    case req @ PUT -> Root / "agents" / agentName / "model" =>
      if !isValidAgentName(agentName) then BadRequest(Json.obj("error" -> "Invalid agent name".asJson))
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

    // PUT /agents/:name/preset — set or remove the agent's preset reference.
    // Body: {"preset": "vision"} or {"preset": null} (removes the field, falls
    // back to default preset). Uses EntityLoader.findAgentDir to locate the
    // agent.json across all three layers.
    case req @ PUT -> Root / "agents" / agentName / "preset" =>
      if !isValidAgentName(agentName) then BadRequest(Json.obj("error" -> "Invalid agent name".asJson))
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

    // ===== Entity API (Team/Flow/Agent management) =====

    // PUT /agents/:name — update agent's skills/flows
    case req @ PUT -> Root / "agents" / agentName =>
      if !isValidAgentName(agentName) then BadRequest(Json.obj("error" -> "Invalid agent name".asJson))
      else
        req.as[Json].flatMap { body =>
          for
            dirOpt <- EntityLoader.findAgentDir(agentName)
            result <- dirOpt match
              case Some(dir) =>
                IO.blocking {
                  val jsonPath = dir / "agent.json"
                  val json = os.read(jsonPath)
                  io.circe.parser.parse(json) match
                    case Right(parsed) =>
                      // Extract skills/flows from request body
                      val skillsOpt = body.hcursor.downField("skills").as[Option[List[String]]]
                      val flowsOpt = body.hcursor.downField("flows").as[Option[List[String]]]
                      val merged = parsed
                        .deepMerge(skillsOpt.toOption.map(s => Json.obj("skills" -> s.asJson)).getOrElse(Json.obj()))
                        .deepMerge(flowsOpt.toOption.map(f => Json.obj("flows" -> f.asJson)).getOrElse(Json.obj()))
                      AtomicJson.writeSync(jsonPath, merged.noSpaces)
                      true
                    case Left(_) => false
                }.flatMap {
                  case true =>
                    Ok(Json.obj("updated" -> true.asJson))
                  case false =>
                    InternalServerError(Json.obj("error" -> "Failed to write agent.json".asJson))
                }
              case None =>
                NotFound(Json.obj("error" -> s"Agent '$agentName' not found".asJson))
          yield result
        }

    // GET /skills — list all available skills (name + description) for the Agent panel
    case GET -> Root / "skills" =>
      for
        skills <- SkillService.listSkills()
        entries = skills.sortBy(_.name).map { s =>
          Json.obj(
            "name" -> s.name.asJson,
            "description" -> s.description.asJson
          )
        }
        result <- Ok(Json.obj("skills" -> entries.asJson))
      yield result

    // GET /flows/list — list all flow definitions (name, description, node count, maxLoop)
    case GET -> Root / "flows" / "list" =>
      for
        flows <- EntityLoader.listFlows()
        entries = flows.values.toList.sortBy(_.name).map { f =>
          val edges = f.nodes.toList.sortBy(_._1).flatMap { (nodeId, node) =>
            node.onComplete match
              case NodeRoute.Goto(target) => List((nodeId, target, None))
              case NodeRoute.Return => List((nodeId, "$return", None))
              case p: NodeRoute.Parallel => p.fan.map(t => (nodeId, t, None))
              case p: NodeRoute.ParallelDynamic => List((nodeId, p.template, None))
              case NodeRoute.Switch(_, cases, _, _) =>
                cases.toList.map { (cond, route) =>
                  route match
                    case NodeRoute.Goto(t)     => List((nodeId, t, Some(cond)))
                    case NodeRoute.Return      => List((nodeId, "$return", Some(cond)))
                    case p: NodeRoute.Parallel => p.fan.map(t => (nodeId, t, Some(cond)))
                    case p: NodeRoute.ParallelDynamic => List((nodeId, p.template, Some(cond)))
                    case _                     => List((nodeId, "?", Some(cond)))
                }.flatten
          }
          Json.obj(
            "name" -> f.name.asJson,
            "description" -> f.description.asJson,
            "entry" -> f.entry.asJson,
            "maxLoop" -> f.maxLoop.asJson,
            "nodeCount" -> f.nodes.size.asJson,
            "nodes" -> f.nodes.toList
              .sortBy(_._1)
              .map { (nodeId, node) =>
                Json.obj(
                  "nodeId" -> nodeId.asJson,
                  "agent" -> node.agent.asJson
                )
              }
              .asJson,
            "edges" -> edges.map { (from, to, cond) =>
              Json.obj("from" -> from.asJson, "to" -> to.asJson, "condition" -> cond.asJson)
            }.asJson
          )
        }
        result <- Ok(Json.obj("flows" -> entries.asJson))
      yield result

    // GET /teams/:name — team detail
    case GET -> Root / "teams" / teamName =>
      if !isValidAgentName(teamName) then BadRequest(Json.obj("error" -> "Invalid team name".asJson))
      else
        for
          teamOpt <- EntityLoader.loadTeam(teamName)
          result <- teamOpt match
            case None => NotFound(Json.obj("error" -> s"Team '$teamName' not found".asJson))
            case Some(team) =>
              Ok(
                Json.obj(
                  "name" -> team.name.asJson,
                  "description" -> team.description.asJson,
                  "lead" -> team.lead.asJson,
                  "members" -> team.members.asJson
                )
              )
        yield result

    // GET /team/rules/:name — read team rules.md
    case GET -> Root / "team" / "rules" / teamName =>
      if !isValidAgentName(teamName) then BadRequest(Json.obj("error" -> "Invalid team name".asJson))
      else
        for
          rules <- EntityLoader.loadTeamRules(teamName)
          result <- Ok(Json.obj("content" -> rules.asJson))
        yield result

    // POST /team/rules/:name — save team rules.md + trigger reload
    case req @ POST -> Root / "team" / "rules" / teamName =>
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

    // GET /entity-agents — list all agents (entity format, with useWhen)
    case GET -> Root / "entity-agents" =>
      for
        agents <- EntityLoader.listAgents()
        entries = agents.values.toList.sortBy(_.name).map { a =>
          Json.obj(
            "name" -> a.name.asJson,
            "description" -> a.description.asJson,
            "useWhen" -> a.useWhen.asJson,
            "tools" -> a.tools.asJson,
            "voice" -> a.voice.asJson,
            "category" -> a.category.asJson
          )
        }
        result <- Ok(Json.obj("agents" -> entries.asJson))
      yield result

    // ===== Model Presets =====

    // GET /presets — list all presets + default name + agent references
    case GET -> Root / "presets" =>
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

    // POST /presets — create a new preset (409 on duplicate name)
    case req @ POST -> Root / "presets" =>
      req.as[Json].flatMap { body =>
        val name = body.hcursor.downField("name").as[String].getOrElse("")
        if name.isEmpty then BadRequest(Json.obj("error" -> "Missing required field: name".asJson))
        else
          val store = new PresetStore()
          IO.blocking(store.load()).flatMap { file =>
            if file.presets.contains(name) then Conflict(Json.obj("error" -> s"Preset '$name' already exists".asJson))
            else
              val description = body.hcursor.downField("description").as[String].getOrElse("")
              val preferred = body.hcursor.downField("preferred").as[Option[String]].toOption.flatten
              val fallbacks = body.hcursor.downField("fallbacks").as[List[String]].getOrElse(Nil)
              val preset = ModelPreset(name, description, preferred, fallbacks)
              val updated = file.copy(presets = file.presets + (name -> preset))
              IO.blocking(store.save(updated)) *>
                Created(preset.asJson)
          }
      }

    // PUT /presets/default — set the default preset (must exist)
    case req @ PUT -> Root / "presets" / "default" =>
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

    // PUT /presets/:name — update an existing preset (name immutable)
    case req @ PUT -> Root / "presets" / presetName =>
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

    // DELETE /presets/:name — delete a preset (409 if default; scrub agent refs)
    case DELETE -> Root / "presets" / presetName =>
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

    // POST /presets/migrate-legacy — migrate per-agent model configs to presets
    // Body: {"agentNames": ["Coder", "qa-frontend", ...]}
    case req @ POST -> Root / "presets" / "migrate-legacy" =>
      req.as[Json].flatMap { body =>
        val agentNames = body.hcursor.downField("agentNames").as[List[String]].getOrElse(Nil)
        if agentNames.isEmpty then BadRequest(Json.obj("error" -> "Missing or empty agentNames".asJson))
        else migrateLegacyModels(agentNames)
      }

    // ===== Provider model discovery =====

    // POST /provider/models — fetch a provider's model list (GET {baseUrl}models)
    // so the settings dialog can auto-populate model ids instead of hand-typing
    // them. Body: {baseUrl, apiKey?, protocol?, name?}. When apiKey is absent or
    // the masked "***" (edit dialog), the stored key of provider `name` is used.
    // Best-effort: any failure returns 502 + message; the frontend falls back to
    // manual entry (失败降级).
    case req @ POST -> Root / "provider" / "models" =>
      withAuth(req) {
        req.as[Json].flatMap { body =>
          val baseUrl = body.hcursor.downField("baseUrl").as[String].getOrElse("").trim
          val protocol = body.hcursor.downField("protocol").as[String].getOrElse("anthropic")
          val name = body.hcursor.downField("name").as[String].toOption.filter(_.nonEmpty)
          if baseUrl.isEmpty then BadRequest(Json.obj("error" -> "Missing required field: baseUrl".asJson))
          else
            checkHttpUrl(baseUrl) match
              case Left(err) => BadRequest(Json.obj("error" -> err.asJson))
              case Right(modelsUrl) =>
                configRef.get.flatMap { cfg =>
                  val rawKey = body.hcursor.downField("apiKey").as[String].toOption.map(_.trim).getOrElse("")
                  val apiKey =
                    // Masked key from the edit dialog — fall back to the stored one.
                    if rawKey.nonEmpty && rawKey != "***" then rawKey
                    else name.flatMap(n => cfg.llm.providers.get(n).map(_.apiKey)).getOrElse("")
                  fetchProviderModels(modelsUrl, apiKey, protocol).flatMap {
                    case Right(models) => Ok(Json.obj("models" -> models.asJson))
                    case Left(err)    => BadGateway(Json.obj("error" -> err.asJson))
                  }
                }
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
   */
  private def scanAgentPresets(): Map[String, Option[String]] =
    allAgentJsonFiles().flatMap { path =>
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

  /**
   * Determine the resolvedFrom value for an agent by reading the raw agent.json.
   * Returns "preset" | "legacy-model" | "default-preset" | "global".
   */
  private def computeResolvedFrom(preset: Option[String], agentName: String): String =
    val store = new PresetStore()
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
            else if file.presets.get(file.defaultPreset).exists(p => p.preferred.isDefined || p.fallbacks.nonEmpty) then
              "default-preset"
            else "global"
          case None => "global"
    else
      // No preset — check legacy model
      EntityLoader.findAgentDir(agentName).unsafeRunSync() match
        case Some(dir) =>
          val json = parser.parse(os.read(dir / "agent.json")).toOption.getOrElse(Json.obj())
          val model = json.hcursor.downField("model").as[Option[nebflow.shared.AgentModelConfig]].toOption.flatten
          if model.exists(m => m.preferred.isDefined || m.fallbacks.nonEmpty) then "legacy-model"
          else
            val file = store.load()
            if file.presets.get(file.defaultPreset).exists(p => p.preferred.isDefined || p.fallbacks.nonEmpty) then
              "default-preset"
            else "global"
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
      }
    yield teamsList.asJson

  // ── Flow editor helpers ──────────────────────────────────

  private def isValidFlowName(name: String): Boolean =
    name.nonEmpty && name.matches("^[a-zA-Z0-9][a-zA-Z0-9._-]*$") && !name.contains("..")

  private def isValidAgentName(name: String): Boolean =
    name.nonEmpty && name.matches("^[a-zA-Z0-9][a-zA-Z0-9._-]*$") && !name.contains("..")

  private def withAuth(req: Request[IO])(f: => IO[Response[IO]]): IO[Response[IO]] =
    if checkAuth(req) then f
    else Forbidden(Json.obj("error" -> "Unauthorized".asJson))

  /** Run block only if NeblinkService is available and request is authenticated. */
  private def withNeblink(req: Request[IO])(f: NeblinkService => IO[Response[IO]]): IO[Response[IO]] =
    if !checkAuth(req) then Forbidden(Json.obj("error" -> "Unauthorized".asJson))
    else
      neblinkService match
        case Some(ms) => f(ms)
        case None => NotFound(Json.obj("error" -> "NebLink not enabled".asJson))

  /**
   * Verify peer-to-peer access.
   *
   * Primary check: is the caller IP in the trusted-peer list (populated from
   * NebLink Server discovery)? The NebLink Server is the trust boundary — only
   * devices on the same network can reach each other.
   *
   * Fallback: trust by device-ID network membership. The trusted IP list can be
   * stale or wrong (a peer's advertised endpoints didn't match its actual
   * source IP — NIC filtering, DHCP rotation, multi-homed host). When a caller
   * presents a deviceId we discovered via the NebLink Server (i.e. a confirmed
   * member of the same networkId), we accept it regardless of the IP list. The
   * relay path authenticates the same way with a server-issued token, so this
   * keeps the P2P-direct path on par with relay.
   *
   * The fallback is gated on a private/LAN source IP: a claimed deviceId alone
   * never grants access to internet-sourced requests (P2P-direct is LAN-only).
   */
  private def verifyPeerAccess(req: Request[IO]): IO[Either[Response[IO], NeblinkService]] =
    neblinkService match
      case None =>
        IO.pure(Left(Response[IO](Status.NotFound).withEntity(Json.obj("error" -> "NebLink not enabled".asJson))))
      case Some(ms) =>
        val remoteIp = req.remoteAddr.fold("")(a => a.toString)
        if ms.isTrustedPeer(remoteIp) then IO.pure(Right(ms))
        else
          val callerDeviceId =
            req.headers.get(CIString("x-neblink-device")).map(_.head.value).getOrElse("")
          isKnownNetworkDevice(ms, callerDeviceId, remoteIp).flatMap {
            case true =>
              logger.info(
                s"Peer $callerDeviceId trusted by device-ID membership (IP $remoteIp not in trusted list)"
              ) *> IO.pure(Right(ms))
            case false =>
              IO.pure(
                Left(
                  Response[IO](Status.Forbidden)
                    .withEntity(Json.obj("error" -> s"Not a trusted peer (from $remoteIp)".asJson))
                )
              )
          }

  /**
   * Network-membership check by device ID. True when the caller claims a
   * deviceId we discovered via the NebLink Server (a member of our networkId)
   * AND the request originates from a private/LAN address.
   */
  private def isKnownNetworkDevice(
    ms: NeblinkService,
    claimedDeviceId: String,
    remoteIp: String
  ): IO[Boolean] =
    if claimedDeviceId.isEmpty || !isPrivateLanIp(remoteIp) then IO.pure(false)
    else ms.peers.map(_.exists(_.deviceId == claimedDeviceId))

  /** RFC1918 private ranges + loopback + link-local. P2P-direct is LAN-only. */
  private def isPrivateLanIp(rawIp: String): Boolean =
    val ip = rawIp.stripPrefix("::ffff:")
    ip == "127.0.0.1" || ip == "::1" ||
    ip.startsWith("10.") ||
    ip.startsWith("192.168.") ||
    ip.startsWith("169.254.") ||
    ip.startsWith("fe80:") ||
    ip.startsWith("172.") && {
      val octet = ip.split('.').lift(1).flatMap(_.toIntOption).getOrElse(-1)
      octet >= 16 && octet <= 31
    }

  private def checkAuth(req: Request[IO]): Boolean =
    req.headers.get[Authorization].collectFirst { case Authorization(Credentials.Token(AuthScheme.Bearer, t)) =>
      t
    } match
      case Some(t) => Auth.validateToken(t, token)
      case None =>
        req.params.get("token").exists(t => Auth.validateToken(t, token))

  /**
   * POST the enrollment body to the NebLink Server and parse the JSON reply.
   * Uses java.net.http directly (mirrors NeblinkClient) to avoid pulling an
   * http4s client dependency into this routes class. Bypasses the system proxy
   * so direct LAN access works.
   */
  private def enrollWithServer(serverUrl: String, body: String): IO[Either[String, Json]] =
    proxyPost(serverUrl, nebflow.neblink.Protocol.DeviceApi.enroll, body)

  /**
   * SSRF guard for provider model discovery: only absolute http(s) URLs with a
   * non-empty host are fetchable. Blocks other schemes (file:, jar:, ftp:...)
   * and URLs the JDK client would resolve to something unexpected. Returns the
   * fully-joined `{baseUrl}models` URL on success.
   */
  private def checkHttpUrl(baseUrl: String): Either[String, java.net.URI] =
    try
      val normalized = if baseUrl.endsWith("/") then baseUrl else baseUrl + "/"
      val uri = java.net.URI.create(normalized + "models")
      val scheme = Option(uri.getScheme).map(_.toLowerCase).getOrElse("")
      val host = Option(uri.getHost).map(_.trim).getOrElse("")
      if (scheme == "http" || scheme == "https") && host.nonEmpty then Right(uri)
      else Left("baseUrl must be an absolute http(s) URL with a host")
    catch case _: Exception => Left("Invalid baseUrl")

  /**
   * GET {baseUrl}models with protocol-specific auth headers and extract the
   * model entries (both OpenAI-compatible and Anthropic reply
   * `{"data":[{"id":..}]}`, normalized with empty ids removed and duplicates
   * collapsed). Each entry is `{id}` plus `contextLength` when the provider
   * reports one (OpenRouter `context_length`, others `context_window`) —
   * absent/unparsable means the field is simply omitted. Uses the same JDK
   * HttpClient posture as the LLM adapters: HTTP/1.1 forced, system proxy
   * honored — a probe must see the same network path real completions take.
   */
  private def fetchProviderModels(
    modelsUrl: java.net.URI,
    apiKey: String,
    protocol: String
  ): IO[Either[String, List[Json]]] =
    IO.blocking {
      val client = java.net.http.HttpClient
        .newBuilder()
        .version(java.net.http.HttpClient.Version.HTTP_1_1)
        .connectTimeout(java.time.Duration.ofSeconds(10))
        .build()
      val reqBuilder = java.net.http.HttpRequest
        .newBuilder()
        .uri(modelsUrl)
        .timeout(java.time.Duration.ofSeconds(15))
        .GET()
      if protocol == "openai" then
        if apiKey.nonEmpty then reqBuilder.header("Authorization", s"Bearer $apiKey")
      else
        // anthropic
        reqBuilder.header("x-api-key", apiKey)
        reqBuilder.header("anthropic-version", "2023-06-01")
      try
        val response = client.send(reqBuilder.build(), java.net.http.HttpResponse.BodyHandlers.ofString())
        val status = response.statusCode()
        if status >= 200 && status < 300 then
          parser.parse(response.body()) match
            case Right(json) =>
              val entries = json.hcursor
                .downField("data")
                .as[List[Json]]
                .getOrElse(Nil)
                .flatMap(j => j.hcursor.downField("id").as[String].toOption.map(_.trim).filter(_.nonEmpty).map(id => (id, j)))
              // distinct by id, first occurrence wins
              val seen = scala.collection.mutable.LinkedHashSet.empty[String]
              val models = entries.collect { case (id, raw) if seen.add(id) =>
                val ctx = List("context_length", "context_window")
                  .flatMap(k => raw.hcursor.downField(k).as[Long].toOption)
                  .headOption
                ctx match
                  case Some(n) => Json.obj("id" -> id.asJson, "contextLength" -> n.asJson)
                  case None    => Json.obj("id" -> id.asJson)
              }
              if models.isEmpty then Left("Provider returned no models")
              else Right(models)
            case Left(err) => Left(s"Invalid JSON from provider: ${err.message}")
        else
          val detail = parser.parse(response.body()).toOption
            .flatMap(_.hcursor.downField("error").downField("message").as[String].toOption)
            .getOrElse(response.body().take(200))
          Left(s"Provider returned HTTP $status: $detail")
      catch case e: Exception => Left(e.getMessage)
      end try
    }.handleErrorWith(e => IO.pure(Left(e.getMessage)))

  /**
   * Generic POST proxy to the NebLink Server. Returns the parsed JSON on
   * success (2xx) or an error message on failure. Bypasses the system proxy.
   */
  /**
    * Shared completion for BOTH device-flow paths (self-hosted token poll
    * and Logto register) and the AC+PKCE callback: read the EnrollResponse
    * fields, persist the credential, switch the config, hot-swap the client,
    * and record the user's profile info. `logtoRefresh` carries the provider
    * refresh token (AC+PKCE / silent re-login) into the persisted credential.
    */
  private def completeDeviceEnrollment(
    ms: NeblinkService,
    resolvedUrl: String,
    json: Json,
    logtoRefresh: Option[String] = None
  ): IO[org.http4s.Response[IO]] =
    persistEnrollment(ms, resolvedUrl, json, logtoRefresh).flatMap {
      case Right(_) =>
        val networkId = json.hcursor.downField("networkId").as[String].toOption.getOrElse("")
        Ok(
          Json.obj(
            "ok" -> true.asJson,
            "networkId" -> networkId.asJson
          )
        )
      case Left(err) => BadRequest(Json.obj("error" -> err.asJson))
    }

  /** The enrollment half of completeDeviceEnrollment — delegates to
    * NeblinkEnrollment (shared with the startup client's silent re-login
    * hook, which has no HTTP context). Returns the persisted device token. */
  private def persistEnrollment(
    ms: NeblinkService,
    resolvedUrl: String,
    json: Json,
    logtoRefresh: Option[String]
  ): IO[Either[String, String]] =
    NeblinkEnrollment.persist(
      ms,
      resolvedUrl,
      json,
      logtoRefresh,
      neblinkDiscovery,
      gatewayPort,
      reloginHook = Some(LogtoSilentRelogin.make(ms, IO.pure(neblinkDiscovery), gatewayPort, neblinkServerUrl(None)))
    )
  end persistEnrollment

  // ── Loopback callback (RFC 8252): Logto redirects the browser here after
  // the hosted login. No gateway token — the browser carries only the
  // provider redirect; the PKCE state parameter is the anti-CSRF check.

  /** The registered loopback redirect (RFC 8252: the provider accepts ANY
    * local port against the port-less registered URI). */
  private def loopbackCallbackUri: String = s"http://127.0.0.1:$gatewayPort/auth/callback"

  def authCallbackRoutes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ GET -> Root / "callback" =>
      handleAuthCallback(req.uri.query.params)
  }

  private def handleAuthCallback(query: Map[String, String]): IO[org.http4s.Response[IO]] =
    // Provider error redirect (?error=...&error_description=...) — user
    // denied / hosted-page failure.
    LogtoAuthCode.parseCallbackError(query) match
      case Some(cb) =>
        val msg = s"${cb.error}${cb.description.fold("")(d => s": $d")}"
        pkceLogin.fail(msg) *> htmlResponse(callbackPage(ok = false, msg), Status.BadRequest)
      case None =>
        val code = query.getOrElse("code", "")
        val state = query.getOrElse("state", "")
        pkceLogin.take(state).flatMap {
          // None = mismatch / expired / stray hit (take already set the
          // error status for pending attempts).
          case None =>
            htmlResponse(
              callbackPage(ok = false, "登录回调校验失败（state 不匹配或已过期）"),
              Status.BadRequest
            )
          case Some(verifier) =>
            neblinkService match
              case None =>
                pkceLogin.fail("NebLink service not initialized") *>
                  htmlResponse(callbackPage(ok = false, "nebflow 服务未初始化"), Status.InternalServerError)
              case Some(ms) =>
                for
                  // Same resolution as /api/neblink/auth/start: explicit logto
                  // block wins, otherwise the embedded production default.
                  // The raw `_.logto` read here previously broke the PKCE
                  // chain for fresh installs (start succeeded via the
                  // embedded default, then the callback died with a
                  // misleading "Logto 登录未配置" — 2026-08-30).
                  logto <- ms.neblinkConfig.map(_.effectiveLogto)
                  serverUrl <- neblinkServerUrl(None)
                  resp <- logto.flatMap(lc => lc.pkceClientId.map(pkce => (lc, pkce))) match
                    case Some((lc, pkceClientId)) =>
                      LogtoAuthCode
                        .tokenCall(LogtoDeviceFlow.jdkSend)(
                          LogtoAuthCode.tokenRequest(
                            lc.endpoint,
                            pkceClientId,
                            loopbackCallbackUri,
                            code,
                            verifier
                          )
                        )
                        .flatMap {
                          case Right(tokens) =>
                            ms.identity.flatMap { identity =>
                              LogtoDeviceFlow
                                .register(LogtoDeviceFlow.jdkSend)(
                                  serverUrl,
                                  tokens.accessToken,
                                  identity.deviceId,
                                  identity.deviceName,
                                  identity.platform
                                )
                                .flatMap {
                                  case Right(json) =>
                                    completeDeviceEnrollment(ms, serverUrl, json, tokens.refreshToken).attempt
                                      .flatMap {
                                        case Right(r) if r.status.isSuccess =>
                                          pkceLogin.succeed *> htmlResponse(callbackPage(ok = true, ""), Status.Ok)
                                        case Right(_) =>
                                          pkceLogin.fail("Enrollment failed") *>
                                            htmlResponse(callbackPage(ok = false, "设备注册未完成"), Status.BadGateway)
                                        case Left(e) =>
                                          pkceLogin.fail(Option(e.getMessage).getOrElse("enrollment error")) *>
                                            htmlResponse(
                                              callbackPage(ok = false, Option(e.getMessage).getOrElse("登录处理失败")),
                                              Status.InternalServerError
                                            )
                                      }
                                  case Left(err) =>
                                    pkceLogin.fail(s"register: $err") *>
                                      htmlResponse(callbackPage(ok = false, s"设备注册失败：$err"), Status.BadGateway)
                                }
                            }
                          case Left(err) =>
                            pkceLogin.fail(s"token: $err") *>
                              htmlResponse(callbackPage(ok = false, s"登录令牌交换失败：$err"), Status.BadRequest)
                        }
                    case _ =>
                      // With effectiveLogto this only fires when an explicit
                      // logto block exists but lacks pkceClientId (the
                      // embedded default carries one; a missing block falls
                      // back to it). Name the actual misconfiguration.
                      pkceLogin.fail("logto-pkce-client-not-configured") *>
                        htmlResponse(
                          callbackPage(ok = false, "Logto PKCE 未配置：logto 段缺少 pkceClientId"),
                          Status.NotFound
                        )
                yield resp
        }

  /** Static loopback login result page (success + error variants). The
    * frontend learns the outcome by polling /api/neblink/auth/state. */
  private def callbackPage(ok: Boolean, message: String): String =
    val headline = if ok then "登录成功，可关闭本页" else "登录失败"
    val detail = if ok then "nebflow 账号已连接，本窗口可以关闭" else message
    val icon = if ok then "✓" else "✕"
    val iconColor = if ok then "#07c160" else "#d1242f"
    s"""<!doctype html>
       |<html lang="zh-CN"><head><meta charset="utf-8">
       |<meta name="viewport" content="width=device-width,initial-scale=1">
       |<title>nebflow 登录</title>
       |<style>body{font-family:-apple-system,'Segoe UI','PingFang SC',sans-serif;display:flex;align-items:center;justify-content:center;height:100vh;margin:0;background:#f6f7f9;color:#1f2328}
       |.card{text-align:center;padding:44px 52px;border-radius:14px;background:#fff;box-shadow:0 2px 14px rgba(0,0,0,.08)}
       |.icon{color:$iconColor;font-size:42px;line-height:1;margin-bottom:10px}h1{font-size:18px;font-weight:600;margin:0 0 8px}
       |p{color:#6a737d;font-size:13px;margin:0;max-width:320px;word-break:break-all}</style></head>
       |<body><div class="card"><div class="icon">$icon</div><h1>$headline</h1><p>$detail</p></div></body></html>""".stripMargin

  /** HTML response without circe's String-entity hijack (explicit bytes +
    * content type + length). */
  private def htmlResponse(markup: String, status: Status): IO[org.http4s.Response[IO]] =
    val bytes = markup.getBytes(java.nio.charset.StandardCharsets.UTF_8)
    IO.pure(
      org.http4s.Response(status = status)
        .withHeaders(Headers(
          `Content-Type`(MediaType.text.html, Charset.`UTF-8`),
          org.http4s.headers.`Content-Length`.unsafeFromLong(bytes.length.toLong)
        ))
        .withBodyStream(Stream.emits(bytes))
    )

  private def proxyPost(serverUrl: String, path: String, body: String): IO[Either[String, Json]] =
    IO.blocking {
      // Force HTTP/1.1 + bypass system proxy. The JDK HttpClient's HTTP/2
      // connection-reuse + TLS 1.3 session resumption clashes with the Caddy
      // reverse proxy in front of neblink.nebflow.space, producing
      // connect timeouts / bad_record_mac TLS alerts on reused connections.
      val client = java.net.http.HttpClient
        .newBuilder()
        .version(java.net.http.HttpClient.Version.HTTP_1_1)
        .proxy(java.net.ProxySelector.of(null))
        .connectTimeout(java.time.Duration.ofSeconds(15))
        .build()
      val request = java.net.http.HttpRequest
        .newBuilder()
        .uri(java.net.URI.create(s"${serverUrl.stripSuffix("/")}$path"))
        .timeout(java.time.Duration.ofSeconds(15))
        .header("Content-Type", "application/json")
        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
        .build()
      try
        val response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString())
        val status = response.statusCode()
        val respBody = response.body()
        if status >= 200 && status < 300 then
          parser.parse(respBody) match
            case Right(json) => Right(json)
            case Left(err) => Left(s"invalid JSON from server: ${err.message}")
        else
          // Surface the server's error message if it's JSON (e.g. authorization_pending).
          parser.parse(respBody) match
            case Right(json) =>
              json.hcursor.downField("error").as[String].toOption match
                case Some(errMsg) => Left(errMsg)
                case None => Left(s"HTTP $status")
            case Left(_) => Left(s"HTTP $status")
      catch case e: Exception => Left(e.getMessage)
      end try
    }.handleErrorWith(e => IO.pure(Left(e.getMessage)))

  /**
   * Resolve the NebLink Server URL for device-flow requests. Priority:
   * 1. Explicitly provided URL (from the request body).
   * 2. URL from the current neblink config.
   * 3. The public default URL.
   */
  private def neblinkServerUrl(explicit: Option[String] = None): IO[String] =
    explicit match
      case Some(url) => IO.pure(url)
      case None =>
        neblinkService match
          case Some(ms) =>
            ms.neblinkConfig.map(_.neblinkServer.map(_.url)).flatMap {
              case Some(url) => IO.pure(url)
              case None => IO.pure(Branding.serverUrl)
            }
          case None => IO.pure(Branding.serverUrl)

end RestApiRoutes
