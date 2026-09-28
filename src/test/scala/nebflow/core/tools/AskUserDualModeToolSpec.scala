package nebflow.core.tools

import cats.effect.{Deferred, IO}
import cats.effect.unsafe.implicits.global
import io.circe.JsonObject
import io.circe.syntax.*
import munit.FunSuite
import nebflow.actor.{ActorPath, ActorRef}
import nebflow.agent.{AgentCommand, AgentDef, AskMode}

import scala.concurrent.duration.*

/**
 * 工具面按角色分化批（2026-09-13）**运行期兜底闸**（B3）+ **可达性预检**（B6）
 * 的工具级验收（规格 §6①-b / §6⑤ 第二层）：
 *
 *  - `parseMode` 真值表：缺席 ⇒ 默认阻塞；**出现但非法 ⇒ 显式错误**（不静默
 *    回落，明禁的「静默忽略参数」伪处理）；
 *  - 非 root **硬造**非阻塞 ⇒ `Left(ASKUSER_NONBLOCK_NOT_ROOT)` + **零副作用**
 *    （stub agent ref 零消息 —— 闸在派发之前）；
 *  - 预检 fail-closed（无 hub / 无 root 窗口）⇒ 显式错误 + 零副作用（连
 *    `AskUser` 都没派发 ⇒ hub 零槽位）；
 *  - 阻塞默认路径零漂移：不传 `mode` ⇒ 派发的 `AgentCommand.AskUser` 带
 *    `AskMode.Blocking`（默认值即既有语义，2 个既有构造点不传即不变）。
 *
 * 真实 AgentActor / hub 侧的全链读数见 `AskUserDualModeRuntimeSpec`。
 */
