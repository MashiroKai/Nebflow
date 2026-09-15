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
 * @param autoLoginParked 踢下线停摆位的**自动登录门**（2026-09-14 踢旧批 r2，
 *   作者 17:07 裁定 C+B·客户端一刀）。默认 `IO.pure(false)`（未停摆 ⇒ 零行为变化）。
 *   生产装配点传 `IO(ms.kickParked)`。停摆期本客户端**不发任何登录/会话交换请求**
 *   ——被服务端 `disconnect` 踢下线后，自动重登录会连带触发服务端 register
 *   （kick-on-re-enroll），那正是复核位判 fail 的那条腿。唯一解除口 = 用户显式登录
 *   （`NeblinkEnrollment.persist(explicitUserAction = true)` 清停摆位）。
 */
/** E3 下载响应（4b 腿 A-3）：**状态码 + 原始字节 + 两个透传头**。
  *
  * 设计要点：非 2xx **不折叠成 `Left`** —— 410 `attachment_expired` / 404
  * `attachment_not_found` / 403 `not_friends` 是**三个语义不同的可判读态**
  * （§B.7 ③ 要求「已过期」与「下载失败」在客户端**可区分**），折叠会把它们
  * 压成一个「失败」。
  *
  * 已知代价（如实登记）：字节**整件进内存**（`BodyHandlers.ofByteArray()`）—— 4b 腿 A
  * 立项时上限 = 100,000,000 B，该代价按 100 MB 级登记并接受。
  *
  * 🔴 **上限升到 1024 MB = 1 GiB 后本代价放大 10.7 倍，已不再可接受**（2026-09-14
  * 附件上限批 r2 实测登记，**本批未改此腿**）：宿主 JVM 堆 = `MaxRAMPercentage=12.5`
  * ⇒ 本机 2 GB，且配 `-XX:+ExitOnOutOfMemoryError`（OOM = 硬退出，看门狗再拉起）。
  * 一次 1 GiB 附件的 E3 下载会在该堆里一次分配 ≈1 GiB（`Array[Byte]`），叠加
  * `RestApiRoutes` 的 `Stream.emits(fetch.bytes)` 还会再留一份引用 ⇒ 逼近/触顶 2 GB
  * 堆。**修复方向（未实施，交下一批）**：E3 改流式落盘（`BodyHandlers.ofFile` /
  * 分块 GET），`AttachmentFetch` 由 `bytes: Array[Byte]` 换成临时文件句柄 + 响应
  * 完成即删；或对**好友下载腿**单列一个内存可承载的上限（与作者给定数解耦）。
  * 详见报告 `.nebflow/reports/20260914_dropbox-attach-fix-impl2-r2.md` 的开放项。 */
case class AttachmentFetch(
  status: Int,
  bytes: Array[Byte],
  contentDisposition: Option[String] = None,
  sha256Header: Option[String] = None
)

