package nebflow.neblink

import nebflow.neblink.core.NeblinkService

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.core.device.{DeviceHmac, DeviceRegistry}
import nebflow.shared.{NebflowLogger, PathUtil}

/**
 * ProfileSync —— 「双描述」四条字段的**跨设备收敛通道**（profile-sync 批
 * 2026-11-06，作者令「描述信息需要支持云同步」）。
 *
 * 断点（改造前）：`ProfileDescriptions` 的四条字段（好友 `bio` 缓存 / 我的群备注 /
 * 群介绍缓存 / 自我 `selfBio`）只落本机数据根 `<dataRoot>/profile-descriptions.json`，
 * 多机各跑一个引擎 ⇒ A 机写的备注在 B 机永不可见（`ProfileDescriptions` 头注逐字：
 * 「四条字段全部零跨设备同步」）。上游 `neblink-server` 没有这两个键（`NeblinkModel`
 * 解码器注释：上游无 bio/description 键）⇒ **没有可用的上游写通道**。
 *
 * 通道（**全复用既有设施，零新通道**，[[AgentIdentitySync]] / [[AgentSessionSync]]
 * 同族同构）：设备数据通道（presence `sendData` 优先 + relay `relayNotify` 兜底，
 * 与 identity-sync 逐行同款双腿）——一种载荷 `profile-sync`（本机全部描述键的
 * **全量状态**），两种触发：
 *
 *   · **推**（写后外推）：`FriendService` 的描述写面（setFriendBio / setGroupRemark /
 *     setGroupDescription / setSelfBio）落盘成功后 fire-and-forget 外推到全部已配对
 *     设备（[[afterLocalWrite]]）；
 *   · **对账推**（重连/发现即收敛）：peer 集合变化回调（boot 后隧道首联、对端重连、
 *     新配对都会触发）把本机全部描述态再推一遍——对端按**逐键** LWW 收敛，离线对端
 *     在它自己下次上线/重启的对账推里补齐（两腿对称，无定向重试、零新增定时器）。
 *
 * 收敛纪律：
 *   · **LWW + 网关时钟**：每个键带 `updatedAt`（**写入方网关**的毫秒时钟）与
 *     `originDeviceId`；收帧时 `updatedAt` **不新于**本地戳 ⇒ 丢弃（debug 级、不含
 *     内容）；严格更新 ⇒ 覆盖并落盘（[[decide]] 纯函数，spec 直测）；
 *   · **首见即收**：本地该键既无内容也无戳（新装/未写过）⇒ 直接收（戳为零的乱序
 *     守卫同 `RemoteAgentSessions`）；
 *   · **回声抑制靠结构**：远端收敛写入（[[applyVerified]]）**不在任何外推路径上**
 *     ——它只改 Ref + 落盘 + 广播 UI 帧，绝不调用 [[pushAllProfile]]；
 *   · **验签**：配对 sharedKey 的 HMAC（DeviceHmac，ts ±90s 窗 + nonce），未配对/
 *     伪造/过期帧一律丢弃（可读 WARN，不含载荷体）；
 *   · **内容永不入日志**：审计行只有 `key` 类别 / `originDeviceId` / `updatedAt` /
 *     `result`（applied|stale|rejected）/ `reason`；描述正文（用户写的备注/介绍）
 *     **绝不**进日志、不进异常消息。
 *
 * 元数据落点：`<dataRoot>/profile-sync-meta.json`（`key → {updatedAt, originDeviceId}`，
 * 与正文文件**分离**）。为什么分离：`profile-descriptions.json` 的现有结构与逐键
 * 折叠解码器**一个字节都不动** —— 旧文件照旧可读、旧版引擎照旧可写（版本兼容的
 * 最强形态：兼容性缺口为零）。缺 meta 文件 / 单键缺字段 ⇒ 空元数据回落（[[ProfileSyncMetaStore]]
 * 逐键 getOrElse），内容永不因元数据缺失而丢失。
 *
 * 边界（如实说明，「云同步」在本仓可达的形态）：这是**已配对设备之间的收敛**——
 * 走的是设备 P2P/relay 数据通道，不是公网云存储。未配对的机器之间不同步；所有对端
 * 长期离线时本机写入只停在本机（下次对端上线由对账推补齐）。
 */
