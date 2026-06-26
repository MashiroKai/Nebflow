package nebflow.server

import cats.effect.unsafe.implicits.global
import cats.effect.{IO, Resource}
import com.comcast.ip4s.{Host, Port}
import fs2.Stream
import io.circe.syntax.given
import io.circe.{Json, parser}
import nebflow.core.NebflowLogger
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.Router
import org.http4s.server.websocket.WebSocketBuilder2
import org.http4s.websocket.WebSocketFrame

import scala.concurrent.duration.*

/**
 * Relay server entry point.
 *
 * Usage: sbt "runMain nebflow.server.RelayServerMain [port] [dataDir]"
 * Default: port=9090, dataDir=./relay-data
 *
 * All endpoints accept POST /api/mesh with JSON body { "action": "...", ... }.
 * The protocol is compatible with the existing callCloudFunction client code.
 */
object RelayServerMain:
  private val logger = NebflowLogger.forName("nebflow.server")

  def main(args: Array[String]): Unit =
    val port = args.headOption.flatMap(_.toIntOption).getOrElse(9090)
    val dataDir = args.lift(1).getOrElse("./relay-data")

    logger.info(s"Starting Nebflow Relay Server on port $port, data dir: $dataDir")

    val program = for
      store <- Resource.eval(RelayStore.create(os.Path(dataDir)))
      wsManager <- Resource.eval(WebSocketManager.create)
      routes = new RelayRoutes(store, wsManager).routes
      _ <- Resource.eval(IO.delay(logger.info(s"Loaded ${store.userCount} users from disk")))
      server <- EmberServerBuilder
        .default[IO]
        .withHost(Host.fromString("0.0.0.0").get)
        .withPort(Port.fromInt(port).get)
        .withHttpWebSocketApp(wsb => routes(wsb))
        .withShutdownTimeout(5.seconds)
        .build
    yield server

    program.useForever.unsafeRunSync()
  end main

  extension (s: RelayStore) def userCount: Int = 0 // best-effort, avoids exposing Ref
end RelayServerMain

/**
 * HTTP routes for the relay server.
 * Single POST endpoint dispatches by "action" field, exactly like the cloud function.
 */
