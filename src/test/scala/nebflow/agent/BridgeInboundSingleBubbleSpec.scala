package nebflow.agent

import cats.effect.IO
import cats.effect.Ref
import cats.effect.unsafe.implicits.global
import fs2.Stream
import io.circe.Json
import munit.CatsEffectSuite
import nebflow.actor.ActorSystem
import nebflow.actor.{AgentCommand, AgentDef}
import nebflow.bridge.BridgeOrigin
import nebflow.shared.{
  ContentBlock,
  FallbackAttempt,
  LlmHandle,
  LlmRequest,
  LlmResponse,
  PathUtil,
  StreamChunk,
  UiMessage
}

import scala.concurrent.duration.*

/**
 * 飞书/微信重复气泡批（2026-11-19）判据门。
 *
 * 作者报障逐字：「另外 飞书消息现在是会重复发送吗 你看 绿色气泡已经有一组内容了
 * 检查同类微信等会不会这样」。
 *
 * 现象 = 同一条社交通道入站渲染**两个气泡** + 落**两行** `.ui.json`：
 *   ① 作者自己的绿色用户气泡 —— `WebSocketRoutes.handleBridgeMessage` 落的显示面
 *      裸行（`UiMessage.User(displayText)`）+ 一帧 `bridgeUser`（前端绿色气泡）；
 *   ② `BRIDGE · USER-MESSAGE` 注入泡 —— `AgentActor#emitInjectedUserEvent` 的那行，
 *      正文是**模型面** payload（`<system-reminder>以下消息来自…` 来源块 + 原文）。
 *      （前端 `ask-label` 的 `text-transform: uppercase` 把它显示成全大写。）
 * 根因 = 2026-11-05「外部注入统一通道批」把可见性判据从白名单翻成总函数后，
 * `source="bridge"` 也被发了注入泡 —— 而入站**本来就有自己的显示面**。
 *
 * 本 spec 两层：
 *   · 判据层：`SelfDisplayingInboundSources` 收 `bridge`（`emitInjectedUserEvent`
 *     门口零动作）；同时钉住**模型面不受影响** —— `visibleExternalEventSource("bridge", …)`
 *     仍返回 `Some("bridge")`，即模型面 `Message.source` 没有被悄悄改成 `"external"`
 *     （踩 SelfEmittingSources 那条捷径会顺手改模型上下文，本批刻意不走）。
 *   · 真会话层（real AgentActor + 脚本化 LLM，零 spawn 零端口零 :8080 接触）：
 *     预置 handleBridgeMessage 的那一行 → 喂一条 bridge 形态的 ExternalEvent ⇒
 *     会话 `.ui.json` **只增 0 行**（总行数恒 = 1），且**没有任何一行**的正文含来源块
 *     marker；同一时刻模型面的请求正文**仍含** marker + 原文（来源归因只归模型，
 *     不归会话流）。
 *
 * Red semantics（负控）：撤掉 `emitInjectedUserEvent` 门口的
 * `SelfDisplayingInboundSources` 判据 ⇒ 真会话层立刻红：总行数 1 → 2，且第 2 行正文
 * 带 `<system-reminder>` + 「以下消息来自…」⇒ 闸**同时**抓住「注入泡」与「marker 泄漏」
 * 两样（二者同根：一个被抑制的注入泡同时是多余的第二个气泡与 marker 的呈现载体）。
 */
class BridgeInboundSingleBubbleSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 120.seconds

  // ── 与生产病历同形的一条飞书入站（作者 2026-11-19 12:38 那条） ────────────────
  private val displayText = "排查一下 为什么我看你llm流式输出这么慢？ 是因为我代理的网速慢吗？"
  private val origin = BridgeOrigin("feishu", "飞书", Some("oc_ca78ba35322269a37ab570bfda78b479"))
  private val senderId = Some("ou_d4179ebe03759b491522eb04e144f1a2")

  /** 与 `handleBridgeMessage` 逐字同源的两面（显示面裸正文 / 模型面带来源块）。 */
  private val faces = BridgeOrigin.faces(Some(origin), senderId, displayText)

  private val tempRoot: os.Path = os.pwd / "target" / "test-bridge-single-bubble"
  private val originalRoot: os.Path = PathUtil.dataRoot

  override def afterAll(): Unit = PathUtil.setDataRoot(originalRoot)

  private final class ScriptedLlm extends LlmHandle[IO]:
    val requests = Ref.unsafe[IO, List[LlmRequest]](Nil)

    def send(req: LlmRequest): IO[LlmResponse] =
      IO.raiseError(new RuntimeException("send not expected"))

    def sendStream(
      req: LlmRequest,
      onAttempt: Option[FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] =
      Stream.eval(requests.update(rs => rs :+ req)) >>
        Stream(StreamChunk.TextDelta("ok"), StreamChunk.Done(None, None))
  end ScriptedLlm

  private def requestText(req: LlmRequest): String =
    req.messages
      .map(_.content)
      .flatMap {
        case Right(blocks) => blocks.collect { case ContentBlock.Text(t) => t }
        case Left(t) => List(t)
      }
      .mkString("\n")

  private def userTexts(msgs: List[UiMessage]): List[String] =
    msgs.collect { case u: UiMessage.User => u.text }

  private def waitUntil(timeout: FiniteDuration, every: FiniteDuration = 25.millis)(cond: IO[Boolean]): IO[Unit] =
    def go(deadline: Long): IO[Boolean] =
      cond.flatMap {
        case true => IO.pure(true)
        case _ if System.currentTimeMillis() >= deadline => IO.pure(false)
        case false => IO.sleep(every) >> go(deadline)
      }
    go(System.currentTimeMillis() + timeout.toMillis).void

  // ═══════════════════════ 判据层（纯函数，改前即红） ═══════════════════════

  test("① 判据：bridge 入站在气泡唯一发射点被抑制（自带显示面）"):
    assert(
      AgentActor.SelfDisplayingInboundSources.contains("bridge"),
      "bridge 必须在 SelfDisplayingInboundSources 内：社交通道入站自带显示行（handleBridgeMessage 的裸用户行 + bridgeUser 帧）⇒ 再发注入泡就是同一条入站的两行/两泡"
    )

  test("② 模型面零改：visibleExternalEventSource 仍给 bridge 归因（不得被悄悄改成 external）"):
    assertEquals(
      AgentActor.visibleExternalEventSource("bridge", "user-message"),
      Some("bridge"),
      "visibleExternalEventSource 的返回值同时喂养注入泡标签与本门外的模型面 Message.source（AgentIdle/MailboxDrain 的 visSource.orElse(Some(\"external\"))）⇒ bridge 不得入 SelfEmittingSources（那会把模型面 source 改成 external = 对模型上下文的语义改动）"
    )

  test("③ 两面分列：显示面裸正文、模型面带来源块（bridge 入站的两个面逐字同源）"):
    assertEquals(faces.sessionDisplay, displayText, "显示面 = 裸正文")
    assert(!faces.sessionDisplay.contains("<system-reminder>"), "显示面不得携带 reminder 包裹")
    assert(!faces.sessionDisplay.contains("以下消息来自"), "显示面不得携带来源块（作者硬裁定：system reminder 永远不显示在会话中）")
    assert(faces.modelPayload.startsWith("<system-reminder>\n以下消息来自飞书"), "模型面必须带来源块（归因只归模型）")
    assert(faces.modelPayload.endsWith(displayText), "模型面必须带原文")

  // ═══════════════════════ 真会话层（端到端：一条入站 = 一行） ═══════════════════

  /** 真会话夹具读数：预置行的行数 / 喂 bridge 件之后的全部会话行 / 模型面请求正文。 */
  private final case class Readings(before: Int, afterRows: List[UiMessage], modelBody: String)

  /**
   * 真 AgentActor + 脚本化 LLM：预置 `handleBridgeMessage` 的显示面裸行 → 喂 bridge 件
   *  → 回读会话流与模型请求。零 spawn 零端口零 :8080 接触。
   */
  private def probeBridgeInbound(runName: String): IO[Readings] =
    val sid = s"bridge-single-bubble-$runName"
    val ws = tempRoot / runName
    os.remove.all(ws)
    os.makeDir.all(ws)
    val prevLlmLog = nebflow.core.LlmLogWriter.isEnabled
    nebflow.core.LlmLogWriter.setEnabled(false)
    PathUtil.setDataRoot(tempRoot / s"data-$runName")
    val system = ActorSystem(s"bridge-single-bubble-$runName")
    val llm = new ScriptedLlm
    val program = for
      resources <- SpecResources.mkResources(system, tempRoot / runName, llm).flatMap { r =>
        IO.blocking(
          os.write.over(
            PathUtil.configJsonWritePath(PathUtil.dataRoot),
            """{"safety":{"defaultMode":"auto-all"}}""",
            createFolders = true
          )
        ).as(r)
      }
      // ① 预置 `handleBridgeMessage` 的那一行（显示面裸行，逐字段同形）
      _ <- resources.sessionStore.appendUiMessages(
        sid,
        List(UiMessage.User(faces.sessionDisplay, Nil, timestamp = 1700000000000L))
      )
      before <- resources.sessionStore.getUiMessages(sid, 0, 0)
      agent <- system.spawn(
        AgentActor(
          agentDef = AgentDef(
            name = "BridgeProbe",
            description = "bridge inbound single-bubble probe",
            tools = Nil,
            systemPrompt = ""
          ),
          resources = resources,
          wsSend = (_: Json) => IO.unit,
          depth = 0,
          parentRef = None,
          sessionId = Some(sid),
          sessionName = Some("BridgeProbe"),
          safetyMode = "auto-all"
        ),
        sid
      )
      // ② 喂与 `handleBridgeMessage` 逐字同源的 bridge 形态 ExternalEvent
      _ <- agent ! AgentCommand.ExternalEvent(
        source = "bridge",
        eventType = "user-message",
        payload = faces.modelPayload,
        metadata = faces.metadata,
        correlationId = None,
        blocks = None
      )
      _ <- waitUntil(30.seconds)(llm.requests.get.map(_.nonEmpty))
      // 落盘腿是 forkTurn 异步 ⇒ 给足收敛窗口再读数（负控下第 2 行在此窗口内必现）
      _ <- IO.sleep(1.second)
      after <- resources.sessionStore.getUiMessages(sid, 0, 0)
      reqs <- llm.requests.get
    yield Readings(before._2, after._1, reqs.map(requestText).mkString("\n"))
    program.guarantee(
      system.stopAll.handleErrorWith(_ => IO.unit) *>
        IO(nebflow.core.LlmLogWriter.setEnabled(prevLlmLog)) *>
        IO(PathUtil.setDataRoot(originalRoot))
    )

  end probeBridgeInbound

  test("④ 真会话 · 闸①「注入泡」：bridge 入站只留一行（改前 2 → 改后 1）"):
    probeBridgeInbound("rows").map { r =>
      assertEquals(r.before, 1, "前置：handleBridgeMessage 的显示面裸行必须在场")
      assertEquals(
        r.afterRows.size,
        1,
        "bridge 入站不得再落第二行 —— 同一条入站只应有一行（作者的一个绿色气泡）。改前此处为 2（多一行 BRIDGE · USER-MESSAGE 注入泡行）"
      )
      assertEquals(userTexts(r.afterRows), List(displayText), "唯一一行就是显示面裸正文（逐字）")
    }

  test("⑤ 真会话 · 闸②「marker 泄漏」：会话流零来源块行，且模型面仍收到来源块与原文"):
    probeBridgeInbound("marker").map { r =>
      assertEquals(
        r.afterRows.filter(m => userTexts(List(m)).exists(_.contains("<system-reminder>"))).size,
        0,
        "会话流里不得出现携带 `<system-reminder>` 的行（模型面来源块的呈现面泄漏）"
      )
      assertEquals(
        r.afterRows.filter(m => userTexts(List(m)).exists(_.contains("以下消息来自"))).size,
        0,
        "会话流里不得出现「以下消息来自…」源块行（作者硬裁定：system reminder 永远不显示在会话中）"
      )
      assert(r.modelBody.contains("以下消息来自飞书"), "模型面必须仍收到来源块（归因只归模型）")
      assert(r.modelBody.contains(displayText), "模型面必须仍收到原文")
    }

end BridgeInboundSingleBubbleSpec
