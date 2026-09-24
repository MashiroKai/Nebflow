/* 从 RestApiRoutes 迁出(行为保持重构,2026-09-24)。 */
package nebflow.gateway

import cats.effect.{IO, Ref}
import io.circe.Json
import nebflow.agent.SharedResources
import nebflow.core.NebflowLogger
import nebflow.llm.NebflowServiceConfig
import nebflow.neblink.NeblinkService
import org.http4s.{Request, Response}

/**
 * REST 域分发上下文(B 步起,仿 WsDispatchCtx 先例,行为保持重构 2026-09-24):
 * val 成员为 RestApiRoutes 构造参数与 logger 的引用;def 成员为被迁出代码调用的
 * 类私有方法的委托(参数名与原方法一致,命名参数/柯里化调用形态逐字可用,方法
 * 本体仍留守 RestApiRoutes 类内,全仓单一实现)。impl 参数为非成员构造参数,
 * 不进入 `import ctx.*` 的可见面;后续域按需增量补成员,不预铺。
 *
 * withAuth 第二参数原为 by-name;经 impl 函数接线后在函数边界变为按值(每次
 * 调用求值一次、不再按闸延迟)。其全部调用点传入的都是纯 IO 构造表达式(含
 * `new PresetStore` / `new DaemonStore`,构造只算路径与取 logger,无副作用),
 * 求值时机前移无任何可观察差异。
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
  val logger: NebflowLogger,
  scanAgentPresetsImpl: () => Map[String, Option[String]],
  computeResolvedFromImpl: (Option[String], String) => String,
  scrubPresetRefsImpl: String => IO[Unit],
  migrateLegacyModelsImpl: List[String] => IO[Response[IO]],
  buildMountedTeamsJsonImpl: () => IO[Json],
  isValidFlowNameImpl: String => Boolean,
  isValidAgentNameImpl: String => Boolean,
  dispatchSwitchImpl: (String, Boolean) => IO[Response[IO]],
  withAuthImpl: Request[IO] => IO[Response[IO]] => IO[Response[IO]],
  socialErrorResponseImpl: nebflow.social.SocialChannels.Failure => IO[Response[IO]],
  presencePeerDeviceIdImpl: Request[IO] => String,
  isKnownNetworkDeviceImpl: (NeblinkService, String, String) => IO[Boolean],
  checkHttpBaseUrlImpl: String => Either[String, String]
):

  def scanAgentPresets(): Map[String, Option[String]] = scanAgentPresetsImpl()

  def computeResolvedFrom(preset: Option[String], agentName: String): String =
    computeResolvedFromImpl(preset, agentName)
  def scrubPresetRefs(presetName: String): IO[Unit] = scrubPresetRefsImpl(presetName)
  def migrateLegacyModels(agentNames: List[String]): IO[Response[IO]] = migrateLegacyModelsImpl(agentNames)
  def buildMountedTeamsJson(): IO[Json] = buildMountedTeamsJsonImpl()
  def isValidFlowName(name: String): Boolean = isValidFlowNameImpl(name)
  def isValidAgentName(name: String): Boolean = isValidAgentNameImpl(name)
  def dispatchSwitch(name: String, enable: Boolean): IO[Response[IO]] = dispatchSwitchImpl(name, enable)
  def withAuth(req: Request[IO])(f: => IO[Response[IO]]): IO[Response[IO]] = withAuthImpl(req)(f)
  def socialErrorResponse(err: nebflow.social.SocialChannels.Failure): IO[Response[IO]] = socialErrorResponseImpl(err)
  def presencePeerDeviceId(req: Request[IO]): String = presencePeerDeviceIdImpl(req)

  def isKnownNetworkDevice(
    ms: NeblinkService,
    claimedDeviceId: String,
    remoteIp: String
  ): IO[Boolean] =
    isKnownNetworkDeviceImpl(ms, claimedDeviceId, remoteIp)
  def checkHttpBaseUrl(baseUrl: String): Either[String, String] = checkHttpBaseUrlImpl(baseUrl)
end RestApiCtx
