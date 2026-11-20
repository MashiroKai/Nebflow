package nebflow.core.tools

import cats.effect.IO
import munit.CatsEffectSuite
import nebflow.shared.PathUtil

import java.nio.file.Files

/**
 * 文件打开三级缓存（台账 #902 · 收口批 F）——作者三条 spec 钉 + 语义红线钉：
 *
 *   ① 「二次打开不走全量取数」——T1：同一件连开两次，`diskReads` 只 +1（首次），
 *      第二次 `fromCache=true`（计数器断言面；时延体感由零磁盘读保证）；
 *   ② 「指纹变化后缓存失效」——T2：改写文件（size 变）⇒ 新指纹必未命中 + 条目
 *      立即失效（`invalidations` +1）+ 全量重读（`diskReads` 再 +1），内容必新；
 *   ③ 「跨设备二次打开命中本机缓存」——T4：`putRemote` 后同指纹 `lookupRemote`
 *      命中、异指纹必未命中且失效（探测指纹是唯一放行闸）；
 *   红线钉——T3：重启（清进程态）后命中持久层（`diskReads` 不再 +1，`diskHits`
 *   +1）；T5：blob 0600；T6：pickup 落位幂等；T7：残缺指纹（缺 mtime）绝不放行。
 *
 * 🔴 只读纪律外的写面全部圈在 temp dataRoot（`PathUtil.setDataRoot` 隔离，
 * afterAll 归还原值）；计数器全用**增量断言**（单例全局态防 spec 间扰动）。
 */
