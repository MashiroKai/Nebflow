package nebflow.llm

import cats.effect.IO
import cats.effect.kernel.Ref
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import nebflow.shared.*

/**
 * provider 并发上限 · 派发时刻 fallback 链快照（provider-concurrency 批，作者令
 * 2026-11-06 逐字：每个 agent actor 按既有 fallback 逻辑选模型；首选 provider 的
 * 并发=10 且已有 10 个 actor 占用时，下一个创建的 actor 从第二个 provider 开始；
 * **并发按 actor 计、在派发那一刻算一次**——之后即使占用下降，该 actor 的链首
 * 也不变，保证 prompt cache 不因反复切 provider 失效）。
 *
 * 单点纪律（本对象是全仓唯一一处「按 actor 的并发预算」）：
 *   · 与 [[AdaptiveThrottle]] 正交（不是第二套并发控制）：那套键 = provider
 *     baseUrl、计数对象 = 已获票的 **HTTP 请求**、生命周期 = 单次请求；本套键 =
 *     providerId、计数对象 = **占坑的 actor**、生命周期 = actor 终生。闸位在
 *     spawn（派发时刻编队），不做排队、不做拒发、不改在飞请求并发度。
 *   · 候选构造零新增：链源 = 调用方用 `ProviderRegistry.getCandidatesForAgentUnfiltered`
 *     的产物映射而来（与请求路径同一解析函数）；本对象只做「链首下标扫描 + 占坑」。
 *   · 计数表 = `Ref[IO, Counts].modify` 单点原子 CAS（cats-effect 语义；先例
 *     AdaptiveThrottle.gates）——**不新增** java 锁 / synchronized，与作者 actor 化
 *     裁定对齐（spawn 路径一次读-改-写，modify 已线性化，actor 往返无收益）。
 */
final case class PinnedChain(
  /** 链首 ref，`provider/model`；分配时被选中的那一根。 */
  headRef: String,
  /** 链首之后的全部 ref，原链相对顺序（自 head 之后；储备层已在调用方入参内）。 */
  orderedRefs: List[String],
  /** 每根 ref 的 provider/model（诊断面/可观测面用）。 */
  refs: List[(String, String)],
  /** 占坑的 provider（= headRef 的 provider id）。 */
  pinnedProviderId: String,
  /**
   * 派发时刻被链首扫描剔出的候选与其原因（缺陷批 2026-11-06 收口，provider-concurrency
   * 批移交）：`orderedChain` 里排在本链首**之前**的 ref——它们因 provider 满载
   * （actor 占坑数已达 `maxConcurrentActors`）被剔出本 actor 的链。没有这份账，
   * 这些候选在请求账本里既不在链上也没有原因 ⇒ `SILENTLY EXCLUDED`（现网实证
   * 2026-11-06：`UTSC/glm-5.3-flash SILENTLY EXCLUDED` 反复出现）。顺序 = 原链
   * 相对顺序；经 `AgentSessionExecution` 请求构造单点下发到 `LlmRequest.pinSkips`，
   * 进候选账本 seeded 层。
   */
  preExcluded: List[(String, CandidateSkipCause)] = Nil
):

  /** 快照物化面：下发到 `LlmRequest.agentModel` 的形状（preferred=链首，fallbacks=尾链）。 */
  def toAgentModelConfig: nebflow.shared.AgentModelConfig =
    nebflow.shared.AgentModelConfig(preferred = Some(headRef), fallbacks = orderedRefs)
end PinnedChain

