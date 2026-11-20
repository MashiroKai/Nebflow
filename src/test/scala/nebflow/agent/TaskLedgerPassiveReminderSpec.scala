package nebflow.agent

import munit.FunSuite
import nebflow.shared.PathUtil
import java.nio.file.{Files, Path}
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

  private def reset(): Unit = if os.exists(home / "task-ledger-reminder-throttle.json") then
    os.remove(home / "task-ledger-reminder-throttle.json")

  test("first real-input check attaches; under boundary suppresses; exact two-hour boundary attaches"):
    reset()
    val a = TaskLedgerPassiveReminder.due("root-session", 1000L, "3 unfinished tasks")
    assert(a.exists(_.contains("3 unfinished tasks")))
    assertEquals(TaskLedgerPassiveReminder.due("root-session", 1000L + gap - 1, "fresh"), None)
    assert(TaskLedgerPassiveReminder.due("root-session", 1000L + gap, "fresh").exists(_.contains("fresh")))

  test("production turn entry dynamically gates root human turns and attaches the due summary"):
    reset()
    var summaryEvaluations = 0
    def summary =
      summaryEvaluations += 1; "3 unfinished tasks"
    val allowed = TaskLedgerPassiveReminder.forTurn(true, true, false, false, Some("root"), 100L, summary)
    assert(allowed.exists(r => r.category == "task-ledger" && r.content.contains("3 unfinished tasks")))
    assertEquals(summaryEvaluations, 1)
    assertEquals(TaskLedgerPassiveReminder.forTurn(false, true, false, false, Some("root"), 101L, "background"), None)
    assertEquals(TaskLedgerPassiveReminder.forTurn(true, false, false, false, Some("child"), 101L, "non-root"), None)
    assertEquals(TaskLedgerPassiveReminder.forTurn(true, true, true, false, Some("root"), 101L, "compact"), None)
    assertEquals(TaskLedgerPassiveReminder.forTurn(true, true, false, true, Some("root"), 101L, "ask"), None)
    assertEquals(TaskLedgerPassiveReminder.forTurn(true, true, false, false, None, 101L, "no session"), None)
    assertEquals(summaryEvaluations, 1)
    assertEquals(TaskLedgerPassiveReminder.due("root", 102L, "still due"), None)
    assert(
      TaskLedgerPassiveReminder
        .forTurn(true, true, false, false, Some("root"), 100L + gap, "expired")
        .exists(_.content.contains("expired"))
    )

  test("atomic replace failure preserves old bytes, emits nothing, then normal retry succeeds"):
    val throttle = home / "replace-failure.json"
    val original = """{"lastDeliveredBySession":{"prior":7}}"""
    os.write.over(throttle, original)
    val failReplace: (java.nio.file.Path, java.nio.file.Path) => Unit =
      (_, _) => throw new java.io.IOException("injected atomic replace failure")
    assertEquals(
      TaskLedgerPassiveReminder
        .dueAtWithReplace(throttle, "target", 10L, "must-not-deliver", (_, _) => (), failReplace),
      None
    )
    assertEquals(os.read(throttle), original)
    assert(TaskLedgerPassiveReminder.dueAt(throttle, "target", 10L, "retry", (_, _) => ()).exists(_.contains("retry")))

  test("state survives a new process-like call and session keys do not cross-contaminate"):
    reset()
    assert(TaskLedgerPassiveReminder.due("s1", 100L, "one").nonEmpty)
    assertEquals(TaskLedgerPassiveReminder.due("s1", 101L, "two"), None)
    assert(TaskLedgerPassiveReminder.due("s2", 101L, "other session").nonEmpty)

  test("two OS child processes contend once per shared session and independently by session"):
    val throttle = home / "multiprocess-throttle.json"
    val dir = Files.createTempDirectory("ledger-multiprocess")
    def javaBin = Path
      .of(
        sys.props("java.home"),
        "bin",
        if sys.props("os.name").toLowerCase.contains("win") then "java.exe" else "java"
      )
      .toString
    def childCp = sys.props("java.class.path")
    def race(session: String): Int =
      val gate = dir.resolve(s"$session.start")
      val children = (1 to 2).map { i =>
        val result = dir.resolve(s"$session.$i.result")
        val ready = dir.resolve(s"$session.$i.ready")
        val pb = new ProcessBuilder(
          javaBin,
          "-cp",
          childCp,
          "nebflow.agent.TaskLedgerReminderChild",
          throttle.toString,
          session,
          gate.toString,
          result.toString,
          ready.toString
        )
        pb.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD)
        (pb.start(), result)
      }
      val readyDeadline = System.currentTimeMillis() + 20_000L
      while children.exists { case (_, result) =>
          !Files.exists(result.resolveSibling(result.getFileName.toString.stripSuffix(".result") + ".ready"))
        } && System.currentTimeMillis() < readyDeadline
      do Thread.`yield`()
      assert(
        children.forall { case (_, result) =>
          Files.exists(result.resolveSibling(result.getFileName.toString.stripSuffix(".result") + ".ready"))
        },
        "both children reached the start barrier"
      )
      Files.createFile(gate)
      children.foreach { case (p, _) =>
        assert(p.waitFor(20, java.util.concurrent.TimeUnit.SECONDS), s"child ${p.pid} did not exit")
      }
      children.foreach { case (p, _) => assertEquals(p.exitValue(), 0, s"child ${p.pid}") }
      children.count { case (_, result) => Files.readString(result) == "DELIVERED" }
    end race
    assertEquals(race("same-session"), 1)
    val gate = dir.resolve("different-start")
    val distinct = List("session-a", "session-b").zipWithIndex.map { case (session, i) =>
      val result = dir.resolve(s"distinct-$i.result")
      val ready = dir.resolve(s"distinct-$i.ready")
      val pb = new ProcessBuilder(
        javaBin,
        "-cp",
        childCp,
        "nebflow.agent.TaskLedgerReminderChild",
        throttle.toString,
        session,
        gate.toString,
        result.toString,
        ready.toString
      )
      pb.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD)
      (pb.start(), result)
    }
    val distinctReady = List("distinct-0.ready", "distinct-1.ready").map(dir.resolve(_))
    val distinctDeadline = System.currentTimeMillis() + 20_000L
    while !distinctReady.forall(Files.exists(_)) && System.currentTimeMillis() < distinctDeadline do Thread.`yield`()
    assert(distinctReady.forall(Files.exists(_)), "both distinct-session children reached the start barrier")
    Files.createFile(gate)
    distinct.foreach { case (p, _) =>
      assert(p.waitFor(20, java.util.concurrent.TimeUnit.SECONDS), s"child ${p.pid} did not exit")
    }
    distinct.foreach { case (p, _) => assertEquals(p.exitValue(), 0, s"child ${p.pid}") }
    assertEquals(distinct.count { case (_, result) => Files.readString(result) == "DELIVERED" }, 2)
    assertEquals(TaskLedgerPassiveReminder.dueAt(throttle, "same-session", 123457L, "suppressed", (_, _) => ()), None)
    assertEquals(TaskLedgerPassiveReminder.dueAt(throttle, "session-a", 123457L, "suppressed", (_, _) => ()), None)
    assert(
      TaskLedgerPassiveReminder.dueAt(throttle, "third-session", 123457L, "after children exit", (_, _) => ()).nonEmpty
    )
    val abnormalGate = dir.resolve("abnormal-start")
    val marker = dir.resolve("abnormal-result")
    val abnormal = new ProcessBuilder(
      javaBin,
      "-cp",
      childCp,
      "nebflow.agent.TaskLedgerReminderChild",
      throttle.toString,
      "abnormal-halt",
      abnormalGate.toString,
      marker.toString,
      dir.resolve("abnormal.ready").toString
    )
      .redirectErrorStream(true)
      .redirectOutput(ProcessBuilder.Redirect.DISCARD)
      .start()
    Files.createFile(abnormalGate)
    assert(abnormal.waitFor(20, java.util.concurrent.TimeUnit.SECONDS), "abnormal child did not exit")
    assertEquals(abnormal.exitValue(), 17)
    assert(
      TaskLedgerPassiveReminder
        .dueAt(throttle, "after-abnormal", 123458L, "lock released after halt", (_, _) => ())
        .nonEmpty
    )

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

  test("failed quarantine move leaves corrupt throttle byte-for-byte intact and suppresses reminder"):
    reset()
    val throttle = home / "throttle.json"
    val damaged = "{cannot-move"
    os.write.over(throttle, damaged)
    val deniedMove: (java.nio.file.Path, java.nio.file.Path) => Unit =
      (_, _) => throw new java.io.IOException("injected quarantine move failure")
    assertEquals(TaskLedgerPassiveReminder.dueAt(throttle, "s", 8L, "must not overwrite", deniedMove), None)
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

  test("production execution path calls the real gated entry (dynamic exclusions covered above)"):
    val source =
      os.read(os.Path(System.getProperty("user.dir") + "/src/main/scala/nebflow/agent/AgentSessionExecution.scala"))
    assert(source.contains("TaskLedgerPassiveReminder.forTurn("))
    assert(source.contains("isRealUserTurn = isRealUserTurn"))
    assert(!source.contains("ScheduleTaskLedgerPassiveReminder"))
end TaskLedgerPassiveReminderSpec
