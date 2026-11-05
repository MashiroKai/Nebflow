package nebflow.agent

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import fs2.Stream
import io.circe.{Json, JsonObject}
import munit.CatsEffectSuite
import nebflow.actor.ActorSystem
import nebflow.actor.{AgentCommand, AgentDef, AgentKind, AgentRecord}
import nebflow.core.delegate.DelegateNotify
import nebflow.core.project.{FlowMapStore, NodeDef, NodeEngine, NodeLifecycle}
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
 * 三条注入腿的**注入时机**红线（midturn-injection-timing 批，2026-11-04）。
 *
 * 作者报障（逐字）：「现在我的输入还有问题，现在我输入的消息发给你的行为是在你的
 * 轮次结束的时候才注入，而不是在你的两次 LLM 调用之间（工具调用间隙，随工具结果
 * 一起给你）的这种即时注入。包括你发给 delegate 的 mail，以及 dispatcher 发给
 * node 的信息，都是采用这一套统一的行为。」
 *
 * 本 spec 钉的是**实测时机**，不是设计意图：三条腿（用户控制台输入 / delegate
 * notify(Mail) / node 消息）各自在「工具批次在飞」窗口投入，读数是**下一次 LLM
 * 请求**（脚本化 LLM 记录的第 2 次 call 的 messages）是否**已经带上**该正文。
 * 由于第 2 次请求是在工具批次完成的边界处发出的，正文出现在其中即证明注入发生在
 * **本回合内**的工具边界（回合末注入在时序上不可能追溯进一条已发出的请求）。
 *
 * 五条断言（brief 固定编号）：
 *   - L1 用户腿：`AgentCommand.ImmediateInput(fromUser = true)`（= gateway
 *     immediateInput 帧 → WebSocketRoutes.dispatchUserText:2508 的既有承载形）。
 *   - L2 Mail delegate 腿：`DelegateNotify.inject`（source=delegate-notify，唯一
 *     生产投递函数：core/delegate/DelegateNotify.scala:54-78）。
 *   - L2b Mail 补充件腿（同族第二承载形，作者口中的「发给 delegate 的 mail」）：
 *     `UserInput(source="mail")` —— MailTool 续聊腿 :1265 / 邮件腿 :2385 的形，
 *     边界消费点 = TurnBoundaryDrains.drainMailPackage。
 *   - L3 node 腿：`NodeEngine.sendNodeMessage` 对 running 节点（sessionRef 指向本
 *     在飞会话）。
 *   - L4 幂等：同一条消息跨两个工具边界只注入一次。
 *   - L5 回落：回合已结束（idle）时消息仍送达且只送达一次。
 *
 * 夹具纪律（BusyConsoleInputInjectionSpec / MailBoundaryInjectionSpec 同款）：真
 * `AgentActor` + 脚本化 LLM + 门文件 Bash，零 spawn 零端口零 :8080 接触；工具的
 * 在飞窗口完全由门文件掌控（无 sleep 猜测；唯一的固定 sleep 只用于让批次确实进入
 * 执行（300ms）并已被「门未释放」断言交叉证实）。
 */
class MidTurnInjectionTimingSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 300.seconds

  // ── 脚本化 LLM（calls/requests 都是可读事实面）────────────────────────────
  private class ScriptedLlm(streams: Ref[IO, List[Stream[IO, StreamChunk]]]) extends LlmHandle[IO]:
    val calls    = Ref.unsafe[IO, Int](0)
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

  /** 门文件阻塞的 Bash 工具轮（批次在飞窗口 = 门未释放的全部时间）。 */
  private def gatedBash(id: String, gate: os.Path, body: String): Stream[IO, StreamChunk] =
    toolCall(
      id,
      "Bash",
      JsonObject("command" -> Json.fromString(s"while [ ! -f '${gate}' ]; do sleep 0.05; done; $body"))
    )

  /** 请求里的全部文本段（text / tool_result / tool_use 一并取——只判「含不含」）。 */
  private def requestText(req: LlmRequest): String =
    req.messages
      .map(_.content)
      .flatMap {
        case Right(blocks) => blocks.collect {
          case ContentBlock.Text(t)                   => t
          case ContentBlock.ToolResult(_, content, _) => content
          case use: ContentBlock.ToolUse              => use.input.toString
        }
        case Left(t) => List(t)
      }
      .mkString("\n")

  private def occurrences(hay: String, needle: String): Int =
    if needle.isEmpty then 0 else hay.sliding(needle.length).count(_ == needle)

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

  private val tempRoot: os.Path     = os.pwd / "target" / "test-midturn-injection-timing"
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

  private final case class Fixture(
    system: ActorSystem,
    resources: SharedResources,
    agent: nebflow.actor.ActorRef[AgentCommand],
    llm: ScriptedLlm,
    ws: os.Path,
    sid: String,
    gate1: os.Path,
    gate2: os.Path,
    roundComplete: Ref[IO, Int]
  )

  /**
   * 夹具：一个三边界回合 ——
   *   round 1 = Bash(gate1) 工具批次；round 2 = Bash(gate2) 工具批次；round 3 = 纯文本答复（回合末）。
   * 进入 body 时 round-1 批次已被证实阻塞在飞（llm.calls >= 1 且 gate1 不存在）。
   */
  private def withGatedTurn(name: String)(body: Fixture => IO[Unit]): IO[Unit] =
    val ws = tempRoot / name
    os.remove.all(ws)
    os.makeDir.all(ws)
    val sid = s"midturn-$name"
    val dir = tempRoot / "sessions"
    List(dir / s"$sid.json", dir / s"$sid.ui.json").foreach(f => if os.exists(f) then os.remove(f))
    val gate1 = ws / "gate1.release"
    val gate2 = ws / "gate2.release"
    val prevLlmLog = nebflow.core.LlmLogWriter.isEnabled
    nebflow.core.LlmLogWriter.setEnabled(false)
    PathUtil.setDataRoot(tempRoot / "data")
    val system = ActorSystem(s"midturn-$name")
    val streams = Ref.unsafe[IO, List[Stream[IO, StreamChunk]]](
      List(
        gatedBash("tu-g1", gate1, "echo batch-one-done"),
        gatedBash("tu-g2", gate2, "echo batch-two-done"),
        text("final answer"),
        text("post-turn answer")
      )
    )
    val llm = new ScriptedLlm(streams)
    val roundComplete = Ref.unsafe[IO, Int](0)
    val wsSend: Json => IO[Unit] = (j: Json) =>
      IO(j.hcursor.get[String]("type").toOption.contains("roundComplete"))
        .flatMap(isRC => if isRC then roundComplete.update(_ + 1) else IO.unit)
    val program = for
      resources <- SpecResources.mkResources(system, tempRoot, llm) <* writeAutoAllConfig
      agent <- system.spawn(
        AgentActor(
          agentDef = AgentDef(
            name = "MidTurnProbe",
            description = "mid-turn injection timing probe",
            tools = List("Bash"),
            systemPrompt = ""
          ),
          resources = resources,
          wsSend = wsSend,
          depth = 0,
          parentRef = None,
          sessionId = Some(sid),
          sessionName = Some("MidTurnProbe"),
          safetyMode = "auto-all"
        ),
        sid
      )
      // 统一注册表登记（L2 的 DelegateNotify.inject 以注册表行为 live 判据；生产由
      // AgentStarter/NodeStarter 注册，本夹具手工登记同一形）
      _ <- resources.agentRegistry.update(_ + (sid -> AgentRecord(sid, agent, AgentKind.Root, sid)))
      _ <- agent ! AgentCommand.UserInput("run the gated batches")
      _ <- waitUntil(30.seconds)(llm.calls.get.map(_ >= 1))
      _ <- IO.sleep(300.millis) // 让 round-1 的工具批次真正进入执行（下方断言交叉证实门未释放）
      _ <- IO.blocking(assert(!os.exists(gate1), "precondition: batch 1 must still be in flight"))
      _ <- body(Fixture(system, resources, agent, llm, ws, sid, gate1, gate2, roundComplete))
    yield ()
    program.guarantee(
      IO.blocking(os.write.over(gate1, "go")) *>
        IO.blocking(os.write.over(gate2, "go")) *>
        system.stopAll.handleErrorWith(_ => IO.unit) *>
        IO(nebflow.core.LlmLogWriter.setEnabled(prevLlmLog)) *>
        IO(PathUtil.setDataRoot(originalRoot))
    )
  end withGatedTurn

  /** 批次 1 的边界：释放门 → ToolsComplete 边界 → 续轮（第 2 次 LLM call）已在飞/完成。 */
  private def releaseAndAwaitBoundary1(fx: Fixture): IO[Unit] =
    IO.blocking(os.write.over(fx.gate1, "go")) *>
      waitUntil(60.seconds)(fx.llm.calls.get.map(_ >= 2)) *>
      IO.sleep(200.millis)

  // ══════════════ L1 用户腿 ══════════════
  test("L1 用户腿: 工具批次在飞时投入的控制台形 ImmediateInput 必在本回合的下一次 LLM 请求里"):
    withGatedTurn("l1") { fx =>
      val body = "L1-USER-改用快速路径"
      for
        _ <- fx.agent ! AgentCommand.ImmediateInput(body, fromUser = true)
        _ <- IO.blocking(assert(!os.exists(fx.gate1), "precondition: batch still in flight when the input arrives"))
        _ <- releaseAndAwaitBoundary1(fx)
        reqs <- fx.llm.requests.get
        rc <- fx.roundComplete.get
        first = reqs.lift(0).map(requestText).getOrElse("")
        second = reqs.lift(1).map(requestText).getOrElse("")
        _ <- IO(
          println(
            s"[MTIT L1] calls=${reqs.size} roundCompleteBeforeBoundary2=$rc " +
              s"inFirst=${first.contains(body)} inSecond=${second.contains(body)} " +
              s"secondHasBatchEcho=${second.contains("batch-one-done")}"
          )
        )
      yield
        assert(!first.contains(body), s"L1: 在飞第 1 轮请求不得含后到消息。body=$first")
        assert(
          second.contains("batch-one-done"),
          "L1 前置：第 2 次请求必须是本次工具批次的续轮（含批次回显）"
        )
        assert(
          second.contains(body),
          s"L1 VIOLATED — 用户腿必须在工具边界并入本回合续轮请求（不是下一回合）。second=$second"
        )
        assertEquals(rc, 0, "L1: 边界注入不得伴随 roundComplete（回合未结束）——出现即证明落到了回合末")
    }

  // ══════════════ L1b 用户腿的第二承载形（附件/引用/队列 drain 帧）═════════════
  // WebSocketRoutes.handleMessage 的 **typeless 腿**（前端 input.js:139-150 的默认用户
  // 帧：带附件/引用/引言的输入，以及 console.inputMode='queue' 的排队 drain 腿）投的是
  // `AgentCommand.UserInput(..., clientMessageId, Some(blocks), chatWidth)`
  // （WebSocketRoutes.scala:2240-2248），**不是** ImmediateInput。
  // 本断言按作者期望写（工具边界即注入）；今天预期**红**——pendingUserInputs 自
  // 2026-09-15 裁定起在工具边界一律不消费（AgentProcessing.scala:738-757 注释面），
  // 只有 mail 来源件有豁免（TurnBoundaryDrains.isMailSupplement）。
  test("L1b 用户腿(附件/引用承载形): typeless 帧的 UserInput 也必须在工具边界注入"):
    withGatedTurn("l1b") { fx =>
      val body = "L1B-TYPELESS 带引用的追问"
      for
        // typeless 腿的引擎承载形（WebSocketRoutes.scala:2241-2248 逐字形态）
        _ <- fx.agent ! AgentCommand.UserInput(
          body,
          replyTo = None,
          clientMessageId = Some("mtit-l1b-cm1"),
          blocks = Some(List(ContentBlock.Text(body))),
          chatWidth = 900
        )
        _ <- IO.blocking(assert(!os.exists(fx.gate1), "precondition: batch still in flight"))
        _ <- releaseAndAwaitBoundary1(fx)
        atBoundary <- fx.llm.requests.get.map(_.map(requestText))
        // 走完回合（第二边界 + 回合末），看这条消息最终落在哪里
        _ <- IO.blocking(os.write.over(fx.gate2, "go"))
        _ <- waitUntil(60.seconds)(fx.llm.calls.get.map(_ >= 3))
        _ <- IO.sleep(400.millis)
        afterTurn <- fx.llm.requests.get.map(_.map(requestText))
        _ <- IO(
          println(
            s"[MTIT L1b] callsAfterBoundary1=${atBoundary.size} inBoundaryRequest=${atBoundary.lift(1).map(_.contains(body))} " +
              s"callsAfterTurnEnd=${afterTurn.size} landing=${afterTurn.zipWithIndex.filter((t, _) => t.contains(body)).map((_, i) => i).mkString(",")}"
          )
        )
      yield assert(
        atBoundary.lift(1).exists(_.contains(body)),
        "L1b VIOLATED — typeless 帧（附件/引用/队列 drain）的用户输入必须在工具边界并入本回合续轮请求；" +
          s"实际落在 turn 之后（landingIndex=${afterTurn.zipWithIndex.filter((t, _) => t.contains(body)).map((_, i) => i).mkString(",")}）= 回合末 head-forward。"
      )
    }

  // ══════════════ L2 Mail delegate 腿（DelegateNotify.inject）══════════════
  test("L2 Mail(delegate)腿: DelegateNotify.inject 的流程通知必在本回合的下一次 LLM 请求里"):
    withGatedTurn("l2") { fx =>
      val body = "L2-DELEGATE-NOTIFY 节点失败通知"
      for
        live <- DelegateNotify.inject(fx.resources.agentRegistry, fx.sid, body)
        _ <- IO.blocking(assert(!os.exists(fx.gate1), "precondition: batch still in flight"))
        _ <- releaseAndAwaitBoundary1(fx)
        reqs <- fx.llm.requests.get
        second = reqs.lift(1).map(requestText).getOrElse("")
        _ <- IO(
          println(
            s"[MTIT L2] delegateInjectLive=$live calls=${reqs.size} " +
              s"inSecond=${second.contains(body)} secondHasBatchEcho=${second.contains("batch-one-done")}"
          )
        )
      yield
        assert(live, "L2 前置：注册表有行 ⇒ DelegateNotify.inject 必须报 true（已入队）")
        assert(
          second.contains(body),
          s"L2 VIOLATED — delegate-notify 注入必须在工具边界并入本回合续轮请求。second=$second"
        )
    }

  // ══════════════ L2b Mail 补充件腿（MailTool 的 UserInput(source=mail) 承载形）═════════════
  test("L2b Mail 补充件腿: UserInput(source=mail) 必在本回合的下一次 LLM 请求里"):
    withGatedTurn("l2b") { fx =>
      val body = "L2B-MAIL-补充件 use the fast path"
      for
        _ <- fx.agent ! AgentCommand.UserInput(
          body,
          source = Some("mail"),
          sender = Some("Dispatcher"),
          eventType = Some("info"),
          fromUser = false
        )
        _ <- IO.blocking(assert(!os.exists(fx.gate1), "precondition: batch still in flight"))
        _ <- releaseAndAwaitBoundary1(fx)
        reqs <- fx.llm.requests.get
        second = reqs.lift(1).map(requestText).getOrElse("")
        _ <- IO(println(s"[MTIT L2b] calls=${reqs.size} inSecond=${second.contains(body)}"))
      yield assert(
        second.contains(body),
        s"L2b VIOLATED — mail 补充件必须在工具边界并入本回合续轮请求（mail-injected-at-tools-complete 面）。second=$second"
      )
    }

  // ══════════════ L3 node 腿（NodeEngine.sendNodeMessage → running 会话）═════════════
  test("L3 node 腿: sendNodeMessage 对 running 节点的注入必在本回合的下一次 LLM 请求里"):
    withGatedTurn("l3") { fx =>
      val body = "L3-NODE 立刻停下并报告"
      val nodeId = "n-l3"
      for
        store <- FlowMapStore.open("midturn-l3", fx.ws.toString)
        _ <- store.mutate(s =>
          s.copy(nodes = s.nodes + (nodeId -> NodeDef(
            id = nodeId,
            name = "n-l3",
            agent = "general",
            status = NodeLifecycle.Running,
            sessionRef = Some(fx.sid),
            task = Some("l3 probe"),
            createdAt = System.currentTimeMillis() - 1000L
          )))
        )
        engine = new NodeEngine(
          store,
          fx.system,
          fx.resources,
          wsSendFn = (_: Json) => IO.unit,
          workspace = fx.ws.toString,
          rootSessionId = "midturn-root",
          projectName = "midturn-l3",
          emitEvent = (_: String, _: String, _: Json) => IO.unit
        )
        res <- engine.sendNodeMessage(nodeId, body)
        _ <- IO.blocking(assert(!os.exists(fx.gate1), "precondition: batch still in flight"))
        _ <- releaseAndAwaitBoundary1(fx)
        reqs <- fx.llm.requests.get
        second = reqs.lift(1).map(requestText).getOrElse("")
        _ <- IO(
          println(
            s"[MTIT L3] sendNodeMessage=${res.map(_.take(50))} calls=${reqs.size} " +
              s"inSecond=${second.contains(body)}"
          )
        )
      yield
        assert(res.isRight, s"L3 前置：running 节点的投递必须成立，得到 $res")
        assert(
          second.contains(body),
          s"L3 VIOLATED — node 消息必须在工具边界并入本回合续轮请求。second=$second"
        )
    }

  // ══════════════ L4 幂等：跨两个工具边界只注入一次 ══════════════
  test("L4 幂等: 同一条消息跨连续两个工具边界只注入一次"):
    withGatedTurn("l4") { fx =>
      val body = "L4-IDEMPOTENT 只出现一次"
      for
        _ <- fx.agent ! AgentCommand.ImmediateInput(body, fromUser = true)
        _ <- releaseAndAwaitBoundary1(fx)
        beforeSecondBoundary <- fx.llm.requests.get.map(_.map(requestText))
        _ <- IO.blocking(os.write.over(fx.gate2, "go"))
        _ <- waitUntil(60.seconds)(fx.llm.calls.get.map(_ >= 3))
        _ <- IO.sleep(200.millis)
        after <- fx.llm.requests.get.map(_.map(requestText))
        _ <- IO(
          println(
            s"[MTIT L4] calls=${after.size} afterBoundary1=${beforeSecondBoundary.lift(1).map(s => occurrences(s, body))} " +
              s"afterBoundary2=${after.lift(2).map(s => occurrences(s, body))}"
          )
        )
      yield
        assertEquals(
          after.lift(1).map(s => occurrences(s, body)).getOrElse(0),
          1,
          "L4: 第一个边界注入一次（且仅一次）"
        )
        assertEquals(
          after.lift(2).map(s => occurrences(s, body)).getOrElse(0),
          1,
          s"L4 VIOLATED — 跨第二个边界不得重复注入（转录里必须恰一次，实际见日志）。calls=${after.size}"
        )
    }

  // ══════════════ L5 回落：回合已结束（idle）时送达且只送达一次 ══════════════
  test("L5 回落: 回合结束后到达的消息仍然送达（新回合）且只送达一次"):
    withGatedTurn("l5") { fx =>
      val body = "L5-FALLBACK 回合已结束后的消息"
      for
        // 走完整个回合（两个边界 + 回合末纯文本轮）
        _ <- IO.blocking(os.write.over(fx.gate1, "go"))
        _ <- waitUntil(60.seconds)(fx.llm.calls.get.map(_ >= 2))
        _ <- IO.blocking(os.write.over(fx.gate2, "go"))
        _ <- waitUntil(60.seconds)(fx.llm.calls.get.map(_ >= 3))
        _ <- IO.sleep(400.millis) // 回合末收口（idle）
        idleRc <- fx.roundComplete.get
        _ <- fx.agent ! AgentCommand.ImmediateInput(body, fromUser = true)
        _ <- waitUntil(60.seconds)(fx.llm.calls.get.map(_ >= 4))
        _ <- IO.sleep(500.millis) // 观察窗：确认不会再派发第 5 次
        reqs <- fx.llm.requests.get
        texts = reqs.map(requestText)
        _ <- IO(
          println(
            s"[MTIT L5] roundCompleteBeforeIdle=$idleRc calls=${reqs.size} " +
              s"inTurnEndRound=${texts.lift(2).map(s => s.contains(body))} inNextTurn=${texts.lift(3).map(s => s.contains(body))} " +
              s"deliveries=${texts.map(s => occurrences(s, body)).mkString(",")}"
          )
        )
      yield
        assert(
          texts.lift(2).map(s => !s.contains(body)).getOrElse(false),
          "L5: 回合末那一轮的请求本就不该含后到消息（它先于消息发出）"
        )
        assertEquals(
          texts.lift(3).map(s => occurrences(s, body)).getOrElse(0),
          1,
          s"L5 VIOLATED — 回合结束后到达的消息必须送达到下一次请求且恰一次。calls=${reqs.size}"
        )
        assertEquals(reqs.size, 4, "L5: 不得出现第 5 次 LLM call（重复投递）")
    }

  // ══════════════ G 组：判据层的机械读数（解释「落到回合末」的两个门）══════════════
  test("G1: 边界判据的两个门（compaction 挂起 / 队首一件）—— 纯函数面读数"):
    val a = AgentCommand.ImmediateInput("A", fromUser = true)
    val b = AgentCommand.ImmediateInput("B", fromUser = true)
    val q = List(a, b)
    val (normal, rest) = TurnBoundaryDrains.drainHeadExpanded(q, compactionPending = false)(x => List(x))
    val (held, heldRest) = TurnBoundaryDrains.drainHeadExpanded(q, compactionPending = true)(x => List(x))
    val mailQ: List[AgentCommand] = List(
      AgentCommand.UserInput("m1", source = Some("mail"), fromUser = false),
      AgentCommand.UserInput("m2", source = Some("mail"), fromUser = false)
    )
    val (mailNormal, _) = TurnBoundaryDrains.drainMailPackage(mailQ, compactionPending = false)
    val (mailHeld, _) = TurnBoundaryDrains.drainMailPackage(mailQ, compactionPending = true)
    println(
      s"[MTIT G1] headOnlyDrained=${normal.map(_.text)} stayed=${rest.map(_.text)} " +
        s"compactionPendingDrained=${held.size} mailPackageDrained=${mailNormal.size} " +
        s"mailCompactionPendingDrained=${mailHeld.size}"
    )
    assertEquals(normal.map(_.text), List("A"), "G1: 每个边界只消费队首一件（其余留队）")
    assertEquals(rest.map(_.text), List("B"), "G1: 队尾留在队列里")
    assertEquals(held.size, 0, "G1: compaction 挂起 ⇒ 边界零消费（回落回合末）")
    assertEquals(heldRest.map(_.text), List("A", "B"), "G1: compaction 挂起时队列逐字保留")
    assertEquals(mailNormal.map(_.text), List("m1", "m2"), "G1: mail 补充件整包摘出（豁免面）")
    assertEquals(mailHeld.size, 0, "G1: compaction 挂起时 mail 件亦零消费")

end MidTurnInjectionTimingSpec
