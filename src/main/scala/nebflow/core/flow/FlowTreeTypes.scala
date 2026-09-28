package nebflow.core.flow

import io.circe.*
import io.circe.syntax.*
import nebflow.actor.ActorRef
import nebflow.core.entity.TeamDef

// ============================================================
// Mount Result
// ============================================================

sealed trait MountResult

object MountResult:
  case class Mounted(name: String, address: String) extends MountResult
  case class Unmounted(name: String) extends MountResult
  case class Error(message: String) extends MountResult

end MountResult

// ============================================================
// Tree Commands
// ============================================================

sealed trait TreeCommand

object TreeCommand:

  case class UnmountBranch(name: String) extends TreeCommand

  case class ReloadDefinition(flowName: String) extends TreeCommand

  case class CancelPipeline(name: String) extends TreeCommand

  /** Watch this agent actor for termination. */
  case class WatchAgent(
    ref: nebflow.actor.ActorRef[nebflow.agent.AgentCommand],
    sessionId: String
  ) extends TreeCommand

  /** FlowTreeActor self-message: resume an interrupted turn for this session. */
  case class ResumeAgent(
    sessionId: String,
    turnStartMessageCount: Int,
    turnIdx: Int
  ) extends TreeCommand

  case object Shutdown extends TreeCommand

  /** Mount a Team (team.json format). Creates sessions for lead + members. */
  case class MountTeam(
    teamDef: TeamDef,
    replyTo: Option[ActorRef[MountResult]] = None
  ) extends TreeCommand

end TreeCommand
