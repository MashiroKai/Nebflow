package nebflow.core

import cats.effect.IO

import java.util.concurrent.atomic.AtomicReference

/** 条件依赖：仅当 ref 问题的答案等于 equals 时才显示此问题 */
case class QuestionDependency(ref: String, equals: String)

/** 选项内嵌预览（askuser-canvas 方向 C §2.1）：swatch 色板或图片缩略图。
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
  /** true = 工作区目录选择卡（2026-09-05 作者裁定）：前端渲染「选择工作区」大目标，
    * 点击 → 应用内目录浏览器（workspacePicker.js，2026-09-06 拍板）。2026-09-09
    * 作者裁定：options 下发空列表（无候选 chips、无「其他…」），选择面 = 目录浏览器
    * 或自由输入（支持 ~，后端负责展开/绝对化校验）。缺省 false = 行为字节不变。 */
  dirPicker: Boolean = false
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
