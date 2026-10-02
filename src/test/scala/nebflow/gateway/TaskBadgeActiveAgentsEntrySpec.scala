package nebflow.gateway


import io.circe.syntax.*
import munit.FunSuite
import nebflow.actor.ActorRef
import nebflow.actor.{AgentCommand, AgentKind, AgentRecord} // W1 shim: main exported these from nebflow.agent; the merge moved them to actor

/** Task-badge contract on the activeAgents restore reply (taskbadge batch,
  * 2026-09-27): the snapshot face of the sub-agents panel task attribution.
  *
  * The panel's rows are restored from the `activeAgents` snapshot after a page
  * refresh (live agentStart events are never replayed), so the badge must ride
  * the same entry: `taskId` = the registration-time snapshot on AgentRecord
  * (dispatcher slot / NodeDef.taskId), `taskTitle` = the ledger title resolved
  * by the getActiveAgents handler (one ledger read per snapshot, passed in).
  *
  * Wire form mirrors the `project` badge precedent: keys are ALWAYS present,
  * empty string = no attribution (frontend falsy → no badge rendered) — a
  * stable form, never conditional keys.
  */
class TaskBadgeActiveAgentsEntrySpec extends FunSuite:

  private def rec(sessionId: String, kind: AgentKind, root: String = "root-1"): AgentRecord =
    AgentRecord(
      sessionId = sessionId,
      ref = null.asInstanceOf[ActorRef[AgentCommand]],
      kind = kind,
      rootSessionId = root
    )

  test("attributed dispatcher row carries taskId + taskTitle (restore path)") {
    val r = rec("dispatcher-ab12cd34", AgentKind.Flow)
      .copy(project = Some("nebflow"), taskId = Some("35"))
    val json = WebSocketRoutes.activeAgentEntryJson(r, None, taskTitle = Some("task thirty-five"))
    assertEquals(json.hcursor.get[String]("taskId"), Right("35"))
    assertEquals(json.hcursor.get[String]("taskTitle"), Right("task thirty-five"))
    // coexisting badges intact
    assertEquals(json.hcursor.get[String]("project"), Right("nebflow"))
    assertEquals(json.hcursor.get[String]("agentName"), Right("dispatcher-ab12cd34"))
  }

  test("attributed node row carries its NodeDef task attribution") {
    val r = rec("node-ab12cd34", AgentKind.Flow).copy(taskId = Some("12"))
    val json = WebSocketRoutes.activeAgentEntryJson(r, None, taskTitle = Some("task twelve"))
    assertEquals(json.hcursor.get[String]("taskId"), Right("12"))
    assertEquals(json.hcursor.get[String]("taskTitle"), Right("task twelve"))
  }

  test("no attribution → both keys present but EMPTY (form stable, frontend falsy)") {
    val json = WebSocketRoutes.activeAgentEntryJson(rec("delegate-x-12345678", AgentKind.Delegate), None)
    assertEquals(json.hcursor.get[String]("taskId"), Right(""))
    assertEquals(json.hcursor.get[String]("taskTitle"), Right(""))
  }

  test("taskId without resolved title → taskId set, taskTitle empty (degraded badge)") {
    val r = rec("node-99", AgentKind.Flow).copy(taskId = Some("99"))
    val json = WebSocketRoutes.activeAgentEntryJson(r, None, taskTitle = None)
    assertEquals(json.hcursor.get[String]("taskId"), Right("99"))
    assertEquals(json.hcursor.get[String]("taskTitle"), Right(""))
  }

  test("existing entry contract unchanged (agentId==sessionId, six base fields)") {
    val r = rec("sid-1", AgentKind.Flow).copy(taskId = Some("35"))
    val json = WebSocketRoutes.activeAgentEntryJson(r, None, taskTitle = Some("t"))
    assertEquals(json.hcursor.get[String]("agentId"), Right("sid-1"))
    assertEquals(json.hcursor.get[String]("sessionId"), Right("sid-1"))
    assertEquals(json.hcursor.get[String]("kind"), Right("Flow"))
    assertEquals(json.hcursor.get[Long]("startedAt"), Right(0L))
    assertEquals(json.hcursor.get[Int]("retryCount"), Right(0))
    assertEquals(json.hcursor.get[String]("status"), Right("Idle"))
  }

end TaskBadgeActiveAgentsEntrySpec
