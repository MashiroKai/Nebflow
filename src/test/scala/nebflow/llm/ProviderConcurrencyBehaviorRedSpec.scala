package nebflow.llm

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import munit.CatsEffectSuite
import nebflow.shared.*
import sttp.capabilities.fs2.Fs2Streams
import sttp.client4.StreamBackend

/**
 * provider 并发上限 + 派发时刻链快照 —— 改前行为红（red-before · 可编译腿）。
 *
 * 本文件只使用**现存公共缝**（`ProviderRegistry.getCandidatesForAgentUnfiltered`
 * + 它背后的内存 `configRef` 热更），不引用本批任何新符号（`nebflow.llm.
 * ProviderConcurrency` / `PinnedChain` / `maxConcurrentActors` / `providerChainPin`
 * / `providerSlotRelease` —— 那些在 `ProviderConcurrencyFallbackSpec`，改前以
 * 编译红取证）。因此本文件**改前即可编译运行**，两条用例断言的是**期望行为**，
 * 改前必然断言失败 —— 失败输出即 02-red-before.txt 的「行为红」证据。
 *
 * 🔴 处置约定（实现落地后，二选一，须在 02-red-before.txt 记录删了什么/为什么）：
 *   · 删除（推荐）：裸 registry 缝实现后依然不感知占坑（占坑在
 *     `ProviderConcurrency.assign` 一层），本文件断言面已由 T1（第 11 个落二档、
 *     幂等不迁移）与 T2（每请求 agentModel 不变 = 运行期不得重排）完整承接；
 *   · 转换：BR-2 改写为「经 providerChainPin 的钉定 actor 在配置热更下 N 次请求
 *     链首不变」的端到端回归断言；BR-1 的期望腿由 T1 承接。
 *
 * 边界实录（防止误读断言）：候选解析对**已离场 provider** 的 ref 是 fail-closed
 * （解不出 ⇒ 跳过/回落全局链）——「钉定」保护的是 actor 每请求收到的 agentModel
 * 快照（引擎发什么），不是把已不存在的 provider 硬调（解析面照旧适配现实）。
 *
 * 断言纪律：配置全部在 spec 内构造，不读/不写真实 HOME（非空 `llm.fallback`
 * 把解析钉在 config 腿——先例 `FallbackChainCandidateSpec`）。
 */
class ProviderConcurrencyBehaviorRedSpec extends CatsEffectSuite:

  private val NullBackend = null.asInstanceOf[StreamBackend[IO, Fs2Streams[IO]]]

  private def provider(models: String*): ProviderConfig =
    ProviderConfig(
      baseUrl = "http://127.0.0.1:1/",
      apiKey = "fake-key-not-a-secret",
      protocol = LlmProtocol.OpenAI,
      models = models.toList.map(m => ModelConfig(m, contextWindow = 1000))
    )

  private def cfg(chain: Option[AgentModelConfig], providers: (String, ProviderConfig)*): NebflowServiceConfig =
    NebflowServiceConfig(llm = ServiceLlmConfig(providers = providers.toMap, fallback = chain))

  /** registry + 它背后的 configRef：热更腿用同一个 Ref，模拟运行期配置热更。 */
  private def mkRegistry(
    c: NebflowServiceConfig
  ): IO[(ProviderRegistry, Ref[IO, NebflowServiceConfig])] =
    Ref.of[IO, NebflowServiceConfig](c).map(ref => (new ProviderRegistry(ref, NullBackend), ref))

  private def headOf(cs: List[ModelCandidate]): String =
    cs.headOption.map(c => s"${c.providerId}/${c.model}").getOrElse("<empty>")

  private val chainAB: Option[AgentModelConfig] = Some(AgentModelConfig(Some("a/m1"), List("b/m1")))

  test("BR-1 无并发上限实录：11 个 actor 连续派发，第 11 个的链首仍是第一档 a/m1（期望 = 落第二档 b/m1）"):
    for
      (reg, _) <- mkRegistry(cfg(chainAB, "a" -> provider("m1"), "b" -> provider("m1")))
      // 11 次派发 = 引擎现状下 11 次「链首解析」：同一配置、没有任何 actor 占坑概念
      heads <- (1 to 11).toList.traverse(_ => reg.getCandidatesForAgentUnfiltered(chainAB).map(headOf))
    yield
      // 改前实录行（当前恒真）：每一次派发都重算同一配置链 ⇒ 11 个全落 a/m1。
      // 实现落地后本行若失败 = 解析面被改动，须人工核对（哨兵）。
      assertEquals(
        heads.distinct,
        List("a/m1"),
        s"改前实录行：11 次派发链首全部 = a/m1（引擎无 actor 占坑机制），实际 = ${heads.mkString(",")}"
      )
      // 改前红断言（期望行为）：并发上限生效后，第 11 个 actor 的链首必须换档
      assert(
        heads(10) != "a/m1",
        s"第 11 个 actor 的链首 = ${heads(10)} —— 仍是第一档 a/m1：无并发上限（red-before；实现后由 ProviderConcurrencyFallbackSpec.T1 承接回归断言）"
      )

  test("BR-2 同会话链首漂移实录：派发后配置热更（链序翻转），同一 actor 的链首跟着漂移（期望：派发时刻钉定不动）"):
    for
      (reg, configRef) <- mkRegistry(cfg(chainAB, "a" -> provider("m1"), "b" -> provider("m1")))
      // 派发时刻：actor-X 的链首 = a/m1（此后应作为快照钉定，actor 终生不变）
      atDispatch <- reg.getCandidatesForAgentUnfiltered(chainAB).map(headOf)
      // 运行期配置热更：llm.fallback 链序翻转（面板/文件编辑的日常形态）
      _ <- configRef.set(
        cfg(Some(AgentModelConfig(Some("b/m1"), List("a/m1"))), "a" -> provider("m1"), "b" -> provider("m1"))
      )
      // 同一 actor 的后续请求：引擎现状 = 每 turn 重导出 agentModel（ContextRefresher
      // refreshTurn 现读 fallback/agent.json）再交本缝解析 —— 参数即重导出的新链
      newChain <- configRef.get.map(_.llm.fallback)
      reads <- (1 to 5).toList.traverse(_ => reg.getCandidatesForAgentUnfiltered(newChain).map(headOf))
    yield
      assertEquals(atDispatch, "a/m1", "派发时刻链首基线必须是 a/m1")
      // 改前红断言（期望行为）：派发后钉定 ⇒ 同一 actor 的 5 次请求链首不得漂移
      assertEquals(
        reads.distinct,
        List("a/m1"),
        s"同一 actor 派发后的 5 次请求链首 = ${reads.distinct} —— 跟着配置热更漂移：每请求重算、无按 actor 钉定" +
          "（red-before；实现后由 ProviderConcurrencyFallbackSpec.T2 的『每请求 agentModel 不变』回归面承接）"
      )

end ProviderConcurrencyBehaviorRedSpec