class NeblinkClient(
  config: NeblinkServerConfig,
  serverPort: Int,
  onDeviceTokenRejected: Option[IO[Option[String]]] = None,
  identity: Option[IO[DeviceIdentity]] = None,
  autoLoginParked: IO[Boolean] = IO.pure(false)
):
  private val logger = NebflowLogger.forName("nebflow.neblink.client")

  // HTTP client: force HTTP/1.1 and bypass system proxy (direct LAN/WAN access).
  //
  // ── D1: why HTTP/1.1 here — evidence, then judge-red ──────────────────────
  // The sentence that used to stand here ("HTTP/2 connection-reuse + TLS 1.3
  // session resumption clash with the Caddy reverse proxy … connect timeouts /
  // bad_record_mac TLS alerts") was a causal claim with **no evidence bound to
  // it**, copied verbatim into 8 sites.
  //
  // Evidence (2026-09-12 read-only probe of this exact topology, Caddy in
  // front of neblink.nebflow.space): the default version (i.e. h2, ALPN=h2
  // signed by this Caddy) served 14/14 requests 200 with 0 GOAWAY and 0 TLS
  // alert, TLS 1.3 ticket resumption was accepted 5/5, and the connection
  // reuse boundary measured (28.6 s, 32.8 s]. Source chain: the neblink pin
  // landed as 42fd15b6, whose own message says "same TLS fix as 3773699b" —
  // i.e. it is an analogy carried over from the 2026-08-11 upstream LLM gateway
  // (nginx/one-api style reverse proxy), not a reproduction here.
  // 未证 (both directions): the original failure was intermittent and no log
  // of it survives, so a green local window does NOT prove the trap is absent
  // — it only says "not triggered here, in this window, on this JDK build".
  //
  // Judge-red (any one ⇒ reopen the pin + a rollback review; until then
  // HTTP/1.1 stays and no h1/h2 decision moves):
  //   ① the outbound-failure buckets logged by sendRequestTimed report
  //      IOException(GOAWAY) or IOException(closed/reset) on a non-idempotent
  //      POST (those are never retried);
  //   ② bad_record_mac or any TLS alert reproduces against this topology;
  //   ③ a same-window h1-vs-h2 comparison (n ≥ 100 per arm) shows h2 p95 >
  //      h1 p95 × 1.2.
  private val httpClient = HttpClient
    .newBuilder()
    .version(HttpClient.Version.HTTP_1_1)
    .proxy(java.net.ProxySelector.of(null))
    .connectTimeout(java.time.Duration.ofSeconds(15))
    .build()

  @volatile private var sessionToken: Option[String] = None

  /** C1: own `/24` prefixes from the last [[detectLocalEndpoints]] run (see [[localPrefixes]]). */
  @volatile private var cachedLocalPrefixes: Set[String] = Set.empty

  /** Active long-lived device credential — starts from config, replaced by
    * the silent re-login hook when the server rejects the old one. */
  @volatile private var activeDeviceToken: Option[String] = config.deviceToken

  /** Expose current session token for NeblinkRelayTunnel (live read on each reconnect). */
  def currentSessionToken: Option[String] = sessionToken

  /**
   * Detect local IPv4 addresses for endpoint reporting to the NebLink Server.
   *
   * Filters out virtual NICs (Hyper-V, WSL2, Docker, VMware, VirtualBox) whose
   * addresses are unreachable from the real LAN — e.g. `192.0.2.10` on a
   * Windows `vEthernet (WSL)` adapter. If filtering would remove every
   * endpoint, falls back to the unfiltered list so P2P still has a chance.
   *
   * C1 (2026-09-11 P2P 直连修复批): also labels each endpoint with a `kind`
   * (`tailscale` for `100.64.0.0/10`, else `lan`) and the NIC name, and caches
   * our own `/24` prefixes so inbound namelists can be preference-ordered
   * without re-enumerating NICs. `NeblinkEndpoint` already carried `kind`/`label`
   * — no protocol change (方案 §3.3, 与 `updateTrustedIps` 全端点口径一致).
   */
  def detectLocalEndpoints: IO[List[NeblinkEndpoint]] = IO.blocking {
    try
      val allNics = NetworkInterface.getNetworkInterfaces.asScala.toList
        .filter(_.isUp)
        .filterNot(_.isLoopback)

      def toEndpoints(nics: List[NetworkInterface]): List[NeblinkEndpoint] =
        nics.flatMap { nic =>
          val label = Option(nic.getDisplayName).filter(_.nonEmpty).orElse(Option(nic.getName)).getOrElse("")
          nic.getInetAddresses.asScala.toList
            .filter(_.isInstanceOf[java.net.Inet4Address])
            .map(addr => NeblinkEndpoint(addr.getHostAddress, serverPort, classifyKind(addr.getHostAddress), label))
        }

      val physical = allNics.filterNot(isLikelyVirtualNic)
      val preferred = toEndpoints(physical)
      val chosen = if preferred.nonEmpty then preferred else toEndpoints(allNics) // never lose all endpoints
      cachedLocalPrefixes = EndpointPreference.localPrefixesOf(chosen.map(_.address))
      chosen
    catch case _: Exception => Nil
  }

  /** C1 地址口径 (§3.3): `100.64.0.0/10` ⇒ `tailscale`，其余 IPv4 ⇒ `lan`. */
  private def classifyKind(address: String): String =
    if EndpointPreference.isTailscaleHost(address) then "tailscale" else "lan"

  /** Local `/24` prefixes seen by the most recent [[detectLocalEndpoints]] run.
    *
    * Both caller paths that produce a namelist run it first — `login`
    * (`doLogin` detects when the caller passes no endpoints) and `heartbeat`
    * (re-detects every beat) — so this is warm by the time
    * [[toNeblinkPeers]] maps a server response.
    */
  def localPrefixes: Set[String] = cachedLocalPrefixes

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
    autoLoginBlocked("login").flatMap {
      case Some(refused) => IO.pure(Left(refused))
      case None =>
        doLoginUnparked(deviceId, deviceName, platform, endpoints, allowRelogin)
    }

  /** 踢下线停摆门（2026-09-14 踢旧批 r2）——**自动登录入口的公共前置**。
    *
    * 返回 `Some(err)` = 停摆中，调用方必须原样返回该错误（**零 HTTP 请求**）；
    * `None` = 未停摆，照常走原路径。
    *
    * 为什么门必须比「persist 失败」更深一层：服务端 register 里的
    * kick-on-re-enroll 发生在**客户端 persist 之前**（`LogtoSilentRelogin` 的
    * `.register` 先于 `NeblinkEnrollment.persist`）⇒ 只堵 persist / 只堵隧道，
    * 被踢端照样每 ~24s（心跳周期）把 register 打到服务端。本门挡住的是
    * **登录/会话交换本身**，register 因此没有机会起飞（第二道门在
    * `LogtoSilentRelogin.refreshAndRegister`，那是 register 的最近前驱）。
    *
    * 调用面（列全，全为自动路径）：`login` ← `discover`（心跳失效后的 re-login）
    * 与 `silentRelogin`（API/隧道自愈）。用户显式登录不经过本客户端
    * （`POST /api/neblink/auth/start` → PKCE 回环 → `RestApiRoutes.handleAuthCallback`
    * → `LogtoDeviceFlow.register`，与 `NeblinkClient` 无关）。
    *
    * `entry` 只用于日志归因（哪个消费者发现停摆），不参与判定。 */
  private def autoLoginBlocked(entry: String): IO[Option[String]] =
    autoLoginParked.flatMap {
      case false => IO.pure(None)
      case true =>
        logger
          .warn(
            s"NebLink auto-login suppressed [$entry]: this device was signed out elsewhere " +
              "(server-forced disconnect) — waiting for an explicit user login"
          )
          .as(Some(NeblinkClient.KickParkedAutoLoginRefusal))
    }

  private def doLoginUnparked(
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
  def sendFriendMessage(
    friendUserId: String,
    body: String,
    origin: Option[String] = None,
    attachmentIds: List[String] = Nil,
    clientMsgId: Option[String] = None
  ): IO[Either[String, Json]] =
    withSession { token =>
      val fields = List(
        Some("body" -> body.asJson),
        origin.map(o => "origin" -> o.asJson),
        // 4b 腿 A-4（§B.2 M4）：`attachments` = **attachmentId 列表**，顺序即展示顺序，
        // 键缺省 = 现状（旧服务端也据此走原校验 ⇒ 旧端容忍，§B.3）。空列表 ⇒ **省键**
        // （与 `None` 同形：不带附件的消息逐字节等于今天）。
        Option.when(attachmentIds.nonEmpty)("attachments" -> attachmentIds.asJson),
        clientMsgId.map(id => "clientMsgId" -> id.asJson)
      ).flatten
      sendRequest("POST", s"${config.url}/api/friends/$friendUserId/messages", Json.fromFields(fields).noSpaces, Some(token))
        .map(_.flatMap(resp => decode[Json](resp).left.map(_.getMessage)))
    }

  // ===== 4b 好友附件（腿 A）：能力探测 / 声明 / 分块上传 / 鉴权取字节 =====
  //
  // 全部经同一 `withSession` 缝（401/403 会话自愈语义与其余好友面逐字相同）。
  // 线面逐字对齐跨仓契约件 §B.1（E1–E4）；本仓**不新增**任何端点号/协议号
  // （红线：4b 不占 `AttachContract` proto 号）。

  /** A-5：**服务端附件能力存在性探测** —— 裁定④（不加自报字段）下唯一判据，
    * 三态语义/理由见 [[AttachmentCapability]]。
    *
    * 探测体故意非法（`name` 空 + `size = 0`）⇒ 按 §B.1 E1 的校验顺序，新服务端
    * **零副作用**地答 422；老服务端（无该路由）答 404。两码互斥 ⇒ 判据单调。 */
  def probeAttachmentCapability(friendUserId: String): IO[AttachmentCapability] =
    val path = AttachmentCapability.probePath(friendUserId)
    withSession(token => sendRequest("POST", s"${config.url}$path", AttachmentCapability.ProbeBody, Some(token)))
      .map(AttachmentCapability.judge)

  /** E1 创建上传会话（§B.1）：`{name,size,sha256}` → 201 `{attachmentId,receivedBytes,state}`。
    * 错误码逐字按 §E.3（403 `not_friends` / 422 `ATTACH_TOO_LARGE`/`INVALID_ARGUMENT` / 429）。 */
  def createAttachment(friendUserId: String, name: String, size: Long, sha256: String): IO[Either[String, Json]] =
    withSession { token =>
      val payload = Json.obj("name" -> name.asJson, "size" -> size.asJson, "sha256" -> sha256.asJson).noSpaces
      val url     = s"${config.url}/api/friends/${enc(friendUserId)}/attachments"
      sendRequest("POST", url, payload, Some(token)).map(_.flatMap(json(_)))
    }

  /** E2 追加一块（§B.1）：raw bytes + `X-Chunk-Sha256` 头；`offset` 由
    * [[nebflow.dropbox.AttachContract.plan]] 推导（**唯一**来源，禁自由填）。
    * `offset < receivedBytes` 的重传语义由服务端负责（截断重写）⇒ 本方法天然可重试。 */
  def uploadAttachmentChunk(
    attachmentId: String,
    offset: Long,
    bytes: Array[Byte],
    chunkSha256: String
  ): IO[Either[String, Json]] =
    withSession { token =>
      val url = s"${config.url}/api/attachments/${enc(attachmentId)}/chunks?offset=$offset"
      sendRequestBytes("POST", url, bytes, Some(token), Map("X-Chunk-Sha256" -> chunkSha256))
        .map(_.flatMap(json(_)))
    }

  /** E3 下载（腿 A-3）：**应用内鉴权**取字节。
    *
    * 只被网关的鉴权代理路由（`GET /api/friends/attachments/{id}`）调用 ⇒ 前端拿不到
    * 服务端地址/凭证，也不存在任何静态/公开 URL 面（裁定②；服务端附件目录不挂 Caddy）。
    * 非 2xx **不抛**：调用方按状态码分派可判读态（410 已过期 / 404 不存在 / 403 非好友）。 */
  def downloadAttachment(attachmentId: String): IO[Either[String, AttachmentFetch]] =
    withSession(token => sendRequestBinary("GET", s"${config.url}/api/attachments/${enc(attachmentId)}", Some(token)))

  /** E4 接收完毕回执（补件批 4b1 · §B.1 / §F.1b ②）：`POST /api/attachments/{id}/received`，
    * body **逐字** `{"wholeSha256":"<64 位小写 hex>"}`。
    *
    * 调用方 = 网关鉴权路由 → `FriendService.ackAttachmentReceived`（**唯一**调用链；
    * 前端只上报证据，判定在引擎侧 [[AttachmentAck]]）。本方法**不含任何判定**：
    * 「能不能发」在闸里，「发出去成没成」由状态码回给调用方。
    *
    * 三态**不折叠**（同 E3 的设计要点，理由见 [[AttachmentFetch]] 的注释）：
    *   - `200` ⇒ 服务端已置 `deleted`（含 `state='deleted'` 的**幂等**重复 ack，§B.1）；
    *   - `404`/`410` ⇒ **无需回执**（未就绪 `uploading`/`staged` §F.1b ⑥ / 已按瞬态口径删除）
    *     ⇒ 调用方记**信息级**日志，不是失败；
    *   - `422` ⇒ `WHOLE_DIGEST_MISMATCH`（本地与服务端 digest 不符 ⇒ 服务端零副作用）。
    *
    * 鉴权/自愈缝 = 既有 `withSession`（禁新造 HTTP 客户端、禁复制鉴权与关系闸——禁双实现）。
    * 超时 = `DefaultRelayTimeout`（10 s）：回执是**尽力而为**面，**不给**它长尾
    * （失败静默容忍 + 24 h TTL 兜底，§F.1b 规则 4）。 */
  def confirmAttachmentReceived(attachmentId: String, wholeSha256: String): IO[Either[String, (Int, String)]] =
    withSession(token =>
      sendRequestJsonWithStatus(
        "POST",
        s"${config.url}/api/attachments/${enc(attachmentId)}/received",
        Json.obj("wholeSha256" -> wholeSha256.asJson).noSpaces,
        Some(token)
      )
    )

  private def enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

  private def json(body: String): Either[String, Json] = decode[Json](body).left.map(_.getMessage)

  /** 二进制**请求**通道（E2 分块上传专用；本批唯一新增传输面）。
    * 返回约定与 [[sendRequest]] 逐字同形：非 2xx ⇒ `Left("HTTP <code>: <body>")`。 */
  protected def sendRequestBytes(
    method: String,
    url: String,
    body: Array[Byte],
    token: Option[String],
    headers: Map[String, String]
  ): IO[Either[String, String]] =
    IO.blocking {
      try
        val builder = HttpRequest
          .newBuilder()
          .uri(URI.create(url))
          .timeout(java.time.Duration.ofMillis(NeblinkClient.ChunkUploadTimeout.toMillis))
          .header("Content-Type", "application/octet-stream")
        token.foreach(t => builder.header("Authorization", s"Bearer $t"))
        headers.foreach { case (k, v) => builder.header(k, v) }
        val response = httpClient.send(builder.method(method, HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofString())
        if response.statusCode() >= 200 && response.statusCode() < 300 then Right(response.body())
        else Left(s"HTTP ${response.statusCode()}: ${response.body()}")
      catch
        case e: Exception =>
          NeblinkClient.noteOutboundFailure(e, method, url, logger)
          Left(if e.getMessage == null then e.toString else e.getMessage)
    }.handleErrorWith(e => IO.pure(Left(e.getMessage)))

  /** 二进制**响应**通道（E3 下载专用）。与 [[sendRequestBytes]] 的差别只有一处：
    * **非 2xx 也返回 `Right`**（携状态码 + 原始字节）—— 410/404/403 是**可判读态**，
    * 不是「失败」；只有传输层异常才是 `Left`。 */
  protected def sendRequestBinary(
    method: String,
    url: String,
    token: Option[String]
  ): IO[Either[String, AttachmentFetch]] =
    IO.blocking {
      try
        val builder = HttpRequest
          .newBuilder()
          .uri(URI.create(url))
          .timeout(java.time.Duration.ofMillis(NeblinkClient.AttachmentDownloadTimeout.toMillis))
        token.foreach(t => builder.header("Authorization", s"Bearer $t"))
        val response = httpClient.send(builder.method(method, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofByteArray())
        val hs = response.headers()
        Right(
          AttachmentFetch(
            status = response.statusCode(),
            bytes = response.body(),
            contentDisposition = Option(hs.firstValue("content-disposition").orElse(null)),
            sha256Header = Option(hs.firstValue("x-attachment-sha256").orElse(null))
          )
        )
      catch
        case e: Exception =>
          NeblinkClient.noteOutboundFailure(e, method, url, logger)
          Left(if e.getMessage == null then e.toString else e.getMessage)
    }.handleErrorWith(e => IO.pure(Left(e.getMessage)))

  /** JSON **请求**通道，**保留状态码**（E4 回执专用）。与 [[sendRequestTimed]] 的差别
    * 只有一处：非 2xx **不是** `Left`，而是 `Right((<code>, <body>))` —— `404`/`410`
    * 是**可判读态**（无需回执）而非失败，折叠成 `Left` 会让调用方只能靠解析字符串
    * 区分它们（同 [[sendRequestBinary]] 与 [[sendRequestBytes]] 的关系）。只有传输层
    * 异常才是 `Left`（其文案与 [[sendRequestTimed]] 逐字同形，下游日志口径不变）。
    *
    * 超时**按调用传**：默认 `DefaultRelayTimeout`，与 [[sendRequest]] 同值 ⇒ 不动任何
    * 既有语义（E2/E3 的长超时各有自己的常量，本通道不碰）。 */
  protected def sendRequestJsonWithStatus(
    method: String,
    url: String,
    body: String,
    token: Option[String],
    timeout: scala.concurrent.duration.FiniteDuration = NeblinkClient.DefaultRelayTimeout
  ): IO[Either[String, (Int, String)]] =
    IO.blocking {
      try
        val builder = HttpRequest
          .newBuilder()
          .uri(URI.create(url))
          .timeout(java.time.Duration.ofMillis(timeout.toMillis))
        token.foreach(t => builder.header("Authorization", s"Bearer $t"))
        builder.header("Content-Type", "application/json")
        val request = if method == "POST" then builder.POST(HttpRequest.BodyPublishers.ofString(body)).build()
          else builder.method(method, HttpRequest.BodyPublishers.ofString(body)).build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        Right((response.statusCode(), response.body()))
      catch
        case e: Exception =>
          NeblinkClient.noteOutboundFailure(e, method, url, logger)
          Left(if e.getMessage == null then e.toString else e.getMessage)
    }.handleErrorWith(e => IO.pure(Left(e.getMessage)))

  /** 更新未读 cursor（仅本地角标口径，无回执）。 */
  def markConversationRead(conversationId: String, lastReadMessageId: Long): IO[Either[String, String]] =
    withSessionRaw("POST", s"/api/conversations/$conversationId/read", s"""{"lastReadMessageId":$lastReadMessageId}""")

  /** 群路由代理腿（gwroutes 批，2026-09-15）：**保留上游状态码**的会话内转发口。
    *
    * 与 [[withSessionRaw]] 的**唯一**差别：非 2xx 不折叠成
    * `Left("HTTP <code>: <body>")`，而是 `Right((<code>, <body>))`。理由 = 群域的
    * `404 group_not_found` / `403 group_disbanded` / `403 not_member` 是**群终态**
    * （客户端 `web/js/friendGroups.js` 的 `groupErrToast` 按语义码分态），折叠后
    * 统一压成 502 ⇒ 客户端再也分不出「群不存在 / 群已解散 / 我被踢了」。
    *
    * 同族先例（本仓既有，非新形态）：[[sendRequestJsonWithStatus]]（E4 回执）与
    * [[sendRequestBinary]]（E3 下载）—— 可判读的非 2xx 走 `Right`，**只有传输层
    * 异常**才是 `Left`（其文案口径与既有通道逐字同形，下游日志不受影响）。
    *
    * `path` 为**相对段**（含 `/api` 前缀，形如 `/api/groups/invites`），与
    * [[withSessionRaw]] / [[withSessionJson]] 同形。鉴权身份仍**只**由 `withSession`
    * 的 `Authorization: Bearer <device session token>` 承载——与全部既有 friends /
    * conversations 代理逐字一致：本层**不发** `sender` / `uid` 类自定义头（服务端
    * 由 session token 自行解身份，客户端不得自报身份）。
    */
  def proxyWithStatus(method: String, path: String, body: String): IO[Either[String, (Int, String)]] =
    withSession(token => sendRequestJsonWithStatus(method, s"${config.url}$path", body, Some(token)))

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
    * Deferred and reuse its outcome. Shared by EVERY heal caller through
    * ensureFreshSession. */
  private val reloginGate: Ref[IO, Option[Deferred[IO, Boolean]]] = Ref.unsafe(None)

  /**
   * Single-flight session self-heal — ONE re-login per concurrent burst,
   * regardless of how many independent consumers noticed the rejection.
   *
   * Entry points (both feed the SAME gate by construction):
   *   - the API-level path (`withSession` → selfHeal) for REST consumers;
   *   - the relay-tunnel upgrade path (`NeblinkRelayTunnel.handleAuthRejection`),
   *     which is the tunnel's ONLY way to refresh a token the server kicked.
   *
   * WHY the gate must be shared rather than per-caller: every login kicks our
   * own previous session server-side (one-live-session-per-device), so two
   * gates = two racing logins = each kicking the other's fresh session, which
   * is exactly the self-inflicted churn this batch exists to prevent.
   * Single-flight is per client instance, and F1 guarantees every consumer
   * shares one instance (discovery.clientRef / NeblinkService.relayClientOpt).
   *
   * `trigger` only labels the log line (attribution: which consumer noticed).
   * Returns true when a usable fresh session exists afterwards.
   */
  def ensureFreshSession(trigger: String): IO[Boolean] =
    autoLoginBlocked(s"self-heal:$trigger").flatMap {
      // 停摆期（被服务端 kick 后的自愈入口）：**不发任何登录请求**，直接报 false
      // ⇒ 调用方拿到原始 401/403 原文（前端提示需显式登录）。
      // 落点选择：门在 single-flight 闸**之前**——停摆期连闸都不该被占用
      // （否则并发消费者的 losers 会等在闸上，直到一次注定失败的登录走完。
      // r1 的失败点正是这条腿：闸本身不拦停摆，登录照发，register 照打。
      case Some(_) => IO.pure(false)
      case None =>
        Deferred[IO, Boolean].flatMap { mine =>
          reloginGate
            .modify {
              case None         => (Some(mine), Left(mine))
              case Some(winner) => (Some(winner), Right(winner))
            }
            .flatMap {
              case Left(won) =>
                // I won the race: re-login exactly once, publish the outcome, and
                // clear the gate only if it is still mine (a newer winner may have
                // replaced it while my login was in flight).
                val run = silentRelogin(trigger).flatMap(ok => won.complete(ok).as(ok))
                run.guarantee(reloginGate.update {
                  case Some(g) if g eq won => None
                  case other               => other
                })
              case Right(winner) => winner.get
            }
        }
    }

  private def selfHeal[A](err: String, f: String => IO[Either[String, A]]): IO[Either[String, A]] =
    ensureFreshSession("api-auth-reject").flatMap {
      case true  => replayAfterHeal(f, err)
      case false => IO.pure(Left(err))
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
    * Logto hook. Never throws — failures map to false. `trigger` name-tags the
    * log line so a burst can be attributed to its consumer (API vs relay
    * tunnel). */
  private def silentRelogin(trigger: String): IO[Boolean] =
    identity match
      case None => IO.pure(false)
      case Some(getId) =>
        logger.warn(s"NebLink session rejected by server (401/403 auth) — attempting silent re-login [$trigger]") *>
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
  def relayExec(
    targetDeviceId: String,
    action: String,
    params: JsonObject,
    timeout: scala.concurrent.duration.FiniteDuration = NeblinkClient.DefaultRelayTimeout
  ): IO[Either[String, String]] =
    val body = Json.obj(
      "action" -> action.asJson,
      "params" -> params.asJson,
      "projectRoot" -> System.getProperty("user.dir", ".").asJson
    ).noSpaces
    // 2026-09-10 隧道鉴权自愈批: this used to read sessionToken directly and
    // was therefore the one cross-device dispatch path WITHOUT the 401/403
    // self-heal (the friend batch registered it as a leftover). It now goes
    // through withSession, so "control the remote computer" recovers from a
    // kicked session exactly like search/list do — same shared gate, same
    // per-request single shot. The heal wraps ONLY the HTTP call: the decoded
    // `error` field is a remote-tool error, not an auth signal, and must never
    // feed the trigger.
    withSession(token =>
      dispatchRequest("POST", s"${config.url}/api/relay/$targetDeviceId/exec", body, Some(token), timeout)
    ).flatMap {
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

  /**
   * 唯一出口：**默认超时**走可覆写的 `sendRequest`（既有测试桩零改动），
   * **自定义超时**走真实传输 `sendRequestTimed`（分块腿专用）。
   *
   * WHY 分流：把超时做成第 5 个参数会改掉 `sendRequest` 的签名 —— 而它是 5 个既有
   * 测试桩的覆写点，签名一变全部编译失败（本次实施实测命中）。分流让「既有语义零改动」
   * 与「分块腿按调用传超时」同时成立。
   */
  private def dispatchRequest(
    method: String,
    url: String,
    body: String,
    token: Option[String],
    timeout: scala.concurrent.duration.FiniteDuration
  ): IO[Either[String, String]] =
    if timeout == NeblinkClient.DefaultRelayTimeout then sendRequest(method, url, body, token)
    else sendRequestTimed(method, url, body, token, timeout)

  /** Convert NebLink Server peers to neblink PeerInfo.
   *
   * C1 (2026-09-11 P2P 直连修复批): **keeps every declared endpoint**, ordered by
   * [[EndpointPreference]] (Tailscale → 同网段 → 其余), with `address` =
   * `endpoints.head` (the best candidate). Previously only `endpoints.head` was
   * kept and the rest discarded — in the incident that head was an unreachable
   * LAN address while the working Tailscale endpoint sat at index 1, so
   * `connect` had nothing to fall back to and `directOnline` stayed false
   * (方案 §1.2 (A), §3.2 结论 2). Consistent with the全端点 口径 already used by
   * [[peerAddresses]] (→ `NeblinkService.updateTrustedIps`).
   *
   * Presence v2: a peer the server explicitly flags online=false (lazy TTL
   * judgement) maps to lastSeen=0 — non-fresh, so the C3 freshness predicate
   * reports it offline. Without this mapping, an offline-flagged device would
   * refresh itself back to "online" on every heartbeat just by appearing in
   * the response, fighting the DeviceStatusUpdate push (C6). */
  def toNeblinkPeers(serverPeers: List[NeblinkPeerInfo]): List[PeerInfo] =
    val prefixes = localPrefixes
    serverPeers.filter(_.endpoints.nonEmpty).map { p =>
      val urls = EndpointPreference.order(
        p.endpoints.map(ep => s"http://${ep.address}:${ep.port}"),
        prefixes
      )
      PeerInfo(
        deviceId = p.deviceId,
        deviceName = p.deviceName,
        platform = p.platform,
        address = urls.head,
        lastSeen = if p.online then System.currentTimeMillis() else 0L,
        endpoints = urls
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

  // ===== Relay 分块腿（附件腿批，2026-09-12）=====
  //
  // 语义**收紧**（relay 腿不再是「整块 base64 + 自证 hash」）：
  //   - 逐块承载（单块有界，不整件驻留 —— 不变量 I4）；
  //   - 接收端**自算**并回传块摘要与（末块）整件摘要 —— 不变量 I2，禁自证；
  //   - **按调用传超时**（禁把全局 10 s 放宽 —— 该方法是所有 relay 调用的共用路径，
  //     放宽会改掉工具执行/好友消息的既有语义）。
  //
  // 通道能力**保留**：`FileTransferAction` / `relayTransferGet|Put` /
  // `/api/neblink/transfer` / `NeblinkService.receiveFile|sendFile` 一个不删，
  // 本组方法只是给同一条 relay 腿加上分块参数。

  /** 推**一块**给远端（relay 腿）。返回远端自算的摘要回执。 */
  def relayTransferPutChunk(
    targetDeviceId: String,
    path: String,
    contentB64: String,
    chunkIndex: Int,
    totalBytes: Long,
    chunkSize: Int,
    chunkSha256: String,
    wholeSha256: String,
    overwrite: Boolean,
    timeout: scala.concurrent.duration.FiniteDuration
  ): IO[Either[String, Json]] =
    val params = JsonObject(
      "direction" -> "put".asJson,
      "path" -> path.asJson,
      "content" -> contentB64.asJson,
      "overwrite" -> overwrite.asJson,
      // 分块参数：缺失 ⇒ 旧端按整件语义处理（向后兼容矩阵 §3.9）
      "chunkIndex" -> chunkIndex.asJson,
      "totalBytes" -> totalBytes.asJson,
      "chunkSize" -> chunkSize.asJson,
      "chunkSha256" -> chunkSha256.asJson,
      "wholeSha256" -> wholeSha256.asJson
    )
    relayExec(targetDeviceId, "FileTransfer", params, timeout).flatMap {
      case Right(output) =>
        decode[Json](output) match
          case Right(json) => IO.pure(Right(json))
          case Left(err) => IO.pure(Left(s"Decode error: ${err.getMessage}"))
      case Left(err) => IO.pure(Left(err))
    }

  /** 续传探针（relay 腿）：问远端当前 offset 与其自算的前缀摘要。 */
  def relayTransferProbe(
    targetDeviceId: String,
    path: String,
    timeout: scala.concurrent.duration.FiniteDuration
  ): IO[Either[String, Json]] =
    val params = JsonObject(
      "direction" -> "probe".asJson,
      "path" -> path.asJson
    )
    relayExec(targetDeviceId, "FileTransfer", params, timeout).flatMap {
      case Right(output) =>
        decode[Json](output) match
          case Right(json) => IO.pure(Right(json))
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
   *
   * ⚠️ 附件腿批（2026-09-12）：本方法的**签名与语义保持不变** —— 它仍是既有
   * 测试桩的**唯一覆写点**，并承担**默认超时**（`DefaultRelayTimeout` = 10 s）的
   * 全部调用。分块腿要「按调用传超时」，但它**不得**改掉这个共用路径的既有语义
   * （设计件 §3.5：全局放宽会连带改掉工具执行 / 好友消息的行为）。
   */
  protected def sendRequest(
    method: String,
    url: String,
    body: String,
    token: Option[String]
  ): IO[Either[String, String]] =
    sendRequestTimed(method, url, body, token, NeblinkClient.DefaultRelayTimeout)

  /**
   * 真实 HTTP 传输实现，**超时按调用传**。默认值 = 10 s（与既有行为一致）。
   * 分块腿传更长超时（块级 30 s / 60 s），服务端 waiter 120 s 是其硬上界。
   */
  protected def sendRequestTimed(
    method: String,
    url: String,
    body: String,
    token: Option[String],
    timeout: scala.concurrent.duration.FiniteDuration
  ): IO[Either[String, String]] =
    IO.blocking {
      try
        val builder = HttpRequest
          .newBuilder()
          .uri(URI.create(url))
          .timeout(java.time.Duration.ofMillis(timeout.toMillis))
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
            // D4(a) instrument: bucket + count + log before the value is
            // collapsed (the Left text below is deliberately unchanged).
            NeblinkClient.noteOutboundFailure(e, method, url, logger)
            // Some JDK-native exceptions (e.g. bare ConnectException) carry a
            // null message — fall back to toString so downstream Left values
            // never NPE on encoding/logging.
            Left(if e.getMessage == null then e.toString else e.getMessage)
    }.handleErrorWith(e => IO.pure(Left(e.getMessage)))

end NeblinkClient

object NeblinkClient:

  /**
   * Default per-call relay timeout — **保留既有 10 s 语义**作为默认值。
   *
   * 附件腿批（2026-09-12）把 `sendRequest` / `relayExec` 的超时改为**按调用可传**：
   * 分块腿传长超时而**不动全局默认**——`sendRequest` 是全部 relay 调用的共用路径，
   * 全局放宽会连带改掉工具执行 / 好友消息的既有语义（设计件 §3.5）。
   */
  val DefaultRelayTimeout: scala.concurrent.duration.FiniteDuration =
    scala.concurrent.duration.FiniteDuration(10, scala.concurrent.duration.SECONDS)

  /** E2 单块上传超时（工程值，非冻结口径）：一块 ≤ `AttachContract.ChunkSize`
    * （4 MiB）；60 s 对局域网/公网都留足余量，且**不**改动 [[DefaultRelayTimeout]]。 */
  val ChunkUploadTimeout: scala.concurrent.duration.FiniteDuration =
    scala.concurrent.duration.FiniteDuration(60, scala.concurrent.duration.SECONDS)

  /** E3 整件下载超时（工程值）：单件上限 = 作者给定数 1,073,741,824 B（1024 MB = 1 GiB）。
    *
    * ⚠️ 上限升到 1 GiB 后本超时的**有效含义变了**（2026-09-14 r2 登记，本批未改值）：
    * 300 s 走完 1 GiB 需要 ≥3.6 MB/s 的持续吞吐；更慢的链路会在中途 `HttpTimeout`
    * 失败（该腿是**整件一个请求**，不是分块 ⇒ 没有断点续传兜底）。是否放宽/改分块
    * 属设计裁定，见报告开放项。 */
  val AttachmentDownloadTimeout: scala.concurrent.duration.FiniteDuration =
    scala.concurrent.duration.FiniteDuration(300, scala.concurrent.duration.SECONDS)

  /** 自动登录被停摆门拒绝时的错误原文（2026-09-14 踢旧批 r2）。它走 `Left(...)`
    * 通道，与网络/服务端失败在调用方侧可区分（自愈把它翻成 `false` ⇒ 用户看到
    * 原始 401 并被告知需显式登录），且**不含任何 URL / 凭据**，可安全落日志。 */
  val KickParkedAutoLoginRefusal: String =
    "Signed out on another device — automatic re-login and re-registration are parked " +
      "until an explicit user login"

  // ────────────────────────────────────────────────────────────────────────
  // Outbound failure buckets — the D4(a) instrument (clientperf 批, 2026-09-13)
  // ────────────────────────────────────────────────────────────────────────
  //
  // WHY: every transport failure in sendRequestTimed used to collapse into one
  // `String` (`Left(e.getMessage)`), so nothing downstream could tell a connect
  // timeout from a GOAWAY from a closed connection. Without that separation the
  // h1/h2 re-evaluation (设计件 §3.4 R4) has no judge-red signal at all — one
  // green run proves nothing about an intermittent failure.
  //
  // WHAT is unchanged (hard constraints of this batch): the returned value keeps
  // exactly the same shape (`Left(message)` text untouched), no wire change (no
  // request/response/header touched), no new file, no protocol change, no new
  // dependency — the buckets only ADD one WARN line each through the existing
  // NebflowLogger pipeline.

  /** Bucket labels — the judge-red vocabulary (设计件 §3.1/§3.2). */
  object Bucket:
    val ConnectTimeout = "HttpConnectTimeoutException"
    val Timeout = "HttpTimeoutException"
    val Goaway = "IOException(GOAWAY)"
    val ConnClosed = "IOException(closed/reset)"
    val Other = "other"

  private val outboundFailureCounts =
    new java.util.concurrent.ConcurrentHashMap[String, java.util.concurrent.atomic.AtomicLong]()

  /** Classify one transport failure. NOTE the order: `HttpConnectTimeoutException`
    * extends `HttpTimeoutException` extends `IOException`, so the most specific
    * class must be matched first or every connect timeout would land in the
    * plain-timeout bucket. A GOAWAY is only distinguishable by message text
    * (the JDK raises a bare `IOException`), hence the message probes.
    */
  private[neblink] def bucketOf(e: Throwable): String =
    e match
      case _: java.net.http.HttpConnectTimeoutException => Bucket.ConnectTimeout
      case _: java.net.http.HttpTimeoutException        => Bucket.Timeout
      case io: java.io.IOException =>
        val m = Option(io.getMessage).getOrElse("").toLowerCase
        if m.contains("goaway") then Bucket.Goaway
        else if m.contains("closed") || m.contains("reset") then Bucket.ConnClosed
        else Bucket.Other
      case _ => Bucket.Other

  /** One classified failure: bump the bucket, then log the raw evidence line.
    * Called from inside `sendRequestTimed`'s catch — sync logger on purpose
    * (we are already on `IO.blocking`). Never throws: the instrument must not
    * be able to change the request outcome.
    */
  private[neblink] def noteOutboundFailure(e: Exception, method: String, url: String, logger: NebflowLogger): Unit =
    try
      val bucket = bucketOf(e)
      val count = outboundFailureCounts
        .computeIfAbsent(bucket, _ => new java.util.concurrent.atomic.AtomicLong(0L))
        .incrementAndGet()
      // Single self-contained line (the 1-arg `warnSync`) so all fields form one
      // greppable token stream for the judge-red greps. Style choice, not a
      // workaround: the kv overload renders its pairs fine (probe-measured —
      // see the clientperf evidence dir, 19-temp-kvprobe.log).
      logger.warnSync(
        s"NebLink outbound request failed bucket=$bucket bucketCount=$count method=$method" +
          s" path=${pathShapeOf(url)} at=${java.time.Instant.now()}" +
          s" errorClass=${e.getClass.getSimpleName} error=${scrub(Option(e.getMessage).getOrElse(e.toString))}"
      )
    catch case _: Throwable => ()

  /** Per-bucket totals since JVM start (observation seam). */
  private[neblink] def outboundFailureSnapshot: Map[String, Long] =
    outboundFailureCounts.asScala.map { case (k, v) => k -> v.get() }.toMap

  /** Test seam — the counters are JVM-global, so specs reset them. */
  private[neblink] def resetOutboundFailureCounters(): Unit = outboundFailureCounts.clear()

  /** Path SHAPE only, for logs: no query string, no host, no token. Segments
    * that look like identifiers (any digit, or a non `[A-Za-z0-9._-]` byte —
    * UUIDs, hex ids, percent-encoded values) collapse to `:id`, so
    * `/api/friends/<id>/messages?token=…` logs as `/api/friends/:id/messages`.
    */
  private[neblink] def pathShapeOf(url: String): String =
    val cut =
      val q = url.indexOf('?')
      val h = url.indexOf('#')
      List(q, h).filter(_ >= 0).minOption.getOrElse(url.length)
    val pathOnly = url.substring(0, cut)
    val schemeEnd = pathOnly.indexOf("://")
    val path =
      if schemeEnd >= 0 then
        val slash = pathOnly.indexOf('/', schemeEnd + 3)
        if slash < 0 then "/" else pathOnly.substring(slash)
      else pathOnly
    if path.isEmpty then "/" else path.split("/", -1).map(maskPathSegment).mkString("/")

  private def maskPathSegment(s: String): String =
    if s.isEmpty then ""
    else if s.exists(_.isDigit) then ":id"
    else if s.forall(c => c.isLetter || c == '-' || c == '_' || c == '.') then s
    else ":id"

  /** Belt-and-braces scrub for the logged message: a query string, a bearer
    * token or a `secret=…` pair must never reach the log line (the path shape
    * is already host- and query-free, this covers the free-text message).
    */
  private def scrub(s: String): String =
    s.replaceAll("(?i)bearer\\s+\\S+", "Bearer <redacted>")
      .replaceAll("(?i)(token|secret|password|api[_-]?key)=[^\\s&]+", "$1=<redacted>")
      .replaceAll("(?i)(https?://[^\\s]*)\\?[^\\s]*", "$1?<redacted>")

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
