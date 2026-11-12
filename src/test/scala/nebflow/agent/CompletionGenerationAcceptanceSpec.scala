package nebflow.agent

import cats.effect.*
import cats.syntax.all.*
import fs2.Stream
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import munit.CatsEffectSuite
import nebflow.actor.*
import nebflow.core.delegate.DelegateDeliveryReceipts
import nebflow.core.mail.CompletionDebtStore
import nebflow.shared.*
import nebflow.shared.given

import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}

import scala.concurrent.duration.*

/**
 * 跨代完成结算验收批（#2141/#2178 续 · 真实 actor 面）：真 AgentActor + 真
 * CompletionDebtStore + 真 DeliveredReceiptLedger，idle/busy 双入口四类判红锚。
 * 字段契约按真实协议（BackoffSupervisor.notifyParentAndStop 的写点）：
 * owner=correlationId、recipient=metadata.parentSessionId、
 * messages=metadata.completionMessages、generation=metadata.completionGeneration。
 *
 *  T1 idle/busy 双入口身份矩阵：双方缺失 / 各缺一方 / 空串 / 字段畸形 /
 *     错非空 recipient / 合法双投——错误件不崩 actor、不清任何
 *     owner/recipient/generation 的看护债、合法轮照常恰一次结算；busy 面
 *     追加钉「忙队列 drain 注入**不结债**」（#25 owedCompletion 停车设计面）。
 *  T2 busy 旧代反例（queueing→drain 全链）：同 owner 新代完成不得被**在队
 *     旧代件的身份键**吞掉，也不得被**旧代件的已投标记**吞掉（修复面 =
 *     DeliveredReceiptLedger.identityKeys 对携带 completionGeneration 的
 *     delegate 终态加 `#<gen>` 后缀——与 SessionMailbox terminalIdentity
 *     `subagentId#gen` 同约定；旧链无代际件保持裸 id，行为零回退）。
 *  T3 确定性换代竞态：验收已查（acceptOnce 落账）与最终 ACK（acknowledge）
 *     之间的窗口内债被换新代——store 测试接缝 testHookBeforeAcknowledge 在
 *     acknowledge 读债**之前**同步换代（生产恒 no-op），actor 级钉「旧代 ACK
 *     不得吞新代债」+ 下一轮照常结算（非 store 级等价重放）。
 *  T4 「验收已持久、ACK 未落」重放恢复（崩溃窗口 replay）：Duplicate 分支必须
 *     幂等补结算（否则债永久悬挂）；新代不被旧验收行吞；错代件的交付回执确认
 *     只认注入（injection-scoped，by design）绝不等价于债结算。
 */
