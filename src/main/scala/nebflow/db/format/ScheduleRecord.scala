package nebflow.db.format

import io.circe.{Decoder, Encoder, Json}
import io.circe.syntax.*

/**
 * 调度任务记录（设计 §3.4(d)，案 B 类④）：envelope 4 字段〔提案〕，
 * `taskDef` = 现状 `ScheduledTask` 载荷原样（opaque Json，须携带 `id`——现状面恒有）。
 *
 * `tombstone` 恒写出（未删 = null，对拍样例形）；逻辑删除 = 追加 tombstone 行
 * （taskDef 退化为 `{"id":…}` 桩），复活 = 重新追加完整 taskDef 行——
 * 折叠语义 = 同 id 最后一行定生死，原文永不动（V10）。
 */
final case class ScheduleRecord(
  seq: Long,
  sessionId: String,
  taskDef: Json,
  createdAt: Long,
  tombstone: Option[Long]
)

object ScheduleRecord:

  /** 从行内取任务 id（taskDef.id；写面已 require 在场）。 */
  def taskIdOf(rec: ScheduleRecord): Option[String] =
    rec.taskDef.hcursor.downField("id").as[String].toOption

  /** tombstone 行构造（taskDef = id 桩）。 */
  def tombstoneRow(seq: Long, sessionId: String, taskId: String, now: Long): ScheduleRecord =
    ScheduleRecord(seq, sessionId, Json.obj("id" -> Json.fromString(taskId)), now, Some(now))

  /**
   * 逻辑视图折叠（纯函数）：同 id 取**最后一行**——tombstone=Some ⇒ 死；否则活
   * （活的带最后写入的 taskDef = 天然 upsert 复活语义）。返回 (活任务, 已 tombstone 数)。
   */
  def foldLive(rows: List[ScheduleRecord]): (List[ScheduleRecord], Int) =
    val lastById =
      rows.foldLeft(Map.empty[String, ScheduleRecord]) { (acc, r) =>
        taskIdOf(r).map(id => acc.updated(id, r)).getOrElse(acc)
      }
    val all = lastById.values.toList.sortBy(_.seq)
    val live = all.filterNot(_.tombstone.isDefined)
    (live, all.size - live.size)

  given Encoder[ScheduleRecord] = r =>
    Json.obj(
      "seq" -> Json.fromLong(r.seq),
      "sessionId" -> Json.fromString(r.sessionId),
      "taskDef" -> r.taskDef,
      "createdAt" -> Json.fromLong(r.createdAt),
      "tombstone" -> r.tombstone.asJson // None → null（设计 §3.4(d) 样例形）
    )

  given Decoder[ScheduleRecord] = c =>
    for
      seq <- c.downField("seq").as[Long]
      sessionId <- c.downField("sessionId").as[String]
      taskDef <- c.downField("taskDef").as[Json]
      createdAt <- c.downField("createdAt").as[Long]
      tombstone <- c.downField("tombstone").as[Option[Long]]
    yield ScheduleRecord(seq, sessionId, taskDef, createdAt, tombstone)

end ScheduleRecord
