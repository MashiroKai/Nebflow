package nebflow.gateway

import io.circe.Json
import io.circe.syntax.*
import cats.effect.unsafe.implicits.global
import munit.FunSuite
import nebflow.actor.{ActorRef, AgentCommand, AgentKind, AgentRecord}
import nebflow.core.tools.{BgHealth, BgTaskRegistry}

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
        bgWaiting = waiting.map(t => (t.jobId, t.description, t.startedAtMs, BgHealth.of(t)))
      )
      assertEquals(json.hcursor.get[Int]("bgWaitingCount"), Right(2), "explicit count field on the status face")
      val rows = json.hcursor.downField("bgWaiting").values.get.toList
      assertEquals(rows.size, 2)
      assert(rows.exists(_.hcursor.get[String]("taskId").exists(_ == "job-1")), rows)
      assert(rows.exists(_.hcursor.get[String]("description").exists(_ == "render deck (puppeteer)")), rows)
      assert(rows.forall(_.hcursor.get[Long]("startedAt").isRight), "start timestamp carried for the 起止 face")
      // P0 面板投影批（设计稿 §3.4 验收③）：行健康字段随行且与账本行同源——
      // 值 = 同一 ActiveTask 行经 BgHealth.of 派生（单一事实源，禁第二状态源）。
      assert(rows.forall(_.hcursor.get[String]("health").contains("healthy")), rows)
    finally BgTaskRegistry.unregisterSession(Some(sessionId)).unsafeRunSync()

    end try

  test("S2 无在飞任务 ⇒ bgWaitingCount=0（与 bgWaiting 空数组恒一致）"):
    val json = WebSocketRoutes.activeAgentEntryJson(rec("sid-bgcount-2"), None, bgWaiting = Nil)
    assertEquals(json.hcursor.get[Int]("bgWaitingCount"), Right(0))
    assertEquals(json.hcursor.downField("bgWaiting").values.map(_.size), Some(0))

  test("S3 健康写缝激活后 ⇒ 快照行 health 与账本行同源（suspect/escalated 逐字一致）"):
    // P0 面板投影批（设计稿 §7 P0 验收③「快照帧与实时帧字段同源」）：updateHealth
    // 写缝（P1 reconciler 落点）落行后，bgWaiting 载荷的健康值与 BgHealth.of(行)
    // 恒一致——面板黄/红徽不依赖第二状态源。
    val sessionId = "sid-bgcount-3"
    BgTaskRegistry.register("job-h1", sessionId, "playwright e2e", kind = "local").unsafeRunSync()
    try
      BgTaskRegistry.updateHealth("job-h1", "suspect").unsafeRunSync()
      val waitingSuspect = BgTaskRegistry.waitingBySession.unsafeRunSync().getOrElse(sessionId, Nil)
      val jsonS = WebSocketRoutes.activeAgentEntryJson(
        rec(sessionId),
        None,
        bgWaiting = waitingSuspect.map(t => (t.jobId, t.description, t.startedAtMs, BgHealth.of(t)))
      )
      val rowS = jsonS.hcursor.downField("bgWaiting").values.get.toList.head
      assertEquals(rowS.hcursor.get[String]("health"), Right("suspect"), "suspect 投影随行")

      BgTaskRegistry.updateHealth("job-h1", "escalated").unsafeRunSync()
      val waitingEsc = BgTaskRegistry.waitingBySession.unsafeRunSync().getOrElse(sessionId, Nil)
      val jsonE = WebSocketRoutes.activeAgentEntryJson(
        rec(sessionId),
        None,
        bgWaiting = waitingEsc.map(t => (t.jobId, t.description, t.startedAtMs, BgHealth.of(t)))
      )
      val rowE = jsonE.hcursor.downField("bgWaiting").values.get.toList.head
      assertEquals(rowE.hcursor.get[String]("health"), Right("escalated"), "escalated 投影随行")
      // 重排不判死（设计稿 §3.1）：降回 healthy ⇒ 停滞计数清零、升级戳清零。
      BgTaskRegistry.updateHealth("job-h1", "healthy").unsafeRunSync()
      val waitingOk = BgTaskRegistry.waitingBySession.unsafeRunSync().getOrElse(sessionId, Nil)
      assertEquals(BgHealth.of(waitingOk.head), "healthy")
      assertEquals(waitingOk.head.suspicionLevel, 0)
      assertEquals(waitingOk.head.escalatedAtMs, 0L)
    finally BgTaskRegistry.unregisterSession(Some(sessionId)).unsafeRunSync()

    end try
end BgWaitingCountSpec
