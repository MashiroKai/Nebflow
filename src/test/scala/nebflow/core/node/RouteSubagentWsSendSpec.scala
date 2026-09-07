package nebflow.core.node

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite

/** 帧契约锁定：routeSubagentWsSend 的 project 注入（2026-09-06 作者裁定——
  * Sub-Agents 面板 Flow 徽标旁标注项目名）。
  *
  * 契约面：
  * - project=Some 时 **仅 agentStart 帧**注入 project（面板入口帧）——其余流事件
  *   （textDelta/toolStart/usageUpdate…）零载荷膨胀；
  * - project=None（缺省/Delegate 域不传）任何帧都不注入 → 前端无徽标；
  * - 既有 rootSessionId/sessionId/nodeSessionId 注入语义不回归。
  */
class RouteSubagentWsSendSpec extends CatsEffectSuite:

  private def capture(): (Ref[IO, List[Json]], Json => IO[Unit]) =
    val ref = Ref.unsafe[IO, List[Json]](Nil)
    val fn: Json => IO[Unit] = j => ref.update(_ :+ j)
    (ref, fn)

  private def first(ref: Ref[IO, List[Json]]): IO[Json] =
    ref.get.map(_.head)

  test("agentStart frame carries project when Some (live panel badge path)") {
    val (ref, send) = capture()
    val routed = NodeRunner.routeSubagentWsSend(send, "root-1", "node-ab12cd34", Some("proj-alpha"))
    for
      _ <- routed(Json.obj("type" -> "agentStart".asJson, "agentId" -> "node-ab12cd34".asJson))
      j <- first(ref)
    yield
      assertEquals(j.hcursor.get[String]("project"), Right("proj-alpha"))
      assertEquals(j.hcursor.get[String]("rootSessionId"), Right("root-1"))
      assertEquals(j.hcursor.get[String]("nodeSessionId"), Right("node-ab12cd34"))
      assertEquals(j.hcursor.get[String]("sessionId"), Right("root-1"))
  }

  test("non-agentStart frames carry NO project (zero payload bloat)") {
    val (ref, send) = capture()
    val routed = NodeRunner.routeSubagentWsSend(send, "root-1", "node-ab12cd34", Some("proj-alpha"))
    for
      _ <- routed(Json.obj("type" -> "agentTextDelta".asJson, "agentId" -> "node-ab12cd34".asJson, "delta" -> "hi".asJson))
      _ <- routed(Json.obj("type" -> "agentToolStart".asJson, "agentId" -> "node-ab12cd34".asJson, "label" -> "Bash".asJson))
      _ <- routed(Json.obj("type" -> "agentDone".asJson, "agentId" -> "node-ab12cd34".asJson))
      frames <- ref.get
    yield
      assertEquals(frames.size, 3)
      frames.foreach { f =>
        assertEquals(f.asObject.exists(_.contains("project")), false, s"unexpected project key in ${f.noSpaces.take(80)}")
      }
  }

  test("project=None injects nothing (delegate/subtask domain — no badge)") {
    val (ref, send) = capture()
    val routed = NodeRunner.routeSubagentWsSend(send, "root-1", "delegate-99", None)
    for
      _ <- routed(Json.obj("type" -> "agentStart".asJson, "agentId" -> "delegate-99".asJson))
      j <- first(ref)
    yield
      assertEquals(j.asObject.exists(_.contains("project")), false)
      // 既有路由键不回归
      assertEquals(j.hcursor.get[String]("rootSessionId"), Right("root-1"))
      assertEquals(j.hcursor.get[String]("nodeSessionId"), Right("delegate-99"))
  }

  test("pre-existing sessionId/nodeSessionId keys are preserved, project still stamped") {
    val (ref, send) = capture()
    val routed = NodeRunner.routeSubagentWsSend(send, "root-1", "dispatcher-77", Some("proj-beta"))
    for
      _ <- routed(
        Json.obj(
          "type" -> "agentStart".asJson,
          "agentId" -> "dispatcher-77".asJson,
          "sessionId" -> "own-sid".asJson,
          "nodeSessionId" -> "dispatcher-77".asJson
        )
      )
      j <- first(ref)
    yield
      assertEquals(j.hcursor.get[String]("sessionId"), Right("own-sid"))
      assertEquals(j.hcursor.get[String]("project"), Right("proj-beta"))
  }

end RouteSubagentWsSendSpec
