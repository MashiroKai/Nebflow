package nebflow.llm

import cats.effect.{IO, Ref}
import cats.effect.std.Dispatcher
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import fs2.Stream
import io.circe.Json
import munit.CatsEffectSuite
import nebflow.actor.{
  ActorContext,
  ActorPath,
  ActorSystem,
  AgentCommand,
  AgentDef,
  AgentKind,
  AgentRecord,
  Behavior,
  Behaviors,
  LocalActorSystem
}
import nebflow.agent.{AgentActor, AgentLibrary, SharedResources, SubAgentTaskStore}
import nebflow.core.{RateLimiter, SessionStore}
import nebflow.shared.*

import scala.concurrent.duration.*

import sttp.capabilities.fs2.Fs2Streams
import sttp.client4.StreamBackend

/**
 * provider 并发上限 + 派发时刻 fallback 链快照 —— T1–T4（provider-concurrency 批）。
 *
 * 对齐 01-recon.md §3 实现契约，签名逐字：
 *   · `nebflow.llm.ProviderConcurrency`：`assign(counts, config, actorId, orderedChain):
 *     IO[Option[PinnedChain]]` / `release(counts, actorId)` / `reconcile(counts, liveIds)` /
 *     `snapshot(counts, config): IO[Json]`；`PinnedChain(headRef, orderedRefs, refs,
 *     pinnedProviderId)`；`Counts(byProvider: Map[String, Set[String]])` + `empty`；
 *   · `ProviderConfig.maxConcurrentActors: Option[Int]`（None=不限；≤0 非法 ⇒ 当 None）；
 *   · `AgentActor(providerChainPin: Option[PinnedChain])`（派发时刻快照的唯一载体）；
 *   · `LocalActorSystem.providerSlotRelease: String => IO[Unit]`（private[nebflow]，默认
 *     no-op，`loop.guarantee` 唯一收尸点）。
 *
 * 🔴 改前红状态（两腿取证）：
 *   · 本文件引用上述新符号 ⇒ 改前 `Test/compile` 失败（红-不编译；证据 =
 *     `reports-evidence/provider-concurrency-20261106/02-red-before.txt` §三）；
 *   · 可编译的行为红（现存公共缝复现缺陷）在同目录
 *     `ProviderConcurrencyBehaviorRedSpec.scala`（BR-1/BR-2，改前断言失败）。
 *
 * 夹具纪律：
 *   · 配置全部在 spec 内构造，不读/不写真实 HOME（非空 `llm.fallback` 钉住 config
 *     腿——先例 `FallbackChainCandidateSpec`）；
 *   · actor 计数注入 = 测试自建 `Ref[IO, ProviderConcurrency.Counts]`（缝 1）；
 *     release 钩子由测试显式赋给 `providerSlotRelease`（缝 4，镜像 GatewayMain 装配）；
 *   · turn 面 = `SharedResources` + `CaptureLlm extends LlmHandle`（先例
 *     `ToolResultTtlSpec` wiring 测），`providerRegistry = null` 是既有先例形态
 *     （`ToolResultTtlSpec.scala:316`）——T2 的链源用独立 registry（缝 2）解析。
 *
 * 语义对齐申报：契约 §3f 裁定「正常完成回 Idle **不释放**、Frozen 不释放」（并发按
 * actor 计、不按 turn）——因此 T3 的减计数路径 = **销毁型终态**（stop / 崩溃 / 自停 /
 * TTL / stopAll）；「正常完成不释放」由 T2 的 5-turn 保留断言承接；Frozen 保留属装配
 * 面（actor 不死 ⇒ 钩子不触发），不另设夹具。任务简报 T3 行内「正常完成/冻结各自减
 * 计数」按契约裁定修正为上述形态。
 */
