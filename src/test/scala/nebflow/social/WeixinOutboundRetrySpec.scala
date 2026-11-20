package nebflow.social

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}
import munit.CatsEffectSuite
import nebflow.social.outbound.RetryPolicy

import java.net.{InetSocketAddress, ServerSocket}
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * 微信 iLink 出站腿的有界退避 spec（社交出站 P0 批 2026-11-21，作者令③ +
 * 作者铁律②「出站失败绝不静默」）。
 *
 * 实证背景（2026-11-21 01:35:01 / 01:50:33 两条日志）：
 * `outbound send failed channel=weixin-ilink code=502 reason=delivery_failed:
 * sendmessage ret=-2 errmsg=prepare failed`，**`attempts=1` 后就放弃**。而
 * `ret=-2`（iLink `prepare failed` = 会话尚未就绪）正是重启之后最常见、也最
 * 该退避重试的那一类。
 *
 * 本 spec 钉住四件事：
 *
 *   ① **分类表** —— 确定性未送达（`ret=-2`、5xx）可重试；超时类
 *      `sidecar_unreachable` **永不重试**（design §0.4 的重发边界：超时的 flush
 *      可能已被 iLink 接受，重发就是重复投递）；401/4xx/200-未投递一次即止。
 *   ② **有界退避真的在跑** —— 连续 `ret=-2` 时逐次尝试、每次一条 **ERROR**，
 *      `attempts=` 是真实序号（1/2/3），最后返回最后一次读数。
 *   ③ **恢复即停** —— 第 2 次成功就交付，且只留一条失败行。
 *   ④ **生产默认带策略** —— [[WeixinIlinkBridgePlugin.DefaultSend]] 装配的是
 *      [[RetryPolicy.Bounded]]（这条是 ③ 的「接线」证据，不只是适配器能力）。
 *
 * 离线纪律（同 `WeixinOutboundLogSpec`）：side-car 是 127.0.0.1:0 上的
 * `com.sun.net.httpserver` stub，超时臂用「bind-0 拿端口再关」造成的 connection
 * refused（与超时走同一个 `handleErrorWith` 包含臂，但瞬时完成），token 铸进临时
 * 数据根。除 DefaultSend 那一条（走真实 [[RetryPolicy.Bounded]] 的 0.5s+1s）之外
 * 都用毫秒级策略，判定不看真实时间。
 */
