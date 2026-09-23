package nebflow.core.project

import cats.effect.IO
import cats.syntax.all.*
import io.circe.parser.parse as jsonParse
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.core.PathUtil
import nebflow.shared.{Defaults, SleepWindow}

/**
 * 宿主睡眠感知单测（hostresume 批 2026-09-22，设计卡 §4 #2/#6/#8 / §6 口径 3、4、5）。
 *
 * 覆盖：双钟断流判定纯函数（DarkWake 突刺回放 = 口径 4；NTP 步进 = 口径 5）、台账
 * kind 标注形态（口径 3 前半：kind=sleep/wake + blocking=false 恒）、append-only 与
 * boot 条目共存（口径 3 后半：duplicate-boot 幂等不受影响）、停机成因标注三态、
 * kill-switch launch 闸。纤维拍级（真实时钟驱动）验证归隔离实例 e2e。
 */
class WakeSensorSpec extends CatsEffectSuite:

  private val root: os.Path = os.pwd / "target" / "test-wake-sense"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(root)
  os.remove.all(root)
  os.makeDir.all(root)

  override def afterAll(): Unit = PathUtil.setDataRoot(originalRoot)

  override def afterEach(context: munit.AfterEach): Unit =
    os.remove.all(root)
    os.makeDir.all(root)
    sys.props.remove("nebflow.wake.sense.enabled")
    super.afterEach(context)

  private val slop = Defaults.WakeSenseSlopMs // 45_000

  private def pdOf(name: String): ProjectDef =
    val ws = root / "projects" / name
    os.makeDir.all(ws)
    ProjectDef(name = name, workspace = ws.toString, agentFile = (ws / "AGENTS.md").toString, createdAt = 1L)

  private def await[A](io: IO[A]): A = io.unsafeRunSync()

  /** 读台账（测试面直读文件反序列化——readMarker 是对象内 private，测试走公开 codec）。 */
  private def readEntries(p: os.Path): List[BootDispatcherWake.MarkerEntry] =
    jsonParse(os.read(p)).flatMap(_.as[BootDispatcherWake.MarkerFile]) match
      case Right(f) => f.entries
      case Left(e) => sys.error(s"marker parse failed: $e")

  // ── 口径 4：DarkWake 突刺——19s 缝 < slop ⇒ 不产生窗（取证实测形态回放）──────

  test("J1 DarkWake 突刺回放：34s 墙缝内 19s 清醒 ⇒ frozen 15s < 45s ⇒ 无窗") {
    val prev = WakeSensor.Sample(wallMs = 1_000_000L, nanoMs = 5_000_000_000L)
    val cur = WakeSensor.Sample(wallMs = prev.wallMs + 34_000L, nanoMs = prev.nanoMs + 19_000_000_000L)
    assertEquals(WakeSensor.judge(prev, cur, slop), None)
  }

  test("J2 DarkWake 真冻结变体：34s 墙缝全冻结 ⇒ frozen 34s < 45s ⇒ 仍无窗") {
    val prev = WakeSensor.Sample(1_000_000L, 5_000_000_000L)
    val cur = WakeSensor.Sample(prev.wallMs + 34_000L, prev.nanoMs) // nano 全冻
    assertEquals(WakeSensor.judge(prev, cur, slop), None)
  }

  // ── 口径 5：NTP 步进——Δwall≈Δnano ⇒ 不分类为睡眠 ────────────────────────────

  test("J3 NTP 步进 30s：Δwall=45s、Δnano=15s ⇒ frozen 30s < 45s ⇒ 无窗") {
    val prev = WakeSensor.Sample(1_000_000L, 5_000_000_000L)
    val cur = WakeSensor.Sample(prev.wallMs + 45_000L, prev.nanoMs + 15_000_000_000L)
    assertEquals(WakeSensor.judge(prev, cur, slop), None)
  }

  test("J4 边界严格性：frozen 恰 = slop ⇒ 无窗（严格大于才判）") {
    val prev = WakeSensor.Sample(1_000_000L, 5_000_000_000L)
    // wall +90s、nano +45s ⇒ frozen 恰 45s
    val cur = WakeSensor.Sample(prev.wallMs + 90_000L, prev.nanoMs + 45_000_000_000L)
    assertEquals(WakeSensor.judge(prev, cur, slop), None)
  }

  // ── 睡眠判定正例 + 窗界 ─────────────────────────────────────────────────────

  test("J5 睡眠签名：8min 墙缝 / 2s 单调 ⇒ 窗 [wakeAt−frozen, wakeAt]") {
    val prev = WakeSensor.Sample(1_000_000L, 5_000_000_000L)
    val cur = WakeSensor.Sample(prev.wallMs + 492_000L, prev.nanoMs + 2_000_000_000L)
    val got = WakeSensor.judge(prev, cur, slop)
    assert(got.isDefined, "8min 冻结必须判出睡眠窗")
    // 尾置窗：[cur.wallMs − frozenMs, cur.wallMs] = [1_002_000, 1_492_000]
    assertEquals(got.get.sleepAtMs, prev.wallMs + 2_000L)
    assertEquals(got.get.wakeAtMs, prev.wallMs + 492_000L)
    assertEquals(got.get.durationMs, 490_000L)
  }

  // ── 口径 3：台账形态（kind 标注 + blocking=false 恒 + 幂等不受影响）──────────

  test("E1 powerEntries：恰两条、kind=sleep/wake、blocking=false 恒、sleepAt/wakeAt 载荷正确") {
    val w = SleepWindow(1_000_000L, 1_490_000L)
    val es = WakeSensor.powerEntries(w, "boot-x", "general")
    assertEquals(es.size, 2)
    val sleep = es(0)
    val wake = es(1)
    assertEquals(sleep.kind, BootDispatcherWake.KindSleep)
    assertEquals(sleep.blocking, false)
    assertEquals(sleep.sleepAt, Some(1_000_000L))
    assertEquals(sleep.wakeAt, None)
    assertEquals(wake.kind, BootDispatcherWake.KindWake)
    assertEquals(wake.blocking, false)
    assertEquals(wake.sleepAt, Some(1_000_000L))
    assertEquals(wake.wakeAt, Some(1_490_000L))
    assertEquals(wake.bootId, "boot-x")
    assertEquals(wake.project, "general")
  }

  test("E2 append-only 共存：boot blocking 条目不被电源条目顶掉（duplicate-boot 幂等不受影响）") {
    val pd = pdOf("pw")
    val marker = BootDispatcherWake.markerPath(pd)
    val bootEntry = BootDispatcherWake.MarkerEntry(
      bootId = "boot-1",
      project = "pw",
      at = 1L,
      result = "woken",
      reason = "",
      blocking = true,
      nodes = 0,
      items = Nil
    )
    // 预置合法 v2 台账文件（含 boot blocking 条目）
    os.makeDir.all(marker / os.up)
    os.write.over(
      marker,
      BootDispatcherWake.MarkerFile(project = "pw", lastBootId = "boot-1", entries = List(bootEntry)).asJson.noSpaces
    )
    // 追加一对电源条目（WakeSensor 同款写点）
    val w = SleepWindow(1_000L, 2_000L)
    WakeSensor
      .powerEntries(w, "boot-1", "pw")
      .traverse_(e => BootDispatcherWake.appendPowerMarker(marker, "pw", e))
      .unsafeRunSync()
    val after = readEntries(marker)
    assertEquals(after.count(_.kind == BootDispatcherWake.KindBoot), 1, "boot 条目必须原样保留")
    assertEquals(after.count(_.kind == BootDispatcherWake.KindSleep), 1)
    assertEquals(after.count(_.kind == BootDispatcherWake.KindWake), 1)
    // duplicate-boot 幂等判据仍命中（口径 3 后半）
    val stillBlocking = await(BootDispatcherWake.hasBlockingEntry(marker, "boot-1", "pw"))
    assertEquals(stillBlocking, true)
  }

  // ── 停机成因标注三态（#8，D-5）───────────────────────────────────────────────

  test("E3 bootShutdownCause：无 marker 无 unclean ⇒ None；marker 在 ⇒ graceful；本 boot unclean ⇒ unclean") {
    val pd = pdOf("pc")
    // (a) fresh：None
    assertEquals(await(BootDispatcherWake.bootShutdownCause(pd, "boot-1")), None)
    // (b) marker 在 ⇒ graceful
    os.makeDir.all(BootDispatcherWake.shutdownMarkerPath / os.up)
    os.write.over(
      BootDispatcherWake.shutdownMarkerPath,
      BootDispatcherWake
        .ShutdownMarker(v = 1, kind = "graceful", at = 42L, bootId = "boot-0", cause = "SIGINT/SIGTERM")
        .asJson
        .noSpaces
    )
    assertEquals(await(BootDispatcherWake.bootShutdownCause(pd, "boot-1")), Some("graceful"))
    // (c) marker 不在 + 本 boot unclean 条目 ⇒ unclean
    os.remove.all(BootDispatcherWake.shutdownMarkerPath)
    val marker = BootDispatcherWake.markerPath(pd)
    os.makeDir.all(marker / os.up)
    os.write.over(
      marker,
      BootDispatcherWake
        .MarkerFile(
          project = "pc",
          lastBootId = "boot-1",
          entries = List(
            BootDispatcherWake.MarkerEntry(
              bootId = "boot-1",
              project = "pc",
              at = 9L,
              result = "noted",
              reason = "unclean",
              blocking = false,
              nodes = 3,
              items = Nil,
              kind = BootDispatcherWake.KindUnclean
            )
          )
        )
        .asJson
        .noSpaces
    )
    assertEquals(await(BootDispatcherWake.bootShutdownCause(pd, "boot-1")), Some("unclean"))
    // (d) unclean 条目是别的 boot 的 ⇒ None（不跨 boot 嫁接）
    assertEquals(await(BootDispatcherWake.bootShutdownCause(pd, "boot-9")), None)
  }

  test("E4 annotateShutdownCause：零残留零条目；有残留无 marker ⇒ unclean；有残留有 marker ⇒ graceful") {
    val pd = pdOf("pa")
    val marker = BootDispatcherWake.markerPath(pd)
    // (a) 零残留 ⇒ 零写
    await(ProjectCrashRecovery.annotateShutdownCause(pd, 0, 100L))
    assertEquals(os.exists(marker), false)
    // (b) 有残留无 marker ⇒ unclean
    await(ProjectCrashRecovery.annotateShutdownCause(pd, 2, 200L))
    val rows1 = readEntries(marker)
    assertEquals(rows1.count(_.kind == BootDispatcherWake.KindUnclean), 1)
    assertEquals(rows1.count(_.kind == BootDispatcherWake.KindGraceful), 0)
    assertEquals(rows1.head.nodes, 2)
    // (c) 有残留有 marker ⇒ graceful（append-only：unclean 行仍在）
    os.makeDir.all(BootDispatcherWake.shutdownMarkerPath / os.up)
    os.write.over(
      BootDispatcherWake.shutdownMarkerPath,
      BootDispatcherWake
        .ShutdownMarker(v = 1, kind = "graceful", at = 42L, bootId = "b0", cause = "SIGINT")
        .asJson
        .noSpaces
    )
    await(ProjectCrashRecovery.annotateShutdownCause(pd, 1, 300L))
    val rows2 = readEntries(marker)
    assertEquals(rows2.count(_.kind == BootDispatcherWake.KindUnclean), 1)
    assertEquals(rows2.count(_.kind == BootDispatcherWake.KindGraceful), 1)
  }

  // ── kill-switch launch 闸 ───────────────────────────────────────────────────

  test("K1 开关关闭 ⇒ launch 即 IO.unit（不挂纤维不写盘）") {
    sys.props.put("nebflow.wake.sense.enabled", "false")
    val before = os.walk(root).size
    assertEquals(Defaults.WakeSenseEnabled, false)
    await(WakeSensor.launch)
    assertEquals(os.walk(root).size, before, "关闭态零落盘")
  }
end WakeSensorSpec
