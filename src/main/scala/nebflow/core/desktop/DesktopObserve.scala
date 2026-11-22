package nebflow.core.desktop

import cats.effect.IO
import io.circe.parser
import nebflow.shared.PathUtil

import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/* ═══════════════════════════════════════════════════════════════════════════
 * desktop-observe-p0（2026-11-22）· 桌面观测执行层（引擎侧，系统 CLI 收编）
 *
 * 零件全是 macOS 自带只读 CLI：osascript（System Events 进程/窗口只读查询）、
 * screencapture -x（全屏/区域截图，落 captures 目录）、system_profiler -json（显示器
 * 清单）、pbpaste（剪贴板读）、open -g -a（后台启动，不抢前台）。
 *
 * 红线：本模块不含任何写类输入（无点击/键入/窗口几何/AX 写）。宿主拒绝在本层与工具层
 * 双重前置：名单命中的应用/窗口在结果里剔除，指名宿主应用的截图/启动直接 HOST-PROTECTED。
 * 每个子进程有超时上限，失败即返回错误、不重试。
 * ═══════════════════════════════════════════════════════════════════════════ */
object DesktopObserve:

  /** 单个系统命令超时（秒）。 */
  val CommandTimeoutSec: Long = 15L

  /** 截图落盘子目录（数据根下）。 */
  def captureDir: os.Path = PathUtil.dataRoot / "captures" / "desktop"

  /** 运行命令，返回 stdout；非零退出 / 超时 / 启动失败 → Left（不重试）。stderr 落临时文件后读取。 */
  def exec(cmd: List[String], timeoutSec: Long = CommandTimeoutSec): Either[String, String] =
    val errFile = File.createTempFile("desktop-observe-", ".err")
    try
      val pb = new ProcessBuilder(cmd*)
      pb.redirectError(ProcessBuilder.Redirect.to(errFile))
      val proc = pb.start()
      val out = new String(proc.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
      if !proc.waitFor(timeoutSec, TimeUnit.SECONDS) then
        proc.destroyForcibly()
        Left(s"${cmd.head}: timed out after ${timeoutSec}s")
      else
        val code = proc.exitValue()
        if code == 0 then Right(out)
        else
          val err = scala.util.Try(java.nio.file.Files.readString(errFile.toPath)).getOrElse("").trim
          Left(s"${cmd.head} exit $code${if err.isEmpty then "" else s": ${err.take(300)}"}")
    catch case e: Exception => Left(s"${cmd.head}: ${Option(e.getMessage).getOrElse(e.getClass.getSimpleName)}")
    finally errFile.delete()

  // System Events 只读脚本：不激活任何应用、不改任何属性。
  private val ListAppsScript: String =
    """tell application "System Events"
      |  set out to ""
      |  repeat with p in (every process whose background only is false)
      |    set out to out & (name of p) & tab & (unix id of p) & linefeed
      |  end repeat
      |  return out
      |end tell""".stripMargin

  private val ListWindowsScript: String =
    """tell application "System Events"
      |  set out to ""
      |  repeat with p in (every process whose background only is false)
      |    try
      |      set pn to name of p
      |      repeat with w in (every window of p)
      |        try
      |          set {px, py} to position of w
      |          set {sw, sh} to size of w
      |          set out to out & pn & tab & (name of w) & tab & px & tab & py & tab & sw & tab & sh & linefeed
      |        end try
      |      end repeat
      |    end try
      |  end repeat
      |  return out
      |end tell""".stripMargin

  /** 运行中（前台可见，非后台）应用清单，宿主名单剔除。 */
  def listApps: IO[Either[String, List[DesktopPolicy.AppRow]]] =
    IO.blocking {
      exec(List("osascript", "-e", ListAppsScript)).map { out =>
        DesktopPolicy.parseAppLines(out).filterNot(a => DesktopPolicy.isHostName(a.name))
      }
    }

  /** 窗口清单（可选按应用名过滤），宿主名单剔除。 */
  def listWindows(appFilter: Option[String]): IO[Either[String, List[DesktopPolicy.WindowRow]]] =
    IO.blocking {
      exec(List("osascript", "-e", ListWindowsScript)).map { out =>
        DesktopPolicy
          .parseWindowLines(out)
          .filterNot(w => DesktopPolicy.isHostName(w.app))
          .filter(w => appFilter.forall(f => w.app.equalsIgnoreCase(f)))
      }
    }

  /** 区域截图（屏幕点坐标）。全屏档暂不开放（待遮罩机制，见审计 P1 开放项）。 */
  def screenshotRegion(x: Int, y: Int, w: Int, h: Int): IO[Either[String, (String, Long)]] =
    shoot(Some((x, y, w, h)))

  /** 指定应用第一扇窗口的矩形区域截图（非窗口 id 截取；被遮挡部分会一并入图，已在 SKILL 标注）。 */
  def screenshotApp(app: String): IO[Either[String, (String, Long)]] =
    listWindows(Some(app)).flatMap {
      case Left(e) => IO.pure(Left(e))
      case Right(Nil) => IO.pure(Left(s"no visible window for app `$app` (or Accessibility not granted)"))
      case Right(w :: _) => shoot(Some((w.x, w.y, w.w, w.h)))
    }

  private def shoot(region: Option[(Int, Int, Int, Int)]): IO[Either[String, (String, Long)]] =
    // 仅区域/窗口档经此入口（region=None 分支保留为未来遮罩机制的落点，当前不可达）。
    IO.blocking {
      val dir = captureDir
      os.makeDir.all(dir)
      val stamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"))
      val file = dir / s"desktop_$stamp.png"
      val base = List("screencapture", "-x")
      val cmd = region match
        case None => base :+ file.toString
        case Some((x, y, w, h)) => base ++ List("-R", s"$x,$y,$w,$h", file.toString)
      exec(cmd).flatMap(_ => scala.util.Try(os.size(file)).toEither.left.map(_ => "screencapture produced no file (Screen Recording not granted?)")).map(size => (file.toString, size))
    }

  /** 显示器清单（system_profiler -json 的 SPDisplaysDataType 精简字段）。 */
  def displays: IO[Either[String, String]] =
    IO.blocking {
      exec(List("system_profiler", "SPDisplaysDataType", "-json", "-detailLevel", "mini"), 30L).flatMap { out =>
        parser.parse(out).left.map(e => s"system_profiler json parse: ${e.getMessage}").map { json =>
          val cursor = json.hcursor.downField("SPDisplaysDataType")
          val entries = cursor.focus.flatMap(_.asArray).getOrElse(Vector.empty)
          entries.map { e =>
            val gpu = e.hcursor.downField("sppci_model").as[String].getOrElse("?")
            val ndrvs = e.hcursor.downField("spdisplays_ndrvs").focus.flatMap(_.asArray).getOrElse(Vector.empty)
            val disps = ndrvs.map { d =>
              val name = d.hcursor.downField("_name").as[String].getOrElse("?")
              val res = d.hcursor.downField("_spdisplays_resolution").as[String].getOrElse("?")
              val main = d.hcursor.downField("spdisplays_main").as[String].getOrElse("")
              s"  - $name · $res${if main.nonEmpty then " · main" else ""}"
            }
            (s"GPU $gpu" +: disps.toList).mkString("\n")
          }.mkString("\n")
        }
      }
    }

  /** 剪贴板读取（pbpaste），截断到上限。输出可能含敏感内容，调用方须只回显必要部分。 */
  def clipboardRead: IO[Either[String, (String, Boolean)]] =
    IO.blocking(exec(List("pbpaste")).map(DesktopPolicy.capClipboard))

  /** 后台启动应用（open -g：不抢前台）。宿主名单命中由调用方先行拒绝。 */
  def openApp(app: String): IO[Either[String, String]] =
    IO.blocking(exec(List("open", "-g", "-a", app)).map(_ => s"opened `$app` in background (no focus change)"))

end DesktopObserve
