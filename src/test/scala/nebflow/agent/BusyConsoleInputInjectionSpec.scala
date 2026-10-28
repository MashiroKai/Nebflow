package nebflow.agent

import cats.effect.{Deferred, IO, Ref}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import fs2.Stream
import io.circe.Json
import io.circe.JsonObject
import munit.CatsEffectSuite
import nebflow.actor.ActorSystem
import nebflow.actor.{AgentCommand, AgentDef}
import nebflow.shared.{
  ContentBlock,
  FallbackAttempt,
  LlmHandle,
  LlmRequest,
  LlmResponse,
  PathUtil,
  StreamChunk,
  ToolCall
}

import scala.concurrent.duration.*

/**
 * 控制台输入「排队改直通」引擎语义钉（queuedirect 批，作者令 2026-10-28 11:1x
 * 「取消我们的这个输入排队功能（不删代码）……改为，直接输入给你」；11:14 补充裁定
 * 「应该不会打断你的 llm 调用吧？效果就像是我们现在的立即发送一样」）。
 *
 * 目标语义（作者 11:14 令，覆盖 brief 的「打断」描述）：busy 会话收到用户消息 ⇒
 * **不取消、不打断**在飞 LLM 调用与工具批次，消息在**回合内最近安全边界**并入当前
 * 回合——控制台直通腿在引擎侧的承载 = `ImmediateInput`（gateway immediateInput 帧 →
 * dispatchUserText 的既有投放形），Processing 态入 `pendingImmediateInputs`：
 *   · 工具批次在飞 ⇒ ToolsComplete 边界（当前工具完成后、下一次 LLM 调用前）注入；
 *   · 已无更多内部边界（在飞轮就是最后一轮）⇒ 回合末续轮注入（与旧行为等价的等待）。
 * 引擎实现**零改动**（既有行为，本 spec 是它的端到端红线）；queue 旧轨
 * （默认用户帧 → UserInput → pendingUserInputs → turn 末 head-forward 逐条独立
 * turn，2026-09-15 ub 裁定面）负对照不许被改坏。
 *
 * 真会话层（real AgentActor + 脚本化 LLM + 门文件 Bash，MailBoundaryInjectionSpec
 * 同款夹具，零 spawn 零端口零 :8080 接触）：
 *   W1 工具批次在飞窗口投入控制台形 ImmediateInput ⇒ 门释放后续轮请求（第 2 次
 *      LLM call）携带该正文（= 工具边界注入，同回合）；在飞批次正常完成（批次
 *      回显进续轮请求 = 未被砍）；第 1 轮请求不含后到消息（无重发/重放）。
 *   W2 LLM 流在飞（Deferred 门扣流，无工具边界）投入控制台形 ImmediateInput ⇒
 *      在飞轮**完整跑完**（round-1 产出保留进续轮转录）后回合末续轮请求携带该
 *      正文（= 已无内部边界，自然落到回合结束注入，不打断 LLM 调用）。
 *   W3 负对照：同窗口投入的真人 UserInput（queue 旧轨的引擎承载形）**不进**续轮
 *      请求——留队由 turn 末 head-forward 逐条独立 turn（旧语义逐字不变）。
 */
class BusyConsoleInputInjectionSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 180.seconds

  private class ScriptedLlm(streams: Ref[IO, List[Stream[IO, StreamChunk]]]) extends LlmHandle[IO]:
    val calls = Ref.unsafe[IO, Int](0)
    val requests = Ref.unsafe[IO, List[LlmRequest]](Nil) // append 序：lift(1) = 第 2 次 call

    def send(req: LlmRequest): IO[LlmResponse] =
      IO.raiseError(new RuntimeException("send not expected"))

    def sendStream(
      req: LlmRequest,
      onAttempt: Option[FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] =
      Stream.eval(calls.modify(n => (n + 1, n))).flatMap { n =>
        Stream.eval(requests.update(rs => rs :+ req)) >>
          Stream.eval(streams.get).flatMap { ss =>
            ss.lift(n).getOrElse(Stream.raiseError[IO](new RuntimeException(s"unexpected stream call #$n")))
          }
      }
  end ScriptedLlm

  private def toolCall(id: String, name: String, input: JsonObject): Stream[IO, StreamChunk] =
    Stream(StreamChunk.ToolCallChunk(ToolCall(id, name, input)), StreamChunk.Done(None, None))

  private def text(s: String): Stream[IO, StreamChunk] =
    Stream(StreamChunk.TextDelta(s), StreamChunk.Done(None, None))

  /** 请求里的全部文本段（user/assistant/tool_result 一并取——只做「含不含该正文」的判读）。 */
  private def requestText(req: LlmRequest): String =
    req.messages
      .map(_.content)
      .flatMap {
        case Right(blocks) =>
          blocks.collect {
            case ContentBlock.Text(t) => t
            case ContentBlock.ToolResult(_, content, _) => content
            case use: ContentBlock.ToolUse => use.input.toString
          }
        case Left(t) => List(t)
      }
      .mkString("\n")

  private def waitUntil(timeout: FiniteDuration, every: FiniteDuration = 25.millis)(cond: IO[Boolean]): IO[Unit] =
    def go(deadline: Long): IO[Unit] =
      cond.flatMap {
        case true => IO.unit
        case _ =>
          if System.currentTimeMillis() >= deadline then
            IO.raiseError(new AssertionError(s"waitUntil: condition not met within $timeout"))
          else IO.sleep(every) >> go(deadline)
      }
    go(System.currentTimeMillis() + timeout.toMillis)

  private val tempRoot: os.Path = os.pwd / "target" / "test-busy-console-input-injection"
  private val originalRoot: os.Path = PathUtil.dataRoot

  override def afterAll(): Unit = PathUtil.setDataRoot(originalRoot)

  private def writeAutoAllConfig: IO[Unit] =
    IO.blocking(
      os.write.over(
        PathUtil.configJsonWritePath(PathUtil.dataRoot),
        """{"safety":{"defaultMode":"auto-all"}}""",
        createFolders = true
      )
    )

  /**
   * 夹具 A：real AgentActor，turn 1 = 门文件阻塞的 Bash 批次（批次在飞窗口完全由
   * spec 掌控——DeferredCancelWireSpec / MailBoundaryInjectionSpec 同款机械读数，
   * 零 sleep 猜测），turn 2 = 纯文本答复。进入 body 时批次已被证实阻塞在飞
   * （llm.calls >= 1 且门文件不存在）。
   */
  private def withToolBatchInFlight(name: String)(
    body: (nebflow.actor.ActorRef[AgentCommand], os.Path, ScriptedLlm) => IO[Unit]
  ): IO[Unit] =
    val ws = tempRoot / name
    os.remove.all(ws)
    os.makeDir.all(ws)
    val sid = s"busy-console-$name"
    val dir = tempRoot / "sessions"
    List(dir / s"$sid.json", dir / s"$sid.ui.json").foreach(f => if os.exists(f) then os.remove(f))
    val gate = ws / "release-gate"
    val prevLlmLog = nebflow.core.LlmLogWriter.isEnabled
    nebflow.core.LlmLogWriter.setEnabled(false)
    PathUtil.setDataRoot(tempRoot / "data")
    val system = ActorSystem(s"busy-console-$name")
    val streams = Ref.unsafe[IO, List[Stream[IO, StreamChunk]]](
      List(
        toolCall(
          "tu-block",
          "Bash",
          JsonObject("command" -> Json.fromString(s"while [ ! -f '${gate}' ]; do sleep 0.05; done; echo batch-done"))
        ),
        text("second round answer")
      )
    )
    val llm = new ScriptedLlm(streams)
    val program = for
      resources <- SpecResources.mkResources(system, tempRoot, llm) <* writeAutoAllConfig
      agent <- system.spawn(
        AgentActor(
          agentDef = AgentDef(
            name = "BusyConsoleProbe",
            description = "busy console input probe",
            tools = List("Bash"),
            systemPrompt = ""
          ),
          resources = resources,
          wsSend = (_: Json) => IO.unit,
          depth = 0,
          parentRef = None,
          sessionId = Some(sid),
          sessionName = Some("BusyConsoleProbe"),
          safetyMode = "auto-all"
        ),
        sid
      )
      _ <- agent ! AgentCommand.UserInput("run the batch")
      // 在飞前置读数：LLM 已记 turn 1 且门文件尚不存在（批次真阻塞，非 sleep 猜测）
      _ <- waitUntil(30.seconds)(llm.calls.get.map(_ >= 1))
      _ <- IO.blocking(assert(!os.exists(gate), "precondition: batch still in flight when the console input arrives"))
      _ <- body(agent, gate, llm)
    yield ()
    program.guarantee(
      IO.blocking(os.write.over(gate, "go")) *>
        system.stopAll.handleErrorWith(_ => IO.unit) *>
        IO(nebflow.core.LlmLogWriter.setEnabled(prevLlmLog)) *>
        IO(PathUtil.setDataRoot(originalRoot))
    )
  end withToolBatchInFlight

  // ═══════════════════ W1：工具批次边界（同回合并入，在飞批次不砍） ═══════════════════

  test("W1: busy 会话的控制台形 ImmediateInput 在工具边界并入续轮请求（在飞批次完整跑完）"):
    withToolBatchInFlight("w1") { (agent, gate, llm) =>
      for
        // 批次在飞窗口（门未释放）：控制台直通腿的引擎承载形到达
        // （gateway immediateInput 帧 → dispatchUserText → ImmediateInput(fromUser=true)）
        _ <- agent ! AgentCommand.ImmediateInput("直通：改用快速路径", fromUser = true)
        _ <- IO.blocking(assert(!os.exists(gate), "precondition: batch still in flight"))
        _ <- IO.blocking(os.write.over(gate, "go"))
        // 门释放 → 批次完成 → ToolsComplete 边界 → 续轮（第 2 次 LLM call）
        _ <- waitUntil(30.seconds)(llm.calls.get.map(_ >= 2))
        _ <- IO.sleep(200.millis)
        reqs <- llm.requests.get
        first = reqs.headOption
        second = reqs.lift(1)
      yield
        assert(second.isDefined, "W1 前置：门释放后必须派出续轮请求（第 2 次 LLM call）")
        val body1 = first.map(requestText).getOrElse("")
        assert(
          !body1.contains("直通：改用快速路径"),
          "在飞第 1 轮请求不得含后到消息（消息在轮内到达，无重发/重放）"
        )
        val body2 = second.map(requestText).getOrElse("")
        assert(
          body2.contains("直通：改用快速路径"),
          s"W1 VIOLATED — 控制台形 ImmediateInput 必须在工具边界并入续轮请求（同回合）。second-round body=$body2"
        )
        assert(
          body2.contains("batch-done"),
          s"在飞批次必须完整跑完（不砍工具批次）：批次回显应进续轮转录。body=$body2"
        )
    }

  // ═══════════════════ W2：LLM 流在飞、无工具边界 ⇒ 回合末续轮（不打断 LLM 调用） ═══════════════════

  test("W2: Deferred 门扣住的 LLM 流在飞窗口投入 ImmediateInput ⇒ 在飞轮完整跑完后续轮携带该正文"):
    val system = ActorSystem("busy-console-w2")
    val ws = tempRoot / "w2"
    os.remove.all(ws)
    os.makeDir.all(ws)
    val sid = "busy-console-w2"
    val dir = tempRoot / "sessions"
    List(dir / s"$sid.json", dir / s"$sid.ui.json").foreach(f => if os.exists(f) then os.remove(f))
    val prevLlmLog = nebflow.core.LlmLogWriter.isEnabled
    nebflow.core.LlmLogWriter.setEnabled(false)
    PathUtil.setDataRoot(tempRoot / "data")
    val scripted = Ref.unsafe[IO, List[Stream[IO, StreamChunk]]](Nil)
    val llm = new ScriptedLlm(scripted)
    val program = for
      gate <- Deferred[IO, Unit]
      // 流脚本：第 1 次 call = 门扣住的纯文本轮（无工具 ⇒ 流结束即回合末）；第 2 次 = 续轮答复
      _ <- scripted.update(_ :+ (Stream.eval(gate.get) >> text("first answer")))
      _ <- scripted.update(_ :+ text("continuation answer"))
      resources <- SpecResources.mkResources(system, tempRoot, llm) <* writeAutoAllConfig
      agent <- system.spawn(
        AgentActor(
          agentDef = AgentDef(
            name = "BusyConsoleLlmProbe",
            description = "busy console llm-stream probe",
            tools = List(),
            systemPrompt = ""
          ),
          resources = resources,
          wsSend = (_: Json) => IO.unit,
          depth = 0,
          parentRef = None,
          sessionId = Some(sid),
          sessionName = Some("BusyConsoleLlmProbe"),
          safetyMode = "auto-all"
        ),
        sid
      )
      _ <- agent ! AgentCommand.UserInput("start a plain turn")
      _ <- waitUntil(30.seconds)(llm.calls.get.map(_ >= 1)) // 第 1 次 call 已发出，流被门扣住在飞
      // 流在飞窗口：控制台直通腿到达
      _ <- agent ! AgentCommand.ImmediateInput("直通：趁你在跑先看这个", fromUser = true)
      _ <- gate.complete(()) // 释放在飞 LLM 流（正常完成，非取消）
      // round 1 完成 → 回合末分支见 pendingImmediateInputs → 续轮（第 2 次 call）
      _ <- waitUntil(30.seconds)(llm.calls.get.map(_ >= 2))
      _ <- IO.sleep(200.millis)
      reqs <- llm.requests.get
      second = reqs.lift(1)
    yield
      assert(second.isDefined, "W2 前置：回合末续轮必须派出（第 2 次 LLM call）")
      val body2 = second.map(requestText).getOrElse("")
      assert(
        body2.contains("first answer"),
        s"在飞 LLM 轮必须完整跑完且产出保留（不被打断/丢弃）：round-1 产出应进续轮转录。body=$body2"
      )
      assert(
        body2.contains("直通：趁你在跑先看这个"),
        s"W2 VIOLATED — 无内部边界时消息应自然落到回合末续轮注入。body=$body2"
      )
    program.guarantee(
      system.stopAll.handleErrorWith(_ => IO.unit) *>
        IO(nebflow.core.LlmLogWriter.setEnabled(prevLlmLog)) *>
        IO(PathUtil.setDataRoot(originalRoot))
    )

  // ═══════════════════ W3：queue 旧轨负对照（真人 UserInput 不进边界包体） ═══════════════════

  test("W3: busy 会话的真人 UserInput（queue 旧轨引擎承载形）不进工具边界续轮——旧排队语义逐字不变"):
    withToolBatchInFlight("w3") { (agent, gate, llm) =>
      for
        // queue 旧轨：客户端「立即发送」以外的一切 busy 投放形 = 默认用户帧 → UserInput
        _ <- agent ! AgentCommand.UserInput("旧轨真人消息", clientMessageId = Some("legacy-1"), fromUser = true)
        _ <- IO.blocking(os.write.over(gate, "go"))
        _ <- waitUntil(30.seconds)(llm.calls.get.map(_ >= 2))
        _ <- IO.sleep(400.millis)
        reqs <- llm.requests.get
        second = reqs.lift(1)
      yield
        val body2 = second.map(requestText).getOrElse("")
        assert(
          !body2.contains("旧轨真人消息"),
          s"W3 VIOLATED — 真人 UserInput 必须留队（2026-09-15 逐条独立 turn 裁定面），不得进工具边界续轮。body=$body2"
        )
    }

end BusyConsoleInputInjectionSpec
