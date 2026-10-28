package nebflow.agent

import cats.effect.IO
import cats.effect.Ref
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.{ActorSystem, Behaviors}
import nebflow.core.tools.{AskUserQuestionTool, ToolContext}
import nebflow.shared.{AskItem, AskOption, AskUserCancelled, AskUserDispatch}

import scala.concurrent.duration.*

/**
 * askucancel 批（2026-10-08）—— 取消信号的**两个消费点**接线（单测级）：
 *
 *   ① 非阻塞桥腿（AskUserAnswerBridge）：取消信号 ⇒ 注入取消通告
 *      （ImmediateInput，source/eventType = askUserCancelled，fromUser=false——
 *      它是引擎侧结论，不是 user 气泡）；同一条管道的既有语义零回归：
 *      派发失败信号仍零注入、真答复仍注入（fromUser=true）。
 *   ② 判据单点（AskUserCancelled）：isCancelled 与 AskUserDispatch.isFailure
 *      互斥（同一文本永不双判），常规答案/空载荷/多槽一律 false。
 */
class AskUserCancelDeliverySpec extends CatsEffectSuite:

  override val munitIOTimeout = 30.seconds

  private val items = List(AskItem("继续？", List(AskOption("yes"))))

  /** 捕获 ImmediateInput 的目标 agent ref + 命中读数（actor 常驻，可收多条）。 */
  private def targetCapturer(system: ActorSystem): IO[
    (
      nebflow.actor.ActorRef[nebflow.actor.AgentCommand],
      Ref[IO, List[(String, Option[String], Option[String], Boolean)]]
    )
  ] =
    Ref.of[IO, List[(String, Option[String], Option[String], Boolean)]](Nil).flatMap { seen =>
      lazy val behavior: nebflow.actor.Behavior[nebflow.actor.AgentCommand] =
        Behaviors.receiveMessage[nebflow.actor.AgentCommand] {
          // arity 对齐 ImmediateInput（text/blocks/source/eventType/sender/senderTeam/delivery/fromUser/project/windowItems）
          case nebflow.actor.AgentCommand.ImmediateInput(text, _, source, eventType, _, _, _, fromUser, _, _) =>
            seen.update(_ :+ ((text, source, eventType, fromUser))).as(behavior)
          case _ => IO.pure(behavior)
        }
      system.spawn(behavior, s"cancel-delivery-target-${System.nanoTime()}").map(ref => (ref, seen))
    }

  private def bridgeCtx(system: ActorSystem): ToolContext =
    ToolContext(projectRoot = "", sessionId = None, sharedResources = None, actorSystem = Some(system))

  test("① 取消信号 ⇒ 注入取消通告（askUserCancelled 标注 + fromUser=false）") {
    val system = ActorSystem("askcancel-deliver-pos")
    for
      (target, seen) <- targetCapturer(system)
      bridge = AskUserAnswerBridge.ref(target, items, "asknb-d1", bridgeCtx(system))
      _ <- bridge ! AskUserCancelled.signal("asknb-d1", "user-cancelled", "web")
      _ <- IO.sleep(100.millis)
      hits <- seen.get
      _ <- system.stopAll
    yield
      assertEquals(hits.size, 1, s"取消通告注入次数异常：${hits.size}")
      val (text, source, eventType, fromUser) = hits.head
      assert(text.contains(AskUserCancelled.Code) && text.contains("asknb-d1"), s"通告文本不自描述：$text")
      assertEquals(source, Some(AskUserAnswerBridge.CancelSource))
      assertEquals(eventType, Some(AskUserAnswerBridge.CancelSource))
      assertEquals(fromUser, false, "取消通告被伪装成用户发言")
    end for
  }

  test("① 零回归：派发失败信号仍零注入；真答复仍按 fromUser=true 注入") {
    val system = ActorSystem("askcancel-deliver-neg")
    for
      (target, seen) <- targetCapturer(system)
      bridge = AskUserAnswerBridge.ref(target, items, "asknb-d2", bridgeCtx(system))
      _ <- bridge ! AskUserDispatch.unavailable("asknb-d2", "Nebula")
      _ <- bridge ! List(AskUserQuestionTool.formatAnswer(items, List("yes"))) // 真答复同文本形态
      _ <- IO.sleep(100.millis)
      hits <- seen.get
      _ <- system.stopAll
    yield
      assertEquals(hits.size, 1, s"失败信号被注入或真答复丢失：${hits.map(_._1.take(60))}")
      val (_, _, _, fromUser) = hits.head
      assertEquals(fromUser, true, "真答复未按用户发言注入")
    end for
  }

  test("② 判据单点：isCancelled 与 isFailure 互斥；常规答案/空载荷/多槽恒 false") {
    val cancel = AskUserCancelled.signal("asknb-x", "user-cancelled", "web")
    val failure = AskUserDispatch.unavailable("asknb-x", "Nebula")
    // 互斥：取消信号不是失败信号，反之亦然（同槽双判永不成立）
    assertEquals(AskUserCancelled.isCancelled(cancel), true)
    assertEquals(AskUserDispatch.isFailure(cancel), false, "取消信号被误判为派发失败（面板腿会报错而非搁置）")
    assertEquals(AskUserDispatch.isFailure(failure), true)
    assertEquals(AskUserCancelled.isCancelled(failure), false, "失败信号被误判为取消（桥腿会注入它）")
    // 形态边界
    assertEquals(AskUserCancelled.isCancelled(Nil), false)
    assertEquals(AskUserCancelled.isCancelled(List("yes")), false)
    assertEquals(AskUserCancelled.isCancelled(List("a", "b")), false)
    assertEquals(AskUserCancelled.isCancelled(List(s"${AskUserCancelled.Marker} 后缀携带 requestId=asknb-x")), true)
  }

end AskUserCancelDeliverySpec
