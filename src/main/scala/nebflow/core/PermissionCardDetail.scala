package nebflow.core

import io.circe.{Json, JsonObject}
import io.circe.syntax.*
import nebflow.core.tools.BashTool
import nebflow.shared.ToolCall

/**
 * 审批卡的三要素载荷：**命令正文 + 为什么危险 + 作用域**（报障批 2026-11-19）。
 *
 * WHY 单点在这里：作者 2026-11-19 17:30 实拍的控制台卡只有「ⓘ 危险操作：」+
 * 「允许 Bash？」两行 —— 要执行什么命令、为什么算危险、在哪个设备哪个目录，
 * 卡上一个字都没有，用户只能盲批。三要素都是**引擎已经知道的事实**，只是从来没
 * 下发过：正文 = ToolCall 的参数；理由 = 判定闸（[[ToolReversibility.askReason]] /
 * `BashTool.dangerVerdict`）自己的命中规则；作用域 = 执行上下文（项目根 / 目标路径 /
 * 本机设备名）。
 *
 * 纪律：本对象**只投影既有判定**，不做任何新的风险判断（前端更不许自猜）；
 * 每个字符串字段都有界（超长显式标注截断量，绝不静默裁掉）。
 */
object PermissionCardDetail:

  /** 单个字符串参数在卡片正文里的上限（超过显式标注，不静默截断）。 */
  val MaxInlineChars: Int = 4000

  /** 档位 + 命中规则（0 = 未命中任何风险档）。 */
  final case class Level(value: Int, ruleId: String, label: String)

  object Level:
    val Safe: Level = Level(0, "", "")

  /**
   * 工具调用的危险档位（与 `AgentSessionExecution` 改前的 dangerLevel 逐字同源：
   * Bash 走 `BashTool.dangerLevel`，Curl 非只读方法 = 2，其余 = 1）。
   */
  def levelOf(call: ToolCall): Level =
    call.name match
      case "Bash" =>
        call.input("command").flatMap(_.asString) match
          case Some(cmd) =>
            val v = BashTool.dangerVerdict(cmd)
            Level(v.level, v.ruleId, v.label)
          case None => Level.Safe
      case "Curl" =>
        call.input("method").flatMap(_.asString).map(_.toUpperCase) match
          case Some(m) if !Set("GET", "HEAD", "OPTIONS").contains(m) =>
            Level(2, "curl.non-safe-method", s"网络写操作（$m 非只读方法）")
          case _ => Level.Safe
      case _ => Level(1, "", "")

  /**
   * 命令正文：要执行的**实际文本**（Bash = 命令行原文，逐字保留换行；
   * Curl = `METHOD url`；文件类 = 目标路径；其余 = 参数 JSON 的显式摘要）。
   */
  def commandText(call: ToolCall): String =
    call.name match
      case "Bash" =>
        call.input("command").flatMap(_.asString).map(clip).getOrElse {
          val jobId = call.input("background_job_id").flatMap(_.asString).getOrElse("")
          val cancel = call.input("cancel_background_job").flatMap(_.asBoolean).getOrElse(false)
          if jobId.isEmpty then "(无命令文本：本调用只带后台任务参数)"
          else if cancel then s"(取消后台任务 $jobId)"
          else s"(等待后台任务 $jobId 的完成通知)"
        }
      case "Curl" =>
        val m = call.input("method").flatMap(_.asString).map(_.toUpperCase).getOrElse("GET")
        val url = call.input("url").flatMap(_.asString).getOrElse("(无 url 参数)")
        clip(s"$m $url")
      case _ =>
        val target = targetPathOf(call)
        val rest = Json.fromJsonObject(call.input).spaces2
        if target.isEmpty then clip(rest) else clip(target + "\n" + rest)

  /** 文件类操作的目标路径（无 ⇒ 空串）。 */
  def targetPathOf(call: ToolCall): String =
    val keys = List("file_path", "path", "target", "notebook_path")
    keys.flatMap(k => call.input(k).flatMap(_.asString)).find(_.trim.nonEmpty).map(_.trim).getOrElse("")

  /**
   * 卡片载荷（`askPermission` 帧的 `permDetail` 键）。
   *
   * 旧客户端不认识本键 ⇒ 忽略即可（纯加法，缺省不下发时既有帧逐字节不变）。
   * `reason` 取判定闸自己的命中规则；闸没有规则可报（例如写类工具的固定确认档）
   * 时回落该闸的固定标签 —— **任何情况下理由都非空**，因为「出卡」本身就是判定。
   */
  def build(
    call: ToolCall,
    cwd: String,
    mode: SafetyMode,
    reason: Option[ToolReversibility.AskReason],
    isRemoteExec: Boolean,
    deviceName: String
  ): JsonObject =
    val level = levelOf(call)
    val reasonJson = Json.obj(
      "gate" -> Json.fromString(reason.map(_.gate).getOrElse("ToolReversibility")),
      "ruleId" -> Json.fromString(reason.map(_.ruleId).getOrElse("")),
      "label" -> Json.fromString(reason.map(_.label).getOrElse("")),
      "level" -> level.value.asJson,
      "levelRuleId" -> level.ruleId.asJson,
      "levelLabel" -> level.label.asJson,
      "safetyMode" -> SafetyMode.toString(mode).asJson
    )
    val target = targetPathOf(call)
    val scopeJson = Json.obj(
      "device" -> deviceName.asJson,
      "cwd" -> cwd.asJson,
      "target" -> target.asJson,
      "remoteExec" -> isRemoteExec.asJson
    )
    JsonObject(
      "tool" -> call.name.asJson,
      "command" -> commandText(call).asJson,
      "reason" -> reasonJson,
      "scope" -> scopeJson
    )

  end build

  private def clip(s: String): String =
    if s.length <= MaxInlineChars then s
    else s.take(MaxInlineChars) + s"\n…（正文超长，此处展示前 $MaxInlineChars 字/共 ${s.length} 字；完整参数见工具卡）"
end PermissionCardDetail
