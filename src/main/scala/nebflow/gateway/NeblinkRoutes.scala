/* 从 RestApiRoutes 迁出(行为保持重构,2026-09-24)。 */
package nebflow.gateway

import cats.effect.IO
import cats.syntax.all.*
import fs2.Stream
import io.circe.syntax.*
import io.circe.{Json, parser}
import nebflow.neblink.*
import nebflow.shared.PathUtil
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*
import org.typelevel.ci.CIString

import scala.concurrent.duration.*

/**
 * 设备互联域(neblink,E 步 2026-09-24):原 RestApiRoutes.routes 巨型 match 尾段
 * 的 /neblink 全族(discover/announce/scan/status/avatar/config/logout/end-session/
 * enroll/device-flow/auth/device-info/peer-description/remote-exec/update/transfer/
 * dropbox/remote-update)连同 /avatars/{userId}、/devices/{deviceId}/messages 三块
 * case 逐字迁入,块内相对顺序原样;经 RestApiRoutes.routes 尾部
 * `<+> NeblinkRoutes.routes(ctx) <+> SocialRoutes.routes(ctx)` 挂载 —— 本域先于
 * SocialRoutes 解析,与迁出前一致(本域首段 {neblink,avatars,devices} 与社交域
 * {friends,conversations,attachments,users,groups} 及类内剩余首段字面量互不
 * 重叠,无遮蔽)。域专属助手 doRemoteUpdate / relayUpdateFallback / withNeblink /
 * parseDropboxChunkHeaders / chunkErrorResponse / verifyPeerAccess /
 * enrollWithServer / completeDeviceEnrollment / proxyPost 随迁,依赖实例态
 * (logger / neblinkService / 鉴权判据)的成员按 AuthRoutes 先例追加
 * (using ctx: RestApiCtx) 上下文参数,方法体逐字保留;跨域共用助手 checkAuth /
 * withAuth / isKnownNetworkDevice / neblinkServerUrl /
 * completeDeviceEnrollmentDetailed / friendErr / groupProxyResult / rawBody /
 * encSeg(类内 /models 等面、PresenceRoutes、AuthRoutes、SocialRoutes 仍调用)
 * 留守 RestApiRoutes 类内、经 RestApiCtx 委托引用 —— 全仓单一实现,禁两处各写
 * 一套。
 */
