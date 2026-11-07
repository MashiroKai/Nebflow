package nebflow.llm

import cats.effect.IO
import cats.effect.kernel.Ref
import cats.syntax.traverse.*
import nebflow.llm.providers.{AnthropicAdapter, OpenAiAdapter}
import nebflow.shared.*
import sttp.capabilities.fs2.Fs2Streams
import sttp.client4.StreamBackend

/**
 * One candidate of a fallback chain.
 *
 * @param contextWindow
 *   **生效**上下文窗口 = `min(configured, modelMaxContext)`：配置值（用户填写）与 provider 侧真值的较小者。旧配置无真值字段 ⇒
 *   `modelMaxContext = None` ⇒ 逐字等于配置值（向后兼容的铁律，禁顺手收紧）。
 * @param modelMaxContext
 *   provider 上报的该 model 真实可受理上限（`None` = 未知）。取数单点 =
 *   [[ProviderRegistry.effectiveContextWindow]]；真值来源 = 模型列举端点的
 *   `context_length` / `context_window`（`RestApiRoutes.extractModels`，经前端
 *   写回 `ModelConfig.modelMaxContext`）。保留在候选上是为了让「配置值被真值压低」
 *   这件事在运行时可读（案① A3 的日志面 / 调试面），不参与别的判定。
 */
case class ModelCandidate(
  providerId: String,
  provider: ProviderConfig,
  model: String,
  contextWindow: Int = Defaults.ContextWindow,
  capabilities: Set[String] = Set.empty,
  modelMaxContext: Option[Int] = None,
  /**
   * thinking-config 批（2026-10-28）：该 model 的三层继承 model 层 + 显式能力类
   * （`ModelConfig.thinking` / `ModelConfig.thinkingKind` 直通）。provider 层经
   * `provider: ProviderConfig` 随行；解析单点 = interface.scala 的
   * `resolveThinkingFor`（`ThinkingMode.resolveEffective`）。
   */
  thinking: Option[ThinkingLayerConfig] = None,
  thinkingKind: Option[ThinkingKind] = None
)

