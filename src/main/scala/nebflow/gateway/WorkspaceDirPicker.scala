package nebflow.gateway

import cats.effect.IO
import io.circe.Json
import nebflow.core.NebflowLogger

import java.awt.{FileDialog, GraphicsEnvironment}
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JFileChooser

/** 工作区目录原生选择器（2026-09-05 作者裁定，workspace-picker 批次）。
  *
  * 前端 ProjectCreate「选择工作区」卡片点击 → WS `pickWorkspaceDir` → 本组件在
  * 专用线程打开真实系统目录对话框：
  *  - macOS：java.awt.FileDialog + `apple.awt.fileDialogForDirectories` → 原生
  *    NSOpenPanel（Finder 同款，自带「新建文件夹」——OpenJDK Cocoa 实现在目录
  *    模式下设 canChooseDirectories=YES / canCreateDirectories=YES）。
  *  - Windows/其他：Swing JFileChooser(DIRECTORIES_ONLY)——JVM 自带真实文件
  *    浏览器对话框（Windows LAF 自带「创建新文件夹」钮）。注：这是 Swing 形态
  *    而非 Explorer 原生 common dialog——AWT FileDialog 在 Windows 不支持目录
  *    选择，真原生目录框需 JNA/进程外方案（登记 TODO，未做）。
  *
  * 三态结果经 WS 事件 `workspaceDirPicked` 回传（单一事件类型，载荷互斥）：
  *  - 选中：{ sessionId, requestId, path }
  *  - 取消：{ sessionId, requestId, cancelled: true }
  *  - 降级：{ sessionId, requestId, fallback: true, reason } — headless JVM /
    *    对话框异常时前端自动打开应用内浏览器弹窗（workspacePicker.js）。
  *
  * 线程模型（关键）：WS 帧是 per-connection `evalMap` 串行处理——对话框阻塞
  * 若内联会冻结整条连接（后续 askUserAnswer 无法处理）。调用方必须
  * `pick(...).start` 派独立 fiber；对话框本体走 `IO.blocking`（专用阻塞池），
  * 绝不占用 compute 线程或 WS 线程。
  *
  * 单飞护栏：全 JVM 同一时刻至多一个系统对话框（AWT 层幕面共享）。重复点击
  * 忽略并 log warn（不回 fallback——那会在真框已开时再叠一层应用内弹窗）。
  *
  * 可测试性：Env 注入 headless 探测与 opener——spec 层永不真开对话框
  * （变异验红点：摘除 headless gate → headless 用例红）。真实链路取证只到
  * gate 前（隔离实例实测 isHeadless + Toolkit 初始化，严禁在作者屏幕弹框）。
  */
