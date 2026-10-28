package nebflow.core.delegate

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import io.circe.JsonObject
import nebflow.actor.*
import nebflow.core.AgentRuntimePort
import nebflow.core.device.{DeviceHmac, DeviceRegistry, NonceCache}
import nebflow.shared.{NebflowLogger, PeerInfo}

/**
 * 设备腿 delegate 通道（v2.1 阶段 1 §A.3 / §E 阶段 1「项目分布执行」）。
 *
 * **通道**：presence WS 结构化消息（既有数据通道单点，`sendData`）+ relay 兜底
 * （`relayNotify` → 对端 `handleDataMessage` 同一入口）。载荷判别键 = `kind`
 * （数据通道的 channel 在 presence 监听器侧被剥离——dropbox 先例同构）：
 *   - `delegate-spawn`：host → origin 设备（投活；幂等 spawnId；HMAC 签名必验）；
 *   - `delegate-accept`：设备 → host（对端本地会话已建，回执对端 sessionId）；
 *   - `delegate-result`：设备 → host（最终结果必达；产物指针清单随行，字节不回传）。
 *
 * **安全**（v2 §A.4 / v2.1 §A.3）：spawn 必须来自**已配对**设备（DeviceRegistry）
 * 且 `allowSpawn=true`，载荷 HMAC 验签（ts ±90s + nonce 防重放 + 常数时间比对）。
 * 未配对/验签失败 = 显式拒绝（日志带 deviceId），**绝不静默**。spawn 是新通道、
 * 无存量兼容面 ⇒ HMAC **恒开**（无双轨灰度——灰度双轨只属于既有 HTTP 设备面，
 * 见 NeblinkRoutes.verifyPeerAccess 的 DeviceAuthMode）。
 *
 * **路由语义**（v2.1 §A.2 裁决）：delegate 不加 device 参数；`project.origin.device`
 * 派生路由。本对象只承载「已派生出发设备」的投递/接收机械——派生判定在
 * [[nebflow.core.tools.DelegateTool]]（resolveWorkspace）。
 *
 * **对端执行形态**：收 spawn 后建**它自己的**本地 delegate 会话
 * （[[nebflow.core.tools.DelegateTool.spawnBackground]] 单点——Mail 续聊复活腿同点
 * 先例），对端用自己的 executor 集原生执行；对端 DelegateRegistry 记
 * `delegated-by:<hostDeviceId>`（origin 标记，验收 #2）；完成经**回投代理 actor**
 * （[[resultProxyBehavior]]）把终态事件折成 `delegate-result` 发回 host。
 * host 收 result 以 `source="delegate(project:<名> @ <设备名>)"` 系统消息回投父会话
 * （对齐既有 delegate 回投语义），产物指针登记进 ExternalEvent metadata
 * （`artifactPointers`）+ TaskLedger 注记——**字节不回传**。
 */
