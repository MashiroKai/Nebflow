package nebflow.core.mail

import cats.effect.IO
import io.circe.*
import io.circe.parser.decode
import io.circe.syntax.*
import nebflow.core.AtomicJson
import nebflow.shared.{Message, PathUtil}
import nebflow.shared.given

/** Durable, identity-keyed completion debt for a session's current supervisor. */
object CompletionDebtStore:
  final case class Debt(ownerSessionId: String, messages: List[Message], phase: String = "deliverable")
  object Debt:
    given Encoder[Debt] = Encoder.forProduct3("ownerSessionId", "messages", "phase")(d => (d.ownerSessionId, d.messages, d.phase))
    given Decoder[Debt] = Decoder.forProduct3("ownerSessionId", "messages", "phase")(Debt.apply)

  private def file(owner: String): os.Path =
    PathUtil.dataRoot / "sessions" / SessionMailbox.safeId(owner) / "completion-debt.json"

  /** Durable before the caller sends its actor notification. Repeated writes replace the same debt. */
  def record(ownerSessionId: String, messages: List[Message]): IO[Unit] = IO.blocking {
    require(ownerSessionId.trim.nonEmpty, "completion debt requires an owner session id")
    val p = file(ownerSessionId)
    os.makeDir.all(p / os.up)
    AtomicJson.writeSync(p, Debt(ownerSessionId, messages).asJson.noSpaces)
  }

  /** Read after receiver registration; missing or legacy sessions simply have no debt. */
  def pending(ownerSessionId: String): IO[Option[Debt]] = IO.blocking {
    val p = file(ownerSessionId)
    if !os.exists(p) then None else decode[Debt](os.read(p)).toOption
  }.handleError(_ => None)

  /** Acknowledgement is explicit and occurs only after the receiver accepts the terminal event. */
  def acknowledge(ownerSessionId: String): IO[Unit] = IO.blocking {
    val p = file(ownerSessionId)
    if os.exists(p) then os.remove(p)
  }
end CompletionDebtStore
