package nebflow.llm.decision

import cats.effect.IO
import cats.syntax.all.*
import nebflow.core.jev.{CatalogEntry, JevAllocation, JevFallbackKind, PluginAllocator}
import nebflow.llm.ProviderRegistry
import nebflow.shared.NebflowLogger

/**
 * PluginAllocatorMachine —— **插件分配族编排体**（jev-retire 批：JeV 腿全量退役，
 * 分配 = 小模型单腿）.
 *
 * 分层：core 只见 `PluginAllocator` 端口（core 零 llm import）；本件落
 * llm.decision，注册点 = `agent.SharedResources` 构造行。
 *
 * == 编排（jev-retire 后）==
 * 启用插件（调用方总闸）→ CatalogEntry 目录（调用方组装）→
 * [[LlmAllocator]]（`SchemePolicy.smallLlmModel()` 候选链，未配置回退主模型）。
 * 产出两态：`via=LlmFallback`（集合产出，可能为空集）／fail-open
 * （`FellBack(...)`，空集注入派发照走；JevFallback 已落 WARN＋审计事件，
 * fallback 从不静默）。
 *
 * 探活：终态写 [[DecisionHealthMonitor]]（写腿 = 分配器，读面 =
 * `GET /api/decision/health`）。
 *
 * 🔴 每次分配恰好一行 `allocation decision` 日志（info=成功/空目录，
 * warn=失败——jev-retire 批四路断言锁死）。
 *
 * 🔴 本件不产出模型可见文案（提示词单源在 [[LlmAllocator]]，随件报审）。
 */
object PluginAllocatorMachine:

  /** 结构帧/审计的兜底臂承载词（与 [[PluginAllocator.AllocationVia.LlmFallback]].wire 同值单源）。 */
  val LlmFallbackWire: String = "llm-fallback"

  /**
   * 生产装配（注册点 = `agent.SharedResources` 构造行）：小模型腿 =
   * `LlmAllocator.fromRegistry` 候选解析（`SchemePolicy.smallLlmModel()`，
   * 未配置回退主模型+WARN——SchemePolicy 单点）；探活 = 分配决策通道监控单实例。
   *
   * 候选解析自身异常（配置读失败等逃逸错）= `Failure` fail-open（handleError
   * 兜底，分配面任何形态的失败都不允许 wedge 派发）。
   */
  def face(registry: ProviderRegistry, health: Option[DecisionHealthPort] = None): PluginAllocator.Face =
    new PluginAllocator.Face:
      def allocatePlugins(subject: String, taskText: String, catalog: List[CatalogEntry]): IO[PluginAllocator.PluginAllocationResult] =
        LlmAllocator.fromRegistry(registry).flatMap {
          case Some(allocator) =>
            allocator.allocateFor(subject, taskText, catalog).map {
              case a @ JevAllocation.Allocated(_) =>
                PluginAllocator.PluginAllocationResult(a, Some(PluginAllocator.AllocationVia.LlmFallback))
              case a => PluginAllocator.PluginAllocationResult(a, None)
            }
          case None =>
            IO.pure(PluginAllocator.PluginAllocationResult(
              JevAllocation.FellBack(JevFallbackKind.Failure, "small model unavailable"), None
            ))
        }.handleError { error =>
          PluginAllocator.PluginAllocationResult(
            JevAllocation.FellBack(JevFallbackKind.Failure, s"small model allocation failed: ${error.getClass.getSimpleName}"),
            None
          )
        }.flatTap { result =>
          val audit = result.allocation match
            case JevAllocation.Allocated(names) if catalog.isEmpty =>
              IO(NebflowLogger.forName("nebflow.llm.plugin-alloc").infoSync(s"allocation decision subject=${subject.replaceAll("[\\r\\n]+", " ")} plugins=${names.mkString(",")} reason=empty catalog"))
            case JevAllocation.FellBack(_, reason) =>
              IO(NebflowLogger.forName("nebflow.llm.plugin-alloc").warnSync(s"allocation decision subject=${subject.replaceAll("[\\r\\n]+", " ")} plugins= reason=${reason.replaceAll("[\\r\\n]+", " ")}"))
            case _ => IO.unit
          val state = result.allocation match
            case JevAllocation.Allocated(_) => DecisionHealth.Up
            case JevAllocation.FellBack(_, reason) => DecisionHealth.Down(reason, System.currentTimeMillis())
          audit *> health.fold(IO.unit)(_.recordAllocationOutcome(Map("llm" -> state), state))
        }

end PluginAllocatorMachine
