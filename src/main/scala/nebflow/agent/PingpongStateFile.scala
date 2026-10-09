/* 乒乓压缩影子状态旁车文件（主会话无感上下文压缩一期，2026-10-09 spec §F2/§E-7/8）。
 * 路径 = `<dataRoot>/sessions/<id>/pingpong/state.json`（目录式旁车，旧二进制不
 * 识别即忽略，spec §G3 只增不改）。唯一写者 = 主 actor 的消息处理 IO 链（§R4）；
 * 影子 fiber 零写。同时是 §E 重启恢复（#7 作废重触发 / #8 S 续用换入）的依据。 */
package nebflow.agent

import cats.effect.IO
import cats.syntax.all.*
import io.circe.syntax.*
import io.circe.{Decoder, Encoder, Json}
import nebflow.core.AtomicJson
import nebflow.shared.*

private[agent] object PingpongStateFile:

  private val logger = NebflowLogger.forName("nebflow.agent.lifecycle")

  /** 影子相位（spec §F2）：compacting = 影子在途；s-ready = S 已产出未换入。 */
  sealed trait Phase
  case object Compacting extends Phase
  case object SReady extends Phase

  /**
   * @param sText 仅 phase=s-ready 有值——S 摘要消息的文本本体。S 是「落盘即活」的
   *             纯数据（§E 取舍论证），重启恢复据此**续用换入**，不重算。
   */
  case class Record(
    phase: Phase,
    shadowId: String,
    forkMsgCount: Int,
    forkedAt: Long,
    sText: Option[String],
    sTokensEst: Int,
    ppFailures: Int
  )

  given Encoder[Phase] = Encoder.encodeString.contramap {
    case Compacting => "compacting"
    case SReady     => "s-ready"
  }

  given Encoder[Record] = Encoder.instance { r =>
    Json.obj(
      "phase" -> r.phase.asJson,
      "shadowId" -> r.shadowId.asJson,
      "forkMsgCount" -> r.forkMsgCount.asJson,
      "forkedAt" -> r.forkedAt.asJson,
      "sText" -> r.sText.asJson,
      "sTokensEst" -> r.sTokensEst.asJson,
      "ppFailures" -> r.ppFailures.asJson
    )
  }

  given Decoder[Phase] = Decoder.decodeString.emap {
    case "compacting" => Right(Compacting)
    case "s-ready"    => Right(SReady)
    case other        => Left(s"unknown pingpong phase: $other")
  }

  given Decoder[Record] = Decoder.instance { c =>
    for
      phase   <- c.downField("phase").as[Phase]
      sid     <- c.downField("shadowId").as[String]
      fmc     <- c.downField("forkMsgCount").as[Int]
      forkedAt <- c.downField("forkedAt").as[Long]
      sText   <- c.downField("sText").as[Option[String]]
      est     <- c.downField("sTokensEst").as[Option[Int]]
      fails   <- c.downField("ppFailures").as[Option[Int]]
    yield Record(phase, sid, fmc, forkedAt, sText, est.getOrElse(0), fails.getOrElse(0))
  }

  private def file(sessionId: String): os.Path =
    PathUtil.dataRoot / "sessions" / sessionId / "pingpong" / "state.json"

  /** Persist the record. Failure is logged, never propagated — a sidecar hiccup
    * must not block the fork/swap leg (the in-memory bits remain authoritative). */
  def save(sessionId: String, rec: Record): IO[Unit] =
    if sessionId.isEmpty then IO.unit
    else
      val f = file(sessionId)
      IO.blocking {
        if !os.exists(f / os.up) then os.makeDir.all(f / os.up)
        AtomicJson.writeSync(f, rec.asJson.noSpaces)
      }.void.handleErrorWith(e =>
        logger.warn(s"PingpongStateFile.save failed for $sessionId: ${e.getMessage}").void
      )

  /** Load the record. None if missing or corrupt (corrupt → warn + None ⇒ §E-9
    * 正常启动语义：阈值判定自然再触发）。 */
  def load(sessionId: String): IO[Option[Record]] =
    if sessionId.isEmpty then IO.pure(None)
    else
      IO.blocking {
        val f = file(sessionId)
        if !os.exists(f) then None
        else io.circe.parser.decode[Record](os.read(f)).toOption
      }.handleErrorWith(e =>
        logger.warn(s"PingpongStateFile.load failed for $sessionId: ${e.getMessage}") *> IO.pure(None)
      )

  /** Remove the sidecar (swap landed / failure / reset / recovery-consume). */
  def clear(sessionId: String): IO[Unit] =
    if sessionId.isEmpty then IO.unit
    else
      IO.blocking {
        val f = file(sessionId)
        if os.exists(f) then os.remove(f)
      }.void.handleErrorWith(e =>
        logger.warn(s"PingpongStateFile.clear failed for $sessionId: ${e.getMessage}").void
      )

  /** Build the s-ready record's S message from the persisted sText (§E-8 续用). */
  def sMessageOf(rec: Record): Option[Message] =
    rec.sText.filter(_.trim.nonEmpty).map(text => Message(MessageRole.User, Left(text)))

end PingpongStateFile
