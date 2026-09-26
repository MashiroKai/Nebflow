/* Phase 5 解耦(行为保持重构,2026-09-25)。 */
// ports.scala — core ← gateway 依赖倒置的窄端口(C 步)。
//
// 形态:接口在 core(只含 core 实际用到的成员签名,类型零 gateway/agent 符号),
// 实现在 gateway 原地不搬(SessionStore / WsHub 以 mixin 直接实现;NfFilePolicy
// 以适配方法实现),接线在装配点(GatewayMain / 构造传参处)。core 自此不再
// import 或 FQN 引用 nebflow.gateway(scripts/check-scala-layers.mjs 门禁锚定)。

package nebflow.core

import cats.effect.IO
import io.circe.{Json, JsonObject}
import nebflow.shared.*

import java.nio.file.Path

import scala.concurrent.duration.*

/**
 * `gateway.SessionStore` 的窄投影(C 步倒置;D 步增补落盘 flush 两成员)。core 的
 * 消费面:会话元数据与活跃会话 id(scheduler/ScheduledTaskActor)、UI 消息追加
 * (ScheduledTaskActor)、目录名(flow/FlowTreeRegistry 经 ToolContext.sessionStore)、
 * 热重启 [3] 状态落盘核验的两条 flush(hotrestart/HotRestart,D 步由整只
 * SharedResources 改经本端口注入)。
 * gateway 的 `SessionStore` 原地混入本端口(签名与既有定义逐字一致,零行为差),
 * 既有构造/传参点(GatewayMain、SharedResources 字段、TeamSessionRegistry 调用)
 * 经子类型继续编译,接线零改动。
 */
trait SessionStorePort:
  def getSessionMeta(id: String): IO[Option[SessionMeta]]
  def getActiveId: IO[String]
  def appendUiMessages(sessionId: String, msgs: List[UiMessage]): IO[Unit]
  def getFolderName(folderId: String): Option[String]
  def flushPendingUiWrites: IO[Unit]
  def flushPendingMessages: IO[Unit]
end SessionStorePort

/**
 * `gateway.WsHub` 的窄投影(C 步倒置):core(processor/TaskStuckWatcher)只消费
 * 全站广播这一个成员。gateway 的 `WsHub` 原地混入本端口,调用点
 * (GatewayMain 传 wsHub、spec 传 new WsHub())经子类型继续编译。
 */
trait EventSink:
  def broadcast(json: Json): IO[Unit]
end EventSink

/**
 * `gateway.NfFilePolicy` 的窄端口(C 步倒置)。core 工具面(tools/FileRefs)只问
 * 两个问题:端点整条判据阶梯会不会拒绝这个 realpath(拒在哪一层、什么理由),
 * 以及这个 realpath 是不是凭据 inode。判据本体(白名单表、判据阶梯、inode 快照)
 * 全部留在 gateway 单点——imgref 批「与端点同一份判据(🔴 复用,禁复制)」裁定
 * 不变;`NfPathPolicy.current()` 由实现方自取(判据随在役根漂移、不随进程历史
 * 冻结——r2 F1 ② 裁定不变)。
 */
trait FilePolicyPort:
  def endpointVerdictLayer(real: Path): Option[(FilePolicyPort.NfDenyLayer, String, String)]
  def credentialInodeHit(real: Path): Boolean
end FilePolicyPort

