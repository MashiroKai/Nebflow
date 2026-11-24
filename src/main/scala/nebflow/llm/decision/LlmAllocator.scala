package nebflow.llm.decision

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import nebflow.core.jev.{CatalogEntry, JevAllocation, JevFallback, JevFallbackKind}
import nebflow.llm.kernel.{KernelCallSites, LlmKernel}
import nebflow.llm.{CacheBreakpoints, ModelCandidate, ProviderRegistry}
import nebflow.shared.{Defaults, Message, MessageRole, NebflowLogger}

/**
 * LlmAllocator —— **小模型分配腿**（jev-retire 批：JeV 腿退役后唯一分配执行体）.
 *
 * == 机制 ==
 * 单次 LLM 调用；模型 = 候选链整链遍历（候选源 = `SchemePolicy.smallLlmModel()`，
 * 未配置回退主模型+WARN）。执行次序由 [[PluginAllocatorMachine]] 编排；本件只做
 * 「task + 目录 → 集合」的一条腿。
 *
 * == kernel-spec 批（2026-11-19）==
 * 本腿不再自建 `SendMessageParams`、不再自写候选链遍历：调用描述 = [[LlmKernel.KernelCall]]，
 * 装配 = `LlmKernel.assemble`（唯一生产构造点），遍历 = `LlmKernel.chainCall`，
 * 计量 = `LlmKernel.chainCall` 内的单点（K1/K5/K6）。
 * **B2（V1 修）**：常量前导 [[StatePreamble]] 提为 `systemStable`（文本逐字不变，
 * 只换 wire 位置），任务正文留在 user 消息 —— 前缀自此**位置可复用**。
 *
 * == state 模板（单源本件）==
 * preamble ＋ brief ＋ 目录块——常量与 `catalogText` 自 JevAllocator 原文迁入
 * （jev-retire 批，逐字节不变；preamble 终源在 `PromptCatalog.Classifiers`）；
 * 成员资格规则 = 同一条 R2 主规则（「任务主目的离开该能力包即无法正确执行
 * ⇒ needed」），问答形态 = 单次 JSON。目录序、brief 封顶（12000）不变。
 *
 * == 解析纪律 ==
 * 回文取第一个完整 JSON 对象（裸 JSON / ```json 围栏 / 混排皆容——判定机
 * extractJsonObject 同一实现单点复用）；`plugins` 值域 = 字符串数组；**未知包名
 * 剔除**（目录是唯一合法值域——模型幻觉名绝不进注入面），剔除留 WARN；缺键/形错
 * = Failure fail-open（绝不 fabricate 空集成功）。
 *
 * 🔴 提示词为**模型可见文案**——逐字报审（jev-retire 批随件呈作者）。
 */
