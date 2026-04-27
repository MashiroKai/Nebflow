package nebflow.gateway

import nebflow.llm.LlmInterface

import cats.effect.{IO, IOApp}
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.Router

object GatewayMain extends IOApp.Simple:
  def run: IO[Unit] =
    GatewayConfig.load.flatMap { cfg =>
      LlmInterface.createLlm().flatMap { case (handle, releaseBackend) =>
        val httpApp = Router("/" -> ChatRoutes(handle).routes).orNotFound

        IO.consoleForIO.errorln(s"gateway listening on ${cfg.host}:${cfg.port}") *>
          EmberServerBuilder.default[IO]
            .withHost(cfg.host)
            .withPort(cfg.port)
            .withHttpApp(httpApp)
            .build
            .useForever
            .guarantee(releaseBackend)
      }
    }
