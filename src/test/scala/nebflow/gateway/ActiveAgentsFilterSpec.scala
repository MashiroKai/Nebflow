package nebflow.gateway

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.FunSuite
import nebflow.agent.{AgentCommand, AgentKind, AgentRecord}
import nebflow.core.flow.TeamSessionRegistry
import nebflow.actor.ActorRef

/**
  * Regression tests for the Teams ghost-running fix: getActiveAgents used to
  * report every AgentRegistry entry of kind Team as running. Team agents are
  * long-lived (activateAgent registers them on the first Mail; they stay in
  * the registry while idle), so after any browser refresh the bg-agent
  * dropdown showed idle team agents as running ghosts — the "3 bare-name
  * running rows" in the Teams panel (2026-08-16 report).
  *
  * filterActiveAgents now gates Team entries on the same busy signal
  * /api/teams/mounted uses (TeamSessionRegistry.markBusy/markIdle, driven by
  * markTeamBusy/markTeamIdle around each team agent turn). Task-lifecycle
  * kinds (Delegate/Ephemeral/Flow/SubTask) remain presence-based: they
  * unregister on completion.
  *
  * Uses the REAL TeamSessionRegistry singleton (busyMap) — each test marks
  * only its own session ids and clears them in finally, so no cross-test
  * state leaks.
  */
class ActiveAgentsFilterSpec extends FunSuite:

  // filterActiveAgents never touches the actor ref — null is safe here.
  private def rec(sessionId: String, kind: AgentKind): AgentRecord =
    AgentRecord(
      sessionId = sessionId,
      ref = null.asInstanceOf[ActorRef[AgentCommand]],
      kind = kind,
      rootSessionId = "root-1"
    )

  private def run(registry: Map[String, AgentRecord]): Set[(String, AgentKind)] =
    WebSocketRoutes
      .filterActiveAgents(registry)
      .unsafeRunSync()
      .map(r => (r.sessionId, r.kind))
      .toSet

  private def withBusy[A](sids: String*)(test: => A): A =
    sids.foreach(s => TeamSessionRegistry.markBusy(s).unsafeRunSync())
    try test
    finally sids.foreach(s => TeamSessionRegistry.markIdle(s).unsafeRunSync())

  test("idle team agent is NOT reported — the ghost-running regression") {
    // Long-lived team agent registered by a past Mail, turn long finished
    // (markIdle ran): must not show up as running after a browser refresh.
    val out = run(Map("team-sid-1" -> rec("team-sid-1", AgentKind.Team)))
    assert(!out.exists(_._1 == "team-sid-1"), s"idle team agent leaked: $out")
  }

  test("busy team agent IS reported") {
    withBusy("team-sid-2") {
      val out = run(Map("team-sid-2" -> rec("team-sid-2", AgentKind.Team)))
      assertEquals(out, Set(("team-sid-2", AgentKind.Team)))
    }
  }

  test("team agent transitions out when the turn ends (markIdle)") {
    withBusy("team-sid-3") {
      val busy = run(Map("team-sid-3" -> rec("team-sid-3", AgentKind.Team)))
      assert(busy.exists(_._1 == "team-sid-3"))
    }
    // after finally/markIdle the same registry must report nothing
    val idle = run(Map("team-sid-3" -> rec("team-sid-3", AgentKind.Team)))
    assert(idle.isEmpty, s"team agent still reported after markIdle: $idle")
  }

  test("task-lifecycle kinds are presence-based (no busy gating)") {
    // Delegate/SubTask/Flow/Ephemeral unregister on completion, so registry
    // presence == in flight. No busy marking needed for them to be reported.
    val registry = Map(
      "del-1" -> rec("del-1", AgentKind.Delegate),
      "sub-1" -> rec("sub-1", AgentKind.SubTask),
      "flow-1" -> rec("flow-1", AgentKind.Flow),
      "eph-1" -> rec("eph-1", AgentKind.Ephemeral)
    )
    val out = run(registry)
    assertEquals(
      out,
      Set(
        ("del-1", AgentKind.Delegate),
        ("sub-1", AgentKind.SubTask),
        ("flow-1", AgentKind.Flow),
        ("eph-1", AgentKind.Ephemeral)
      )
    )
  }

  test("root agents are never reported") {
    val out = run(Map("root-1" -> rec("root-1", AgentKind.Root)))
    assert(out.isEmpty)
  }

  test("mixed registry: only busy team + task-lifecycle kinds survive") {
    val registry = Map(
      "root-9" -> rec("root-9", AgentKind.Root),
      "team-idle" -> rec("team-idle", AgentKind.Team),
      "team-busy" -> rec("team-busy", AgentKind.Team),
      "del-9" -> rec("del-9", AgentKind.Delegate)
    )
    withBusy("team-busy") {
      val out = run(registry)
      assertEquals(
        out,
        Set(
          ("team-busy", AgentKind.Team),
          ("del-9", AgentKind.Delegate)
        )
      )
    }
  }
