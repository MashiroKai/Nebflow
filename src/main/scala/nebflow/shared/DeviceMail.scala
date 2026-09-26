/* 严格DAG第⑥步第二批裁定(2026-09-27):DeviceMail 本体整文件自 nebflow/neblink/DeviceMail.scala 下沉 shared(原样迁移,仅 package 改 nebflow.shared;含 TypeAgentMail 等枚举/常量),DeviceMailAck 留驻 neblink 经 core.DeviceMailAckPort 注册器倒置——斩断 core→neblink 边;neblink/core 内引用改指本处。 */
package nebflow.shared

import io.circe.Json
import io.circe.syntax.*

/**
 * 跨设备 Nebula 邮件（device-mail 批，2026-09-15 作者令 12:04 逐字）：
 * 「给 Mail 工具加上设备参数，让 Mail 可以给设备发邮件，然后邮件直接注入给该设备的
 * nebula，也是蓝色气泡。」
 *
 * 本对象 = **契约单点**（生产端构造 + 消费端解析共用一处，禁第二份字面量）：
 * 载荷与 ack 的形状**逐字冻结**（与 neblink-server 服务端传输腿同版；改动须 root
 * 签认，本批禁改）。契约原文逐字引用：
 *
 *   载荷：`{"type":"agent_mail", "from_device":…, "from_device_id":…, "to_nebula":true, "text":…}`
 *   服务端**强制** `from_device_id` = 鉴权身份（客户端填值不得被信任为权威）；
 *   ack：`{"type":"ack","eventId":"message-<id>"}`
 *
 * 🔴 五键 = 全部；**禁自创字段、禁自改语义**。客户端侧的口径：
 *   - 生产端：`from_device` = 本机设备显示名（`DeviceIdentity.deviceName`），
 *     `from_device_id` = 本机 deviceId —— **客户端自报值仅供展示**，权威值由服务端
 *     以鉴权身份覆盖/判读（本模块不做任何身份断言，也不把自报值当权威）；
 *   - 消费端：`from_device` 用于气泡标签与注入头行；`from_device_id` 只做归属
 *     展示/审计，不参与任何授权判定（本批零新增授权面）。
 *
 * 传输（契约 **v2.1**，2026-09-15 12:57 root 裁定，取代任务书内的数据通道自述）：
 *   - **发送**：`POST {server}/api/relay/{target_device_id}/mail`（目标走路径，body =
 *     五键载荷本体；`NeblinkClient.mailUrl` / `relayAgentMail` = 唯一构造点）；
 *   - **收件**：**设备事件流**信封——服务端经本设备隧道推
 *     `{"type":"friend_event","eventId":"message-<id>","event":{"payload":{<五键载荷>},"type":"agent_mail"}}`
 *     ⇒ 唯一入场 = `NeblinkRelayTunnel` 的 `friend_event` 分支（`DeviceMailInbox.handle`）。
 *     🔴 单一入场：既非设备数据通道（v1，已废弃、零残留），也无顶层裸 `agent_mail`
 *     分支（那会把同一条腿挂成两个入口）。
 */
