package nebflow.gateway

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*

import java.util.Base64

/**
 * 静态资产批量打包面（console-fastpath 批 2026-10-23，作者令腿②「请求打包」）：
 * `POST /api/console-assets` 一次请求打包至多 [[MaxPathsPerRequest]] 个小静态
 * 资产，消灭控制台冷启「每资产一条隧道往返」的串行 RTT 墙（诊断实锤：整页
 * 410 请求/14.1MB 全走浏览器→relay→隧道→网关，每请求两段跨境 RTT）。
 *
 * 直白回答（作者问「400多个请求能打包一起发送吗」）：能——本端点把首开
 * 瀑布里最大的静态段（index.html 引用的全部 css/vendor/favicon，实测 ~37-45
 * 个请求）压成 **1 个请求**；ES module 图（~140 个）不受打包影响，但隧道桥本就
 * 逐请求并发（PortalTunnelBridge 每周期独立 fiber），打包后首开请求墙从
 * ~410 降到 ~1 打包 + module 图 + API 面。
 *
 * 访问语义与静态树逐字等价：
 *   - **白名单前缀**：只打包公开静态资产（`css/` `js/` `vendor/` `brand/` 前缀
 *     + 根 favicon 族）——与 jsRoutes/vendorSubtree/brandSubtree/css case 服务
 *     的同一 classpath `web/` 树、同一 traversal 守卫（逐段拒绝 `..`/反斜杠），
 *     不打包 index.html（brand 注入应答，语义非纯静态）与 sw.js（注册语义）。
 *     公开面不加鉴权（与静态树同纪律：本来就是公开字节，打包不改变访问语义）；
 *   - **逐文件来源 = classpath `web/<path>`**：与 StaticFile.fromResource 同一
 *     来源、同一新鲜度（dev 树热改即时生效；dist 装配走 bundled 资源）；
 *   - 体积护栏：单文件 ≤ [[MaxBytesPerAsset]]、整包 ≤ [[MaxBytesTotal]]（超限
 *     路径进 `missing[]`，浏览端回落逐文件取——大文件走打包无收益）；
 *   - 响应 JSON：`{assets: {path: {contentType, body(base64)}}, missing: [...]}`。
 *     base64 是 4/3 膨胀面，换来「一条隧道周期」，跨境 RTT 语义下净赚一个
 *     数量级；POST 响应不被浏览器 HTTP 缓存（新鲜度免费）。
 *
 * 挂载面：[[RestApiRoutes.routes]] 的 `/api` 级联（与隧道面共享同一 lifted
 * Router ⇒ 隧道/直连两个脸自动都有此面）。
 */
