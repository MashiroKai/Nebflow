package nebflow.gateway

import io.circe.Json
import io.circe.syntax.*
import munit.FunSuite
import nebflow.actor.ActorRef
import nebflow.agent.{AgentCommand, AgentKind, AgentRecord}
import nebflow.shared.SessionMeta

/**
  * Contract tests for the activeAgents restore reply entry
  * (WebSocketRoutes.activeAgentEntryJson).
  *
  * The frontend keys its bg-agent map by the agentId of BOTH this restore
  * reply and live agentStart events. Live events carry the ACTOR PATH NAME
  * (ctx.self.path.name), so the two key spaces only merge when every
  * subagent spawn path names its actor by the session id — the Mail path
  * used to spawn "mail-<sid8>", and after a browser refresh the restore
  * reply (agentId=sid) plus the next live agentStart (agentId=mail-<sid8>)
  * filed TWO running rows for ONE session: the Teams panel double-entry
  * ghost (2026-08-17 report, Mail-activation path).
  *
  * `task` mirrors the live agentStart taskDescription (= session display
  * name, "team/agent" for mounted team sessions) so restored rows render
  * with the same team attribution as live ones.
  */
class ActiveAgentsEntrySpec extends FunSuite:

  // activeAgentEntryJson never touches the actor ref — null is safe here
  // (same pattern as ActiveAgentsFilterSpec).
  private def rec(sessionId: String, kind: AgentKind, root: String = "root-1"): AgentRecord =
    AgentRecord(
      sessionId = sessionId,
      ref = null.asInstanceOf[ActorRef[AgentCommand]],
      kind = kind,
      rootSessionId = root
    )

  private def meta(
    id: String,
    name: String,
    agentName: Option[String]
  ): SessionMeta =
    SessionMeta(
      id = id,
      name = name,
      createdAt = 0L,
      updatedAt = 0L,
      hasUnread = false,
      agentName = agentName
    )

  test("contract: agentId equals sessionId — restore and live key spaces merge") {
    val json = WebSocketRoutes.activeAgentEntryJson(rec("sid-123", AgentKind.Team), None)
    assertEquals(json.hcursor.get[String]("agentId"), Right("sid-123"))
    assertEquals(json.hcursor.get[String]("sessionId"), Right("sid-123"))
  }

  test("mounted team session restores with team attribution (task = session name)") {
    val m = meta("sid-123", "nebflow-project/Frontend", Some("Frontend"))
    val json = WebSocketRoutes.activeAgentEntryJson(rec("sid-123", AgentKind.Team), Some(m))
    assertEquals(json.hcursor.get[String]("agentName"), Right("Frontend"))
    assertEquals(json.hcursor.get[String]("task"), Right("nebflow-project/Frontend"))
    assertEquals(json.hcursor.get[String]("kind"), Right("Team"))
  }

  test("missing meta falls back: agentName = sessionId, task empty") {
    val json = WebSocketRoutes.activeAgentEntryJson(rec("sid-xyz", AgentKind.Delegate), None)
    assertEquals(json.hcursor.get[String]("agentName"), Right("sid-xyz"))
    assertEquals(json.hcursor.get[String]("task"), Right(""))
  }

end ActiveAgentsEntrySpec
