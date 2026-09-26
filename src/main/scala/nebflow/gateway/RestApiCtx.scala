/* 从 RestApiRoutes 迁出(行为保持重构,2026-09-24)。 */
package nebflow.gateway

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.syntax.*
import io.circe.{Json, parser}
import nebflow.agent.SharedResources
import nebflow.llm.NebflowServiceConfig
import nebflow.neblink.*
import nebflow.shared.NebflowLogger
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*
import org.http4s.headers.Authorization
import org.typelevel.ci.CIString

/**
 * REST 域分发上下文(B 步起,仿 WsDispatchCtx 先例,行为保持重构 2026-09-24):
 * val 成员为 RestApiRoutes 构造参数与 logger 的引用。
 *
 * F 步(2026-09-24)起本类同时是**跨域共用助手的单一定义点**:withAuth /
 * checkAuth / friendErr / groupProxyResult / rawBody / encSeg / presence 判据族 /
 * neblinkServerUrl / completeDeviceEnrollmentDetailed 的实现自 RestApiRoutes
 * 类内整体迁入此处(HealthRoutes / SessionRoutes / ConfigRoutes / ProjectsRoutes /
 * RegistryRoutes / PresenceRoutes / NeblinkRoutes / SocialRoutes / AuthRoutes 均
 * 经 `import ctx.*` 调用,全仓一份实现,禁两处各写一套);presence 域专属助手
 * (preset/flow 校验写回族、socialErrorResponse、checkHttpBaseUrl)随真实调用方
 * 迁入 PresenceRoutes。RestApiRoutes 类收敛为编排器,仅保留测试面(GatewayMain
 * 与 DeviceFaceHardeningRoutesSpec 所需)的同名委托。
 *
 * withAuth 第二参数保持 by-name(与迁出前的类内方法一致):调用点传入的 IO 构造
 * 表达式按闸延迟求值。
 */
