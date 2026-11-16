package nebflow.gateway

import cats.effect.IO
import munit.CatsEffectSuite
import nebflow.core.msgtokens.MsgTokenRegistry
import nebflow.core.scheduler.{ScheduledTask, ScheduledTaskStore}

class ConsoleQuoteResolverSpec extends CatsEffectSuite:
  test("author references expand short bodies, fold long bodies with queryable handles, and mark missing IDs") {
    val root = os.temp.dir(prefix = "console-quote-")
    val store = new ScheduledTaskStore(root / "tasks")
    val registry = new MsgTokenRegistry(root / "sessions", () => true)
    val body = "完整正文\n第二行\t原样"
    val long = "长文" * 2500
    val shortTodo = ScheduledTask.create("other", body, 1L, None, None, Some("标题")).copy(kind = Some("todo"), detail = Some(body))
    val longTodo = ScheduledTask.create("other", long, 1L, None, None, Some("长标题")).copy(kind = Some("todo"), detail = Some(long))
    (for
      _ <- store.addTask(shortTodo)
      _ <- store.addTask(longTodo)
      short <- ConsoleQuoteResolver.resolve(s"#标题·${shortTodo.id} 执行", store, registry)
      folded <- ConsoleQuoteResolver.resolve(s"#长标题·${longTodo.id} 执行", store, registry)
      recovered <- store.findTaskById(longTodo.id)
      missing <- ConsoleQuoteResolver.resolve("#不存在·deadbeef 执行", store, registry)
      card <- registry.mint(Some("source-session"), body)
      expanded <- ConsoleQuoteResolver.resolve(card.get.token, store, registry)
      longCard <- registry.mint(Some("source-session"), long)
      foldedCard <- ConsoleQuoteResolver.resolve(longCard.get.token, store, registry)
      lookedUp <- registry.lookup(longCard.get.token)
      badCard <- ConsoleQuoteResolver.resolve("{{msg:deadbeef-000001|missing}}", store, registry)
    yield
      assert(short.contains(body), short)
      assert(folded.contains("已折叠") && folded.contains(longTodo.id) && !folded.contains(long), folded)
      assertEquals(recovered.flatMap(_.detail), Some(long))
      assert(missing.contains("引用未能解析"), missing)
      assert(expanded.contains(body), expanded)
      assert(foldedCard.contains("已折叠") && foldedCard.contains("/api/sessions/msg-token/") && !foldedCard.contains(long), foldedCard)
      assertEquals(lookedUp.map(_.text), Some(long))
      assert(badCard.contains("引用未能解析"), badCard)
    ).guarantee(IO.blocking(os.remove.all(root)))
  }
end ConsoleQuoteResolverSpec
