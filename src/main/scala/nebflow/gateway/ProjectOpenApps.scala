package nebflow.gateway

import java.nio.file.{Files, Path, Paths}
import scala.util.Try

/** Host-local application discovery and shell-free project opening. */
private[gateway] object ProjectOpenApps:
  final case class App(id: String, label: String, command: List[String])

  private def executable(path: String): Boolean = Files.isExecutable(Paths.get(path))
  private def exists(path: String): Boolean = Files.exists(Paths.get(path))
  private def onPath(name: String): Option[String] =
    sys.env.getOrElse("PATH", "").split(java.io.File.pathSeparator).iterator
      .filter(_.nonEmpty).map(p => Paths.get(p, name).toString).find(executable)

  def detect(): List[App] =
    val os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT)
    if os.contains("mac") && executable("/usr/bin/open") then
      val open = "/usr/bin/open"
      List(
        Option.when(exists("/System/Library/CoreServices/Finder.app"))(App("finder", "Finder", List(open))),
        Option.when(exists("/Applications/Visual Studio Code.app"))(App("vscode", "VS Code", List(open, "-a", "Visual Studio Code"))),
        Option.when(exists("/System/Applications/Utilities/Terminal.app"))(App("terminal", "Terminal", List(open, "-a", "Terminal"))),
        Option.when(exists("/Applications/iTerm.app"))(App("iterm", "iTerm", List(open, "-a", "iTerm")))
      ).flatten
    else if os.contains("linux") then
      onPath("xdg-open").toList.map(p => App("file-manager", "File manager", List(p)))
    else if os.contains("windows") then
      onPath("explorer.exe").toList.map(p => App("file-manager", "File manager", List(p)))
    else Nil

  /** Both paths must independently resolve to the same existing directory. */
  def registeredPath(requested: String, registered: String): Either[String, Path] =
    Try {
      val candidate = Paths.get(requested)
      val workspace = Paths.get(registered)
      require(candidate.isAbsolute && workspace.isAbsolute, "Workspace must be absolute")
      val real = candidate.toRealPath()
      require(real == workspace.toRealPath() && Files.isDirectory(real), "Path is not the registered project workspace")
      real
    }.toEither.left.map(_ => "Path is not the registered project workspace")

  def launch(app: App, path: Path): Int =
    new ProcessBuilder((app.command :+ path.toString)*).start().waitFor()
