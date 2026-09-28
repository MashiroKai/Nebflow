package nebflow.core

import java.io.File

/** Locates the jpackage app-image payload and the bundled dependencies
  * (Team #11, beta.53).
  *
  * The jpackage launcher (the Windows msi AND the macOS dmg both build on
  * the app-image) runs the bundled JVM with `-Dapp.dir=<install>/app`; the
  * fat-jar installs made by release/install.ps1 / install.sh run on a
  * system JVM and have no such property.
  *
  * `msi-install.marker` is staged by packaging/build-msi.sh into the msi
  * payload, so its presence identifies the msi install form —
  * RemoteUpdateAction refuses the install.ps1 jar-drift path when it is
  * set (2026-08-28 hazard: in-app update on an msi install silently turned
  * it into a plain-jar layout).
  */
object InstallLayout:

  def isWindows: Boolean =
    sys.props.getOrElse("os.name", "").toLowerCase.contains("win")

  /** jpackage app payload dir (<install>/app), None on jar installs. */
  lazy val appDir: Option[String] =
    sys.props.get("app.dir").map(p => p.stripSuffix("/").stripSuffix("\\"))

  private def underAppDir(parts: String*): Option[String] =
    appDir.map(dir => (dir :: parts.toList).mkString(File.separator))

  /** Bundled MinGit bash (<install>\app\git\usr\bin\bash.exe) — msi payload
    * only, staged by packaging/build-msi.sh (which copies the bash engine
    * from usr/bin/sh.exe so it runs in full bash mode). Version pinned in
    * that script. */
  lazy val bundledBash: Option[String] =
    underAppDir("git", "usr", "bin", "bash.exe").filter(p => new File(p).isFile)

  /** Bundled ripgrep (<install>\app\rg.exe) — msi payload only. */
  lazy val bundledRg: Option[String] =
    underAppDir("rg.exe").filter(p => new File(p).isFile)

  /** True when this copy was installed by the Windows msi (marker file
    * staged into the app payload by packaging/build-msi.sh). */
  lazy val isMsiInstall: Boolean =
    isWindows && underAppDir("msi-install.marker").exists(p => new File(p).isFile)
