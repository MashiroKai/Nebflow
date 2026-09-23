package nebflow.llm

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import munit.CatsEffectSuite
import nebflow.shared.*

/**
 * 案② 红验锚点 1（LLM 硬杀波 · `chain-llmstall-fix`，2026-09-21 作者绿灯）——
 * **per-model 上下文上限 clamp**：`effective = min(configured, modelMaxContext)`。
 *
 * 病（定谳报告 `20260921_182544` 核查 2）：`contextWindow` 的唯一来源是用户配置，
 * 全链不存在与 provider 真值比对的地方 ⇒ 全局默认（1M）可击穿任一 model 的真实上限；
 * 唯一真值口（模型列举端点的 `contextLength`）被前端 `fillContextIfEmpty` 的空值早退
 * 分支吃掉、永远落不了地。
 *
 * 断言（全部二值）：
 *   ① `configured = 1000000, modelMaxContext = 200000` ⇒ `ModelCandidate.contextWindow == 200000`；
 *   ② `modelMaxContext = None` ⇒ `== configured`（**逐字守旧行为**，防「顺手收紧」）；
 *   ③ 压缩链按 **clamped** 值取门限（1M→200k 时应为 `200000 * 0.8 = 160000`，而不是
 *      1M 档的固定 256000）。
 *
 * 变异验红（读数见收尾报告）：把 `ProviderRegistry.effectiveContextWindow` 的
 * `math.min` 换成 `configured` ⇒ T1 / T3 红。
 */
