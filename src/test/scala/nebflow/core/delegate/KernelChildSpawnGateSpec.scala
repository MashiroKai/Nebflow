package nebflow.core.delegate

import cats.effect.{IO, Ref}
import cats.effect.std.Dispatcher
import cats.syntax.all.*
import fs2.Stream
import io.circe.Json
import munit.CatsEffectSuite
import nebflow.actor.{ActorRef, ActorSystem, AgentCommand, AgentDef, AgentKind, Behavior, Behaviors}
import nebflow.agent.{SharedResources, SubAgentTaskStore}
import nebflow.core.compact.HistoryArchiver
import nebflow.core.node.NodeRunner
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.core.{FileChangeTracker, LlmLogWriter, RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor}
import nebflow.shared.*

import scala.concurrent.duration.*

/**
 * actor-tree spec v1 §13.2 #P2 / 条款 R-3a —— **kernel 不得生子 actor 的 spawn 期硬闸**。
 *
 * 背景（[推断·待确认·#P2] 的实测答复）：今天只有**工具面裁剪**（kernelFace 摘
 * Subagent/AgentFlow/node 四件/delegate_report 侧的减法），`buildToolList` 装配面
 * 一旦漏一件，kernel 会话就能派生；**代码层没有任何 spawn 期断言**。本 spec 钉住
 * 补上的那道闸：
 *
 *  - K1：kernel 模式 delegate 会话 ⇒ spawn 子 actor **直接失败**，错误文本可读
 *    （说清是谁、被哪条不变量拒、该怎么办）。
 *  - K2：dispatcher 模式 delegate 会话 ⇒ 照常 spawn（零行为变化）。
 *  - K3：无父会话（parentRef=None，节点/分发器/flow 轨形态）⇒ 照常 spawn。
 *  - K4：历史 kernel 会话 id 前缀（`delegate-kernel-`，before-merge 存量身份）⇒
 *    同样拒（两条 kernel 判据的并集）。
 *  - K5：纯判据表——kernel 词 ⇒ Left，dispatcher/None ⇒ Right（非 kernel 身份逐位
 *    不变）。
 *
 * 🔴 验收纪律（反向验红）：K1/K4 在**未加闸的树上必须是红**的（spawn 会成功），
 * 加闸后转绿；K2/K3 恒绿（证明「不得改变其它角色行为」）。
 */
class KernelChildSpawnGateSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 120.seconds

  private val originalRoot = PathUtil.dataRoot
  private var base: os.Path = os.pwd / "target" / "test-kernel-spawn-gate"

  override def beforeAll(): Unit =
    base = os.temp.dir(prefix = "nb-kernel-gate-")
    PathUtil.setDataRoot(base / "data")
    LlmLogWriter.setEnabled(false)

  override def afterAll(): Unit =
    LlmLogWriter.setEnabled(true)
    PathUtil.setDataRoot(originalRoot)
    try os.remove.all(base)
    catch case _: Throwable => ()

  /** 本 spec 从不驱动回合：LLM 只是在 spawn 期必须存在（永不调用）。 */
  private object NullLlm extends LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] =
      IO.raiseError(new RuntimeException("KernelChildSpawnGateSpec: send not expected"))

    def sendStream(
      req: LlmRequest,
      onAttempt: Option[FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] =
      Stream(StreamChunk.Done(None, None))

  private final case class Fx(resources: SharedResources, system: ActorSystem, tmp: os.Path)

  private def withFx(name: String)(body: Fx => IO[Unit]): Unit =
    val tmp = base / name
    val system = ActorSystem(s"kernel-gate-$name-${java.util.UUID.randomUUID().toString.take(6)}")
    val io =
      for
        dispatcher <- Dispatcher.parallel[IO].allocated.map(_._1)
        rateLimiter <- RateLimiter.create()
        tracker <- FileChangeTracker.create(os.pwd.toString)
        fileLocks <- FileLockManager.create
        thinkingRef <- Ref.of[IO, ThinkingConfig](ThinkingConfig())
        modelOverrides <- Ref.of[IO, Map[String, ModelCandidate]](Map.empty)
        voiceMuted <- Ref.of[IO, Boolean](false)
        resources = SharedResources(
          llm = NullLlm,
          dispatcher = dispatcher,
          sessionStore = SessionStore(tmp / "sessions", tmp / "tasks"),
          projectRoot = os.pwd,
          thinkingConfigRef = thinkingRef,
          rateLimiter = rateLimiter,
          fileChangeTracker = tracker,
          contextWindow = 100_000,
          agentLibrary = new nebflow.agent.AgentLibrary(tmp / "agents"),
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
        _ <- body(Fx(resources, system, tmp))
      yield ()
    try io.unsafeRunSync()
    finally
      system.stopAll.attempt.void.unsafeRunSync()

  private def mkParent(record: Ref[IO, List[AgentCommand]]): Behavior[AgentCommand] =
    def loop: Behavior[AgentCommand] = Behaviors.receiveMessage(cmd => record.update(_ :+ cmd).as(loop))
    loop

  /** 真父 actor + registry 登记（spawn 期判据的**唯一身份来源** = parentRef → registry）。 */
  private def parentRefOf(fx: Fx, sid: String): IO[ActorRef[AgentCommand]] =
    for
      rec <- Ref.of[IO, List[AgentCommand]](Nil)
      ref <- fx.system.spawn(mkParent(rec), s"parent-${sid.take(28)}")
      _ <- NodeRunner.registerAgent(
        fx.resources,
        id = sid,
        ref = ref,
        kind = AgentKind.Delegate,
        rootSessionId = "root-1",
        parentRef = None,
        parentSessionId = "root-1"
      )
    yield ref

  private def putRecord(sid: String, cwd: String, mode: Option[String]): IO[Unit] =
    IO(
      DelegateRegistry.put(
        DelegateRegistry.Record(
          id = sid,
          executor = nebflow.core.executor.ExecutorRegistry.DefaultId,
          cwd = cwd,
          project = None,
          taskId = None,
          parentSessionId = "root-1",
          title = "kernel spawn gate",
          createdAt = System.currentTimeMillis(),
          mode = mode
        )
      )
    )

  private def childParams(
    fx: Fx,
    parentRef: Option[ActorRef[AgentCommand]],
    childId: String
  ): NodeRunner.SpawnParams =
    NodeRunner.SpawnParams(
      agentDef = AgentDef(name = "subagent", description = "kernel spawn gate child", tools = Nil),
      resources = fx.resources,
      sessionId = childId,
      sessionName = "kernel spawn gate child",
      depth = 2,
      parentRef = parentRef,
      wsSend = (_: Json) => IO.unit,
      projectRoot = Some(fx.tmp.toString),
      rootSessionId = "root-1"
    )

  private def spawnOutcome(fx: Fx, params: NodeRunner.SpawnParams): IO[Either[String, Unit]] =
    fx.resources
      .spawnAgentActor(fx.system, params)
      .attempt
      .map {
        case Right(_) => Right(())
        case Left(e)  => Left(Option(e.getMessage).filter(_.nonEmpty).getOrElse(e.getClass.getName))
      }

  // ── K1：kernel 模式 delegate 会话 spawn 子 actor ⇒ 直接失败 + 可读错误 ──────────

  test("K1 kernel-mode delegate cannot spawn a child actor (spawn refused with a readable error)") {
    withFx("kernel") { fx =>
      val kernelSid = "delegate-nebflow-k1a2b3c4"
      for
        pref <- parentRefOf(fx, kernelSid)
        _ <- putRecord(kernelSid, fx.tmp.toString, Some(DualModeConfig.ModeKernel))
        out <- spawnOutcome(fx, childParams(fx, Some(pref), "subagent-c0ffee01"))
        _ <- IO(println(s"[K1] kernel spawn outcome => ${out.fold(m => s"REFUSED: $m", _ => "SPAWNED (no gate!)")}"))
        _ <- IO(
          assert(
            out.isLeft,
            "kernel-mode delegate session spawned a child actor — the R-3a spawn-time gate is MISSING " +
              "(today only the tool face is trimmed, per spec §13.2 #P2)"
          )
        )
      yield ()
    }
  }

  // ── K2：非 kernel 身份不受影响（dispatcher 模式照常 spawn）────────────────────

  test("K2 dispatcher-mode delegate still spawns children (no behaviour change for other roles)") {
    withFx("dispatcher") { fx =>
      val dispatcherSid = "delegate-nebflow-d9e8f7a6"
      for
        pref <- parentRefOf(fx, dispatcherSid)
        _ <- putRecord(dispatcherSid, fx.tmp.toString, Some(DualModeConfig.ModeDispatcher))
        out <- spawnOutcome(fx, childParams(fx, Some(pref), "node-0a0a0a0a"))
        _ <- IO(assert(out.isRight, s"dispatcher-mode parent must keep spawning: $out"))
      yield ()
    }
  }

  // ── K3：无父会话（节点/分发器/flow 轨）不受影响 ───────────────────────────────

  test("K3 parentless spawns (node/dispatcher/flow tracks) are untouched") {
    withFx("parentless") { fx =>
      for out <- spawnOutcome(fx, childParams(fx, None, "node-0b0b0b0b"))
      yield assert(out.isRight, s"parentless spawn must keep working: $out")
    }
  }

  // ── K4：历史 kernel 身份（delegate-kernel- 前缀，无 mode 记录）同样拒 ──────────

  test("K4 legacy kernel session id prefix is refused too (both kernel judgements)") {
    withFx("legacy") { fx =>
      val legacySid = "delegate-kernel-abcd1234"
      for
        pref <- parentRefOf(fx, legacySid)
        out <- spawnOutcome(fx, childParams(fx, Some(pref), "subagent-c0ffee02"))
        _ <- IO(println(s"[K4] legacy kernel spawn outcome => ${out.fold(m => s"REFUSED: $m", _ => "SPAWNED (no gate!)")}"))
        _ <- IO(assert(out.isLeft, s"legacy kernel session '$legacySid' spawned a child actor — gate missing"))
      yield ()
    }
  }

  // ── K5：纯判据表（kernel 词/前缀 ⇒ Left；dispatcher/None ⇒ Right）─────────────

  test("K5 pure judgement table: kernel word or legacy prefix => Left, dispatcher/None => Right") {
    val kernelMode: String => Option[String] = _ => Some(DualModeConfig.ModeKernel)
    val dispatcherMode: String => Option[String] = _ => Some(DualModeConfig.ModeDispatcher)
    val noRecord: String => Option[String] = _ => None

    val kernelWord = KernelChildSpawnGate.judge(Some("delegate-aaaa1111"), kernelMode, "subagent-1")
    val legacyPrefix = KernelChildSpawnGate.judge(Some("delegate-kernel-abcd1234"), noRecord, "subagent-2")
    val dispatcher = KernelChildSpawnGate.judge(Some("delegate-bbbb2222"), dispatcherMode, "node-1")
    val parentless = KernelChildSpawnGate.judge(None, kernelMode, "node-2")

    assert(kernelWord.isLeft, s"kernel mode word must be refused: $kernelWord")
    assert(
      kernelWord.swap.getOrElse("").contains("R-3a"),
      s"refusal must name the invariant (who/what/how is readable): $kernelWord"
    )
    assert(legacyPrefix.isLeft, s"legacy kernel prefix must be refused: $legacyPrefix")
    assert(dispatcher == Right(()), s"dispatcher mode must pass: $dispatcher")
    assert(parentless == Right(()), s"parentless spawn must pass: $parentless")
  }

end KernelChildSpawnGateSpec
