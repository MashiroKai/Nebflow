package nebflow.core.presets

import io.circe.syntax.*
import io.circe.Json
import nebflow.core.AtomicJson
import nebflow.shared.*

/**
 * ModelChainRegistry —— preset→model-chain 迁移批（2026-10-30，作者令 08:29「做」）
 * 的**命名链注册表**：`model-presets.json` 的 preset 定义由启动迁移腿
 * （[[nebflow.core.ModelChainMigration]]）迁为本文件承载的链形态
 * （`~/.stand/model-chains.json`），即「model registry 的链形态」——
 * `{preferred, fallbacks}` 的 [[AgentModelConfig]] 链按 preset 原名保留，
 * 旧 preset 名因此**天然兼容**（名字逐字随迁移带走，无别名表）。
 *
 * 文件形态（version 1）：
 * {{{
 * {"version":1, "migratedAt":"<iso-8601 迁移完成戳>",
 *  "defaultPreset":"general",
 *  "chains": { "<preset 原名>": {"name":..,"description":..,"preferred":..,"fallbacks":..} }}
 * }}}
 *
 * 读写纪律：
 *   - **write-ahead**（对齐 R5 先例 `c30e6945c`）：迁移先合并写本注册表、后封存
 *     原目录文件——进程死在两者之间时二次启动重放合并（**已有条目恒胜入参**，
 *     幂等），绝不丢链；
 *   - `migratedAt` 戳一旦落下不再改写（它是封存动作的对偶记录）；
 *   - 全部原子写（AtomicJson：UUID tmp + ATOMIC_MOVE）；
 *   - 读面 fail-soft：文件缺失/不可解析 ⇒ None（调用方退回 legacy 面），不抛。
 *
 * [[PresetStore]] 默认路径的读面在注册表存在时**切到本文件**（单解析路径原则：
 * SubTask preset 参数、工具文档目录、available-names 错误面全部同源，不存在
 * 「工具一套、设置一套」的分叉）。
 */
object ModelChainRegistry:

  val FileName = "model-chains.json"

  private val logger = NebflowLogger.forName("nebflow.modelchain")

  def chainsPath(root: os.Path = PathUtil.dataRoot): os.Path = root / FileName

  /**
   * 该 store 的规范读面是否已切到注册表——仅当 store 走默认路径
   * （`dataRoot/model-presets.json`）且注册表文件实存。测试注入的自定义
   * configPath（临时目录）永不命中，legacy 语义原样保留。
   */
  def activeFor(configPath: os.Path, root: os.Path = PathUtil.dataRoot): Option[os.Path] =
    if configPath == (root / "model-presets.json") && os.exists(chainsPath(root)) then Some(root)
    else None

  // ── 读面 ────────────────────────────────────────────────

  /**
   * 读注册表为 [[PresetFile]]（chains 映射进 presets 槽位，PresetStore 读面
   * 零改动切换）。缺失/不可解析/无 chains 对象 ⇒ None。
   */
  def load(root: os.Path = PathUtil.dataRoot): Option[PresetFile] =
    val path = chainsPath(root)
    if !os.exists(path) then None
    else
      io.circe.parser.parse(os.read(path)).toOption.flatMap { json =>
        val c = json.hcursor
        val chains = c
          .downField("chains")
          .focus
          .flatMap(_.asObject)
          .map(_.toList.flatMap { (name, pj) =>
            pj.as[ModelPreset].toOption.map(p => name -> p).orElse {
              logger.warnSync(s"chain registry: dropping unparseable entry '$name'")
              None
            }
          }.toMap)
        chains match
          case None => None
          case Some(m) =>
            val default = c.downField("defaultPreset").as[Option[String]].toOption.flatten.getOrElse("general")
            Some(PresetFile(defaultPreset = default, presets = m))
      }

  /** migratedAt 迁移完成戳（已迁移 home 的对偶记录；未迁移 ⇒ None）。 */
  def migratedAt(root: os.Path = PathUtil.dataRoot): Option[String] =
    val path = chainsPath(root)
    if !os.exists(path) then None
    else
      io.circe.parser
        .parse(os.read(path))
        .toOption
        .flatMap(_.hcursor.downField("migratedAt").as[Option[String]].toOption.flatten)

  // ── 写面 ────────────────────────────────────────────────

  /**
   * 全量写（PresetStore.save 的迁移面目标）：chains 槽位落 file.presets，
   * 保留既有 migratedAt 戳（已落下则不重写），version 恒 1。
   */
  def save(file: PresetFile, root: os.Path = PathUtil.dataRoot): Unit =
    val path = chainsPath(root)
    val stamp = migratedAt(root).getOrElse {
      java.time.OffsetDateTime.now().format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME)
    }
    AtomicJson.writeSync(path, render(file, stamp).noSpaces)

  /**
   * write-ahead 合并迁移（启动迁移腿专用）：入参条目并入既有注册表，
   * **既有条目恒胜**（幂等重放/用户后编辑不被陈旧目录覆盖）；首写落
   * migratedAt 戳；defaultPreset 取既有值、否则取入参。
   */
  def mergeChains(entries: Map[String, ModelPreset], defaultPreset: String, root: os.Path = PathUtil.dataRoot): Unit =
    val existing = load(root)
    // 注意 Map `++` 右侧优先：既有注册表必须放在右侧 ⇒ 既有条目恒胜入参
    // （幂等重放/用户后编辑不被陈旧目录数据覆盖）。
    val merged = existing match
      case Some(f) => entries ++ f.presets
      case None    => entries
    val effectiveDefault = existing.map(_.defaultPreset).getOrElse(defaultPreset)
    save(PresetFile(effectiveDefault, merged), root)
    logger.infoSync(
      s"model-chain migration: preset catalog -> chain registry: ${entries.size} chain(s) " +
        s"(${entries.keys.toList.sorted.mkString(", ")}); registry now holds ${merged.size}"
    )

  private def render(file: PresetFile, stamp: String): Json =
    Json.fromFields(
      List(
        "version"       -> 1.asJson,
        "migratedAt"    -> stamp.asJson,
        "defaultPreset" -> file.defaultPreset.asJson,
        "chains" -> Json.fromFields(
          file.presets.toList.sortBy(_._1).map { (name, p) => name -> p.asJson }
        )
      )
    )

end ModelChainRegistry