object DeviceMail:

  // ===== 契约字面量（唯一来源；生产端与消费端都读这里）=====

  /** 载荷类型判别值（契约逐字）。 */
  val TypeAgentMail: String = "agent_mail"

  /** 设备事件流信封类型（v2.1 收件入场帧；服务端投递实证形态）。 */
  val TypeFriendEvent: String = "friend_event"

  /** ack 类型判别值（契约逐字；**既有** ack 机制，本批不新建、不改形状）。 */
  val TypeAck: String = "ack"

  /** ack 的 eventId 前缀（契约逐字 `"message-<id>"`）。 */
  val AckEventIdPrefix: String = "message-"

  val KeyType: String = "type"
  val KeyFromDevice: String = "from_device"
  val KeyFromDeviceId: String = "from_device_id"
  val KeyToRoot: String = "to_nebula"
  val KeyText: String = "text"
  val KeyEventId: String = "eventId"
  val KeyEvent: String = "event"
  val KeyPayload: String = "payload"

  /** 契约不变量：`to_nebula` 恒为真（本批唯一目标形态 = 对端 Nebula 会话）。 */
  val ToRootValue: Boolean = true

  /**
   * 注入来源定名（后端唯一定名源 = `InjectionAttribution.BackendNamedSources` 的
   * 一员；前端 `web/js/chat.js#injectedSourceLabel` 必须显式登记 —— 契约门
   * `InjectionSourceContractSpec` 守两侧同源）。
   */
  val SourceDeviceMail: String = "deviceMail"

  /**
   * 邮件类型语义 = **INFO**（作者口径「类型 INFO 语义」）：补充上下文、不打断
   * 当前工作。线上取值 = `MailTool` 既有口径（mailType.toLowerCase）。
   */
  val EventTypeInfo: String = "info"

  /** 注入文本头行（逐字，作者口径）：`[DEVICE-MAIL · from <from_device>]`。 */
  def headerLine(fromDevice: String): String = s"[DEVICE-MAIL · from $fromDevice]"

  /** 注入正文 = 头行 + 换行 + 邮件正文（`MailTool` 的 chainId 前缀同族形态）。 */
  def injectedText(fromDevice: String, text: String): String =
    s"${headerLine(fromDevice)}\n$text"

  // ===== 生产端 =====

  /** 载荷构造 —— **发送端唯一构造点**：恰好契约五键，无额外字段。 */
  def payload(text: String, fromDevice: String, fromDeviceId: String): Json =
    Json.obj(
      KeyType -> TypeAgentMail.asJson,
      KeyFromDevice -> fromDevice.asJson,
      KeyFromDeviceId -> fromDeviceId.asJson,
      KeyToRoot -> ToRootValue.asJson,
      KeyText -> text.asJson
    )

  /** 载荷键全集（契约逐字对照用；顺序 = 契约书写顺序）。 */
  val PayloadKeys: List[String] =
    List(KeyType, KeyFromDevice, KeyFromDeviceId, KeyToRoot, KeyText)

  // ===== 消费端 =====

  /** 解析通过后的收件（三字段都是**必填**契约字段；`to_nebula` 已判为真）。 */
  final case class Incoming(text: String, fromDevice: String, fromDeviceId: String)

  /** `type` 判别（先于字段校验：非本类型一律交回调用方按既有路径处理）。 */
  def isAgentMail(json: Json): Boolean =
    json.hcursor.get[String](KeyType).toOption.contains(TypeAgentMail)

  /** ack 形态判定（既有 ack 机制，本批只读不产；返回 eventId 供日志/审计引用）。 */
  def ackEventId(json: Json): Option[String] =
    json.hcursor.get[String](KeyType).toOption.filter(_ == TypeAck).flatMap { _ =>
      json.hcursor.get[String](KeyEventId).toOption.map(_.trim).filter(_.nonEmpty)
    }

  /** `"message-<id>"` → `<id>`（id 面的**唯一**去前缀点；非该前缀原样返回 trim 结果）。 */
  def stripMessagePrefix(raw: String): String =
    val t = raw.trim
    if t.startsWith(AckEventIdPrefix) then t.drop(AckEventIdPrefix.length) else t

  /** `<id>` → `"message-<id>"`（反向拼接同源；已带前缀则幂等）。 */
  def withMessagePrefix(id: String): String =
    val t = id.trim
    if t.isEmpty then t
    else if t.startsWith(AckEventIdPrefix) then t
    else s"$AckEventIdPrefix$t"

  // ===== 收件入场（v2.1：设备**事件流信封**，单一入口）=====

  /** 事件流信封的解析结果：内层载荷 + **帧级** `eventId`（收件回执的关联键，可缺席）。 */
  final case class Envelope(payload: Json, eventId: Option[String])

  /** 内层事件类型（`event.type`）。 */
  def envelopeEventType(frame: Json): Option[String] =
    frame.hcursor.downField(KeyEvent).get[String](KeyType).toOption

  /**
   * 是否为本批的设备邮件事件（**唯一**入场判据：`type == friend_event` ∧
   * `event.type == agent_mail`）。非本判据的帧一律交回既有路径（零副作用）。
   */
  def isAgentMailEnvelope(frame: Json): Boolean =
    frame.hcursor.get[String](KeyType).toOption.contains(TypeFriendEvent) &&
      envelopeEventType(frame).contains(TypeAgentMail)

  /**
   * 信封拆解（`isAgentMailEnvelope` 为真时才给出值；`payload` 非对象 ⇒ 不给值，
   * 由 [[parseEnvelope]] 给出可读原因）。`eventId` 取帧根，回落内层（v2.1 实证样例
   * 在帧根）。
   */
  def envelope(frame: Json): Option[Envelope] =
    if !isAgentMailEnvelope(frame) then None
    else
      val event = frame.hcursor.downField(KeyEvent)
      event.downField(KeyPayload).focus.filter(_.isObject).map { payload =>
        Envelope(payload, frameEventId(frame))
      }

  /** 帧级 eventId（帧根优先，回落 `event.eventId`；空串视同缺席）。 */
  def frameEventId(frame: Json): Option[String] =
    frame.hcursor
      .get[String](KeyEventId)
      .toOption
      .orElse(frame.hcursor.downField(KeyEvent).get[String](KeyEventId).toOption)
      .map(_.trim)
      .filter(_.nonEmpty)

  /**
   * 事件流信封的 **fail-closed 解析**（v2.1 唯一收件入场）：非本批事件 ⇒
   * `Left(可读原因)`（调用方按帧类别决定 DEBUG/WARN）；`payload` 缺口/五键校验失败
   * ⇒ `Left(可读原因)`。零副作用、零 IO。
   */
  def parseEnvelope(frame: Json): Either[String, (Incoming, Option[String])] =
    frame.hcursor.get[String](KeyType).toOption match
      case Some(TypeFriendEvent) =>
        envelopeEventType(frame) match
          case Some(TypeAgentMail) =>
            frame.hcursor.downField(KeyEvent).downField(KeyPayload).focus match
              case None => Left(s"missing '$KeyEvent.$KeyPayload'")
              case Some(p) if !p.isObject =>
                Left(s"'$KeyEvent.$KeyPayload' is not a JSON object")
              case Some(p) =>
                parse(p).map(incoming => (incoming, frameEventId(frame)))
          case other =>
            Left(s"event.type='${other.getOrElse("<absent>")}' is not '$TypeAgentMail'")
      case Some(other) => Left(s"frame type '$other' is not '$TypeFriendEvent'")
      case None => Left(s"frame has no '$KeyType'")

  /**
   * **fail-closed 解析**（老版本对端降级面）：未知 `type` / 缺字段 / 类型错 /
   * `to_nebula` 非真值 / 空正文 ⇒ `Left(可读原因)`，调用方**忽略 + 落可读日志**，
   * 禁崩溃、禁误渲染（本方法零副作用、零 IO）。
   */
  def parse(json: Json): Either[String, Incoming] =
    val hc = json.hcursor
    if !isAgentMail(json) then
      Left(s"not an '$TypeAgentMail' payload (type='${hc.get[String](KeyType).getOrElse("<absent>")}')")
    else if !json.isObject then Left(s"'$TypeAgentMail' payload is not a JSON object")
    else
      val fromDevice = hc.get[String](KeyFromDevice).toOption.map(_.trim).filter(_.nonEmpty)
      val fromDeviceId = hc.get[String](KeyFromDeviceId).toOption.map(_.trim).filter(_.nonEmpty)
      val toRoot = hc.get[Boolean](KeyToRoot).toOption
      val text = hc.get[String](KeyText).toOption.filter(_.nonEmpty)
      fromDevice match
        case None => Left(s"missing/blank '$KeyFromDevice'")
        case Some(_) =>
          fromDeviceId match
            case None => Left(s"missing/blank '$KeyFromDeviceId'")
            case Some(_) =>
              toRoot match
                case Some(true) =>
                  text match
                    case Some(t) => Right(Incoming(t, fromDevice.get, fromDeviceId.get))
                    case None => Left(s"missing/blank '$KeyText'")
                case Some(other) => Left(s"'$KeyToRoot' must be true (got $other)")
                case None => Left(s"missing/non-boolean '$KeyToRoot'")
    end if
  end parse

end DeviceMail
