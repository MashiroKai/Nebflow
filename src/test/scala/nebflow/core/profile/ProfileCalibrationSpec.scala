package nebflow.core.profile

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.CatsEffectSuite
import nebflow.shared.PathUtil

/**
 * 呈现校准数据面（呈现档入口改造+围栏语义批，作者令 2026-10-30 20:13 ②）。
 * 钉三件事：
 *   - ProfileCalibrationStore：缺文件空态 / 追加持久化 roundtrip / 环形缓冲
 *     超帽丢最旧 / 损坏文件回空不炸面；
 *   - classifyComplaint：§6.3 信号词表四类全命中 + 非抱怨 None（纯函数）；
 *   - ProfileCalibrator.suggest：calibrate 钩子只出建议不改档——阈值 ≥2、
 *     方向语义（maxLines 降/reminderCadence 降频/foldDefault 放宽/teaching
 *     关闭）、极限位 None、profile-switch/drawer-expanded 不入抱怨聚合。
 */
class ProfileCalibrationSpec extends CatsEffectSuite:

  private val tempRoot: os.Path = os.pwd / "target" / "test-profile-calibration"

  override def beforeEach(context: BeforeEach): Unit =
    if os.exists(tempRoot) then os.remove.all(tempRoot)
    os.makeDir.all(tempRoot)
    PathUtil.setDataRoot(tempRoot)

  private def sig(kind: String, data: Map[String, String]): ProfileCalibrationSignal.Signal =
    ProfileCalibrationSignal.Signal(ts = System.currentTimeMillis(), kind = kind, data = data)

  // ── Store ────────────────────────────────────────────────────────

  test("缺文件 = 空信号表，零迁移") {
    val store = ProfileCalibrationStore(tempRoot)
    store.all.map(s => assertEquals(s, List.empty))
  }

  test("追加持久化 roundtrip：新实例读到同一信号") {
    val store = ProfileCalibrationStore(tempRoot)
    for
      _ <- store.record(sig("profile-switch", Map("from" -> "expert-minimal", "to" -> "deep-work")))
      _ <- store.record(sig("drawer-expanded", Map.empty))
      store2 = ProfileCalibrationStore(tempRoot)
      all <- store2.all
      _ = assertEquals(all.length, 2)
      _ = assertEquals(all.head.kind, "profile-switch")
      _ = assertEquals(all.head.data.get("to"), Some("deep-work"))
      recent <- store2.recent(1)
      _ = assertEquals(recent.length, 1)
      _ = assertEquals(recent.head.kind, "drawer-expanded")
    yield ()
  }

  test("环形缓冲：超帽丢最旧，容量恒 ≤ KeepLast") {
    val store = ProfileCalibrationStore(tempRoot)
    val n = ProfileCalibrationStore.KeepLast + 20
    for
      _ <- (1 to n).toList.foldLeft(IO.unit)((acc, i) =>
        acc *> store.record(sig("drawer-expanded", Map("i" -> i.toString))).void)
      all <- store.all
      _ = assertEquals(all.length, ProfileCalibrationStore.KeepLast)
      // 最旧 20 条被丢：首条 = 第 21 条（i=21）。
      _ = assertEquals(all.head.data.get("i"), Some("21"))
    yield ()
  }

  test("损坏文件回空信号表，不抛") {
    os.write.over(tempRoot / "profile-calibration.json", "{not json")
    val store = ProfileCalibrationStore(tempRoot)
    store.all.map(s => assertEquals(s, List.empty))
  }

  // ── classifyComplaint（词表） ────────────────────────────────────

  test("词表四类全命中") {
    assertEquals(ProfileCalibrationSignal.classifyComplaint("下次简洁点"), Some("too-long"))
    assertEquals(ProfileCalibrationSignal.classifyComplaint("报告太长了"), Some("too-long"))
    assertEquals(ProfileCalibrationSignal.classifyComplaint("别烦我"), Some("too-frequent"))
    assertEquals(ProfileCalibrationSignal.classifyComplaint("给我看细节"), Some("need-detail"))
    assertEquals(ProfileCalibrationSignal.classifyComplaint("别教我"), Some("no-teaching"))
  }

  test("非抱怨文本 = None；空/Null 不炸") {
    assertEquals(ProfileCalibrationSignal.classifyComplaint("帮我看看这个报错"), None)
    assertEquals(ProfileCalibrationSignal.classifyComplaint("太长" + "的上下文你压缩一下"), Some("too-long"))
    assertEquals(ProfileCalibrationSignal.classifyComplaint(null), None)
  }

  // ── ProfileCalibrator.suggest（calibrate 钩子） ─────────────────

  test("钩子：同类 ≥2 才出建议；建议值方向正确且不改档") {
    val signals = List(
      sig("complaint", Map("rule" -> "too-long")),
      sig("complaint", Map("rule" -> "too-long")),
      sig("complaint", Map("rule" -> "too-long"))
    )
    val cur = PresentationProfile.ExpertMinimal // maxLines=3, reminderCadence=quiet
    val out = ProfileCalibrator.suggest(signals, cur)
    assertEquals(out.length, 1)
    assertEquals(out.head.param, "maxLines")
    assertEquals(out.head.value, "2")
    assertEquals(out.head.evidence, 3)
    // 纯函数：档本身不被改。
    assertEquals(cur.maxLines, 3)
  }

  test("钩子：单条噪音不出建议（阈值 ≥2）") {
    val signals = List(sig("complaint", Map("rule" -> "too-long")))
    assertEquals(ProfileCalibrator.suggest(signals, PresentationProfile.AdhdFriendly), Nil)
  }

  test("钩子：降频序 single→aggregate→quiet，quiet 已到极限 None") {
    val twice = List(
      sig("complaint", Map("rule" -> "too-frequent")),
      sig("complaint", Map("rule" -> "too-frequent"))
    )
    val adhd = ProfileCalibrator.suggest(twice, PresentationProfile.AdhdFriendly) // single
    assertEquals(adhd.map(_.value), List("aggregate"))
    val quiet = ProfileCalibrator.suggest(twice, PresentationProfile.DeepWork) // quiet 极限
    assertEquals(quiet, Nil)
  }

  test("钩子：引导层关闭建议只对备考冲刺档出；换档/抽屉信号不入抱怨聚合") {
    val twice = List(
      sig("complaint", Map("rule" -> "no-teaching")),
      sig("complaint", Map("rule" -> "no-teaching"))
    )
    assertEquals(ProfileCalibrator.suggest(twice, PresentationProfile.ExamSprint).map(_.value), List("false"))
    assertEquals(ProfileCalibrator.suggest(twice, PresentationProfile.ExpertMinimal), Nil)
    // 非抱怨信号再多也不出建议。
    val noise = List.fill(5)(sig("drawer-expanded", Map.empty)) ++
      List.fill(5)(sig("profile-switch", Map("from" -> "a", "to" -> "b")))
    assertEquals(ProfileCalibrator.suggest(noise, PresentationProfile.ExamSprint), Nil)
  }

end ProfileCalibrationSpec
