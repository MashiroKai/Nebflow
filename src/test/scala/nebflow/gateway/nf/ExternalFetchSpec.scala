package nebflow.gateway.nf

import cats.effect.unsafe.implicits.global
import nebflow.gateway.*

import java.io.{InputStream, OutputStream}
import java.net.{InetAddress, ServerSocket}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import munit.FunSuite

/**
 * 拉取动作面的端到端断言（external-open 批，2026-11-19）：真 socket、真 HTTP/1.1
 * 报文、真落盘。用一个 **loopback 上的最小 HTTP 服务**当对端——生产闸禁止
 * loopback，所以这里注入两条 spec 专用闸：
 *
 *   - [[allowLoopback]]：只允许 `127.0.0.1`（把客户端本身测到）；
 *   - 重定向用例：第一跳允许 loopback，第二跳回到**生产闸**（`P.gateUrl`）并让
 *     `internal.test` 解析到 `10.1.2.3` ⇒ 「跳去私网」必须被拒（负控 ② 的跳转腿）。
 *
 * 生产装配点（`ExternalOpenRoutes` 的默认参数）用的是 `ExternalOpenPolicy.gateUrl`
 * ——本文件的两条注入只存在于 spec 内，没有任何配置面能改到它。
 */
class ExternalFetchSpec extends FunSuite:

  private val P = ExternalOpenPolicy

  private def ip(s: String): InetAddress = InetAddress.getByName(s)

  /** 只放行 loopback 的闸（spec 专用）。 */
  private val allowLoopback: ExternalFetch.Gate = uri =>
    if uri.getHost == "127.0.0.1" then Right((uri, "127.0.0.1", List(ip("127.0.0.1"))))
    else Left(P.Refusal(403, "url-private-address", s"spec gate refuses ${uri.getHost}"))

  /** 第一跳 loopback、其余走**生产闸**并让任意域名解析到 10.1.2.3。 */
  private val redirectToPrivateGate: ExternalFetch.Gate = uri =>
    if uri.getHost == "127.0.0.1" then Right((uri, "127.0.0.1", List(ip("127.0.0.1"))))
    else P.gateUrl(uri.toString, _ => List(ip("10.1.2.3")))

  // ── 最小 HTTP 服务（一次一连接；`Connection: close` 语义与客户端一致）──────

  private def response(status: String, headers: Seq[String], body: Array[Byte]): Array[Byte] =
    // 状态行**不带**结尾 CRLF（带上的话 mkString 的隔离符会再给一个 ⇒ 头部提前
    // 结束，剩下的头行会被当成 body —— 这正是本 spec 第一次跑红的原因）。
    val head = (s"HTTP/1.1 $status" +: headers :+ "").mkString("\r\n") + "\r\n"
    head.getBytes(StandardCharsets.ISO_8859_1) ++ body

  private def okBytes(body: Array[Byte]): Array[Byte] =
    response("200 OK", Seq(s"Content-Length: ${body.length}", "Content-Type: application/octet-stream"), body)

  private final class Server(responses: Vector[Array[Byte]]):
    private val socket = new ServerSocket(0, 16, ip("127.0.0.1"))
    val port: Int = socket.getLocalPort
    @volatile var served: Int = 0
    private val thread = new Thread(
      () =>
        responses.foreach { resp =>
          val conn =
            try Some(socket.accept())
            catch case _: Throwable => None
          conn.foreach { s =>
            try
              readHead(s.getInputStream)
              val out: OutputStream = s.getOutputStream
              out.write(resp)
              out.flush()
              served += 1
            catch case _: Throwable => ()
            finally
              try s.close()
              catch case _: Throwable => ()
          }
        },
      "nf-ext-test-server"
    )
    thread.setDaemon(true)
    thread.start()
    def close(): Unit = try socket.close() catch case _: Throwable => ()

  private def readHead(in: InputStream): Unit =
    var state = 0
    while state < 4 do
      val b = in.read()
      if b < 0 then state = 4
      else
        state = (state, b) match
          case (0, 13) => 1
          case (1, 10) => 2
          case (2, 13) => 3
          case (3, 10) => 4
          case (_, 13) => 1
          case (_, 10) => 2
          case _ => 0

  private def withServer[A](responses: Vector[Array[Byte]])(f: Server => A): A =
    val s = new Server(responses)
    try f(s)
    finally s.close()

  private def tmpFile(): Path =
    Files.createTempDirectory("nf-ext-fetch").resolve(".tmp-test")

  // ── ① 正常公网拉取（正控）──────────────────────────────────────────────

  test("a normal fetch stores the bytes, hashes them and sniffs the real type"):
    val png = Array[Byte](0x89.toByte, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a) ++
      Array.fill[Byte](512)(7)
    withServer(Vector(okBytes(png))) { server =>
      val tmp = tmpFile()
      val outcome = ExternalFetch
        .fetch(s"http://127.0.0.1:${server.port}/a/lying-name.txt", tmp, gate = allowLoopback)
        .unsafeRunSync()
      val o = outcome.getOrElse(fail(s"expected success, got $outcome"))
      assertEquals(o.size, png.length.toLong)
      assertEquals(o.sha256, P.sha256Hex(png))
      assertEquals(P.sniffExtension(o.head), Some("png"))
      // 落盘内容与字节完全一致（sha 是内容寻址的根）
      assertEquals(Files.size(tmp), png.length.toLong)
      assertEquals(P.sha256Hex(Files.readAllBytes(tmp)), o.sha256)
    }

  test("a chunked response is assembled"): 
    val body = "0123456789abcdef".getBytes(StandardCharsets.US_ASCII)
    val chunked =
      response("200 OK", Seq("Transfer-Encoding: chunked", "Content-Type: text/plain"), Array.emptyByteArray) ++
        "4\r\n0123\r\n4\r\n4567\r\n8\r\n89abcdef\r\n0\r\n\r\n".getBytes(StandardCharsets.US_ASCII)
    withServer(Vector(chunked)) { server =>
      val outcome = ExternalFetch
        .fetch(s"http://127.0.0.1:${server.port}/x.txt", tmpFile(), gate = allowLoopback)
        .unsafeRunSync()
      val o = outcome.getOrElse(fail(s"expected success, got $outcome"))
      assertEquals(o.size, body.length.toLong)
      assertEquals(o.sha256, P.sha256Hex(body))
    }

  // ── ② 超限必拒（负控 ③）──────────────────────────────────────────────

  test("a body over the byte cap is refused with too-large and no partial file is kept"):
    val big = Array.fill[Byte](64 * 1024)(1)
    withServer(Vector(okBytes(big))) { server =>
      val tmp = tmpFile()
      val outcome = ExternalFetch
        .fetch(s"http://127.0.0.1:${server.port}/big.bin", tmp, maxBytes = 1024, gate = allowLoopback)
        .unsafeRunSync()
      outcome match
        case Left(r) =>
          assertEquals(r.status, 413)
          assertEquals(r.reason, "too-large")
        case Right(o) => fail(s"expected too-large, got ${o.size} bytes")
      assert(!Files.exists(tmp), "the partial temp file must be deleted")
    }

  // ── ③ 跳转到私网必拒（负控 ② 的跳转腿）───────────────────────────────

  test("a redirect into a private address is refused (no auto-follow)"):
    val redirect = response(
      "302 Found",
      Seq("Location: http://internal.test/secret", "Content-Length: 0"),
      Array.emptyByteArray
    )
    withServer(Vector(redirect)) { server =>
      val outcome = ExternalFetch
        .fetch(s"http://127.0.0.1:${server.port}/go", tmpFile(), gate = redirectToPrivateGate)
        .unsafeRunSync()
      outcome match
        case Left(r) =>
          assertEquals(r.reason, "url-private-address")
          assert(r.message.contains("10.1.2.3"), r.message)
        case Right(o) => fail(s"expected the redirect hop to be refused, got ${o.size} bytes")
    }

  test("a redirect loop is bounded by MaxRedirects"):
    val redirect = response(
      "302 Found",
      Seq("Location: /again", "Content-Length: 0"),
      Array.emptyByteArray
    )
    // 同一连接上的 4 个响应（首跳 + 3 次重定向后仍要第 4 跳 → 超限）
    withServer(Vector.fill(8)(redirect)) { server =>
      val outcome = ExternalFetch
        .fetch(s"http://127.0.0.1:${server.port}/loop", tmpFile(), gate = allowLoopback)
        .unsafeRunSync()
      outcome match
        case Left(r) => assertEquals(r.reason, "too-many-redirects")
        case Right(o) => fail(s"expected too-many-redirects, got ${o.size} bytes")
    }

  // ── ④ 「DNS 解析到私网」在生产闸下必拒（负控 ②，零网络）────────────────

  test("with the production gate, a host resolving to 127.0.0.1 is refused before connecting"):
    val tmp = tmpFile()
    val outcome = ExternalFetch
      .fetch("https://evil.example/a.png", tmp, resolve = _ => List(ip("127.0.0.1")))
      .unsafeRunSync()
    outcome match
      case Left(r) => assertEquals(r.reason, "url-private-address")
      case Right(o) => fail(s"expected refusal, got ${o.size} bytes")
    assert(!Files.exists(tmp))

  test("with the production gate, a metadata address is refused"):
    val outcome = ExternalFetch
      .fetch("http://169.254.169.254/latest/meta-data/", tmpFile(), resolve = _ => List(ip("169.254.169.254")))
      .unsafeRunSync()
    outcome match
      case Left(r) => assertEquals(r.reason, "url-private-address")
      case Right(_) => fail("expected refusal")

  test("with the production gate, file:// is refused"):
    val outcome = ExternalFetch.fetch("file:///etc/passwd", tmpFile()).unsafeRunSync()
    outcome match
      case Left(r) =>
        assertEquals(r.reason, "url-scheme")
        assertEquals(r.status, 400)
      case Right(_) => fail("expected refusal")

end ExternalFetchSpec
