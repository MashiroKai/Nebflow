package nebflow.neblink

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import ch.qos.logback.classic.Logger as LogbackLogger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.sun.net.httpserver.{HttpExchange, HttpServer}
import munit.FunSuite
import org.slf4j.LoggerFactory

import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.net.{InetAddress, InetSocketAddress, ServerSocket}
import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters.*

/**
 * clientperf 批（2026-09-13）的验收件：
 *
 *   - 件 1：`NeblinkClient.sendRequestTimed` 的出网异常分桶仪表 —— 分类正确 +
 *     真异常真落日志（不是只看代码里有这行）；
 *   - 件 3：`OutboundHttpClients` 共享策略工厂 —— 身份/构数 + **网络层**复用
 *     观测（共享 client 两次请求同一源端口；改前的 per-call client 两次请求
 *     两个源端口）。
 *
 * 只用本机 127.0.0.1 与 JDK 自带的 com.sun.net.httpserver：零外部网络、零新进程。
 */
class OutboundTransportPolicySpec extends FunSuite:

  // ── 件 1：分桶分类 ────────────────────────────────────────────────────────

  test("bucketOf separates the five buckets, most specific class first") {
    assertEquals(
      NeblinkClient.bucketOf(new java.net.http.HttpConnectTimeoutException("HTTP connect timed out")),
      NeblinkClient.Bucket.ConnectTimeout
    )
    assertEquals(
      NeblinkClient.bucketOf(new java.net.http.HttpTimeoutException("request timed out")),
      NeblinkClient.Bucket.Timeout
    )
    assertEquals(
      NeblinkClient.bucketOf(new java.io.IOException("Received GOAWAY frame")),
      NeblinkClient.Bucket.Goaway
    )
    assertEquals(
      NeblinkClient.bucketOf(new java.io.IOException("Connection reset by peer")),
      NeblinkClient.Bucket.ConnClosed
    )
    assertEquals(
      NeblinkClient.bucketOf(new java.io.IOException("Connection closed by peer")),
      NeblinkClient.Bucket.ConnClosed
    )
    assertEquals(
      NeblinkClient.bucketOf(new java.net.ConnectException("Connection refused")),
      NeblinkClient.Bucket.Other
    )
    assertEquals(NeblinkClient.bucketOf(new RuntimeException("boom")), NeblinkClient.Bucket.Other)
  }

  test("pathShapeOf drops host + query and masks identifier segments") {
    assertEquals(
      NeblinkClient.pathShapeOf("https://neblink.nebflow.space/api/device/heartbeat"),
      "/api/device/heartbeat"
    )
    assertEquals(
      NeblinkClient.pathShapeOf("https://h/api/friends/abc123/messages?token=SECRET&x=1"),
      "/api/friends/:id/messages"
    )
    assertEquals(
      NeblinkClient.pathShapeOf("http://h/api/conversations/9f8e7d6c-1234-4abc-9def-0123456789ab/read"),
      "/api/conversations/:id/read"
    )
    assertEquals(NeblinkClient.pathShapeOf("http://h"), "/")
    // 脱敏断言：host / query / token 一个都不许出现（这是硬约束）
    val shaped = NeblinkClient.pathShapeOf("https://neblink.nebflow.space/api/users/lookup?q=alice&token=SECRET")
    assert(!shaped.contains("neblink.nebflow.space"), shaped)
    assert(!shaped.contains("SECRET"), shaped)
    assert(!shaped.contains("alice"), shaped)
  }

  // ── 件 1：真异常 → 真落日志（验红实证） ────────────────────────────────────

  test("a real unreachable target is bucketed as 'other', logged once, and the Left text is unchanged") {
    val deadPort =
      val ss = new ServerSocket(0)
      try ss.getLocalPort
      finally ss.close()
    val url = s"http://127.0.0.1:$deadPort/api/device/heartbeat"

    // 独立对照：不经 NeblinkClient 的裸 JDK 调用，取同一失效面的异常文本
    val bareMessage =
      try
        val bare = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
        val req =
          HttpRequest.newBuilder(java.net.URI.create(url)).timeout(java.time.Duration.ofSeconds(5)).GET().build()
        val _ = bare.send(req, HttpResponse.BodyHandlers.ofString())
        "unexpected success"
      catch case e: Exception => Option(e.getMessage).getOrElse(e.toString)

    NeblinkClient.resetOutboundFailureCounters()
    var result: Either[String, String] = null
    val lines = captureLogs {
      result = probe(s"http://127.0.0.1:$deadPort")
        .rawSend("POST", url, """{"deviceToken":"SECRET-TOKEN"}""", 5000)
        .unsafeRunSync()
    }

    assertEquals(NeblinkClient.outboundFailureSnapshot.get(NeblinkClient.Bucket.Other), Some(1L))
    assertEquals(lines.size, 1, s"expected exactly one instrument line, got $lines")
    val line = lines.head
    println(s"[instrument] $line")
    assert(line.contains(s"bucket=${NeblinkClient.Bucket.Other}"), line)
    assert(line.contains("bucketCount=1"), line)
    assert(line.contains("method=POST"), line)
    assert(line.contains("path=/api/device/heartbeat"), line)
    assert(line.contains("at="), line)
    assert(!line.contains("SECRET-TOKEN"), line) // 脱敏：body/凭据不进日志
    assert(!line.contains(s"127.0.0.1:$deadPort"), line) // 脱敏：不带 host:port
    // 返回值语义不变：Left 文本 = 裸 JDK 抛出的同一条消息
    assertEquals(result, Left(bareMessage))
  }

  test("a real request timeout lands in the HttpTimeoutException bucket") {
    val blackhole = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    val holder = new Thread(() =>
      try
        val conn = blackhole.accept()
        Thread.sleep(4000)
        conn.close()
      catch case _: Throwable => ()
    )
    holder.setDaemon(true)
    holder.start()
    val url = s"http://127.0.0.1:${blackhole.getLocalPort}/api/health"

    NeblinkClient.resetOutboundFailureCounters()
    try
      var result: Either[String, String] = null
      val lines = captureLogs {
        result = probe(s"http://127.0.0.1:${blackhole.getLocalPort}")
          .rawSend("GET", url, "", 400) // 400 ms 请求超时 ≪ 黑洞的 4 s
          .unsafeRunSync()
      }
      assert(result.isLeft, s"expected Left, got $result")
      assertEquals(
        NeblinkClient.outboundFailureSnapshot.get(NeblinkClient.Bucket.Timeout),
        Some(1L),
        s"snapshot=${NeblinkClient.outboundFailureSnapshot} lines=$lines"
      )
      assert(lines.exists(_.contains(s"bucket=${NeblinkClient.Bucket.Timeout}")), s"lines=$lines")
      lines.foreach(l => println(s"[instrument] $l"))
    finally
      blackhole.close()
      holder.interrupt()
    end try
  }

  // ── 件 3：共享工厂（策略单点 + 复用非 0） ─────────────────────────────────

  test("the factory memoizes one client per policy, with the two policies' timeouts/proxy preserved") {
    val before = OutboundHttpClients.constructedClients
    val direct1 = OutboundHttpClients.client(OutboundHttpClients.Policy.Direct15s)
    val direct2 = OutboundHttpClients.client(OutboundHttpClients.Policy.Direct15s)
    val direct3 = OutboundHttpClients.client(OutboundHttpClients.Policy.Direct15s)
    val proxied = OutboundHttpClients.client(OutboundHttpClients.Policy.SystemProxy10s)

    assert(direct1 eq direct2, "same policy must return the same instance")
    assert(direct2 eq direct3, "same policy must return the same instance")
    assert(!(direct1 eq proxied), "different policies must not share an instance")

    val built = OutboundHttpClients.constructedClients - before
    println(s"[factory] lookups=4 (3x Direct15s + 1x SystemProxy10s) constructions=$built")
    assert(built <= 2, s"4 lookups must construct at most 2 clients (one per policy), built=$built")

    // 策略语义与改前逐字一致：两处都是 h1；15s 直连 vs 10s 走系统代理
    assertEquals(direct1.version(), HttpClient.Version.HTTP_1_1)
    assertEquals(proxied.version(), HttpClient.Version.HTTP_1_1)
    assertEquals(direct1.connectTimeout().get().getSeconds, 15L)
    assertEquals(proxied.connectTimeout().get().getSeconds, 10L)
    assert(direct1.proxy().isPresent, "Direct15s must pin the no-proxy selector")
    assert(proxied.proxy().isEmpty, "SystemProxy10s must leave the JDK default proxy selector")
  }

  test("the shared client reuses its TCP connection; the pre-change per-call clients do not") {
    val seen = new ConcurrentLinkedQueue[Integer]()
    val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext(
      "/ping",
      (ex: HttpExchange) =>
        seen.add(ex.getRemoteAddress.getPort)
        val bytes = "pong".getBytes("UTF-8")
        try
          ex.sendResponseHeaders(200, bytes.length.toLong)
          ex.getResponseBody.write(bytes)
        finally ex.close()
    )
    server.start()
    val port = server.getAddress.getPort
    try
      val shared = OutboundHttpClients.client(OutboundHttpClients.Policy.Direct15s)
      assertEquals(get(shared, s"http://127.0.0.1:$port/ping"), "pong")
      assertEquals(get(shared, s"http://127.0.0.1:$port/ping"), "pong")
      val sharedPorts = seen.asScala.toList

      val mark = seen.size
      // 改前的形态：每次调用新建一个 client（同一段 builder 链）
      val call1 = legacyPerCallClient()
      val _ = get(call1, s"http://127.0.0.1:$port/ping")
      closeQuietly(call1)
      val call2 = legacyPerCallClient()
      val _ = get(call2, s"http://127.0.0.1:$port/ping")
      closeQuietly(call2)
      val perCallPorts = seen.asScala.toList.drop(mark)

      println(
        s"[reuse] shared-client client ports=$sharedPorts (expect 1 distinct) ; per-call client ports=$perCallPorts (expect 2 distinct)"
      )
      assertEquals(sharedPorts.size, 2)
      assertEquals(sharedPorts.distinct.size, 1, s"shared client must reuse its connection, saw $sharedPorts")
      assertEquals(perCallPorts.size, 2)
      assertEquals(perCallPorts.distinct.size, 2, s"per-call clients must NOT reuse, saw $perCallPorts")
    finally server.stop(0)
    end try
  }

  // ── helpers ──────────────────────────────────────────────────────────────

  /** 触到真传输面：`sendRequestTimed` 是 protected，子类可暴露它。 */
  private class Probe(config: NeblinkServerConfig) extends NeblinkClient(config, serverPort = 1):

    def rawSend(method: String, url: String, body: String, timeoutMs: Long): IO[Either[String, String]] =
      sendRequestTimed(
        method,
        url,
        body,
        None,
        scala.concurrent.duration.FiniteDuration(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
      )

  private def probe(url: String): Probe =
    new Probe(NeblinkServerConfig(url = url, networkId = "spec-net", secret = "s"))

  private def captureLogs(body: => Unit): List[String] =
    val logger = LoggerFactory.getLogger("nebflow.neblink.client").asInstanceOf[LogbackLogger]
    val appender = new ListAppender[ILoggingEvent]()
    appender.start()
    logger.addAppender(appender)
    try body
    finally logger.detachAppender(appender)
    appender.list.asScala.toList.map(_.getFormattedMessage)

  /** 改前四个站点的构造链（逐字复刻，用于「复用从 0」的对照臂）。 */
  private def legacyPerCallClient(): HttpClient =
    HttpClient
      .newBuilder()
      .version(HttpClient.Version.HTTP_1_1)
      .proxy(java.net.ProxySelector.of(null))
      .connectTimeout(java.time.Duration.ofSeconds(15))
      .build()

  /**
   * `HttpClient#close()/shutdownNow()` 是 JDK 21+，`-release:17` 下走反射
   * （与 llm/interface.scala 的 invokeNoArg 同法）。只用于本 spec 自建的临时
   * client —— 共享工厂的实例绝不关闭（同 JVM 里其它 suite 还在用）。
   */
  private def closeQuietly(client: HttpClient): Unit =
    try
      classOf[HttpClient].getMethod("shutdownNow").invoke(client)
      ()
    catch case scala.util.control.NonFatal(_) => ()

  private def get(client: HttpClient, url: String): String =
    val req = HttpRequest
      .newBuilder(java.net.URI.create(url))
      .timeout(java.time.Duration.ofSeconds(5))
      .GET()
      .build()
    client.send(req, HttpResponse.BodyHandlers.ofString()).body()

end OutboundTransportPolicySpec
