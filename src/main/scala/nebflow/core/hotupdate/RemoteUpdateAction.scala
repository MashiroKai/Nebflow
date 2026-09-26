/* 严格DAG第⑥步第二批裁定(2026-09-27):本文件自 nebflow/neblink/RemoteUpdateAction.scala 整文件落 core/hotupdate(原样迁移,package 改 nebflow.core.hotupdate;InstallLayout 引用自此为同包系上行引用),斩断 core→neblink 边;neblink 侧(NeblinkRelayTunnel)与 gateway 侧(NeblinkRoutes)改经下行 import/FQN 引用本处。 */
package nebflow.core.hotupdate

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

  /**
   * The exact install command this action would run for the given channel (single
   * source: [[runInstallScript]] executes what this returns, the hot-update batch-1
   * verification reads it without executing).
   */
  def installCommand(beta: Boolean): String =
    val isWindows = sys.props.getOrElse("os.name", "").toLowerCase.contains("win")
    if beta then
      if isWindows then
        """powershell -Command "$env:CHANNEL='beta'; iwr """ + nebflow.shared.Branding.installPs1Url + """ | iex" """
      else "curl -fsSL " + nebflow.shared.Branding.installUrl + " | sh -s -- --beta"
    else if isWindows then
      """powershell -Command "& { iwr """ + nebflow.shared.Branding.installPs1Url + """ | iex }" """
    else "curl -fsSL " + nebflow.shared.Branding.installUrl + " | sh"

  /** Run the install script. Returns Right(msg) on success, Left(error) on failure. */
  def runInstallScript(beta: Boolean): IO[Either[String, String]] =
    if nebflow.core.InstallLayout.isMsiInstall then
      IO.pure(
        Left(
          "This copy was installed with the Windows installer (msi); the in-app update " +
            "script would replace it with a plain-jar layout, so it was skipped. " +
            "Please download the latest msi from the download page and install it over this copy."
        )
      )
    else
      IO.blocking {
        import sys.process.*
        installCommand(beta).!
      }.flatMap {
        case 0 => IO.pure(Right("Update installed, restarting..."))
        case code => IO.pure(Left(s"Install script failed (exit code: $code)"))
      }.handleErrorWith(e => IO.pure(Left(s"Install error: ${e.getMessage}")))

end RemoteUpdateAction
