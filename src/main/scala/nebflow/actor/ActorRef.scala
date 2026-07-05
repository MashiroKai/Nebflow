package nebflow.actor

import cats.effect.IO

import scala.concurrent.duration.{DurationInt, FiniteDuration}

/**
 * Public handle for sending messages to an actor.
 *
 * Two operations:
 *   - `ref ! msg` — tell: fire-and-forget, puts message in mailbox
 *   - `ref ? (replyTo => msg)` — ask: send message with reply-to, wait for response
 *
 * Both return IO, so message sending is composable and cancelable.
 */
trait ActorRef[-Msg]:
  def path: ActorPath

  /** Tell — put a message in the actor's mailbox. Non-blocking. */
  def !(msg: Msg): IO[Unit]

  /**
   * Ask — send a message containing a temporary reply address, wait for reply.
   *
   * Creates a one-shot Deferred internally. The target actor must send a
   * message to the provided replyTo reference to complete the ask.
   *
   * @param timeout pass `None` to wait indefinitely (e.g. for human interaction)
   */
  def ?[Reply](makeMsg: ActorRef[Reply] => Msg, timeout: Option[FiniteDuration] = Some(30.seconds)): IO[Reply]

end ActorRef

object ActorRef:

  /** Parse a path string and resolve via the given system. */
  def resolve[Msg](path: String)(using system: ActorSystem): IO[ActorRef[Msg]] =
    system.resolve[Msg](path)
