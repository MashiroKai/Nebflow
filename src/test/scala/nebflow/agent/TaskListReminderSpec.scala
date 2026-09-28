package nebflow.agent

import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.JsonObject
import io.circe.syntax.*
import munit.FunSuite
import nebflow.core.PathUtil
import nebflow.core.project.{TaskEntry, TaskLedgerData, TaskLedgerEvent, TaskLedgerHistory, TaskLedgerStore}

import java.nio.file.Files

/**
 * Task-ledger lifecycle injection spec (open-task summary line).
 *
 * Renamed/retargeted by the taskunify batch (2026-09-24): the data source moved from
 * the retired `~/.nebflow/tasks.json` (`TaskListStore.openSummaryLine`) to the unified
 * ledger `~/.nebflow/tasks-v2.json` (`TaskLedgerStore.openSummaryLine`), and the
 * rendered prefix moved `[TaskList]` → `[Task]`. The injection INVARIANTS are unchanged
 * and are what this spec pins:
 *
 *  - task detail NEVER enters the system prompt (read on demand with `Task(action=list)`);
 *  - while open tasks exist, a lifecycle node injects a ONE-LINE open summary;
 *  - when nothing is open any more, the line disappears;
 *  - the summary line names the on-demand follow-up (`Task(action=list|show)`);
 *  - the note timeline / history file text is NEVER injected (only `TaskInfo`/`show` reads it).
 *
 * Gate = the systemStable lifecycle rebuild point (cache v2: the conditional block
 * carrying the whole memoryBlock enters systemStable, rebuilt only on restart /
 * compaction / definition change and reused between turns) — `openSummaryLine`
 * unconditionally returns the current snapshot; the lifecycle-ness is carried by the
 * rebuild point (the one-shot MemoryHygieneSignal only adds the tidy-up reminder).
 *
 * Isolation: `PathUtil.setDataRoot(temp)` + `MemoryHygieneSignal.resetForTest`
 * (`private[agent]`, same package as MemoryHygieneSpec).
 */
