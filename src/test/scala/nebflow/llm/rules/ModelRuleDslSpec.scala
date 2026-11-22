package nebflow.llm.rules

import java.time.LocalDateTime

import munit.FunSuite
import nebflow.llm.rules.ModelRuleDsl.*

/**
 * 模型分配规则 DSL（P2）验收：
 *   (a) §3.4 八例全部解析通过并求值为预期候选；
 *   (b) 冲突与未知 provider 保存期报错；
 *   (c) 无规则会话的候选与基线逐项相同（P1 零行为变化口径）；
 *   (d) 框图 JSON 节点数与 srcLine 正确。
 */
class ModelRuleDslSpec extends FunSuite:

  private val catalog = Catalog(Map(
    "Irical" -> Set("gpt-6.1-terra"),
    "deepseek" -> Set("deepseek-flash"),
    "zhipu" -> Set("glm-5"),
    "kimi" -> Set("k2")
  ))

  /** 基线候选链（无规则时的现行链）。 */
  private val base = List("Irical/gpt-6.1-terra", "deepseek/deepseek-flash", "zhipu/glm-5", "kimi/k2")

  // 2026-11-21 = 周六，2026-11-23 = 周一（ISO：周一=1 … 周日=7）
  private def at(day: Int, h: Int, m: Int): LocalDateTime = LocalDateTime.of(2026, 11, day, h, m)
  private val Mon = 23
  private val Sat = 21
  private val Sun = 22

  private def sess(level: Option[String], call: String, t: LocalDateTime, slot: Option[String] = None): Session =
    Session(level, slot, call, t)

  private def scriptOf(src: String): Script = parse(src) match
    case Right(s) => s
    case Left(errs) => fail(s"解析失败：$errs")

  private def checkOk(src: String): Script = check(src, catalog) match
    case Right(s) => s
    case Left(errs) => fail(s"保存期校验失败：$errs")

  private def checkErr(src: String): List[RuleError] = check(src, catalog) match
    case Right(_) => fail("应当保存期报错，实际通过")
    case Left(errs) => errs

  // ── §3.4 八例（作者原话，逐字照录设计稿）─────────────────────────────────

  private val Ex1 = """
rule 计费时段避让 {
  when time 14:00..18:00
  unavailable zhipu/*, kimi/*
}
"""
  private val Ex2 = """rule 白天主流程 {
  when time 09:00..18:00
  level root, team
  model Irical/gpt-6.1-terra
  concurrency Irical = 10
  overflow deepseek/deepseek-flash      # 仅派发时新 actor（OQ1 已拍板）
}
"""
  private val Ex3 = """
rule 夜间子任务降档 {
  when time 23:00..07:00
  level subtask, node
  model deepseek/deepseek-flash
}
"""
  private val Ex4 = """rule 紧急窗口 { priority 100 when time 09:00..10:00 level root model Irical/gpt-6.1-terra }
rule 白天主流程 { when time 09:00..18:00 level root, team model deepseek/deepseek-flash }
"""
  private val Ex5 = """
rule 周末冻结 { priority 1000 when time 00:00..24:00 weekday 6, 7 freeze }
"""
  private val Ex6 = """
rule 子任务避开计费模型 { when time 14:00..18:00 level subtask freeze model zhipu/*, kimi/* }
"""
  private val Ex7 = """
rule 选区ask走便宜模型 { call selection-ask, canvas-ask model deepseek/deepseek-flash }
rule 小LLM统一 { call small-llm model deepseek/deepseek-flash }
"""
  private val Ex8 = """
rule 白天转便宜模型 { priority 50 when time 18:00..23:00 level team switch deepseek/deepseek-flash confirm }
"""

  // ── (a) 八例：解析 + 保存期校验 + 求值为预期候选 ──────────────────────────

  test("(a) 例 1 计费时段避让：14:00 起禁 zhipu/kimi，18:00 整解禁") {
    checkOk(Ex1)
    val s = scriptOf(Ex1)
    val in = evaluate(s, sess(Some("root"), "main-chat", at(Mon, 14, 0)), base)
    assertEquals(in.candidates, List("Irical/gpt-6.1-terra", "deepseek/deepseek-flash"))
    assertEquals(evaluate(s, sess(Some("root"), "main-chat", at(Mon, 13, 59)), base).candidates, base)
    assertEquals(evaluate(s, sess(Some("root"), "main-chat", at(Mon, 18, 0)), base).candidates, base)
  }

  test("(a) 例 2 白天主流程：链首 Irical，并发与 overflow 作为独立输出，不进请求链") {
    checkOk(Ex2)
    val s = scriptOf(Ex2)
    val r = evaluate(s, sess(Some("root"), "main-chat", at(Mon, 10, 0)), base)
    assertEquals(r.head, Some("Irical/gpt-6.1-terra"))
    assertEquals(r.candidates, base)
    assertEquals(r.overflow, Some("deepseek/deepseek-flash"))
    assertEquals(r.concurrency, Map("Irical" -> 10))
    // 子任务层不命中（level root, team）
    assertEquals(evaluate(s, sess(Some("subtask"), "main-chat", at(Mon, 10, 0)), base).candidates, base)
    // 18:00 整已过窗口
    assertEquals(evaluate(s, sess(Some("root"), "main-chat", at(Mon, 18, 0)), base).head, None)
  }

  test("(a) 例 3 夜间子任务降档：跨午夜，晚腿与清晨腿均命中，07:00 整不命中") {
    checkOk(Ex3)
    val s = scriptOf(Ex3)
    assertEquals(evaluate(s, sess(Some("subtask"), "delegate", at(Mon, 23, 30)), base).head,
      Some("deepseek/deepseek-flash"))
    assertEquals(evaluate(s, sess(Some("node"), "delegate", at(Mon, 6, 59)), base).head,
      Some("deepseek/deepseek-flash"))
    assertEquals(evaluate(s, sess(Some("node"), "delegate", at(Mon, 7, 0)), base).candidates, base)
    assertEquals(evaluate(s, sess(Some("root"), "main-chat", at(Mon, 23, 30)), base).candidates, base)
  }

  test("(a) 例 4 优先级覆盖：09:30 主会话 priority 100 胜出；团队层只命中白天规则") {
    checkOk(Ex4)
    val s = scriptOf(Ex4)
    val root = evaluate(s, sess(Some("root"), "main-chat", at(Mon, 9, 30)), base)
    assertEquals(root.head, Some("Irical/gpt-6.1-terra"))
    assertEquals(root.matched, List("紧急窗口", "白天主流程"))
    val team = evaluate(s, sess(Some("team"), "delegate", at(Mon, 9, 30)), base)
    assertEquals(team.head, Some("deepseek/deepseek-flash"))
    assertEquals(team.matched, List("白天主流程"))
    assertEquals(team.candidates.head, "deepseek/deepseek-flash")
  }

  test("(a) 例 5 周末全局冻结：周六、周日全天冻结（链被剔空），周一不冻结") {
    checkOk(Ex5)
    val s = scriptOf(Ex5)
    assertEquals(evaluate(s, sess(Some("root"), "main-chat", at(Sat, 10, 0)), base).candidates, Nil)
    assertEquals(evaluate(s, sess(Some("node"), "delegate", at(Sun, 0, 0)), base).candidates, Nil)
    assertEquals(evaluate(s, sess(Some("node"), "delegate", at(Sun, 23, 59)), base).candidates, Nil)
    assertEquals(evaluate(s, sess(Some("root"), "main-chat", at(Mon, 10, 0)), base).candidates, base)
  }

  test("(a) 例 6 按模型 × 按层级冻结：仅子任务层在 14–18 点剔除 zhipu/kimi") {
    checkOk(Ex6)
    val s = scriptOf(Ex6)
    assertEquals(evaluate(s, sess(Some("subtask"), "delegate", at(Mon, 15, 0)), base).candidates,
      List("Irical/gpt-6.1-terra", "deepseek/deepseek-flash"))
    assertEquals(evaluate(s, sess(Some("root"), "main-chat", at(Mon, 15, 0)), base).candidates, base)
    assertEquals(evaluate(s, sess(Some("subtask"), "delegate", at(Mon, 18, 0)), base).candidates, base)
  }

  test("(a) 例 7 按调用点：选区/画布 ask 与 small-llm 走 deepseek；main-chat 不受影响") {
    checkOk(Ex7)
    val s = scriptOf(Ex7)
    assertEquals(evaluate(s, sess(None, "selection-ask", at(Mon, 10, 0)), base).head, Some("deepseek/deepseek-flash"))
    assertEquals(evaluate(s, sess(None, "canvas-ask", at(Mon, 10, 0)), base).head, Some("deepseek/deepseek-flash"))
    assertEquals(evaluate(s, sess(None, "small-llm", at(Mon, 10, 0)), base).head, Some("deepseek/deepseek-flash"))
    assertEquals(evaluate(s, sess(Some("root"), "main-chat", at(Mon, 10, 0)), base).candidates, base)
  }

  test("(a) 例 7 未知调用点 fail-closed：按 small-llm 语义回落并在 trace 记 unknown-call-point") {
    val s = scriptOf(Ex7)
    val r = evaluate(s, sess(None, "no-such-call", at(Mon, 10, 0)), base)
    assertEquals(r.head, Some("deepseek/deepseek-flash"))
    assert(r.trace.exists(_.action == "unknown-call-point"), clue(r.trace))
  }

  test("(a) 例 8 会话内切模型：只产生提议，未确认不改候选链") {
    checkOk(Ex8)
    val s = scriptOf(Ex8)
    val r = evaluate(s, sess(Some("team"), "delegate", at(Mon, 18, 30)), base)
    assertEquals(r.candidates, base)
    assertEquals(r.head, None)
    assertEquals(r.switches, List(SwitchProposal("白天转便宜模型", "deepseek/deepseek-flash")))
    assertEquals(evaluate(s, sess(Some("root"), "main-chat", at(Mon, 18, 30)), base).switches, Nil)
    assertEquals(evaluate(s, sess(Some("team"), "delegate", at(Mon, 17, 59)), base).switches, Nil)
  }

  test("(a) 八例文本全部解析通过") {
    List(Ex1, Ex2, Ex3, Ex4, Ex5, Ex6, Ex7, Ex8).foreach(src => assert(parse(src).isRight, clue(src)))
  }

  // ── (b) 保存期报错：冲突 / 未知 provider / 未知 model / 语法 ──────────────

  test("(b) 未知 provider 保存期报错，并给出行号") {
    val errs = checkErr("rule r {\n  model nope/x\n}\n")
    assertEquals(errs.map(_.line), List(2))
    assert(errs.head.message.contains("未知 provider 'nope'"), clue(errs))
  }

  test("(b) 未知 model 保存期报错") {
    val errs = checkErr("rule r { model Irical/nope }")
    assert(errs.exists(_.message.contains("未知 model 'Irical/nope'")), clue(errs))
  }

  test("(b) 未知 provider 也覆盖 unavailable / freeze / concurrency / overflow / switch") {
    assert(checkErr("rule r { unavailable nope/* }").exists(_.message.contains("未知 provider")))
    assert(checkErr("rule r { freeze model nope/* }").exists(_.message.contains("未知 provider")))
    assert(checkErr("rule r { concurrency nope = 3 }").exists(_.message.contains("未知 provider")))
    assert(checkErr("rule r { overflow nope/x }").exists(_.message.contains("未知 provider")))
    assert(checkErr("rule r { switch Irical/nope confirm }").exists(_.message.contains("未知 model")))
  }

  test("(b) 同 priority、同动作、作用域重叠 ⇒ 冲突保存期报错") {
    val src = """rule A { when time 09:00..12:00 model Irical/gpt-6.1-terra }
                |rule B { when time 11:00..13:00 model deepseek/deepseek-flash }""".stripMargin
    val errs = checkErr(src)
    assert(errs.exists(e => e.line == 2 && e.message.contains("冲突")), clue(errs))
  }

  test("(b) 同动作但时段不重叠 ⇒ 不冲突") {
    val src = """rule A { when time 09:00..12:00 model Irical/gpt-6.1-terra }
                |rule B { when time 12:00..13:00 model deepseek/deepseek-flash }""".stripMargin
    checkOk(src)
  }

  test("(b) 同动作、时段重叠但 level 不相交 ⇒ 不冲突") {
    val src = """rule A { when time 09:00..12:00 level root model Irical/gpt-6.1-terra }
                |rule B { when time 09:00..12:00 level team model deepseek/deepseek-flash }""".stripMargin
    checkOk(src)
  }

  test("(b) priority 不同 ⇒ 不冲突（例 4 形态）") {
    checkOk(Ex4)
  }

  test("(b) 同 provider 的 concurrency 作用域重叠 ⇒ 冲突；不同 provider ⇒ 不冲突") {
    assert(checkErr("""rule A { concurrency Irical = 5 }
                      |rule B { concurrency Irical = 9 }""".stripMargin)
      .exists(_.message.contains("concurrency")))
    checkOk("""rule A { concurrency Irical = 5 }
              |rule B { concurrency deepseek = 9 }""".stripMargin)
  }

  test("(b) switch 缺 confirm ⇒ 语法错误（N2）") {
    val errs = checkErr("rule r { switch deepseek/deepseek-flash }")
    assert(errs.exists(_.message.contains("confirm")), clue(errs))
  }

  test("(b) 零长度时段、重名规则、单值子句重复、非法星期均报错") {
    assert(checkErr("rule r { when time 10:00..10:00 model Irical/gpt-6.1-terra }").exists(_.message.contains("零长度")))
    assert(checkErr("rule r { model Irical/gpt-6.1-terra }\nrule r { model Irical/gpt-6.1-terra }")
      .exists(_.message.contains("规则名重复")))
    assert(checkErr("rule r { model Irical/gpt-6.1-terra model Irical/gpt-6.1-terra }")
      .exists(_.message.contains("重复")))
    assert(checkErr("rule r { when weekday 8 freeze }").exists(_.message.contains("1..7")))
  }

  test("(b) 未知维度值（level / slot / call）报错") {
    assert(checkErr("rule r { level boss freeze }").exists(_.message.contains("未知 level")))
    assert(checkErr("rule r { slot nope freeze }").exists(_.message.contains("未知 slot")))
    assert(checkErr("rule r { call nope freeze }").exists(_.message.contains("未知 call")))
  }

  test("语法：未知子句 / 缺少 when 条件 / 未闭合均报错，带行号") {
    val e1 = parse("rule r {\n  bogus x\n}")
    assertEquals(e1.swap.toOption.map(_.map(_.line)), Some(List(2)))
    assert(parse("rule r { when freeze }").isLeft)
    assert(parse("rule r { model Irical/gpt-6.1-terra").isLeft)
  }

  // ── (c) 无规则会话：候选与基线逐项相同 ───────────────────────────────────

  test("(c) 空脚本：任意会话候选 == 基线（逐项），无命中、无动作") {
    val empty = Script(Nil)
    val r = evaluate(empty, sess(Some("root"), "main-chat", at(Mon, 10, 0)), base)
    assertEquals(r.candidates, base)
    assert(r.noRuleHit)
    assertEquals(r.head, None)
    assertEquals(r.trace, Nil)
  }

  test("(c) 有规则但全部不命中的会话：候选 == 基线（逐项）") {
    val r = evaluate(scriptOf(Ex3), sess(Some("root"), "main-chat", at(Mon, 10, 0)), base)
    assertEquals(r.candidates, base)
    assert(r.noRuleHit)
  }

  test("(c) 基线含重复或空链时原样返回（不去重、不重排）") {
    val odd = List("kimi/k2", "kimi/k2", "zhipu/glm-5")
    assertEquals(evaluate(Script(Nil), sess(None, "main-chat", at(Mon, 10, 0)), odd).candidates, odd)
    assertEquals(evaluate(Script(Nil), sess(None, "main-chat", at(Mon, 10, 0)), Nil).candidates, Nil)
  }

  // ── 框图 JSON（DSL → 图）：节点数与 srcLine ─────────────────────────────

  test("(d) 例 2 框图：5 个节点、srcLine 为 2/3/4/5/6，边为 match×3 + overflow×1") {
    val s = scriptOf(Ex2)
    val (nodes, edges) = graph(s)
    assertEquals(nodes.size, 5)
    assertEquals(nodes.map(_.id), List("r1.when", "r1.level", "r1.model", "r1.conc", "r1.over"))
    assertEquals(nodes.map(_.kind), List("when-time", "level", "model", "concurrency", "overflow"))
    assertEquals(nodes.map(_.srcLine), List(2, 3, 4, 5, 6))
    assertEquals(nodes.map(_.ruleId).distinct, List("白天主流程"))
    assertEquals(edges.map(_.kind), List("match", "match", "match", "overflow"))
  }

  test("(d) 例 4 框图：两条规则共 7 个节点（优先级子句计为节点），srcLine 分别在第 1、2 行") {
    val (nodes, _) = graph(scriptOf(Ex4))
    assertEquals(nodes.size, 7)
    assertEquals(nodes.filter(_.ruleId == "紧急窗口").map(_.srcLine).distinct, List(1))
    assertEquals(nodes.filter(_.ruleId == "白天主流程").map(_.srcLine).distinct, List(2))
    assert(nodes.exists(n => n.kind == "priority" && n.label == "priority 100"))
  }

  test("(d) 例 5 框图：when-time、when-weekday、freeze 三节点（周末全局冻结）") {
    val (nodes, _) = graph(scriptOf(Ex5))
    assertEquals(nodes.map(_.kind), List("priority", "when-time", "when-weekday", "freeze"))
    assertEquals(nodes.last.label, "全部模型")
  }

  test("(d) 框图 JSON：节点数与 srcLine 字段与图一致，且转义正确") {
    val s = scriptOf(Ex2)
    val json = diagramJson(s)
    assert(json.startsWith("{\"nodes\":["), clue(json))
    assertEquals("\"id\":".r.findAllIn(json).size, 5) // 5 节点（边只带 from/to/kind，不带 id）
    assertEquals("\"from\":".r.findAllIn(json).size, 4)
    assertEquals("\"srcLine\":".r.findAllIn(json).size, 5)
    assert(json.contains("\"srcLine\":6"), clue(json))
    assert(json.contains("\"kind\":\"overflow\""), clue(json))
  }

  test("框图 JSON 对引号与反斜杠转义") {
    val s = Script(List(Rule("a\"b\\c", 1, List(Freeze(None, 1)))))
    val json = diagramJson(s)
    assert(json.contains("\"ruleId\":\"a\\\"b\\\\c\""), clue(json))
  }

  // ── 语义细节 ──────────────────────────────────────────────────────────────

  test("unavailable 的 provider/* 模式与精确 ref 模式") {
    val s = scriptOf("rule r { unavailable Irical/gpt-6.1-terra }")
    assertEquals(evaluate(s, sess(None, "main-chat", at(Mon, 1, 0)), base).candidates,
      List("deepseek/deepseek-flash", "zhipu/glm-5", "kimi/k2"))
  }

  test("freeze 的 model 子句作用于候选并与 unavailable 取并集") {
    val s = scriptOf("rule a { freeze model zhipu/* }\nrule b { unavailable kimi/* }")
    assertEquals(evaluate(s, sess(None, "main-chat", at(Mon, 1, 0)), base).candidates,
      List("Irical/gpt-6.1-terra", "deepseek/deepseek-flash"))
  }

  test("when 跨午夜归属：周一 weekday 限定时，周二清晨属于周一的腿") {
    val s = scriptOf("rule r { when time 23:00..07:00 weekday 1 model deepseek/deepseek-flash }")
    // 周二 06:00 = 周一晚腿的清晨延续 ⇒ 命中
    assertEquals(evaluate(s, sess(None, "main-chat", at(24, 6, 0)), base).head, Some("deepseek/deepseek-flash"))
    // 周一 06:00 = 归属于周日的清晨腿 ⇒ 不命中
    assertEquals(evaluate(s, sess(None, "main-chat", at(23, 6, 0)), base).head, None)
  }

  test("model 链首 = priority 最高者；同级按文件序") {
    val s = scriptOf(
      """rule low { when time 09:00..10:00 model deepseek/deepseek-flash }
        |rule hi { priority 5 when time 09:00..10:00 model zhipu/glm-5 }
        |rule same { priority 5 when time 09:00..10:00 level root model kimi/k2 }""".stripMargin)
    val r = evaluate(s, sess(Some("root"), "main-chat", at(Mon, 9, 30)), base)
    assertEquals(r.head, Some("zhipu/glm-5"))
  }

  test("ModelRuleDsl 本身无 IO：同一输入两次求值结果相同（纯函数）") {
    val s = scriptOf(Ex2)
    val a = evaluate(s, sess(Some("root"), "main-chat", at(Mon, 10, 0)), base)
    val b = evaluate(s, sess(Some("root"), "main-chat", at(Mon, 10, 0)), base)
    assertEquals(a, b)
  }

  test("注释与同行多子句（例 4 形态）解析正确") {
    val s = scriptOf("rule r { # 注释\n  model Irical/gpt-6.1-terra # 尾注\n}")
    assertEquals(s.rules.head.clauses, List(ModelAct("Irical/gpt-6.1-terra", 2)))
  }
end ModelRuleDslSpec