object ProfileSync:

  private val logger = NebflowLogger.forName("nebflow.neblink.profile-sync")

  /** 数据通道名（presence sendData / relay Notify 的 channel 维度）。 */
  val ChannelName: String = "profile-descriptions"

  /** 载荷 kind（handleDataMessage 的 kind 判别维度）。 */
  val KindSync: String = "profile-sync"

  /** 前端 UI 帧型（引擎 → 浏览器；描述被远端更新后重读，不等刷新）。 */
  val FrameType: String = "profileDescriptionsChanged"

  // ── 键命名空间（单点：写入方、载荷、裁定、状态出口都读这里）────────────────

  val KindFriendBio: String = "friendBio"
  val KindGroupRemark: String = "groupRemark"
  val KindGroupDescription: String = "groupDescription"
  val KindSelfBio: String = "selfBio"

  /** 同步面（四条描述；`friendMuted` / `friendPinned` 属**设备本地偏好**，不在本通道）。 */
  val Kinds: List[String] = List(KindFriendBio, KindGroupRemark, KindGroupDescription, KindSelfBio)

  /**
   * 键 = `<kind>`（无 id 的单值键，如 `selfBio`）或 `<kind>:<id>`（映射键）。
   * id = `userId` / `groupId`（稳定 id，不用可变用户名/群名——与写面同律）。
   */
  def keyOf(kind: String, id: String = ""): String =
    if id.isEmpty then kind else s"$kind:$id"

  def kindOf(key: String): String =
    val i = key.indexOf(':')
    if i < 0 then key else key.take(i)

  def idOf(key: String): String =
    val i = key.indexOf(':')
    if i < 0 then "" else key.drop(i + 1)

  /** 键形状合法性（未知类别 / 缺 id / 多余段 ⇒ 帧内该条 rejected）。 */
  def isValidKey(key: String): Boolean =
    val k = kindOf(key)
    val id = idOf(key)
    if !Kinds.contains(k) then false
    else if k == KindSelfBio then id.isEmpty && key == KindSelfBio
    else id.nonEmpty && !id.contains(':')

  /** 单条描述值的长度闸（描述是人的定语，不是文档；超长 = 畸形帧，整条 rejected）。 */
  val MaxValueChars: Int = 4_000

  // ── 元数据（每键 LWW 戳 + 来源设备）──────────────────────────────────────

  final case class SyncMeta(updatedAt: Long, originDeviceId: String)

  /** 单条描述键的线上形状（`value` 为空串 = **清除**，墓碑语义）。 */
  final case class Entry(key: String, value: String, updatedAt: Long, originDeviceId: String)

  /** 全量载荷（本机全部描述键；单帧一次送达，无分页无增量）。 */
  final case class ProfilePayload(
    entries: List[Entry],
    fromDeviceId: String,
    tsSec: Long,
    nonce: String,
    sig: String
  )

  /** 裁定结果（审计与 spec 共用的字面量单点）。 */
  object Verdict:
    val Applied: String = "applied"
    val Stale: String = "stale"
    val Rejected: String = "rejected"

  final case class Outcome(key: String, result: String, reason: String)

  // ── 载荷（构建/解析/验签全部纯函数，spec 直测）────────────────────────────

  /** 描述正文 → 键映射（**只收非空值**；空值只以墓碑（元数据）形态存在于载荷里）。 */
  def contentMap(d: ProfileDescriptions): Map[String, String] =
    val bio = d.friendBio.view.filter(_._2.nonEmpty).map((id, v) => keyOf(KindFriendBio, id) -> v).toMap
    val gr = d.groupRemark.view.filter(_._2.nonEmpty).map((id, v) => keyOf(KindGroupRemark, id) -> v).toMap
    val gd = d.groupDescription.view.filter(_._2.nonEmpty).map((id, v) => keyOf(KindGroupDescription, id) -> v).toMap
    val self = if d.selfBio.nonEmpty then Map(KindSelfBio -> d.selfBio) else Map.empty[String, String]
    bio ++ gr ++ gd ++ self

  /**
   * 全量条目：**内容键 ∪ 元数据键**。元数据键单独在册的必要性 = 墓碑——A 机把
   * 某条备注清空后，B 机若只收「内容键」就永远删不掉旧值（清空没有内容可推）。
   */
  def entriesOf(d: ProfileDescriptions, meta: Map[String, SyncMeta]): List[Entry] =
    val content = contentMap(d)
    val keys = (content.keySet ++ meta.keySet).filter(isValidKey).toList.sorted
    keys.map { k =>
      meta.get(k) match
        case Some(m) => Entry(k, content.getOrElse(k, ""), m.updatedAt, m.originDeviceId)
        case None => Entry(k, content.getOrElse(k, ""), 0L, "")
    }

  /** 本机状态 → **未签名**载荷（无任何描述键 ⇒ None：无可外推）。 */
  def buildPayload(
    d: ProfileDescriptions,
    meta: Map[String, SyncMeta],
    fromDeviceId: String,
    nowMs: Long
  ): Option[ProfilePayload] =
    val entries = entriesOf(d, meta)
    if entries.isEmpty then None
    else Some(ProfilePayload(entries, fromDeviceId, nowSec(nowMs), newNonce(), ""))

  /** 条目数组的规范 JSON（**签名与编码的同一拼装点**；按 key 排序 ⇒ 确定性）。 */
  private def entriesJson(entries: List[Entry]): Json =
    Json.arr(
      entries.sortBy(_.key).map { e =>
        Json.obj(
          "key" -> e.key.asJson,
          "value" -> e.value.asJson,
          "updatedAt" -> e.updatedAt.asJson,
          "originDeviceId" -> e.originDeviceId.asJson
        )
      }*
    )

  /** 签名规范串（签名与验签的唯一拼装点；JSON 转义消除分隔符歧义）。 */
  def canonicalOf(p: ProfilePayload): String =
    s"$ChannelName|${p.fromDeviceId}|${p.tsSec}|${p.nonce}|${entriesJson(p.entries).noSpaces}"

  /** 以「本机与 `peerDeviceId` 的配对 key」签署载荷（每目标一份，identity 同律）。 */
  def signedFor(p: ProfilePayload, peerDeviceId: String): ProfilePayload =
    p.copy(sig = DeviceHmac.sign(DeviceRegistry.secretForSync(peerDeviceId).getOrElse(""), canonicalOf(p)))

  private def newNonce(): String = java.util.UUID.randomUUID().toString.replace("-", "")
  private def nowSec(nowMs: Long): Long = nowMs / 1000L

  /** 宽容解析（缺字段/类型错/任一条目畸形 ⇒ None——收端 fail-closed，同 DeviceMail.parse 纪律）。 */
  def parsePayload(json: Json): Option[ProfilePayload] =
    val c = json.hcursor
    val kind = c.downField("kind").as[String].getOrElse("")
    if kind != KindSync then None
    else
      for
        fromDeviceId <- c.downField("fromDeviceId").as[String].toOption.filter(_.nonEmpty)
        tsSec <- c.downField("ts").as[Long].toOption
        nonce <- c.downField("nonce").as[String].toOption.filter(_.nonEmpty)
        sig <- c.downField("sig").as[String].toOption.filter(_.nonEmpty)
        raw <- c.downField("entries").as[List[Json]].toOption.filter(_.nonEmpty)
        entries <- raw.traverse(parseEntry)
      yield ProfilePayload(entries, fromDeviceId, tsSec, nonce, sig)

  private def parseEntry(json: Json): Option[Entry] =
    val c = json.hcursor
    for
      key <- c.downField("key").as[String].toOption.filter(_.nonEmpty)
      value <- c.downField("value").as[String].toOption
      updatedAt <- c.downField("updatedAt").as[Long].toOption
      origin = c.downField("originDeviceId").as[String].toOption.getOrElse("")
    yield Entry(key, value, updatedAt, origin)

  /** 编码上机（kind 信封 + 条目数组）。 */
  def encodePayload(p: ProfilePayload): Json =
    Json.fromJsonObject(
      JsonObject(
        "kind" -> KindSync.asJson,
        "entries" -> entriesJson(p.entries),
        "fromDeviceId" -> p.fromDeviceId.asJson,
        "ts" -> p.tsSec.asJson,
        "nonce" -> p.nonce.asJson,
        "sig" -> p.sig.asJson
      )
    )

  /** 验签（配对 key + ts 窗）。None = 通过；Some = 拒收原因（可读、不含载荷体）。 */
  def verifyPayload(p: ProfilePayload, nowMs: Long): Option[String] =
    if !DeviceHmac.tsFresh(p.tsSec.toString, nowMs) then Some(s"stale ts (${p.tsSec})")
    else
      DeviceRegistry.secretForSync(p.fromDeviceId) match
        case None => Some(s"sender device '${p.fromDeviceId}' not paired")
        case Some("") => Some("empty shared key")
        case Some(key) =>
          if DeviceHmac.verify(key, canonicalOf(p), p.sig) then None
          else Some("signature mismatch")

  // ── LWW 裁定（纯函数）──────────────────────────────────────────────────────

  /**
   * 逐键裁定（**唯一判据点**）：
   *   ① 键形状非法 / 值超长 ⇒ `rejected`（不改本地）；
   *   ② 本地有戳且 `remote.updatedAt <= local.updatedAt` ⇒ `stale`（丢弃）；
   *   ③ 本地无戳：内容也为空 ⇒ `applied`（首见即收）；内容非空且远端戳为零 ⇒ `stale`
   *      （升级前的旧内容 vs 升级前的旧帧：无法排序，保持本地——本机升级后首次写
   *      就会补戳，此后按 ② 走）；
   *   ④ 其余 ⇒ `applied`（远端严格更新 / 首见）。
   */
  def decide(
    localContent: Map[String, String],
    localMeta: Map[String, SyncMeta],
    entries: List[Entry]
  ): List[Outcome] =
    entries.map { e =>
      if !isValidKey(e.key) then Outcome(e.key, Verdict.Rejected, "unknown key shape")
      else if e.value.length > MaxValueChars then
        Outcome(e.key, Verdict.Rejected, s"value too long (${e.value.length} > $MaxValueChars)")
      else
        localMeta.get(e.key) match
          case Some(m) if e.updatedAt <= m.updatedAt =>
            Outcome(e.key, Verdict.Stale, s"local updatedAt=${m.updatedAt} not older than remote ${e.updatedAt}")
          case Some(m) =>
            Outcome(e.key, Verdict.Applied, s"remote ${e.updatedAt} newer than local ${m.updatedAt}")
          case None =>
            if localContent.contains(e.key) && e.updatedAt == 0L then
              Outcome(e.key, Verdict.Stale, "unstamped local value wins over unstamped frame")
            else if localContent.contains(e.key) then Outcome(e.key, Verdict.Applied, "local value unstamped")
            else Outcome(e.key, Verdict.Applied, "first seen")
    }

  /** 单键写入（`value` 空串 = 清除；纯函数）。 */
  def putValue(d: ProfileDescriptions, key: String, value: String): ProfileDescriptions =
    val id = idOf(key)
    kindOf(key) match
      case KindFriendBio =>
        d.copy(friendBio = if value.isEmpty then d.friendBio - id else d.friendBio + (id -> value))
      case KindGroupRemark =>
        d.copy(groupRemark = if value.isEmpty then d.groupRemark - id else d.groupRemark + (id -> value))
      case KindGroupDescription =>
        d.copy(groupDescription = if value.isEmpty then d.groupDescription - id else d.groupDescription + (id -> value))
      case KindSelfBio => d.copy(selfBio = value)
      case _ => d

  /** 把已裁定为 applied 的条目写进描述（纯函数，全量折叠）。 */
  def applyEntries(d: ProfileDescriptions, entries: List[Entry]): ProfileDescriptions =
    entries.foldLeft(d)((acc, e) => putValue(acc, e.key, e.value))

  // ── 装配缝（AgentIdentitySync 同款单例形态；private[neblink] 供 spec 注入）──

  @volatile private[neblink] var sendFn: (String, Json) => IO[Boolean] = null
  @volatile private[neblink] var selfIdFn: IO[String] = IO.pure("")
  @volatile private[neblink] var panelBroadcastFn: Json => IO[Unit] = null

  /** 描述面的**同一 Ref**（与 `FriendService` 的写面同源；未挂 = 单机形态）。 */
  @volatile private[neblink] var descRef: Ref[IO, ProfileDescriptions] = null

  /** 最近一次外推是否全部送达（`None` = 本进程还没推过；状态出口读它）。 */
  @volatile private[neblink] var lastPushOk: Option[Boolean] = None

  private val pushInFlight = new java.util.concurrent.atomic.AtomicBoolean(false)

  /**
   * 描述 Ref 挂接（`FriendService` 构造体内自注册，先例 = `NeblinkWiring` 的
   * LlmRuntimePort/DeviceMailAck 自注册段）：远端收敛写面必须与本地写面**同源**，
   * 否则收帧改的是磁盘、内存 Ref 仍是旧值（下次本地写把远端值覆盖回去）。
   */
  private[neblink] def attachDescriptions(ref: Ref[IO, ProfileDescriptions]): Unit =
    descRef = ref

  /** spec 用：清空全部运行期缝（生产无调用点）。 */
  private[neblink] def resetRuntimeState(): Unit =
    sendFn = null
    selfIdFn = IO.pure("")
    panelBroadcastFn = null
    descRef = null
    lastPushOk = None
    pushInFlight.set(false)

  /**
   * 收件面装配（GatewayMain boot 单点调用，先例 = AgentIdentitySync.initialize）：
   * 注册数据通道 handler + peer 变化对账推。未接线（NebLink 未配置）⇒ WARN + 跳过
   * （推送面变 no-op；本地写戳照旧进行，状态出口全报 `local-only`）。
   */
  def initialize(ns: Option[NeblinkService], panelBroadcast: Json => IO[Unit]): Unit =
    ns match
      case None =>
        logger.warnSync(
          "ProfileSync.initialize skipped: NebLink is not configured (no device network) — all fields local-only"
        )
      case Some(svc) =>
        sendFn = (deviceId, payload) => sendTwoLeg(svc, deviceId, payload)
        selfIdFn = svc.identity.map(_.deviceId)
        panelBroadcastFn = panelBroadcast
        // 收件面：unsafeRunSync 注册（DeviceDelegate/AgentIdentitySync 同款纪律）。
        svc
          .addDataHandler(payload => handlePayload(payload))
          .unsafeRunSync()(using
            cats.effect.unsafe.implicits.global
          )
        // 对账推：peer 集合变化（boot 后首联/重连/新配对）⇒ 全量描述外推。
        svc
          .addPeerChangeCallback(pushAllProfile())
          .unsafeRunSync()(using
            cats.effect.unsafe.implicits.global
          )
        logger.infoSync(s"ProfileSync receive face armed (channel: $ChannelName, kind: $KindSync)")

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
                logger.debug(s"profile-sync relay fallback failed for $deviceId: $err").as(false)
            }
          case None => IO.pure(false)
    }

  /** 本机设备 id（未装配 ⇒ 空串：单机形态的戳来源诚实标「无来源设备」）。 */
  private def originNow: IO[String] =
    if sendFn == null then IO.pure("")
    else selfIdFn.attempt.map(_.getOrElse(""))

  // ── 推送面（FriendService 写路径 + peer 变化回调调用；未装配 = no-op）──────

  /**
   * 本机描述写面落盘成功后的统一收尾：① 逐键打戳（**网关时钟** ms + 本机设备 id）；
   * ② 结构化审计一行/键；③ fire-and-forget 全量外推（未装配 = no-op）。
   *
   * 调用方（`FriendService.putDescription`）以 `.start` 起 fiber ⇒ 永不阻塞写路径。
   */
  def afterLocalWrite(touchedKeys: List[String]): IO[Unit] =
    if touchedKeys.isEmpty then IO.unit
    else
      val nowMs = System.currentTimeMillis()
      for
        origin <- originNow
        _ <- ProfileSyncMetaStore.update { m =>
          touchedKeys.foldLeft(m)((acc, k) => acc + (k -> SyncMeta(nowMs, origin)))
        }
        _ <- touchedKeys.traverse_ { k =>
          audit(k, origin, nowMs, Verdict.Applied, "local write")
        }
        _ <- pushAllProfile()
      yield ()

  /**
   * 全量描述外推（写后外推与对账推是**同一个**动作——载荷本来就是全量，语义幂等）。
   * 单飞闸防帧暴（identity-sync 同款：进行中 ⇒ 跳过，下一拍 peer 变化自然再推）。
   * 失败只留可读 WARN（不含内容），绝不抛给写路径。
   */
  def pushAllProfile(): IO[Unit] =
    val fn = sendFn
    val ref = descRef
    if fn == null || ref == null then IO.unit
    else if !pushInFlight.compareAndSet(false, true) then
      logger.debug("profile-sync push: already in flight, skipping this round")
    else
      (for
        selfId <- selfIdFn.attempt.map(_.getOrElse(""))
        d <- ref.get
        meta <- ProfileSyncMetaStore.all()
        payload = buildPayload(d, meta, selfId, System.currentTimeMillis())
        targets = DeviceRegistry.loadSync
          .map(_.deviceId)
          .filterNot(_ == selfId)
          // 配对表条目缺 sharedKey ⇒ 跳过（空 key 签不出可验的帧；硬签会让整轮外推
          // 中断——identity-sync 的空 key 抛异常同源，这里显式挡在签名之前）。
          .filter(id => DeviceRegistry.secretForSync(id).exists(_.nonEmpty))
        _ <- payload match
          case None => IO.unit
          case Some(unsigned) =>
            logger
              .info(
                "profile-sync push",
                "entries" -> unsigned.entries.size.toString,
                "targets" -> targets.size.toString
              )
              .flatMap { _ =>
                // 每目标单独签署（配对 key 每对一份——见 signedFor）。
                targets
                  .traverse(t => fn(t, encodePayload(signedFor(unsigned, t))).redeem(_ => false, ok => ok))
                  .flatMap(oks => IO { lastPushOk = Some(oks.forall(ok => ok)) })
              }
      yield ())
        .handleErrorWith(e => logger.warn(s"profile-sync push failed: ${e.getMessage}"))
        .guarantee(IO(pushInFlight.set(false)))
    end if
  end pushAllProfile

  // ── 收件面（对端帧落地：验签 → 逐键 LWW → 落盘 → 审计 → 广播；零回推）──────

  private[neblink] def handlePayload(json: Json): IO[Unit] =
    parsePayload(json) match
      case None => IO.unit // 非 profile-sync / 结构坏 —— 数据通道上有别的流量，静默
      case Some(p) =>
        val nowMs = System.currentTimeMillis()
        verifyPayload(p, nowMs) match
          case Some(reason) =>
            logger.warn(
              "profile-sync frame REJECTED",
              "from" -> p.fromDeviceId,
              "entries" -> p.entries.size.toString,
              "reason" -> reason
            )
          case None => applyVerified(p).void

  /** 已验签帧的收敛面（**不触发任何外推**——回声抑制靠结构）。 */
  private[neblink] def applyVerified(p: ProfilePayload): IO[List[Outcome]] =
    val ref = descRef
    if ref == null then logger.warn("profile-sync frame dropped: descriptions ref not attached").as(Nil)
    else
      for
        cur <- ref.get
        meta <- ProfileSyncMetaStore.all()
        outcomes = decide(contentMap(cur), meta, p.entries)
        applied = outcomes.filter(_.result == Verdict.Applied)
        appliedKeys = applied.map(_.key).toSet
        appliedEntries = p.entries.filter(e => appliedKeys.contains(e.key))
        _ <-
          if applied.isEmpty then IO.unit
          else
            ref
              .modify { now =>
                val next = applyEntries(now, appliedEntries)
                (next, next)
              }
              .flatTap(ProfileDescriptionStore.save)
              .flatMap { _ =>
                ProfileSyncMetaStore.update { m =>
                  appliedEntries.foldLeft(m)((acc, e) => acc + (e.key -> SyncMeta(e.updatedAt, e.originDeviceId)))
                }
              }
        _ <- outcomes.traverse_ { o =>
          // 审计的 originDeviceId 取**条目自带的来源设备**（远端帧的作者），缺省回落到
          // 发帧设备——「谁写的」比「谁转发的」更有判读价值。
          val src = p.entries.find(_.key == o.key)
          audit(
            o.key,
            src
              .map(e => if e.originDeviceId.nonEmpty then e.originDeviceId else p.fromDeviceId)
              .getOrElse(p.fromDeviceId),
            src.map(_.updatedAt).getOrElse(0L),
            o.result,
            o.reason
          )
        }
        _ <- if applied.isEmpty then IO.unit else broadcastChanged()
      yield outcomes

    end if

  end applyVerified

  /** 描述被远端改动：面板立即重读（不等刷新/重启）。未装配 = no-op。 */
  private def broadcastChanged(): IO[Unit] =
    Option(panelBroadcastFn) match
      case Some(broadcast) => broadcast(Json.obj("type" -> FrameType.asJson)).handleError(_ => IO.unit)
      case None => IO.unit

  // ── 审计（结构化；**描述正文永不入日志**）────────────────────────────────

  private def audit(key: String, originDeviceId: String, updatedAt: Long, result: String, reason: String): IO[Unit] =
    val kv = Seq(
      "key" -> key,
      "originDeviceId" -> originDeviceId,
      "updatedAt" -> updatedAt.toString,
      "result" -> result,
      "reason" -> reason
    )
    result match
      case Verdict.Applied => logger.info("profile-sync audit", kv*)
      case Verdict.Stale => logger.debug("profile-sync audit", kv*) // 旧戳丢弃：debug（不含内容）
      case _ => logger.warn("profile-sync audit", kv*)

  // ── 只读状态出口（前端徽标读它；未配置 NebLink ⇒ 全 local-only）───────────

  /**
   * `GET /api/profile/sync-status` 的数据面：每类字段
   * `{state: synced|syncing|local-only, updatedAt, peers}`。
   *
   * 判据（诚实优先——**绝不**在无证据时声称 synced）：
   *   · 未装配 NebLink / 已配对设备为 0 ⇒ `local-only`（没有对端可同步）；
   *   · 外推在飞行 ⇒ `syncing`；
   *   · 本机该字段有账目但最近一次外推未成功（失败 / 从未成功）⇒ `local-only`；
   *   · 否则 ⇒ `synced`（最近一次全量外推全部送达，或本机该字段无任何账目）。
   */
  def status: IO[Json] =
    for
      meta <- ProfileSyncMetaStore.all()
      selfId <- originNow
      d <- Option(descRef).fold(IO.pure(ProfileDescriptions.empty))(_.get)
    yield
      val wired = sendFn != null
      val peers = DeviceRegistry.loadSync.map(_.deviceId).filterNot(_ == selfId).size
      val content = contentMap(d)
      val fields = Kinds.map { kind =>
        val keys = (content.keySet ++ meta.keySet).filter(k => kindOf(k) == kind)
        val updatedAt = keys.flatMap(meta.get).map(_.updatedAt).maxOption.getOrElse(0L)
        val state =
          if !wired || peers == 0 then "local-only"
          else if pushInFlight.get then "syncing"
          else if keys.nonEmpty && !lastPushOk.contains(true) then "local-only"
          else "synced"
        kind -> Json
          .obj(
            "state" -> state.asJson,
            "updatedAt" -> updatedAt.asJson,
            "peers" -> peers.asJson
          )
          .asJson
      }
      Json.obj(
        "neblink" -> wired.asJson,
        "selfDeviceId" -> selfId.asJson,
        "peers" -> peers.asJson,
        "fields" -> Json.fromFields(fields)
      )

