package nebflow.neblink

import cats.effect.{Deferred, IO, Ref}
import io.circe.generic.semiauto.*
import io.circe.parser.decode
import io.circe.syntax.*
import io.circe.{Decoder, Encoder, Json, JsonObject}
import nebflow.core.NebflowLogger

import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.net.{NetworkInterface, URI}

import scala.jdk.CollectionConverters.*

/** NebLink Server configuration. */
case class NeblinkServerConfig(
  url: String, // e.g. "http://192.168.1.200:9090"
  networkId: String,
  secret: String,
  /**
   * Long-lived per-device credential (pairing-code enrollment). When present,
   * the client authenticates via `/api/device/session` instead of the legacy
   * shared-secret `/api/device/login`.
   */
  deviceToken: Option[String] = None
)

object NeblinkServerConfig:
  given Encoder[NeblinkServerConfig] = deriveEncoder

  given Decoder[NeblinkServerConfig] = Decoder.instance { c =>
    for
      url <- c.downField("url").as[Option[String]].flatMap {
        case Some(u) => Right(u)
        case None => c.downField("server").as[String] // backward compat
      }
      networkId <- c.downField("networkId").as[String]
      secret <- c.downField("secret").as[Option[String]].map(_.getOrElse(""))
      deviceToken <- c.downField("deviceToken").as[Option[String]]
    yield NeblinkServerConfig(url, networkId, secret, deviceToken)
  }

// ===== Internal types (matching NebLink Server's JSON response format) =====

private[neblink] case class NeblinkEndpoint(address: String, port: Int, kind: String, label: String = "")

private[neblink] case class NeblinkPeerInfo(
  deviceId: String,
  deviceName: String,
  platform: String,
  endpoints: List[NeblinkEndpoint],
  online: Boolean
)

private case class LoginResponse(token: String, networkId: String, deviceId: String, peers: List[NeblinkPeerInfo])
private case class HeartbeatResponse(peers: List[NeblinkPeerInfo])

// ===== Circe Decoders =====

object NeblinkCodecs:

  given Decoder[NeblinkEndpoint] = Decoder.instance { c =>
    for
      address <- c.downField("address").as[String]
      port <- c.downField("port").as[Int]
      kind <- c.downField("kind").as[String]
      label <- c.downField("label").as[Option[String]].map(_.getOrElse(""))
    yield NeblinkEndpoint(address, port, kind, label)
  }

  given Decoder[NeblinkPeerInfo] = Decoder.instance { c =>
    for
      deviceId <- c.downField("deviceId").as[String]
      deviceName <- c.downField("deviceName").as[String]
      platform <- c.downField("platform").as[String]
      endpoints <- c.downField("endpoints").as[Option[List[NeblinkEndpoint]]].map(_.getOrElse(Nil))
      online <- c.downField("online").as[Option[Boolean]].map(_.getOrElse(true))
    yield NeblinkPeerInfo(deviceId, deviceName, platform, endpoints, online)
  }

  given Decoder[LoginResponse] = Decoder.instance { c =>
    for
      token <- c.downField("token").as[String]
      networkId <- c.downField("networkId").as[String]
      deviceId <- c.downField("deviceId").as[String]
      peers <- c.downField("peers").as[Option[List[NeblinkPeerInfo]]].map(_.getOrElse(Nil))
    yield LoginResponse(token, networkId, deviceId, peers)
  }

  given Decoder[HeartbeatResponse] = Decoder.instance { c =>
    c.downField("peers").as[Option[List[NeblinkPeerInfo]]].map(_.getOrElse(Nil)).map(HeartbeatResponse.apply)
  }
end NeblinkCodecs

import NeblinkCodecs.{given, *}
import FriendCodecs.{given, *}

