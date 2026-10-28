package nebflow.neblink

import cats.effect.IO
import cats.effect.std.Dispatcher
import cats.effect.unsafe.implicits.global
import com.sun.net.httpserver.{HttpExchange, HttpServer}
import io.circe.Json
import io.circe.parser.*
import munit.FunSuite
import nebflow.shared.PathUtil

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue

import scala.jdk.CollectionConverters.*

import NeblinkCodecs.given // HeartbeatResponse 的手写解码器（同包 object 内 given）

/**
 * 门户 MVP E5（2026-10-10）：45s 心跳 payload 增 `uiPort`。
 *
 * 契约（方案 §7 契约冻结点）：附加键 `uiPort`（number，camelCase）= 网关端口——
 * 门户用它拼「控制台地址」（`http://<endpoint>:<uiPort>`，不带凭据）。
 *
 * 兼容面（新旧互兼容，serde 宽容）：
 *   · 新 engine → 旧 server：未知字段被 server 忽略（跨仓只读已核，本 spec 不覆盖）；
 *   · 旧 server → 新 engine：心跳**响应**侧新增字段必须被本侧解码器忽略——钉之。
 *
 * `serverPort` 传参 = gatewayPort（装配点 GatewayMain.scala:985 / NeblinkEnrollment
 * 热换点 :221 均传 `cfg.port.value`），断言值与 mock server 端口**刻意不同**（8123 vs
 * 临时端口），证明 uiPort 取的是「网关端口」这个语义位而不是对端端口。
 */
class NeblinkHeartbeatUiPortSpec extends FunSuite:

  /** (method, path, body) of every POST /api/device/heartbeat seen. */
  private case class Recorded(method: String, path: String, body: String)

  private var tmpDir: java.nio.file.Path = null
  private var savedRoot: os.Path = scala.compiletime.uninitialized

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    savedRoot = PathUtil.dataRoot
    tmpDir = Files.createTempDirectory("nb-hb-uiport-spec")
    PathUtil.setDataRoot(os.Path(tmpDir, os.pwd))

  override def afterEach(context: AfterEach): Unit =
    PathUtil.setDataRoot(savedRoot)
    os.remove.all(os.Path(tmpDir, os.pwd))
    super.afterEach(context)

  private def startMockServer: IO[(HttpServer, String, ConcurrentLinkedQueue[Recorded])] =
    IO.blocking {
      val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
      val heartbeats = new ConcurrentLinkedQueue[Recorded]()

      def respond(ex: HttpExchange, status: Int, body: String): Unit =
        ex.getRequestBody.transferTo(java.io.OutputStream.nullOutputStream())
        ex.getRequestBody.close()
        val bytes = body.getBytes(StandardCharsets.UTF_8)
        ex.getResponseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(status, bytes.length.toLong)
        val os = ex.getResponseBody
        os.write(bytes)
        os.close()

      server.createContext(
        "/api/device/login",
        ex => respond(ex, 200, """{"token":"tok-hb","networkId":"n1","deviceId":"d1","peers":[]}""")
      )
      server.createContext(
        "/api/device/heartbeat",
        ex =>
          val bodyBytes = ex.getRequestBody.readAllBytes()
          heartbeats.add(
            Recorded(ex.getRequestMethod, ex.getRequestURI.getPath, new String(bodyBytes, StandardCharsets.UTF_8))
          )
          respond(ex, 200, """{"peers":[]}""")
      )
      server.start()
      (server, s"http://127.0.0.1:${server.getAddress.getPort}", heartbeats)
    }

  private val GatewayPort = 8123 // 刻意 ≠ mock 端口：证明 uiPort = 网关端口语义位

  test("heartbeat body carries uiPort = the gateway port (number, camelCase key)") {
    val (client, server, heartbeats) = startMockServer
      .flatMap { case (server, url, heartbeats) =>
        val client = new NeblinkClient(NeblinkServerConfig(url = url, networkId = "n1", secret = "s"), GatewayPort)
        client.login("d1", "dev", "macos", Nil).flatMap {
          case Left(err) => IO.raiseError(new IllegalStateException(s"login failed: $err"))
          case Right(_) =>
            client.heartbeat.flatMap {
              case Left(err) => IO.raiseError(new IllegalStateException(s"heartbeat failed: $err"))
              case Right(_) => IO.pure((client, server, heartbeats))
            }
        }
      }
      .unsafeRunSync()

    try
      val beats = heartbeats.asScala.toList
      assertEquals(beats.length, 1, s"exactly one heartbeat expected, got $beats")
      val body = parse(beats.head.body).toOption.getOrElse(fail(s"non-JSON heartbeat body: ${beats.head.body}"))
      assertEquals(body.hcursor.downField("uiPort").as[Int].toOption, Some(GatewayPort))
      // 既有键不回归：endpoints / version 仍在
      assert(body.hcursor.downField("endpoints").succeeded, "endpoints key must stay in the heartbeat body")
      assertEquals(body.hcursor.downField("version").as[String].toOption, Some(nebflow.Version.string))
    finally IO.blocking(server.stop(0)).unsafeRunSync()
  }

  test("heartbeat decode ignores unknown response fields (server-side additive compat)") {
    val decoded = decode[HeartbeatResponse]("""{"peers":[],"uiPort":123,"someFutureField":{"a":1}}""")
    assertEquals(decoded.isRight, true, s"extra fields must not break the decode: $decoded")
    assertEquals(decoded.toOption.map(_.peers), Some(Nil))
  }
end NeblinkHeartbeatUiPortSpec
