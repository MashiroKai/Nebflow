package nebflow.agent

import cats.effect.std.Dispatcher
import cats.effect.{IO, Ref}
import cats.syntax.all.*
import fs2.Stream
import io.circe.JsonObject
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.ActorSystem
import nebflow.core.PathUtil
import nebflow.core.compact.HistoryArchiver
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor, ThinkingConfig}
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, StreamChunk, ToolCall}

import scala.concurrent.duration.*

/**
 * 🔴 **P1 臂「退役文案等式」实测钉**（mailunify-full 批，2026-09-23 作者裁定；
 * 读数要求见分发器补充 #2 §1）。
 *
 * ## 为什么必须有本件（不靠静态推理）
 * 摘空 `RetiredToolGuides`（`AgentCore.scala:3033` ⇒ `Map.empty`）后，「旧名的实际文案」
 * 这一读数**必须实跑取得**，且必须在**正确的臂**上取：
 *
 *   - **P1 臂**（`AgentCore.scala:1147-1150` 允许集过滤 ⇒ `:1358-1359` 产出
 *     `Tool not available: ${call.name}`）—— 这是真实 agent turn 里**唯一可达**的
 *     「名字不在工具面」路径；
 *   - **P2 分支**（`AgentCore.scala:2027-2035` 的 `RetiredToolGuides.get` / 两个文案）——
 *     通过 P1 的名字**必属 `allowedTools`**，而现读任何 allowed set 成员都已注册
 *     （证据 `03-guide-reachability.md` §3.3 脚本复算）⇒ **P2 结构性不可达**。
 *     ⇒ 🔴 **不得**拿 P2 测（跑不到 ⇒ 只能合成断言，不是读数）。
 *
 * ## 本件取的读数
 * 一个**真** `AgentActor` turn（真 `AgentCore` 管线、真 `buildAllowedToolSet`、真
 * 允许集过滤腿），脚本化 LLM 在一轮里同时发五个工具调用：
 * `Task` / `NodeMessage` / `TransferFile` / `SendMessage` / 一个杜撰名。
 * ⇒ 断言五者的**实际文案逐字同形**：`Tool not available: <name>`。
 *
 * 🔴 这同时是「表空 ⇒ 与『该名从未存在过』不可区分」这条新政策的**运行面守卫**
 * （政策面断言在 `DeletedToolGuardSpec`；本件给运行读数）。
 *
 * 另附一条**结构性守卫**：`RetiredToolGuides` 为空 ∧ 全仓生产代码**零**「退役指引」
 * 文案来源 ⇒ 全仓只剩 P1 一个退役文案（`AgentCore.scala:3027-3028` 的自陈也随之成立）。
 */
