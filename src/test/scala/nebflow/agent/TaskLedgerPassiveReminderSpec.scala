package nebflow.agent

import munit.FunSuite
import nebflow.shared.PathUtil
import java.nio.file.Files
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.*

class TaskLedgerPassiveReminderSpec extends FunSuite:
  private val home = os.Path(Files.createTempDirectory("task-ledger-passive-reminder"))
  private var previous = PathUtil.dataRoot
  override def beforeAll(): Unit =
    previous = PathUtil.dataRoot
    PathUtil.setDataRoot(home)
  override def afterAll(): Unit =
    PathUtil.setDataRoot(previous)
    os.remove.all(home)

  private val gap = 2L * 60 * 60 * 1000
  private def reset(): Unit = if os.exists(home / "task-ledger-reminder-throttle.json") then os.remove(home / "task-ledger-reminder-throttle.json")

  test("first real-input check attaches; under boundary suppresses; exact two-hour boundary attaches"):
    reset()
    val a = TaskLedgerPassiveReminder.due("root-session", 1000L, "3 unfinished tasks")
    assert(a.exists(_.contains("3 unfinished tasks")))
    assertEquals(TaskLedgerPassiveReminder.due("root-session", 1000L + gap - 1, "fresh"), None)
    assert(TaskLedgerPassiveReminder.due("root-session", 1000L + gap, "fresh").exists(_.contains("fresh")))

  test("state survives a new process-like call and session keys do not cross-contaminate"):
    reset()
    assert(TaskLedgerPassiveReminder.due("s1", 100L, "one").nonEmpty)
    assertEquals(TaskLedgerPassiveReminder.due("s1", 101L, "two"), None)
    assert(TaskLedgerPassiveReminder.due("s2", 101L, "other session").nonEmpty)

  test("empty ledger text does not consume throttle; corrupt throttle is quarantined before recovery"):
    reset()
    assertEquals(TaskLedgerPassiveReminder.due("s", 1L, " "), None)
    assert(TaskLedgerPassiveReminder.due("s", 2L, "available").nonEmpty)
    reset()
    val throttle = home / "task-ledger-reminder-throttle.json"
    val damaged = "{broken-preserve-these-bytes"
    os.write.over(throttle, damaged)
    assert(TaskLedgerPassiveReminder.due("s", 3L, "degraded").nonEmpty)
    val quarantined = os.list(home).find(_.last.startsWith("task-ledger-reminder-throttle.json.corrupt-")).get
    assertEquals(os.read(quarantined), damaged)
    assert(io.circe.parser.decode[io.circe.Json](os.read(throttle)).isRight)

  test("failed quarantine rename leaves the corrupt throttle untouched and suppresses reminder"):
    reset()
    val throttle = home / "task-ledger-reminder-throttle.json"
    val damaged = "{cannot-move"
    os.write.over(throttle, damaged)
    val target = os.Path(s"${throttle}.corrupt-${System.currentTimeMillis()}")
    os.makeDir(target)
    assertEquals(TaskLedgerPassiveReminder.due("s", 8L, "must not overwrite"), None)
    assertEquals(os.read(throttle), damaged)

  test("bounded session cache evicts oldest entries deterministically, retaining newest active sessions"):
    reset()
    (1 to 520).foreach(i => assert(TaskLedgerPassiveReminder.due(s"s$i", i.toLong, "x").nonEmpty))
    assert(TaskLedgerPassiveReminder.due("s520", 521L, "x").isEmpty)
    assert(TaskLedgerPassiveReminder.due("s1", 521L, "x").nonEmpty)

  test("duplicate concurrent user-input evaluations deliver at most once"):
    reset()
    given ExecutionContext = ExecutionContext.global
    val calls = Future.sequence((1 to 16).map(_ => Future(TaskLedgerPassiveReminder.due("same", 500L, "summary"))))
    val results = scala.concurrent.Await.result(calls, 5.seconds)
    assertEquals(results.count(_.nonEmpty), 1)

  test("system/background path is structurally not a trigger: helper is only called conditionally by caller"):
    val source = os.read(os.Path(System.getProperty("user.dir") + "/src/main/scala/nebflow/agent/AgentSessionExecution.scala"))
    assert(source.contains("if isRealUserTurn && isRootAgent && !isCompactTurn && !isAskTurn then"))
    assert(!source.contains("ScheduleTaskLedgerPassiveReminder"))
end TaskLedgerPassiveReminderSpec
