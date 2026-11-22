package nebflow.core.tools

import cats.effect.IO
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.core.desktop.{DesktopObserve, DesktopPolicy}

/* ═══════════════════════════════════════════════════════════════════════════
 * desktop-observe-p0（2026-11-22）· 桌面观测六动词一等工具面（只读，写类不做）
 *
 * desktop_list_apps / desktop_list_windows / desktop_screenshot / desktop_clipboard_read /
 * desktop_open（后台启动） / desktop_displays。执行体 = nebflow.core.desktop.DesktopObserve。
 *
 * 授权面（单点，B 形态，同 browser-verbs-p0）：名字进 AgentCore.RootExclusiveTools（防声明
 * 逃逸）+ 挂载面 = AgentSessionExecution.buildAllowedToolSet 尾段 DesktopObserveTools 腿
 * （Nebula 根 / 非 kernel 形态 delegate 会话）。工具内侧双保险：DesktopPolicy.callerAllowed。
 *
 * 宿主保护：名单命中（nebflow/nebula/stand/sbt/java/openjdk）的应用/窗口在清单里剔除；
 * 指名宿主应用的截图与启动 → HOST-PROTECTED，不发起任何系统调用。
 * ═══════════════════════════════════════════════════════════════════════════ */

/** 六动词共用基座：身份闸 → 参数校验 → 阻塞执行 → 错误收口为 ToolError。 */
abstract class DesktopVerbTool extends Tool:

  override def requiresTrustedExecutionIdentity: Boolean = true

  override def summarizeResult(input: JsonObject, result: String): String =
    if result.length > 160 then result.take(157) + "..." else result

  protected def str(input: JsonObject, key: String): Option[String] =
    input(key).flatMap(_.asString).map(_.trim).filter(_.nonEmpty)

  protected def guarded(ctx: ToolContext)(body: IO[Either[String, String]]): IO[Either[ToolError, String]] =
    if !DesktopPolicy.callerAllowed(ctx.isNebulaRoot, ctx.sessionId) then
      IO.pure(
        Left(
          ToolError(
            s"$name: permission denied — desktop observe verbs are exclusive to the Nebula root and delegate sessions."
          )
        )
      )
    else
      body.attempt.map[Either[ToolError, String]] {
        case Right(Right(out)) => Right(out)
        case Right(Left(err)) => Left(ToolError(s"$name: $err"))
        case Left(e) => Left(ToolError(s"$name: ${Option(e.getMessage).getOrElse(e.getClass.getSimpleName)}"))
      }

  protected def hostRefusal(what: String): String =
    s"${DesktopPolicy.HostProtectedCode}: $what is a host target (Stand/engine/sbt/java) — refused before any system call. Not retried."

  protected def obj(props: (String, Json)*)(required: String*): JsonObject =
    JsonObject(
      "type" -> "object".asJson,
      "properties" -> Json.fromFields(props),
      "required" -> required.toList.asJson
    )

  protected def prop(kind: String, desc: String): Json =
    Json.obj("type" -> kind.asJson, "description" -> desc.asJson)

end DesktopVerbTool

object DesktopListAppsTool extends DesktopVerbTool:
  val Name: String = "desktop_list_apps"
  override def name: String = Name

  override def description: String =
    """List currently running visible (non-background) macOS applications as `name · pid`. Read-only. Host applications (Stand/engine/sbt/java) are never listed. Requires no focus change."""

  override def inputSchema: JsonObject = obj()()

  override def summarize(input: JsonObject): String = "desktop_list_apps()"

  override def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    guarded(ctx) {
      DesktopObserve.listApps.map(_.map { apps =>
        if apps.isEmpty then "no visible applications"
        else apps.map(a => s"${a.name} · ${a.pid}").mkString("\n")
      })
    }

object DesktopListWindowsTool extends DesktopVerbTool:
  val Name: String = "desktop_list_windows"
  override def name: String = Name

  override def description: String =
    """List visible windows as `app · title · x,y,w,h` (global screen points, read-only). Optional `app` filters to one application. Host windows are never listed. Titles require the Accessibility permission; without it the title may be empty."""

  override def inputSchema: JsonObject =
    obj("app" -> prop("string", "Optional application name to filter by (exact, case-insensitive)."))()

  override def summarize(input: JsonObject): String =
    s"desktop_list_windows(${str(input, "app").getOrElse("*")})"

  override def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    val app = str(input, "app")
    guarded(ctx) {
      DesktopObserve.listWindows(app).map(_.map { ws =>
        if ws.isEmpty then "no visible windows"
        else ws.map(w => s"${w.app} · ${w.title} · ${w.x},${w.y},${w.w},${w.h}").mkString("\n")
      })
    }

