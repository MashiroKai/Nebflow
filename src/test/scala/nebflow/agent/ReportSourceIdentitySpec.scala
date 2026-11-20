package nebflow.agent

import io.circe.Json
import nebflow.actor.AgentDef
import munit.FunSuite

class ReportSourceIdentitySpec extends FunSuite:
  test("stamp uses emitting agent and depth, not session id") {
    val event = Json.obj("type" -> Json.fromString("textDelta"))
    val nebula = AgentDef(name = "Nebula", description = "")
    val other = AgentDef(name = "worker", description = "")
    assertEquals(AgentActor.stampSourceIdentity(event, nebula, 0).hcursor.get[Boolean]("isRoot"), Right(true))
    assertEquals(AgentActor.stampSourceIdentity(event, nebula, 1).hcursor.get[Boolean]("isRoot"), Right(false))
    assertEquals(AgentActor.stampSourceIdentity(event, other, 0).hcursor.get[Boolean]("isRoot"), Right(false))
  }

  test("forwarding preserves an existing source stamp") {
    val event = Json.obj("isRoot" -> Json.fromBoolean(false))
    val root = AgentDef(name = "Nebula", description = "")
    assertEquals(AgentActor.stampSourceIdentity(event, root, 0), event)
  }