class CompletionGenerationAcceptanceSpec extends CatsEffectSuite:
  override def munitIOTimeout: FiniteDuration = 180.seconds

  // ── 夹具（BusyConsoleInputInjectionSpec W2 同款机械读数，零 sleep 猜测门） ──

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
        Stream.eval(requests.update(rs => rs :+ req)) >>
          Stream
            .eval(streams.get)
            .flatMap(ss => ss.lift(n).getOrElse(Stream(StreamChunk.TextDelta("ok"), StreamChunk.Done(None, None))))
      }
  end ScriptedLlm

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
            case ContentBlock.ToolResult(_, c, _) => c
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

  /**
   * Run-boundary barrier for sequential events against one actor. The actor emits exactly one
   * sessionBusy{busy:false} frame when a run ends in true idle (drain turns emit none), and its
   * loop dequeues the next message only after the handler that emitted it returns. An event sent
   * after [[toIdle]] returns therefore reaches the idle behavior (idle acceptance leg) and is
   * never queued behind a running turn (busy drain leg). Waiting on the LLM call counter alone
   * gives no such guarantee: the call starts before the run ends.
   */
  private final class IdleBarrier:
    private val idles = Ref.unsafe[IO, Int](0)

    val wsSend: Json => IO[Unit] = frame =>
      val c = frame.hcursor
      if c.get[String]("type").toOption.contains("sessionBusy") && c.get[Boolean]("busy").toOption.contains(false) then
        idles.update(_ + 1)
      else IO.unit

    /** Runs `send`, then waits until the actor has returned to idle once more. */
    def toIdle(send: IO[Unit]): IO[Unit] =
      idles.get.flatMap(before => send *> waitUntil(15.seconds)(idles.get.map(_ > before)))
  end IdleBarrier

  /** 真实协议形 delegate 终态事件（写点契约见类头）。 */
  private def terminalEvent(
    payload: String,
    owner: Option[String],
    parentSessionId: Option[String],
    generation: Option[String] = None,
    completionMessages: Option[Json] = None
  ): AgentCommand.ExternalEvent =
    val entries: List[(String, Json)] = List(
      parentSessionId.map(p => "parentSessionId" -> Json.fromString(p)),
      generation.map(g => "completionGeneration" -> Json.fromString(g)),
      completionMessages.map(m => "completionMessages" -> m)
    ).flatten
    AgentCommand.ExternalEvent(
      source = "delegate",
      eventType = "completed",
      payload = payload,
      metadata = JsonObject(entries*),
      correlationId = owner
    )

  end terminalEvent

  private def spawnAgent(
    system: ActorSystem,
    resources: SharedResources,
    sid: String,
    wsSend: Json => IO[Unit] = _ => IO.unit
  ): IO[ActorRef[AgentCommand]] =
    system.spawn(
      AgentActor(
        AgentDef(name = "CGA", description = "completion generation acceptance probe", tools = Nil, systemPrompt = ""),
        resources,
        wsSend = wsSend,
        depth = 0,
        sessionId = Some(sid),
        sessionName = Some(sid)
      ),
      sid
    )

  private def debtOf(owner: String): IO[CompletionDebtStore.Debt] =
    CompletionDebtStore.pending(owner).flatMap {
      case Right(Some(d)) => IO.pure(d)
      case other => IO.raiseError(new AssertionError(s"debt for '$owner' unreadable/missing: $other"))
    }

  private def withTestRoot[A](name: String)(
    body: (os.Path, ActorSystem) => IO[A]
  ): IO[A] =
    val tmp = os.temp.dir(prefix = s"cga-$name-")
    val previousRoot = PathUtil.dataRoot
    val previousLog = nebflow.core.LlmLogWriter.isEnabled
    val system = ActorSystem(s"cga-$name")
    body(tmp, system).guarantee(
      system.stopAll.handleErrorWith(_ => IO.unit) *>
        IO { PathUtil.setDataRoot(previousRoot); nebflow.core.LlmLogWriter.setEnabled(previousLog) }
    )

  private def startRoot(tmp: os.Path): IO[Unit] =
    IO { PathUtil.setDataRoot(tmp); nebflow.core.LlmLogWriter.setEnabled(false) }

  // ═══════════════════ T1-idle：身份矩阵 ═══════════════════

  test("T1-idle: 缺失/空串/畸形/错recipient不崩不结，看护债原样，合法轮恰一次结算"):
    withTestRoot("idle") { (tmp, system) =>
      val llm = new ScriptedLlm(Ref.unsafe[IO, List[Stream[IO, StreamChunk]]](Nil))
      val msgs = List(Message(MessageRole.Assistant, Left("t1 idle result")))
      val guards = List("cgai-guard-both", "cgai-owner-no-recipient", "cgai-owner-malformed", "cgai-owner-wrong")
      val program = for
        _ <- startRoot(tmp)
        resources <- SpecResources.mkResources(system, tmp, llm)
        _ <- guards.traverse_(g => CompletionDebtStore.record(g, s"cgai-guard-r-${g.takeRight(12)}", msgs))
        guardGens <- guards.traverse(g => debtOf(g).map(d => (g, d.generation)))
        // ① 双方缺失（correlationId 与 parentSessionId 均缺席）
        a1 <- spawnAgent(system, resources, "cgai-host-1")
        _ <- a1 ! terminalEvent(
          "M-IDLE-BOTH-MISSING",
          owner = None,
          parentSessionId = None,
          generation = Some("gen-x"),
          completionMessages = Some(msgs.asJson)
        )
        _ <- waitUntil(15.seconds)(llm.calls.get.map(_ >= 1))
        // ② 缺 owner（parentSessionId 在场）
        a2 <- spawnAgent(system, resources, "cgai-host-2")
        _ <- a2 ! terminalEvent("M-IDLE-NO-OWNER", owner = None, parentSessionId = Some("cgai-host-2"))
        _ <- waitUntil(15.seconds)(llm.calls.get.map(_ >= 2))
        // ③ 缺 recipient（owner 有看护债——事件无 parentSessionId，跳过债面不得触碰它）
        a3 <- spawnAgent(system, resources, "cgai-host-3")
        _ <- a3 ! terminalEvent(
          "M-IDLE-NO-RECIPIENT",
          owner = Some("cgai-owner-no-recipient"),
          parentSessionId = None,
          generation = Some("gen-x"),
          completionMessages = Some(msgs.asJson)
        )
        _ <- waitUntil(15.seconds)(llm.calls.get.map(_ >= 3))
        // ④ 空串双方（= 缺失的协议残余形）
        a4 <- spawnAgent(system, resources, "cgai-host-4")
        _ <- a4 ! terminalEvent("M-IDLE-BLANK", owner = Some(""), parentSessionId = Some(""))
        _ <- waitUntil(15.seconds)(llm.calls.get.map(_ >= 4))
        // ⑤ 字段畸形：parentSessionId 非串（int）+ completionMessages 非列表（string）
        a5 <- spawnAgent(system, resources, "cgai-host-5")
        _ <- a5 ! AgentCommand.ExternalEvent(
          source = "delegate",
          eventType = "completed",
          payload = "M-IDLE-MALFORMED",
          metadata = JsonObject(
            "parentSessionId" -> Json.fromInt(42),
            "completionGeneration" -> Json.fromString("gen-x"),
            "completionMessages" -> Json.fromString("not-a-list")
          ),
          correlationId = Some("cgai-owner-malformed")
        )
        _ <- waitUntil(15.seconds)(llm.calls.get.map(_ >= 5))
        // ⑥ 错非空 recipient（看护债的真实收件方另有其人）
        a6 <- spawnAgent(system, resources, "cgai-host-6")
        _ <- a6 ! terminalEvent(
          "M-IDLE-WRONG",
          owner = Some("cgai-owner-wrong"),
          parentSessionId = Some("cgai-wrong-parent"),
          generation = Some("gen-x"),
          completionMessages = Some(msgs.asJson)
        )
        _ <- waitUntil(15.seconds)(llm.calls.get.map(_ >= 6))
        // ⑦ 合法双投：首投恰一次结算；同件重投被 intake 恰一次闸吞掉；actor 存活
        _ <- CompletionDebtStore.record("cgai-owner-legal", "cgai-host-legal", msgs)
        genLegal <- debtOf("cgai-owner-legal").map(_.generation)
        legalEvent = terminalEvent(
          "M-IDLE-LEGAL",
          owner = Some("cgai-owner-legal"),
          parentSessionId = Some("cgai-host-legal"),
          generation = Some(genLegal),
          completionMessages = Some(msgs.asJson)
        )
        a7 <- spawnAgent(system, resources, "cgai-host-legal")
        _ <- a7 ! legalEvent
        _ <- waitUntil(15.seconds)(llm.calls.get.map(_ >= 7))
        _ <- IO.sleep(500.millis)
        afterFirst <- llm.calls.get
        _ <- a7 ! legalEvent
        _ <- IO.sleep(500.millis)
        afterSecond <- llm.calls.get
        _ <- a7 ! AgentCommand.UserInput("still alive after duplicate")
        _ <- waitUntil(15.seconds)(llm.calls.get.map(_ > afterSecond))
        settled <- debtOf("cgai-owner-legal")
        sweep <- guards.traverse(g => debtOf(g).map(d => (g, d.phase, d.generation)))
      yield
        assertEquals(settled.phase, "settled", "合法轮（owner/recipient/messages/generation 全匹配）必须恰一次结算")
        assertEquals(afterSecond, afterFirst, "同件重投必须被 intake 恰一次闸吞掉（零重注入）")
        sweep.foreach { (g, phase, gen) =>
          assertEquals(phase, "deliverable", s"错误件不得清掉看护债 $g")
          assertEquals(gen, guardGens.toMap.apply(g), s"看护债 $g 的代际不得漂移")
        }
      program
    }

  // ═══════════════════ T1-busy：身份矩阵（忙队列 → 边界 drain） ═══════════════════

  test("T1-busy: 身份矩阵忙队列齐入、边界drain齐注入；忙注入停车债（#25）；看护债原样；合法轮续跑"):
    withTestRoot("busy") { (tmp, system) =>
      val gate = Deferred.unsafe[IO, Unit]
      val streams = Ref.unsafe[IO, List[Stream[IO, StreamChunk]]](
        List(Stream.eval(gate.get) >> text("turn one"), text("turn two"))
      )
      val llm = new ScriptedLlm(streams)
      val sid = "cgab-host"
      val msgs = List(Message(MessageRole.Assistant, Left("t1 busy result")))
      val guards = List("cgab-guard-both", "cgab-owner-no-recipient", "cgab-owner-malformed", "cgab-owner-wrong")
      val program = for
        _ <- startRoot(tmp)
        resources <- SpecResources.mkResources(system, tmp, llm)
        _ <- guards.traverse_(g => CompletionDebtStore.record(g, s"cgab-guard-r-${g.takeRight(12)}", msgs))
        guardGens <- guards.traverse(g => debtOf(g).map(d => (g, d.generation)))
        _ <- CompletionDebtStore.record("cgab-owner-legal", sid, msgs)
        genLegal <- debtOf("cgab-owner-legal").map(_.generation)
        actor <- spawnAgent(system, resources, sid)
        _ <- actor ! AgentCommand.UserInput("start t1-busy")
        _ <- waitUntil(15.seconds)(llm.calls.get.map(_ >= 1)) // 忙态前置读数（W2 机械）
        _ <- actor ! terminalEvent(
          "M-BUSY-BOTH-MISSING",
          owner = None,
          parentSessionId = None,
          generation = Some("gen-x"),
          completionMessages = Some(msgs.asJson)
        )
        _ <- actor ! terminalEvent("M-BUSY-NO-OWNER", owner = None, parentSessionId = Some(sid))
        _ <- actor ! terminalEvent(
          "M-BUSY-NO-RECIPIENT",
          owner = Some("cgab-owner-no-recipient"),
          parentSessionId = None,
          generation = Some("gen-x"),
          completionMessages = Some(msgs.asJson)
        )
        _ <- actor ! terminalEvent("M-BUSY-BLANK", owner = Some(""), parentSessionId = Some(""))
        _ <- actor ! terminalEvent(
          "M-BUSY-MALFORMED",
          owner = Some("cgab-owner-malformed"),
          parentSessionId = Some("cgab-r-malformed"),
          generation = Some("gen-x"),
          completionMessages = Some(Json.fromString("not-a-list"))
        )
        _ <- actor ! terminalEvent(
          "M-BUSY-WRONG",
          owner = Some("cgab-owner-wrong"),
          parentSessionId = Some("cgab-wrong-parent"),
          generation = Some("gen-x"),
          completionMessages = Some(msgs.asJson)
        )
        _ <- actor ! terminalEvent(
          "M-BUSY-LEGAL-PARKED",
          owner = Some("cgab-owner-legal"),
          parentSessionId = Some(sid),
          generation = Some(genLegal),
          completionMessages = Some(msgs.asJson)
        )
        _ <- gate.complete(())
        _ <- waitUntil(15.seconds)(llm.calls.get.map(_ >= 2))
        _ <- IO.sleep(400.millis)
        reqs <- llm.requests.get
        body2 = reqs.lift(1).map(requestText).getOrElse("")
        parked <- debtOf("cgab-owner-legal")
        sweep <- guards.traverse(g => debtOf(g).map(d => (g, d.phase, d.generation)))
        _ <- actor ! AgentCommand.UserInput("legal round continues after drain")
        _ <- waitUntil(15.seconds)(llm.calls.get.map(_ >= 3))
      yield
        List(
          "M-BUSY-BOTH-MISSING",
          "M-BUSY-NO-OWNER",
          "M-BUSY-NO-RECIPIENT",
          "M-BUSY-BLANK",
          "M-BUSY-MALFORMED",
          "M-BUSY-WRONG",
          "M-BUSY-LEGAL-PARKED"
        ).foreach(m => assert(body2.contains(m), s"忙队列 drain 注入必须携带 $m（错误件照常入回合，绝不崩 actor）。drain body=$body2"))
        assertEquals(
          parked.phase,
          "deliverable",
          "忙面 drain 只注入不结债（#25 owedCompletion 停车设计面——结算是 idle 验收腿/监督者 AlreadyPresent 确认腿的事）"
        )
        assertEquals(parked.generation, genLegal)
        sweep.foreach { (g, phase, gen) =>
          assertEquals(phase, "deliverable", s"忙面错误件不得清掉看护债 $g")
          assertEquals(gen, guardGens.toMap.apply(g), s"看护债 $g 的代际不得漂移")
        }
      program
    }

  // ═══════════════════ T2：busy 旧代反例（queueing→drain 全链） ═══════════════════

  test("T2: 同owner新代完成不得被在队旧代件身份键或旧代已投标记吞掉（busy全链）"):
    withTestRoot("t2") { (tmp, system) =>
      val gate = Deferred.unsafe[IO, Unit]
      val streams = Ref.unsafe[IO, List[Stream[IO, StreamChunk]]](
        List(
          Stream.eval(gate.get) >> text("turn one"),
          text("turn two"),
          text("turn three")
        )
      )
      val llm = new ScriptedLlm(streams)
      val sid = "cgat2-parent"
      val owner = "cgat2-owner"
      val msgs1 = List(Message(MessageRole.Assistant, Left("t2 round one result")))
      val msgs2 = List(Message(MessageRole.Assistant, Left("t2 round two result")))
      val barrier = new IdleBarrier
      val program = for
        _ <- startRoot(tmp)
        resources <- SpecResources.mkResources(system, tmp, llm)
        _ <- CompletionDebtStore.record(owner, sid, msgs1)
        gen1 <- debtOf(owner).map(_.generation)
        actor <- spawnAgent(system, resources, sid, barrier.wsSend)
        _ <- actor ! AgentCommand.UserInput("start t2")
        _ <- waitUntil(15.seconds)(llm.calls.get.map(_ >= 1)) // 忙态前置读数
        // 旧代件先入队（代际早已被新一轮取代——stale 形）
        _ <- actor ! terminalEvent(
          "T2-STALE-ROUND",
          owner = Some(owner),
          parentSessionId = Some(sid),
          generation = Some("stale-gen-9"),
          completionMessages = Some(msgs1.asJson)
        )
        // 同 owner 的**当前代**完成随后到达忙父：不得被在队旧代件的身份键判重吞掉
        _ <- actor ! terminalEvent(
          "T2-CURRENT-ROUND",
          owner = Some(owner),
          parentSessionId = Some(sid),
          generation = Some(gen1),
          completionMessages = Some(msgs1.asJson)
        )
        // Releasing the gate ends run 1; the boundary drain injects both queued events in run 2,
        // and only then does the actor return to idle.
        _ <- barrier.toIdle(gate.complete(()).void)
        reqs <- llm.requests.get
        body2 = reqs.lift(1).map(requestText).getOrElse("")
        parked <- debtOf(owner) // 忙 drain 不结债（#25）：本轮交付义务保留
        // Mail 续轮：新完成（新内容）必须铸造新代且不被旧代已投标记吞掉
        _ <- CompletionDebtStore.record(owner, sid, msgs2)
        gen2 <- debtOf(owner).map(_.generation)
        _ <- barrier.toIdle(
          actor ! terminalEvent(
            "T2-NEWGEN-ROUND",
            owner = Some(owner),
            parentSessionId = Some(sid),
            generation = Some(gen2),
            completionMessages = Some(msgs2.asJson)
          )
        )
        finalDebt <- debtOf(owner)
        reqsFinal <- llm.requests.get
        allBodies = reqsFinal.map(requestText).mkString("\n")
      yield
        assert(body2.contains("T2-STALE-ROUND"), s"旧代件必须照常入回合。drain body=$body2")
        assert(
          body2.contains("T2-CURRENT-ROUND"),
          s"同 owner 当前代完成不得被在队旧代件的身份键吞掉（busy intake 恰一次闸的跨代误判）。drain body=$body2"
        )
        assertEquals(parked.phase, "deliverable", "忙 drain 只注入不结债（#25 停车设计面）")
        assertEquals(parked.generation, gen1)
        assert(gen2.nonEmpty && gen2 != gen1, "Mail 续轮必须铸造新代")
        assert(allBodies.contains("T2-NEWGEN-ROUND"), s"新代完成不得被旧代件的已投标记吞掉（idle 重放必须进门结算）。bodies=$allBodies")
        assertEquals(finalDebt.phase, "settled", "新代完成经 idle 验收腿必须落结算")
        assertEquals(finalDebt.generation, gen2)
      program
    }

  // ═══════════════════ T3：确定性换代竞态（store 接缝，生产恒 no-op） ═══════════════════

  test("T3: 验收检查与最终ACK之间债被换新代——旧代ACK不得吞新代债，下一轮照常结算"):
    withTestRoot("t3") { (tmp, system) =>
      val llm = new ScriptedLlm(Ref.unsafe[IO, List[Stream[IO, StreamChunk]]](Nil))
      val sid = "cgat3-parent"
      val owner = "cgat3-owner"
      val msgs1 = List(Message(MessageRole.Assistant, Left("t3 round one result")))
      val msgs2 = List(Message(MessageRole.Assistant, Left("t3 continuation result")))
      val barrier = new IdleBarrier
      val program = for
        _ <- startRoot(tmp)
        resources <- SpecResources.mkResources(system, tmp, llm)
        _ <- CompletionDebtStore.record(owner, sid, msgs1)
        gen1 <- debtOf(owner).map(_.generation)
        // 接缝：acknowledge 读债之前同步换代（= 「验收已查、ACK 未落」窗口内新一轮
        // record 落盘）——一次性，只在第一次 acknowledge 触发。
        swapOnce = new AtomicBoolean(true)
        hook = () =>
          if swapOnce.compareAndSet(true, false) then CompletionDebtStore.record(owner, sid, msgs2).unsafeRunSync()
        actor <- CompletionDebtStore.beforeAcknowledgeForTest(owner)(hook).use { _ =>
          for
            actor <- spawnAgent(system, resources, sid, barrier.wsSend)
            _ <- barrier.toIdle(
              actor ! terminalEvent(
                "T3-FIRST",
                owner = Some(owner),
                parentSessionId = Some(sid),
                generation = Some(gen1),
                completionMessages = Some(msgs1.asJson)
              )
            )
          yield actor
        }
        raced <- debtOf(owner)
        _ <- barrier.toIdle(
          actor ! terminalEvent(
            "T3-SECOND",
            owner = Some(owner),
            parentSessionId = Some(sid),
            generation = Some(raced.generation),
            completionMessages = Some(msgs2.asJson)
          )
        )
        finalDebt <- debtOf(owner)
      yield
        assert(raced.generation.nonEmpty && raced.generation != gen1, "接缝必须已把债换到新代（前置自检）")
        assertEquals(raced.messages, msgs2, "竞态窗口内的换代 record 必须完整落盘")
        assertEquals(raced.phase, "deliverable", "旧代 ACK 不得吞掉/结算新代债（acknowledge 代际匹配原子守卫）")
        assertEquals(finalDebt.phase, "settled", "新代完成重放必须照常进门结算")
        assertEquals(finalDebt.generation, raced.generation)
      program
    }

  // ═══════════════════ T4：「验收已持久、ACK 未落」重放恢复 ═══════════════════

  test("T4: 崩溃窗口重放幂等补结算；新代不被旧验收行吞；错代回执确认只认注入不结债"):
    withTestRoot("t4") { (tmp, system) =>
      val llm = new ScriptedLlm(Ref.unsafe[IO, List[Stream[IO, StreamChunk]]](Nil))
      val sid = "cgat4-parent"
      val ownerA = "cgat4-owner-a"
      val msgsA = List(Message(MessageRole.Assistant, Left("t4 phase a result")))
      val msgsB = List(Message(MessageRole.Assistant, Left("t4 phase b result")))
      val ownerC = "cgat4-owner-c"
      val msgsC = List(Message(MessageRole.Assistant, Left("t4 phase c result")))
      val barrier = new IdleBarrier
      val program = for
        _ <- startRoot(tmp)
        resources <- SpecResources.mkResources(system, tmp, llm)
        // Phase A：验收已持久（acceptOnce 落账）而 ACK 未落（= acceptOnce 与
        // acknowledge 之间崩溃）——重放到达 idle 面进 Duplicate 分支。
        _ <- CompletionDebtStore.record(ownerA, sid, msgsA)
        genA <- debtOf(ownerA).map(_.generation)
        crashEvent = terminalEvent(
          "T4-A",
          owner = Some(ownerA),
          parentSessionId = Some(sid),
          generation = Some(genA),
          completionMessages = Some(msgsA.asJson)
        )
        acceptance <- CompletionDebtStore.acceptOnce(ownerA, sid, crashEvent)
        actor <- spawnAgent(system, resources, sid, barrier.wsSend)
        _ <- barrier.toIdle(actor ! crashEvent)
        afterReplay <- debtOf(ownerA)
        // Phase B：新代不被旧验收行吞（同 owner 新内容 ⇒ 新代新报）。
        _ <- CompletionDebtStore.record(ownerA, sid, msgsB)
        genB <- debtOf(ownerA).map(_.generation)
        _ <- barrier.toIdle(
          actor ! terminalEvent(
            "T4-B",
            owner = Some(ownerA),
            parentSessionId = Some(sid),
            generation = Some(genB),
            completionMessages = Some(msgsB.asJson)
          )
        )
        afterB <- debtOf(ownerA)
        // Phase C：错代件的交付回执确认只认注入（injection-scoped）——回执可 ack，
        // 债绝不被错代件结算；随后当前代照常结算。
        _ <- CompletionDebtStore.record(ownerC, sid, msgsC)
        genC <- debtOf(ownerC).map(_.generation)
        _ <- DelegateDeliveryReceipts.record(
          DelegateDeliveryReceipts.Receipt(
            delegateId = ownerC,
            parentSessionId = sid,
            eventType = "completed",
            payload = "T4-C-stale",
            metadata = JsonObject.empty,
            deliveredAt = System.currentTimeMillis()
          )
        )
        _ <- barrier.toIdle(
          actor ! terminalEvent(
            "T4-C-STALE",
            owner = Some(ownerC),
            parentSessionId = Some(sid),
            generation = Some("stale-gen-c"),
            completionMessages = Some(msgsC.asJson)
          )
        )
        afterStaleC <- debtOf(ownerC)
        receiptC <- IO { DelegateDeliveryReceipts.find(ownerC) }
        _ <- barrier.toIdle(
          actor ! terminalEvent(
            "T4-C-CURRENT",
            owner = Some(ownerC),
            parentSessionId = Some(sid),
            generation = Some(genC),
            completionMessages = Some(msgsC.asJson)
          )
        )
        afterCurrentC <- debtOf(ownerC)
      yield
        assertEquals(acceptance, CompletionDebtStore.Acceptance.New, "Phase A 前置：手工落账 = 崩溃窗口盘面")
        assertEquals(afterReplay.phase, "settled", "Duplicate 分支必须幂等补结算（崩溃窗口重放须恢复债结算）")
        assert(genB.nonEmpty && genB != genA)
        assertEquals(afterB.phase, "settled", "新代完成不被旧验收行吞（新代替换该 owner 旧行）")
        assertEquals(afterStaleC.phase, "deliverable", "错代件绝不能结算当前债")
        assertEquals(afterStaleC.generation, genC)
        assert(receiptC.exists(_.ackedAt.isDefined), "交付回执确认按设计只认注入（injection-scoped）——与债结算是两个面")
        assertEquals(afterCurrentC.phase, "settled", "当前代完成随后照常结算")
        assertEquals(afterCurrentC.generation, genC)
      program
    }

  test("T5: busy duplicate crash replay settles debt and drains exactly once"):
    withTestRoot("t5") { (tmp, system) =>
      val gate = Deferred.unsafe[IO, Unit]
      val llm = new ScriptedLlm(
        Ref.unsafe[IO, List[Stream[IO, StreamChunk]]](List(Stream.eval(gate.get) >> text("first"), text("drain")))
      )
      val sid = "cgat5-parent"
      val owner = "cgat5-owner"
      val msgs = List(Message(MessageRole.Assistant, Left("same result")))
      val barrier = new IdleBarrier
      for
        _ <- startRoot(tmp)
        resources <- SpecResources.mkResources(system, tmp, llm)
        _ <- CompletionDebtStore.record(owner, sid, msgs)
        gen <- debtOf(owner).map(_.generation)
        event = terminalEvent("T5-RECOVERY", Some(owner), Some(sid), Some(gen), Some(msgs.asJson))
        accepted <- CompletionDebtStore.acceptOnce(owner, sid, event)
        actor <- spawnAgent(system, resources, sid, barrier.wsSend)
        _ <- actor ! AgentCommand.UserInput("start busy")
        _ <- waitUntil(15.seconds)(llm.calls.get.map(_ >= 1))
        _ <- actor ! event
        _ <- waitUntil(15.seconds)(debtOf(owner).map(_.phase == "settled"))
        recovered <- debtOf(owner)
        // Releasing the gate ends run 1; the boundary drain injects the replay in run 2, then idle.
        _ <- barrier.toIdle(gate.complete(()).void)
        reqs <- llm.requests.get
        _ <- actor ! event
        _ <- barrier.toIdle(actor ! AgentCommand.UserInput("continue legally"))
        _ <- CompletionDebtStore.record(owner, sid, msgs)
        next <- debtOf(owner)
        _ <- barrier.toIdle(
          actor ! terminalEvent("T5-NEXT", Some(owner), Some(sid), Some(next.generation), Some(msgs.asJson))
        )
        _ <- waitUntil(15.seconds)(debtOf(owner).map(_.phase == "settled"))
        _ <- waitUntil(15.seconds)(llm.calls.get.map(_ >= 4))
        finalReqs <- llm.requests.get
      yield
        assertEquals(accepted, CompletionDebtStore.Acceptance.New)
        assertEquals(recovered.generation, gen)
        assert(reqs.lift(1).exists(r => requestText(r).contains("T5-RECOVERY")))
        assertEquals(finalReqs.lift(2).map(r => "T5-RECOVERY".r.findAllIn(requestText(r)).length), Some(1))
        assert(finalReqs.lastOption.exists(r => requestText(r).contains("T5-NEXT")))
        assert(next.generation != gen)
      end for
    }

  test("T6: same-content generation changes between acceptance and ACK"):
    withTestRoot("t6") { (tmp, system) =>
      val llm = new ScriptedLlm(Ref.unsafe[IO, List[Stream[IO, StreamChunk]]](Nil))
      val sid = "cgat6-parent"
      val owner = "cgat6-owner"
      val msgs = List(Message(MessageRole.Assistant, Left("identical")))
      val barrier = new IdleBarrier
      for
        _ <- startRoot(tmp)
        resources <- SpecResources.mkResources(system, tmp, llm)
        _ <- CompletionDebtStore.record(owner, sid, msgs)
        gen <- debtOf(owner).map(_.generation)
        once = new AtomicBoolean(true)
        hook = () =>
          if once.compareAndSet(true, false) then
            (CompletionDebtStore.acknowledge(owner, sid, msgs, gen) *> CompletionDebtStore.record(owner, sid, msgs))
              .unsafeRunSync(); ()
        actor <- CompletionDebtStore.beforeAcknowledgeForTest(owner)(hook).use { _ =>
          for
            a <- spawnAgent(system, resources, sid, barrier.wsSend)
            _ <- barrier.toIdle(a ! terminalEvent("T6-OLD", Some(owner), Some(sid), Some(gen), Some(msgs.asJson)))
          yield a
        }
        raced <- debtOf(owner)
        _ <- IO {
          assert(raced.generation != gen); assertEquals(raced.phase, "deliverable"); assertEquals(raced.messages, msgs)
        }
        _ <- barrier.toIdle(
          actor ! terminalEvent("T6-NEW", Some(owner), Some(sid), Some(raced.generation), Some(msgs.asJson))
        )
        _ <- waitUntil(15.seconds)(debtOf(owner).map(_.phase == "settled"))
        settled <- debtOf(owner)
      yield assertEquals(settled.generation, raced.generation)
      end for
    }

  test("T7: legacy missing generation matches only missing generation through actor"):
    withTestRoot("t7") { (tmp, system) =>
      val llm = new ScriptedLlm(Ref.unsafe[IO, List[Stream[IO, StreamChunk]]](Nil))
      val sid = "cgat7-parent"
      val msgs = List(Message(MessageRole.Assistant, Left("legacy")))
      val owners = List("cgat7-empty-empty", "cgat7-new-empty", "cgat7-empty-new")
      def makeLegacy(owner: String): IO[Unit] = IO {
        val p = tmp / "sessions" / owner / "completion-debt.json"
        val json = io.circe.parser.parse(os.read(p)).toOption.get
        os.write.over(p, json.mapObject(_.remove("generation")).noSpaces)
      }
      val barrier = new IdleBarrier
      for
        _ <- startRoot(tmp)
        resources <- SpecResources.mkResources(system, tmp, llm)
        _ <- owners.traverse_(o => CompletionDebtStore.record(o, sid, msgs))
        current <- debtOf(owners(2))
        _ <- makeLegacy(owners(0)) *> makeLegacy(owners(1))
        events = List(
          terminalEvent("T7-LEGACY", Some(owners(0)), Some(sid), None, Some(msgs.asJson)),
          terminalEvent("T7-NEW-EVENT", Some(owners(1)), Some(sid), Some("new-gen"), Some(msgs.asJson)),
          terminalEvent("T7-OLD-EVENT", Some(owners(2)), Some(sid), None, Some(msgs.asJson))
        )
        _ <- owners.zip(events).traverse_((owner, event) => CompletionDebtStore.acceptOnce(owner, sid, event))
        actor <- spawnAgent(system, resources, sid, barrier.wsSend)
        _ <- events.traverse_(event => barrier.toIdle(actor ! event))
        results <- owners.traverse(debtOf)
      yield
        assertEquals(results.map(_.phase), List("settled", "deliverable", "deliverable"))
        assertEquals(results.map(_.generation), List("", "", current.generation))
      end for
    }

  test("acknowledge test hook rejects a competing owner registration without replacing or removing the incumbent"):
    withTestRoot("hook-contention") { (tmp, _) =>
      val owner = s"hook-contention-${java.util.UUID.randomUUID()}"
      val incumbentCalls = new AtomicInteger(0)
      val contenderCalls = new AtomicInteger(0)
      val incumbent = () =>
        incumbentCalls.incrementAndGet(); ()
      val contender = () =>
        contenderCalls.incrementAndGet(); ()
      def acknowledge: IO[Boolean] = CompletionDebtStore.acknowledge(owner, "hook-recipient", Nil, "")
      for
        _ <- startRoot(tmp)
        _ <- CompletionDebtStore.beforeAcknowledgeForTest(owner)(incumbent).use { _ =>
          for
            before <- acknowledge
            rejected <- Resource
              .make(
                CompletionDebtStore.beforeAcknowledgeForTest(owner)(contender).use(_ => IO.unit).attempt.start
              )(_.cancel)
              .use(_.joinWithNever.timeout(10.seconds))
            _ <- IO {
              rejected match
                case Left(error: IllegalArgumentException) =>
                  assertEquals(error.getMessage, s"requirement failed: ack hook already registered for $owner")
                case other => fail(s"Expected the competing acquisition to be rejected, got $other")
            }
            after <- acknowledge
          yield
            assert(before && after, "No-debt acknowledgements must still invoke the registered hook")
            assertEquals(incumbentCalls.get(), 2, "Failed acquisition must neither replace nor remove the incumbent")
            assertEquals(contenderCalls.get(), 0)
        }
        _ <- CompletionDebtStore.beforeAcknowledgeForTest(owner)(contender).use { _ =>
          acknowledge.map { acked =>
            assert(acked)
            assertEquals(incumbentCalls.get(), 2)
            assertEquals(contenderCalls.get(), 1, "The contender must acquire after the incumbent releases")
          }
        }
        _ <- acknowledge
      yield
        assertEquals(incumbentCalls.get(), 2)
        assertEquals(contenderCalls.get(), 1, "Released hooks must no longer receive acknowledgements")
      end for
    }

  test("acknowledge test hooks for two overlapping owners route and release independently"):
    withTestRoot("hook-overlap") { (tmp, _) =>
      val ownerA = s"hook-overlap-a-${java.util.UUID.randomUUID()}"
      val ownerB = s"hook-overlap-b-${java.util.UUID.randomUUID()}"
      val callsA = new AtomicInteger(0)
      val callsB = new AtomicInteger(0)
      val replacementCallsA = new AtomicInteger(0)
      val hookA = () =>
        callsA.incrementAndGet(); ()
      val hookB = () =>
        callsB.incrementAndGet(); ()
      val replacementHookA = () =>
        replacementCallsA.incrementAndGet(); ()
      def acknowledge(owner: String): IO[Boolean] = CompletionDebtStore.acknowledge(owner, "hook-recipient", Nil, "")
      for
        _ <- startRoot(tmp)
        acquiredA <- Deferred[IO, Unit]
        acquiredB <- Deferred[IO, Unit]
        releaseA <- Deferred[IO, Unit]
        releaseB <- Deferred[IO, Unit]
        _ <- Resource
          .make(
            CompletionDebtStore
              .beforeAcknowledgeForTest(ownerA)(hookA)
              .use { _ =>
                acquiredA.complete(()) *> releaseA.get.timeout(10.seconds)
              }
              .start
          )(_.cancel)
          .use { holderA =>
            Resource
              .make(
                CompletionDebtStore
                  .beforeAcknowledgeForTest(ownerB)(hookB)
                  .use { _ =>
                    acquiredB.complete(()) *> releaseB.get.timeout(10.seconds)
                  }
                  .start
              )(_.cancel)
              .use { holderB =>
                for
                  _ <- acquiredA.get.timeout(10.seconds)
                  _ <- acquiredB.get.timeout(10.seconds)
                  ackA <- acknowledge(ownerA)
                  _ <- IO {
                    assert(ackA)
                    assertEquals(callsA.get(), 1)
                    assertEquals(callsB.get(), 0, "Owner A must not invoke owner B's hook")
                  }
                  ackB <- acknowledge(ownerB)
                  _ <- IO {
                    assert(ackB)
                    assertEquals(callsA.get(), 1, "Owner B must not invoke owner A's hook")
                    assertEquals(callsB.get(), 1)
                  }
                  parallelAcks <- (acknowledge(ownerA), acknowledge(ownerB)).parTupled.timeout(10.seconds)
                  _ <- IO {
                    assertEquals(parallelAcks, (true, true))
                    assertEquals(
                      callsA.get(),
                      2,
                      "Concurrent acknowledgements must each invoke the matching owner's hook once"
                    )
                    assertEquals(callsB.get(), 2)
                  }
                  _ <- releaseA.complete(())
                  _ <- holderA.joinWithNever.timeout(10.seconds)
                  _ <- acknowledge(ownerA)
                  _ <- acknowledge(ownerB)
                  _ <- IO {
                    assertEquals(callsA.get(), 2, "Owner A's hook must be removed after release")
                    assertEquals(callsB.get(), 3, "Releasing owner A must leave owner B registered")
                  }
                  _ <- CompletionDebtStore.beforeAcknowledgeForTest(ownerA)(replacementHookA).use { _ =>
                    for
                      ackA <- acknowledge(ownerA)
                      _ <- IO {
                        assert(ackA)
                        assertEquals(callsA.get(), 2)
                        assertEquals(replacementCallsA.get(), 1, "Owner A must route to its replacement hook")
                        assertEquals(callsB.get(), 3, "Reacquiring owner A must not invoke owner B's hook")
                      }
                      ackB <- acknowledge(ownerB)
                    yield
                      assert(ackB)
                      assertEquals(replacementCallsA.get(), 1, "Owner B must not invoke owner A's replacement hook")
                      assertEquals(callsB.get(), 4, "Owner B must remain registered while owner A reacquires")
                  }
                  _ <- acknowledge(ownerA)
                  _ <- acknowledge(ownerB)
                  _ <- IO {
                    assertEquals(callsA.get(), 2)
                    assertEquals(replacementCallsA.get(), 1, "Owner A's replacement hook must be removed after release")
                    assertEquals(callsB.get(), 5, "Releasing owner A's replacement must leave owner B registered")
                  }
                  _ <- releaseB.complete(())
                  _ <- holderB.joinWithNever.timeout(10.seconds)
                  _ <- acknowledge(ownerA)
                  _ <- acknowledge(ownerB)
                yield
                  assertEquals(callsA.get(), 2)
                  assertEquals(replacementCallsA.get(), 1)
                  assertEquals(callsB.get(), 5, "Owner B's hook must also be removed after release")
              }
          }
      yield ()
      end for
    }

  test("canceling an acknowledge hook holder releases its registration and permits the same owner to reacquire"):
    withTestRoot("hook-cancellation") { (tmp, _) =>
      val owner = s"hook-cancellation-${java.util.UUID.randomUUID()}"
      val canceledCalls = new AtomicInteger(0)
      val replacementCalls = new AtomicInteger(0)
      val canceledHook = () =>
        canceledCalls.incrementAndGet(); ()
      val replacementHook = () =>
        replacementCalls.incrementAndGet(); ()
      def acknowledge: IO[Boolean] = CompletionDebtStore.acknowledge(owner, "hook-recipient", Nil, "")
      for
        _ <- startRoot(tmp)
        acquired <- Deferred[IO, Unit]
        release <- Deferred[IO, Unit]
        _ <- Resource
          .make(
            CompletionDebtStore
              .beforeAcknowledgeForTest(owner)(canceledHook)
              .use { _ =>
                acquired.complete(()) *> release.get.timeout(10.seconds)
              }
              .start
          )(_.cancel)
          .use { holder =>
            for
              _ <- acquired.get.timeout(10.seconds)
              _ <- acknowledge
              _ <- IO { assertEquals(canceledCalls.get(), 1) }
              _ <- holder.cancel.timeout(10.seconds)
              outcome <- holder.join.timeout(10.seconds)
              _ <- IO {
                outcome match
                  case Outcome.Canceled() => ()
                  case other => fail(s"Expected the holder to be canceled, got $other")
              }
              _ <- acknowledge
              _ <- IO {
                assertEquals(canceledCalls.get(), 1, "Cancellation must remove the old hook before reacquisition")
              }
              _ <- CompletionDebtStore.beforeAcknowledgeForTest(owner)(replacementHook).use { _ =>
                acknowledge.map { acked =>
                  assert(acked)
                  assertEquals(canceledCalls.get(), 1)
                  assertEquals(replacementCalls.get(), 1, "The same owner must acquire a new hook after cancellation")
                }
              }
              _ <- acknowledge
            yield
              assertEquals(canceledCalls.get(), 1)
              assertEquals(replacementCalls.get(), 1, "The replacement must also release normally")
          }
      yield ()
      end for
    }

  test("acknowledge test hook is scoped and removed after failure"):
    val owner = s"hook-cleanup-${java.util.UUID.randomUUID()}"
    val hook = () => ()
    val expected = new RuntimeException("expected hook use failure")
    for
      failed <- CompletionDebtStore
        .beforeAcknowledgeForTest(owner)(hook)
        .use(_ => IO.raiseError[Unit](expected))
        .attempt
      _ <- IO { assertEquals(failed, Left(expected)) }
      _ <- CompletionDebtStore.beforeAcknowledgeForTest(owner)(hook).use(_ => IO.unit)
    yield ()

end CompletionGenerationAcceptanceSpec
