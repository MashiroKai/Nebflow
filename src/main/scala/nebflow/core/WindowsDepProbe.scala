package nebflow.core

import cats.effect.IO
import nebflow.core.tools.RgHelper
import nebflow.core.tools.ShellSession

/** Boot-time dependency probe (Team #11 item ④): say out loud, at startup,
  * which Windows toolchain pieces (Git Bash / ripgrep) resolved and which
  * are missing — instead of failing later inside a tool call with a
  * one-line ToolError the user never sees.
  *
  * The msi bundles both (packaging/build-msi.sh stages MinGit + rg.exe);
  * zip/jar installs get them from release/install.ps1. Non-Windows: no-op.
  */
object WindowsDepProbe:

  private val logger = NebflowLogger.forName("nebflow.core.deps")

  def warnIfMissing: IO[Unit] =
    IO.delay {
      if InstallLayout.isWindows then
        val bashPath = ShellSession.resolvedBashPath
        val rgPath = RgHelper.resolvedPath
        for
          _ <-
            if bashPath == "bash" then
              logger.warn(
                "Git Bash not found (no bundled copy, no Git install, no usable PATH bash) — " +
                  "the Bash tool will fail until Git for Windows is installed. " +
                  "The msi ships a bundled copy under the app dir (\\git\\usr\\bin\\bash.exe)."
              )
            else logger.info(s"Git Bash: $bashPath")
          _ <- rgPath match
            case Some(p) => logger.info(s"ripgrep: $p")
            case None =>
              logger.warn(
                "ripgrep (rg) not found — Grep/Glob tools will be unavailable. " +
                  "Reinstall this app or install rg: https://github.com/BurntSushi/ripgrep"
              )
        yield ()
      else IO.unit
    }.flatten