final class LlmAllocator(
  /** 单次调用缝（生产 = 统一内核 `LlmKernel.run`；spec 注 canned 回文）。 */
  kernelCall: LlmKernel.KernelCall => IO[Option[LlmKernel.KernelReply]],
  /** 模型候选（None = 无可用模型——腿不可用，Failure fail-open）。 */
  candidate: IO[Option[ModelCandidate]]
):

  private val logger = NebflowLogger.forName("nebflow.llm.plugin-alloc")

  /** 判定会话的身份戳（SendMessageParams 元数据；router 日志归因——判定机同款）。 */
  private val AllocSessionId = "plugin-alloc"

  def allocateFor(
    subject: String,
    taskText: String,
    catalog: List[CatalogEntry]
  ): IO[JevAllocation] =
    if catalog.isEmpty then
      // 空目录 = 合法「无能力面安装」态（JevAllocator 同款短路口）——零模型调用。
      JevFallback
        .recordSuccess(subject, Nil, Some(PluginAllocatorMachine.LlmFallbackWire))
        .as(
          JevAllocation.Allocated(Nil)
        )
    else
      candidate.flatMap {
        case None =>
          JevFallback.record(JevFallbackKind.Failure, subject, "llm allocator: no model candidate available")
        case Some(_) =>
          // kernel-spec 批（2026-11-19）：调用描述 = KernelCall（K2/K7 必填声明），
          // 装配/遍历/计量三者全在统一内核。辅面钉旧值（钳制批 2026-10-21）：
          // ProbeMaxTokens（16384）零行为变化，128000 主面值不上此线。
          val call = LlmKernel.KernelCall(
            site = KernelCallSites.PluginAlloc,
            // 🔴 作者裁定 2026-11-19 17:22：常量段（前导＋表头＋目录）**整体**入
            // `systemStable`，与主腿同形态；user 侧只留每次变化的部分。
            systemPrefix = LlmKernel.SystemPrefix.Stable(LlmAllocator.stableBlock(catalog)),
            messages = List(Message(MessageRole.User, Left(body(taskText)))),
            source = LlmKernel.CandidateSource.SmallLlmModel,
            sessionId = Some(AllocSessionId),
            agentId = Some(AllocSessionId),
            maxTokensOverride = Some(Defaults.ProbeMaxTokens),
            // 🔴 缺陷①（2026-11-02）：超时/传输类失败先在同候选重试一次（单候选部署下
            // 「换下一候选」等于没有重试）——语义随调用描述声明，遍历模板在核心里。
            retrySameCandidateOnTransportError = true,
            cacheBreakpoints = LlmAllocator.AllocBreakpoints
          )
          kernelCall(call).attempt
            .flatMap {
              case Right(Some(reply)) =>
                parsePlugins(reply.reply, catalog) match
                  case Right(names) =>
                    val reason = DelegateJudgeMachine
                      .extractJsonObject(reply.reply)
                      .flatMap(_.hcursor.get[String]("reason").toOption)
                      .map(_.replaceAll("[\\r\\n]+", " ").take(240))
                      .getOrElse("model supplied no rationale")
                    IO(
                      logger.infoSync(
                        s"allocation decision subject=${subject.replaceAll("[\\r\\n]+", " ")} plugins=${names.mkString(",").replaceAll("[\\r\\n]+", " ")} reason=$reason"
                      )
                    ) *>
                      JevFallback
                        .recordSuccess(subject, names, Some(PluginAllocatorMachine.LlmFallbackWire))
                        .as(JevAllocation.Allocated(names))
                  case Left(reason) =>
                    // 归因精确化批（2026-10-29）：回文不可用 = ParseFail——可恢复
                    // 错误各归各词，不再与结构性全败混记 fail-open。
                    JevFallback.record(JevFallbackKind.ParseFail, subject, s"llm allocator: $reason")
              case Right(None) =>
                // 调用缝 None = 候选链全败（链遍历缝，2026-10-29 根因修复——见
                // companion chainCall）＝调用层失败 LlmError，绝不复述成「无回复」
                // 了事：缝内逐候选 WARN 已留真因。
                JevFallback.record(
                  JevFallbackKind.LlmError,
                  subject,
                  "llm allocator: call failed (candidate chain exhausted — see per-candidate WARN lines)"
                )
              case Left(e) =>
                // 🔴 零超时（2026-10-19 作者拍板）：本腿不再挂分配调用定时器
                // （原 15s `.timeout()` 已拆）——左值只能来自调用缝自身的网络/
                // 传输异常，统一记 LlmError（可恢复调用层失败，各归各词）。
                JevFallback.record(JevFallbackKind.LlmError, subject, s"llm allocator: ${e.getClass.getSimpleName}")
            }
      }

  /**
   * 模型可见 user 正文（**只含每次变化的部分**）：任务正文 ＋ 成员资格规则 ＋ JSON 答令。
   *
   * 🔴 常量段（前导 ＋ 表头 ＋ 目录）已整体搬入 `KernelCall.systemPrefix`（作者裁定
   * 2026-11-19 17:22，见 [[LlmAllocator.stableBlock]]）——本正文自此不含任何可复用
   * 常量，逐发变化的只有任务正文。措辞逐字沿用批前文案（只换 wire 位置）。
   */
  private def body(taskText: String): String =
    val brief =
      if taskText.length > LlmAllocator.DefaultStateCapChars then taskText.take(LlmAllocator.DefaultStateCapChars)
      else taskText
    s"""$brief

Choose the capability packages this task NEEDS, applying exactly the membership rule stated above: count a package as needed only when the task's primary purpose cannot be properly executed without the capability that package provides. Multiple packages may be needed; judge each package independently. If no package is needed, return an empty list.

Answer with ONLY a JSON object: {"plugins": ["<package-name>", ...], "reason": "<brief rationale>"}"""

  // ── 纯函数解析（spec 判红锚） ─────────────────────────────────────────

  /**
   * 回文 → 目录内包名集（保序去重）。`Left` = 缺 `plugins` 键 / 非 JSON / 值域形错
   * （fail-open 走 Failure）；未知包名**剔除**（WARN 逐名留痕——幻觉名不进注入面），
   * 剩余合法名照常成功。
   */
  private[decision] def parsePlugins(text: String, catalog: List[CatalogEntry]): Either[String, List[String]] =
    DelegateJudgeMachine.extractJsonObject(text) match
      case None => Left("reply carries no JSON object")
      case Some(json) =>
        json.hcursor.get[Vector[Json]]("plugins").toOption match
          case None => Left("""reply JSON has no "plugins" array""")
          case Some(raw) =>
            raw.traverse(_.asString) match
              case None => Left("""reply "plugins" must be an array of strings""")
              case Some(all) =>
                val unique = all.toList.distinct
                val known = catalog.map(_.name).toSet
                val unknown = unique.filterNot(known)
                unknown.foreach(n => logger.warnSync(s"llm allocator: unknown package '$n' dropped (not in catalog)"))
                Right(unique.filter(known))

