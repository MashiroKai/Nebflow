package nebflow.gateway.nf

import nebflow.gateway.NfThumb

import munit.FunSuite

import java.nio.file.Files
import java.nio.file.attribute.FileTime

/**
 * `NfThumb` 缩略缓存的**淘汰面真跑读数**（媒体加载慢批 2026-11-19 · 作者令
 * 「GC / 联动 cid 布局的真跑读数补上（别只留静态说明）」）。
 *
 * 形态：纯文件系统面 —— 临时目录里造真文件、用 NIO 设真 mtime，走**产品真路径**
 * （`NfThumb.store` 落盘后顺手 `sweep`；`cached` 命中面），零 HTTP 零实例。
 *
 * 钉死的合同：
 *   T1 年龄腿：mtime > 30 天的条目被删；新的（含本次落盘的）保留。
 *   T2 容量腿：条目数超上限 `SweepCap=200` ⇒ 保留**最新 200**（不多删、不少留）。
 *   T3 内容寻址联动：文件名 = `thumbCid(cid,w)`；同 (cid,w) 幂等落盘（不重复）；
 *      异 w 分立；过期 cid 的条目被年龄腿清掉而当前 cid 保留；未命中 ⇒ `cached` 空。
 *   T4 布局：默认缓存目录 = `<dataRoot>/cache/nf-thumb`（与既有 cid 布局同根）。
 */
