package nebflow.core.scheduler

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import io.circe.parser.parse
import io.circe.syntax.*
import munit.CatsEffectSuite
import cats.effect.unsafe.implicits.global
import java.nio.file.Paths

object TodoOccurrenceCrashChild:
  def main(args: Array[String]): Unit =
    val store = new ScheduledTaskStore(os.Path(args(0)))
    val occurrence = TodoOccurrence.create("old", 1000L, 7L)
    val halt = () => Runtime.getRuntime.halt(71)
    if args(1) == "race" then
      val recorded = store.recordOccurrence("s1", occurrence, Some(2000L)).unsafeRunSync()
      sys.exit(if recorded then 0 else 3)
    val before = if args(1) == "before" then halt else () => ()
    val after = if args(1) == "after" then halt else () => ()
    store.recordOccurrence("s1", occurrence, Some(2000L), before, after).unsafeRunSync()
    sys.exit(72)


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
    val expected =
      """{"id":"old","sessionId":"s1","content":"legacy","triggerAt":123456,"createdAt":100,"triggered":false,"triggeredAt":null,"referencePath":null,"repeat":null,"enabled":true,"name":null,"detail":null,"kind":"todo","condition":null,"conditionState":null,"idleStreak":0,"reviewFlag":false,"lastFiredAt":null,"missCount":0,"project":null,"priority":0,"completedAt":null,"source":null,"revision":0,"decision":null}"""
    val output = legacy.as[ScheduledTask].toOption.get.asJson.noSpaces
    assertEquals(output, expected)
    assert(!output.contains("\"due\""))

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

  test("occurrence identities survive snooze and differ only by scheduled instant"):
    val first = TodoOccurrence.create("todo-1", 1735732800000L, 1L)
    val retry = TodoOccurrence.create("todo-1", 1735732800000L, 1L)
    val next = TodoOccurrence.create("todo-1", 1736337600000L, 1L)
    val snoozed = first.copy(nextReminderAt = Some(1736340000000L))
    assertEquals(first.key, retry.key)
    assertNotEquals(first.key, next.key)
    assertEquals(snoozed.key, first.key)
    assertEquals(snoozed.scheduledAt, first.scheduledAt)
    assertEquals(snoozed.nextReminderAt, Some(1736340000000L))
    assertEquals(snoozed.asJson.as[TodoOccurrence].toOption, Some(snoozed))

  test("occurrence and next due commit atomically across crash and retry"):
    val dir = os.temp.dir(prefix = "occurrence-atomic-")
    val store = new ScheduledTaskStore(dir)
    val initial = legacy.as[ScheduledTask].toOption.get.copy(nextDueAt = Some(1000L))
    val occurrence = TodoOccurrence.create(initial.id, 1000L, 7L).copy(nextReminderAt = Some(1200L))
    for
      _ <- store.addTask(initial)
      before <- store
        .recordOccurrence("s1", occurrence, Some(2000L), () => throw new RuntimeException("before commit"))
        .attempt
      unchanged <- new ScheduledTaskStore(dir).loadTasks("s1")
      after <- store
        .recordOccurrence("s1", occurrence, Some(2000L), () => (), () => throw new RuntimeException("after commit"))
        .attempt
      retry <- new ScheduledTaskStore(dir).recordOccurrence("s1", occurrence, Some(2000L))
      rows <- new ScheduledTaskStore(dir).loadTasks("s1")
    yield
      assert(before.isLeft)
      assertEquals(unchanged.head.nextDueAt, Some(1000L))
      assertEquals(unchanged.head.occurrences, Nil)
      assert(after.isLeft)
      assertEquals(rows.head.nextDueAt, Some(2000L))
      assertEquals(rows.head.occurrences, List(occurrence))
      assertEquals(retry, false)

  private def checkHardCrash(point: String): IO[Unit] =
    {
      val dir = os.temp.dir(prefix = "occurrence-halt-")
      val initial = legacy.as[ScheduledTask].toOption.get.copy(nextDueAt = Some(1000L))
      val occurrence = TodoOccurrence.create("old", 1000L, 7L)
      val cp = List.newBuilder[String]
      var loader = Thread.currentThread().getContextClassLoader
      while loader != null do
        loader match
          case urls: java.net.URLClassLoader =>
            cp ++= urls.getURLs.toList.filter(_.getProtocol == "file").map(u => Paths.get(u.toURI).toString)
          case _ => ()
        loader = loader.getParent
      cp += System.getProperty("java.class.path")
      for
        _ <- new ScheduledTaskStore(dir).addTask(initial)
        status <- IO.blocking {
          val process = new ProcessBuilder(Paths.get(System.getProperty("java.home"), "bin", "java").toString,
            "-cp", cp.result().distinct.mkString(java.io.File.pathSeparator),
            "nebflow.core.scheduler.TodoOccurrenceCrashChild", dir.toString, point)
            .redirectErrorStream(true).redirectOutput((dir / "child.log").toIO).start()
          assert(process.waitFor(90, java.util.concurrent.TimeUnit.SECONDS), "child did not exit")
          process.exitValue()
        }
        rows <- new ScheduledTaskStore(dir).loadTasks("s1")
        _ <- IO {
          assertEquals(status, 71, os.read(dir / "child.log"))
          assertEquals(rows.head.occurrences, if point == "before" then Nil else List(occurrence))
          assertEquals(rows.head.nextDueAt, Some(if point == "before" then 1000L else 2000L))
        }
        result <- new ScheduledTaskStore(dir).recordOccurrence("s1", occurrence, Some(2000L))
        finalRows <- new ScheduledTaskStore(dir).loadTasks("s1")
      yield
        assertEquals(result, point == "before")
        assertEquals(finalRows.head.occurrences, List(occurrence))
        assertEquals(finalRows.head.nextDueAt, Some(2000L))
    }

  test("hard halt before atomic replacement preserves unadvanced series"):
    checkHardCrash("before")

  test("hard halt after atomic replacement preserves advanced series"):
    checkHardCrash("after")

  test("two store instances concurrently record one occurrence once"):
    val dir = os.temp.dir(prefix = "occurrence-race-")
    val first = new ScheduledTaskStore(dir)
    val second = new ScheduledTaskStore(dir)
    val initial = legacy.as[ScheduledTask].toOption.get.copy(nextDueAt = Some(1000L))
    val occurrence = TodoOccurrence.create("old", 1000L, 7L)
    for
      _ <- first.addTask(initial)
      outcomes <- (first.recordOccurrence("s1", occurrence, Some(2000L)),
        second.recordOccurrence("s1", occurrence, Some(2000L))).parTupled
      rows <- new ScheduledTaskStore(dir).loadTasks("s1")
    yield
      assertEquals(List(outcomes._1, outcomes._2).count(identity), 1)
      assertEquals(rows.head.occurrences, List(occurrence))
      assertEquals(rows.head.nextDueAt, Some(2000L))

  test("cancelled lock waiter does not enter or retain the mutex"):
    val dir = os.temp.dir(prefix = "occurrence-cancel-waiter-")
    val store = new ScheduledTaskStore(dir)
    for
      entered <- cats.effect.Deferred[IO, Unit]
      release <- cats.effect.Deferred[IO, Unit]
      attempted <- cats.effect.Deferred[IO, Unit]
      ran <- cats.effect.Ref.of[IO, Boolean](false)
      holder <- store.withStoreLock(entered.complete(()) *> release.get).start
      _ <- entered.get
      waiter <- (attempted.complete(()) *> new ScheduledTaskStore(dir).withStoreLock(ran.set(true))).start
      _ <- attempted.get
      _ <- waiter.cancel
      _ <- release.complete(())
      _ <- holder.joinWithNever
      _ <- new ScheduledTaskStore(dir).withStoreLock(IO.unit).timeout(scala.concurrent.duration.DurationInt(5).seconds)
      didRun <- ran.get
    yield assertEquals(didRun, false)

  test("cancelled lock holder releases locks and leaves file intact"):
    val dir = os.temp.dir(prefix = "occurrence-cancel-holder-")
    val store = new ScheduledTaskStore(dir)
    val initial = legacy.as[ScheduledTask].toOption.get.copy(nextDueAt = Some(1000L))
    for
      _ <- store.addTask(initial)
      entered <- cats.effect.Deferred[IO, Unit]
      holder <- store.withStoreLock(entered.complete(()) *> IO.never[Unit]).start
      _ <- entered.get
      _ <- holder.cancel
      rows <- new ScheduledTaskStore(dir).loadTasks("s1").timeout(scala.concurrent.duration.DurationInt(5).seconds)
    yield assertEquals(rows, List(initial))

  test("two independent JVMs serialize occurrence writes"):
    val dir = os.temp.dir(prefix = "occurrence-process-race-")
    val initial = legacy.as[ScheduledTask].toOption.get.copy(nextDueAt = Some(1000L))
    val cp = List.newBuilder[String]
    var loader = Thread.currentThread().getContextClassLoader
    while loader != null do
      loader match
        case urls: java.net.URLClassLoader =>
          cp ++= urls.getURLs.toList.filter(_.getProtocol == "file").map(u => Paths.get(u.toURI).toString)
        case _ => ()
      loader = loader.getParent
    cp += System.getProperty("java.class.path")
    val classpath = cp.result().distinct.mkString(java.io.File.pathSeparator)
    def child(n: Int): Process =
      new ProcessBuilder(Paths.get(System.getProperty("java.home"), "bin", "java").toString,
        "-cp", classpath, "nebflow.core.scheduler.TodoOccurrenceCrashChild", dir.toString, "race")
        .redirectErrorStream(true).redirectOutput((dir / s"child-$n.log").toIO).start()
    for
      _ <- new ScheduledTaskStore(dir).addTask(initial)
      statuses <- IO.blocking {
        val a = child(1)
        val b = child(2)
        assert(a.waitFor(90, java.util.concurrent.TimeUnit.SECONDS))
        assert(b.waitFor(90, java.util.concurrent.TimeUnit.SECONDS))
        List(a.exitValue(), b.exitValue()).sorted
      }
      rows <- new ScheduledTaskStore(dir).loadTasks("s1")
    yield
      assertEquals(statuses, List(0, 3))
      assertEquals(rows.head.occurrences, List(TodoOccurrence.create("old", 1000L, 7L)))
      assertEquals(rows.head.nextDueAt, Some(2000L))

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