/**
 * Client for the NebLink Server.
 * Handles device login, periodic heartbeat, and peer discovery.
 * NebLink Server is the discovery mechanism.
 *
 * @param onDeviceTokenRejected Logto silent re-login hook (stage 2): invoked
 *   when the server rejects the long-lived deviceToken (HTTP 401 on the
 *   session exchange). Performs refresh_token → register → persist + hot-swap
 *   and returns the NEW device token; the client swaps it in and retries the
 *   login ONCE. None (no hook) or a None result surfaces the original error —
 *   the frontend then prompts a fresh login.
 * @param identity Device identity source for the API-level session self-heal
 *   (2026-09-10 friend-search incident): withSession requests that hit a
 *   server-side auth rejection (401, or the "Missing or invalid token" 403 —
 *   one-live-session-per-(device,network) kicks the session on every fresh
 *   login/enrollment on this device) trigger ONE silent re-login + replay.
 *   None disables the heal (tests / pure-transport construction sites).
 */
class NeblinkClient(
  config: NeblinkServerConfig,
  serverPort: Int,
  onDeviceTokenRejected: Option[IO[Option[String]]] = None,
  identity: Option[IO[DeviceIdentity]] = None
):
  private val logger = NebflowLogger.forName("nebflow.neblink.client")

  // HTTP client: force HTTP/1.1 and bypass system proxy (direct LAN/WAN access).
  // HTTP/1.1 avoids the JDK HttpClient's HTTP/2 connection-reuse + TLS 1.3
  // session resumption clash with the Caddy reverse proxy in front of
  // neblink.nebflow.space (connect timeouts / bad_record_mac TLS alerts).
  private val httpClient = HttpClient
    .newBuilder()
    .version(HttpClient.Version.HTTP_1_1)
    .proxy(java.net.ProxySelector.of(null))
    .connectTimeout(java.time.Duration.ofSeconds(15))
    .build()

  @volatile private var sessionToken: Option[String] = None

  /** Active long-lived device credential — starts from config, replaced by
    * the silent re-login hook when the server rejects the old one. */
  @volatile private var activeDeviceToken: Option[String] = config.deviceToken

  /** Expose current session token for NeblinkRelayTunnel (live read on each reconnect). */
  def currentSessionToken: Option[String] = sessionToken

  /**
   * Detect local IPv4 addresses for endpoint reporting to the NebLink Server.
   *
   * Filters out virtual NICs (Hyper-V, WSL2, Docker, VMware, VirtualBox) whose
   * addresses are unreachable from the real LAN — e.g. `192.168.121.1` on a
   * Windows `vEthernet (WSL)` adapter. If filtering would remove every
   * endpoint, falls back to the unfiltered list so P2P still has a chance.
   */
  def detectLocalEndpoints: IO[List[NeblinkEndpoint]] = IO.blocking {
    try
      val allNics = NetworkInterface.getNetworkInterfaces.asScala.toList
        .filter(_.isUp)
        .filterNot(_.isLoopback)

      def toEndpoints(nics: List[NetworkInterface]): List[NeblinkEndpoint] =
        nics.flatMap(_.getInetAddresses.asScala)
          .filter(_.isInstanceOf[java.net.Inet4Address])
          .map(addr => NeblinkEndpoint(addr.getHostAddress, serverPort, "lan", ""))

      val physical = allNics.filterNot(isLikelyVirtualNic)
      val preferred = toEndpoints(physical)
      if preferred.nonEmpty then preferred else toEndpoints(allNics) // never lose all endpoints
    catch case _: Exception => Nil
  }

  /**
   * Heuristic virtual-NIC detector. `NetworkInterface.isVirtual()` is
   * unreliable across OSes (returns false for Hyper-V/WSL on Windows), so we
   * combine it with name-pattern matching against the common virtual adapter
   * display names. Conservative: only flags well-known virtual markers.
   */
  private def isLikelyVirtualNic(nic: NetworkInterface): Boolean =
    val names = Seq(nic.getDisplayName, nic.getName).map(n => Option(n).getOrElse("").toLowerCase)
    val virtualMarkers =
      Seq("virtual", "vethernet", "vmware", "docker", "wsl", "hyper-v", "virtualbox", "loopback pseudo")
    nic.isVirtual || names.exists(n => virtualMarkers.exists(n.contains))

  /**
   * Login to NebLink Server. Stores session token. Returns initial peer list.
   *
   * If a per-device credential (`deviceToken`) is configured (pairing-code
   * enrollment), authenticates via `/api/device/session`. Otherwise falls
   * back to the legacy shared-secret `/api/device/login`.
   */
  def login(
    deviceId: String,
    deviceName: String,
    platform: String,
    endpoints: List[NeblinkEndpoint]
  ): IO[Either[String, List[NeblinkPeerInfo]]] =
    doLogin(deviceId, deviceName, platform, endpoints, allowRelogin = true)

  private def doLogin(
    deviceId: String,
    deviceName: String,
    platform: String,
    endpoints: List[NeblinkEndpoint],
    allowRelogin: Boolean
  ): IO[Either[String, List[NeblinkPeerInfo]]] =
    for
      localEndpoints <- if endpoints.isEmpty then detectLocalEndpoints else IO.pure(endpoints)
      endpointJson = localEndpoints.map { e =>
        Json.obj(
          "address" -> e.address.asJson,
          "port" -> e.port.asJson,
          "kind" -> e.kind.asJson
        )
      }
      // Choose path + body based on whether we have a device credential.
      (path, body) = activeDeviceToken match
        case Some(token) =>
          (
            Protocol.DeviceApi.session,
            Json
              .obj(
                "networkId" -> config.networkId.asJson,
                "deviceId" -> deviceId.asJson,
                "deviceToken" -> token.asJson,
                "deviceName" -> deviceName.asJson,
                "platform" -> platform.asJson,
                "endpoints" -> endpointJson.asJson
              )
              .noSpaces
          )
        case None =>
          (
            Protocol.DeviceApi.login,
            Json
              .obj(
                "networkId" -> config.networkId.asJson,
                "secret" -> config.secret.asJson,
                "deviceId" -> deviceId.asJson,
                "deviceName" -> deviceName.asJson,
                "platform" -> platform.asJson,
                "endpoints" -> endpointJson.asJson
              )
              .noSpaces
          )
      result <- sendRequest("POST", s"${config.url}$path", body, None).flatMap {
        case Right(respBody) =>
          decode[LoginResponse](respBody) match
            case Right(login) =>
              IO { sessionToken = Some(login.token) } *>
                logger.info(s"Logged into NebLink Server: ${login.peers.size} peer(s)").as(Right(login.peers))
            case Left(err) =>
              logger
                .warn(s"NebLink Server login decode error: ${err.getMessage}")
                .as(Left(s"Decode error: ${err.getMessage}"))
        // Stage 2: the server rejected the long-lived deviceToken (revoked /
        // rotated out) — attempt ONE silent Logto re-login, then retry the
        // session exchange with the fresh token. NeblinkClient.reloginAllowed
        // is the ONLY route to the hook: the retry call passes
        // `allowRelogin = false`, so a repeated 401 can never re-enter the
        // hook (single-shot anti-loop guarantee — spec'd + mutation-nailed in
        // NeblinkClientReloginSpec; an unbounded loop would re-send the login
        // and re-run the refresh-token rotation forever).
        case Left(err) =>
          if NeblinkClient.reloginAllowed(err, allowRelogin) then
            onDeviceTokenRejected match
              case None => IO.pure(Left(err))
              case Some(hook) =>
                logger.info("Device token rejected by NebLink Server — attempting Logto silent re-login") *>
                  hook.flatMap { newToken =>
                    NeblinkClient.reloginOutcome(newToken, err) match
                      case Right(fresh) =>
                        IO { activeDeviceToken = Some(fresh) } *>
                          doLogin(deviceId, deviceName, platform, endpoints, allowRelogin = false)
                      case Left(original) =>
                        logger
                          .warn("Logto silent re-login unavailable — surfacing login required")
                          .as(Left(original))
                  }
          else IO.pure(Left(err))
      }
    yield result

  /** Send heartbeat and get updated peer list. Requires prior login.
   *
   * Re-detects local endpoints on every heartbeat so the server sees our current
   * IP — after a network change (e.g. laptop moved to a different Wi-Fi), the
   * server's stored address becomes stale and peers can't reach us via P2P.
   * Including endpoints in the body lets the server update our address even
   * when it doesn't change (idempotent).
   */
  def heartbeat: IO[Either[String, List[NeblinkPeerInfo]]] =
    sessionToken match
      case None => IO.pure(Left("Not logged in"))
      case Some(token) =>
        detectLocalEndpoints.flatMap { endpoints =>
          val body = Json.obj(
            "endpoints" -> endpoints.map { e =>
              Json.obj("address" -> e.address.asJson, "port" -> e.port.asJson, "kind" -> e.kind.asJson)
            }.asJson,
            "version" -> nebflow.Version.string.asJson
          ).noSpaces
          sendRequest("POST", s"${config.url}/api/device/heartbeat", body, Some(token)).flatMap {
            case Right(respBody) =>
              decode[HeartbeatResponse](respBody) match
                case Right(hb) => IO.pure(Right(hb.peers))
                case Left(err) => IO.pure(Left(s"Decode error: ${err.getMessage}"))
            case Left(err) => IO.pure(Left(err))
          }
        }

  /**
   * Smart discovery: login if needed, otherwise heartbeat.
   * If heartbeat fails (token expired), re-login automatically.
   */
  def discover(
    deviceId: String,
    deviceName: String,
    platform: String,
    endpoints: List[NeblinkEndpoint]
  ): IO[Either[String, List[NeblinkPeerInfo]]] =
    sessionToken match
      case None => login(deviceId, deviceName, platform, endpoints)
      case Some(_) =>
        heartbeat.flatMap {
          case Right(peers) => IO.pure(Right(peers))
          case Left(err) =>
            logger.info(s"Heartbeat failed ($err), re-logging in...") *>
              IO { sessionToken = None } *>
              login(deviceId, deviceName, platform, endpoints)
        }

  /** Logout from NebLink Server. */
  def logout: IO[Unit] =
    sessionToken match
      case None => IO.unit
      case Some(token) =>
        sendRequest("DELETE", s"${config.url}/api/device/logout", "", Some(token))
          .handleErrorWith(_ => IO.unit)
          *> IO { sessionToken = None }

  // ===== A2A 好友与消息 API（spec §6.1，Bearer device session token） =====

  /** 查号：精确匹配 neblink_id（默认=邮箱），大小写不敏感。 */
  def lookupUser(q: String): IO[Either[String, Json]] =
    withSession { token =>
      sendRequest("GET", s"${config.url}/api/users/lookup?q=${java.net.URLEncoder.encode(q, "UTF-8")}", "", Some(token))
        .map(_.flatMap(body => decode[Json](body).left.map(_.getMessage)))
    }

  /** 搜索：username OR email 双键 NOCASE 精确（neblink-server /api/users/search，
    * friend-search-contract §4.1——唯一搜索入口，替代已移除的 /api/users/lookup）。
    * 命中 {found:true,user:{username,display_name,avatar},relation_status}；
    * 未命中统一 {found:false}；>256 字符 422 invalid_query、超频 429 —— 均由上游折叠，
    * 网关透传不变形。 */
  def searchUser(q: String): IO[Either[String, Json]] =
    withSession { token =>
      sendRequest("GET", s"${config.url}/api/users/search?q=${java.net.URLEncoder.encode(q, "UTF-8")}", "", Some(token))
        .map(_.flatMap(body => decode[Json](body).left.map(_.getMessage)))
    }

  /** [U3] 自定义 NebLink 号（PUT /api/users/me/neblink-id）。200 {neblinkId} /
    * 上游 409 taken / 422 invalid 折叠为 Left("HTTP <code>: <body>")。 */
  def setNeblinkId(neblinkId: String): IO[Either[String, Json]] =
    withSession { token =>
      sendRequest("PUT", s"${config.url}/api/users/me/neblink-id",
        Json.obj("neblinkId" -> neblinkId.asJson).noSpaces, Some(token))
        .map(_.flatMap(body => decode[Json](body).left.map(_.getMessage)))
    }

  /** [U3] 号可用性实时检测（GET /api/users/me/neblink-id/available?q=）。
    * 200 {available, reason?}（reason: taken | invalid）；限速与查号同桶。 */
  def neblinkIdAvailable(q: String): IO[Either[String, Json]] =
    withSession { token =>
      sendRequest("GET", s"${config.url}/api/users/me/neblink-id/available?q=${java.net.URLEncoder.encode(q, "UTF-8")}", "", Some(token))
        .map(_.flatMap(body => decode[Json](body).left.map(_.getMessage)))
    }

  /** 好友列表（accepted + 双向 pending 分组）。 */
  def listFriends: IO[Either[String, FriendListResponse]] =
    withSessionJson[FriendListResponse]("GET", "/api/friends", "")

  /** 发好友请求（按 NebLink 号寻址，服务器 get-or-create 会话于首条消息）。 */
  def sendFriendRequest(query: String, note: Option[String] = None): IO[Either[String, Json]] =
    withSession { token =>
      val body = Json.obj("query" -> query.asJson, "note" -> note.asJson).noSpaces
      sendRequest("POST", s"${config.url}/api/friends/requests", body, Some(token))
        .map(_.flatMap(resp => decode[Json](resp).left.map(_.getMessage)))
    }

  def acceptFriendRequest(requestId: String): IO[Either[String, Json]] =
    withSessionJson[Json]("POST", s"/api/friends/requests/$requestId/accept", "")

  def declineFriendRequest(requestId: String): IO[Either[String, String]] =
    withSessionRaw("POST", s"/api/friends/requests/$requestId/decline", "")

  def removeFriend(friendUserId: String): IO[Either[String, String]] =
    withSessionRaw("DELETE", s"/api/friends/$friendUserId", "")

  /** 拉黑好友（#290 §1.2 WeChat 式黑名单；上游 upsert 到 blocked）。 */
  def blockFriend(friendUserId: String): IO[Either[String, String]] =
    withSessionRaw("POST", s"/api/friends/$friendUserId/block", "")

  /** 移出黑名单（仅拉黑方；上游非拉黑方 403 not_blocker）。 */
  def unblockFriend(friendUserId: String): IO[Either[String, String]] =
    withSessionRaw("POST", s"/api/friends/$friendUserId/unblock", "")

  /** 会话列表（按 last_message_id 倒序，含 unreadCount）。 */
  def listConversations: IO[Either[String, List[ConversationSummary]]] =
    withSessionJson[List[ConversationSummary]]("GET", "/api/conversations", "")

  /** keyset 分页拉消息（after=0 全量，limit 默认 50）。 */
  def listMessages(conversationId: String, after: Long = 0L, limit: Int = 50): IO[Either[String, List[MessageSummary]]] =
    withSessionJson[List[MessageSummary]]("GET", s"/api/conversations/$conversationId/messages?after=$after&limit=$limit", "")

  /** 发消息（好友寻址，服务器 get-or-create 会话）。
    *
    * `origin` 透传给服务器落库（#290 spec v1.1 wire 契约：缺省 "user"，
    * 非法值 422）。agent 代发链（FriendService.sendAsAgent → doSend）必须
    * 传 Some("agent")——否则 agent 发的消息被标成 user，origin 语义
    * （前端徽章/审计/spec §7.2 限速区分）整体失效。
    */
  def sendFriendMessage(friendUserId: String, body: String, origin: Option[String] = None): IO[Either[String, Json]] =
    withSession { token =>
      val payload = origin match
        case Some(o) => Json.obj("body" -> body.asJson, "origin" -> o.asJson).noSpaces
        case None    => Json.obj("body" -> body.asJson).noSpaces
      sendRequest("POST", s"${config.url}/api/friends/$friendUserId/messages", payload, Some(token))
        .map(_.flatMap(resp => decode[Json](resp).left.map(_.getMessage)))
    }

  /** 更新未读 cursor（仅本地角标口径，无回执）。 */
  def markConversationRead(conversationId: String, lastReadMessageId: Long): IO[Either[String, String]] =
    withSessionRaw("POST", s"/api/conversations/$conversationId/read", s"""{"lastReadMessageId":$lastReadMessageId}""")

  // ---- helpers ----

  private def withSession[A](f: String => IO[Either[String, A]]): IO[Either[String, A]] =
    withSessionPlain(f).flatMap {
      case Left(err) if NeblinkClient.sessionRecoverable(err) => selfHeal(err, f)
      case other                                              => IO.pure(other)
    }

  private def withSessionPlain[A](f: String => IO[Either[String, A]]): IO[Either[String, A]] =
    sessionToken match
      case None => IO.pure(Left("Not logged in"))
      case Some(token) => f(token)

  // ===== API-level session self-heal (2026-09-10 friend-search incident) =====
  //
  // Server-side one-live-session-per-(device, network) means any fresh
  // login/enrollment on this device KICKS the current session (store.rs
  // kick_sessions_where). The kicked session then 403s "Missing or invalid
  // token" forever: the discover() heartbeat re-login loop only runs on the
  // discovery-held client, so plain API consumers (FriendService search/list)
  // had no recovery path — the gateway had to be restarted (F2 of the
  // incident report; F1 unifies the client reference on top of this).
  //
  // Trigger is deliberately NARROW: a re-login kicks our own previous session
  // server-side, so business 403s (not_blocker etc.) must never enter this
  // path — only 401 or the server's auth-rejection 403 body shape qualifies.
  //
  // Anti-loop: the replay goes through withSessionPlain, NOT withSession —
  // a fresh 401/403 after healing surfaces as-is (per-request single shot,
  // mirroring doLogin's allowRelogin = false pattern).

  /** Single-flight gate: N concurrent auth rejections must produce exactly ONE
    * re-login (each login kicks the previous session server-side — racing
    * logins would kick each other in a loop). Losers await the winner's
    * Deferred and retry with whatever session it produced. */
  private val reloginGate: Ref[IO, Option[Deferred[IO, Boolean]]] = Ref.unsafe(None)

  private def selfHeal[A](err: String, f: String => IO[Either[String, A]]): IO[Either[String, A]] =
    Deferred[IO, Boolean].flatMap { mine =>
      reloginGate
        .modify {
          case None          => (Some(mine), Left(mine))
          case Some(winner)  => (Some(winner), Right(winner))
        }
        .flatMap {
          case Left(won) =>
            // I won the race: re-login exactly once, publish the outcome, and
            // clear the gate only if it is still mine (a newer winner may have
            // replaced it while my login was in flight).
            val run = silentRelogin.flatMap(ok => won.complete(ok).as(ok))
            run.guarantee(reloginGate.update {
              case Some(g) if g eq won => None
              case other               => other
            }).flatMap {
              case true  => replayAfterHeal(f, err)
              case false => IO.pure(Left(err))
            }
          case Right(winner) =>
            winner.get.flatMap {
              case true  => replayAfterHeal(f, err)
              case false => IO.pure(Left(err))
            }
        }
    }

  /** One replay attempt with the (possibly refreshed) session token. Reads the
    * token live so a re-login that swapped it in is picked up; no session
    * means the heal didn't produce one — surface the ORIGINAL error. */
  private def replayAfterHeal[A](f: String => IO[Either[String, A]], originalErr: String): IO[Either[String, A]] =
    sessionToken match
      case None         => IO.pure(Left(originalErr))
      case Some(token)  => f(token)

  /** Silent re-login for the self-heal path. Uses the full public login chain
    * (allowRelogin = true): the kicked-session case re-exchanges the still
    * valid deviceToken; the revoked-deviceToken case additionally runs the
    * Logto hook. Never throws — failures map to false. */
  private def silentRelogin: IO[Boolean] =
    identity match
      case None => IO.pure(false)
      case Some(getId) =>
        logger.warn("NebLink session rejected by server (401/403 auth) — attempting silent re-login") *>
          getId.flatMap { id =>
            login(id.deviceId, id.deviceName, id.platform, Nil).flatMap {
              case Right(_)  => IO.pure(true)
              case Left(e)   => logger.warn(s"NebLink silent re-login failed: $e").as(false)
            }
          }.handleErrorWith(e => logger.warn(s"NebLink silent re-login errored: ${e.getMessage}").as(false))

  private def withSessionRaw(method: String, path: String, body: String): IO[Either[String, String]] =
    withSession(token => sendRequest(method, s"${config.url}$path", body, Some(token)))

  private def withSessionJson[A](method: String, path: String, body: String)(using d: io.circe.Decoder[A]): IO[Either[String, A]] =
    withSessionRaw(method, path, body).map(_.flatMap(resp => decode[A](resp).left.map(_.getMessage)))

  /**
   * Execute a tool on a remote device via the NebLink Server relay tunnel.
   * Used as fallback when P2P direct connection is unreachable (cross-network).
   * The request goes: this device -> Server -> target device's WS tunnel.
   */
  def relayExec(targetDeviceId: String, action: String, params: JsonObject): IO[Either[String, String]] =
    sessionToken match
      case None => IO.pure(Left("Not logged in"))
      case Some(token) =>
        val body = Json.obj(
          "action" -> action.asJson,
          "params" -> params.asJson,
          "projectRoot" -> System.getProperty("user.dir", ".").asJson
        ).noSpaces
        sendRequest("POST", s"${config.url}/api/relay/$targetDeviceId/exec", body, Some(token)).flatMap {
          case Right(respBody) =>
            decode[Json](respBody) match
              case Right(json) =>
                val output = json.hcursor.downField("output").as[String].getOrElse("")
                val error = json.hcursor.downField("error").as[String].getOrElse("")
                if error.nonEmpty then IO.pure(Left(error))
                else IO.pure(Right(output))
              case Left(err) => IO.pure(Left(s"Decode error: ${err.getMessage}"))
          case Left(err) => IO.pure(Left(err))
        }

  /** Convert NebLink Server peers to neblink PeerInfo. Picks first endpoint as address.
   *
   * Presence v2: a peer the server explicitly flags online=false (lazy TTL
   * judgement) maps to lastSeen=0 — non-fresh, so the C3 freshness predicate
   * reports it offline. Without this mapping, an offline-flagged device would
   * refresh itself back to "online" on every heartbeat just by appearing in
   * the response, fighting the DeviceStatusUpdate push (C6). */
  def toNeblinkPeers(serverPeers: List[NeblinkPeerInfo]): List[PeerInfo] =
    serverPeers.filter(_.endpoints.nonEmpty).map { p =>
      val ep = p.endpoints.head
      PeerInfo(
        deviceId = p.deviceId,
        deviceName = p.deviceName,
        platform = p.platform,
        address = s"http://${ep.address}:${ep.port}",
        lastSeen = if p.online then System.currentTimeMillis() else 0L
      )
    }

  /** Extract all peer IP addresses from NebLink Server peer list. */
  def peerAddresses(serverPeers: List[NeblinkPeerInfo]): Set[String] =
    serverPeers.flatMap(_.endpoints.map(_.address)).toSet

  // ===== Relay file transfer (FileTransfer action) =====

  /** Pull a file from a remote device via relay. Returns (base64 content, size). */
  def relayTransferGet(targetDeviceId: String, path: String): IO[Either[String, (String, Long)]] =
    val params = JsonObject("direction" -> "get".asJson, "path" -> path.asJson)
    relayExec(targetDeviceId, "FileTransfer", params).flatMap {
      case Right(output) =>
        decode[Json](output) match
          case Right(json) =>
            val contentB64 = json.hcursor.downField("content").as[String].getOrElse("")
            val size = json.hcursor.downField("size").as[Long].getOrElse(0L)
            if contentB64.isEmpty then IO.pure(Left("Empty response"))
            else IO.pure(Right((contentB64, size)))
          case Left(err) => IO.pure(Left(s"Decode error: ${err.getMessage}"))
      case Left(err) => IO.pure(Left(err))
    }

  /** Push a file to a remote device via relay. Returns size in bytes. */
  def relayTransferPut(
    targetDeviceId: String,
    path: String,
    contentB64: String,
    overwrite: Boolean
  ): IO[Either[String, Long]] =
    val params = JsonObject(
      "direction" -> "put".asJson,
      "path" -> path.asJson,
      "content" -> contentB64.asJson,
      "overwrite" -> overwrite.asJson
    )
    relayExec(targetDeviceId, "FileTransfer", params).flatMap {
      case Right(output) =>
        decode[Json](output) match
          case Right(json) =>
            val size = json.hcursor.downField("size").as[Long].getOrElse(0L)
            IO.pure(Right(size))
          case Left(err) => IO.pure(Left(s"Decode error: ${err.getMessage}"))
      case Left(err) => IO.pure(Left(err))
    }

  /** Send a notification message to a remote device via relay (Notify action). */
  def relayNotify(targetDeviceId: String, channel: String, payload: Json): IO[Either[String, String]] =
    val params = JsonObject(
      "channel" -> channel.asJson,
      "payload" -> payload
    )
    relayExec(targetDeviceId, "Notify", params)

  /** Trigger a remote update on a device via relay (RemoteUpdate action). */
  def relayUpdate(targetDeviceId: String, beta: Boolean): IO[Either[String, String]] =
    val params = JsonObject("beta" -> beta.asJson)
    relayExec(targetDeviceId, "RemoteUpdate", params)

  // ===== Private helpers =====

  /**
   * HTTP transport seam. `protected` (not private) so NeblinkClientReloginSpec's
   * stub subclass can inject a canned transport and observe the full retry
   * chain — hook entry count + retry-request body — without real sockets.
   */
  protected def sendRequest(
    method: String,
    url: String,
    body: String,
    token: Option[String]
  ): IO[Either[String, String]] =
    IO.blocking {
      try
        val builder = HttpRequest
          .newBuilder()
          .uri(URI.create(url))
          .timeout(java.time.Duration.ofSeconds(10))
        token.foreach(t => builder.header("Authorization", s"Bearer $t"))
        if method == "POST" then
          builder.header("Content-Type", "application/json")
          if body.nonEmpty then builder.POST(HttpRequest.BodyPublishers.ofString(body))
          else builder.POST(HttpRequest.BodyPublishers.noBody())
        else if method == "PUT" then
          builder.header("Content-Type", "application/json")
          builder.PUT(HttpRequest.BodyPublishers.ofString(body))
        else if method == "DELETE" then builder.DELETE()
        else builder.GET()
        val request = builder.build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if response.statusCode() >= 200 && response.statusCode() < 300 then Right(response.body())
        else Left(s"HTTP ${response.statusCode()}: ${response.body()}")
        catch
          case e: Exception =>
            // Some JDK-native exceptions (e.g. bare ConnectException) carry a
            // null message — fall back to toString so downstream Left values
            // never NPE on encoding/logging.
            Left(if e.getMessage == null then e.toString else e.getMessage)
    }.handleErrorWith(e => IO.pure(Left(e.getMessage)))