final class RestApiCtx(
  val token: String,
  val configRef: Ref[IO, NebflowServiceConfig],
  val sharedResources: SharedResources,
  val sessionStore: SessionStore,
  val wsRoutes: WebSocketRoutes,
  val neblinkService: Option[NeblinkService],
  val ttsService: Option[TtsService],
  val neblinkDiscovery: Option[nebflow.neblink.NeblinkDiscovery],
  val gatewayPort: Int,
  val wsHub: WsHub,
  val connGuard: ConnGuard,
  val logger: NebflowLogger
):

  def withAuth(req: Request[IO])(f: => IO[Response[IO]]): IO[Response[IO]] =
    if checkAuth(req) then f
    else Forbidden(Json.obj("error" -> "Unauthorized".asJson))

  // 设备互联域(NeblinkRoutes,E 步)所需的鉴权判据:checkAuth 同时服务本处
  // withAuth 与 NeblinkRoutes 的免门控普查条目直调。
  def checkAuth(req: Request[IO]): Boolean =
    req.headers.get[Authorization].collectFirst { case Authorization(Credentials.Token(AuthScheme.Bearer, t)) =>
      t
    } match
      // 接受面双轨（patbackend 批，2026-09-20）：轨 1 = 本机文件令牌（Auth，逐字不变）；
      // 轨 2 = Logto 签发的 PAT（自包含 JWT，用 JWKS 公钥**离线**验签；逻辑全在 PatAuth，
      // 本行只做薄委调）。两轨皆否 ⇒ 同一个 Forbidden，对外不区分原因（防枚举）。
      case Some(t) => Auth.validateToken(t, token) || PatAuth.accepts(t)
      case None =>
        req.params.get("token").exists(t => Auth.validateToken(t, token))

  // ===== 好友域上游错误的单一判据（2026-09-11 boot 快照修复，R3(a)） =====

  /**
   * 本网关是否配置了 NebLink（server 址存在）？**live 读 config ref**
   * （同既有先例 `neblinkServerUrl` / `ms.relayTunnelOpt`，不得引入新的 boot
   * 快照）。未配置 ⇒ 好友域维持 `404 NebLink not enabled`，前端
   * `errKind='neblinkOff'` 保持可表达（其 retry 只对「已配置但暂时失败」有意义）。
   */
  private def neblinkConfigured: IO[Boolean] =
    neblinkService match
      case Some(ms) => ms.neblinkConfig.map(_.neblinkServer.isDefined)
      case None => IO.pure(false)

  /**
   * 好友域上游失败的**单一**应答判据。三个渲染上游 `Left` 的落点共用它：
   * `friendResult` / `friendResultRaw` / `GET /friends` 内联（其余好友路由全部
   * 经前两个 helper 汇聚，禁逐处复制粘贴分叉）。
   *
   *  - `"Not logged in"`（`FriendService.withClient`：登出 / 从未 enroll）
   *    **且已配置** ⇒ **401** + `code=neblink_not_logged_in`。
   *    **有意与 withAuth 的 403 分化**（2026-09-11 复核 N4）：网关自身鉴权缺失
   *    是 403 `Unauthorized`，NebLink 会话缺失是 401；同一端点族的两种
   *    「未认证」靠 `code` 字段消歧，**不要「统一」掉**（前端 401/403 都映射
   *    到 auth → 重登引导，语义一致）。
   *  - `"Not logged in"` **且未配置** ⇒ **404** `NebLink not enabled`
   *    （与修前 wire 契约逐字一致——缺了这条就是净回归：前端把 502 认成
   *    `retryable`、重试恒无效）。
   *  - 其余上游错误 ⇒ **502**（不变）。🟡 **例外（rcptcode 批，2026-09-20）**：好友
   *    **发送**路由（`POST /friends/{id}/messages`）已改走「保留状态码」通道
   *    （`sendAsUserWithStatus` + `groupProxyResult`）⇒ 该腿的上游 4xx/5xx **逐字**
   *    到达客户端、**不经本判据**；其余好友路由（列表/请求/备注/拉黑/已读/搜索）
   *    **继续**走本判据（是否推广是另一刀）。
   */
  def friendErr(err: String): IO[Response[IO]] =
    if err != "Not logged in" then BadGateway(Json.obj("error" -> err.asJson))
    else
      neblinkConfigured.flatMap {
        case true =>
          // 显式构造（http4s 的 `Unauthorized(...)` 有 WWW-Authenticate 重载，
          // 直接传 Json 会命中它）——不带 WWW-Authenticate：这不是 HTTP 层面的
          // 401 challenge，是 NebLink 会话缺失的应用层状态。
          IO.pure(
            Response[IO](Status.Unauthorized)
              .withEntity(Json.obj("error" -> "Not logged in".asJson, "code" -> "neblink_not_logged_in".asJson))
          )
        case false =>
          NotFound(Json.obj("error" -> "NebLink not enabled".asJson))
      }

  /**
   * 请求体**逐字**取原文（代理腿专用；空体 ⇒ `""`）。
   *
   * 用 `bodyText.compile.string` 而**不**用 `req.as[Json]`：后者把 body 解析成 AST
   * 再序列化回去会重排键 / 丢未知键 / 改数字字面量 ⇒ 上游收到的字节与客户端发的不
   * 同形。代理腿的职责是搬运字节，不是理解它。
   */
  def rawBody(req: Request[IO]): IO[String] =
    req.bodyText.compile.string

  /**
   * 上游 `(status, body)` ⇒ 本网关响应：**状态码逐字**，体优先 JSON 解析。
   *
   * 🔴 禁吞：既不把上游 4xx 折成 500 / 502，也不把错误折成「空成功」——群域三码
   * （`group_not_found` / `group_disbanded` / `not_member`）必须原样到达客户端。
   * 非 JSON 体（网关/代理层注入的 HTML 错误页等）包成 `{"error":<原文>}`：既保住
   * 可判读性，又不让一次体解析失败把响应升级成 500。空体保持空体（不透传伪实体）。
   */
  def groupProxyResult(result: Either[String, (Int, String)]): IO[Response[IO]] =
    result match
      case Left(err) => friendErr(err)
      case Right((code, body)) =>
        val status = Status.fromInt(code).getOrElse(Status.BadGateway)
        IO.pure(
          if body.isBlank then Response[IO](status)
          else
            Response[IO](status).withEntity(
              parser.parse(body).getOrElse(Json.obj("error" -> body.asJson))
            )
        )

  /**
   * 路径段编码（代理腿转发用）：避免上游路径被段内容改写（段内 `/`、`?` 注入）。
   * 与 `NeblinkClient.enc` 同法，只把 `+` 归一成 `%20`（`URLEncoder` 的
   * `application/x-www-form-urlencoded` 口径在路径段里会变成字面 `+`）。
   */
  def encSeg(s: String): String =
    java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

  /**
   * Identity claimed by the peer opening a presence WS upgrade.
   *
   * A1 (2026-09-20 device-face hardening batch): the handshake HEADER
   * ([[nebflow.neblink.Protocol.DeviceHeader]] — the same channel
   * [[verifyPeerAccess]] already reads on the REST peer face) is the primary
   * carrier, so deviceId stops travelling in the URL. The legacy `?deviceId=`
   * query param stays as the FALLBACK: dialers built before the change send
   * only that, and dropping it would refuse every existing peer.
   */
  private[gateway] def presencePeerDeviceId(req: Request[IO]): String =
    // Fully qualified on purpose: `org.http4s._` (imported after
    // `nebflow.neblink._`) also defines a `Protocol`, so the bare name is
    // ambiguous at this call site.
    req.headers
      .get(CIString(nebflow.neblink.Protocol.DeviceHeader))
      .map(_.head.value)
      .map(_.trim)
      .filter(_.nonEmpty)
      .getOrElse(req.params.getOrElse("deviceId", ""))

  /**
   * Network-membership check by device ID. True when the caller claims a
   * deviceId we discovered via the NebLink Server (a member of our networkId),
   * the request originates from a private/LAN address, AND that peer is still
   * FRESH (within the online window, see [[NeblinkService.isPeerOnline]]).
   *
   * A2 (2026-09-20 device-face hardening batch): the freshness leg is the new
   * half. A peer id is not a secret (it is published, see the A1 half of the
   * same batch) and the peer map deliberately KEEPS rows for peers the server
   * has flagged offline (`lastSeen = 0`, `NeblinkService.applyServerPeerStatus`
   * :374), so "is a known deviceId" alone stayed true forever after a peer went
   * away — including after DHCP handed its old address to a different host (the
   * address-reuse row of the threat table). The freshness predicate is REUSED,
   * not re-derived: [[NeblinkService.isPeerOnline]] with the configured
   * `syncIntervalSec`, exactly as documented there (floor 90s = the server's
   * own online TTL, 2x the sync interval).
   */
  def isKnownNetworkDevice(
    ms: NeblinkService,
    claimedDeviceId: String,
    remoteIp: String
  ): IO[Boolean] =
    if claimedDeviceId.isEmpty || !isPrivateLanIp(remoteIp) then IO.pure(false)
    else
      for
        peers <- ms.peers
        cfg <- ms.neblinkConfig
      yield isFreshKnownPeer(peers, claimedDeviceId, System.currentTimeMillis(), cfg.syncIntervalSec)

  /**
   * Pure form of the membership + freshness leg, so the criterion is testable
   * without a live [[NeblinkService]] (see `PeerCriterionFreshnessSpec`).
   */
  private[gateway] def isFreshKnownPeer(
    peers: List[PeerInfo],
    claimedDeviceId: String,
    nowMs: Long,
    syncIntervalSec: Int
  ): Boolean =
    peers.exists(p => p.deviceId == claimedDeviceId && NeblinkService.isPeerOnline(p, nowMs, syncIntervalSec))

  /**
   * Private addresses a LAN-direct P2P peer can legitimately come from:
   * loopback, RFC1918 (10/8, 172.16/12, 192.168/16) and IPv6 `::1`.
   *
   * A2 (2026-09-20 device-face hardening batch): IPv4 link-local
   * `169.254.0.0/16` and IPv6 link-local `fe80::/10` are REMOVED here. They are
   * not "private LAN" ranges (RFC1918/loopback) at all, and any host on the wire
   * may self-assign them — so they let a caller satisfy "came from a private
   * address" without ever being part of the trusted network. The legitimate
   * private ranges below are unchanged (zero narrowing beyond those two).
   */
  private[gateway] def isPrivateLanIp(rawIp: String): Boolean =
    val ip = rawIp.stripPrefix("::ffff:")
    ip == "127.0.0.1" || ip == "::1" ||
    ip.startsWith("10.") ||
    ip.startsWith("192.168.") ||
    ip.startsWith("172.") && {
      val octet = ip.split('.').lift(1).flatMap(_.toIntOption).getOrElse(-1)
      octet >= 16 && octet <= 31
    }

  /**
   * `completeDeviceEnrollment` 的结果通道版本：`Right(networkId)` = 已落盘并热换，
   * `Left(err)` = **真实失败原因原文**（护栏拒绝 ⇒ `EnrollGuard` 的 reason）。
   * 回调页需要它来透真因（案 C ①(a)）；HTTP 形态由调用方决定。
   */
  def completeDeviceEnrollmentDetailed(
    ms: NeblinkService,
    resolvedUrl: String,
    json: Json,
    logtoRefresh: Option[String] = None,
    logtoIdToken: Option[String] = None,
    explicitUserAction: Boolean = false
  ): IO[Either[String, String]] =
    persistEnrollment(ms, resolvedUrl, json, logtoRefresh, logtoIdToken, explicitUserAction).map(
      _.map(_ => json.hcursor.downField("networkId").as[String].toOption.getOrElse(""))
    )

  /**
   * The enrollment half of completeDeviceEnrollment — delegates to
   * NeblinkEnrollment (shared with the startup client's silent re-login
   * hook, which has no HTTP context). Returns the persisted device token.
   */
  private def persistEnrollment(
    ms: NeblinkService,
    resolvedUrl: String,
    json: Json,
    logtoRefresh: Option[String],
    logtoIdToken: Option[String] = None,
    explicitUserAction: Boolean = false
  ): IO[Either[String, String]] =
    NeblinkEnrollment.persist(
      ms,
      resolvedUrl,
      json,
      logtoRefresh,
      neblinkDiscovery,
      gatewayPort,
      reloginHook = Some(LogtoSilentRelogin.make(ms, IO.pure(neblinkDiscovery), gatewayPort, neblinkServerUrl(None))),
      logtoIdToken = logtoIdToken,
      explicitUserAction = explicitUserAction
    )
  end persistEnrollment

  /**
   * Resolve the NebLink Server URL for device-flow requests. Priority:
   * 1. Explicitly provided URL (from the request body).
   * 2. URL from the current neblink config.
   * 3. The public default URL — 🔴 **suppressed on a redirected data root**
   *    (案 b①，2026-09-20 作者令 · 测试卫生).
   *
   * `None` = 「**没有目标**」：隔离实例既无显式 URL 也无配置 URL，就**不得**悄悄继承
   * 生产默认当入网目标（今晚事故链：隔离 home 自铸身份 → 本回落 → 案 C 显式登录 →
   * `POST /api/device/register` 打到 `neblink.nebflow.space`）。调用点把 `None` 变成
   * **可见失败**（[[EnrollGuard.prodFallbackRefusalReason]]），放行通道 =
   * `NEBFLOW_ALLOW_PROD_ENROLL=1`，置上后回落与改前**逐字相同**。
   *
   * 默认数据根 / 显式 URL / 配置 URL 三条路径零行为变化（判据不在本函数，而在
   * `EnrollGuard.prodDefaultTarget` —— 单点）。
   */
  def neblinkServerUrl(explicit: Option[String] = None): IO[Option[String]] =
    explicit match
      case Some(url) => IO.pure(Some(url))
      case None =>
        neblinkService match
          case Some(ms) =>
            ms.neblinkConfig.map(_.neblinkServer.map(_.url)).flatMap {
              case Some(url) => IO.pure(Some(url))
              case None => prodDefaultTargetIO
            }
          case None => prodDefaultTargetIO

  /**
   * Last-resort enrollment target (`Branding.serverUrl`, gated by [[EnrollGuard]]).
   * The refusal is loud, never silent — the log line is the 「零注册出网」的日志面判据。
   */
  private def prodDefaultTargetIO: IO[Option[String]] =
    EnrollGuard.prodDefaultTarget match
      case some @ Some(_) => IO.pure(some)
      case None =>
        logger
          .warn(
            "isolated data root: the production server default is NOT used as an enrollment target " +
              s"(案 b①) — ${EnrollGuard.prodFallbackRefusalReason}",
            "code" -> CredentialFailure.EnrollRefusedIsolatedHome.code
          )
          .as(None)

end RestApiCtx
