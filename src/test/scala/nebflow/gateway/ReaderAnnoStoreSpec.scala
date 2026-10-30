package nebflow.gateway

import munit.FunSuite

/** [[ReaderAnnoStore]] 纯核钉扎（reader P3 批，设计稿 20261030_181655 §4.2 存储面）。 */
class ReaderAnnoStoreSpec extends FunSuite:

  test("storePath lands under the project root, root-shape independent") {
    val cases = List(
      os.temp.dir(),
      os.Path(os.temp.dir().toString + "/"), // trailing slash
      os.temp.dir() / "sub dir"              // spaces
    )
    for root <- cases do
      val p = ReaderAnnoStore.storePath(root)
      assert(p.toString.startsWith(root.toString), s"root=$root p=$p")
      assertEquals(p.last, "reader-annotations.json")
      assert(p.toString.contains("/.nebflow/"), s"expected .nebflow segment: $p")
  }

  test("saveVerdict admits kb-scale stores and refuses beyond the cap") {
    assertEquals(ReaderAnnoStore.saveVerdict(0), Right(()))
    assertEquals(ReaderAnnoStore.saveVerdict(4096), Right(()))
    assertEquals(ReaderAnnoStore.saveVerdict(ReaderAnnoStore.MaxBytes), Right(()))
    val over = ReaderAnnoStore.saveVerdict(ReaderAnnoStore.MaxBytes + 1)
    assert(over.isLeft, "over-cap save must be refused")
    assert(over.left.toOption.get.contains("exceeds"))
  }

  test("frames are typed and never silent") {
    val okLoad = ReaderAnnoStore.loadedFrame("r1", Some("{\"v\":1}"))
    assertEquals(okLoad.hcursor.get[Boolean]("ok").toOption, Some(true))
    assertEquals(okLoad.hcursor.get[String]("reqId").toOption, Some("r1"))
    assertEquals(okLoad.hcursor.get[String]("content").toOption, Some("{\"v\":1}"))
    // 缺件 = 空库起步（ok:true + content:null），非错误帧。
    val empty = ReaderAnnoStore.loadedFrame("r2", None)
    assertEquals(empty.hcursor.get[Boolean]("ok").toOption, Some(true))
    assert(empty.hcursor.downField("content").focus.exists(_.isNull))

    val okSave = ReaderAnnoStore.savedFrame("r3")
    assertEquals(okSave.hcursor.get[Boolean]("ok").toOption, Some(true))

    val err = ReaderAnnoStore.errorFrame("r4", "boom")
    assertEquals(err.hcursor.get[Boolean]("ok").toOption, Some(false))
    assertEquals(err.hcursor.get[String]("error").toOption, Some("boom"))
  }

end ReaderAnnoStoreSpec
