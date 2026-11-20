package nebflow.shared

import cats.effect.IO

import java.util.concurrent.atomic.AtomicReference

/** 条件依赖：仅当 ref 问题的答案等于 equals 时才显示此问题 */
case class QuestionDependency(ref: String, equals: String)

/**
 * 选项内嵌预览里的**一件媒体**（askuser-media 批 2026-11-18 · 作者令
 * 「要能填充图片和视频 并且支持多张叠卡显示」）。
 *
 * 形态 = **消息面媒体件的同一形状**（`popArtifacts.js` 的 pop-item：
 * `{kind, src, path, name}`）——「同源」不是比喻：前端把本 case class 直接喂给
 * 消息面既有的叠卡组件 `buildMediaStack`，零第二套媒体模型、零第二套叠卡几何。
 *
 *  - `kind`：`"image"` | `"video"`（其余值前端按无预览裁决，解析侧宽松透传）。
 *  - `src`：**两种来源**都要支持——远端 URL（`https://…` / `data:`）直取；
 *    本地路径（`/abs/x.png` 或 `~/x.png`）由前端走既有票腿（/api/nf-file）。
 *    判据在前端单点（[[askPreviewMedia.js]]）：带 scheme 视作 URL，否则视作路径。
 *  - `path`：显式本地绝对路径（给了就用它走票腿，`src` 作为直显 URL 腿仍优先）。
 *  - `poster`（仅 video）：静帧封面图（远端 URL / 本地路径同 `src` 口径）。
 *  - `name`：显示名（alt / 失败注记行用）。
 */
case class AskPreviewMedia(
  kind: String,
  src: Option[String] = None,
  poster: Option[String] = None,
  name: Option[String] = None,
  path: Option[String] = None
)

/**
 * 选项内嵌预览（askuser-canvas 方向 C §2.1；askuser-media 批 2026-11-18 扩容）。
 * 前端在选项卡内渲染；type 未知 / colors 为空 / 无可渲染媒体时前端按**无预览**
 * 渲染（零回归），因此解析保持宽松透传、由前端裁决。
 *
 * 四种形态（**严格向后兼容**——前两种是改前既有形态，字段/语义逐字不变）：
 *   ① `type = "swatch"` + `colors`：1~5 条色带（既有）。
 *   ② `type = "image"`  + `src`：单张图片缩略（既有）。
 *   ③ `type = "video"`  + `src` [+ `poster`]：单段视频（静帧 + 可播放）。
 *   ④ `type = "media"`  + `items`：**媒体组**——≥2 件时以**叠卡**呈现（前一张
 *      完整 + 后面几张露边 + 可切换/展开），1 件时退化为单件面。
 *
 * 「媒体组」是本批对外的**稳定数据形态**（另三支在飞批次复用同一形状，见报告）。
 */
case class AskPreview(
  `type`: String,
  colors: Option[List[String]] = None,
  src: Option[String] = None,
  /** 仅 `type = "video"`：静帧封面图。 */
  poster: Option[String] = None,
  /** 仅 `type = "media"`：媒体组（≥1 件；空组等同无预览）。 */
  items: Option[List[AskPreviewMedia]] = None
)