object DeviceDelegate:

  private val logger = NebflowLogger.forName("nebflow.device-delegate")

  /** 数据通道名（informational——presence 监听器剥离 channel，判别靠 payload.kind）。 */
  val ChannelName = "delegate"

  val KindSpawn = "delegate-spawn"
  val KindAccept = "delegate-accept"
  val KindResult = "delegate-result"

  /** spawn 规范串的伪 method/path（HMAC canonical 的域分隔，恒定字面）。 */
  private val SpawnDomain = "SPAWN"
  private val SpawnPath = "/neblink/delegate-spawn"

  // ── 幂等面 ────────────────────────────────────────────────────────────
  // spawnId 去重（收端进程内；at-least-once 通道（relay 重发/presence 半开重投）
  // 的重放防线。跨重启 = 重放窗也随进程归零——对端重启后同一 spawnId 重投会再执行
  // 一次：诚实边界（与 NonceCache 同级），声明于交付报告。）
  private val processedSpawns = new java.util.concurrent.ConcurrentHashMap[String, java.lang.Boolean]()

  private val nonces = new NonceCache(1024)

  // ── 载荷构造（host 侧，纯）─────────────────────────────────────────────

  /**
   * spawn 规范串（**签名与验签唯一拼装点**）：语义字段直接进串（不经 JSON 序列化
   * ——禁两套编码）。`taskId` 可缺席（台账开条失败也要能派活）。
   */
  def spawnCanonical(
    fromDeviceId: String,
    ts: Long,
    nonce: String,
    spawnId: String,
    projectName: String,
    workspace: String,
    task: String,
    title: String,
    parentSessionId: String,
    rootSessionId: String,
    taskId: Option[String]
  ): String =
    List(
      fromDeviceId,
      ts.toString,
      nonce,
      SpawnDomain,
      SpawnPath,
      spawnId,
      projectName,
      workspace,
      title,
      parentSessionId,
      rootSessionId,
      taskId.getOrElse(""),
      DeviceHmac.bodySha256(task)
    ).mkString("|")

  /** 组 spawn 载荷并签名（host 侧单点）。 */
  def buildSpawnPayload(
    sharedKey: String,
    fromDeviceId: String,
    fromDeviceName: String,
    spawnId: String,
    projectName: String,
    workspace: String,
    task: String,
    title: String,
    parentSessionId: String,
    rootSessionId: String,
    taskId: Option[String],
    nowMs: Long = System.currentTimeMillis(),
    nonce: String = newNonce()
  ): Json =
    val tsSec = nowMs / 1000
    val sig = DeviceHmac.sign(
      sharedKey,
      spawnCanonical(fromDeviceId, tsSec, nonce, spawnId, projectName, workspace, task, title, parentSessionId, rootSessionId, taskId)
    )
    Json.obj(
      "kind" -> KindSpawn.asJson,
      "spawnId" -> spawnId.asJson,
      "taskId" -> taskId.asJson,
      "projectName" -> projectName.asJson,
      "workspace" -> workspace.asJson,
      "task" -> task.asJson,
      "title" -> title.asJson,
      "parentSessionId" -> parentSessionId.asJson,
      "rootSessionId" -> rootSessionId.asJson,
      "fromDeviceId" -> fromDeviceId.asJson,
      "fromDeviceName" -> fromDeviceName.asJson,
      "ts" -> tsSec.asJson,
      "nonce" -> nonce.asJson,
      "sig" -> sig.asJson
    )

  private def newNonce(): String =
    java.util.UUID.randomUUID().toString.replace("-", "")

  // ── host 侧：派发（投 spawn；presence 优先 → relay 兜底）───────────────

  /**
   * 投递单点。两腿任一成功即 Right（fire-and-forget：设备中途离线的终态不再送达
   * ——ack 文案已如实声明，不排队、不静默重投）。双腿全败 = 显式失败。
   */
  def dispatch(
    resources: AgentRuntimePort,
    peer: PeerInfo,
    projectName: String,
    workspace: String,
    task: String,
    title: String,
    parentSessionId: String,
    rootSessionId: String,
    taskId: Option[String]
  ): IO[Either[String, Json]] =
    for
      selfId <- resources.neblinkService match
        case Some(ns) => ns.identity.map(_.deviceId)
        case None => IO.pure("")
      selfName <- resources.neblinkService match
        case Some(ns) => ns.identity.map(_.deviceName)
        case None => IO.pure("")
      spawnId = s"dd-${java.util.UUID.randomUUID().toString.take(8)}"
      dispatchResult <-
        if selfId.isEmpty then IO.pure(Left("NebLink identity unavailable on this host (DEVICE_ROUTE_UNAVAILABLE)"))
        else
          DeviceRegistry.secretFor(peer.deviceId).flatMap {
            case None =>
              IO.pure(
                Left(
                  s"device '${peer.deviceName}' is not PAIRED with this host " +
                    s"(no shared key in ${nebflow.shared.PathUtil.dataRoot}/neblink/devices.json) — " +
                    "pair both devices first (ops face, phase 1 manual pairing). (DEVICE_NOT_PAIRED)"
                )
              )
            case Some(key) =>
              val payload = buildSpawnPayload(
                key, selfId, selfName, spawnId, projectName, workspace, task, title,
                parentSessionId, rootSessionId, taskId
              )
              sendToDevice(resources, peer.deviceId, payload)
          }
    yield dispatchResult.map(_ => Json.obj("spawnId" -> spawnId.asJson))

  /**
   * 数据通道发送单点：presence `sendData` 优先（送达判真——半开连接回 false），
   * relay `relayNotify` 兜底（对端同一入口 handleDataMessage）。双腿全败 = Left。
   *
   * 台账 #944：本方法**只管发一次**（不落盘）——需要「宁重勿丢」的完成件必须走
   * [[sendDurable]]（先落盘再发）。
   */
  def sendToDevice(resources: AgentRuntimePort, deviceId: String, payload: Json): IO[Either[String, Json]] =
    resources.neblinkService match
      case None => IO.pure(Left("NebLink is not configured on this host (DEVICE_ROUTE_UNAVAILABLE)"))
      case Some(ns) =>
        ns.sendData(deviceId, ChannelName, payload).flatMap {
          case true => IO.pure(Right(payload))
          case false =>
            ns.relayClientOpt match
              case Some(client) =>
                client.relayNotify(deviceId, ChannelName, payload).flatMap {
                  case Right(_) => IO.pure(Right(payload))
                  case Left(err) => IO.pure(Left(s"presence send failed and relay fallback failed: $err"))
                }
              case None =>
                IO.pure(Left("presence send failed and no relay client is configured (device offline?)"))
        }
  end sendToDevice

  /**
   * boot 收殓腿的跨设备补录（台账 #944）：进程重启把在飞会话连回投代理一起杀掉 ⇒
   * 终态件永不到达、从未离机。收殓腿在本机终态化任务行之后，用本方法把「这件丢了，
   * 部分结果如下」落进**设备待发区**（[[DeviceResultOutbox]]），relay（重）连时按序
   * 补投，宿主按 spawnId 判重。
   *
   * 载荷形态与 [[resultProxyBehavior]] 的真终态件**同构**（同一 `kind`、同一宿主
   * 处理入口 `handleResult`）——宿主侧零新分支：一条 delegate-result，`status=failed`
   * 且 `result` 里带收殓说明与抢救出的部分结果。改前该路径因「父会话不在本机」
   * 被判为「父会话已删」整条跳过（实证 6/6 静默蒸发）。
   *
   * 幂等：`enqueue` 以 spawnId 为键，重复 boot 不重写不重排。
   */
  def enqueueLostResultNotice(
    task: nebflow.shared.SubAgentTask,
    hostDeviceId: String,
    partial: Option[String],
    head: String
  ): IO[Unit] =
    val selfId = neblinkDeviceId()
    val payload = Json.obj(
      "kind" -> KindResult.asJson,
      "spawnId" -> task.taskId.asJson,
      "status" -> "failed".asJson,
      "source" -> "delegate".asJson,
      "result" -> (partial match
        case Some(text) =>
          s"$head Partial result recovered from the child's disk transcript:\n$text"
        case None =>
          s"$head No recoverable result on disk — the task is marked failed. " +
            s"Original prompt: ${task.prompt.take(500)}"
      ).asJson,
      "deviceName" -> neblinkDisplayName(selfId).asJson,
      "deviceId" -> selfId.asJson,
      // projectName 从派发时的 registry 记录取（收殓路径没有 spawn 载荷；空 = 未知，
      // 宿主侧 handleResult 按既有缺省处理）。
      "projectName" -> DelegateRegistry.find(task.taskId).flatMap(_.project).getOrElse("").asJson,
      "parentSessionId" -> task.parentSessionId.asJson,
      "pointers" -> Json.arr()
    )
    logger.info(
      s"[device-delegate] boot recovery: enqueueing lost-result notice for spawnId=${task.taskId} " +
        s"into the device outbox (host=$hostDeviceId, partialResult=${partial.isDefined})"
    ) *>
      DeviceResultOutbox.enqueue(hostDeviceId, ChannelName, payload, task.taskId).void

  /** 本机 deviceId（同步只读探测；设备身份文件缺席 = 空串，显示面退化可接受）。 */
  private def neblinkDeviceId(): String =
    try
      nebflow.neblink.DeviceIdentity.readDeviceIdOnly.getOrElse("")
    catch case _: Throwable => ""

  /**
   * **持久化发送腿**（台账 #944 跨设备回报丢件腿）：先落盘到
   * [[DeviceResultOutbox]] 再发一次；发成功即出队，失败/异常留在盘上由 relay（重）连
   * 时按序补投。语义 = **at-least-once（宁重勿丢）**——幂等在宿主侧
   * （`DeliveredReceiptLedger`，键 = spawnId）。
   *
   * 定因（本腿堵的那条缝）：进程重启把在飞会话连回投代理一起杀掉 ⇒ 终态事件永不到
   * 达 ⇒ 结果从未离机；boot 收殓腿因「父会话不在本机」跳过通知 ⇒ 件静默蒸发。
   * 落盘队列让「机器死了/隧道不在/宿主暂不可达」都退化成「晚一点到」，绝不丢。
   *
   * 落盘本身失败不阻断发送（簿记面纪律：簿记挂掉不该让件也挂掉）——此时退化为
   * 改前的 fire-and-forget，WARN 如实留痕。
   */
  def sendDurable(
    resources: AgentRuntimePort,
    deviceId: String,
    channel: String,
    payload: Json,
    id: String
  ): IO[Either[String, Json]] =
    DeviceResultOutbox.enqueue(deviceId, channel, payload, id) *>
      sendToDevice(resources, deviceId, payload).flatMap {
        case ok @ Right(_) =>
          DeviceResultOutbox.delivered(id).as(ok)
        case ko @ Left(_) => IO.pure(ko) // 留盘：等 relay 重连 / 下一次 boot 补投
      }.handleErrorWith { err =>
        // 发送腿自身炸了：件已在盘上，如实回 Left（下一次 drain 会重来）。
        logger
          .warn(s"[device-delegate] durable send errored for id=$id (kept in outbox): ${err.getMessage}")
          .as(Left(Option(err.getMessage).getOrElse(err.getClass.getSimpleName)))
      }

  /**
   * 补投待发区全量（relay（重）连上时调用；boot 亦有兜底调用点）。
   * `send` 参数留缝供 spec 纯桩驱动——生产调用恒传 [[sendToDevice]]。
   */
  def drainOutbox(
    resources: AgentRuntimePort,
    send: (String, String, Json) => IO[Either[String, Json]] = null
  ): IO[DeviceResultOutbox.DrainReport] =
    val leg = Option(send).getOrElse((d: String, c: String, p: Json) => sendToDevice(resources, d, p))
    DeviceResultOutbox.drain(leg).flatMap { report =>
      (if report.delivered.nonEmpty then
         logger.info(
           s"[device-delegate] outbox drain: ${report.delivered.size} held result(s) delivered after reconnect " +
             s"(${report.delivered.mkString(", ")})"
         )
       else IO.unit) *>
        (if report.failed.nonEmpty then
           logger.warn(
             s"[device-delegate] outbox drain: ${report.failed.size} result(s) still undeliverable, kept on disk — " +
               report.failed.map((id, err) => s"$id: $err").mkString("; ")
           )
         else IO.unit)
          .as(report)
    }

  // ── 设备侧：接收装配 + spawn 处理 ─────────────────────────────────────

  /**
   * 收端装配（GatewayMain 启动单点，先例 = DeviceMailInbox.initialize）：注册数据
   * 通道 handler。同一二进制跑在两端——spawn（本设备作为执行设备）与 result/accept
   * （本设备作为编排权威）都在本入口分派。
   */
  def initialize(resources: AgentRuntimePort): Unit =
    resources.neblinkService match
      case None =>
        // P6 诊断探针批（2026-10-14 步2 · 可观测性修复⑥）：两分支原 logger 均为
        // 丢弃 IO（装配面此前零读数——B 侧「handler 到底注册没有」不可判）。
        // 改 Sync 真打。⌚ 步2 判别读数。
        // ⚠ 冲突面：本文件在作者 4 件 WIP 内。
        logger.warnSync("DeviceDelegate.initialize skipped: NebLink is not configured (no device network)")
      case Some(ns) =>
        // P6 步2 根因修复（2026-10-14）：原 `ns.addDataHandler(...)` 返回 IO[Unit]，
        // 在本 **Unit 同步语境被丢弃** ⇒ delegate 收件面**从未注册**（r4 判别读数：
        // dropbox 注册走 NeblinkService.addDataHandler 打出 size-after=1，本注册连
        // 方法体都没进；分发面 handlers=1 恒缺本 handler）。后果 = spawn 帧到达后由
        // dropbox handler 对未知 kind 静默 IO.unit 吞掉 → 隧道回 200 notified 但
        // spawn 零分发 —— 即 M10 断点本体（双腿皆然，presence 直发与 relay 兜底
        // 共用本 handleDataMessage 入口）。
        // 修法 = boot 期真注册：unsafeRunSync（既有先例 = GatewayMain
        // `neblinkConfigAtBoot.unsafeRunSync()`，同处 boot 链、同款阻塞语义）。
        // ⚠ 冲突面：本文件在作者 4 件 WIP 内，合并时将与其 WIP 冲突。
        ns.addDataHandler(payload => handlePayload(resources, payload)).unsafeRunSync()(
          using cats.effect.unsafe.implicits.global
        )
        // 步2 判别读数：实例指纹（identityHashCode）——与分发面「svc=」指纹对照。
        logger.infoSync(
          s"DeviceDelegate receive face armed (channel: delegate-spawn/result/accept) ns=${System.identityHashCode(ns)}"
        )

  /** 数据通道入口分派（dropbox handleDataMessage 同款 kind 判别）。 */
  private[delegate] def handlePayload(resources: AgentRuntimePort, payload: Json): IO[Unit] =
    payload.hcursor.downField("kind").as[String].getOrElse("") match
      case KindSpawn => handleSpawn(resources, payload)
      case KindResult => handleResult(resources, payload)
      case KindAccept => handleAccept(resources, payload)
      case _ => IO.unit

  /**
   * 设备侧 spawn 处理：验配对 → 验 HMAC（ts/nonce/常数时间）→ spawnId 去重 →
   * 本地 spawn（[[nebflow.core.tools.DelegateTool.spawnBackground]] 单点）→
   * 回 delegate-accept。**任何失败都显式留痕**（WARN + 拒绝计数级日志），绝不静默。
   */
  private[delegate] def handleSpawn(resources: AgentRuntimePort, payload: Json): IO[Unit] =
    val hc = payload.hcursor
    val spawnId = hc.downField("spawnId").as[String].getOrElse("")
    val fromDeviceId = hc.downField("fromDeviceId").as[String].getOrElse("")
    val ts = hc.downField("ts").as[Long].toOption.map(_.toString).getOrElse("")
    val nonce = hc.downField("nonce").as[String].getOrElse("")
    val sig = hc.downField("sig").as[String].getOrElse("")
    val projectName = hc.downField("projectName").as[String].getOrElse("")
    val workspace = hc.downField("workspace").as[String].getOrElse("")
    val task = hc.downField("task").as[String].getOrElse("")
    val title = hc.downField("title").as[String].getOrElse("")
    val parentSessionId = hc.downField("parentSessionId").as[String].getOrElse("")
    val rootSessionId = hc.downField("rootSessionId").as[String].getOrElse("")
    val taskId = hc.downField("taskId").as[Option[String]].toOption.flatten
    // P6 诊断探针批（2026-10-14 步2 · 可观测性修复①）：本行原为 `logger.info`——
    // NebflowLogger.info 返回 IO[Unit]，此处位于同步 Unit 语境（语句位置），IO 被
    // 丢弃 ⇒ 「delegate-spawn RECEIVED」从未真正输出（M10 审计登记过的同款坑：
    // 「观测探针 logger.info 返回未运行的 IO 导致探针静默」）。改用 infoSync 真打。
    // ⚠ 冲突面：本文件在作者 4 件 WIP 内，合并时将与其 WIP 冲突。
    logger.infoSync(
      s"[device-delegate] delegate-spawn RECEIVED (from=$fromDeviceId, project=$projectName, spawnId=$spawnId) — verifying pairing/HMAC"
    )

    // 注（步2 复核）：reject 的 IO 是 handleSpawn if/else 的**分支值**，经
    // handleDataMessage 的 handlers.traverse_ 链会被真跑——logger.warn（IO 版）
    // 语义正确，勿改 Sync 变体（类型不符）。
    def reject(reason: String): IO[Unit] =
      logger.warn(
        s"[device-delegate] spawn REJECTED (spawnId=$spawnId from=$fromDeviceId): $reason " +
          "(fail-closed — no local session is created)"
      )

    if spawnId.isEmpty || fromDeviceId.isEmpty || task.isEmpty || workspace.isEmpty then
      reject("malformed payload (missing spawnId/fromDeviceId/task/workspace)")
    else if processedSpawns.putIfAbsent(spawnId, java.lang.Boolean.TRUE) != null then
      logger.info(s"[device-delegate] duplicate spawnId=$spawnId — re-execution suppressed (at-least-once replay)")
    else
      DeviceRegistry.entrySync(fromDeviceId) match
        case None =>
          processedSpawns.remove(spawnId)
          reject(s"device $fromDeviceId is not paired (devices.json) — pairing is required for delegate spawn")
        case Some(entry) if !entry.allowSpawn =>
          processedSpawns.remove(spawnId)
          reject(s"device ${entry.deviceName} has allowSpawn=false in the pairing registry")
        case Some(entry) =>
          val canonical = spawnCanonical(
            fromDeviceId, ts.toLongOption.getOrElse(0L), nonce, spawnId,
            projectName, workspace, task, title, parentSessionId, rootSessionId, taskId
          )
          val verify =
            if !DeviceHmac.tsFresh(ts, System.currentTimeMillis()) then
              Left(s"stale ts ($ts) outside ±${DeviceHmac.TsWindowMs / 1000}s window")
            else if !nonces.firstSeen(fromDeviceId, nonce) then Left("replayed nonce")
            else if !DeviceHmac.verify(entry.sharedKey, canonical, sig) then Left("signature mismatch")
            else Right(())
          verify match
            case Left(reason) =>
              processedSpawns.remove(spawnId)
              reject(s"$reason (deviceId=$fromDeviceId)")
            case Right(()) =>
              logger.info(
                s"[device-delegate] spawn accepted (spawnId=$spawnId from=${entry.deviceName}, " +
                  s"project='$projectName', workspace=$workspace) — creating local delegate session"
              ) *> spawnLocalDelegate(resources, payload).flatMap {
                case Left(err) =>
                  processedSpawns.remove(spawnId)
                  // 注（步2 复核）：此 warn 是 flatMap 回调的**返回值**，链上真跑——
                  // 勿改 Sync 变体（类型不符）。
                  logger.warn(s"[device-delegate] local spawn failed for spawnId=$spawnId: $err")
                case Right(localSessionId) =>
                  // delegate-accept 回执（fire-and-forget：host 侧只作可观测读数）。
                  val accept = Json.obj(
                    "kind" -> KindAccept.asJson,
                    "spawnId" -> spawnId.asJson,
                    "sessionId" -> localSessionId.asJson,
                    "deviceId" -> fromDeviceId.asJson
                  )
                  sendToDevice(resources, fromDeviceId, accept).handleErrorWith(e =>
                    // 注：此处的 logger.warn（IO 版）在 handleErrorWith 链内会真跑，
                    // 勿改 Sync 变体（类型不符且不必要）——步2 复核留痕。
                    logger.warn(s"[device-delegate] accept send failed for spawnId=$spawnId: ${e.getMessage}")
                  ).void
              }
      end match
    end if
  end handleSpawn

  /**
   * 设备侧本地 delegate 会话（spawn 单点 = spawnBackground，Mail 续聊复活腿先例；
   * judgeWorkspace=false = 无 worktree 判定——远端腿不做合并窗语义，声明于报告）。
   * parentRef = 回投代理（[[resultProxyBehavior]]）：终态 ExternalEvent 折成
   * delegate-result 发回 host。
   */
  private[delegate] def spawnLocalDelegate(
    resources: AgentRuntimePort,
    payload: Json
  ): IO[Either[String, String]] =
    val hc = payload.hcursor
    val spawnId = hc.downField("spawnId").as[String].getOrElse("")
    val fromDeviceId = hc.downField("fromDeviceId").as[String].getOrElse("")
    val fromDeviceName = hc.downField("fromDeviceName").as[String].getOrElse("host")
    val projectName = hc.downField("projectName").as[String].getOrElse("")
    val workspace = hc.downField("workspace").as[String].getOrElse("")
    val task = hc.downField("task").as[String].getOrElse("")
    val title = hc.downField("title").as[String].getOrElse("")
    val parentSessionId = hc.downField("parentSessionId").as[String].getOrElse("")
    val rootSessionId = hc.downField("rootSessionId").as[String].getOrElse("")
    val taskId = hc.downField("taskId").as[Option[String]].toOption.flatten

    for
      selfId <- resources.neblinkService.fold(IO.pure(""))(_.identity.map(_.deviceId))
      // 本机台账一条（对端自己的任务面可查；失败不阻断派发——fail-soft 先例）。
      // createSyncReturningId = Either[ToolError, String] ⇒ 双层 Option 折叠。
      localTaskId <- IO.blocking {
        nebflow.core.project.TaskLedgerStore
          .open()
          .createSyncReturningId(
            title = s"$projectName — $title (device-delegate from ${fromDeviceName.take(40)})",
            // 🔴 project 字段随派发面落库（#506 过滤字段错位修复，2026-10-18）——
            // 与 DelegateTool.createLedgerEntry 同批同型；空名 = 缺省座位（None）。
            project = Option(projectName).map(_.trim).filter(_.nonEmpty),
            actor = nebflow.core.project.TaskLedgerHistory.Actors.Nebula
          )
      }.attempt.map(_.toOption.flatMap(_.toOption))
      proxyRef <-
        resources.actorSystem.spawn(
          resultProxyBehavior(
            resources = resources,
            spawnId = spawnId,
            hostDeviceId = fromDeviceId,
            hostDeviceName = fromDeviceName,
            projectName = projectName,
            parentSessionId = parentSessionId,
            selfDeviceId = selfId,
            localTaskId = localTaskId
          ),
          s"device-delegate-result-$spawnId"
        )
      brief =
        s"""[workspace] $workspace (file tools need absolute paths; Bash cwd is not guaranteed)
[device-delegate] dispatched by host '$fromDeviceName' ($fromDeviceId) for project '$projectName'${taskId
            .map(t => s" (host task #$t)")
            .getOrElse("")} — your session id here is the spawn's local record; report scope: this device only.
${nebflow.core.tools.DelegateTool.BriefReportLine}

$task"""
      agentDef <- IO.blocking(
        nebflow.core.entity.BuiltinAgents
          .delegateEntry(nebflow.core.entity.BuiltinAgents.ExecutorName)
          .map(_.toAgentDef)
          .get
      )
      spawned <- nebflow.core.tools.DelegateTool.spawnBackground(
        agentDef = agentDef,
        task = brief,
        description = s"[device:$fromDeviceName] $title",
        workspace = workspace,
        project = Some(projectName),
        taskId = localTaskId,
        system = resources.actorSystem,
        resources = resources,
        parentDepth = 0,
        parentRef = Some(proxyRef),
        wsSend = None,
        parentSessionId = Some(parentSessionId),
        rootSessionId = rootSessionId,
        // origin 标记（验收 #2）：对端 DelegateRegistry 记 delegated-by:<host>。
        delegatedBy = Some(fromDeviceId)
      )
    yield spawned.fold(e => Left(e.message), t => Right(t._1))
    end for
  end spawnLocalDelegate

  /**
   * 回投代理：等监督者的**终态** ExternalEvent（completed/failed/cancelled——
   * BackoffSupervisor.notifyParentAndStop 的既有形态），折成 delegate-result 发回
   * host 后自停。产物指针 = 终态 metadata 里 fileChanges 的 path 集（对端盘语义）
   * ⇒ `pointers: [{device: <本机>, path, changeType}]`（**指针不回传字节**）。
   */
  private[delegate] def resultProxyBehavior(
    resources: AgentRuntimePort,
    spawnId: String,
    hostDeviceId: String,
    hostDeviceName: String,
    projectName: String,
    parentSessionId: String,
    selfDeviceId: String,
    localTaskId: Option[String]
  ): Behavior[AgentCommand] =
    Behaviors.receiveMessage { cmd =>
      cmd match
        case AgentCommand.ExternalEvent(source, eventType, payloadText, metadata, _, _) =>
          val pointers = extractPointers(metadata, selfDeviceId)
          val result = Json.obj(
            "kind" -> KindResult.asJson,
            "spawnId" -> spawnId.asJson,
            "status" -> eventType.asJson,
            "source" -> source.asJson,
            "result" -> payloadText.asJson,
            "deviceName" -> deviceNameOf(resources, selfDeviceId).asJson,
            "deviceId" -> selfDeviceId.asJson,
            "projectName" -> projectName.asJson,
            "parentSessionId" -> parentSessionId.asJson,
            "pointers" -> pointers.asJson
          )
          // 台账 #944：完成件**先落盘再发**（sendDurable）——改前是纯 fire-and-forget，
          // 主机不可达 / 进程死在本行之前 / 隧道不在，结果就无声蒸发（KAI 14:39 重启
          // 那一批 6 件的实证形态）。落盘后即使发送失败，relay 重连或下一次 boot 会
          // 按序补投（at-least-once；宿主侧按 spawnId 判重，不会二次唤醒父会话）。
          sendDurable(resources, hostDeviceId, ChannelName, result, spawnId)
            .flatMap {
              case Right(_) =>
                logger.info(
                  s"[device-delegate] delegate-result sent to host ($hostDeviceName) for spawnId=$spawnId (status=$eventType, pointers=${pointers.length})"
                )
              case Left(err) =>
                logger.warn(
                  s"[device-delegate] delegate-result DELIVERY FAILED for spawnId=$spawnId (host=$hostDeviceId): $err — " +
                    "RESULT KEPT IN THE DEVICE OUTBOX (device-outbox/) and will be replayed on the next relay " +
                    "reconnect / boot; the host dedupes by spawnId so a replay never double-wakes the parent"
                )
            }
            .as(Behaviors.stopped[AgentCommand])
        case _ => IO.pure(Behaviors.receiveMessage(_ => IO.pure(Behaviors.stopped[AgentCommand])))
      end match
    }
  end resultProxyBehavior

  /** 本机设备名（显示用；解析不到退 deviceId）。 */
  private def deviceNameOf(resources: AgentRuntimePort, selfDeviceId: String): String =
    // 同步上下文里不取 IO——用身份文件只读探测（readDeviceIdOnly 同族；名字缺失
    // 退回 deviceId，显示面退化可接受）。
    neblinkDisplayName(selfDeviceId)

  private def neblinkDisplayName(selfDeviceId: String): String =
    try
      val p = nebflow.shared.PathUtil.dataRoot / "device.json"
      if !os.exists(p) then selfDeviceId
      else
        io.circe.parser
          .parse(os.read(p))
          .toOption
          .flatMap(_.hcursor.downField("deviceName").as[String].toOption)
          .filter(_.nonEmpty)
          .getOrElse(selfDeviceId)
    catch case _: Throwable => selfDeviceId

  /**
   * 终态 metadata 的 fileChanges → 指针清单（对端盘语义；`changeType` 随行）。
   * metadata 形状 = BackoffSupervisor.fileChangesMetadata（`{files:[{path,...}]}`）。
   */
  private[delegate] def extractPointers(metadata: JsonObject, selfDeviceId: String): List[Json] =
    metadata("fileChanges").toList.flatMap { fc =>
      fc.hcursor.downField("files").as[List[Json]].toOption.getOrElse(Nil).flatMap { e =>
        e.hcursor.downField("path").as[String].toOption.map { path =>
          Json.obj(
            "device" -> selfDeviceId.asJson,
            "path" -> path.asJson,
            "changeType" -> e.hcursor.downField("changeType").as[String].toOption.fold(Json.Null)(_.asJson)
          )
        }
      }
    }

  // ── host 侧：result / accept 处理 ─────────────────────────────────────

  /**
   * host 侧 result 处理：回投父会话（ExternalEvent，`source="delegate(project:<名>
   * @ <设备名>)"`——与既有 delegate 回投同构）+ 指针登记（metadata.artifactPointers
   * + TaskLedger 注记）+ 任务面终态。父会话不在册（已关闭/重启）⇒ WARN + 台账
   * 注记（**不静默丢**——结果文本留在台账可查）。
   */
  private[delegate] def handleResult(resources: AgentRuntimePort, payload: Json): IO[Unit] =
    val hc = payload.hcursor
    val spawnId = hc.downField("spawnId").as[String].getOrElse("")
    val status = hc.downField("status").as[String].getOrElse("completed")
    val resultText = hc.downField("result").as[String].getOrElse("")
    val deviceName = hc.downField("deviceName").as[String].getOrElse("device")
    val projectName = hc.downField("projectName").as[String].getOrElse("")
    val parentSessionId = hc.downField("parentSessionId").as[String].getOrElse("")
    val pointers = hc.downField("pointers").as[List[Json]].toOption.getOrElse(Nil)
    val dispatchSource = s"delegate(project:$projectName @$deviceName)"
    val terminal = Set("completed", "failed", "cancelled").contains(status)

    logger.info(
      s"[device-delegate] result received (spawnId=$spawnId, project='$projectName' @$deviceName, status=$status, pointers=${pointers.size}) — delivering to parent session $parentSessionId"
    ) *>
      IO.blocking(DelegateRegistry.find(spawnId)).flatMap {
        case Some(rec) =>
          val meta = JsonObject(
            "artifactPointers" -> pointers.asJson,
            "device" -> deviceName.asJson,
            "projectName" -> projectName.asJson,
            "spawnId" -> spawnId.asJson
          )
          // ── 台账 #944：重投幂等闸（发端 outbox 是 at-least-once ⇒ 收端必须判重）──
          // correlationId 恒 = spawnId（跨重启稳定身份），并先查既有
          // DeliveredReceiptLedger（#886 的已投标记面，与 delegate/subtask 回执同一本
          // 台账，不新造第二套）。已投 ⇒ **不再注入父会话**（避免重复唤醒/重复回合），
          // 但台账注记与任务面收尾照跑（幂等）。
          nebflow.core.processor.DeliveredReceiptLedger.load(parentSessionId).flatMap { delivered =>
            val alreadyDelivered = parentSessionId.nonEmpty && delivered.contains(spawnId)
            (if alreadyDelivered then
               logger.info(
                 s"[device-delegate] duplicate delegate-result for spawnId=$spawnId (outbox replay) — parent session " +
                   "already consumed this result, injection suppressed (ledger hit)"
               )
             else IO.unit) *> resources.agentRegistry.get.flatMap { registry =>
              if alreadyDelivered then IO.unit
              else
                registry.get(parentSessionId) match
                  case Some(record) =>
                    record.ref ! AgentCommand.ExternalEvent(
                      source = dispatchSource,
                      eventType = status,
                      payload = resultText,
                      metadata = meta,
                      // 身份 = spawnId ⇒ 父会话侧 DeliveredReceiptLedger 的判重键
                      // （AgentIdle/AgentProcessing 注入即 markDelivered）。
                      correlationId = Some(spawnId)
                    )
                  case None =>
                    logger.warn(
                      s"[device-delegate] parent session '$parentSessionId' is gone — result of spawnId=$spawnId kept in the task ledger only (no live injection)"
                    )
            }
          } *>
            IO.blocking {
              // 任务面终态 + 台账注记（指针登记的持久读数）。
              resources.subAgentTaskStore
                .updateStatus(
                  parentSessionId,
                  spawnId,
                  if terminal then status else "completed",
                  completedAt = Some(System.currentTimeMillis())
                )
                .handleErrorWith(_ => IO.unit)
              DelegateRegistry.find(spawnId).foreach(rec =>
                DelegateRegistry.put(rec.copy(reportStatus = Some(if terminal then status else "completed")))
              )
              rec.taskId.foreach { tid =>
                nebflow.core.project.TaskLedgerStore
                  .open()
                  .appendNoteSync(
                    tid,
                    s"Device delegate finished on $deviceName (status=$status); artifact pointers: " +
                      pointers
                        .map(p =>
                          s"${p.hcursor.downField("device").as[String].getOrElse("?")}:${p.hcursor.downField("path").as[String].getOrElse("?")}"
                        )
                        .mkString(", "),
                    from = nebflow.core.project.TaskLedgerHistory.Origins.Nebula,
                    actor = nebflow.core.project.TaskLedgerHistory.Actors.Nebula
                  )
              }
            }.handleErrorWith(e => logger.warn(s"[device-delegate] ledger/task updates failed for $spawnId: ${e.getMessage}"))
        case None =>
          logger.warn(
            s"[device-delegate] result for unknown spawnId=$spawnId (no dispatch record on this host) — dropped with this log line (not silent: grep device-delegate)"
          )
      }
  end handleResult

  /** host 侧 accept 处理：对端本地会话 id 落台账注记（可观测读数，非阻塞语义）。 */
  private[delegate] def handleAccept(resources: AgentRuntimePort, payload: Json): IO[Unit] =
    val hc = payload.hcursor
    val spawnId = hc.downField("spawnId").as[String].getOrElse("")
    val localSessionId = hc.downField("sessionId").as[String].getOrElse("")
    if spawnId.isEmpty then IO.unit
    else
      logger.info(s"[device-delegate] accept received (spawnId=$spawnId, remote session=$localSessionId)") *>
        IO.blocking(DelegateRegistry.find(spawnId)).flatMap {
          case Some(rec) =>
            IO.blocking(
              rec.taskId.foreach { tid =>
                nebflow.core.project.TaskLedgerStore
                  .open()
                  .appendNoteSync(
                    tid,
                    s"Device session started on the origin device: $localSessionId (spawnId=$spawnId)",
                    from = nebflow.core.project.TaskLedgerHistory.Origins.Nebula,
                    actor = nebflow.core.project.TaskLedgerHistory.Actors.Nebula
                  )
              }
            ).handleErrorWith(_ => IO.unit)
          case None => IO.unit
        }
  end handleAccept

end DeviceDelegate
