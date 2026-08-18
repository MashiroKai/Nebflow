package nebflow.agent

import munit.FunSuite
import nebflow.core.presets.{ModelPreset, PresetFile, PresetStore}
import nebflow.llm.ColdStartConfig
import nebflow.shared.{AgentModelConfig, ContentBlock, Message, MessageRole}

/**
 * Cold-start routing guards (A, 2026-08-18) — pure decision logic, no actors.
 *
 * All guards must pass to route a wake-up request to the low-cost chain:
 * enabled, depth==0, category != flow, idle >= threshold, no images, not
 * already on the free gateway.
 */
class ColdStartRouterSpec extends FunSuite:

  private def storeWithLowCost(): PresetStore =
    val dir = os.pwd / "target" / "cold-start-test" / s"store-${System.nanoTime()}"
    os.makeDir.all(dir)
    val path = dir / "model-presets.json"
    val store = new PresetStore(path)
    store.save(
      PresetFile(
        "general",
        Map(
          "LowCost" -> ModelPreset("LowCost", "", Some("107/deepseek-v4-flash-ascend"), List("107/deepseek-v4-pro")),
          "general" -> ModelPreset("general", "", Some("deepseek/deepseek-v4-flash"), Nil)
        )
      )
    )
    store

  private val now = 1_800_000_000_000L
  private val idleThreshold = 30 * 60 * 1000L
  private val defaultCfg = Some(ColdStartConfig())
  private val paidModel = Some(AgentModelConfig(Some("deepseek/deepseek-v4-flash"), Nil))

  private def textMsg(text: String, ts: Long = now): Message =
    Message(MessageRole.User, Left(text), ts)

  private def imgMsg(ts: Long = now): Message =
    Message(MessageRole.User, Right(List(ContentBlock.Image("base64data", "image/png"))), ts)

  private def evaluate(
    cfg: Option[ColdStartConfig] = defaultCfg,
    depth: Int = 0,
    category: String = "team",
    lastActivityMs: Long = now - 2 * 60 * 60 * 1000L, // 2h idle → cold
    messages: List[Message] = List(textMsg("hello")),
    currentModel: Option[AgentModelConfig] = paidModel,
    store: PresetStore = storeWithLowCost()
  ): Option[AgentModelConfig] =
    ColdStartRouter.evaluate(cfg, "Backend", depth, category, now, lastActivityMs, messages, currentModel, store)

  private val lowCostChain = AgentModelConfig(Some("107/deepseek-v4-flash-ascend"), List("107/deepseek-v4-pro"))

  test("cold idle wake-up routes to LowCost chain"):
    assertEquals(evaluate(), Some(lowCostChain))

  test("disabled → no routing"):
    assertEquals(evaluate(cfg = Some(ColdStartConfig(enabled = Some(false)))), None)

  test("sub-agents (depth > 0) never cold-start routed"):
    assertEquals(evaluate(depth = 1), None)

  test("flow agents never cold-start routed"):
    assertEquals(evaluate(category = "flow"), None)

  test("hot agent (idle < threshold) not routed"):
    assertEquals(evaluate(lastActivityMs = now - 60 * 1000L), None)

  test("custom threshold respected"):
    // 20min idle: cold for a 10min threshold, hot for the default 30min.
    val twentyMinIdle = now - 20 * 60 * 1000L
    assertEquals(evaluate(lastActivityMs = twentyMinIdle), None, "default 30min → hot")
    assertEquals(
      evaluate(lastActivityMs = twentyMinIdle, cfg = Some(ColdStartConfig(idleThresholdMs = Some(10 * 60 * 1000L)))),
      Some(lowCostChain),
      "custom 10min → cold"
    )

  test("image-bearing wake-up not routed (LowCost may lack vision)"):
    assertEquals(evaluate(messages = List(textMsg("go"), imgMsg())), None)

  test("agent already on free gateway (107 preferred) not routed"):
    val already107 = Some(AgentModelConfig(Some("107/glm-5.2-107"), Nil))
    assertEquals(evaluate(currentModel = already107), None)

  test("missing LowCost preset falls back to builtin 107 chain"):
    val bareStore = {
      val dir = os.pwd / "target" / "cold-start-test" / s"bare-${System.nanoTime()}"
      os.makeDir.all(dir)
      val store = new PresetStore(dir / "model-presets.json")
      store.save(PresetFile("general", Map("general" -> ModelPreset("general", "", Some("deepseek/x"), Nil))))
      store
    }
    val result = evaluate(store = bareStore)
    assert(result.isDefined)
    assertEquals(result.get.preferred, Some("107/deepseek-v4-flash-ascend"))
    assert(result.get.fallbacks.exists(_.startsWith("107/")))

  test("custom preset name used when configured"):
    val store = storeWithLowCost()
    store.save(
      PresetFile(
        "general",
        Map(
          "MyCost" -> ModelPreset("MyCost", "", Some("107/glm-5.2-107"), Nil),
          "general" -> ModelPreset("general", "", Some("deepseek/x"), Nil)
        )
      )
    )
    val result = evaluate(store = store, cfg = Some(ColdStartConfig(preset = Some("MyCost"))))
    assertEquals(result, Some(AgentModelConfig(Some("107/glm-5.2-107"), Nil)))

  test("first-ever request (no records) counts as idle"):
    // lastActivityMs = 0 → idle → routed (post-restart / brand-new agent).
    assertEquals(evaluate(lastActivityMs = 0L), Some(lowCostChain))

end ColdStartRouterSpec