class WeixinOutboundRetrySpec extends CatsEffectSuite:

  // ───────────────────────── harness ─────────────────────────

  private def tmpRoot(): os.Path = os.temp.dir(prefix = "nb-weixin-outbound-retry-")

  private val ret502Body =
    """{"error":"delivery_failed: sendmessage ret=-2 errmsg=prepare failed","client_id":"stub"}"""

  private val deliveredBody = """{"ok":true,"delivered":true,"message_id":"421001"}"""

  /** 计数 + 可编排的 stub：第 n 次请求返回 `answers(n)`（超出用最后一个）。 */
  private final class ScriptedHandler(answers: List[(Int, String)]) extends HttpHandler:
    val hits = new AtomicInteger(0)

    def handle(exchange: HttpExchange): Unit =
      exchange.getRequestBody.readAllBytes() // drain
      val n = hits.incrementAndGet()
      val (status, body) = if n <= answers.length then answers(n - 1) else answers.last
      val bytes = body.getBytes(StandardCharsets.UTF_8)
      exchange.sendResponseHeaders(status, bytes.length)
      exchange.getResponseBody.write(bytes)
      exchange.close()

  private def withStub[A](answers: List[(Int, String)])(f: (ScriptedHandler, Int) => A): A =
    val handler = new ScriptedHandler(answers)
    val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/", handler)
    server.setExecutor(Executors.newCachedThreadPool())
    server.start()
    try f(handler, server.getAddress.getPort)
    finally server.stop(0)

  private def deadPort(): Int =
    val s = new ServerSocket(0)
    val p = s.getLocalPort
    s.close()
    p

  private def armLeg(root: os.Path, port: Int): IO[Unit] =
    for
      _ <- WeixinOutboundAdapter.ensureToken(root)
      _ <- IO(
        SocialChannels.save(
          root,
          WeixinIlinkBridgePlugin.Name,
          io.circe.parser.parse(s"""{"fields":{"sidecar_url":"http://127.0.0.1:$port"}}""").toOption.get
        )
      )
    yield ()

  private def withAppender[A](
    body: ch.qos.logback.core.read.ListAppender[ch.qos.logback.classic.spi.ILoggingEvent] => A
  ): A =
    val lb = org.slf4j.LoggerFactory
      .getLogger("nebflow.social.weixin-outbound")
      .asInstanceOf[ch.qos.logback.classic.Logger]
    val appender = new ch.qos.logback.core.read.ListAppender[ch.qos.logback.classic.spi.ILoggingEvent]
    appender.start()
    lb.addAppender(appender)
    try body(appender)
    finally lb.detachAppender(appender)

  /** 快照之后**所有**非 INFO 行（WARN/ERROR）——失败面只允许这两级。 */
  private def newFailures(
    appender: ch.qos.logback.core.read.ListAppender[ch.qos.logback.classic.spi.ILoggingEvent],
    before: Int
  ): List[String] =
    appender.list.asScala.toList
      .drop(before)
      .filter(_.getLevel == ch.qos.logback.classic.Level.ERROR)
      .map(_.getFormattedMessage)

  private def snapshot(appender: ch.qos.logback.core.read.ListAppender[ch.qos.logback.classic.spi.ILoggingEvent]): Int =
    appender.list.size

  /** 毫秒级策略：3 次、10ms 起、封顶 20ms —— 判定不看真实时间。 */
  private val fastPolicy = RetryPolicy(3, 10.millis, 20.millis)

  // ───────────────────────── ① 分类表 ─────────────────────────

  test("WX-RT1 retryableFailure: ret=-2 / 5xx 可重试；超时、401、503-配置、200-未投递 一次即止") {
    def r(code: Int, reason: String) = WeixinOutboundAdapter.SendResult(false, false, None, code, reason)

    val retryable = List(
      r(502, "delivery_failed: sendmessage ret=-2 errmsg=prepare failed"), // 重启后的实况形态
      r(502, "delivery_failed: cdn http_500"),
      r(500, "delivery_failed: sendmessage ret=-1 errmsg=busy"),
      r(504, "delivery_failed: upstream timeout")
    )
    val terminal = List(
      r(-1, "sidecar_unreachable: ReadTimeout: 40 seconds"), // §0.4：可能已送达 ⇒ 绝不重发
      r(-1, "shim_token_unconfigured: no token at secrets/weixin-ilink-send-token"),
      r(401, "shim_auth_rejected"),
      r(400, "delivery_failed: bad target"),
      r(200, "delivery_failed: side-car answered ok without delivered")
    )
    retryable.foreach(x => assert(WeixinOutboundAdapter.retryableFailure(x), s"应可重试：$x"))
    terminal.foreach(x => assert(!WeixinOutboundAdapter.retryableFailure(x), s"不该重试：$x"))
  }

  // ───────────────────── ② 有界退避真的在跑 ─────────────────────

  test("WX-RT2 连续 ret=-2 ⇒ 3 次尝试、3 条 ERROR（attempts=1/2/3）、返回最后一次读数") {
    val root = tmpRoot()
    withStub(List((502, ret502Body))) { (handler, port) =>
      armLeg(root, port).unsafeRunSync()
      withAppender { appender =>
        val before = snapshot(appender)
        val r = WeixinOutboundAdapter.sendLive(root, fastPolicy)("wx_alice", "重启后这条").unsafeRunSync()
        val fired = newFailures(appender, before)
        assertEquals(r.ok, false)
        assertEquals(handler.hits.get, 3, "有界退避：3 次尝试真的发出去了")
        assertEquals(fired.length, 3, s"每次失败一条 ERROR（作者铁律②），got $fired")
        assert(
          fired.head.contains("attempts=1") && fired(1).contains("attempts=2") && fired(2).contains("attempts=3"),
          s"attempts= 是真实序号：$fired"
        )
        assert(fired.forall(_.contains("channel=weixin-ilink")), "每条都带渠道")
        assert(fired.forall(_.contains("to=wx_alice")), "每条都带收件人")
        assert(fired.forall(_.contains("sendmessage ret=-2")), "iLink ret 明细原样保留")
        assert(r.reason.contains("ret=-2"), "返回的是最后一次读数，不吞不改写")
      }
    }
  }

  test("WX-RT3 恢复即停：第 2 次成功 ⇒ 交付成功、只留一条失败行、正好 2 次请求") {
    val root = tmpRoot()
    withStub(List((502, ret502Body), (200, deliveredBody))) { (handler, port) =>
      armLeg(root, port).unsafeRunSync()
      withAppender { appender =>
        val before = snapshot(appender)
        val r = WeixinOutboundAdapter.sendLive(root, fastPolicy)("wx_alice", "恢复了").unsafeRunSync()
        val fired = newFailures(appender, before)
        assertEquals(r.ok, true, "退避之后就送达了 —— 作者令③要的正是这一条")
        assertEquals(r.messageId, Some("421001"))
        assertEquals(handler.hits.get, 2, "成功即停，不再多试")
        assertEquals(fired.length, 1, s"只留第 1 次失败的那一行，got $fired")
        assert(fired.head.contains("attempts=1"), fired.head)
      }
    }
  }

  test("WX-RT4 确定性拒绝（401）一次即止：不浪费 3 次名额") {
    val root = tmpRoot()
    withStub(List((401, """{"error":"shim_auth_rejected"}"""))) { (handler, port) =>
      armLeg(root, port).unsafeRunSync()
      withAppender { appender =>
        val before = snapshot(appender)
        val r = WeixinOutboundAdapter.sendLive(root, fastPolicy)("wx_alice", "hi").unsafeRunSync()
        val fired = newFailures(appender, before)
        assertEquals(r.ok, false)
        assertEquals(handler.hits.get, 1, "鉴权拒绝重试只是把同一份拒绝多写两行")
        assertEquals(fired.length, 1)
        assert(fired.head.contains("shim_auth_rejected"), fired.head)
      }
    }
  }

  test("WX-RT5 超时类（sidecar_unreachable）**永不重试** —— design §0.4 的重发边界没被放宽") {
    val root = tmpRoot()
    withAppender { appender =>
      armLeg(root, deadPort()).unsafeRunSync()
      val before = snapshot(appender)
      val r = WeixinOutboundAdapter.sendLive(root, fastPolicy)("wx_alice", "hello").unsafeRunSync()
      val fired = newFailures(appender, before)
      assertEquals(r.ok, false)
      assert(r.reason.startsWith("sidecar_unreachable"), r.reason)
      assertEquals(fired.length, 1, s"中立失败只有一行、只有一次，got $fired")
      assert(fired.head.contains("attempts=1"), "attempts=1 证明没有重发（可能已送达）")
    }
  }

  // ───────────────── ④ 生产默认装配带策略（接线证据） ─────────────────

  test("WX-RT6 生产默认缝 DefaultSend 装的是 Bounded：一条失败 ⇒ 3 次尝试（作者令③接线）") {
    val root = tmpRoot()
    withStub(List((502, ret502Body))) { (handler, port) =>
      armLeg(root, port).unsafeRunSync()
      withAppender { appender =>
        val before = snapshot(appender)
        val r = WeixinIlinkBridgePlugin.DefaultSend(root)("wx_alice", "生产默认").unsafeRunSync()
        val fired = newFailures(appender, before)
        assertEquals(r.ok, false)
        assertEquals(handler.hits.get, RetryPolicy.Bounded.maxAttempts, "生产默认 = 有界退避（3 次），不是一次即弃")
        assertEquals(fired.length, RetryPolicy.Bounded.maxAttempts, s"got $fired")
        assert(fired.last.contains(s"attempts=${RetryPolicy.Bounded.maxAttempts}"), fired.last)
      }
    }
  }

end WeixinOutboundRetrySpec
