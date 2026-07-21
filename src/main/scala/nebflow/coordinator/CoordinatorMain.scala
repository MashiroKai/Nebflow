package nebflow.coordinator

import cats.effect.{IO, IOApp}
import com.comcast.ip4s.{Host, Port}
import nebflow.core.NebflowLogger
import org.http4s.ember.server.EmberServerBuilder

object CoordinatorMain extends IOApp.Simple:
  private val logger = NebflowLogger.forName("nebflow.coordinator.main")

  private val port = 9090

  def run: IO[Unit] =
    for
      _ <- logger.info(s"Starting Nebflow Coordination Server on port $port")
      store <- CoordinatorStore.make
      service = new CoordinatorService(store)
      routes = new CoordinatorRoutes(service)
      _ <- service.startCleanupTask()
      server <- EmberServerBuilder.default[IO]
        .withHost(Host.fromString("0.0.0.0").get)
        .withPort(Port.fromInt(port).get)
        .withHttpApp(routes.routes.orNotFound)
        .build
        .use { _ =>
          logger.info(s"Coordination server started on :$port") *>
            IO.never[Unit]
        }
    yield server

end CoordinatorMain
