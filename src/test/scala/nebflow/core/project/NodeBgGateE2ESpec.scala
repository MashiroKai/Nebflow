package nebflow.core.project

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import fs2.Stream
import io.circe.Json
import io.circe.syntax.*
import io.circe.JsonObject
import munit.CatsEffectSuite
import nebflow.actor.{ActorSystem, Behaviors}
import nebflow.agent.{AgentCommand, AgentKind, AgentLibrary, AgentRecord, SharedResources}
import nebflow.core.PathUtil
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.{BgTaskRegistry, FileLockManager, NodeEditTool, ToolContext}
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ThinkingConfig}
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, StreamChunk, ToolCall}

import scala.concurrent.duration.*

/**
 * 节点完成闸 E2E（bgtask-completion-gate 批，验证要求 3）：真实后台命令全链路——
 * stub LLM 驱动节点 agent 真调 Bash(run_in_background: echo + sleep 2)：
 * turn 1 结束（后台仍跑）→ 节点保持 Running（桥 hold，registry 真登记可查）→
 * 后台完成回调（真 BashTool notifyAgent 链路）→ 唤醒轮（收到含真实输出的
 * [Background task completed] 通知）→ 节点完成投递，result = 最后一轮文本。
 *
 * 变异验红：摘除桥 gate → 本 spec「turn 1 后节点必须仍 Running」红（后台未完成
 * 即 completed、通知注入死会话丢失——原始缺陷复现）；恢复后绿。
 */
