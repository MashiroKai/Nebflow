package nebflow.core

import cats.effect.IO

/**
 * 专用化改造·轨道二（引擎护栏）— team task #5（设计基线
 * docs/Nebflow/20260827_dedicated-agents-taxonomy-design.md §C）。
 *
 * 2026-08-27 用户裁定：flow 与 team 的 agent 应是专门定义，尽量不从全局
 * standalone 取用；被复用进流水线的节点在错位人设下会做出面向用户的表演性
 * 交付（deck-v6 实证：qa 节点向链路内 Pop 可视化 HTML 报告）。轨道二是引擎
 * 层护栏：
 *
 *   1. T1 flow worker 默认剥离面向用户的展示类工具（Pop / AskUserQuestion；
 *      "Card" 只是前端 UI 概念，无对应引擎工具；"Screenshot" 引擎亦不存在，
 *      无需保留动作——QA 取证走 Bash headless 截图落盘）。策略是「引擎剥离」
 *      而非「提示词求它别用」：declared 工具也扣掉。
 *   2. flow.json 节点级 `userFacing: true` 白名单可显式要回展示类工具
 *      （终审型节点确实两头都要喂），同时身份条款自动切到「受众=用户+下游」
 *      变体。
 *   3. T1/T2 身份条款由 harness 注入（PromptSections order 395 动态块，
 *      分类判定走 PromptContext 字段），机器消费受众、落盘交付、限字输出。
 *
 * 功能开关默认关（`dedicatedAgents.enabled`，缺 key / 解析失败一律 false）
 * ——轨道一（deck 家族专属定义落盘）验证后由配置打开。热读模式照抄
 * GlobalSafety.defaultMode：每次调用读盘，setConfig 后下个 turn 生效。
 */
object Guardrails:

  /**
   * T1 flow-node leaf workers 默认剥离的用户向工具集。即使 agent.json 显式
   * 声明也不生效（同 Issue/NebulaExclusiveTools 的机制层语义）。
   */
  val FlowWorkerStrippedTools: Set[String] = Set("Pop", "AskUserQuestion")

  /** Read `dedicatedAgents.enabled` from nebflow.json; false on any miss
    * (default OFF — 轨道一验证后才允许打开). Hot-read, no caching. */
  def enabled: IO[Boolean] =
    IO.blocking {
      val configPath = PathUtil.configJsonReadPath(PathUtil.dataRoot)
      if !os.exists(configPath) then None
      else
        io.circe.parser
          .parse(os.read(configPath))
          .toOption
          .flatMap(_.hcursor.downField("dedicatedAgents").downField("enabled").as[Boolean].toOption)
    }.handleErrorWith(_ => IO.pure(None))
      .map(_.getOrElse(false))

end Guardrails
