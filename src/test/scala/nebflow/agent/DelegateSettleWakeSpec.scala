package nebflow.agent

import cats.effect.IO
import cats.effect.Ref
import cats.effect.unsafe.implicits.global
import fs2.Stream
import io.circe.Json
import io.circe.JsonObject
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.{AgentRecord, ActorRef, ActorSystem, AgentCommand, AgentDef, AgentEvent, AgentKind}
import nebflow.shared.{FallbackAttempt, LlmHandle, LlmRequest, LlmResponse, PathUtil, StreamChunk}

import scala.concurrent.duration.*

/**
 * **dispatcher 终态批 2026-11-05 · 真会话层**（作者报障「dispatcher 模式 delegate
 * 无法正常结束 + 永远被 base-moved 吵」）。
 *
 * 病灶（[[AgentFinishTurn.resolveSettleTargets]] 类注有全链）：
 * base-moved / flow-notify 走 `ImmediateInput`（**没有 replyTo 槽**），idle 腿把它转成
 * `UserInput(replyTo = None)`；上一回合收尾又把 `owedCompletion` 清空 ⇒ 该回合收尾时
 * 完成目标为空 ⇒ `AgentEvent.Completed` **零发信** ⇒ BackoffSupervisor 收不到完成事件
 * ⇒ `DelegateTerminal.onCompleted` 从不运行 ⇒ 会话挂在 Idle，下一次 base-moved 再唤醒
 * 一轮，永不收敛（现场实证见本批报告：`delegate-nebflow-b657f259` 15:18:10 / 15:25:06 /
 * 15:30:24 三次 turn-complete，`chains/1571.json` 零 `terminal-verdict` 行）。
 *
 * 本 spec 用真 `AgentActor` + 脚本化 LLM 把这条线钉成红绿：
 *   - W1（delegate 会话，注册表带 supervisorRef）：第一回合（带 replyTo）有完成信号；
 *     **第二回合由无 replyTo 的 ImmediateInput 唤醒**——改前零发信，改后必须照发；
 *   - W2（负控，根会话无 supervisorRef）：同一唤醒腿**仍零发信**（不凭空造监督者，
 *     根会话行为逐字不变）。
 *
 * Red semantics：把 `emitCompletion` 退回旧的 `completionTargets.traverse_(…)`（即
 * 不查 `supervisorRef` 兜底）⇒ W1 的第二发永远不来，`waitUntil(... >= 2)` 超时即红；
 * W2 是反向闸：把兜底放宽到「任意会话」或「造一个目标」⇒ W2 变红。
 */
class DelegateSettleWakeSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 120.seconds

  private val tempRoot: os.Path     = os.pwd / "target" / "test-delegate-settle-wake"
  private val originalRoot: os.Path = PathUtil.dataRoot

  override def afterAll(): Unit = PathUtil.setDataRoot(originalRoot)

  /** 完成探针：只数 `AgentEvent.Completed`（其他事件忽略）。 */
  private def probeRef(counter: Ref[IO, Int]): ActorRef[AgentEvent] =
    new ActorRef[AgentEvent]:
      def path: nebflow.actor.ActorPath = nebflow.actor.ActorPath("settle-probe", Nil)
      def !(msg: AgentEvent): IO[Unit] = msg match
        case _: AgentEvent.Completed => counter.update(_ + 1)
        case _                       => IO.unit
      def ?[Reply](makeMsg: ActorRef[Reply] => AgentEvent, timeout: Option[FiniteDuration]): IO[Reply] =
        IO.raiseError(new UnsupportedOperationException("probe ref cannot ask"))

  /** 逐次取脚本流（turn 1 / turn 2 …）；每次调用记一条请求。 */
  private class ScriptedLlm(streams: Ref[IO, List[Stream[IO, StreamChunk]]]) extends LlmHandle[IO]:
    val calls = Ref.unsafe[IO, Int](0)
    def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new RuntimeException("send not expected"))
    def sendStream(
      req: LlmRequest,
      onAttempt: Option[FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] =
      Stream.eval(calls.modify(n => (n + 1, n))).flatMap { n =>
        Stream.eval(streams.get).flatMap { ss =>
          ss.lift(n).getOrElse(Stream.raiseError[IO](new RuntimeException(s"unexpected stream call #$n")))
        }
      }

  private def text(s: String): Stream[IO, StreamChunk] =
    Stream(StreamChunk.TextDelta(s), StreamChunk.Done(None, None))

  private def waitUntil(timeout: FiniteDuration, every: FiniteDuration = 25.millis)(cond: IO[Boolean]): IO[Boolean] =
    def go(deadline: Long): IO[Boolean] =
      cond.flatMap {
        case true => IO.pure(true)
        case false =>
          if System.currentTimeMillis() >= deadline then IO.pure(false)
          else IO.sleep(every) >> go(deadline)
      }
    go(System.currentTimeMillis() + timeout.toMillis)

  /**
   * 夹具：真 AgentActor（两回合脚本：turn 1 / turn 2 各一段纯文本）+ 可选注册表行。
   * `supervisor` = 注册表 `supervisorRef`（`None` = 根会话形态）。
   */
  private def withSession(name: String, sid: String, supervisor: Option[ActorRef[AgentEvent]])(
    body: (SharedResources, ActorRef[AgentCommand], Ref[IO, Int]) => IO[Unit]
  ): IO[Unit] =
    os.remove.all(tempRoot / name)
    os.makeDir.all(tempRoot / name)
    // 幂等墓碑必须清：`input-landing` 把上一轮 `clientMessageId` 记成 taken，重跑会
    // 让 turn 1 被「duplicate identity … turn skipped (idempotency key)」直接吞掉。
    os.remove.all(tempRoot / "data")
    val prevLlmLog = nebflow.core.LlmLogWriter.isEnabled
    nebflow.core.LlmLogWriter.setEnabled(false)
    PathUtil.setDataRoot(tempRoot / "data")
    val system = ActorSystem(s"delegate-settle-$name")
    val streams = Ref.unsafe[IO, List[Stream[IO, StreamChunk]]](
      List(text("turn one answer"), text("turn two answer"))
    )
    val llm = new ScriptedLlm(streams)
    val program = for
      resources <- SpecResources.mkResources(system, tempRoot / name, llm).flatMap { r =>
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
            name = "SettleWakeProbe",
            description = "settle wake probe",
            tools = Nil,
            systemPrompt = ""
          ),
          resources = resources,
          wsSend = (_: Json) => IO.unit,
          depth = 0,
          parentRef = None,
          sessionId = Some(sid),
          sessionName = Some("SettleWakeProbe"),
          safetyMode = "auto-all"
        ),
        sid
      )
      _ <- resources.agentRegistry.update(
             _ + (sid -> AgentRecord(
               sessionId = sid,
               ref = agent,
               kind = if supervisor.isDefined then AgentKind.Delegate else AgentKind.Root,
               rootSessionId = sid,
               supervisorRef = supervisor
             ))
           )
      // 夹具自检：注册表里这一行的 supervisorRef 必须在我们投放后仍可读（否则 W1 的
      // 断言测的就不是被测代码，而是夹具没装上）。
      _ <- resources.agentRegistry.get.map { m =>
             assertEquals(
               m.get(sid).flatMap(_.supervisorRef).isDefined,
               supervisor.isDefined,
               s"夹具自检失败：注册表行 supervisorRef 与投放不一致（keys=${m.keys.toList}）"
             )
           }
      completions <- Ref.of[IO, Int](0)
      probe = probeRef(completions)
      // turn 1：带 replyTo 的唤醒（= supervisor 派发腿的形态）⇒ 必有完成信号
      _ <- agent ! AgentCommand.UserInput("kick the first turn", Some(probe), Some(s"$sid-t1"))
      ok1 <- waitUntil(30.seconds)(completions.get.map(_ >= 1))
      _ = assert(ok1, s"turn 1（带 replyTo）必须发出 Completed —— 夹具基线：$sid")
      _ <- body(resources, agent, completions)
    yield ()
    program.guarantee(
      system.stopAll.handleErrorWith(_ => IO.unit) *>
        IO(nebflow.core.LlmLogWriter.setEnabled(prevLlmLog)) *>
        IO(PathUtil.setDataRoot(originalRoot))
    )
  end withSession

  /** base-moved / flow-notify 的真实形态：`ImmediateInput`，**没有 replyTo**。 */
  private def notifyWake(agent: ActorRef[AgentCommand]): IO[Unit] =
    agent ! AgentCommand.ImmediateInput(
      text = "[base-moved] main advanced 1 time(s) since you were dispatched",
      source = Some("delegate-notify"),
      eventType = Some("flow-notify")
    )

  // ── W1：delegate 会话被无 replyTo 的通知唤醒 ⇒ 收尾仍向监督者发 Completed ──
  test("W1: 无 replyTo 的 [base-moved] 唤醒回合 ⇒ 完成信号照发监督者（改前零发信 = 永不终判）") {
    val sid = "delegate-nebflow-settlewake"
    val counterProbe = Ref.unsafe[IO, Int](0)
    val sup = probeRef(counterProbe)
    withSession("w1", sid, Some(sup)) { (resources, agent, completions) =>
      for
        pre <- AgentFinishTurn.resolveSettleTargets(resources.agentRegistry, Some(sid), Nil)
        keys <- resources.agentRegistry.get.map(_.keys.toList)
        _ = assert(pre.nonEmpty, s"夹具自检：唤醒前解析器就该认出监督者（keys=$keys sid=$sid）")
        _ <- notifyWake(agent)
        // 监督者探针（`sup`）与 replyTo 探针是两个计数器：唤醒回合**没有** replyTo，
        // 所以它只能落到监督者那一侧——这正是改后的判据。
        got2 <- waitUntil(20.seconds)(counterProbe.get.map(_ >= 1))
        toSup <- counterProbe.get
        toReplyTo <- completions.get
      yield
        assert(
          got2,
          s"delegate 会话被无 replyTo 的通知唤醒后，收尾必须仍向监督者发 Completed（改前为 0 次）——监督者收到 $toSup 次；keys=$keys"
        )
        assertEquals(toSup, 1, s"唤醒回合恰好一发，落到监督者：$toSup")
        assertEquals(toReplyTo, 1, s"replyTo 腿（turn 1）只有那一发，唤醒回合不得再回到这里：$toReplyTo")
    }
  }

  // ── W2：负控——根会话（无 supervisorRef）同一唤醒腿仍零发信 ────────────────
  test("W2（负控）：根会话（无 supervisorRef）同一唤醒腿 ⇒ 第二回合仍零发信（不凭空造目标）") {
    val sid = "root-settlewake"
    withSession("w2", sid, None) { (_, agent, completions) =>
      for
        _ <- notifyWake(agent)
        // 给足窗口：改坏成「任意会话都补目标」时这一等会看到第 2 发
        _ <- IO.sleep(2.seconds)
        n <- completions.get
      yield assertEquals(n, 1, s"根会话的完成信号面逐字不变（只有 turn 1 那一次）：$n")
    }
  }
