package nebflow.llm.decision

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import nebflow.core.jev.{CatalogEntry, JevAllocation, JevFallback, JevFallbackKind}
import nebflow.llm.{ModelCandidate, ProviderRegistry, SendMessageParams}
import nebflow.shared.{Defaults, Message, MessageRole, NebflowLogger}

/**
 * LlmAllocator —— **LLM 兜底分配腿**（jev-plugins 批 #1226；原文「未配 Jev → 新
 * LlmAllocator（单次 LLM JSON 选插件，同语义）」的执行体）.
 *
 * == 与判定机同族（裁定⑦背书）==
 * 分配族 = **Jev 优先＋LLM 兜底**，与 #1224 判定机（SmallJudge）机制同一：
 * Jev 未配置**或 Jev 调用失败** ⇒ 单次 LLM 调用（HealthMonitor.SendMessageParams
 * 模式；模型 = 全局候选链首选；JSON 输出）。执行次序由
 * [[PluginAllocatorMachine]] 编排；本件只做「task + 目录 → 集合」的一条腿。
 *
 * == 「同语义」的落法 ==
 * state 模板逐字复用 JevAllocator 的（preamble ＋ brief ＋ 目录块——`private[decision]`
 * 单源引用，禁第二份拷贝）；成员资格规则 = 同一条 R2 主规则（「任务主目的离开该
 * 能力包即无法正确执行 ⇒ needed」），只是问答形态从 JeV 批量问句换成单次 JSON。
 * 目录序、brief 封顶（12000）与 JevAllocator 完全一致。
 *
 * == 解析纪律 ==
 * 回文取第一个完整 JSON 对象（裸 JSON / ```json 围栏 / 混排皆容——判定机
 * extractJsonObject 同一实现单点复用）；`plugins` 值域 = 字符串数组；**未知包名
 * 剔除**（目录是唯一合法值域——模型幻觉名绝不进注入面），剔除留 WARN；缺键/形错
 * = Failure fail-open（绝不 fabricate 空集成功）。
 *
 * 🔴 提示词为**模型可见文案**——本批最小成稿（§16 待过目，终稿归 N3 提示词批）。
 */
final class LlmAllocator(
  /** 单次调用缝（生产 = registry.getAdapter(...).sendMessage；spec 注 canned 回文）。 */
  singleCall: SendMessageParams => IO[Option[String]],
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
        case Some(c) =>
          val params = SendMessageParams(
            messages = List(Message(MessageRole.User, Left(prompt(taskText, catalog)))),
            model = c.model,
            sessionId = Some(AllocSessionId),
            agentId = Some(AllocSessionId),
            // 辅面钉旧值（钳制批 2026-10-21）：同 HealthMonitor/小判官——
            // ProbeMaxTokens（16384）零行为变化，128000 主面值不上此线。
            maxTokensOverride = Some(Defaults.ProbeMaxTokens)
          )
          singleCall(params).attempt
            .flatMap {
              case Right(Some(reply)) =>
                parsePlugins(reply, catalog) match
                  case Right(names) =>
                    JevFallback
                      .recordSuccess(subject, names, Some(PluginAllocatorMachine.LlmFallbackWire))
                      .as(JevAllocation.Allocated(names))
                  case Left(reason) =>
                    JevFallback.record(JevFallbackKind.Failure, subject, s"llm allocator: $reason")
              case Right(None) =>
                JevFallback.record(JevFallbackKind.Failure, subject, "llm allocator: call failed (no reply)")
              case Left(e) =>
                // 🔴 零超时（2026-10-19 作者拍板）：本腿不再挂分配调用定时器
                // （原 15s `.timeout()` 已拆）——左值只能来自调用缝自身的网络/
                // 传输异常，统一记 Failure（Timeout 分类无进路，不再造）。
                JevFallback.record(JevFallbackKind.Failure, subject, s"llm allocator: ${e.getClass.getSimpleName}")
            }
      }

  /**
   * 模型可见 prompt：state 模板（JevAllocator 单源）＋成员资格规则＋JSON 答令。
   * 🔴 §16 待过目——除 state 模板（既有单源）外的新增文案 = 下两段，逐字报审。
   */
  private def prompt(taskText: String, catalog: List[CatalogEntry]): String =
    val brief =
      if taskText.length > JevAllocator.DefaultStateCapChars then taskText.take(JevAllocator.DefaultStateCapChars)
      else taskText
    s"""${JevAllocator.StatePreamble}

${JevAllocator.BriefHeader}
$brief
${JevAllocator.CatalogHeader}
${JevAllocator.catalogText(catalog)}

Choose the capability packages this task NEEDS, applying exactly the membership rule stated above: count a package as needed only when the task's primary purpose cannot be properly executed without the capability that package provides. Multiple packages may be needed; judge each package independently. If no package is needed, return an empty list.

Answer with ONLY a JSON object: {"plugins": ["<package-name>", ...]}"""

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
   * 生产装配：模型候选解析（`judge.utilityModel` ref → 候选；缺省 = 全局候选链首选
   * ——判定机 utilityCandidate 同口径；判定机件不动，此处为分配族自己的同形装配）。
   * None = 无可用候选（腿不可用）。
   *
   * 🔴 零超时（2026-10-19 作者拍板，「stand 设计不要有任何超时控制——通过监测的
   * 手段」）：本腿**不带分配调用预算**（原 `timeoutMs` 参数与 15s `.timeout()`
   * 定时器杀已拆，不留配置位）；调用完成与否由探活面与分配审计表达。
   */
  def fromRegistry(registry: ProviderRegistry): IO[Option[LlmAllocator]] =
    candidateFrom(registry).map {
      case None => None
      case Some(cand) =>
        val call = (params: SendMessageParams) =>
          registry.getAdapter(cand.providerId).flatMap(_.sendMessage(params)).attempt.map {
            case Right(resp) => Some(resp.reply)
            case Left(_) => None
          }
        Some(new LlmAllocator(call, IO.pure(Some(cand))))
    }

  /** 候选解析（判定机 utilityCandidate 同口径：ref 优先，缺省 = 候选链首选）。 */
  private[decision] def candidateFrom(registry: ProviderRegistry): IO[Option[ModelCandidate]] =
    IO.blocking(nebflow.shared.Config.loadServiceConfig().judge.flatMap(_.utilityModel)).flatMap {
      case Some(ref) =>
        registry.getCandidateForRef(ref).flatMap {
          case Some(c) => IO.pure(Some(c))
          case None =>
            NebflowLogger
              .forName("nebflow.llm.plugin-alloc")
              .warnSync(s"plugin-alloc: judge.utilityModel '$ref' unresolved — falling back to root-chain head")
            registry.getCandidates().map(_.headOption)
        }
      case None => registry.getCandidates().map(_.headOption)
    }

end LlmAllocator
