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
 *  - K1a：kernel 模式 delegate 会话 → spawn **只读侦察子**（def=subagent）**成功**，
 *    且子会话交付面 = 机制固定的只读五件（tool-face-agentflow-retire 批 2026-11-05
 *    作者逐字裁定「改 R-3a，只放行只读 subagent」后的反转面）。
 *  - K1b：kernel 模式 delegate 会话 → spawn **任何其它 def** 仍**直接失败**，错误
 *    文本可读（说清是谁、被哪条不变量拒、唯一豁免是什么、该怎么办）。
 *  - K2：dispatcher 模式 delegate 会话 ⇒ 照常 spawn（零行为变化）。
 *  - K3：无父会话（parentRef=None，节点/分发器/flow 轨形态）⇒ 照常 spawn。
 *  - K4：历史 kernel 会话 id 前缀（`delegate-kernel-`，before-merge 存量身份）⇒
 *    两条判据并集同款：非 recon def 拒、recon def 放行。
 *  - K5：纯判据表——kernel 词/前缀 + 非 recon def ⇒ Left，+ recon def ⇒ Right，
 *    dispatcher/None ⇒ Right（非 kernel 身份逐位不变）。
 *  - K6：**放行面不可扩**——声明（agent.json tools）对收敛名 `subagent` 不授能，
 *    SubagentTool 的 schema 无 def/tools 参数且实现不转发 defName，闸的放行判据
 *    只认那一个精确名字。
 *
 * 🔴 验收纪律（反向验红）：K1b/K4(非 recon) 在**未加闸的树上必须是红**的（spawn 会
 * 成功），加闸后转绿；K2/K3 恒绿（证明「不得改变其它角色行为」）。
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
    childId: String,
    defName: String = nebflow.core.entity.BuiltinAgents.SubagentName,
    declaredTools: List[String] = Nil
  ): NodeRunner.SpawnParams =
    NodeRunner.SpawnParams(
      agentDef = AgentDef(name = defName, description = "kernel spawn gate child", tools = declaredTools),
      resources = fx.resources,
      sessionId = childId,
      sessionName = "kernel spawn gate child",
      depth = 2,
      parentRef = parentRef,
      wsSend = (_: Json) => IO.unit,
      projectRoot = Some(fx.tmp.toString),
      rootSessionId = "root-1"
    )

  private def spawnOutcome(fx: Fx, params: NodeRunner.SpawnParams): IO[Either[String, String]] =
    fx.resources
      .spawnAgentActor(fx.system, params)
      .attempt
      .map {
        case Right(ref) => Right(ref.path.name)
        case Left(e)    => Left(Option(e.getMessage).filter(_.nonEmpty).getOrElse(e.getClass.getName))
      }

  // ── K1（tool-face-agentflow-retire 批 2026-11-05 反转）：kernel 父的两条 spawn 判据 ──
  // 作者逐字裁定「改 R-3a，只放行只读 subagent」：kernel 身份下**只**放行
  // `def == subagent` 的子 actor 生成，其余任何 def 一律照旧拒绝。两条断言成对
  // 在场（只改一半 = 放行面失控）。

  test("K1a kernel-mode delegate MAY spawn the read-only recon child (def=subagent) — and its face is the fixed read-only five") {
    withFx("kernel-recon") { fx =>
      val kernelSid = "delegate-nebflow-k1a2b3c4"
      for
        pref <- parentRefOf(fx, kernelSid)
        _ <- putRecord(kernelSid, fx.tmp.toString, Some(DualModeConfig.ModeKernel))
        out <- spawnOutcome(fx, childParams(fx, Some(pref), "subagent-c0ffee01"))
        _ <- IO(println(s"[K1a] kernel -> read-only subagent spawn outcome => ${out.fold(m => s"REFUSED: $m", _ => "SPAWNED")}"))
        _ <- IO(
          assert(
            out.isRight,
            s"kernel-mode delegate MUST be able to run the read-only recon leg (Subagent tool is mounted in the " +
              s"kernel face by author ruling 2026-11-05) — got: $out"
          )
        )
        // 子会话真的被 spawn 出来了（返回的 actor 名 = 子会话 id；spawn 不是空转）
        _ <- IO(
          assertEquals(
            out,
            Right("subagent-c0ffee01"),
            "the recon child actor must actually spawn (registered id = the child session id)"
          )
        )
        // 交付面 = 机制固定的只读五件（不是「kernel 一律放行」，放行面自带一套面）
        face = nebflow.agent.AgentCore.fixedToolsFor(
          AgentDef(name = nebflow.core.entity.BuiltinAgents.SubagentName, description = "", tools = Nil)
        )
        _ <- IO(println(s"[K1a] recon child tool face => ${face.toList.sorted.mkString(", ")}"))
        _ <- IO(
          assertEquals(
            face,
            nebflow.agent.AgentCore.SubagentFixedTools,
            "the recon child's face is the mechanism-fixed read-only five"
          )
        )
        _ <- IO(assertEquals(face.size, 5, "exactly five pieces"))
        _ <- IO(
          Set("Write", "Edit", "Bash", "Mail", "Subagent", "delegate_report", "node_report").foreach { t =>
            assert(!face.contains(t), s"the recon leg must have no $t — read-only by mechanism, not by prompt")
          }
        )
      yield ()
    }
  }

  test("K1b kernel-mode delegate still cannot spawn ANY other agent def (R-3a holds outside the recon leg)") {
    withFx("kernel-other") { fx =>
      val kernelSid = "delegate-nebflow-k1b5d6e7"
      for
        pref <- parentRefOf(fx, kernelSid)
        _ <- putRecord(kernelSid, fx.tmp.toString, Some(DualModeConfig.ModeKernel))
        fanOut <- spawnOutcome(
          fx,
          childParams(fx, Some(pref), "node-0f0f0f0f", defName = nebflow.core.entity.BuiltinAgents.ExecutorName)
        )
        _ <- IO(println(s"[K1b] kernel -> executor-def spawn outcome => ${fanOut.fold(m => s"REFUSED: $m", _ => "SPAWNED (gate hole!)")}"))
        _ <- IO(
          assert(
            fanOut.isLeft,
            "kernel-mode delegate spawned a NON-recon child actor — the R-3a gate must only exempt def=subagent"
          )
        )
        _ <- IO(assert(fanOut.swap.getOrElse("").contains("R-3a"), s"refusal must name the invariant: $fanOut"))
        _ <- IO(
          assert(
            fanOut.swap.getOrElse("").contains(nebflow.core.entity.BuiltinAgents.ExecutorName),
            s"refusal must name the offending def (readable diagnosis): $fanOut"
          )
        )
        // 任意自定义 def 同样拒（判据只认那一个名字）
        custom <- spawnOutcome(fx, childParams(fx, Some(pref), "node-1a1a1a1a", defName = "my-own-agent"))
        _ <- IO(assert(custom.isLeft, s"an arbitrary custom def must be refused too: $custom"))
      yield ()
    }
  }

  // ── K6：放行面不可扩（作者令第 2 条：只读面必须真的只读 + 不许被传参改 def/扩面）──

  test("K6 the exemption face cannot be widened: subagent's def is mechanism-fixed and its declaration is inert") {
    // ① 声明不授能：子 def 名 `subagent` 在 ConvergedAgentNames 内 ⇒ AgentDef.tools
    //    里写什么都被无视，交付面恒为只读五件（**同一条链路**判定，不是另抄一份）。
    val sneakyDecl = AgentDef(
      name = nebflow.core.entity.BuiltinAgents.SubagentName,
      description = "",
      tools = List("Write", "Edit", "Bash", "Mail", "delegate_report")
    )
    val face = nebflow.agent.AgentCore.fixedToolsFor(sneakyDecl)
    assertEquals(
      face,
      nebflow.agent.AgentCore.SubagentFixedTools,
      s"a declaration on the recon def grants nothing (converged name): got ${face.toList.sorted}"
    )
    assert(face.subsetOf(nebflow.agent.AgentCore.SubagentFixedTools), "the recon face is exactly the fixed read-only five")
    // ② def 不可由调用方指定：SubagentTool 的 schema 只声明 prompt/description，
    //    且其实现**不传** defName（BlockingSubagent.run 的缺省 = BuiltinAgents.SubagentName）
    //    ——源扫描断言（同仓既有 producer-pin 手法）。
    val schemaKeys = nebflow.core.tools.SubagentTool.inputSchema("properties").flatMap(_.asObject).map(_.keys.toSet).getOrElse(Set.empty)
    assertEquals(schemaKeys, Set("prompt", "description"), s"Subagent declares no def/tools parameter: $schemaKeys")
    val src = os.read(
      os.pwd / "src" / "main" / "scala" / "nebflow" / "core" / "tools" / "SubagentTool.scala"
    )
    assert(
      !src.contains("defName"),
      "SubagentTool must never forward a caller-supplied defName — the spawn target is the builtin `subagent` def, full stop"
    )
    // ③ 闸的放行判据单点 = 那一个名字（改名换不出第二个放行面）
    assert(KernelChildSpawnGate.readOnlyReconExempt(nebflow.core.entity.BuiltinAgents.SubagentName), "the recon def passes")
    List(" nebflow", "subagent ", "Subagent", "subagent-x", "", "nebflow").foreach { n =>
      assert(!KernelChildSpawnGate.readOnlyReconExempt(n), s"'$n' must NOT be exempt (only the exact builtin name is)")
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

  // ── K4：历史 kernel 身份（delegate-kernel- 前缀，无 mode 记录）—— 判据并集同理 ──

  test("K4 legacy kernel session id prefix: non-recon defs refused, the read-only recon def allowed") {
    withFx("legacy") { fx =>
      val legacySid = "delegate-kernel-abcd1234"
      for
        pref <- parentRefOf(fx, legacySid)
        fanOut <- spawnOutcome(
          fx,
          childParams(fx, Some(pref), "node-0c0c0c0c", defName = nebflow.core.entity.BuiltinAgents.ExecutorName)
        )
        _ <- IO(println(s"[K4] legacy kernel -> executor-def spawn outcome => ${fanOut.fold(m => s"REFUSED: $m", _ => "SPAWNED (gate hole!)")}"))
        _ <- IO(assert(fanOut.isLeft, s"legacy kernel session '$legacySid' spawned a non-recon child actor — gap in the gate"))
        recon <- spawnOutcome(fx, childParams(fx, Some(pref), "subagent-c0ffee02"))
        _ <- IO(println(s"[K4] legacy kernel -> read-only recon spawn outcome => ${recon.fold(m => s"REFUSED: $m", _ => "SPAWNED")}"))
        _ <- IO(
          assert(
            recon.isRight,
            s"the legacy kernel identity must get the same recon exemption (two judgements, one face): $recon"
          )
        )
      yield ()
    }
  }

  // ── K5：纯判据表（kernel 词/前缀 ⇒ 非 recon 拒、recon 放行；dispatcher/None ⇒ 放行）──

  test("K5 pure judgement table: kernel word or legacy prefix => Left for non-recon defs, Right for the recon def") {
    val kernelMode: String => Option[String] = _ => Some(DualModeConfig.ModeKernel)
    val dispatcherMode: String => Option[String] = _ => Some(DualModeConfig.ModeDispatcher)
    val noRecord: String => Option[String] = _ => None
    val recon = nebflow.core.entity.BuiltinAgents.SubagentName
    val other = nebflow.core.entity.BuiltinAgents.ExecutorName

    val kernelWord = KernelChildSpawnGate.judge(Some("delegate-aaaa1111"), kernelMode, "node-1", other)
    val legacyPrefix = KernelChildSpawnGate.judge(Some("delegate-kernel-abcd1234"), noRecord, "node-2", other)
    val kernelRecon = KernelChildSpawnGate.judge(Some("delegate-aaaa1111"), kernelMode, "subagent-1", recon)
    val legacyRecon = KernelChildSpawnGate.judge(Some("delegate-kernel-abcd1234"), noRecord, "subagent-2", recon)
    val dispatcher = KernelChildSpawnGate.judge(Some("delegate-bbbb2222"), dispatcherMode, "node-3", other)
    val parentless = KernelChildSpawnGate.judge(None, kernelMode, "node-4", other)

    assert(kernelWord.isLeft, s"kernel mode word must refuse a non-recon child: $kernelWord")
    assert(
      kernelWord.swap.getOrElse("").contains("R-3a"),
      s"refusal must name the invariant (who/what/how is readable): $kernelWord"
    )
    assert(
      kernelWord.swap.getOrElse("").contains(nebflow.core.entity.BuiltinAgents.SubagentName),
      s"refusal must point at the one exemption (the recon leg): $kernelWord"
    )
    assert(legacyPrefix.isLeft, s"legacy kernel prefix must refuse a non-recon child: $legacyPrefix")
    assert(kernelRecon == Right(()), s"kernel word + recon def must pass: $kernelRecon")
    assert(legacyRecon == Right(()), s"legacy prefix + recon def must pass: $legacyRecon")
    assert(dispatcher == Right(()), s"dispatcher mode must pass: $dispatcher")
    assert(parentless == Right(()), s"parentless spawn must pass: $parentless")
  }

end KernelChildSpawnGateSpec
