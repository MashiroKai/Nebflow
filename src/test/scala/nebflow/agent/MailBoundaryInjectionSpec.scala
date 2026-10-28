package nebflow.agent

import cats.effect.IO
import cats.effect.Ref
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import fs2.Stream
import io.circe.Json
import io.circe.JsonObject
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.ActorSystem
import nebflow.actor.{AgentCommand, AgentDef, AgentEvent}
import nebflow.shared.{
  ContentBlock,
  FallbackAttempt,
  LlmHandle,
  LlmRequest,
  LlmResponse,
  Message,
  MessageRole,
  PathUtil,
  StreamChunk,
  ToolCall
}

import scala.concurrent.duration.*

/**
 * #939（2026-10-26 作者令「Mail 注入时机提前到工具调用边界」）验证面。
 *
 * 作者令：Mail 到达正在 Processing 的 delegate 会话时，注入发生在整个回合结束后
 * （小时级长回合的补充指令干等数小时）。目标：回合进行中 → 下一个工具调用边界
 * 注入（tool result → next LLM call 之间），不中断在飞工具；多封 Mail 的既有合并
 * 窗口逻辑不变，合并后的包体在下一个边界一次性注入；零丢失（注入即从待投队列
 * 摘除）；idle 即时路径不变；到达→注入延迟可查。
 *
 * 本 spec 两层：
 *  - 纯函数层（[[TurnBoundaryDrains.isMailSupplement]] / [[drainMailPackage]] /
 *    [[mergeMailPackage]]）：判据单点、摘取保序、compaction 守卫、合并包形、
 *    真人消息与其它 replyTo 件不受影响的负对照。
 *  - 真会话层（real AgentActor + 脚本化 LLM + 门文件 Bash，DeferredCancelWireSpec
 *    同款夹具，零 spawn 零端口零 :8080 接触）：Processing 中的工具批次在飞窗口
 *    投入 mail 件 ⇒ 门释放后**续轮请求**（第 2 次 LLM call）必须携带该 mail 正文
 *    （= 边界注入，而非 turn 末）；replyTo 完成债在 turn 末结付；非 mail 排队件
 *    （负对照）不进续轮请求。
 *
 * Red semantics: 摘掉 ToolsComplete 边界的 drainMailPackage 接线 ⇒ W1 的续轮请求
 * 不再携带 mail 正文（回到 turn 末 head-forward 旧路径）即红；把判据放宽到
 * fromUser=true ⇒ 纯函数层真人负对照即红。
 */
class MailBoundaryInjectionSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 120.seconds

  /** 完成探针：计收 AgentEvent.Completed 次数的假 ActorRef。 */
  private def probeRef(counter: Ref[IO, Int]): nebflow.actor.ActorRef[AgentEvent] =
    new nebflow.actor.ActorRef[AgentEvent]:
      def path: nebflow.actor.ActorPath = nebflow.actor.ActorPath("probe", Nil)
      def !(msg: AgentEvent): IO[Unit] = msg match
        case _: AgentEvent.Completed => counter.update(_ + 1)
        case _ => IO.unit
      def ?[Reply](makeMsg: nebflow.actor.ActorRef[Reply] => AgentEvent, timeout: Option[FiniteDuration]): IO[Reply] =
        IO.raiseError(new UnsupportedOperationException("probe ref cannot ask"))

  // ═══════════════════ 纯函数层 ═══════════════════

  private def mail(
    text: String,
    sender: String = "Dispatcher",
    replyTo: Option[nebflow.actor.ActorRef[AgentEvent]] = None,
    blocks: Option[List[ContentBlock]] = None,
    queuedAtMs: Option[Long] = None
  ): AgentCommand.UserInput =
    AgentCommand.UserInput(
      text,
      replyTo = replyTo,
      source = Some("mail"),
      sender = Some(sender),
      eventType = Some("info"),
      blocks = blocks,
      fromUser = false,
      queuedAtMs = queuedAtMs
    )

  test("判据单点：mail 来源件 = 非真人 + source=mail；真人/其它来源一律不算") {
    assert(TurnBoundaryDrains.isMailSupplement(mail("x")), "mail 件必须命中判据")
    assert(
      !TurnBoundaryDrains.isMailSupplement(
        AgentCommand.UserInput("user text", clientMessageId = Some("c1"), fromUser = true)
      ),
      "真人消息（fromUser=true）绝不算 mail 件——2026-09-15 逐条独立 turn 裁定面逐字不变"
    )
    assert(
      !TurnBoundaryDrains.isMailSupplement(AgentCommand.UserInput("plain", source = None, fromUser = false)),
      "无 source 标注的服务端件（SubTask/Delegate 派发腿等）不进本豁免"
    )
    assert(
      !TurnBoundaryDrains.isMailSupplement(
        AgentCommand.UserInput("node leg", source = Some("system"), fromUser = false)
      ),
      "node: 腿（source=system）走 ImmediateInput 既有边界，不在本判据内"
    )
  }

  test("摘取：mail 件全部摘出且到达序保持；真人件与其它命令原样留队（去重不重复）") {
    val realUser: AgentCommand =
      AgentCommand.UserInput("typed by human", clientMessageId = Some("m1"), fromUser = true)
    val skill: AgentCommand =
      AgentCommand.SkillActivate("s1", "args", "sess-1", "content", "/tmp/skills/s1")
    val nodeMsg: AgentCommand =
      AgentCommand.UserInput("from node leg", source = Some("system"), fromUser = false)
    val queue = List(mail("a1"), realUser, skill, mail("a2"), nodeMsg)

    val (taken, rest) = TurnBoundaryDrains.drainMailPackage(queue, compactionPending = false)
    assertEquals(taken.map(_.text), List("a1", "a2"), "mail 件按到达序全部摘出")
    assertEquals(rest, List(realUser, skill, nodeMsg), "真人件/技能件/非 mail 件原样留队（序不变）")
    // 消费即摘除：同一队列二次摘取为空（禁重复注入的纯函数面）
    val (taken2, _) = TurnBoundaryDrains.drainMailPackage(rest, compactionPending = false)
    assertEquals(taken2, Nil, "已摘除的 mail 件不得再次出现")
  }

  test("compaction 挂起 ⇒ 零消费（持久化不丢的守卫面：注入进将被摘要替换的历史 = 丢失）") {
    val queue: List[AgentCommand] = List(mail("a1"), mail("a2"))
    val (taken, rest) = TurnBoundaryDrains.drainMailPackage(queue, compactionPending = true)
    assertEquals(taken, Nil, "compaction 窗口不得消费——队列原样保留")
    assertEquals(rest, queue, "队列逐字节不变（CompactionComplete 排空时照旧处理）")
  }

  test("合并窗口：单件 = idle 等价形（正文原样；带块件不重复正文）；多件 = 批头 + 逐件分节保序") {
    // 单件无块：正文逐字（与 idle 直投的 Message(User, Left(text)) 等价——零内容漂移）
    val single = TurnBoundaryDrains.mergeMailPackage(List(mail("only body")))
    assertEquals(single.text, "only body")
    assertEquals(single.blocks, Nil)

    // 单件带块（G3：blocks 首块即正文）：块形态直传，正文不二次拼入（禁重复）
    val withBlocks = List(ContentBlock.Text("block body"), ContentBlock.Image("aGk=", "image/png"))
    val singleBlk = TurnBoundaryDrains.mergeMailPackage(List(mail("ignored", blocks = Some(withBlocks))))
    assertEquals(singleBlk.blocks, withBlocks, "块逐字直传")
    assertEquals(singleBlk.text, "", "块形态下单件不再重复拼正文")

    // 多件：批头 + 逐件全文（到达序保持），块按件序拼接
    val b1 = List(ContentBlock.Image("aGkx", "image/png"))
    val b2 = List(ContentBlock.Image("aGky", "image/png"))
    val merged = TurnBoundaryDrains.mergeMailPackage(
      List(mail("first supplement", sender = "Disp-A"), mail("second supplement", blocks = Some(b2)))
    )
    assert(merged.text.contains("2 件"), "批头必须声明件数")
    assert(merged.text.contains("first supplement"), "件 1 正文全文在包内")
    assert(merged.text.indexOf("first supplement") < merged.text.indexOf("件 2"), "分节按到达序")
    assert(merged.text.contains("Disp-A"), "逐件发送方归属保留")
    assert(!merged.text.contains("second supplement"), "带块件的正文在块里，文本段不再重复")
    assertEquals(merged.blocks, b2, "带块件的块按序拼接进包")
  }

  test("完成债原料：摘出件携带 replyTo 供 owedCompletion 并入（结算不搁浅的判据面）") {
    // 真会话层 W2 断言 replyTo 实收 Completed；此处钉住纯函数面：摘出件原样携带
    // replyTo 与到达戳（打点原料），drain 不抹不改。
    val probe = probeRef(Ref.unsafe[IO, Int](0))
    val stamped = mail("with reply", replyTo = Some(probe), queuedAtMs = Some(123L))
    val (taken, _) = TurnBoundaryDrains.drainMailPackage(List(stamped), compactionPending = false)
    assertEquals(taken.map(_.replyTo), List(Some[nebflow.actor.ActorRef[AgentEvent]](probe)))
    assertEquals(taken.map(_.queuedAtMs), List(Some(123L)), "到达戳原样携带（延迟打点原料）")
  }

  // ═══════════════════ 真会话层（边界注入生效） ═══════════════════

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
  end ScriptedLlm

  private def toolCall(id: String, name: String, input: JsonObject): Stream[IO, StreamChunk] =
    Stream(StreamChunk.ToolCallChunk(ToolCall(id, name, input)), StreamChunk.Done(None, None))

  private def text(s: String): Stream[IO, StreamChunk] =
    Stream(StreamChunk.TextDelta(s), StreamChunk.Done(None, None))

  /** 请求里的全部文本段（user/assistant/tool 一并取——只做「含不含该正文」的判读）。 */
  private def requestText(req: LlmRequest): String =
    req.messages
      .map(_.content)
      .flatMap {
        case Right(blocks) => blocks.collect { case ContentBlock.Text(t) => t }
        case Left(t) => List(t)
      }
      .mkString("\n")

  private def waitUntil(timeout: FiniteDuration, every: FiniteDuration = 25.millis)(cond: IO[Boolean]): IO[Unit] =
    def go(deadline: Long): IO[Boolean] =
      cond.flatMap {
        case true => IO.pure(true)
        case _ if System.currentTimeMillis() >= deadline => IO.pure(false)
        case false => IO.sleep(every) >> go(deadline)
      }
    go(System.currentTimeMillis() + timeout.toMillis).void

  private val tempRoot: os.Path = os.pwd / "target" / "test-mail-boundary-injection"
  private val originalRoot: os.Path = PathUtil.dataRoot

  override def afterAll(): Unit = PathUtil.setDataRoot(originalRoot)

  /**
   * 夹具：real AgentActor，turn 1 = 门文件阻塞的 Bash 批次（批次在飞窗口完全由
   * spec 掌控——DeferredCancelWireSpec 同款机械读数，零 sleep 猜测），turn 2 =
   * 纯文本答复。进入 body 时批次已被证实阻塞在飞。
   */
  private def withTurnInFlight(name: String)(
    body: (ActorSystem, SharedResources, nebflow.actor.ActorRef[AgentCommand], os.Path, ScriptedLlm) => IO[Unit]
  ): IO[Unit] =
    val ws = tempRoot / name
    os.remove.all(ws)
    os.makeDir.all(ws)
    val sid = s"mail-boundary-$name"
    val dir = tempRoot / "sessions"
    List(dir / s"$sid.json", dir / s"$sid.ui.json").foreach(f => if os.exists(f) then os.remove(f))
    val gate = ws / "release-gate"
    val prevLlmLog = nebflow.core.LlmLogWriter.isEnabled
    nebflow.core.LlmLogWriter.setEnabled(false)
    PathUtil.setDataRoot(tempRoot / "data")
    val system = ActorSystem(s"mail-boundary-$name")
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
            name = "MailBoundaryProbe",
            description = "mail boundary probe",
            tools = List("Bash"),
            systemPrompt = ""
          ),
          resources = resources,
          wsSend = (_: Json) => IO.unit,
          depth = 0,
          parentRef = None,
          sessionId = Some(sid),
          sessionName = Some("MailBoundaryProbe"),
          safetyMode = "auto-all"
        ),
        sid
      )
      _ <- agent ! AgentCommand.UserInput("run the batch")
      // 在飞前置读数：LLM 已记 turn 1 且门文件尚不存在（批次真阻塞，非 sleep 猜测）
      _ <- waitUntil(30.seconds)(llm.calls.get.map(_ >= 1))
      _ <- IO.sleep(300.millis)
      _ <- body(system, resources, agent, gate, llm)
    yield ()
    program.guarantee(
      IO.blocking(os.write.over(gate, "go")) *>
        system.stopAll.handleErrorWith(_ => IO.unit) *>
        IO(nebflow.core.LlmLogWriter.setEnabled(prevLlmLog)) *>
        IO(PathUtil.setDataRoot(originalRoot))
    )
  end withTurnInFlight

  test("W1: Processing 中到达的 mail 件在工具边界并入续轮请求（而非干等 turn 末）") {
    withTurnInFlight("w1") { (_, _, agent, gate, llm) =>
      val completions = Ref.unsafe[IO, Int](0)
      val probe = probeRef(completions)
      for
        // 批次在飞窗口（门未释放）：mail 补充件到达
        _ <- agent ! AgentCommand.UserInput(
          "supplement: use the fast path",
          replyTo = Some(probe),
          source = Some("mail"),
          sender = Some("Dispatcher"),
          eventType = Some("info"),
          fromUser = false
        )
        // 负对照：真人消息同窗到达——必须留队（不进本边界包体）
        _ <- agent ! AgentCommand.UserInput("human follow-up", clientMessageId = Some("hum-1"), fromUser = true)
        _ <- IO.blocking(assert(!os.exists(gate), "precondition: batch still in flight when the mail arrives"))
        _ <- IO.blocking(os.write.over(gate, "go"))
        // 门释放 → 批次完成 → ToolsComplete 边界 → 续轮（第 2 次 LLM call）
        _ <- waitUntil(30.seconds)(llm.calls.get.map(_ >= 2))
        _ <- IO.sleep(200.millis)
        reqs <- llm.requests.get
        secondRound = reqs.lift(1)
      yield
        assert(
          secondRound.isDefined,
          "W1 前置：门释放后必须派出续轮请求（第 2 次 LLM call）"
        )
        val body = secondRound.map(requestText).getOrElse("")
        assert(
          body.contains("supplement: use the fast path"),
          s"W1 VIOLATED — mail 件必须在工具边界并入续轮请求（改前：干等 turn 末 head-forward，续轮请求里没有它）。second-round body=$body"
        )
        assert(
          !body.contains("human follow-up"),
          "负对照：真人消息不得进本边界包体（2026-09-15 裁定面逐字不变——留队由 turn 末处理）"
        )
      end for
    }
  }

  test("W2: 边界注入的 mail 件 replyTo 在 turn 末收付 Completed（结算不搁浅）") {
    withTurnInFlight("w2") { (_, _, agent, gate, _) =>
      val completions = Ref.unsafe[IO, Int](0)
      val probe = probeRef(completions)
      for
        _ <- agent ! AgentCommand.UserInput(
          "supplement with completion target",
          replyTo = Some(probe),
          source = Some("mail"),
          sender = Some("Dispatcher"),
          eventType = Some("info"),
          fromUser = false
        )
        _ <- IO.blocking(os.write.over(gate, "go"))
        // turn 末（第 2 轮纯文本答复完成）→ completionTargets 结付
        _ <- waitUntil(30.seconds)(completions.get.map(_ >= 1))
        n <- completions.get
      yield assert(
        n >= 1,
        "W2 VIOLATED — mail 件的 replyTo 必须并入 owedCompletion 并在本 turn 末收到 Completed（改前：mail 自己的 turn 末结付；边界注入后由完成债通道承接种算）"
      )
      end for
    }
  }

  test("W3: idle 会话的 mail 件走既有即时腿（新 turn 立即开——降级语义逐字不变）") {
    val ws = tempRoot / "w3"
    os.remove.all(ws)
    os.makeDir.all(ws)
    val sid = "mail-boundary-w3"
    val dir = tempRoot / "sessions"
    List(dir / s"$sid.json", dir / s"$sid.ui.json").foreach(f => if os.exists(f) then os.remove(f))
    val prevLlmLog = nebflow.core.LlmLogWriter.isEnabled
    nebflow.core.LlmLogWriter.setEnabled(false)
    PathUtil.setDataRoot(tempRoot / "data")
    val system = ActorSystem("mail-boundary-w3")
    val llm = new ScriptedLlm(Ref.unsafe[IO, List[Stream[IO, StreamChunk]]](List(text("idle answer"))))
    val io = for
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
          agentDef = AgentDef(name = "IdleProbe", description = "idle probe", tools = List(), systemPrompt = ""),
          resources = resources,
          wsSend = (_: Json) => IO.unit,
          depth = 0,
          parentRef = None,
          sessionId = Some(sid),
          sessionName = Some("IdleProbe"),
          safetyMode = "auto-all"
        ),
        sid
      )
      // 会话 idle（无任何在飞轮）：mail 件直达 ⇒ 既有腿立即开 turn
      _ <- agent ! AgentCommand.UserInput(
        "mail to an idle session",
        source = Some("mail"),
        sender = Some("Dispatcher"),
        eventType = Some("info"),
        fromUser = false
      )
      _ <- waitUntil(30.seconds)(llm.calls.get.map(_ >= 1))
      reqs <- llm.requests.get
    yield
      val body = reqs.headOption.map(requestText).getOrElse("")
      assert(
        body.contains("mail to an idle session"),
        "W3 VIOLATED — idle 会话的 mail 件必须走既有即时腿立即开 turn（不经过排队/边界）"
      )
    io.guarantee(
      system.stopAll.handleErrorWith(_ => IO.unit) *>
        IO(nebflow.core.LlmLogWriter.setEnabled(prevLlmLog)) *>
        IO(PathUtil.setDataRoot(originalRoot))
    )
  }
end MailBoundaryInjectionSpec