object WorkspaceDirPicker:

  private val logger = NebflowLogger.forName("nebflow.ws.picker")

  /** 对话框开启函数：阻塞至用户选择/取消（运行在 blocking 线程上），Some=选中路径
    * None=取消。返回 IO 形态以便 spec 用 Deferred/Ref 注入可控 opener。 */
  type Opener = () => IO[Option[String]]

  /** 注入点：headless 探测 + opener（spec 用，生产用默认值）。 */
  final case class Env(
    headless: () => Boolean = () => GraphicsEnvironment.isHeadless,
    opener: Option[Opener] = None
  )

  private val inFlight = new AtomicBoolean(false)

  /** 测试辅助：单飞状态观察/复位（package-private）。 */
  private[gateway] def busy: Boolean = inFlight.get()
  private[gateway] def resetForTest(): Unit = inFlight.set(false)

  /** 处理一次 pickWorkspaceDir 请求。生产路径由 WebSocketRoutes 以 `.start` 调用。 */
  def pick(sessionId: String, requestId: String, wsSend: Json => IO[Unit], env: Env = Env()): IO[Unit] =
    if env.headless() then
      logger.warn(s"WorkspaceDirPicker: headless JVM — fallback to in-app browser (session=$sessionId requestId=$requestId)")
      sendFallback(wsSend, sessionId, requestId, "headless-jvm")
    else if !inFlight.compareAndSet(false, true) then
      // 已有系统对话框在开：忽略重复点击（真框还在，用户应继续用那个）。
      logger.warn(s"WorkspaceDirPicker: picker already open — duplicate click ignored (requestId=$requestId)")
      IO.unit
    else
      val open = env.opener.getOrElse(() => IO { openNative() })
      IO.blocking(open()).flatten
        .guarantee(IO(inFlight.set(false)))
        .flatMap {
          case Some(path) =>
            logger.info(s"WorkspaceDirPicker: picked '$path' (session=$sessionId requestId=$requestId)")
            wsSend(Json.obj(
              "type" -> Json.fromString("workspaceDirPicked"),
              "sessionId" -> Json.fromString(sessionId),
              "requestId" -> Json.fromString(requestId),
              "path" -> Json.fromString(path)
            ))
          case None =>
            logger.info(s"WorkspaceDirPicker: cancelled (session=$sessionId requestId=$requestId)")
            wsSend(Json.obj(
              "type" -> Json.fromString("workspaceDirPicked"),
              "sessionId" -> Json.fromString(sessionId),
              "requestId" -> Json.fromString(requestId),
              "cancelled" -> Json.fromBoolean(true)
            ))
        }
        .handleErrorWith { e =>
          logger.error(s"WorkspaceDirPicker: dialog failed — fallback (${e.getMessage}) (requestId=$requestId)")
          sendFallback(wsSend, sessionId, requestId, Option(e.getMessage).getOrElse(e.getClass.getSimpleName))
        }

  private def sendFallback(wsSend: Json => IO[Unit], sessionId: String, requestId: String, reason: String): IO[Unit] =
    wsSend(Json.obj(
      "type" -> Json.fromString("workspaceDirPicked"),
      "sessionId" -> Json.fromString(sessionId),
      "requestId" -> Json.fromString(requestId),
      "fallback" -> Json.fromBoolean(true),
      "reason" -> Json.fromString(reason)
    ))

  /** 生产 opener：平台分派的真实系统目录对话框（阻塞调用——只在 IO.blocking 里跑）。 */
  private def openNative(): Option[String] =
    if isMac then openMacDirectoryDialog() else openSwingDirectoryDialog()

  private def isMac: Boolean =
    Option(System.getProperty("os.name")).exists(_.toLowerCase.contains("mac"))

  /** macOS：FileDialog 目录模式 → 原生 NSOpenPanel。
    * `apple.awt.fileDialogForDirectories` 在对话框 realize 时被 OpenJDK Cocoa
    * 实现读取（awtFileDialog.c/awtFileDialog.m：目录模式设 canChooseDirectories +
    * canCreateDirectories + canChooseFiles=NO）——须在构造/显示前置位。 */
  private def openMacDirectoryDialog(): Option[String] =
    System.setProperty("apple.awt.fileDialogForDirectories", "true")
    val fd = new FileDialog(null.asInstanceOf[java.awt.Frame], "选择工作区", FileDialog.LOAD)
    try
      fd.setDirectory(System.getProperty("user.home"))
      fd.setVisible(true) // 阻塞至用户关闭
      val file = fd.getFile
      val dir = fd.getDirectory
      if file == null then None
      else Some(stripTrailingSlash(Option(dir).getOrElse("") + file))
    finally fd.dispose()

  /** Windows/Linux：JFileChooser DIRECTORIES_ONLY（真实文件浏览器对话框，
    * 自带「创建新文件夹」钮）。非 Explorer 原生框——见类注释 TODO。 */
  private def openSwingDirectoryDialog(): Option[String] =
    val chooser = new JFileChooser(System.getProperty("user.home"))
    chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY)
    chooser.setDialogTitle("选择工作区")
    val res = chooser.showOpenDialog(null)
    if res == JFileChooser.APPROVE_OPTION then
      Option(chooser.getSelectedFile).map(f => stripTrailingSlash(f.getAbsolutePath))
    else None

  private def stripTrailingSlash(s: String): String = s.replaceAll("/+$", "")
end WorkspaceDirPicker
