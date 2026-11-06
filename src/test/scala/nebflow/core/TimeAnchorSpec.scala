package nebflow.core

import munit.FunSuite

import java.time.{ZoneId, ZonedDateTime}
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * 时间感知修复批（作者指令 2026-11-05 18:48「修复你的时间感知问题」）——
 * [[nebflow.shared.TimeAnchor]] 的格式 / 可解析性 / 不冻结 / 跨时区 契约。
 *
 * 现场症状（本批复现用例）：把 **10 天前**（2026-10-06 / 10-10）的两次提交说成
 * 「两年」。病根 = 没有可信的「现在」。本 spec 钉的是「有可信的现在」这件事的
 * 可测面：
 *
 *   ① 一条时间锚必须**无歧义**：本地时间 + UTC 偏移 + 时区名 + 星期几 + epoch 毫秒
 *      五件齐（缺星期 = 模型要推「今天周几」；缺 epoch = 只能做日期串算术）；
 *   ② 必须**可精确回读**（渲染 → 解析 → 与原 instant 相等）：不可回读的格式就是
 *      诱人猜测的格式；
 *   ③ 必须**每次现取**：不得有任何缓存把「现在」冻在长生命周期对象里；
 *   ④ 跨时区：同一 instant 在两地必须渲染出不同的本地时间且 epoch 相同；
 *   ⑤ 时间**只准出现在回合级持久化消息**（时钟简化批 2026-11-06：真用户轮的
 *      `Now:` 极简时钟行 append-only 进历史；原「请求级动态段时钟」整体退役
 *      「永不主动注入」），绝不出现在 systemStable（缓存前缀）里——
 *      写进稳定前缀 = 时间冻结，比没有更坏。
 */
