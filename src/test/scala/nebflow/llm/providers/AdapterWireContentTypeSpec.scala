package nebflow.llm.providers

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import com.sun.net.httpserver.{HttpExchange, HttpServer}
import munit.CatsEffectSuite
import nebflow.shared.*
import nebflow.llm.{ProviderAdapter, SendMessageParams}

import java.net.InetSocketAddress
import scala.jdk.CollectionConverters.*
import java.nio.charset.StandardCharsets
import scala.concurrent.duration.*

/**
 * 假 DOWN 批（2026-11-05）判据 A 的**回归闸**：请求的 Content-Type 只能有一个。
 *
 * 现场根因（本轮实测定位，逐字证据见批报告 §A）：两个 adapter 的四处装配点原先都是
 *
 * {{{
 * basicRequest.post(uri"$endpoint")
 *   .header("content-type", "application/json")   // ① 手写头
 *   .body(json)                                   // ② sttp 的 StringBody 再带一份 contentType
 * }}}
 *
 * sttp 不会去重 ⇒ JDK 发出**两行同名头**，本地 mock 现读实测：
 *
 * {{{
 * Content-type=[application/json, application/json]
 * }}}
 *
 * 后果分层（这就是「Deepseek 永久 DOWN」的真根因之一）：
 *   · cmdcode（Cloudflare 前置）容忍重复值 ⇒ 200，缺陷隐身；
 *   · DeepSeek 的 anthropic 边缘做严格单值校验 ⇒ **HTTP 415
 *     `Expected request with Content-Type: application/json`**（引擎真身实测，见批报告）；
 *   · 415 走 ProviderError/Permanent ⇒ markDown，而**探测也是同一条装配腿** ⇒
 *     每一轮探测都 415 ⇒ 健康表永远清不回来 ⇒ 作者面「Deepseek 一直卡在 DOWN」。
 *
 * 修法 = 用 sttp 的 contentType 单一入口（V2 实测：只发一个头）。本 spec 把「只发一个」
 * 钉成机械判据：任何一处回退成手写 `.header("content-type", …)` 都当场红。
 */