class PerModelContextClampSpec extends CatsEffectSuite:

  private def configWith(configured: Int, modelMax: Option[Int]): NebflowServiceConfig =
    NebflowServiceConfig(
      llm = ServiceLlmConfig(
        providers = Map(
          "p" -> ProviderConfig(
            baseUrl = "http://127.0.0.1:1",
            apiKey = "test",
            protocol = LlmProtocol.Anthropic,
            models = List(ModelConfig("m", contextWindow = configured, modelMaxContext = modelMax))
          )
        )
      )
    )

  private def candidateFor(configured: Int, modelMax: Option[Int]): IO[ModelCandidate] =
    for
      configRef <- Ref.of[IO, NebflowServiceConfig](configWith(configured, modelMax))
      sessionOverrides <- Ref.of[IO, Map[String, ModelCandidate]](Map.empty)
      triple <- LlmInterface.createLlm(sessionOverrides, None, Some(configRef))
      registry = triple._2
      release = triple._4
      c <- registry.getCandidateForRef("p/m").map(_.getOrElse(fail("candidate must resolve")))
      chain <- registry.getCandidates()
      _ <- release
    yield
      // 全局链腿（同文件第二处取数单点）必须给出同一读数——两腿分叉 = clamp 有洞。
      assertEquals(
        chain.find(c0 => c0.providerId == "p" && c0.model == "m").map(_.contextWindow),
        Some(c.contextWindow),
        "getCandidates()（全局链腿）与 getCandidateForRef（按 ref 腿）必须给出同一 clamp 结果"
      )
      c

  test("T1: configured=1000000 + modelMax=200000 ⇒ 生效窗口被 clamp 到 200000") {
    candidateFor(1000000, Some(200000)).map { c =>
      assertEquals(c.contextWindow, 200000, "provider 真值必须压低配置值")
      assertEquals(c.modelMaxContext, Some(200000), "真值随候选保留（运行时 / 取证面可读）")
    }
  }

  test("T2: modelMax=None（旧配置 / provider 未上报）⇒ 逐字等于配置值（禁顺手收紧）") {
    candidateFor(1000000, None).map { c =>
      assertEquals(c.contextWindow, 1000000, "真值未知时不得改动既有行为")
      assertEquals(c.modelMaxContext, None)
    }
  }

  test("T2b: clamp 只可能压低、不可能放大（configured < modelMax ⇒ 取 configured）") {
    candidateFor(128000, Some(200000)).map { c =>
      assertEquals(c.contextWindow, 128000, "配置值更小时不被真值放大")
    }
  }

  test("T3: 压缩门限按 clamped 值取（1M→200k 的门限 = 160k，而非 1M 档的 256k）") {
    candidateFor(1000000, Some(200000)).map { c =>
      val thresholdAt1M = nebflow.core.compact.CompactThreshold.threshold(1000000)
      val clampedThreshold = nebflow.core.compact.CompactThreshold.threshold(c.contextWindow)
      assertEquals(thresholdAt1M, 256000, "1M 档既有读数（回归锚，非本批改动）")
      assertEquals(c.contextWindow, 200000)
      assertEquals(
        clampedThreshold,
        160000,
        "clamp 后必须走 cw ≤ 300k 分支的 0.8 倍门限——若仍读 256000，说明压缩链吃的是未 clamp 的窗口"
      )
    }
  }

  test("T5: 储备层（第四构造点）同受 clamp——provchain 腿 a 追加腿不得绕过真值上界") {
    // 第四构造点 = registry.reserveTier（provchain 腿 a，晚于卡文成文合入 main；
    // 卡文 B2 只点名两处 ⇒ 本测试是「偏离登记」的钉版：任何后续构造点绕过
    // effectiveContextWindow 都会在这里红。
    for
      configRef <- Ref.of[IO, NebflowServiceConfig](
        NebflowServiceConfig(
          llm = ServiceLlmConfig(
            providers = Map(
              "p" -> ProviderConfig(
                baseUrl = "http://127.0.0.1:1",
                apiKey = "test",
                protocol = LlmProtocol.Anthropic,
                models = List(
                  // agent 链只引 m1 ⇒ m2 不被引用 ⇒ 经储备层（reserveTier）追加进链尾
                  ModelConfig("m1", contextWindow = 1000000),
                  ModelConfig("m2", contextWindow = 1000000, modelMaxContext = Some(200000))
                )
              )
            )
          )
        )
      )
      sessionOverrides <- Ref.of[IO, Map[String, ModelCandidate]](Map.empty)
      triple <- LlmInterface.createLlm(sessionOverrides, None, Some(configRef))
      registry = triple._2
      chain <- registry.getCandidatesForAgent(
        Some(AgentModelConfig(preferred = Some("p/m1"), fallbacks = Nil))
      )
      _ <- triple._4
    yield
      val reserve = chain.find(c => c.providerId == "p" && c.model == "m2")
      assert(
        reserve.isDefined,
        s"m2 应经储备层进链（agent 链未引用它），实际链：${chain.map(c => s"${c.providerId}/${c.model}")}"
      )
      assertEquals(
        reserve.get.contextWindow,
        200000,
        "储备层候选必须同受 min(configured, modelMaxContext) 约束（绕过 = clamp 有洞）"
      )
      assertEquals(reserve.get.modelMaxContext, Some(200000), "真值随候选保留")
  }

  /**
   * 建一个注册表实例供纯算式读数用。注意 `effectiveContextWindow` 是 **class
   * ProviderRegistry 上的实例方法**（不是伴生对象成员）——三个 ModelCandidate 构造点
   * 全部经它 ⇒ 「单点」成立；spec 侧走实例调用（salvage 草稿写成伴生对象调用 ⇒ 编译
   * 不过，前身从未编译过）。
   */
  private def withRegistry[A](f: ProviderRegistry => A): IO[A] =
    for
      configRef <- Ref.of[IO, NebflowServiceConfig](configWith(1, None))
      sessionOverrides <- Ref.of[IO, Map[String, ModelCandidate]](Map.empty)
      triple <- LlmInterface.createLlm(sessionOverrides, None, Some(configRef))
      out <- IO(f(triple._2)).guarantee(triple._4)
    yield out

  test("T4: 纯算式单点（effectiveContextWindow）——与候选构造读数一致") {
    withRegistry { reg =>
      assertEquals(reg.effectiveContextWindow(1000000, Some(200000)), 200000)
      assertEquals(reg.effectiveContextWindow(1000000, None), 1000000)
      assertEquals(reg.effectiveContextWindow(128000, Some(200000)), 128000)
      assertEquals(reg.effectiveContextWindow(200000, Some(200000)), 200000)
    }
  }
end PerModelContextClampSpec
