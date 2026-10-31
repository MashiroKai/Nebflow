package nebflow.core.scheduler

import cats.effect.IO
import io.circe.parser.parse as jsonParse
import io.circe.syntax.*
import io.circe.Json
import nebflow.shared.NebflowLogger

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path as JPath, StandardOpenOption}
import java.time.format.DateTimeFormatter
import java.time.{DayOfWeek, Instant, ZoneId, ZonedDateTime}

/**
 * 周报生成器（认知二期产品批·设计稿 §6.5 第 5 段 + §9-12 裁定：引擎生成＋
 * 收件面浮出＋可归档 reports/；模板 = 附录 C 实例化）。
 *
 * 周期与正确性（零超时设计：一切正确性靠状态表达，不靠定时精度）：
 * 每个引擎 tick 跑 [[WeeklyReportGenerator.tick]]，谓词便宜（读一枚 marker
 * JSON + 本地日判）；该生成周（上一个完整自然周，周一 00:00–周日 24:00）有
 * 报告且 marker ≠ 该周键 ⇒ 生成并落 marker。引擎停机错过周一 ⇒ 下次 tick
 * 补生成（catch-up）；marker 持久化 ⇒ 重复 tick/重启不重复生成。
 *
 * 产物：
 * - 周报 markdown → `<workspace>/reports/<yyyyMMdd>_weekly-checkin/REPORT.md`
 *   （reports/ = 纯过程文件，零 frontmatter，仓内约定）
 * - 打卡热力图 SVG → `<workspace>/docs/attachments/checkin-heatmap-<weekKey>.svg`
 *   （附件纪律：docs/attachments/ 供 vault 索引；SVG = 零依赖矢量图，趋势一图
 *   一结论，图注 = 结论句）
 *
 * 工作区解析：注入 `resolveWorkspace: IO[Option[os.Path]]`（GatewayMain 装配 =
 * 第一个本机非归档项目；None = 本机无可写工作区，记日志跳过，绝不静默写别处）。
 */
