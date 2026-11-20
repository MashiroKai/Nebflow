package nebflow.social

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import io.circe.Json
import munit.CatsEffectSuite
import nebflow.social.outbound.RetryPolicy

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * 飞书腿「出站绝不静默」spec（社交出站 P0 批 2026-11-21，作者实证 + 铁律②）。
 *
 * 实证（作者 2026-11-21 01:50）：「飞书收不到你的消息」。canary 重启之后
 * 01:35–02:09 之间的助手回复里，**带 `<report>` 块的两轮全部到达、不带块的 5 轮
 * 一条都没到**，而 bridge 日志只有 inbound routed ——出站发送零日志、零报错。
 * 这不是连接问题（长连接、路由表、凭据都正常），而是：
 *
 *   主会话轮尾的 `<report>` 块闸（[[nebflow.social.outbound.OutputBlock.extractGated]]）
 *   对「无块」的回复判 `NoBlocks` ⇒ 文字腿熄灭 ⇒ **不发、也不打一行日志**。
 *
 * 本批**不动线上语义**（作者令「不要回退成发全文」逐字有效）：块闸照旧不投递
 * 块外文本。改的是**可观测性**——同一件事现在落一条 ERROR + 一条面板可见的
 * 健康 strike。本 spec 把这条边界两侧都钉住：
 *
 *   - 无块 ⇒ **零发送**（线上一字不改）**且有一条 ERROR**（不再静默）；
 *   - 有块 ⇒ 只发块内内容（既有契约）；
 *   - 非主会话 ⇒ 原文直通（既有契约，无抑制、无 ERROR）；
 *   - 发送失败 ⇒ ERROR 带 channel/to/code/attempt，且**永不写 msg**（零泄漏）；
 *   - 有界退避（作者令③）⇒ 恢复即停、确定性拒绝一次即止。
 *
 * 离线纪律与 `FeishuBridgePluginSpec` 同源：send 是注入的 recorder，无网络、
 * 无凭据、不开 socket。
 */