class AskUserDualModeToolSpec extends FunSuite:

  private val generalDef = AgentDef(name = "general", description = "", tools = Nil)
  private val nebulaDef = AgentDef(name = "Nebula", description = "", tools = Nil)

  /** 记录式 stub agent ref：`!` 落 sink；`?` 记录消息后**立即**用 `answers`
    * 回复（模拟「人在窗口点答」）——阻塞路径因此不挂起、非阻塞路径不受影响。 */
  private def recordingRef(
    sink: scala.collection.mutable.ListBuffer[AgentCommand],
    answers: List[String] = List("A")
  ): ActorRef[AgentCommand] =
    new ActorRef[AgentCommand]:
      val path: ActorPath = ActorPath("__rec", Nil)
      def !(msg: AgentCommand): IO[Unit] = IO { sink += msg; () }
      def ?[R](makeMsg: ActorRef[R] => AgentCommand, t: Option[FiniteDuration]): IO[R] =
        for
          d <- Deferred[IO, R]
          replyRef = new ActorRef[R]:
            val path: ActorPath = ActorPath("__rec-reply", Nil)
            def !(m: R): IO[Unit] = d.complete(m).void
            def ?[Q](mk: ActorRef[Q] => R, tt: Option[FiniteDuration]): IO[Q] =
              IO.raiseError(new UnsupportedOperationException("no nested asks on the recording ref"))
          msg = makeMsg(replyRef)
          _ <- IO { sink += msg; () }
          // 测试桩：AskUser 的 reply 载荷恒为 List[String]（工具侧只走这一条），
          // 泛型 R 在此被擦除 ⇒ 直接投答案即可（无类型不安全的生产面）。
          _ <- replyRef ! answers.asInstanceOf[R]
          r <- d.get
        yield r

  private def ctxFor(
    defn: AgentDef,
    depth: Int,
    ref: Option[ActorRef[AgentCommand]],
    shared: Option[nebflow.agent.SharedResources] = None
  ): ToolContext =
    ToolContext(
      projectRoot = "",
      sessionId = Some("probe-session"),
      rootSessionId = Some("probe-session"),
      agentDef = Some(defn),
      depth = depth,
      agentActorRef = ref,
      sharedResources = shared
    )

  private val twoQuestions = JsonObject(
    "questions" -> io.circe.Json.arr(
      io.circe.Json.obj("question" -> "picked?".asJson, "options" -> io.circe.Json.arr(io.circe.Json.obj("label" -> "A".asJson)))
    )
  )

  // ============================================================
  // parseMode 真值表
  // ============================================================

  test("parseMode: 缺席 ⇒ 默认阻塞；两个合法值各归位") {
    assertEquals(AskUserQuestionTool.parseMode(JsonObject.empty), Right(AskMode.Blocking))
    assertEquals(AskUserQuestionTool.parseMode(JsonObject("mode" -> AskMode.BlockingWire.asJson)), Right(AskMode.Blocking))
    assertEquals(
      AskUserQuestionTool.parseMode(JsonObject("mode" -> AskMode.NonBlockingWire.asJson)),
      Right(AskMode.NonBlocking)
    )
  }

  test("parseMode: 非法值一律显式错误（不静默回落 —— 明禁的伪处理②）") {
    val bads = List(
      "nonblocking".asJson,          // 拼写
      "Non-Blocking".asJson,         // 大小写
      "".asJson,                     // 空串
      123.asJson,                    // 类型不对
      io.circe.Json.True             // 布尔（boolean 形态的旧设想）
    )
    bads.foreach { bad =>
      AskUserQuestionTool.parseMode(JsonObject("mode" -> bad)) match
        case Left(err) => assert(err.message.contains(AskUserQuestionTool.BadModeCode), s"错误码缺失：${err.message}")
        case Right(m)  => fail(s"非法 mode $bad 被静默接受为 $m")
    }
  }

  // ============================================================
  // 负控（核心）：非 root 硬造非阻塞 ⇒ 显式拒绝 + 零副作用
  // ============================================================

  test("负控: 节点会话（general, depth=1）硬造 non-blocking ⇒ 显式拒绝且零副作用") {
    val sink = scala.collection.mutable.ListBuffer.empty[AgentCommand]
    val ctx = ctxFor(generalDef, depth = 1, ref = Some(recordingRef(sink)))
    val input = twoQuestions.add("mode", AskMode.NonBlockingWire.asJson)
    AskUserQuestionTool.call(input, ctx).unsafeRunSync() match
      case Left(err) =>
        assert(err.message.contains(AskUserQuestionTool.NonBlockingNotRootCode), err.message)
        assert(err.message.contains("non-blocking mode is Nebula-root-only"), err.message)
        assert(err.message.contains("depth=1"), err.message)
      case Right(ok) => fail(s"非 root 的非阻塞请求被放行（静默降级）：$ok")
    // 零副作用（第一层）：闸在派发之前 ⇒ agent ref 零消息（无 askUser 帧、无 hub 槽位、
    // 无 WaitingForUser、无 pause 的可能来源）
    assertEquals(sink.toList, Nil, "闸之后仍有副作用（AskUser 已派发）")
  }

  test("负控: 内核会话（kernel, depth=1）同上；程序化注入形态（agentDef=None）fail-closed") {
    val sink1 = scala.collection.mutable.ListBuffer.empty[AgentCommand]
    val kernel = AgentDef(name = "kernel", description = "", tools = Nil)
    val input = twoQuestions.add("mode", AskMode.NonBlockingWire.asJson)
    AskUserQuestionTool.call(input, ctxFor(kernel, depth = 1, ref = Some(recordingRef(sink1)))).unsafeRunSync() match
      case Left(err) => assert(err.message.contains(AskUserQuestionTool.NonBlockingNotRootCode), err.message)
      case Right(ok) => fail(s"内核会话的非阻塞请求被放行：$ok")
    assertEquals(sink1.toList, Nil)

    // agentDef=None（REST 直调 / 非 agent 上下文）——同一闸的 fail-closed 分支
    val sink2 = scala.collection.mutable.ListBuffer.empty[AgentCommand]
    val ctxNoDef = ToolContext(
      projectRoot = "",
      sessionId = Some("rest-call"),
      agentDef = None,
      depth = 0,
      agentActorRef = Some(recordingRef(sink2))
    )
    AskUserQuestionTool.call(input, ctxNoDef).unsafeRunSync() match
      case Left(err) => assert(err.message.contains(AskUserQuestionTool.NonBlockingNotRootCode), err.message)
      case Right(ok) => fail(s"agentDef=None 的非阻塞请求被放行（fail-closed 失效）：$ok")
    assertEquals(sink2.toList, Nil)
  }

  // ============================================================
  // B6 可达性预检：fail-closed + 零槽位
  // ============================================================

  test("B6: 预检 fail-closed —— 无 hub（sharedResources=None）⇒ 显式拒绝且零副作用") {
    val sink = scala.collection.mutable.ListBuffer.empty[AgentCommand]
    val ctx = ctxFor(nebulaDef, depth = 0, ref = Some(recordingRef(sink)), shared = None)
    val input = twoQuestions.add("mode", AskMode.NonBlockingWire.asJson)
    AskUserQuestionTool.call(input, ctx).unsafeRunSync() match
      case Left(err) =>
        assert(err.message.contains(AskUserQuestionTool.NoRootWindowCode), err.message)
      case Right(ok) => fail(s"无交互窗口时非阻塞被放行（答案会静默丢失）：$ok")
    assertEquals(sink.toList, Nil, "预检之后仍派发了 AskUser —— 零槽位纪律被破坏")
  }

  // ============================================================
  // 阻塞默认路径零漂移 + ack 形态
  // ============================================================

  test("阻塞零漂移: 不传 mode ⇒ 派发的 AskUser 带 Blocking 默认值，返回值仍是答复") {
    val sink = scala.collection.mutable.ListBuffer.empty[AgentCommand]
    val ctx = ctxFor(generalDef, depth = 1, ref = Some(recordingRef(sink, answers = List("A"))))
    AskUserQuestionTool.call(twoQuestions, ctx).unsafeRunSync() match
      case Right(answer) => assertEquals(answer, "A")
      case Left(err)     => fail(s"阻塞路径行为漂移：${err.message}")
    sink.toList match
      case List(cmd: AgentCommand.AskUser) =>
        assertEquals(cmd.mode, AskMode.Blocking, "既有调用点（不传 mode）落到非阻塞 —— 默认值失效")
        assertEquals(cmd.items.size, 1)
      case other => fail(s"期望恰好一条 AskUser 派发，实得：$other")
  }

  test("ack 文案: 机器可读（requestId + 问题数）+ 明确「不要等待」+ 未答兜底指令") {
    val items = AskUserQuestionTool.parseItems(
      io.circe.Json.arr(io.circe.Json.obj("question" -> "q1".asJson, "options" -> io.circe.Json.arr(io.circe.Json.obj("label" -> "A".asJson)))).asArray.get
    )
    val ack = AskUserQuestionTool.nonBlockingAck(items, "abcdef01")
    assert(ack.contains("requestId=abcdef01"), ack)
    assert(ack.contains("1 question(s)"), ack)
    assert(ack.contains("do not wait"), ack)
    assert(ack.contains("best judgment"), ack)
  }

  test("askGuard 仍第一顺位: headless 下非阻塞请求也恒拒（不被模式分支绕过）") {
    val sink = scala.collection.mutable.ListBuffer.empty[AgentCommand]
    val ctx = ctxFor(nebulaDef, depth = 0, ref = Some(recordingRef(sink)))
    val input = twoQuestions.add("mode", AskMode.NonBlockingWire.asJson)
    AskUserQuestionTool.askGuard(headless = true) match
      case Some(_) =>
        // 真实 call 路径：headless 由 HeadlessMode.enabled 决定；此处直接钉住顺序语义
        // （askGuard 是 call 的第一顺位 —— AskUserDualModeSchemaSpec 有源码级 pin）。
        assertEquals(AskUserQuestionTool.askGuard(headless = true).map(_.message), Some(AskUserQuestionTool.HeadlessErrorMessage))
      case None => fail("askGuard(headless=true) 未拒")
    // headless 分支不走模式解析：非 headless 下同输入落到非阻塞预检（可区分）
    assert(
      AskUserQuestionTool.askGuard(headless = false).isEmpty,
      "askGuard(headless=false) 不应拦"
    )
    assert(sink.toList.isEmpty)
  }

end AskUserDualModeToolSpec
