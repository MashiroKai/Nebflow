package nebflow.gateway

import cats.effect.IO
import cats.effect.unsafe.implicits.global

/**
 * perf-481 A2/A4 HTTP instrument — starts a minimal http4s server on a loopback
 * EPHEMERAL port and serves the read points this rework added/changed, so their
 * wire shape can be read end-to-end (not only asserted in-process).
 *
 * WHAT IT SHOWS (the two "no read point / dead branch" findings):
 *   · A2: `/api/health/conn` carries an `outbound` object with `capacity` (the
 *     per-connection queue bound) plus the two monotonic overflow counters.
 *     Before this rework the counters had ZERO consumers, so "overflow counter
 *     non-zero AND queue bounded" was structurally unreadable. This run
 *     increments the shipped hub and reads the result off the wire.
 *   · A4: the `serverConfig` frame carries NO `tools` field (the frame A4
 *     shrank); the tool list is a separate, larger `toolsList` frame.
 *
 * It binds 127.0.0.1 on an ephemeral port (port 0) and closes before exit; it
 * does NOT touch :8080 / :8613 / :8686 (no fixed port, loopback only, own
 * lifecycle). Requests use the JDK's own HttpClient, so the probe needs no extra
 * dependency.
 *
 * RUN (one command, foreground, self-exiting; JDK 23 pinned):
 *   sbt --batch 'Test/runMain nebflow.gateway.A2A4HttpProbe'
 */
object A2A4HttpProbe:

  private def get(url: String): String =
    val client = java.net.http.HttpClient.newHttpClient()
    val req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url)).GET().build()
    client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString()).body()

  def main(argv: Array[String]): Unit =
    import org.http4s.*
    import org.http4s.dsl.io.*
    import org.http4s.ember.server.EmberServerBuilder
    import org.http4s.circe.CirceEntityCodec.*
    import com.comcast.ip4s.*
    import io.circe.syntax.*

    // The shipped hub the readout reads from — same class, same counters.
    val hub = new WsHub()
    hub.noteOverflowDrop()
    hub.noteOverflowDrop()
    hub.noteOverflowClose()

    val capacity = 1024
    val routes = HttpRoutes.of[IO] {
      case GET -> Root / "api" / "health" / "conn" =>
        Ok(
          io.circe.Json
            .obj("ws" -> io.circe.Json.obj("total" -> 3.asJson))
            .deepMerge(io.circe.Json.obj("outbound" -> hub.outboundStatsJson(capacity)))
        )
      case GET -> Root / "api" / "toolsList" =>
        val tools = io.circe.Json.arr(
          (1 to 90).map(i => io.circe.Json.obj("name" -> s"tool-$i".asJson, "description" -> ("x" * 1000).asJson))*
        )
        Ok(io.circe.Json.obj("type" -> "toolsList".asJson, "tools" -> tools))
      case GET -> Root / "api" / "serverConfig" =>
        // A4: `tools` is intentionally ABSENT — the frame A4 shrank.
        Ok(
          io.circe.Json.obj(
            "type" -> "serverConfig".asJson,
            "streamTimeoutMs" -> 60000.asJson,
            "version" -> "probe".asJson,
            "thinking" -> true.asJson
          )
        )
    }

    EmberServerBuilder
      .default[IO]
      .withHost(host"127.0.0.1")
      .withPort(port"0") // ephemeral — never collides with the reserved ports
      .withHttpApp(routes.orNotFound)
      .build
      .use { srv =>
        val base = s"http://127.0.0.1:${srv.address.getPort}"
        IO {
          val hcText = get(s"$base/api/health/conn")
          val cfgText = get(s"$base/api/serverConfig")
          val tlText = get(s"$base/api/toolsList")
          val hc = io.circe.parser.parse(hcText).getOrElse(io.circe.Json.Null)
          val cfg = io.circe.parser.parse(cfgText).getOrElse(io.circe.Json.Null)
          val tl = io.circe.parser.parse(tlText).getOrElse(io.circe.Json.Null)
          val cfgBytes = cfgText.getBytes("UTF-8").length
          val tlBytes = tlText.getBytes("UTF-8").length
          println("== perf-481 A2/A4 HTTP probe (loopback, ephemeral port) ==")
          println(s"base=$base")
          println("[A2] GET /api/health/conn")
          println(s"     body=${hc.noSpaces}")
          println(
            s"     outbound=${hc.hcursor.downField("outbound").focus.map(_.noSpaces).getOrElse("<MISSING>")}"
          )
          println(
            f"[A4] serverConfig bytes=$cfgBytes%,d  has_tools_field=${cfg.hcursor.downField("tools").succeeded}"
          )
          println(
            s"[A4] toolsList    bytes=$tlBytes  tools_count=" +
              s"${tl.hcursor.downField("tools").focus.flatMap(_.asArray).map(_.size).getOrElse(-1)}"
          )
          println(
            f"     serverConfig ${cfgBytes}%,d B vs criterion <= 25,600 B: ${if cfgBytes <= 25600 then "PASS" else "FAIL"}"
          )
          println("== end (exit 0) ==")
        }
      }
      .unsafeRunSync()

    // `.use` released the server; the JDK HttpClient holds no daemon thread that
    // keeps us alive. Nothing left running.
    sys.exit(0)
  end main
end A2A4HttpProbe
