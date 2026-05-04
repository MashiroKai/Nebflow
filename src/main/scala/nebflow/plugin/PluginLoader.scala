package nebflow.plugin

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Decoder
import io.circe.yaml.parser
import nebflow.core.NebflowLogger
import nebflow.llm.McpServerConfig

/** Scans ~/.nebflow/plugins/ and loads plugin manifests. */
object PluginLoader:
  private val logger = NebflowLogger.forName("nebflow.plugin")
  val PluginDir: os.Path = nebflow.llm.Config.NebflowHome / "plugins"

  /** Scan plugin directory and parse all valid manifests. */
  def scan(): IO[List[PluginManifest]] = scan(PluginDir)

  /** Scan a specific directory for plugin manifests (used in tests). */
  def scan(dir: os.Path): IO[List[PluginManifest]] = IO.blocking {
    if !os.exists(dir) || !os.isDir(dir) then Nil
    else
      os.list(dir)
        .filter(os.isDir)
        .flatMap { d =>
          val yamlPath = d / "plugin.yaml"
          val jsonPath = d / "plugin.json"
          if os.exists(yamlPath) then parseManifestFile(yamlPath)
          else if os.exists(jsonPath) then parseManifestFile(jsonPath)
          else None
        }
        .toList
  }.handleError { e =>
    logger.warn(s"Failed to scan plugins: ${e.getMessage}")
    Nil
  }

  /** Extract MCP server configs from plugins that declare them.
   *  Returns (serverId -> McpServerConfig) pairs.
   */
  def extractMcpConfigs(manifests: List[PluginManifest]): Map[String, McpServerConfig] =
    manifests.flatMap { pm =>
      pm.mcp.map { cfg =>
        val serverId = s"plugin__${pm.name}"
        serverId -> PluginMcpConfig.toMcpServerConfig(cfg)
      }
    }.toMap

  /** Collect all frontend asset paths from manifests.
   *  Returns (pluginName -> FrontendConfig).
   */
  def extractFrontendConfigs(manifests: List[PluginManifest]): Map[String, FrontendConfig] =
    manifests.flatMap { pm =>
      pm.frontend.map(pm.name -> _)
    }.toMap

  private def parseManifestFile(path: os.Path): Option[PluginManifest] =
    try
      val raw = os.read(path)
      val parseResult = if path.last.endsWith(".yaml") || path.last.endsWith(".yml") then
        parser.parse(raw)
      else
        io.circe.parser.parse(raw)

      parseResult.flatMap(_.as[PluginManifest]) match
        case Right(pm) =>
          logger.info(s"Loaded plugin: ${pm.name} v${pm.version} from ${path.last}")
          Some(pm)
        case Left(err) =>
          logger.warn(s"Failed to parse plugin manifest ${path}: ${err.getMessage}")
          None
    catch
      case e: Exception =>
        logger.warn(s"Failed to read plugin manifest ${path}: ${e.getMessage}")
        None
