package nebflow.shared

import cats.effect.IO
import com.microsoft.playwright.*
import com.microsoft.playwright.options.*
import nebflow.core.NebflowLogger

import java.nio.file.{Files, Paths}
import java.util.concurrent.Executors

import scala.compiletime.uninitialized
import scala.concurrent.ExecutionContext
import scala.jdk.CollectionConverters.*

/** 浏览器获取结果。 */
final case class BrowserFetchResult(
  status: Int,
  title: String,
  content: String,
  finalUrl: String
)

/**
 * Playwright 浏览器管理器（单例）。
 *
 *  设计要点：
 *  - 单线程 EC：Playwright 要求所有操作在创建实例的同一个线程上执行
 *  - 持久化 context：cookies/localStorage 保存到 ~/.nebflow/browser-data/，
 *    Cloudflare cf_clearance cookie 在有效期内可自动复用
 *  - Headless/Headed 切换：切换时关闭并重开 context（cookies 已持久化，不受影响）
 *  - 优先使用系统 Chrome（指纹更真实），无 Chrome 时用 Playwright 自带 Chromium
 */
object BrowserManager:
  private val logger = NebflowLogger.forName("nebflow.browser")

  /** 持久化数据目录，保存 cookies、localStorage 等。 */
  private val dataDir = Paths.get(
    System.getProperty("user.home"),
    ".nebflow",
    "browser-data"
  )

  /** 单线程 EC：Playwright 所有操作必须在此线程执行。 */
  private val playwrightEC = ExecutionContext.fromExecutorService(
    Executors.newSingleThreadExecutor { r =>
      val t = new Thread(r, "playwright-worker")
      t.setDaemon(true)
      t
    }
  )

  @volatile private var playwright: Playwright = uninitialized
  @volatile private var context: BrowserContext = uninitialized
  @volatile private var currentHeadless = true

  /** 检测系统是否安装了 Chrome，用于设置 Playwright channel。 */
  private def detectChannel: Option[String] =
    val osName = System.getProperty("os.name").toLowerCase
    if osName.contains("mac") then
      if Files.exists(Paths.get("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"))
      then Some("chrome")
      else None
    else if osName.contains("win") then
      val pf = Option(System.getenv("ProgramFiles")).getOrElse("C:\\Program Files")
      if Files.exists(Paths.get(s"$pf\\Google\\Chrome\\Application\\chrome.exe"))
      then Some("chrome")
      else None
    else None // Linux: 让 Playwright 用自带 Chromium

  /** 判断标题是否为反爬虫挑战页面。 */
  private def isChallengeTitle(title: String): Boolean =
    val t = title.toLowerCase
    t.contains("just a moment") || title.contains("请稍候") ||
    t.contains("attention required") || t.contains("access denied")

  /** 确保 BrowserContext 以指定模式运行，必要时关闭重开。 */
  private def ensureContext(headless: Boolean): Unit =
    if context != null && currentHeadless == headless then ()
    else
      // 切换模式：关闭旧 context，cookies 已持久化
      if context != null then
        try context.close()
        catch case _: Exception => ()
        context = null

      Files.createDirectories(dataDir)

      val opts = new BrowserType.LaunchPersistentContextOptions()
        .setHeadless(headless)
        .setUserAgent(SharedBackend.UserAgent)
      detectChannel.foreach(opts.setChannel)
      opts.setArgs(List("--disable-blink-features=AutomationControlled").asJava)

      if playwright == null then playwright = Playwright.create()
      context = playwright.chromium().launchPersistentContext(dataDir, opts)

      // 隐藏 webdriver 标记，降低被检测概率
      context.addInitScript(
        "Object.defineProperty(navigator, 'webdriver', { get: () => false });"
      )

      currentHeadless = headless
      logger.infoSync(
        "Browser context started",
        "headless" -> headless.toString,
        "channel" -> detectChannel.getOrElse("chromium")
      )
  end ensureContext

  /**
   * 用浏览器获取页面内容。
   *
   *  - headless=true：自动模式，适用于 IEEE/APS 等 JS 渲染网站
   *  - headless=false：弹窗模式，适用于 ScienceDirect/Wiley（需用户完成 Cloudflare 验证）
   *
   *  两阶段智能等待：
   *  1. 快速阶段：只检查标题（title() 开销小），等标题变为非空且非 challenge
   *  2. 内容阶段：标题 OK 后才获取 content()，等内容达到合理长度
   *
   *  所有操作在 playwrightEC 单线程上执行，满足 Playwright 线程安全要求。
   */
  def fetch(url: String, headless: Boolean, maxWaitSeconds: Int = 15): IO[BrowserFetchResult] =
    IO.delay {
      ensureContext(headless)
      val page = context.newPage()
      try
        logger.infoSync("Browser navigating", "url" -> url.take(80), "headless" -> headless.toString)
        val response = page.navigate(
          url,
          new Page.NavigateOptions().setTimeout(15_000).setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
        )
        val status = if response != null then response.status() else 0

        // Phase 1: 快速等待标题（不获取 content，开销小）
        var title = page.title()
        var waited = 0
        while (title.isEmpty || isChallengeTitle(title)) && waited < maxWaitSeconds do
          page.waitForTimeout(1000)
          waited += 1
          title = page.title()

        // Phase 2: 标题 OK 后检查内容长度（仅对非 403 页面）
        var content = page.content()
        while status != 403 && content.length < 3000 && waited < maxWaitSeconds do
          page.waitForTimeout(1000)
          waited += 1
          content = page.content()

        val finalUrl = page.url()
        logger.infoSync(
          "Browser fetched",
          "url" -> url.take(80),
          "status" -> status.toString,
          "title" -> title.take(50),
          "contentLen" -> content.length.toString,
          "waited" -> waited.toString
        )
        BrowserFetchResult(status, title, content, finalUrl)
      finally
        try page.close()
        catch case _: Exception => ()
      end try
    }.evalOn(playwrightEC)

  /** 关闭浏览器和 Playwright，释放资源。 */
  def shutdown(): Unit =
    try
      if context != null then context.close()
      if playwright != null then playwright.close()
    catch case _: Exception => ()
    finally playwrightEC.shutdown()

end BrowserManager