class RelayRoutes(store: RelayStore, wsManager: WebSocketManager):
  private val logger = NebflowLogger.forName("nebflow.server.routes")

  def routes(wsb: WebSocketBuilder2[IO]): HttpApp[IO] =
    val httpRoutes = HttpRoutes.of[IO] {
      case req @ POST -> Root / "api" / "mesh" =>
        req.as[Json].flatMap { body =>
          val action = body.hcursor.downField("action").as[String].getOrElse("")
          dispatch(action, body).handleErrorWith { e =>
            logger.warn(s"Action '$action' failed: ${e.getMessage}")
            Ok(Json.obj("code" -> 500.asJson, "message" -> e.getMessage.asJson))
          }
        }

      case GET -> Root / "health" =>
        Ok(Json.obj("status" -> "ok".asJson, "server" -> "nebflow-relay".asJson))

      // WebSocket endpoint for real-time relay push
      case req @ GET -> Root / "ws" =>
        val userId = req.params.getOrElse("userId", "")
        val deviceId = req.params.getOrElse("deviceId", "")
        val token = req.params.getOrElse("token", "")

        if userId.isEmpty || deviceId.isEmpty || token.isEmpty then Forbidden("Missing userId, deviceId, or token")
        else
          store
            .verifySession(userId, token)
            .flatMap { _ =>
              wsManager.register(userId, deviceId).flatMap { topic =>
                val sendStream = topic.subscribe(64).map(text => WebSocketFrame.Text(text: String))
                wsb.build(sendStream, _.evalMap(_ => IO.unit))
              }
            }
            .handleErrorWith { e =>
              Forbidden(s"Auth failed: ${e.getMessage}")
            }
    }

    Router("/" -> httpRoutes).orNotFound

  end routes

  private def dispatch(action: String, body: Json): IO[org.http4s.Response[IO]] =
    val hc = body.hcursor
    val result: IO[Json] = action match
      // Auth
      case "auth/check-username" =>
        val username = hc.downField("username").as[String].getOrElse("")
        store.checkUsername(username).map(available => Json.obj("available" -> available.asJson))

      case "auth/register" =>
        val username = hc.downField("username").as[String].getOrElse("")
        val password = hc.downField("password").as[String].getOrElse("")
        store.register(username, password)

      case "auth/login" =>
        val username = hc.downField("username").as[String].getOrElse("")
        val password = hc.downField("password").as[String].getOrElse("")
        store.login(username, password)

      // Discovery
      case "discover/register" =>
        val userId = hc.downField("userId").as[String].getOrElse("")
        val sessionToken = hc.downField("sessionToken").as[String].getOrElse("")
        val deviceId = hc.downField("deviceId").as[String].getOrElse("")
        val deviceName = hc.downField("deviceName").as[String].getOrElse("Unknown")
        val platform = hc.downField("platform").as[String].getOrElse("")
        val address = hc.downField("address").as[String].getOrElse("")
        val caps = hc.downField("capabilities").as[Map[String, String]].getOrElse(Map.empty)
        val userDesc = hc.downField("userDescription").as[String].getOrElse("")
        val deviceSecret = hc.downField("deviceSecret").as[String].getOrElse("")
        for
          _ <- store.verifySession(userId, sessionToken)
          r <- store.registerDevice(
            userId,
            deviceId,
            deviceName,
            platform,
            address,
            caps,
            userDesc,
            deviceSecret
          )
          // Notify this account's other already-connected devices that a new peer joined,
          // so they can re-run discovery immediately (seconds) instead of waiting for the
          // next periodic tick. Best-effort: ignored if no WS clients connected.
          _ <- wsManager
            .broadcastToUser(
              userId,
              Json.obj(
                "type" -> "peer-joined".asJson,
                "device" -> Json.obj(
                  "deviceId" -> deviceId.asJson,
                  "deviceName" -> deviceName.asJson,
                  "platform" -> platform.asJson,
                  "address" -> address.asJson,
                  "capabilities" -> caps.asJson,
                  "userDescription" -> userDesc.asJson,
                  "deviceSecret" -> deviceSecret.asJson
                )
              )
            )
            .handleErrorWith(_ => IO.unit)
        yield r
        end for

      case "discover/lookup" =>
        val userId = hc.downField("userId").as[String].getOrElse("")
        val sessionToken = hc.downField("sessionToken").as[String].getOrElse("")
        val deviceId = hc.downField("deviceId").as[String].getOrElse("")
        for
          _ <- store.verifySession(userId, sessionToken)
          r <- store.lookupDevices(userId, if deviceId.nonEmpty then Some(deviceId) else None)
        yield r

      case "device/capabilities" =>
        val userId = hc.downField("userId").as[String].getOrElse("")
        val sessionToken = hc.downField("sessionToken").as[String].getOrElse("")
        val deviceId = hc.downField("deviceId").as[String].getOrElse("")
        val caps = hc.downField("capabilities").as[Map[String, String]].getOrElse(Map.empty)
        val desc = hc.downField("userDescription").as[String].getOrElse("")
        for
          _ <- store.verifySession(userId, sessionToken)
          // Re-register device with updated info
          r <- store.registerDevice(userId, deviceId, "Unknown", "", "", caps, desc, "")
        yield r

      // Session sync + busy lock — removed (session sync deleted)

      // Relay
      case "relay/submit" =>
        val userId = hc.downField("userId").as[String].getOrElse("")
        val sessionToken = hc.downField("sessionToken").as[String].getOrElse("")
        val fromDeviceId = hc.downField("fromDeviceId").as[String].getOrElse("")
        val toDeviceId = hc.downField("toDeviceId").as[String].getOrElse("")
        val params = hc.downField("params").as[Json].getOrElse(Json.obj())
        val realAction = hc.downField("relayAction").as[String].getOrElse("")
        for
          _ <- store.verifySession(userId, sessionToken)
          r <- store.relaySubmit(userId, fromDeviceId, toDeviceId, realAction, params)
          relayId = r.hcursor.downField("relayId").as[String].getOrElse("")
          _ <- wsManager
            .pushRelayCommand(userId, toDeviceId, relayId, fromDeviceId, realAction, params)
            .handleErrorWith(_ => IO.unit)
        yield r

      case "relay/poll" =>
        val userId = hc.downField("userId").as[String].getOrElse("")
        val sessionToken = hc.downField("sessionToken").as[String].getOrElse("")
        val deviceId = hc.downField("deviceId").as[String].getOrElse("")
        for _ <- store.verifySession(userId, sessionToken); r <- store.relayPoll(userId, deviceId) yield r

      case "relay/result" =>
        val userId = hc.downField("userId").as[String].getOrElse("")
        val sessionToken = hc.downField("sessionToken").as[String].getOrElse("")
        val relayId = hc.downField("relayId").as[String].getOrElse("")
        val resultStr = hc.downField("result").as[String].getOrElse("")
        val errorStr = hc.downField("error").as[String].getOrElse("")
        for
          _ <- store.verifySession(userId, sessionToken)
          r <- store.relayResult(relayId, resultStr, errorStr)
          // Push result to originator via WebSocket
          _ <- wsManager
            .pushRelayResult(userId, relayId, resultStr, errorStr)
            .handleErrorWith(_ => IO.unit)
        yield r

      case "relay/fetch-result" =>
        val userId = hc.downField("userId").as[String].getOrElse("")
        val sessionToken = hc.downField("sessionToken").as[String].getOrElse("")
        val relayId = hc.downField("relayId").as[String].getOrElse("")
        for _ <- store.verifySession(userId, sessionToken); r <- store.relayFetchResult(relayId) yield r

      // File sync — removed

      // Agent status broadcast — pushes to all WebSocket clients of this user
      case "agent/status" =>
        val userId = hc.downField("userId").as[String].getOrElse("")
        val sessionToken = hc.downField("sessionToken").as[String].getOrElse("")
        val status = hc.downField("status").as[Json].getOrElse(Json.obj())
        for
          _ <- store.verifySession(userId, sessionToken)
          _ <- wsManager.broadcastToUser(userId, status)
        yield Json.obj("ok" -> true.asJson)

      case other =>
        IO.pure(Json.obj("code" -> 400.asJson, "message" -> s"Unknown action: $other".asJson))

    result.flatMap(json => Ok(json))
  end dispatch

end RelayRoutes
