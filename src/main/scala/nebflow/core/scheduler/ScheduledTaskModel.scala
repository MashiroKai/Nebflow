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
  enabled: Boolean = true
)

object ScheduledTask:

  given Codec[ScheduledTask] = deriveCodec

  def create(
    sessionId: String,
    content: String,
    triggerAt: Long,
    referencePath: Option[String] = None,
    repeat: Option[String] = None
  ): ScheduledTask =
    ScheduledTask(
      id = UUID.randomUUID().toString.take(8),
      sessionId = sessionId,
      content = content,
      triggerAt = triggerAt,
      createdAt = System.currentTimeMillis(),
      referencePath = referencePath,
      repeat = repeat
    )
end ScheduledTask
