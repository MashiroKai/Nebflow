package nebflow.shared

import munit.FunSuite

import java.time.ZoneId

/**
 * 模型不可用时段判定 spec（model-schedule-unavailable 批，2026-10-29）——纯函数
 * 谓词面（设计件 §3/§7）：
 *   - 边界：[from, to)——14:00 整即禁、18:00 整即解；
 *   - 跨午夜：from > to 两腿覆盖，清晨腿的星期归**昨天**；
 *   - 多条规则并集；days（ISO 星期）过滤；非法/零长 fail-safe 永不命中；
 *   - 缺省（空列表）= 无限制。
 *
 * 判定用固定日期注入 now（eval 用系统时区，测试同侧构造，口径一致——
 * FreezeScheduleSpec 同法）。
 */
class ModelAvailabilitySpec extends FunSuite:

  private val zone = ZoneId.systemDefault()

  private def at(day: Int, h: Int, m: Int): Long =
    java.time.LocalDate.of(2026, 10, day).atTime(h, m).atZone(zone).toInstant.toEpochMilli
  // 2026-10-29 = 周四（ISO 4）；10-30 = 周五（5）；10-28 = 周三（3）；11-01 = 周日（7）
  private val thu = 29
  private val wed = 28
  private val fri = 30

  private def w(from: String, to: String, days: List[Int] = Nil): UnavailableWindow =
    UnavailableWindow(from, to, if days.isEmpty then None else Some(days))

  // ── 边界（[from, to)）─────────────────────────────────────

  test("T1 边界：14:00 整即禁（含起点）") {
    assert(ModelAvailability.isUnavailable(List(w("14:00", "18:00")), at(thu, 14, 0)))
  }
  test("T2 边界：13:59 仍可用") {
    assert(!ModelAvailability.isUnavailable(List(w("14:00", "18:00")), at(thu, 13, 59)))
  }
  test("T3 边界：18:00 整即解（不含终点）") {
    assert(!ModelAvailability.isUnavailable(List(w("14:00", "18:00")), at(thu, 18, 0)))
  }
  test("T4 边界：17:59 仍禁") {
    assert(ModelAvailability.isUnavailable(List(w("14:00", "18:00")), at(thu, 17, 59)))
  }

  // ── 跨午夜 ────────────────────────────────────────────────

  test("T5 跨午夜：23:00-07:00 覆盖 23:30 与 06:59，不覆盖 07:00 与 22:59") {
    val win = List(w("23:00", "07:00"))
    assert(ModelAvailability.isUnavailable(win, at(thu, 23, 30)), "当晚腿")
    assert(ModelAvailability.isUnavailable(win, at(fri, 6, 59)), "次日清晨腿")
    assert(!ModelAvailability.isUnavailable(win, at(fri, 7, 0)), "07:00 整即解")
    assert(!ModelAvailability.isUnavailable(win, at(thu, 22, 59)), "开窗前")
  }
  test("T6 跨午夜 + days：清晨腿归昨天的星期（周四 23:00 开窗 → 周五 06:59 仍禁；周五 23:00 开窗、周六 06:59 禁）") {
    val win = List(w("23:00", "07:00", days = List(4))) // 仅周四
    assert(ModelAvailability.isUnavailable(win, at(thu, 23, 30)), "周四晚腿（today=周四）")
    assert(ModelAvailability.isUnavailable(win, at(fri, 6, 59)), "周五清晨腿（yesterday=周四）")
    assert(!ModelAvailability.isUnavailable(win, at(fri, 23, 30)), "周五晚不在窗（周五∉days）")
    assert(!ModelAvailability.isUnavailable(win, at(wed, 6, 59)), "周三清晨（yesterday=周二∉days）")
  }

  // ── days（ISO 星期）────────────────────────────────────────

  test("T7 days 过滤：仅工作日 → 周四命中、周六不命中") {
    val win = List(w("14:00", "18:00", days = List(1, 2, 3, 4, 5)))
    assert(ModelAvailability.isUnavailable(win, at(thu, 15, 0)), "周四∈工作日")
    val sat = java.time.LocalDate.of(2026, 10, 31).atTime(15, 0).atZone(zone).toInstant.toEpochMilli
    assert(!ModelAvailability.isUnavailable(win, sat), "周六∉工作日")
  }

  // ── 多条并集 / 缺省零限制 ──────────────────────────────────

  test("T8 多条规则取并集：任一命中即受限") {
    val wins = List(w("12:00", "13:00"), w("14:00", "18:00"))
    assert(ModelAvailability.isUnavailable(wins, at(thu, 12, 30)))
    assert(ModelAvailability.isUnavailable(wins, at(thu, 15, 0)))
    assert(!ModelAvailability.isUnavailable(wins, at(thu, 13, 30)), "两窗之间可用")
  }
  test("T9 缺省（空列表）= 无限制（缺省零行为变化铁律）") {
    assert(!ModelAvailability.isUnavailable(Nil, at(thu, 15, 0)))
  }

  // ── fail-safe（非法配置 = 关闭该条）────────────────────────

  test("T10 零长段（from==to）非法：永不命中（不定义为 24h 全禁——FreezeSchedule D6 同裁）") {
    assert(!ModelAvailability.isUnavailable(List(w("14:00", "14:00")), at(thu, 15, 0)))
    assert(!ModelAvailability.isValidWindow(w("14:00", "14:00")))
  }
  test("T11 非法时刻格式 fail-safe：永不命中、不抛") {
    assert(!ModelAvailability.isUnavailable(List(w("25:00", "18:00")), at(thu, 15, 0)))
    assert(!ModelAvailability.isUnavailable(List(w("14:00", "xx:00")), at(thu, 15, 0)))
    assert(!ModelAvailability.isValidWindow(w("bad", "18:00")))
  }
  test("T12 非法条不拖累合法条：合法条照常生效") {
    val wins = List(w("bad", "18:00"), w("14:00", "18:00"))
    assert(ModelAvailability.isUnavailable(wins, at(thu, 15, 0)))
  }

  // ── 描述串（日志/错误消息/面板可见性面）────────────────────

  test("T13 describeWindow/Windows：可读形态") {
    assertEquals(ModelAvailability.describeWindow(w("14:00", "18:00")), "14:00-18:00")
    assertEquals(ModelAvailability.describeWindow(w("23:00", "07:00", days = List(6, 7))), "23:00-07:00(周6,周7)")
    assertEquals(ModelAvailability.describeWindows(List(w("12:00", "13:00"), w("14:00", "18:00"))), "12:00-13:00, 14:00-18:00")
  }
  test("T14 activeWindowDesc：命中返回该窗描述，未命中 None") {
    assertEquals(ModelAvailability.activeWindowDesc(List(w("14:00", "18:00")), at(thu, 15, 0)), Some("14:00-18:00"))
    assertEquals(ModelAvailability.activeWindowDesc(List(w("14:00", "18:00")), at(thu, 19, 0)), None)
  }
end ModelAvailabilitySpec