class FileOpenCacheSpec extends CatsEffectSuite:

  private val tempRoot: os.Path = os.Path(Files.createTempDirectory("nb-file-open-cache"))
  private val originalRoot: os.Path = PathUtil.dataRoot

  override def beforeAll(): Unit = PathUtil.setDataRoot(tempRoot)

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)
    FileOpenCache.dropProcessStateForTest() // 账面复位：不把 temp 根的加载态泄漏给同 JVM 的后续 spec
    os.remove.all(tempRoot)

  private def fp(p: os.Path): FileOpenCache.Fingerprint =
    FileOpenCache.Fingerprint(os.size(p), os.mtime(p))

  test("T1 二次打开不走全量取数：diskReads 只 +1，第二次 fromCache=true（热层直出）") {
    val f = tempRoot / "t1-second-open.txt"
    os.write.over(f, "second-open-instant-body")
    val before = FileOpenCache.statsSnapshot
    val r1 = FileOpenCache.readWholeThrough(f, fp(f))
    assertEquals(r1.content, "second-open-instant-body")
    assertEquals(r1.fromCache, false, "first open must be a real read")
    val r2 = FileOpenCache.readWholeThrough(f, fp(f))
    assertEquals(r2.content, "second-open-instant-body")
    assertEquals(r2.fromCache, true, "second open must come from cache")
    val after = FileOpenCache.statsSnapshot
    assertEquals(after.diskReads - before.diskReads, 1L, "exactly one full disk read across two opens")
    val servedFromCache = (after.hotHits - before.hotHits) + (after.diskHits - before.diskHits)
    assertEquals(servedFromCache, 1L, "second open served by hot tier")
  }

  test("T2 指纹变化后缓存失效：改写 ⇒ 失效 +1、全量重读、内容必新（绝不陈旧）") {
    val f = tempRoot / "t2-fingerprint.txt"
    os.write.over(f, "v1-short")
    val r1 = FileOpenCache.readWholeThrough(f, fp(f))
    assertEquals(r1.content, "v1-short")
    os.write.over(f, "v2-rewritten-longer-body") // size 变 ⇒ 指纹必变
    val before = FileOpenCache.statsSnapshot
    val r2 = FileOpenCache.readWholeThrough(f, fp(f))
    assertEquals(r2.content, "v2-rewritten-longer-body", "post-write open must see fresh bytes")
    assertEquals(r2.fromCache, false, "changed fingerprint must miss")
    val after = FileOpenCache.statsSnapshot
    assertEquals(after.diskReads - before.diskReads, 1L, "re-read after invalidation")
    assert(after.invalidations - before.invalidations >= 1L, "stale entry evicted immediately")
  }

  test("T3 重启后依然秒开：清进程态 ⇒ 持久层命中（diskReads 不再 +1）") {
    val f = tempRoot / "t3-restart.txt"
    os.write.over(f, "persistent-tier-body")
    val r1 = FileOpenCache.readWholeThrough(f, fp(f))
    assertEquals(r1.fromCache, false)
    FileOpenCache.dropProcessStateForTest() // 模拟引擎重启：热层清空、账面待重载；磁盘原样
    val before = FileOpenCache.statsSnapshot
    val r2 = FileOpenCache.readWholeThrough(f, fp(f))
    assertEquals(r2.content, "persistent-tier-body")
    assertEquals(r2.fromCache, true, "restart must not force a full fetch")
    val after = FileOpenCache.statsSnapshot
    assertEquals(after.diskReads - before.diskReads, 0L, "no disk read of the source file")
    assertEquals(after.diskHits - before.diskHits, 1L, "served by the persistent tier")
  }

  test("T4 跨设备二次打开命中本机缓存：同指纹命中、异指纹未命中且失效") {
    val dev = "kai-dev-01"
    val rp = "/repo/docs/report.md"
    val body = "cross-device cached bytes".getBytes("UTF-8")
    val good = FileOpenCache.Fingerprint(27L, 1730000000000L)
    FileOpenCache.putRemote(dev, rp, good, body, "abcdef0123456789abcdef")
    val before = FileOpenCache.statsSnapshot
    val hit = FileOpenCache.lookupRemote(dev, rp, good)
    assert(hit.isDefined, "same fingerprint must hit the local cache")
    assertEquals(new String(hit.get.bytes, "UTF-8"), "cross-device cached bytes")
    assertEquals(hit.get.sha16, "abcdef0123456789")
    val miss = FileOpenCache.lookupRemote(dev, rp, FileOpenCache.Fingerprint(99L, 1730000000001L))
    assertEquals(miss, None, "changed remote fingerprint must never serve")
    assertEquals(FileOpenCache.lookupRemote(dev, rp, good), None, "entry evicted after mismatch")
    val after = FileOpenCache.statsSnapshot
    assert(after.invalidations - before.invalidations >= 1L, "mismatch evicts immediately")
  }

  test("T5 缓存 blob 权限 0600（POSIX；Windows 跳过）") {
    if System.getProperty("os.name", "").toLowerCase.contains("win") then assert(true, "windows: posix perms N/A")
    else
      val f = tempRoot / "t5-perms.txt"
      os.write.over(f, "perm-check")
      val _ = FileOpenCache.readWholeThrough(f, fp(f))
      val blobDir = PathUtil.dataRoot / "cache" / "fileopen" / "v1" / "blobs"
      val blobs = os.list(blobDir).filter(_.last.endsWith(".bin"))
      assert(blobs.nonEmpty, "at least one blob written")
      assert(
        os.perms(blobs.head).toString() == "rw-------",
        s"blob perms must be 0600, got ${os.perms(blobs.head)}"
      )
  }

  test("T6 ensurePickup 落位：字节恒等 + 同落位读数幂等（近端优先不重复写）") {
    val dev = "kai-dev-02"
    val rp = "/repo/media/clip.bin"
    val body = "pickup-bytes-0123456789".getBytes("UTF-8")
    val good = FileOpenCache.Fingerprint(body.length.toLong, 1730000000100L)
    FileOpenCache.putRemote(dev, rp, good, body, "0123456789abcdef0123")
    val target = FileOpenCache.ensurePickup(dev, rp, body)
    assertEquals(os.read.bytes(target).toList, body.toList, "pickup bytes must equal cached bytes")
    assertEquals(
      target,
      PointerPickup.pickupPath(dev, rp),
      "pickup must land at the PointerPickup single point"
    )
    val m1 = os.mtime(target)
    val again = FileOpenCache.ensurePickup(dev, rp, body)
    assertEquals(os.mtime(again), m1, "same matFp ⇒ idempotent zero-write return")
  }

  test("T7 残缺指纹绝不放行：缺 mtime/size 的列举应答 = None（旁路全量拉取）") {
    val listing = io.circe.Json.obj(
      "entries" -> io.circe.Json.fromValues(
        Vector(
          io.circe.Json.obj("name" -> io.circe.Json.fromString("a.txt"), "size" -> io.circe.Json.fromLong(5L)),
          io.circe.Json.obj(
            "name" -> io.circe.Json.fromString("b.txt"),
            "size" -> io.circe.Json.fromLong(7L),
            "mtime" -> io.circe.Json.fromLong(1730000000000L)
          )
        )
      )
    )
    assertEquals(FileOpenCache.fingerprintFromListing(listing, "a.txt"), None, "missing mtime = bypass")
    assertEquals(
      FileOpenCache.fingerprintFromListing(listing, "b.txt"),
      Some(FileOpenCache.Fingerprint(7L, 1730000000000L)),
      "complete {size,mtime} = usable fingerprint"
    )
    assertEquals(FileOpenCache.fingerprintFromListing(listing, "c.txt"), None, "absent name = bypass")
  }

  test("T8 写穿失效：invalidateLocalPath 后同指纹也不再命中") {
    val f = tempRoot / "t8-write-through.txt"
    os.write.over(f, "write-through-body")
    val _ = FileOpenCache.readWholeThrough(f, fp(f))
    FileOpenCache.invalidateLocalPath(f.toString)
    val before = FileOpenCache.statsSnapshot
    val r = FileOpenCache.readWholeThrough(f, fp(f))
    assertEquals(r.fromCache, false, "write-through must drop the entry")
    val after = FileOpenCache.statsSnapshot
    assertEquals(after.diskReads - before.diskReads, 1L, "forced back to a full read")
  }

end FileOpenCacheSpec
