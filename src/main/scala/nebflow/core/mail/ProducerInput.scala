package nebflow.core.mail

import cats.effect.IO
import nebflow.actor.AgentCommand

/** Durable acceptance of producer notifications before the source records delivery. */
object ProducerInput:
  val Producer = "node-notification"

  def isProducerIdentity(identity: Option[String]): Boolean = identity.exists(_.startsWith("node-notification:"))

  def persist(
    sessionId: String,
    input: AgentCommand.ImmediateInput,
    identity: String
  ): IO[AgentCommand.ImmediateInput] =
    val prepared = input.copy(clientMessageId = Some(identity))
    InputMailbox.enqueue(sessionId, InputPayload.ofImmediate(prepared), identity, producer = Producer).flatMap {
      case InputMailbox.InputMailResult.Failed(cause) =>
        IO.raiseError(new IllegalStateException(s"notification intake failed: $cause"))
      case _ => IO.pure(prepared)
    }

  def identity(parts: String*): String =
    val encoded = parts.map(s => s"${s.length}:$s").mkString
    val hash = java.security.MessageDigest
      .getInstance("SHA-256")
      .digest(encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    "node-notification:" + hash.map(b => f"${b & 0xff}%02x").mkString
end ProducerInput
