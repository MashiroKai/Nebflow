package nebflow.neblink

import cats.effect.IO
import com.sun.net.httpserver.{HttpExchange, HttpServer}
import munit.CatsEffectSuite

import java.net.{InetSocketAddress, URI}
import scala.concurrent.duration.*

/**
 * 2026-11-04 旧域 301 批的**同类回归闸**（`AvatarProxy` 跳转策略 + `AvatarByteCache`）。
 *
 * 存在的理由：本批缺陷（好友头像 URL 指旧域 → 301 → 代理腿 502 → 字节缓存永远
 * 建不起来）不是一处笔误，而是**两个类级缺口**：
 *   ① 「3xx 一律当失败」——JDK `HttpClient` 默认 `Redirect.NEVER`，而 `fetch`
 *      只认 `status == 200`；任何 3xx（301/302/303/307/308）都会重演同一故障；
 *   ② 「代理腿每次请求都回源」——`If-None-Match` 判定在取数**之后**，没有
 *      网关侧字节缓存，于是「第二次请求」和「第一次」等价。
 * 本文件把两条都钉成机械判据（走**真 transport**、真 mock 源站，不是替身）：
 *   A. 五种跳转状态码逐个必须被跟随（不是只测 301）；跳数有上限；
 *   B. 白名单外 / 降级 / 异常 scheme 的跳转必须**拒绝且零请求外发**；
 *   C. 缓存：命中零回源、失败不落盘、TTL 过期即未命中、条目数与总字节有界。
 */