end ProfileSync

/**
 * 描述同步元数据仓：`<dataRoot>/profile-sync-meta.json`
 * （`{"<key>": {"updatedAt": <ms>, "originDeviceId": "<deviceId>"}}`）。
 *
 * 为什么与正文分文件：正文文件（`profile-descriptions.json`）的**结构、编码器、
 * 逐键折叠解码器一个字节都不动** ⇒ 旧文件/旧版引擎读写零影响（版本兼容的最强形态）。
 *
 * 容错（缺键回落，同 `RemoteAgentSessions` 的 codec 纪律）：
 *   · 文件缺席 / 顶层非对象 / JSON 坏 ⇒ 空元数据（正文不受影响，首见即收）；
 *   · 单键缺 `updatedAt` ⇒ 该键按「无戳」处理（其余键照读，禁整份失败）。
 *
 * 写者纪律：单进程单写者；`update` 在锁内读-改-写整文件（`os.write.over` 原子覆盖，
 * 与 `ProfileDescriptionStore` / `FriendRemarkStore` 同族）。`def path` 而非 `val`
 * （f1cd3709 规则：`PathUtil.setDataRoot` 可重定向，per-test data root 必需）。
 */
object ProfileSyncMetaStore:

  private val logger = NebflowLogger.forName("nebflow.neblink.profile-sync.meta")
  private def path = PathUtil.dataRoot / "profile-sync-meta.json"
  private val lock = new Object

  def all(): IO[Map[String, ProfileSync.SyncMeta]] = IO.blocking(readSync())

  /** 原子读-改-写（返回改后的全量；落盘失败只留 WARN，不砸调用方）。 */
  def update(
    f: Map[String, ProfileSync.SyncMeta] => Map[String, ProfileSync.SyncMeta]
  ): IO[Map[String, ProfileSync.SyncMeta]] =
    IO.blocking {
      lock.synchronized {
        val next = f(readSync())
        try os.write.over(path, encode(next), createFolders = true)
        catch case e: Exception => logger.warnSync(s"profile-sync-meta persist failed: ${e.getMessage}")
        next
      }
    }

  private def encode(m: Map[String, ProfileSync.SyncMeta]): String =
    Json
      .obj(
        m.toList.sortBy(_._1).map { case (k, v) =>
          k -> Json.obj(
            "updatedAt" -> v.updatedAt.asJson,
            "originDeviceId" -> v.originDeviceId.asJson
          )
        }*
      )
      .spaces2

  private def readSync(): Map[String, ProfileSync.SyncMeta] =
    if !os.exists(path) then Map.empty
    else
      io.circe.parser.parse(os.read(path)) match
        case Left(err) =>
          logger.warnSync(s"profile-sync-meta decode failed (${err.getMessage}) — treating as empty")
          Map.empty
        case Right(json) =>
          json.asObject match
            case None =>
              logger.warnSync("profile-sync-meta is not a JSON object — treating as empty")
              Map.empty
            case Some(o) =>
              o.toMap.flatMap { case (k, v) =>
                val c = v.hcursor
                c.downField("updatedAt")
                  .as[Long]
                  .toOption
                  .map(ts =>
                    k -> ProfileSync.SyncMeta(ts, c.downField("originDeviceId").as[String].toOption.getOrElse(""))
                  )
              }

end ProfileSyncMetaStore
