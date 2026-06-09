package nebflow.core.scheduler

import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec

import java.util.UUID

/** A scheduled task attached to a session — fires once at the specified time. */
case class ScheduledTask(
  id: String,
  sessionId: String,
  content: String,
  triggerAt: Long,
  createdAt: Long,
  triggered: Boolean = false,
  triggeredAt: Option[Long] = None,
  /** Optional file path for the LLM to reference when the task fires. */
  referencePath: Option[String] = None
)

object ScheduledTask:

  given Codec[ScheduledTask] = deriveCodec

  def create(
    sessionId: String,
    content: String,
    triggerAt: Long,
    referencePath: Option[String] = None
  ): ScheduledTask =
    ScheduledTask(
      id = UUID.randomUUID().toString.take(8),
      sessionId = sessionId,
      content = content,
      triggerAt = triggerAt,
      createdAt = System.currentTimeMillis(),
      referencePath = referencePath
    )
end ScheduledTask
