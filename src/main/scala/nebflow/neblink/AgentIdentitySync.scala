package nebflow.neblink

import cats.effect.IO
import cats.syntax.all.*
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.core.device.{DeviceHmac, DeviceRegistry}
import nebflow.core.entity.AgentIdentityStore
import nebflow.shared.NebflowLogger

import java.util.Base64

/**
 * AgentIdentitySync —— 智能体名称/头像的**跨引擎收敛通道**（identity-sync 批
 * 2026-10-23）。跨机不同步的根因：展示面（`agents/<name>/agent.json` 的
 * displayName + `agents/<name>/avatar` 字节）只落**本机引擎**的数据根，多机各跑
 * 一个引擎 ⇒ A 机写入对 B 机永不可见；同引擎多浏览器形态下前端又是「localStorage
 * 优先」，旧本地键遮住服务端新值（2026-10-21 诊断批症状⑧的 v1 已知限制）。
 *
 * 通道（**全复用既有设施，零新通道**）：设备数据通道（presence `sendData` 优先 +
 * relay `relayNotify` 兜底，DeviceDelegate.sendToDevice 同款双腿）——一种载荷
 * `identity-sync`（单 agent 全量状态），两种触发：
 *
 *   · **推**（写后外推）：面板 PUT display-name / PUT·DELETE avatar 落盘成功后
 *     fire-and-forget 外推到全部已配对设备；
 *   · **对账推**（重连/发现即收敛）：peer 集合变化回调（boot 后隧道首联、对端
 *     重连、新配对都会触发）把本机全部身份态各推一遍——对端按
 *     `identityUpdatedAt` LWW 收敛，离线对端在它自己下次上线/重启的对账推里
 *     补齐（两腿对称，无定向重试、零新增定时器）。
 *
 * 收敛纪律：
 *   · **LWW**：远端戳 `identityUpdatedAt` 严格更旧 ⇒ 丢弃；等/新 ⇒ 覆盖本地
 *     （AgentIdentityStore.applyRemote，远端戳落盘）；
 *   · **回声抑制靠结构**：收敛写入不在任何广播路径上，绝不回推；
 *   · **验签**：载荷按配对 sharedKey 做 HMAC（DeviceHmac，ts ±90s 窗 + nonce），
 *     未配对/伪造/过期帧一律丢弃（可读 WARN，不含载荷体）；
 *   · **红线**：头像字节（base64）**永不入日志**——日志面只有 agent 名/sha 前
 *     8 位/字节长度/目标设备数。
 *
 * 收敛落地后两件事同时发生：Soul.md 自我认知段改写（落到智能体的记忆）+ 面板
 * `agentIdentityChanged` 广播（对端 UI 立即重读，不等重启）。
 */
