package nebflow.core

import cats.effect.unsafe.implicits.global
import io.circe.syntax.*
import munit.FunSuite
import nebflow.agent.AgentLibrary
import nebflow.core.entity.BuiltinAgents
import nebflow.llm.ProviderRegistry
import nebflow.shared.{
  AgentModelConfig,
  LlmProtocol,
  ModelConfig,
  NebflowServiceConfig,
  PathUtil,
  ProviderConfig,
  ServiceLlmConfig
}

/**
 * /model → fallback-anchor wiring spec (2026-10-29 author review):
 *
 * The /model chain face stores an ordered `{preferred, fallbacks}` chain on
 * disk and reports every follower as inheriting the Nebula primary chain
 * (`SchemePolicy.resolveModel`). The LLM fallback path consumes exactly that
 * resolved `AgentDef.model` via `ProviderRegistry.getCandidatesForAgent`.
 * These tests pin the two ends together: a /model write must become the
 * runtime candidate-chain prefix on the next per-turn reload, not fall back
 * to a dispatcher/default-preset/config-seed chain.
 */
class ModelChainAnchorSpec extends FunSuite:

  private val executorName: String = BuiltinAgents.ExecutorName

  private val modelChain = AgentModelConfig(
    preferred = Some("zhipu/glm-primary"),
    fallbacks = List("kimi/k3-fallback")
  )

  private val updatedModelChain = AgentModelConfig(
    preferred = Some("kimi/k3-primary"),
    fallbacks = List("zhipu/glm-fallback")
  )

  private val dispatcherChain = AgentModelConfig(
    preferred = Some("dispatcher/private"),
    fallbacks = Nil
  )

  private def withRoot(name: String)(body: os.Path => Unit): Unit =
    val tmp = os.pwd / "target" / s"model-chain-anchor-$name-${System.nanoTime().toHexString}"
    os.makeDir.all(tmp)
    val originalRoot = PathUtil.dataRoot
    PathUtil.setDataRoot(tmp)
    try body(tmp)
    finally
      PathUtil.setDataRoot(originalRoot)
      os.remove.all(tmp)
    end try
  end withRoot

  private def writeModel(root: os.Path, agent: String, chain: AgentModelConfig): Unit =
    val dir = root / "agents" / agent
    os.makeDir.all(dir)
    os.write.over(
      dir / "agent.json",
      io.circe.Json
        .obj(
          "name" -> agent.asJson,
          "model" -> chain.asJson
        )
        .noSpaces
    )

  private def writeFixture(root: os.Path): Unit =
    // The config-provider seed chain is deliberately different from the /model
    // chain: if runtime resolution consults a fixed/default chain instead of
    // the /model anchor, this fixture exposes it as a wrong candidate prefix.
    os.write.over(
      root / nebflow.shared.Branding.configFileName,
      """{"llm":{"providers":{
        |"legacy":{"baseUrl":"http://127.0.0.1:1","apiKey":"k","protocol":"openai","models":[{"id":"first-model","contextWindow":8192}]},
        |"zhipu":{"baseUrl":"http://127.0.0.1:2","apiKey":"k","protocol":"openai","models":[{"id":"glm-primary","contextWindow":8192},{"id":"glm-fallback","contextWindow":8192}]},
        |"kimi":{"baseUrl":"http://127.0.0.1:3","apiKey":"k","protocol":"openai","models":[{"id":"k3-fallback","contextWindow":8192},{"id":"k3-primary","contextWindow":8192}]},
        |"dispatcher":{"baseUrl":"http://127.0.0.1:4","apiKey":"k","protocol":"openai","models":[{"id":"private","contextWindow":8192}]}
        |}}}""".stripMargin
    )
    writeModel(root, SchemePolicy.NebulaName, modelChain)
    writeModel(root, SchemePolicy.DispatcherName, dispatcherChain)
  end writeFixture

  private def registry(root: os.Path): ProviderRegistry =
    val config = NebflowServiceConfig(
      llm = ServiceLlmConfig(
        providers = Map(
          "legacy" -> ProviderConfig(
            "http://127.0.0.1:1",
            "k",
            LlmProtocol.OpenAI,
            List(ModelConfig("first-model", 8192))
          ),
          "zhipu" -> ProviderConfig(
            "http://127.0.0.1:2",
            "k",
            LlmProtocol.OpenAI,
            List(ModelConfig("glm-primary", 8192), ModelConfig("glm-fallback", 8192))
          ),
          "kimi" -> ProviderConfig(
            "http://127.0.0.1:3",
            "k",
            LlmProtocol.OpenAI,
            List(ModelConfig("k3-fallback", 8192), ModelConfig("k3-primary", 8192))
          ),
          "dispatcher" -> ProviderConfig(
            "http://127.0.0.1:4",
            "k",
            LlmProtocol.OpenAI,
            List(ModelConfig("private", 8192))
          )
        )
      )
    )
    new ProviderRegistry(
      cats.effect.Ref.unsafe(config),
      null.asInstanceOf[sttp.client4.StreamBackend[cats.effect.IO, sttp.capabilities.fs2.Fs2Streams[cats.effect.IO]]]
    )

  end registry

  private def candidateRefs(reg: ProviderRegistry, chain: AgentModelConfig): List[String] =
    reg.getCandidatesForAgent(Some(chain)).unsafeRunSync().map(c => s"${c.providerId}/${c.model}")

  test(
    "builtin executor: a /model Nebula write is the chain anchor (single-root), ahead of config/dispatcher defaults"
  ) {
    withRoot("builtin-executor") { root =>
      writeFixture(root)

      val entry = BuiltinAgents.entry(executorName).getOrElse(fail(s"builtin entry missing: $executorName"))
      val agentDef = entry.toAgentDef
      assertEquals(
        agentDef.model,
        Some(modelChain),
        "executor runtime AgentDef.model must be the /model Nebula chain (the fallback anchor)"
      )

      // fallback 废除批（作者令 2026-11-06 20:36「不再支持模型fallback」）：链 =
      // preferred 单根（registry.scala getCandidatesForAgentUnfiltered 在案），
      // AgentModelConfig.fallbacks 退役为死数据不进请求链——恰一候选即正钉。
      val refs = candidateRefs(registry(root), agentDef.model.get)
      assertEquals(
        refs,
        List("zhipu/glm-primary"),
        s"the /model chain must be the single-root candidate chain (fallbacks retired); actual chain = $refs"
      )
      assert(!refs.contains("dispatcher/private"), "a hidden dispatcher own chain must not hijack the executor anchor")
    }
  }

  test("per-turn reload: changing /model re-anchors the candidate chain without restart") {
    withRoot("live-reanchor") { root =>
      writeFixture(root)
      val before = BuiltinAgents.entry(executorName).getOrElse(fail("builtin entry missing")).toAgentDef
      assertEquals(before.model, Some(modelChain))

      // The /model PUT arm persists exactly this shape on the Nebula sidecar.
      writeModel(root, SchemePolicy.NebulaName, updatedModelChain)

      val after = BuiltinAgents.entry(executorName).getOrElse(fail("builtin entry missing")).toAgentDef
      assertEquals(
        after.model,
        Some(updatedModelChain),
        "a fresh AgentEntry read after the /model write must see the new fallback anchor"
      )
      // 重锚 + 链深恒 1：新 preferred 即整链；退役 fallback 不回流。
      assertEquals(
        candidateRefs(registry(root), after.model.get),
        List("kimi/k3-primary"),
        "the runtime candidate chain must follow the updated /model anchor (single-root, no fallback revival)"
      )
    }
  }

  test("disk custom agent: stored refs stay audit-only and the agent follows the /model Nebula chain") {
    withRoot("disk-custom") { root =>
      writeFixture(root)
      val customDir = root / "agents" / "Coder"
      os.makeDir.all(customDir)
      os.write.over(
        customDir / "agent.json",
        """{"name":"Coder","description":"custom","model":{"preferred":"custom/private","fallbacks":[]}}"""
      )
      os.write.over(customDir / "system.md", "custom")

      val lib = new AgentLibrary(root / "agents")
      val custom = lib.loadFromDir(customDir).getOrElse(fail("custom agent def missing"))
      assertEquals(
        custom.model,
        Some(modelChain),
        "a non-settable custom agent must follow the /model Nebula chain; its stored model is audit-only"
      )
      assertEquals(
        candidateRefs(registry(root), custom.model.get),
        List("zhipu/glm-primary"),
        "the custom agent follows the /model anchor as a single-root chain"
      )
    }
  }
end ModelChainAnchorSpec