object ProviderConcurrency:

  private val logger = NebflowLogger.forName("nebflow.llm.provider-concurrency")

  /**
   * 钉链**失效回落**判读结果（model-404-freeze 批 2026-11-07，纯函数可测）。
   *
   * 钉链是 spawn 时刻的链快照（[[PinnedChain]]，缓存键稳定面），可配置是热读的。
   * 两者一旦漂移——现场实证 `delegate-nebflow-5c905a1d`：10:04 spawn 时链首 =
   * `Irical/gpt-6.1-terra`，11:59–12:50 配置把该模型移到 `IricalGpt` 分组下 ⇒ 钉链
   * 指向一个在自己的 provider 上已经不存在的 ref ⇒ 每个请求都吃
   * `404 model_not_found`，冻结后无限自动重试（作者「一直卡住没跑完」）。
   *
   * 处置规则（写死、不猜，判据 = **该 ref 是否仍在当前配置里**）：
   *   - 钉链首仍在当前配置的 provider/model 清单里 ⇒ [[Keep]]（快照语义不回归；
   *     「配了但不在链首」不算失效——那是合法 pin，不得静默降级）；
   *   - 钉链首已不在配置里（provider 删了/disabled/模型从该 provider 的清单里移走）
   *     ⇒ [[Drop]]，回落该会话现配置链（`to` = 现配置链首；None = 连链都没有，交给
   *     既有空链 fail-fast，不猜模型）。
   *
   * 🔴 为什么不拿「钉链首是否等于现配置链首」当判据：链深恒 1 后任何合法 pin 都会
   * 不等于链首以外的东西……反过来，一个**配了却不在链上**的合法模型会被误判失效而
   * 静默降级（连带 provider 并发占坑迁移）——所以判据必须是「还在不在配置里」。
   */
  enum PinVerdict:
    /** 钉链仍有效（或本就没有钉链）⇒ 原样使用。 */
    case Keep

    /** 钉链首已不在当前配置里 ⇒ 弃用；`to` = 现配置链首（None = 连链都没有，交给空链 fail-fast）。 */
    case Drop(from: String, to: Option[String])

  /**
   * 钉链失效判读（纯函数）。
   *
   * @param pin 该 actor 的钉链快照（None = 没钉）
   * @param headStillConfigured 钉链首这个 `provider/model` 是否仍在当前配置里
   *        （provider 存在、未 `disabled`、且该 model id 在其 models 清单里）
   * @param currentChain 现配置下该会话会走的 `provider/model` 序列（只用于给出回落目标）
   */
  def pinVerdict(
    pin: Option[PinnedChain],
    headStillConfigured: Boolean,
    currentChain: List[String]
  ): PinVerdict =
    pin match
      case None => PinVerdict.Keep
      case Some(p) =>
        if headStillConfigured then PinVerdict.Keep
        else PinVerdict.Drop(p.headRef, currentChain.headOption)

  /** `provider/model` ref 是否仍在当前配置里（口径同 [[pinVerdict]] 的 `headStillConfigured`）。 */
  def refConfigured(ref: String, providers: Map[String, nebflow.shared.ProviderConfig]): Boolean =
    val parts = Option(ref).getOrElse("").split('/')
    if parts.length < 2 then false
    else
      val pid = parts.head
      val mid = parts.tail.mkString("/")
      providers.get(pid) match
        case Some(p) if p.disabled.getOrElse(false) => false
        case Some(p) => p.models.exists(_.id == mid)
        case None => false

  /**
   * 计数表：providerId -> 该 provider 上占坑的 actorId 集合；`pinExclusions` =
   * actorId -> 派发时刻被链首扫描剔出的候选与原因（缺陷批 2026-11-06 收口）——
   * 与占坑同一张表、同一次原子 modify 读写（单一真相；release/reconcile 随占坑
   * 一起清账，不留僵尸条目）。缺省空表 = 既有构造点零改动。
   */
  final case class Counts(
    byProvider: Map[String, Set[String]],
    pinExclusions: Map[String, List[(String, CandidateSkipCause)]] = Map.empty
  )

  val empty: Counts = Counts(Map.empty)

  /** 配置解析（缺省/非法 ≤0 ⇒ None = 不限；0 不得静默当全拒，否则链首永远落二档）。 */
  private[llm] def capOf(p: ProviderConfig): Option[Int] = p.maxConcurrentActors.filter(_ > 0)

  /**
   * 分配：取「若该 actor 现在派发，会在什么链首上跑」，并**原子地把坑占上**。
   *
   * 幂等：同 actorId 已在某 provider 上占坑 ⇒ 原样返回同一快照、不重算、不加计数、
   * 不迁移（BackoffSupervisor 重 spawn / Mail 复活 / SessionResumption 同 id 全覆盖
   * ——这是「链快照终生稳定」的第二条保证线）。剔除账（[[Counts.pinExclusions]]）
   * 同幂等：re-assign 取**首钉那一刻**的记录，不随现读计数漂移改写历史。
   *
   * 缺陷批 2026-11-06（provider-concurrency 批移交的 `SILENTLY EXCLUDED` 收口）：
   * 链首扫描跳过的每一根（provider 满载）必须带原因入账
   * `PinnedChain.preExcluded`——没有这份账，被剔候选在请求账本里既不在链上也没有
   * 原因，正是现网 `UTSC/glm-5.3-flash SILENTLY EXCLUDED` 反复出现的病灶。
   *
   * @param orderedChain 全序候选 [(providerId, model)]——调用方用
   *        `getCandidatesForAgentUnfiltered` 的产物映射（已含储备层、已剔 disabled）。
   * @return None = 不钉（空链／全部超限）⇒ 不占坑、剔除账作废，回落既有全局行为
   *         （零新失败模式）。
   */
  def assign(
    counts: Ref[IO, Counts],
    config: NebflowServiceConfig,
    actorId: String,
    orderedChain: List[(String, String)]
  ): IO[Option[PinnedChain]] =
    counts
      .modify { c =>
        val existing = c.byProvider.collectFirst { case (pid, ids) if ids.contains(actorId) => pid }
        existing match
          case Some(pid) => (c, Left(pid)) // 幂等：永不迁移；剔除账由 modify 外取回
          case None =>
            // (providerId, 链下标, cap, 现占坑数) 一次成形；span 到第一根有余位的为止
            val scan = orderedChain.zipWithIndex.map { case ((pid, _), idx) =>
              (pid, idx, config.llm.providers.get(pid).flatMap(capOf), c.byProvider.getOrElse(pid, Set.empty).size)
            }
            val (before, rest) = scan.span(entry => !entry._3.forall(limit => entry._4 < limit))
            rest.headOption match
              case None => (c, Right(None)) // 全满 ⇒ 不钉
              case Some((pid, _, _, _)) =>
                val upd = c.byProvider.updated(pid, c.byProvider.getOrElse(pid, Set.empty) + actorId)
                // 被剔的前段逐根记账（首钉那一刻的占坑数 + 上限；有原因，不静默）
                val excl = before.collect { case (p, idx, Some(cap), inUse) =>
                  s"$p/${orderedChain(idx)._2}" -> CandidateSkipCause.ProviderFull(inUse, cap)
                }.toList
                (Counts(upd, c.pinExclusions.updated(actorId, excl)), Right(Some((pid, excl))))
        end match
      }
      .flatMap {
        // 幂等命中也返回完整快照：按 pid 在链上定位重造 + 剔除账取首钉记录（纯函数零副作用）。
        case Left(pid) =>
          counts.get.map { c =>
            val idx = orderedChain.indexWhere(_._1 == pid)
            if idx < 0 then None
            else Some(pinAt(orderedChain, idx).copy(preExcluded = c.pinExclusions.getOrElse(actorId, Nil)))
          }
        case Right(None) =>
          // 不钉：剔除账一并作废（无 pin 就无账），回落既有全局行为
          counts.update(c => c.copy(pinExclusions = c.pinExclusions - actorId)).as(None)
        case Right(Some((pid, excl))) =>
          val idx = orderedChain.indexWhere(_._1 == pid)
          IO.pure(Some(pinAt(orderedChain, idx).copy(preExcluded = excl)))
      }

  /**
   * 尾链策略（契约 §3d，作者原话「其后按原 fallback 顺序接续」）＝ **仅取被选中的
   * 下标之后**，不回绕被跳过的前段：回绕等于「先因满拒绝、再在尾链里尝试它」，
   * 与上限意图矛盾，且会与储备层交错。反例申报：链只有 2 根且第 1 根满 ⇒ 第二个
   * actor 的尾链 = 空 + 储备层（故意的；若作者后要回绕，改动点只有本函数一处）。
   */
  private[llm] def pinAt(chain: List[(String, String)], idx: Int): PinnedChain =
    val head = chain(idx)
    val after = chain.drop(idx + 1)
    PinnedChain(
      headRef = s"${head._1}/${head._2}",
      orderedRefs = after.map((p, m) => s"$p/$m"),
      refs = head :: after,
      pinnedProviderId = head._1
    )

  /** 释放（幂等：坑不在就 no-op；永不 decrement 别人的坑、计数永不为负）。剔除账随占坑一起清（不留僵尸条目，缺陷批 2026-11-06）。 */
  def release(counts: Ref[IO, Counts], actorId: String): IO[Unit] =
    counts.update { c =>
      Counts(
        c.byProvider.view.mapValues(_ - actorId).filter(_._2.nonEmpty).toMap,
        c.pinExclusions - actorId
      )
    }

  /**
   * 启动自愈：计数表投影为「原表 ∩ 活 actor 集」（活 ID 集来自
   * `SharedResources.agentRegistry`——agent 记录的唯一权威）。崩溃/热重启后由
   * GatewayMain boot 调一次；不新增定时器/fiber。剔除账同法裁剪（死 actor 的账
   * 不复活，缺陷批 2026-11-06）。
   */
  def reconcile(counts: Ref[IO, Counts], liveActorIds: Set[String]): IO[Unit] =
    counts.update { c =>
      Counts(
        c.byProvider.view.mapValues(_ intersect liveActorIds).filter(_._2.nonEmpty).toMap,
        c.pinExclusions.view.filterKeys(liveActorIds.contains).toMap
      )
    }

  /** 只读投影（§3h 可观测面）：assignable/inUse/cap（不限=显式 null）/full + totalActors。 */
  def snapshot(counts: Ref[IO, Counts], config: NebflowServiceConfig): IO[Json] =
    counts.get.map { c =>
      val entries = config.llm.providers.toList.sortBy(_._1).map { case (pid, p) =>
        val inUse = c.byProvider.getOrElse(pid, Set.empty).size
        val cap = capOf(p)
        Json.obj(
          "providerId" -> pid.asJson,
          "inUse" -> inUse.asJson,
          "cap" -> cap.asJson, // Option[Int] ⇒ None 编码为显式 null（键存在、值 null）
          "full" -> cap.fold(false)(limit => inUse >= limit).asJson
        )
      }
      Json.obj(
        "assignable" -> entries.asJson,
        "totalActors" -> c.byProvider.values.flatten.size.asJson
      )
    }

  /**
   * 分配日志行（§3h(3) 单点；由 SharedResources 壳在 spawn 路径调用）。pin = None
   * 的 reason 由壳侧显式给（empty-chain / all-full）——本对象不自测原因。
   */
  def logAssign(
    actorId: String,
    agentName: String,
    pin: Option[PinnedChain],
    configuredHead: Option[String],
    noPinReason: Option[String] = None
  ): IO[Unit] =
    IO {
      pin match
        case Some(p) =>
          logger.infoSync(
            s"[provider-concurrency] assign actor=$actorId agent=$agentName head=${p.headRef} " +
              s"pinned=${p.pinnedProviderId} chainHead=${configuredHead.getOrElse("-")}"
          )
        case None =>
          logger.warnSync(
            s"[provider-concurrency] no-pin actor=$actorId agent=$agentName " +
              s"reason=${noPinReason.getOrElse("all-full")} chainHead=${configuredHead.getOrElse("-")}"
          )
    }.attempt.void
end ProviderConcurrency