class AvatarProxyRedirectSpec extends CatsEffectSuite:

  private val AvatarBytes = Array[Byte](9, 8, 7, 6)

  // ── mock 源站 ────────────────────────────────────────────

  /** 每种跳转状态码一个路径 + 同源终点 + 跳数循环 + 白名单外终点。 */
  private def startOrigin(offsiteTarget: String): IO[(HttpServer, String)] =
    IO.blocking {
      val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
      def respond(ex: HttpExchange, status: Int, bytes: Array[Byte], contentType: String): Unit =
        ex.getRequestBody.transferTo(java.io.OutputStream.nullOutputStream())
        ex.getRequestBody.close()
        ex.getResponseHeaders.add("Content-Type", contentType)
        ex.sendResponseHeaders(status, bytes.length.toLong)
        val os = ex.getResponseBody
        os.write(bytes)
        os.close()
      def redirect(ex: HttpExchange, status: Int, location: String): Unit =
        ex.getRequestBody.transferTo(java.io.OutputStream.nullOutputStream())
        ex.getRequestBody.close()
        ex.getResponseHeaders.add("Location", location)
        ex.sendResponseHeaders(status, -1L)
        ex.close()
      server.createContext("/final.png", ex => respond(ex, 200, AvatarBytes, "image/png"))
      // 五种跳转状态码 ⇒ 全部指向 /final.png（任何一种不被跟随 = 同一缺陷重演）
      Seq(301, 302, 303, 307, 308).foreach { code =>
        server.createContext(s"/r$code", ex => redirect(ex, code, "/final.png"))
      }
      server.createContext("/loop", ex => redirect(ex, 301, "/loop"))
      server.createContext("/offsite", ex => redirect(ex, 302, offsiteTarget + "/final.png"))
      server.start()
      (server, s"http://127.0.0.1:${server.getAddress.getPort}")
    }

  private def startPlain: IO[(HttpServer, String)] =
    IO.blocking {
      val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
      server.createContext(
        "/final.png",
        ex =>
          ex.getRequestBody.transferTo(java.io.OutputStream.nullOutputStream())
          ex.getRequestBody.close()
          ex.getResponseHeaders.add("Content-Type", "image/png")
          ex.sendResponseHeaders(200, AvatarBytes.length.toLong)
          val os = ex.getResponseBody
          os.write(AvatarBytes)
          os.close()
      )
      server.start()
      (server, s"http://127.0.0.1:${server.getAddress.getPort}")
    }

  private def withOrigin[A](use: String => IO[A]): IO[A] =
    startPlain.flatMap { case (plain, plainBase) =>
      startOrigin(plainBase).flatMap { case (origin, base) =>
        use(base).guarantee(IO.blocking(origin.stop(0))).guarantee(IO.blocking(plain.stop(0)))
      }
    }

  // ── A. 跳转跟随（真 transport）────────────────────────────

  test("五种跳转状态码（301/302/303/307/308）逐个被跟随 ⇒ 终点字节") {
    withOrigin { base =>
      Seq(301, 302, 303, 307, 308).foldLeft(IO.unit) { (acc, code) =>
        acc *> AvatarProxy.fetch(AvatarProxy.jdkFetch)(s"$base/r$code").flatMap {
          case Left(err) => IO(fail(s"$code must be followed, got Left($err)"))
          case Right((contentType, bytes)) =>
            IO {
              assertEquals(contentType, "image/png", s"status $code")
              assertEquals(bytes.toSeq, AvatarBytes.toSeq, s"status $code")
            }
        }
      }
    }
  }

  test("跳数上限存在且被遵守（自指 301 ⇒ Left 且点名 redirect，不无限跟随）") {
    withOrigin { base =>
      AvatarProxy.fetch(AvatarProxy.jdkFetch)(s"$base/loop").map {
        case Left(err) => assert(err.contains("redirect"), s"error should name the redirect failure: $err")
        case Right(ok) => fail(s"self-referencing redirect must not succeed: $ok")
      }
    }
  }

  test("跳数上限幅度在 (0, 5] —— 有界但足够覆盖真实一跳") {
    assert(AvatarProxy.MaxRedirects >= 1 && AvatarProxy.MaxRedirects <= 5, s"MaxRedirects=${AvatarProxy.MaxRedirects}")
  }

  test("白名单外跳转 ⇒ 拒跟（Left，且请求不外发到第三方主机）") {
    withOrigin { base =>
      AvatarProxy.fetch(AvatarProxy.jdkFetch)(s"$base/offsite").map {
        case Left(err) => assert(err.contains("redirect"), s"error should name the refusal: $err")
        case Right(ok) => fail(s"off-allowlist redirect must be refused: $ok")
      }
    }
  }

  test("IsRedirect 集合语义：3xx 跳转面钉死（300/304/305/306 与 2xx/4xx 不是跳转）") {
    Seq(301, 302, 303, 307, 308).foreach(c => assert(AvatarProxy.isRedirectStatus(c), s"$c must be a redirect"))
    Seq(200, 204, 300, 304, 305, 306, 400, 500).foreach(c =>
      assert(!AvatarProxy.isRedirectStatus(c), s"$c must NOT be a redirect")
    )
  }

  // ── B. 白名单 / scheme 策略（纯函数）──────────────────────

  test("resolveRedirect：相对地址按 RFC 3986 相对当前 URL 解析；畸形/空 ⇒ Left") {
    val from = URI.create("https://mashiro.staging.nebflow.space/avatars/u1.png")
    assertEquals(AvatarProxy.resolveRedirect(from, "/a.png").map(_.toString), Right("https://mashiro.staging.nebflow.space/a.png"))
    assertEquals(
      AvatarProxy.resolveRedirect(from, "https://staging.mystand.space/avatars/u1.png").map(_.toString),
      Right("https://staging.mystand.space/avatars/u1.png")
    )
    assert(AvatarProxy.resolveRedirect(from, "  ").isLeft)
    assert(AvatarProxy.resolveRedirect(from, "http://[::1").isLeft)
  }

  test("redirectAllowed：同 origin 与产品域族内放行；异 host 拒绝") {
    val families = AvatarProxy.redirectHostFamilies
    val from = URI.create("https://mashiro.staging.nebflow.space/avatars/u1.png")
    def check(target: String, allowed: Boolean, label: String): Unit =
      val uri = URI.create(target)
      assertEquals(AvatarProxy.redirectAllowed(from, uri, families).isRight, allowed, s"$label ($target)")
    check("https://mashiro.staging.nebflow.space/a.png", allowed = true, "同 origin")
    // 线上实测的跳转（2026-11-04 20:50 CST）：旧域 → 新域，必须能跟
    check("https://staging.mystand.space/avatars/u1.png", allowed = true, "品牌域族内")
    check("https://auth.nebflow.space/x.png", allowed = true, "旧服务子域族内")
    check("https://evil.example.com/x.png", allowed = false, "域外主机")
    check("https://mystand.space.evil.example.com/x.png", allowed = false, "后缀混淆")
  }

  test("redirectAllowed：https→http 降级与非 http(s) scheme 一律拒绝") {
    val families = AvatarProxy.redirectHostFamilies
    val from = URI.create("https://staging.mystand.space/a.png")
    assert(AvatarProxy.redirectAllowed(from, URI.create("http://staging.mystand.space/a.png"), families).isLeft)
    assert(AvatarProxy.redirectAllowed(from, URI.create("file:///etc/passwd"), families).isLeft)
    assert(AvatarProxy.redirectAllowed(from, URI.create("ftp://staging.mystand.space/a.png"), families).isLeft)
  }

  test("redirectHostFamilies：含当前品牌域（单一真源 Branding.domain）与旧服务子域族") {
    val families = AvatarProxy.redirectHostFamilies
    assert(families.contains(nebflow.shared.Branding.domain.toLowerCase), s"families=$families")
    assert(families.contains(AvatarProxy.RetiredServiceHostFamily))
    assert(families.forall(_.nonEmpty))
  }

  // ── C. 字节缓存 ─────────────────────────────────────────

  test("缓存：未命中 ⇒ fetch 一次；再取 ⇒ 零 fetch（可数）") {
    for
      cache <- AvatarByteCache.create()
      counter <- cats.effect.Ref.of[IO, Int](0)
      fetch = counter.update(_ + 1).as(Right(("image/png", AvatarBytes)))
      first <- cache.getOrFetch("u")(fetch)
      second <- cache.getOrFetch("u")(fetch)
      n <- counter.get
    yield
      assertEquals(n, 1)
      assertEquals(first.map(_.sha256).toOption, Some(AvatarProxy.sha256Hex(AvatarBytes)))
      assertEquals(second.map(_.sha256).toOption, first.map(_.sha256).toOption)
      assertEquals(first.map(_.etag).toOption, Some("\"" + AvatarProxy.sha256Hex(AvatarBytes) + "\""))
  }

  test("缓存：失败不落盘（Left 原样上抛，条目数为 0）") {
    for
      cache <- AvatarByteCache.create()
      res <- cache.getOrFetch("u")(IO.pure(Left("upstream HTTP 301")))
      size <- cache.size
    yield
      assert(res.isLeft, s"failure must not be cached as success: $res")
      assertEquals(res.swap.getOrElse(""), "upstream HTTP 301")
      assertEquals(size, 0)
  }

  test("缓存：TTL 过期 ⇒ 再未命中（不会永久吐旧字节）") {
    for
      cache <- AvatarByteCache.create(ttl = 50.millis)
      now <- IO.realTime.map(_.toMillis)
      _ <- cache.put("u", AvatarByteCache.Entry("image/png", AvatarBytes, "sha", fetchedAtMs = now))
      before <- cache.get("u")
      _ <- IO.sleep(80.millis)
      after <- cache.get("u")
    yield
      assertEquals(before.map(_.sha256), Some("sha"))
      assertEquals(after, None)
  }

  test("缓存：条目数与总字节有界（超限淘汰最旧，绝不无界增长）") {
    for
      now <- IO.realTime.map(_.toMillis)
      byCount <- AvatarByteCache.create(maxEntries = 2)
      _ <- byCount.put("a", AvatarByteCache.Entry("image/png", Array[Byte](1), "a", now - 3))
      _ <- byCount.put("b", AvatarByteCache.Entry("image/png", Array[Byte](2), "b", now - 2))
      _ <- byCount.put("c", AvatarByteCache.Entry("image/png", Array[Byte](3), "c", now - 1))
      n1 <- byCount.size
      oldestGone <- byCount.get("a")
      newest <- byCount.get("c")
      byBytes <- AvatarByteCache.create(maxEntries = 100, maxTotalBytes = 2L)
      _ <- byBytes.put("a", AvatarByteCache.Entry("image/png", Array[Byte](1, 1), "a", now - 3))
      _ <- byBytes.put("b", AvatarByteCache.Entry("image/png", Array[Byte](2, 2), "b", now - 2))
      n2 <- byBytes.size
      byEntry <- AvatarByteCache.create(maxEntryBytes = 2L)
      _ <- byEntry.put("big", AvatarByteCache.Entry("image/png", Array[Byte](1, 2, 3), "big", now - 1))
      n3 <- byEntry.size
    yield
      assertEquals(n1, 2)
      assertEquals(oldestGone, None)
      assertEquals(newest.map(_.sha256), Some("c"))
      assertEquals(n2, 1)
      assertEquals(n3, 0)
  }

  override def munitIOTimeout: scala.concurrent.duration.FiniteDuration = 60.seconds

end AvatarProxyRedirectSpec