class ProviderRegistry(
  configRef: Ref[IO, NebflowServiceConfig],
  backend: StreamBackend[IO, Fs2Streams[IO]]
):
  private val adaptersRef: Ref[IO, Map[String, ProviderAdapter[IO]]] = Ref.unsafe(Map.empty)

  /**
   * 配置热读单点（provider-concurrency 批，契约 §3b 实现必读 1）：派发时刻分配
   * （`SharedResources.assignFallbackChain` → `ProviderConcurrency.assign`）要拿
   * `NebflowServiceConfig` 判 cap/语义——**只准读本 registry 的这一份 configRef**
   * （与请求路径同一真相，热重载即时生效）。禁止调用方另开 `Config.loadServiceConfig()`
   * （那会成为第二份配置真相）。可见性 private[nebflow]：调用方在 nebflow.agent。
   */
  private[nebflow] def currentConfig: IO[NebflowServiceConfig] = configRef.get

  /**
   * LLM provider 自适应并发限流器（429 反应式并发闸批，作者令 2026-10-29 21:32）。
   * 每 registry 一实例（= 每网关一份），键 = provider.baseUrl（账户键）；
   * 总闸 `llm.adaptiveThrottle.enabled` 缺省关 = 零行为变化；状态与闸机制见
   * [[AdaptiveThrottle]]。reloadConfig 清 adaptersRef 时**不**清闸状态——账户级
   * 429 风暴的记忆跨配置热重载保留（provider 改名/换 baseUrl 视为新账户键，
   * 天然全新闸）。
   */
  private[llm] val throttle: AdaptiveThrottle = new AdaptiveThrottle(configRef)

  private val logger = NebflowLogger.forName("nebflow.llm.registry")

  private def createAdapter(providerId: String, provider: ProviderConfig): ProviderAdapter[IO] =
    // 思考回传判据（单点 = ThinkingFamily.requiresPassback）：**两面都要拿到**。
    // protocol-compat 批（2026-11-01）前这里只把开关交给 AnthropicAdapter，
    // openai 面收到同样配置（cmdcode 就显式写了 requireThinkingPassback:true）
    // 时是个哑开关——配置说了要回传，代码从不回传。
    val passback = ThinkingFamily.requiresPassback(providerId, provider)
    provider.protocol match
      case LlmProtocol.OpenAI =>
        OpenAiAdapter(
          provider.baseUrl,
          provider.apiKey,
          backend,
          requireThinkingPassback = passback,
          providerId = providerId,
          providerHeaders = provider.headers
        )
      case LlmProtocol.Anthropic =>
        AnthropicAdapter(
          provider.baseUrl,
          provider.apiKey,
          backend,
          // DeepSeek's thinking mode rejects history replay that omits unsigned
          // thinking blocks (400 invalid_request → provider marked DOWN); real
          // Anthropic is the opposite. Explicit config overrides the default.
          // thinking-config 批（2026-10-28）旁支修复：缺省判据从
          // providerId=="deepseek" 扩为「providerId 或任一模型 id 命中 DeepSeek
          // 模型族」（判定单点 = ThinkingFamily.requiresPassback，spec 钉）——
          // 代码面根治 + live 配置显式补键 = 双保险。
          requireThinkingPassback = passback,
          providerId = providerId,
          providerHeaders = provider.headers
        )

    end match

  end createAdapter

  /**
   * 过闸适配器视图（429 反应式并发闸批）：返回的 adapter 每次 sendMessage /
   * sendMessageStream 过一次 [[throttle]] 闸（键 = provider 当前配置的 baseUrl，
   * 热读）——handle 的流/非流路径、LlmAllocator、DelegateModeMachine、
   * DelegateJudgeMachine 全部经本方法取适配器 ⇒ 全部模型流量单点过闸；闸关
   * （缺省）= 包装内纯透传零行为变化。粒度 = 单次 HTTP attempt：fallback 换
   * provider 各过各的闸，同 provider 重试（60s 退避后）重新过闸排队。
   * 包装为无状态薄壳，每次取用现包（裸适配器本体仍走 adaptersRef 单缓存）。
   */
  def getAdapter(providerId: String): IO[ProviderAdapter[IO]] =
    for
      raw <- getAdapterRaw(providerId)
      baseUrlOpt <- baseUrlOf(providerId)
    yield baseUrlOpt.fold[ProviderAdapter[IO]](raw)(baseUrl => new GatedAdapter(raw, baseUrl, throttle))

  /** providerId → 当前配置的 baseUrl（429 信号喂入的账户键解析；热读）。 */
  private[llm] def baseUrlOf(providerId: String): IO[Option[String]] =
    configRef.get.map(_.llm.providers.get(providerId).map(_.baseUrl))

  /**
   * 限流器健康面读数（/api/health `throttle` 键，429 反应式并发闸批）——[[throttle]]
   * 的公开只读投影（gateway 侧唯一合法触点；闸本体与信号喂入仍 private[llm]）。
   */
  def throttleHealthJson: IO[Option[io.circe.Json]] = throttle.healthJson

  /**
   * 限流风暴活性读数（后台任务生命周期 reconciler 的 Nudge 抑制面，设计稿 §5：
   * 「节流风暴期抑制非必要 Nudge（只保留徽标）」）——任一账户闸处于有限档
   * （rung>0）⇒ true。公开只读投影（gateway 侧合法触点；闸本体仍 private[llm]）。
   */
  def throttleStormActive: IO[Boolean] =
    throttle.snapshot.map(_.values.exists(_.limit.isDefined))

  /**
   * **不过闸**的裸适配器视图（裸本体缓存单点，getAdapter 在其上现包闸壳）——
   * 仅供健康探测（HealthMonitor.probe）使用：探测是恢复机制（1 次/2min 微量
   * 请求），排队在饱和闸后反而拖慢恢复判定（探测超时会计入排队等待 ⇒ 假阴性
   * DOWN 延长）。其余一切调用方必须走 [[getAdapter]]。
   */
  private[llm] def getAdapterRaw(providerId: String): IO[ProviderAdapter[IO]] =
    adaptersRef.get.map(_.get(providerId)).flatMap {
      case Some(a) => IO.pure(a)
      case None =>
        configRef.get.flatMap { config =>
          config.llm.providers.get(providerId) match
            case Some(provider) =>
              val adapter = createAdapter(providerId, provider)
              adaptersRef.update(_ + (providerId -> adapter)).as(adapter)
            case None =>
              IO.raiseError(new RuntimeException(s"Unknown provider: $providerId"))
        }
    }

  def getCandidates(): IO[List[ModelCandidate]] =
    // 读面出口过滤（model-schedule-unavailable 批）——判定机缺省腿等读面取已滤
    // 链；请求路径走 [[getCandidatesForAgentUnfiltered]]（终局判定在 LlmInterface）。
    getCandidatesUnfiltered().flatMap { candidates =>
      configRef.get.map(config => dropScheduleRestricted(candidates, config))
    }

  /**
   * 未过滤全局链（/model 主链为锚，缺省降级 seed 链 + 首 provider 兜底——见
   * model-chain anchor 批）——请求路径专用（LlmInterface 时段闸输入）。
   */
  def getCandidatesUnfiltered(): IO[List[ModelCandidate]] =
    configRef.get.map { config =>
      // 🔴 config-single-source batch (author rulings ①③ 2026-11-05): the
      // config file's `llm.fallback` section is THE chain when it carries at
      // least one ref — the panel, the file and the runtime are one truth
      // ([[FallbackChain]]). Only when the section is absent do we fall back to
      // Nebula's sidecar chain, then to the provider-derived seed. Reading the
      // decoded config (not the raw file) keeps this on the hot-reload path:
      // an `updateConfig` swaps configRef ⇒ the next call uses the new order.
      val chain =
        try
          val fromConfig = nebflow.shared.FallbackChain.configured(config)
          if fromConfig.nonEmpty then fromConfig
          else
            val policy = nebflow.core.SchemePolicy
            val (am, _) = policy.resolveModel(policy.NebulaName, policy.nebulaChain())
            am.preferred.toList ++ am.fallbacks
        catch case _: Exception => Nil
      val fromChain = chain.flatMap { ref =>
        // Gracefully skip invalid refs instead of throwing
        try
          val (providerId, modelId) = Config.parseModelRef(ref)
          config.llm.providers.get(providerId) match
            // 摘除标记守卫（reserveTier 语义修复批）：disabled provider 一律不可被选
            case Some(provider) if !isDisabled(provider) =>
              val modelConfig = provider.models.find(_.id == modelId)
              // 案② B2：`min(configured, modelMaxContext)` 的取数**单点**（见
              // [[effectiveContextWindow]]）——此处是「全局链」这条腿。
              val contextWindow = effectiveContextWindow(
                modelConfig.map(_.contextWindow).getOrElse(Defaults.ContextWindow),
                modelConfig.flatMap(_.modelMaxContext)
              )
              val caps = resolveCapabilities(providerId, modelId, modelConfig)
              Some(
                ModelCandidate(
                  providerId,
                  provider,
                  modelId,
                  contextWindow,
                  caps,
                  modelConfig.flatMap(_.modelMaxContext),
                  modelConfig.flatMap(_.thinking),
                  modelConfig.flatMap(_.thinkingKind)
                )
              )
            case _ => None // Skip unknown or disabled provider
          end match
        catch case _: Exception => None // Skip malformed ref
      }
      // Fallback: if model chain resolves to nothing (e.g. default points to a
      // non-existent provider after initial setup), use the first available model
      // across all providers so the system works out of the box.
      // 摘除标记守卫（reserveTier 语义修复批）：seed 兜底同样跳过 disabled provider。
      val fromChainOrFallback =
        if fromChain.nonEmpty then fromChain
        else
          config.llm.providers
            .find { case (_, p) => !isDisabled(p) && p.models.nonEmpty }
            .map { case (providerId, provider) =>
              provider.models.headOption.map { mc =>
                val caps = resolveCapabilities(providerId, mc.id, Some(mc))
                // 案② B2 同点（**第三处构造点**，与上面两处同形；卡文只列了 :72/:122，
                // 本处一并收口以免留下「绕过 clamp」的形状——见报告「偏离登记」）：
                ModelCandidate(
                  providerId,
                  provider,
                  mc.id,
                  effectiveContextWindow(mc.contextWindow, mc.modelMaxContext),
                  caps,
                  mc.modelMaxContext,
                  mc.thinking,
                  mc.thinkingKind
                )
              }
            }
            .flatten
            .toList
      // 模型不可用时段闸在此**不**过滤（本出口 = 未滤；过滤在 [[getCandidates]]
      // 读面出口与 LlmInterface.applyScheduleGate 请求闸两处单点）。
      fromChainOrFallback
    }

  // ============================================================
  // 候选集可观测面（config-single-source 批，作者裁定③ 2026-11-05）
  // ============================================================

  /**
   * "What would THIS call try, in what order?" — the readable candidate plan.
   *
   * Exposed as `GET /models/candidates` (the panel/log face the author asked
   * for): `source` names the chain's origin (`llm.fallback` = the config file,
   * `nebula-chain` / `seed` = legacy legs), `chain` is the configured ref order,
   * `skipped` lists refs the config can no longer resolve (they are NOT called —
   * and each one also yields a boot-time warning), `candidates` is the effective
   * ordered try-list (chain + reserve tier).
   */
  def candidatePlan(): IO[io.circe.Json] =
    import io.circe.syntax.*
    configRef.get.flatMap { config =>
      getCandidatesForAgentUnfiltered(None).map { cs =>
        val fromConfig = nebflow.shared.FallbackChain.configured(config)
        val (source, chainRefs) =
          if fromConfig.nonEmpty then ("llm.fallback", fromConfig)
          else
            val policy = nebflow.core.SchemePolicy
            val (am, src) = policy.resolveModel(policy.NebulaName, policy.nebulaChain())
            (src, am.preferred.toList ++ am.fallbacks)
        def resolves(ref: String): Boolean =
          ref.split("/", 2) match
            case Array(pid, mid) =>
              config.llm.providers.get(pid).exists(p => !isDisabled(p) && p.models.exists(_.id == mid))
            case _ => false
        val candidates = cs.map(c => s"${c.providerId}/${c.model}")
        io.circe.Json.obj(
          "source" -> source.asJson,
          "chain" -> chainRefs.asJson,
          "skipped" -> chainRefs.filterNot(resolves).asJson,
          "candidates" -> candidates.asJson,
          "warnings" -> nebflow.shared.FallbackChain.validate(config).asJson
        )
      }
    }
  end candidatePlan

  // ============================================================
  // 模型不可用时段闸（model-schedule-unavailable 批，2026-10-29 作者令 17:44）
  // ============================================================

  /** 时钟缝（观测/测试面；生产 = 系统钟）。 */
  protected def nowMillis(): Long = System.currentTimeMillis()

  /**
   * provider 摘除标记判定（reserveTier 语义修复批，2026-10-30）：`disabled: true`
   * ⇒ fail-closed 不可被选。缺省（None/false）= 未禁用。判定散列在链 tiering 的
   * 每条构建腿（全局链 / seed 兜底 / agent ref 腿 / 储备层），与
   * docs/20261030_cmdcode-provider-removal.md §5 对齐。
   */
  private[llm] def isDisabled(provider: ProviderConfig): Boolean =
    provider.disabled.getOrElse(false)

  /** 某 provider/model 的生效不可用窗口 = provider 级 ∪ 模型级（判定输入单点）。 */
  private def scheduleWindowsFor(
    config: NebflowServiceConfig,
    providerId: String,
    modelId: String
  ): List[UnavailableWindow] =
    config.llm.providers.get(providerId).toList.flatMap { provider =>
      provider.unavailableWindows.getOrElse(Nil) ++
        provider.models.find(_.id == modelId).toList.flatMap(_.unavailableWindows.getOrElse(Nil))
    }

  /**
   * 链出口过滤（[[getCandidates]] / [[getCandidatesForAgent]] 共用）：剔除当前
   * 处于不可用时段内的候选，**序不变**。缺省配置（全链无 unavailableWindows）
   * 恒等原样返回——零行为变化铁律。判定单点 = [[ModelAvailability.isUnavailable]]。
   * 注意：全受限时这里返回空链（不在此报错）——「全受限 ⇒ 显式报错」的终局
   * 判定在 LlmInterface（合并 override 后、有完整受限清单时）单点给出。
   */
  private def dropScheduleRestricted(
    candidates: List[ModelCandidate],
    config: NebflowServiceConfig
  ): List[ModelCandidate] =
    val now = nowMillis()
    candidates.filterNot(c => ModelAvailability.isUnavailable(scheduleWindowsFor(config, c.providerId, c.model), now))

  /**
   * 过滤 + 受限清单（带各自窗口描述）：LlmInterface 合并会话 override 后的
   * **补滤单点**（override 候选不经链构建出口）——返回 (可用链, 受限[候选, 原因])。
   * 原因串形态 = "14:00-18:00"（当前命中窗口，供日志/错误消息直出）。
   */
  def filterScheduleRestricted(
    candidates: List[ModelCandidate]
  ): IO[(List[ModelCandidate], List[(ModelCandidate, String)])] =
    configRef.get.map { config =>
      val now = nowMillis()
      val (available, restricted) = candidates.partitionMap { c =>
        ModelAvailability.activeWindowDesc(scheduleWindowsFor(config, c.providerId, c.model), now) match
          case None => Left(c)
          case Some(w) => Right((c, w))
      }
      (available, restricted)
    }

  /** 某 ref（"provider/model"）当前是否处于不可用时段（判定机 utility ref 腿用）。None = 可用。 */
  def scheduleRestrictionFor(ref: String): IO[Option[String]] =
    configRef.get.map { config =>
      try
        val (providerId, modelId) = Config.parseModelRef(ref)
        ModelAvailability.activeWindowDesc(scheduleWindowsFor(config, providerId, modelId), nowMillis())
      catch case _: Exception => None // 非法 ref：无时段信息 = 不受限（解析错误由既有路径处理）
    }

  /** effectiveChain 各 ref 的窗口声明面（REST 可见性腿；active = 当前在窗内）。 */
  def scheduleWindowsByRef(refs: List[String]): IO[List[(String, List[String], Boolean)]] =
    configRef.get.map { config =>
      val now = nowMillis()
      refs.map { ref =>
        val windows =
          try
            val (providerId, modelId) = Config.parseModelRef(ref)
            scheduleWindowsFor(config, providerId, modelId)
          catch case _: Exception => Nil
        val desc = windows.map(ModelAvailability.describeWindow)
        val active = ModelAvailability.isUnavailable(windows, now)
        (ref, desc, active)
      }
    }

  /**
   * 案② B2（chain-llmstall-fix）：**生效上下文窗口的唯一算式**——
   * `effective = modelMaxContext.fold(configured)(m => math.min(configured, m))`。
   *
   * 语义：配置值（用户在设置面板填写的 `provider.models[].contextWindow`）是**愿望
   * 上界**；provider 上报的真值（`modelMaxContext`）是**物理上界**；取较小者。
   * 真值未知（`None`：旧配置、或 provider 未上报）⇒ **逐字返回配置值** ⇒ 旧行为零
   * 变化（禁「顺手收紧」——卡文 §三 向后兼容条）。
   *
   * 为什么必须是单点：`contextWindow` 有三个消费者（压缩门限
   * `CompactThreshold.threshold`、硬截断 `AgentCore.hardLimit = 0.95 × cw`、回传前端的
   * `LlmMeta.contextWindow`），任何一处拿到未 clamp 的值都会让整条压缩/截断链按错
   * 的窗口计算（1M→200k 时门限应为 160k 而非 256k）。
   */
  private[llm] def effectiveContextWindow(configured: Int, modelMaxContext: Option[Int]): Int =
    modelMaxContext.fold(configured)(m => math.min(configured, m))

  /** List all available models across all providers. Returns (ref, displayName) pairs. */
  def getAllModels(): IO[List[(String, String)]] =
    configRef.get.map { config =>
      config.llm.providers.flatMap { case (providerId, provider) =>
        if provider.models.nonEmpty then provider.models.map(mc => (s"$providerId/${mc.id}", mc.id))
        else List.empty
      }.toList
    }

  /**
   * List all models with descriptions. Returns (ref, displayLabel, description) triples.
   * displayLabel includes the provider name to ensure uniqueness when multiple
   * providers offer the same model ID (e.g. two providers both have model-x).
   */
  def getAllModelsDetailed(): IO[List[(String, String, Option[String])]] =
    configRef.get.map { config =>
      config.llm.providers.flatMap { case (providerId, provider) =>
        provider.models.map(mc => (s"$providerId/${mc.id}", s"$providerId / ${mc.id}", mc.description))
      }.toList
    }

  /** Build a ModelCandidate from a model ref string (e.g. "openai/gpt-4o"). Returns None gracefully. */
  def getCandidateForRef(ref: String): IO[Option[ModelCandidate]] =
    configRef.get.map { config =>
      try
        val (providerId, modelId) = Config.parseModelRef(ref)
        // 摘除标记守卫（reserveTier 语义修复批）：disabled provider 的显式 ref 也
        // 不可被选（fail-closed）——调用方（agent 链腿 / allocator / judge /
        // 面板）全部按 None 优雅处理，与「unknown id ⇒ None」同形。
        config.llm.providers.get(providerId).filterNot(isDisabled).map { provider =>
          val modelConfig = provider.models.find(_.id == modelId)
          // 案② B2：取数单点（按 ref 这条腿）。
          val contextWindow = effectiveContextWindow(
            modelConfig.map(_.contextWindow).getOrElse(Defaults.ContextWindow),
            modelConfig.flatMap(_.modelMaxContext)
          )
          val caps = resolveCapabilities(providerId, modelId, modelConfig)
          ModelCandidate(
            providerId,
            provider,
            modelId,
            contextWindow,
            caps,
            modelConfig.flatMap(_.modelMaxContext),
            modelConfig.flatMap(_.thinking),
            modelConfig.flatMap(_.thinkingKind)
          )
        }
      catch case _: Exception => None
    }

  /**
   * Get candidate list customized for an agent's model configuration.
   *
   * **fallback 废除批（作者令 2026-11-06 20:36「不再支持模型fallback」）**：链 =
   * `preferred` **单候选**——`fallbacks` 尾链与储备层（原 `reserveTier`）双双退役：
   * 模型失效不再换链，会话进失效态（子 agent 面板可见），请求静默等待恢复
   * （`LlmInterface` 既有等待/冻结判据不变）。
   *
   * If the preferred ref fails to resolve, we fall back to the global candidate
   * list so the agent still has something to use. No capability filtering is
   * performed — the user picks the model.
   */
  def getCandidatesForAgent(agentModel: Option[nebflow.shared.AgentModelConfig]): IO[List[ModelCandidate]] =
    // 模型不可用时段闸（model-schedule-unavailable 批，2026-10-29）：读面出口
    // 过滤（/model 面板 current、判定机缺省腿等）——窗内候选剔除、序不变；缺省
    // 恒等原样（零行为变化）。请求路径**不走本出口**：LlmInterface 取
    // [[getCandidatesForAgentUnfiltered]] 并在 [[LlmInterface.applyScheduleGate]]
    // 单点过滤——全受限终局判定需要完整受限清单，链出口先滤会把受限信息丢掉
    // （漏成「空链」，流面落进 120s 全灭闸等待而非带原因快失败）。
    for
      candidates <- getCandidatesForAgentUnfiltered(agentModel)
      config <- configRef.get
    yield dropScheduleRestricted(candidates, config)

  end getCandidatesForAgent

  /**
   * 未过滤 agent 链（**单候选**，fallback 废除批）——请求路径专用（LlmInterface
   * 时段闸的单点输入）。
   */
  def getCandidatesForAgentUnfiltered(agentModel: Option[nebflow.shared.AgentModelConfig]): IO[List[ModelCandidate]] =
    agentModel match
      case None | Some(nebflow.shared.AgentModelConfig(None, _)) =>
        getCandidatesUnfiltered() // no preferred → global default
      case Some(cfg) =>
        // fallback 废除批：链 = preferred 一根（cfg.fallbacks 不再读取）。
        cfg.preferred.toList.filter(_.trim.nonEmpty).traverse(ref => getCandidateForRef(ref)).flatMap {
          resolved =>
            val agentCandidates = resolved.flatten
            if agentCandidates.nonEmpty then IO.pure(agentCandidates) else getCandidatesUnfiltered()
        }
    // 时段闸在此**不过滤**（本出口 = 未滤；过滤在 LlmInterface.applyScheduleGate 单点）。

  end getCandidatesForAgentUnfiltered

  // 储备层（原 reserveTier）已随 fallback 废除批（2026-11-06 20:36 作者令）退役：
  // 「把配置里未引用的 provider/model 追加到链尾」本身就是 fallback——链深恒 1 后
  // 该形态不再存在。模型失效的处置 = 会话失效态 + 静默等待 + 面板显式换模型。

  /**
   * Resolve capabilities for a model.
   * Priority: ModelConfig inline fields > ModelRegistry (models.json).
   *
   * visionfix (甲): the `vision` half of this resolver is retired — nothing
   * consumes a per-candidate vision bit any more, so resolving one would produce
   * a dead value. `ModelEntry.vision` in `models.json` is still read by the
   * annotation channel (REST / the settings badge) but no longer feeds the send
   * path: images are always sent, and whether an endpoint accepts them is
   * decided by the provider.
   */
  private def resolveCapabilities(
    providerId: String,
    modelId: String,
    modelConfig: Option[ModelConfig]
  ): Set[String] =
    val registryEntry = ModelRegistry.lookup(providerId, modelId)
    modelConfig
      .flatMap(_.capabilities)
      .orElse(registryEntry.map(_.capabilities))
      .getOrElse(Nil)
      .toSet

  end resolveCapabilities

  /**
   * Reload: re-read config from disk, clear adapter caches and drop
   * per-session model overrides whose provider no longer exists (#33).
   *
   * The in-memory session overrides are `ModelCandidate` snapshots built from
   * the OLD config — after a hot-reload removes a provider, a stale override
   * would raise "Unknown provider" at the adapter on the next request.
   * Dropping it here makes the session follow the new global chain (same
   * semantics as `clearAllSessionModels` at startup — a reload is a soft
   * restart of the model config). Returns the dropped session ids so callers
   * can also clear the persisted session meta (`modelRef`) for UI consistency.
   */
  def reloadConfig(
    overridesRef: Option[Ref[IO, Map[String, ModelCandidate]]] = None
  ): IO[List[String]] =
    for
      newConfig <- IO.blocking {
        try Config.loadServiceConfig()
        catch
          case _: Exception =>
            NebflowServiceConfig(
              llm = ServiceLlmConfig(
                providers = Map.empty
                // #339：占位 llm.model 已删（字段退役）
              )
            )
      }
      _ <- configRef.set(newConfig)
      _ <- adaptersRef.set(Map.empty)
      // 候选集可观测（provider-config-vs-runtime-mismatch 批，2026-11-05）：
      // 每次热生效都把**生效后**的候选链打进日志——删掉/加回某个 provider 后，
      // 「运行期到底会试哪些」在 stand.log 里可直接 grep，无需重启或翻代码。
      // 只打 provider/model ref，绝不打 key。
      _ <- getCandidatesForAgentUnfiltered(None).attempt.flatMap {
        case Right(cs) =>
          IO.delay(
            logger.infoSync(
              s"Config hot-reloaded: providers=[${newConfig.llm.providers.keys.toList.sorted.mkString(",")}] " +
                s"candidateChain=${cs.map(c => s"${c.providerId}/${c.model}").mkString(" -> ")}"
            )
          ).attempt
            .void
        case Left(e) =>
          IO.delay(logger.warnSync(s"Candidate-chain snapshot after reload failed: ${e.getMessage}")).attempt.void
      }
      staleIds <- overridesRef match
        case Some(ref) =>
          ref.modify { overrides =>
            val (keep, drop) = overrides.partition { case (_, c) => newConfig.llm.providers.contains(c.providerId) }
            (keep, drop.keys.toList)
          }
        case None => IO.pure(Nil)
    yield staleIds

  /**
   * Graceful skip for candidates whose provider is not present in the current
   * config (#33). A stale session-model override (set before a hot-reload
   * removed the provider) would otherwise surface "Unknown provider" at the
   * adapter. The request chain simply continues with the remaining
   * candidates — reload-time invalidation ([[reloadConfig]]) is the primary
   * fix; this is the request-time safety net for any stale entry that slips
   * through (e.g. a race between setSessionModel and a concurrent reload).
   */
  def filterKnownProviders(candidates: List[ModelCandidate]): IO[List[ModelCandidate]] =
    configRef.get.map(cfg => candidates.filter(c => cfg.llm.providers.contains(c.providerId)))

  /**
   * [[filterKnownProviders]] 的**带原因**版本（缺陷批 2026-11-05）：被剔的候选不
   * 再静默消失，返回 `(存活, 被剔[(ref, 原因)])`，原因 = `not-configured`（provider
   * 不在当前配置里：#33 的过期会话 override / 热重载竞态）。
   */
  def filterKnownProvidersDetailed(
    candidates: List[ModelCandidate]
  ): IO[(List[ModelCandidate], List[(String, CandidateSkipCause)])] =
    configRef.get.map { cfg =>
      val (kept, dropped) = candidates.partition(c => cfg.llm.providers.contains(c.providerId))
      val skips = dropped.map(c =>
        s"${c.providerId}/${c.model}" -> CandidateSkipCause.NotConfigured("provider not in current config")
      )
      (kept, skips)
    }

  /**
   * 配置可见全集（静默排除审计的基准，缺陷批 2026-11-05）：当前配置里**所有**
   * 未标 `disabled` 的 `provider/model` ref（字典序，可复现）。
   *
   * 用途 = 在请求入口拿它与「实际进链 + 已记录跳过原因」对账：
   * `expected - chain - skipped` 非空 ⇒ 有候选在没有任何阶段原因的情况下被扔掉
   * （作者报障的核心形态：面板显示 up、配置里有，delegate 却当它不存在）。
   * 判定与渲染单点 = [[nebflow.shared.CandidateDisposition]]。
   */
  def expectedCandidateRefs(): IO[List[String]] =
    configRef.get.map { config =>
      config.llm.providers.toList.sortBy(_._1).flatMap { case (providerId, provider) =>
        if isDisabled(provider) then Nil
        else provider.models.sortBy(_.id).map(mc => s"$providerId/${mc.id}")
      }
    }

  /**
   * 配置级「本来就不可入选」的 ref 与原因（缺陷批 2026-11-05）：`disabled: true`
   * 的 provider 下的每个模型 → `not-configured (provider disabled)`。它让「为什么
   * 它不在链上」变成账本里的一行，而不是一片沉默。
   */
  def configLevelExclusions(): IO[List[(String, CandidateSkipCause)]] =
    configRef.get.map { config =>
      config.llm.providers.toList.sortBy(_._1).flatMap { case (providerId, provider) =>
        if !isDisabled(provider) then Nil
        else
          provider.models
            .sortBy(_.id)
            .map(mc => s"$providerId/${mc.id}" -> CandidateSkipCause.NotConfigured("provider disabled"))
      }
    }

  // ============================================================
  // 候选集可观测面（provider-config-vs-runtime-mismatch 批，2026-11-05 作者令）
  // ============================================================

  /** 盘上配置的只读投影（每次现读 `nebflow.json`，不碰 configRef）。 */
  private case class DiskConfigView(
    refs: Set[String],
    activeRefs: Set[String],
    providers: Set[String],
    disabled: Set[String]
  )

  private def diskConfigView(): IO[Option[DiskConfigView]] =
    IO.blocking {
      try
        val cfg = Config.loadServiceConfig()
        val providers = cfg.llm.providers
        val refs = providers.iterator.flatMap { case (pid, p) => p.models.map(m => s"$pid/${m.id}") }.toSet
        val disabled = providers.collect { case (pid, p) if p.disabled.getOrElse(false) => pid }.toSet
        Some(
          DiskConfigView(
            refs = refs,
            activeRefs = refs.filterNot(r => disabled.contains(r.takeWhile(_ != '/'))),
            providers = providers.keySet,
            disabled = disabled
          )
        )
      catch case _: Exception => None
    }

  /**
   * 「这次调用会试哪些 provider、按什么顺序」的自查面（作者令 2026-11-05：
   * 「作者要能自查是不是漏了/多了」）。
   *
   * 输出 = 与请求路径**同一个**取链函数（[[getCandidatesForAgentUnfiltered]]）
   * 的有序结果，逐项标注来源：
   *   - `chain`  —— 显式链（agent.json `model` / Nebula 主链 / seed 兜底）里写的；
   *   - `reserve` —— 链未引用、但**配置里存在**而被 [[reserveTier]] 追加的。
   *
   * 另外三块是判据面（本批核心）：
   *   - `chainRefsDropped`：链里写了、但配置里没有（或被 `disabled: true` 摘除）
   *     因而运行期**被跳过**的 ref —— 「面板写了却不生效」的可见化；
   *   - `configProviders` / `disabledProviders`：运行期实际持有的配置面；
   *   - `drift`：**同一时刻**盘上 `nebflow.json` 与内存 `configRef` 的差集。
   *     `drift.known=false` 表示盘上不可读（刚写/损坏）；`inSync=false` 即
   *     「配置面 ≠ 运行面」，`onlyOnDisk` 里的 provider 就是「删掉了还在被调」
   *     或「加回来了还没生效」的那批名字。
   */
  def candidateReport(
    agentName: String = nebflow.core.SchemePolicy.NebulaName,
    agentModel: Option[AgentModelConfig] = None
  ): IO[io.circe.Json] =
    for
      candidates <- getCandidatesForAgentUnfiltered(agentModel)
      config <- configRef.get
      disk <- diskConfigView()
    yield
      import io.circe.syntax.*
      val policy = nebflow.core.SchemePolicy
      val (effective, resolvedFrom) =
        policy.resolveModel(agentName, agentModel.orElse(policy.ownChainOf(agentName)))
      val chainRefs = effective.preferred.toList ++ effective.fallbacks
      val dropped = chainRefs.filter { ref =>
        try
          val (pid, mid) = Config.parseModelRef(ref)
          config.llm.providers.get(pid) match
            case None => true
            case Some(p) => isDisabled(p) || !p.models.exists(_.id == mid)
        catch case _: Exception => true
      }
      val drift = disk match
        case None => io.circe.Json.obj("known" -> false.asJson)
        case Some(d) =>
          // fallback 废除批（作者令 2026-11-06 20:36「不再支持模型fallback」）：
          // 运行期候选集 = 链面的真子集（链深恒 1 = preferred 单候选），不再是
          // 「配置全集」的代理 ⇒ drift 基准从「候选 refs」改锚**配置面 refs**
          //（内存 config vs 盘上 config 的逐 ref 差集）——原判据（候选 refs ==
          // 盘 activeRefs）在链深恒 1 后会把每个未引用 provider 都误报成
          // drift（inSync 恒 false，ProviderCandidateConfigSyncSpec.A3 实证）。
          val active = config.llm.providers.toList.flatMap { case (pid, p) =>
            if isDisabled(p) then Nil else p.models.map(m => s"$pid/${m.id}")
          }.toSet
          val runtimeProviders = config.llm.providers.keySet
          io.circe.Json.obj(
            "known" -> true.asJson,
            "inSync" -> ((d.activeRefs == active) && (d.providers == runtimeProviders)).asJson,
            "onlyInRuntime" -> (runtimeProviders -- d.providers).toList.sorted.asJson,
            "onlyOnDisk" -> (d.providers -- runtimeProviders).toList.sorted.asJson,
            "refsOnlyInRuntime" -> (active -- d.activeRefs).toList.sorted.asJson,
            "refsOnlyOnDisk" -> (d.activeRefs -- active).toList.sorted.asJson
          )
      io.circe.Json.obj(
        "agent" -> agentName.asJson,
        "resolvedFrom" -> resolvedFrom.asJson,
        "chainRefs" -> chainRefs.asJson,
        "chainRefsDropped" -> dropped.asJson,
        "candidates" -> io.circe.Json.fromValues(
          candidates.map { c =>
            val ref = s"${c.providerId}/${c.model}"
            io.circe.Json.obj(
              "ref" -> ref.asJson,
              "origin" -> (if chainRefs.contains(ref) then "chain" else "reserve").asJson
            )
          }
        ),
        "configProviders" -> config.llm.providers.keys.toList.sorted.asJson,
        "disabledProviders" ->
          config.llm.providers.collect { case (pid, p) if isDisabled(p) => pid }.toList.sorted.asJson,
        "drift" -> drift
      )

end ProviderRegistry
