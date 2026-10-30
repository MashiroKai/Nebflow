package nebflow.core.scheduler

import cats.effect.IO
import cats.effect.Ref
import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.shared.PathUtil

import java.time.{Instant, ZoneId}

/**
 * 认知二期产品批④周报生成器：纯函数（周界/聚合/markdown/SVG）+ 生成器
 * tick（marker 判重、catch-up、产物落盘、广播）。零超时设计：正确性全在
 * marker 状态表达，生成时刻不承载语义。
 */
class WeeklyReportSpec extends CatsEffectSuite:

  private val tempRoot: os.Path = os.pwd / "target" / "test-weekly-report"
  private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

  override def beforeEach(context: BeforeEach): Unit =
    if os.exists(tempRoot) then os.remove.all(tempRoot)
    os.makeDir.all(tempRoot)
    PathUtil.setDataRoot(tempRoot)

  /** 2026-11-02 = 周一（Asia/Shanghai）。上一完整周 = 2026-10-26 周一起。 */
  private val mondayAfter: Instant = ZonedDateTimeHelper.mondayAt9(2026, 11, 2, zone)

  private def rec(doneAt: Instant): DoneRecord =
    DoneRecord("t1", Some("plan"), "今天：深蹲 5×5", "s1", doneAt.toEpochMilli, "tool")

  test("weekKeyOf：ISO week-based 形态") {
    assertEquals(WeeklyReport.weekKeyOf(mondayAfter, zone).take(5), "2026-")
    assert(WeeklyReport.weekKeyOf(mondayAfter, zone).matches("\\d{4}-W\\d{2}"))
  }

  test("lastWeekBounds：上一个完整自然周（周一 00:00 起，7 天）") {
    val (from, to, key) = WeeklyReport.lastWeekBounds(mondayAfter, zone)
    assertEquals(to - from, 7L * 86_400_000L)
    assertEquals(WeeklyReport.weekKeyOf(Instant.ofEpochMilli(from + 3 * 86_400_000L), zone), key)
    val fromDay = java.time.ZonedDateTime.ofInstant(Instant.ofEpochMilli(from), zone).getDayOfWeek
    assertEquals(fromDay, java.time.DayOfWeek.MONDAY)
  }

  test("aggregate：逐日计数 / 最长连打 / 打卡率 / 上周对比") {
    // 上周（2026-10-19 周）：2 条；上上周周内：3 条（周一/二/四）。
    val prevWeekBase = mondayAfter.minus(java.time.Duration.ofDays(14)) // 上上周周一 09:00
    val prevWeek = mondayAfter.minus(java.time.Duration.ofDays(7))
    val records = List(
      rec(prevWeekBase),                                   // 上上周周一
      rec(prevWeekBase.plus(java.time.Duration.ofDays(1))),
      rec(prevWeekBase.plus(java.time.Duration.ofDays(3))),
      rec(prevWeek),                                       // 上周周一
      rec(prevWeek.plus(java.time.Duration.ofDays(1)))
    )
    val stats = WeeklyReport.aggregate(records, openTodoCount = 3, mondayAfter, zone)
    assertEquals(stats.doneCount, 2)
    assertEquals(stats.plannedCount, 5) // 2 done + 3 open
    assertEquals(stats.perDay.take(2), List(1, 1))
    assertEquals(stats.perDay.drop(2).sum, 0)
    assertEquals(stats.bestStreakDays, 2)
    assertEquals(stats.prevDoneCount, Some(3))
    assertEquals(stats.trendArrow, "↓")
    assertEquals(Math.round(stats.rate * 100), 40L)
  }

  test("aggregate：无计划不报假红（rate=1）；无上周数据趋势 →") {
    val stats = WeeklyReport.aggregate(Nil, 0, mondayAfter, zone)
    assertEquals(stats.rate, 1.0)
    assertEquals(stats.trendArrow, "→")
  }

  test("renderMarkdown：附录 C 四段 + ```human 围栏 + 热力图引用") {
    val stats = WeeklyReport.aggregate(
      List(rec(mondayAfter.minus(java.time.Duration.ofDays(6)))),
      openTodoCount = 1,
      mondayAfter,
      zone
    )
    val md = WeeklyReport.renderMarkdown(stats, "checkin-heatmap-X.svg", List("今天：跑步 3km"), zone)
    assert(md.contains("```human"), "人面子围栏在")
    assert(md.contains("**关键数字**"), "关键数字行在")
    assert(md.contains("![[checkin-heatmap-X.svg]]"), "热力图附件引用在")
    assert(md.contains("## 发现") && md.contains("## 下周计划"), "Findings/下周计划在")
    assert(md.contains("1/2"), "打卡率数字在")
    assert(!md.contains("```human\n```"), "围栏体非空")
  }

  test("renderHeatmapSvg：合法 SVG，含周键与总数，7 个色块") {
    val stats = WeeklyReport.aggregate(
      List(rec(mondayAfter.minus(java.time.Duration.ofDays(6))), rec(mondayAfter.minus(java.time.Duration.ofDays(6)))),
      0,
      mondayAfter,
      zone
    )
    val svg = WeeklyReport.renderHeatmapSvg(stats)
    assert(svg.startsWith("<svg xmlns="), "SVG 根元素")
    assert(svg.contains(stats.weekKey), "周键在图注")
    assertEquals(svg.split("<rect").length - 1, 7)
  }

  test("生成器：到期生成 REPORT.md + SVG + marker + 广播；重复 tick 不重复生成") {
    val ws = tempRoot / "ws"
    os.makeDir.all(ws / "docs" / "attachments")
    val taskStore = new ScheduledTaskStore(tempRoot / "scheduled-tasks")
    val store = DoneRecordStore(tempRoot)
    val broadcasted = Ref.unsafe[IO, List[Json]](Nil)
    val gen = new WeeklyReportGenerator(
      taskStore,
      store,
      IO.pure(Some(ws)),
      j => broadcasted.update(_ :+ j),
      (tempRoot / "weekly-report" / "state.json").toNIO
    )
    for
      _ <- store.record(rec(mondayAfter.minus(java.time.Duration.ofDays(6))))
      _ <- gen.tick(mondayAfter, zone)
      reports = os.list(ws / "reports")
      _ = assertEquals(reports.length, 1)
      md = os.read(reports.head / "REPORT.md")
      _ = assert(md.contains("# 打卡周报"))
      svgs = os.list(ws / "docs" / "attachments")
      _ = assertEquals(svgs.length, 1)
      _ = assert(svgs.head.last.endsWith(".svg"))
      frames <- broadcasted.get
      _ = assertEquals(frames.length, 1)
      _ = assertEquals(frames.head.hcursor.downField("type").as[String].toOption, Some("weeklyReportReady"))
      // 再 tick：marker 已落 ⇒ 零新增
      _ <- gen.tick(mondayAfter.plus(java.time.Duration.ofDays(1)), zone)
      _ <- IO.delay(assertEquals(os.list(ws / "reports").length, 1))
      frames2 <- broadcasted.get
      _ = assertEquals(frames2.length, 1)
    yield ()
  }

  test("生成器：停机错过 ⇒ 下次 tick 补生成（catch-up）") {
    val ws = tempRoot / "ws2"
    os.makeDir.all(ws / "docs" / "attachments")
    val taskStore = new ScheduledTaskStore(tempRoot / "scheduled-tasks-2")
    val gen = new WeeklyReportGenerator(
      taskStore,
      DoneRecordStore(tempRoot),
      IO.pure(Some(ws)),
      _ => IO.unit,
      (tempRoot / "weekly-report-2" / "state.json").toNIO
    )
    // marker 空 + 本周三（错过周一）⇒ 仍生成上一完整周。
    gen.tick(mondayAfter.plus(java.time.Duration.ofDays(2)), zone)
      .map(_ => assertEquals(os.list(ws / "reports").length, 1))
  }

  test("生成器：无可写工作区 ⇒ 跳过且不写 marker（下次有工作区补生成）") {
    val taskStore = new ScheduledTaskStore(tempRoot / "scheduled-tasks-3")
    val gen = new WeeklyReportGenerator(
      taskStore,
      DoneRecordStore(tempRoot),
      IO.pure(None),
      _ => IO.unit,
      (tempRoot / "weekly-report-3" / "state.json").toNIO
    )
    gen.tick(mondayAfter, zone).map { _ =>
      assert(!os.exists(tempRoot / "weekly-report-3" / "state.json"), "marker 未落")
    }
  }

end WeeklyReportSpec

/** 测试小件：某周周一 09:00（本地 zone）的 Instant。 */
private object ZonedDateTimeHelper:
  def mondayAt9(year: Int, month: Int, day: Int, zone: ZoneId): Instant =
    java.time.ZonedDateTime.of(year, month, day, 9, 0, 0, 0, zone).toInstant