class TimeAnchorSpec extends FunSuite:

  /** 渲染串里「一个时刻」的正则：yyyy-MM-dd HH:mm:ss +08:00 */
  private val StampRe =
    raw"(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}) ([+-]\d{2}:\d{2}|Z)".r

  /** 任意渲染串里是否含一个真实时间戳（用于「不许进稳定前缀」的反向断言）。 */
  private def hasStamp(s: String): Boolean = StampRe.findFirstIn(s).isDefined

  private def fixed(epochMs: Long, zone: ZoneId): ZonedDateTime =
    ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(epochMs), zone)

  // 固定读数：2026-10-26T10:00:00Z = 2026-10-26 18:00 +08:00（Asia/Shanghai）
  private val SampleMs = 1793008800000L

  // ============================================================
  // ① 格式五件齐
  // ============================================================

  test("time anchor carries local time + UTC offset + zone name + weekday + epoch_ms"):
    val at = fixed(SampleMs, ZoneId.of("Asia/Shanghai"))
    val s = nebflow.shared.TimeAnchor.stamp(at)
    assert(StampRe.findFirstIn(s).isDefined, s"local time with offset missing: $s")
    assert(s.contains("+08:00"), s"UTC offset missing: $s")
    assert(s.contains("Asia/Shanghai"), s"zone NAME missing (offset alone is ambiguous): $s")
    assert(s.contains("Monday 周一"), s"weekday missing: $s")
    assert(s.contains(s"epoch_ms $SampleMs"), s"epoch_ms missing (no arithmetic base): $s")

  test("weekday rendering is locale-stable (JVM default locale must not shift bytes)"):
    val at = fixed(SampleMs, ZoneId.of("Asia/Shanghai"))
    val saved = java.util.Locale.getDefault
    try
      java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR")) // 土耳其语：i/İ 陷阱区
      val a = nebflow.shared.TimeAnchor.stamp(at)
      java.util.Locale.setDefault(java.util.Locale.forLanguageTag("zh-CN"))
      val b = nebflow.shared.TimeAnchor.stamp(at)
      assertEquals(a, b, "同一时刻在任何默认 Locale 下必须逐字节相同")
      assert(a.contains("Monday 周一"), a)
    finally java.util.Locale.setDefault(saved)

  // ============================================================
  // ② 可精确回读（渲染 → 解析 → 原 instant）
  // ============================================================

  test("rendered stamp parses back to the exact instant (no ambiguity left for guessing)"):
    val at = fixed(SampleMs, ZoneId.of("Asia/Shanghai"))
    val s = nebflow.shared.TimeAnchor.stamp(at)
    val extracted = StampRe.findFirstMatchIn(s) match
      case Some(m) => s"${m.group(1)}${m.group(2)}"
      case None => fail(s"no stamp in: $s")
    val parsed = OffsetDateTime.parse(extracted, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ssxxx"))
    assertEquals(parsed.toInstant.toEpochMilli, SampleMs, s"round-trip mismatch for: $s")
    assertEquals(parsed.toLocalDateTime, at.toLocalDateTime, "local time must round-trip too")

  // ============================================================
  // ③ 两个不同时刻 → 两个不同且各自正确的锚（"两条输入的锚不同"）
  // ============================================================

  test("two distinct instants render two distinct, individually correct anchors"):
    val zone = ZoneId.of("Asia/Shanghai")
    // 2026-10-06T02:00:00Z 与 2026-10-26T10:00:00Z：相差 20.33 天（现场症状里被说成
    // 「两年」的那类跨度——同一对读数，锚在就必须算得出来）
    val older = 1791252000000L
    val newer = SampleMs
    val a = nebflow.shared.TimeAnchor.stamp(fixed(older, zone))
    val b = nebflow.shared.TimeAnchor.stamp(fixed(newer, zone))
    assertNotEquals(a, b, "不同时刻必须渲染出不同的锚")
    assert(a.contains("2026-10-06 10:00:00 +08:00"), a)
    assert(b.contains("2026-10-26 18:00:00 +08:00"), b)
    assert(a.contains("epoch_ms 1791252000000"), a)
    assert(b.contains("epoch_ms 1793008800000"), b)
    // 差值可直接从 epoch 相减得出（这正是「换算而非推算」的底盘）
    assertEquals(newer - older, 1756800000L)
    assertEquals((newer - older) / 86_400_000L, 20L, "20 天量级——绝无「两年」")

  // ============================================================
  // ④ 跨时区：同一 instant，两地不同本地时间、相同 epoch
  // ============================================================

  test("same instant renders different local times in different zones, same epoch_ms"):
    val sh = nebflow.shared.TimeAnchor.stamp(fixed(SampleMs, ZoneId.of("Asia/Shanghai")))
    val la = nebflow.shared.TimeAnchor.stamp(fixed(SampleMs, ZoneId.of("America/Los_Angeles")))
    assertNotEquals(sh, la, "跨时区必须是两条不同的锚")
    assert(sh.contains("2026-10-26 18:00:00 +08:00"), sh)
    assert(la.contains("2026-10-26 03:00:00 -07:00"), la)
    assert(sh.contains("Asia/Shanghai") && la.contains("America/Los_Angeles"), s"$sh / $la")
    assert(sh.contains(s"epoch_ms $SampleMs") && la.contains(s"epoch_ms $SampleMs"), "epoch 恒等")
    // 两地星期同为周一（同日，不跨日界）——错位就是渲染 bug
    assert(sh.contains("Monday 周一") && la.contains("Monday 周一"), s"$sh / $la")

  test("cross-midnight: same instant can be two different weekdays in two zones"):
    // 2026-10-26T23:30:00Z = 上海 10-27 07:30（周二）/ 洛杉矶 10-26 16:30（周一）
    val ts = 1793057400000L
    val sh = nebflow.shared.TimeAnchor.stamp(fixed(ts, ZoneId.of("Asia/Shanghai")))
    val la = nebflow.shared.TimeAnchor.stamp(fixed(ts, ZoneId.of("America/Los_Angeles")))
    assert(sh.contains("2026-10-27 07:30:00 +08:00") && sh.contains("周二"), sh)
    assert(la.contains("2026-10-26 16:30:00 -07:00") && la.contains("Monday 周一"), la)

  // ============================================================
  // ⑤ 不冻结：每次现取
  // ============================================================

  test("now() is re-read on every call (a cached 'now' would freeze long sessions)"):
    val a = nebflow.shared.TimeAnchor.now()
    Thread.sleep(25) // 直接证明「现取」：缓存值必然相等
    val b = nebflow.shared.TimeAnchor.now()
    assert(b.isAfter(a), s"now() must advance between calls: $a vs $b")

  test("rendering is a pure function of the injected instant (no hidden 'now' inside)"):
    val at = fixed(SampleMs, ZoneId.of("Asia/Shanghai"))
    assertEquals(nebflow.shared.TimeAnchor.stamp(at), nebflow.shared.TimeAnchor.stamp(at))
    val other = fixed(SampleMs + 1000L, ZoneId.of("Asia/Shanghai"))
    assertNotEquals(
      nebflow.shared.TimeAnchor.stamp(at),
      nebflow.shared.TimeAnchor.stamp(other),
      "同一对象两次渲染同一时刻必须逐字节相同；换时刻必须变"
    )

  test("the persisted turn line keeps the Current time: prefix (prune/identify contract)"):
    val line = nebflow.shared.TimeAnchor.line(fixed(SampleMs, ZoneId.of("Asia/Shanghai")))
    assert(line.startsWith("Current time: "), line)
    assert(hasStamp(line), line)
    assert(
      SystemReminders.isTimeReminderMessage(
        nebflow.shared.Message(nebflow.shared.MessageRole.User, Left(s"<system-reminder>\n$line\n</system-reminder>"))
      ),
      "pruneTimeReminders / isTimeReminderMessage 必须仍能识别新格式"
    )

  test("the real-user-turn clock line is minimal and machine-readable (时钟简化批 2026-11-06)"):
    val line = nebflow.shared.TimeAnchor.userLine(fixed(SampleMs, ZoneId.of("Asia/Shanghai")))
    // 极简形（作者令「内容要简单一点 让能读懂是时间就行」）：
    // `Now: yyyy-MM-dd HH:mm:ss +08:00 周一 | epoch_ms <ms>` —— 无推导文案、无时区名、无英文星期
    assert(line.startsWith("Now: "), line)
    assert(StampRe.findFirstIn(line).isDefined, line)
    assert(line.contains("周一"), line)
    assert(line.contains(s"epoch_ms $SampleMs"), line)
    assert(!line.contains("| Asia/Shanghai"), s"极简形不带时区名: $line")
    assert(!line.toLowerCase.contains("derive"), s"极简形不带推导文案: $line")
    // append-only 保证：新形态不以 Current time: 开头 ⇒ 不被旧裁剪闸识别
    assert(!line.startsWith("Current time: "), line)
    assert(hasStamp(line), line)

  test("userLine is not recognized as a legacy prunable time reminder (append-only contract)"):
    val line = nebflow.shared.TimeAnchor.userLine(fixed(SampleMs, ZoneId.of("Asia/Shanghai")))
    val msg = nebflow.shared.Message(nebflow.shared.MessageRole.User, Left(s"<system-reminder>\n$line\n</system-reminder>"))
    assert(!SystemReminders.isTimeReminderMessage(msg), "新形态必须不被识别 ⇒ 永不裁剪")
    // 旧形态仍被识别（旧会话历史的裁剪契约不变）
    val legacy = nebflow.shared.TimeAnchor.line(fixed(SampleMs, ZoneId.of("Asia/Shanghai")))
    assert(
      SystemReminders.isTimeReminderMessage(
        nebflow.shared.Message(nebflow.shared.MessageRole.User, Left(s"<system-reminder>\n$legacy\n</system-reminder>"))
      ),
      "旧形态识别契约不变"
    )

  // ============================================================
  // ⑥ 时间纪律规则本体（模型面）：禁止无依据的日期推断
  // ============================================================

  test("TimeDisciplineRule forbids unfounded date inference and points at the anchor"):
    val rule = nebflow.shared.Defaults.TimeDisciplineRule
    // 允许引用锚
    assert(rule.contains("<system-reminder>"), "必须指向 system-reminder 包裹的时钟行")
    assert(rule.contains("Now: <local time and UTC offset> <weekday> | epoch_ms <ms>"), "必须点明极简时钟行格式")
    assert(rule.contains("Current time:"), "必须点明历史里的回合锚")
    assert(rule.contains("epoch_ms"), "必须点明算术底座")
    // 禁止无依据推断（硬规则原文）
    assert(rule.contains("MUST be derived from that anchor"), rule)
    assert(rule.contains("Never from memory, never from feeling, never from a training prior"), rule)
    assert(rule.contains("you may NOT state an absolute date, a weekday, or an elapsed duration"), rule)
    assert(rule.contains("A long turn does not freeze time"), rule)
    // 相对时间词表必须在场（这正是误判高发面）
    Seq("today/今天", "yesterday/昨天", "N minutes ago/N 分钟前", "last time/上次").foreach { w =>
      assert(rule.contains(w), s"relative-time vocabulary missing: $w")
    }

  // ============================================================
  // ⑦ 活体读数（本批报告里的「改后样例行」由本用例直接产出）
  // ============================================================

  test("live sample: the anchor forms the model actually reads (taken from the running clock)"):
    val line = nebflow.shared.TimeAnchor.line()
    val user = nebflow.shared.TimeAnchor.userLine()
    assert(line.startsWith("Current time: "), line)
    assert(hasStamp(line), line)
    assert(user.startsWith("Now: ") && hasStamp(user), user)
    println(s"[time-anchor-sample/legacy-persisted] $line")
    println(s"[time-anchor-sample/user-turn-minimal] $user")

end TimeAnchorSpec
