package nebflow.social

import io.circe.Json
import io.circe.syntax.*
import munit.FunSuite
import nebflow.shared.AskItem
import nebflow.social.outbound.{AskPromptCodec, PromptAction, PromptOption, PromptView}

/**
 * askuser-secret 批（2026-10-08 · 规格 §3）渠道侧验收面：
 *
 *   - 渠道模型读面：`viewsFromItemsJson` / `fromItems` 把 secret 标记读进
 *     `PromptView.secret`（缺省 false = 旧载荷行为不变）；
 *   - 共用闸（单一实现 `AskPromptCodec.validate`）：指向 secret 题的作答
 *     （Pick / Other）**显式拒绝**，拒绝文案 = SecretRefuseLine + 题位；
 *   - 微信文本臂：secret 题渲染桌面引导行（零选项零入口）；parseReply 三条
 *     寻址腿（裸编号 / 问.编 / 0+文本）对 secret 题 SecretReject；
 *   - 飞书卡臂：oneCard secret 题只有引导 div（零按钮零输入面）；文本降级腿
 *     渲染引导短形。
 *
 * 值纪律：本 spec 全程不含任何 secret 值字面量——渠道面从头到尾没有值的席位。
 */
class AskSecretChannelSpec extends FunSuite:

  private val Rid = "asknb-abc123"

  // ── 渠道模型读面 ───────────────────────────────────────────────────────

  test("C1 viewsFromItemsJson：wire secret 键读进 PromptView（缺省 false 零回归）") {
    val items = List(
      Json.obj("question" -> "方案?".asJson, "options" -> Json.arr(Json.obj("label" -> "A".asJson))),
      Json.obj("question" -> "key?".asJson, "secret" -> Json.fromBoolean(true))
    )
    val views = AskPromptCodec.viewsFromItemsJson(items)
    assertEquals(views(0).secret, false, "无 secret 键的旧载荷照旧")
    assertEquals(views(1).secret, true)
    assertEquals(views.map(_.idx), List(0, 1), "槽位对齐不因 secret 改变")
  }

  test("C2 fromItems：AskItem.secret 读进 PromptView") {
    val views = AskPromptCodec.fromItems(List(AskItem("k?", Nil, secret = true), AskItem("方案?", Nil)))
    assertEquals(views(0).secret, true)
    assertEquals(views(1).secret, false)
  }

  // ── 共用作答闸（validate） ─────────────────────────────────────────────

  test("C3 validate：指向 secret 题的 Pick/Other 显式拒绝；非 secret 作答放行；越界判据不变") {
    val views = List(
      PromptView(0, "方案?", List(PromptOption("A"), PromptOption("B"))),
      PromptView(1, "key?", secret = true)
    )
    assertEquals(AskPromptCodec.validate(views, List(PromptAction.Pick(0, 0))), Right(()))
    val pickSecret = AskPromptCodec.validate(views, List(PromptAction.Pick(1, 0)))
    assert(pickSecret.left.toOption.get.startsWith("secret-question-not-answerable-on-channel: 1"))
    assert(pickSecret.left.toOption.get.contains(AskPromptCodec.SecretRefuseLine), "拒绝文案 = 共用回线")
    val otherSecret = AskPromptCodec.validate(views, List(PromptAction.Other(1, "leaky?")))
    assert(otherSecret.left.toOption.get.startsWith("secret-question-not-answerable-on-channel: 1"))
    // 既有越界判据逐字不变（零回归）
    assertEquals(
      AskPromptCodec.validate(views, List(PromptAction.Pick(5, 0))).left.toOption.get,
      "question-index-out-of-range: 5"
    )
  }

  test("C4 secretGuideLine：规格 §3 原句（第 N/M 问 + requestId 括注）") {
    assertEquals(
      AskPromptCodec.secretGuideLine(1, 3, Rid),
      "第 2/3 问需安全输入（密码/验证码不进聊天渠道），请在桌面 Nebula 面板完成此问（requestId=asknb-abc123）。"
    )
  }

  // ── 微信文本臂 ────────────────────────────────────────────────────────

  test("C5 renderAskText：secret 题渲染引导行、零选项行；非 secret 集合输出零引导（零回归）") {
    val views = List(
      PromptView(0, "方案?", List(PromptOption("A"), PromptOption("B"))),
      PromptView(1, "key?", secret = true)
    )
    val (text, truncated) = WeixinAskLeg.renderAskText(views, Nil, Rid)
    assertEquals(truncated, false)
    assert(text.contains(AskPromptCodec.secretGuideLine(1, 2, Rid)), "secret 题 = 桌面引导文案（带 rid 括注）")
    assert(!text.contains("（需安全输入，请在桌面 Nebula 面板完成）"), "rid 在手 ⇒ 用带括注全形而非短形")
    // 非 secret 集合：无 requestId 缺省参数 ⇒ 输出无任何引导字样（改前逐字节同形）
    val (plain, _) = WeixinAskLeg.renderAskText(List(PromptView(0, "方案?", List(PromptOption("A")))), Nil)
    assert(!plain.contains("需安全输入"), "无 secret 题 ⇒ 零引导文案")
    assert(plain.contains("1) A"), "非 secret 题照常渲染编号选项")
  }

  test("C6 parseReply：secret 题三条寻址腿全部 SecretReject；非 secret 题照常作答") {
    val views = List(
      PromptView(0, "key?", secret = true),
      PromptView(1, "方案?", List(PromptOption("A"), PromptOption("B")))
    )
    assertEquals(WeixinAskLeg.parseReply("1", views), Left(WeixinAskLeg.SecretReject), "裸编号打头题 = secret")
    assertEquals(WeixinAskLeg.parseReply("1.1", views), Left(WeixinAskLeg.SecretReject), "问.编 寻址 secret 题")
    assertEquals(
      WeixinAskLeg.parseReply("0 my-otp-code", views),
      Left(WeixinAskLeg.SecretReject),
      "0+文本 自由输入打 secret 题"
    )
    assertEquals(WeixinAskLeg.parseReply("他 my-otp-code", views), Left(WeixinAskLeg.SecretReject), "他 前缀同拒")
    // 拒绝线单一来源
    assertEquals(WeixinAskLeg.SecretReject.line, AskPromptCodec.SecretRefuseLine)
    // 非 secret 题照常（只答非 secret 题的合法路径）
    assertEquals(WeixinAskLeg.parseReply("2.1", views), Right(Some(List(PromptAction.Pick(1, 0)))))
  }

  // ── 飞书卡臂 ──────────────────────────────────────────────────────────

  test("C7 oneCard：secret 题只有引导 div——零 action/按钮元素，文案带 rid 括注") {
    val views = List(PromptView(0, "key?", secret = true))
    val cards = FeishuAskCard.render(views, Rid)
    assertEquals(cards.size, 1)
    val card = io.circe.parser.parse(cards.head).toOption.get
    val elements = card.hcursor.downField("elements").as[List[Json]].toOption.get
    assert(
      elements.forall(_.hcursor.downField("tag").as[String].toOption.forall(_ != "action")),
      "secret 卡零作答入口（无 action 容器 = 无按钮）"
    )
    val contents = elements.flatMap(_.hcursor.downField("text").downField("content").as[String].toOption)
    assert(
      contents.exists(_.contains("→ " + AskPromptCodec.secretGuideLine(0, 1, Rid))),
      "引导 section = secretGuideLine 单一来源"
    )
  }

  test("C8 renderText（文本降级腿）：secret 题渲染引导短形") {
    val t = FeishuAskCard.renderText(List(PromptView(0, "key?", secret = true)), "no-card")
    assert(t.contains("→ 需安全输入，请在桌面 Nebula 面板完成"), "降级腿无 rid 在手 ⇒ 无括注短形")
    assert(!t.contains("requestId="), "短形不带括注")
    // 非 secret 降级腿零引导（零回归）
    val plain = FeishuAskCard.renderText(List(PromptView(0, "方案?", List(PromptOption("A")))), "no-card")
    assert(!plain.contains("需安全输入"))
    assert(plain.contains("1) A"))
  }

end AskSecretChannelSpec