/** 提问项 */
case class AskItem(
  question: String,
  options: List[AskOption],
  allowOther: Boolean = true,
  id: Option[String] = None,
  dependsOn: Option[QuestionDependency] = None,
  /** true = 多选题（可勾选多个选项，答案为数组）；缺省 false = 单选，行为不变 */
  multiple: Boolean = false,
  /** 可选：问题出现时自动 Pop 到 Canvas 面板的对比页绝对路径（方向 C §2.1） */
  canvas: Option[String] = None,
  /**
   * true = 工作区目录选择卡（2026-09-05 作者裁定）：前端渲染「选择工作区」大目标，
   * 点击 → 应用内目录浏览器（workspacePicker.js，2026-09-06 拍板）。2026-09-09
   * 作者裁定原句保留可读：「options 下发空列表（无候选 chips、无「其他…」），
   * 选择面 = 目录浏览器或自由输入（支持 ~，后端负责展开/绝对化校验）」——其中
   * **「自由输入」一条已被 2026-09-17 作者裁定取代**（见下方 freeInput：dirPicker 卡
   * 不提供手输面，选择面 = 目录浏览器；退役的是前端手输面，后端 expandTilde /
   * parsePanelAnswer 的绝对化校验保留，供降级兜底 / legacy / CLI 面使用）。
   * 「不下发候选 options」一条继续成立、禁改。缺省 false = 行为字节不变。
   */
  dirPicker: Boolean = false,
  /**
   * 2026-09-17 作者裁定（S3 ②-7 协议收敛；取代 2026-09-09 的「空 options 使下方
   * 自由输入 textarea 直接可见」一条）：false = 本卡不提供自由输入面——前端不渲染
   * textarea、跳过 localStorage 草稿恢复，答案只由选择面（目录浏览器）写入。
   * 缺省 true = 逐字节现状（字段缺失的旧载荷行为不变）。dirPicker 卡由发起方显式置
   * false；选择面不可用（动态 import 拒绝 / 目录列表超时或 error）时前端按需揭示
   * 降级输入面并聚焦，答案仍能成功上送（②-6 禁死路）。
   */
  freeInput: Boolean = true,
  /**
   * secret 型 AskUser（设计规格 §1，2026-10-08 批）：true = 本题渲染**密码框**而非
   * 选项/自由输入——用户的值只走「卡片密码框 → 专用 WS 键（secretSlots）→ 网关直写
   * secrets（0600 F1 写序）」这一条路，请求方（Agent）永远只收 keyRef 指针回执
   * （`{stored:true, keyRef, location, variableName}`），**从不收值**。缺省 false =
   * 逐字节现状（字段缺席的旧载荷行为不变）。与 options/dirPicker/canvas 互斥
   * （解析侧裁定：secret 胜出——options 剥除、dirPicker/canvas 题整条跳过，
   * 见 `AskUserQuestionTool.parseItems`）。
   */
  secret: Boolean = false,
  /**
   * secret 题的落盘名（过 `SecretsStore.NamePattern` 校验）；缺省由网关派生
   * `askuser-<requestId>-<题序>`。非法名不炸整卡——落 `stored:false` 回执。
   */
  secretName: Option[String] = None,
  /**
   * secret 题的**验证码通道**：true = 一次性值——默认 TTL 10 分钟，读取一次即
   * 消费（原子 rename-out），用于 OTP/2FA 码而非长期密钥。缺省 false。
   */
  ephemeral: Boolean = false,
  /**
   * ephemeral 题的存活秒数（钳位 10–86400；缺省 600 = 10 分钟）。非 ephemeral
   * 题忽略。
   */
  ttlSec: Option[Int] = None,
  /**
   * secret 题的**可查询元数据**（可查询批，作者令 2026-11-18 21:31「新建 secret
   * 时可带说明与标签」）：说明（这道题问的是什么凭据、干什么用）与关键词标签，
   * 落库时随值一起写进 `secrets-meta.json` 侧车——于是这次落库的密钥**从诞生起
   * 就可被查询**（`stand secrets show <name>` / `stand secrets list --tag …`）。
   *
   * 🔴 零值字段：两者描述的都是「这个凭据是干什么用的」，与值无关，也不会被
   * 任何读面拼进值文件；侧车本身也从不承载值。非 secret 题忽略。
   */
  secretDescription: Option[String] = None,
  secretTags: List[String] = Nil
)

case class AskOption(
  label: String,
  description: Option[String] = None,
  /** 可选：选项内嵌预览（swatch / image），方向 C §2.1 */
  preview: Option[AskPreview] = None
)

/** 用户按 Esc 中断 */
class UserAbort extends Exception("UserAbort")

object AskUser:
  private val handler = new AtomicReference[Option[List[AskItem] => IO[List[String]]]](None)

  def setHandler(h: List[AskItem] => IO[List[String]]): Unit =
    handler.set(Some(h))

  def clearHandler(): Unit =
    handler.set(None)

  def ask(items: List[AskItem]): IO[List[String]] =
    handler.get() match
      case Some(h) => h(items)
      case None => IO.raiseError(new Exception("AskUser not available in non-interactive mode"))

  def isInteractive: Boolean = handler.get().isDefined
