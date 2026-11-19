package nebflow.gateway

import cats.effect.IO
import io.circe.Json
import org.http4s.*
import org.http4s.circe.CirceEntityEncoder.*
import org.http4s.dsl.io.*

import java.security.MessageDigest

/**
 * HTTP 缓存语义小刀（M2 · 设计 v2 §3.1 清单「①取数语义补齐」的单一实现面）。
 *
 * 六端点（`/api/usage/aggregate`、`/api/projects/<n>/flow-map[/archive]`、
 * `/api/delegates/:id`、`/api/chains`、WS `historyPage` 页指纹）中五个 HTTP 面共用
 * 本处的条件请求协商 + `Cache-Control: private` 纪律：
 *
 *   - **ETag**：两个来源——[[ofJson]]（载荷内容 sha256，适合「载荷稳定 = 语义不变」
 *     的聚合/行投影面）与调用方自给的结构键（`flow-map`/`archive` 载荷含逐秒演算的
 *     TTL 展示字段 ⇒ 内容 hash 永不命中，必须用 `meta.updatedAt` / 批计数这类**结构
 *     键**——键选择在每个路由旁注申报）。
 *   - **304**：`If-None-Match` 包含比对（弱比较足够——设计只求「未变不重发体」）。
 *   - **`private`**：全部端点在 `withAuth`（会话 cookie/Bearer）之后 ⇒ 凭据在会话，
 *     响应永不进共享缓存（#538 同规，v1 §2.1 口径纪律）。
 *   - **`no-cache`（可以存、用前必须再验证）**：304 往返 ≈2ms（本机实测，v1 §A.1），
 *     体内容不再重发；语义 = 「先给旧值、后台对账」的静态/半静态面判据（v2 §3 判据
 *     一句话）。
 *
 * 不入本面：WS 帧（`historyPage` 页指纹）不走 HTTP 头，指纹直接进帧（见
 * `HistoryPageFingerprint`）；GzipMiddleware 只包 `/` 静态树（`GatewayMain.scala`
 * 装配注释「gzip must never wrap the /api tree」）⇒ 本处 ETag 不会被 gzip 剥除。
 */
private[gateway] object HttpCache:

  /** 载荷内容寻址 ETag（强语义：payload 相等 ⇔ ETag 相等）。 */
  def ofJson(json: Json): String =
    val sha = MessageDigest.getInstance("SHA-256").digest(json.noSpaces.getBytes("UTF-8"))
    "\"" + sha.map(b => f"${b & 0xff}%02x").mkString.take(32) + "\""

  /** 结构键 ETag（调用方给稳定读数串，如 `updatedAt:节点数`）。 */
  def ofSeed(seed: String): String =
    val sha = MessageDigest.getInstance("SHA-256").digest(seed.getBytes("UTF-8"))
    "\"" + sha.map(b => f"${b & 0xff}%02x").mkString.take(32) + "\""

  private val PrivateNoCache = "private, no-cache"

  /**
   * 条件 200/304 出口：请求带 `If-None-Match` 且命中当前 ETag ⇒ 304（**重述**
   * ETag + Cache-Control——304 不带策略头浏览器会丢缓存条目语义）；否则 200 全量。
   *
   * @param etag  当前表示的实体标签（含引号）
   * @param cacheControl 缺省 `private, no-cache`；如需更长复用窗由调用方覆盖
   *   （本批六端点统一缺省——「未变 ⇒ 一次条件往返」，零启发式新鲜度猜判）。
   */
  def conditionalOk(req: Request[IO], body: Json, etag: String, cacheControl: String = PrivateNoCache): IO[Response[IO]] =
    val inm = req.headers
      .get(org.typelevel.ci.CIString("If-None-Match"))
      .map(nel => nel.head.value)
      .getOrElse("")
    val notModified = inm.nonEmpty && inm.split(',').map(_.trim).exists(t => t == etag || t == "*")
    if notModified then
      IO.pure(
        Response[IO](Status.NotModified)
          .withHeaders(
            org.http4s.Header.Raw(org.typelevel.ci.CIString("ETag"), etag),
            org.http4s.Header.Raw(org.typelevel.ci.CIString("Cache-Control"), cacheControl)
          )
      )
    else
      IO.pure(
        Response[IO](Status.Ok)
          .withEntity(body)
          .withHeaders(
            org.http4s.Header.Raw(org.typelevel.ci.CIString("ETag"), etag),
            org.http4s.Header.Raw(org.typelevel.ci.CIString("Cache-Control"), cacheControl)
          )
      )

end HttpCache
