package nebflow.shared

import cats.effect.IO

import java.util.concurrent.atomic.AtomicReference

/** 条件依赖：仅当 ref 问题的答案等于 equals 时才显示此问题 */
case class QuestionDependency(ref: String, equals: String)

/**
 * 选项内嵌预览（askuser-canvas 方向 C §2.1）：swatch 色板或图片缩略图。
 * 前端在 56×40 槽内渲染；type 未知 / colors 为空 / src 缺失时前端按无预览
 * 渲染（零回归），因此解析保持宽松透传、由前端裁决。
 */
case class AskPreview(
  `type`: String,
  colors: Option[List[String]] = None,
  src: Option[String] = None
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
  freeInput: Boolean = true
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
 *  - `ProjectCreateTool.parsePanelAnswer`（面板阻塞腿）：**不**把它当「用户关闭了
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
      case _             => false

end AskUserDispatch
