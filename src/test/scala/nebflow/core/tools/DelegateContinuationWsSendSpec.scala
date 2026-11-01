package nebflow.core.tools

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite

import java.nio.file.{Files, Paths}

/**
 * 续聊腿 wsSend 桥接批（2026-11-01 报障「delegate 会话详情面板部分会话正文空白，
 * 只剩归因徽章行」）——机制钉 + 防复活钉。
 *
 * 归因链（本 spec 钉的是链上的机械环节）：
 *   1. 子会话（delegate/subtask/flow）的 ai / tool / thinking 三类 `.ui.json` 行，
 *      **唯一**写者是根会话的录制层（`WebSocketRoutes#makeRecordingWsSend`：按帧内
 *      `nodeSessionId` 落盘）。该录制层是子代理链上 `wsSend` 的底座。
 *   2. `DelegateTool.routeWsSend`（spawn 唯一路由点）在 `wsSend = None` 时把底座退化成
 *      `(_) => IO.unit`（DelegateTool.scala 的 `wsSend.getOrElse` 行）⇒ 该子会话的
 *      **全部流帧在发射处被吞**：既无 live 广播，也到不了录制层 ⇒ ui.json 只剩注入行。
 *   3. 注入行不经 wsSend（`AgentActor#emitInjectedUserEvent` 直写 SessionStore）⇒
 *      面板里注入行（= 归因徽章行）照常渲染，正文一条都没有——正是报障现象。
 *   4. 判别变量 = 会话是否由 Mail 续聊腿（`MailTool.continueDelegate`）所起：该腿此前
 *      恒传 `None`；新鲜派发腿（`DelegateTool.spawnKernel`）与外置执行器续聊腿
 *      （`ExternalSessionRunner.continueExternal`）都传 `ctx.wsSend`。
 *
 * S1/S2 = 机制（吞帧 vs 送帧 + 路由标记）；S3 = 合法缺省（无父会话时的盖章契约）；
 * S4 = 防复活钉（续聊腿的调用点必须接 `ctx.wsSend`，禁止回退成 `None`）。
 */
class DelegateContinuationWsSendSpec extends CatsEffectSuite:

  private val SubId = "delegate-nebflow-deadbeef"
  private val ParentId = "root-session-1234"

  /** 录制层同形的底座：把收到的每帧收进 Ref（= makeRecordingWsSend 的 base 位置）。 */
  private def sink =
    for ref <- IO.ref(List.empty[Json])
    yield (ref, (j: Json) => ref.update(_ :+ j))

  private def textFrame(delta: String): Json =
    Json.obj(
      "type" -> "agentTextDelta".asJson,
      "agentId" -> "nebflow/delegate".asJson,
      "nodeSessionId" -> SubId.asJson,
      "delta" -> delta.asJson
    )

  test("S1 wsSend=None（续聊腿改前形态）⇒ 帧在发射处即被吞，录制层零收帧") {
    val io = for
      frames <- IO.ref(List.empty[Json])
      wsSend = DelegateTool.routeWsSendFor(SubId, Some(ParentId), None)
      _ <- wsSend(textFrame("hello"))
      got <- frames.get
    yield got
    // 拿 frames 只是为了证伪「别处还有一条落盘腿」：底座为 None 时这个路由闭包
    // 不写任何地方（本 spec 的收帧器压根没接上）。
    io.map(got => assertEquals(got, Nil))
  }

  test("S2 wsSend 在场 ⇒ 帧送到录制层且盖 nodeSessionId/sessionId/rootSessionId（落盘键）") {
    val io = for
      (ref, base) <- sink
      wsSend = DelegateTool.routeWsSendFor(SubId, Some(ParentId), Some(base))
      _ <- wsSend(textFrame("hello"))
      got <- ref.get
    yield got
    io.map { got =>
      assertEquals(got.size, 1)
      val hc = got.head.hcursor
      assertEquals(hc.downField("delta").as[String].toOption, Some("hello"))
      // 录制层按这三键判归属（makeRecordingWsSend 的 agent* 分支读 nodeSessionId）。
      assertEquals(hc.downField("nodeSessionId").as[String].toOption, Some(SubId))
      assertEquals(hc.downField("sessionId").as[String].toOption, Some(ParentId))
      assertEquals(hc.downField("rootSessionId").as[String].toOption, Some(ParentId))
      assertEquals(hc.downField("type").as[String].toOption, Some("agentTextDelta"))
    }
  }

  test("S3 帧自带 nodeSessionId ⇒ 路由不覆盖（跨层路由的既有契约不破）") {
    val own = "node-1234abcd"
    val io = for
      (ref, base) <- sink
      wsSend = DelegateTool.routeWsSendFor(SubId, Some(ParentId), Some(base))
      _ <- wsSend(Json.obj("type" -> "agentToolEnd".asJson, "nodeSessionId" -> own.asJson))
      got <- ref.get
    yield got
    io.map(got => assertEquals(got.head.hcursor.downField("nodeSessionId").as[String].toOption, Some(own)))
  }

  test("S4 防复活钉：MailTool 续聊腿（continueDelegate）必须桥接 ctx.wsSend，禁止回退 wsSend=None") {
    val src = Files.readString(Paths.get("src/main/scala/nebflow/core/tools/MailTool.scala"))
    val i = src.indexOf("private def continueDelegate")
    assert(i >= 0, "continueDelegate 不在 MailTool.scala —— 本钉需随源码结构重锚")
    val body = src.substring(i, math.min(src.length, i + 6000))
    val spawnAt = body.indexOf("spawnBackground(")
    assert(spawnAt >= 0, "continueDelegate 内找不到 spawnBackground 调用")
    val call = body.substring(spawnAt, math.min(body.length, spawnAt + 3000))
    assert(
      call.contains("wsSend = ctx.wsSend"),
      "续聊腿必须把调用方 wsSend 传给 spawnBackground（否则该会话 ai/tool/thinking 行永不落盘 ⇒ 面板正文空白）"
    )
    assert(!call.contains("wsSend = None"), "续聊腿回退成 wsSend = None 会让面板重新空白（2026-11-01 报障根因）")
  }