class NodeBgGateE2ESpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 90.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-node-bg-gate-e2e"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot / "agents" / "bg-e2e-agent")
  // tools:["Bash"]：非收敛 agent 的声明式授予（buildAllowedToolSet legacy 分支）
  os.write.over(
    tempRoot / "agents" / "bg-e2e-agent" / "agent.json",
    """{"name":"bg-e2e-agent","description":"bg gate e2e agent","tools":["Bash"],"category":"standalone"}"""
  )
  os.write.over(tempRoot / "agents" / "bg-e2e-agent" / "system.md", "# bg-e2e-agent\n")
  // 2026-09-05 agent 退役：新建节点执行统一 general——fixture 侧补 general agent。
  // E2E 需真调 Bash：general 声明 tools:["Bash"]（非收敛 agent 声明式授予）。
  os.makeDir.all(tempRoot / "agents" / "general")
  os.write.over(tempRoot / "agents" / "general" / "agent.json",
    """{"name":"general","description":"general executor","tools":["Bash"],"category":"standalone"}""")
  os.write.over(tempRoot / "agents" / "general" / "system.md", "# general\n")

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)

  /** 三段式 stub：r1 = Bash 工具调用（真后台命令）；r2 = turn 1 收尾文本；
    * r3+（通知唤醒轮）= 记录请求全文（断言真实后台输出到达）+ 最终文本。 */
  private class BgE2eLlm:
    val requests: Ref[IO, List[String]] = Ref.unsafe[IO, List[String]](Nil)
    def handle: LlmHandle[IO] = new LlmHandle[IO]:
      def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new RuntimeException("send not expected"))
      def sendStream(
          req: LlmRequest,
          onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
      ): Stream[IO, StreamChunk] =
        Stream.eval(requests.update(_ :+ req.messages.map(_.textContent).mkString("\n"))).drain ++
          Stream.eval(requests.get.map(_.size)).flatMap { n =>
            val chunk: StreamChunk = n match
              case 1 =>
                StreamChunk.ToolCallChunk(
                  ToolCall("tu-bg-1", "Bash", JsonObject(
                    "command" -> Json.fromString("echo bg-gate-e2e-ok && sleep 6"),  // 6s 窗口：hold 断言轮询从容
                    "description" -> Json.fromString("e2e bg marker task"),
                    "run_in_background" -> Json.fromBoolean(true)
                  ))
                )
              case 2 => StreamChunk.TextDelta("turn1-done")
              case _ => StreamChunk.TextDelta("final-after-bg")
            Stream(chunk, StreamChunk.Done(None, None))
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
      actorSystem = null,
      voiceMutedRef = voiceMuted,
      // 本测试环境 sandbox-exec 不可用（sandbox_apply: Operation not permitted，
      // 加固运行时限制）——节点 spawn 硬编码 sandboxEnabled=true，Bash 会 fail-closed。
      // E2E 用 sandboxConfig.enabled=false 一键回旧（SandboxPolicy.forRoot 短路 off，
      // :184 先例）；沙箱行为本身由 SandboxSpec 族覆盖（本机属其环境性红基线）。
      sandboxConfig = nebflow.core.sandbox.SandboxConfig(enabled = false)
    )

  private def registerRecorder(res: SharedResources, system: ActorSystem, sid: String): IO[Ref[IO, List[AgentCommand]]] =
    for
      recorded <- Ref.of[IO, List[AgentCommand]](Nil)
      behavior =
        lazy val b: nebflow.actor.Behavior[AgentCommand] =
          Behaviors.receiveMessage[AgentCommand](msg => recorded.update(_ :+ msg).as(b))
        b
      ref <- system.spawn(behavior, s"rec-${scala.util.Random.nextInt(100000)}")
      _ <- res.agentRegistry.update(_ + (sid -> AgentRecord(sid, ref, AgentKind.Root, sid)))
    yield recorded

  test("E2E: turn end does not complete the node; real bg completion notifies; node delivers last-turn result") {
    val ws = tempRoot / "ws-e2e"
    os.makeDir.all(ws)
    val system = ActorSystem(s"bg-e2e-${scala.util.Random.nextInt(100000)}")
    val llm = BgE2eLlm()
    for
      res <- mkResources(system, tempRoot, llm.handle)
      recorded <- registerRecorder(res, system, "nebula-root")
      store <- FlowMapStore.open("bg-e2e", ws.toString)
      engine = new NodeEngine(
        store, system, res,
        wsSendFn = (_: Json) => IO.unit,
        workspace = ws.toString,
        rootSessionId = "nebula-root",
        projectName = "bg-e2e",
        emitEvent = (_, _, _) => IO.unit,
        // noderpt 批 A 段：本 fixture 主题 = 完成门腿 1（后台任务存活拦终态化）的
        // 真实 E2E ⇒ 必须显式开腿 1（生产默认 **false = 封存**，见
        // Defaults#BgGateCompletionHold；封存态行为由 NodeBgCompletionGateSpec#G9 覆盖）；
        // 腿 2（node_report 未申报 hold）与本 fixture 主题正交 ⇒ 显式关
        // （生产默认开；默认开行为由 NodeReportReminderSpec 覆盖）。
        reportGateHold = Some(false),
        bgGateCompletionHold = Some(true)
      )
      pd = ProjectDef(name = "bg-e2e", workspace = ws.toString, agentFile = (ws / "AGENTS.md").toString, createdAt = System.currentTimeMillis())
      rt = ProjectRuntime(pd, store, engine, system, res, None)
      _ <- ProjectRuntimeRegistry.register(rt)
      ctx = ToolContext(
        projectRoot = ws.toString,
        sessionId = Some("e2e-sid"),
        rootSessionId = Some("nebula-root"),
        sharedResources = Some(res),
        actorSystem = Some(system)
      )
      _ <- NodeEditTool.call(
        Json.obj("project" -> "bg-e2e".asJson, "nodename" -> "e2e-a".asJson,
          "description" -> "e2e bg gate node".asJson, "task" -> "run the marker bg task".asJson,
          "out" -> "Nebula".asJson).asObject.get,
        ctx
      ).map {
        case Left(err) => fail(s"NodeEdit failed: $err")
        case Right(_)  => ()
      }
      // turn 1 结束（agent Idle）——此时真后台任务（sleep 2）仍在跑
      _ <- waitUntil(20.seconds) {
        res.agentRegistry.get.map { reg =>
          reg.values.find(r => r.kind == AgentKind.Flow && r.sessionId.startsWith("node-")) match
            case Some(rec) => rec.status == nebflow.agent.AgentStatus.Idle
            case None      => false
        }
      }
      nodeSid <- res.agentRegistry.get.map(_.values.find(r => r.kind == AgentKind.Flow).map(_.sessionId)).map(_.getOrElse(fail("node session missing")))
      // 桥 hold 铁证：真实 BashTool 登记（emitBgTaskStarted）可见 + 节点未终态。
      // 注意：AgentRecord.status 默认 Idle（spawn 即 Idle，turn 开始才翻
      // Processing）——上面的 Idle 轮询可能停在 turn 前窗口，等登记完成再快照。
      _ <- waitUntil(15.seconds)(BgTaskRegistry.waitingFor(nodeSid).map(_.nonEmpty))
      waitingMid <- BgTaskRegistry.waitingFor(nodeSid)
      _ <- IO.sleep(300.millis)
      mid <- store.snapshot.map(_.nodes.values.find(_.name == "e2e-a")).map(_.getOrElse(fail("node missing")))
      _ <- waitUntil(30.seconds) {
        store.snapshot.map(_.nodes.values.find(_.name == "e2e-a")).flatMap {
          case Some(n) => IO.pure(NodeLifecycle.Terminal.contains(n.status))
          case None    => IO.pure(false)
        }
      }
      done <- store.snapshot.map(_.nodes.values.find(_.name == "e2e-a")).map(_.getOrElse(fail("node missing")))
      reqs <- llm.requests.get
      // 投递链确定性同步点：节点终态落盘与 out=Nebula 投递（`ref ! ImmediateInput`
      // fire-and-forget）不在同一原子步——终态可见 ≠ recorder 已记账。有界轮询
      // 等投递消息到达再断言（断言语义不变；投递真丢失时此处清晰红）
      _ <- waitUntil(10.seconds) {
        recorded.get.map(_.exists {
          case m: AgentCommand.ImmediateInput => m.text.contains("[Node 'e2e-a' completed]")
          case _                              => false
        })
      }
      imms <- recorded.get.map(_.collect { case m: AgentCommand.ImmediateInput => m })
      // 进程/资源清理纪律（2026-09-05）：节点会话的 ShellSession 携带 cleanup
      // fiber（5min 循环）——destroySession 取消它并清后台任务表，不留测试残渣。
      _ <- nebflow.core.tools.ShellSession.destroySession(nodeSid).attempt.void
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(waitingMid.nonEmpty, s"real bg task must be registered and visible to the gate: ${waitingMid}")
      assertEquals(mid.status, NodeLifecycle.Running, "node must stay Running while real bg task pending (hold)")
      assertEquals(done.status, NodeLifecycle.Completed, s"node must complete after real bg task finished: ${done.result}")
      assertEquals(done.result, Some("final-after-bg"), "result must be the last turn text")
      assert(reqs.exists(r => r.contains("[Background task completed]") && r.contains("bg-gate-e2e-ok")),
        "wake turn must receive the completion notification carrying REAL bg output")
      assert(imms.exists(_.text.contains("[Node 'e2e-a' completed]")), "delivery must happen after release")
  }

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
end NodeBgGateE2ESpec
