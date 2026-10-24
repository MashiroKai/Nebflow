package nebflow.neblink

import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import munit.CatsEffectSuite
import nebflow.shared.PathUtil

import java.nio.file.Files

/**
 * `FileTransferAction` 浏览面（v2.1 阶段 1 §C.1-3）与 legacy 双轨的承重断言：
 *   - `direction=list`（元数据列举）：entries 形状 `{name,type,size,mtime,broken}`、
 *     目录排序（dir 先、名次典序）、**零 content 键**（字节不流动的锚）、
 *     缺目录 = 显式 Left；
 *   - `scope=browse` 白名单闸：白名单内放行、白名单外显式拒绝（零元数据外泄）；
 *   - **legacy 双轨**：无 `scope` 键的 get 逐字节旧行为（base64 content + size），
 *     白名单不设限（存量 capture 回拉零回归）。
 *
 * 🔴 执行模型纪律（本批实测教训）：munit-cats-effect 只运行**测试体返回值**（末表
 * 达式）里的 IO——体中段落的 assertIO 静默丢弃（vacuous pass）。副作用统一
 * `unsafeRunSync` 落定后走纯断言；`share` 用 def（每次现取 uploadsRoot——val 会在
 * 类初始化期把**默认 dataRoot** 冻进路径）。
 */
class FileTransferActionListSpec extends CatsEffectSuite:

  private var savedRoot: os.Path = null
  private var tmp: os.Path = null

  override def beforeEach(context: munit.BeforeEach): Unit =
    savedRoot = PathUtil.dataRoot
    tmp = os.Path(Files.createTempDirectory("nb-fta-list"))
    PathUtil.setDataRoot(tmp)
    seedShare()

  override def afterEach(context: munit.AfterEach): Unit =
    PathUtil.setDataRoot(savedRoot)
    os.remove.all(tmp)

  private def share = EgressWhitelist.uploadsRoot / "browse-fixture"

  private def seedShare(): Unit =
    os.makeDir.all(share / "zsub")
    os.write.over(share / "a.txt", "hello")
    os.write.over(share / "m.md", "# doc")

  test("direction=list (scope=browse, whitelisted root): metadata entries only, dirs first, zero content") {
    val result = FileTransferAction
      .handle(JsonObject("direction" -> "list".asJson, "path" -> share.toString.asJson, "scope" -> "browse".asJson))
      .map(_.fold(l => fail(s"list failed: $l"), identity))
      .unsafeRunSync()

    assertEquals(result.hcursor.downField("content").focus.isEmpty, true) // 零字节流动
    assertEquals(result.hcursor.downField("path").as[String].getOrElse(""), share.toString)

    val rows = result.hcursor
      .downField("entries")
      .as[Vector[Json]]
      .getOrElse(Vector.empty)
      .map(e =>
        (
          e.hcursor.downField("name").as[String].getOrElse("?"),
          e.hcursor.downField("type").as[String].getOrElse("?"),
          e.hcursor.downField("size").as[Long].getOrElse(-1L),
          e.hcursor.downField("broken").as[Boolean].getOrElse(true)
        )
      )
      .toList

    assertEquals(rows.map(_._1), List("zsub", "a.txt", "m.md")) // 目录先、其后名次典序
    assert(rows.exists(r => r._1 == "zsub" && r._2 == "dir" && r._3 == 0L))
    assert(rows.exists(r => r._1 == "a.txt" && r._2 == "file" && r._3 == 5L && !r._4))
  }

  test("direction=list outside the whitelist (scope=browse) is denied with zero metadata") {
    val outside = tmp / "secret-dir"
    os.makeDir.all(outside)
    os.write.over(outside / "s.txt", "classified")
    val result = FileTransferAction
      .handle(
        JsonObject("direction" -> "list".asJson, "path" -> outside.toString.asJson, "scope" -> "browse".asJson)
      )
      .unsafeRunSync()
    assert(result.isLeft)
    assert(result.fold(_.toLowerCase, _ => "").contains("whitelist"))
  }

  test("direction=list on a missing directory fails explicitly") {
    val result = FileTransferAction
      .handle(
        JsonObject("direction" -> "list".asJson, "path" -> (share / "nope").toString.asJson, "scope" -> "browse".asJson)
      )
      .unsafeRunSync()
    assert(result.left.exists(_.contains("not found")))
  }

  test("legacy get (no scope key): base64 content + size, byte-identical behaviour, whitelist not applied") {
    val outside = tmp / "legacy.txt"
    os.write.over(outside, "legacy-bytes")
    val result = FileTransferAction
      .handle(JsonObject("direction" -> "get".asJson, "path" -> outside.toString.asJson))
      .map(_.fold(l => fail(s"get failed: $l"), identity))
      .unsafeRunSync()

    val b64 = result.hcursor.downField("content").as[String].getOrElse("")
    assert(java.util.Base64.getDecoder.decode(b64).sameElements("legacy-bytes".getBytes("UTF-8")))
    assertEquals(result.hcursor.downField("size").as[Long].getOrElse(-1L), 12L)
  }

end FileTransferActionListSpec
