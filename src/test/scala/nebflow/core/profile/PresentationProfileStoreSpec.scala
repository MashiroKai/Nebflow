package nebflow.core.profile

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.CatsEffectSuite
import nebflow.shared.PathUtil

/**
 * 认知二期产品批①选档器：方案档案存储（作者裁定 §9-11：~/.stand 数据根独立
 * JSON）。钉：缺文件默认档 / 选档校验 / 逐项覆盖校验与清除 / 合成 / 持久化
 * roundtrip / 损坏文件回默认不炸面。
 */
class PresentationProfileStoreSpec extends CatsEffectSuite:

  private val tempRoot: os.Path = os.pwd / "target" / "test-profile-store"

  override def beforeEach(context: BeforeEach): Unit =
    if os.exists(tempRoot) then os.remove.all(tempRoot)
    os.makeDir.all(tempRoot)
    PathUtil.setDataRoot(tempRoot)

  test("缺文件 = 默认档（专家极简），零迁移") {
    val store = PresentationProfileStore(tempRoot)
    store.currentProfile.map { p =>
      assertEquals(p.key, "expert-minimal")
    }
  }

  test("选档：五档全受理，未知档给可行动报错") {
    val store = PresentationProfileStore(tempRoot)
    for
      r1 <- store.setCurrent("adhd-friendly")
      _ = assertEquals(r1.map(_.key), Right("adhd-friendly"))
      cur <- store.currentProfile
      _ = assertEquals(cur.key, "adhd-friendly")
      bad <- store.setCurrent("nope")
      _ = assert(bad.isLeft)
      _ <- store.currentProfile.map(p => assertEquals(p.key, "adhd-friendly"))
    yield ()
  }

  test("覆盖：先校验后入账；value=None 清除；合成只影响该档") {
    val store = PresentationProfileStore(tempRoot)
    for
      bad <- store.setOverride("adhd-friendly", "maxLines", Some("999"))
      _ = assert(bad.isLeft)
      bad2 <- store.setOverride("adhd-friendly", "teachingAllowed", Some("true"))
      _ = assert(bad2.isLeft)
      ok <- store.setOverride("adhd-friendly", "maxLines", Some("6"))
      _ = assert(ok.isRight)
      ok2 <- store.setOverride("adhd-friendly", "foldDefault", Some("strong"))
      _ = assert(ok2.isRight)
      resolved <- store.resolvedCurrent.map(_ => store.resolve(PresentationProfile.AdhdFriendly, Map.empty))
      _ = assertEquals(resolved.maxLines, 8) // 默认定义不受覆盖影响
      cur <- store.load
      adhdOv = cur.overrides.getOrElse("adhd-friendly", Map.empty)
      _ = assertEquals(adhdOv.get("maxLines"), Some("6"))
      // 切档到 adhd-friendly 后合成 = 覆盖值
      _ <- store.setCurrent("adhd-friendly")
      merged <- store.resolvedCurrent
      _ = assertEquals(merged.maxLines, 6)
      _ = assertEquals(merged.foldDefault, "strong")
      // 清除 maxLines 覆盖
      _ <- store.setOverride("adhd-friendly", "maxLines", None)
      merged2 <- store.resolvedCurrent
      _ = assertEquals(merged2.maxLines, 8)
      _ = assertEquals(merged2.foldDefault, "strong")
    yield ()
  }

  test("持久化 roundtrip：新实例读到同一档与覆盖") {
    val store = PresentationProfileStore(tempRoot)
    for
      _ <- store.setCurrent("deep-work")
      _ <- store.setOverride("deep-work", "keyNumbers", Some("2"))
      store2 = PresentationProfileStore(tempRoot)
      cur <- store2.currentProfile
      _ = assertEquals(cur.key, "deep-work")
      st <- store2.load
      _ = assertEquals(st.overrides.get("deep-work").flatMap(_.get("keyNumbers")), Some("2"))
    yield ()
  }

  test("损坏文件回默认档，不抛") {
    os.write.over(tempRoot / "presentation-profiles.json", "{not json")
    val store = PresentationProfileStore(tempRoot)
    store.currentProfile.map(p => assertEquals(p.key, "expert-minimal"))
  }

  test("snapshotJson：五档全量 + 当前键 + 覆盖块") {
    val store = PresentationProfileStore(tempRoot)
    for
      _ <- store.setOverride("exam-sprint", "maxLines", Some("7"))
      snap <- store.snapshotJson
      hc = snap.hcursor
      profiles <- IO.fromEither(hc.downField("profiles").as[List[io.circe.Json]])
      _ = assertEquals(profiles.length, 5)
      current <- IO.fromEither(hc.downField("current").as[String])
      _ = assertEquals(current, "expert-minimal")
      exam = profiles.find(p => p.hcursor.downField("key").as[String].toOption.contains("exam-sprint")).get
      ov <- IO.fromEither(exam.hcursor.downField("overrides").as[Map[String, String]])
      _ = assertEquals(ov.get("maxLines"), Some("7"))
    yield ()
  }

end PresentationProfileStoreSpec
