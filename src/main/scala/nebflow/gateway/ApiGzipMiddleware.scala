package nebflow.gateway

import cats.data.Kleisli
import cats.effect.IO
import cats.syntax.all.*
import fs2.compression.Compression
import org.http4s.*
import org.http4s.headers.*
import org.typelevel.ci.CIString

/**
 * `/api` 面 JSON 压缩（手机面板卡顿修复批 2026-11-21 · 根因二a）。
 *
 * 修前 `GzipMiddleware` 只挂 Router "/" 的静态树，`/api` 全族**一律 identity**：
 * 实测 `GET /api/sessions/<id>/history` 10 991 449 B、`/api/subagent-tasks/<id>`
 * 568 589 B（生产 1 110 156 B）、`/api/delegates` 1 395 892 B（生产 501 336 B）
 * 全部裸传。作者场景是 `link.mystand.space` 门户隧道，**每跳都过境**——这是
 * 「首屏与刷新都慢」的确定成因，与帧率无关但可确定计量。
 *
 * ## 为什么当初「gzip 不得包 `/api` 树」（原注释的理由复核）
 *
 * 原文：`gzip must never wrap the "/api" tree (SSE stream, presence WS)`。两顾虑：
 *  ① **WebSocket**：`/api/ws`、presence WS 走 HTTP Upgrade，应答是 101 —— 本
 *     中间件只碰 `Status.Ok` + 可压缩内容类型，**结构上不可能**碰到升级应答
 *     （与原 `GzipMiddleware` 同一道 status 闸）。另外显式排除 `/ws` 前缀（双保险）。
 *  ② **SSE 流**：`POST /api/v1/chat/stream` 是 `text/event-stream`。把一条**无限
 *     流**塞进同步 gzip = 攒到流结束才出字节（等于挂死）。这是真顾虑，故本中间件
 *     按**内容类型正反两向**排除：`text/event-stream` 永不压缩，且只压缩**已知
 *     有界**的 JSON 面——白名单式 mainType/subType 判定，而不是「先 gzip 再判」。
 * 结论：原顾虑只对「流」成立，对「有界的 JSON 应答」不成立。**不推翻原约束，
 * 而是把它的适用面收窄到流**——静态树的 `GzipMiddleware` 一字未动。
 *
 * ## 与 `HttpCache` 强 ETag 的相互作用（必须显式保住的那条）
 *
 * `/api/chains`、`/api/delegates`、`/api/subagent-tasks/:id` 等腿发的是**强** ETag
 * （`HttpCache.ofJson`，内容寻址）。同一实体的 identity / gzip 两个表示**字节不同**，
 * 按 RFC 9110 不得共享强验证器；而 http4s 的 `If-None-Match` 比较是**严格**的
 * （`Weakness` 位参与比较）⇒ 若把强标签原样留在 gzip 表示上，客户端下次条件请求
 * 会拿 gzip 字节的标签去对 identity 标签，**恒不命中**，304 通路整体失效——
 * 比修前更糟。
 *
 * 因此本中间件的处理与静态树**同一手法**：gzip 表示上**剥掉强标签**，只保留弱
 * 标签（弱比较按定义忽略内容编码），并在压缩面补 `Vary: Accept-Encoding`。
 * 代价（如实记录，不掩盖）：这些腿一旦被压缩，就失去 ETag 304 复验能力，退回每次
 * 200 全量（但体积降 70–85%）。取舍是**明算过**的：默认 5s 轮询的腿**没有**强标签
 * （`/subagent-tasks` 走的是 `HttpCache.conditionalOk`… 有，见下方注①），而
 * `/api/chains` 在稳态下靠 `chain-digests` 摘要面避免重取，块体只在真变时拉一次。
 * 把「每次都 304 零体」换成「真变时传 1/6 字节」，对隧道腿是净赢；若后续实测
 * 发现某腿 304 命中率极高、压缩反而更亏，可按端点白名单回退（开关在下面
 * [[opts]] 的 `excludedPrefixes`）。
 *
 * 注①：`/api/subagent-tasks/:id` 也发强 ETag ⇒ 本批同时把**载荷从 1.1MB 降到
 * 按会话裁剪**（根因三/一），两端一并收口。
 *
 * ## 不压缩的情形（与静态树一致，都有明确理由）
 *
 *  - 非 GET（大批量 JSON 都在 GET 读面；POST 体小）。
 *  - 非 `Status.Ok`：304/204/4xx/5xx 无体或体极小，压缩只增壳。
 *  - 客户端未发 `Accept-Encoding: gzip`（`q=0` 也算未接受——`satisfiedBy` 判据）。
 *  - 应答已有 `Content-Encoding`（上游/预压缩）。
 *  - 体小于 [[MinCompressBytes]]：gzip 头 + 字典开销会让小体**变大**。
 *  - 内容类型不在白名单（见 [[compressible]]）。
 */
