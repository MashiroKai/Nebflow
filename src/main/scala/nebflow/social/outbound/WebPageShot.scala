package nebflow.social.outbound

import cats.effect.IO
import nebflow.shared.NebflowLogger

import java.nio.file.Files
import java.util.concurrent.TimeUnit
import scala.jdk.CollectionConverters.*
import scala.util.Try

/**
 * 网页类产物（html/htm）的出站整页截图（social-webshot batch, 2026-10-09）。
 *
 * 背景（用户实况 2026-10-09）：Pop 出的 `.html` 框图落在两张权威表之外
 * （[[RichKind.of]] ⇒ Unknown），飞书腿把机制报错
 * `extension is in neither authority table: html` 原样透给了用户，而飞书/微信
 * 都没有 html 渲染能力。
 *
 * 🔴 角色变更（用户裁定 2026-10-09 14:0x「社交 html 可以以文件的形式发过来
 * 不用自动转图」）：本对象不再是 html 的默认出站腿——两渠道现以既有文件消息腿
 * 直发 html 原件（飞书 `uploadFile`+`msg_type=file`；微信 `file_path`，见
 * [[RichFeishuAdapter.deliverWebPage]] / 微信桥 `deliverWebPageMedia`）；本截图
 * 能力保留不删、降为**兜底**：仅当文件腿发送失败（渠道拒绝/超时）才把原件截成
 * 整页 PNG 走既有图片腿，截图再失败才落 b 层文案。分类层（[[RichKind.of]]）两轮
 * 都刻意零改动——html 仍判 Unknown，本谓词只在网页腿被读取。
 *
 * 结构（沿用 [[RichRenderer]] 的纪律，逐条对应）：
 *
 *   - 真实进程、真实产物：生产实现 [[NodePlaywright]] 以 `node
 *     scripts/social-html-shot.mjs <in> <out>` 子进程跑仓内 playwright 脚本
 *     （`fullPage: true` 整页截图）。没有合成图、没有占位图。
 *   - 有界前台：30s 墙钟预算（[[DefaultTimeoutMs]]），超时 `destroyForcibly`，
 *     不留活进程；node 正常退出（与卡片腿的 Chrome 不同），产出秒级落盘。
 *   - 产物判定：成功 = exit 0 且产物过 [[RichRenderer.inspect]] 的 PNG magic +
 *     可解码 + 非纯色下限（整页截图的几何只能事后读，用 [[RichRenderer.inspect]]
 *     的单参重载——声明几何在这里天然自洽，承重的是 magic/解码/像素下限）。
 *   - 封闭原因词汇：`Left` 只携带 [[RichDegrade.Reason]] 的值；机制细节全部进
     * 日志，用户侧永远只见 b 层的友好中文一行。
 *   - 脚本发现（[[NodePlaywright.detectScript]]）：系统属性覆盖 > 从 user.dir /
 *     cwd 向上找 `scripts/social-html-shot.mjs`。找不到 ⇒ `available = false`
 *     ⇒ 调用方直接退 b 层（打包环境中仓内脚本不存在是正常形态，不是错误）。
 *
 * 🔴 离线纪律：spec 永远注入 fake/[[Unavailable]]，不启动 node。[[NodePlaywright]]
 * 的可测面只有发现逻辑与 `available`（纯文件系统读）。
 */
