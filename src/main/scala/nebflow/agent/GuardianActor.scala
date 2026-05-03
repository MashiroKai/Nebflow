package nebflow.agent

import cats.effect.std.Dispatcher
import cats.effect.{IO, Ref}
import io.circe.Json
import nebflow.gateway.SessionStore
import nebflow.shared.*
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, Behavior}

object GuardianActor:

  def apply(resources: SharedResources): Behavior[GuardianCommand] =
    Behaviors
      .supervise(
        Behaviors.setup[GuardianCommand] { context =>
          guardian(resources, Map.empty, context)
        }
      )
      .onFailure[Exception](org.apache.pekko.actor.typed.SupervisorStrategy.restart)

  private def guardian(
    resources: SharedResources,
    sessions: Map[String, ActorRef[SessionCommand]],
    ctx: org.apache.pekko.actor.typed.scaladsl.ActorContext[GuardianCommand]
  ): Behavior[GuardianCommand] =
    Behaviors.receiveMessage:
      case GuardianCommand.CreateSession(wsConnId, replyTo, wsSend) =>
        val sessionName = s"session-$wsConnId"
        val perConnResources = resources.copy(wsSend = wsSend)
        val session = ctx.spawn(SessionActor(wsConnId, perConnResources), sessionName)
        ctx.watchWith(session, SessionTerminated(wsConnId))
        replyTo ! SessionRef(session)
        guardian(resources, sessions + (wsConnId -> session), ctx)

      case GuardianCommand.DestroySession(wsConnId) =>
        sessions.get(wsConnId).foreach { ref =>
          ref ! SessionCommand.Terminate()
          ctx.stop(ref)
        }
        guardian(resources, sessions - wsConnId, ctx)

      case SessionTerminated(wsConnId) =>
        guardian(resources, sessions - wsConnId, ctx)

      case GuardianCommand.Shutdown(replyTo) =>
        sessions.values.foreach(ctx.stop)
        replyTo ! Ack
        Behaviors.stopped

end GuardianActor
