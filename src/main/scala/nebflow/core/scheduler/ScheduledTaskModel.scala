package nebflow.core.scheduler

import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec

import java.util.UUID

/** A scheduled task attached to a session — fires once or on a recurring schedule. */
case class ScheduledTask(
  id: String,
  sessionId: String,
  content: String,
  triggerAt: Long,
  createdAt: Long,
  triggered: Boolean = false,
  triggeredAt: Option[Long] = None,
  /** Optional file path for the LLM to reference when the task fires. */
  referencePath: Option[String] = None,
  /** Recurrence pattern: "hourly", "daily", "weekly", or None for one-shot. */
  repeat: Option[String] = None,
  /** When false, the task is skipped by fireDueTasks but kept in storage. */
  enabled: Boolean = true,
  /** Stable upsert key（2026-09-06 定时任务升级，事故根因修复）：同 name 再建
    * = 替换不叠加（ScheduledTaskStore.upsertTaskByName），重启后 re-arm 例行
    * 任务自动去重。None = 匿名任务，行为同旧版（永不参与去重）。匹配为全库
    * 跨会话精确等值。 */
  name: Option[String] = None
)

object ScheduledTask:

  // deriveCodec 对存量文件向后兼容已实证（2026-09-06 变异验红）：Scala 3 泛型
  // 派生对缺失键采用构造器默认值——升级前的任务文件没有 name 键 → 解码为 None；
  // 显式 "name":null 同样归 None。两条 legacy 加载用例钉死该语义
  // （ScheduleActionsSpec "legacy file..." / "explicit name:null..."）。
  given Codec[ScheduledTask] = deriveCodec

  def create(
    sessionId: String,
    content: String,
    triggerAt: Long,
    referencePath: Option[String] = None,
    repeat: Option[String] = None,
    name: Option[String] = None
  ): ScheduledTask =
    ScheduledTask(
      id = UUID.randomUUID().toString.take(8),
      sessionId = sessionId,
      content = content,
      triggerAt = triggerAt,
      createdAt = System.currentTimeMillis(),
      referencePath = referencePath,
      repeat = repeat,
      name = name.map(_.trim).filter(_.nonEmpty)
    )
end ScheduledTask
