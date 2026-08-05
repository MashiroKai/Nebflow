package nebflow.core.mcp

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.sun.net.httpserver.HttpServer
import io.circe.Json
import munit.FunSuite

import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * End-to-end tests for the Streamable-HTTP transport (MCP 2025-06-18) using a
 * real local HTTP server: header emission (Accept / MCP-Protocol-Version /
 * Mcp-Session-Id), JSON single-response compatibility, and SSE response
 * parsing.
 */
class HttpTransportSpec extends FunSuite:

  /** Server behavior: (method, requestBody, requestHeaders) -> (status, contentType, body, extraHeaders). */
  private type Handler = (String, String, Map[String, String]) => (Int, String, String, Map[String, String])

  private val requestLog = new ConcurrentLinkedQueue[(String, String, Map[String, String])]()

  private def withServer(handler: Handler)(test: HttpTransport => Unit): Unit =
    requestLog.clear()
    val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/", exchange =>
      val body = new String(exchange.getRequestBody.readAllBytes(), "UTF-8")
      val headers = scala.collection.mutable.Map[String, String]()
      exchange.getRequestHeaders.forEach { (k, vs) => vs.forEach(v => headers(k.toLowerCase) = v) }
      requestLog.add((exchange.getRequestMethod, body, headers.toMap))
      val (status, contentType, respBody, extraHeaders) =
        handler(exchange.getRequestMethod, body, headers.toMap)
      extraHeaders.foreach { case (k, v) => exchange.getResponseHeaders.add(k, v) }
      exchange.getResponseHeaders.add("Content-Type", contentType)
      val bytes = respBody.getBytes("UTF-8")
      exchange.sendResponseHeaders(status, bytes.length)
      exchange.getResponseBody.write(bytes)
      exchange.close()
    )
    server.start()
    val port = server.getAddress.getPort
    val transport = new HttpTransport(s"http://127.0.0.1:$port", Map("X-Custom" -> "yes"))
    try test(transport)
    finally
      transport.close().unsafeRunSync()
      server.stop(0)

  private def req(id: Int, method: String) =
    JsonRpcRequest(id = Json.fromInt(id), method = method, params = None)

  private def headersOf(method: String): Map[String, String] =
    requestLog.toArray.toList
      .map(_.asInstanceOf[(String, String, Map[String, String])])
      .find(_._1 == method)
      .map(_._3)
      .getOrElse(Map.empty)

  test("JSON response (application/json) is parsed — backward compatible"):
    withServer { (_, _, _) =>
      (200, "application/json", """{"jsonrpc":"2.0","id":3,"result":{"ok":true}}""", Map.empty)
    } { transport =>
      val response = transport.send(req(3, "ping")).unsafeRunSync()
      assertEquals(response.id, Json.fromInt(3))
      assertEquals(response.error, None)
      assertEquals(response.result.flatMap(_.hcursor.get[Boolean]("ok").toOption), Some(true))
    }

  test("JSON-RPC error response is surfaced"):
    withServer { (_, _, _) =>
      (200, "application/json", """{"jsonrpc":"2.0","id":5,"error":{"code":-32601,"message":"Method not found"}}""", Map.empty)
    } { transport =>
      val response = transport.send(req(5, "nope")).unsafeRunSync()
      assertEquals(response.error.map(_.code), Some(-32601))
      assertEquals(response.error.map(_.message), Some("Method not found"))
    }

  test("SSE response — extracts the JSON-RPC response matching the request id"):
    val sseBody =
      """event: message
        |data: {"jsonrpc":"2.0","method":"notifications/message","params":{"level":"info"}}
        |
        |data: {"jsonrpc":"2.0","id":7,"result":{"tools":[{"name":"t1"}]}}
        |
        |""".stripMargin
    withServer { (_, _, _) =>
      (200, "text/event-stream", sseBody, Map.empty)
    } { transport =>
      val response = transport.send(req(7, "tools/list")).unsafeRunSync()
      assertEquals(response.id, Json.fromInt(7))
      assertEquals(response.error, None)
      val tools = response.result.flatMap(_.hcursor.downField("tools").as[List[Json]].toOption)
      assertEquals(tools.map(_.size), Some(1))
    }

  test("SSE response — falls back to the first message carrying an id"):
    val sseBody =
      """data: {"jsonrpc":"2.0","id":42,"result":{"done":true}}
        |""".stripMargin
    withServer { (_, _, _) =>
      (200, "text/event-stream", sseBody, Map.empty)
    } { transport =>
      // Request id differs (99) — transport should still pick up the response by id field
      val response = transport.send(req(99, "anything")).unsafeRunSync()
      assertEquals(response.id, Json.fromInt(42))
      assertEquals(response.result.flatMap(_.hcursor.get[Boolean]("done").toOption), Some(true))
    }

  test("SSE response with no response message throws"):
    withServer { (_, _, _) =>
      (200, "text/event-stream", """data: {"jsonrpc":"2.0","method":"notifications/initialized"}""" + "\n\n", Map.empty)
    } { transport =>
      intercept[RuntimeException] {
        transport.send(req(1, "ping")).unsafeRunSync()
      }
    }

  test("requests carry Accept + MCP-Protocol-Version headers"):
    withServer { (_, _, _) =>
      (200, "application/json", """{"jsonrpc":"2.0","id":1,"result":{}}""", Map.empty)
    } { transport =>
      transport.send(req(1, "initialize")).unsafeRunSync()
      val h = headersOf("POST")
      assert(h.get("accept").exists(_.contains("application/json")))
      assert(h.get("accept").exists(_.contains("text/event-stream")), "Accept must include text/event-stream")
      assertEquals(h.get("mcp-protocol-version"), Some(McpProtocol.Version))
      assertEquals(h.get("x-custom"), Some("yes"), "custom headers still forwarded")
    }

  test("session id from initialize response is sent on subsequent requests"):
    withServer { (_, _, _) =>
      (200, "application/json", """{"jsonrpc":"2.0","id":1,"result":{}}""", Map("Mcp-Session-Id" -> "sess-abc"))
    } { transport =>
      transport.send(req(1, "initialize")).unsafeRunSync()
      // First request must NOT carry a session id yet
      val first = requestLog.toArray.toList.map(_.asInstanceOf[(String, String, Map[String, String])]).head._3
      assert(!first.contains("mcp-session-id"), "initialize must not send a session id")
      // Subsequent requests carry it
      transport.send(req(2, "tools/list")).unsafeRunSync()
      val second = requestLog.toArray.toList.map(_.asInstanceOf[(String, String, Map[String, String])]).last._3
      assertEquals(second.get("mcp-session-id"), Some("sess-abc"))
    }

  test("sendNotification carries protocol + session headers"):
    withServer { (_, _, _) =>
      (200, "application/json", """{"jsonrpc":"2.0","id":1,"result":{}}""", Map("Mcp-Session-Id" -> "sess-xyz"))
    } { transport =>
      transport.send(req(1, "initialize")).unsafeRunSync()
      transport.sendNotification(JsonRpcNotification(method = "notifications/initialized")).unsafeRunSync()
      val notif = requestLog.toArray.toList.map(_.asInstanceOf[(String, String, Map[String, String])]).last
      assertEquals(notif._1, "POST")
      assertEquals(notif._3.get("mcp-session-id"), Some("sess-xyz"))
      assertEquals(notif._3.get("mcp-protocol-version"), Some(McpProtocol.Version))
      assert(notif._3.get("accept").exists(_.contains("text/event-stream")))
    }
end HttpTransportSpec