end LlmAllocator

object LlmAllocator:

  /**
   * `state` brief 封顶（原 JevAllocator 同名常量原文迁入）：封顶的是 BRIEF 段，
   * 不封目录段——迁移前 scorer 的口径（12000 字符），逐值不变。
   */
  private[decision] val DefaultStateCapChars: Int = 12000

  /**
   * state 前导（原 JevAllocator.StatePreamble 原文迁入）——终源在
   * `PromptCatalog.Classifiers.AllocationStatePreamble`（单行拼接字面量，不得改行）。
   *
   * kernel-spec 批（2026-11-19，B2）：本常量**不再进 user 正文**；作者裁定 17:22 起
   * 它与两个表头、目录一起组成 [[stableBlock]] 整体走 `KernelCall.systemPrefix`
   * （`systemStable`）——文本逐字不变，只换 wire 位置。
   */
  private[decision] val StatePreamble: String = nebflow.prompts.PromptCatalog.Classifiers.AllocationStatePreamble

  /**
   * **常量段（本腿唯一的可复用前缀）**：前导 ＋ 两个表头 ＋ 目录。
   *
   * 作者裁定 2026-11-19 17:22：整段搬入 `KernelCall.systemPrefix`（`systemStable`），
   * 与主腿同形态；user 侧只留每次变化的部分（[[body]]）。这是本批唯一一处**反向**
   * 解除 K3「前缀低于供应商最小可缓存长度就不钉断点」的腿——理由只有一条：
   * **整段越过阈值**（实测第二发 `read≈5288`）。其余各腿的前缀都在阈值之下，
   * 仍一律不钉断点（见 [[DelegateJudgeMachine.JudgeBreakpoints]] 与探针腿）。
   *
   * 🔴 **失效条件（写死在注释里，改目录前先读）**：本段是**一个**缓存前缀。
   * 目录里任何一条新增 / 改名 / 改描述，或前导、表头改一个字，都会让整段前缀
   * 一次性失效——下一次调用要把整段重新写一遍（写一次的钱），之后各发才重新命中。
   * 所以目录变动应当成批进行；逐条微调会让每一发都变成「写满整段」。
   */
  private[decision] def stableBlock(catalog: List[CatalogEntry]): String =
    s"""$StatePreamble

$BriefHeader
$CatalogHeader
${catalogText(catalog)}"""

  /**
   * 本腿断点预算（K3 的**唯一反向解除**）：**StablePrefix** —— 只钉稳定段
   * （system 稳定块；有工具面时加末工具），不钉逐发变化的正文尾。
   *
   * 依据（2026-11-19 实测，每臂独立 nonce 的干净读数：
   * `.stand/tmp/kernel-spec-20261119/evidence/probe_legs_v4.out`）：
   *   - 批前形态（尾断点钉在**逐发变化**的整段正文上）：`creation=5452 / read=0`，
   *     两发逐值相同 —— 与审计 §5 E2 的 `897/0` 同型（量级差只因目录更大）；
   *   - 只把 [[StatePreamble]]（616 字符 ≈200 tokens）提为 system：**逐值相同** ——
   *     低于供应商最小可缓存长度，钉了也不写；
   *   - 常量段**整体**提为 system（[[stableBlock]]，≈5288 tokens）：第二发
   *     `creation=164 / read=5288` —— **命中**；这是本腿钉断点的唯一依据；
   *   - 阈值区间：400 tokens 两发 `0/0`（不缓存）⇒ 最小可缓存长度落在 (400, 5288]。
   *
   * 🔴 判据是「整段越过阈值」，不是「本腿特殊」：常量段一旦缩回阈值之下
   * （例如目录被裁到几十条以内），本常量必须改回 [[CacheBreakpoints.None_]]，
   * 否则就是空转写——把「其实没缓存」伪装成「已配置缓存」。
   */
  private[decision] val AllocBreakpoints: CacheBreakpoints = CacheBreakpoints.StablePrefix

  /** brief 段头（原 JevAllocator 同名常量原文迁入）。 */
  private[decision] val BriefHeader: String = "=== TASK BRIEF ==="

  /** 目录段头（原 JevAllocator 同名常量原文迁入）。 */
  private[decision] val CatalogHeader: String = "=== INSTALLED PLUGIN CAPABILITY CATALOG ==="

  /**
   * 目录渲染为 `### <name>\n<description>` 块、空行相接、保序（原
   * JevAllocator.catalogText 原文迁入）。双模式批（2026-10-28）增量键增量渲染：
   * keywords 非空 / useWhen 在 ⇒ 各加一行；缺席 = 块形不变。
   */
  private[decision] def catalogText(catalog: List[CatalogEntry]): String =
    catalog
      .map { e =>
        val kw = if e.keywords.isEmpty then "" else s"\nkeywords: ${e.keywords.mkString(", ")}"
        val uw = e.useWhen.map(w => s"\nuse when: $w").getOrElse("")
        s"### ${e.name}\n${e.description}$kw$uw"
      }
      .mkString("\n\n")

  /**
   * 超时/传输类异常判定（缺陷① 重试闸，2026-11-02）：实现已迁 [[LlmKernel.isTimeoutOrTransport]]
   * ——内核整链遍历单点是它的唯一消费方（K5），本别名只为既读面/判读锚保留入口。
   */
  private[decision] def isTimeoutOrTransport(e: Throwable): Boolean = LlmKernel.isTimeoutOrTransport(e)

  /** 异常的一行可归因描述（K5 单点：[[LlmKernel.describe]]）。 */
  private[decision] def describe(e: Throwable): String = LlmKernel.describe(e)

  /**
   * 生产装配：候选解析（`SchemePolicy.smallLlmModel()` 候选链，未配置回退主模型）
   * ＋ **统一内核调用缝**（kernel-spec 批 2026-11-19：遍历/装配/计量全在
   * [[LlmKernel]]，本腿不再自建 `SendMessageParams`、不再自写候选链遍历——
   * K1/K5/K6）。None = 无可用候选（腿不可用）。
   *
   * 🔴 零超时（2026-10-19 作者拍板，「stand 设计不要有任何超时控制——通过监测的
   * 手段」）：本腿**不带分配调用预算**（`KernelCall.timeout = None`）；调用完成与否
   * 由探活面与分配审计表达。
   */
  def fromRegistry(registry: ProviderRegistry): IO[Option[LlmAllocator]] =
    if registry == null then
      IO.pure(None) // registry-absent（降级装配/测试夹具）⇒ 无兜底腿 = 既有行为（SharedResources.assignFallbackChain 同款守卫）
    else
      candidatesFrom(registry).map { cands =>
        if cands.isEmpty then None
        else
          Some(
            new LlmAllocator(
              call => LlmKernel.run(registry, call),
              IO.pure(cands.headOption)
            )
          )
      }

  /**
   * 候选解析（fail-open 根因修复批 2026-10-29）：返回**整条候选链**而非链头一个
   * （实现单点 = [[LlmKernel.candidates]] 的 [[LlmKernel.CandidateSource.SmallLlmModel]]，
   * K7）。
   *
   * 根因（jev-allocation.jsonl 实证，2026-10-29）：旧读法只取链头一个 ——单候选、
   * 单发、败即弃；主 agent 的请求路径走整链（preferred→fallbacks→储备层），于是
   * 链头候选恰好坏掉时「主 agent 正常用 LLM、兜底腿仍 fail-open」。
   */
  private[decision] def candidatesFrom(registry: ProviderRegistry): IO[List[ModelCandidate]] =
    LlmKernel.candidates(registry, LlmKernel.CandidateSource.SmallLlmModel)

  /**
   * 整链遍历调用缝（fail-open 根因修复批 2026-10-29；**kernel-spec 批 2026-11-19
   * 收敛为内核单模板的薄委托**——本腿不再自写遍历，K5）。逐候选 `sendMessage`：
   * 某候选传输/适配层失败 ⇒ WARN 落真因（providerId/model/异常类）＋试下一候选；
   * **全链败才 None**（None 语义 = 「候选链耗尽」）。超时/传输类失败先在同候选重试
   * 一次（缺陷① 2026-11-02）。
   *
   * 🔴 每候选失败必留 WARN——真错不再被 `case Left(_) => None` 吞成「no reply」。
   */
  private[decision] def chainCall(
    registry: ProviderRegistry,
    candidates: List[ModelCandidate]
  ): LlmKernel.KernelCall => IO[Option[LlmKernel.KernelReply]] =
    (call: LlmKernel.KernelCall) => LlmKernel.chainCall(registry, candidates, call)

end LlmAllocator
