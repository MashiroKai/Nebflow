package nebflow.neblink

import cats.effect.IO
import cats.effect.kernel.Ref
import io.circe.syntax.*
import io.circe.Json
import nebflow.core.NebflowLogger
import nebflow.core.tools.RelayExecAudit

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
 *      未命中且 eventId 属 `message-` 族 ⇒ WARN + 审计行（`ack-unmatched`：迟到 ack
 *      或非本次发送的 ack），**禁静默**。
 *   ② **超时路径**：登记时挂一条超时腿（`AckTimeout`，默认 15 s）；到点仍在 pending
 *      ⇒ WARN + 审计行（`ack-timeout`）。服务端腿未落地时（本批为外部依赖）该行就是
 *      「发送成功但回执未到」的唯一可见读数。
 *
 * 无响应 id（`Right("")`）⇒ 登记 `@unkeyed` 占位：**不伪造 id**，超时行里写明
 * 「ack 无法关联（响应没带 id）」——同样可见。
 */
object DeviceMailAck:

  private val logger = NebflowLogger.forName("nebflow.neblink.devicemail.ack")

  /** ack 等待窗（超时腿长度）。 */
  val AckTimeout: FiniteDuration = 15.seconds

  /** 无法关联的占位键（响应未带 id）。 */
  val Unkeyed: String = "@unkeyed"

  /** 一条待回执登记。 */
  final case class Pending(targetDeviceId: String, eventId: String, registeredAt: Long)

  private val pending: Ref[IO, Map[String, Pending]] = Ref.unsafe[IO, Map[String, Pending]](Map.empty)

  /** 当前 pending 数（读数/测试用）。 */
  def pendingCount: IO[Int] = pending.get.map(_.size)

  /** 登记一条待回执 + 挂超时腿。`innerId` = 服务端 id（空串 ⇒ `Unkeyed` 占位）。 */
  def await(targetDeviceId: String, innerId: String): IO[String] =
    val eventId = DeviceMail.withMessagePrefix(if innerId.trim.isEmpty then Unkeyed else innerId)
    for
      _ <- pending.update(_ + (eventId -> Pending(targetDeviceId, eventId, System.currentTimeMillis())))
      _ <- timeoutLeg(eventId, targetDeviceId).start.void
    yield eventId

  private def timeoutLeg(eventId: String, targetDeviceId: String): IO[Unit] =
    IO.sleep(AckTimeout) *> pending.get.flatMap { m =>
      m.get(eventId) match
        case None => IO.unit
        case Some(p) =>
          pending.update(_ - eventId) *>
            logger.warn(
              s"[device-mail] ack TIMEOUT after ${AckTimeout.toSeconds}s — eventId=$eventId " +
                s"target=$targetDeviceId (delivery receipt not seen; the send itself is NOT retried)"
            ) *>
            audit("ack-timeout", targetDeviceId, eventId, s"waited=${AckTimeout.toSeconds}s")
    }

  /** 隧道 `type=="ack"` 帧入口。命中 pending ⇒ 出队 + INFO + 审计；未命中 ⇒
    * `message-` 族 WARN + 审计（迟到/陌生 ack），其它族 DEBUG（非本批 ack）。 */
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
                audit("ack-received", p.targetDeviceId, eventId, s"elapsed_ms=${System.currentTimeMillis() - p.registeredAt}")
            case None =>
              if eventId.startsWith(DeviceMail.AckEventIdPrefix) then
                logger.warn(
                  s"[device-mail] ack UNMATCHED — eventId=$eventId has no pending send in this session " +
                    "(late ack beyond the wait window, or an ack for another device's send)"
                ) *> audit("ack-unmatched", "-", eventId, "no pending send")
              else logger.debug(s"[device-mail] ack ignored (non message- eventId): $eventId")
        }

  /** 测试用重置（清 pending，不影响已挂的超时腿——它们到点发现无登记即 no-op）。 */
  private[nebflow] def resetForTest(): IO[Unit] = pending.set(Map.empty)

  private def audit(status: String, targetDeviceId: String, eventId: String, detail: String): IO[Unit] =
    RelayExecAudit.record(
      sourceDeviceId = "device-mail",
      targetDeviceId = targetDeviceId,
      via = "relay",
      action = s"DeviceMail.$status",
      command = s"eventId=$eventId; $detail",
      projectRoot = "",
      cwd = Option(System.getProperty("user.dir")).getOrElse("")
    ).handleErrorWith(e => logger.warn(s"[device-mail] audit line dropped: ${e.getMessage}"))

end DeviceMailAck
