package nebflow.llm

import munit.FunSuite
import nebflow.llm.rules.{ModelRuleDsl, ModelRuleStore}
import nebflow.shared.*
import java.time.LocalDateTime

/**
 * P3 接线：规则脚本改写请求候选链（纯函数 `ModelDecision.applyRules`，脚本显式传入）。
 * 不触碰数据根；生产入口 `applyRulesNow` 仅在脚本文件存在时生效。
 */
class ModelRuleApplySpec extends FunSuite:

  private def provider(models: String*): ProviderConfig =
    ProviderConfig(
      baseUrl = "http://127.0.0.1:1/",
      apiKey = "fake-key-not-a-secret",
      protocol = LlmProtocol.OpenAI,
      models = models.toList.map(m => ModelConfig(m, contextWindow = 1000))
    )

  private val pa = provider("m1")
  private val pb = provider("m1")
  private def cand(pid: String, p: ProviderConfig): ModelCandidate = ModelCandidate(pid, p, "m1")

  private val chain: List[ModelCandidate] = List(cand("a", pa), cand("b", pb))
  private val at = LocalDateTime.of(2026, 11, 23, 10, 0) // 周一 10:00

  private def loadedOf(src: String): ModelRuleStore.Loaded =
    ModelRuleDsl.parse(src) match
      case Right(s) => ModelRuleStore.Loaded(s, None)
      case Left(e) => fail(s"fixture parse failed: $e")

  private def refs(cs: List[ModelCandidate]): List[String] = cs.map(ModelDecision.refOf)

  test("空脚本 ⇒ 候选逐项相同（P1 恒等）") {
    val (out, hits) = ModelDecision.applyRules(ModelRuleStore.EmptyLoaded, "main-chat", at, chain)
    assertEquals(out, chain)
    assertEquals(hits, Nil)
  }

  test("无命中（时段外）⇒ 候选原样") {
    val l = loadedOf("rule 夜间冻结 { when time 23:00..07:00 freeze }")
    val (out, hits) = ModelDecision.applyRules(l, "main-chat", at, chain)
    assertEquals(out, chain)
    assertEquals(hits, Nil)
  }

  test("freeze 命中时段 ⇒ 剔除匹配 provider，trace 记 freeze") {
    val l = loadedOf("rule 白天冻结 { when time 09:00..18:00 freeze model a/* }")
    val (out, hits) = ModelDecision.applyRules(l, "main-chat", at, chain)
    assertEquals(refs(out), List("b/m1"))
    assert(hits.exists(h => h.step == "rule" && h.detail.contains("freeze")), clue(hits))
  }

  test("model 链首重排：高优先级规则的 model 成为链首，其余保序") {
    val l = loadedOf("rule 走b { priority 10 when time 09:00..18:00 model b/m1 }")
    val (out, _) = ModelDecision.applyRules(l, "main-chat", at, chain)
    assertEquals(refs(out), List("b/m1", "a/m1"))
  }

  test("call 维度：规则只作用于指定调用点") {
    val l = loadedOf("rule 小LLM冻结 { call small-llm freeze model a/* }")
    val (main, _) = ModelDecision.applyRules(l, "main-chat", at, chain)
    val (small, _) = ModelDecision.applyRules(l, "small-llm", at, chain)
    assertEquals(refs(main), refs(chain))
    assertEquals(refs(small), List("b/m1"))
  }

  test("未知调用点 fail-closed 按 small-llm 语义回落，trace 记 unknown-call-point") {
    val l = loadedOf("rule 小LLM冻结 { call small-llm freeze model a/* }")
    val (out, hits) = ModelDecision.applyRules(l, "no-such-call", at, chain)
    assertEquals(refs(out), List("b/m1"))
    assert(hits.exists(_.detail.contains("unknown-call-point")), clue(hits))
  }

  test("脚本解析失败 ⇒ 不应用规则、候选原样，trace 记 script-invalid") {
    val bad = ModelRuleStore.Loaded(ModelRuleDsl.Script(Nil), Some("line 1: 语法错误"))
    val (out, hits) = ModelDecision.applyRules(bad, "main-chat", at, chain)
    assertEquals(out, chain)
    assert(hits.exists(_.detail.contains("script-invalid")), clue(hits))
  }

  test("evaluate 给出的 ref 在原候选中找不到时忽略，不凭空造候选") {
    val l = loadedOf("rule 走z { when time 09:00..18:00 model z/m9 }")
    val (out, _) = ModelDecision.applyRules(l, "main-chat", at, chain)
    assertEquals(refs(out), List("a/m1", "b/m1"))
  }


  test("P6 三态门：仅 rules 态暴露脚本；缺失/非法模式回落 follow") {
    val root = os.temp.dir(prefix = "mr-gate-")
    val l = loadedOf("rule 冻结 { when time 09:00..18:00 freeze model a/* }")
    assertEquals(ModelRuleStore.effective(root, l), ModelRuleStore.EmptyLoaded)
    ModelRuleStore.setMode(root, "slots")
    assertEquals(ModelRuleStore.effective(root, l), ModelRuleStore.EmptyLoaded)
    ModelRuleStore.setMode(root, "rules")
    assertEquals(ModelRuleStore.effective(root, l), l)
    os.write.over(ModelRuleStore.modeOf(root), "bogus")
    assertEquals(ModelRuleStore.mode(root), "follow")
    assert(ModelRuleStore.setMode(root, "bogus").isLeft)
  }

  test("P6 保存期校验：不合格脚本返回行号错误且不落盘") {
    val root = os.temp.dir(prefix = "mr-save-")
    val cat = ModelRuleDsl.Catalog(Map("a" -> Set("m1")))
    val r = ModelRuleStore.save(root, "rule r { model nope/x }", cat)
    assert(r.isLeft, clue(r))
    assert(!os.exists(ModelRuleStore.scriptOf(root)))
    assert(ModelRuleStore.save(root, "rule r { model a/m1 }", cat).isRight)
    assert(os.exists(ModelRuleStore.scriptOf(root)))
  }