object WebPageShot:

  private val logger = NebflowLogger.forName("nebflow.social.outbound.webshot")

  /** 可渲染网页类的扩展名（小写、不带点）。authority：本对象是这两个扩展名的
    * 唯一判定位；不入 [[RichKind]]（分类层的两张权威表不因降级能力而扩表）。 */
  val Extensions: Set[String] = Set("html", "htm")

  /** 小写扩展名（不带点），无扩展名 ⇒ ""。与 [[RichKind.of]] 同一读取口径。 */
  def extensionOf(name: String): String =
    val lower = name.toLowerCase
    lower.lastIndexOf('.') match
      case -1 => ""
      case i  => lower.substring(i + 1)

  def isWebPage(p: os.Path): Boolean = Extensions.contains(extensionOf(p.toString))

  /** 一枪的墙钟预算（毫秒）。仓内实测：整页截图 ~1s；30s 与卡片渲染腿
    * （[[RichRenderer.DefaultTimeoutMs]]）同档，任务书点名 30s。 */
  val DefaultTimeoutMs: Long = 30000L

  /** 截图缝：`(html 原件, 输出 png) → Either[封闭原因, 实际产物]`。生产 =
    * [[NodePlaywright]]；离线 spec 注入 fake；[[Unavailable]] 是「本机不可用」
    * 载体（直接落 Left，调用方退 b 层）。失败永不 raise——调用方要的是降级，
    * 不是炸掉一个回合。 */
  trait Renderer:
    def available: Boolean
    def shot(htmlFile: os.Path, outPng: os.Path): IO[Either[String, os.Path]]

  /** 「本机没有截图能力」载体（HC-5 同款：响亮降级，不假装成功）。离线 spec
    * 的默认注入面。 */
  object Unavailable extends Renderer:
    def available: Boolean = false
    def shot(htmlFile: os.Path, outPng: os.Path): IO[Either[String, os.Path]] =
      IO.pure(Left(RichDegrade.Reason.EngineUnavailable))

  final class NodePlaywright(
      script: Option[os.Path] = NodePlaywright.detectScript,
      nodeBin: String = "node",
      timeoutMs: Long = DefaultTimeoutMs
  ) extends Renderer:

    def available: Boolean = script.exists(os.exists)

    def shot(htmlFile: os.Path, outPng: os.Path): IO[Either[String, os.Path]] =
      script match
        case None =>
          IO.pure(Left(RichDegrade.Reason.EngineUnavailable))
        case Some(s) if !os.exists(s) =>
          IO.pure(Left(RichDegrade.Reason.EngineUnavailable))
        case Some(s) =>
          IO.blocking {
            Files.createDirectories(outPng.toNIO.getParent)
            Files.deleteIfExists(outPng.toNIO) // 上一次的旧产物绝不能冒充本次产物
            val pb = new ProcessBuilder(
              List(nodeBin, s.toString, htmlFile.toString, outPng.toString).asJava)
            pb.redirectErrorStream(true)
            val started = System.nanoTime()
            val proc = pb.start()
            try
              // node 会自己退出（与卡片腿的 Chrome 不同）：先等退出、再排水。
              // 等待期脚本只写一行 stdout（异常时几行 stderr），远小于管道缓冲，
              // 不会死锁；极端刷屏场景由超时 kill 兜底，等待仍有界。
              val finished = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
              if !finished then
                logger.warnSync(s"web page shot timed out (${timeoutMs}ms budget) for $htmlFile")
                Left(RichDegrade.Reason.RenderTimeout)
              else
                val code = proc.exitValue()
                if code != 0 then
                  val tail = drain(proc)
                  logger.warnSync(s"web page shot failed (exit=$code) for $htmlFile: $tail")
                  Left(RichDegrade.Reason.EngineUnavailable)
                else
                  RichRenderer.inspect(outPng) match
                    case Right(r) => Right(r.path.getOrElse(outPng))
                    case Left(err) =>
                      logger.warnSync(s"web page shot product rejected: ${err.reason} detail=${err.detail}")
                      Left(err.reason)
            catch
              case e: Throwable =>
                logger.warnSync(s"web page shot threw ${e.getClass.getSimpleName}: " +
                  Option(e.getMessage).getOrElse(""))
                Left(RichDegrade.Reason.EngineUnavailable)
            finally
              // 与 [[RichRenderer.ChromeRenderer]] 同一条红线：本调用不留活进程。
              if proc.isAlive then proc.destroyForcibly()
          }.handleErrorWith(e =>
            IO.pure {
              logger.warnSync(s"web page shot leg aborted: ${e.getClass.getSimpleName}: " +
                Option(e.getMessage).getOrElse(""))
              Left(RichDegrade.Reason.EngineUnavailable)
            })

    /** 排水子进程合并输出（前 400 字符进日志，够定位、不刷屏）。 */
    private def drain(proc: Process): String =
      try
        val bytes = proc.getInputStream.readAllBytes()
        new String(bytes, "UTF-8").trim.take(400)
      catch case _: Throwable => ""

  object NodePlaywright:

    /** 仓内脚本发现：系统属性 `nebflow.social.webshot.script` 显式指定优先；
      * 否则从 user.dir 与 cwd 各向上找 ≤6 级的 `scripts/social-html-shot.mjs`
      * （sbt 的 cwd 不保证是仓根；打包安装形态下两处都找不到 = 不可用，属
      * 正常降级形态）。 */
    def detectScript: Option[os.Path] =
      val rel = os.rel / "scripts" / "social-html-shot.mjs"
      val byProp = Option(System.getProperty("nebflow.social.webshot.script"))
        .flatMap(p => Try(os.Path(p, os.pwd)).toOption)
      def up(start: os.Path): Option[os.Path] =
        Iterator.iterate(start)(p => p / os.up).take(6).map(_ / rel).find(os.exists)
      byProp.orElse(up(os.Path(System.getProperty("user.dir"), os.pwd)))
        .orElse(up(os.pwd))

end WebPageShot