private[gateway] object ConsoleAssetBatchRoutes:

  /** 单请求至多打包的路径数（浏览端按此分页）。 */
  val MaxPathsPerRequest: Int = 64

  /** 单文件上限：大于它的资产打包无收益（膨胀 + 内存），回落逐文件取。 */
  val MaxBytesPerAsset: Int = 512 * 1024

  /** 整包原始字节上限（打包响应基线保护）。 */
  val MaxBytesTotal: Int = 8 * 1024 * 1024

  /** 路径最大段数（与静态树实际深度同量级，防滥用长路径）。 */
  private val MaxSegments = 8

  /** 根目录可打包文件名（favicon 族——index.html 首开引用）。 */
  private val RootFiles: Set[String] = Set(
    "favicon-16.png",
    "favicon-32.png",
    "favicon-180.png",
    "favicon-192.png",
    "favicon-512.png",
    "favicon.ico",
    "favicon.svg"
  )

  /** 可打包前缀（公开静态子树，与静态路由服务面一一对应）。 */
  private val Prefixes: List[String] = List("css/", "js/", "vendor/", "brand/")

  /**
   * 打包路径判据（纯函数，spec 直读）：白名单前缀或根文件名 + 段级 traversal
   * 守卫。`..`/反斜杠/空段一律 false——与静态路由的 404 守卫同口径，
   * 判 false 的路径进 `missing[]`（无存在性预言面）。
   */
  private[gateway] def isBatchablePath(raw: String): Boolean =
    if raw == null then false
    else
      val p = raw.trim.stripPrefix("/")
      if p.isEmpty || p.length > 512 then false
      else if RootFiles.contains(p) then true
      else if !Prefixes.exists(p.startsWith) then false
      else
        val segs = p.split('/').toList
        segs.nonEmpty && segs.lengthIs <= MaxSegments &&
        segs.forall(s => s.nonEmpty && s != ".." && !s.contains('\\'))

  /** 扩展名 → Content-Type（与 StaticFile 的推导同词表）。 */
  private[gateway] def contentTypeOf(path: String): String =
    val ext = path.lastIndexOf('.') match
      case i if i >= 0 && i < path.length - 1 => path.substring(i + 1).toLowerCase(java.util.Locale.ROOT)
      case _ => ""
    MediaType
      .forExtension(ext)
      .map(mt => s"${mt.mainType}/${mt.subType}")
      .getOrElse("application/octet-stream")

  /**
   * 读一个白名单资源的原始字节，上限护栏：读至多 `limit + 1` 字节——超限时
   * 返回 `None`（调用方计 missing），不会把超大文件整读进内存。
   * package 可见：StaticRoutes 的隧道打包面（[[StaticRoutes.packedIndexHtml]]）
   * 复用同一读取语义（同一上限纪律）。
   */
  private[gateway] def readCapped(resourcePath: String, limit: Int): Option[Array[Byte]] =
    var in: java.io.InputStream = null
    try
      in = Option(Thread.currentThread().getContextClassLoader.getResourceAsStream(resourcePath)).orNull
      if in == null then None
      else
        val buf = new Array[Byte](limit + 1)
        var total = 0
        var eof = false
        while !eof && total <= limit do
          val n = in.read(buf, total, math.min(buf.length - total, 8192).max(1))
          if n < 0 then eof = true else total += n
        if total > limit then None
        else Some(java.util.Arrays.copyOf(buf, total))
    catch case _: Throwable => None
    finally
      if in != null then
        try in.close()
        catch case _: Throwable => ()

    end try

  end readCapped

  def routes: HttpRoutes[IO] = HttpRoutes.of[IO] { case req @ POST -> Root / "console-assets" =>
    req.as[Json].attempt.flatMap {
      case Left(_) =>
        BadRequest(Json.obj("error" -> "malformed body".asJson))
      case Right(body) =>
        val rawPaths = body.hcursor
          .downField("paths")
          .as[List[String]]
          .getOrElse(Nil)
          .take(MaxPathsPerRequest)
        val paths = rawPaths.filter(isBatchablePath).distinct
        // 非法/超前缀路径逐字丢进 missing（与静态路由 404 同面，无预言）。
        val dropped = rawPaths.filterNot(isBatchablePath).distinct
        // 读取并行（parTraverse）：大包 2MB+ 时串行 classpath 读是本端唯一
        // 可感成本；每文件读取自带上限（≤512KiB+1 字节探针），并行有界。
        // 整包预算在拼装阶段顺序执行（超限的尾部入账 missing）。
        paths
          .parTraverse(p => IO.blocking(readCapped(s"web/$p", MaxBytesPerAsset)).map(p -> _))
          .map { reads =>
            val assetsB = Map.newBuilder[String, Json]
            val missingB = Vector.newBuilder[Json]
            var total = 0
            for (p, bytesOpt) <- reads do
              bytesOpt match
                case None => missingB += p.asJson
                case Some(bytes) if total + bytes.length > MaxBytesTotal =>
                  missingB += p.asJson
                case Some(bytes) =>
                  assetsB += p -> Json.obj(
                    "contentType" -> contentTypeOf(p).asJson,
                    "body" -> Base64.getEncoder.encodeToString(bytes).asJson
                  )
                  total += bytes.length
            Json.obj(
              "assets" -> Json.obj(assetsB.result().toSeq*),
              "missing" -> Json.arr((missingB.result() ++ dropped.map(_.asJson))*)
            )
          }
          .flatMap(Ok(_))
    }
  }

end ConsoleAssetBatchRoutes
