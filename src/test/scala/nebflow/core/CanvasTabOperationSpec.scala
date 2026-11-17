package nebflow.core

import io.circe.{Json, parser}
import munit.CatsEffectSuite

class CanvasTabOperationSpec extends CatsEffectSuite:
  private def j(s: String): Json = parser.parse(s).toOption.get
  private val now = 1794888000000L
  private def op(id: String, opened: String = "[]", closed: String = "[]", reopened: String = "[]", active: String = "null", time: Long = now): Json =
    j(s"""{"operationId":"$id","issuedAt":$time,"baseVersion":0,"opened":$opened,"closed":$closed,"reopened":$reopened,"activeTabId":$active}""")
  private val a = """[{"id":"a","title":"A","type":"generic"}]"""
  private val b = """[{"id":"b","title":"B","type":"generic"}]"""
  private def ids(x: Json): Vector[String] = x.hcursor.get[Vector[Json]]("tabs").toOption.get.map(_.hcursor.get[String]("id").toOption.get)
  private def fixture() = new CanvasTabStore(os.temp.dir() / "tabs.json")

  test("atomic operations preserve concurrent opens, update metadata and apply active in arrival order") {
    val s = fixture()
    for
      x <- s.applyOperation(op("1", a, active = "\"a\""), now)
      y <- s.applyOperation(op("2", b, active = "\"b\""), now)
      z <- s.applyOperation(op("3", """[{"id":"a","title":"changed","type":"generic"}]"""), now)
    yield
      assertEquals(ids(y.toOption.get._1), Vector("a", "b"))
      assertEquals(z.toOption.get._1.hcursor.downField("tabs").downN(0).get[String]("title").toOption, Some("changed"))
      assertEquals(z.toOption.get._1.hcursor.get[String]("activeTabId").toOption, Some("b"))
      assertEquals(x.toOption.get._1.hcursor.get[Long]("writeVersion").toOption, Some(1L))
  }

  test("duplicate id does not increment and expired operations are rejected") {
    val s = fixture()
    for
      x <- s.applyOperation(op("one", a), now)
      y <- s.applyOperation(op("one", b), now)
      z <- s.applyOperation(op("expired", b, time = now - 600001L), now)
    yield
      assertEquals(y.toOption.get._2, true)
      assertEquals(y.toOption.get._1.hcursor.get[Long]("writeVersion").toOption, Some(1L))
      assert(z.isLeft)
  }

  test("legacy stale snapshot must not erase another client's new tab") {
    val s = fixture()
    for
      _ <- s.mergeSave(j(s"""{"v":2,"tabs":$a}"""), now)
      result <- s.mergeSave(j(s"""{"v":2,"tabs":$b}"""), now)
    yield assertEquals(ids(result).toSet, Set("a", "b"))
  }

  test("close and explicit reopen use tombstone time semantics") {
    val s = fixture()
    for
      _ <- s.applyOperation(op("1", a), now)
      x <- s.applyOperation(op("2", closed = s"""[{"id":"a","closedAt":$now}]"""), now)
      y <- s.applyOperation(op("3", a, reopened = s"""[{"id":"a","reopenedAt":${now + 1}}]"""), now + 1)
    yield
      assertEquals(ids(x.toOption.get._1), Vector.empty)
      assertEquals(ids(y.toOption.get._1), Vector("a"))
  }
