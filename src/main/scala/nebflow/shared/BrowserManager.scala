package nebflow.shared

import cats.effect.IO
import com.microsoft.playwright.*
import com.microsoft.playwright.options.*
import nebflow.core.NebflowLogger

import java.nio.file.{Files, Paths}
import java.util.concurrent.{Executors, TimeUnit}

import scala.compiletime.uninitialized
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** 浏览器获取结果。 */
final case class BrowserFetchResult(
  status: Int,
  title: String,
  content: String,
  finalUrl: String,
  isMarkdown: Boolean = false
)

/**
 * 浏览器管理器（单例）——支持三种引擎，自动降级：
 *
 *  1. Obscura（Rust 无头浏览器，内建 stealth + DOM-to-Markdown）
 *  2. Playwright（系统 Chrome / Chromium，带增强 stealth 注入）
 *  3. 回退到纯 HTTP（由 WebFetchTool 处理）
 *
 *  设计参考：https://github.com/h4ckf0r0day/obscura
 */
object BrowserManager:
  private val logger = NebflowLogger.forName("nebflow.browser")

  private val dataDir =
    Option(System.getProperty("nebflow.browser.dataDir"))
      .map(Paths.get(_))
      .getOrElse(Paths.get(System.getProperty("user.home"), ".nebflow", "browser-data"))

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

  // ── Obscura 检测 ────────────────────────────────────────────────────

  /** 检测 Obscura 是否安装。只检测一次，结果缓存。 */
  private lazy val obscuraPath: Option[String] =
    try
      val pb = new ProcessBuilder("which", "obscura")
      pb.redirectErrorStream(true)
      val proc = pb.start()
      proc.waitFor(3, TimeUnit.SECONDS)
      val path = new String(proc.getInputStream.readAllBytes()).trim
      if proc.exitValue() == 0 && path.nonEmpty then
        logger.infoSync("Obscura detected", "path" -> path)
        Some(path)
      else None
    catch case _: Exception => None

  /** 用 Obscura CLI 获取页面。返回 Markdown。 */
  private def fetchWithObscura(url: String, maxWaitSeconds: Int): Option[BrowserFetchResult] =
    obscuraPath.flatMap { exe =>
      try
        val timeoutMs = maxWaitSeconds * 1000
        val cmd = List(exe, "fetch", url, "--stealth", "--timeout", timeoutMs.toString, "--dump", "markdown")
        val pb = new ProcessBuilder(cmd.asJava)
        pb.redirectErrorStream(true)
        val proc = pb.start()
        proc.waitFor((maxWaitSeconds + 10).toLong, TimeUnit.SECONDS)
        if proc.exitValue() == 0 then
          val output = new String(proc.getInputStream.readAllBytes()).trim
          if output.nonEmpty then
            val title = """(?m)^#\s+(.+)$""".r.findFirstMatchIn(output).map(_.group(1)).getOrElse("")
            logger.infoSync("Obscura fetched", "url" -> url.take(80), "len" -> output.length.toString)
            Some(BrowserFetchResult(200, title, output, url, isMarkdown = true))
          else None
        else None
      catch case _: Exception => None
    }

  // ── Playwright stealth ──────────────────────────────────────────────

  /** 检测系统 Chrome。 */
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
    else None

  /**
   * 增强反检测脚本——参考 Obscura 的 stealth mode。
   *
   *  覆盖以下检测维度：
   *  - navigator.webdriver / userAgentData
   *  - Canvas/WebGL/Audio 指纹随机化
   *  - navigator.plugins / languages 一致性
   *  - window.chrome 运行时
   *  - Permissions API
   */
  private val STEALTH_JS: String =
    """(function(){
      |// 1. navigator.webdriver = undefined
      |Object.defineProperty(navigator,'webdriver',{get:()=>undefined,configurable:true});
      |// 2. navigator.userAgentData (high-entropy Client Hints)
      |var brands=[{brand:'Chromium',version:'131'},{brand:'Google Chrome',version:'131'},{brand:'Not_A Brand',version:'24'}];
      |try{Object.defineProperty(navigator,'userAgentData',{get:function(){return{brands:brands,mobile:false,platform:'macOS',
      |getHighEntropyValues:function(){return Promise.resolve({brands:brands,mobile:false,platform:'macOS',
      |architecture:'arm',bitness:'64',model:'',platformVersion:'10.15.7',uaFullVersion:'131.0.0.0',
      |fullVersionList:[{brand:'Chromium',version:'131.0.0.0'},{brand:'Google Chrome',version:'131.0.0.0'}]});}};},configurable:true});}catch(e){}
      |// 3. Canvas fingerprint noise
      |try{var oGI=CanvasRenderingContext2D.prototype.getImageData;
      |CanvasRenderingContext2D.prototype.getImageData=function(){var d=oGI.apply(this,arguments);
      |for(var i=0;i<d.data.length;i+=400){d.data[i]=(d.data[i]+1)&0xff;}return d;};}catch(e){}
      |// 4. WebGL vendor/renderer
      |try{var oGP=WebGLRenderingContext.prototype.getParameter;
      |WebGLRenderingContext.prototype.getParameter=function(p){if(p===37445)return'Google Inc. (Intel)';
      |if(p===37446)return'ANGLE (Intel, Intel(R) Iris(TM) Plus Graphics 655, OpenGL 4.1)';return oGP.call(this,p);};}catch(e){}
      |try{if(typeof WebGL2RenderingContext!=='undefined'){var oGP2=WebGL2RenderingContext.prototype.getParameter;
      |WebGL2RenderingContext.prototype.getParameter=function(p){if(p===37445)return'Google Inc. (Intel)';
      |if(p===37446)return'ANGLE (Intel, Intel(R) Iris(TM) Plus Graphics 655, OpenGL 4.1)';return oGP2.call(this,p);};}}catch(e){}
      |// 5. Audio fingerprint noise
      |try{var oFFD=AnalyserNode.prototype.getFloatFrequencyData;
      |AnalyserNode.prototype.getFloatFrequencyData=function(a){oFFD.call(this,a);for(var i=0;i<a.length;i++){a[i]+=(Math.random()-0.5)*0.1;}};}catch(e){}
      |// 6. window.chrome
      |try{if(!window.chrome)window.chrome={runtime:{},loadTimes:function(){},csi:function(){},app:{isInstalled:false}};}catch(e){}
      |// 7. navigator.plugins
      |try{Object.defineProperty(navigator,'plugins',{get:function(){var a=[{name:'PDF Viewer',filename:'internal-pdf-viewer',description:'Portable Document Format'}];
      |a.item=function(i){return a[i]};a.namedItem=function(n){return a[0]};a.refresh=function(){};Object.defineProperty(a,'length',{value:1});return a;},configurable:true});}catch(e){}
      |// 8. navigator.languages
      |try{Object.defineProperty(navigator,'languages',{get:function(){return['en-US','en']},configurable:true});}catch(e){}
      |// 9. Permissions API consistency
      |try{var oQ=navigator.permissions.query;navigator.permissions.query=function(d){
      |if(d.name==='notifications')return Promise.resolve({state:Notification.permission});return oQ.call(this,d);};}catch(e){}
      |})();
    """.stripMargin

  /**
   * DOM-to-Markdown 转换脚本——参考 Obscura 的 LP.getMarkdown。
   *
   *  在 page.evaluate() 中调用，将渲染后的 DOM 转为简洁 Markdown。
   *  替代 WebFetchTool 的正则 HTML 提取，正确处理嵌套结构和表格。
   */
  private val DOM_TO_MD_JS: String =
    """(function(){
      |function conv(n){
      |if(n.nodeType===3)return n.textContent.replace(/\s+/g,' ');
      |if(n.nodeType!==1)return '';
      |var tag=n.tagName.toLowerCase();
      |var ch=function(){var r='';for(var c of n.childNodes)r+=conv(c);return r;};
      |if(['script','style','nav','header','footer','aside','noscript','iframe','svg','form','button','input','select'].includes(tag))return '';
      |if(tag.match(/^h[1-6]$/))return '\n'+('#').repeat(parseInt(tag[1]))+' '+n.textContent.trim()+'\n\n';
      |if(tag==='p')return ch()+'\n\n';
      |if(tag==='br')return '\n';
      |if(tag==='strong'||tag==='b')return '**'+ch()+'**';
      |if(tag==='em'||tag==='i')return '*'+ch()+'*';
      |if(tag==='code')return '`'+n.textContent+'`';
      |if(tag==='pre')return '\n```\n'+n.textContent.trim()+'\n```\n\n';
      |if(tag==='a'){var h=n.getAttribute('href')||'';var t=ch().trim();return t?'['+t+']('+h+')':'';}
      |if(tag==='img'){var s=n.getAttribute('src')||'';var a=n.getAttribute('alt')||'';return s?'!['+a+']('+s+')':'';}
      |if(tag==='ul')return Array.from(n.children).map(function(li){return '- '+conv(li).trim()}).join('\n')+'\n\n';
      |if(tag==='ol')return Array.from(n.children).map(function(li,i){return (i+1)+'. '+conv(li).trim()}).join('\n')+'\n\n';
      |if(tag==='blockquote')return '> '+ch().trim()+'\n\n';
      |if(tag==='hr')return '\n---\n\n';
      |if(tag==='table'){var rows=Array.from(n.querySelectorAll('tr'));
      |if(!rows.length)return '';var md=rows.map(function(tr){return '|'+Array.from(tr.querySelectorAll('th,td')).map(function(td){return td.textContent.trim()}).join('|')+'|';});
      |if(md.length>1)md.splice(1,0,md[0].replace(/[^|]/g,'-'));return md.join('\n')+'\n\n';}
      |if(tag==='li')return ch();
      |return ch();}
      |var main=document.querySelector('main')||document.querySelector('article')||document.body;
      |var r=conv(main);return r.replace(/\n{3,}/g,'\n\n').trim();
      |})()
    """.stripMargin

  /** 常见追踪/广告域名，通过 Playwright route 拦截以减少检测信号。 */
  private val TRACKER_DOMAINS = List(
    "google-analytics.com",
    "googletagmanager.com",
    "doubleclick.net",
    "googlesyndication.com",
    "googleadservices.com",
    "connect.facebook.net",
    "analytics.twitter.com",
    "ads.twitter.com",
    "sb.scorecardresearch.com",
    "hotjar.com",
    "mixpanel.com",
    "segment.io",
    "amplitude.com",
    "fullstory.com",
    "clarity.ms",
    "bat.bing.com",
    "cloudflareinsights.com",
    "quantserve.com",
    "crazyegg.com",
    "optimizely.com"
  )

  private def isChallengeTitle(title: String): Boolean =
    val t = title.toLowerCase
    t.contains("just a moment") || title.contains("请稍候") ||
    t.contains("attention required") || t.contains("access denied")

  // ── Playwright 浏览器管理 ───────────────────────────────────────────

  private def ensureContext(headless: Boolean): Unit =
    if context == null || currentHeadless != headless then
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
      try context = playwright.chromium().launchPersistentContext(dataDir, opts)
      catch
        case _: Exception =>
          // Chrome may have crashed on a previous run, leaving a stale SingletonLock.
          // Delete it and retry once.
          val lock = dataDir.resolve("SingletonLock")
          if Files.exists(lock) then
            logger.infoSync("Removing stale SingletonLock", "dir" -> dataDir.toString)
            try Files.delete(lock)
            catch case _: Exception => ()
          context = playwright.chromium().launchPersistentContext(dataDir, opts)

      // Set a global default timeout so NO Playwright operation can block
      // indefinitely.  Anti-bot systems can cause navigate/title/content to
      // hang forever; this ensures they throw after the limit instead.
      context.setDefaultTimeout(15_000.0)
      context.setDefaultNavigationTimeout(15_000.0)

      // 注入增强 stealth 脚本（在每个页面加载前执行）
      context.addInitScript(STEALTH_JS)

      currentHeadless = headless
      logger.infoSync(
        "Browser context started",
        "headless" -> headless.toString,
        "channel" -> detectChannel.getOrElse("chromium"),
        "stealth" -> "enhanced"
      )
  end ensureContext

  /** 用 Playwright 获取页面，返回 DOM-to-Markdown 转换后的内容。 */
  private def fetchWithPlaywright(url: String, headless: Boolean, maxWaitSeconds: Int): BrowserFetchResult =
    ensureContext(headless)
    val page = context.newPage()

    try
      logger.infoSync("Browser navigating", "url" -> url.take(80), "headless" -> headless.toString)
      // Use COMMIT wait strategy: fires as soon as the response headers are
      // received, without waiting for JavaScript or DOM events.  This prevents
      // anti-bot challenges from blocking navigation indefinitely.
      val response =
        page.navigate(url, new Page.NavigateOptions().setTimeout(15_000).setWaitUntil(WaitUntilState.COMMIT))
      val status = if response != null then response.status() else 0

      // Phase 1: 等待标题
      var title = page.title()
      var waited = 0
      while (title.isEmpty || isChallengeTitle(title)) && waited < maxWaitSeconds do
        page.waitForTimeout(1000)
        waited += 1
        title = page.title()

      // Phase 2: 等待内容
      while status != 403 && page.content().length < 3000 && waited < maxWaitSeconds do
        page.waitForTimeout(1000)
        waited += 1

      // DOM-to-Markdown 转换（空或太短则回退到 raw HTML）
      val markdown =
        try
          val md = page.evaluate(DOM_TO_MD_JS).asInstanceOf[String]
          if md != null && md.length >= 50 then md else page.content()
        catch case _: Exception => page.content()

      val finalUrl = page.url()
      logger.infoSync(
        "Browser fetched",
        "url" -> url.take(80),
        "status" -> status.toString,
        "title" -> title.take(50),
        "len" -> markdown.length.toString,
        "waited" -> waited.toString,
        "format" -> "markdown"
      )
      BrowserFetchResult(status, title, markdown, finalUrl, isMarkdown = true)
    catch
      case e: Exception =>
        // Global timeout or Playwright error — return partial result instead
        // of blocking the single-thread playwrightEC forever.
        logger.infoSync("Browser fetch error", "url" -> url.take(80), "error" -> e.getMessage.take(100))
        // Reset context: anti-bot timeouts can leave Chrome in a bad state.
        // Closing forces ensureContext to create a fresh context for the next call.
        try context.close()
        catch case _: Exception => ()
        context = null
        BrowserFetchResult(0, "Timeout", s"Page load failed for $url: ${e.getMessage}", url)
    finally
      try page.close()
      catch case _: Exception => ()
    end try
  end fetchWithPlaywright

  // ── 公开 API ────────────────────────────────────────────────────────

  /** 获取页面内容。优先使用 Obscura（轻量 + 内建 stealth），回退到 Playwright。 */
  def fetch(url: String, headless: Boolean, maxWaitSeconds: Int = 15): IO[BrowserFetchResult] =
    // Hard cap: maxWaitSeconds + 15s buffer for navigation + DOM conversion
    val hardTimeout = (maxWaitSeconds + 15).seconds
    // Obscura 优先（不需要 playwrightEC，独立进程）
    val io =
      if obscuraPath.isDefined then
        IO.blocking {
          fetchWithObscura(url, maxWaitSeconds)
        }.flatMap {
          case Some(result) => IO.pure(result)
          case None =>
            logger.infoSync("Obscura failed, falling back to Playwright", "url" -> url.take(80))
            IO.delay { fetchWithPlaywright(url, headless, maxWaitSeconds) }.evalOn(playwrightEC)
        }
      else IO.delay { fetchWithPlaywright(url, headless, maxWaitSeconds) }.evalOn(playwrightEC)
    // Timeout: anti-bot challenges can make Playwright block indefinitely on a
    // single-thread EC.  Return a graceful error instead of hanging forever.
    io.timeout(hardTimeout).recover { case _: java.util.concurrent.TimeoutException =>
      logger.infoSync("Browser fetch timed out", "url" -> url.take(80), "timeout" -> hardTimeout.toString)
      BrowserFetchResult(0, "Timeout", s"Page load timed out after $hardTimeout.", url)
    }

  end fetch

  /** 关闭浏览器和 Playwright，释放资源。 */
  def shutdown(): Unit =
    try
      if context != null then context.close()
      if playwright != null then playwright.close()
    catch case _: Exception => ()
    finally playwrightEC.shutdown()

end BrowserManager