object AgentIdentitySync:

  private val logger = NebflowLogger.forName("nebflow.neblink.identity")

  /** 数据通道名（presence sendData / relay Notify 的 channel 维度）。 */
  val ChannelName: String = "agent-identity"

  /** 载荷 kind（handleDataMessage 的 kind 判别维度）。 */
  val KindSync: String = "identity-sync"

  /** 头像字节闸（与 PUT /agents/:name/avatar 同闸）。 */
  val MaxAvatarBytes: Int = 200_000

  // ── 载荷（构建/解析/验签全部纯函数，spec 直测）────────────────────────────

  /** 单 agent 身份载荷（total 语义：displayName/avatar 的 None = 「无」）。 */
  final case class IdentityPayload(
    agent: String,
    displayName: Option[String],
    /** base64（标准编码）；None = 无自定义头像。 */
    avatarB64: Option[String],
    avatarSha256: Option[String],
    updatedAt: Long,
    fromDeviceId: String,
    tsSec: Long,
    nonce: String,
    sig: String
  )

  /** 签名规范串（签名与验签的唯一拼装点；字段值入串而非整段 JSON——判读与语言无关）。 */
  def canonicalOf(p: IdentityPayload): String =
    s"$ChannelName|${p.fromDeviceId}|${p.tsSec}|${p.nonce}|${p.agent}|${p.displayName.getOrElse("")}|${p.avatarSha256
        .getOrElse("")}|${p.updatedAt}"

  /** 从本机状态构建**未签名**载荷（纯组装；含头像字节读取与 b64 编码，同步面）。
    * 签名按目标逐台做（[[signedFor]]）——配对 key 是**每对一份**的（A-B 与 A-C
    * 两把 key），一份载荷签一个对端（DeviceDelegate.buildSpawnPayload 同款）。 */
  def buildPayload(rec: AgentIdentityStore.AgentIdentityRec, fromDeviceId: String, nowMs: Long): Option[IdentityPayload] =
    if !AgentIdentityStore.hasIdentity(rec) then None
    else
      val b64 = rec.avatarSha256.map { _ =>
        val bytes = os.read.bytes(nebflow.shared.PathUtil.dataRoot / "agents" / rec.agent / "avatar")
        Base64.getEncoder.encodeToString(bytes)
      }
      Some(
        IdentityPayload(
          rec.agent,
          rec.displayName,
          b64,
          rec.avatarSha256,
          rec.updatedAt,
          fromDeviceId,
          nowMs / 1000,
          newNonce(),
          ""
        )
      )

  /** 以「本机与 `peerDeviceId` 的配对 key」签署载荷（每目标一份）。 */
  def signedFor(p: IdentityPayload, peerDeviceId: String): IdentityPayload =
    p.copy(sig = DeviceHmac.sign(DeviceRegistry.secretForSync(peerDeviceId).getOrElse(""), canonicalOf(p)))

  private def newNonce(): String = java.util.UUID.randomUUID().toString.replace("-", "")

  private def sign(p: IdentityPayload): IdentityPayload =
    val key = DeviceRegistry.secretForSync(p.fromDeviceId).getOrElse("")
    // 本机自己是 fromDeviceId —— devices.json 不含自条目，这里恒空 ⇒ 空 key 签出
    // 的 sig 收端必拒。防呆：推送前自查（pushIdentity 的 fromDeviceId 来自 identity，
    // 正常装配下 sharedKey 必在；缺 key 说明 devices.json 与 device.json 漂移）。
    p.copy(sig = DeviceHmac.sign(key, canonicalOf(p)))

  /** 宽容解析（缺字段/类型错 ⇒ None——收端 fail-closed，同 DeviceMail.parse 纪律）。 */
  def parsePayload(json: Json): Option[IdentityPayload] =
    val c = json.hcursor
    val kind = c.downField("kind").as[String].getOrElse("")
    if kind != KindSync then None
    else
      for
        agent <- c.downField("agent").as[String].toOption.filter(_.nonEmpty)
        fromDeviceId <- c.downField("fromDeviceId").as[String].toOption
        tsSec <- c.downField("ts").as[Long].toOption
        nonce <- c.downField("nonce").as[String].toOption.filter(_.nonEmpty)
        sig <- c.downField("sig").as[String].toOption.filter(_.nonEmpty)
        updatedAt <- c.downField("updatedAt").as[Long].toOption
        displayName = c.downField("displayName").as[Option[String]].toOption.flatten
        avatarB64 = c.downField("avatar").as[Option[String]].toOption.flatten
        avatarSha = c.downField("avatarSha256").as[Option[String]].toOption.flatten
      yield IdentityPayload(agent, displayName, avatarB64, avatarSha, updatedAt, fromDeviceId, tsSec, nonce, sig)

  /** 编码上机（kind 信封 + 全字段平铺）。 */
  def encodePayload(p: IdentityPayload): Json =
    Json.fromJsonObject(
      JsonObject(
        "kind" -> KindSync.asJson,
        "agent" -> p.agent.asJson,
        "displayName" -> p.displayName.asJson,
        "avatar" -> p.avatarB64.asJson,
        "avatarSha256" -> p.avatarSha256.asJson,
        "updatedAt" -> p.updatedAt.asJson,
        "fromDeviceId" -> p.fromDeviceId.asJson,
        "ts" -> p.tsSec.asJson,
        "nonce" -> p.nonce.asJson,
        "sig" -> p.sig.asJson
      )
    )

  /** 验签（配对 key + ts 窗）。None = 拒收原因（可读、不含载荷体）。 */
  def verifyPayload(p: IdentityPayload, nowMs: Long): Option[String] =
    if !DeviceHmac.tsFresh(p.tsSec.toString, nowMs) then Some(s"stale ts (${p.tsSec})")
    else
      DeviceRegistry.secretForSync(p.fromDeviceId) match
        case None => Some(s"sender device '${p.fromDeviceId}' not paired")
        case Some("") => Some("empty shared key")
        case Some(key) =>
          if DeviceHmac.verify(key, canonicalOf(p), p.sig) then None
          else Some("signature mismatch")

  /** 载荷 → 收敛输入（头像 b64 解码 + 字节闸；None = 解码失败）。 */
  def decodeAvatar(p: IdentityPayload): Option[Option[Array[Byte]]] =
    p.avatarB64 match
      case None => Some(None) // 无自定义头像
      case Some(b64) =>
        scala.util.Try(Base64.getDecoder.decode(b64)) match
          case scala.util.Success(bytes) if bytes.nonEmpty && bytes.length <= MaxAvatarBytes =>
            Some(Some(bytes))
          case _ => None

  // ── 装配缝（DeviceMailInbox/DeviceDelegate.initialize 同款单例形态）────────
  // seams 是 private[neblink]：neblink spec 注入假发送/自证做写读路径单测。

  @volatile private[neblink] var sendFn: (String, Json) => IO[Boolean] = null
  @volatile private[neblink] var selfIdFn: IO[String] = IO.pure("")
  @volatile private[neblink] var panelBroadcastFn: Json => IO[Unit] = null

  /**
   * 收件面装配（GatewayMain boot 单点调用，先例 = DeviceDelegate.initialize）：
   * 注册数据通道 handler + peer 变化对账推。未接线（NebLink 未配置）⇒ WARN +
   * 跳过（推送/收发面全变 no-op，单机形态零行为）。
   */
  def initialize(ns: Option[NeblinkService], panelBroadcast: Json => IO[Unit]): Unit =
    ns match
      case None =>
        logger.warnSync("AgentIdentitySync.initialize skipped: NebLink is not configured (no device network)")
      case Some(svc) =>
        sendFn = (deviceId, payload) => sendTwoLeg(svc, deviceId, payload)
        selfIdFn = svc.identity.map(_.deviceId)
        panelBroadcastFn = panelBroadcast
        // 收件面：unsafeRunSync 注册（DeviceDelegate 同款纪律——本语境是 Unit，
        // IO 返回值被丢弃即注册面静默缺失）。
        svc.addDataHandler(payload => handlePayload(payload)).unsafeRunSync()(
          using cats.effect.unsafe.implicits.global
        )
        // 对账推：peer 集合变化（boot 后首联/重连/新配对）⇒ 全量身份外推。
        svc.addPeerChangeCallback(pushAllIdentity()).unsafeRunSync()(
          using cats.effect.unsafe.implicits.global
        )
        logger.infoSync(
          s"AgentIdentitySync receive face armed (channel: $ChannelName, kind: $KindSync)"
        )

  /** DeviceDelegate.sendToDevice 同款双腿：presence 优先（送达判真），relay 兜底。 */
  private def sendTwoLeg(svc: NeblinkService, deviceId: String, payload: Json): IO[Boolean] =
    svc.sendData(deviceId, ChannelName, payload).flatMap {
      case true => IO.pure(true)
      case false =>
        svc.relayClientOpt match
          case Some(client) =>
            client.relayNotify(deviceId, ChannelName, payload).flatMap {
              case Right(_) => IO.pure(true)
              case Left(err) =>
                logger.debug(s"identity relay fallback failed for $deviceId: $err").as(false)
            }
          case None => IO.pure(false)
    }

  // ── 推送面（PresenceRoutes 写路径调用；未装配 = no-op）────────────────────

  /**
   * 本机身份写面（面板 PUT display-name / PUT·DELETE avatar）落盘成功后的统一
   * 收尾：① Soul.md 自我认知段（「落实到智能体的记忆」——本机写与远端收敛两条
   * 路都走这里，语义一致）；② 跨机外推（未装配 = no-op）。fire-and-forget，
   * 永不阻塞写路径。
   */
  def afterLocalWrite(agent: String): IO[Unit] =
    for
      rec <- IO.blocking(AgentIdentityStore.readRec(agent))
      _ <- AgentIdentityStore.touchSoulIdentity(agent, rec.displayName, rec.avatarSha256.isDefined)
      _ <- pushIdentity(agent)
    yield ()

  /** 单 agent 身份外推（fire-and-forget；失败只留元数据读数，不阻塞写路径）。 */
  def pushIdentity(agent: String): IO[Unit] =
    if sendFn == null then IO.unit
    else {
      for
        selfId <- selfIdFn.attempt.map(_.getOrElse(""))
        rec <- IO.blocking(AgentIdentityStore.readRec(agent))
        payloadOpt = buildPayload(rec, selfId, System.currentTimeMillis())
        _ <- payloadOpt match
          case None => IO.unit // 无身份内容 = 无可外推
          case Some(unsigned) =>
            val targets = DeviceRegistry.loadSync.map(_.deviceId).filterNot(_ == selfId)
            val digest = unsigned.avatarSha256.map(_.take(8)).getOrElse("-")
            val bytes = unsigned.avatarB64.map(_.length).getOrElse(0)
            logger
              .info(
                s"identity-sync push: agent=${unsigned.agent} updatedAt=${unsigned.updatedAt} avatarSha=$digest avatarLen=$bytes targets=${targets.size}"
              )
              .flatMap { _ =>
                // 每目标单独签署（配对 key 每对一份——见 signedFor）。
                targets.traverse_(t => sendFn(t, encodePayload(signedFor(unsigned, t))).flatMap {
                  case true  => IO.unit
                  case false => logger.debug(s"identity-sync: device $t unreachable (offline?)")
                })
              }
      yield ()
    }

  /**
   * 对账推：本机全部身份态逐一外推（peer 变化回调触发）。单飞闸防发现循环抖动
   * 下的帧暴（进行中 ⇒ 直接跳过，下一拍 peer 变化自然再推——对账推是幂等全量，
   * 跳过一拍无收敛风险）。
   */
  private val reconcileInFlight = new java.util.concurrent.atomic.AtomicBoolean(false)

  def pushAllIdentity(): IO[Unit] =
    if sendFn == null then IO.unit
    else if !reconcileInFlight.compareAndSet(false, true) then
      logger.debug("identity-sync reconcile: already in flight, skipping this round")
    else
      IO.blocking {
        val dir = nebflow.shared.PathUtil.dataRoot / "agents"
        if !os.exists(dir) then Nil
        else
          os.list(dir)
            .filter(os.isDir)
            .map(d => AgentIdentityStore.readRec(d.last))
            .filter(AgentIdentityStore.hasIdentity)
            .map(_.agent)
            .toList
      }.flatMap { agents =>
        if agents.isEmpty then IO.unit
        else
          logger.info(s"identity-sync reconcile: pushing ${agents.size} identity agent(s) on peer change") *>
            agents.traverse_(pushIdentity)
      }.guarantee(IO(reconcileInFlight.set(false)))

  // ── 收件面（对端帧落地：验签 → LWW 收敛 → Soul 腿 + 面板广播；零回推）─────

  private[neblink] def handlePayload(json: Json): IO[Unit] =
    parsePayload(json) match {
      case None => IO.unit // 非 identity-sync / 结构坏 —— 数据通道上有别的流量，静默
      case Some(p) =>
        val nowMs = System.currentTimeMillis()
        verifyPayload(p, nowMs) match {
          case Some(reason) =>
            logger.warn(s"identity-sync frame REJECTED from=${p.fromDeviceId} agent=${p.agent}: $reason")
          case None =>
            decodeAvatar(p) match {
              case None =>
                logger.warn(s"identity-sync frame REJECTED from=${p.fromDeviceId} agent=${p.agent}: avatar decode failed")
              case Some(bytesOpt) =>
                AgentIdentityStore
                  .applyRemote(
                    AgentIdentityStore.AgentIdentityRec(p.agent, p.displayName, p.avatarSha256, p.updatedAt),
                    bytesOpt
                  )
                  .flatMap { applied =>
                    if !applied then
                      logger
                        .debug(s"identity-sync: stale frame skipped agent=${p.agent} updatedAt=${p.updatedAt}")
                        .void
                    else {
                      // ① 落到智能体的记忆（Soul.md 自我认知段；仅根 agent 生效）。
                      // ② 面板立即重读（不等重启/刷新）。
                      val soulLeg = AgentIdentityStore
                        .touchSoulIdentity(p.agent, p.displayName, bytesOpt.isDefined)
                        .handleError(_ => ())
                      val broadcast = Option(panelBroadcastFn).getOrElse((_: Json) => IO.unit)(
                        Json.obj("type" -> "agentIdentityChanged".asJson, "agent" -> p.agent.asJson)
                      )
                      logger
                        .info(
                          s"identity-sync applied: agent=${p.agent} updatedAt=${p.updatedAt} " +
                            s"avatarSha=${p.avatarSha256.map(_.take(8)).getOrElse("-")} from=${p.fromDeviceId}"
                        )
                        .productR(soulLeg)
                        .productR(broadcast)
                    }
                  }
        }
    }
}
