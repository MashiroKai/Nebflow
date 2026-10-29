package nebflow.core.flow

import nebflow.core.entity.TeamDef

// ============================================================
// Tree Commands
// ============================================================
//
// R4 legacy-hygiene batch (2026-10-29):
//   · MountResult + the replyTo carrier on MountTeam removed — the only
//     sender (Load tool) always passed None, so the reply protocol was
//     unreachable.
//   · UnmountBranch / WatchAgent / ResumeAgent / Shutdown removed — zero
//     senders repo-wide. The WatchAgent → Terminated → ResumeAgent chain
//     was part of the fake TurnStateStore crash recovery: nothing ever
//     wrote turn-state.json in production.

sealed trait TreeCommand

object TreeCommand:

  case class ReloadDefinition(flowName: String) extends TreeCommand

  case class CancelPipeline(name: String) extends TreeCommand

  /** Mount a Team (team.json format). Creates sessions for lead + members. */
  case class MountTeam(teamDef: TeamDef) extends TreeCommand

end TreeCommand
