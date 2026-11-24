package nebflow.core.scheduler

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import io.circe.parser.parse
import io.circe.syntax.*
import munit.CatsEffectSuite

class TodoDuePersistenceSpec extends CatsEffectSuite:

  private val legacy = parse(
    """{"id":"old","sessionId":"s1","content":"legacy","triggerAt":123456,"createdAt":100,"kind":"todo"}"""
  ).toOption.get

  private val once = parse(
    """{"type":"once","dueAt":1735732800000,"zoneId":"America/New_York","wallClock":"2025-01-01T07:00","originalOffset":"-05:00"}"""
  ).toOption.get

  private val weekly = parse(
    """{"type":"weekly","dayOfWeek":3,"localTime":"07:00","zoneId":"America/New_York","wallClock":"2025-01-01T07:00"}"""
  ).toOption.get

  private def withDue(value: Json): Json = legacy.deepMerge(Json.obj("due" -> value))

  test("legacy record has no due setting and old encoded fields retain their values"):
    val task = legacy.as[ScheduledTask].toOption.get
    val encoded = task.asJson
    assert(encoded.hcursor.downField("due").focus.forall(_.isNull))
    assertEquals(encoded.hcursor.get[Long]("triggerAt").toOption, Some(123456L))
    assertEquals(encoded.as[ScheduledTask].toOption, Some(task))

  test("legacy encoded golden bytes are identical after decoding and encoding"):
    val original = legacy.as[ScheduledTask].toOption.get.asJson.mapObject(_.remove("due")).noSpaces
    assertEquals(parse(original).toOption.get.as[ScheduledTask].toOption.get.asJson.noSpaces, original)

  test("store rejects directly constructed invalid reminder rules"):
    val base = legacy.as[ScheduledTask].toOption.get
    val rules = List(
      TodoDue.Weekly(3, "07:00", "Not/AZone", "2025-01-01T07:00"),
      TodoDue.Weekly(3, "25:60", "America/New_York", "2025-01-01T07:00"),
      TodoDue.Weekly(0, "07:00", "America/New_York", "2025-01-01T07:00"),
      TodoDue.Once(1L, "America/New_York", "", "-05:00")
    )
    rules.traverse_ { rule =>
      val dir = os.temp.dir(prefix = "todo-due-invalid-")
      new ScheduledTaskStore(dir).addTask(base.copy(due = Some(rule))).attempt.map { result =>
        assert(result.isLeft, s"invalid rule accepted: $rule")
        assert(result.swap.toOption.get.getMessage.nonEmpty)
      }
    }

  test("one-shot and weekly golden payloads survive store and fresh reader"):
    val dir = os.temp.dir(prefix = "todo-due-fixture-")
    val store = new ScheduledTaskStore(dir)
    val golden = List(withDue(once), withDue(weekly).deepMerge(Json.obj("id" -> "weekly".asJson)))
    for
      tasks <- IO.fromEither(golden.traverse(_.as[ScheduledTask]))
      _ <- store.saveTasks("s1", tasks)
      loaded <- new ScheduledTaskStore(dir).loadTasks("s1")
    yield assertEquals(loaded.map(_.asJson.hcursor.downField("due").focus), List(Some(once), Some(weekly)))

  test("invalid or incomplete due rules are rejected with readable causes"):
    val cases = List(
      weekly.deepMerge(Json.obj("zoneId" -> "Not/AZone".asJson)) -> "zone",
      weekly.deepMerge(Json.obj("localTime" -> "25:60".asJson)) -> "time",
      weekly.mapObject(_.remove("dayOfWeek")) -> "dayOfWeek"
    )
    cases.foreach { (invalid, reason) =>
      val result = withDue(invalid).as[ScheduledTask]
      assert(result.isLeft, s"accepted invalid rule: $invalid")
      assert(result.swap.toOption.get.getMessage.toLowerCase.contains(reason.toLowerCase), result.toString)
    }
end TodoDuePersistenceSpec
