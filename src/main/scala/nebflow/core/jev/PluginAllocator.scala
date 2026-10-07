package nebflow.core.jev

import cats.effect.IO

import java.util.concurrent.atomic.AtomicReference

/**
 * PluginAllocator —— **插件分配面接线端口**（jev-plugins 批 #1226；M2 注册器形态 =
 * `DelegateJudge.DelegateJudgePort` 同款）.
 *
 * == 原文链条与三态（Batch 6 增补合并口径）==
 * Delegate(nebflow) spawn（及 S9 扩面后的 flow/链每步 spawn）时：
 * 启用插件 → CatalogEntry 目录 → JevGate? → `JevAllocator.allocate(task)`（need_
 * 问题对，现成）；未配 Jev → 新 `LlmAllocator`（单次 LLM JSON 选插件，同语义）；
 * 失败 → JevFallback fail-open——分配三态（Jev / LLM 兜底 / fail-open）齐备。
 *
 * 分层（依赖倒置纪律：core 零 `nebflow.llm` import——`JevAllocatorPort` 同款裁定）：
 * 本件只承载**分配结果类型 + 接线端口**；分配族的**执行体**（Jev 腿 / LlmAllocator
 * 单次 JSON 调用 / 兜底次序）落 `nebflow.llm.decision.PluginAllocatorMachine`，注册点
 * = `agent.SharedResources` 构造行（生产 boot 与测试装配共用）。
 *
 * == `via` 的语义 ==
 * `via` 只在 `allocation` 为 `Allocated` 时有值，记录**哪个臂**产出了该集合：
 * `Jev`（JevAllocator 的批量问句腿）或 `LlmFallback`（LLM 兜底腿——含 Jev 未配置
 * 与 Jev 调用失败两种进路，与 #1224 判定机「⑦ Jev 优先＋LLM 兜底」同族口径）。
 * `FellBack`（kind=Timeout/Failure）= 两臂全败的 **fail-open**（派发照走、空集注入）；
 * `FellBack(Off)` = 分配动作根本未发生（端口未注册等）——JevFallback 的既有
 * 「eventless」语义（正常态零噪音）。
 */
object PluginAllocator:

  /** 哪个臂产出了 Allocated 集合（FellBack 时恒 None——失败无臂可归功）。 */
  enum AllocationVia:
    case Jev
    case LlmFallback

    /** 审计/结构帧承载词（ChainStore `allocation` 字段值域的前两态）。 */
    def wire: String = this match
      case Jev         => "jev"
      case LlmFallback => "llm-fallback"

  /** 一次插件分配的完整结果：分配值 + 产出臂。 */
  final case class PluginAllocationResult(
      allocation: JevAllocation,
      via: Option[AllocationVia]
  )

  object PluginAllocationResult:
    val failOpen: PluginAllocationResult = PluginAllocationResult(
      JevAllocation.FellBack(JevFallbackKind.Failure, "plugin allocation failed — fail-open"),
      via = None
    )

  /** 接线面（执行体实现；单方法，带 subject 供审计事件定位对象）。 */
  trait Face:
    def allocatePlugins(
        subject: String,
        taskText: String,
        catalog: List[CatalogEntry]
    ): IO[PluginAllocationResult]
  end Face

  private val face = new AtomicReference[Option[Face]](None)

  def install(f: Face): Unit = face.set(Some(f))

  def clear(): Unit = face.set(None)

  def installed: Boolean = face.get.isDefined

  /**
   * 端口统一入口（core 调用点唯一消费面）。未注册 = `Off`（分配动作未发生，
   * JevFallback 既有 eventless 语义——调用方照走空集注入，绝不阻断派发）；注册面
   * **内部缺陷**（不该发生的逃逸异常）= `Failure` fail-open，与裁定 #3 姿态一致：
   * 分配面任何形态的失败都不允许 wedge 派发。
   */
  def allocatePlugins(
      subject: String,
      taskText: String,
      catalog: List[CatalogEntry]
  ): IO[PluginAllocationResult] =
    face.get match
      case None =>
        JevFallback
          .record(JevFallbackKind.Off, subject, "plugin allocation face not installed")
          .map(PluginAllocationResult(_, via = None))
      case Some(f) =>
        f.allocatePlugins(subject, taskText, catalog).handleErrorWith { e =>
          JevFallback
            .record(JevFallbackKind.Failure, subject, s"allocator defect: ${e.getClass.getSimpleName}")
            .map(PluginAllocationResult(_, via = None))
        }

end PluginAllocator
