package nebflow.core.profile

import io.circe.Codec
import io.circe.derivation.{Configuration, ConfiguredCodec}

/**
 * 方案档案（认知二期产品批，设计锚 docs/design/20261030_human-readable-
 * presentation.zh-CN.md §6.2 五档参数表）：一组呈现参数的命名档。agent 按档
 * 下笔，同一任务在不同档下呈现不同；档集枚举与参数差异已获作者裁定
 * （§9-9，2026-10-30 14:43 五档照单）。
 *
 * 参数全集即档定义（§6.2 表注）；扩参数（配色/字号等）再议（§9-11）。
 *
 * @param foldDefault 折叠默认（前端 ```human 过滤层渲染消费）：
 *   "fold"（框外折叠）| "all-fold"（全折叠）| "strong"（强折叠只留结论卡）|
 *   "anomaly-top"（折叠，异常条目置顶）| "expand-findings"（Findings 默认展开——
 *   唯一不折档，备考冲刺档独占，P5 教学引导层唯一例外档）。
 * @param imageDensity 图密度："high" | "medium" | "low"（人面子下笔义务，视觉锚点）。
 * @param reminderCadence 提醒节奏："single"（单任务轻提醒）| "aggregate"（聚合汇总）|
 *   "quiet"（免打扰窗口/仅阻塞时提醒）——映射 Schedule 待办浮出策略。
 * @param supervision 监督颗粒度："fine"（日打卡小步拆解）| "weekly"（周级自报）|
 *   "coarse"（只看卡）| "exception"（异常才报）——计划跟踪拆解粒度。
 * @param pendingStyle 未决项呈现："single"（单条带默认建议）| "batch"（攒批一次给）|
 *   "none"（卡上不给，抽屉里）| "blocking"（只给阻塞类）| "deadline"（给，带截止日）。
 * @param teachingAllowed 教学引导层（P5 专家反转红线：仅备考冲刺档允许）。
 */
case class PresentationProfile(
  key: String,
  /** 人面子长度上限（行）。 */
  maxLines: Int,
  /** 关键数字上限（个，Cowan 4±1 锚点）。 */
  keyNumbers: Int,
  foldDefault: String,
  imageDensity: String,
  reminderCadence: String,
  supervision: String,
  pendingStyle: String,
  teachingAllowed: Boolean
)

object PresentationProfile:

  // 五档内置定义（§6.2 表逐项产品化；顺序 = 表列序）。
  val AdhdFriendly: PresentationProfile = PresentationProfile(
    key = "adhd-friendly",
    maxLines = 8,
    keyNumbers = 4,
    foldDefault = "fold",
    imageDensity = "high",
    reminderCadence = "single",
    supervision = "fine",
    pendingStyle = "single",
    teachingAllowed = false
  )

  val DeepWork: PresentationProfile = PresentationProfile(
    key = "deep-work",
    maxLines = 4,
    keyNumbers = 3,
    foldDefault = "all-fold",
    imageDensity = "low",
    reminderCadence = "quiet",
    supervision = "weekly",
    pendingStyle = "batch",
    teachingAllowed = false
  )

  val QuickBrowse: PresentationProfile = PresentationProfile(
    key = "quick-browse",
    maxLines = 3,
    keyNumbers = 4,
    foldDefault = "strong",
    imageDensity = "medium",
    reminderCadence = "aggregate",
    supervision = "coarse",
    pendingStyle = "none",
    teachingAllowed = false
  )

  val ExpertMinimal: PresentationProfile = PresentationProfile(
    key = "expert-minimal",
    maxLines = 3,
    keyNumbers = 2,
    foldDefault = "anomaly-top",
    imageDensity = "low",
    reminderCadence = "quiet",
    supervision = "exception",
    pendingStyle = "blocking",
    teachingAllowed = false
  )

  val ExamSprint: PresentationProfile = PresentationProfile(
    key = "exam-sprint",
    maxLines = 6,
    keyNumbers = 5,
    foldDefault = "expand-findings",
    imageDensity = "high",
    reminderCadence = "single",
    supervision = "fine",
    pendingStyle = "deadline",
    teachingAllowed = true
  )

  /** 五档全集（唯一枚举源，校验/下发/UI 均消费此表）。 */
  val All: List[PresentationProfile] =
    List(AdhdFriendly, DeepWork, QuickBrowse, ExpertMinimal, ExamSprint)

  /** 作者裁定默认值（§6.2 表注「默认=专家极简偏快速浏览」的落点：专家极简档）。 */
  val DefaultKey: String = ExpertMinimal.key

  def find(key: String): Option[PresentationProfile] =
    All.find(_.key == key.trim)

  /** 可覆盖参数的取值域（档参数逐项覆盖的校验面；key/teachingAllowed 不可覆盖）。 */
  val FoldValues: Set[String] =
    Set("fold", "all-fold", "strong", "anomaly-top", "expand-findings")
  val ImageDensityValues: Set[String] = Set("high", "medium", "low")
  val ReminderCadenceValues: Set[String] = Set("single", "aggregate", "quiet")
  val SupervisionValues: Set[String] = Set("fine", "weekly", "coarse", "exception")
  val PendingStyleValues: Set[String] =
    Set("single", "batch", "none", "blocking", "deadline")

  /** 数值参数（正整数）上界：防呆写入，不替代作者裁定表。 */
  val MaxLinesBound: Int = 20
  val KeyNumbersBound: Int = 9

  /**
   * 覆盖参数校验（可行动报错，错因明说）：返回 None = 合法，Some(原因) = 拒。
   * 纯函数——spec 钉。
   */
  def validateOverride(param: String, value: String): Option[String] =
    val p = param.trim
    val v = value.trim
    if p == "maxLines" then
      v.toIntOption.filter(n => n > 0 && n <= MaxLinesBound) match
        case Some(_) => None
        case None => Some(s"maxLines must be an integer in 1..$MaxLinesBound")
    else if p == "keyNumbers" then
      v.toIntOption.filter(n => n > 0 && n <= KeyNumbersBound) match
        case Some(_) => None
        case None => Some(s"keyNumbers must be an integer in 1..$KeyNumbersBound")
    else if p == "foldDefault" then
      if FoldValues(v) then None else Some(s"foldDefault must be one of ${FoldValues.toList.sorted.mkString(", ")}")
    else if p == "imageDensity" then
      if ImageDensityValues(v) then None else Some(s"imageDensity must be one of ${ImageDensityValues.toList.sorted.mkString(", ")}")
    else if p == "reminderCadence" then
      if ReminderCadenceValues(v) then None else Some(s"reminderCadence must be one of ${ReminderCadenceValues.toList.sorted.mkString(", ")}")
    else if p == "supervision" then
      if SupervisionValues(v) then None else Some(s"supervision must be one of ${SupervisionValues.toList.sorted.mkString(", ")}")
    else if p == "pendingStyle" then
      if PendingStyleValues(v) then None else Some(s"pendingStyle must be one of ${PendingStyleValues.toList.sorted.mkString(", ")}")
    else Some(s"unknown override param \"$p\" — overridable: maxLines, keyNumbers, foldDefault, imageDensity, reminderCadence, supervision, pendingStyle")

  end validateOverride

  given Configuration = Configuration.default.withDefaults
  given Codec[PresentationProfile] = ConfiguredCodec.derived

end PresentationProfile