object ApiGzipMiddleware:

  /**
   * 压缩门槛（字节）。低于此值的体不压：gzip 容器头 ~20B + 末块开销，小体压缩
   * 后往往更肥。取 1 KiB 是「任何有意义的 JSON 页都过线、单对象应答（健康检查、
   * 小配置）不过线」的分界——本批实测的 `/api/plugins` 540B、`/api/neblink/status`
   * 798B 都在线下，压它们纯亏。
   */
  val MinCompressBytes: Long = 1024L

  private val VaryAcceptEncoding: Header.Raw =
    Header.Raw(CIString("Vary"), "Accept-Encoding")

  /** 永不压缩的路径前缀（流与升级面）。`/api` 前缀由 Router 挂载剥离，故这里用裸路径。 */
  private val ExcludedPrefixes: List[String] = List("/ws")

  def apply(routes: HttpRoutes[IO]): HttpRoutes[IO] =
    Kleisli(req => routes(req).map(transform(req, _)))

  private def transform(req: Request[IO], resp: Response[IO]): Response[IO] =
    val path = req.pathInfo.renderString
    if req.method != Method.GET || ExcludedPrefixes.exists(path.startsWith) then resp
    else
      resp.status match
        case Status.Ok if compressible(resp) =>
          if acceptsGzip(req) && resp.headers.get[`Content-Encoding`].isEmpty then compress(resp)
          else
            // 可压缩资源但客户端不接受（或上游已编码）：仍宣告 Vary，缓存不得混两表示。
            resp.putHeaders(VaryAcceptEncoding)
        case Status.NotModified =>
          // 304 同静态树：条件重验证面也必须宣告 Vary，否则缓存把两表示混一。
          resp.putHeaders(VaryAcceptEncoding)
        case _ => resp

  private def acceptsGzip(req: Request[IO]): Boolean =
    req.headers.get[`Accept-Encoding`].exists(_.satisfiedBy(ContentCoding.gzip))

  /**
   * 可压缩判据：**只有有界的 JSON**（以及同形的 xml/svg 变体）。
   * 明确排除：`text/event-stream`（SSE 无限流）、multipart 家族、
   * `application/octet-stream`（媒体/二进制，`StaticFile` 族已自带压缩或无收益）、
   * `text/html`（走静态树）。
   *
   * 另外按**已声明的** `Content-Length` 把 [[MinCompressBytes]] 以下的小体挡在外面
   * ——gzip 壳对它们是净增肥。没有 `Content-Length`（chunked/流）时一律**不压**：
   * 判不出上界就等于判不出「是不是流」，这正是本条最保守的一面。
   */
  private def compressible(resp: Response[IO]): Boolean =
    sizeOk(resp) && resp.contentType.exists { ct =>
      val mt = ct.mediaType
      (mt.mainType == "application" && (mt.subType == "json" || mt.subType.endsWith("+json"))) ||
      (mt.mainType == "text" && (mt.subType == "plain" || mt.subType == "xml")) ||
      (mt.mainType == "image" && mt.subType == "svg+xml")
    }

  /** 有界且过门槛：只认显式 `Content-Length`（无长度 ⇒ 视为流 ⇒ 不压）。 */
  private def sizeOk(resp: Response[IO]): Boolean =
    resp.headers.get[`Content-Length`].exists(_.length >= MinCompressBytes)

  private def compress(resp: Response[IO]): Response[IO] =
    // 字节长度变了：必须丢掉 Content-Length，改走 chunked，否则体被截断。
    //
    // 验证器：见类头注——**强**标签必须剥（否则 304 通路整体失效），**弱**标签保留
    // （RFC 9110 §8.8.3 弱比较忽略内容编码）。
    val base = if weakEtagPresent(resp) then resp else resp.removeHeader[ETag]
    base
      .removeHeader[`Content-Length`]
      .putHeaders(`Content-Encoding`(ContentCoding.gzip), VaryAcceptEncoding)
      .withBodyStream(resp.body.through(Compression.forSync[IO].gzip()))

  /** 弱（`W/"…"`）验证器可安全留在压缩表示上。 */
  private def weakEtagPresent(resp: Response[IO]): Boolean =
    resp.headers.get(CIString("ETag")).exists(_.head.value.startsWith("W/"))

end ApiGzipMiddleware
