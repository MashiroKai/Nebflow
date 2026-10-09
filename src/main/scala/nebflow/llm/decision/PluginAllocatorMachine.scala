package nebflow.llm.decision

import cats.effect.IO
import cats.syntax.all.*
import nebflow.core.jev.{CatalogEntry, JevAllocation, JevConfigReader, JevFallbackKind, JevGateOutcome, PluginAllocator}
import nebflow.llm.ProviderRegistry
import nebflow.shared.NebflowLogger

/**
 * PluginAllocatorMachine —— **插件分配族编排体**（jev-plugins 批 #1226；⑦ 分配族 =
 * Jev 优先＋LLM 兜底，与 #1224 判定机同族、机制同一——原文既定，裁定背书）.
 *
 * 分层：core 只见 `PluginAllocator` 端口（core 零 llm import——JevAllocatorPort
 * 同款裁定）；本件落 llm.decision，注册点 = `agent.SharedResources` 构造行。
 *
 * == 编排（原文链条逐字）==
 * 启用插件（调用方总闸）→ CatalogEntry 目录（调用方组装）→ **JevGate?** →
 * `JevAllocator.allocate`（need_ 问题对，现成）；**未配 Jev → LlmAllocator**（单次
 * LLM JSON 选插件，同语义）；**失败 → JevFallback fail-open**。三态齐备：
 *   ① `via=Jev`（gate Applied 且 Jev 腿产出集合）；
 *   ② `via=LlmFallback`（gate Off / Jev 未装配 / Jev 腿回落——LLM 兜底腿产出集合；
 *      兜底进路与判定机同族：未配置**或失败**都兜）；
 *   ③ fail-open（兜底腿也败——`FellBack(Timeout|Failure)`，空集注入派发照走；
 *      JevFallback 已落 WARN＋审计事件，fallback 从不静默）。
 * `Off`（gate 关且无可用模型候选）= 分配动作未发生（JevFallback eventless 语义）。
 *
 * 探活：两腿各自的终态写 [[DecisionHealthMonitor]]（写腿 = 分配器，读面 =
 * `GET /api/decision/health` + Settings Jev 区探活显示）。
 *
 * 🔴 本件不产出模型可见文案（提示词全部在 JevAllocator 既有单源与
 * [[LlmAllocator]]，随件报审）。
 */
final class JevPluginAllocator(
    /** Jev 腿热读装配：None = 配置面解不出 provider（gate 之外的兜底进路）。 */
    jevLeg: IO[Option[JevAllocator]],
    /** LLM 兜底腿装配：None = 无可用模型候选（腿不可用）。 */
    llmLeg: IO[Option[LlmAllocator]],
    /** 门快照（每分配热读——PluginsConfig.enabled/JevConfigReader 同款纪律）。 */
    gate: IO[JevGateOutcome],
    health: Option[DecisionHealthPort]
) extends PluginAllocator.Face:

  private val logger = NebflowLogger.forName("nebflow.llm.plugin-alloc")

  override def allocatePlugins(
      subject: String,
      taskText: String,
      catalog: List[CatalogEntry]
  ): IO[PluginAllocator.PluginAllocationResult] =
    gate.flatMap {
      case JevGateOutcome.Applied(_) =>
        jevLeg.flatMap {
          case Some(jev) =>
            jev.allocateFor(subject, taskText, catalog).flatMap {
              case alloc @ JevAllocation.Allocated(_) =>
                recordHealth(true, None).as(
                  PluginAllocator.PluginAllocationResult(alloc, Some(PluginAllocator.AllocationVia.Jev))
                )
              case fell @ JevAllocation.FellBack(kind, reason) =>
                // Jev 腿运行了但回落 ⇒ LLM 兜底（⑦ 同族口径：未配置或失败都兜）。
                recordHealth(false, Some(s"jev leg: $reason")) *>
                  fallbackLeg(subject, taskText, catalog, Some(s"jev leg fell back (${kind.wire})"))
            }
          case None =>
            fallbackLeg(subject, taskText, catalog, Some("jev provider unavailable"))
        }
      case JevGateOutcome.Off(reason) =>
        // 未配 Jev（原文）——LLM 兜底腿直接承接。
        fallbackLeg(subject, taskText, catalog, Some(s"jev gate off (${reason.message})"))
    }
  end allocatePlugins

  /** LLM 兜底腿：产出集合 = `LlmFallback`；腿也败 = fail-open（JevFallback 已记账）。 */
  private def fallbackLeg(
      subject: String,
      taskText: String,
      catalog: List[CatalogEntry],
      viaNote: Option[String]
  ): IO[PluginAllocator.PluginAllocationResult] =
    viaNote.foreach(n => logger.infoSync(s"plugin allocation '$subject': llm fallback leg ($n)"))
    llmLeg.flatMap {
      case None =>
        recordHealth(false, Some("no llm candidate")).as(
          PluginAllocator.PluginAllocationResult(
            JevAllocation.FellBack(JevFallbackKind.Failure, "llm allocator: no model candidate available"),
            via = None
          )
        )
      case Some(llm) =>
        llm.allocateFor(subject, taskText, catalog).flatMap {
          case alloc @ JevAllocation.Allocated(_) =>
            recordHealth(true, None).as(
              PluginAllocator.PluginAllocationResult(alloc, Some(PluginAllocator.AllocationVia.LlmFallback))
            )
          case fell @ JevAllocation.FellBack(kind, reason) =>
            recordHealth(false, Some(s"llm leg: $reason")).as(
              PluginAllocator.PluginAllocationResult(fell, via = None)
            )
        }
    }

  private def recordHealth(up: Boolean, reason: Option[String]): IO[Unit] =
    health match
      case None => IO.unit
      case Some(h) =>
        if up then h.recordDecisionSuccess()
        else h.recordDecisionFailure(reason.getOrElse("plugin allocation failed"))

end JevPluginAllocator

object PluginAllocatorMachine:

  /** 结构帧/审计的兜底臂承载词（与 [[PluginAllocator.AllocationVia.LlmFallback]].wire 同值单源）。 */
  val LlmFallbackWire: String = "llm-fallback"

  /**
   * 生产装配（注册点 = `agent.SharedResources` 构造行）：Jev 腿 = JevSettings 热读
   * 构建（判定机 jevLeg 同款）；LLM 兜底腿 = 候选解析（判定机 utilityCandidate 同
   * 口径）；门 = JevConfigReader 热读；探活 = 分配决策通道监控单实例。
   */
  def face(registry: ProviderRegistry, health: Option[DecisionHealthPort] = None): PluginAllocator.Face =
    new JevPluginAllocator(
      jevLeg = jevLeg,
      llmLeg = LlmAllocator.fromRegistry(registry),
      gate = JevConfigReader.outcome,
      health = health
    )

  /** Jev 腿热读装配（未配置/解不出 provider = None → 兜底腿承接）。分配调用时限
    * 内置于 JevAllocator（JevSettings.timeoutMs——构建单点）。 */
  private def jevLeg: IO[Option[JevAllocator]] =
    IO.blocking(JevAllocator.fromConfig(health = None))

end PluginAllocatorMachine