end NeblinkClient

object NeblinkClient:

  /** HTTP 401 marker from sendRequest's `HTTP <code>: <body>` error shape. */
  private[neblink] def isUnauthorized(err: String): Boolean =
    err.startsWith("HTTP 401")

  /**
   * Self-heal trigger for withSession requests (2026-09-10 friend-search
   * incident, F2). Narrow by design: a re-login kicks our own previous
   * session server-side (one-live-session policy), so ONLY server auth
   * rejections may enter the heal path —
   *  - 401: unconditional (unauthorized = session/token problem);
   *  - 403: only when the body carries the server's token-rejection shape
   *    ("Missing or invalid token", friends.rs require_user_or_device).
   *    Business 403s ("not_blocker", "not_friend", ...) contain no "token"
   *    and stay untouched.
   */
  private[neblink] def sessionRecoverable(err: String): Boolean =
    isUnauthorized(err) || (err.startsWith("HTTP 403") && err.toLowerCase.contains("token"))

  /**
   * Phase-1 gate for the deviceToken-rejected path — the ONLY route to the
   * silent re-login hook (single call site in doLogin, grep-audited).
   *
   * The single-shot anti-loop guarantee lives here: `allowRelogin` is true
   * only on the first attempt; the post-relogin retry passes false, so a
   * fresh 401 can never re-enter the hook. An unbounded retry loop would
   * re-send the full login request and re-run the refresh-token rotation
   * forever, burning provider tokens. Regression nails (NeblinkClientReloginSpec):
   * pure-gate quadrants + full-chain single-shot + mutation red for
   * both the gate check and the `allowRelogin = false` call-site constant.
   */
  private[neblink] def reloginAllowed(err: String, allowRelogin: Boolean): Boolean =
    allowRelogin && isUnauthorized(err)

  /**
   * Phase-2 decision after the hook ran: a fresh token retries the session
   * exchange exactly once; None (hook not configured / unavailable / failed)
   * surfaces the ORIGINAL error so the frontend prompts a fresh login.
   */
  private[neblink] def reloginOutcome(hookResult: Option[String], originalErr: String): Either[String, String] =
    hookResult.toRight(originalErr)

end NeblinkClient
