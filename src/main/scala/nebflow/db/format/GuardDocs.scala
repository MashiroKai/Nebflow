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
    source: String, // 如 "dispatch-notify:failed" / "dispatch-notify:cancelled"
    failedAt: List[Long], // 滚动窗口时间戳（现状 2 字段之一）
    cooldownUntil: Long, // 冷却截止 epoch ms（现状 2 字段之二）
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
      yield NotifyGuardDoc(
        version,
        source,
        rawFailedAt match
          case Some(xs) => xs
          case None => Nil,
        rawCooldown match
          case Some(v) => v
          case None => 0L,
        updatedAt
      )

  end NotifyGuardDoc

  final case class AvoidGuardDoc(
    version: Int,
    source: String, // "provider-avoid"
    avoidUntil: Map[String, Long], // key → epoch ms（现状 avoidUntilRef 形）
    updatedAt: Long,
    /**
     * 熔断（Down）面：key → downSince epoch ms（现状 statesRef 的 Down 子集——
     * 轮 B 增量：任务面「provider 故障/熔断/避坑窗口」双面落盘；D-10 原文只列软避窗，
     * 设计件 §1.3 位点 15「软避/健康窗」同病面。reason 为诊断字段不入档（重水合
     * 时以固定口径回填）；旧档无此字段 ⇒ 空 map（Option 兜底，向后兼容）。
     */
    down: Map[String, Long] = Map.empty
  )

  object AvoidGuardDoc:

    given Encoder[AvoidGuardDoc] = d =>
      Json.obj(
        "version" -> Json.fromInt(d.version),
        "source" -> Json.fromString(d.source),
        "avoidUntil" -> d.avoidUntil.asJson,
        "updatedAt" -> Json.fromLong(d.updatedAt),
        "down" -> d.down.asJson
      )

    given Decoder[AvoidGuardDoc] = c =>
      for
        version <- c.downField("version").as[Int]
        source <- c.downField("source").as[String]
        rawAvoid <- c.downField("avoidUntil").as[Option[Map[String, Long]]]
        updatedAt <- c.downField("updatedAt").as[Long]
        rawDown <- c.downField("down").as[Option[Map[String, Long]]]
      yield AvoidGuardDoc(
        version,
        source,
        rawAvoid match
          case Some(m) => m
          case None => Map.empty,
        updatedAt,
        rawDown match
          case Some(m) => m
          case None => Map.empty
      )
  end AvoidGuardDoc

end GuardDocs
