package nebflow.core

import cats.effect.IO
import io.circe.{Json, parser}
import munit.CatsEffectSuite

import scala.concurrent.duration.*

/**
 * #910 墓碑语义契约（2026-10-26 作者裁定「找少的那个认吧」＝关闭意图优先）。
 *
 * 症状：多窗口/多设备下，A 端关闭的标签被 B 端陈旧全量 PUT 复活（旧语义=
 * 整档覆盖）。本 spec 锁死合并规则的每条路径：
 * - 关闭意图落墓碑，墓碑并集压过开放集（陈旧全量 PUT 无法复活）
 * - 显式重开（reopenedAt > closedAt）才清墓碑；老墓碑惰性保留（旧账重放
 *   既压不回重开态、也复活不了再关态）
 * - 同 id 竞争按时间戳裁决（早于 closedAt 的重开无效）
 * - TTL 30d 回收 + 数量上限 500（防无限膨胀）
 * - 旧格式 v2（无 tombstones 字段）平滑升级；老客户端 v2 全量 PUT 照常工作
 * - activeTabId 命中生效墓碑则丢弃
 */
class CanvasTabTombstoneSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 30.seconds

  private def json(s: String): Json =
    parser.parse(s).fold(e => throw new RuntimeException(s"bad fixture: $e"), identity)

  private def tabsJs(ids: String*): String =
    ids.map(id => s"""{"id":"$id","title":"$id","type":"t"}""").mkString("[", ",", "]")

  /** 从合并结果提取 tabs 的 id 序列。 */
  private def tabIds(merged: Json): Vector[String] =
    merged.hcursor.downField("tabs").as[Vector[Json]].getOrElse(Vector.empty).flatMap(
      _.hcursor.downField("id").as[String].toOption
    )

  private def tombRecs(merged: Json): Vector[Json] =
    merged.hcursor.downField("tombstones").as[Vector[Json]].getOrElse(Vector.empty)

  private def tombOf(merged: Json, id: String): Option[Json] =
    tombRecs(merged).find(_.hcursor.downField("id").as[String].toOption.contains(id))

  private def isActive(merged: Json, id: String): Boolean =
    tombOf(merged, id).flatMap(t =>
      for
        c <- t.hcursor.downField("closedAt").as[Long].toOption
        r = t.hcursor.downField("reopenedAt").as[Long].toOption
      yield c > r.getOrElse(Long.MinValue)
    ).contains(true)

  private val NOW: Long = 1792988000000L

  // ── 基线：纯 v2 行为保持 ─────────────────────────────────────────────

  test("merge on empty archive keeps tabs verbatim and emits empty tombstones") {
    val put = json(s"""{"v":2,"tabs":${tabsJs("a", "b")}}""")
    val merged = CanvasTabs.merge(None, put, NOW)
    assertEquals(tabIds(merged), Vector("a", "b"))
    assertEquals(tombRecs(merged), Vector.empty)
    assertEquals(merged.hcursor.downField("v").as[Int].toOption, Some(2))
  }

  test("merge validates v:2 payloads carrying tombstones/reopened (route contract)") {
    val p = json(s"""{"v":2,"tabs":${tabsJs("a","b")},"tombstones":[{"id":"x","closedAt":1,"dev":"Win32"}],"reopened":[{"id":"x","reopenedAt":2}]}""")
    assertEquals(CanvasTabs.validate(p).isRight, true)
  }

  // ── 关闭意图 → 墓碑压过开放集 ────────────────────────────────────────

  test("close intent: tombstone filters the id from the merged open set") {
    val prev = json(s"""{"v":2,"tabs":${tabsJs("a", "b")},"tombstones":[]}""")
    val put = json(s"""{"v":2,"tabs":${tabsJs("b")},"tombstones":[{"id":"a","closedAt":$NOW}]}""")
    val merged = CanvasTabs.merge(Some(prev), put, NOW)
    assertEquals(tabIds(merged), Vector("b"))
    assert(isActive(merged, "a"), "a must carry an ACTIVE tombstone")
  }

  test("stale full-set replay (no ledger) cannot resurrect a tombstoned tab") {
    // B 窗口陈旧视图重放全量 PUT（含 a），无任何关闭/重开账 → a 仍被压住。
    val prev = json(s"""{"v":2,"tabs":${tabsJs("b")},"tombstones":[{"id":"a","closedAt":$NOW,"dev":"Win32"}]}""")
    val put = json(s"""{"v":2,"tabs":${tabsJs("a", "b")}}""")
    val merged = CanvasTabs.merge(Some(prev), put, NOW)
    assertEquals(tabIds(merged), Vector("b"))
    assert(isActive(merged, "a"))
  }

  test("legacy v2 client (old engine browser) full PUT also cannot resurrect") {
    val prev = json(s"""{"v":2,"tabs":${tabsJs("b")},"tombstones":[{"id":"a","closedAt":$NOW}]}""")
    val put = json(s"""{"v":2,"tabs":${tabsJs("a", "b")}}""")
    val merged = CanvasTabs.merge(Some(prev), put, NOW)
    assertEquals(tabIds(merged), Vector("b"))
  }

  // ── 显式重开 ─────────────────────────────────────────────────────────

  test("explicit reopen (reopenedAt > closedAt) restores the tab and deactivates the tombstone") {
    val t1 = NOW - 1000
    val t2 = NOW
    val prev = json(s"""{"v":2,"tabs":${tabsJs("b")},"tombstones":[{"id":"a","closedAt":$t1}]}""")
    val put = json(s"""{"v":2,"tabs":${tabsJs("a", "b")},"reopened":[{"id":"a","reopenedAt":$t2}]}""")
    val merged = CanvasTabs.merge(Some(prev), put, NOW)
    assertEquals(tabIds(merged), Vector("a", "b"))
    assert(!isActive(merged, "a"), "tombstone must go inert after newer reopen")
    // 惰性记录保留（不删除）——旧账重放的护城河。
    assert(tombOf(merged, "a").isDefined)
    assertEquals(tombOf(merged, "a").get.hcursor.downField("reopenedAt").as[Long].toOption, Some(t2))
  }

  test("replayed old close ledger after a reopen cannot re-close the tab") {
    val t1 = NOW - 2000
    val t2 = NOW - 1000
    val prev = json(s"""{"v":2,"tabs":${tabsJs("a", "b")},"tombstones":[{"id":"a","closedAt":$t1,"reopenedAt":$t2}]}""")
    // 陈旧窗口重放它旧账里的关闭意图（closedAt=t1 < reopenedAt=t2）。
    val put = json(s"""{"v":2,"tabs":${tabsJs("a", "b")},"tombstones":[{"id":"a","closedAt":$t1}]}""")
    val merged = CanvasTabs.merge(Some(prev), put, NOW)
    assertEquals(tabIds(merged), Vector("a", "b"), "stale close replay must not win")
    assert(!isActive(merged, "a"))
  }

  test("re-close after reopen re-activates the tombstone (last close intent wins)") {
    val t1 = NOW - 3000
    val t2 = NOW - 2000
    val t3 = NOW - 1000
    val prev = json(s"""{"v":2,"tabs":${tabsJs("a", "b")},"tombstones":[{"id":"a","closedAt":$t1,"reopenedAt":$t2}]}""")
    val put = json(s"""{"v":2,"tabs":${tabsJs("b")},"tombstones":[{"id":"a","closedAt":$t3}]}""")
    val merged = CanvasTabs.merge(Some(prev), put, NOW)
    assertEquals(tabIds(merged), Vector("b"))
    assert(isActive(merged, "a"))
  }

  test("reopen older than the tombstone loses (same-id race: A closes at t2, B reopens at t1)") {
    val t1 = NOW - 2000
    val t2 = NOW - 1000
    val prev = json(s"""{"v":2,"tabs":${tabsJs("b")},"tombstones":[{"id":"a","closedAt":$t2}]}""")
    val put = json(s"""{"v":2,"tabs":${tabsJs("a", "b")},"reopened":[{"id":"a","reopenedAt":$t1}]}""")
    val merged = CanvasTabs.merge(Some(prev), put, NOW)
    assertEquals(tabIds(merged), Vector("b"), "stale reopen must lose to newer close")
    assert(isActive(merged, "a"))
  }

  test("reopen for an unknown id is ignored (no phantom records)") {
    val put = json(s"""{"v":2,"tabs":${tabsJs("a")},"reopened":[{"id":"ghost","reopenedAt":$NOW}]}""")
    val merged = CanvasTabs.merge(None, put, NOW)
    assertEquals(tombRecs(merged), Vector.empty)
  }

  test("same-id close+reopen in one payload resolves by timestamps") {
    // 用户一次防抖窗内先关后开（同一 id）→ reopenedAt 更晚 → 保留。
    val put = json(
      s"""{"v":2,"tabs":${tabsJs("a")},"tombstones":[{"id":"a","closedAt":${NOW - 500}}],"reopened":[{"id":"a","reopenedAt":$NOW}]}"""
    )
    val merged = CanvasTabs.merge(None, put, NOW)
    assertEquals(tabIds(merged), Vector("a"))
    assert(!isActive(merged, "a"))
  }

  // ── TTL / 数量上限 ───────────────────────────────────────────────────

  test("tombstones older than TTL are reclaimed (tab becomes openable again)") {
    val stale = NOW - CanvasTabs.TombstoneTtlMs - 1000
    val prev = json(s"""{"v":2,"tabs":${tabsJs("b")},"tombstones":[{"id":"a","closedAt":$stale}]}""")
    val put = json(s"""{"v":2,"tabs":${tabsJs("a", "b")}}""")
    val merged = CanvasTabs.merge(Some(prev), put, NOW)
    assertEquals(tabIds(merged), Vector("a", "b"), "expired tombstone must not filter")
    assertEquals(tombRecs(merged), Vector.empty, "expired record must be reclaimed")
  }

  test("tombstone count cap: oldest records are dropped beyond MaxTombstones") {
    val recs = (0 until 600).map(i => s"""{"id":"t$i","closedAt":${NOW - 600 + i}}""").mkString("[", ",", "]")
    val prev = json(s"""{"v":2,"tabs":${tabsJs("b")},"tombstones":$recs}""")
    val put = json(s"""{"v":2,"tabs":${tabsJs("b")}}""")
    val merged = CanvasTabs.merge(Some(prev), put, NOW)
    assertEquals(tombRecs(merged).length, CanvasTabs.MaxTombstones)
    // 最旧的 t0..t99 被淘汰，最新的 t599 在册。
    assert(tombOf(merged, "t0").isEmpty)
    assert(tombOf(merged, "t599").isDefined)
  }

  // ── 旧格式兼容 / 去重 / activeTabId ──────────────────────────────────

  test("legacy archive without tombstones field upgrades smoothly") {
    val prev = json(s"""{"v":2,"tabs":${tabsJs("a", "b")}}""") // 老文件：无 tombstones
    val put = json(s"""{"v":2,"tabs":${tabsJs("a", "b")},"tombstones":[{"id":"a","closedAt":$NOW}]}""")
    val merged = CanvasTabs.merge(Some(prev), put, NOW)
    assertEquals(tabIds(merged), Vector("b"))
    assert(isActive(merged, "a"))
  }

  test("duplicate ids in put tabs keep the first occurrence") {
    val put = json(s"""{"v":2,"tabs":[{"id":"a","title":"first","type":"t"},{"id":"a","title":"dup","type":"t"}]}""")
    val merged = CanvasTabs.merge(None, put, NOW)
    assertEquals(tabIds(merged), Vector("a"))
    assertEquals(
      merged.hcursor.downField("tabs").as[Vector[Json]].toOption.get.head.hcursor.downField("title").as[String].toOption,
      Some("first")
    )
  }

  test("activeTabId is dropped when it hits an active tombstone, preserved otherwise") {
    val prev = json(s"""{"v":2,"tabs":${tabsJs("b")},"tombstones":[{"id":"a","closedAt":$NOW}],"activeTabId":"a"}""")
    val put = json(s"""{"v":2,"tabs":${tabsJs("b")}}""")
    val merged = CanvasTabs.merge(Some(prev), put, NOW)
    assertEquals(merged.hcursor.downField("activeTabId").as[Option[String]].toOption, Some(None),
      "tombstoned activeTabId must be dropped")
  }

  test("activeTabId from prev survives a put that omits it") {
    val prev = json(s"""{"v":2,"tabs":${tabsJs("a", "b")},"activeTabId":"b"}""")
    val put = json(s"""{"v":2,"tabs":${tabsJs("a", "b")}}""")
    val merged = CanvasTabs.merge(Some(prev), put, NOW)
    assertEquals(merged.hcursor.downField("activeTabId").as[Option[String]].toOption, Some(Some("b")))
  }

  // ── validate：墓碑/重开账的拒绝路径（路由转 400）────────────────────

  test("validate rejects malformed tombstones/reopened arrays") {
    val bad = List(
      s"""{"v":2,"tabs":[],"tombstones":{"id":"x","closedAt":1}}""",              // 非数组
      s"""{"v":2,"tabs":[],"tombstones":[{"closedAt":1}]}""",                     // 缺 id
      s"""{"v":2,"tabs":[],"tombstones":[{"id":"","closedAt":1}]}""",             // 空 id
      s"""{"v":2,"tabs":[],"tombstones":[{"id":"x"}]}""",                         // 缺 closedAt
      s"""{"v":2,"tabs":[],"tombstones":[{"id":"x","closedAt":-5}]}""",           // 负时间戳
      s"""{"v":2,"tabs":[],"tombstones":[{"id":"x","closedAt":"now"}]}""",        // 非数字
      s"""{"v":2,"tabs":[],"tombstones":[{"id":"x","closedAt":1,"dev":7}]}""",    // dev 非字符串
      s"""{"v":2,"tabs":[],"reopened":[{"id":"x"}]}""",                           // reopened 缺 reopenedAt
      s"""{"v":2,"tabs":[],"reopened":[{"id":"x","reopenedAt":null}]}""",         // null 时间戳
    )
    bad.foreach { s =>
      assert(CanvasTabs.validate(json(s)).isLeft, s"must reject: $s")
    }
  }

  test("validate rejects over-limit tombstones/reopened arrays (anti-abuse)") {
    val many = (0 until CanvasTabs.MaxTombstoneInput + 1).map(i => s"""{"id":"x$i","closedAt":1}""").mkString("[", ",", "]")
    assert(CanvasTabs.validate(json(s"""{"v":2,"tabs":[],"tombstones":$many}""")).isLeft)
    val manyR = (0 until CanvasTabs.MaxTombstoneInput + 1).map(i => s"""{"id":"x$i","reopenedAt":1}""").mkString("[", ",", "]")
    assert(CanvasTabs.validate(json(s"""{"v":2,"tabs":[],"reopened":$manyR}""")).isLeft)
  }

  // ── store 级：mergeSave 落盘往返 ─────────────────────────────────────

  test("mergeSave round-trips the merged archive through disk") {
    for
      dir <- IO(os.temp.dir())
      f = dir / "canvas_tabs.json"
      store = new CanvasTabStore(f)
      first = json(s"""{"v":2,"tabs":${tabsJs("a", "b")}}""")
      _ <- store.save(first)
      closePut = json(s"""{"v":2,"tabs":${tabsJs("b")},"tombstones":[{"id":"a","closedAt":$NOW}]}""")
      merged <- store.mergeSave(closePut, NOW + 1)
      loaded <- store.load()
      reopenPut = json(s"""{"v":2,"tabs":${tabsJs("a", "b")},"reopened":[{"id":"a","reopenedAt":${NOW + 2}}]}""")
      merged2 <- store.mergeSave(reopenPut, NOW + 3)
      loaded2 <- store.load()
      residue <- IO(os.list(dir).filter(_.last.contains(".tmp.")))
    yield
      assertEquals(loaded, Some(merged), "close leg: disk == merged")
      assertEquals(tabIds(merged), Vector("b"))
      assertEquals(tabIds(merged2), Vector("a", "b"), "reopen leg restores a")
      assertEquals(loaded2, Some(merged2), "reopen leg: disk == merged")
      assertEquals(residue.toList, List.empty[os.Path], "no tmp residue")
  }

  test("mergeSave accepts old-format payloads (v2 client against new engine)") {
    for
      dir <- IO(os.temp.dir())
      store = new CanvasTabStore(dir / "canvas_tabs.json")
      plain = json(s"""{"v":2,"tabs":${tabsJs("x")}}""")
      merged <- store.mergeSave(plain, NOW)
      loaded <- store.load()
    yield
      assertEquals(tabIds(merged), Vector("x"))
      assertEquals(loaded, Some(merged))
  }

  // ── #910 追补 spec 钉（2026-10-27 作者报障「关掉的页签还是老是会加载」）──
  // 任务钉面：①关闭→重载不复活 ②关闭→对端广播同页签不复活 ③重启（持久层）
  // 不复活。服务端侧 = 持久层三条：墓碑落盘、GET（load）永不含生效墓碑页签、
  // 全新实例（=进程重启）后关闭意图仍压过陈旧全量 PUT。前端恢复腿的钉在
  // tests/canvas-tab-tombstone-revive.spec.mjs。

  test("close → reload: GET archive excludes the closed tab while the tombstone persists") {
    for
      dir <- IO(os.temp.dir())
      store = new CanvasTabStore(dir / "canvas_tabs.json")
      _ <- store.mergeSave(json(s"""{"v":2,"tabs":${tabsJs("a", "b")}}"""), NOW)
      _ <- store.mergeSave(
        json(s"""{"v":2,"tabs":${tabsJs("a", "b")},"tombstones":[{"id":"b","closedAt":${NOW + 1}}]}"""),
        NOW + 2
      )
      fetched <- store.load() // GET 的唯一来源
    yield
      val archive = fetched.getOrElse(Json.Null)
      assertEquals(tabIds(archive), Vector("a"), "重载（GET）不含已关页签")
      assert(isActive(archive, "b"), "墓碑持久在场（重启不丢）")
  }

  test("restart (fresh store instance over the same file) keeps close intent: stale PUT still filtered") {
    for
      dir <- IO(os.temp.dir())
      path = dir / "canvas_tabs.json"
      store1 = new CanvasTabStore(path)
      _ <- store1.mergeSave(
        json(s"""{"v":2,"tabs":${tabsJs("b")},"tombstones":[{"id":"a","closedAt":$NOW}]}"""),
        NOW + 1
      )
      // 「进程重启」= 全新实例读同一持久层文件
      store2 = new CanvasTabStore(path)
      loaded <- store2.load()
      merged <- store2.mergeSave(json(s"""{"v":2,"tabs":${tabsJs("a", "b")}}"""), NOW + 2)
    yield
      assertEquals(tabIds(loaded.getOrElse(Json.Null)), Vector("b"), "重启后 GET 不含已关页签")
      assertEquals(tabIds(merged), Vector("b"), "重启后陈旧全量 PUT（对端广播同页签）仍不可复活")
      assert(isActive(merged, "a"), "重启后墓碑仍生效")
  }
