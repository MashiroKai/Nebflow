package nebflow.core.tools

import munit.CatsEffectSuite
import nebflow.shared.PathUtil

import java.nio.file.Files
import java.nio.file.attribute.FileTime

/**
 * PointerPickup（v2.1 阶段 1 §C.2 按需取件的本地落盘单点）承重断言：
 *   - 落位 = `<dataRoot>/uploads/pickups/<pointerHash>/<消毒名>`（uploads 白名单
 *     子树——/api/nf-file 二进制腿可取回的锚）；
 *   - pointerHash = sha256(device|path) 前 16 hex（幂等键：同指针重取覆盖 + 消毒
 *     名防穿越）；
 *   - TTL 清扫与 CapturePull 同族（7 天、逐件删、空目录回收）。
 *
 * 执行模型纪律：副作用 `unsafeRunSync` 落定后走纯断言（munit-cats-effect 只跑
 * 测试体返回值——体中段落 assertIO 静默丢弃，见 FileTransferActionListSpec 注）。
 */
class PointerPickupSpec extends CatsEffectSuite:

  private var savedRoot: os.Path = null
  private var tmp: os.Path = null

  override def beforeEach(context: munit.BeforeEach): Unit =
    savedRoot = PathUtil.dataRoot
    tmp = os.Path(Files.createTempDirectory("nb-pickup"))
    PathUtil.setDataRoot(tmp)

  override def afterEach(context: munit.AfterEach): Unit =
    PathUtil.setDataRoot(savedRoot)
    os.remove.all(tmp)

  test("pointerHash: 16 hex chars, stable per (device,path), distinct across devices and paths") {
    val h1 = PointerPickup.pointerHash("dev-1", "/ws/a.txt")
    assertEquals(h1, PointerPickup.pointerHash("dev-1", "/ws/a.txt"))
    assertEquals(h1.length, 16)
    assert(h1.forall(c => c.isDigit || (c >= 'a' && c <= 'f')))
    assert(h1 != PointerPickup.pointerHash("dev-2", "/ws/a.txt"))
    assert(h1 != PointerPickup.pointerHash("dev-1", "/ws/b.txt"))
  }

  test("save lands under uploads/pickups/<hash>/<sanitized name>; same pointer re-fetch overwrites") {
    val f = PointerPickup.save("dev-1", "/remote/ws/report final?.txt", "v1".getBytes("UTF-8"))
    // 落位在 uploads 白名单子树（父链含 uploads/pickups）。
    assert(
      f.toString.contains(s"uploads/pickups/${PointerPickup.pointerHash("dev-1", "/remote/ws/report final?.txt")}")
    )
    // 非法字符消毒、名字取远端路径末段。
    assertEquals(f.last, "report_final_.txt")
    assertEquals(os.read(f), "v1")
    // 同指针重取 = 覆盖（缓存语义：新字节胜出）。
    val f2 = PointerPickup.save("dev-1", "/remote/ws/report final?.txt", "v2-longer".getBytes("UTF-8"))
    assertEquals(f2, f)
    assertEquals(os.read(f2), "v2-longer")
    // 不同指针 = 不同桶，互不干扰。
    val other = PointerPickup.save("dev-2", "/remote/ws/report final?.txt", "x".getBytes("UTF-8"))
    assert(other != f)
  }

  test("save refuses traversal shapes by sanitization (no path separators survive in the name)") {
    val f = PointerPickup.save("dev-1", "/a/../../etc/passwd", "p".getBytes("UTF-8"))
    assertEquals(f.last, "passwd")
    assert(f.startsWith(PointerPickup.pickupsRoot / PointerPickup.pointerHash("dev-1", "/a/../../etc/passwd")))
  }

  test("sweepExpired removes only over-TTL files and prunes emptied pointer dirs") {
    val fresh = PointerPickup.save("dev-1", "/ws/fresh.txt", "keep".getBytes("UTF-8"))
    val stale = PointerPickup.save("dev-1", "/ws/stale.txt", "drop".getBytes("UTF-8"))
    // 回拨 mtime 越过 TTL（7 天 + 1 分钟）。
    Files.setLastModifiedTime(
      stale.toNIO,
      FileTime.fromMillis(System.currentTimeMillis() - PointerPickup.PickupTtlMs - 60_000L)
    )
    val removed = PointerPickup.sweepExpired().unsafeRunSync()
    assertEquals(removed, 1)
    assertEquals(os.exists(stale), false)
    assertEquals(os.read(fresh), "keep")
    // 空掉的指针目录被回收，根保留。
    assertEquals(os.exists(stale / os.up), false)
    assertEquals(os.exists(PointerPickup.pickupsRoot), true)
  }

end PointerPickupSpec
