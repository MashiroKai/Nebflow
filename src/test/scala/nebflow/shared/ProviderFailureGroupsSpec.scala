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

  // 族区分呈现（2026-11-21 作者裁定）：format ≠ 服务商不可用。
  test("family is parsed from the attempt label and drives presentation") {
    def line(family: String, reason: String) =
      s"All providers failed (1 attempt(s)):\n  [$family] p/model: $reason"
    val shape = snapshot(List(Task("a", line("format", "Format — bad shape (400)")))).head
    assertEquals(shape.family, "format")
    assertEquals(shape.presentation, "shape")

    val infra = snapshot(List(Task("b", line("server", "ServerError — 503")))).head
    assertEquals(infra.family, "server")
    assertEquals(infra.presentation, "availability")

    val config = snapshot(List(Task("c", line("auth", "Auth — 401")))).head
    assertEquals(config.presentation, "config")

    // 未知标签不臆测：归 unknown，沿用可用性文案。
    val odd = snapshot(List(Task("d", line("wire-ish", "weird")))).head
    assertEquals(odd.family, "unknown")
    assertEquals(odd.presentation, "availability")
  }

  // error-freeze 的 compact 详情只有摘要行（无 `[family] provider/model:` 明细）：
  // 族仍须从 `families: X` 摘要读出，否则面板一律 unknown、族区分文案失效。
  test("summary-only detail still yields the family from 'families:'") {
    val g = snapshot(List(Task("e", "All providers failed (2 attempt(s); families: format)"))).head
    assertEquals(g.family, "format")
    assertEquals(g.presentation, "shape")
    val infra = snapshot(List(Task("f", "all providers down: none recovered within 120000ms"))).head
    assertEquals(infra.family, "unknown")
    assertEquals(infra.presentation, "availability")
  }

end ProviderFailureGroupsSpec
