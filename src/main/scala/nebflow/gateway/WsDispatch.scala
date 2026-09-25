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
import nebflow.llm.*
import nebflow.service.*
import nebflow.shared.{NebflowLogger, *}
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*
import org.http4s.headers.`Content-Type`
import org.http4s.server.websocket.WebSocketBuilder2
import org.http4s.websocket.WebSocketFrame

import scala.concurrent.duration.*
import scala.io.Source

/**
 * F 步消息分派注册表(行为保持重构,2026-09-24):键 = handleMessage 原 msgType match 的
 * case 字符串(一字不差),查不到时的回退 = 原 `case _` 腿(留在 WebSocketRoutes 里,
 * 其文本被 PasteAttachmentGuardSpec/UserTextGateSpec 钉死)。各域 handler 经
 * WsDispatchCtx 取 WebSocketRoutes 的私有依赖;ctx 仅持引用/eta 展开,构建零副作用。
 */
private[gateway] object WsDispatch:

  type WsHandler = (WsDispatchCtx, String, io.circe.Json => IO[Unit], ExplorerWatchSession) => IO[Unit]

  private[gateway] val handlers: Map[String, WsHandler] = List(
    WsSessionChatHandlers.handlers,
    WsConfigHandlers.handlers,
    WsSkillsAsksHandlers.handlers,
    WsTasksWorkspaceHandlers.handlers,
    WsFileOpsHandlers.handlers,
    WsDropboxHandlers.handlers,
    WsMemoryFoldersHandlers.handlers,
    WsSystemHandlers.handlers
  ).reduce(_ ++ _)

  /**
   * 入站消息信封(行为保持重构,2026-09-24):收敛两类完全同形的重复惯用法——
   * parse(text) 后取字段(parse 失败回退 io.circe.Json.Null,见 parsedJson)与
   * downField("sessionId") 取会话 id(缺失/非串回退空串,Either.getOrElse 与
   * toOption.getOrElse 同果,统一走 inboundEnvelope(text).sessionId)。边界:
   * 仅覆盖这两族;先绑定 json/hcursor 再取多字段的站点、字段级 Option 链、直接
   * 产出 HCursor 等近形不收敛(见各站点一行注释),回退与错误响应逐处保持原状。
   */
  private[gateway] case class InboundEnvelope(json: Json):
    def sessionId: String = json.hcursor.downField("sessionId").as[String].getOrElse("")

  private[gateway] def parsedJson(text: String): Json =
    parse(text).toOption.getOrElse(io.circe.Json.Null)

  private[gateway] def inboundEnvelope(text: String): InboundEnvelope =
    InboundEnvelope(parsedJson(text))

end WsDispatch

/**
 * 分发上下文:值成员为原类私有 val/构造参数的引用;def 成员为原类私有方法的
 * 委托(参数名与原方法一致,命名参数/柯里化调用形态逐字可用)。impl 参数为
 * 非成员构造参数,不进入 `import ctx.*` 的可见面。
 */
