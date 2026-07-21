package nebflow.coordinator

import cats.effect.IO
import io.circe.Json
import io.circe.syntax.*
import nebflow.core.NebflowLogger
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.Http4sDsl
import org.http4s.headers.Authorization
import org.http4s.server.Router

class CoordinatorRoutes(service: CoordinatorService) extends Http4sDsl[IO]:
  private val logger = NebflowLogger.forName("nebflow.coordinator.routes")

  private def extractToken(req: Request[IO]): Option[String] =
    req.headers.get[Authorization].collect {
      case Authorization(Credentials.Token(AuthScheme.Bearer, token)) => token
    }

  val routes: HttpRoutes[IO] = Router("/api" -> HttpRoutes.of[IO] {

    // POST /api/network/create
    case req @ POST -> Root / "network" / "create" =>
      req.as[CreateNetworkRequest].flatMap { body =>
        service.createNetwork(body.name).flatMap { resp =>
          Ok(resp.asJson)
        }
      }

    // POST /api/device/login
    case req @ POST -> Root / "device" / "login" =>
      req.as[LoginRequest].flatMap { body =>
        service.loginDevice(body).flatMap {
          case Right(resp) => Ok(resp.asJson)
          case Left(err)   => Forbidden(ErrorResponse(err).asJson)
        }
      }

    // POST /api/device/heartbeat (Bearer token required)
    case req @ POST -> Root / "device" / "heartbeat" =>
      extractToken(req) match
        case Some(token) =>
          service.heartbeat(token).flatMap {
            case Right(resp) => Ok(resp.asJson)
            case Left(err)   => Forbidden(ErrorResponse(err).asJson)
          }
        case None => Forbidden(ErrorResponse("Missing token").asJson)

    // GET /api/device/peers (Bearer token required)
    case req @ GET -> Root / "device" / "peers" =>
      extractToken(req) match
        case Some(token) =>
          service.getPeers(token).flatMap {
            case Right(peers) => Ok(peers.asJson)
            case Left(err)    => Forbidden(ErrorResponse(err).asJson)
          }
        case None => Forbidden(ErrorResponse("Missing token").asJson)

    // POST /api/device/endpoints (Bearer token required)
    case req @ POST -> Root / "device" / "endpoints" =>
      extractToken(req) match
        case Some(token) =>
          req.as[UpdateEndpointsRequest].flatMap { body =>
            service.updateEndpoints(token, body).flatMap {
              case Right(_)   => Ok(Json.obj("ok" -> Json.fromBoolean(true)))
              case Left(err)  => Forbidden(ErrorResponse(err).asJson)
            }
          }
        case None => Forbidden(ErrorResponse("Missing token").asJson)

    // DELETE /api/device/logout (Bearer token required)
    case req @ DELETE -> Root / "device" / "logout" =>
      extractToken(req) match
        case Some(token) =>
          service.logout(token) *> Ok(Json.obj("ok" -> Json.fromBoolean(true)))
        case None => Forbidden(ErrorResponse("Missing token").asJson)

    // GET /api/health
    case GET -> Root / "health" =>
      Ok(Json.obj("status" -> Json.fromString("ok")))
  })

end CoordinatorRoutes
