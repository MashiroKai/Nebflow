package nebflow.core.scheduler

import io.circe.syntax.*
import io.circe.parser.decode
import munit.FunSuite

/**
 * 待办决策统一批（2026-11-22）期 1 行为闸：作答校验纯函数 + 作答模型线格式。
 * 钉死：secret 禁答、过期拒、选项越界拒、单选多选、allowOther、空作答、
 * 缺键回默认（存量零迁移）、作答 round-trip。
 */
class TodoDecisionAnswerSpec extends FunSuite:

  private val now = 1_000_000L
  private val opts = List(DecisionOption("o1", "A"), DecisionOption("o2", "B"))

  private def dec(
    multi: Boolean = false,
    other: Boolean = false,
    secret: Boolean = false,
    exp: Option[Long] = None
  ) =
    TodoDecision(
      question = "选哪个",
      options = opts,
      multiSelect = multi,
      allowOther = other,
      secret = secret,
      expiresAt = exp
    )

  private def code(r: Either[(String, String), DecisionAnswer]): String =
    r.left.map(_._1).left.getOrElse("ok")

  test("单选选一个 → 通过，answer 落 selected/answeredAt/via") {
    val r = DecisionAnswerRules.validate(dec(), List("o1"), None, now, "mobile")
    assertEquals(r, Right(DecisionAnswer(List("o1"), None, now, "mobile")))
  }

  test("单选选两个 → decision_single_choice") {
    assertEquals(code(DecisionAnswerRules.validate(dec(), List("o1", "o2"), None, now, "")), "decision_single_choice")
  }

  test("多选选两个 → 通过") {
    assertEquals(code(DecisionAnswerRules.validate(dec(multi = true), List("o1", "o2"), None, now, "")), "ok")
  }

  test("选项 id 越界 → decision_option_unknown") {
    assertEquals(code(DecisionAnswerRules.validate(dec(), List("zz"), None, now, "")), "decision_option_unknown")
  }

  test("allowOther=false 给自由文本 → decision_other_not_allowed") {
    assertEquals(
      code(DecisionAnswerRules.validate(dec(), Nil, Some("随便"), now, "")),
      "decision_other_not_allowed"
    )
  }

  test("allowOther=true 仅自由文本 → 通过，文本 trim") {
    val r = DecisionAnswerRules.validate(dec(other = true), Nil, Some("  自定义  "), now, "")
    assertEquals(r.map(_.otherText), Right(Some("自定义")))
  }

  test("选与填皆空 → decision_answer_empty（空白文本视为空）") {
    assertEquals(code(DecisionAnswerRules.validate(dec(other = true), Nil, Some("   "), now, "")), "decision_answer_empty")
  }

  test("secret 题 → decision_secret_unsupported（本期禁作答）") {
    assertEquals(code(DecisionAnswerRules.validate(dec(secret = true), List("o1"), None, now, "")), "decision_secret_unsupported")
  }

  test("过期 → decision_expired；截止未到通过") {
    assertEquals(code(DecisionAnswerRules.validate(dec(exp = Some(now - 1)), List("o1"), None, now, "")), "decision_expired")
    assertEquals(code(DecisionAnswerRules.validate(dec(exp = Some(now + 1)), List("o1"), None, now, "")), "ok")
  }

  test("存量 decision 无 answer 键 → 解码为 None（零迁移）") {
    val legacy = """{"question":"q","options":[{"id":"o1","title":"A"}]}"""
    val d = decode[TodoDecision](legacy)
    assertEquals(d.map(_.answer), Right(None))
  }

  test("作答 round-trip：编码后解码一致") {
    val d = dec().copy(answer = Some(DecisionAnswer(List("o2"), Some("x"), now, "desktop")))
    assertEquals(decode[TodoDecision](d.asJson.noSpaces), Right(d))
  }
