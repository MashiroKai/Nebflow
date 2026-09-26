package nebflow.neblink

import cats.effect.IO
import cats.effect.kernel.Ref
import io.circe.Json
import io.circe.syntax.*
import nebflow.core.tools.RelayExecAudit
import nebflow.shared.NebflowLogger

import scala.concurrent.duration.*

/**
 * 跨设备 Nebula 邮件的 **ack 关联与超时腿**（device-mail 批，2026-09-15；契约 v2 ④）。
 *
 * 契约（逐字，冻结）：`{"type":"ack","eventId":"message-<id>"}`——**既有** ack 机制
 * 复用，本批**不新建** ack 形状、不改其语义。本对象只承担两件在发送侧可见的事：
 *
 *   ① **eventId 关联**：POST `/api/relay/{target}/mail` 响应里的 id（键名未冻结 ⇒
 *      宽容读取，见 `NeblinkClient.relayAgentMail`）登记为 pending；隧道收到
 *      `type=="ack"` 帧 ⇒ 按 `eventId` 命中 → INFO 日志 + 审计行（`ack-received`）。
 *      未命中（`message-` 族）⇒ 记入「抢先 ack」缓冲（**禁静默**：到期未认领则
 *      `ack-unmatched`）——见下方「时序」节。
 *   ② **超时路径**：登记时挂一条超时腿（`AckTimeout`，默认 15 s）；到点仍在 pending
 *      ⇒ WARN + 审计行（`ack-timeout`）。服务端腿未落地时（本批为外部依赖）该行就是
 *      「发送成功但回执未到」的唯一可见读数。
 *
 * 无响应 id（`Right("")`）⇒ 登记 `@unkeyed` 占位：**不伪造 id**，超时行里写明
 * 「ack 无法关联（响应没带 id）」——同样可见。
 *
 * ==时序（ackfix 批，2026-09-17；卡 #678）==
 *
 * 登记点在 `relayAgentMail` 的 **HTTP 响应返回之后**，而对端 ack 常在响应之前就抵达隧道
 * ⇒ 「**ack 先到、登记后到**」是常态而非异常。改前该时序下 ack 未命中即被丢弃（无缓冲、
 * 无回填），而已挂的超时腿必然在 15 s 后打出假 `TIMEOUT … delivery receipt not seen`。
 * 三条机制共同保证该时序下**不再产生假失败**：
 *
 *   ① **抢先 ack 缓冲**（`earlyAcks`，**有界**：容量 [[EarlyAckCapacity]]、存活窗
 *      [[EarlyAckTtl]]，溢出丢最旧并留审计）：ack 未命中登记表 ⇒ 先记入缓冲
 *      （INFO + 审计 `ack-buffered`）；
 *   ② **登记时先查缓冲**：命中 ⇒ 立即出队、**不挂超时腿**（INFO + 审计 `ack-race-resolved`）；
 *   ③ **超时腿到点再查缓冲**：命中 ⇒ 降为 INFO（审计 `ack-race-late`），🔴 不再打
 *      `delivery receipt not seen`（该腿只覆盖 ② 与 ack 到达之间的残余交错窗）。
 *
 * **措辞准确性**：`ack UNMATCHED` 原先自称 "in this session"，但登记表/缓冲都是**进程内
 * 全局单例**（跨会话、跨 agent 共用，无 per-session 表）⇒ 措辞已改为准确描述
 * （`in this process`）。同时该告警只在「**缓冲到期仍未被任何登记认领**」时发出
 * （真·陌生/迟到 ack），不再为「抢先 ack」误发——抢先前置由 ①-③ 吸收。
 */