final class WsDispatchCtx(
  val logger: NebflowLogger,
  val sessionService: SessionService,
  val agentService: AgentService,
  val configService: ConfigService.type,
  val configRef: Ref[IO, NebflowServiceConfig],
  val sessionStore: SessionStore,
  val sharedResources: SharedResources,
  val mcpManager: McpManager,
  val sttServiceRef: Ref[IO, Option[SttService]],
  val textSearch: TextStreamSearch,
  val sessionTextBuffers: Ref[IO, Map[String, String]],
  val sessionThinkingBuffers: Ref[IO, Map[String, String]],
  val sessionTurnStarts: Ref[IO, Map[String, Long]],
  replayPendingAsksImpl: (String, io.circe.Json => IO[Unit]) => IO[Unit],
  listAllPendingAsksImpl: (io.circe.Json => IO[Unit]) => IO[Unit],
  applyPermissionUpgradeImpl: (String, nebflow.core.SafetyMode) => IO[Unit],
  persistGlobalSafetyModeImpl: String => IO[Unit],
  removeRootAgentImpl: String => IO[Unit],
  stopTeamSessionActorsImpl: String => IO[Unit],
  stopChildDelegateActorsImpl: String => IO[Unit],
  forwardInteractionAnswerImpl: (String, String, io.circe.Json) => IO[Unit],
  isRootScopeSessionImpl: String => IO[Boolean],
  compactThresholdInfoImpl: String => IO[Json],
  ensureAgentImpl: String => (nebflow.actor.ActorRef[AgentCommand] => IO[Unit]) => IO[Unit],
  maybeSessionKickImpl: (String, String) => IO[Unit],
  broadcastServerConfigImpl: () => IO[Unit],
  persistThinkingConfigImpl: ThinkingConfig => IO[Unit],
  persistWorkScheduleImpl: nebflow.core.schedule.FreezeScheduleConfig => IO[Unit],
  persistMcpServerEnabledImpl: (String, Boolean) => IO[Unit],
  broadcastMcpServersUpdateImpl: () => IO[Unit],
  sendAgentSessionListImpl: (io.circe.Json => IO[Unit], String) => IO[Unit],
  sendAgentSessionListByNameImpl: (io.circe.Json => IO[Unit], String) => IO[Unit],
  sendMemoryStatusImpl: (io.circe.Json => IO[Unit], String) => IO[Unit],
  expandTildeImpl: String => String,
  wsBrowseEventImpl: (String, String, Option[String], List[String], Option[String]) => Json,
  resolveExplorerBaseRootImpl: (String, Option[String]) => IO[String],
  admitWorkOrRefuseImpl: (io.circe.Json => IO[Unit]) => IO[Unit] => IO[Unit],
  handleUserTextImpl: (String, String, String, Boolean) => IO[Unit],
  skipCurrentFreezeWindowImpl: () => IO[Unit],
  executeAskImpl: (String, String, io.circe.Json => IO[Unit]) => IO[Unit],
  executeSkillImpl: (String, String, String, io.circe.Json => IO[Unit]) => IO[Unit]
):

  def replayPendingAsks(
    sessionId: String,
    wsSend: io.circe.Json => IO[Unit]
  ): IO[Unit] =
    replayPendingAsksImpl(sessionId, wsSend)
  def listAllPendingAsks(wsSend: io.circe.Json => IO[Unit]): IO[Unit] = listAllPendingAsksImpl(wsSend)

  def applyPermissionUpgrade(
    sessionId: String,
    mode: nebflow.core.SafetyMode
  ): IO[Unit] =
    applyPermissionUpgradeImpl(sessionId, mode)
  def persistGlobalSafetyMode(mode: String): IO[Unit] = persistGlobalSafetyModeImpl(mode)
  def removeRootAgent(sessionId: String): IO[Unit] = removeRootAgentImpl(sessionId)
  def stopTeamSessionActors(sessionId: String): IO[Unit] = stopTeamSessionActorsImpl(sessionId)
  def stopChildDelegateActors(sessionId: String): IO[Unit] = stopChildDelegateActorsImpl(sessionId)

  def forwardInteractionAnswer(
    requestId: String,
    sessionId: String,
    payload: io.circe.Json
  ): IO[Unit] =
    forwardInteractionAnswerImpl(requestId, sessionId, payload)
  def isRootScopeSession(sessionId: String): IO[Boolean] = isRootScopeSessionImpl(sessionId)
  def compactThresholdInfo(sessionId: String): IO[Json] = compactThresholdInfoImpl(sessionId)

  def ensureAgent(sessionId: String)(f: nebflow.actor.ActorRef[AgentCommand] => IO[Unit]): IO[Unit] =
    ensureAgentImpl(sessionId)(f)
  def maybeSessionKick(sessionId: String, trigger: String): IO[Unit] = maybeSessionKickImpl(sessionId, trigger)
  def broadcastServerConfig: IO[Unit] = broadcastServerConfigImpl()
  def persistThinkingConfig(tc: ThinkingConfig): IO[Unit] = persistThinkingConfigImpl(tc)
  def persistWorkSchedule(cfg: nebflow.core.schedule.FreezeScheduleConfig): IO[Unit] = persistWorkScheduleImpl(cfg)

  def persistMcpServerEnabled(
    serverId: String,
    enabled: Boolean
  ): IO[Unit] =
    persistMcpServerEnabledImpl(serverId, enabled)
  def broadcastMcpServersUpdate: IO[Unit] = broadcastMcpServersUpdateImpl()

  def sendAgentSessionList(
    wsSend: io.circe.Json => IO[Unit],
    sessionId: String
  ): IO[Unit] =
    sendAgentSessionListImpl(wsSend, sessionId)

  def sendAgentSessionListByName(
    wsSend: io.circe.Json => IO[Unit],
    agentName: String
  ): IO[Unit] =
    sendAgentSessionListByNameImpl(wsSend, agentName)

  def sendMemoryStatus(
    wsSend: io.circe.Json => IO[Unit],
    sessionId: String
  ): IO[Unit] =
    sendMemoryStatusImpl(wsSend, sessionId)
  def expandTilde(path: String): String = expandTildeImpl(path)

  def wsBrowseEvent(
    evType: String,
    path: String,
    parent: Option[String],
    entries: List[String],
    err: Option[String]
  ): Json =
    wsBrowseEventImpl(evType, path, parent, entries, err)

  def resolveExplorerBaseRoot(
    sessionId: String,
    overrideRoot: Option[String]
  ): IO[String] =
    resolveExplorerBaseRootImpl(sessionId, overrideRoot)

  def admitWorkOrRefuse(wsSend: io.circe.Json => IO[Unit])(cont: IO[Unit]): IO[Unit] =
    admitWorkOrRefuseImpl(wsSend)(cont)

  def handleUserText(
    sessionId: String,
    content: String,
    source: String,
    fromUser: Boolean
  ): IO[Unit] =
    handleUserTextImpl(sessionId, content, source, fromUser)
  def skipCurrentFreezeWindow: IO[Unit] = skipCurrentFreezeWindowImpl()

  def executeAsk(
    sessionId: String,
    question: String,
    wsSend: io.circe.Json => IO[Unit]
  ): IO[Unit] =
    executeAskImpl(sessionId, question, wsSend)

  def executeSkill(
    skillName: String,
    input: String,
    sessionId: String,
    wsSend: io.circe.Json => IO[Unit]
  ): IO[Unit] =
    executeSkillImpl(skillName, input, sessionId, wsSend)
end WsDispatchCtx
