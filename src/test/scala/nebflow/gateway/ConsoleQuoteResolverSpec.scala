package nebflow.gateway

import cats.effect.IO
import io.circe.Json
import io.circe.syntax.*
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
  test("structured snapshots retain short text, long query handles, missing markers and media metadata") {
    val root = os.temp.dir(prefix = "structured-quote-")
    val registry = new MsgTokenRegistry(root / "sessions", () => true)
    def ref(text: String, complete: Boolean) = Json.obj(
      "source" -> Json.obj("messageId" -> "row-1".asJson, "senderName" -> "author".asJson),
      "content" -> Json.obj("fullText" -> text.asJson, "fullTextComplete" -> complete.asJson,
        "attachments" -> Json.arr(Json.obj("type" -> "image".asJson, "name" -> "a.png".asJson, "path" -> "/tmp/a.png".asJson)),
        "itemIndex" -> 1.asJson, "itemTotal" -> 3.asJson)
    )
    val short = ("abc" * 800) + "\tEND"
    val long = ("long text " * 600) + "THE END"
    (for
      small <- ConsoleQuoteResolver.structured(ref(short, true), "receiving-session", registry)
      large <- ConsoleQuoteResolver.structured(ref(long, true), "receiving-session", registry)
      core = "msg-token/([0-9a-f]{8}-[0-9]{6})".r.findFirstMatchIn(large).get.group(1)
      lookup <- registry.lookup(core)
      missing <- ConsoleQuoteResolver.structured(ref("", false), "receiving-session", registry)
      legacy <- ConsoleQuoteResolver.structured(ref("possibly truncated", false), "receiving-session", registry)
    yield
      assert(small.contains(short), small)
      assert(small.contains("a.png") && small.contains("本机不可用") && small.contains("2/3"), small)
      assert(large.contains("已折叠") && !large.contains(long), large)
      assertEquals(lookup.map(_.text), Some(long))
      assert(missing.contains("引用未能解析"), missing)
      assert(legacy.contains("引用未能解析"), legacy)
    ).guarantee(IO.blocking(os.remove.all(root)))
  }
  test("quoted local image path resolves to an absolute readable file") {
    val root = os.temp.dir(prefix = "quote-image-")
    val image = root / "sample.png"
    os.write(image, "image-bytes")
    val registry = new MsgTokenRegistry(root / "sessions", () => true)
    val ref = Json.obj("source" -> Json.obj("messageId" -> "image".asJson),
      "content" -> Json.obj("fullText" -> "image".asJson, "fullTextComplete" -> true.asJson,
        "attachments" -> Json.arr(Json.obj("type" -> "image".asJson, "name" -> "sample.png".asJson, "path" -> image.toString.asJson))))
    ConsoleQuoteResolver.structured(ref, "session", registry).map { out =>
      assert(out.contains("本机可读"), out)
      assert(out.contains(image.toString), out)
    }.guarantee(IO.blocking(os.remove.all(root)))
  }
end ConsoleQuoteResolverSpec
