package nebflow.core.scheduler

import io.circe.Codec
import io.circe.derivation.{Configuration, ConfiguredCodec}

/**
 * 待办决策模型（待办数据模型与命名规范批 2026-11-18，作者令
 * 「需要决策的是待办 然后每个待办必须要有选项」+ 17:52 追加令
 * 「选项要能填充图片和视频 并且支持多张叠卡显示」）。
 *
 * ── 为什么整块搬到这里 ────────────────────────────────────────────────
 * `TodoDecision` 原先只留在 [[ScheduledTaskModel]] 里、且**全仓零写者**
 * （模型字段存在、序列化在读口在场，但没有任何创建路径能填它）⇒ 本批把
 * 「选项是数据模型的一部分」落成真源：模型 + 校验（[[TodoTaxonomy]]）+ 两条
 * 写侧接线（Schedule 工具面、WS 创建帧）同批落地。
 *
 * ── 线格式（wire）与兼容纪律 ─────────────────────────────────────────
 * 所有 String/Long 字段都带构造器默认值 + `Configuration.default.withDefaults`
 * ——**缺键回默认、未知键容忍**（库内先例 ScheduledTask/TaskListEntry）。这条不是
 * 装饰：`ScheduledTask` 的会话文件是**整档解码**，任何一处非 Option 字段缺键 =
 * 硬失败「Missing required field」= 整个会话的任务静默消失（2026-10-29 实证）。
 * 故本模型任何新增字段一律带默认值。
 *
 * ── 媒体为什么是「引用」而不是内嵌 ────────────────────────────────────
 * 内嵌 base64 会让 `ScheduledTask` 会话文件（整读整写 + AtomicJson 整档换入）
 * 体积失控，且与「文件本体由附件/资产面托管」的既有分工冲突。故媒体 = **引用**：
 * `ref` + `refType` 说明引用种类，字节与寿命归对应解析面所有。本仓另有一支在查
 * 「已发送文件过一会失效」——本模型**不新建第二条寿命假设**：失效一律降级为
 * 「无媒体」，绝不影响待办本身（见 [[TodoTaxonomy.validateDecision]] 不校验可达性
 * 的理由，与 TaskBoard `links` 同一裁决）。
 */

/**
 * 一张媒体的引用（图片 / 视频，一张选项可挂多张 = 叠卡显示）。
 *
 * 全字段带默认值 ⇒ 缺键回默认（本模型任何缺键都不得让整档会话文件解码失败）。
 */
case class DecisionMedia(
  /** 媒体种类：`"image"` | `"video"`（其它值在**写侧**被拒，读侧容忍）。 */
  kind: String = "",
  /**
   * 引用本体（**不是字节**）：
   *   · refType=`"attachment"` ⇒ 附件/资产面的 id（由网关附件面解析）；
   *   · refType=`"path"` ⇒ 设备上的绝对路径（由渲染侧按设备解析）；
   *   · refType=`"url"` ⇒ http(s) 外链（由渲染侧直接取用）。
   */
  ref: String = "",
  /** 引用种类：`"attachment"` | `"path"` | `"url"`（其它值写侧拒、读侧容忍）。 */
  refType: String = "",
  /** MIME（可空——渲染侧据 kind + 扩展名兜底）。 */
  mime: Option[String] = None,
  /** 字节数（可空——未知就是未知，绝不猜）。 */
  bytes: Option[Long] = None,
  width: Option[Int] = None,
  height: Option[Int] = None,
  /** 视频时长（毫秒）。图片恒 None。 */
  durationMs: Option[Long] = None,
  /** 内容散列（可空；填了可供渲染侧判「引用是否已换人」）。 */
  sha256: Option[String] = None,
  /** 叠卡上的说明文字（可空）。 */
  caption: Option[String] = None,
  /**
   * 叠卡位序语义（可空 = 普通一张）：`"cover"` = 叠卡最上面那张（封面）。
   * 一张选项里 cover 至多一张这件事由**渲染侧**决定取第一张即可——写侧不强制
   * （强制会造成「多张都想当封面」的无效拒绝）。
   */
  role: Option[String] = None
)

object DecisionMedia:
  given Configuration = Configuration.default.withDefaults
  given Codec[DecisionMedia] = ConfiguredCodec.derived

  /** kind 值域（写侧唯一来源）。 */
  val KindImage: String = "image"
  val KindVideo: String = "video"
  val Kinds: Set[String] = Set(KindImage, KindVideo)

  /** refType 值域（写侧唯一来源）。 */
  val RefAttachment: String = "attachment"
  val RefPath: String = "path"
  val RefUrl: String = "url"
  val RefTypes: Set[String] = Set(RefAttachment, RefPath, RefUrl)

/**
 * 一个选项（待办决策的可选答案）。
 *
 * 🔴 `title` 是**唯一必填内容面**——媒体全可空（作者 2026-11-18 17:52：
 * 「没有媒体的选项」是合法形态）。渲染侧因此永远可以退化成「只有一行字」，
 * 媒体失效不会让一条待办变成空行。
 */
case class DecisionOption(
  /**
   * 稳定选项 id（幂等键 / 作答回执的定位键）。
   * 不给默认语义上的强约束——`title` 才是人读主键；`id` 空白时由写侧补
   * （见 [[TodoTaxonomy.normalizeDecision]]），读侧容忍空白。
   */
  id: String = "",
  /** 短标题（必填、非空白）——叠卡/列表行上的那一行字。 */
  title: String = "",
  /** 长说明（可空）——展开详情时显示。 */
  detail: Option[String] = None,
  /** 媒体引用（可空、0..N；多张 = 叠卡显示，作者 17:52 令）。 */
  media: List[DecisionMedia] = Nil
)

object DecisionOption:
  given Configuration = Configuration.default.withDefaults
  given Codec[DecisionOption] = ConfiguredCodec.derived

/**
 * 挂在待办上的结构化决策（选项集）。
 *
 * 恒等要求（写侧强制）：`options` 至少一条非空选项（作者令「每个待办必须有选项」）。
 * 存量行 / 无决策行 ⇒ `decision = None`（本字段是 Option，缺键零迁移）。
 * 秘密值绝不落此结构（`secret` 只声明「该答案敏感」，值本身走密钥通道）。
 */
case class TodoDecision(
  /** 问题/待决事项的一句话（可空——`ScheduledTask.content` 已是主文本）。 */
  question: String = "",
  /** 选项集（待办面 必填 ≥1；见 [[TodoTaxonomy.validateCreate]]）。 */
  options: List[DecisionOption] = Nil,
  multiSelect: Boolean = false,
  allowOther: Boolean = false,
  secret: Boolean = false,
  /** 决策截止墙钟（epoch ms）；None = 无截止。 */
  expiresAt: Option[Long] = None
)

object TodoDecision:
  given Configuration = Configuration.default.withDefaults
  given Codec[TodoDecision] = ConfiguredCodec.derived
