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
 *
 * msi guard (Team #11 ③, 2026-08-28): on an msi install the update script
 * would silently re-install as a plain-jar layout (form drift), so it is
 * refused with a pointer to the download page instead. The msi form is
 * identified by the marker file packaging/build-msi.sh stages into the
 * app payload (nebflow.core.InstallLayout.isMsiInstall).
 */
object RemoteUpdateAction:

  /** Run the install script. Returns Right(msg) on success, Left(error) on failure. */
  def runInstallScript(beta: Boolean): IO[Either[String, String]] =
    val isWindows = sys.props.getOrElse("os.name", "").toLowerCase.contains("win")
    if nebflow.core.InstallLayout.isMsiInstall then
      IO.pure(
        Left(
          "This copy was installed with the Windows installer (msi); the in-app update " +
            "script would replace it with a plain-jar layout, so it was skipped. " +
            "Please download the latest msi from the download page and install it over this copy."
        )
      )
    else
      val script =
        if beta then
          if isWindows then
            """powershell -Command "$env:CHANNEL='beta'; iwr """ + nebflow.core.Branding.installPs1Url + """ | iex" """
          else "curl -fsSL " + nebflow.core.Branding.installUrl + " | sh -s -- --beta"
        else if isWindows then """powershell -Command "& { iwr """ + nebflow.core.Branding.installPs1Url + """ | iex }" """
        else "curl -fsSL " + nebflow.core.Branding.installUrl + " | sh"

      IO.blocking {
        import sys.process.*
        script.!
      }.flatMap {
        case 0     => IO.pure(Right("Update installed, restarting..."))
        case code  => IO.pure(Left(s"Install script failed (exit code: $code)"))
      }.handleErrorWith(e => IO.pure(Left(s"Install error: ${e.getMessage}")))

end RemoteUpdateAction