class AdapterWireContentTypeSpec extends CatsEffectSuite:

  override val munitIOTimeout = 60.seconds

  private val anthropicJson =
    """{"id":"m","type":"message","role":"assistant","content":[{"type":"text","text":"OK"}],""" +
      """"stop_reason":"end_turn","usage":{"input_tokens":1,"output_tokens":1}}"""
  private val openAiJson =
    """{"id":"c","object":"chat.completion","model":"m","choices":[{"index":0,"message":{"role":"assistant","content":"OK"},"finish_reason":"stop"}],"usage":{"prompt_tokens":1,"completion_tokens":1}}"""
  private val anthropicSse =
    "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"id\":\"m\",\"usage\":{\"input_tokens\":1,\"output_tokens\":0}}}\n\n" +
      "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"OK\"}}\n\n" +
      "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n"
  private val openAiSse =
    "data: {\"id\":\"c\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"OK\"}}]}\n\n" +
      "data: [DONE]\n\n"

  /** 收一份请求头快照，回一个固定响应。 */
  private def serve(path: String, body: String, sse: Boolean): (HttpServer, java.util.concurrent.atomic.AtomicReference[List[String]]) =
    val seen = new java.util.concurrent.atomic.AtomicReference[List[String]](Nil)
    val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext(
      path,
      (ex: HttpExchange) =>
        val _ = ex.getRequestBody.readAllBytes()
        // getRequestHeaders 是 case-insensitive map；同名多值会以 List 形态保留 ⇒
        // 「发了两行 content-type」在这里看得见（这正是线上 415 的成因）。
        val values = Option(ex.getRequestHeaders.get("Content-type")).map(_.asScala.toList).getOrElse(Nil)
        seen.set(values)
        val bytes = body.getBytes(StandardCharsets.UTF_8)
        ex.getResponseHeaders.add("Content-Type", if sse then "text/event-stream" else "application/json")
        ex.sendResponseHeaders(200, if sse then 0 else bytes.length.toLong)
        val os = ex.getResponseBody
        os.write(bytes)
        os.flush()
        os.close()
        ex.close()
    )
    server.start()
    (server, seen)

  private def backend =
    sttp.client4.httpclient.fs2.HttpClientFs2Backend
      .usingClient[IO](
        java.net.http.HttpClient.newBuilder().version(java.net.http.HttpClient.Version.HTTP_1_1).build(),
        cats.effect.std.Dispatcher.parallel[IO].allocated.unsafeRunSync()._1
      )

  private val params =
    SendMessageParams(
      messages = List(Message(MessageRole.User, Left("hi"))),
      model = "deepseek-flash",
      sessionId = Some("wire-spec"),
      agentId = Some("wire-spec")
    )

  private def assertSingleJsonContentType(values: List[String], face: String): Unit =
    assertEquals(
      values,
      List("application/json"),
      s"$face must send EXACTLY ONE Content-Type: application/json " +
        s"(duplicate values => DeepSeek edge answers HTTP 415 and the probe can never clear DOWN), got $values"
    )

  test("anthropic adapter (non-stream) sends exactly one content-type") {
    val (server, seen) = serve("/v1/messages", anthropicJson, sse = false)
    val adapter = new AnthropicAdapter(
      providerId = "Deepseek",
      apiKey = "sk-test",
      baseUrl = s"http://127.0.0.1:${server.getAddress.getPort}/",
      backend = backend,
      providerHeaders = None
    )
    adapter.sendMessage(params).attempt
      .map(r => (assert(r.isRight, s"mock must answer 200, got $r"), assertSingleJsonContentType(seen.get(), "anthropic non-stream")))
      .guarantee(IO(server.stop(0)))
  }

  test("anthropic adapter (stream) sends exactly one content-type") {
    val (server, seen) = serve("/v1/messages", anthropicSse, sse = true)
    val adapter = new AnthropicAdapter(
      providerId = "Deepseek",
      apiKey = "sk-test",
      baseUrl = s"http://127.0.0.1:${server.getAddress.getPort}/",
      backend = backend,
      providerHeaders = None
    )
    adapter.sendMessageStream(params).compile.toList.attempt
      .map(r => (assert(r.isRight, s"mock must stream 200, got ${r.left.map(_.toString)}"), assertSingleJsonContentType(seen.get(), "anthropic stream")))
      .guarantee(IO(server.stop(0)))
  }

  test("openai adapter (non-stream) sends exactly one content-type") {
    val (server, seen) = serve("/chat/completions", openAiJson, sse = false)
    val adapter = new OpenAiAdapter(
      providerId = "cmdcode",
      apiKey = "sk-test",
      baseUrl = s"http://127.0.0.1:${server.getAddress.getPort}/",
      backend = backend,
      providerHeaders = None
    )
    adapter.sendMessage(params).attempt
      .map(r => (assert(r.isRight, s"mock must answer 200, got $r"), assertSingleJsonContentType(seen.get(), "openai non-stream")))
      .guarantee(IO(server.stop(0)))
  }

  test("openai adapter (stream) sends exactly one content-type") {
    val (server, seen) = serve("/chat/completions", openAiSse, sse = true)
    val adapter = new OpenAiAdapter(
      providerId = "cmdcode",
      apiKey = "sk-test",
      baseUrl = s"http://127.0.0.1:${server.getAddress.getPort}/",
      backend = backend,
      providerHeaders = None
    )
    adapter.sendMessageStream(params).compile.toList.attempt
      .map(r => (assert(r.isRight, s"mock must stream 200, got ${r.left.map(_.toString)}"), assertSingleJsonContentType(seen.get(), "openai stream")))
      .guarantee(IO(server.stop(0)))
  }
end AdapterWireContentTypeSpec