class FeishuOutboundVisibilitySpec extends CatsEffectSuite:

  private def tmpRoot(): os.Path = os.temp.dir(prefix = "nb-feishu-outbound-visibility-")

  private def meta(id: String, chatId: Option[String]): nebflow.shared.SessionMeta =
    nebflow.shared.SessionMeta(
      id = id,
      name = id,
      createdAt = 0L,
      updatedAt = 0L,
      hasUnread = false,
      bridges = chatId.map(c => Map("feishu" -> Json.obj("chat_id" -> Json.fromString(c)))).getOrElse(Map.empty)
    )

  private final class RecordingCtx(sessions: List[nebflow.shared.SessionMeta]) extends nebflow.bridge.BridgeContext:
    def injectMessage(
      sessionId: String,
      content: String,
      senderId: Option[String],
      origin: Option[nebflow.bridge.BridgeOrigin] = None
    ): IO[Unit] = IO.unit
    def interruptAgent(sessionId: String): IO[Unit] = IO.unit
    def sessionMeta(sessionId: String): IO[Option[nebflow.shared.SessionMeta]] = IO.none
    def listSessions: IO[List[nebflow.shared.SessionMeta]] = IO.pure(sessions)
    def updateBridgeConfig(sessionId: String, platform: String, config: Option[Json]): IO[Unit] = IO.unit

  /** 记下每次 send 的 `(chatId, text)`；读数由 `answer(第 n 次调用)` 决定。 */
  private final class RecordingSend(answer: Int => FeishuChannel.SendResult):
    val calls: Ref[IO, List[(String, String)]] = Ref.unsafe[IO, List[(String, String)]](Nil)
    val fn: FeishuBridgePlugin.Send = (_, _, _, chatId, text) =>
      calls.modify(cs => (cs :+ ((chatId, text)), cs.size + 1)).map(answer)

  private def okResult: FeishuChannel.SendResult =
    FeishuChannel.SendResult(true, Some("om_out"), 0, "ok", "2026-11-21T01:00:00Z", "chat_id")

  private def failedResult(code: Int, msg: String = "rejected"): FeishuChannel.SendResult =
    FeishuChannel.SendResult(false, None, code, msg, "2026-11-21T01:00:00Z", "chat_id")

  private def bridge(
    root: os.Path,
    send: FeishuBridgePlugin.Send,
    rootSession: Boolean,
    retry: RetryPolicy = RetryPolicy.Single
  ): FeishuBridgePlugin =
    new FeishuBridgePlugin(
      root,
      send = send,
      pinnedCreds = Some(FeishuCredentials.Credential("test-app", "test-secret", "spec")),
      rootSessionGate = Some(_ => rootSession),
      retryPolicy = retry
    )

  private def delta(sessionId: String, text: String): Json =
    Json.obj(
      "type" -> Json.fromString("textDelta"),
      "sessionId" -> Json.fromString(sessionId),
      "delta" -> Json.fromString(text)
    )

  private def done(sessionId: String): Json =
    Json.obj("type" -> Json.fromString("done"), "sessionId" -> Json.fromString(sessionId))

  private def withAppender[A](
    body: ch.qos.logback.core.read.ListAppender[ch.qos.logback.classic.spi.ILoggingEvent] => A
  ): A =
    val lb = org.slf4j.LoggerFactory
      .getLogger("nebflow.social.feishu-bridge")
      .asInstanceOf[ch.qos.logback.classic.Logger]
    val appender = new ch.qos.logback.core.read.ListAppender[ch.qos.logback.classic.spi.ILoggingEvent]
    appender.start()
    lb.addAppender(appender)
    try body(appender)
    finally lb.detachAppender(appender)

  private def errors(
    appender: ch.qos.logback.core.read.ListAppender[ch.qos.logback.classic.spi.ILoggingEvent]
  ): List[String] =
    appender.list.asScala.toList
      .filter(_.getLevel == ch.qos.logback.classic.Level.ERROR)
      .map(_.getFormattedMessage)

  // ─────────────── ① 静默丢失点：无块的主会话回复 ───────────────

  test("FS-P0-1 主会话无 report 块 ⇒ 零发送（线上不变）且一条 ERROR —— 静默丢失点被堵住") {
    val root = tmpRoot()
    val rec = RecordingSend(_ => okResult)
    val p = bridge(root, rec.fn, rootSession = true)
    val ctx = RecordingCtx(List(meta("s1", Some("oc_1"))))
    withAppender { appender =>
      val calls = (for
        _ <- p.rebuildRoutes(ctx)
        _ <- p.onAgentEvent("s1", delta("s1", "查到了，跟重启直接相关：你的端重连时一次性重放几百条链面板请求…"))
        _ <- p.onAgentEvent("s1", done("s1"))
        cs <- rec.calls.get
      yield cs).unsafeRunSync()
      assertEquals(calls, Nil,
        "无块 ⇒ 一条都不发（作者令：不要回退成发全文，线上语义一字不改）")
      val fired = errors(appender)
      val line = fired.find(_.contains("turn text suppressed"))
        .getOrElse(fail(s"作者实证的那次静默丢失必须有 ERROR，got $fired"))
      assert(line.contains("channel=feishu"), s"ERROR 必带渠道：$line")
      assert(line.contains("to=oc_1"), s"ERROR 必带投递目标：$line")
      assert(line.contains("no-report-block"), s"ERROR 必带原因：$line")
      assert(line.contains("chars="), s"ERROR 必带被吞的文本规模：$line")
    }
    // 面板可见性：同一条失败同时落一条通道健康 strike。
    val health = SocialChannels
      .channelHealth(root, FeishuBridgePlugin.Name)
      .getOrElse(fail("面板健康面必须能看到这条丢失"))
    val strike = health.hcursor
      .downField("strikes")
      .as[List[Json]]
      .toOption
      .getOrElse(Nil)
      .find(_.hcursor.downField("reason").as[String].toOption.contains(FeishuBridgePlugin.ReasonOutboundSend))
      .getOrElse(fail(s"strike 必须写入（reason=${FeishuBridgePlugin.ReasonOutboundSend}）：$health"))
    assertEquals(strike.hcursor.downField("to").as[String].toOption, Some("oc_1"))
  }

  test("FS-P0-2 有块的主会话回复照旧只发块内内容，且不产生抑制 ERROR（对照臂）") {
    val root = tmpRoot()
    val rec = RecordingSend(_ => okResult)
    val p = bridge(root, rec.fn, rootSession = true)
    val ctx = RecordingCtx(List(meta("s1", Some("oc_1"))))
    withAppender { appender =>
      (for
        _ <- p.rebuildRoutes(ctx)
        _ <- p.onAgentEvent("s1", delta("s1", "块外叙述不出站。<report>这条该到</report>"))
        _ <- p.onAgentEvent("s1", done("s1"))
        calls <- rec.calls.get
      yield calls).unsafeRunSync() match
        case (chat, text) :: Nil =>
          assertEquals(chat, "oc_1")
          assertEquals(text, "这条该到", "只发块内拼接（既有契约）")
        case other => fail(s"应有且仅有一条块内投递，got $other")
      assert(
        !errors(appender).exists(_.contains("turn text suppressed")),
        s"有块的一轮不该报抑制：${errors(appender)}"
      )
    }
  }

  test("FS-P0-3 非主会话无块 ⇒ 原文直通（既有契约），无抑制 ERROR") {
    val root = tmpRoot()
    val rec = RecordingSend(_ => okResult)
    val p = bridge(root, rec.fn, rootSession = false)
    val ctx = RecordingCtx(List(meta("s1", Some("oc_1"))))
    withAppender { appender =>
      (for
        _ <- p.rebuildRoutes(ctx)
        _ <- p.onAgentEvent("s1", delta("s1", "普通会话的全文"))
        _ <- p.onAgentEvent("s1", done("s1"))
        calls <- rec.calls.get
      yield calls).unsafeRunSync() match
        case (_, text) :: Nil => assertEquals(text, "普通会话的全文")
        case other            => fail(s"非主会话应原文直通，got $other")
      assert(!errors(appender).exists(_.contains("turn text suppressed")), s"${errors(appender)}")
    }
  }

  // ─────────────── ② 发送失败绝不静默（作者铁律②） ───────────────

  test("FS-P0-4 发送失败 ⇒ ERROR 带 channel/to/code/attempt，且永不写 msg（零泄漏）") {
    val root = tmpRoot()
    val secret = "SENTINEL_FEISHU_OUTBOUND_9153"
    val rec = RecordingSend(_ => failedResult(400, secret))
    val p = bridge(root, rec.fn, rootSession = true)
    val ctx = RecordingCtx(List(meta("s1", Some("oc_1"))))
    withAppender { appender =>
      (for
        _ <- p.rebuildRoutes(ctx)
        _ <- p.onAgentEvent("s1", delta("s1", "<report>会失败这条</report>"))
        _ <- p.onAgentEvent("s1", done("s1"))
      yield ()).unsafeRunSync()
      val line = errors(appender)
        .find(_.contains("send rejected"))
        .getOrElse(fail(s"发送失败必须 ERROR，got ${errors(appender)}"))
      assert(line.contains("channel=feishu") && line.contains("to=oc_1"), line)
      assert(line.contains("code=400") && line.contains("attempt=1/1"), line)
      assert(!line.contains(secret), s"SendResult.msg 永不进日志：$line")
    }
  }

  // ─────────────── ③ 有界退避（作者令③） ───────────────

  test("FS-P0-5 有界退避：5xx 连败后成功 ⇒ 恢复即停，同一段只推一条、每次失败各留一条 ERROR") {
    val root = tmpRoot()
    val rec = RecordingSend(n => if n <= 2 then failedResult(502) else okResult)
    val p = bridge(root, rec.fn, rootSession = true, retry = RetryPolicy(3, 1.millis, 2.millis))
    val ctx = RecordingCtx(List(meta("s1", Some("oc_1"))))
    withAppender { appender =>
      val calls = (for
        _ <- p.rebuildRoutes(ctx)
        _ <- p.onAgentEvent("s1", delta("s1", "<report>退避之后送达</report>"))
        _ <- p.onAgentEvent("s1", done("s1"))
        cs <- rec.calls.get
      yield cs).unsafeRunSync()
      // 重试是在**同一条**回复上的三次尝试（recorder 记每一次调用），第 3 次
      // 成功即停 —— 所以恰好 3 次调用、正文始终是同一段块内内容。
      assertEquals(calls.size, 3, s"两次 5xx + 一次成功 = 3 次尝试，got $calls")
      assertEquals(calls.map(_._2).distinct, List("退避之后送达"), s"每次尝试发的是同一段，got $calls")
      assertEquals(calls.map(_._1).distinct, List("oc_1"))
      val fired = errors(appender).filter(_.contains("send rejected"))
      assertEquals(fired.length, 2, s"两次 5xx 各一条 ERROR，got $fired")
      assert(fired.head.contains("attempt=1/3") && fired(1).contains("attempt=2/3"), s"$fired")
    }
  }

  test("FS-P0-6 确定性拒绝（403）一次即止：不该吃满 3 次名额") {
    val root = tmpRoot()
    val rec = RecordingSend(_ => failedResult(403))
    val p = bridge(root, rec.fn, rootSession = true, retry = RetryPolicy(3, 1.millis, 2.millis))
    val ctx = RecordingCtx(List(meta("s1", Some("oc_1"))))
    val calls = (for
      _ <- p.rebuildRoutes(ctx)
      _ <- p.onAgentEvent("s1", delta("s1", "<report>x</report>"))
      _ <- p.onAgentEvent("s1", done("s1"))
      cs <- rec.calls.get
    yield cs).unsafeRunSync()
    assertEquals(calls.size, 1, "鉴权/参数拒绝重试只是多写两行 ERROR")
  }

  // ─────────────── ④ 生产装配带策略 ───────────────

  test("FS-P0-7 生产装配策略 = Bounded（作者令③：不许一次失败就放弃）") {
    assertEquals(FeishuBridgePlugin.DefaultRetryPolicy.maxAttempts, RetryPolicy.Bounded.maxAttempts)
    assertEquals(FeishuBridgePlugin.DefaultRetryPolicy, RetryPolicy.Bounded)
  }

end FeishuOutboundVisibilitySpec