object DeviceMailAck:

  private val logger = NebflowLogger.forName("nebflow.neblink.devicemail.ack")

  /** ack 等待窗（超时腿长度）。 */
  val AckTimeout: FiniteDuration = 15.seconds

  /** 无法关联的占位键（响应未带 id）。 */
  val Unkeyed: String = "@unkeyed"

  /** 「抢先 ack」缓冲的**容量上界**（条）——有界内存：超出即丢最旧并留审计行。 */
  val EarlyAckCapacity: Int = 64

  /**
   * 「抢先 ack」缓冲的**存活窗** = 2× 等待窗——覆盖「POST 往返（客户端上限 10 s）
   * + 超时腿到点」全窗；到期仍未被认领的条目在下次触碰缓冲时留下 `ack-unmatched`。
   */
  val EarlyAckTtl: FiniteDuration = AckTimeout * 2

  /** 一条待回执登记。 */
  final case class Pending(targetDeviceId: String, eventId: String, registeredAt: Long)

  private val pending: Ref[IO, Map[String, Pending]] = Ref.unsafe[IO, Map[String, Pending]](Map.empty)

  /**
   * 「抢先 ack」缓冲（新 → 旧）：ack 先于登记抵达时暂存，登记到场时用于关联。
   * **有界**：容量 [[EarlyAckCapacity]]、TTL [[EarlyAckTtl]]，两者都在写入/读取时裁剪。
   */
  private val earlyAcks: Ref[IO, List[(String, Long)]] = Ref.unsafe[IO, List[(String, Long)]](Nil)

  /** 当前 pending 数（读数/测试用）。 */
  def pendingCount: IO[Int] = pending.get.map(_.size)

  /** 当前「抢先 ack」缓冲条数（读数/测试用；恒 ≤ [[EarlyAckCapacity]]）。 */
  def earlyAckCount: IO[Int] = earlyAcks.get.map(_.size)

  /** 登记一条待回执 + 挂超时腿。`innerId` = 服务端 id（空串 ⇒ `Unkeyed` 占位）。 */
  def await(targetDeviceId: String, innerId: String): IO[String] =
    val eventId = DeviceMail.withMessagePrefix(if innerId.trim.isEmpty then Unkeyed else innerId)
    for
      _ <- pending.update(_ + (eventId -> Pending(targetDeviceId, eventId, System.currentTimeMillis())))
      // ② 入表后**先查「抢先 ack」缓冲**：回执在登记之前就已抵达 ⇒ 立刻闭环关联
      // （命中 ⇒ 出队 + **不挂超时腿**；否则该腿必在 15 s 后打出假 TIMEOUT）。
      race <- takeEarly(eventId)
      _ <-
        if race then
          pending.update(_ - eventId) *>
            logger.info(
              s"[device-mail] ack received — eventId=$eventId target=$targetDeviceId " +
                "(receipt arrived before this send's registration; matched from the early-ack buffer, " +
                "no timeout leg armed)"
            ) *> audit("ack-race-resolved", targetDeviceId, eventId, "receipt arrived before registration")
        else timeoutLeg(eventId, targetDeviceId).start.void
    yield eventId

    end for

  end await

  private def timeoutLeg(eventId: String, targetDeviceId: String): IO[Unit] =
    IO.sleep(AckTimeout) *> pending.get.flatMap { m =>
      m.get(eventId) match
        case None => IO.unit
        case Some(p) =>
          // ③ 到点**再查「抢先 ack」缓冲**：命中 ⇒ 回执确已抵达、只是关联晚到 ⇒ 降为
          // INFO（🔴 禁再打 `delivery receipt not seen`——那是假陈述）。
          takeEarly(eventId).flatMap {
            case true =>
              pending.update(_ - eventId) *>
                logger.info(
                  s"[device-mail] ack matched late — eventId=$eventId target=${p.targetDeviceId} " +
                    s"(receipt had already arrived, but only entered the early-ack buffer after this " +
                    s"send's registration check had run; the ${AckTimeout.toSeconds}s wait window is NOT " +
                    "a delivery verdict)"
                ) *> audit("ack-race-late", p.targetDeviceId, eventId, "receipt seen; association late")
            case false =>
              pending.update(_ - eventId) *>
                logger.warn(
                  s"[device-mail] ack TIMEOUT after ${AckTimeout.toSeconds}s — eventId=$eventId " +
                    s"target=$targetDeviceId (delivery receipt not seen; the send itself is NOT retried)"
                ) *>
                audit("ack-timeout", targetDeviceId, eventId, s"waited=${AckTimeout.toSeconds}s")
          }
    }

  /**
   * 隧道 `type=="ack"` 帧入口。命中 pending ⇒ 出队 + INFO + 审计；未命中 ⇒ 记入
   * 「抢先 ack」缓冲（INFO + 审计 `ack-buffered`，到期未认领才 `ack-unmatched`），
   * 其它族 DEBUG（非本批 ack）。
   */
  def handle(frame: Json): IO[Unit] =
    DeviceMail.ackEventId(frame) match
      case None =>
        logger.debug(s"[device-mail] ack frame without a usable eventId (ignored): ${frame.noSpaces.take(120)}")
      case Some(eventId) =>
        pending.get.flatMap { m =>
          m.get(eventId) match
            case Some(p) =>
              pending.update(_ - eventId) *>
                logger.info(
                  s"[device-mail] ack received — eventId=$eventId target=${p.targetDeviceId} " +
                    s"elapsed_ms=${System.currentTimeMillis() - p.registeredAt}"
                ) *>
                audit(
                  "ack-received",
                  p.targetDeviceId,
                  eventId,
                  s"elapsed_ms=${System.currentTimeMillis() - p.registeredAt}"
                )
            case None =>
              if eventId.startsWith(DeviceMail.AckEventIdPrefix) then
                // ① 未命中 ⇒ **先记入有界「抢先 ack」缓冲**。此处**不打 WARN**：ack 抢在
                // 登记之前抵达是本设计的常态，而此刻诊断上无法区分「抢先」与「陌生」——
                // 打 WARN 就是改前那对假警告的来源。可见性不减（禁静默）：缓冲区内的
                // 每一条都在到期时逐条留 `ack-unmatched`，溢出另留 `ack-buffer-overflow`。
                // `IO.cede`：让出 compute 线程后再进入该分支的阻塞式审计写盘（并把
                // 「登记可插入」的交错窗显式化，供 ③ 覆盖）。
                IO.cede *> remember(eventId) *>
                  logger.info(
                    s"[device-mail] ack buffered — eventId=$eventId arrived with no pending send in " +
                      s"this process (a send for it may still be registering: its HTTP response has not " +
                      s"returned yet); held ${EarlyAckTtl.toSeconds}s for the registration, no verdict yet"
                  ) *> audit("ack-buffered", "-", eventId, s"no pending send yet; held ${EarlyAckTtl.toSeconds}s")
              else logger.debug(s"[device-mail] ack ignored (non message- eventId): $eventId")
        }

  // ============================================================
  // 「抢先 ack」缓冲：有界 + TTL，两处裁剪（写入 / 读取）
  // ============================================================

  /**
   * 记入缓冲：TTL 裁剪 + 容量上界（超出丢最旧）。被裁掉的**未认领**条目逐条
   * `ack-unmatched`（禁静默）；溢出另记一条 `ack-buffer-overflow`（只报条数，不刷屏）。
   */
  private def remember(eventId: String): IO[Unit] =
    val now = System.currentTimeMillis()
    earlyAcks
      .modify { xs =>
        val (expired, live) = ((eventId -> now) :: xs).partition { case (_, ts) => now - ts > EarlyAckTtl.toMillis }
        val deduped = live.distinctBy(_._1)
        val kept = deduped.take(EarlyAckCapacity)
        (kept, (expired.map(_._1), deduped.size - kept.size))
      }
      .flatMap { case (expired, overflow) =>
        expired.foldLeft(IO.unit)((acc, id) => acc *> unmatchedWarn(id)) *>
          (if overflow > 0 then
             logger.warn(
               s"[device-mail] early-ack buffer overflow — $overflow oldest entr" +
                 s"${if overflow == 1 then "y" else "ies"} dropped (capacity=$EarlyAckCapacity); " +
                 "the dropped acks are NOT re-associated"
             ) *> audit("ack-buffer-overflow", "-", eventId, s"dropped=$overflow capacity=$EarlyAckCapacity")
           else IO.unit)
      }

  end remember

  /** 从缓冲取走一条（命中 ⇒ 消费掉，避免重复匹配）；顺带做同样的 TTL 裁剪。 */
  private def takeEarly(eventId: String): IO[Boolean] =
    val now = System.currentTimeMillis()
    earlyAcks
      .modify { xs =>
        val (expired, live) = xs.partition { case (_, ts) => now - ts > EarlyAckTtl.toMillis }
        (live.filterNot(_._1 == eventId), (live.exists(_._1 == eventId), expired.map(_._1)))
      }
      .flatMap { case (hit, expired) =>
        expired.foldLeft(IO.unit)((acc, id) => acc *> unmatchedWarn(id)) *> IO.pure(hit)
      }

  /**
   * 缓冲条目**到期仍未被任何登记认领** ⇒ 可见告警（禁静默）。措辞与事实一致：
   * 登记表与缓冲区都是**进程内全局**单例（跨会话/跨 agent 共用），不是 "this session"。
   */
  private def unmatchedWarn(eventId: String): IO[Unit] =
    logger.warn(
      s"[device-mail] ack UNMATCHED — eventId=$eventId has no pending send in this process " +
        s"(held in the early-ack buffer for ${EarlyAckTtl.toSeconds}s and never claimed by any " +
        "registration: an ack for another device's or another leg's send, or a receipt whose send " +
        "was never registered)"
    ) *> audit("ack-unmatched", "-", eventId, s"no pending send after ${EarlyAckTtl.toSeconds}s held")

  /** 测试用重置（清 pending + 「抢先 ack」缓冲，不影响已挂的超时腿——它们到点发现无登记即 no-op）。 */
  private[nebflow] def resetForTest(): IO[Unit] = pending.set(Map.empty) *> earlyAcks.set(Nil)

  private def audit(status: String, targetDeviceId: String, eventId: String, detail: String): IO[Unit] =
    RelayExecAudit
      .record(
        sourceDeviceId = "device-mail",
        targetDeviceId = targetDeviceId,
        via = "relay",
        action = s"DeviceMail.$status",
        command = s"eventId=$eventId; $detail",
        projectRoot = "",
        cwd = Option(System.getProperty("user.dir")).getOrElse("")
      )
      .handleErrorWith(e => logger.warn(s"[device-mail] audit line dropped: ${e.getMessage}"))

end DeviceMailAck