class ProviderConcurrencyFallbackSpec extends CatsEffectSuite:

  override val munitIOTimeout = 120.seconds

  private val NullBackend = null.asInstanceOf[StreamBackend[IO, Fs2Streams[IO]]]

  private def provider(models: String*): ProviderConfig =
    ProviderConfig(
      baseUrl = "http://127.0.0.1:1/",
      apiKey = "fake-key-not-a-secret",
      protocol = LlmProtocol.OpenAI,
      models = models.toList.map(m => ModelConfig(m, contextWindow = 1000))
    )

  private def withCap(p: ProviderConfig, cap: Option[Int]): ProviderConfig = p.copy(maxConcurrentActors = cap)

  /** T1/T2 主配置：a cap=10；b 不限；c/m2 未被链引用 ⇒ 储备层（reserveTier）追加链尾。 */
  private val capCfg: NebflowServiceConfig =
    NebflowServiceConfig(
      llm = ServiceLlmConfig(
        providers = Map(
          "a" -> withCap(provider("m1"), Some(10)),
          "b" -> provider("m1"),
          "c" -> provider("m2")
        ),
        fallback = Some(AgentModelConfig(Some("a/m1"), List("b/m1")))
      )
    )

  private val agentChain: Option[AgentModelConfig] = Some(AgentModelConfig(Some("a/m1"), List("b/m1")))

  /** T1b 的 z-配置链源（cfg0/cfgNeg 里没有 provider a——链必须与配置同源，镜像真实 spawn 路径）。 */
  private val zChain: Option[AgentModelConfig] = Some(AgentModelConfig(Some("z/m1"), List("b/m1")))

  private def mkRegistry(c: NebflowServiceConfig): IO[ProviderRegistry] =
    Ref.of[IO, NebflowServiceConfig](c).map(ref => new ProviderRegistry(ref, NullBackend))

  /** 链源单点：与请求路径 / 壳方法（assignFallbackChain）同一个解析函数（契约 §3b）。 */
  private def orderedChainOf(
    reg: ProviderRegistry,
    ch: Option[AgentModelConfig] = agentChain
  ): IO[List[(String, String)]] =
    reg.getCandidatesForAgentUnfiltered(ch).map(cs => cs.map(c => c.providerId -> c.model))

  private def countsRef: IO[Ref[IO, ProviderConcurrency.Counts]] =
    Ref.of[IO, ProviderConcurrency.Counts](ProviderConcurrency.empty)

  /** 有界轮询（条件成立即返回；超时显式失败——无固定 sleep 竞态）。 */
  private def waitUntil(desc: String, timeoutMs: Long = 30000L, stepMs: Long = 100L)(
    cond: IO[Boolean]
  ): IO[Unit] =
    IO.monotonic.flatMap { t0 =>
      def loop(deadline: Long): IO[Unit] =
        cond.flatMap {
          case true => IO.unit
          case false =>
            IO.monotonic.flatMap { now =>
              if now.toMillis >= deadline then IO.raiseError(new RuntimeException(s"[timeout] $desc"))
              else IO.sleep(stepMs.millis) *> loop(deadline)
            }
        }
      loop(t0.toMillis + timeoutMs)
    }

  /** 缝 4：真 ActorSystem + 显式装配 release 钩子（镜像 GatewayMain 的赋值点）。 */
  private def wiredSystem(counts: Ref[IO, ProviderConcurrency.Counts], label: String): IO[LocalActorSystem] =
    IO {
      val s = ActorSystem(label).asInstanceOf[LocalActorSystem]
      s.providerSlotRelease = (name: String) => ProviderConcurrency.release(counts, name)
      s
    }

  /** 请求捕获 LLM（先例 ToolResultTtlSpec:278-284）：记录每个 LlmRequest 后立即回一段文本。 */
  private class CaptureLlm(requests: Ref[IO, List[LlmRequest]]) extends LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new RuntimeException("send not expected"))

    def sendStream(req: LlmRequest, onAttempt: Option[FallbackAttempt => IO[Unit]] = None): Stream[IO, StreamChunk] =
      Stream.eval(requests.update(_ :+ req)).drain ++
        Stream(StreamChunk.TextDelta("ok"), StreamChunk.Done(None, None))

  // ── T1 ────────────────────────────────────────────────────────────────────

  test("T1: cap=10 占满 ⇒ 第 11 个 actor 链首落第二档；A 释放到只剩 3 ⇒ 第 11 个 actor 的链首仍不变"):
    for
      reg <- mkRegistry(capCfg)
      chain <- orderedChainOf(reg)
      counts <- countsRef
      // 10 个 actor 依次占坑 ⇒ 全部钉在第一档
      first10 <- (1 to 10).toList.traverse(i => ProviderConcurrency.assign(counts, capCfg, s"actor-$i", chain))
      p11 <- ProviderConcurrency.assign(counts, capCfg, "actor-11", chain)
      // 占用释放：a1..a7 退出 ⇒ a 只剩 {actor-8, actor-9, actor-10} = 3
      _ <- (1 to 7).toList.traverse_(i => ProviderConcurrency.release(counts, s"actor-$i"))
      // 已派发 actor 重复询问（幂等面）：链首必须原样（不重算 / 不迁移 / 不加计数）
      p11Again <- ProviderConcurrency.assign(counts, capCfg, "actor-11", chain)
      survivors <- (8 to 10).toList.traverse(i => ProviderConcurrency.assign(counts, capCfg, s"actor-$i", chain))
      countsMid <- counts.get
      // 上限恢复面：释放后新派发的 actor 回第一档
      p12 <- ProviderConcurrency.assign(counts, capCfg, "actor-12", chain)
      countsEnd <- counts.get
    yield
      // 链源先验：储备层 c/m2 必须追加链尾（同配置下确定序）
      assertEquals(chain, List(("a", "m1"), ("b", "m1"), ("c", "m2")), "orderedChain 先验：a/m1 → b/m1 + 储备层 c/m2")
      first10.zipWithIndex.foreach { case (p, i) =>
        assertEquals(p.map(_.headRef), Some("a/m1"), s"actor-${i + 1} 必须钉在第一档 a/m1")
        assertEquals(p.map(_.pinnedProviderId), Some("a"), s"actor-${i + 1} 的占坑 provider = a")
      }
      // 第 11 个：链首换档（改前引擎无占坑概念，恒 a/m1 —— 行为红镜像 = BR-1）
      assertEquals(p11.map(_.headRef), Some("b/m1"), "第 11 个 actor 的链首必须落第二档 b/m1")
      // 作者最在意的一条：A 释放到 3 后，第 11 个 actor 的链首仍不变（整链快照相等 = 幂等不迁移）
      assertEquals(p11Again, p11, "已派发 actor 重复 assign 必须原样返回同一快照（不重算、不迁移、不加计数）")
      assertEquals(p11Again.map(_.headRef), Some("b/m1"), "释放到 3/10 后 actor-11 的链首仍必须是 b/m1")
      // 尾链策略（契约 §3d）：只取被选中下标之后（含储备层），不回绕被跳过的前段
      assertEquals(p11.map(_.orderedRefs), Some(List("c/m2")), "actor-11 的尾链 = b 之后（含储备层）")
      assert(!p11.exists(_.orderedRefs.contains("a/m1")), "尾链不得回绕第一档 a/m1")
      assertEquals(p11.map(_.refs), Some(List(("b", "m1"), ("c", "m2"))), "refs = head 之后的有序 (providerId, model)")
      // 存活 actor 链首不变
      survivors.zipWithIndex.foreach { case (p, i) =>
        assertEquals(p.map(_.headRef), Some("a/m1"), s"存活 actor-${i + 8} 重复 assign 链首必须仍是 a/m1")
      }
      // 幂等 re-assign 不得加计数
      assertEquals(countsMid.byProvider.get("a"), Some(Set("actor-8", "actor-9", "actor-10")), "a 恰 = 存活 3 个")
      assertEquals(countsMid.byProvider.get("b"), Some(Set("actor-11")), "b 恰 = actor-11")
      // 上限恢复：新派发回第一档，尾链 = 链首之后全部
      assertEquals(p12.map(_.headRef), Some("a/m1"), "A 回到 3/10 后新派发的 actor-12 必须能用回第一档")
      assertEquals(p12.map(_.orderedRefs), Some(List("b/m1", "c/m2")), "actor-12 的尾链 = 链首之后的全部")
      assertEquals(
        countsEnd.byProvider.get("a"),
        Some(Set("actor-8", "actor-9", "actor-10", "actor-12")),
        "a 最终占坑 = 存活 3 + actor-12"
      )
      assertEquals(countsEnd.byProvider.get("b"), Some(Set("actor-11")), "b 最终占坑 = actor-11")

  test("T1b: cap ≤ 0 非法 ⇒ 当不限（不得静默当 0 全拒——否则链首永远落二档）"):
    val cfg0 = NebflowServiceConfig(
      llm = ServiceLlmConfig(
        providers = Map("z" -> withCap(provider("m1"), Some(0)), "b" -> provider("m1")),
        fallback = Some(AgentModelConfig(Some("z/m1"), List("b/m1")))
      )
    )
    val cfgNeg = cfg0.copy(
      llm = cfg0.llm.copy(providers = Map("z" -> withCap(provider("m1"), Some(-7)), "b" -> provider("m1")))
    )
    for
      reg0 <- mkRegistry(cfg0)
      chain0 <- orderedChainOf(reg0, zChain)
      counts0 <- countsRef
      ps0 <- (1 to 3).toList.traverse(i => ProviderConcurrency.assign(counts0, cfg0, s"zc-$i", chain0))
      c0 <- counts0.get
      regNeg <- mkRegistry(cfgNeg)
      chainNeg <- orderedChainOf(regNeg, zChain)
      countsNeg <- countsRef
      psNeg <- (1 to 3).toList.traverse(i => ProviderConcurrency.assign(countsNeg, cfgNeg, s"zn-$i", chainNeg))
      cNeg <- countsNeg.get
    yield
      ps0.foreach(p => assertEquals(p.map(_.headRef), Some("z/m1"), "cap=0 必须视为不限，链首照常落 z"))
      assertEquals(c0.byProvider.get("z"), Some(Set("zc-1", "zc-2", "zc-3")), "cap=0 ⇒ 3 个 actor 全部占上 z")
      psNeg.foreach(p => assertEquals(p.map(_.headRef), Some("z/m1"), "cap=-7 同法（非法 ⇒ 不限）"))
      assertEquals(cNeg.byProvider.get("z"), Some(Set("zn-1", "zn-2", "zn-3")), "cap=-7 ⇒ 同不限")

    end for

  test("T1c: 全部 provider 满载 ⇒ assign = None（不钉、回落既有全局行为）；释放后坑可复用"):
    val cfgFull = NebflowServiceConfig(
      llm = ServiceLlmConfig(
        providers = Map("a" -> withCap(provider("m1"), Some(2))),
        fallback = Some(AgentModelConfig(Some("a/m1"), List("b/m1"))) // b 不在 providers ⇒ 解析为空
      )
    )
    for
      reg <- mkRegistry(cfgFull)
      chain <- orderedChainOf(reg)
      counts <- countsRef
      p1 <- ProviderConcurrency.assign(counts, cfgFull, "x1", chain)
      p2 <- ProviderConcurrency.assign(counts, cfgFull, "x2", chain)
      p3 <- ProviderConcurrency.assign(counts, cfgFull, "x3", chain)
      countsAtFull <- counts.get
      _ <- ProviderConcurrency.release(counts, "x1")
      p3retry <- ProviderConcurrency.assign(counts, cfgFull, "x3", chain)
    yield
      assertEquals(chain, List(("a", "m1")), "先验：b/m1 解析不出 ⇒ 链只剩 a/m1")
      assertEquals(p1.map(_.headRef), Some("a/m1"), "第 1 个占 a/m1")
      assertEquals(p2.map(_.headRef), Some("a/m1"), "第 2 个占满 a/m1")
      assertEquals(p3, None, "第 3 个：全满 ⇒ None（无可用 provider ⇒ 不钉，回落既有行为）")
      assertEquals(countsAtFull.byProvider.get("a"), Some(Set("x1", "x2")), "失败的 assign 不得加计数")
      assertEquals(p3retry.map(_.headRef), Some("a/m1"), "释放 x1 后 x3 必须能复用坑位")

    end for

  test("T1d: 空链 ⇒ assign = None（空链/全灭由既有全局链兜底，分配器不编造）"):
    for
      counts <- countsRef
      p <- ProviderConcurrency.assign(counts, capCfg, "solo", Nil)
      c <- counts.get
    yield
      assertEquals(p, None, "空 orderedChain ⇒ None")
      assertEquals(c.byProvider, Map.empty[String, Set[String]], "None 分配不得产生计数")

  // ── T2 ────────────────────────────────────────────────────────────────────

  test("T2: 同一 actor 连续 5 turn —— 每请求 agentModel 逐字 = 派发时刻快照，命中 provider 序列全同；正常完成不释放；stop 才释放"):
    val tmp = os.temp.dir()
    var system: ActorSystem = null
    try
      val program =
        for
          configRef <- Ref.of[IO, NebflowServiceConfig](capCfg)
          reg = new ProviderRegistry(configRef, NullBackend)
          chain <- orderedChainOf(reg)
          counts <- countsRef
          sys0 = ActorSystem("pconc-t2")
          sys = sys0.asInstanceOf[LocalActorSystem]
          _ = system = sys0
          _ = sys.providerSlotRelease = (name: String) => ProviderConcurrency.release(counts, name)
          dispatcher <- Dispatcher.parallel[IO].allocated.map(_._1)
          rateLimiter <- RateLimiter.create()
          tracker <- nebflow.core.FileChangeTracker.create(os.pwd.toString)
          fileLocks <- nebflow.core.tools.FileLockManager.create
          thinkingRef <- IO.ref(nebflow.shared.ThinkingConfig())
          modelOverrides <- IO.ref(Map.empty[String, nebflow.llm.ModelCandidate])
          voiceMuted <- IO.ref(false)
          sessionStore = SessionStore(tmp / "sessions", tmp / "tasks-ui")
          sessionMeta <- sessionStore.createSession("pconc-t2")
          sid = sessionMeta.id
          requests <- IO.ref(List.empty[LlmRequest])
          resources = SharedResources(
            llm = null,
            dispatcher = dispatcher,
            sessionStore = sessionStore,
            projectRoot = os.pwd,
            thinkingConfigRef = thinkingRef,
            rateLimiter = rateLimiter,
            fileChangeTracker = tracker,
            contextWindow = 100_000,
            agentLibrary = new AgentLibrary(tmp / "agents"),
            taskStore = nebflow.core.task.FileTaskStore,
            historyArchiver = nebflow.core.compact.HistoryArchiver.fileSystem(tmp / "archives"),
            fileLockManager = fileLocks,
            sessionModelOverrides = modelOverrides,
            providerRegistry = null,
            healthMonitor = nebflow.llm.ProviderHealthMonitor(null),
            actorSystem = sys0,
            subAgentTaskStore = new SubAgentTaskStore(tmp / "subagent-tasks"),
            voiceMutedRef = voiceMuted
          )
          // 派发时刻分配（缝 1：与生产 spawnAgentActor 将来调用的同一纯函数机）
          pin <- ProviderConcurrency.assign(counts, capCfg, sid, chain)
          expectedModel = pin.map(p => AgentModelConfig(preferred = Some(p.headRef), fallbacks = p.orderedRefs))
          resourcesWithLlm = resources.copy(llm = new CaptureLlm(requests))
          // startup def 的 model 与快照不同（z/zzz 是不存在的 ref）⇒ pin 一旦被忽略或
          // 每 turn 重导出，捕获到的 agentModel 就不可能逐字等于快照
          agentDef = AgentDef(
            name = "pconc-pin-agent",
            description = "provider-concurrency T2 fixture",
            tools = Nil,
            systemPrompt = "answer briefly",
            model = Some(AgentModelConfig(Some("z/zzz"), Nil))
          )
          behavior = AgentActor(
            agentDef = agentDef,
            resources = resourcesWithLlm,
            wsSend = _ => IO.unit,
            depth = 0,
            sessionId = Some(sid),
            sessionName = Some("pconc-t2"),
            providerChainPin = pin
          )
          ref <- sys0.spawn(behavior, sid)
          _ <- resourcesWithLlm.agentRegistry.update(_ + (sid -> AgentRecord(sid, ref, AgentKind.Root, sid, None)))
          // 连续 5 个 turn，逐个等请求数推进（有界轮询，无 sleep 竞态）
          _ <- (1 to 5).toList.traverse { k =>
            (ref ! AgentCommand.UserInput(s"turn $k", None, Some(s"cmid-$k"))) *>
              waitUntil(s"turn $k 的请求未在期限内到达", 60000L)(requests.get.map(_.size >= k))
          }
          reqs <- requests.get
          midCounts <- counts.get
          // 命中 provider 序列：经同一条公共缝解析每个捕获请求的链首
          seq <- reqs.traverse(r =>
            reg.getCandidatesForAgentUnfiltered(r.agentModel).map(cs => cs.headOption.map(_.providerId))
          )
          _ <- sys0.stop(ref)
          _ <- waitUntil("stop 后占坑未释放", 30000L)(counts.get.map(_.byProvider.isEmpty))
          endCounts <- counts.get
        yield
          assert(pin.isDefined, "派发时刻必须拿到钉定快照（a 空闲 ⇒ 链首 a/m1）")
          assertEquals(pin.map(_.headRef), Some("a/m1"), "a 空闲时快照链首 = a/m1")
          assert(reqs.size >= 5, s"至少 5 次 LLM 请求，实际 ${reqs.size}")
          reqs.zipWithIndex.foreach { case (r, i) =>
            assertEquals(
              r.agentModel,
              expectedModel,
              s"第 ${i + 1} 个请求的 agentModel 必须逐字 = 派发时刻快照（preferred=${pin.map(_.headRef)}, " +
                s"fallbacks=${pin.map(_.orderedRefs)}）；startup def 的 z/zzz 不得漏出 ⇒ pin 被忽略/每 turn 重导出都在此变红"
            )
          }
          assertEquals(seq.distinct, List(Some("a")), s"实际命中 provider 序列必须全同 = a，实际 = $seq")
          assertEquals(
            midCounts.byProvider.get("a"),
            Some(Set(sid)),
            "5 个 turn 正常完成（Idle）后占坑必须原样保留：并发按 actor 计、不按 turn（契约 §3f）"
          )
          assertEquals(
            endCounts.byProvider,
            Map.empty[String, Set[String]],
            "actor stop 后必须经 providerSlotRelease 钩子释放占坑（loop.guarantee 唯一收尸点）"
          )
      program.unsafeRunSync()
    finally
      if system != null then system.stopAll.attempt.void.unsafeRunSync()
      os.remove.all(tmp)
    end try

  // ── T3 ────────────────────────────────────────────────────────────────────

  test("T3a: 终态·外部停止（system.stop ⇒ loop.guarantee）⇒ 占坑释放、计数归零"):
    for
      reg <- mkRegistry(capCfg)
      chain <- orderedChainOf(reg)
      counts <- countsRef
      sys <- wiredSystem(counts, "pconc-t3a")
      ref <- sys.spawn(Behaviors.ignore[String], "t3-stop-actor")
      pin <- ProviderConcurrency.assign(counts, capCfg, "t3-stop-actor", chain)
      before <- counts.get
      _ <- sys.stop(ref)
      _ <- waitUntil("T3a stop 后占坑未释放")(counts.get.map(_.byProvider.isEmpty))
      after <- counts.get
    yield
      assertEquals(pin.map(_.headRef), Some("a/m1"), "前置：链首钉在 a/m1")
      assertEquals(before.byProvider.get("a"), Some(Set("t3-stop-actor")), "停止前必须占坑")
      assertEquals(after.byProvider, Map.empty[String, Set[String]], "system.stop 必须经钩子释放占坑（loop.guarantee 收尸）")

  test("T3b: 终态·异常退出（receive 抛错且 onError 逃逸 ⇒ fiber 崩溃）⇒ 占坑释放"):
    for
      reg <- mkRegistry(capCfg)
      chain <- orderedChainOf(reg)
      counts <- countsRef
      sys <- wiredSystem(counts, "pconc-t3b")
      crashing: Behavior[String] = new Behavior[String]:
        def receive(ctx: ActorContext[String], msg: String): IO[Behavior[String]] =
          IO.raiseError(new RuntimeException(s"boom: $msg"))
        override def onError(ctx: ActorContext[String], err: Throwable): IO[Behavior[String]] =
          IO.raiseError(err) // 默认 onError 是 resume；逃逸 ⇒ fiber 异常退出 ⇒ guarantee 收尸
      ref <- sys.spawn(crashing, "t3-crash-actor")
      _ <- ProviderConcurrency.assign(counts, capCfg, "t3-crash-actor", chain)
      _ <- ref ! "boom"
      _ <- waitUntil("T3b 崩溃后 actor 未退出")(sys.isAlive(ActorPath(sys.localDevice, "t3-crash-actor")).map(!_))
      _ <- waitUntil("T3b 崩溃后占坑未释放")(counts.get.map(_.byProvider.isEmpty))
      after <- counts.get
    yield assertEquals(after.byProvider, Map.empty[String, Set[String]], "崩溃路径必须释放占坑（异常退出 ≠ 漏收尸）")

  test("T3c: 终态·自行停止（behavior 回 Behaviors.stopped ⇒ loop 正常退出）⇒ 占坑释放"):
    for
      reg <- mkRegistry(capCfg)
      chain <- orderedChainOf(reg)
      counts <- countsRef
      sys <- wiredSystem(counts, "pconc-t3c")
      stopping: Behavior[String] =
        Behaviors.receiveMessage[String](msg =>
          if msg == "stop" then IO.pure(Behaviors.stopped) else IO.pure(Behaviors.ignore[String])
        )
      ref <- sys.spawn(stopping, "t3-selfstop-actor")
      _ <- ProviderConcurrency.assign(counts, capCfg, "t3-selfstop-actor", chain)
      _ <- ref ! "stop"
      _ <- waitUntil("T3c 自停后 actor 未退出")(sys.isAlive(ActorPath(sys.localDevice, "t3-selfstop-actor")).map(!_))
      _ <- waitUntil("T3c 自停后占坑未释放")(counts.get.map(_.byProvider.isEmpty))
      after <- counts.get
    yield assertEquals(after.byProvider, Map.empty[String, Set[String]], "AgentCommand.Stop 同型的自停路径必须释放占坑")

  test("T3d: 终态·TTL 到期 ⇒ 占坑释放"):
    for
      reg <- mkRegistry(capCfg)
      chain <- orderedChainOf(reg)
      counts <- countsRef
      sys <- wiredSystem(counts, "pconc-t3d")
      ref <- sys.spawn(Behaviors.ignore[String], "t3-ttl-actor", ttl = Some(300.millis))
      _ <- ProviderConcurrency.assign(counts, capCfg, "t3-ttl-actor", chain)
      _ <- waitUntil("T3d TTL 后 actor 未退出")(sys.isAlive(ActorPath(sys.localDevice, "t3-ttl-actor")).map(!_))
      _ <- waitUntil("T3d TTL 后占坑未释放")(counts.get.map(_.byProvider.isEmpty))
      after <- counts.get
    yield assertEquals(after.byProvider, Map.empty[String, Set[String]], "TTL 路径必须释放占坑")

  test("T3e: 终态·stopAll ⇒ 全部占坑释放"):
    for
      reg <- mkRegistry(capCfg)
      chain <- orderedChainOf(reg)
      counts <- countsRef
      sys <- wiredSystem(counts, "pconc-t3e")
      names = List("t3-all-x", "t3-all-y")
      refs <- names.traverse(n => sys.spawn(Behaviors.ignore[String], n))
      _ <- names.traverse(n => ProviderConcurrency.assign(counts, capCfg, n, chain))
      before <- counts.get
      _ <- sys.stopAll
      _ <- waitUntil("T3e stopAll 后占坑未释放")(counts.get.map(_.byProvider.isEmpty))
      after <- counts.get
    yield
      assertEquals(before.byProvider.get("a"), Some(Set("t3-all-x", "t3-all-y")), "stopAll 前两个 actor 各占一坑")
      assertEquals(after.byProvider, Map.empty[String, Set[String]], "stopAll ⇒ 每个 fiber 各自走 guarantee ⇒ 全释放")

  test("T3f: 跑 N 轮释放后计数回 0；重复 release / 未占坑 id release 幂等（无负数、无双减）"):
    for
      reg <- mkRegistry(capCfg)
      chain <- orderedChainOf(reg)
      counts <- countsRef
      _ <- (1 to 5).toList.traverse { r =>
        ProviderConcurrency
          .assign(counts, capCfg, s"round-$r", chain)
          .flatMap(p =>
            ProviderConcurrency.release(counts, s"round-$r") *> IO {
              assert(p.isDefined, s"第 $r 轮 assign 必须成功")
            }
          )
      }
      mid <- counts.get
      _ <- ProviderConcurrency.release(counts, "round-1") // 已释放 ⇒ no-op
      _ <- ProviderConcurrency.release(counts, "round-1") // 再重复 ⇒ 仍 no-op
      _ <- ProviderConcurrency.release(counts, "ghost-never-assigned") // 从未占坑 ⇒ no-op
      end <- counts.get
    yield
      assertEquals(mid.byProvider, Map.empty[String, Set[String]], "每轮 assign→release 后计数必须回 0")
      assertEquals(end.byProvider, Map.empty[String, Set[String]], "重复 release 必须 no-op：计数不为负、不双减")

  // ── T4 ────────────────────────────────────────────────────────────────────

  test("T4: 重启自愈 —— reconcile 以存活 actor 集重建计数（原表 ∩ 活集），新 actor 能用回第一档（无永久占坑）"):
    for
      reg <- mkRegistry(capCfg)
      chain <- orderedChainOf(reg)
      counts <- countsRef
      // 直造重启残留：上一进程留下 a 满 10 + b 2 个占坑
      _ <- counts.set(
        ProviderConcurrency.Counts(
          Map(
            "a" -> (1 to 10).map(i => s"actor-$i").toSet,
            "b" -> Set("actor-11", "actor-13")
          )
        )
      )
      // 重启后只有这些 actor 还活着
      _ <- ProviderConcurrency.reconcile(counts, Set("actor-1", "actor-2", "actor-11"))
      after <- counts.get
      pNew <- ProviderConcurrency.assign(counts, capCfg, "actor-new", chain)
    yield
      assertEquals(
        after.byProvider,
        Map("a" -> Set("actor-1", "actor-2"), "b" -> Set("actor-11")),
        "reconcile 必须把计数表剪到 原表 ∩ 活集（死 actor 的坑不得跨重启存活）"
      )
      assertEquals(pNew.map(_.headRef), Some("a/m1"), "剪残后（a=2/10）新派发必须能用回第一档 —— 无永久占坑")

  test("T4b: 活集为空（全新进程 boot）⇒ reconcile 清空整表"):
    for
      counts <- countsRef
      _ <- counts.set(ProviderConcurrency.Counts(Map("a" -> Set("a1", "a2"), "b" -> Set("b1"))))
      _ <- ProviderConcurrency.reconcile(counts, Set.empty[String])
      after <- counts.get
    yield assertEquals(after.byProvider, Map.empty[String, Set[String]], "boot 空表起点：活集为空 ⇒ 表清空")

  test("T4c: snapshot 可观测面 —— assignable/inUse/cap/full/totalActors 形状（契约 §3h；cap 不限 = 显式 null）"):
    for
      counts <- Ref.of[IO, ProviderConcurrency.Counts](
        ProviderConcurrency.Counts(
          Map(
            "a" -> (1 to 10).map(i => s"actor-$i").toSet,
            "b" -> Set("actor-11")
          )
        )
      )
      json <- ProviderConcurrency.snapshot(counts, capCfg)
    yield
      val a = entryOf(json, "a")
      val b = entryOf(json, "b")
      assert(a.isDefined, "assignable 必须列出 provider a")
      assert(b.isDefined, "assignable 必须列出 provider b")
      assertEquals(numAt(a, "inUse"), Some(10L), "a.inUse = 10")
      assertEquals(capAt(a), Some(Some(10L)), "a.cap 必须显式存在 = 10")
      assertEquals(boolAt(a, "full"), Some(true), "a 10/10 ⇒ full = true")
      assertEquals(numAt(b, "inUse"), Some(1L), "b.inUse = 1")
      assertEquals(capAt(b), Some(None), "cap 不限必须编码为显式 null（键存在、值 null，不得缺键）")
      assertEquals(boolAt(b, "full"), Some(false), "cap null ⇒ full 恒 false")
      assertEquals(
        json.hcursor.downField("totalActors").as[Long].toOption,
        Some(11L),
        "totalActors = 占坑 actor 数（跨 provider 去重）"
      )

  // ── snapshot JSON 读数助手（§3h 形状） ────────────────────────────────────

  private def entryOf(json: Json, pid: String): Option[Json] =
    json.hcursor.downField("assignable").as[List[Json]].toOption.getOrElse(Nil).find { e =>
      e.hcursor.downField("providerId").as[String].toOption.contains(pid)
    }

  private def numAt(e: Option[Json], f: String): Option[Long] =
    e.flatMap(_.hcursor.downField(f).as[Long].toOption)

  private def capAt(e: Option[Json]): Option[Option[Long]] =
    e.flatMap(_.hcursor.downField("cap").as[Option[Long]].toOption)

  private def boolAt(e: Option[Json], f: String): Option[Boolean] =
    e.flatMap(_.hcursor.downField(f).as[Boolean].toOption)

  // ── 派发钉链剔除账本（缺陷批 2026-11-06，provider-concurrency 批移交）─────────
  // 现网实证：`UTSC/glm-5.3-flash SILENTLY EXCLUDED（无原因剔除）` 反复出现——
  // provider 满载被链首扫描剔出的候选没有入账面。收口判据：assign 的快照必须携带
  // `preExcluded`（ref → ProviderFull(inUse, cap)），release/reconcile 清账，
  // 幂等 re-assign 不丢账。

  test("PE1: 满载 provider 的候选在派发钉链时被剔，且带 provider-full 原因进 preExcluded（不再无账消失）"):
    val cfg1 = NebflowServiceConfig(
      llm = ServiceLlmConfig(
        providers = Map("a" -> withCap(provider("m1"), Some(1)), "b" -> provider("m1")),
        fallback = Some(AgentModelConfig(Some("a/m1"), List("b/m1")))
      )
    )
    for
      reg <- mkRegistry(cfg1)
      chain <- orderedChainOf(reg, Some(AgentModelConfig(Some("a/m1"), List("b/m1"))))
      counts <- countsRef
      p1 <- ProviderConcurrency.assign(counts, cfg1, "actor-1", chain)
      p2 <- ProviderConcurrency.assign(counts, cfg1, "actor-2", chain)
      c <- counts.get
    yield
      assertEquals(chain, List(("a", "m1"), ("b", "m1")), "先验：a/m1 → b/m1")
      assertEquals(p1.map(_.headRef), Some("a/m1"), "actor-1 钉链首 a/m1")
      assertEquals(p1.map(_.preExcluded), Some(Nil), "链首本身无剔除")
      assertEquals(p2.map(_.headRef), Some("b/m1"), "actor-2 链首落第二档 b/m1")
      assertEquals(
        p2.map(_.preExcluded),
        Some(List("a/m1" -> CandidateSkipCause.ProviderFull(1, 1))),
        "被剔的 a/m1 必须带 provider-full(1 of 1) 原因进账——不许无账消失（UTSC 形态根治判据）"
      )
      assertEquals(c.byProvider.get("a"), Some(Set("actor-1")), "a 占坑 = actor-1")
      assertEquals(c.byProvider.get("b"), Some(Set("actor-2")), "b 占坑 = actor-2")

    end for

  test("PE2: 幂等 re-assign 不丢 preExcluded（重 spawn 场景：取首钉那一刻的账，不随现读计数漂移）"):
    val cfg1 = NebflowServiceConfig(
      llm = ServiceLlmConfig(
        providers = Map("a" -> withCap(provider("m1"), Some(1)), "b" -> provider("m1")),
        fallback = Some(AgentModelConfig(Some("a/m1"), List("b/m1")))
      )
    )
    for
      reg <- mkRegistry(cfg1)
      chain <- orderedChainOf(reg, Some(AgentModelConfig(Some("a/m1"), List("b/m1"))))
      counts <- countsRef
      _ <- ProviderConcurrency.assign(counts, cfg1, "actor-1", chain)
      p2 <- ProviderConcurrency.assign(counts, cfg1, "actor-2", chain)
      // a 的坑释放（现读 a 已空闲）——已钉 actor-2 重复询问不得因漂移改判/丢账
      _ <- ProviderConcurrency.release(counts, "actor-1")
      p2Again <- ProviderConcurrency.assign(counts, cfg1, "actor-2", chain)
    yield
      assertEquals(p2Again, p2, "整快照相等（含 preExcluded）——幂等不迁移不丢账")
      assertEquals(
        p2Again.map(_.preExcluded),
        Some(List("a/m1" -> CandidateSkipCause.ProviderFull(1, 1))),
        "剔除原因必须保持首钉时刻的账（a 此刻已空闲也不改写历史）"
      )

    end for

  test("PE3: release / reconcile 清剔除账（不留僵尸条目）"):
    val cfg1 = NebflowServiceConfig(
      llm = ServiceLlmConfig(
        providers = Map("a" -> withCap(provider("m1"), Some(1)), "b" -> provider("m1")),
        fallback = Some(AgentModelConfig(Some("a/m1"), List("b/m1")))
      )
    )
    for
      reg <- mkRegistry(cfg1)
      chain <- orderedChainOf(reg, Some(AgentModelConfig(Some("a/m1"), List("b/m1"))))
      counts <- countsRef
      _ <- ProviderConcurrency.assign(counts, cfg1, "actor-1", chain)
      _ <- ProviderConcurrency.assign(counts, cfg1, "actor-2", chain)
      withBoth <- counts.get
      _ <- ProviderConcurrency.release(counts, "actor-2")
      afterRelease <- counts.get
      _ <- ProviderConcurrency.reconcile(counts, Set("actor-1"))
      afterReconcile <- counts.get
    yield
      assert(withBoth.pinExclusions.contains("actor-2"), "actor-2 被剔时必须入账")
      assertEquals(
        afterRelease.pinExclusions.get("actor-2"),
        None,
        "release 必须连剔除账一起清（不留僵尸条目）"
      )
      assertEquals(afterRelease.pinExclusions.get("actor-1"), Some(Nil), "actor-1 无剔除 ⇒ 账为空表但保留")
      assertEquals(
        afterReconcile.pinExclusions.keySet,
        Set("actor-1"),
        "reconcile 以活集裁账（死 actor 的剔除账不复活）"
      )

    end for

  test("PE4: 全满 ⇒ assign = None 且剔除账作废（无 pin 就无账，回落既有全局行为）"):
    val cfgFull = NebflowServiceConfig(
      llm = ServiceLlmConfig(
        providers = Map(
          "a" -> withCap(provider("m1"), Some(1)),
          "b" -> withCap(provider("m1"), Some(1))
        ),
        fallback = Some(AgentModelConfig(Some("a/m1"), List("b/m1")))
      )
    )
    for
      reg <- mkRegistry(cfgFull)
      chain <- orderedChainOf(reg, Some(AgentModelConfig(Some("a/m1"), List("b/m1"))))
      counts <- countsRef
      p1 <- ProviderConcurrency.assign(counts, cfgFull, "actor-1", chain)
      p2 <- ProviderConcurrency.assign(counts, cfgFull, "actor-2", chain)
      p3 <- ProviderConcurrency.assign(counts, cfgFull, "actor-3", chain)
      c <- counts.get
    yield
      assertEquals(p1.map(_.headRef), Some("a/m1"), "actor-1 钉 a/m1")
      assertEquals(p2.map(_.headRef), Some("b/m1"), "actor-2 钉 b/m1（a 已满）")
      assertEquals(p2.flatMap(_.preExcluded.headOption.map(_._1)), Some("a/m1"), "actor-2 的剔除账 = a/m1")
      assertEquals(p3, None, "全满 ⇒ None（不钉，零新失败模式）")
      assertEquals(c.pinExclusions.get("actor-3"), None, "不钉的 actor 不得留剔除账")

    end for

end ProviderConcurrencyFallbackSpec