object WeeklyReport:

  /** 一周聚合读数（纯数据；spec 直断言）。 */
  final case class WeekStats(
    weekKey: String,
    /** 周起点（周一 00:00 本地，epoch ms）。 */
    fromMs: Long,
    toMs: Long,
    /** 本周完成条数。 */
    doneCount: Int,
    /** 打卡率分母：完成 + 当前仍在库的待办条数（本周新账旧欠同视）。 */
    plannedCount: Int,
    /** 最长连续打卡天数（本周内，有 ≥1 条完成即算一天）。 */
    bestStreakDays: Int,
    /** 当前未销账待办数 = 计划负荷。 */
    openTodos: Int,
    /** 上周完成条数（趋势对比面；无上周数据 = None）。 */
    prevDoneCount: Option[Int],
    /** 逐日完成数（周一起，7 天）。 */
    perDay: List[Int]
  ):
    /** 打卡率（0..1；分母 0 = 1.0——无计划无可缺，不报假红）。 */
    def rate: Double = if plannedCount <= 0 then 1.0 else doneCount.toDouble / plannedCount

    /** 趋势箭头：↑ 优于上周 / → 持平或无对比 / ↓ 逊于上周。 */
    def trendArrow: String =
      prevDoneCount match
        case None => "→"
        case Some(p) if doneCount > p => "↑"
        case Some(p) if doneCount < p => "↓"
        case Some(_) => "→"

  /** 周键（ISO week-based 年-周序，marker/文件名片段共用）。 */
  def weekKeyOf(instant: Instant, zone: ZoneId): String =
    val date = ZonedDateTime.ofInstant(instant, zone).toLocalDate
    val weekBasedYear = date.get(java.time.temporal.IsoFields.WEEK_BASED_YEAR)
    val week = date.get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR)
    f"$weekBasedYear%04d-W$week%02d"

  /** 自然周界（上一个完整周：含 `instant` 的周的前一周，周一 00:00 起）。 */
  def lastWeekBounds(instant: Instant, zone: ZoneId): (Long, Long, String) =
    val today = ZonedDateTime.ofInstant(instant, zone).toLocalDate
    val thisMonday = today.`with`(DayOfWeek.MONDAY)
    val monday = thisMonday.minusDays(7)
    val from = monday.atStartOfDay(zone).toInstant.toEpochMilli
    val to = thisMonday.atStartOfDay(zone).toInstant.toEpochMilli
    (from, to, weekKeyOf(Instant.ofEpochMilli(from + 86_400_000L * 3), zone))

  /** 纯聚合（now/zone 注入——spec 钉）：从全量 done 记录 + 当前未销账数算一周读数。 */
  def aggregate(
    allRecords: List[DoneRecord],
    openTodoCount: Int,
    instant: Instant,
    zone: ZoneId
  ): WeekStats =
    val (fromMs, toMs, key) = lastWeekBounds(instant, zone)
    val week = allRecords.filter(r => r.doneAt >= fromMs && r.doneAt < toMs)
    val prevFrom = fromMs - 7 * 86_400_000L
    val prev = allRecords.count(r => r.doneAt >= prevFrom && r.doneAt < fromMs)
    val perDay = (0 until 7).toList.map { i =>
      val dayStart = fromMs + i * 86_400_000L
      week.count(r => r.doneAt >= dayStart && r.doneAt < dayStart + 86_400_000L)
    }
    // 最长连续打卡天数：逐日计数 > 0 的游程最长值（一天多条 = 一天）。
    val streaks = perDay.foldLeft((0, 0)) { case ((best, cur), n) =>
      if n > 0 then (best.max(cur + 1), cur + 1) else (best, 0)
    }
    WeekStats(
      weekKey = key,
      fromMs = fromMs,
      toMs = toMs,
      doneCount = week.length,
      plannedCount = week.length + openTodoCount,
      bestStreakDays = streaks._1,
      openTodos = openTodoCount,
      prevDoneCount = if prev > 0 || allRecords.exists(_.doneAt < fromMs) then Some(prev) else None,
      perDay = perDay
    )

  private def fmtDate(ms: Long, zone: ZoneId): String =
    DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(zone).format(Instant.ofEpochMilli(ms))

  /**
   * 周报 markdown（附录 C 模板实例化）：Impression（```human 围栏，人面子义务）
   * → 关键数字 → 热力图附件引用 → 发现（逐日记录，机器面）→ 下周计划（未销账
   * 清单）。图注 = 趋势结论句（一图一结论，P4）。
   */
  def renderMarkdown(stats: WeekStats, heatmapFileName: String, openTodos: List[String], zone: ZoneId): String =
    val ratePct = Math.round(stats.rate * 100).toInt
    val impression =
      s"本周打卡 ${stats.doneCount}/${stats.plannedCount}（${ratePct}%），趋势${stats.trendArrow}" +
        (if stats.bestStreakDays > 1 then s"，最长连打 ${stats.bestStreakDays} 天" else "") +
        (if stats.openTodos > 0 then s"；${stats.openTodos} 项未销账带入下周" else "；账已清") +
        "。"
    val keyNumbers =
      s"打卡率 ${stats.doneCount}/${stats.plannedCount} · 连续天数 ${stats.bestStreakDays} · 计划负荷 ${stats.openTodos}"
    val caption =
      s"本周热力图：${stats.perDay.count(_ > 0)} 天有打卡，峰值 ${stats.perDay.max} 条/天，趋势${stats.trendArrow}。"
    val findings = stats.perDay.zipWithIndex.map { case (n, i) =>
      val day = fmtDate(stats.fromMs + i * 86_400_000L, zone)
      val dayName = Seq("周一", "周二", "周三", "周四", "周五", "周六", "周日")(i)
      s"- $day（$dayName）：完成 $n 条"
    }.mkString("\n")
    val nextPlan =
      if openTodos.isEmpty then "（无未销账待办——新计划可直接入账。）"
      else openTodos.zipWithIndex.map { case (c, i) => s"${i + 1}. $c" }.mkString("\n")

    s"""# 打卡周报 · 第 ${stats.weekKey} 周（${fmtDate(stats.fromMs, zone)} ~ ${fmtDate(stats.toMs - 1, zone)}）
       |
       |```human
       |## 印象
       |$impression
       |
       |**关键数字**：$keyNumbers
       |![[${heatmapFileName}]]   <!-- 打卡热力图，图注=趋势结论句 -->
       |$caption
       |```
       |
       |## 发现
       |$findings
       |
       |## 下周计划
       |$nextPlan
       |""".stripMargin

  /** 热力图 SVG（GitHub 贡献图形态：7 行工作日 × 1 列本周，色阶 = 当日条数）。 */
  def renderHeatmapSvg(stats: WeekStats): String =
    val cell = 16
    val gap = 3
    val pad = 12
    val dayNames = Seq("一", "二", "三", "四", "五", "六", "日")
    val max = math.max(1, stats.perDay.max)
    def level(n: Int): String =
      if n <= 0 then "#ebedf0"
      else
        val l = (n.toDouble / max * 3).toInt
        Seq("#9be9a8", "#40c463", "#30a14e", "#216e39")(math.min(3, l))
    val cells = (0 until 7).map { i =>
      val y = pad + i * (cell + gap)
      val n = stats.perDay(i)
      s"""<rect x="$pad" y="$y" width="$cell" height="$cell" rx="2" fill="${level(n)}"><title>周${dayNames(i)} · $n 条</title></rect>"""
    }.mkString("\n    ")
    val labels = (0 until 7).map { i =>
      val y = pad + i * (cell + gap) + cell - 4
      s"""<text x="${pad - 6}" y="$y" text-anchor="end" font-size="9" fill="#57606a">周${dayNames(i)}</text>"""
    }.mkString("\n    ")
    val w = pad * 2 + cell
    val h = pad * 2 + 7 * (cell + gap)
    s"""<svg xmlns="http://www.w3.org/2000/svg" width="$w" height="$h" viewBox="0 0 $w $h" font-family="-apple-system, 'Segoe UI', sans-serif">
    $labels
    $cells
    <text x="$pad" y="${h - 2}" font-size="9" fill="#57606a">第 ${stats.weekKey} 周 · 共 ${stats.doneCount} 条 · 趋势${stats.trendArrow}</text>
    </svg>"""

