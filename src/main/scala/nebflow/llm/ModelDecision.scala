package nebflow.llm

import cats.effect.{IO, Ref}
import java.time.LocalDateTime
import nebflow.core.ModelRoles
import nebflow.llm.kernel.LlmKernel
import nebflow.llm.rules.{ModelRuleDsl, ModelRuleStore}
import nebflow.shared.AgentModelConfig

/**
 * 统一模型层（模型分配规则引擎 P1，行为等价收口）。
 *
 * 模型决策的组合点收口于本对象：
 *   - 请求时组合（槽 > 钉链 > base）只在 [[composeAgentModel]] 出现一次；
 *   - 请求面候选只在 [[resolveRequest]] 出现一次；
 *   - 派发时占坑 + 链首钉只在 [[spawnPin]] 出现一次。
 * 每个函数内部直接调用既有解析函数（`ModelRoles.slotChainOf`、
 * `ProviderRegistry.getCandidatesForAgent[Unfiltered]`、`ProviderConcurrency.assign`），
 * 不新增选择语义。
 *
 * 两条时间线（不可破坏）：请求时（槽链每 turn 现读、时段过滤）vs 派发时（占坑 + 链首钉，
 * spawn 一次、终生不迁移，保 prompt cache）。
 *
 * P1 占位：规则集恒空（P2 接入规则脚本）；实例内 fallback 维持废除（链深恒 1）。
 */
