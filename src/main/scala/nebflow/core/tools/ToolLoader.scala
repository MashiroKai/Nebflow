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

  /** Layer priority for name conflicts: global < agent < team < flow (higher wins). */
  private val layerPriority: Map[String, Int] = Map("global" -> 0, "agent" -> 1, "team" -> 2, "flow" -> 3)

  /**
   * Tracks external tool names currently registered in ToolRegistry,
   * so we can cleanly unregister them before reloading from disk.
   */
  private val registeredNames = java.util.Collections.newSetFromMap(
    new java.util.concurrent.ConcurrentHashMap[String, java.lang.Boolean]()
  )

  /**
   * Reload all external tools from the four layers (global / agent / team /
   * flow): unregister previously loaded tools, re-read all JSON configs, and
   * register fresh ScriptTool instances. On name conflicts a higher-priority
   * layer (flow > team > agent > global) overrides a lower one; built-in
   * tools always win.
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
   * Load all external tool definitions from the four layers, merged by name
   * with layer priority (global < agent < team < flow). Returns (config,
   * sourceDir) pairs; sourceDir becomes the ScriptTool's $TOOL_DIR.
   */
  def loadAll(): IO[List[(ExternalToolConfig, os.Path)]] =
    for
      global <- loadFromDir(toolsDir, layer = "global", scope = None)
      // Agent-scoped tools: ~/.nebflow/agents/*/tools/
      globalAgentTools <- loadNestedTools(PathUtil.dataRoot / "agents", layer = "agent")
      teams <- IO.blocking(subdirs(PathUtil.dataRoot / "teams"))
      teamLayer <- teams.traverse(name => loadFromDir(teamToolsDir(name), layer = "team", scope = Some(name)))
      // Team agent tools: ~/.nebflow/teams/<t>/agents/*/tools/ (skip <t>/tools — loaded above)
      teamAgentTools <- loadNestedTools(PathUtil.dataRoot / "teams", layer = "agent", excludeDirect = true)
      flows <- IO.blocking(subdirs(PathUtil.dataRoot / "flows"))
      flowLayer <- flows.traverse(name => loadFromDir(flowToolsDir(name), layer = "flow", scope = Some(name)))
      // Flow agent tools: ~/.nebflow/flows/<f>/agents/*/tools/ (skip <f>/tools — loaded above)
      flowAgentTools <- loadNestedTools(PathUtil.dataRoot / "flows", layer = "agent", excludeDirect = true)
    yield mergeByPriority(
      global ++ globalAgentTools ++ teamLayer.flatten ++ teamAgentTools ++ flowLayer.flatten ++ flowAgentTools
    )

  /**
   * Scan baseDir for `tools/` subdirectories of agent directories and load
   * every *.json tool config found. Two shapes are matched:
   *   - direct:  baseDir/<agent>/tools/                 (used for ~/.nebflow/agents)
   *   - nested:  baseDir/<scope>/agents/<agent>/tools/  (used for teams & flows)
   * With `excludeDirect` the immediate baseDir/<x>/tools dirs are skipped — they
   * are the team/flow scope tools dirs already loaded separately by loadAll().
   * Configs are tagged layer "agent"; scope is the path relative to the data root.
   */
  private def loadNestedTools(baseDir: os.Path, layer: String, excludeDirect: Boolean = false): IO[List[(ExternalToolConfig, os.Path)]] =
    IO.blocking {
      if !os.exists(baseDir) then Nil
      else
        os.walk(baseDir)
          .filter { p =>
            os.isDir(p) && p.last == "tools" &&
            !(excludeDirect && p.segments.length == baseDir.segments.length + 2)
          }
          .toList
    }.flatMap { toolsDirs =>
      toolsDirs.traverse { toolsDir =>
        val rel = toolsDir.relativeTo(PathUtil.dataRoot).segments.mkString("/")
        loadFromDir(toolsDir, layer = layer, scope = Some(rel))
      }
    }.map(_.flatten)

  // Load a single tool by name (searches all three layers, highest priority wins)
  def load(name: String): IO[Option[ExternalToolConfig]] =
    loadAll().map(_.collectFirst { case (config, _) if config.name == name => config })

  // Load all and create ScriptTool instances ready for registration
  def loadScripts(): IO[List[ScriptTool]] =
    loadAll().map(_.map { case (config, dir) => ScriptTool(config, dir) })

  /**
   * Read tool configs from a single directory. Supports two layouts:
   *
   *   1. **Subdirectory** (preferred): `dir/<name>/tool.json` — `$TOOL_DIR`
   *      resolves to the subdirectory (so sibling scripts are next to the
   *      config).
   *   2. **Flat** (legacy): `dir/` with `*.json` files — `$TOOL_DIR` resolves to `dir`
   *      itself.
   *
   * Both layouts can coexist in the same directory. Invalid files are skipped
   * with a warning.
   */
  private def loadFromDir(dir: os.Path, layer: String, scope: Option[String]): IO[List[(ExternalToolConfig, os.Path)]] =
    IO.blocking {
      if !os.exists(dir) then Nil
      else
        // Subdirectory layout: dir/<name>/tool.json
        val subDirConfigs = os.list(dir).filter(os.isDir).flatMap { subDir =>
          val jsonFile = subDir / "tool.json"
          if os.exists(jsonFile) then List((jsonFile, subDir)) else Nil
        }
        // Flat layout (legacy): dir/*.json
        val flatConfigs = os.list(dir)
          .filter(f => f.last.endsWith(".json") && os.isFile(f))
          .map(f => (f, dir))
        subDirConfigs.toList ++ flatConfigs.toList
    }.flatMap { paths =>
      paths
        .traverse { case (p, sourceDir) =>
          IO.blocking(decode[ExternalToolConfig](os.read(p))).flatMap {
            case Right(config) =>
              // Resolve $TOOL_DIR at load time to the directory holding this
              // config file, so commands can reference sibling resources, e.g.
              //   "command": "node $TOOL_DIR/deploy.cjs"
              val resolved = config.withLayer(layer, scope)
                .copy(command = config.command.replace("$TOOL_DIR", sourceDir.toString))
              IO.pure(Some((resolved, sourceDir)))
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
