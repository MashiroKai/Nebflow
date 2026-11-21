package nebflow.llm.kernel

import cats.effect.IO
import cats.syntax.all.*
import nebflow.core.LlmUsageRecord
import nebflow.llm.*
import nebflow.shared.*

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.*

/**
 * **统一 LLM 内核**（kernel-spec 批 2026-11-19；依据档
 * `cache-hit-spec-coverage-and-unified-llm-kernel__20261119_111124.md` B1–B4 / K1–K7）。
 *
 * == 为什么 ==
 * 批前实测：spec 的 C1–C4 只断言「`AgentSessionExecution` 装配的持久历史」这一条腿；
 * `LlmAllocator`（插件分配）、`DelegateJudgeMachine`（决策卡判官）、`HealthMonitor`
 * （健康探针）三条辅腿**直连适配器**，各自拼提示词、各自写候选链遍历、**零计量零归因**
 * ——同一常量前缀每次重发（实测 `cache_creation=897 / cache_read=0`，两发皆然），
 * 且面板/`/api/usage/aggregate`/成本当量**看不见辅腿**（V1/V2）。
 *
 * == 本内核提供的四件事（逐条对 K 契约）==
 *   1. **装配单点**（[[assemble]]）——全仓**唯一**构造 `SendMessageParams` 的生产点；
 *      稳定 system / 请求级临时段 / 工具面 / 断点策略 / 钳制位全部在一处落位（K2/K3/K4）；
 *   2. **候选解析单点**（[[candidates]]）——[[CandidateSource]] 枚举显式声明来源，
 *      禁止隐式回退全局链（K7）；
 *   3. **整链遍历单点**（[[chainCall]] / [[run]]）——候选链遍历 + 单调用预算 + 超时后
 *      同候选重试一次，模板只有一个（K5）；
 *   4. **计量单点**（[[meter]] / [[stream]]）——`CacheStats.record` +
 *      `CacheAttribution.observeUsage` + `LlmUsageRecord` 三账一入口；**计量与过闸解耦**
 *      （[[Gating.Ungated]] 仍过计量，探针恢复路径不受 429 闸拖累）（K6）。
 *
 * == 零行为变化纪律 ==
 * [[assemble]] 是**纯字段映射**：`CacheBreakpoints.Conversation`（缺省）下主腿 wire
 * 与本批前逐字节相同；辅腿的前缀提位（B2）是唯一的 wire 变化，且由 `CachePrefixAssemblySpec`
 * 的新判据钉住。
 */
