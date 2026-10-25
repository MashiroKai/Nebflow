package nebflow.gateway

import io.circe.Json
import io.circe.syntax.*
import cats.effect.unsafe.implicits.global
import munit.FunSuite
import nebflow.actor.{ActorRef, AgentCommand, AgentKind, AgentRecord}
import nebflow.core.tools.BgTaskRegistry

/**
 * 修复批 2026-10-24 spec ①「起后台任务→结束回合→会话状态面可见任务数断言」：
 * activeAgents 快照行必须携带本会话在飞后台任务的**显式任务数**（id+描述+起始
 * 随行）——作者病历「状态面报本会话无后台任务，而账目其实在别处丢了」的可见性
 * 收口：状态面读数与账本一致、且无需前端从数组长度反推。
 */
class BgWaitingCountSpec extends FunSuite:

  // activeAgentEntryJson never touches the actor ref — null is safe here
  // (same pattern as ActiveAgentsEntrySpec).
  private def rec(sessionId: String): AgentRecord =
    AgentRecord(
      sessionId = sessionId,
      ref = null.asInstanceOf[ActorRef[AgentCommand]],
      kind = AgentKind.Delegate,
      rootSessionId = "root-1"
    )

  test("S1 有在飞后台任务 ⇒ bgWaitingCount=N 且明细（id+描述+startedAt）随行"):
    // 账本权威：先在 registry 起一个「后台任务」（起后台任务→结束回合的形态）
    val sessionId = "sid-bgcount-1"
    BgTaskRegistry.register("job-1", sessionId, "render deck (puppeteer)", kind = "local").unsafeRunSync()
    BgTaskRegistry.register("job-2", sessionId, "sbt -batch test", kind = "local").unsafeRunSync()
    try
      val waiting = BgTaskRegistry.waitingBySession.unsafeRunSync().getOrElse(sessionId, Nil)
      assertEquals(waiting.size, 2, "registry is the in-flight authority for the status face")
      val json: Json = WebSocketRoutes.activeAgentEntryJson(
        rec(sessionId),
        None,
        bgWaiting = waiting.map(t => (t.jobId, t.description, t.startedAtMs))
      )
      assertEquals(json.hcursor.get[Int]("bgWaitingCount"), Right(2), "explicit count field on the status face")
      val rows = json.hcursor.downField("bgWaiting").values.get.toList
      assertEquals(rows.size, 2)
      assert(rows.exists(_.hcursor.get[String]("taskId").exists(_ == "job-1")), rows)
      assert(rows.exists(_.hcursor.get[String]("description").exists(_ == "render deck (puppeteer)")), rows)
      assert(rows.forall(_.hcursor.get[Long]("startedAt").isRight), "start timestamp carried for the 起止 face")
    finally BgTaskRegistry.unregisterSession(Some(sessionId)).unsafeRunSync()

    end try

  test("S2 无在飞任务 ⇒ bgWaitingCount=0（与 bgWaiting 空数组恒一致）"):
    val json = WebSocketRoutes.activeAgentEntryJson(rec("sid-bgcount-2"), None, bgWaiting = Nil)
    assertEquals(json.hcursor.get[Int]("bgWaitingCount"), Right(0))
    assertEquals(json.hcursor.downField("bgWaiting").values.map(_.size), Some(0))
end BgWaitingCountSpec