class TaskListReminderSpec extends FunSuite:

  var home: os.Path = os.Path(Files.createTempDirectory("nb-taskledger-reminder"))
  var prevRoot: os.Path = PathUtil.dataRoot

  override def beforeAll(): Unit =
    prevRoot = PathUtil.dataRoot
    PathUtil.setDataRoot(home)

  override def afterAll(): Unit =
    PathUtil.setDataRoot(prevRoot)
    os.remove.all(home)

  private def file: os.Path = home / TaskLedgerStore.FileName
  private def historyFile: os.Path = home / TaskLedgerHistory.FileName

  private def resetTasks(): Unit =
    List(file, historyFile, home / s"${TaskLedgerHistory.FileName}.1").foreach { p =>
      if os.exists(p) then os.remove(p)
    }

  /** Write the ledger directly (not through the tool — this spec only cares about the
    * injection side's response to file state). Only the OPEN entries matter here. */
  private def seedTask(id: String, title: String, status: String): Unit =
    val store = readStore.getOrElse(TaskLedgerData())
    val existing = store.tasks.filterNot(_.id == id)
    val entry = TaskEntry(
      id = id, title = title, status = status,
      createdAt = Some("2026-09-24T00:00:00Z"), updatedAt = Some("2026-09-24T00:00:00Z"))
    os.write.over(file, store.copy(tasks = existing :+ entry).asJson.noSpaces, createFolders = true)

  private def readStore: Option[TaskLedgerData] =
    if os.exists(file) then io.circe.parser.decode[TaskLedgerData](os.read(file)).toOption
    else None

  /** Append a NOTE event straight into the history file (the entry has no note field
    * any more — the timeline lives in its own file). */
  private def seedHistoryNote(id: String, text: String): Unit =
    val ev = TaskLedgerEvent(
      at = "2026-09-24T00:00:00Z", kind = TaskLedgerHistory.Kinds.Note, id = Some(id),
      actor = TaskLedgerHistory.Actors.Nebula, from = Some(TaskLedgerHistory.Origins.Nebula),
      text = Some(text))
    os.write.over(historyFile, ev.asJson.noSpaces + "\n", createFolders = true)

  private val smallMem = Some("# User\n\n- normal content")

  // ===== pure render: renderMemoryBlock's 4th parameter =====

  test("render: an empty openTasksLine is not rendered (the render-side guarantee that the line disappears)"):
    val block = ContextRefresher.renderMemoryBlock(smallMem, None, (false, false), "")
    assert(!block.contains("[Task]"), block)

  test("render: the summary line is appended as a `---` segment, memory body intact"):
    val line = "[Task] 1 open task(s): #1[open] something — details: Task(action=list|show)"
    val block = ContextRefresher.renderMemoryBlock(smallMem, None, (false, false), line)
    assert(block.contains("# Memory"), "memory body retained")
    assert(block.contains("[Task]"), "summary line rendered")
    assert(block.indexOf("## User Memory") < block.indexOf("[Task]"), "summary line after the body")
    assert(block.contains("---"), "segment separator")

  test("render: no memory file + summary line → the memory block is the task section only (an empty Nebula memory must not drop the reminder)"):
    val line = "[Task] 2 open task(s): #1[open] a | #2[closed] b"
    val block = ContextRefresher.renderMemoryBlock(None, None, (false, false), line)
    assert(block.startsWith("[Task]"), block)
    assert(block.contains("---") == false, "a single segment has no separator")

  test("render: summary line and tidy-up reminder coexist in one turn (independent segments, order body→notice→task)"):
    val userBig = Some("x" * 41000) // >40KB soft line → IMMEDIATE TASK
    val line = "[Task] 1 open task(s): #1[open] something"
    val block = ContextRefresher.renderMemoryBlock(userBig, None, (true, false), line)
    val iBody = block.indexOf("# Memory")
    val iNotice = block.indexOf("IMMEDIATE TASK")
    val iTask = block.indexOf("[Task]")
    assert(iBody < iNotice && iNotice < iTask, s"three-segment order: $iBody,$iNotice,$iTask")

  // ===== integration: buildMemoryBlock (real files + lifecycle signal) =====

  test("integration: restart signal + an open task → the summary line appears"):
    resetTasks()
    seedTask("1", "write the delivery report", TaskLedgerStore.Status.Open)
    MemoryHygieneSignal.resetForTest(restartedV = true, compactedV = false)

    val block1 = ContextRefresher.buildMemoryBlock("Nebula")
    assert(block1.contains("[Task]"), "the first injection after a restart carries the open summary")
    assert(block1.contains("#1[open] write the delivery report"), block1)

  test("injection minimality: the memory block carries ONE summary line — entry note / dependency detail / the full list are not injected (read on demand with list)"):
    resetTasks()
    seedTask("1", "a secret title that must not appear in full in the prompt", TaskLedgerStore.Status.Open)
    seedHistoryNote("1", "a very long secret note body that must not be injected XYZ")
    val block = ContextRefresher.buildMemoryBlock("Nebula")
    assert(block.contains("[Task]"), "the summary line is there")
    assert(block.contains("a secret title that must not appear"), "the title (truncated at 40) is inside the summary line")
    assert(!block.contains("a very long secret note body"), "the note body is NOT injected")
    assert(!block.contains("Task — "), "the full `list` render block is not injected (that is action=list output)")

  test("integration: a compaction signal likewise carries the summary line"):
    resetTasks()
    seedTask("2", "follow up the review", TaskLedgerStore.Status.Open)
    MemoryHygieneSignal.resetForTest(restartedV = false, compactedV = true)

    val block = ContextRefresher.buildMemoryBlock("Nebula")
    assert(block.contains("[Task]"), "the first injection after compaction carries the open summary")
    assert(block.contains("#2[open] follow up the review"), block)

  test("the summary line tail points at list|show (the on-demand detail pointer)"):
    resetTasks()
    seedTask("1", "write the delivery report", TaskLedgerStore.Status.Open)
    MemoryHygieneSignal.resetForTest(restartedV = true, compactedV = false)
    val block = ContextRefresher.buildMemoryBlock("Nebula")
    assert(block.contains("Task(action=list|show)"),
      s"the tail pointer must name show (otherwise the model cannot discover it): $block")

  test("the change history / note timeline text NEVER enters the memory block (the history is read on demand only)"):
    resetTasks()
    seedTask("1", "a task with history", TaskLedgerStore.Status.Open)
    // Craft the history file directly: a loud marker string + note-timeline-shaped text.
    os.write.over(historyFile,
      """{"at":"2026-09-24T00:00:00Z","actor":"nebula","kind":"note","id":"1","from":"nebula","text":"history-file secret body XYZ must not be injected"}
        |{"at":"2026-09-24T00:01:00Z","actor":"nebula","kind":"update","id":"1","from":"old secret A must not be injected","next":"new secret B must not be injected"}""".stripMargin,
      createFolders = true)
    MemoryHygieneSignal.resetForTest(restartedV = true, compactedV = false)
    val block = ContextRefresher.buildMemoryBlock("Nebula")
    assert(block.contains("[Task]"), block)
    assert(!block.contains("history-file secret body XYZ must not be injected"), "the note body must not be injected")
    assert(!block.contains("old secret A must not be injected"), "the note change old side must not be injected")
    assert(!block.contains("new secret B must not be injected"), "the note change new side must not be injected")
    assert(!block.contains("Note timeline"), "the timeline section must not be injected")
    assert(!block.contains("tasks-v2-history"), "the history file name must not be injected")

  test("integration: nothing open + lifecycle signal → the summary line disappears"):
    resetTasks()
    seedTask("1", "a finished item", TaskLedgerStore.Status.Completed)
    MemoryHygieneSignal.resetForTest(restartedV = true, compactedV = false)

    val block = ContextRefresher.buildMemoryBlock("Nebula")
    assert(!block.contains("[Task]"), "the reminder disappears once nothing is open")

  test("integration: no ledger file + lifecycle signal → no task segment (an empty ledger renders nothing)"):
    resetTasks()
    MemoryHygieneSignal.resetForTest(restartedV = true, compactedV = false)
    val block = ContextRefresher.buildMemoryBlock("Nebula")
    assert(!block.contains("[Task]"), block)

  test("gate: shouldInjectMemory admits Nebula only — a task reminder can never appear in another identity's memory block"):
    // The reminder hangs off buildMemoryBlock, which shouldInjectMemory gates.
    assert(ContextRefresher.shouldInjectMemory(isWorker = false, agentName = "Nebula"))
    assert(!ContextRefresher.shouldInjectMemory(isWorker = false, agentName = "general"))
    assert(!ContextRefresher.shouldInjectMemory(isWorker = false, agentName = "project-dispatcher"))
    assert(!ContextRefresher.shouldInjectMemory(isWorker = true, agentName = "Nebula"), "a worker has no memory")

end TaskListReminderSpec
