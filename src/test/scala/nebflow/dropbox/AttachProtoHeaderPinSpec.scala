package nebflow.dropbox

import munit.FunSuite
import org.http4s.{Header, Headers}
import org.typelevel.ci.CIString

/**
 * 钉 B（spec §⑦ 钉 B）—— **头面恒 1** 常绿钉（本批最高优先）。
 *
 * 契约来源 = `.nebflow/Spec/20260914_162723_devattach-targetdir-contract-upgrade__chain-n-d623bb5b.md`
 * §1.3 头面红线 + §4.3 表 1：`AttachContract` 的数值等级（0/1/2）**只走 JSON 面**；
 * P2P 腿的 `X-Dropbox-Proto` HTTP 头**恒为 1**。
 *
 * 🔴 为什么这条必须钉死：接收端 `parseDropboxChunkHeaders` 的判据是**等值**
 * （`proto == AttachContract.ProtoChunked`，`RestApiRoutes.scala:3089`）。若本题把该头
 * 升成 `2`，**旧接收端**的 for-comprehension guard 为假 ⇒ 返回 `None` ⇒ 走 legacy 整件
 * 路径（`RestApiRoutes.scala:1355-1359` → `receiveLegacyWholeFile`）⇒ **把第一块当整件
 * 落盘**（静默数据损坏，旧端无从自纠）。把 `==` 改成 `>=` 也无法救旧端，只会掩盖破坏面。
 *
 * 常绿语义：**修正前与修正后都必须绿** —— 它的价值是阻止后来者「顺手把 `>=` 放宽」
 * （放宽后新头会被**本次实现**接受，但对旧端仍无救）以及任何把头值升到 2 的改动。
 *
 * 注：本文件在**修正前**只使用既有符号（`AttachContract.ProtoChunked`），判定用与
 * `parseDropboxChunkHeaders` 逐字等价的复制判据；**修正后**改为直接调用真实判据
 * （`DropboxChunkHeaderParser.parse`）。两侧读数见 evidence 目录。
 */
class AttachProtoHeaderPinSpec extends FunSuite:

  /** 今天的判据 = 等值（`proto == ProtoChunked`），**不是** `>=`。 */
  private def todayJudge(proto: String): Option[Int] =
    proto.toIntOption.filter(_ == AttachContract.ProtoChunked)

  /** 与 `RestApiRoutes.parseDropboxChunkHeaders` 逐字等价的头集合判定（修正前复制版）。 */
  private def replicatedParse(all: List[(String, String)]): Option[DropboxService.ChunkHeaders] =
    def h(name: String): Option[String] =
      all.find(_._1.equalsIgnoreCase(name)).map(_._2.trim).filter(_.nonEmpty)
    for
      proto <- h("x-dropbox-proto").flatMap(_.toIntOption)
      if proto == AttachContract.ProtoChunked
      index <- h("x-dropbox-index").flatMap(_.toIntOption)
      total <- h("x-dropbox-total-bytes").flatMap(_.toLongOption)
      chunkSize <- h("x-dropbox-chunk-size").flatMap(_.toIntOption)
      chunkSha <- h("x-dropbox-chunk-sha256")
      wholeSha <- h("x-dropbox-whole-sha256")
    yield DropboxService.ChunkHeaders(index, total, chunkSize, chunkSha, wholeSha)

  private val fullHeaderSet: List[(String, String)] = List(
    "X-Dropbox-Proto" -> AttachContract.ProtoChunked.toString,
    "X-Dropbox-Index" -> "0",
    "X-Dropbox-Total-Bytes" -> "10",
    "X-Dropbox-Chunk-Size" -> AttachContract.ChunkSize.toString,
    "X-Dropbox-Chunk-Sha256" -> "a" * 64,
    "X-Dropbox-Whole-Sha256" -> "b" * 64
  )

  test("B3 发送端头值恒 1：P2P 头的数值轴恒定，等级只在 JSON 面走"):
    // 发送端唯一构造点 `DropboxChunkTransports.scala:54` 写的就是本常量。
    assertEquals(AttachContract.ProtoChunked, 1)
    assertEquals(AttachContract.ProtoChunked.toString, "1")

  test("B1 正控：头值 1 + 其余 5 头齐备 ⇒ Some（分块路径）"):
    val parsed = replicatedParse(fullHeaderSet)
    assertEquals(parsed.map(_.chunkIndex), Some(0))

  test("B2 负控：头值 2 ⇒ None（旧端由此走 legacy 整件 ⇒ 静默数据损坏）"):
    val withTwo = fullHeaderSet.map { case (k, v) => if k == "X-Dropbox-Proto" then k -> "2" else k -> v }
    assertEquals(replicatedParse(withTwo), None, "头值 2 必须落 legacy（等值判据）")
    assertEquals(todayJudge("2"), None)
    assertEquals(todayJudge("1"), Some(1))
    assert(
      AttachContract.ProtoChunked < AttachContract.ProtoAssignDir,
      "数值等级本身单调上升（**JSON 面**）；头面不参与该阶梯"
    )

  // ===== 真判据（修正后）：直接喂 `DropboxChunkHeaderParser.parse`（= 路由所用谓词） =====

  private def headers(all: List[(String, String)]): Headers =
    Headers(all.map { case (k, v) => Header.Raw(CIString(k), v) })

  test("B4 真判据·正控：头值 1 + 其余 5 头齐备 ⇒ Some（分块路径）"):
    assertEquals(DropboxChunkHeaderParser.parse(headers(fullHeaderSet)).map(_.chunkIndex), Some(0))

  test("B5 真判据·负控：头值 2 ⇒ None（**不得**改成 `>=`；新头对旧端仍无救）"):
    val withTwo = fullHeaderSet.map { case (k, v) => if k == "X-Dropbox-Proto" then k -> "2" else k -> v }
    assertEquals(
      DropboxChunkHeaderParser.parse(headers(withTwo)),
      None,
      "头值 2 必须落 legacy 整件路径 —— 这是 §1.3 红线的可执行判据"
    )

  test("B6 真判据·负控：头值缺失 / 非数字 ⇒ None"):
    assertEquals(DropboxChunkHeaderParser.parse(headers(fullHeaderSet.tail)), None)
    val bogus = fullHeaderSet.map { case (k, v) => if k == "X-Dropbox-Proto" then k -> "x" else k -> v }
    assertEquals(DropboxChunkHeaderParser.parse(headers(bogus)), None)

  test("B7 真判据·负控：其余必需头缺一 ⇒ None（缺字段不得静默到看似成功）"):
    fullHeaderSet.indices.foreach { i =>
      val missing = fullHeaderSet.patch(i, Nil, 1)
      assertEquals(DropboxChunkHeaderParser.parse(headers(missing)), None, s"缺 ${fullHeaderSet(i)._1} 必须落 legacy")
    }
end AttachProtoHeaderPinSpec
