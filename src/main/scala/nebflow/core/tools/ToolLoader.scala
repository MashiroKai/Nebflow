package nebflow.core.tools

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import io.circe.parser.decode
import nebflow.core.{NebflowLogger, PathUtil}

object ToolLoader:
  private val logger = NebflowLogger.forName("nebflow.tools")

  private def toolsDir: os.Path = PathUtil.dataRoot / "tools"
  private def teamToolsDir(team: String): os.Path = PathUtil.dataRoot / "teams" / team / "tools"
  private def flowToolsDir(flow: String): os.Path = PathUtil.dataRoot / "flows" / flow / "tools"

  /** Layer priority for name conflicts: global < team < flow (higher wins). */
  private val layerPriority: Map[String, Int] = Map("global" -> 0, "team" -> 1, "flow" -> 2)

  /**
   * Tracks external tool names currently registered in ToolRegistry,
   * so we can cleanly unregister them before reloading from disk.
   */
  private val registeredNames = java.util.Collections.newSetFromMap(
    new java.util.concurrent.ConcurrentHashMap[String, java.lang.Boolean]()
  )

  /**
   * Reload all external tools from the three layers (global / team / flow):
   * unregister previously loaded tools, re-read all JSON configs, and register
   * fresh ScriptTool instances. On name conflicts a higher-priority layer
   * (flow > team > global) overrides a lower one; built-in tools always win.
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
   * Start a blocking file watcher that monitors the tools directories of all
   * three layers (global + every existing team/flow tools dir) for `.json`
   * changes and hot-reloads external tool definitions with a 500ms debounce.
   * Intended to run as a background fiber.
   */
  def startFileWatcher(): IO[Unit] =
    IO.blocking {
      val watchService = java.nio.file.FileSystems.getDefault.newWatchService()
      val dirs = globalToolsDir() ++ existingLayerToolsDirs()
      dirs.foreach { dir =>
        if !java.nio.file.Files.exists(dir.toIO.toPath) then
          java.nio.file.Files.createDirectories(dir.toIO.toPath)
        dir.toIO.toPath.register(
          watchService,
          java.nio.file.StandardWatchEventKinds.ENTRY_CREATE,
          java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY,
          java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
        )
      }
      logger.info(s"Watching ${dirs.size} tool config director${if dirs.size == 1 then "y" else "ies"}: ${dirs.map(_.toString).mkString(", ")}")
      while true do
        val key = watchService.take()
        var hasJsonChange = false
        key.pollEvents().forEach { event =>
          if event.context().toString.endsWith(".json") then hasJsonChange = true
        }
        key.reset()
        if hasJsonChange then
          // Debounce: wait for file system to settle, then drain queued events
          Thread.sleep(500)
          var wk = watchService.poll()
          while wk != null do
            wk.pollEvents(); wk.reset(); wk = watchService.poll()
          try reload().unsafeRunSync()
          catch
            case e: Exception =>
              logger.warn(s"Tool reload failed: ${e.getMessage}").unsafeRunSync()
      end while
    }.void
      .handleErrorWith(e => logger.warn(s"Tool file watcher error: ${e.getMessage}").void)

  // Global tools dir — created on demand so the watcher has a directory to register.
  private def globalToolsDir(): List[os.Path] =
    val dir = toolsDir
    if !os.exists(dir) then os.makeDir.all(dir)
    List(dir)

  // Team/flow tools dirs that already exist (new dirs are picked up on the next reload).
  private def existingLayerToolsDirs(): List[os.Path] =
    val teams = subdirs(PathUtil.dataRoot / "teams").map(teamToolsDir)
    val flows = subdirs(PathUtil.dataRoot / "flows").map(flowToolsDir)
    (teams ++ flows).filter(os.exists)

  private def subdirs(parent: os.Path): List[String] =
    if os.exists(parent) then os.list(parent).filter(os.isDir).map(_.last).toList else Nil

  /**
   * Load all external tool definitions from the three layers, merged by name
   * with layer priority (global < team < flow). Returns (config, sourceDir)
   * pairs; sourceDir becomes the ScriptTool's $TOOL_DIR.
   */
  def loadAll(): IO[List[(ExternalToolConfig, os.Path)]] =
    for
      global <- loadFromDir(toolsDir, layer = "global", scope = None)
      teams <- IO.blocking(subdirs(PathUtil.dataRoot / "teams"))
      teamLayer <- teams.traverse(name => loadFromDir(teamToolsDir(name), layer = "team", scope = Some(name)))
      flows <- IO.blocking(subdirs(PathUtil.dataRoot / "flows"))
      flowLayer <- flows.traverse(name => loadFromDir(flowToolsDir(name), layer = "flow", scope = Some(name)))
    yield mergeByPriority(global ++ teamLayer.flatten ++ flowLayer.flatten)

  // Load a single tool by name (searches all three layers, highest priority wins)
  def load(name: String): IO[Option[ExternalToolConfig]] =
    loadAll().map(_.collectFirst { case (config, _) if config.name == name => config })

  // Load all and create ScriptTool instances ready for registration
  def loadScripts(): IO[List[ScriptTool]] =
    loadAll().map(_.map { case (config, dir) => ScriptTool(config, dir) })

  /**
   * Read `*.json` config files from a single directory, tagging each with its
   * layer/scope. Invalid files are skipped with a warning (existing behavior).
   */
  private def loadFromDir(dir: os.Path, layer: String, scope: Option[String]): IO[List[(ExternalToolConfig, os.Path)]] =
    IO.blocking {
      if !os.exists(dir) then Nil
      else os.list(dir).filter(_.last.endsWith(".json")).toList
    }.flatMap { paths =>
      paths
        .traverse { p =>
          IO.blocking(decode[ExternalToolConfig](os.read(p))).flatMap {
            case Right(config) =>
              IO.pure(Some((config.withLayer(layer, scope), dir)))
            case Left(err) =>
              logger.warn(s"Skipping invalid tool config at $p: ${err.getMessage}").as(None)
          }
        }
        .map(_.flatten)
    }

  /**
   * Merge layer configs into a single name-keyed list. Sorted by ascending
   * layer priority so that on conflict the higher layer overwrites the lower
   * one; the override is logged.
   */
  private def mergeByPriority(configs: List[(ExternalToolConfig, os.Path)]): List[(ExternalToolConfig, os.Path)] =
    val sorted = configs.sortBy { case (cfg, _) => layerPriority.getOrElse(cfg.layer, 0) }
    val merged = scala.collection.mutable.LinkedHashMap[String, (ExternalToolConfig, os.Path)]()
    sorted.foreach { entry =>
      val cfg = entry._1
      merged.get(cfg.name) match
        case Some((prev, _)) =>
          logger.warn(
            s"Tool '${cfg.name}' from ${cfg.layer} layer (scope=${cfg.scope.getOrElse("-")}) " +
              s"overrides ${prev.layer} layer (scope=${prev.scope.getOrElse("-")})"
          )
          merged.update(cfg.name, entry)
        case None =>
          merged.update(cfg.name, entry)
    }
    merged.values.toList
end ToolLoader
