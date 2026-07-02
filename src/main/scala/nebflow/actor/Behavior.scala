package nebflow.actor

import cats.effect.IO

/** An actor's behavior: process one message, return the next behavior.
  *
  * State is carried through the returned behavior's closure. This is the
  * classic Actor model pattern — each message handler returns the next
  * behavior, carrying the updated state.
  *
  * The key difference from Pekko: receive returns IO[Behavior[Msg]],
  * making the message handler cancelable, composable, and resource-safe.
  */
trait Behavior[Msg]:
  def receive(ctx: ActorContext[Msg], msg: Msg): IO[Behavior[Msg]]
  def isStopped: Boolean = false
  /** Called on actor spawn. Setup behaviors override to initialize context. */
  def onStart(ctx: ActorContext[Msg]): IO[Behavior[Msg]] = IO.pure(this)

object Behaviors:

  /** Setup behavior — runs factory on spawn to produce initial behavior. */
  final case class Setup[Msg](factory: ActorContext[Msg] => IO[Behavior[Msg]]) extends Behavior[Msg]:
    def receive(ctx: ActorContext[Msg], msg: Msg): IO[Behavior[Msg]] =
      factory(ctx).flatMap(_.receive(ctx, msg))
    override def onStart(ctx: ActorContext[Msg]): IO[Behavior[Msg]] = factory(ctx)

  /** Setup: initialize context on spawn, return initial behavior. */
  def setup[Msg](factory: ActorContext[Msg] => IO[Behavior[Msg]]): Behavior[Msg] =
    Setup(factory)

  /** Pure message handler — most common. */
  def receiveMessage[Msg](handler: Msg => IO[Behavior[Msg]]): Behavior[Msg] = new:
    def receive(ctx: ActorContext[Msg], msg: Msg): IO[Behavior[Msg]] = handler(msg)

  /** Message handler with context access. */
  def receive[Msg](handler: (ActorContext[Msg], Msg) => IO[Behavior[Msg]]): Behavior[Msg] = new:
    def receive(ctx: ActorContext[Msg], msg: Msg): IO[Behavior[Msg]] = handler(ctx, msg)

  /** Terminal behavior — actor stops after returning this. */
  object Stopped extends Behavior[Nothing]:
    def receive(ctx: ActorContext[Nothing], msg: Nothing): IO[Behavior[Nothing]] =
      IO.pure(this)
    override def isStopped: Boolean = true

  def stopped[Msg]: Behavior[Msg] = Stopped.asInstanceOf[Behavior[Msg]]
end Behaviors
