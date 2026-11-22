package nebflow.llm.providers

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.sun.net.httpserver.{HttpExchange, HttpServer}
import io.circe.Json
import munit.CatsEffectSuite
import nebflow.shared.*
import nebflow.llm.SendMessageParams

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import scala.concurrent.duration.*

/**
 * 空白 text 块请求形状回归闸（2026-11-22 engine 修复批）。
 *
 * 现场根因：历史里落盘的空白 text 块（`""` / `"  \n"`）被 AnthropicAdapter 原样编码成
 * `{"type":"text","text":"  "}`，上游返回 `HTTP 400 messages: text content blocks must
 * contain non-whitespace text`；因为请求从恢复的历史重建，每次回放必 400 ⇒ frozen。
 *
 * 判据（线级，经真实 HTTP 收到的请求体）：编码后的 wire 请求里
 *  · 零个 text 块的 text 为空白；
 *  · 零条 content 为空（或空白字符串）的消息；
 *  · 有效内容照常保留；
 *  · 净化后历史为空 ⇒ 显式失败，且**不发任何请求**（不静默发空 messages）。
 */
class AnthropicBlankTextBlockSpec extends CatsEffectSuite:

  override val munitIOTimeout = 60.seconds

  private val anthropicJson =
    """{"id":"m","type":"message","role":"assistant","content":[{"type":"text","text":"OK"}],""" +
      """"stop_reason":"end_turn","usage":{"input_tokens":1,"output_tokens":1}}"""

  /** 收每一次请求体；回固定 200。 */
  private def serve(): (HttpServer, java.util.concurrent.atomic.AtomicReference[List[String]]) =
    val seen = new java.util.concurrent.atomic.AtomicReference[List[String]](Nil)
    val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext(
      "/v1/messages",
      (ex: HttpExchange) =>
        val body = new String(ex.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)
        seen.getAndUpdate(_ :+ body)
        val bytes = anthropicJson.getBytes(StandardCharsets.UTF_8)
        ex.getResponseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(200, bytes.length.toLong)
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

  private def adapterFor(port: Int) =
    new AnthropicAdapter(
      providerId = "Deepseek",
      apiKey = "sk-test",
      baseUrl = s"http://127.0.0.1:$port/",
      backend = backend,
      providerHeaders = None
    )

  private def paramsOf(history: List[Message]) =
    SendMessageParams(
      messages = history,
      model = "deepseek-flash",
      sessionId = Some("blank-text-spec"),
      agentId = Some("blank-text-spec")
    )

  /** 被 400 的那类历史：空串 / 纯空白 / 仅空白块 / 思考+空白正文 / 空白块与有效块混排。 */
  private val poisonedHistory: List[Message] = List(
    Message(MessageRole.User, Left("hi")),
    Message(MessageRole.Assistant, Right(List(ContentBlock.Thinking("plan", Some("sig")), ContentBlock.Text("   ")))),
    Message(MessageRole.User, Left("")),
    Message(MessageRole.Assistant, Left("\n\t")),
    Message(MessageRole.User, Right(List(ContentBlock.Text(" "), ContentBlock.Text("real question")))),
    Message(MessageRole.Assistant, Right(List(ContentBlock.Text("")))),
    Message(MessageRole.User, Left("continue"))
  )

  private def wireMessages(body: String): List[Json] =
    io.circe.parser.parse(body).toOption.flatMap(_.hcursor.get[List[Json]]("messages").toOption).getOrElse(Nil)

  private def blankTextBlocks(msgs: List[Json]): List[Json] =
    msgs.flatMap { m =>
      m.hcursor.get[Vector[Json]]("content").toOption.toList.flatten.filter { b =>
        b.hcursor.get[String]("type").toOption.contains("text") &&
        b.hcursor.get[String]("text").toOption.exists(_.isBlank)
      }
    }

  private def emptyMessages(msgs: List[Json]): List[Json] =
    msgs.filter { m =>
      m.hcursor.get[String]("content").toOption.exists(_.isBlank) ||
      m.hcursor.get[Vector[Json]]("content").toOption.exists(_.isEmpty)
    }

  test("wire request carries no blank text block and no empty message from a poisoned history") {
    val (server, seen) = serve()
    adapterFor(server.getAddress.getPort)
      .sendMessage(paramsOf(poisonedHistory))
      .attempt
      .map { r =>
        val bodies = seen.get()
        assert(r.isRight, s"mock must answer 200 once the history is sanitized, got ${r.left.map(_.toString)}")
        assertEquals(bodies.size, 1, "exactly one request must reach the wire")
        val msgs = wireMessages(bodies.head)
        assertEquals(blankTextBlocks(msgs), Nil, s"no blank text block may reach the wire: $msgs")
        assertEquals(emptyMessages(msgs), Nil, s"no empty message may reach the wire: $msgs")
        val allText = msgs.flatMap(m =>
          m.hcursor.get[Vector[Json]]("content").toOption.toList.flatten.flatMap(_.hcursor.get[String]("text").toOption)
        )
        assert(allText.contains("real question"), s"valid content must survive sanitization: $allText")
        assert(allText.contains("continue"), s"valid content must survive sanitization: $allText")
      }
      .guarantee(IO(server.stop(0)))
  }

  test("history that is entirely blank is refused explicitly and sends nothing") {
    val (server, seen) = serve()
    val blankOnly = List(
      Message(MessageRole.User, Left("   ")),
      Message(MessageRole.Assistant, Right(List(ContentBlock.Text("")))),
      Message(MessageRole.User, Left(""))
    )
    adapterFor(server.getAddress.getPort)
      .sendMessage(paramsOf(blankOnly))
      .attempt
      .map { r =>
        assert(r.isLeft, s"a blank-only history must fail explicitly, got $r")
        assert(
          r.left.toOption.exists(_.getMessage.contains("non-blank")),
          s"refusal must name the cause, got ${r.left.map(_.getMessage)}"
        )
        assertEquals(seen.get(), Nil, "no request may be sent for a blank-only history")
      }
      .guarantee(IO(server.stop(0)))
  }
end AnthropicBlankTextBlockSpec