end AskUser

/**
 * 交互派发失败信号 —— AskUser 派发腿在**承接面缺席**时给出的返回值。
 *
 * 语境：`AgentProcessing` 的 `AskUser` 分支在 `interactionHubRef` 缺席（early
 * boot / headless / harness）时无法把问题交给任何承接面 ⇒ 问题从未上卡、答复
 * 永不投达。该腿对 `replyTo` 的返回值**不得**与「用户没有作答」同形（空列表 /
 * 空文本）：两个订阅腿都据该返回值作语义判定 ——
 *
 *  - `AskUserAnswerBridge`（工具面非阻塞腿）：**不**把它当用户答复注入会话
 *    （否则会话里会凭空多出一条空用户消息）；
 *  - `ProjectTool.parsePanelAnswer`（面板阻塞腿）：**不**把它当「用户关闭了
 *    面板」（`Shelved` 搁置），而是给出明确报错。
 *
 * 判据本体（「这是不是失败信号」）只在本对象内实现一处，两个订阅点一律委托
 * （禁第二份副本，与全仓「一处实现、消费点委托」的纪律同源）。
 */
object AskUserDispatch:

  /** 机器可读锚：失败类别 = 承接面缺席（无 InteractionHub 可用）。 */
  val UnavailableCode = "ASKUSER_DISPATCH_UNAVAILABLE"

  /** 人可读判读起点；机器判据（[[isFailure]]）亦以它为准。 */
  val FailureMarker = s"AskUser dispatch FAILED ($UnavailableCode)"

  /**
   * 承接面缺席的失败信号（**单槽**）。自描述：失败类别 + requestId +
   * sourceAgent + 后果（答案永不投达）+ 与用户答复的判别句。
   */
  def unavailable(requestId: String, sourceAgent: String): List[String] =
    List(
      s"$FailureMarker: no interaction hub is available, so this question was never shown and no " +
        s"answer can arrive — requestId=$requestId sourceAgent=$sourceAgent. " +
        s"This is a dispatch failure, not a user answer."
    )

  /**
   * 是否派发失败信号。空载荷（`Nil`）、空白文本与常规答复一律 `false` ——
   * 失败信号必须与「用户没答」在**判据上**可分离，否则本信号退化成同一团模糊。
   */
  def isFailure(answers: List[String]): Boolean =
    answers match
      case signal :: Nil => signal.startsWith(FailureMarker)
      case _ => false

end AskUserDispatch

/**
 * AskUser **取消信号**（askucancel 批，2026-10-08）—— hub `CancelRequest` 在终结
 * 槽位时经**既有答复管道**（`InteractionReply.AskUserReply.replyTo`）投回请求方的
 * 明确结论。与 [[AskUserDispatch]] 同一形态族（单槽自描述文本 + 单点判据），但
 * 语义相反：它**是**要注入会话的（请求方必须知道「不会有答案了」），而派发失败
 * 信号明确**不**注入。
 *
 * 两个消费点一律委托本对象（禁第二份副本，与全仓「一处实现、消费点委托」纪律同源）：
 *  - `AskUserAnswerBridge.deliver`（非阻塞桥腿）：注入取消通告（`fromUser=false`，
 *    带 `askUserCancelled` 来源标注——它不是用户答复，不能伪装成 user 气泡）；
 *  - `ProjectTool.parsePanelAnswer`（阻塞面板腿）：判 `Shelved`（用户撤回 ≙ 既有
 *    `__cancelled__` 哨兵的落点，但走**可判读信号**而非伪造答案）。
 */