object DesktopScreenshotTool extends DesktopVerbTool:
  val Name: String = "desktop_screenshot"
  override def name: String = Name

  override def description: String =
    """Take a read-only screenshot (macOS screencapture -x). Modes: `full` (whole main screen, default), `region` (global points `region: "x,y,w,h"`), `window` (the first visible window of `app`, by its rectangle; occluding windows are included). Returns the PNG path and byte size under the engine captures directory; the image itself is not inlined. Refused for host applications. Requires the Screen Recording permission; if denied, the error is reported and not retried. Note: a `full` capture CAN include host windows — it is not masked."""

  override def inputSchema: JsonObject =
    obj(
      "mode" -> prop("string", "full | region | window (default full)."),
      "region" -> prop("string", "Required when mode=region: global screen points `x,y,w,h` (integers, w/h > 0)."),
      "app" -> prop("string", "Required when mode=window: application name.")
    )()

  override def summarize(input: JsonObject): String =
    s"desktop_screenshot(${str(input, "mode").getOrElse("full")})"

  override def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    val mode = str(input, "mode").getOrElse("full")
    val app = str(input, "app")
    val region = str(input, "region")
    guarded(ctx) {
      mode match
        case "full" =>
          DesktopObserve.screenshotFull.map(_.map(fmt))
        case "region" =>
          region.flatMap(DesktopPolicy.parseRegion) match
            case Some((x, y, w, h)) => DesktopObserve.screenshotRegion(x, y, w, h).map(_.map(fmt))
            case None => IO.pure(Left("mode=region needs `region` as `x,y,w,h` (integers, w/h > 0)"))
        case "window" =>
          app match
            case Some(a) if DesktopPolicy.isHostName(a) => IO.pure(Left(hostRefusal(s"app `$a`")))
            case Some(a) if DesktopPolicy.validAppName(a).isDefined =>
              DesktopObserve.screenshotApp(a).map(_.map(fmt))
            case _ => IO.pure(Left("mode=window needs a valid `app` name"))
        case other => IO.pure(Left(s"unknown mode `$other` (full | region | window)"))
    }

  private def fmt(r: (String, Long)): String =
    s"PNG saved: ${r._1} (${r._2} bytes). Read the file to view it."

object DesktopClipboardReadTool extends DesktopVerbTool:
  val Name: String = "desktop_clipboard_read"
  override def name: String = Name

  override def description: String =
    """Read the macOS clipboard as plain text (pbpaste, read-only), capped at 64 KB and marked when truncated. The clipboard may contain secrets: do not repeat credential-looking content in your answer, and never write it to files."""

  override def inputSchema: JsonObject = obj()()

  override def summarize(input: JsonObject): String = "desktop_clipboard_read()"

  override def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    guarded(ctx) {
      DesktopObserve.clipboardRead.map(_.map { case (text, truncated) =>
        if text.isEmpty then "(clipboard empty or non-text)"
        else if truncated then text + "\n[truncated at 64 KB]"
        else text
      })
    }

object DesktopOpenTool extends DesktopVerbTool:
  val Name: String = "desktop_open"
  override def name: String = Name

  override def description: String =
    """Launch a macOS application in the BACKGROUND (open -g -a <app>): it does not take focus. The only state-changing verb in this observation face — use only when the task needs the app running. Refused for host applications; the app name must be a plain name (letters, digits, space . + _ -)."""

  override def inputSchema: JsonObject =
    obj("app" -> prop("string", "Application name, e.g. `Calculator`."))("app")

  override def summarize(input: JsonObject): String =
    s"desktop_open(${str(input, "app").getOrElse("?")})"

  override def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    val app = str(input, "app").getOrElse("")
    guarded(ctx) {
      if DesktopPolicy.isHostName(app) then IO.pure(Left(hostRefusal(s"app `$app`")))
      else DesktopPolicy.validAppName(app) match
        case None => IO.pure(Left("`app` must be a plain application name (letters, digits, space . + _ -, 1-80 chars)"))
        case Some(a) => DesktopObserve.openApp(a)
    }

object DesktopDisplaysTool extends DesktopVerbTool:
  val Name: String = "desktop_displays"
  override def name: String = Name

  override def description: String =
    """List connected displays and the GPU that drives them (system_profiler, read-only). Global window coordinates may be negative when a display sits left of the main one."""

  override def inputSchema: JsonObject = obj()()

  override def summarize(input: JsonObject): String = "desktop_displays()"

  override def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    guarded(ctx)(DesktopObserve.displays)

/** 观测动词名单（单点）：AgentCore 的防声明逃逸集与挂载腿共用。 */
object DesktopVerbNames:
  val All: List[String] = List(
    DesktopListAppsTool.Name,
    DesktopListWindowsTool.Name,
    DesktopScreenshotTool.Name,
    DesktopClipboardReadTool.Name,
    DesktopOpenTool.Name,
    DesktopDisplaysTool.Name
  )
