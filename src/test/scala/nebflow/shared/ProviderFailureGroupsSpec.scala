package nebflow.shared

class ProviderFailureGroupsSpec extends munit.FunSuite:
  import ProviderFailureGroups.*
  private def detail(reason: String, request: String) =
    s"All providers failed:\n [auth] p/model: Auth — $reason request_id=$request"

  test("same provider and reason counts distinct tasks, not attempts") {
    val a = detail("permission denied", "one")
    val groups = snapshot(List(Task("a", a + "\n" + a), Task("b", detail("permission denied", "two"))))
    assertEquals(groups.map(_.count), List(2))
    assertEquals(groups.head.taskIds, List("a", "b"))
  }
  test("different causes stay separate; recovered tasks disappear") {
    val tasks = List(Task("a", detail("permission denied", "one")), Task("b", detail("subscription expired", "two")))
    assertEquals(snapshot(tasks).size, 2)
    assertEquals(snapshot(tasks.tail).map(_.taskIds), List(List("b")))
    assertEquals(snapshot(Nil), Nil)
  }