object AskUserCancelled:

  /** 机器可读锚：信号类别 = 用户发起的取消。 */
  val Code = "ASKUSER_CANCELLED"

  /** 人可读判读起点；机器判据（[[isCancelled]]）亦以它为准。 */
  val Marker = s"AskUser CANCELLED ($Code)"

  /**
   * 取消信号（**单槽**）。自描述：类别 + requestId + 取消方 + 原因 + 后果（答案
   * 永不投达）+ 与用户答复的判别句。
   */
  def signal(requestId: String, reason: String, by: String): List[String] =
    List(
      s"$Marker: requestId=$requestId reason=$reason by=$by — the question was withdrawn before an " +
        s"answer arrived, so no answer will be delivered; proceed with your best judgment. " +
        s"This is a cancellation notice, not a user answer."
    )

  /**
   * 是否取消信号。空载荷（`Nil`）、空白文本与常规答复一律 `false` —— 判据形态与
   * [[AskUserDispatch.isFailure]] 逐字同构（单槽 + 前缀匹配）。
   */
  def isCancelled(answers: List[String]): Boolean =
    answers match
      case signal :: Nil => signal.startsWith(Marker)
      case _ => false

end AskUserCancelled

/**
 * AskUser **答复附件清单槽**（askuser-dragdrop 实施批 2026-10-19，批 #551）——
 * 网关 `askUserAnswer` 帧携带的 `attachments`（用户随卡回传的已落盘文件引用）经
 * hub `complete` 沿**既有答复管道**（`AskUserReply.replyTo`，`ActorRef[List[String]]`）
 * 投回请求方时的搭载体。
 *
 * 形态与 [[AskUserDispatch]] / [[AskUserCancelled]] 同一信号族（「一条判据 + 单点
 * 解析，禁第二份副本」），差别只在**不替换**答复槽：清单以**尾随追加槽**的形式
 * 与正常答复同投（`answers :+ manifest`）——
 *   · `AskUserQuestionTool.formatAnswer`（桥腿格式化）按 `items.zipWithIndex.lift`
 *     读槽，尾随的多余槽天然被忽略；
 *   · `ProjectTool.parsePanelAnswer`（面板阻塞腿）只读 `answers.head`，同样无感；
 *   · `SendConfirm` 的确认链槽位**永远不会**携带清单（其答复载荷无 attachments 键，
 *     hub 只在 kind=AskUser 且清单非空时追加）。
 *
 * 消费点 = `AskUserAnswerBridge.deliver`（唯一）：剥下清单槽 → 附件清单文本注入 +
 * 图片子集 vision 块注入（≤5 张 / ≤10MB，对齐 `ImageInject` 既有上限）。
 */
object AskUserAnswerAttachments:

  /** 机器可读锚（清单槽 JSON 的唯一顶层键，判据起点）。 */
  val Key = "askUserAnswerAttachments"

  /** 清单槽前缀（槽串 = `{"askUserAnswerAttachments":[…]}`，前缀匹配即可判）。 */
  val Prefix: String = s"""{"$Key":"""

  /**
   * 构造**尾随清单槽**：清单为空 ⇒ `Nil`（答复列表逐字节同形，无清单零改动）；
   * 非空 ⇒ 单槽紧凑 JSON。调用点 = hub `complete`（唯一生产者）。
   */
  def manifestSlot(attachments: List[io.circe.Json]): List[String] =
    if attachments.isEmpty then Nil
    else List(io.circe.Json.obj(Key -> io.circe.Json.fromValues(attachments)).noSpaces)

  /**
   * 是否清单槽（单点判据，消费点一律委托）。只对**尾随**槽有意义；调用方
   * （[[take]]）保证只检查最后一个元素。
   */
  def isManifestSlot(slot: String): Boolean = slot.startsWith(Prefix)

  /**
   * 剥取尾随清单槽：`(纯答复槽, 附件清单)`。无清单（空 / 不可解析）⇒
   * `(原列表, Nil)` —— 旧信号（取消 / 派发失败）与无附件答复逐字节透传。
   */
  def take(answers: List[String]): (List[String], List[io.circe.Json]) =
    answers.lastOption.filter(isManifestSlot) match
      case None => (answers, Nil)
      case Some(slot) =>
        val parsed = io.circe.parser
          .parse(slot)
          .toOption
          .flatMap(_.hcursor.downField(Key).as[List[io.circe.Json]].toOption)
          .getOrElse(Nil)
        (answers.dropRight(1), parsed)

end AskUserAnswerAttachments
