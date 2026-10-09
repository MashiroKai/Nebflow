package nebflow.neblink

import munit.CatsEffectSuite
import nebflow.shared.PathUtil

import java.nio.file.Files

/**
 * 浏览面出口白名单（v2 §C.4 / v2.1 阶段 1 §C.1-3）承重断言：
 *   - 判据核（纯）：segment 前缀语义——同根/子根放行，前缀串编造（`/a/bc` vs
 *     `/a/b`）与越根拒绝；
 *   - 固定根 = captures/ + uploads/；browse 闸经 checkBrowseAccess 的放行/拒绝
 *     两腿（临时 dataRoot 的空项目表下 uploads 落点即为白名单内）；
 *   - `deviceShare.extraPaths` 配置追加根（宽容解码：坏条目跳过、缺配置 = Nil）。
 */
class EgressWhitelistSpec extends CatsEffectSuite:

  private var savedRoot: os.Path = null
  private var tmp: os.Path = null

  override def beforeEach(context: munit.BeforeEach): Unit =
    savedRoot = PathUtil.dataRoot
    tmp = os.Path(Files.createTempDirectory("nb-egress"))
    PathUtil.setDataRoot(tmp)

  override def afterEach(context: munit.AfterEach): Unit =
    PathUtil.setDataRoot(savedRoot)
    os.remove.all(tmp)

  private val root = os.Path("/data/share")

  test("isAllowed: same root and subpaths pass; sibling-prefix and outside fail") {
    val roots = List(root)
    assert(EgressWhitelist.isAllowed(root, roots))
    assert(EgressWhitelist.isAllowed(root / "sub" / "file.txt", roots))
    assert(!EgressWhitelist.isAllowed(root / os.up, roots)) // 越根
    assert(!EgressWhitelist.isAllowed(os.Path("/data/sharex"), roots)) // 前缀串编造（segment 语义拒绝）
    assert(!EgressWhitelist.isAllowed(os.Path("/etc/passwd"), roots))
  }

  test("isAllowed: empty root set denies everything (fail-closed)") {
    assert(!EgressWhitelist.isAllowed(root, Nil))
  }

  test("isAllowed: multiple roots — any hit suffices") {
    val roots = List(os.Path("/data/a"), os.Path("/data/b"))
    assert(EgressWhitelist.isAllowed(os.Path("/data/b/c"), roots))
    assert(!EgressWhitelist.isAllowed(os.Path("/data/c"), roots))
  }

  test("browse gate: uploads subtree is servable; outside paths are denied") {
    val uploads = EgressWhitelist.uploadsRoot
    os.makeDir.all(uploads / "pickups" / "abc")
    os.write.over(uploads / "pickups" / "abc" / "x.txt", "hi")
    // 执行模型纪律：unsafeRunSync 落定后纯断言（体中段落 assertIO 静默丢弃）。
    val inside = EgressWhitelist.checkBrowseAccess(uploads / "pickups" / "abc" / "x.txt").unsafeRunSync()
    assertEquals(inside, None)
    val outside = tmp / "outside.txt"
    os.write.over(outside, "no")
    val denied = EgressWhitelist.checkBrowseAccess(outside).unsafeRunSync()
    assert(denied.isDefined)
  }

  test("deviceShare.extraPaths: configured extra roots extend the whitelist (tolerant decode)") {
    val extra = tmp / "extra-share"
    os.makeDir.all(extra)
    os.write.over(extra / "y.txt", "yo")
    os.write.over(
      tmp / "nebflow.json",
      s"""{"deviceShare": {"extraPaths": ["/definitely/not/a/real/dir", "$extra"]}}"""
    )
    // 坏条目被跳过（WARN 留痕），好条目生效。
    val allowed = EgressWhitelist.checkBrowseAccess(extra / "y.txt").unsafeRunSync()
    assertEquals(allowed, None)
    // 无配置 = 无追加根（宽容解码 Nil ⇒ 拒绝）。
    os.write.over(tmp / "nebflow.json", "{}")
    val denied = EgressWhitelist.checkBrowseAccess(extra / "y.txt").unsafeRunSync()
    assert(denied.isDefined)
  }

end EgressWhitelistSpec