end WeeklyReport

/**
 * 生成器（状态 + 落盘 + 广播 + 收件箱入账）。`tick` 每轮引擎调度循环调用一次，
 * 内部谓词早退（非周一后/已生成周 ⇒ 零写盘零广播零入账）。
 *
 * @param resolveWorkspace 本机可写工作区解析（None = 跳过 + 日志，不静默写别处）
 * @param broadcast 周报就绪广播面（多窗同步 + 前端收件箱刷新信号）
 * @param stateFile marker 持久化位置（默认 dataRoot 内；spec 注临时路径）
 * @param activeSession 收件箱入账会话解析（§6.4 回填）：空串 = 无活跃会话，
 *   跳过入账（报告与广播不受影响）；默认 IO.pure("") 保存量 spec 构造零改动。
 */
final class WeeklyReportGenerator(
  taskStore: ScheduledTaskStore,
  doneRecords: DoneRecordStore,
  resolveWorkspace: IO[Option[os.Path]],
  broadcast: Json => IO[Unit],
  stateFile: JPath,
  activeSession: IO[String] = IO.pure("")
):

  private val logger = NebflowLogger.forName("nebflow.weekly-report")

  private case class Marker(lastReportedWeek: String = "")
  private object Marker:
    import io.circe.generic.semiauto.*
    implicit val decoder: io.circe.Decoder[Marker] = deriveDecoder
    implicit val encoder: io.circe.Encoder[Marker] = deriveEncoder

  private def readMarker: IO[Marker] =
    IO.blocking {
      if Files.exists(stateFile) then
        jsonParse(new String(Files.readAllBytes(stateFile), StandardCharsets.UTF_8))
          .flatMap(_.as[Marker])
          .getOrElse(Marker())
      else Marker()
    }

  private def writeMarker(m: Marker): IO[Unit] =
    IO.blocking {
      Option(stateFile.getParent).foreach(p => Files.createDirectories(p))
      Files.write(
        stateFile,
        m.asJson.noSpaces.getBytes(StandardCharsets.UTF_8),
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING
      )
    }

  /**
   * 一周一应谓词（纯判据，spec 钉）：「上一个完整自然周」的键 ≠ marker 且
   * 本地今天 ≥ 周一（任何时刻——周一 00:00 后 tick 即补生成，不等 09:00：
   * 正确性全在 marker，生成时刻不承载语义）。
   */
  def dueWeek(instant: Instant, zone: ZoneId, markerWeek: String): Option[String] =
    val (_, _, key) = WeeklyReport.lastWeekBounds(instant, zone)
    if key != markerWeek then Some(key) else None

  /** 主 tick：早退便宜；该生成 ⇒ 聚合 → 写 REPORT.md + SVG → marker → 广播。 */
  def tick(instant: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): IO[Unit] =
    readMarker.flatMap { marker =>
      dueWeek(instant, zone, marker.lastReportedWeek) match
        case None => IO.unit
        case Some(weekKey) =>
          (for
            wsOpt <- resolveWorkspace
            _ <- wsOpt match
              case None =>
                logger.info("Weekly report due but no local workspace — skipping (no silent fallback dir)")
              case Some(ws) =>
                for
                  all <- doneRecords.loadAll
                  openTodos <- taskStore.getAllTodos
                  stats = WeeklyReport.aggregate(all, openTodos.length, instant, zone)
                  heatmapName = s"checkin-heatmap-$weekKey.svg"
                  stamp = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(zone).format(instant)
                  reportDir = ws / "reports" / s"${stamp}_weekly-checkin"
                  _ <- IO.blocking { os.makeDir.all(reportDir); os.makeDir.all(ws / "docs" / "attachments") }
                  md = WeeklyReport.renderMarkdown(stats, heatmapName, openTodos.map(_.content), zone)
                  _ <- IO.blocking { os.write.over(reportDir / "REPORT.md", md) }
                  _ <- IO.blocking { os.write.over(ws / "docs" / "attachments" / heatmapName, WeeklyReport.renderHeatmapSvg(stats)) }
                  _ <- writeMarker(Marker(weekKey))
                  reportPath = (reportDir / "REPORT.md").toString
                  // §6.4 接合面回填（§9-12/§9-13 授权）：周报 Ready 从通知横幅改为
                  // 待办收件箱浮出——upsert 一条 kind=todo 条目，走既有待办语义
                  // （引擎持久 / 每日 09:00 汇总浮出接 digest 面 / done 销账 = P2
                  // 闭合回路），零新机制。接合面两件：
                  //   (a) 来源动作：content = 反悬念结论（P6 文案给结论不给悬念）+
                  //       报告路径，referencePath 落报告现场（digest/面板可回得到）。
                  //   (b) 浮出节奏 = 聚合（每日 09:00 汇总）——提醒节奏的既有引擎
                  //       语义落点；引擎侧无按人分频机制，不增设条目级提示字段（反增额）。
                  // name 带周键：marker 判重之外再以状态表达收敛——重复 tick/重启
                  // 补跑同名 upsert ⇒ 单份不叠加（§6.4「name 稳定 upsert」语义）。
                  sid <- activeSession
                  _ <-
                    if sid.isEmpty then
                      logger.info("Weekly report surfaced but no active session — inbox entry skipped (report archived)")
                    else
                      taskStore
                        .upsertTaskByName(
                          ScheduledTask.create(
                            sessionId = sid,
                            content =
                              s"打卡周报 $weekKey 已生成：打卡 ${stats.doneCount}/${stats.plannedCount}，趋势${stats.trendArrow}" +
                                s" — 周报：$reportPath（读后可打卡销账）",
                            triggerAt = ScheduledTask.nextTodoSurfaceAt(instant.toEpochMilli, zone),
                            referencePath = Some(reportPath),
                            name = Some(s"weekly-report-$weekKey"),
                            kind = Some(ScheduledTask.KindTodo)
                          )
                        )
                        .void
                  summary = s"打卡周报 ${weekKey} 已生成：打卡 ${stats.doneCount}/${stats.plannedCount}，趋势${stats.trendArrow}（reports/weekly-checkin）"
                  _ <- logger.info(s"Weekly report written: $reportPath")
                  _ <- broadcast(
                    Json.obj(
                      "type" -> "weeklyReportReady".asJson,
                      "weekKey" -> weekKey.asJson,
                      "reportPath" -> (reportDir / "REPORT.md").toString.asJson,
                      "summary" -> summary.asJson
                    )
                  )
                yield ()
          yield ()
        ).handleErrorWith(e => logger.warn(s"Weekly report generation failed: ${e.getMessage}").void)
    }

end WeeklyReportGenerator
