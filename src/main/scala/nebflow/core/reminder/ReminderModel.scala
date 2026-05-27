package nebflow.core.reminder

import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec

import java.util.UUID

/** A scheduled reminder attached to a session. */
case class Reminder(
  id: String,
  sessionId: String,
  content: String,
  triggerAt: Long,
  createdAt: Long,
  triggered: Boolean = false,
  triggeredAt: Option[Long] = None,
  /** Optional file path for the LLM to reference when the reminder fires. */
  referencePath: Option[String] = None
)

object Reminder:

  given Codec[Reminder] = deriveCodec

  def create(
    sessionId: String,
    content: String,
    triggerAt: Long,
    referencePath: Option[String] = None
  ): Reminder =
    Reminder(
      id = UUID.randomUUID().toString.take(8),
      sessionId = sessionId,
      content = content,
      triggerAt = triggerAt,
      createdAt = System.currentTimeMillis(),
      referencePath = referencePath
    )
end Reminder
