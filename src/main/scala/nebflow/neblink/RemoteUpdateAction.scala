package nebflow.neblink

import cats.effect.IO

/**
 * Runs the remote update install script and returns the result.
 *
 * Shared logic used by:
 *   - RestApiRoutes POST /neblink/update (P2P endpoint)
 *   - NeblinkRelayTunnel handleRelayRequest for "RemoteUpdate" action (relay)
 *
 * The caller is responsible for scheduling the actual JVM restart after a
 * successful install — see RestartHelper.spawnRestart().
 */
object RemoteUpdateAction:

  /** Run the install script. Returns Right(msg) on success, Left(error) on failure. */
  def runInstallScript(beta: Boolean): IO[Either[String, String]] =
    val isWindows = sys.props.getOrElse("os.name", "").toLowerCase.contains("win")
    val script =
      if beta then
        if isWindows then
          """powershell -Command "$env:CHANNEL='beta'; iwr https://nebflow.space/install.ps1 | iex" """
        else "curl -fsSL https://nebflow.space/install.sh | sh -s -- --beta"
      else if isWindows then """powershell -Command "& { iwr https://nebflow.space/install.ps1 | iex }" """
      else "curl -fsSL https://nebflow.space/install.sh | sh"

    IO.blocking {
      import sys.process.*
      script.!
    }.flatMap {
      case 0     => IO.pure(Right("Update installed, restarting..."))
      case code  => IO.pure(Left(s"Install script failed (exit code: $code)"))
    }.handleErrorWith(e => IO.pure(Left(s"Install error: ${e.getMessage}")))

end RemoteUpdateAction