private[gateway] object NeblinkRoutes:

  def routes(ctx: RestApiCtx): HttpRoutes[IO] =
    import ctx.*

    // 类内先例(RestApiRoutes 的 `private given RestApiCtx = ctx` 解析锚点):
    // AuthRoutes 的 (using ctx: RestApiCtx) 成员(performLocalLogout /
    // beginPkceLogin 等)从这里解析,迁出的 case 体调用文本保持原状。
    given RestApiCtx = ctx

    HttpRoutes.of[IO] {
      // ===== NebLink P2P Discovery (no gateway auth — used by other Nebflow instances) =====
      // 注（2026-09-20 加固批）：本段的**对端判据**见下方 /neblink/discover 与 /neblink/announce
      // 两条（前者本批补入 verifyPeerAccess，后者原已内联 isTrustedPeer/isKnownNetworkDevice）。

      // Return local device info for NebLink discovery probes
      // 2026-09-20 加固批（chain-apiguard）：本路由原为裸奔（无门亦无内联判据）。它面向
      // **对端设备**而非 UI 客户端（对端无 Bearer 令牌）⇒ 落点是本族既有的对端判据
      // helper（同 /neblink/remote-exec、/neblink/transfer 等），**不**套 withAuth。
      // NebLink 未启用时 verifyPeerAccess 回 404，体与改前的 None 分支逐字相同。
      case req @ GET -> Root / "neblink" / "discover" =>
        verifyPeerAccess(req).flatMap {
          case Left(resp) => IO.pure(resp)
          case Right(ms) =>
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
                        Ok(ApiJson.ok)
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
            // 🔴 缺陷 A（上游 §8.2 第 4 项 / 判据 G4②）：本读点**永不抛** —— 坏凭据
            // （读不开/解码坏）由 `DeviceCredentialStore.loadDiagnosed` 自愈（改名留档 +
            // 当作无凭据），本端点因此稳定回答 200 + `loggedIn=false`，而不是 500
            // （修前 500 被前端 `neblink.js:159` 静默吞掉 ⇒ 假「已登录」中间态，上游 S4）。
            // 失败**不静默**：分类经下方**加法字段** `credentialIssue` 透出（老消费方忽略未知键）。
            credentialRead <- DeviceCredential.loadDiagnosed
            cred = credentialRead.getOrElse(None)
            credentialIssue = credentialRead.left.toOption
            cfg <- ms.neblinkConfig
            // Logged in = device credential exists AND NebLink is enabled.
            loggedIn = cred.isDefined && cfg.enabled
            // P1-2: accurate per-peer connectivity. serverOnline (heartbeat) alone
            // doesn't mean the peer is reachable — cross-network peers need relay.
            directOnline = (deviceId: String) => ms.presenceServiceOpt.exists(_.isConnected(deviceId))
            // C3 (2026-09-11 P2P 直连修复批): WHY the P2P leg is where it is.
            // `directOnline=false` alone was unattributable — a failed presence
            // dial only wrote a logger.debug line that root level=INFO filtered
            // out (方案 §1.1 环③ / U-1), so "unreachable address" and "never
            // dialed" looked identical. These are ADDITIVE fields; no existing
            // field's meaning changes.
            dialStatusOf = (deviceId: String) => ms.presenceServiceOpt.flatMap(_.dialStatus(deviceId))
            relayAvailable = ms.relayTunnelOpt.exists(_.isAlive)
            // F7 (2026-09-10 隧道鉴权自愈批): relayAvailable alone hides WHY the
            // tunnel is down. authRejected distinguishes "our session was
            // rejected (401/403) — self-heal territory" from a server-side 5xx,
            // which is the report §6 cross-project discriminator.
            // 2026-09-14（踢旧批案 B）：再补本地已判定的「已在别处登录」态
            // （`disconnect` 帧 → 停摆）——**加法字段，本地网关↔浏览器侧**，服务端
            // wire 零变化（见 NeblinkRelayTunnel.statusJson 注释）。
            relayStatus = nebflow.neblink.NeblinkRelayTunnel.statusJson(
              relayAvailable,
              ms.relayTunnelOpt.flatMap(_.authStatus),
              ms.relayTunnelOpt.map(_.signedOutElsewhereAt).getOrElse(0L)
            )
            // C3 (ghost-peer fix): `online` is a real freshness judgement — the
            // peer must have appeared in a server heartbeat/discovery response
            // within the online window (NeblinkService.onlineFreshnessMs), not
            // merely exist in the local list.
            nowMs = System.currentTimeMillis()
            peerOnline = (p: nebflow.neblink.PeerInfo) => NeblinkService.isPeerOnline(p, nowMs, cfg.syncIntervalSec)
            // Account identity hints (switch-account, 2026-09-10): decoded
            // READ-ONLY from the ALREADY-persisted id_token (no extra I/O —
            // `cred` is loaded right below anyway). Same trust rationale as
            // the C2 picture claim: TLS-sourced token, claim read only, the
            // token never leaves the store. The web client's account memory
            // persists ONLY these two display strings — never any credential.
            acctClaims = cred.flatMap(_.logto.flatMap(_.idToken)) match
              case Some(tok) => LogtoAuthCode.decodeIdTokenClaims(tok, Seq("email", "name"))
              case None => Map.empty[String, String]
            // 批 C（§3.7）：好友消息面的**对账计数暴露**。批 A 只产不曝（`FriendService`
            // 的 `recordPull`/`recordPullLine` 是唯一计数点，注释已声明暴露归批 C），
            // 本行即那条指令的落点——**读的就是批 A 那份累加值**，不另建计数器
            // （两份计数 = 两个读数会各说各话 = 判据④不可机械判）。
            // 未装配 friendService（未配置 NebLink Server）⇒ 显式 `null`，不是空对象：
            // 「没有这个面」与「有这个面且计数全 0」必须可区分（同既有 `relay` 字段口径）。
            friendPull <- sharedResources.friendService match
              case Some(fs) => fs.pullCountersJson
              case None => IO.pure(Json.Null)
            r <- Ok(
              Json.obj(
                "loggedIn" -> loggedIn.asJson,
                "relay" -> relayStatus,
                "friendPull" -> friendPull,
                // 缺陷 A（加法字段，老消费方忽略未知键）：本机凭据面的可判读读数。
                // `null` = 读干净（有/无凭据都是正常态）；非 null = 出过事，带
                // `code`/`reason`/`action` 三段（**零路径、零异常类名**，判据 G2/G3）。
                "credentialIssue" -> credentialIssue.fold(Json.Null)(_.toJson),
                "device" -> Json.obj(
                  "id" -> id.deviceId.asJson,
                  "name" -> id.deviceName.asJson,
                  "platform" -> id.platform.asJson,
                  "capabilities" -> id.capabilities.asJson,
                  "userDescription" -> id.userDescription.asJson,
                  "avatarUrl" -> id.avatarUrl.asJson,
                  "githubLogin" -> id.githubLogin.asJson,
                  "email" -> acctClaims.getOrElse("email", "").asJson,
                  "displayName" -> acctClaims.getOrElse("name", "").asJson
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
                      "online" -> peerOnline(p).asJson, // freshness: seen by server within the online window
                      "directOnline" -> directOnline(p.deviceId).asJson, // P2P WS reachable
                      "lastDialError" -> dialStatusOf(p.deviceId)
                        .flatMap(_.error)
                        .asJson, // C3: why not (None = last dial succeeded)
                      "lastDialAt" -> dialStatusOf(p.deviceId)
                        .map(_.atMs)
                        .asJson, // C3: when that dial happened (null = never dialed)
                      "dialEndpoint" -> dialStatusOf(p.deviceId)
                        .map(_.endpoint)
                        .asJson, // C1: candidate actually dialed / won
                      "relayAvailable" -> relayAvailable.asJson // our relay tunnel is up
                    )
                  )
                  .asJson
              )
            )
          yield r
        }

      // Own-account avatar proxy (2026-09-07 设置页头像加载态修复): the avatar
      // origin (neblink-server static host) sends no CORS headers, so the web
      // local-first cache (avatarCache.js) could never fetch it cross-origin and
      // never populated — every settings open fell back to a slow remote <img>.
      // Same-origin proxy fixes the cache build; fetches ONLY the current
      // identity avatarUrl (client supplies no URL — no open-proxy surface).
      case req @ GET -> Root / "neblink" / "avatar" =>
        withNeblink(req) { ms =>
          ms.identity.flatMap { id =>
            id.avatarUrl.filter(_.nonEmpty) match
              case None => NotFound(Json.obj("error" -> "no avatar".asJson))
              case Some(url) =>
                AvatarProxy.fetch(AvatarProxy.jdkFetch)(url).flatMap {
                  case Right((contentType, bytes)) =>
                    // Explicit byte-stream entity: bare Ok(Array[Byte]) resolves to
                    // the circe generic encoder in this scope (circe encodes byte
                    // arrays as JSON number arrays — the bytes would be mangled).
                    val ct = org.http4s.headers.`Content-Type`
                      .parse(contentType)
                      .getOrElse(org.http4s.headers.`Content-Type`(MediaType.application.`octet-stream`))
                    IO.pure(
                      Response[IO](Status.Ok)
                        .withEntity(fs2.Stream.emits(bytes).covary[IO])
                        .withHeaders(ct)
                    )
                  case Left(err) =>
                    BadGateway(Json.obj("error" -> err.asJson))
                }
          }
        }

      // 对端头像同源只读路由（sessperf Phase B 前置项 · 作者 2026-09-20 18:55 令 B）。
      //
      // 为什么需要：网页本地层要给**对端**头像建「内容指纹双层缓存」（方案卡 §4③），
      // 而跨源头像源站**不发 CORS 头** ⇒ 浏览器直 fetch 恒失败（`AvatarProxy.scala`
      // 头注 2026-09-07 取证）；`GET /api/neblink/avatar` 只服务当前身份**自己**。
      // 本路由是「按 userId 取对端头像字节」的唯一取数面。
      //
      // 🔴 **出生即包鉴权闸**（作者令：新路由禁裸奔过夜；apiguard 批 38 路由同族口径）：
      //    判据与全部 `/friends*` / `/conversations*` 代理腿**逐字同款**——
      //    ① `withAuth`（网关 Bearer 令牌）在先：无令牌 ⇒ **403**，零上游往返；
      //    ② `friendService` 缺席 ⇒ **404 `NebLink not enabled`**（fail-closed）；
      //    ③ 上游 `Not logged in` ⇒ **401 `code=neblink_not_logged_in`**（`friendErr` 单点，
      //       与网关自身 403 的**有意分化**见 `friendErr` 注释）。
      //
      // 🔴 **不是开放代理 / SSRF 面为零**：客户端**只能给 `userId`**；具体 URL 由网关从
      //    **本账号好友档案**（`FriendSummary.avatar`）解出 —— 请求方无法让网关去取任意
      //    地址。够不着的好友（非好友 / 未知 id / 该好友无头像）⇒ **404 `no avatar`**。
      //
      // 响应面（方案卡 §3 前置项逐条）：字节 + `Content-Type` 原样透传，
      //   带 `X-Avatar-Sha256` + `ETag`（强 ETag = 内容 sha）+ `Cache-Control: private`；
      //    `If-None-Match` 命中 ⇒ **304**（零重传 —— 客户端「hash 未变 ⇒ 复用旧 blob」的
      //    判据面）。上游非 200 ⇒ 502（客户端按失败退避，不污染既有缓存）。
      case req @ GET -> Root / "avatars" / userId =>
        withAuth(req) {
          sharedResources.friendService match
            case None => NotFound(Json.obj("error" -> "NebLink not enabled".asJson))
            case Some(fs) =>
              val wanted = userId.trim
              if wanted.isEmpty then BadRequest(Json.obj("error" -> "userId required".asJson))
              else
                fs.listFriends.flatMap {
                  case Left(err) => friendErr(err)
                  case Right(resp) =>
                    resp.friends.find(_.userId == wanted).flatMap(_.avatar).filter(_.nonEmpty) match
                      case None => NotFound(Json.obj("error" -> "no avatar".asJson))
                      case Some(url) =>
                        AvatarProxy.fetch(AvatarProxy.jdkFetch)(url).flatMap {
                          case Right((contentType, bytes)) =>
                            val sha = AvatarProxy.sha256Hex(bytes)
                            val etag = "\"" + sha + "\""
                            val inm = req.headers.get(CIString("If-None-Match")).map(_.head.value.trim).getOrElse("")
                            if inm.nonEmpty && inm.contains(sha) then
                              IO.pure(
                                Response[IO](Status.NotModified)
                                  .withHeaders(Headers(Header.Raw(CIString("ETag"), etag)))
                              )
                            else
                              // 显式字节流实体（同 /neblink/avatar 与附件下载先例：裸
                              // `Ok(Array[Byte])` 会命中 circe 的 byte 数组编码器，把字节
                              // 变成 JSON 数字数组 ⇒ 图片损坏）。
                              val ct = org.http4s.headers.`Content-Type`
                                .parse(contentType)
                                .getOrElse(org.http4s.headers.`Content-Type`(MediaType.application.`octet-stream`))
                              IO.pure(
                                Response[IO](Status.Ok)
                                  .withEntity(fs2.Stream.emits(bytes).covary[IO])
                                  .withHeaders(
                                    Headers(
                                      ct,
                                      Header.Raw(CIString("ETag"), etag),
                                      Header.Raw(CIString("X-Avatar-Sha256"), sha),
                                      Header.Raw(CIString("Cache-Control"), "private, max-age=3600")
                                    )
                                  )
                              )
                            end if
                          case Left(err) =>
                            BadGateway(Json.obj("error" -> err.asJson))
                        }
                }
              end if
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
            } *> Ok(ApiJson.ok)
          }
        }

      // Logout — notify the server, stop tunnels, clear device credential,
      // disable NebLink (keep server address), stop the client, clear user info
      // and peers. Reverses the device-flow login.
      //
      // NOTE (RP-logout fix, 2026-09-06): this endpoint is LOCAL-only teardown
      // — it never touches the provider's browser SSO session, so a login
      // right after it silently redirects back into the original account.
      // The full logout (local teardown + Logto end-session handoff) is
      // POST /neblink/auth/end-session below (POST + token since the 2026-09-20
      // closeout batch; the old GET arm is a gated 405); the web logout button
      // drives that one. This endpoint stays for API compatibility and scripted use.
      case req @ POST -> Root / "neblink" / "logout" =>
        withNeblink(req) { ms =>
          AuthRoutes.performLocalLogout(ms) *> Ok(ApiJson.ok)
        }

      // RP-initiated logout (OIDC Session Management, RP-logout fix
      // 2026-09-06; POST + gate since the 2026-09-20 closeout batch) — local
      // teardown FIRST (same steps as POST /neblink/logout above, credential read
      // before it is cleared), then the provider's end_session_endpoint URL is
      // returned as DATA (`{"endSessionUrl": …}`) instead of a 302: the caller is
      // now a `fetch` (a cross-origin 302 cannot be followed by fetch — it is
      // CORS-blocked and the body is opaque, so the target URL would be
      // unreachable), and it navigates a popup it reserved in the same gesture
      // tick (top-level navigation = no CORS). The URL carries the persisted
      // id_token as `id_token_hint` (valid hint = no confirmation page) and the
      // local `/auth/logged-out` landing page as `post_logout_redirect_uri`
      // (ignored by the provider until the uri is allow-listed on the Logto app —
      // probed 2026-09-06, always safe). The browser-side Logto session cookie
      // dies at that navigation, so the NEXT login shows the account page instead
      // of silently re-entering the old account.
      //
      // 🔴 门控（2026-09-20 收尾批 = 作者裁定 + 分发器定形）：旧注记
      // 「No gateway checkAuth BY DESIGN」（理由 = 浏览器导航跳拿不到
      // Authorization）**已作废** —— 同一形态也是「GET 带副作用」的反模式（凭据删除
      // 可由一次导航 / 链接预取 / 扫描器触发）。现在 **POST + 令牌** 才可达：客户端
      // `web/js/neblink.js` `openEndSessionHandoff` 在手势内先预约空白窗、再 fetch
      // （带 Authorization）、拿到 URL 后导航该窗。旧 GET 面留下**门内 405**（下一条 arm）。
      case req @ POST -> Root / "neblink" / "auth" / "end-session" =>
        withAuth(req) {
          // Body（两字段皆可选；缺失 / 空 / 非 JSON body = 纯登出，容错与
          // POST /neblink/auth/start 同款）：
          //   {"scenario":"switch","uiLocales":"zh"|"en"}
          //
          // Scenario marker (one-window switch, 2026-09-16): `scenario=switch` is
          // sent ONLY by the switch-account entry; it arms the single-use handoff
          // so the landing page this hop ends on continues into the login in the
          // SAME window. A plain logout explicitly DISARMS, so a leftover marker
          // can never drag the plain-logout landing page into an auto-login.
          // `uiLocales` is whitelisted exactly like /neblink/auth/start (the
          // landing hop is a bare navigation — it cannot read the app's locale).
          // 两者都从 query 迁到 POST body（本批：GET 面退场）。arm/disarm 调用点语义不变。
          req.attemptAs[Json].value.map(_.toOption).flatMap { bodyJson =>
            val switchScenario = bodyJson
              .flatMap(_.hcursor.downField("scenario").as[String].toOption)
              .contains("switch")
            val uiLocales = bodyJson
              .flatMap(_.hcursor.downField("uiLocales").as[String].toOption)
              .filter(v => v == "zh" || v == "en")
              .getOrElse("")
            val armOrDisarm =
              if switchScenario then AuthRoutes.switchHandoff.arm(uiLocales) else AuthRoutes.switchHandoff.disarm
            neblinkService match
              case None =>
                armOrDisarm *> NotFound(Json.obj("error" -> "NebLink service not initialized".asJson))
              case Some(ms) =>
                val run =
                  for
                    _ <- armOrDisarm
                    logto <- ms.neblinkConfig.map(_.effectiveLogto)
                    // Read the hint BEFORE performLocalLogout deletes the file.
                    //
                    // 🔴 缺陷 A（上游 §8.2 第 4 项 / 判据 G5）：读失败**不得**跳过本地拆除。
                    // 修前这一读异常裸冒泡 ⇒ 整条路由 500、拆除一步没跑（凭据没删、config
                    // 没关、client 没置空），用户因此**无法通过「退出账号」自救**（上游 S3）。
                    // 现在：读失败 ⇒ 只跳过 `id_token_hint`（登录态可能不完整），拆除照跑；
                    // 分类读数由存储层的 WARN 留档（带分类码），无需在这里再判一次。
                    credentialRead <- DeviceCredential.loadDiagnosed
                    idToken = credentialRead.toOption.flatten.flatMap(_.logto).flatMap(_.idToken)
                    resp <- logto.flatMap(lc => lc.pkceClientId.map(_ => lc)) match
                      case Some(lc) =>
                        val target = LogtoAuthCode.endSessionUrl(
                          lc.endpoint,
                          idToken,
                          Some(s"http://127.0.0.1:$gatewayPort/auth/logged-out")
                        )
                        for
                          _ <- AuthRoutes.performLocalLogout(ms)
                          _ <- logger.info(
                            "RP-initiated logout: local teardown done, returning the provider end_session URL"
                          )
                          // 出口 = 200 + JSON（不再是 302）：见上方注释（fetch 无法消费跨域 302）。
                          r <- Ok(Json.obj("endSessionUrl" -> target.asJson))
                        yield r
                      // Same surface as auth/start: unconfigured provider (or AC app
                      // id missing) — the caller falls back to the local-only logout,
                      // and no landing hop will ever come back to consume the marker.
                      case _ =>
                        AuthRoutes.switchHandoff.disarm *> NotFound(Json.obj("error" -> "logto-not-configured".asJson))
                  yield resp
                // 意外失败（拆除腿异常等）⇒ **可判读的失败页**：三段式文案，绝不再把裸异常
                // 变成无解释的 500（`getMessage` 直出 = 上游 §4 第 3 处丢失点）。
                run.handleErrorWith { e =>
                  val diagnostic = nebflow.neblink.CredentialDiagnostics.classifyFailure(
                    e,
                    nebflow.neblink.CredentialFailure.Unclassified
                  )
                  logger.warn(diagnostic.logLine("end-session failed"), "code" -> diagnostic.code) *>
                    AuthRoutes.htmlResponse(
                      AuthRoutes.callbackPage(ok = false, diagnostic.message),
                      Status.InternalServerError
                    )
                }
            end match
          }
        }

      // 旧 GET 面（本批退场，分发器 2026-09-20 定形 ⒜(ii)：**门内 405**）—— 旧调用方
      // （书签 / 脚本 / 旧页缓存）拿到自解释的「方法不对」而不是模糊 404；且因为它**在门内**
      // （先过 checkAuth），加门后的未门控集仍 = 恰 4 条（4 条设计面豁免），不新增普查条目。
      // 🔴 副作用不可达：本 arm 只回状态码，不 arm/disarm 标记、不碰凭据。
      case req @ GET -> Root / "neblink" / "auth" / "end-session" =>
        withAuth(req) {
          // 显式构造（DSL 的 `MethodNotAllowed` 只接受 `Allow` 头、不带体）：体自解释
          // （`error` + 迁移目标），调用方拿到 405 而非模糊 404。
          IO.pure(
            Response[IO](status = Status.MethodNotAllowed).withEntity(
              Json.obj(
                "error" -> "method-not-allowed".asJson,
                "allow" -> "POST".asJson
              )
            )
          )
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
                                // 未走 ApiJson 信封助手:{ok:true,message,networkId} 三键,非 {ok,message} 同形,保持手写(2026-09-25)
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
                      // The neblink-server URL: explicit request field > config > the
                      // public default — the last one is gated by 案 b① (isolated data
                      // root without the explicit switch ⇒ **no target**, see
                      // `neblinkServerUrl`). For device flow the server must be reachable
                      // from both the browser (for OAuth) and the device (for polling).
                      serverTarget <- neblinkServerUrl(None)
                      result <- serverTarget match
                        case Some(serverUrl) =>
                          val body = Json
                            .obj(
                              "deviceId" -> identity.deviceId.asJson,
                              "deviceName" -> identity.deviceName.asJson,
                              "platform" -> identity.platform.asJson
                            )
                            .noSpaces
                          proxyPost(serverUrl, nebflow.neblink.Protocol.DeviceApi.code, body)
                        case None =>
                          // 案 b①：无目标 ⇒ 零出站（不发起任何请求，更不注册）。
                          IO.pure(Left(EnrollGuard.prodFallbackRefusalReason))
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
                    serverTarget <- neblinkServerUrl(serverUrl)
                    resp <- serverTarget match
                      case None =>
                        // 案 b①：无目标 ⇒ 不轮询、不注册；文案与其余入口同源。
                        BadRequest(Json.obj("error" -> EnrollGuard.prodFallbackRefusalReason.asJson))
                      case Some(resolvedUrl) =>
                        for
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
                                          case Left(err) => Left(err)
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
      // Optional body {"forceLogin":true} → authorize prompt "login consent"
      // (RP-logout fix, 2026-09-06): the switch-account entry — `login`
      // forces the hosted account page even with a live Logto SSO session;
      // `consent` re-asks for consent on that same hosted page (UX: the account
      // chooser must be reached even when the provider SSO session is alive).
      // [O5, 2026-09-11] The offline_access/refresh-token invariant this comment
      // used to cite NO LONGER EXISTS — authorize requests `openid email
      // profile`, no refresh token is issued for a new login, and the persisted
      // identity comes from the id_token (see LogtoAuthCode.authorizeUrl /
      // DeviceCredentialStore.LogtoRefresh). Body is optional:
      // absent/empty/unparsable → plain login.
      // Optional body {"uiLocales":"zh"|"en"} (BYUI handoff ①, 2026-09-09):
      // forwarded as the OIDC ui_locales hint so the hosted page matches the
      // client UI language. WHITELISTED to "zh"/"en" — any other value (or an
      // absent field) resolves to "" and the param is omitted, which is also
      // the byte-identical legacy behavior for old callers.
      case req @ POST -> Root / "neblink" / "auth" / "start" =>
        if !checkAuth(req) then Forbidden(Json.obj("error" -> "Unauthorized".asJson))
        else
          neblinkService match
            case None => BadRequest(Json.obj("error" -> "NebLink service not initialized".asJson))
            case Some(ms) =>
              for
                // One body read feeds both optional fields (http4s streams a
                // request body once).
                bodyJson <- req
                  .attemptAs[Json]
                  .value
                  .map(_.toOption) // empty / non-JSON body = plain login
                forceLogin = bodyJson
                  .flatMap(_.hcursor.downField("forceLogin").as[Boolean].toOption)
                  .getOrElse(false)
                uiLocales = bodyJson
                  .flatMap(_.hcursor.downField("uiLocales").as[String].toOption)
                  .flatMap(v => if v == "zh" || v == "en" then Some(v) else None)
                  .getOrElse("")
                // Embedded-default fallback: missing logto block resolves to the
                // product's hosted auth service (fresh installs get PKCE login).
                // The authorize URL itself has ONE builder — [[beginPkceLogin]] —
                // shared with the switch-account landing continuation below.
                // An explicit login start supersedes any pending switch handoff
                // (the marker is single-use and must not survive a new attempt).
                _ <- AuthRoutes.switchHandoff.disarm
                authorizeUrl <- AuthRoutes.beginPkceLogin(ms, forceLogin, uiLocales)
                resp <- authorizeUrl match
                  case Some(url) => Ok(Json.obj("authorizeUrl" -> url.asJson))
                  // Logto unconfigured, or configured without the AC app id —
                  // the PKCE login surface treats both as "not configured".
                  // (Defensive: effectiveLogto always resolves via the embedded
                  // default, so this arm only fires if that invariant changes.)
                  case None => NotFound(Json.obj("error" -> "logto-not-configured".asJson))
              yield resp

      // Login-state poll for the frontend (pending while the hosted page is
      // open; success/error sticky until the next start).
      case req @ GET -> Root / "neblink" / "auth" / "state" =>
        if !checkAuth(req) then Forbidden(Json.obj("error" -> "Unauthorized".asJson))
        else AuthRoutes.pkceLogin.statusJson.flatMap(Ok(_))

      // Switch-account handoff readout (one-window switch, 2026-09-16). The app
      // window watches this after a switch gesture to tell the two cases apart:
      //   · "consumed" — the logout window's landing page took the continuation
      //     over (one window total, nothing to do);
      //   · anything else past the client deadline — the continuation never
      //     arrived (e.g. the gateway port is not on the provider's
      //     post_logout_redirect_uri allow-list, which the provider answers with
      //     400), so the app shows the login panel as the visible failure face
      //     instead of retrying a window.
      // READ-ONLY by construction: it must never consume/arm the marker
      // (only GET /auth/logged-out consumes; only end-session arms — the POST
      // arm since the 2026-09-20 closeout batch; the retired GET arm is a gated
      // 405 and touches no marker).
      case req @ GET -> Root / "neblink" / "auth" / "handoff" =>
        if !checkAuth(req) then Forbidden(Json.obj("error" -> "Unauthorized".asJson))
        else AuthRoutes.switchHandoff.stateName.flatMap(s => Ok(Json.obj("state" -> s.asJson)))

      // Cloud session sync toggle — removed (session sync deleted)

      // Update device capabilities / user description
      case req @ PUT -> Root / "neblink" / "device-info" =>
        withNeblink(req) { ms =>
          req.as[Json].flatMap { body =>
            val userDesc = body.hcursor.downField("userDescription").as[Option[String]].toOption.flatten
            val caps = body.hcursor.downField("capabilities").as[Option[Map[String, String]]].toOption.flatten
            ms.updateDeviceInfo(userDescription = userDesc, capabilities = caps) *>
              Ok(ApiJson.ok)
          }
        }

      // Update a peer device's description (local override)
      case req @ PUT -> Root / "neblink" / "peer-description" =>
        withNeblink(req) { ms =>
          req.as[Json].flatMap { body =>
            val deviceId = body.hcursor.downField("deviceId").as[String].toOption
            val desc = body.hcursor.downField("userDescription").as[String].toOption.getOrElse("")
            deviceId match
              case Some(did) => ms.updatePeerDescription(did, desc) *> Ok(ApiJson.ok)
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
                    case Left(err) => Ok(Json.obj("error" -> err.asJson, "output" -> "".asJson))
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
                        case Right(Left(err)) => Ok(Json.obj("error" -> err.message.asJson, "output" -> "".asJson))
                        case Left(e) =>
                          Ok(
                            Json.obj("error" -> s"Tool execution failed: ${e.getMessage}".asJson, "output" -> "".asJson)
                          )
                      }
                    case None =>
                      BadRequest(Json.obj("error" -> s"Unknown tool: $action".asJson))
                  end match
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
                      Ok(ApiJson.okMessage(msg))
                  case Left(err) =>
                    // 未走 ApiJson 信封助手:此处为 Ok(200)+{ok:false,error}(无 message 键),与 ok 信封族不同形且状态码属客户端可见契约,保持手写(2026-09-25)
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
                  // 未走 ApiJson 信封助手:成功/失败腿均带业务字段({ok:true,path,size} / {ok:false,error}),非裸 ok 信封同形,保持手写(2026-09-25)
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
                case Right(_) => Ok(ApiJson.ok)
                case Left(err) =>
                  // xferb 批（P0-3）：失败响应带**结构化原因**（code + errorDetail 全文），
                  // 与 `dropboxError` / `dropbox-file-complete` 事件同形态 —— 前端据此回显
                  // 可判读原因，而不是一句人读文本（第二段上屏消费本字段）。
                  // 未走 ApiJson 信封助手:{ok:false,error,errorCode,errorDetail} 结构化四键,非裸 ok 信封同形,保持手写(2026-09-25)
                  Ok(
                    Json.obj(
                      "ok" -> false.asJson,
                      "error" -> err.render.asJson,
                      "errorCode" -> err.code.asJson,
                      "errorDetail" -> err.toJson
                    )
                  )
              }
        }

      // Peer pushes a file (or one chunk) via HTTP (peer IP auth).
      //
      // 附件腿批（2026-09-12）：同一端点承载两种形态，靠**分块头**区分 ——
      //   - 带 `X-Dropbox-Proto: 1` + index/total/chunk-size/chunk-sha/whole-sha ⇒ 分块模式
      //     （按 offset 追加、幂等重放、gap 拒绝、末块整件摘要**接收端自算**）；
      //   - 无该头 ⇒ **legacy 整件模式**，行为与今天逐字节一致（旧发送端零回归）。
      // 端点路径与鉴权不变（不放松）。
      case req @ POST -> Root / "neblink" / "dropbox" / "transfer" / transferId =>
        verifyPeerAccess(req).flatMap {
          case Left(resp) => IO.pure(resp)
          case Right(_) =>
            sharedResources.dropboxService match
              case None => NotFound(Json.obj("error" -> "Dropbox not enabled".asJson))
              case Some(svc) =>
                val chunkHeaders = parseDropboxChunkHeaders(req)
                val receive: IO[Response[IO]] = chunkHeaders match
                  case Some(h) =>
                    // 分块模式：回执 = **接收端自算**的块摘要 + 权威 offset（R4：不是请求头回显
                    // —— 回显会让发送端 `ack.chunkSha256 == frame.chunkSha256` 的比对恒真）。
                    // 末块的 `wholeSha256` 同样只由接收端自算后写入（`ChunkAck.wholeSha256`）。
                    // 未走 ApiJson 信封助手:{ok:true,chunkSha256,bytesReceived,wholeSha256} 为分块回执载荷,非裸 ok 信封同形,保持手写(2026-09-25)
                    svc.receiveChunkFromPeer(transferId, req.body, h).map {
                      case Right(ack) =>
                        Response[IO](Status.Ok).withEntity(
                          Json.obj(
                            "ok" -> true.asJson,
                            "chunkSha256" -> ack.chunkSha256.asJson,
                            "bytesReceived" -> ack.bytesReceived.asJson,
                            "wholeSha256" -> ack.wholeSha256.map(_.asJson).getOrElse(Json.Null)
                          )
                        )
                      case Left(err) => chunkErrorResponse(err)
                    }
                  case None =>
                    svc.receiveFromPeer(transferId, req.body, None).map {
                      case Right(hash) => Response[IO](Status.Ok).withEntity(Json.obj("sha256" -> hash.asJson))
                      case Left(err) => chunkErrorResponse(err)
                    }
                receive.handleErrorWith(e =>
                  // 未走 ApiJson 信封助手:500+{ok:false,error},状态码与 ok=false 形态均为客户端可见契约,保持手写(2026-09-25)
                  IO.pure(
                    Response[IO](Status.InternalServerError)
                      .withEntity(Json.obj("ok" -> false.asJson, "error" -> e.getMessage.asJson))
                  )
                )
        }

      // 断点续传探针（peer IP auth）：返回接收端权威 offset 与其**重算**的前缀摘要。
      case req @ GET -> Root / "neblink" / "dropbox" / "probe" / transferId =>
        verifyPeerAccess(req).flatMap {
          case Left(resp) => IO.pure(resp)
          case Right(_) =>
            sharedResources.dropboxService match
              case None => NotFound(Json.obj("error" -> "Dropbox not enabled".asJson))
              case Some(svc) =>
                svc.probeTransfer(transferId).map { probe =>
                  probe match
                    case Right(state) =>
                      Response[IO](Status.Ok).withEntity(
                        Json.obj(
                          "bytesReceived" -> state.bytesReceived.asJson,
                          "totalBytes" -> state.totalBytes.asJson,
                          "prefixSha256" -> state.prefixSha256.asJson
                        )
                      )
                    case Left(err) => Response[IO](Status.NotFound).withEntity(err.toJson)
                }
        }
      // Gateway-mediated remote update: CLI sends this, gateway does P2P first then relay
      case req @ POST -> Root / "neblink" / "remote-update" =>
        withAuth(req) {
          neblinkService match
            // 未走 ApiJson 信封助手:remote-update 族用 success 键(非 ok),字段名属跨端契约,保持手写(2026-09-25)
            case None => Ok(Json.obj("success" -> false.asJson, "error" -> "NebLink not enabled".asJson))
            case Some(ns) =>
              req.as[Json].flatMap { body =>
                val targetDevice = body.hcursor.downField("device").as[String].getOrElse("")
                val beta = body.hcursor.downField("beta").as[Boolean].getOrElse(false)
                // 幂等键（hotupdate 批 3 · G8）：**可选**读取（缺席 = 现行为逐字节不变）；
                // 空串归一成缺席（与「本次未带键」同语义，见契约 §B.2「缺席 = 本次未带键」）。
                val clientRequestId = body.hcursor
                  .downField("clientRequestId")
                  .as[String]
                  .toOption
                  .map(_.trim)
                  .filter(_.nonEmpty)
                doRemoteUpdate(ns, targetDevice, beta, clientRequestId).flatMap {
                  // 未走 ApiJson 信封助手:remote-update 族用 success 键(非 ok),字段名属跨端契约,保持手写(2026-09-25)
                  case Right(msg) => Ok(Json.obj("success" -> true.asJson, "message" -> msg.asJson))
                  case Left(err) => Ok(Json.obj("success" -> false.asJson, "error" -> err.asJson))
                }
              }
        }

      /**
       * MVP-2 设备会话域统一（2026-09-15）：**设备会话发送面**
       * （`POST /api/devices/{device_id}/messages` → neblink-server
       * `src/friends.rs:1300 device_send_message`）。
       *
       * 段名是 **`devices`（复数）**：与服务端路由表逐字对齐（`src/friends.rs:1788`），
       * 且与既有的单数 `/api/device/` 设备认证面（`device/code`、`device/token` 等）
       * **命名空间不相交** —— 两者共享前缀会让「设备授权流」与「设备消息」两条语义
       * 完全不同的面在路由分派上互相遮挡。
       *
       * 🔴 **`origin` 闸**（复用 [[GroupSendOriginVerdict]] 的**同一**判据，不新写一套）：
       * 本路由同样是**用户身份直发**面（web 前端是它的唯一调用者，前端不得自报
       * `origin:"agent"`——那会把非 agent 通道的消息落库成 agent 代发，而
       * 「收端禁采信 wire `origin`」正是本批 P3 红线）。缺席 / 逐字 `"user"` ⇒ 原文
       * 转发（= 服务端缺省语义，字节零变化）；其余值 ⇒ `400` 显式拒绝，**零上游往返**。
       * 显式拒绝而非静默改写：静默剔键会让一次越界自报**静默消失**（本仓禁止的
       * 「静默不达」缺陷族）。
       *
       * 🔴 **请求体按原文转发**（[[rawBody]]）：不解析、不重编码 —— 解析后再编码会
       * 重排键并丢掉未知键（`attachments` 等加性键），等于替冻结契约改了形态。
       * `{device_id}` 路径段先 [[encSeg]] 编码（段内 `/`、`?` 注入面）。
       *
       * 🔴 幂等语义原样交给服务端（契约 §8.6：同 `clientMsgId` 重复 ⇒ **仍是 201**，
       * `existing:true` 仅表示回放原行）。本层**不**把 `existing` 折成别的状态码。
       */
      case req @ POST -> Root / "devices" / deviceId / "messages" =>
        withAuth(req) {
          sharedResources.friendService match
            case None => NotFound(Json.obj("error" -> "NebLink not enabled".asJson))
            case Some(fs) =>
              rawBody(req).flatMap { body =>
                GroupSendOriginVerdict.check(body) match
                  case Left(reason) => BadRequest(Json.obj("error" -> reason.asJson))
                  case Right(()) =>
                    // 转发复用 [[groupProxy]] 的**同一实现**（同一 `(status, body)` 保留语义、
                    // 同一 live-client 缝、同一「身份只由 device session token 承载」纪律）。
                    // 该入口是「按原文转发任意上游路径」的通用代理口，群面只是它的第一个
                    // 调用方 ⇒ 设备面直接复用，**禁**为它复制第二份转发实现。
                    fs.groupProxy("POST", s"/api/devices/${encSeg(deviceId)}/messages", body)
                      .flatMap(groupProxyResult)
              }
        }
    }

  end routes

  /**
   * Shared remote-update logic: P2P HTTP first, relay fallback. Used by REST + WS handlers.
   *
   * `clientRequestId`（hotupdate 批 3 · G8）= **可选**幂等键：仅在 P2P 载荷里作为
   * 加法字段随行（老端对端 read 只见 `beta`，未知键静默忽略）；缺席 ⇒ 载荷与改前
   * 逐字节相同。🔴 中继腿（`relayUpdateFallback`）的隧道参数面保持 `{beta}` 不变
   * ——隧道动作 `RemoteUpdate` 的参数集由跨仓契约钉死（契约 §B.1.3），本批零越仓。
   */
  private def doRemoteUpdate(
    ns: nebflow.neblink.NeblinkService,
    targetDevice: String,
    beta: Boolean,
    clientRequestId: Option[String] = None
  )(using ctx: RestApiCtx): IO[Either[String, String]] =
    import ctx.*

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
                val fields = List("beta" -> beta.asJson)
                  ++ clientRequestId.map(id => "clientRequestId" -> id.asJson)
                val body = Json.obj(fields*).noSpaces
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

  end doRemoteUpdate

  private def relayUpdateFallback(
    ns: nebflow.neblink.NeblinkService,
    peer: nebflow.neblink.PeerInfo,
    beta: Boolean,
    p2pError: String
  )(using ctx: RestApiCtx): IO[Either[String, String]] =
    import ctx.*

    ns.relayClientOpt match
      case Some(client) =>
        logger.info(s"P2P update failed ($p2pError), trying relay to ${peer.deviceName}") *>
          client.relayUpdate(peer.deviceId, beta)
      case None => IO.pure(Left(p2pError))

  /** Run block only if NeblinkService is available and request is authenticated. */
  private def withNeblink(req: Request[IO])(f: NeblinkService => IO[Response[IO]])(using
    ctx: RestApiCtx
  ): IO[Response[IO]] =
    import ctx.*

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
  private def verifyPeerAccess(req: Request[IO])(using ctx: RestApiCtx): IO[Either[Response[IO], NeblinkService]] =
    import ctx.*

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

        end if

    end match

  end verifyPeerAccess

  /**
   * 解析 Dropbox 分块头。**只有** `X-Dropbox-Proto` 明确为 1（且其余必需头齐备）时才
   * 返回 `Some` —— 任何缺失 ⇒ `None` ⇒ 走 legacy 整件路径（向后兼容的判定依据，
   * 契约 §3.9「未知/缺失字段不得静默到看似成功」：这里「缺失」有明确定义的降级行为）。
   *
   * 🔴 **判据**（等值，非 `>=`）**与头值（恒 1）都不得改动** —— 本批契约升版（设备腿
   * `targetDir`，`AttachContract.ProtoAssignDir = 2`）**只升 JSON 面数值轴**。把发送端
   * 头值升成 `2` 会让旧接收端 guard 为假 ⇒ 走 legacy 整件 ⇒ 静默数据损坏；把 `==` 改成
   * `>=` 则让新头被本次实现接受、却对旧端仍无救（掩盖破坏面）。实现体已逐字抽到
   * [[nebflow.dropbox.DropboxChunkHeaderParser]]（可判定性抽出，语义零改动），
   * 常绿钉见 `AttachProtoHeaderPinSpec`。
   */
  private def parseDropboxChunkHeaders(req: Request[IO]): Option[nebflow.dropbox.DropboxService.ChunkHeaders] =
    nebflow.dropbox.DropboxChunkHeaderParser.parse(req.headers)

  /** 分块接收失败的结构化回执（会话不存在 ⇒ 404，其余 ⇒ 500）。 */
  private def chunkErrorResponse(err: nebflow.dropbox.AttachContract.AttachError): Response[IO] =
    val status =
      if err.code == nebflow.dropbox.AttachContract.Codes.SessionNotFound then Status.NotFound
      else Status.InternalServerError
    Response[IO](status).withEntity(err.toJson)

  /**
   * POST the enrollment body to the NebLink Server and parse the JSON reply.
   * Uses java.net.http directly (mirrors NeblinkClient) to avoid pulling an
   * http4s client dependency into this routes class. Bypasses the system proxy
   * so direct LAN access works.
   *
   * Isolation guard (2026-09-11): single choke point for the pairing-code
   * enroll path — an instance on a redirected data root does not auto-register
   * with the production network (see `EnrollGuard`). Refusal is returned on the
   * error channel (caller renders "Enrollment failed: …") AND logged, so it is
   * never silent; `NEBFLOW_ALLOW_PROD_ENROLL=1` restores the old behaviour.
   */
  private def enrollWithServer(serverUrl: String, body: String)(using ctx: RestApiCtx): IO[Either[String, Json]] =
    import ctx.*

    nebflow.neblink.EnrollGuard.enrollRefusal(serverUrl) match
      case Some(reason) =>
        logger.warn(s"enroll refused by the isolation guard: $reason").as(Left(reason))
      case None =>
        proxyPost(serverUrl, nebflow.neblink.Protocol.DeviceApi.enroll, body)

  /**
   * Shared completion for BOTH device-flow paths (self-hosted token poll
   * and Logto register) and the AC+PKCE callback: read the EnrollResponse
   * fields, persist the credential, switch the config, hot-swap the client,
   * and record the user's profile info. `logtoRefresh` carries the provider
   * refresh token (AC+PKCE / silent re-login) into the persisted credential.
   *
   * `explicitUserAction` (2026-09-14 案 C ①(b)) is forwarded to the isolation
   * gate; only the PKCE callback — downstream of a matched, single-use login
   * state — passes `true`. Default `false` = the device-flow poll path, which
   * has no server-side marker proving who started it.
   */
  private def completeDeviceEnrollment(
    ms: NeblinkService,
    resolvedUrl: String,
    json: Json,
    logtoRefresh: Option[String] = None,
    logtoIdToken: Option[String] = None,
    explicitUserAction: Boolean = false
  )(using ctx: RestApiCtx): IO[org.http4s.Response[IO]] =
    import ctx.*

    completeDeviceEnrollmentDetailed(ms, resolvedUrl, json, logtoRefresh, logtoIdToken, explicitUserAction)
      .flatMap {
        case Right(networkId) =>
          // 未走 ApiJson 信封助手:{ok:true,networkId} 两键但非 message,与 {ok,message} 族不同形,保持手写(2026-09-25)
          Ok(
            Json.obj(
              "ok" -> true.asJson,
              "networkId" -> networkId.asJson
            )
          )
        // 案 C ①(a)：不再把失败压成泛化串——`err` 原文（隔离护栏拒绝时就是
        // `EnrollGuard` 的 reason）走调用方的渲染面。
        case Left(err) => BadRequest(Json.obj("error" -> err.asJson))
      }

  end completeDeviceEnrollment

  /**
   * Generic POST proxy to the NebLink Server. Returns the parsed JSON on
   * success (2xx) or an error message on failure. Bypasses the system proxy.
   */
  private def proxyPost(serverUrl: String, path: String, body: String): IO[Either[String, Json]] =
    IO.blocking {
      // The shared OutboundHttpClients.Policy.Direct15s client: HTTP/1.1, system
      // proxy bypassed, 15 s connect. Previously built per call (device-flow
      // code/token/enroll) — now one memoized instance (D5, 2026-09-13).
      //
      // D1 — why HTTP/1.1 here, and what would flip it:
      //   · Evidence: this peer is the neblink-server, behind the SAME Caddy as
      //     NeblinkClient. The 2026-09-12 probe of that topology served 14/14
      //     requests 200 with ALPN=h2, 0 GOAWAY, 0 TLS alert, 5/5 TLS 1.3
      //     resumptions accepted ⇒ the old "HTTP/2 reuse + TLS 1.3 resumption
      //     clashes with Caddy" sentence has no support on this link; it was
      //     copied from the 2026-08-11 upstream (nginx/one-api) incident (42fd15b6
      //     self-describes it as "same TLS fix as 3773699b"). 未证 either way:
      //     the original symptom was intermittent and left no logs, so one
      //     green local window does not prove the trap absent.
      //   · Judge-red: a GOAWAY / closed-reset bucket on this non-idempotent
      //     POST in the outbound-failure counters, or a reproduced TLS alert,
      //     or same-window h2 p95 > h1 p95 × 1.2 (n ≥ 100/arm) — then re-review
      //     the pin (not before; no version decision moves on this evidence).
      val client = OutboundHttpClients.client(OutboundHttpClients.Policy.Direct15s)
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
end NeblinkRoutes
