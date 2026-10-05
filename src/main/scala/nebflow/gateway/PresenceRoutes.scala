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
import nebflow.core.daemon.{DaemonConfig, DaemonPanelSchema, DaemonPanelStore, DaemonService, DaemonStore}
import nebflow.core.entity.{EntityLoader, NodeRoute}
import nebflow.core.flow.{FlowTreeRegistry, TreeCommand}
import nebflow.core.hotrestart.HealthPayload
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
import nebflow.shared.{LlmProtocol, NebflowServiceConfig, PathUtil}
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
 * (presence WS 受理臂 + teams/agents/plugins/social/team-rules/provider/daemons
 * 等 REST 臂),行为保持;经 RestApiRoutes.presenceWsRoutes 委托挂载,GatewayMain 零改动。
 */
private[gateway] object PresenceRoutes:

  def routes(wsb: WebSocketBuilder2[IO], ctx: RestApiCtx): HttpRoutes[IO] =
    import ctx.*

    /**
     * Feishu scan-bind session manager (feiscanbind batch, 2026-09-27): carries
     * the single-flight registry for POST …/scan-bind/begin and the read-only
     * status projection for GET …/scan-bind/status. The SDK leg stays behind
     * the production thunk; the activation leg is the ONE sync point
     * ([[nebflow.social.FeishuBridgePlugin.sync]]) — identical wiring to the
     * save endpoint's resync below, so runtime state is never assembled two
     * different ways.
     *
     * LAZY on purpose (two distinct reasons, both load-bearing):
     *   - construction reads `sharedResources.bridgeManager`, and the gateway
     *     route harnesses that only exercise the agents arms build a
     *     `RestApiRoutes` with `sharedResources = null` (the /agents and /model
     *     route specs, e.g. `AgentPanelConvergenceSpec` / `ModelChainRoutesSpec`).
     *     An eager val would NPE those arms the moment `presenceWsRoutes` is
     *     mounted — a spec-only de-registration, not a behavior change.
     *   - the single-flight registry must outlive a single request (one manager
     *     per mounted routes value, exactly the class-member lifetime it had
     *     before the domain split), so it cannot move inside the handler.
     */
    lazy val feishuScanBind = new nebflow.social.FeishuScanBind(
      PathUtil.dataRoot,
      registerFn = nebflow.social.FeishuScanBind.sdkRegister,
      activate =
        sharedResources.bridgeManager.fold(IO.unit)(m => nebflow.social.FeishuBridgePlugin.sync(m, PathUtil.dataRoot))
    )

    /**
     * Weixin scan-bind session manager (weixin-scanbind batch, 2026-10-03):
     * the SAME begin/status face the feishu card ships, keyed to the
     * weixin-ilink channel. The QR leg stays behind the production
     * [[nebflow.social.WeixinIlinkScanBind.sidecarLogin]] thunk (the official
     * plugin's side-car owns the login); the activation leg is the ONE sync
     * point ([[nebflow.social.WeixinIlinkBridgePlugin.sync]]) — identical
     * wiring to the weixin save endpoint's resync, so runtime state is never
     * assembled two different ways. LAZY for the same two reasons as
     * [[feishuScanBind]] above (spec-only null manager + one registry per
     * mounted routes value).
     */
    lazy val weixinScanBind = new nebflow.social.WeixinIlinkScanBind(
      PathUtil.dataRoot,
      loginFn = nebflow.social.WeixinIlinkScanBind.sidecarLogin(PathUtil.dataRoot),
      activate = sharedResources.bridgeManager.fold(IO.unit)(m =>
        nebflow.social.WeixinIlinkBridgePlugin.sync(m, PathUtil.dataRoot)
      )
    )

    // ── Daemon config-panel helpers (daemonpanel Phase A) ──────────────────
    //
    // 以下三个助手随 daemonpanel 端点一波自 RestApiRoutes 类内迁入本域(形态为
    // **迁移**而非逐字搬运:原为类内 private def,直接取类字段 configRef / logger;
    // 此处为 routes 局部 def,层级不变地经 `import ctx.*` 取同两个成员,调用面仅
    // 三类 config-panel 端点 + GET /daemons 的 hasConfigPanel 旗)。

    /**
     * Read a `kind:"web"` panel's `htmlFile` so the HOST can carry the document
     * into `srcdoc` and inject the local `<meta CSP>` (F-7).
     *
     * Why the host carries it instead of pointing an iframe at a URL: the
     * browser-facing `/api/nf-file` whitelist deliberately omits `html`/`htm`,
     * so there is no URL that serves a panel document — and even if there were,
     * a cross-origin/standalone document cannot be given a `<meta CSP>` by its
     * embedder. Reading the bytes host-side is the only path on which the policy
     * can actually be attached.
     *
     * 🔴 ONE path judge, no second policy: each candidate is resolved to a
     * realpath and put through the SAME credential-namespace ladder as
     * `/api/nf-file` ([[nebflow.gateway.NfFilePolicy.nfVerdictForRealLayer]] —
     * the ladder that used to be reached as `WebSocketRoutes.*` before the
     * Phase-5 decoupling moved it into `NfFilePolicy`). Only the extension LEG
     * differs — that endpoint answers for browser-renderable asset types, while
     * a panel document is `html`/`htm`. Reading the refusing layer from the
     * single source (instead of re-implementing the ladder) is exactly the
     * pattern that function documents: a path refused for a
     * credential/namespace/inode reason is refused here too, and only a
     * `FileType` refusal may be re-judged against `PanelHtmlExtensions`.
     *
     * Candidate roots follow the two namespaces the endpoint can serve (the
     * design's "servable namespace" requirement): `<dataRoot>/<htmlFile>` and the
     * project's own `.nebflow/<htmlFile>`. A relative `htmlFile` cannot escape
     * either root lexically (`..` is rejected by the validator, and the realpath
     * ladder re-checks the result) — and a symlink out of an allowlisted subtree
     * is refused by the namespace layer, not followed.
     *
     * Fail-closed: every refusal is a `Left`, and an oversized document is refused
     * rather than truncated (truncation could drop the panel's own closing tags).
     */
    def readPanelHtml(htmlFile: String): IO[Either[String, String]] =
      IO.blocking {
        val policy = nebflow.gateway.NfFilePolicy.NfPathPolicy.current()
        val roots = List(
          java.nio.file.Paths.get(PathUtil.dataRoot.toString),
          java.nio.file.Paths.get(os.pwd.toString).resolve(".nebflow")
        )
        def extOf(p: java.nio.file.Path): String =
          val name = p.getFileName.toString
          val dot = name.lastIndexOf('.')
          if dot < 0 then "" else name.substring(dot + 1).toLowerCase

        // The first candidate that EXISTS decides: a denial is final, never
        // "shop the next root until one gets past the judge".
        val existing = roots.map(_.resolve(htmlFile).normalize()).find { p =>
          java.nio.file.Files.exists(p) && java.nio.file.Files.isRegularFile(p)
        }
        existing match
          case None => Left(s"panel file not found: $htmlFile")
          case Some(lexical) =>
            val real =
              try Right(lexical.toRealPath())
              catch case e: Throwable => Left(s"panel file could not be resolved: ${e.getMessage}")
            real match
              case Left(err) => Left(err)
              case Right(r) =>
                nebflow.gateway.NfFilePolicy.nfVerdictForRealLayer(r, policy) match
                  case Some((layer, denied)) if layer != nebflow.gateway.NfFilePolicy.NfDenyLayer.FileType =>
                    // Namespace / credential / credential-inode refusal is FINAL:
                    // `secrets/*.html`, a hard link to a credential file, or any
                    // path outside the servable namespaces never becomes a panel.
                    Left(s"panel file refused ($layer): ${denied.message}")
                  case _ =>
                    val name = r.getFileName.toString
                    val ext = extOf(r)
                    val size = java.nio.file.Files.size(r)
                    if !DaemonPanelSchema.PanelHtmlExtensions.contains(ext) then
                      Left(s"panel file must be .html/.htm (got '$name')")
                    else if size > DaemonPanelSchema.MaxPanelHtmlBytes then
                      Left(s"panel file is too large: $size bytes > ${DaemonPanelSchema.MaxPanelHtmlBytes}")
                    else Right(new String(java.nio.file.Files.readAllBytes(r), java.nio.charset.StandardCharsets.UTF_8))
            end match
        end match
      }.handleErrorWith(e => IO.pure(Left(s"panel file could not be read: ${e.getMessage}")))

    /**
     * Is this daemon's config panel actually usable right now?
     *
     * The single answer behind both `hasConfigPanel` (the row button, F1/C7) and
     * the config-panel endpoints (F-8 fail-closed): a declaration that does not
     * validate is hidden, and so is a `kind:"web"` + `htmlFile` declaration whose
     * file cannot be carried. Sharing one question is what keeps the button and
     * the endpoint from disagreeing (a button that always answers 409 is not a
     * fail-closed hidden entry).
     */
    def panelUsable(
      cfg: Option[DaemonConfig],
      svcCfg: NebflowServiceConfig
    ): IO[Option[DaemonPanelSchema.Declaration]] =
      cfg.flatMap(_.configPanel) match
        case None => IO.pure(None)
        case Some(raw) =>
          DaemonPanelSchema.validate(raw, DaemonPanelSchema.allowWeb(svcCfg.daemonPanel)) match
            case Left(_) => IO.pure(None)
            case Right(decl) =>
              decl.htmlFile match
                case None => IO.pure(Some(decl))
                case Some(f) => readPanelHtml(f).map(_.toOption.map(_ => decl))

    /**
     * Resolve a daemon id to its validated config-panel declaration.
     *
     * Shared by the three config-panel endpoints so their degradation is
     * uniform and mechanical:
     *   - daemon unknown                    -> 404
     *   - no `configPanel` key              -> 409 `no config panel`   (F-8)
     *   - declaration fails validation      -> 409 `no config panel`   (F-8,
     *     fail-closed: an invalid declaration is hidden, never partially used)
     *   - `kind:"web"` without the explicit  -> 409 `no config panel`   (F-5/F-9,
     *     nebflow.json switch                    default-closed escape hatch)
     *   - `kind:"web"`+`htmlFile` unreadable -> 409 `no config panel`   (F-7,
     *     fail-closed: an unusable panel document hides the entry too)
     */
    def daemonPanelContext(
      daemonId: String
    ): IO[Either[Response[IO], (DaemonPanelSchema.Declaration, DaemonConfig)]] =
      // Bare `Response` values (not the DSL constructors, which yield
      // `IO[Response[IO]]`) so callers can uniformly `IO.pure` the error branch.
      def jsonResponse(status: Status, body: Json): Response[IO] =
        Response[IO](status).withEntity(body)
      val noPanel: Response[IO] =
        jsonResponse(
          Status.Conflict,
          Json.obj("error" -> "no config panel".asJson, "id" -> daemonId.asJson)
        )
      new DaemonStore().load().flatMap { configs =>
        configs.find(_.id == daemonId) match
          case None =>
            IO.pure(Left(jsonResponse(Status.NotFound, Json.obj("error" -> s"Daemon '$daemonId' not found".asJson))))
          case Some(cfg) =>
            if cfg.configPanel.isEmpty then IO.pure(Left(noPanel))
            else
              configRef.get.flatMap { svcCfg =>
                // The SAME question behind `hasConfigPanel` (F1/C7) — so the row
                // button and these endpoints can never disagree: an unusable panel
                // is hidden from BOTH, never a button that always answers 409.
                panelUsable(Some(cfg), svcCfg).map {
                  case Some(decl) => Right((decl, cfg))
                  case None =>
                    logger.warn(s"Rejected configPanel declaration for daemon '$daemonId' (invalid or unusable)")
                    Left(noPanel)
                }
              }
      }
    end daemonPanelContext

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
                      val peer = nebflow.shared.PeerInfo(
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
                      "flows" -> defn.flows.asJson,
                      // builtin-def 批（2026-10-03 作者令①）：代码定义 agent 的
                      // 只读标记——面板据此关闭 prompt 编辑器与保存通道。
                      "builtin" -> nebflow.core.entity.BuiltinAgents.isBuiltin(defn.name).asJson
                    )
                  )
            yield result
        }

      // GET /agents/:name/model — the agent's model-chain read face (the /model
      // contract). mode = "explicit" when the agent carries a non-empty own
      // chain, "follow" otherwise; chain = the own stored chain (null when
      // following); effectiveChain = what the engine resolves (own chain >
      // Nebula primary chain > seed chain); resolvedFrom names the resolution
      // source; current = the healthy head of the effective chain; settable =
      // whether PUT accepts writes for this agent.
      //
      // chain-face (modelcfg batch): this is the ONE shape the /model panel and
      // the CLI read (`web/js/modelPanel.js` keys on `effectiveChain` and
      // `resolvedFrom`; `cli/ModelCommand.scala` builds its write body from
      // `chain | effectiveChain`). The older flat shape it replaces (model /
      // preferred / fallbacks / default / preset) has no consumer left in the
      // tree, and its `resolvedFrom` vocabulary was a different one
      // ("preset"|"legacy-model"|…) — the chain face reports
      // own-chain|nebula-chain|seed, so merging the two would make the field
      // mean two things at once.
      case req @ GET -> Root / "agents" / agentName / "model" =>
        withAuth(req) {
          if !isValidAgentName(agentName) then BadRequest(Json.obj("error" -> "Invalid agent name".asJson))
          else
            for
              agentOpt <- EntityLoader.findAgentByName(agentName)
              result <- agentOpt match
                case None => NotFound(Json.obj("error" -> s"Agent '$agentName' not found".asJson))
                case Some(_) =>
                  val policy = nebflow.core.SchemePolicy
                  val own = policy.ownChainOf(agentName).filter(policy.hasChain)
                  val (effective, resolvedFrom) = policy.resolveModel(agentName, own)
                  val mode = if own.isDefined then "explicit" else "follow"
                  for
                    candidates <- sharedResources.providerRegistry.getCandidatesForAgent(Some(effective))
                    (healthy, _) <- sharedResources.healthMonitor.filterCandidates(candidates)
                    current = healthy.headOption.map(c => s"${c.providerId}/${c.model}")
                    result <- Ok(
                      Json.obj(
                        "name" -> agentName.asJson,
                        "mode" -> mode.asJson,
                        "chain" -> own.asJson,
                        "effectiveChain" -> effective.asJson,
                        "resolvedFrom" -> resolvedFrom.asJson,
                        "current" -> current.asJson,
                        "settable" -> policy.isSettable(agentName).asJson
                      )
                    )
                  yield result
                  end for
            yield result
        }

      // PUT /agents/:name/model — write the agent's own model chain (the /model
      // write face; gate = SchemePolicy.SettableAgents). Body:
      // {"model": {"preferred": "...", "fallbacks": ["...", ...]}} sets an own
      // chain (Nebula = the primary chain, a follower write = a fork); null
      // model or an empty chain returns the agent to the follow state (the
      // key is removed). Takes effect on the next turn via the per-turn def
      // reload.
      case req @ PUT -> Root / "agents" / agentName / "model" =>
        withAuth(req) {
          if !isValidAgentName(agentName) then BadRequest(Json.obj("error" -> "Invalid agent name".asJson))
          else if !nebflow.core.SchemePolicy.isSettable(agentName) then
            BadRequest(
              Json.obj(
                "error" ->
                  (s"Agent '$agentName' does not accept a model chain: only "
                    + nebflow.core.SchemePolicy.SettableAgents.toList.sorted.mkString(", ")
                    + " are settable. Other agents follow the Nebula primary chain.").asJson
              )
            )
          else
            req.as[Json].flatMap { body =>
              // Accept {"model": {...}|null}; a bare {preferred, fallbacks}
              // body (no "model" key) is decoded as the chain itself.
              val parsed: Either[String, Option[nebflow.shared.AgentModelConfig]] =
                body.asObject.flatMap(_.apply("model")) match
                  case Some(m) =>
                    m.as[Option[nebflow.shared.AgentModelConfig]]
                      .left
                      .map(e => s"Invalid model chain: ${e.getMessage}")
                  case None =>
                    io.circe.parser
                      .decode[nebflow.shared.AgentModelConfig](body.noSpaces)
                      .left
                      .map(e => s"Invalid model chain: ${e.getMessage}")
                      .map(Some(_))
              parsed match
                case Left(err) => BadRequest(Json.obj("error" -> err.asJson))
                case Right(chainOpt) =>
                  // An empty chain (no preferred, no fallbacks) means follow:
                  // normalize it to key removal so agent.json carries no dead data.
                  val own = chainOpt.filter(nebflow.core.SchemePolicy.hasChain)
                  for
                    dirOpt <- EntityLoader.findAgentDir(agentName)
                    // builtin-def 批（2026-10-03）：builtin 名的 agent.json 是纯
                    // model-chain sidecar——新 home 上不存在 ⇒ 就地建最小 sidecar
                    // （{"name": ...}），再走既有 merge 写。模型链是用户设置，
                    // 依旧可写（SchemePolicy.SettableAgents 闸不变）。
                    resolvedDirOpt <- dirOpt match
                      case Some(dir) => IO.pure(Some(dir))
                      case None if nebflow.core.SchemePolicy.isSettable(agentName) =>
                        IO.blocking {
                          val dir = nebflow.shared.PathUtil.dataRoot / "agents" / agentName
                          os.makeDir.all(dir)
                          val jsonPath = dir / "agent.json"
                          if !os.exists(jsonPath) then
                            AtomicJson.writeSync(
                              jsonPath,
                              io.circe.Json.obj("name" -> agentName.asJson).noSpaces
                            )
                          Some(dir)
                        }
                      case None => IO.pure(None)
                    result <- resolvedDirOpt match
                      case Some(dir) =>
                        IO.blocking {
                          val jsonPath = dir / "agent.json"
                          io.circe.parser.parse(os.read(jsonPath)) match
                            case Right(parsedJson) =>
                              val stripped = parsedJson.asObject
                                .map(obj => Json.fromFields(obj.toMap.removed("model")))
                                .getOrElse(parsedJson)
                              val updated = own match
                                case Some(c) => stripped.deepMerge(Json.obj("model" -> c.asJson))
                                case None => stripped
                              AtomicJson.writeSync(jsonPath, updated.noSpaces)
                              true
                            case Left(_) => false
                        }.flatMap {
                          case true =>
                            val (effective, resolvedFrom) = nebflow.core.SchemePolicy.resolveModel(agentName, own)
                            val mode = if own.isDefined then "explicit" else "follow"
                            Ok(
                              Json.obj(
                                "updated" -> true.asJson,
                                "mode" -> mode.asJson,
                                "chain" -> own.asJson,
                                "effectiveChain" -> effective.asJson,
                                "resolvedFrom" -> resolvedFrom.asJson
                              )
                            )
                          case false =>
                            InternalServerError(Json.obj("error" -> "Failed to write agent.json".asJson))
                        }
                      case None =>
                        NotFound(Json.obj("error" -> s"Agent '$agentName' not found".asJson))
                  yield result
                  end for
              end match
            }
        }

      // PUT /agents/:name/model — SUPERSEDED by the chain-face arm above.
      //
      // This second PUT for the same path was dead code: the chain-face PUT is
      // mounted first, so this arm never ran (the compiler's
      // "-Werror Unreachable case" is what surfaced it). It carried the
      // pre-modelcfg shape, and its companions are gone from the merged tree —
      // the `PUT /agents/:name/preset` sibling and the `/presets` CRUD family
      // were both PR-side-only (absent from MAINPRE) and are removed with this
      // wave, as is `computeResolvedFrom`, this arm's GET-side twin, which had
      // no remaining caller at all. Two handlers for one path is a routing
      // hazard regardless of reachability (a future reorder silently flips the
      // write contract), so the wave removes the stale copy rather than leaving
      // the pair. No merged-tree functionality is lost: its shape has no live
      // consumer — `/model` panel (`web/js/modelPanel.js`) and the CLI
      // (`cli/ModelCommand.scala`) both read the chain face.

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
              s"Rejected PUT /agents/$agentName — skills/flows write-back is definition/plugin-managed"
            )
            Gone(
              Json.obj(
                "error" -> "agent skills/flows write-back is not supported: per-agent capability config is definition/plugin-managed; agent.json is not written from the panel".asJson
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
          // feishubridge: the LIVE adapter registration set (BridgeManager) feeds
          // the per-channel adapterRegistered flags; without a manager the
          // phase-1 answer (none registered) is served unchanged.
          val registered: IO[Set[String]] = sharedResources.bridgeManager match
            case Some(m) => m.registeredNames
            case None => IO.pure(Set.empty)
          registered.flatMap { set =>
            IO.blocking(nebflow.social.SocialChannels.channelsJson(PathUtil.dataRoot, set)).flatMap(json => Ok(json))
          }
        }

      // (C4) Feishu-only. All logic lives in `nebflow.social`, these cases only
      // compose it. 🔴 No response ever carries a secret — appId is an
      // identifier (displayable, same face as the scan-bind done payload);
      // secret material stays behind the `_ref` triple.
      case req @ GET -> Root / "social" / "channels" / "feishu" / "connection" =>
        withAuth(req) {
          val live: IO[Option[nebflow.social.FeishuBridgePlugin]] =
            sharedResources.bridgeManager match
              case Some(m) =>
                m.plugin(nebflow.social.FeishuBridgePlugin.Name)
                  .map(_.collect { case p: nebflow.social.FeishuBridgePlugin => p })
              case None => IO.pure(None)
          live.flatMap(p => Ok(nebflow.social.FeishuBridgePlugin.connectionJson(PathUtil.dataRoot, p)))
        }

      case req @ GET -> Root / "social" / "channels" / "feishu" / "bindings" =>
        withAuth(req) {
          sessionStore.listSessions.flatMap(metas => Ok(nebflow.social.FeishuBridgePlugin.bindingsJson(metas)))
        }

      // Body {"sessionId": "..."} sets (durable); {"sessionId": null} / a blank
      // value clears (unset = null again). Auto-bind only fires while a default
      // session is set (C4).
      case req @ PUT -> Root / "social" / "channels" / "feishu" / "default-session" =>
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
              val sid = body.hcursor
                .downField("sessionId")
                .as[Option[String]]
                .getOrElse(None)
                .map(_.trim)
                .filter(_.nonEmpty)
              IO.blocking(nebflow.social.SocialChannels.setDefaultSessionId(PathUtil.dataRoot, "feishu", sid))
                .flatMap {
                  case Right(_) => Ok(Json.obj("ok" -> true.asJson, "sessionId" -> sid.asJson))
                  case Left(err) => socialErrorResponse(err)
                }
          }
        }

      // The read leg of the pair above: the panel needs the current default
      // without a write, and `null` here means "unset" (not "unreadable").
      case req @ GET -> Root / "social" / "channels" / "feishu" / "default-session" =>
        withAuth(req) {
          IO.blocking(nebflow.social.SocialChannels.defaultSessionId(PathUtil.dataRoot, "feishu"))
            .flatMap(sid => Ok(Json.obj("sessionId" -> sid.asJson)))
        }

      // feiscanbind (2026-09-27): the scan-bind main path, on the same social
      // face and behind the same auth gate. `begin` starts a BACKGROUND fiber
      // (the SDK register call blocks — it must never sit inside the HTTP
      // response) and answers with a scanId at once; `status` is a read-only
      // registry projection (state / qrUrl / userCode / remainSec / appId /
      // error). 🔴 Neither response ever carries a secret — `done` surfaces only
      // the appId (cli_ prefix); the credential pair itself goes from the SDK
      // result through the EXISTING SocialChannels.save write path on the
      // background fiber, never through these responses or the logs.
      case req @ POST -> Root / "social" / "channels" / "feishu" / "scan-bind" / "begin" =>
        withAuth(req) {
          feishuScanBind.begin().flatMap(json => Ok(json))
        }

      case req @ GET -> Root / "social" / "channels" / "feishu" / "scan-bind" / "status" =>
        withAuth(req) {
          val scanId = req.params.getOrElse("scanId", "").trim
          if scanId.isEmpty then
            BadRequest(Json.obj("error" -> "invalid_field".asJson, "reason" -> "scanId is required".asJson))
          else
            feishuScanBind.status(scanId).flatMap {
              case Some(json) => Ok(json)
              case None => NotFound(Json.obj("error" -> "unknown_scan".asJson))
            }
        }

      // Same contract as the feishu pair: begin answers a scanId at once; the
      // status projection is read-only and never carries a credential (done
      // surfaces only the bot id; the token itself went through the EXISTING
      // SocialChannels.save write path on the background fiber).
      case req @ POST -> Root / "social" / "channels" / "weixin-ilink" / "scan-bind" / "begin" =>
        withAuth(req) {
          weixinScanBind.begin().flatMap(json => Ok(json))
        }

      case req @ GET -> Root / "social" / "channels" / "weixin-ilink" / "scan-bind" / "status" =>
        withAuth(req) {
          val scanId = req.params.getOrElse("scanId", "").trim
          if scanId.isEmpty then
            BadRequest(Json.obj("error" -> "invalid_field".asJson, "reason" -> "scanId is required".asJson))
          else
            weixinScanBind.status(scanId).flatMap {
              case Some(json) => Ok(json)
              case None => NotFound(Json.obj("error" -> "unknown_scan".asJson))
            }
        }

      // feishubridge: the session ↔ chat binding primitive. Upstream design
      // (socchannel-plan, the "which session receives a message" section):
      // bindings persist through SessionStore.updateSessionBridge and a UI
      // settings face manages them later — until that face exists this route is
      // the persistence primitive on the same social face. Body
      // {"sessionId": "...", "chatId": "oc_..."} binds; omitting chatId unbinds.
      case req @ POST -> Root / "social" / "channels" / "feishu" / "bind" =>
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
              val sessionId = body.hcursor.downField("sessionId").as[String].getOrElse("").trim
              val chatId = body.hcursor.downField("chatId").as[String].getOrElse("").trim
              if sessionId.isEmpty then
                BadRequest(
                  Json.obj(
                    "error" -> "invalid_field".asJson,
                    "reason" -> "sessionId is required".asJson
                  )
                )
              else
                val cfg = if chatId.isEmpty then None else Some(Json.obj("chat_id" -> chatId.asJson))
                sessionStore.updateSessionBridge(sessionId, "feishu", cfg) *>
                  Ok(
                    Json.obj(
                      "ok" -> true.asJson,
                      "sessionId" -> sessionId.asJson,
                      "chatId" -> chatId.asJson,
                      "bound" -> cfg.isDefined.asJson
                    )
                  )
              end if
          }
        }

      // wechat iLink batch: the ingest leg of the side-car seam. The official
      // ClawBot plugin (running attached to the host) posts ONE inbound message
      // here; the seam applies the sender gate (`allowed_ilink_user_ids`,
      // fail-closed) and injects into the bound/default session through the
      // existing `handleBridgeMessage` path. Nothing iLink-specific crosses this
      // face: no token, no cursor, no call back into the peer — the body is
      // {senderId|from_user_id, text, messageId}. Behind the same auth gate as
      // every other social arm, and the answer is the observable verdict
      // (injected + session / dropped + reason), never a secret.
      case req @ POST -> Root / "social" / "channels" / "weixin-ilink" / "ingress" =>
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
              nebflow.social.WeixinIlinkBridgePlugin.parseInbound(body) match
                case Left(reason) =>
                  BadRequest(Json.obj("error" -> "invalid_field".asJson, "reason" -> reason.asJson))
                case Right(in) =>
                  val seam: IO[Option[nebflow.social.WeixinIlinkBridgePlugin]] =
                    sharedResources.bridgeManager match
                      case Some(m) =>
                        m.plugin(nebflow.social.WeixinIlinkBridgePlugin.Name)
                          .map(_.collect { case p: nebflow.social.WeixinIlinkBridgePlugin => p })
                      case None => IO.pure(None)
                  seam.flatMap {
                    case Some(p) => p.deliver(in).flatMap(v => Ok(v.json))
                    case None =>
                      Ok(
                        nebflow.social.WeixinIlinkBridgePlugin.Verdict
                          .Dropped(nebflow.social.WeixinIlinkBridgePlugin.ReasonNotStarted)
                          .json
                      )
                  }
          }
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
                case Right(json) =>
                  // feishubridge: the config→verify→activate closed loop fires on
                  // the save path — feishu only, every other channel's save is
                  // untouched. Background fiber: the adapter's WebSocket
                  // handshake must never sit inside the HTTP response; the
                  // panel's next probe re-read picks up the flipped flag.
                  // wechat iLink batch: the same closed loop for the
                  // weixin-ilink side-car seam — a save that enables the card
                  // (or fills `allowed_ilink_user_ids`, which re-arms the gate)
                  // takes effect at once, with no restart. Same background-fiber
                  // shape and same rejection of the "assembled two ways" state.
                  val resync =
                    if channelId == "feishu" then
                      sharedResources.bridgeManager match
                        case Some(m) => nebflow.social.FeishuBridgePlugin.sync(m, PathUtil.dataRoot)
                        case None => IO.unit
                    else if channelId == nebflow.social.WeixinIlinkBridgePlugin.Name then
                      sharedResources.bridgeManager match
                        case Some(m) => nebflow.social.WeixinIlinkBridgePlugin.sync(m, PathUtil.dataRoot)
                        case None => IO.unit
                    else IO.unit
                  resync.start.void *> Ok(json)
                case Left(err) => socialErrorResponse(err)
              }
          }
        }

      case req @ GET -> Root / "social" / "probe" =>
        withAuth(req) {
          val channelId = req.params.getOrElse("channel", "")
          val registered: IO[Set[String]] = sharedResources.bridgeManager match
            case Some(m) => m.registeredNames
            case None => IO.pure(Set.empty)
          registered.flatMap { set =>
            IO.blocking(nebflow.social.SocialChannels.probeJson(PathUtil.dataRoot, channelId, set)).flatMap {
              case Right(json) => Ok(json)
              case Left(err) => socialErrorResponse(err)
            }
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
      // /agents/:name and /agents/:name/model.
      //
      // (chain-face take-down, W5 wave) The retired preset face is gone in full:
      // `PUT /agents/:name/preset` (the write face this note used to advertise)
      // together with the whole /presets CRUD family that used to be mounted
      // right here — GET /presets, POST /presets, PUT /presets/default,
      // PUT /presets/:name, DELETE /presets/:name and POST /presets/migrate-legacy.
      // Lineage and the zero-功能-loss argument are in the W5 batch report
      // (.nebflow/reports/20260928_pr48w5-impl-r2.md §退役面): the family and its
      // `PanelSchemeRoutesSpec` were retired by `0d714ef66` — a commit that is an
      // ancestor of MAINPRE — and came back only through the W1 reapply
      // (`ee1c8058c`) plus PR-side content pre-dating that retirement. The
      // per-role model chain (GET/PUT /agents/:name/model) is the live face.

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
                  configRef.get.flatMap { svcCfg =>
                    // daemonpanel Phase A: publish the panel flag ONLY when the
                    // declaration is USABLE (validated, and for `htmlFile` panels
                    // actually carryable). A daemon without a usable panel keeps
                    // its response key set byte-for-byte identical to the baseline
                    // — that is the F1 / C7 zero-regression pin (an invalid
                    // declaration is rejected whole, so its entry is hidden rather
                    // than partially rendered).
                    val flags: IO[List[Boolean]] =
                      states.toList.traverse(st => panelUsable(cfgById.get(st.id), svcCfg).map(_.isDefined))
                    flags.flatMap { flagsByIndex =>
                      val statesJson: Json = states
                        .zip(flagsByIndex)
                        .map { case (st, hasPanel) =>
                          val cfg = cfgById.get(st.id)
                          val base: Json = Json.obj(
                            "autoStart" -> cfg.exists(_.autoStart).asJson,
                            "restartOnExit" -> cfg.exists(_.restartOnExit).asJson
                          )
                          val merged: Json =
                            if hasPanel then base.deepMerge(Json.obj("hasConfigPanel" -> true.asJson)) else base
                          st.asJson.deepMerge(merged)
                        }
                        .asJson
                      Ok(Json.obj("daemons" -> statesJson))
                    }
                  }
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

      // ===== Daemon config panel (daemonpanel Phase A) =====
      //
      // Three endpoints, all `withAuth` (same discipline as the seven above).
      // The declaration is inline in daemons.json; values live outside it. A
      // declaration that fails validation hides the daemon's entry entirely
      // (fail-closed, never a partial render), and a daemon without a valid
      // declaration answers 409 `no config panel` — not 400 (F-8).
      //
      // E4 white-list note: a panel may reach ONLY {its own daemon origin} ∪
      // {these config-panel endpoints, relayed by the host}. The read-only probe
      // GET .../config-panel/credentials is the explicitly named second
      // read-only endpoint (F-9); `/api/config` and `/api/daemons` are NOT in
      // the panel white-list.

      // GET /daemons/:id/config-panel — declaration + current values (secrets masked)
      case req @ GET -> Root / "daemons" / daemonId / "config-panel" =>
        withAuth(req) {
          if daemonId == "credentials" then NotFound(Json.obj("error" -> "Daemon 'credentials' not found".asJson))
          else
            daemonPanelContext(daemonId).flatMap {
              case Left(resp) => IO.pure(resp)
              case Right((decl, _)) =>
                val store = new DaemonPanelStore()
                // F-7: a `kind:"web"` + `htmlFile` panel is carried HOST-SIDE into
                // `srcdoc` with the local <meta CSP> injected, because no URL can
                // serve an `.html` panel and a standalone document cannot be given
                // a meta by its embedder. A `url` panel stays a plain `src` (the
                // host cannot reach into a remote document at all).
                val carried: IO[Either[String, String]] =
                  decl.htmlFile.fold(IO.pure(Right("")): IO[Either[String, String]])(
                    readPanelHtml(_).map(_.map(DaemonPanelSchema.panelSrcdoc))
                  )
                carried.flatMap { carriedHtml =>
                  store.readMasked(decl, daemonId).flatMap { values =>
                    Ok(
                      Json.obj(
                        "id" -> daemonId.asJson,
                        "version" -> decl.version.asJson,
                        "kind" -> decl.kind.asJson,
                        "title" -> decl.title.asJson,
                        "fields" -> decl.fields
                          .map(f =>
                            Json
                              .obj(
                                "key" -> f.key.asJson,
                                "label" -> f.label.asJson,
                                "type" -> f.ftype.asJson,
                                "required" -> f.required.asJson
                              )
                              .deepMerge(f.min.fold(Json.obj())(m => Json.obj("min" -> m.asJson)))
                              .deepMerge(f.max.fold(Json.obj())(m => Json.obj("max" -> m.asJson)))
                              .deepMerge(
                                if f.options.nonEmpty then Json.obj("options" -> f.options.asJson) else Json.obj()
                              )
                          )
                          .asJson,
                        "values" -> values.asJson,
                        "sandbox" -> decl.sandboxTokens.asJson,
                        // Only ever a same-origin host-minted handle, NEVER a
                        // credential: the panel URL carries zero token=/ticket=.
                        "panelUrl" -> (if decl.isWeb then decl.url.getOrElse("") else "").asJson,
                        // The host-carried document with the injected local CSP.
                        // Empty unless the declaration is `kind:"web"`+`htmlFile`.
                        "srcdoc" -> carriedHtml.getOrElse("").asJson,
                        // Explicitly report whether the policy actually landed, so
                        // "the CSP is present" is an assertion on the wire rather
                        // than an inference from the client's markup.
                        "cspInjected" -> carriedHtml.exists(DaemonPanelSchema.hasPanelCsp).asJson
                      )
                    )
                  }
                }
            }
        }

      // GET /daemons/:id/config-panel/credentials — read-only credential probe
      case req @ GET -> Root / "daemons" / daemonId / "config-panel" / "credentials" =>
        withAuth(req) {
          daemonPanelContext(daemonId).flatMap {
            case Left(resp) => IO.pure(resp)
            case Right((decl, _)) =>
              val store = new DaemonPanelStore()
              store.credentialStates(decl, daemonId).flatMap { states =>
                Ok(
                  Json.obj(
                    "id" -> daemonId.asJson,
                    "credentials" -> states
                      .map(s =>
                        Json
                          .obj("key" -> s.key.asJson, "name" -> s.name.asJson, "state" -> s.state.asJson)
                          .deepMerge(s.mode.fold(Json.obj())(m => Json.obj("mode" -> m.asJson)))
                      )
                      .asJson
                  )
                )
              }
          }
        }

      // PUT /daemons/:id/config-panel — write values
      case req @ PUT -> Root / "daemons" / daemonId / "config-panel" =>
        withAuth(req) {
          daemonPanelContext(daemonId).flatMap {
            case Left(resp) => IO.pure(resp)
            case Right((decl, _)) =>
              req.as[Json].flatMap { body =>
                val raw = body.hcursor.downField("values").focus.getOrElse(body)
                raw.asObject match
                  case None => BadRequest(Json.obj("error" -> "invalid value: expected a 'values' object".asJson))
                  case Some(obj) =>
                    val store = new DaemonPanelStore()
                    val incoming = obj.toMap
                    store.writeValues(decl, daemonId, incoming).flatMap {
                      case Left(err) =>
                        // "unknown field"/"invalid value" are client errors; a
                        // credential-permission refusal is also 400 with a reason
                        // (never a silent success, never a 200 on a failed write).
                        if err.startsWith("unknown field") then
                          BadRequest(Json.obj("error" -> "unknown field".asJson, "detail" -> err.asJson))
                        else BadRequest(Json.obj("error" -> "invalid value".asJson, "detail" -> err.asJson))
                      case Right(_) =>
                        store.readMasked(decl, daemonId).flatMap { values =>
                          Ok(Json.obj("id" -> daemonId.asJson, "saved" -> true.asJson, "values" -> values.asJson))
                        }
                    }
                end match
              }
          }
        }
    }
  end routes

  // 以下助手(F 步 2026-09-24 自 RestApiRoutes 类内逐字迁入,调用面全部在本
  // object 的 routes 内):teams/plugins 校验与写回族 + social 渠道错误
  // 映射 + provider baseUrl SSRF 闸。可见性 private 原样(迁入 object 后仅本
  // 域可见,与原类内 private 等价)。

  // ── Preset helpers ── RETIRED with the preset face (chain-face take-down) ──
  //
  // The /presets CRUD family and the `PUT /agents/:name/preset` sibling are gone
  // (see the take-down note in `routes`), and their five helpers went with them:
  // `allAgentJsonFiles` / `scanAgentPresets` / `scrubPresetRefs` were reached
  // only by GET /presets and DELETE /presets/:name; `computeResolvedFrom` was the
  // flat /model read shape's resolution label with no caller left after the
  // chain-face GET replaced it; `migrateLegacyModels` was the body of
  // POST /presets/migrate-legacy. All five were PR-side-only — MAINPRE carries
  // no `nebflow.core.presets` package and no `/presets` arm at all (see the W5
  // batch report §退役面 for the four-tree readings) — so removing them is the
  // zero-功能-loss terminal disposition of the retired face, never a feature
  // removal. The live face is the per-role model chain
  // (GET/PUT /agents/:name/model), which reads agent.json directly through
  // `SchemePolicy`.

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
