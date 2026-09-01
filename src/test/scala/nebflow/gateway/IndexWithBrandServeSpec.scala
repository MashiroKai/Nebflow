package nebflow.gateway

import cats.effect.unsafe.implicits.global
import munit.FunSuite
import org.http4s.{MediaType, Status}

import java.nio.charset.StandardCharsets

/**
  * Serve-level regression spec for the index brand injection — pinned at
  * the HTTP RESPONSE layer, not the injected-string layer.
  *
  * The original implementation used Ok(served) in a file that imports
  * org.http4s.circe.CirceEntityCodec.*: that import's String entity encoder
  * (lexical scope) won over http4s' built-in one (implicit scope), so the
  * whole HTML went out as a JSON string literal — body starting with '"',
  * quotes and newlines escaped, the inlined script invalid JS, every module
  * URL a quoted 404. The page never booted, while string-level greps kept
  * passing (the window.__BRAND__ substring is present in the corrupted body
  * too). These assertions read the encoded response bytes and fail on that
  * entire corruption class.
  */
class IndexWithBrandServeSpec extends FunSuite:

  private val response = WebSocketRoutes.indexWithBrand("web/index.html").unsafeRunSync()
  private val bytes = response.body.compile.toList.unsafeRunSync().toArray
  private val body = new String(bytes, StandardCharsets.UTF_8)

  test("status 200, content-type text/html") {
    assertEquals(response.status, Status.Ok)
    assertEquals(response.contentType.map(_.mediaType), Some(MediaType.text.html))
  }

  test("body is a raw HTML document, not a JSON string literal") {
    // First bytes of the document — a JSON-encoded body would start with '"'.
    assert(clue(body).startsWith("<!DOCTYPE"), s"body head: ${body.take(40)}")
    // a JSON-string-literal body carries backslash-escaped quotes
    assert(!clue(body).contains("\\\""), "JSON-escaped quotes found — entity encoder hijack regression")
    assert(!body.startsWith("\""), "body starts with a quote character")
  }

  test("brand script is inlined as executable markup") {
    assert(clue(body).contains("<script>window.__BRAND__="))
    // the contract JSON must appear with REAL quotes in the document —
    // the hijacked encoder would emit \"productName\" instead
    assert(clue(body).contains("\"productName\":\"Nebflow\""))
    assert(clue(body).contains("\"domain\":\"neblink.space\""))
    // 2026-09-01 login-chain fix: profileUrl joins the inlined contract
    assert(clue(body).contains("\"profileUrl\":\"https://neblink.space/profile\""))
  }

  test("response headers: explicit Content-Length matches body, no-cache") {
    val cl = response.headers.get(org.typelevel.ci.CIString("Content-Length"))
    assertEquals(cl.map(_.head.value), Some(bytes.length.toString))
    val cc = response.headers.get(org.typelevel.ci.CIString("Cache-Control"))
    assertEquals(cc.map(_.head.value), Some("no-cache"))
  }

  test("missing resource degrades to NotFound") {
    val resp = WebSocketRoutes.indexWithBrand("web/definitely-missing.html").unsafeRunSync()
    assertEquals(resp.status, Status.NotFound)
  }

end IndexWithBrandServeSpec
