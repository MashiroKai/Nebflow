package nebflow.gateway

import cats.effect.IO
import cats.syntax.all.*
import nebflow.agent.SharedResources
import nebflow.core.SessionStore
import nebflow.llm.NebflowServiceConfig
import nebflow.neblink.{NeblinkService, PeerInfo}
import org.http4s.server.websocket.WebSocketBuilder2
import org.http4s.{HttpRoutes, Request}

/**
 * REST API routes for CLI consumption.
 * These endpoints mirror the WebSocket message handlers but over HTTP.
 *
 * F 步(2026-09-24)起本类收敛为**编排器**:routes 巨型 match 已全部按域迁出
 * (HealthRoutes / SessionRoutes / ConfigRoutes / ProjectsRoutes / RegistryRoutes /
 * NeblinkRoutes / SocialRoutes,PresenceRoutes 经 presenceWsRoutes,AuthRoutes 经
 * authCallbackRoutes),级联顺序 = 原 case 目录顺序(唯一重排:PATCH /config 随
 * config 域整体前移到 /projects 域之前,两域首段路径字面量互不重叠 ⇒ 匹配行为
 * 逐字不变);跨域共用助手实现单点在 RestApiCtx,presence 域专属助手在
 * PresenceRoutes。
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
  wsHub: WsHub = new WsHub,
  /**
   * R-1b conn-guard：presence WS 受理面 per-IP 看护 + /health/conn 读数源。
   * 缺省 = 全放行实例（既有测试构造点零改动）；生产由 GatewayMain 注入实配。
   */
  connGuard: ConnGuard = ConnGuard.disabled
):
  private val logger = nebflow.shared.NebflowLogger.forName("nebflow.rest-api")

  /**
   * REST 域分发上下文(B 步起,仿 WebSocketRoutes.wsDispatchContext 先例):把各
   * 域 routes 所需的构造参数与 logger 打包给各域(引用展开,构建零副作用)。
   * F 步起跨域共用助手实现整体迁入 RestApiCtx(单一定义),本 ctx 构造不再接
   * 线方法引用;presence 域专属助手随调用方在 PresenceRoutes。
   */
  private val ctx: RestApiCtx =
    RestApiCtx(
      token = token,
      configRef = configRef,
      sharedResources = sharedResources,
      sessionStore = sessionStore,
      wsRoutes = wsRoutes,
      neblinkService = neblinkService,
      ttsService = ttsService,
      neblinkDiscovery = neblinkDiscovery,
      gatewayPort = gatewayPort,
      wsHub = wsHub,
      connGuard = connGuard,
      logger = logger
    )

  /** 登录回调域(AuthRoutes)成员 (using ctx: RestApiCtx) 的解析锚点(恒等于上面的 ctx)。 */
  private given RestApiCtx = ctx

  def routes: HttpRoutes[IO] =
    HealthRoutes.routes(ctx) <+> SessionRoutes.routes(ctx) <+> ConfigRoutes.routes(ctx) <+>
      ProjectsRoutes.routes(ctx) <+> RegistryRoutes.routes(ctx) <+> NeblinkRoutes.routes(ctx) <+>
      SocialRoutes.routes(ctx)

  // ===== WebSocket Presence Server Endpoint =====

  /**
   * Accepts incoming WS presence connections from NebLink peers.
   *
   * The peer's device info arrives as handshake headers and/or query params on
   * the WS upgrade request (A1, 2026-09-20: deviceId moved to the header; see
   * [[RestApiCtx.presencePeerDeviceId]]).
   * Once the WS is established:
   *   - Server sends heartbeat pings every 10s.
   *   - Server responds to client pings with pongs.
   *   - On disconnect, the peer is removed from the peer list.
   *
   * This is a separate HttpRoutes value because it needs WebSocketBuilder2,
   * which is only available inside `withHttpWebSocketApp` in GatewayMain.
   */
  def presenceWsRoutes(wsb: WebSocketBuilder2[IO]): HttpRoutes[IO] = PresenceRoutes.routes(wsb, ctx)

  // 登录回调域已整体迁至 gateway/AuthRoutes.scala(行为保持重构,2026-09-24):
  // PKCE/切换标记状态与 GET /callback、GET /logged-out 的实现都在那边;本类保留
  // 同名挂载委托,GatewayMain 的 /auth 挂载面零改动。
  def authCallbackRoutes: HttpRoutes[IO] = AuthRoutes.callbackRoutes(ctx)

  // 测试面(DeviceFaceHardeningRoutesSpec)直取的三个判据成员:实现已随 F 步迁至
  // RestApiCtx(单一定义),此处保留同名委托 def,签名与修饰符原样。
  private[gateway] def presencePeerDeviceId(req: Request[IO]): String = ctx.presencePeerDeviceId(req)
  private[gateway] def isPrivateLanIp(rawIp: String): Boolean = ctx.isPrivateLanIp(rawIp)

  private[gateway] def isFreshKnownPeer(
    peers: List[PeerInfo],
    claimedDeviceId: String,
    nowMs: Long,
    syncIntervalSec: Int
  ): Boolean = ctx.isFreshKnownPeer(peers, claimedDeviceId, nowMs, syncIntervalSec)

end RestApiRoutes
