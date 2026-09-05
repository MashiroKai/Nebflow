package nebflow.agent

import cats.effect.{Deferred, IO, Ref}
import cats.syntax.all.*
import io.circe.Json
import munit.CatsEffectSuite

import scala.concurrent.duration.*

/**
 * Issue #43 — AskUserQuestion pending 期间 agent 侧注入消息不得被误消费为答案。
 *
 * 真实案例（2026-09-03 11:47-12:03，截图 + ui.json [763]-[766] + gateway log
 * 12:04:05 实证）：Nebula 两问 pending 期间两个 delegate 结果到达，
 * delegate 汇报文本被拆填进两个答案槽（Q1 = 汇报首行 token、Q2 = "任务完成。"），
 * 卡片被锁定 → 用户只能走输入框直通 → 1 槽答案 → Q2 "(skipped)"。
 *
 * 后端契约（本 spec 用真实 InteractionHub actor + 生产 drain 决策钉住）：
 *  1. 答案来源校验：agent 消息形态的负载（无 answers 字段的 delegate 汇报文本）
 *     永远不能完成 pending 的 AskUser 槽 —— 卡片保留、延迟保持 pending（b）；
 *  2. 用户路径回归：卡片点击（Answered with answers）与输入框直通
 *     （AnswerViaChatInput）在「pending → agent 消息到达 → 用户回答」的完整
 *     事故时序下仍正常解除 pending（c）；
 *  3. 排队语义：pending 期间到达的 delegate 结果走 AgentActor processing 梯的
 *     pendingEvents 队列（:2312-2315），turn 恢复后由生产 drain 决策
 *     TurnBoundaryDrains.drainBarrier 按到达顺序完整注入 —— 不丢不改序（b/d）。
 *
 * 前端误消费环（restore 把 injected 气泡当卡片答案）由
 * tests/askuser-answer-source.spec.mjs（Playwright，真实渲染模块）钉住。
 */