class RetiredToolNameReadingsSpec extends CatsEffectSuite:

  override val munitIOTimeout = 120.seconds

  /** 五个探测名：三个老退役键 + 本批新退役名 + 一个从未存在的杜撰名。 */
  private val probes: List[String] = List("Task", "NodeMessage", "TransferFile", "SendMessage", "FooNeverExisted")

  /** 一轮里**逐个**发五个调用（每个名字单独一批）——为了在同一轮内取全五条读数，
    * 脚本按请求序号逐名发出，最后一次纯文本收尾。 */
  private class SequentialProbeLlm(requests: Ref[IO, List[LlmRequest]], names: List[String]) extends LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] =
      IO.raiseError(new RuntimeException("send not expected in this spec"))
    def sendStream(
      req: LlmRequest,
      onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] =
      Stream.eval(requests.update(_ :+ req)).drain ++
        Stream.eval(requests.get.map(_.size)).flatMap { n =>
          names.lift(n - 1) match
            case Some(name) =>
              Stream(StreamChunk.ToolCallChunk(ToolCall(s"tu-$n", name, JsonObject.empty)), StreamChunk.Done(None, None))
            case None => Stream(StreamChunk.TextDelta("all done"), StreamChunk.Done(None, None))
        }

  private def mkResources(system: ActorSystem, tmp: os.Path, llm: LlmHandle[IO]): IO[SharedResources] =
    for
      dispatcher     <- Dispatcher.parallel[IO].allocated.map(_._1)
      rateLimiter    <- RateLimiter.create()
      tracker        <- nebflow.core.FileChangeTracker.create(os.pwd.toString)
      fileLocks      <- FileLockManager.create
      thinkingRef    <- IO.ref(ThinkingConfig())
      modelOverrides <- IO.ref(Map.empty[String, ModelCandidate])
      voiceMuted     <- IO.ref(false)
    yield SharedResources(
      llm = llm,
      dispatcher = dispatcher,
      sessionStore = SessionStore(tmp / "sessions", tmp / "tasks"),
      projectRoot = os.pwd,
      thinkingConfigRef = thinkingRef,
      rateLimiter = rateLimiter,
      fileChangeTracker = tracker,
      contextWindow = 100_000,
      agentLibrary = new AgentLibrary(tmp / "agents"),
      taskStore = FileTaskStore,
      historyArchiver = HistoryArchiver.fileSystem(tmp / "archives"),
      fileLockManager = fileLocks,
      sessionModelOverrides = modelOverrides,
      providerRegistry = null,
      healthMonitor = ProviderHealthMonitor(null),
      actorSystem = system,
      subAgentTaskStore = new SubAgentTaskStore(tmp / "subagent-tasks"),
      voiceMutedRef = voiceMuted
    )

  private def waitFor[A](ref: Ref[IO, A], pred: A => Boolean, msg: String, timeoutMs: Long): IO[Unit] =
    def go(deadline: Long): IO[Unit] =
      ref.get.map(pred).flatMap {
        case true  => IO.unit
        case false =>
          if System.currentTimeMillis() >= deadline then
            IO.raiseError(new AssertionError(s"$msg in time"))
          else IO.sleep(100.millis) >> go(deadline)
      }
    go(System.currentTimeMillis() + timeoutMs)

  /** 驱动一个真 agent turn（`Worker` / depth=0 / 声明工具 = Read ⇒ 允许集仅 {Read}）。 */
  private def driveProbe(): (List[LlmRequest], List[io.circe.Json]) =
    val system = ActorSystem(s"retired-name-${java.util.UUID.randomUUID().toString.take(6)}")
    val tmp = os.temp.dir(prefix = "retired-name-")
    val prevRoot = PathUtil.dataRoot
    val prevLlmLog = nebflow.core.LlmLogWriter.isEnabled
    nebflow.core.LlmLogWriter.setEnabled(false)
    PathUtil.setDataRoot(tmp / "data")
    try
      val program = for
        requests <- IO.ref(List.empty[LlmRequest])
        llm = new SequentialProbeLlm(requests, probes)
        resources <- mkResources(system, tmp, llm)
        wsEvents <- IO.ref(List.empty[io.circe.Json])
        def_ = AgentDef(name = "Worker", description = "retired-name probe", tools = List("Read"), systemPrompt = "")
        sid = "retired-name-probe"
        actor <- system.spawn(
          AgentActor(
            agentDef = def_,
            resources = resources,
            wsSend = j => wsEvents.update(_ :+ j),
            depth = 0,
            parentRef = None,
            sessionId = Some(sid),
            sessionName = Some("probe"),
            safetyMode = "auto-all"
          ),
          "retired-name-probe"
        )
        _ <- resources.agentRegistry.update(_ + (sid -> AgentRecord(sid, actor, AgentKind.Ephemeral, sid, None)))
        _ <- actor ! AgentCommand.UserInput("probe the retired names", None, Some("cmid-probe"))
        // 六个 LLM 往返（五个探测名 + 收尾文本）⇒ 请求数到 6 即本轮结束。
        _ <- waitFor(requests, (r: List[LlmRequest]) => r.size >= probes.size + 1, "probe turn did not finish", 60000)
        reqs <- requests.get
        evs <- wsEvents.get
      yield (reqs.reverse, evs)
      program.unsafeRunSync()
    finally
      nebflow.core.LlmLogWriter.setEnabled(prevLlmLog)
      PathUtil.setDataRoot(prevRoot)
      system.stopAll.attempt.void.unsafeRunSync()
      os.remove.all(tmp)
  end driveProbe

  /** 一轮请求里**模型可见的全部正文**——含 `ToolResult` 块（工具结果的文案就在那里；
    * `Message.textContent` 只收 `Text` 块，故此处显式展开 `ToolResult`）。 */
  private def visibleOf(req: LlmRequest): String =
    req.messages
      .map { m =>
        m.content match
          case Left(text) => text
          case Right(blocks) =>
            blocks
              .map {
                case nebflow.shared.ContentBlock.Text(t)             => t
                case nebflow.shared.ContentBlock.ToolResult(_, c, _) => c
                case other                                            => other.toString
              }
              .mkString("\n")
      }
      .mkString("\n")

  test("🔴 P1 臂文案等式：五个名字（三老键 + 本批退役名 + 杜撰名）逐名得同形 `Tool not available: <name>`"):
    val (reqs, evs) = driveProbe()
    val all = reqs.map(visibleOf).mkString("\n=====\n")
    // 对照组：允许集内的 `Read` 若出现，走的**不是**本文案（证明本判据确实读的是
    // 「不在工具面」这一条，而不是「任何工具结果都长这样」）。
    for name <- probes do
      assert(
        all.contains(s"Tool not available: $name"),
        s"🔴 P1 文案缺席：`Tool not available: $name` 未出现在任何一轮 LLM 请求的正文里。" +
          s"（读数是「该名对该会话不可用」的唯一可达形态；见类头注释的 P1/P2 判据）"
      )
    // 逐名**同形**（前缀逐字相同 ⇒ 与「该名从未存在过」不可区分）。
    for name <- probes do
      assert(
        all.contains(s"Tool not available: $name") &&
          !all.contains(s"Tool '$name' has been retired"),
        s"🔴 `$name` 命中退役指引形态（`Tool '<name>' has been retired`）⇒ `RetiredToolGuides` 未摘空"
      )
    assert(
      !all.contains("No such tool available: "),
      "🔴 P2 分支文案出现 ⇒ 允许集过滤腿未生效（本读数要求必须在 P1 臂取）"
    )
    assert(
      !all.contains("has been retired"),
      "🔴 全仓不得再有任何退役指引文案（⑧ 全表摘空：表空 ⇒ P2 retired 分支结构性不可达）"
    )
    println(s"[P1-ARM-READING] probes=${probes.mkString(",")} rounds=${reqs.size} " +
      s"frames=${evs.size} 断言：五名同形 `Tool not available: <name>`；零 retired-guide 文案；零 P2 文案")

  test("🔴 结构性守卫（静态读数）：表空 ∧ 生产代码零退役指引来源 ⇒ 全仓只剩 P1 一个退役文案"):
    assert(
      AgentCore.RetiredToolGuides.isEmpty,
      s"RetiredToolGuides 必须为空；got keys: ${AgentCore.RetiredToolGuides.keys.toList.sorted}"
    )
    val root = os.pwd
    val core = os.read(root / "src" / "main" / "scala" / "nebflow" / "agent" / "AgentCore.scala")
    // P2 的两个文案仍在源码里（本批**零改动**：作者裁「保留原样」），但其查询源已空
    // ⇒ retired 臂结构性不可达；兜底臂（`No such tool available`）在同层不可达
    // （P1 名字必属 allowedTools，而 allowed 成员全已注册 ⇒ 见 03-guide-reachability §3.3）。
    assert(
      core.contains("AgentCore.RetiredToolGuides.get(call.name)"),
      "P2 消费点必须仍在源码里（本批零改动、原样保留）"
    )
    assert(
      core.contains("Tool not available: ${call.name}"),
      "P1 文案单点必须在源码里（真实 turn 唯一可达的退役形态）"
    )
    assert(
      core.contains("RetiredToolGuides: Map[String, String] = Map.empty"),
      "摘空必须落在右值（`Map.empty`），保留 table 定义与 doc"
    )

end RetiredToolNameReadingsSpec