object FilePolicyPort:

  /**
   * Which layer of the C1 ladder refused a path (imgref rework r2, 2026-09-18).
   *
   * The layers answer DIFFERENT questions, and exactly one caller has to tell
   * them apart (the tool-side inline leg — see `FileRefs.inlineMayTakeOver`):
   *
   *   - `Namespace` = "the endpoint only serves some subtrees of the data
   *     directory / of the project `.nebflow`". A statement about the
   *     ENDPOINT's REACH: meaningful only to a caller that asks `/api/nf-file`
   *     for bytes.
   *   - `Credential` = "this path's own IDENTITY is a credential" — a known
   *     credential entry (`NfExternalCredentialEntries`), a credential-holding
   *     directory (`NfCredentialPathSegments`) or a credential-shaped basename
   *     (`NfCredentialNamePattern`). A statement about the FILE: meaningful to
   *     any caller that would copy its bytes somewhere else.
   *   - `CredentialInode` = R2: a hard link to a credential file's inode.
   *   - `FileType` = the extension of the REAL path is not served.
   *
   * 🔴 This enum only NAMES a decision the shipped ladder already made: the
   * tables, the branch order and every message are byte-identical to the
   * pre-rework judge ([[nfCredentialDeny]] is this function's projection).
   *
   * Phase 5 解耦:本 enum 随窄端口签名迁入 core(词汇类型,零行为),
   * `nebflow.gateway.NfFilePolicy.NfDenyLayer` 以别名保持既有引用路径不变。
   */
  enum NfDenyLayer:
    case Namespace, Credential, CredentialInode, FileType

  /**
   * 接线点 = GatewayMain 装配(生产)与工具面 spec(测试)。未接线时消费方
   * (FileRefs 的两处 try)按既有「判据不可得 ⇒ fail-closed」语义兜底,
   * 与判据本体抛异常同一兜法——不产生新的失败面。
   */
  @volatile private var installed: Option[FilePolicyPort] = None

  def install(impl: FilePolicyPort): Unit = installed = Some(impl)

  def port: FilePolicyPort = installed match
    case Some(impl) => impl
    case None =>
      throw new IllegalStateException(
        "FilePolicyPort 未接线(生产由 GatewayMain 装配;spec 侧需 FilePolicyPort.install(NfFilePolicy))"
      )
end FilePolicyPort

/**
 * `gateway.WsHub` 的窄投影(严格DAG第⑥步第一批):dropbox(DropboxService 的
 * notifyFrontend)只消费全站广播这一个成员,签名与 `gateway.WsHub.broadcast`
 * 逐字一致。gateway 的 `WsHub` 原地混入本端口,既有构造/传参点
 * (GatewayMain / WebSocketRoutes / spec 的 new WsHub())经子类型继续编译。
 */
// 严格DAG第⑥步第一批裁定(2026-09-26):窄口倒置,签名镜像现实现,行为保持
trait WsHubPort:
  def broadcast(json: Json): IO[Unit]
end WsHubPort

/**
 * `neblink.NeblinkClient` 的窄投影(严格DAG第⑥步第一批):dropbox 侧的实调用面 =
 * 消息通知(relayNotify,DropboxService 的 sendDataOrRelay 兜底腿)与分块传输两腿
 * (relayTransferPutChunk / relayTransferProbe,DropboxChunkTransports)。注释里提到的
 * sendRequest / relayTransferPut / relayTransferGet 无 dropbox 实调用,不入端口;
 * 签名与 NeblinkClient 现成员逐字一致(参数/返回全为基本类型与 circe Json,零
 * neblink 符号,无需下沉任何 ADT)。neblink 的 `NeblinkClient` 原地混入本端口。
 */
// 严格DAG第⑥步第一批裁定(2026-09-26):窄口倒置,签名镜像现实现,行为保持
trait NeblinkClientPort:
  def relayNotify(targetDeviceId: String, channel: String, payload: Json): IO[Either[String, String]]

  /**
   * 远端执行(relay 隧道腿)与设备邮件中继(严格DAG第⑥步第二批 R7/R8):core 的
   * RemoteExecutor(relayExec)与 MailTool 设备腿(relayAgentMail,经
   * NeblinkServicePort.relayClientOpt)的实调用面;默认超时统一引用已下沉 shared 的
   * DefaultRelayTimeout(10 s 语义不变),relayAgentMail 返回类型引用已下沉 shared 的
   * RelayMailResult。
   */
  // 严格DAG第⑥步第二批裁定(2026-09-27):窄口倒置,签名镜像现实现(默认参统一引用 shared 常量,行为等值)
  def relayExec(
    targetDeviceId: String,
    action: String,
    params: JsonObject,
    timeout: scala.concurrent.duration.FiniteDuration = DefaultRelayTimeout
  ): IO[Either[String, String]]

  def relayAgentMail(
    targetDeviceId: String,
    payload: Json,
    timeout: scala.concurrent.duration.FiniteDuration = DefaultRelayTimeout
  ): IO[Either[String, RelayMailResult]]

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
    timeout: scala.concurrent.duration.FiniteDuration,
    transferId: Option[String] = None
  ): IO[Either[String, Json]]

  def relayTransferProbe(
    targetDeviceId: String,
    path: String,
    timeout: scala.concurrent.duration.FiniteDuration
  ): IO[Either[String, Json]]
