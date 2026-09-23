package nebflow.dropbox

import org.http4s.Headers
import org.typelevel.ci.CIString

/**
 * P2P 分块请求头解析 —— 从 `RestApiRoutes.parseDropboxChunkHeaders` 原样抽出
 * （**逐字等价**，仅为可判定性抽出：路由类构造依赖过重，钉无法在不实例化整条路由的
 * 前提下验证该判据）。
 *
 * 🔴 **只有 `X-Dropbox-Proto` 明确为 `1`（且其余必需头齐备）时才返回 `Some`** ——
 * 任何缺失/不等 ⇒ `None` ⇒ 走 legacy 整件路径（契约 §3.9「未知/缺失字段不得静默到
 * 看似成功」：这里「缺失」有明确定义的降级行为）。
 *
 * 🔴 **本判据是等值，不是 `>=`，且不得改成 `>=`**：
 *   - 把发送端头值升成 `2` ⇒ **旧接收端**该 guard 为假 ⇒ `None` ⇒ `receiveLegacyWholeFile`
 *     ⇒ **把第一块当整件落盘**（静默数据损坏，旧端无从自纠）；
 *   - 把 `==` 改成 `>=` ⇒ 新头会被**本次实现**接受，但对**旧端**仍无救 ⇒ 破坏面被掩盖。
 * ⇒ 版本数值轴只走 JSON 面（`file-offer` / `file-response`），P2P 头值**恒为 1**
 * （见 [[AttachContract.ProtoAssignDir]] 的文档与 spec §1.3 / §4.3 表 1）。
 */
object DropboxChunkHeaderParser:

  def parse(headers: Headers): Option[DropboxService.ChunkHeaders] =
    def h(name: String): Option[String] =
      headers.get(CIString(name)).map(_.head.value.trim).filter(_.nonEmpty)
    for
      proto <- h("x-dropbox-proto").flatMap(_.toIntOption)
      if proto == AttachContract.ProtoChunked
      index <- h("x-dropbox-index").flatMap(_.toIntOption)
      total <- h("x-dropbox-total-bytes").flatMap(_.toLongOption)
      chunkSize <- h("x-dropbox-chunk-size").flatMap(_.toIntOption)
      chunkSha <- h("x-dropbox-chunk-sha256")
      wholeSha <- h("x-dropbox-whole-sha256")
    yield DropboxService.ChunkHeaders(index, total, chunkSize, chunkSha, wholeSha)

end DropboxChunkHeaderParser