class NfThumbSweepSpec extends FunSuite:

  private val Day: Long = 24L * 60 * 60 * 1000
  private val Hour: Long = 60 * 60 * 1000
  private val Cid = "a" * 32

  private def freshDir(): os.Path = os.Path(Files.createTempDirectory("nf-thumb-sweep"))

  private def mk(dir: os.Path, name: String, size: Int, mtimeMs: Long): os.Path =
    val p = dir / name
    os.write.over(p, Array.fill[Byte](size)(0x41))
    Files.setLastModifiedTime(p.toNIO, FileTime.fromMillis(mtimeMs))
    p

  private def names(dir: os.Path): List[String] = os.list(dir).map(_.last).toList.sorted
  private def thumb(cid: String, w: Int): String = NfThumb.thumbCid(cid, w)

  test("T1 年龄腿（>30 天清扫）：真读数") {
    val dir = freshDir()
    val now = System.currentTimeMillis()
    mk(dir, thumb("1" * 32, 480) + ".jpg", 100, now - 31 * Day)
    mk(dir, thumb("2" * 32, 480) + ".jpg", 100, now - 31 * Day)
    mk(dir, thumb("3" * 32, 480) + ".jpg", 100, now - 31 * Day)
    mk(dir, thumb("4" * 32, 480) + ".jpg", 100, now - 1 * Day)
    mk(dir, thumb("5" * 32, 480) + ".jpg", 100, now - 2 * Hour)
    val before = names(dir)
    NfThumb.store(dir, thumb(Cid, 1280), Array.fill[Byte](50)(0x42), "jpg")   // 落盘 + 顺手清扫
    val after = names(dir)
    println(s"[T1] 年龄腿 before=${before.size} after=${after.size}（3 过期 + 2 新 + 本次 1）")
    assertEquals(after.size, 3, s"过期件应删净，新件与本次落盘应留：$after")
    assert(!after.contains(thumb("1" * 32, 480) + ".jpg"), "31 天前的条目必须被清")
    assert(after.contains(thumb(Cid, 1280) + ".jpg"), "本次落盘的条目必须在")
  }

  test("T2 容量腿（SweepCap=200）：保留最新 200 —— 真读数") {
    val dir = freshDir()
    val now = System.currentTimeMillis()
    for i <- 0 until 250 do
      // 越晚写 mtime 越新（i=249 最新）
      mk(dir, thumb(f"$i%032x", 480) + ".jpg", 10, now - (250 - i) * 1000L)
    val before = names(dir).size
    val newest = thumb(f"${249}%032x", 480) + ".jpg"
    NfThumb.store(dir, thumb(Cid, 1280), Array.fill[Byte](10)(0x43), "jpg")
    val after = names(dir)
    val n0 = thumb(f"${0}%032x", 480) + ".jpg"
    val n249 = thumb(f"${249}%032x", 480) + ".jpg"
    val kept = os.list(dir)
    println(s"[T2-diag] kept=${after.size} minMtime=${kept.map(os.mtime).min - now}ms maxMtime=${kept.map(os.mtime).max - now}ms "
      + s"oldest_kept=${if after.contains(n0) then "在" else "无"} newest_kept=${if after.contains(n249) then "在" else "无"} "
      + s"head=${after.head.take(12)} tail=${after.last.take(12)}")
    val oldestKilled = !after.contains(n0)
    println(s"[T2] 容量腿 before=$before after=${after.size} 最旧被清=$oldestKilled "
      + s"（上限 200 ⇒ 应留 200：最新 200 条 = 本轮 1 件 + 上一轮最新 199 件）")
    assert(after.size <= 200, s"不得超过上限：${after.size}")
    assertEquals(after.size, 200, s"上限内不得过度淘汰：${after.size}（应恰好 200）")
    assert(oldestKilled, "最旧的条目必须被清")
    assert(after.contains(newest), "最新的条目必须保留")
  }

  test("T3 内容寻址联动：文件名 = thumbCid(cid,w)，同键幂等、异 w 分立、过期 cid 被清") {
    val dir = freshDir()
    val a = NfThumb.store(dir, thumb(Cid, 480), Array.fill[Byte](20)(1), "jpg")
    val b = NfThumb.store(dir, thumb(Cid, 480), Array.fill[Byte](20)(1), "jpg")
    val c = NfThumb.store(dir, thumb(Cid, 1280), Array.fill[Byte](20)(1), "jpg")
    println(s"[T3] 文件名 a=${a.last} c=${c.last} 同键再落盘后目录条目=${names(dir).size}")
    assertEquals(a.last, thumb(Cid, 480) + ".jpg")
    assertEquals(b.last, a.last, "同 (cid,w) 必须同文件（幂等，不另起一份）")
    assertEquals(c.last, thumb(Cid, 1280) + ".jpg")
    assert(thumb(Cid, 480) != thumb(Cid, 1280), "异 w 必须异身份")
    assertEquals(names(dir).size, 2, s"(cid,480) 与 (cid,1280) 各一份：${names(dir)}")
    assert(NfThumb.cached(dir, thumb(Cid, 480)).isDefined, "命中面必须找得到")
    assert(NfThumb.cached(dir, thumb("f" * 32, 480)).isEmpty, "未落过盘的 cid 必须未命中")
    // cid 联动：源件内容变 ⇒ cid 变 ⇒ 旧 thumbCid 条目成为过期件，被年龄腿清掉，
    // 而「当前 cid」的条目（刚落盘）保留 —— 淘汰面按内容身份自然收口。
    Files.setLastModifiedTime(
      (dir / (thumb(Cid, 480) + ".jpg")).toNIO,
      FileTime.fromMillis(System.currentTimeMillis() - 40 * Day)
    )
    NfThumb.store(dir, thumb(Cid, 1280), Array.fill[Byte](20)(1), "jpg")
    assert(!names(dir).contains(thumb(Cid, 480) + ".jpg"), "过期 cid 的缩略必须被清")
    assert(names(dir).contains(thumb(Cid, 1280) + ".jpg"), "当前 cid 的缩略必须保留")
  }

  test("T4 布局：默认缓存目录 = <dataRoot>/cache/nf-thumb") {
    val p = NfThumb.defaultCacheDir
    println(s"[T4] defaultCacheDir=$p")
    assertEquals(p.last, "nf-thumb")
    assertEquals((p / os.up).last, "cache")
  }

  test("T5 上限面防回归：恰好 200 条时不得被清空（边界真读数）") {
    val dir = freshDir()
    val now = System.currentTimeMillis()
    for i <- 0 until 200 do mk(dir, thumb(f"$i%032x", 480) + ".jpg", 10, now - (200 - i) * 1000L)
    NfThumb.store(dir, thumb(Cid, 480), Array.fill[Byte](10)(0x44), "jpg")
    val after = names(dir)
    println(s"[T5] 边界腿 before=200 after=${after.size}（应留 200：最新 200 条）")
    assertEquals(after.size, 200, s"恰好上限时不得清空（应留最新 200）：${after.size}")
  }