end NeblinkClientPort

/**
 * `llm.ProviderHealthMonitor` 的窄投影(严格DAG第⑥步第一批):core 的 hotrestart
 * 健康面(HealthPayload.build / ProbeEndpoint.start / HealthCheck.afterBind)只消费
 * 两个只读查询,签名与现实现逐字一致;返回类型引用已下沉 shared 的
 * HealthState / SearchApiHealth(合法向下依赖)。llm 的 `ProviderHealthMonitor`
 * 原地混入本端口,装配点(GatewayMain / SharedResources 字段)经子类型继续编译。
 */
// 严格DAG第⑥步第一批裁定(2026-09-26):窄口倒置,签名镜像现实现,行为保持
trait ProviderHealthPort:
  def getStates: IO[Map[String, HealthState]]
  def getSearchHealth: IO[SearchApiHealth]
end ProviderHealthPort

/**
 * `neblink.DeviceIdentity` 的窄视图(严格DAG第⑥步第二批 R2):DeviceIdentity 本体
 * 不下沉(伴生 object 持 logger/PathUtil/AtomicJson/机器码探测/CredentialWriteAcl,
 * 整件非纯)。core/dropbox 消费点实读的字段面 = deviceId(RemoteExecutor 的
 * relay-exec 头与审计、DropboxService 的 sendText/offerOne、MailTool/FriendMessageTool
 * 的审计腿)、deviceName(MailTool 载荷构造、DropboxService 的 sendText 帧、
 * AgentSessionExecution 的 # Devices 本机行)、userDescription(AgentSessionExecution
 * 的 # Devices 本机行)——只窄不宽。neblink 的 `DeviceIdentity`(case class 字段即
 * val 成员)原地 `extends` 本视图,既有构造点经子类型继续编译。
 */
// 严格DAG第⑥步第二批裁定(2026-09-27):窄视图,成员面=消费点实读字段,只窄不宽,行为保持
trait DeviceIdentityView:
  def deviceId: String
  def deviceName: String
  def userDescription: String
end DeviceIdentityView

/**
 * `neblink.NeblinkRelayTunnel` 的窄视图(严格DAG第⑥步第二批 R3):core 的
 * RemoteExecutor 只消费 `isAlive`(P2P/relay 选路的 relayAvailable 读数)。
 * 隧道本体(连接环/鉴权状态/分帧)留在 neblink;neblink 的 `NeblinkRelayTunnel`
 * 原地 `extends` 本视图。
 */
// 严格DAG第⑥步第二批裁定(2026-09-27):窄视图,成员面=RemoteExecutor 实调用,行为保持
trait RelayTunnelPort:
  def isAlive: Boolean
end RelayTunnelPort

/**
 * `neblink.NeblinkPresenceService` 的窄视图(严格DAG第⑥步第二批 R3):core 的
 * RemoteExecutor 只消费 `isConnected(deviceId)`(probe budget 的 directOnline
 * 提示读数)。presence 本体(拨号预算/心跳/逐端连接表)留在 neblink;neblink 的
 * `NeblinkPresenceService` 原地 `extends` 本视图。
 */
// 严格DAG第⑥步第二批裁定(2026-09-27):窄视图,成员面=RemoteExecutor 实调用,行为保持
trait PresenceServicePort:
  def isConnected(deviceId: String): Boolean
end PresenceServicePort

/**
 * `neblink.NeblinkService` 的窄投影(严格DAG第⑥步第二批 R3):core(MailTool /
 * FriendMessageTool / RemoteExecutor / DeviceProfile 经 ToolContext)与 dropbox
 * (DropboxService)的实际调用面 = 身份(identity,窄化为 DeviceIdentityView)、
 * 名册(peers / scanNow,元素为已下沉 shared 的 PeerInfo)、WS 数据通道
 * (addDataHandler / sendData)、relay 客户端热换指针(relayClientOpt,批一
 * NeblinkClientPort)与 P2P 共享 HTTP 后端(httpBackend)、以及选路读数
 * (relayTunnelOpt / presenceServiceOpt,窄化为 RelayTunnelPort /
 * PresenceServicePort)。签名与 NeblinkService 现成员逐字一致(返回类型按上述
 * 窄化);httpBackend 镜像实现的 `private[nebflow]` 修饰按裁定保留(core 与
 * dropbox 同在 nebflow 命名空间内,可见性不外泄)。neblink 的 `NeblinkService`
 * 原地混入本端口,既有构造/传参点经子类型继续编译。
 */
