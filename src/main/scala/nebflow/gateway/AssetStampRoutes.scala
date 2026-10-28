/* 台账 #919 热更新地基（2026-10-27）：asset stamp 端点。 */
package nebflow.gateway

import cats.effect.IO
import io.circe.Json
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*

import java.util.concurrent.ConcurrentHashMap

/**
 * GET /api/nf-asset-stamp — 前端热更新地基的指纹单点（台账 #919，2026-10-27）。
 *
 * `stamp` = 引擎**实际伺服**的 index.html **原始字节**（brand 注入前的盘上/资源字节
 * ——brandScript 是逐请求动态注入面，不进指纹）的 SHA-256 前 16 hex，与
 * [[StaticRoutes.indexResponse]] 三分支**同源**：
 *   - DevTree     → classpath `web/index.html`（sbt run 热编辑：file: 资源 mtime 变化即重算）；
 *   - BundledDist → classpath `web-dist/index.html`（jar 冻结 ⇒ 每 boot 一次）；
 *   - DiskDist    → `<root>/index.html` 盘上文件（逐请求读盘面的指纹镜像；
 *     (path, mtime) → hash 缓存与 [[AssetVersions]] 同形——mtime 不变零重算）。
 *
 * 重建 bundle 必改 index.html 引用的内容寻址文件名 ⇒ index 字节变 ⇒ stamp 变 ⇒
 * 前端 hotStamp.js 轮询检出 ⇒ 空闲自动 reload（不重启引擎、不手动刷新——设计件
 * `docs/design/20261027_hot-reload-foundation-spec.zh-CN.md` §1.2）。
 *
 * 载荷：`{stamp, source, version}` —— `source` ∈ dev-tree|bundled-dist|disk-dist
 * （诊断面：哪个静态面在伺服）；`version` = `nebflow.Version.string`（与热重启链
 * HealthPayload 同单源——前端可诊断「stamp 没变但 version 变了 = 引擎被热重启过」）。
 * 指纹读不出来（缺文件/IO 意外）→ 404（前端按 unavailable 静默空转，加速面非语义面）。
 */
private[gateway] object AssetStampRoutes:

  /** (absPath, mtimeMs) → hash 的磁盘指纹缓存（AssetVersions.Entry 同形）。 */
  private case class DiskEntry(mtimeMs: Long, hash: String)
  private val diskCache = new ConcurrentHashMap[String, DiskEntry]()

  /** 字节 → SHA-256 前 [[AssetVersions.HexLen]] hex（与 AssetVersions 同短 URL 口径）。 */
  private def sha16(bytes: Array[Byte]): Option[String] =
    try
      val md = java.security.MessageDigest.getInstance("SHA-256")
      Some(md.digest(bytes).map(b => f"${b & 0xff}%02x").mkString.take(AssetVersions.HexLen))
    catch case _: Throwable => None

  /** 盘上 index 的指纹（mtime 缓存；文件消失/IO 意外 → None）。 */
  def diskIndexStamp(file: os.Path): Option[String] =
    try
      if !os.isFile(file) then None
      else
        val mtime = os.mtime(file)
        val hit = diskCache.get(file.toString)
        if hit != null && hit.mtimeMs == mtime then Some(hit.hash)
        else
          val h = sha16(os.read.bytes(file))
          h.foreach(hash => diskCache.put(file.toString, DiskEntry(mtime, hash)))
          h
    catch case _: Throwable => None

  /** 三静态面 → (stamp, source 标签)。纯判定面（spec 直读）；读不出来 = None。 */
  def assetStamp(source: StaticRoutes.StaticSource): Option[(String, String)] =
    source match
      case StaticRoutes.StaticSource.DevTree =>
        AssetVersions.versionOf("web/index.html").map("dev-tree" -> _)
      case StaticRoutes.StaticSource.BundledDist =>
        AssetVersions.versionOf("web-dist/index.html").map("bundled-dist" -> _)
      case StaticRoutes.StaticSource.DiskDist(root) =>
        diskIndexStamp(root / "index.html").map("disk-dist" -> _)

  /** 载荷构造（spec 直读）。stamp 缺席 = Json.Null（路由腿给 404，构造面不抛）。 */
  def stampPayload: IO[Json] =
    IO {
      assetStamp(StaticRoutes.staticSource) match
        case None => Json.Null
        case Some((source, stamp)) =>
          Json.obj(
            "stamp" -> stamp.asJson,
            "source" -> source.asJson,
            "version" -> nebflow.Version.string.asJson,
            "ts" -> System.currentTimeMillis().asJson
          )
    }

  def routes(ctx: RestApiCtx): HttpRoutes[IO] =
    import ctx.*
    HttpRoutes.of[IO] { case req @ GET -> Root / "nf-asset-stamp" =>
      withAuth(req) {
        stampPayload.flatMap {
          case Json.Null => NotFound(Json.obj("error" -> "asset stamp unavailable".asJson))
          case payload => Ok(payload)
        }
      }
    }

end AssetStampRoutes
