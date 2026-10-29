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
 * 模式；模型 = 候选链整链遍历——2026-10-29 fail-open 根因修复，见 companion
 * `candidatesFrom`/`chainCall`；JSON 输出）。执行次序由
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
   * 生产装配：模型候选解析（`judge.utilityModel` ref → 候选；缺省 = 全局候选链——
   * 判定机 utilityCandidate 同口径）＋ **整链遍历调用缝**（fail-open 根因修复批
   * 2026-10-29）。None = 无可用候选（腿不可用）。
   *
   * 🔴 零超时（2026-10-19 作者拍板，「stand 设计不要有任何超时控制——通过监测的
   * 手段」）：本腿**不带分配调用预算**（原 `timeoutMs` 参数与 15s `.timeout()`
   * 定时器杀已拆，不留配置位）；调用完成与否由探活面与分配审计表达。
   */
  def fromRegistry(registry: ProviderRegistry): IO[Option[LlmAllocator]] =
    candidatesFrom(registry).map { cands =>
      if cands.isEmpty then None
      else Some(new LlmAllocator(chainCall(registry, cands), IO.pure(cands.headOption)))
    }

  /**
   * 候选解析（fail-open 根因修复批 2026-10-29）：返回**整条候选链**而非链头一个。
   *
   * 根因（jev-allocation.jsonl 实证，2026-10-29）：旧 `candidateFrom` 只取
   * `getCandidates().headOption` ——单候选、单发、败即弃；主 agent 的请求路径走的是
   * 整链遍历（preferred→fallbacks→储备层，`getCandidatesForAgent`），于是链头候选
   * 恰好坏掉时「主 agent 正常用 LLM、兜底腿仍 fail-open」。新读法：
   *  - `judge.utilityModel` ref 已配且处于**不可用时段** → WARN + 落全局链
   *    （model-schedule-unavailable 批时段闸，与本批合并融合：闸语义 = 受限 ref
   *    不进兜底链；回落形状随本批从「链头一个」升为「整条链」——`getCandidates()`
   *    构建出口已 filterNot 受限模型，回落链不含受限候选）；
   *  - ref 已配且可解 → 该候选**钉首位**，其后接全局链去重
   *    （钉住的模型挂掉时链上后备照答——同一句「主 agent 能用 ⇒ 兜底能用」）；
   *  - ref 已配不可解 → 全链（WARN 留痕，原回落语义保留）；
   *  - ref 未配 → 全链（原语义，从「头一个」升为「整条」）。
   */
  private[decision] def candidatesFrom(registry: ProviderRegistry): IO[List[ModelCandidate]] =
    IO.blocking(nebflow.shared.Config.loadServiceConfig().judge.flatMap(_.utilityModel)).flatMap {
      case Some(ref) =>
        // 时段闸（model-schedule-unavailable 批）：utility ref 当前处于不可用
        // 时段 ⇒ WARN + 落到全局链（链出口已滤，不会选到受限模型）。
        registry.scheduleRestrictionFor(ref).flatMap {
          case Some(w) =>
            NebflowLogger
              .forName("nebflow.llm.plugin-alloc")
              .warnSync(
                s"plugin-alloc: judge.utilityModel '$ref' schedule-restricted ($w) — falling back to the full candidate chain"
              )
            registry.getCandidates()
          case None =>
            registry.getCandidateForRef(ref).flatMap {
              case Some(c) =>
                registry.getCandidates().map { chain =>
                  val rest = chain.filterNot(r => r.providerId == c.providerId && r.model == c.model)
                  c :: rest
                }
              case None =>
                NebflowLogger
                  .forName("nebflow.llm.plugin-alloc")
                  .warnSync(s"plugin-alloc: judge.utilityModel '$ref' unresolved — falling back to the full candidate chain")
                registry.getCandidates()
            }
        }
      case None => registry.getCandidates()
    }

  /**
   * 整链遍历调用缝（fail-open 根因修复批 2026-10-29）：逐候选 `sendMessage`——
   * 某候选传输/适配层失败 ⇒ WARN 落真因＋试下一候选；**全链败才 None**（None 的
   * 语义自此 = 「候选链耗尽」，不再是「链头一个挂了」）。与主 agent 请求路径同一
   * 遍历哲学：「主 agent 都在正常使用 llm ⇒ 兜底腿必有候选能答」（作者 2026-10-29
   * 追问的机制面回答）。
   *
   * 🔴 每候选失败必留 WARN（providerId/model/异常类）——审计可归因，真错不再被
   * `case Left(_) => None` 吞成「no reply」。
   */
  private[decision] def chainCall(
    registry: ProviderRegistry,
    candidates: List[ModelCandidate]
  ): SendMessageParams => IO[Option[String]] =
    val log = NebflowLogger.forName("nebflow.llm.plugin-alloc")
    (params: SendMessageParams) =>
      candidates.foldLeft[IO[Option[String]]](IO.pure(None)) { (acc, cand) =>
        acc.flatMap {
          case Some(reply) => IO.pure(Some(reply))
          case None =>
            registry.getAdapter(cand.providerId).attempt.flatMap {
              case Left(e) =>
                IO(
                  log.warnSync(
                    s"llm allocator: candidate ${cand.providerId}/${cand.model} adapter unavailable (${e.getClass.getSimpleName}) — trying next candidate"
                  )
                ).as(None)
              case Right(adapter) =>
                adapter
                  .sendMessage(params.copy(model = cand.model))
                  .attempt
                  .flatMap {
                    // 空回文（HTTP 200 但 body 空）= provider 侧故障，与传输错同侧
                    // 处理：试下一候选，不落 parse-fail（parse-fail 只收「有回文但
                    // 不可用」的模型行为面）。
                    case Right(resp) if resp.reply.trim.isEmpty =>
                      IO(
                        log.warnSync(
                          s"llm allocator: candidate ${cand.providerId}/${cand.model} replied empty — trying next candidate"
                        )
                      ).as(None)
                    case Right(resp) => IO.pure(Some(resp.reply))
                    case Left(e) =>
                      IO(
                        log.warnSync(
                          s"llm allocator: candidate ${cand.providerId}/${cand.model} errored (${e.getClass.getSimpleName}) — trying next candidate"
                        )
                      ).as(None)
                  }
            }
        }
      }

end LlmAllocator