// 严格DAG第⑥步第二批裁定(2026-09-27):窄口倒置,签名镜像现实现(窄化仅限裁定明示),行为保持
trait NeblinkServicePort:
  def identity: IO[DeviceIdentityView]
  def peers: IO[List[PeerInfo]]
  def scanNow: IO[List[PeerInfo]]
  def addDataHandler(handler: Json => IO[Unit]): IO[Unit]
  def sendData(deviceId: String, channel: String, payload: Json): IO[Boolean]
  def relayClientOpt: Option[NeblinkClientPort]
  private[nebflow] def httpBackend: sttp.client4.SyncBackend
  def relayTunnelOpt: Option[RelayTunnelPort]
  def presenceServiceOpt: Option[PresenceServicePort]
end NeblinkServicePort

/**
 * `neblink.FriendService` 的窄投影(严格DAG第⑥步第二批 R11):core 工具面
 * (ListFriendsTool / FriendMessageTool)的实际调用面 = 搜索(searchUser,L4 邮箱
 * 回落)、好友发送(sendAsAgent)、好友列表(refreshFriends 折叠版 / listFriends
 * 分态穿透版)、群表(listGroups)与群发送(sendGroupAsAgent)。签名与现实现
 * 逐字一致(默认参原样);返回类型引用已下沉 shared 的 FriendListResponse /
 * GroupSummary。neblink 的 `FriendService` 原地混入本端口,GatewayMain 装配与
 * SharedResources 字段(FriendService 具体型)经子类型继续编译。
 */
// 严格DAG第⑥步第二批裁定(2026-09-27):窄口倒置,签名镜像现实现,行为保持
trait FriendServicePort:
  def searchUser(q: String): IO[Either[String, Json]]
  def sendAsAgent(friendUserId: String, body: String, attachments: List[os.Path] = Nil): IO[Either[String, String]]
  def refreshFriends(): IO[FriendListResponse]
  def listGroups: IO[Either[String, List[GroupSummary]]]
  def sendGroupAsAgent(groupId: String, body: String): IO[Either[String, String]]
  def listFriends: IO[Either[String, FriendListResponse]]
end FriendServicePort

/**
 * `dropbox.DropboxService` 的窄投影(严格DAG第⑥步第二批 R12):core 工具面
 * (FriendMessageTool.sendDevice / MailTool.pushDeviceAttachments)的实际调用面 =
 * 文本发送(sendText)与本地件批量发送(sendLocalFiles)。返回类型引用已下沉 shared 的
 * LocalFileOutcome;origin 缺省 = shared DropboxMessage.OriginUser。类型参数
 * `Transport` 只承载 `sendLocalFiles` 的测试自环传输缝参数(transportOverride,
 * `ChunkTransport` 留驻 dropbox)——core 消费方一律以 `DropboxServicePort[?]` 取用
 * (从不传该参,走默认 `None`),dropbox 的 `DropboxService` 以
 * `extends DropboxServicePort[ChunkTransport]` 混入,参数结构逐字镜像,既有构造/
 * 传参点(SharedResources 字段 / 网关调用)经子类型继续编译。
 */
// 严格DAG第⑥步第二批裁定(2026-09-27):窄口倒置,签名镜像现实现(传输缝以类型参数抽象,core 零 dropbox 符号),行为保持
trait DropboxServicePort[Transport]:
  def sendText(deviceId: String, text: String, origin: String = DropboxMessage.OriginUser): IO[Boolean]

  def sendLocalFiles(
    deviceId: String,
    files: List[os.Path],
    targetDir: Option[String] = None,
    transportOverride: Option[Transport] = None,
    acceptWait: scala.concurrent.duration.FiniteDuration = 20.seconds,
    uploadWait: scala.concurrent.duration.FiniteDuration = 15.minutes,
    origin: String = DropboxMessage.OriginUser
  ): IO[Either[AttachContract.AttachError, List[LocalFileOutcome]]]
end DropboxServicePort
