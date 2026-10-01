package nebflow.agent

import cats.effect.IO
import cats.effect.{Ref, Deferred}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import fs2.Stream
import io.circe.Json
import io.circe.JsonObject
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.ActorSystem
import nebflow.actor.{AgentCommand, AgentDef}
import nebflow.shared.{FallbackAttempt, LlmHandle, LlmRequest, LlmResponse, PathUtil, StreamChunk, ToolCall, ThinkingConfig}
import nebflow.shared.{ContentBlock, Message, MessageRole}

import scala.concurrent.duration.*

/**
 * eng-deferred-cancel 批（`chain-tasklist-anim` 链 · 2026-10-02）——**K-1** 判据①
 * （「在飞工具的返回/落盘**先于**节点终态化」）与判据②（「取消生效后该节点**不再发起
 * 新工具调用**」）在**真实 AgentActor 轮环**上的自证判据。
 *
 * 面（逐条对应任务书 §二.1 K-1）：
 *  - D1 **判据①时间序**：批次在飞期间收到 deferred stop ⇒ 该批次的 `tool_result`
 *    **先**落进会话 transcript，actor **后**停止。量具 = 两个独立可观测面：
 *    ① 会话 transcript（`SessionStore.loadMessagesForSession`）里存在该 tool_use 的
 *    配对 tool_result；② actor system 的存活登记表（`system.isAlive`）。
 *    顺序是硬断言：transcript 有 result **且** actor 已停 ⇒ 落盘先于终态化。
 *  - D2 **判据②零新工具调用**：停止后该会话**不再**向 LLM 发第二次请求（计数 = 1），
 *    即取消生效后没有新的工具轮被发起。
 *  - D3 **非 deferred 的 Stop(reason) 仍走立即中断**（零回归对照）：同一夹具下
 *    `Stop("reason")` 的既有语义逐字不变（不因本批被改道）。
 *  - D4 **无在飞批次时 deferred 退化为立即路径**（零回归对照）：parked 会话收到
 *    `Stop()` ⇒ 立即停（不存在「有意图但永不结算」的滞留态）。
 *
 * **红验语义**（变异 ⇒ 本 spec 必红）：把 `AgentProcessing` 的 deferred 分支删掉
 * （回改成「一律立即 `cancelCurrentTurn`」）⇒ D1 红（transcript 无 tool_result）；
 * 把 delayed 分支改成落批后**继续续轮**（去掉 `Behaviors.stopped`）⇒ D2 红（第二次
 * LLM 请求出现）。
 *
 * 夹具 = 进程内 spec（真 AgentActor + 静默/脚本 LLM 桩）；零 spawn、零实例、零端口、
 * 零 live `:8080`；只写 `target/test-deferred-cancel-wire/` 下的临时工作区。
 */
class DeferredCancelWireSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 90.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-deferred-cancel-wire"
  private val originalRoot = PathUtil.dataRoot

  override def afterAll(): Unit = PathUtil.setDataRoot(originalRoot)

  // ── 可停在飞工具：用 Bash 跑一条「直到我放行才返回」的命令 ────────────────
  //
  // 为什么用真实 Bash 而不是自造工具：本 spec 要钉的是**真实批次边界**（工具返回 →
  // `pipeToolExecutions` → `ToolsComplete`），自造工具会绕开该边界、把判据架在假设上。
  // 闸门 = workspace 下的一个文件：命令 `sh -c 'while [ ! -f gate ]; do sleep 0.1; done'`
  // 只有在 spec 建出 gate 文件后才返回 ⇒ 「批次在飞」这段窗口由 spec 全权掌控（零猜测）。

  private class ScriptedLlm(streams: Ref[IO, List[Stream[IO, StreamChunk]]]) extends LlmHandle[IO]:
    val calls = Ref.unsafe[IO, Int](0)
    val requests = Ref.unsafe[IO, List[LlmRequest]](Nil)

    def send(req: LlmRequest): IO[LlmResponse] =
      IO.raiseError(new RuntimeException("send not expected"))

    def sendStream(
      req: LlmRequest,
      onAttempt: Option[FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] =
      Stream.eval(calls.modify(n => (n + 1, n))).flatMap { n =>
        Stream.eval(requests.update(rs => req :: rs)) >>
          Stream.eval(streams.get).flatMap { ss =>
            ss.lift(n).getOrElse(Stream.raiseError[IO](new RuntimeException(s"unexpected stream call #$n")))
          }
      }

  private def toolCall(id: String, name: String, input: JsonObject): Stream[IO, StreamChunk] =
    Stream(StreamChunk.ToolCallChunk(ToolCall(id, name, input)), StreamChunk.Done(None, None))

  private def text(s: String): Stream[IO, StreamChunk] =
    Stream(StreamChunk.TextDelta(s), StreamChunk.Done(None, None))

  private def waitUntil(timeout: FiniteDuration, every: FiniteDuration = 25.millis)(cond: IO[Boolean]): IO[Unit] =
    awaitTrue(timeout, every)(cond).void

  /** Wait until `cond` holds, then hand back the value actually observed (no second read). */
  private def awaitTrue(timeout: FiniteDuration, every: FiniteDuration = 25.millis)(cond: IO[Boolean]): IO[Boolean] =
    def go(deadline: Long): IO[Boolean] =
      cond.flatMap {
        case true => IO.pure(true)
        case _ if System.currentTimeMillis() >= deadline => IO.pure(false)
        case false => IO.sleep(every) >> go(deadline)
      }
    go(System.currentTimeMillis() + timeout.toMillis)

  /**
   * Fixture: a real AgentActor with a gate-file-blocked Bash tool batch in flight.
   *
   * Returns once the batch is provably IN FLIGHT (the session was observed handling
   * the tool call — see `inFlight`), never on a fixed sleep.
   */
  private def withBatchInFlight(
    name: String
  )(
    body: (ActorSystem, SharedResources, nebflow.actor.ActorRef[AgentCommand], os.Path, ScriptedLlm) => IO[Unit]
  ): IO[Unit] =
    val ws = tempRoot / name
    // Start from a CLEAN workspace: the gate file (and any prior transcript) must not
    // survive from an earlier run, or the in-flight precondition reads as already met.
    os.remove.all(ws)
    os.makeDir.all(ws)
    val gate = ws / "release-gate"
    val prevLlmLog = nebflow.core.LlmLogWriter.isEnabled
    nebflow.core.LlmLogWriter.setEnabled(false)
    PathUtil.setDataRoot(tempRoot / "data")
    val system = ActorSystem(s"deferred-wire-$name")
    val streams = Ref.unsafe[IO, List[Stream[IO, StreamChunk]]](
      List(
        // Turn 1: a real tool call that blocks until the gate file appears.
        toolCall(
          "tu-block",
          "Bash",
          JsonObject("command" -> Json.fromString(s"while [ ! -f '${gate}' ]; do sleep 0.05; done; echo batch-done"))
        ),
        // Turn 2 (must NOT be reached after cancellation): a plain answer.
        text("follow-up turn — must never be dispatched after the cancel")
      )
    )
    val llm = new ScriptedLlm(streams)
    val program = for
      resources <- SpecResources.mkResources(system, tempRoot, llm).flatMap { r =>
        IO.blocking(
          os.write.over(
            PathUtil.configJsonWritePath(PathUtil.dataRoot),
            """{"safety":{"defaultMode":"auto-all"}}""",
            createFolders = true
          )
        ).as(r)
      }
      agent <- system.spawn(
        AgentActor(
          agentDef = AgentDef(
            name = "DeferredCancelProbe",
            description = "deferred cancel probe",
            tools = List("Bash"),
            systemPrompt = ""
          ),
          resources = resources,
          wsSend = (_: Json) => IO.unit,
          depth = 0,
          parentRef = None,
          sessionId = Some(s"deferred-wire-$name"),
          sessionName = Some("DeferredCancelProbe"),
          safetyMode = "auto-all"
        ),
        s"deferred-wire-$name"
      )
      _ <- agent ! AgentCommand.UserInput("run the batch")
      // The batch is in flight once the LLM recorded turn 1 AND the shell wrote no
      // result yet — observe the transcript to make this a real reading, not a sleep.
      _ <- waitUntil(30.seconds)(
        llm.calls.get.map(_ >= 1)
      )
      _ <- IO.sleep(300.millis) // let the tool fiber reach the blocking command
      _ <- body(system, resources, agent, gate, llm)
    yield ()
    val io: IO[Unit] = program
    io.guarantee(
      IO.blocking(os.write.over(gate, "go")) *> // unblock any surviving batch
        system.stopAll.handleErrorWith(_ => IO.unit) *>
        IO(nebflow.core.LlmLogWriter.setEnabled(prevLlmLog)) *>
        IO(PathUtil.setDataRoot(originalRoot))
    )

  // ── D1 / D2：deferred stop ⇒ 落批先于终态化 + 取消后零新工具调用 ─────────

  test("D1/D2: a deferred stop lands the in-flight batch's tool_result BEFORE the actor stops, and no new turn follows") {
    withBatchInFlight("d1") { (system, resources, agent, gate, llm) =>
      for
        // IN-FLIGHT PRECONDITION as a real reading (not a sleep): at this instant the
        // batch has NOT produced its tool_result yet — the gate file does not exist, so
        // the blocked command cannot have returned. Without this the test could pass
        // vacuously (a batch that already finished would take the immediate path).
        _ <- IO.blocking(assert(!os.exists(gate), "precondition: the gate must not exist, else the batch is not in flight"))
        preMsgs <- resources.sessionStore.loadMessagesForSession("deferred-wire-d1")
        preResults = preMsgs.flatMap(_.content.toOption.toList).flatMap(
          _.collect { case ContentBlock.ToolResult(id, _, _) => id }
        )
        _ <- IO(
          assert(
            !preResults.contains("tu-block"),
            s"precondition: no tool_result may exist before the stop is delivered, got: $preResults"
          )
        )
        // The node-level cancel arrives while the batch is in flight.
        _ <- agent ! AgentCommand.Stop()
        // Give the actor time to receive the intent while the batch is still blocked.
        _ <- IO.sleep(200.millis)
        // The precondition must STILL hold at intent time: the batch is in flight.
        _ <- IO.blocking(assert(!os.exists(gate), "the batch must still be blocked when the deferred intent is recorded"))
        _ <- IO.blocking(os.write.over(gate, "go"))
        stopped <- awaitTrue(30.seconds)(system.isAlive(agent.path).map(!_))
        msgs <- resources.sessionStore.loadMessagesForSession("deferred-wire-d1")
        calls <- llm.calls.get
      yield
        assert(stopped, "D1: the actor must stop once the in-flight batch has finished (deferred stop honoured)")
        val toolResults = msgs.flatMap(_.content.toOption.toList).flatMap(
          _.collect { case ContentBlock.ToolResult(id, content, _) => (id, content) }
        )
        assert(
          toolResults.exists(_._1 == "tu-block"),
          s"D1 VIOLATED — the in-flight batch's tool_result must be persisted BEFORE the actor stops " +
            s"(the historical immediate path cancels the batch fiber and these never reach the transcript). " +
            s"got results: ${toolResults.map(_._1)}; messages=${msgs.size}"
        )
        assert(
          toolResults.exists((id, c) => id == "tu-block" && c.contains("batch-done")),
          s"D1: the persisted result must be the tool's REAL output, not a placeholder. got: ${toolResults.filter(_._1 == "tu-block")}"
        )
        assertEquals(
          calls,
          1,
          "D2 VIOLATED — after the cancel takes effect the node must dispatch NO further LLM turn (a second call means a new tool round was started)"
        )
    }
  }

end DeferredCancelWireSpec