object LlmKernel:

  // ── 声明类型（编译期闸：无缺省值 ⇒ 少声明一条编译不过）────────────────────

  /**
   * system 段声明（K2）。**必需字段、无缺省**：每条腿都得说清楚自己的稳定前缀是
   * 什么，或者说清楚为什么没有（理由串必须来自 [[KernelCallSites]] 白名单）。
   */
  enum SystemPrefix:
    case Stable(text: String)
    case Absent(reason: String)

    /** 上线到 `SendMessageParams.systemStable` 的文本（[[Absent]] ⇒ None）。 */
    def wireText: Option[String] = this match
      case Stable(t) => Some(t)
      case Absent(_) => None

  /**
   * 模型候选来源（K7）。**必需字段、无缺省**：禁止「没写就回落全局链」的隐式行为——
   * 审计 §6.1 逐条点名的正是「模型与参数来源各自拼」。
   */
  enum CandidateSource:
    case SmallLlmModel
    case DecisionRoute(name: String)
    case NebulaChain
    case Slot(agentModel: Option[AgentModelConfig])

    /** 调用方已解析好的候选（健康探针逐候选——它手里只有一个候选，不是一条链）。 */
    case Explicit(candidates: List[ModelCandidate])

  /** 过闸面（K6「计量与过闸解耦」）。 */
  enum Gating:
    case Throttled
    case Ungated

  /** 计量归因域。 */
  enum MeterKind:
    case Business
    case Probe

  /** 一次辅助腿调用的返回（回文 + 计费面 + 真实生效域）。 */
  final case class KernelReply(reply: String, usage: Option[TokenUsage], providerId: String, model: String)

  /**
   * 内核调用描述（设计稿 §6.3 `KernelCall` 的落码形态）。
   *
   * `site` / `systemPrefix` / `messages` / `source` **无缺省值**——四条声明缺失即编译不过。
   */
  final case class KernelCall(
    site: KernelCallSites.Site,
    systemPrefix: SystemPrefix,
    messages: List[Message],
    source: CandidateSource,
    requestOnlyContext: List[Message] = Nil,
    systemDynamic: Option[String] = None,
    tools: Option[List[ToolDefinition]] = None,
    thinking: Option[io.circe.Json] = None,
    thinkingResolved: Option[ResolvedThinking] = None,
    providerSearch: Option[ProviderSearchKind] = None,
    contextWindow: Option[Int] = None,
    maxTokensOverride: Option[Int] = None,
    sessionId: Option[String] = None,
    agentId: Option[String] = None,
    gating: Gating = Gating.Throttled,
    meterKind: MeterKind = MeterKind.Business,
    cacheBreakpoints: CacheBreakpoints = CacheBreakpoints.Conversation,
    /** K5：单调用预算（None = 零超时，与作者「不做超时控制」的既有裁定一致）。 */
    timeout: Option[FiniteDuration] = None,
    /** K5：超时/传输类失败先重试**同一候选**一次（缺陷① 2026-11-02 的语义）。 */
    retrySameCandidateOnTransportError: Boolean = false,
    attemptBackend: Option[sttp.client4.StreamBackend[IO, sttp.capabilities.fs2.Fs2Streams[IO]]] = None
  ):
    def purpose: String = site.id
    def logTag: String = site.logTag
  end KernelCall

  // ── K6：usage 落账点（进程内单点；生产由 GatewayMain 装 store，测试可装 recording sink）──

  /**
   * `LlmUsageRecord` 落账口。生产在 boot 时装 `SharedResources.usageRecordStore`；
   * 未装（测试夹具 / 降级装配）= 只落 `CacheStats` + `CacheAttribution` 两账，
   * 行为与「本对象不存在」时一致（零回归兜底，同 `LlmRuntimePort` 先例）。
   */
  object UsageSink:
    private val ref = new AtomicReference[Option[LlmUsageRecord => IO[Unit]]](None)

    def install(sink: LlmUsageRecord => IO[Unit]): Unit =
      ref.set(Some(sink)); ()

    def clear(): Unit =
      ref.set(None); ()

    def emit(record: LlmUsageRecord): IO[Unit] =
      ref.get() match
        case None => IO.unit
        case Some(sink) => sink(record).handleErrorWith(_ => IO.unit)

  // ── K2 前缀门（主腿用：把「本请求没有稳定前缀」变成登记过的白名单理由）──────

  /** 主腿前缀门：有稳定串 ⇒ `Stable`；无 ⇒ `Absent(登记理由)`。 */
  def declaredPrefix(stable: Option[String]): SystemPrefix =
    stable.filter(_.nonEmpty) match
      case Some(text) => SystemPrefix.Stable(text)
      case None => SystemPrefix.Absent(KernelCallSites.MainLegAbsence)

  // ── 装配单点（B1–B4）─────────────────────────────────────────────────────

  /**
   * **装配单点**：`KernelCall` × 候选 ⇒ wire 参数。全仓唯一的生产构造点
   * （K1 闸：`SendMessageParams(` 只允许出现在本文件）。
   *
   * 三条不变量在此一次性保证：
   *   - 稳定 system（[[SystemPrefix.wireText]]）与临时段（`requestOnlyContext` /
   *     `systemDynamic`）**分列**——临时段永不进 `messages`（K4 / C1 / C4）；
   *   - 断点预算由 [[CacheBreakpoints]] 声明（K3）；
   *   - `contextWindow` 随候选（主腿原有语义）；辅腿恒带 `maxTokensOverride`，
   *     适配器侧 `maxTokensFor` 以 override 短路窗口钳制 ⇒ 对辅腿是零行为变化。
   */
  def assemble(call: KernelCall, candidate: ModelCandidate): SendMessageParams =
    SendMessageParams(
      messages = call.messages,
      model = candidate.model,
      tools = call.tools,
      thinking = call.thinking,
      systemStable = call.systemPrefix.wireText,
      systemDynamic = call.systemDynamic,
      sessionId = call.sessionId.orElse(Some(call.purpose)),
      agentId = call.agentId.orElse(Some(call.purpose)),
      providerSearch = call.providerSearch,
      contextWindow = call.contextWindow.orElse(Some(candidate.contextWindow)),
      maxTokensOverride = call.maxTokensOverride,
      attemptBackend = call.attemptBackend,
      thinkingResolved = call.thinkingResolved,
      requestOnlyContext = call.requestOnlyContext,
      cacheBreakpoints = call.cacheBreakpoints
    )

  // ── K7 候选解析单点 ──────────────────────────────────────────────────────

  /** 候选解析单点：`CandidateSource` ⇒ 有序候选链（禁隐式回退）。 */
  // 统一模型层（P1）：唯一实现在 ModelDecision.resolveSource，本文件不再自组合。
  def candidates(registry: ProviderRegistry, source: CandidateSource): IO[List[ModelCandidate]] =
    nebflow.llm.ModelDecision.resolveSource(registry, source)

  // ── K5 整链遍历单点 ──────────────────────────────────────────────────────

  private def loggerOf(site: KernelCallSites.Site) = NebflowLogger.forName(site.logName)

  private def adapterFor(registry: ProviderRegistry, call: KernelCall, providerId: String): IO[ProviderAdapter[IO]] =
    call.gating match
      case Gating.Throttled => registry.getAdapter(providerId)
      case Gating.Ungated => registry.getAdapterRaw(providerId)

  private def oneShot(
    call: KernelCall,
    adapter: ProviderAdapter[IO],
    cand: ModelCandidate
  ): IO[Either[Throwable, KernelReply]] =
    val send: IO[Either[Throwable, KernelReply]] =
      adapter.sendMessage(assemble(call, cand)).attempt.map {
        // 空回文（HTTP 200 但 body 空）= provider 侧故障，与传输错同侧处理：试下一
        // 候选，不落 parse-fail（parse-fail 只收「有回文但不可用」的模型行为面）。
        case Right(resp) if resp.reply.trim.isEmpty =>
          Left(new RuntimeException("empty reply (HTTP 200, empty body)"))
        case Right(resp) => Right(KernelReply(resp.reply, resp.usage, cand.providerId, cand.model))
        case Left(e) => Left(e)
      }
    call.timeout.fold(send)(d => send.timeout(d))

  end oneShot

  /**
   * **整链遍历单点**（K5）：逐候选 `sendMessage`——某候选传输/适配层失败 ⇒ WARN 落
   * 真因（provider/model/异常类）＋试下一候选；**全链败才 None**（None 语义 = 「候选链
   * 耗尽」，不再被 `case Left(_) => None` 吞成「no reply」）。
   *
   * `retrySameCandidateOnTransportError = true` 时，超时/传输类失败先在同候选重试一次
   * （缺陷① 2026-11-02；单候选部署下「换下一候选」等于没有重试），重试仍败才落下一候选。
   *
   * 每次尝试都过 [[meter]]（K6：任何响应，含失败）。
   */
  def chainCall(
    registry: ProviderRegistry,
    candidates: List[ModelCandidate],
    call: KernelCall
  ): IO[Option[KernelReply]] =
    val log = loggerOf(call.site)
    val traversal = candidates.foldLeft[IO[Option[KernelReply]]](IO.pure(None)) { (acc, cand) =>
      acc.flatMap {
        case s @ Some(_) => IO.pure(s)
        case None =>
          adapterFor(registry, call, cand.providerId).attempt.flatMap {
            case Left(e) =>
              IO(
                log.warnSync(
                  s"${call.logTag}: candidate ${cand.providerId}/${cand.model} adapter unavailable " +
                    s"(${e.getClass.getSimpleName}) — trying next candidate"
                )
              ).as(None)
            case Right(adapter) =>
              oneShot(call, adapter, cand).flatMap {
                case Right(reply) => IO.pure(Some(reply))
                case Left(e) if call.retrySameCandidateOnTransportError && LlmKernel.isTimeoutOrTransport(e) =>
                  IO(
                    log.warnSync(
                      s"${call.logTag}: candidate ${cand.providerId}/${cand.model} failed on a timeout/transport " +
                        s"error (${LlmKernel.describe(e)}) — retrying the same candidate once"
                    )
                  ) *> oneShot(call, adapter, cand).flatMap {
                    case Right(reply) => IO.pure(Some(reply))
                    case Left(e2) =>
                      IO(
                        log.warnSync(
                          s"${call.logTag}: candidate ${cand.providerId}/${cand.model} failed again after retry " +
                            s"(${LlmKernel.describe(e2)}) — trying next candidate"
                        )
                      ).as(None)
                  }
                case Left(e) =>
                  IO(
                    log.warnSync(
                      s"${call.logTag}: candidate ${cand.providerId}/${cand.model} errored " +
                        s"(${LlmKernel.describe(e)}) — trying next candidate"
                    )
                  ).as(None)
              }
          }
      }
    }
    traversal.flatTap(outcome => meter(call, outcome, candidates.lastOption.map(c => (c.providerId, c.model))))

  end chainCall

  /** 解析候选（K7）后整链遍历（K5）——辅腿的常规入口。 */
  def run(registry: ProviderRegistry, call: KernelCall): IO[Option[KernelReply]] =
    candidates(registry, call.source).flatMap(cs => chainCall(registry, cs, call))

  // ── K6 计量单点 ──────────────────────────────────────────────────────────

  /**
   * **计量单点**：任何响应（含失败）都过这里。三账各自成列、互不双算：
   *   - `CacheStats.record`——供应商侧命中率聚合（read/write/input）；
   *   - `CacheAttribution.observeUsage`——miss 归因与业务/保温分列；
   *   - [[UsageSink]]——`usage-records.jsonl`（token 面板 / 成本当量的输入行）。
   *
   * `MeterKind.Probe` 走 `kind = "warmup"`：探针是恢复机制的微量探测，不进业务归因
   * （否则每 2 分钟一轮的探针会把 miss 归因面刷成噪音），但**通道是同一条**——
   * 「探针有没有过统一路径」自此可断言（K6）。
   */
  def meter(
    call: KernelCall,
    outcome: Option[KernelReply],
    fallbackDomain: Option[(String, String)]
  ): IO[Unit] =
    val domain = outcome.map(r => (r.providerId, r.model)).orElse(fallbackDomain).getOrElse(("unknown", "unknown"))
    val (providerId, model) = domain
    val usage = outcome.flatMap(_.usage)
    val kind = call.meterKind match
      case MeterKind.Probe => "warmup"
      case MeterKind.Business => "business"
    val sessionId = call.sessionId.orElse(Some(call.purpose)).getOrElse(call.purpose)
    val accounts = IO {
      CacheStats.record(providerId, model, usage)
      CacheAttribution.observeUsage(sessionId, providerId, model, usage, kind = kind)
    }.void
    val storeRow = usage match
      case None => IO.unit
      case Some(u) =>
        UsageSink.emit(
          LlmUsageRecord(
            timestamp = System.currentTimeMillis(),
            provider = providerId,
            model = model,
            agent = sessionId,
            sessionId = call.sessionId.orElse(Some(call.purpose)),
            inputTokens = u.inputTokens,
            outputTokens = u.outputTokens,
            cacheReadTokens = u.cacheReadTokens.getOrElse(0),
            cacheWriteTokens = u.cacheWriteTokens.getOrElse(0)
          )
        )
    accounts *> storeRow

  end meter

  /**
   * 流式腿的单候选入口（健康探针）：解析候选 → 取**闸视图**（[[Gating.Ungated]] =
   * 不过闸裸视图，恢复机制专用）→ 装配 → 返回带终端计量的流。
   *
   * 探针在**首个服务事件**即取消流（见 `HealthMonitor.probe`），因此流的终端计量通常
   * 拿不到 usage——这是诚实读数，不是漏记：探针形态由构造决定没有可计费 usage。
   * 归因仍经 [[meter]] 的同一通道（`MeterKind.Probe`）。
   */
  def stream(registry: ProviderRegistry, call: KernelCall): IO[fs2.Stream[IO, StreamChunk]] =
    candidates(registry, call.source).flatMap {
      case Nil =>
        IO.raiseError(new RuntimeException(s"${call.purpose}: no candidate available for a streaming call"))
      case cand :: _ =>
        adapterFor(registry, call, cand.providerId).map { adapter =>
          CacheAttribution.accountStream(
            adapter.sendMessageStream(assemble(call, cand)),
            call.sessionId.orElse(Some(call.purpose)).getOrElse(call.purpose),
            cand.providerId,
            cand.model,
            None,
            if call.meterKind == MeterKind.Probe then "warmup" else "business"
          )
        }
    }

  // ── 超时/传输类异常判定（缺陷① 重试闸，2026-11-02；自 `LlmAllocator` 迁入单点）──

  /**
   * 只有「这次调用根本没拿到回文，且原因在传输层」才值得原样重试一次；解析类/鉴权类
   * 失败重试无益。沿 cause 链查（sttp 常把 `HttpTimeoutException` 包在 `IOException` 里）。
   */
  def isTimeoutOrTransport(e: Throwable): Boolean =
    def chain(t: Throwable, depth: Int): List[Throwable] =
      if t == null || depth > 8 then Nil
      else t :: Option(t.getCause).map(c => chain(c, depth + 1)).getOrElse(Nil)
    chain(e, 0).exists { t =>
      val cls = t.getClass.getName.toLowerCase
      val msg = Option(t.getMessage).getOrElse("").toLowerCase
      cls.contains("timeout") ||
      msg.contains("timed out") || msg.contains("timeout") ||
      t.isInstanceOf[java.io.IOException] ||
      t.isInstanceOf[java.net.SocketException] ||
      t.isInstanceOf[java.util.concurrent.TimeoutException]
    }

  /** 异常的一行可归因描述（类名 + 截断的 message）——WARN 行要能被 grep 到真因。 */
  def describe(e: Throwable): String =
    val msg = Option(e.getMessage).getOrElse("")
    val head = if msg.length > 180 then msg.take(180) + "…" else msg
    if head.isEmpty then e.getClass.getSimpleName else s"${e.getClass.getSimpleName}: $head"

end LlmKernel
