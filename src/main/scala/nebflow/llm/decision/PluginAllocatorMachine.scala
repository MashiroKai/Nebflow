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

  /** 分腿记账键（三改③，2026-10-09）：写腿与显示面（REST `legs` 块 + 设置页
    * 徽标）共用同一拼写。 */
  private val JevLegKey = "jev"
  private val LlmLegKey = "llm"

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
                recordHealth(
                  legs = Map(JevLegKey -> DecisionHealth.Up),
                  effective = DecisionHealth.Up
                ).as(
                  PluginAllocator.PluginAllocationResult(alloc, Some(PluginAllocator.AllocationVia.Jev))
                )
              case fell @ JevAllocation.FellBack(kind, reason) =>
                // 三改③回退观测点（2026-10-09 用户反馈②③）：Jev 腿不可用
                // （超时/网络/鉴权——`DecisionError` 分类件）⇒ WARN 行 + 分腿
                // 记账，然后 LLM 兜底腿**自动接管，分配不丢**（⑦ 同族口径：
                // 未配置或失败都兜）。
                IO(logger.warnSync(
                  s"plugin allocation '$subject': jev leg unavailable ($reason) — falling back to the llm leg (allocation preserved)"
                )) *>
                  fallbackLeg(
                    subject, taskText, catalog,
                    viaNote = Some(s"jev leg fell back (${kind.wire})"),
                    jevState = Some(DecisionHealth.Down(s"jev leg: $reason", System.currentTimeMillis()))
                  )
            }
          case None =>
            fallbackLeg(subject, taskText, catalog, Some("jev provider unavailable"), jevState = None)
        }
      case JevGateOutcome.Off(reason) =>
        // 未配 Jev（原文）——LLM 兜底腿直接承接；jev 腿没跑 = 不造 jev 腿键。
        fallbackLeg(subject, taskText, catalog, Some(s"jev gate off (${reason.message})"), jevState = None)
    }
  end allocatePlugins

  /** LLM 兜底腿：产出集合 = `LlmFallback`；腿也败 = fail-open（JevFallback 已记账）。
    *
    * `jevState` = 本次调用 jev 腿的终态（None = 腿没跑，不造键——显示面据此
    * 区分「没跑」与「跑了挂了」）。通道整体终态语义与三改前一致：兜底腿的
    * 产出/失败就是通道终态。 */
  private def fallbackLeg(
    subject: String,
    taskText: String,
    catalog: List[CatalogEntry],
    viaNote: Option[String],
    jevState: Option[DecisionHealth]
  ): IO[PluginAllocator.PluginAllocationResult] =
    viaNote.foreach(n => logger.infoSync(s"plugin allocation '$subject': llm fallback leg ($n)"))
    llmLeg.flatMap {
      case None =>
        val llmState = DecisionHealth.Down("no llm candidate", System.currentTimeMillis())
        recordHealth(
          legs = jevState.map(s => Map(JevLegKey -> s)).getOrElse(Map.empty) + (LlmLegKey -> llmState),
          effective = llmState
        ).as(
          PluginAllocator.PluginAllocationResult(
            JevAllocation.FellBack(JevFallbackKind.Failure, "llm allocator: no model candidate available"),
            via = None
          )
        )
      case Some(llm) =>
        llm.allocateFor(subject, taskText, catalog).flatMap {
          case alloc @ JevAllocation.Allocated(_) =>
            recordHealth(
              legs = jevState.map(s => Map(JevLegKey -> s)).getOrElse(Map.empty) + (LlmLegKey -> DecisionHealth.Up),
              effective = DecisionHealth.Up
            ).as(
              PluginAllocator.PluginAllocationResult(alloc, Some(PluginAllocator.AllocationVia.LlmFallback))
            )
          case fell @ JevAllocation.FellBack(kind, reason) =>
            val llmState = DecisionHealth.Down(s"llm leg: $reason", System.currentTimeMillis())
            recordHealth(
              legs = jevState.map(s => Map(JevLegKey -> s)).getOrElse(Map.empty) + (LlmLegKey -> llmState),
              effective = llmState
            ).as(
              PluginAllocator.PluginAllocationResult(fell, via = None)
            )
        }
    }

  end fallbackLeg

  /** 三改③写腿：分腿终态 + 通道整体终态一并交探活通道（单写者 = 本编排体）。 */
  private def recordHealth(legs: Map[String, DecisionHealth], effective: DecisionHealth): IO[Unit] =
    health match
      case None => IO.unit
      case Some(h) => h.recordAllocationOutcome(legs, effective)

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

  /**
   * Jev 腿热读装配（未配置/解不出 provider = None → 兜底腿承接）。分配调用时限
   * 内置于 JevAllocator（JevSettings.timeoutMs——构建单点）。
   */
  private def jevLeg: IO[Option[JevAllocator]] =
    IO.blocking(JevAllocator.fromConfig(health = None))

end PluginAllocatorMachine
