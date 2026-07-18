package nebflow.core.tools

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import io.circe.parser.decode
import nebflow.core.{NebflowLogger, PathUtil}

object ToolLoader:
  private val logger = NebflowLogger.forName("nebflow.tools")

  private def toolsDir: os.Path = PathUtil.dataRoot / "tools"

  /** Tracks external tool names currently registered in ToolRegistry,
   * so we can cleanly unregister them before reloading from disk. */
  private val registeredNames = java.util.Collections.newSetFromMap(
    new java.util.concurrent.ConcurrentHashMap[String, java.lang.Boolean]()
  )

  /**
   * Reload all external tools from disk: unregister previously loaded tools,
   * re-read all JSON configs, and register fresh ScriptTool instances.
   * Idempotent — safe to call repeatedly (used by initial load + file watcher).
   */
  def reload(): IO[Unit] =
    for
      _ <- IO(registeredNames.forEach(name => ToolRegistry.unregisterTool(name)))
      _ <- IO(registeredNames.clear())
      scripts <- loadScripts()
      registered = scripts.filterNot { s =>
        val conflict = ToolRegistry.TOOL_MAP.contains(s.name)
        if conflict then logger.warn(s"External tool '${s.name}' conflicts with built-in — skipping")
        conflict
      }
      _ <- IO {
        registered.foreach { t =>
          ToolRegistry.registerTool(t)
          registeredNames.add(t.name)
        }
      }
      _ <- logger.info(
        if registered.nonEmpty then
          s"Loaded ${registered.size} external tool(s): ${registered.map(_.name).mkString(", ")}"
        else "No external tools loaded"
      )
    yield ()

  /**
   * Start a blocking file watcher that monitors `~/.nebflow/tools/` for `.json`
   * changes and hot-reloads external tool definitions with a 500ms debounce.
   * Intended to run as a background fiber.
   */
  def startFileWatcher(): IO[Unit] =
    IO.blocking {
      val dir = toolsDir.toIO.toPath
      if !java.nio.file.Files.exists(dir) then
        java.nio.file.Files.createDirectories(dir)
      val watcher = java.nio.file.FileSystems.getDefault.newWatchService()
      dir.register(
        watcher,
        java.nio.file.StandardWatchEventKinds.ENTRY_CREATE,
        java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY,
        java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
      )
      logger.info(s"Watching $dir for tool config changes")
      while true do
        val key = watcher.take()
        var hasJsonChange = false
        key.pollEvents().forEach { event =>
          if event.context().toString.endsWith(".json") then hasJsonChange = true
        }
        key.reset()
        if hasJsonChange then
          // Debounce: wait for file system to settle, then drain queued events
          Thread.sleep(500)
          var wk = watcher.poll()
          while wk != null do { wk.pollEvents(); wk.reset(); wk = watcher.poll() }
          try reload().unsafeRunSync()
          catch
            case e: Exception =>
              logger.warn(s"Tool reload failed: ${e.getMessage}").unsafeRunSync()
      end while
    }.void
      .handleErrorWith(e => logger.warn(s"Tool file watcher error: ${e.getMessage}").void)

  // Load all external tool definitions from ~/.nebflow/tools/*.json
  def loadAll(): IO[Map[String, ExternalToolConfig]] =
    IO.blocking {
      val dir = toolsDir
      if !os.exists(dir) then Nil
      else os.list(dir).filter(_.last.endsWith(".json")).toList
    }.flatMap { paths =>
      paths
        .traverse { p =>
          IO.blocking(decode[ExternalToolConfig](os.read(p))).flatMap {
            case Right(config) => IO.pure(Some(config))
            case Left(err) =>
              logger.warn(s"Skipping invalid tool config at ${p.last}: ${err.getMessage}").as(None)
          }
        }
        .map(_.flatten.iterator.map(c => c.name -> c).toMap)
    }

  // Load a single tool by name
  def load(name: String): IO[Option[ExternalToolConfig]] =
    val file = toolsDir / s"$name.json"
    IO.blocking(os.exists(file)).flatMap {
      case false => IO.pure(None)
      case true =>
        IO.blocking(os.read(file)).flatMap { content =>
          decode[ExternalToolConfig](content) match
            case Right(config) => IO.pure(Some(config))
            case Left(err) =>
              logger.warn(s"Failed to parse tool config '$name': ${err.getMessage}").as(None)
        }
    }

  // Load all and create ScriptTool instances ready for registration
  def loadScripts(): IO[List[ScriptTool]] =
    loadAll().map(_.values.map(config => ScriptTool(config)).toList)
end ToolLoader