class AskUserPendingInjectionSpec extends CatsEffectSuite:

  // —— 事故原文（ui.json [764][765] 的开头片段，消息形态对齐真实链路）——
  private val delegateReport1 =
    "\"分层压缩提示词设计稿\":\n任务完成。\n\n**规格书路径**：`~/.nebflow/docs/Nebflow/20260903_compaction-prompt-by-level.md`"
  private val delegateReport2 =
    "\"v3.2：纵排层级+曲线连线+禁emoji\":\n# Flow Map 原型 v3.2 修订汇报（作者 11:08 三点意见）\n\n**结论：PASS**"

  /** Agent 消息到达 hub 答案通道时的负载形态：delegate 汇报是纯文本，
    * 没有 answers 字段 —— 与 gateway askUserAnswer 帧（answers: List[String]）
    * 的用户答案形态相区分。 */
  private def agentMessagePayload(text: String): Json = Json.obj("text" -> Json.fromString(text))

  private def userAnswerPayload(answers: String*): Json =
    Json.obj("answers" -> Json.arr(answers.map(Json.fromString)*))

  private def askRequest(
      requestId: String,
      gotAnswers: Ref[IO, Option[List[String]]],
      rootSid: String = "root-1",
      system: nebflow.actor.ActorSystem
  ): IO[InteractionRequest] =
    system
      .spawn(
        nebflow.actor.Behaviors.receiveMessage[List[String]] { answers =>
          gotAnswers.set(Some(answers)).as(nebflow.actor.Behaviors.stopped)
        },
        s"ask-sink-$requestId"
      )
      .map { sink =>
        InteractionRequest(
          requestId = requestId,
          kind = InteractionKind.AskUser,
          payload = Json.obj("items" -> Json.arr(), "agentName" -> Json.fromString("Nebula")),
          reply = InteractionReply.AskUserReply(Some(sink)),
          rootSessionId = rootSid,
          sourceAgent = "Nebula",
          sourceSession = rootSid
        )
      }

  // ============================================================
  // (b) 答案来源校验：agent 消息形态的负载不完成 pending AskUser
  // ============================================================

  test("(b) agent 消息形态的负载不完成 pending AskUser —— 答案槽保持 pending、卡片保留可答") {
    val system = nebflow.actor.ActorSystem("askuser-guard-b")
    for
      hub <- system.spawn(InteractionHub(), "interaction-hub-b")
      _ <- hub ! InteractionHubCommand.RegisterRoot("root-1", (_: Json) => IO.unit)
      gotAnswers <- Ref.of[IO, Option[List[String]]](None)
      req <- askRequest("guard-b", gotAnswers, system = system)
      _ <- hub ! InteractionHubCommand.Request(req)
      _ <- IO.sleep(50.millis)
      // pending 期间 agent 侧消息到达（delegate 汇报形态：无 answers 字段）
      _ <- hub ! InteractionHubCommand.Answered(
        InteractionAnswered("guard-b", "root-1", agentMessagePayload(delegateReport1))
      )
      _ <- hub ! InteractionHubCommand.Answered(
        InteractionAnswered("guard-b", "root-1", agentMessagePayload(delegateReport2))
      )
      _ <- IO.sleep(100.millis)
      polluted <- gotAnswers.get
      // 答案槽保持 pending：用户的真实回答（卡片 Other 路径，含空槽）仍能到达
      _ <- hub ! InteractionHubCommand.Answered(
        InteractionAnswered("guard-b", "root-1", userAnswerPayload("部分调整", ""))
      )
      _ <- IO.sleep(100.millis) // hub forkTurn 异步处理 —— 等 sink 落 Ref
      real <- gotAnswers.get
      _ <- system.stopAll
    yield
      assertEquals(polluted, None, "agent 消息负载不得填入答案槽（pending 必须保持）")
      assertEquals(real, Some(List("部分调整", "")), "用户真实回答不受 agent 消息影响")
    end for
  }

  // ============================================================
  // (c) 用户路径回归：事故完整时序下两条用户通道仍正常
  // ============================================================

  test("(c) 卡片点击回答：pending → agent 消息到达 → 用户点击仍解除 pending") {
    val system = nebflow.actor.ActorSystem("askuser-guard-c1")
    for
      hub <- system.spawn(InteractionHub(), "interaction-hub-c1")
      _ <- hub ! InteractionHubCommand.RegisterRoot("root-1", (_: Json) => IO.unit)
      gotAnswers <- Ref.of[IO, Option[List[String]]](None)
      req <- askRequest("guard-c1", gotAnswers, system = system)
      _ <- hub ! InteractionHubCommand.Request(req)
      _ <- IO.sleep(50.millis)
      _ <- hub ! InteractionHubCommand.Answered(
        InteractionAnswered("guard-c1", "root-1", agentMessagePayload(delegateReport1))
      )
      _ <- IO.sleep(50.millis)
      afterAgent <- gotAnswers.get
      _ <- hub ! InteractionHubCommand.Answered(
        InteractionAnswered("guard-c1", "root-1", userAnswerPayload("全部按建议 a"))
      )
      _ <- IO.sleep(100.millis)
      afterUser <- gotAnswers.get
      _ <- system.stopAll
    yield
      assertEquals(afterAgent, None, "agent 消息不得替代用户点击")
      assertEquals(afterUser, Some(List("全部按建议 a")), "卡片点击回答仍正常")
    end for
  }

  test("(c) 输入框直通回答：pending 期间 agent 消息排队不占槽，用户自由文本成为工具结果") {
    val system = nebflow.actor.ActorSystem("askuser-guard-c2")
    for
      hub <- system.spawn(InteractionHub(), "interaction-hub-c2")
      sent <- Ref.of[IO, List[Json]](Nil)
      _ <- hub ! InteractionHubCommand.RegisterRoot("root-1", (j: Json) => sent.update(_ :+ j))
      gotAnswers <- Ref.of[IO, Option[List[String]]](None)
      req <- askRequest("guard-c2", gotAnswers, system = system)
      _ <- hub ! InteractionHubCommand.Request(req)
      _ <- IO.sleep(50.millis)
      // agent 消息形态负载先到（不得消费直通以外的任何槽位）
      _ <- hub ! InteractionHubCommand.Answered(
        InteractionAnswered("guard-c2", "root-1", agentMessagePayload(delegateReport1))
      )
      _ <- IO.sleep(50.millis)
      // 用户从输入框输入真实回答（2026-08-29 裁定：直通 = 工具结果）
      answered <- Deferred[IO, Boolean]
      _ <- hub ! InteractionHubCommand.AnswerViaChatInput("root-1", "如果 delegate 结果丢失，那更是严重的 bug", answered)
      hit <- answered.get
      slot <- gotAnswers.get
      _ <- IO.sleep(50.millis)
      events <- sent.get
      _ <- system.stopAll
    yield
      assertEquals(hit, true, "直通必须消费 pending 卡片")
      assertEquals(
        slot,
        Some(List("如果 delegate 结果丢失，那更是严重的 bug")),
        "用户的自由文本就是工具结果（1 槽），未被 agent 消息污染"
      )
      val close = events.find(_.hcursor.downField("type").as[String].contains("askUserAnswered")).get
      assertEquals(close.hcursor.downField("requestId").as[String], Right("guard-c2"))
      assertEquals(close.hcursor.downField("via").as[String], Right("chat-input"))
    end for
  }

  // ============================================================
  // (b/d) 排队语义：pending 期间到达的 delegate 结果按序完整注入
  // ============================================================

  // 事件构造与 AgentActor.processing 梯（:2303-2315）入库形态一致：
  // pendingEvents :+ event（追加 = 到达顺序），source/eventType 对齐真实 delegate 链路。
  private def delegateEvent(n: Int, payload: String): AgentCommand.ExternalEvent =
    AgentCommand.ExternalEvent(
      source = "delegate",
      eventType = "completed",
      payload = payload,
      metadata = io.circe.JsonObject("agentName" -> Json.fromString(s"delegate-agent-$n")),
      correlationId = None
    )

  test("(d) pending 期间多条 agent 消息到达 → turn 恢复后按到达顺序注入（FIFO，不丢不改序）") {
    // 生产入库：AgentActor processing 梯 pendingEvents :+ event（到达序追加）
    val queue = List(delegateEvent(1, delegateReport1), delegateEvent(2, delegateReport2))
    // turn 恢复（AskUser 应答、turn 结束）后的生产 drain 决策：无压缩挂起、
    // 批次 outstanding=0 → 全量放行
    val (drained, remaining) = TurnBoundaryDrains.drainBarrier(queue, compactionPending = false, outstanding = 0)
    assertEquals(
      drained.map(_.payload),
      List(delegateReport1, delegateReport2),
      "排队事件必须按到达顺序完整注入（内容一字不丢）"
    )
    assert(remaining.isEmpty, "恢复后队列必须清空（不丢消息）")
  }

  test("(d) pending 期间 delegate 结果属于批次屏障语义：outstanding>0 时持有、归零时整批注入") {
    // 与真实链路一致：spawn 两个 delegate → 两结果先到一个（outstanding 3→2，
    // 持有），全部到齐（outstanding 0）整批放行 —— 不逐条打断 pending 的 turn
    val e1 = delegateEvent(1, delegateReport1)
    val e2 = delegateEvent(2, delegateReport2)
    assert(TurnBoundaryDrains.isSubagentResult(e1), "delegate 结果走屏障队列")
    val (held1, remaining1) = TurnBoundaryDrains.drainBarrier(List(e1), compactionPending = false, outstanding = 2)
    assertEquals(held1, Nil, "批次未完成前结果必须持有（不侵入 pending 的 turn）")
    assertEquals(remaining1, List(e1))
    val (drained, remaining2) =
      TurnBoundaryDrains.drainBarrier(List(e1, e2), compactionPending = false, outstanding = 0)
    assertEquals(drained.map(_.payload), List(delegateReport1, delegateReport2), "整批按序注入")
    assert(remaining2.isEmpty)
  }

  test("(b) 排队期间到达的 agent 消息内容完整 —— 注入载荷与原汇报逐字一致") {
    val longReport = delegateReport1 + "\n\n## 现状审计核心发现（5 条）\n\n1. 分层机制已存在但对新架构失明"
    val queue = List(delegateEvent(1, longReport))
    val (drained, remaining) = TurnBoundaryDrains.drainBarrier(queue, compactionPending = false, outstanding = 0)
    assertEquals(drained.headOption.map(_.payload), Some(longReport), "排队不截断、不改写消息内容")
    assert(remaining.isEmpty)
  }

end AskUserPendingInjectionSpec
