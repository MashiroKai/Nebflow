package nebflow.core.profile

import io.circe.Codec
import io.circe.derivation.{Configuration, ConfiguredCodec}

/**
 * 呈现校准信号与 calibrate 钩子（呈现档入口改造+围栏语义批，作者令
 * 2026-10-30 20:13）：日常使用自适应的**数据面**。本批只立信号采集
 * 与 calibrate 钩子——auto 调整逻辑下批再做（设计稿 §6.3 抱怨校准路的
 * 产品化前置）。
 *
 * 信号三类（作者令 ②）：
 *   - profile-switch：哪档被换（from → to，含 onboarding 选档与设置面板
 *     次级入口，两路同帧 rides setPresentationProfile，引擎侧统一采集）；
 *   - drawer-expanded：框外折叠抽屉被展开（前端 ```human 渲染面用户信号，
 *     「给我看细节」类行为证据的呈现面等价物）；
 *   - complaint：「太长」类抱怨——用户聊天文本命中信号词表（§6.3 词表
 *     产品化），rule 键即词表条目。
 *
 * [[classifyComplaint]] / [[ProfileCalibrator.suggest]] 为纯函数——spec 钉；
 * 不依赖任何引擎组件，下批 auto 调整直接消费 suggest 产出。
 */
object ProfileCalibrationSignal:

  /** 单条校准信号（持久化单元）。data 为键值负载（from/to/rule/detail…），
   * 键集随信号类演化，解码宽容（缺键不炸面）。 */
  final case class Signal(
    ts: Long,
    kind: String,
    data: Map[String, String] = Map.empty
  )

  object Signal:
    given Configuration = Configuration.default.withDefaults
    given Codec[Signal] = ConfiguredCodec.derived

  // 已知信号类（WS recordCalibrationSignal 帧的 kind 校验面；complaint 只走
  // 引擎侧聊天腿，不接受前端直写——抱怨判定权在引擎分类器，前端只报行为）。
  val Kinds: Set[String] = Set("profile-switch", "drawer-expanded")

  // ── 「太长」类抱怨信号词表（设计稿 §6.3 校准信号词表一期草案的产品化）──
  // 每条：rule 键 → (匹配正则, 目标参数, 调整方向)。命中即 complaint 信号。
  private val ComplaintRules: List[(String, scala.util.matching.Regex, String, String)] = List(
    ("too-long", "太[长啰嗦]|简洁点|简短点|短一点|精简点?|少写点".r, "maxLines", "down"),
    ("too-frequent", "别烦我|勿扰|别打扰|免打扰|安静点?|别催".r, "reminderCadence", "down"),
    ("need-detail", "给我看细节|要看细节|展开细节|更多细节|别折叠".r, "foldDefault", "relax"),
    ("no-teaching", "别教我|不用教|别指导|别科普".r, "teachingAllowed", "off")
  )

  /** detail 负载上限：抱怨原文截断落账（防整条长消息塞爆校准文件）。 */
  val DetailMaxChars: Int = 200

  /**
   * 用户聊天文本 → 抱怨信号 rule 键（纯函数；None = 非抱怨）。正则忽略大小写；
   * 多条命中取词表序首条（词表即优先级）。只对真人来源文本调用——调用点
   * （dispatchUserText）已带 fromUser 语义。
   */
  def classifyComplaint(text: String): Option[String] =
    if text == null then None
    else
      val t = text.toLowerCase
      ComplaintRules.find { case (_, re, _, _) => re.findFirstIn(t).nonEmpty }.map(_._1)

  /** 词表反查：rule 键 → (目标参数, 调整方向)（calibrate 钩子的解释面）。 */
  def complaintTarget(rule: String): Option[(String, String)] =
    ComplaintRules.find(_._1 == rule).map { case (_, _, param, dir) => (param, dir) }

end ProfileCalibrationSignal

/**
 * calibrate 钩子（本批立口，下批接 auto 调整）：把信号聚合翻译成**建议**——
 * 不直接改档。建议三件套 = 目标参数 + 建议值 + 证据计数；触发阈值同 rule
 * 累计 ≥2（防单次噪音直接驱动调整，§6.3「改动量小、可撤销」的保守面）。
 * 纯函数——spec 钉；引擎本批不调用（无 auto 调整），下批接调用点时行为已钉。
 */
object ProfileCalibrator:

  import ProfileCalibrationSignal.Signal

  final case class Suggestion(param: String, value: String, evidence: Int, rule: String)

  /** 同 rule 触发阈值（≥2 条同类信号才出建议）。 */
  val TriggerThreshold: Int = 2

  /**
   * 信号列表 + 当前档 → 建议列表（纯函数）。只消费 complaint 信号（profile-switch
   * 是选档事实不是抱怨；drawer-expanded 下批与 foldDefault 放宽联动时再入聚合，
   * 本批采集不落判定——避免行为推断嫌疑，§6.3 行为推断永不做裁定不被波及）。
   */
  def suggest(signals: List[Signal], current: PresentationProfile): List[Suggestion] =
    val complaints = signals.filter(_.kind == "complaint")
    complaints
      .flatMap(s => s.data.get("rule").map(r => (r, s)))
      .groupBy(_._1)
      .toList
      .flatMap { case (rule, group) =>
        val count = group.length
        if count < TriggerThreshold then None
        else
          ProfileCalibrationSignal.complaintTarget(rule).flatMap { (param, dir) =>
            suggestValue(param, dir, current).map(v => Suggestion(param, v, count, rule))
          }
      }
      .sortBy(_.param)

  /** 参数 + 方向 → 建议值（None = 已到极限/不适用，不出建议）。 */
  private def suggestValue(param: String, dir: String, cur: PresentationProfile): Option[String] =
    (param, dir) match
      case ("maxLines", "down") =>
        // 降一档 = 向五档内置最小值收一格（不小于 1）。
        Some(math.max(1, cur.maxLines - 1).toString)
      case ("reminderCadence", "down") =>
        // 降频序：single → aggregate → quiet（已在 quiet = 极限，None）。
        val order = List("single", "aggregate", "quiet")
        val idx = order.indexOf(cur.reminderCadence)
        if idx < 0 || idx >= order.length - 1 then None else Some(order(idx + 1))
      case ("foldDefault", "relax") =>
        // 放宽 = 向「更展开」收一格：strong → fold（框外折叠但段内直出语义
        // 移交下批渲染面）；已是 fold/更松 = None。
        if cur.foldDefault == "strong" then Some("fold") else None
      case ("teachingAllowed", "off") =>
        // 引导层关闭 = 确认项：建议值恒 false；当前已 false 则无需建议。
        if cur.teachingAllowed then Some("false") else None
      case _ => None

end ProfileCalibrator
