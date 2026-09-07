package nebflow.core.project

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import fs2.Stream
import io.circe.Json
import io.circe.JsonObject
import munit.CatsEffectSuite
import nebflow.actor.ActorSystem
import nebflow.agent.{AgentLibrary, SharedResources}
import nebflow.core.PathUtil
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ThinkingConfig}
import nebflow.shared.{ContentBlock, LlmHandle, LlmRequest, LlmResponse, StreamChunk}

import scala.concurrent.duration.*
import scala.util.Random

/** 分发器「建节点+接线」最小闭环冒烟（观测面上下文经济学批 20260907 裁定①验收②）。
  *
  * 审计基准：.nebflow/Spec/20260907_nodelist-flowmap-context-audit.md §4 P0-1 验收②
  * ——spawn 快照移除后分发器必须仍能完成「先 NodeList 读现状 → NodeEdit 建节点 →
  * 接线（in-barrier）→ 节点执行 → 结果投递」最小闭环。形态复用 isolated-smoke
  * §6 mock-LLM 状态机先例（#28 0b：NodeList/NodeEdit 全链路，真实 NodeEngine/store
  * /接线/投递语义在环）：
  *
  *  - 节点请求经 req.sessionId "node-" 前缀识别（§6.2 坑——agentId 可能为空）；
  *  - tool_result 在 role=user 消息的 content blocks（Anthropic 协议，§6.1 坑）；
  *  - 状态机以内容匹配 + 标志推进（§6.5 坑——不数请求），节点 id 靠历史扫描
  *    （创建回执可能与其他消息合并）。
  *
  * 断言：A（入口 task+out）与 B（in=[A]+out=Nebula）双双 completed；A.out 被 B 的
  * in 声明改接为 B；分发器恰好 4 个 turn（NodeList → 建 A → 建 B → 收尾）、节点
  * 会话 ≥2 次。
  */
class DispatcherClosedLoopSmokeSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 240.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-dispatcher-closed-loop"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  for agent <- List("project-dispatcher", "general") do
    os.makeDir.all(tempRoot / "agents" / agent)
    os.write.over(
      tempRoot / "agents" / agent / "agent.json",
      s"""{"name":"$agent","description":"closed-loop smoke agent","tools":[],"category":"standalone"}"""
    )
    os.write.over(tempRoot / "agents" / agent / "system.md", s"# $agent\n")

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)

  /** 消息全文（含 tool_result——Anthropic 协议在 role=user 消息 blocks 里，§6.1）。 */
  private def reqText(req: LlmRequest): String =
    req.messages
      .map { m =>
        m.content match
          case Left(s) => s
          case Right(blocks) =>
            blocks.collect {
              case ContentBlock.Text(t)             => t
              case ContentBlock.ToolResult(_, c, _) => c
            }.mkString("\n")
      }
      .mkString("\n")

  private val NodeIdPattern = """n-[0-9a-f]{8}""".r

  /** mock-LLM 状态机：分发器 turn 推进 NodeList→建A→建B→收尾；节点 turn 一律
    * 文本完成（内容匹配 + 标志推进，§6.5/§6.6）。 */
  private class StateMachineLlm:
    val inputs: Ref[IO, List[String]] = Ref.unsafe[IO, List[String]](Nil)
    val phase: Ref[IO, String] = Ref.unsafe[IO, String]("start")
    private def toolCall(id: String, name: String, input: JsonObject): Stream[IO, StreamChunk] =
      Stream(
        StreamChunk.ToolCallChunk(nebflow.shared.ToolCall(id, name, input)),
        StreamChunk.Done(None, None)
      )
    private def finalText(t: String): Stream[IO, StreamChunk] =
      Stream(StreamChunk.TextDelta(t), StreamChunk.Done(None, None))
    val handle: LlmHandle[IO] = new LlmHandle[IO]:
      def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new RuntimeException("send not expected"))
      def sendStream(
          req: LlmRequest,
          onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
      ): Stream[IO, StreamChunk] =
        val text = reqText(req)
        if req.sessionId.startsWith("node-") then
          // 节点会话：直接完成（NodeRunner 把最终输出落 result 并投递）
          Stream.eval(inputs.update(_ :+ s"[node] ${text.take(80)}")).flatMap { _ =>
            finalText("节点工作完成，交付物见正文。")
          }
        else
          Stream.eval(inputs.update(_ :+ text)).flatMap { _ =>
            Stream.eval(phase.get).flatMap {
              case "start" => // turn 1: spawn prompt → NodeList 读现状（裁定①工作流指引落地）
                Stream.eval(phase.set("listed")).flatMap { _ =>
                  toolCall("c1", "NodeList", JsonObject("project" -> Json.fromString("closed-loop")))
                }
              case "listed" => // turn 2: NodeList 回执在手 → 建入口节点 A（task+out，创建即跑）
                Stream.eval(phase.set("a_created")).flatMap { _ =>
                  toolCall("c2", "NodeEdit", JsonObject(
                    "project" -> Json.fromString("closed-loop"),
                    "nodename" -> Json.fromString("闭环-执行A"),
                    "description" -> Json.fromString("闭环冒烟入口节点"),
                    "task" -> Json.fromString("produce A"),
                    "out" -> Json.fromString("Nebula")
                  ))
                }
              case "a_created" => // turn 3: 建 A 回执（历史扫描取 A id）→ 建下游 B（in=[A]，接线）
                val aId = NodeIdPattern.findFirstIn(text).getOrElse(sys.error("A creation ack must carry node id"))
                Stream.eval(phase.set("b_created")).flatMap { _ =>
                  toolCall("c3", "NodeEdit", JsonObject(
                    "project" -> Json.fromString("closed-loop"),
                    "nodename" -> Json.fromString("闭环-下游B"),
                    "description" -> Json.fromString("闭环冒烟下游节点"),
                    "task" -> Json.fromString("assemble B"),
                    "in" -> Json.arr(Json.fromString(aId)),
                    "out" -> Json.fromString("Nebula")
                  ))
                }
              case _ => // turn 4: B 建成 → 分发器收尾（纯文本，session Completed → 拆桥）
                finalText("闭环完成：A 执行、B 接线就绪。")
            }
          }

  private def mkResources(system: ActorSystem, tmp: os.Path, llm: LlmHandle[IO]): IO[SharedResources] =
    for
      dispatcher <- cats.effect.std.Dispatcher.parallel[IO].allocated.map(_._1)
      rateLimiter <- RateLimiter.create()
      tracker <- nebflow.core.FileChangeTracker.create(os.pwd.toString)
      fileLocks <- FileLockManager.create
      thinkingRef <- Ref.of[IO, ThinkingConfig](ThinkingConfig())
      modelOverrides <- Ref.of[IO, Map[String, ModelCandidate]](Map.empty)
      voiceMuted <- Ref.of[IO, Boolean](false)
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
      historyArchiver = null,
      fileLockManager = fileLocks,
      sessionModelOverrides = modelOverrides,
      providerRegistry = null,
      healthMonitor = null,
      actorSystem = system,
      voiceMutedRef = voiceMuted
    )

  private def waitUntil(timeout: FiniteDuration, every: FiniteDuration = 50.millis)(
      cond: IO[Boolean]
  ): IO[Unit] =
    def go(deadline: Long): IO[Unit] =
      cond.flatMap {
        case true => IO.unit
        case false =>
          if System.currentTimeMillis() >= deadline then
            IO.raiseError(new AssertionError("waitUntil: condition not met in time"))
          else IO.sleep(every) >> go(deadline)
      }
    go(System.currentTimeMillis() + timeout.toMillis)

  private def mount(name: String, ws: os.Path, system: ActorSystem, res: SharedResources): IO[ProjectRuntime] =
    val pd = ProjectDef(
      name = name,
      workspace = ws.toString,
      agentFile = (ws / "AGENTS.md").toString,
      createdAt = System.currentTimeMillis()
    )
    ProjectRuntimeRegistry.mount(pd, system, res, None, rootSessionId = "nebula-root")

  test("闭环：NodeList → 建入口A → 接线下游B → A/B 双 completed → B 投递 Nebula 记账") {
    val ws = tempRoot / "ws-closed-loop"
    os.makeDir.all(ws)
    val system = ActorSystem(s"closed-loop-${Random.nextInt(100000)}")
    val llm = new StateMachineLlm
    for
      resources <- mkResources(system, tempRoot, llm.handle)
      rt <- mount("closed-loop", ws, system, resources)
      actorRef = rt.actorRef.getOrElse(sys.error("ProjectActor must be spawned by mount"))
      _ <- (actorRef ! ProjectActor.ProjectCommand.TriggerDispatcher("完成闭环冒烟任务：建两节点并接线", "nebula-root")).void
      // 分发器 4 turn 全部推进完（收尾文本 = 第 4 turn 输出）
      _ <- waitUntil(30.seconds)(llm.inputs.get.map(_.count(!_.startsWith("[node]")) >= 4))
      // 引擎闭环：A、B 双双到达 completed（执行 + barrier + 投递全在环）
      _ <- waitUntil(60.seconds)(rt.store.snapshot.map { s =>
        val ns = s.nodes.values.toList.sortBy(_.createdAt)
        ns.count(_.status == NodeLifecycle.Completed) >= 2 &&
        ns.find(_.name == "闭环-下游B").exists(_.status == NodeLifecycle.Completed)
      })
      snap <- rt.store.snapshot
      ins <- llm.inputs.get
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      val a = snap.nodes.values.find(_.name == "闭环-执行A").getOrElse(fail("node A missing"))
      val b = snap.nodes.values.find(_.name == "闭环-下游B").getOrElse(fail("node B missing"))
      assertEquals(a.status, NodeLifecycle.Completed, "entry node A must complete")
      assertEquals(b.status, NodeLifecycle.Completed, "downstream node B must complete")
      // 接线：B 的 in 声明把 A.out 改接为 B（barrier 合并接线语义）
      assert(b.in.contains(a.id), s"B.in must contain A (${a.id}), got: ${b.in}")
      assertEquals(a.out, Some(b.id), "A.out must be rewired to B by B's in declaration")
      assertEquals(b.out, Some("Nebula"), "B.out must be Nebula")
      // 注：B→Nebula 投递记账（nebulaDeliveredAt）在本 harness 不可观测——无真实
      // root 会话，deliverToNebula 按设计 WARN 不落账（同 ProjectDispatcher*Spec
      // 先例日志）；Nebula 投递链回归由 NebulaDeliveryDedupSpec/RedeliverySpec 覆盖。
      // 分发器恰好 4 个分发器 turn + ≥2 个节点 turn
      assertEquals(ins.count(!_.startsWith("[node]")), 4, "dispatcher turns: NodeList → createA → createB → wrap-up")
      assert(ins.count(_.startsWith("[node]")) >= 2, "both nodes must have executed a session")
      // 零 BLOCKED：闭环无阻塞
      assert(snap.nodes.values.forall(_.status != NodeLifecycle.Blocked), "closed loop must not block")
  }

end DispatcherClosedLoopSmokeSpec
