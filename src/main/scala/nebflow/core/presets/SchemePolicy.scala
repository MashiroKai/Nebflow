package nebflow.core.presets

import io.circe.parser.decode
import nebflow.actor.RootAgentIdentity
import nebflow.shared.{AgentModelConfig, PathUtil}

/**
 * 面板模型方案收敛策略（panelscheme 批 2026-09-21，作者令）：
 * 插件面板只需要**两种 agent 可设置模型方案** —— **Nebula** 与**任务分发器**
 * （project-dispatcher）。本对象是该令的**解析侧单点来源**：所有 agent 定义装载
 * 路径（AgentLibrary.loadFromDir / AgentEntry.toAgentDef）经 [[effectiveRefs]]
 * 得到有效方案引用后，再走 PresetStore.resolve 既有三级解析（explicit preset >
 * legacy model > default preset，语义不变）。
 *
 * 名称 → 有效方案矩阵：
 *
 *   - **Nebula / project-dispatcher**（可设两类）：自有 agent.json 引用原样生效
 *     ——行为不变（回归红线：两类的设置/读取路径零改动）。
 *   - **执行 agent**（`nebflow`；合并后取代 kernel + general，见
 *     [[nebflow.core.entity.BuiltinAgents.ExecutorName]]）：动态继承
 *     **project-dispatcher 当前方案**——执行会话无自有设置（节点 worker/verify 与
 *     Delegate 目标都落这一个名），分发器所设方案 = 唯一路径（节点侧静态覆盖已
 *     废止：NodeEdit preset 参数退役、NodeEngine §E.3 节点存储方案消费移除）。
 *     退役名（`kernel` / `general`，旧数据 sidecar）经 [[resolveRole]] 归一到本行，
 *     语义与合并前一致（`kernel` 原跟随 Nebula 的行随合并并入执行 agent 行——这是
 *     本批唯一的行为收敛，已在交付报告中显式登记）。
 *   - **其余 agent**（自定义 standalone / team/flow agent 等非在册名）：引擎**忽略**
 *     其存储的 preset/model（数据留盘留审计，零删除），一律回落默认 preset
 *     （resolve level 3 terminal）。
 *
 * 继承是**引用级**透传：把根 agent 的原始 (preset, model) 引用作为有效输入交给
 * 既有 resolve——继承体与根共享同一套三级解析语义，AgentDef.preset 也写根的
 * preset 引用名（面板药丸/GET /agents/:name/model 显示的方案名 = 实际生效者）。
 */
object SchemePolicy:

  val RootName = RootAgentIdentity.Name
  val DispatcherName = "project-dispatcher"

  /** The executor agent name (single point = [[nebflow.core.entity.BuiltinAgents.ExecutorName]]).
    *
    * P1-2 downstream-role-face convergence batch (2026-10-03): the builtin-merge
    * batch (`f646eeeaa`) merged `kernel` + `general` into this one execution agent,
    * so the inheritance table below is written in terms of the CURRENT name; the
    * retired spellings stay resolvable through the rename table
    * ([[resolveRole]]), which is what keeps a stored old-data sidecar behaving as
    * it did before the merge. */
  val ExecutorName: String = nebflow.core.entity.BuiltinAgents.ExecutorName

  /** 面板可设模型方案的 agent 全集（作者令 2026-09-21：仅这两类）。 */
  val SettableAgents: Set[String] = Set(RootName, DispatcherName)

  /** 动态继承表：agent → 它跟随其**当前**方案的根 agent。
    *
    * 执行 agent（合并后取代 kernel + general）跟随**分发器**当前方案——节点执行
    * 会话的模型链口径（原 `general` 行）在合并后仍是本表的承重面：节点无自有
    * 设置，分发器派发时所选方案 = 节点的唯一路径。 */
  val InheritsFrom: Map[String, String] = Map(
    ExecutorName -> DispatcherName
  )

  /** 角色名归一（**读侧解析兜底的单点**）：退役名（`kernel` / `general`，见
    * [[nebflow.core.entity.BuiltinAgents.RetiredNames]]）落到合并后的执行 agent 名；
    * 其余名字原样返回。写侧一律直接写现名，读侧经本函数据此判继承/可设。 */
  def resolveRole(name: String): String =
    nebflow.core.entity.BuiltinAgents.resolveRetired(name).getOrElse(name)

  /**
   * 读根 agent 的 agent.json 原始 (preset, model) 引用（全局 agents 目录单点）。
   * 缺文件 / 解析失败 → (None, None)：与根缺失时 Nebula 代码回退同款宽容路径
   * （回落默认 preset），绝不因继承根缺失而炸装载。每次调用现读 ⇒ 动态跟随。
   */
  def rootRefs(root: String): (Option[String], Option[AgentModelConfig]) =
    val jsonPath = PathUtil.dataRoot / "agents" / root / "agent.json"
    if !os.exists(jsonPath) then (None, None)
    else
      io.circe.parser.parse(os.read(jsonPath)).toOption match
        case None => (None, None)
        case Some(json) =>
          val preset = json.hcursor.downField("preset").as[Option[String]].toOption.flatten
          val model =
            json.hcursor.downField("model").focus.flatMap(_.as[AgentModelConfig].toOption)
          (preset, model)

  /**
   * 名称感知的有效 (preset 引用, legacy model) 解析输入（见类 doc 矩阵）。
   *
   * 返回值直接喂 PresetStore.resolve(presetName, legacy)；AgentDef.preset 取本
   * 返回的首元素（继承体显示根的方案名，可设两类显示自有名，其余 None）。
   *
   * P1-2 批（2026-10-03）：名字先经 [[resolveRole]] 归一——旧数据 sidecar 的
   * `kernel` / `general` 仍按合并后的执行 agent 行解析，**不是**落到「忽略存储
   * 引用」的末支（否则旧 home 的执行会话会静默改链，属行为回归）。
   */
  def effectiveRefs(
    name: String,
    ownPreset: Option[String],
    ownModel: Option[AgentModelConfig]
  ): (Option[String], Option[AgentModelConfig]) =
    val role = resolveRole(name)
    InheritsFrom.get(role) match
      case Some(root) => rootRefs(root)
      case None =>
        if SettableAgents.contains(role) then (ownPreset, ownModel)
        else (None, None) // 引擎忽略存储方案（留盘）；回落默认 preset

end SchemePolicy
