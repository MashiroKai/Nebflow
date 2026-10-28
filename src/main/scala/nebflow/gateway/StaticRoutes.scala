/* 从 WebSocketRoutes 迁出(行为保持重构,2026-09-24)。 */
package nebflow.gateway

import cats.effect.IO
import fs2.Stream
import io.circe.Json
import nebflow.shared.{Branding, PathUtil}
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*
import org.http4s.headers.`Content-Type`

import scala.io.Source

object StaticRoutes:

  /**
   * W1 (mobile-quickwins 2026-10-20): which static tree the gateway feeds.
   * Single switch point for the static tree: only the "/" entry and the
   * /assets prefix choose between dist and dev sources; all other static
   * routes keep serving the web/ source tree (vendor passthrough is
   * byte-identical in both trees, and the C1 source-mode contract requires
   * web/ paths to stay green on prod instances too).
   */
  enum StaticSource:
    /** Dev source tree — the sbt run hot-edit default. */
    case DevTree

    /** esbuild bundle packed on the classpath (sbt -D<lower>.webdist=1 assembly). */
    case BundledDist

    /** esbuild bundle on disk (build/web-dist under the engine working dir). */
    case DiskDist(root: os.Path)
  end StaticSource

  /**
   * W1 resolution order (mobile-quickwins 2026-10-20):
   *   1. explicit dev flag — system property `<lowerName>.webdist` (legacy
   *      `nebflow.webdist` dual-read, same as build.sbt) set to dev / 0 /
   *      false forces the dev tree (the hot-edit escape hatch);
   *   2. classpath dist — the jar-packed bundle wins over disk (an assembly
   *      was a deliberate atomic packaging decision);
   *   3. disk dist — build/web-dist/index.html under the engine working dir,
   *      USED ONLY WHEN FRESH: no file under src/main/resources/web is newer
   *      than the dist index. This is what keeps `sbt run` hot-edit intact —
   *      the moment a web/ source is edited (or a merge touches the tree),
   *      the on-disk dist goes stale and the dev tree is served again until
   *      scripts/build-web.mjs is re-run (the P1 build.sbt guard's "must not
   *      silently serve the stale dist tree" ruling, enforced at runtime).
   *      A missing sources dir (installed runtime) counts as fresh.
   *   4. otherwise dev tree.
   *
   * Any filesystem surprise during the freshness walk is a conservative
   * fallback to the dev tree (never a partial dist).
   */
  def resolveStaticSource(
    lowerName: String,
    webdistProp: Option[String],
    classpathDist: Boolean,
    projectRoot: Option[os.Path]
  ): StaticSource =
    val devFlag = webdistProp.exists(v => Set("dev", "0", "false")(v.trim.toLowerCase))
    if devFlag then StaticSource.DevTree
    else if classpathDist then StaticSource.BundledDist
    else
      projectRoot.flatMap(diskDistRoot) match
        case Some(root) => StaticSource.DiskDist(root)
        case None => StaticSource.DevTree
  end resolveStaticSource

  /** Disk dist root under `root`, only when it exists AND is fresh vs sources. */
  private def diskDistRoot(root: os.Path): Option[os.Path] =
    val index = root / "build" / "web-dist" / "index.html"
    if os.isFile(index) && distFresh(index, root / "src" / "main" / "resources" / "web")
    then Some(index / os.up)
    else None

  /** Dist index mtime must be >= every file under the web/ source tree. */
  private def distFresh(index: os.Path, sources: os.Path): Boolean =
    try
      if !os.isDir(sources) then true // installed runtime: no sources to go stale against
      else
        val stamp = os.mtime(index)
        !os.walk(sources).exists(f => os.isFile(f) && os.mtime(f) > stamp)
    catch case _: Throwable => false
  end distFresh

  /**
   * Production resolution. Lazy — classpath and disk are fixed for the
   * JVM's lifetime, so this resolves once (first dereference at boot, when
   * the static routes compose). The decision is announced by GatewayMain's
   * boot chain via [[staticSourceDescription]] (NebflowLogger returns IO —
   * it can only fire inside an IO context, never from this lazy val).
   */
  lazy val staticSource: StaticSource =
    resolveStaticSource(
      Branding.lowerName,
      sys.props
        .get(s"${Branding.lowerName}.webdist")
        .orElse(sys.props.get("nebflow.webdist")),
      getClass.getClassLoader.getResource("web-dist/index.html") != null,
      Some(os.pwd)
    )
  end staticSource

  /** Boot-visibility line for GatewayMain: which static tree is being fed. */
  def staticSourceDescription: String =
    staticSource match
      case StaticSource.DevTree => "static tree: dev web/ sources" + staleDiskDistHint
      case StaticSource.BundledDist =>
        "static tree: bundled web-dist (classpath) — -D" + Branding.lowerName + ".webdist=dev forces the dev tree"
      case StaticSource.DiskDist(root) =>
        "static tree: disk web-dist at " + root + " (fresh vs web/ sources) — -D" + Branding.lowerName + ".webdist=dev forces the dev tree"
  end staticSourceDescription

  /**
   * console-load-perf 批 2026-10-21 — the silent-fallback visibility fix for the
   * 10-20 stranding: the engine restarted 19:20 chose the dev tree because the
   * disk dist was built at 20:05 (AFTER boot) and [[staticSource]] is a lazy
   * val resolved once — the boot line said "dev web/ sources" with zero hint
   * that a rebuild+restart would flip it. When the dev tree IS being fed and a
   * disk dist EXISTS but lost the freshness race, name the cause and the fix on
   * the boot line (a fresh dist never lands here — it would have been picked by
   * [[resolveStaticSource]]; so this hint only ever fires for the stale case,
   * and the intentional `-D.webdist=dev` hot-edit escape keeps its clean line).
   */
  private def staleDiskDistHint: String =
    try
      val index = os.pwd / "build" / "web-dist" / "index.html"
      if !os.isFile(index) then ""
      else
        val sources = os.pwd / "src" / "main" / "resources" / "web"
        val stale =
          if !os.isDir(sources) then 0
          else os.walk(sources).count(f => os.isFile(f) && os.mtime(f) > os.mtime(index))
        if stale == 0 then ""
        else
          s" — WARNING: disk dist build/web-dist is STALE ($stale web source files newer); " +
            "run scripts/build-web.mjs then restart to activate it"
    catch case _: Throwable => ""
  end staleDiskDistHint

  /**
   * L1 rebrand: the frontend brand contract. window.__BRAND__ is the only
   * brand source web/ may read; fields are append-only across rebrand
   * batches (initial contract: productName, lowerName, domain; L3 batch 3
   * appended homeDirName for the frontend's own legacy-path messaging;
   * 2026-09-01 login-chain fix appended profileUrl — the frontend's ONLY
   * URL input, consumed by activityBar.js to build the profile link;
   * brand-switch-console batch 2026-10-15 appended name/logoUrl/logoDarkUrl —
   * the avatar-letter slot and the two console logo asset references, each
   * optional on the frontend: older gateways that omit them leave every
   * consumer on its current-value fallback).
   * `domain` carries the default value (mystand.space); a `NEBFLOW_BRAND_DOMAIN`
   * env override may replace it (single-domain, 2026-09-07 naming ruling) —
   * display-only, never consumed to build a URL. `logoUrl`/`logoDarkUrl` are
   * reference slots of the same display-only class: <img> sources only.
   *
   * circe handles JSON string escaping; the serialized blob additionally
   * escapes the forward slash of "</" because it is inlined inside a
   * script element (script-tag breakout hardening; JSON permits `\/`).
   */
  private[gateway] def brandScriptTag: String =
    s"""<script>window.__BRAND__=${brandScriptJson(
        Branding.productName,
        Branding.lowerName,
        Branding.domain,
        Branding.homeDirName,
        Branding.profileUrl,
        Branding.name,
        Branding.logoUrl,
        Branding.logoDarkUrl
      )};</script>"""
  end brandScriptTag

  /**
   * Serialize the contract JSON (values injected for testability of the
   * escaping hardening). Field order is append-only: the five original
   * fields first, then the 2026-10-15 console slots.
   */
  private[gateway] def brandScriptJson(
    productName: String,
    lowerName: String,
    domain: String,
    homeDirName: String,
    profileUrl: String,
    name: String,
    logoUrl: String,
    logoDarkUrl: String
  ): String =
    Json
      .obj(
        "productName" -> Json.fromString(productName),
        "lowerName" -> Json.fromString(lowerName),
        "domain" -> Json.fromString(domain),
        "homeDirName" -> Json.fromString(homeDirName),
        "profileUrl" -> Json.fromString(profileUrl),
        "name" -> Json.fromString(name),
        "logoUrl" -> Json.fromString(logoUrl),
        "logoDarkUrl" -> Json.fromString(logoDarkUrl)
      )
      .noSpaces
      .replace("</", "<\\/")

  /**
   * Insert `snippet` directly before the LAST closing head tag — the one
   * structural anchor every index variant (dev source, esbuild dist) is
   * guaranteed to carry, emitted lowercase. None when the tag is absent:
   * callers serve the original bytes rather than guessing a fallback
   * position (a broken template should be visible, not papered over).
   */
  private[gateway] def injectBeforeHeadClose(html: String, snippet: String): Option[String] =
    val idx = html.lastIndexOf("</head>")
    if idx < 0 then None
    else Some(html.substring(0, idx) + snippet + html.substring(idx))
  end injectBeforeHeadClose

  /**
   * L1 rebrand: serve the index entry with the brand script injected.
   * Blocking classpath read of a tiny resource (a few KB) per request —
   * the same order of cost as the StaticFile.fromResource lookup it
   * replaces. Response contract: no-cache, no validators (the body is
   * content-generated, not a static file), text/html in UTF-8. The gzip
   * middleware wraps this route from the Router "/" mount, so it sees the
   * final injected bytes — compression order is correct by construction.
   *
   * The entity is written as a raw byte stream with an explicit
   * Content-Length — deliberately NOT Ok(String)/withEntity(String):
   * this file imports org.http4s.circe.CirceEntityCodec.* for the JSON
   * endpoints, and that import's String entity encoder (lexical scope)
   * wins over http4s' built-in one (implicit scope), silently encoding
   * the whole HTML as a JSON string literal (body starts with '"',
   * quotes escaped throughout — the page never boots). Raw bytes bypass
   * entity-encoder resolution entirely; pinned at the HTTP response
   * layer by IndexWithBrandServeSpec.
   */
  def indexWithBrand(indexResource: String): IO[Response[IO]] =
    IO.blocking(readClasspathResourceUtf8(indexResource)).flatMap {
      case None => NotFound()
      case Some(html) => brandInjectedResponse(html)
    }
  end indexWithBrand

  /**
   * W1: the disk twin of [[indexWithBrand]] — same brand injection, same
   * response contract (no-cache, no validators — the body is content-
   * generated, not the file bytes), fed from build/web-dist/index.html.
   * A read failure (dist vanished mid-boot, permissions) degrades to 404
   * rather than a 500 — the entry must stay a clean miss.
   */
  def indexWithBrandFile(file: os.Path): IO[Response[IO]] =
    IO.blocking(new String(os.read.bytes(file), java.nio.charset.StandardCharsets.UTF_8))
      .flatMap(brandInjectedResponse)
      .handleErrorWith(_ => NotFound())
  end indexWithBrandFile

  /** Shared index response build: brand injection + raw-bytes no-cache response. */
  private def brandInjectedResponse(html: String): IO[Response[IO]] =
    val served = injectBeforeHeadClose(html, brandScriptTag).getOrElse(html)
    val bytes = served.getBytes(java.nio.charset.StandardCharsets.UTF_8)
    IO.pure(
      Response[IO](Status.Ok)
        .withBodyStream(Stream.emits(bytes))
        .withContentType(`Content-Type`(MediaType.text.html, Charset.`UTF-8`))
        .putHeaders(
          "Content-Length" -> bytes.length.toString,
          "Cache-Control" -> "no-cache"
        )
    )
  end brandInjectedResponse

  /**
   * W1: the "/" entry point dispatching on [[staticSource]] — bundled
   * classpath dist, fresh disk dist, or the dev tree. Same brand injection
   * and response contract on every branch.
   */
  def indexResponse: IO[Response[IO]] = staticSource match
    case StaticSource.BundledDist => indexWithBrand("web-dist/index.html")
    case StaticSource.DiskDist(root) => indexWithBrandFile(root / "index.html")
    // 缓存击穿批：dev 树 index 走版本化改写（dist 树已是内容寻址形态，不动）。
    case StaticSource.DevTree => devIndexWithBrand
  end indexResponse

  // ── 隧道首开内联打包面（console-fastpath 批 2026-10-23·腿②「请求打包」）─────
  //
  // 诊断实锤（evidence/20261023_234431）：控制台整页 410 请求走跨境隧道，首开
  // 瀑布里最大的一段是 index.html 引用的全部 css/vendor（dev 树 ~45 条逐文件
  // 请求，每条两段跨境 RTT）。本面把**隧道脸**的 index 应答改成内联打包版：
  // 全部 stylesheet <link> 与 vendor defer <script> 的资源字节直接织进 HTML
  // （1 个请求 = HTML + 全量 CSS + 5 个 vendor 库），ES module 图（js/main.js
  // 起）保持逐文件——模块图无法在不引入 bundler 的前提下合并（前端无构建纪律），
  // 且隧道桥逐请求本就并发（PortalTunnelBridge 每周期独立 fiber）。
  //
  // 脸判别 = [[nebflow.shared.TunnelOriginMark.isMarked]]：进程自签标记、直连
  // 客户无法伪造 ⇒ 直连脸的 `/` 应答逐字节不变（既有 304 校验面不受影响），
  // 隧道脸拿打包体。dist 树（BundledDisk）不打包：JS 已是 esbuild bundle
  // （index + /assets 两段即全量）、资产 immutable 缓存，内联无收益。
  //
  // 转换纪律（[[packedIndexHtml]] 纯函数、spec 直读）：
  //   - 只动 `<link rel="stylesheet" href="css|vendor/...">` 与
  //     `<script defer src="vendor/..."></script>` 两种已知形状，其余标记
  //     （favicon、module script、brand 注入锚点）逐字保留；
  //   - 资源读取与 [[ConsoleAssetBatchRoutes]] 同口径（classpath web/ 树、
  //     单文件上限、缺失/超限 ⇒ 原 tag 原样回落，打包是加速面不是语义面）；
  //   - `<style>` 保留 media 属性（highlight 双主题 media 查询语义）；
  //   - CSS 的相对 url() 重写为绝对（内联 <style> 的解析基是文档而非 css
  //     文件目录——katex.min.css 的 fonts/ 引用必须带 /vendor/ 前缀）；
  //   - script/style 突破加固：`</script` `</style` 字面量转义（与
  //     [[brandScriptJson]] 同族纪律；vendor 树实扫无该字面量，纯保险）。

  /** `/` 与 `/index.html` 的请求分派面：隧道脸打包、直连脸原应答。 */
  def indexResponseFor(req: Request[IO]): IO[Response[IO]] =
    if nebflow.shared.TunnelOriginMark.isMarked(req) then packedIndexResponse
    else indexResponse

  private def packedIndexResponse: IO[Response[IO]] =
    staticSource match
      case StaticSource.DevTree =>
        IO.blocking(readClasspathResourceUtf8("web/index.html")).flatMap {
          case None => NotFound()
          // 打包在前（干净路径供 Pack*Re 捕获），版本化在后（css/vendor 已内联，
          // 只剩 module 入口 src 可版本化 —— 与 dev 脸同一改写函数，幂等形状类）。
          case Some(html) => brandInjectedResponse(versionedIndexHtml(packedIndexHtml(html)))
        }
      case _ => indexResponse
  end packedIndexResponse

  private val PackStylesheetRe = """<link rel="stylesheet" href="((?:css|vendor)/[^"]+)"([^>]*)>""".r
  private val PackVendorScriptRe = """<script defer src="(vendor/[^"]+)"></script>""".r
  private val PackMediaAttrRe  = """media="([^"]*)"""".r

  /** CSS 相对 url() 捕获（绝对/http(s)/data/协议相对/锚引用不碰）。 */
  private val CssRelativeUrlRe = """url\(\s*(?!['"]?(?:data:|https?:|//|/|#))([^)]+)\)""".r

  /** 匹配重建助手：绕开 [[scala.util.matching.Regex.replaceAllIn]] 的替换串
    * 语义（`$` 被当组引用展开——vendor JS 里的 `$` 模板字面量即触发
    * Illegal group reference）。重建是纯拼接：f 的返回值逐字进结果串。 */
  private def rebuildMatches(target: String, re: scala.util.matching.Regex)(
    f: scala.util.matching.Regex.Match => String
  ): String =
    // 注意：不可写三参形式 `sb.append(target, last, m.start)` —— Scala 3.5.2 对
    // scala.collection.mutable.StringBuilder 会把它tupling成 append(Tuple3)
    // （元组 toString 整串进结果），必须显式 slice 出 String 再单参 append。
    val sb = new StringBuilder
    var last = 0
    for m <- re.findAllMatchIn(target) do
      sb.append(target.slice(last, m.start))
      sb.append(f(m))
      last = m.end
    sb.append(target.slice(last, target.length))
    sb.toString

  /** [[packedIndexHtml]] 的 CSS url() 重写（纯函数，spec 直读）。 */
  private[gateway] def rewriteCssUrls(css: String, baseDir: String): String =
    rebuildMatches(css, CssRelativeUrlRe) { m =>
      val inner  = m.group(1).trim
      val quote  = if inner.startsWith("\"") then "\"" else if inner.startsWith("'") then "'" else ""
      val bare   = if quote.isEmpty then inner else inner.stripPrefix(quote).stripSuffix(quote)
      val cut    = bare.indexWhere(c => c == '?' || c == '#')
      val (path, suffix) = if cut < 0 then (bare, "") else bare.splitAt(cut)
      if path.isEmpty then m.matched
      else s"""url($quote$baseDir$path$suffix$quote)"""
    }

  /** 隧道首开打包转换（纯函数）：逐字保留未知形状，单资源失败回落原 tag。 */
  private[gateway] def packedIndexHtml(html: String): String =
    val afterCss = rebuildMatches(html, PackStylesheetRe) { m =>
      val path  = m.group(1)
      val attrs = m.group(2)
      ConsoleAssetBatchRoutes.readCapped(s"web/$path", ConsoleAssetBatchRoutes.MaxBytesPerAsset) match
        case None => m.matched
        case Some(bytes) =>
          val dir = path.substring(0, path.lastIndexOf('/') + 1)
          val css = rewriteCssUrls(
            new String(bytes, java.nio.charset.StandardCharsets.UTF_8),
            "/" + dir
          ).replace("</style", "<\\/style")
          val open = PackMediaAttrRe.findFirstMatchIn(attrs) match
            case Some(mm) => s"""<style media="${mm.group(1)}">"""
            case None     => "<style>"
          open + css + "</style>"
    }
    rebuildMatches(afterCss, PackVendorScriptRe) { m =>
      ConsoleAssetBatchRoutes.readCapped(s"web/${m.group(1)}", ConsoleAssetBatchRoutes.MaxBytesPerAsset) match
        case None => m.matched
        case Some(bytes) =>
          val js = new String(bytes, java.nio.charset.StandardCharsets.UTF_8)
            .replace("</script", "<\\/script")
          "<script>" + js + "</script>"
    }
  end packedIndexHtml

  // ── 静态资源缓存击穿批（2026-10-25 作者令④）：index 引用版本化 ─────────
  //
  // dev 树 index.html 里每个 css/…、vendor/…（stylesheet/defer script）与
  // js/…（module 入口）引用改写为 `<path>?v=<内容指纹>`（[[AssetVersions.versionOf]]
  // 现算，file: 资源 mtime 变即重算 ⇒ sbt run 热编辑下一导航即新 URL）。带版本
  // URL 由静态路由面回 immutable（[[assetCacheControl]]）—— 修完即生效：手机端
  // 旧缓存条目按旧键（裸路径）留在原地，index（no-cache）永拿最新引用。
  //
  // 转换纪律（[[versionedIndexHtml]] 纯函数、spec 直读）：
  //   - 只动两种已知形状：`href="(css|vendor)/…"（stylesheet link）与
  //     `src="(vendor|js)/…"（script）—— favicon 族引用自带静态 digest（?v=…）
  //     且前缀不匹配，零触碰；未知形状逐字保留（packedIndexHtml 同族回落纪律）；
  //   - 单资源指纹失败（缺文件/IO 意外）⇒ 原 tag 原样回落（裸路径 ⇒ no-cache
  //     语义自动接管，页面行为与本批之前逐字节一致）；
  //   - 幂等：已带 `?v=` 的引用（二次改写/packed 面）不匹配形状类，零触碰；
  //   - dist 脸（BundledDist/DiskDist）不走本改写 —— esbuild 产物的 app 代码
  //     已是 assets/app-[hash] 内容寻址形态，vendor 面维持现状（bare ⇒ no-cache）。
  //
  // 模块图边界（刻意不做）：ES module 内部相对 import 指示符不带版本参数，
  // 本批不改任何 js 字节 —— 其新鲜度由 sw.js 索引指纹链（index 变 ⇒ 清静态
  // 缓存 + 补导航）+ 裸路径 no-cache 重验证保底。

  /** stylesheet link：`<link rel="stylesheet" href="css|vendor/...">` 的 href 值。 */
  private val VerHrefRe = """(href=")((?:css|vendor)/[^"?]+)(")""".r

  /** script 引用：defer vendor 与 module 入口（js/main.js、js/mobile/bootstrap.js）。 */
  private val VerSrcRe = """(src=")((?:vendor|js)/[^"?]+)(")""".r

  private def versionTag(path: String): Option[String] =
    AssetVersions.versionOf(s"web/$path").map(h => s"?v=$h")

  /** index 资源引用版本化（纯函数）：命中形状插 `?v=<指纹>`，未命中原样保留。 */
  private[gateway] def versionedIndexHtml(html: String): String =
    val afterHref = rebuildMatches(html, VerHrefRe) { m =>
      versionTag(m.group(2)) match
        case None    => m.matched
        case Some(v) => s"${m.group(1)}${m.group(2)}$v${m.group(3)}"
    }
    rebuildMatches(afterHref, VerSrcRe) { m =>
      versionTag(m.group(2)) match
        case None    => m.matched
        case Some(v) => s"${m.group(1)}${m.group(2)}$v${m.group(3)}"
    }
  end versionedIndexHtml

  /** dev 树 index 应答：版本化 + brand 注入（响应契约同 [[indexWithBrand]]）。 */
  private[gateway] def devIndexWithBrand: IO[Response[IO]] =
    IO.blocking(readClasspathResourceUtf8("web/index.html")).flatMap {
      case None => NotFound()
      case Some(html) => brandInjectedResponse(versionedIndexHtml(html))
    }
  end devIndexWithBrand

  private def readClasspathResourceUtf8(name: String): Option[String] =
    Option(getClass.getClassLoader.getResourceAsStream(name)).map { in =>
      val source = Source.fromInputStream(in, "UTF-8")
      try source.mkString
      finally in.close()
    }

  /**
   * P1: serve the bundled asset tree (any depth under the /assets prefix —
   * hashed entry and lazy chunks produced by build-web.mjs). W1
   * (mobile-quickwins 2026-10-20): dispatches on [[staticSource]] —
   *   · BundledDist: classpath web-dist/assets (jar-packed, unchanged);
   *   · DiskDist: the same tree read from build/web-dist/assets on disk
   *     (the sbt run leg — same esbuild output, same guard chain, plus a
   *     realpath-containment fence mirroring uploadsServe);
   *   · DevTree: 404 (unchanged — the dev index never references /assets,
   *     every hashed name is a dist-build artifact).
   *
   * W2 (same batch): the hashed names ARE the cache key — the response is
   * `public, max-age=31536000, immutable`. The old no-cache turned every
   * revisit into ~200 validator round-trips (tunnel: ~1 RTT each — the #2
   * root cause in evidence/20261019_183728_mobile-boot-waterfall). A rebuilt
   * dist changes the hashes, so immutability can never serve stale bytes;
   * index.html stays no-cache and is the sole freshness judge.
   *
   * Guard pattern copied from jsRoutes: trailing-slash (directory request)
   * is rejected, path traversal (`..` and backslash) is rejected, and the
   * lookup is a plain resource/file join.
   *
   * Standalone via [[assetsRoutesFor]] so it is directly unit-testable;
   * composed ahead of the instance routes like jsRoutes.
   */
  def assetsRoutes: HttpRoutes[IO] = assetsRoutesFor(staticSource)

  def assetsRoutesFor(source: StaticSource): HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ GET -> _ if req.uri.path.renderString.startsWith("/assets/") =>
      if req.uri.path.endsWithSlash then NotFound()
      else
        val relParts = req.uri.path.segments.map(_.encoded).toList.drop(1) // drop "assets"
        if relParts.isEmpty || relParts.last.isEmpty then NotFound()
        else if relParts.exists(s => s == ".." || s.contains("\\")) then NotFound()
        else
          val path = relParts.mkString("/")
          source match
            case StaticSource.BundledDist =>
              StaticFile
                .fromResource(s"web-dist/assets/$path", Some(req))
                .map(_.putHeaders("Cache-Control" -> HashedAssetCacheControl))
                .getOrElseF(NotFound())
            case StaticSource.DiskDist(root) =>
              val base = root / "assets"
              val file = base / os.RelPath(path)
              if file.startsWith(base) && os.isFile(file) then
                StaticFile
                  .fromPath(fs2.io.file.Path(file.toString), Some(req))
                  .map(_.putHeaders("Cache-Control" -> HashedAssetCacheControl))
                  .getOrElseF(NotFound())
              else NotFound()
            case StaticSource.DevTree => NotFound()
          end match
        end if
      end if
  }

  /** W2: hashed dist assets are content-addressed — cache for a year, immutable. */
  val HashedAssetCacheControl: String = "public, max-age=31536000, immutable"

  /**
   * 静态资源缓存击穿批（2026-10-25 作者令④）：shipped 静态树在**内容寻址版本化
   * URL**（index 引用带 `?v=<内容指纹>`，[[AssetVersions.versionOf]] 现算）下的
   * 长效缓存策略 —— 与 [[HashedAssetCacheControl]] 同值：URL 含内容哈希 ⇒ 内容
   * 变则 URL 变，immutable 永远不可能 serve 到旧字节；index.html 仍 no-cache 且
   * 是唯一新鲜裁判。裸路径请求（无版本参数）走 `no-cache`（[[assetCacheControl]]
   * 的另一腿）：ES module 图内的相对 import 不带版本参数，其新鲜度由既有 sw.js
   * 索引指纹链 + no-cache 重验证保底，本批不改模块图字节。
   */
  val ShippedAssetCacheControl: String = "public, max-age=31536000, immutable"

  /**
   * 静态资产应答的 Cache-Control 单点：请求带非空 `v` 版本参数 ⇒ immutable
   * 长效（内容寻址，安全）；裸请求 ⇒ no-cache（现状语义，热编辑/重验证保底）。
   * 消费面 = jsRoutes / vendorSubtree（本文件）与 WebSocketRoutes 的
   * css/vendor/favicon 族 case。
   */
  def assetCacheControl(req: Request[IO]): String =
    if req.params.get("v").exists(_.nonEmpty) then ShippedAssetCacheControl
    else "no-cache"

  /**
   * G1: serve user-uploaded attachments from
   * `~/.nebflow/uploads/<sid>/<file>`. Restored session history (ui.json)
   * records attachments as {name,type,path}; without a route serving the
   * uploads dir those images are unrenderable after a restart.
   *
   * Authenticated — unlike the voice-models route, uploads are user
   * screenshots (sensitive). Phase 3b: the unified GatewayAuth criterion
   * under StaticAuth — session-cookie driven; a presented-but-invalid rail
   * falls through instead of rejecting. A malicious page cross-site
   * <img>-probing the localhost gateway gets no SameSite cookie sent, while
   * the same-origin frontend attaches the session cookie automatically.
   * Stage D retired the legacy file-token rails (legacy cookie + token
   * query param) — the session cookie is the one credential this face
   * accepts.
   *
   * Lives in the companion (composed ahead of the instance routes) because
   * it touches none of the class dependencies — testable with just a
   * session store.
   */
  def uploadsRoutes(tokenRef: cats.effect.Ref[IO, String], sessions: GatewaySessionStore): HttpRoutes[IO] =
    HttpRoutes.of[IO] {
      case req @ GET -> _ if req.uri.path.renderString.startsWith("/uploads/") =>
        tokenRef.get
          .flatMap(t => GatewayAuth.authenticate(req, t, sessions, GatewayAuth.Rails.StaticAuth))
          .flatMap {
            case None => Forbidden("Invalid token")
            case Some(_) =>
              uploadsServe(req)
          }
    }

  /**
   * The serving leg of uploadsRoutes (verbatim from the pre-3b body): the
   * percent-decode + traversal-guard + realpath-containment chain. Split out
   * only so the criterion wrapper above stays readable — behavior is
   * byte-identical.
   */
  private def uploadsServe(req: Request[IO]): IO[Response[IO]] =
    // Percent-DECODE each segment before the lookup (refresh-image fix,
    // 2026-10-09): the wire path is always percent-encoded (browsers encode
    // <img src> per WHATWG; the frontend's attachmentImageUrl
    // encodeURIComponent's each part) while the on-disk name is the decoded
    // form. Matching on `encoded` looked up the literal `%E6…%20…` spelling
    // and 404'd every upload whose name has non-ASCII characters or spaces —
    // i.e. every macOS screenshot (「截屏2026-10-09 07.10.22.jpg」), which is
    // exactly why restored history showed broken-image placeholders while the
    // pre-refresh live row (inline data: preview) rendered fine.
    // `Segment.decoded` defaults (bytecode-verified, 0.23.30): UTF-8,
    // plusIsSpace=false (a literal `+` stays `+` on a path — query-string
    // form semantics must NOT leak here), nothing skipped. Decoding happens
    // BEFORE the guards below so a `%2E%2E` evasion decodes INTO them.
    val segs = req.uri.path.segments.map(s => s.decoded()).toList
    // Path shape: <sid>/<filename> — two segments after "uploads", or (askuser-dragdrop
    // 实施批 2026-10-19，批 #551) <sid>/ask-<requestId>/<filename> — three segments:
    // card-scoped answer attachments live in an extra layer under the same base and
    // are served by the SAME percent-decode + traversal-guard chain below.
    if segs.sizeIs != 3 && segs.sizeIs != 4 then NotFound()
    else
      val relParts = segs.drop(1) // drop "uploads"
      // Block path traversal (same guard style as voice-models). Post-decode
      // additions: a segment that DECODES to something carrying `/` (from
      // %2F — no real POSIX filename contains one, and `../uploads/…` would
      // re-enter the base through os.RelPath up-segments) or NUL (from %00 —
      // java.nio would throw InvalidPathException → 500) can only be an
      // evasion, so it gets the same uniform 404 the old literal lookup
      // produced for both.
      if relParts
          .exists(s => s == ".." || s.contains("\\") || s.contains('/') || s.contains('\u0000'))
      then NotFound()
      else
        val uploadsBase = PathUtil.dataRoot / "uploads"
        val filePath = uploadsBase / os.RelPath(relParts.mkString("/"))
        // Final defense: the resolved path must stay under the base
        if filePath.startsWith(uploadsBase) && os.exists(filePath) && os.isFile(filePath) then
          StaticFile
            .fromPath(fs2.io.file.Path(filePath.toString), Some(req))
            .map { r =>
              // 二次加载清剿批（2026-10-24）：#538 同款确定性浏览器缓存。本路由
              // 一直带 ETag + Last-Modified（StaticFile）并应答 304，但从未发
              // Cache-Control ⇒ 重复消费按启发式新鲜度走（RFC 9111 §4.2.1，截图类
              // 新文件 ≈ 秒级窗），媒体脸每次重渲都把同一体再拉一遍。
              // `private, max-age=300`：5 分钟内零字节往返，之后一次条件往返 304；
              // `private`（带会话凭据的鉴权路由，绝不进共享缓存）。只打在可缓存
              // 正答上（200 体 / 304 须重申策略）；404 腿不动（minimal-diff）。
              if r.status == Status.Ok || r.status == Status.NotModified then
                r.putHeaders(
                  Header.Raw(org.typelevel.ci.CIString("Cache-Control"), "private, max-age=300")
                )
              else r
            }
            .getOrElseF(NotFound())
        else NotFound()
    end if

  end uploadsServe

  /**
   * canvas-media Wave1 (2026-10-05, OD-2=V-C): tokenized static route for
   * plugin EXECUTOR assets — `/api/plugin-assets/<name>/<rel…>`. The small
   * viewers.json control-plane declaration rides inline with
   * `GET /api/plugins` (PluginRegistry.approvalManifest) instead; everything
   * heavy (wasm bundles, renderer scripts) is served here so the 6s plugins
   * poll never re-ships plugin bytes.
   *
   * Contract (canvas-media plan §5.3; guard style copied from
   * uploadsRoutes + the WebSocketRoutes /agents/ precedent, tightened to
   * realpath containment):
   *  - Trusted plugins ONLY. `trustedPluginDir` resolves the registry
   *    entry's directory; unknown or blocked names get the same uniform
   *    404 as a missing file (no existence oracle for blocked packages).
   *  - Session REQUIRED: the same StaticAuth face as uploadsRoutes — the
   *    HttpOnly session cookie is the one credential (stage D retired the
   *    legacy file-token rails; a cross-site <img>-probe gets no SameSite
   *    cookie, while the same-origin frontend attaches the session cookie
   *    automatically).
   *  - Manual segment parsing (http4s DSL matches single segments only):
   *    trailing slash rejected, `..` and backslash segments rejected in
   *    the plugin name and the relative parts, and the FINAL defense is
   *    realpath containment under the plugin dir — the shared
   *    PluginRegistry.containedUnder fence (single judge, no second
   *    policy), so a symlink planted inside the package cannot point this
   *    route outside the plugin root.
   *
   * Standalone (the registry lookup is injected) so it is directly
   * unit-testable (PluginAssetsRoutesSpec), like uploadsRoutes; composed
   * into the /api Router in GatewayMain.
   *
   * Mount-form agnostic path source (plugin-assets-404-fix, 2026-10-07): the
   * guard and the segment parse read `req.pathInfo` — the path RELATIVE TO
   * THE CURRENT MOUNT — never `req.uri.path`, which keeps the absolute form.
   * This closed the production 404 seam: the route composes under
   * GatewayMain's `Router("/api" -> …)`, and http4s hands mounted routes a
   * request whose `uri.path` still carries the `/api` prefix while
   * `pathInfo` is the stripped form — the same form the DSL cases of every
   * sibling route in that Router (ConfigRoutes `Root / "config"`, …) match
   * on. A guard written against `uri.path` therefore NEVER matched in
   * production (`/api/plugin-assets/…` answered 404 on every real instance:
   * the frontend Plugin.md probe URL) while the direct-call spec family
   * (unmounted, pathInfo == uri.path) stayed green, masking the seam.
   * Reading `pathInfo` serves BOTH mount forms with one judge: prefixed
   * under the Router (production), bare when composed directly (specs).
   */
  def pluginAssetsRoutes(
    tokenRef: cats.effect.Ref[IO, String],
    sessions: GatewaySessionStore,
    trustedPluginDir: String => IO[Option[os.Path]]
  ): HttpRoutes[IO] =
    HttpRoutes.of[IO] {
      case req @ GET -> _ if req.pathInfo.renderString.startsWith("/plugin-assets/") =>
        tokenRef.get
          .flatMap(t => GatewayAuth.authenticate(req, t, sessions, GatewayAuth.Rails.StaticAuth))
          .flatMap {
            case None => Forbidden("Invalid token")
            case Some(_) =>
              // Trailing slash = directory request — this route serves files only.
              if req.pathInfo.endsWithSlash then NotFound()
              else pluginAssetsServe(req, trustedPluginDir)
          }
    }

  /** The serving leg of pluginAssetsRoutes (verbatim from the pre-3b body). */
  private def pluginAssetsServe(
    req: Request[IO],
    trustedPluginDir: String => IO[Option[os.Path]]
  ): IO[Response[IO]] =
    val segs = req.pathInfo.segments.map(_.encoded).toList
    // Shape: /plugin-assets/<name>/<rel…> — at least one rel segment.
    if segs.sizeIs < 3 || relPartsEmpty(segs.drop(2)) then NotFound()
    else
      val pname = segs(1)
      val relParts = segs.drop(2)
      // Block path traversal in the plugin name and the relative parts
      if pname.contains("..") || pname.contains("\\") || relParts.exists(s => s == ".." || s.contains("\\"))
      then NotFound()
      else
        trustedPluginDir(pname).flatMap {
          case None => NotFound()
          case Some(dir) =>
            val filePath = dir / os.RelPath(relParts.mkString("/"))
            // Final defense: realpath containment under the plugin dir
            if nebflow.core.plugin.PluginRegistry.containedUnder(dir, filePath) && os.exists(filePath) && os
                .isFile(filePath)
            then StaticFile.fromPath(fs2.io.file.Path(filePath.toString), Some(req)).getOrElseF(NotFound())
            else NotFound()
        }
    end if

  end pluginAssetsServe

  /** jsRoutes-style emptiness guard: no segments at all, or a trailing empty one. */
  private def relPartsEmpty(relParts: List[String]): Boolean =
    relParts.isEmpty || relParts.last.isEmpty

  /**
   * Vendor SUBTREE passthrough (canvas-office-render-fix batch, 2026-10-06)
   * — the depth ≥ 2 shapes under /vendor/ that none of the WebSocketRoutes
   * vendor cases match. The pptx-preview MVP vendored
   * `web/vendor/pptx-renderer/` (7 files, commit 0adfef219) and its viewer
   * fetches the renderer library (aiden0z-pptx-renderer.browser.es.js) under
   * that directory at runtime; the directory-specific
   * cases (monaco/pdfjs/fonts) plus the single-segment case cannot match a
   * 3-segment non-monaco/pdfjs path, so all 7 files answered 404 on a real
   * instance and the renderer never loaded. That is the SAME outage class
   * the [[jsRoutes]] scaladoc documents for /js ("every new directory needed
   * its own hand-written case") — third occurrence on /vendor (monaco →
   * pdfjs → pptx-renderer) — so instead of a fourth per-directory case, the
   * remaining vendor tree is served here, exactly like jsRoutes serves the
   * remaining js tree.
   *
   * Scope is unchanged in kind: `web/vendor/` holds only app-shipped public
   * assets (the same classpath tree the single-segment case already serves
   * one level of). Precedence over monaco/pdfjs/fonts/single-file paths is
   * defined by the caller's case position in WebSocketRoutes (this function
   * is the gap-filler and accepts any depth); guards are the monaco/pdfjs
   * precedent (traversal rejected, trailing slash rejected) plus jsRoutes'
   * `no-cache` — a rebuilt classpath vendor tree must be picked up by the
   * browser, and StaticFile's ETag keeps the revalidation a cheap 304.
   * Content-Type still derives from the file extension via StaticFile.
   *
   * Standalone (zero class deps) so it is directly unit-testable
   * (VendorSubtreeRoutesSpec), like jsRoutes.
   */
  def vendorSubtree(req: Request[IO]): IO[Response[IO]] =
    if req.uri.path.endsWithSlash then NotFound()
    else
      val segs = req.uri.path.segments.map(_.encoded).toList.drop(1) // drop "vendor"
      if segs.isEmpty || segs.last.isEmpty then NotFound()
      else if segs.exists(s => s == ".." || s.contains("\\")) then NotFound()
      else
        val path = segs.mkString("/")
        StaticFile
          .fromResource(s"web/vendor/$path", Some(req))
          .map(_.putHeaders("Cache-Control" -> assetCacheControl(req)))
          .getOrElseF(NotFound())
      end if
    end if
  end vendorSubtree

  /**
   * Brand asset subtree passthrough (stand-logo-slotting batch, 2026-10-15):
   * serves `web/brand/` at ANY depth — the slot tree for rebrand asset kits
   * (`web/brand/stand/…`, the slotted mono kit). The optional __BRAND__
   * logoUrl/logoDarkUrl reference slots (display-only <img> sources, see the
   * brandScriptTag scaladoc) are free strings and may point into this tree
   * via brand.conf/env on switch day — without this route every /brand/…
   * reference 404s (the tree was previously unrouted), which is exactly the
   * outage class [[vendorSubtree]] closed for /vendor (per-directory cases
   * grew one hand-written case at a time). Same gap-filler shape and guard
   * set as vendorSubtree: trailing slash (directory request) rejected, ".."
   * and backslash segments rejected, classpath resource join, no-cache at
   * every depth (a rebuilt classpath tree must be picked up; StaticFile's
   * ETag keeps revalidation a cheap 304). The incumbent /css/logo-* files
   * are NOT touched by this route — it only makes the NEW slot tree
   * reachable; default brand rendering is byte-identical (before slotting,
   * the whole /brand/ tree answered 404 and nothing referenced it).
   *
   * Standalone (zero class deps) so it is directly unit-testable
   * (BrandSubtreeRoutesSpec), like vendorSubtree/jsRoutes.
   */
  def brandSubtree(req: Request[IO]): IO[Response[IO]] =
    if req.uri.path.endsWithSlash then NotFound()
    else
      val segs = req.uri.path.segments.map(_.encoded).toList.drop(1) // drop "brand"
      if segs.isEmpty || segs.last.isEmpty then NotFound()
      else if segs.exists(s => s == ".." || s.contains("\\")) then NotFound()
      else
        val path = segs.mkString("/")
        StaticFile
          .fromResource(s"web/brand/$path", Some(req))
          .map(_.putHeaders("Cache-Control" -> "no-cache"))
          .getOrElseF(NotFound())
      end if
    end if
  end brandSubtree

  /**
   * Serves everything under web/js at ANY depth. The frontend's ES modules
   * live at /js/<file>.js, /js/locales/<lang>.js and /js/viewers/<name>.js,
   * and any future subdirectory — this closes the "add a js subdirectory →
   * every dynamic import 404s" outage class for good. The per-path routes
   * it replaces (in-class single-segment /js, the /js/locales case, and the
   * viewers-only route from 4281f720) each fixed one symptom after the
   * fact: http4s DSL matches single path segments, so every new directory
   * needed its own hand-written case.
   *
   * Manual segment parsing (same pattern and guard style as the monaco
   * route): ".." and backslash segments are rejected; everything else is
   * joined and looked up as a classpath resource. Cache-Control at EVERY
   * depth via [[assetCacheControl]]（静态资源缓存击穿批 2026-10-25）：带版本
   * 参数（index 引用的 `?v=<内容指纹>` 形态）⇒ immutable 长效；裸请求（模块
   * 图相对 import）⇒ no-cache 重验证保底。此前全深度 no-cache 的重验证往返
   * （隧道腿每资产一跳）由版本化 URL 的 immutable 直出消解。
   *
   * Standalone (zero class deps) so it is directly unit-testable
   * (JsStaticRoutesSpec); composed ahead of the instance routes like
   * uploadsRoutes.
   */
  def jsRoutes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ GET -> _ if req.uri.path.renderString.startsWith("/js/") =>
      // Trailing slash = directory request (e.g. /js/viewers/) — this route
      // serves files only; without the guard StaticFile happily serves the
      // classpath directory entry itself (200 with junk).
      if req.uri.path.endsWithSlash then NotFound()
      else
        val relParts = req.uri.path.segments.map(_.encoded).toList.drop(1) // drop "js"
        if relParts.isEmpty || relParts.last.isEmpty then NotFound()
        else if relParts.exists(s => s == ".." || s.contains("\\")) then NotFound()
        else
          val path = relParts.mkString("/")
          StaticFile
            .fromResource(s"web/js/$path", Some(req))
            .map(_.putHeaders("Cache-Control" -> assetCacheControl(req)))
            .getOrElseF(NotFound())
        end if
      end if
  }

end StaticRoutes
