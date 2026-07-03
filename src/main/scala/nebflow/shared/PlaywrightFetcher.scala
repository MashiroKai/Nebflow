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

/**
 * Playwright browser fetcher — optional component.
 *
 * This object imports com.microsoft.playwright.*. If the Playwright JAR is not
 * on the classpath, loading this class throws NoClassDefFoundError, which
 * BrowserManager catches and gracefully degrades to Obscura-only mode.
 *
 * Uses system Chrome/Chromium with enhanced stealth injection and DOM-to-Markdown
 * conversion. Headless only.
 *
 * Design reference: https://github.com/h4ckf0r0day/obscura
 */
object PlaywrightFetcher extends BrowserFetcher:
  private val logger = NebflowLogger.forName("nebflow.playwright")

  private val dataDir =
    Option(System.getProperty("nebflow.browser.dataDir"))
      .map(Paths.get(_))
      .getOrElse(Paths.get(System.getProperty("user.home"), ".nebflow", "browser-data"))

  /** Single-thread EC: all Playwright operations must run on this thread. */
  private val playwrightEC = ExecutionContext.fromExecutorService(
    Executors.newSingleThreadExecutor { r =>
      val t = new Thread(r, "playwright-worker")
      t.setDaemon(true)
      t
    }
  )

  @volatile private var playwright: Playwright = uninitialized
  @volatile private var context: BrowserContext = uninitialized

  // ── Chrome detection ────────────────────────────────────────────────

  /** Detect system Chrome. */
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

  // ── Stealth scripts ─────────────────────────────────────────────────

  /**
   * Enhanced anti-detection script — inspired by Obscura's stealth mode.
   *
   * Covers: navigator.webdriver, userAgentData, Canvas/WebGL/Audio fingerprint
   * randomization, plugins/languages consistency, window.chrome, Permissions API.
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
   * DOM-to-Markdown conversion script — inspired by Obscura's LP.getMarkdown.
   *
   * Called via page.evaluate() to convert rendered DOM into clean Markdown.
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

  private def isChallengeTitle(title: String): Boolean =
    val t = title.toLowerCase
    t.contains("just a moment") || title.contains("请稍候") ||
    t.contains("attention required") || title.contains("access denied")

  // ── Playwright browser management ───────────────────────────────────

  private def ensureContext(): Unit =
    if context == null then
      Files.createDirectories(dataDir)

      val opts = new BrowserType.LaunchPersistentContextOptions()
        .setHeadless(true)
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

      // Global timeout: prevents anti-bot systems from blocking indefinitely.
      context.setDefaultTimeout(15_000.0)
      context.setDefaultNavigationTimeout(15_000.0)
      context.addInitScript(STEALTH_JS)

      logger.infoSync(
        "Playwright context started",
        "channel" -> detectChannel.getOrElse("chromium"),
        "stealth" -> "enhanced"
      )
  end ensureContext

  private def fetchWithPlaywright(url: String, maxWaitSeconds: Int): BrowserFetchResult =
    ensureContext()
    val page = context.newPage()

    try
      logger.infoSync("Playwright navigating", "url" -> url.take(80))
      // COMMIT wait strategy: fires as soon as response headers are received,
      // without waiting for JavaScript or DOM events.
      val response =
        page.navigate(url, new Page.NavigateOptions().setTimeout(15_000).setWaitUntil(WaitUntilState.COMMIT))
      val status = if response != null then response.status() else 0

      // Phase 1: wait for title
      var title = page.title()
      var waited = 0
      while (title.isEmpty || isChallengeTitle(title)) && waited < maxWaitSeconds do
        page.waitForTimeout(1000)
        waited += 1
        title = page.title()

      // Phase 2: wait for content
      while status != 403 && page.content().length < 3000 && waited < maxWaitSeconds do
        page.waitForTimeout(1000)
        waited += 1

      // DOM-to-Markdown conversion (fall back to raw HTML if too short)
      val markdown =
        try
          val md = page.evaluate(DOM_TO_MD_JS).asInstanceOf[String]
          if md != null && md.length >= 50 then md else page.content()
        catch case _: Exception => page.content()

      val finalUrl = page.url()
      logger.infoSync(
        "Playwright fetched",
        "url" -> url.take(80),
        "status" -> status.toString,
        "title" -> title.take(50),
        "len" -> markdown.length.toString,
        "waited" -> waited.toString
      )
      BrowserFetchResult(status, title, markdown, finalUrl, isMarkdown = true)
    catch
      case e: Exception =>
        // Timeout or Playwright error — return partial result and reset context.
        logger.infoSync("Playwright fetch error", "url" -> url.take(80), "error" -> e.getMessage.take(100))
        try context.close()
        catch case _: Exception => ()
        context = null
        BrowserFetchResult(0, "Timeout", s"Page load failed for $url: ${e.getMessage}", url)
    finally
      try page.close()
      catch case _: Exception => ()
    end try
  end fetchWithPlaywright

  // ── BrowserFetcher implementation ───────────────────────────────────

  def fetch(url: String, maxWaitSeconds: Int): IO[BrowserFetchResult] =
    IO.delay { fetchWithPlaywright(url, maxWaitSeconds) }.evalOn(playwrightEC)

  def shutdown(): Unit =
    try
      if context != null then context.close()
      if playwright != null then playwright.close()
    catch case _: Exception => ()
    finally playwrightEC.shutdown()

end PlaywrightFetcher
