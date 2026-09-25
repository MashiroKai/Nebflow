/* Phase 5 解耦(行为保持重构,2026-09-25)。 */
// ports.scala — core ← gateway 依赖倒置的窄端口(C 步)。
//
// 形态:接口在 core(只含 core 实际用到的成员签名,类型零 gateway/agent 符号),
// 实现在 gateway 原地不搬(SessionStore / WsHub 以 mixin 直接实现;NfFilePolicy
// 以适配方法实现),接线在装配点(GatewayMain / 构造传参处)。core 自此不再
// import 或 FQN 引用 nebflow.gateway(scripts/check-scala-layers.mjs 门禁锚定)。

package nebflow.core

import cats.effect.IO
import io.circe.Json
import nebflow.shared.{SessionMeta, UiMessage}

import java.nio.file.Path

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
