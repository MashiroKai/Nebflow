package nebflow.db.format

import io.circe.{Decoder, Encoder, Json}
import io.circe.syntax.*

/**
 * 冷却/软避窗小档（设计 §3.4(f)，案 B 类⑥，D-10 采纳）：`guard/` 下锁内 RMW 整档
 * 五步写（低频，无需 journal）。
 *
 * - [[NotifyGuardDoc]]：DispatchNotify 失败/取消窗口态（现状 `FailedGuardState/
 *   CancelledGuardState(failedAt, cooldownUntil)` 逐字段——纯内存丢失面）。
 * - [[AvoidGuardDoc]]：provider 软避窗（现状 `HealthMonitor.avoidUntilRef` 的
 *   key→epoch ms——纯内存丢失面）。
 *
 * 恢复语义（§3.11 步 5）：坏 ⇒ 保守向（视为「全部仍在冷却」，宁可少发不可风暴）。
 */
object GuardDocs:

  final case class NotifyGuardDoc(
    version: Int,
    source: String,        // 如 "dispatch-notify:failed" / "dispatch-notify:cancelled"
    failedAt: List[Long],  // 滚动窗口时间戳（现状 2 字段之一）
    cooldownUntil: Long,   // 冷却截止 epoch ms（现状 2 字段之二）
    updatedAt: Long
  )

  object NotifyGuardDoc:
    given Encoder[NotifyGuardDoc] = d =>
      Json.obj(
        "version" -> Json.fromInt(d.version),
        "source" -> Json.fromString(d.source),
        "failedAt" -> d.failedAt.asJson,
        "cooldownUntil" -> Json.fromLong(d.cooldownUntil),
        "updatedAt" -> Json.fromLong(d.updatedAt)
      )
    given Decoder[NotifyGuardDoc] = c =>
      for
        version <- c.downField("version").as[Int]
        source <- c.downField("source").as[String]
        rawFailedAt <- c.downField("failedAt").as[Option[List[Long]]]
        rawCooldown <- c.downField("cooldownUntil").as[Option[Long]]
        updatedAt <- c.downField("updatedAt").as[Long]
      yield NotifyGuardDoc(version, source,
        rawFailedAt match
          case Some(xs) => xs
          case None => Nil,
        rawCooldown match
          case Some(v) => v
          case None => 0L,
        updatedAt)

  final case class AvoidGuardDoc(
    version: Int,
    source: String, // "provider-avoid"
    avoidUntil: Map[String, Long], // key → epoch ms（现状 avoidUntilRef 形）
    updatedAt: Long
  )

  object AvoidGuardDoc:
    given Encoder[AvoidGuardDoc] = d =>
      Json.obj(
        "version" -> Json.fromInt(d.version),
        "source" -> Json.fromString(d.source),
        "avoidUntil" -> d.avoidUntil.asJson,
        "updatedAt" -> Json.fromLong(d.updatedAt)
      )
    given Decoder[AvoidGuardDoc] = c =>
      for
        version <- c.downField("version").as[Int]
        source <- c.downField("source").as[String]
        rawAvoid <- c.downField("avoidUntil").as[Option[Map[String, Long]]]
        updatedAt <- c.downField("updatedAt").as[Long]
      yield AvoidGuardDoc(version, source,
        rawAvoid match
          case Some(m) => m
          case None => Map.empty,
        updatedAt)

end GuardDocs