object ModelDecision:

  /**
   * 请求上下文。`source` = 显式来源枚举（K7：必需语义、由 [[ctxOf]] 推导填充，调用方不自选）；
   * `isAskSession` / `callPoint`（N1 调用点，值域见设计件 §8 N1）进统一层，供小模型分流 [[smallLlmRoute]]。
   */
  final case class RequestCtx(
    sessionId: Option[String],
    delegateMode: Option[String] = None,
    isAskSession: Boolean = false,
    callPoint: Option[String] = None,
    pinnedChain: Option[PinnedChain] = None,
    source: Option[LlmKernel.CandidateSource] = None
  )

  /**
   * 由显式来源推导请求上下文（§2.2 步骤 3：枚举退化为 RequestCtx 字段，由 resolve 内部推导）。
   * SmallLlmModel ⇒ callPoint=small-llm（小模型分流点）；其余来源无额外上下文。
   */
  def ctxOf(source: LlmKernel.CandidateSource): RequestCtx = source match
    case LlmKernel.CandidateSource.SmallLlmModel =>
      RequestCtx(None, callPoint = Some("small-llm"), source = Some(source))
    case other => RequestCtx(None, source = Some(other))

  /**
   * K7 候选解析的唯一实现（`LlmKernel.candidates` 委托至此）：来源 ⇒ RequestCtx ⇒ 候选链。
   * 小模型经 [[smallLlmRoute]] 分流（N1 第 4 步接入的真实调用点）。
   */
  def resolveSource(registry: ProviderRegistry, source: LlmKernel.CandidateSource): IO[List[ModelCandidate]] =
    val ctx = ctxOf(source)
    val base: IO[List[ModelCandidate]] = source match
      case LlmKernel.CandidateSource.SmallLlmModel =>
        IO(smallLlmRoute(ctx)).flatMap(m => candidatesOf(registry, m))
      case LlmKernel.CandidateSource.NebulaChain =>
        nebulaCandidates(registry)
      case LlmKernel.CandidateSource.Slot(agentModel) =>
        candidatesOf(registry, agentModel)
      case LlmKernel.CandidateSource.Explicit(list) =>
        IO.pure(list)
      case LlmKernel.CandidateSource.DecisionRoute(name) =>
        decisionRouteCandidates(registry, name)
    source match
      // Explicit = 调用方已钉死的显式链，不受规则脚本改写。
      case LlmKernel.CandidateSource.Explicit(_) => base
      case _ =>
        base.flatMap(cs => IO(applyRulesNow(ctx, sourceCall(source), cs)).map(_._1))

  /** 来源 → 调用点（N1 值域）。ctx.callPoint 显式给出时优先。 */
  def sourceCall(source: LlmKernel.CandidateSource): String = source match
    case LlmKernel.CandidateSource.SmallLlmModel => "small-llm"
    case LlmKernel.CandidateSource.DecisionRoute(_) => "decision-route"
    case _ => "main-chat"

  /** 候选 ref 形式（与 ModelRuleDsl 的 provider/model 口径一致）。 */
  def refOf(c: ModelCandidate): String = s"${c.providerId}/${c.model}"

  /**
   * 规则改写候选链（P3 接线，纯函数：脚本显式传入，便于测试）。
   *   - 脚本空 / 无命中 ⇒ 候选原样返回（逐项相等，P1 golden 不变）。
   *   - 命中 ⇒ 按 `ModelRuleDsl.evaluate` 的顺序重排并剔除 freeze/unavailable 的候选；
   *     evaluate 给出的 ref 在原候选中找不到的忽略（不凭空造候选）。
   *   - 脚本解析失败 ⇒ 不应用规则，trace 记 `script-invalid`（请求期不中断选择）。
   *   - `switch` 只产生提议（N2），不改链；`overflow` / `concurrency` 属派发时，不进请求链。
   */
  def applyRules(
    loaded: ModelRuleStore.Loaded,
    call: String,
    at: LocalDateTime,
    cands: List[ModelCandidate]
  ): (List[ModelCandidate], List[Hit]) =
    loaded.error match
      case Some(err) =>
        (cands, List(Hit("rule", s"script-invalid: $err ⇒ no rules applied")))
      case None if loaded.script.rules.isEmpty =>
        (cands, Nil)
      case None =>
        val outcome = ModelRuleDsl.evaluate(loaded.script, ModelRuleDsl.Session(None, None, call, at), cands.map(refOf))
        if outcome.matched.isEmpty then (cands, Nil)
        else
          val byRef = cands.groupBy(refOf).view.mapValues(_.head).toMap
          val rewritten = outcome.candidates.flatMap(byRef.get)
          val hits = outcome.trace.map(s => Hit("rule", s"${s.rule} ${s.action}: ${s.detail}"))
          (rewritten, hits)

  /** 读取当前数据根的规则脚本并应用（生产入口；空脚本 ⇒ 恒等）。 */
  def applyRulesNow(ctx: RequestCtx, defaultCall: String, cands: List[ModelCandidate]): (List[ModelCandidate], List[Hit]) =
    val call = ctx.callPoint.getOrElse(defaultCall)
    applyRules(ModelRuleStore.current, call, LocalDateTime.now(), cands)

  /** 一步命中依据（trace 元素）。 */
  final case class Hit(step: String, detail: String)

  /** 决策输出：候选链 + 钉链 + 命中痕迹（每个候选至少一条 Hit）。 */
  final case class Decision(
    candidates: List[ModelCandidate],
    pin: Option[PinnedChain],
    trace: List[Hit]
  )

  /**
   * 请求时组合（纯、同步）：槽已配置 ⇒ 槽链；否则钉链物化；否则 base 原样透传。
   * 与既有 `ModelRoles.slotChainOf(sid, mode).orElse(pin.fold(base)(_.toAgentModelConfig))`
   * 逐项等价（AgentSessionExecution 原组合段）。
   */
  def composeAgentModel(
    sessionId: Option[String],
    delegateMode: Option[String],
    base: Option[AgentModelConfig],
    pin: Option[PinnedChain]
  ): (Option[AgentModelConfig], List[Hit]) =
    ModelRoles.slotChainOf(sessionId, delegateMode) match
      case Some(slotChain) =>
        (Some(slotChain), List(Hit("compose", "slot chain (slot configured)")))
      case None =>
        pin match
          case Some(p) => (Some(p.toAgentModelConfig), List(Hit("compose", s"pinned chain head=${p.headRef}")))
          case None => (base, List(Hit("compose", "agent base chain (no slot, no pin)")))

  /** 候选构造单点（时段过滤在内，既有 `getCandidatesForAgent` 口径）。 */
  def candidatesOf(registry: ProviderRegistry, agentModel: Option[AgentModelConfig]): IO[List[ModelCandidate]] =
    registry.getCandidatesForAgent(agentModel)

  /**
   * 小模型分流（N1 第 4 步）：统一层按 `isAskSession` / `callPoint` 区分调用点。
   * 小模型 = `SchemePolicy.smallLlmModel()`（既有选择，不改）；ask 会话与 `small-llm` 调用
   * 走同一小模型链。非小模型（主 / 槽 / 决策）返回 None，由调用方走组合链。
   */
  def smallLlmRoute(ctx: RequestCtx): Option[AgentModelConfig] =
    if ctx.isAskSession || ctx.callPoint.contains("small-llm") then
      Some(nebflow.core.SchemePolicy.smallLlmModel())
    else None

  /** `CandidateSource.NebulaChain` 的候选（主链 = `SchemePolicy.nebulaChain()`，同 getCandidatesForAgent 口径）。 */
  def nebulaCandidates(registry: ProviderRegistry): IO[List[ModelCandidate]] =
    candidatesOf(registry, nebflow.core.SchemePolicy.nebulaChain())

  /** `CandidateSource.SmallLlmModel` 的候选（与既有分支逐字同：smallLlmModel ⇒ getCandidatesForAgent）。 */
  def smallLlmCandidates(registry: ProviderRegistry): IO[List[ModelCandidate]] =
    IO(nebflow.core.SchemePolicy.smallLlmModel()).flatMap(m => candidatesOf(registry, Some(m)))

  /**
   * 决策路由候选（CandidateSource.DecisionRoute 的统一实现，语义逐字同既有
   * `LlmKernel.decisionRouteCandidates`：显式 ref 可解 ⇒ 钉首位并与全局链去重；
   * 时段受限 / 不可解 / 未配 ⇒ 全局链）。
   */
  def decisionRouteCandidates(registry: ProviderRegistry, name: String): IO[List[ModelCandidate]] =
    registry.currentConfig.flatMap { currentConfig =>
      val explicitRoute = currentConfig.llm.decisionRoutes
        .flatMap(_.get(name))
        .flatMap(_.preferred)
        .orElse(if name == "delegate-judge" then currentConfig.judge.flatMap(_.utilityModel) else None)
      def mainRoute = registry.getCandidatesForAgent(nebflow.core.SchemePolicy.nebulaChain())
      explicitRoute match
        case Some(ref) =>
          registry.scheduleRestrictionFor(ref).flatMap {
            case Some(_) => mainRoute
            case None =>
              registry.getCandidateForRef(ref).flatMap {
                case Some(c) =>
                  registry.getCandidates().map { chain =>
                    val rest = chain
                      .filterNot(r => r.providerId == c.providerId && r.model == c.model)
                      .distinctBy(r => (r.providerId, r.model))
                    c :: rest
                  }
                case None => mainRoute
              }
          }
        case None => mainRoute
      end match
    }

  /**
   * 请求面统一决策：用 [[composeAgentModel]] 组合出链，再经 [[candidatesOf]] 得候选；
   * 每个候选带一条 `candidate` 命中（链位次 + 所用组合依据）。
   * 行为等价 = 既有 `LlmKernel.candidates(Slot(...))` 对同一输入链的结果。
   */
  def resolveRequest(
    registry: ProviderRegistry,
    ctx: RequestCtx,
    base: Option[AgentModelConfig]
  ): IO[Decision] =
    val (composed, composeHits) = composeAgentModel(ctx.sessionId, ctx.delegateMode, base, ctx.pinnedChain)
    candidatesOf(registry, composed).map { cs0 =>
      val (cs, ruleHits) = applyRulesNow(ctx, "main-chat", cs0)
      val candHits = cs.zipWithIndex.map { case (c, i) =>
        Hit("candidate", s"${c.providerId}/${c.model} rank=${i + 1} via ${composeHits.headOption.map(_.detail).getOrElse("-")}")
      }
      Decision(cs, ctx.pinnedChain, composeHits ++ ruleHits ++ candHits)
    }

  /**
   * 派发时占坑（spawn 一次，幂等）。等价于既有 `SharedResources.assignFallbackChain` 函数体：
   * 链源 = `getCandidatesForAgentUnfiltered(agentModel)`，配置源 = `currentConfig`，
   * 占坑 = `ProviderConcurrency.assign`。`registry == null`（测试面）⇒ None。
   */
  def spawnPin(
    registry: ProviderRegistry,
    counts: Ref[IO, ProviderConcurrency.Counts],
    actorId: String,
    agentName: String,
    agentModel: Option[AgentModelConfig]
  ): IO[Decision] =
    if registry == null then
      ProviderConcurrency
        .logAssign(actorId, agentName, None, None, Some("registry-absent"))
        .as(Decision(Nil, None, List(Hit("pin", "registry-absent: no pin"))))
    else
      for
        cfg <- registry.currentConfig
        chain <- registry.getCandidatesForAgentUnfiltered(agentModel)
        configuredHead = chain.headOption.map(c => s"${c.providerId}/${c.model}")
        pin <- ProviderConcurrency.assign(counts, cfg, actorId, chain.map(c => c.providerId -> c.model))
        reason = if chain.isEmpty then "empty-chain" else "all-full"
        _ <- ProviderConcurrency.logAssign(actorId, agentName, pin, configuredHead, Some(reason))
        hit = pin match
          case Some(p) => Hit("pin", s"pinned head=${p.headRef} actor=$actorId")
          case None => Hit("pin", s"$reason: no pin")
      yield Decision(Nil, pin, List(hit))

end ModelDecision
