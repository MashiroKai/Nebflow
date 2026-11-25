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
    if args(1) == "probe" then
      val channel = java.nio.channels.FileChannel.open(
        os.Path(args(0)).toNIO.resolve(".scheduled-tasks.lock"),
        java.nio.file.StandardOpenOption.CREATE,
        java.nio.file.StandardOpenOption.WRITE
      )
      val lock = channel.tryLock()
      if lock == null then sys.exit(4)
      lock.release()
      channel.close()
      sys.exit(0)
    if args(1) == "hold" then
      store
        .withStoreLock(IO.blocking {
          System.out.println("LOCKED")
          System.out.flush()
          System.in.read()
        })
        .unsafeRunSync()
      sys.exit(0)
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

  test("claiming a due occurrence twice settles it only once"):
    val dir = os.temp.dir(prefix = "occurrence-claim-")
    val store = new ScheduledTaskStore(dir)
    val occurrence = TodoOccurrence.create("old", 1000L, 0L)
    val initial = legacy.as[ScheduledTask].toOption.get.copy(occurrences = List(occurrence))
    for
      _ <- store.addTask(initial)
      first <- store.claimOccurrence("old", occurrence.key, 1000L)
      second <- store.claimOccurrence("old", occurrence.key, 1000L)
    yield
      assert(first, "first due claim must win")
      assert(!second, "the same occurrence must not settle twice")

  test("device time zone accepts IANA regions and requests clarification without a source"):
    assertEquals(TodoDue.deviceZone(Some("Asia/Tokyo")), Some("Asia/Tokyo"))
    List(None, Some(""), Some("Not/AZone"), Some("+09:00")).foreach { value =>
      assertEquals(TodoDue.deviceZone(value), None)
      assert(TodoDue.requireCreationZone(None, value).isLeft)
    }
    assertEquals(TodoDue.requireCreationZone(None, Some("Asia/Tokyo")), Right("Asia/Tokyo"))

  test("gateway device zone comes only from a valid frame declaration"):
    def frame(s: String) = parse(s).toOption.get
    assertEquals(TodoDue.deviceZoneFromFrame(frame("""{"device":{"timeZone":"Asia/Tokyo"}}""")), Some("Asia/Tokyo"))
    assertEquals(TodoDue.deviceZoneFromFrame(frame("""{"timeZone":"Europe/Paris"}""")), Some("Europe/Paris"))
    List("{}", """{"timeZone":""}""", """{"device":{"timeZone":"Invalid/Zone"}}""").foreach { s =>
      assertEquals(TodoDue.deviceZoneFromFrame(frame(s)), None)
    }

  test("merged input time zone is present only when all sources agree"):
    val tokyo = Some("Asia/Tokyo")
    val paris = Some("Europe/Paris")
    assertEquals(TodoDue.mergedDeviceZone(List(tokyo, tokyo)), tokyo)
    assertEquals(TodoDue.mergedDeviceZone(List(tokyo, paris)), None)
    assertEquals(TodoDue.mergedDeviceZone(List(tokyo, None)), None)
    assertEquals(TodoDue.mergedDeviceZone(List(None, None)), None)
    assertEquals(TodoDue.mergedDeviceZone(Nil), None)

  test("once offset compatibility preserves legacy strings and allows absent offsets while requiring a region"):
    val decoded = once.as[TodoDue].toOption.get
    assertEquals(decoded.asJson.hcursor.get[String]("originalOffset").toOption, Some("-05:00"))
    val noOffset = once.mapObject(_.remove("originalOffset"))
    assert(noOffset.as[TodoDue].isRight, "missing originalOffset must decode")
    assertEquals(noOffset.as[TodoDue].toOption.get.asJson.hcursor.get[Option[String]]("originalOffset").toOption, Some(None))
    assert(once.deepMerge(Json.obj("originalOffset" -> Json.Null)).as[TodoDue].isRight)
    assert(once.deepMerge(Json.obj("originalOffset" -> Json.fromString("invalid"))).as[TodoDue].isLeft)
    assert(once.mapObject(_.remove("zoneId")).as[TodoDue].isLeft)

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
      TodoDue.Once(1L, "America/New_York", "", Some("-05:00"))
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
        val process = new ProcessBuilder(
          Paths.get(System.getProperty("java.home"), "bin", "java").toString,
          "-cp",
          cp.result().distinct.mkString(java.io.File.pathSeparator),
          "nebflow.core.scheduler.TodoOccurrenceCrashChild",
          dir.toString,
          point
        )
          .redirectErrorStream(true)
          .redirectOutput((dir / "child.log").toIO)
          .start()
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

  test("hard halt before atomic replacement preserves unadvanced series"):
    checkHardCrash("before")

  test("hard halt after atomic replacement preserves advanced series"):
    checkHardCrash("after")

  test("occurrence reconciles against its bound historic rule version"):
    val first = TodoDue.Weekly(1, "09:00", "Europe/London", "2024-01-01T09:00")
    val changed = TodoDue.Weekly(2, "09:00", "Europe/London", "2024-01-02T09:00")
    val task = legacy
      .as[ScheduledTask]
      .toOption
      .get
      .copy(due = Some(changed), dueRuleVersion = 1L, dueRuleHistory = List(TodoRuleSnapshot(0L, first)))
    assertEquals(task.ruleForOccurrence(TodoOccurrence.create("old", 1000L, 0L)), Some(first))
    assertEquals(task.ruleForOccurrence(TodoOccurrence.create("old", 2000L, 1L)), Some(changed))
    assertEquals(task.ruleForOccurrence(TodoOccurrence.create("old", 3000L, 2L)), None)

  test("rearm rule change preserves every field of generated occurrences"):
    val dir = os.temp.dir(prefix = "rearm-occurrences-")
    val store = new ScheduledTaskStore(dir)
    val first = TodoDue.Weekly(1, "09:00", "Europe/London", "2024-01-01T09:00")
    val changed = TodoDue.Weekly(2, "09:00", "Europe/London", "2024-01-02T09:00")
    val occurrence = TodoOccurrence.create("old", 1000L, 0L).copy(nextReminderAt = Some(1200L))
    val initial = legacy.as[ScheduledTask].toOption.get.copy(
      name = Some("series"), source = Some("engine"), due = Some(first), occurrences = List(occurrence)
    )
    for
      _ <- store.addTask(initial)
      rearm <- store.upsertTaskByName(initial.copy(sessionId = "s2", due = Some(changed), occurrences = Nil))
    yield
      assertEquals(rearm.saved.occurrences, List(occurrence))
      assertEquals(rearm.saved.ruleForOccurrence(occurrence), Some(first))

  test("persisted occurrence keeps its bound rule across an update"):
    val dir = os.temp.dir(prefix = "due-bound-persisted-")
    val store = new ScheduledTaskStore(dir)
    val first = TodoDue.Weekly(1, "09:00", "Europe/London", "2024-01-01T09:00")
    val changed = TodoDue.Weekly(2, "09:00", "Europe/London", "2024-01-02T09:00")
    val occurrence = TodoOccurrence.create("old", 1000L, 0L).copy(nextReminderAt = Some(1200L))
    val initial = legacy.as[ScheduledTask].toOption.get.copy(due = Some(first), occurrences = List(occurrence))
    for
      _ <- store.addTask(initial)
      _ <- store.updateTask("s1", initial.id)(_.copy(due = Some(changed)))
      rows <- store.loadTasks("s1")
    yield
      assertEquals(rows.head.occurrences, List(occurrence))
      assertEquals(rows.head.ruleForOccurrence(rows.head.occurrences.head), Some(first))

  test("rearm rejects equal-revision conflicting copies instead of creating a third row"):
    val dir = os.temp.dir(prefix = "rearm-conflict-")
    val store = new ScheduledTaskStore(dir)
    val initial = legacy.as[ScheduledTask].toOption.get.copy(name = Some("series"), source = Some("engine"))
    val conflict = initial.copy(sessionId = "s2", content = "conflict")
    for
      _ <- store.saveTasks("s1", List(initial))
      _ <- IO(os.write(dir / "s2.json", List(conflict).asJson.noSpaces))
      result <- store.upsertTaskByName(initial.copy(sessionId = "s3")).attempt
      exists <- IO(os.exists(dir / "s3.json"))
    yield
      assert(result.isLeft)
      assertEquals(exists, false)

  test("rearm rejects conflicting replicas even when another same-name id is valid"):
    val dir = os.temp.dir(prefix = "rearm-mixed-conflict-")
    val store = new ScheduledTaskStore(dir)
    val initial = legacy.as[ScheduledTask].toOption.get.copy(name = Some("series"), source = Some("engine"))
    val conflict = initial.copy(sessionId = "s2", content = "conflict")
    val other = initial.copy(id = "other", sessionId = "s3")
    for
      _ <- store.addTask(initial)
      _ <- IO(os.write(dir / "s2.json", List(conflict).asJson.noSpaces))
      _ <- store.addTask(other)
      result <- store.upsertTaskByName(initial.copy(sessionId = "s4")).attempt
      exists <- IO(os.exists(dir / "s4.json"))
    yield
      assert(result.isLeft)
      assertEquals(exists, false)

  test("named creation refuses an existing id under another name"):
    val dir = os.temp.dir(prefix = "rearm-id-collision-")
    val store = new ScheduledTaskStore(dir)
    val original = legacy.as[ScheduledTask].toOption.get.copy(name = Some("first"), source = Some("engine"))
    for
      _ <- store.addTask(original)
      result <- store.upsertTaskByName(original.copy(sessionId = "s2", name = Some("second"))).attempt
      exists <- IO(os.exists(dir / "s2.json"))
    yield
      assert(result.isLeft)
      assertEquals(exists, false)

  test("rearm refuses overwriting an unrelated destination row sharing the id"):
    val dir = os.temp.dir(prefix = "rearm-target-id-")
    val store = new ScheduledTaskStore(dir)
    val original = legacy.as[ScheduledTask].toOption.get.copy(name = Some("first"), source = Some("engine"))
    val unrelated = original.copy(sessionId = "s2", name = Some("second"), content = "unrelated", revision = 1L)
    for
      _ <- store.addTask(original)
      _ <- IO(os.write(dir / "s2.json", List(unrelated).asJson.noSpaces))
      result <- store.upsertTaskByName(original.copy(sessionId = "s2")).attempt
      rows <- IO(io.circe.parser.decode[List[ScheduledTask]](os.read(dir / "s2.json")).toOption.get)
    yield
      assert(result.isLeft)
      assertEquals(rows, List(unrelated))

  test("cross-session rearm retains and advances reminder rule history"):
    val dir = os.temp.dir(prefix = "due-rearm-history-")
    val store = new ScheduledTaskStore(dir)
    val first = TodoDue.Weekly(1, "09:00", "Europe/London", "2024-01-01T09:00")
    val changed = TodoDue.Weekly(2, "09:00", "Europe/London", "2024-01-02T09:00")
    val initial =
      legacy.as[ScheduledTask].toOption.get.copy(name = Some("series"), source = Some("engine"), due = Some(first))
    for
      _ <- store.addTask(initial)
      rearm <- store.upsertTaskByName(initial.copy(sessionId = "s2", due = Some(changed)))
      rows <- store.loadTasks("s2")
    yield
      assertEquals(rearm.saved.dueRuleVersion, 1L)
      assertEquals(rows.head.dueRuleHistory.map(x => (x.version, x.rule)), List((0L, first)))

  test("changing a reminder rule retains its prior version snapshot"):
    val dir = os.temp.dir(prefix = "due-rule-history-")
    val store = new ScheduledTaskStore(dir)
    val first = TodoDue.Weekly(1, "09:00", "Europe/London", "2024-01-01T09:00")
    val changed = TodoDue.Weekly(2, "09:00", "Europe/London", "2024-01-02T09:00")
    val initial = legacy.as[ScheduledTask].toOption.get.copy(due = Some(first))
    for
      _ <- store.addTask(initial)
      _ <- store.updateTask("s1", initial.id)(_.copy(due = Some(changed)))
      rows <- store.loadTasks("s1")
    yield assertEquals(rows.head.dueRuleHistory.map(x => (x.version, x.rule)), List((0L, first)))

  test("update rule cannot rewrite a generated occurrence"):
    val dir = os.temp.dir(prefix = "due-update-occurrence-")
    val store = new ScheduledTaskStore(dir)
    val first = TodoDue.Weekly(1, "09:00", "Europe/London", "2024-01-01T09:00")
    val changed = TodoDue.Weekly(2, "09:00", "Europe/London", "2024-01-02T09:00")
    val occurrence = TodoOccurrence.create("old", 1000L, 0L).copy(nextReminderAt = Some(1200L))
    val initial = legacy.as[ScheduledTask].toOption.get.copy(due = Some(first), occurrences = List(occurrence))
    for
      _ <- store.addTask(initial)
      result <- store.updateTask("s1", initial.id)(_.copy(due = Some(changed), occurrences = Nil)).attempt
      rows <- store.loadTasks("s1")
    yield
      assert(result.isLeft)
      assertEquals(rows.head.occurrences, List(occurrence))

  test("direct save rejects duplicate task IDs in the same payload"):
    val dir = os.temp.dir(prefix = "due-save-duplicate-")
    val store = new ScheduledTaskStore(dir)
    val row = legacy.as[ScheduledTask].toOption.get
    for
      result <- store.saveTasks("s1", List(row, row.copy(content = "conflict"))).attempt
      exists <- IO(os.exists(dir / "s1.json"))
    yield
      assert(result.isLeft)
      assertEquals(exists, false)

  test("direct save rejects removing a generated occurrence during rule change"):
    val dir = os.temp.dir(prefix = "due-save-occurrence-")
    val store = new ScheduledTaskStore(dir)
    val first = TodoDue.Weekly(1, "09:00", "Europe/London", "2024-01-01T09:00")
    val changed = TodoDue.Weekly(2, "09:00", "Europe/London", "2024-01-02T09:00")
    val occurrence = TodoOccurrence.create("old", 1000L, 0L).copy(nextReminderAt = Some(1200L))
    val initial = legacy.as[ScheduledTask].toOption.get.copy(due = Some(first), occurrences = List(occurrence))
    for
      _ <- store.addTask(initial)
      result <- store.saveTasks("s1", List(initial.copy(due = Some(changed), occurrences = Nil))).attempt
      rows <- store.loadTasks("s1")
    yield
      assert(result.isLeft)
      assertEquals(rows.head.occurrences, List(occurrence))

  test("direct save rejects older cross-session copy even when local revision matches"):
    val dir = os.temp.dir(prefix = "due-save-authority-")
    val store = new ScheduledTaskStore(dir)
    val initial = legacy.as[ScheduledTask].toOption.get
    val latest = initial.copy(sessionId = "s2", revision = 1L, content = "newer")
    for
      _ <- store.addTask(initial)
      _ <- IO(os.write(dir / "s2.json", List(latest).asJson.noSpaces))
      result <- store.saveTasks("s1", List(initial.copy(content = "stale"))).attempt
      rows <- IO(io.circe.parser.decode[List[ScheduledTask]](os.read(dir / "s1.json")).toOption.get)
    yield
      assert(result.isLeft)
      assertEquals(rows, List(initial))

  test("direct save cannot delete a row without an explicit baseline"):
    val dir = os.temp.dir(prefix = "due-save-delete-")
    val store = new ScheduledTaskStore(dir)
    val row = legacy.as[ScheduledTask].toOption.get
    for
      _ <- store.addTask(row)
      result <- store.saveTasks("s1", Nil).attempt
      rows <- store.loadTasks("s1")
    yield
      assert(result.isLeft)
      assertEquals(rows, List(row))

  test("direct save rejects an equal-revision conflicting cross-session replica"):
    val dir = os.temp.dir(prefix = "due-save-conflict-")
    val store = new ScheduledTaskStore(dir)
    val original = legacy.as[ScheduledTask].toOption.get
    val conflict = original.copy(sessionId = "s2", content = "conflict")
    for
      _ <- store.addTask(original)
      result <- store.saveTasks("s2", List(conflict)).attempt
      exists <- IO(os.exists(dir / "s2.json"))
    yield
      assert(result.isLeft)
      assertEquals(exists, false)

  test("direct save accepts a newer cross-session revision"):
    val dir = os.temp.dir(prefix = "due-save-newer-")
    val store = new ScheduledTaskStore(dir)
    val old = legacy.as[ScheduledTask].toOption.get
    val newer = old.copy(sessionId = "s2", revision = 1L, content = "newer")
    for
      _ <- store.addTask(old)
      _ <- store.saveTasks("s2", List(newer))
      rows <- store.loadTasks("s2")
    yield assertEquals(rows, List(newer))

  test("add task refuses duplicate identity in another session"):
    val dir = os.temp.dir(prefix = "due-add-identity-")
    val store = new ScheduledTaskStore(dir)
    val original = legacy.as[ScheduledTask].toOption.get
    for
      _ <- store.addTask(original)
      result <- store.addTask(original.copy(sessionId = "s2", content = "conflict")).attempt
      exists <- IO(os.exists(dir / "s2.json"))
    yield
      assert(result.isLeft)
      assertEquals(exists, false)

  test("user todo retry refuses conflicting identity in another session"):
    val dir = os.temp.dir(prefix = "due-user-identity-")
    val store = new ScheduledTaskStore(dir)
    val original = legacy.as[ScheduledTask].toOption.get.copy(source = Some("user"))
    for
      _ <- store.addTask(original)
      result <- store.addUserTodoOnce(original.copy(sessionId = "s2", content = "conflict")).attempt
      exists <- IO(os.exists(dir / "s2.json"))
    yield
      assert(result.isLeft)
      assertEquals(exists, false)

  test("user todo retry refuses a stale local identity replica"):
    val dir = os.temp.dir(prefix = "due-user-stale-")
    val store = new ScheduledTaskStore(dir)
    val old = legacy.as[ScheduledTask].toOption.get.copy(source = Some("user"))
    val newer = old.copy(sessionId = "s2", revision = 1L, content = "newer")
    for
      _ <- store.addTask(old)
      _ <- IO(os.write(dir / "s2.json", List(newer).asJson.noSpaces))
      result <- store.addUserTodoOnce(old).attempt
    yield assert(result.isLeft)

  test("direct save rejects corrupt rule history on newer propagated replica"):
    val dir = os.temp.dir(prefix = "due-save-history-authority-")
    val store = new ScheduledTaskStore(dir)
    val rule = TodoDue.Weekly(1, "09:00", "Europe/London", "2024-01-01T09:00")
    val original = legacy.as[ScheduledTask].toOption.get.copy(due = Some(rule), dueRuleVersion = 4L)
    val invalid = original.copy(sessionId = "s2", revision = 1L, dueRuleVersion = 0L, dueRuleHistory = Nil)
    for
      _ <- store.addTask(original)
      result <- store.saveTasks("s2", List(invalid)).attempt
      exists <- IO(os.exists(dir / "s2.json"))
    yield
      assert(result.isLeft)
      assertEquals(exists, false)

  test("direct save refuses propagated rule change that drops existing occurrences"):
    val dir = os.temp.dir(prefix = "due-save-propagated-occurrence-")
    val store = new ScheduledTaskStore(dir)
    val first = TodoDue.Weekly(1, "09:00", "Europe/London", "2024-01-01T09:00")
    val changed = TodoDue.Weekly(2, "09:00", "Europe/London", "2024-01-02T09:00")
    val occurrence = TodoOccurrence.create("old", 1000L, 0L)
    val original = legacy.as[ScheduledTask].toOption.get.copy(due = Some(first), occurrences = List(occurrence))
    val propagated = original.copy(sessionId = "s2", revision = 1L, due = Some(changed), dueRuleVersion = 1L,
      dueRuleHistory = List(TodoRuleSnapshot(0L, first)), occurrences = Nil)
    for
      _ <- store.addTask(original)
      result <- store.saveTasks("s2", List(propagated)).attempt
    yield assert(result.isLeft)

  test("direct save advances revision and rejects a second stale save"):
    val dir = os.temp.dir(prefix = "due-save-generation-")
    val store = new ScheduledTaskStore(dir)
    val original = legacy.as[ScheduledTask].toOption.get
    for
      _ <- store.addTask(original)
      _ <- store.saveTasks("s1", List(original.copy(content = "first")))
      result <- store.saveTasks("s1", List(original.copy(content = "second"))).attempt
      rows <- store.loadTasks("s1")
    yield
      assert(result.isLeft)
      assertEquals(rows.head.content, "first")
      assertEquals(rows.head.revision, 1L)

  test("direct save rejects a stale revision instead of overwriting newer state"):
    val dir = os.temp.dir(prefix = "due-save-stale-")
    val store = new ScheduledTaskStore(dir)
    val original = legacy.as[ScheduledTask].toOption.get
    for
      _ <- store.addTask(original)
      _ <- store.updateTask("s1", original.id)(_.copy(content = "newer"))
      result <- store.saveTasks("s1", List(original.copy(content = "stale"))).attempt
      rows <- store.loadTasks("s1")
    yield
      assert(result.isLeft)
      assertEquals(rows.head.content, "newer")

  test("direct save increments rule version when a reminder rule changes"):
    val dir = os.temp.dir(prefix = "due-rule-save-")
    val store = new ScheduledTaskStore(dir)
    val first = TodoDue.Weekly(1, "09:00", "Europe/London", "2024-01-01T09:00")
    val changed = TodoDue.Weekly(2, "09:00", "Europe/London", "2024-01-02T09:00")
    val initial = legacy.as[ScheduledTask].toOption.get.copy(due = Some(first))
    for
      _ <- store.addTask(initial)
      _ <- store.saveTasks("s1", List(initial.copy(due = Some(changed))))
      rows <- store.loadTasks("s1")
    yield assertEquals(rows.head.dueRuleVersion, 1L)

  test("tick refuses to overwrite a stale destination session copy"):
    val dir = os.temp.dir(prefix = "tick-stale-destination-")
    val store = new ScheduledTaskStore(dir)
    val due = TodoDue.Once(1000L, "Europe/London", "1970-01-01T00:00:01", Some("Z"))
    val stale = legacy.as[ScheduledTask].toOption.get.copy(sessionId = "s2", due = Some(due), nextDueAt = Some(1000L))
    val latest = stale.copy(revision = 1L, content = "latest")
    for
      _ <- store.saveTasks("s1", List(latest))
      _ <- IO(os.write(dir / "s2.json", List(stale).asJson.noSpaces))
      result <- store.materializeDueOccurrences(1000L).attempt
      unchanged <- IO(io.circe.parser.decode[List[ScheduledTask]](os.read(dir / "s2.json")).toOption.get)
    yield
      assert(result.isLeft)
      assertEquals(unchanged, List(stale))

  test("occurrence recording rejects stale rule versions"):
    val dir = os.temp.dir(prefix = "occurrence-stale-rule-")
    val store = new ScheduledTaskStore(dir)
    val initial = legacy
      .as[ScheduledTask]
      .toOption
      .get
      .copy(
        due = Some(TodoDue.Once(1000L, "Europe/London", "1970-01-01T00:00:01", Some("Z"))),
        dueRuleVersion = 2L,
        nextDueAt = Some(1000L)
      )
    for
      _ <- store.addTask(initial)
      result <- store.recordOccurrence("s1", TodoOccurrence.create("old", 1000L, 1L), None).attempt
      rows <- store.loadTasks("s1")
    yield
      assert(result.isLeft)
      assertEquals(rows, List(initial))

  test("occurrence recording rejects a stale session copy when another revision is authoritative"):
    val dir = os.temp.dir(prefix = "occurrence-stale-session-")
    val store = new ScheduledTaskStore(dir)
    val old = legacy.as[ScheduledTask].toOption.get.copy(nextDueAt = Some(1000L))
    val newer = old.copy(sessionId = "s2", revision = 1L)
    val occurrence = TodoOccurrence.create("old", 1000L, 0L)
    for
      _ <- store.saveTasks("s1", List(old))
      _ <- IO(os.write(dir / "s2.json", List(newer).asJson.noSpaces))
      result <- store.recordOccurrence("s1", occurrence, Some(2000L)).attempt
      stale <- store.loadTasks("s1")
      latest <- store.loadTasks("s2")
    yield
      assert(result.isLeft)
      assertEquals(stale, Nil)
      assertEquals(latest, List(newer))

  test("two store instances concurrently record one occurrence once"):
    val dir = os.temp.dir(prefix = "occurrence-race-")
    val first = new ScheduledTaskStore(dir)
    val second = new ScheduledTaskStore(dir)
    val initial = legacy.as[ScheduledTask].toOption.get.copy(nextDueAt = Some(1000L))
    val occurrence = TodoOccurrence.create("old", 1000L, 7L)
    for
      _ <- first.addTask(initial)
      outcomes <- (
        first.recordOccurrence("s1", occurrence, Some(2000L)),
        second.recordOccurrence("s1", occurrence, Some(2000L))
      ).parTupled
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

  test("cancelled lock holder releases OS lock for a separate process"):
    val dir = os.temp.dir(prefix = "occurrence-cancel-os-probe-")
    val cp = List.newBuilder[String]
    var loader = Thread.currentThread().getContextClassLoader
    while loader != null do
      loader match
        case urls: java.net.URLClassLoader =>
          cp ++= urls.getURLs.toList.filter(_.getProtocol == "file").map(u => Paths.get(u.toURI).toString)
        case _ => ()
      loader = loader.getParent
    cp += System.getProperty("java.class.path")
    def probe: IO[Int] = IO.blocking {
      val child = new ProcessBuilder(
        Paths.get(System.getProperty("java.home"), "bin", "java").toString,
        "-cp",
        cp.result().distinct.mkString(java.io.File.pathSeparator),
        "nebflow.core.scheduler.TodoOccurrenceCrashChild",
        dir.toString,
        "probe"
      )
        .redirectErrorStream(true)
        .redirectOutput((dir / "probe.log").toIO)
        .start()
      assert(child.waitFor(30, java.util.concurrent.TimeUnit.SECONDS))
      child.exitValue()
    }
    for
      entered <- cats.effect.Deferred[IO, Unit]
      holder <- new ScheduledTaskStore(dir).withStoreLock(entered.complete(()) *> IO.never[Unit]).start
      _ <- entered.get
      locked <- probe
      _ <- IO(assertEquals(locked, 4))
      _ <- holder.cancel
      released <- probe
    yield assertEquals(released, 0)

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

  test("cancel while another process holds OS lock releases local mutex"):
    val dir = os.temp.dir(prefix = "occurrence-os-cancel-")
    val cp = List.newBuilder[String]
    var loader = Thread.currentThread().getContextClassLoader
    while loader != null do
      loader match
        case urls: java.net.URLClassLoader =>
          cp ++= urls.getURLs.toList.filter(_.getProtocol == "file").map(u => Paths.get(u.toURI).toString)
        case _ => ()
      loader = loader.getParent
    cp += System.getProperty("java.class.path")
    val process = new ProcessBuilder(
      Paths.get(System.getProperty("java.home"), "bin", "java").toString,
      "-cp",
      cp.result().distinct.mkString(java.io.File.pathSeparator),
      "nebflow.core.scheduler.TodoOccurrenceCrashChild",
      dir.toString,
      "hold"
    ).redirectErrorStream(true).start()
    val reader = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream))
    val store = new ScheduledTaskStore(dir)
    (for
      ready <- IO.blocking(reader.readLine())
      _ <- IO(assertEquals(ready, "LOCKED"))
      ran <- cats.effect.Ref.of[IO, Boolean](false)
      waiting <- cats.effect.Deferred[IO, Unit]
      gate <- cats.effect.Deferred[IO, Unit]
      competing <- cats.effect.Ref.of[IO, Boolean](false)
      waiter <- store.withStoreLock(ran.set(true), waiting.complete(()).void *> gate.get).start
      _ <- waiting.get.timeout(scala.concurrent.duration.DurationInt(5).seconds)
      available <- store.tryStoreMutex
      _ <- IO(assertEquals(available, false))
      mutexAttempt <- cats.effect.Deferred[IO, Unit]
      mutexAcquired <- cats.effect.Deferred[IO, Unit]
      rival <- new ScheduledTaskStore(dir)
        .withStoreLock(competing.set(true), IO.unit, mutexAttempt.complete(()).void, mutexAcquired.complete(()).void)
        .start
      _ <- mutexAttempt.get.timeout(scala.concurrent.duration.DurationInt(5).seconds)
      acquiredBefore <- mutexAcquired.tryGet
      _ <- IO(assertEquals(acquiredBefore, None))
      _ <- waiter.cancel.timeout(scala.concurrent.duration.DurationInt(5).seconds)
      _ <- mutexAcquired.get.timeout(scala.concurrent.duration.DurationInt(5).seconds)
      value <- ran.get
      _ <- IO(assertEquals(value, false))
      _ <- IO.blocking { process.getOutputStream.write(1); process.getOutputStream.flush(); process.waitFor() }
      _ <- rival.joinWithNever.timeout(scala.concurrent.duration.DurationInt(5).seconds)
      uncontended <- cats.effect.Ref.of[IO, Boolean](false)
      _ <- new ScheduledTaskStore(dir)
        .withStoreLock(IO.unit, uncontended.set(true))
        .timeout(scala.concurrent.duration.DurationInt(5).seconds)
      observed <- uncontended.get
      _ <- IO(assertEquals(observed, false))
      free <- store.tryStoreMutex
      _ <- IO(assertEquals(free, true))
    yield ()).guarantee(IO.blocking { process.getOutputStream.close(); () })

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
      new ProcessBuilder(
        Paths.get(System.getProperty("java.home"), "bin", "java").toString,
        "-cp",
        classpath,
        "nebflow.core.scheduler.TodoOccurrenceCrashChild",
        dir.toString,
        "race"
      )
        .redirectErrorStream(true)
        .redirectOutput((dir / s"child-$n.log").toIO)
        .start()
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
