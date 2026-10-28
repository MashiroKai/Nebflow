package nebflow.agent

import io.circe.syntax.*
import munit.FunSuite
import nebflow.shared.{MemoryHygieneSignal, PathUtil}

import java.nio.file.Files

/**
 * 任务账本生命周期注入 spec（2026-09-06 TaskList 批；2026-10-14 redfix 批按
 * taskunify 合一批（2026-09-24 `75fddc5db`）合同重锚：提醒数据源 =
 * `TaskLedgerStore.openSummaryLine`（合一账本 `~/.nebflow/tasks-v2.json`，标记
 * `[Task]`，尾指引 `Task(action=list|show)`；三态 {open, closed, completed}）。
 * 旧 `tasks.json` / `[TaskList]` 形态为被取代的合同，不再由本 spec 钉。）
 *
 * 注入语义（任务书第 5 条）：任务明细永不进 system prompt（按需
 * Task(action=list) 查询）；open 任务存在时，生命周期节点注入一行 open
 * 摘要；全闭环后提醒消失。门控机制 = systemStable 生命周期重建点（cache
 * v2：条件块含 memoryBlock 整体进 systemStable，仅在重启/压缩/定义变更等
 * lifecycle 节点重建，轮与轮之间复用缓存）——openSummaryLine 无条件返回当前
 * 快照，生命周期性由重建点天然承担（MemoryHygieneSignal 一次性信号只叠加
 * 整理提醒，不承担任务摘要门控）。
 *
 * 覆盖：
 *  - renderMemoryBlock 第 4 参（openTasksLine）：空串不渲染、非空以 --- 分段、
 *    与记忆主体/整理提醒共存（纯函数直测）；
 *  - buildMemoryBlock 集成：open 任务 → 摘要行出现；全闭环 → 摘要行消失；
 *    注入最小性（一行摘要，note 时间线/明细不注入）；
 *  - 门槛：shouldInjectMemory 只放 Nebula——提醒只可能出现在 Nebula 记忆块。
 *
 * 隔离：PathUtil.setDataRoot(temp) + MemoryHygieneSignal.resetForTest
 * （private[agent]，本 spec 同包可用——MemoryHygieneSpec 同款）。
 */
class TaskListReminderSpec extends FunSuite:

  var home: os.Path = os.Path(Files.createTempDirectory("nb-tasklist-reminder"))
  var prevRoot: os.Path = PathUtil.dataRoot

  override def beforeAll(): Unit =
    prevRoot = PathUtil.dataRoot
    PathUtil.setDataRoot(home)

  override def afterAll(): Unit =
    PathUtil.setDataRoot(prevRoot)
    os.remove.all(home)

  private def file: os.Path = home / nebflow.core.project.TaskLedgerStore.FileName

  private def resetTasks(): Unit =
    List(
      file,
      home / "tasks-v2-history.jsonl",
      home / "tasks-v2-history.1.jsonl",
      home / "tasks.json",
      home / "tasks-history.jsonl"
    ).foreach(p => if os.exists(p) then os.remove(p))

  /** 直写合一账本（不经工具——本 spec 只关心注入侧对文件状态的响应）。 */
  private def seedTask(id: String, title: String, status: String): Unit =
    val store = readStore.getOrElse(nebflow.core.project.TaskLedgerData())
    val existing = store.tasks.filterNot(_.id == id)
    val entry = nebflow.core.project.TaskEntry(
      id = id,
      title = title,
      status = status,
      createdAt = Some("2026-09-06T00:00:00Z"),
      updatedAt = Some("2026-09-06T00:00:00Z"),
      closedAt = Option.when(status == nebflow.core.project.TaskLedgerStore.Status.Closed)(
        "2026-09-06T00:00:00Z"
      ),
      completedAt = Option.when(status == nebflow.core.project.TaskLedgerStore.Status.Completed)(
        "2026-09-06T00:00:00Z"
      )
    )
    os.write.over(file, store.copy(tasks = existing :+ entry).asJson.noSpaces, createFolders = true)

  end seedTask

  private def readStore: Option[nebflow.core.project.TaskLedgerData] =
    if os.exists(file) then io.circe.parser.decode[nebflow.core.project.TaskLedgerData](os.read(file)).toOption
    else None

  private val smallMem = Some("# User\n\n- 正常内容")

  // ===== 纯渲染：renderMemoryBlock 第 4 参 =====

  test("render: openTasksLine 空串不渲染（全闭环后提醒消失的渲染侧保证）"):
    val block = ContextRefresher.renderMemoryBlock(smallMem, None, (false, false), "")
    assert(!block.contains("Task"), block)

  test("render: 摘要行以 --- 分段追加，记忆主体完整"):
    val line = "[Task] 1 open task(s): #1[open] 事情 — details: Task(action=list)"
    val block = ContextRefresher.renderMemoryBlock(smallMem, None, (false, false), line)
    assert(block.contains("# Memory"), "记忆主体保留")
    assert(block.contains("[Task]"), "摘要行渲染")
    assert(block.indexOf("## User Memory") < block.indexOf("[Task]"), "摘要行在主体之后")
    assert(block.contains("---"), "分段分隔")

  test("render: 无记忆文件 + 摘要行 → 记忆块只剩任务段（Nebula 空记忆不丢任务提醒）"):
    val line = "[Task] 2 open task(s): #1[open] a | #2[closed] b"
    val block = ContextRefresher.renderMemoryBlock(None, None, (false, false), line)
    assert(block.startsWith("[Task]"), block)
    assert(block.contains("---") == false, "单段无分隔")

  test("render: 摘要行与整理提醒同轮共存（两段各自独立、顺序 主体→提醒→任务）"):
    val userBig = Some("x" * 41000) // >40KB 软线 → IMMEDIATE TASK
    val line = "[Task] 1 open task(s): #1[open] 事情"
    val block = ContextRefresher.renderMemoryBlock(userBig, None, (true, false), line)
    val iBody = block.indexOf("# Memory")
    val iNotice = block.indexOf("IMMEDIATE TASK")
    val iTask = block.indexOf("[Task]")
    assert(iBody < iNotice && iNotice < iTask, s"三段顺序: $iBody,$iNotice,$iTask")

  // ===== 集成：buildMemoryBlock（真文件 + 生命周期信号）=====

  test("集成：重启信号 + open 任务 → 摘要行出现"):
    resetTasks()
    seedTask("1", "写交付报告", "open")
    MemoryHygieneSignal.resetForTest(restartedV = true, compactedV = false)

    val block1 = ContextRefresher.buildMemoryBlock("Nebula")
    assert(block1.contains("[Task]"), "重启后首个注入带 open 摘要")
    assert(block1.contains("#1[open] 写交付报告"), block1)

  test("注入最小性：记忆块只带一行摘要——条目明细/时间线正文不注入（按需 list/show 查询）"):
    resetTasks()
    seedTask("1", "机密标题内容不应全量出现在提示里", "open")
    // note 时间线直写盘面（合一账本的 note 不在条目上——tasks-v2-history.jsonl）
    os.write.over(
      home / "tasks-v2-history.jsonl",
      """{"at":"2026-09-11T00:01:00Z","actor":"nebula","kind":"note","id":"1","from":"旧版机密A不应注入","to":"新版机密B不应注入","body":"这是很长的机密备注正文不应注入提示词XYZ"}""".stripMargin,
      createFolders = true
    )
    val block = ContextRefresher.buildMemoryBlock("Nebula")
    assert(block.contains("[Task]"), "摘要行在")
    assert(block.contains("机密标题内容不应全量出现在提示里"), "标题（截 40 内）在摘要行内")
    assert(!block.contains("机密备注正文不应注入提示词XYZ"), "note 正文不注入")
    assert(!block.contains("旧版机密A不应注入"), "note 变更 old 侧不得注入")
    assert(!block.contains("新版机密B不应注入"), "note 变更 new 侧不得注入")
    assert(!block.contains("Task — "), "list 全量渲染块不注入（那是 action=list 的输出形态）")

  test("集成：压缩信号同理携带摘要行"):
    resetTasks()
    seedTask("2", "跟进 review", "open")
    MemoryHygieneSignal.resetForTest(restartedV = false, compactedV = true)

    val block = ContextRefresher.buildMemoryBlock("Nebula")
    assert(block.contains("[Task]"), "压缩后首个注入带 open 摘要")
    assert(block.contains("#2[open] 跟进 review"), block)

  test("摘要行尾指向 list|show（按需明细/变更史指引）"):
    resetTasks()
    seedTask("1", "写交付报告", "open")
    MemoryHygieneSignal.resetForTest(restartedV = true, compactedV = false)
    val block = ContextRefresher.buildMemoryBlock("Nebula")
    assert(block.contains("Task(action=list|show)"), s"尾指引必须含 show（否则模型不知道能查变更史）: $block")

  test("变更史 / 时间线文本永不进记忆块（史只在 show 按需读取）"):
    resetTasks()
    seedTask("1", "有史任务", "open")
    // 直造史文件：含醒目标记串 + note 时间线形态文本
    os.write.over(
      home / "tasks-v2-history.jsonl",
      """{"at":"2026-09-11T00:00:00Z","actor":"nebula","kind":"log","id":"1","text":"史文件机密正文XYZ不应注入"}
        |{"at":"2026-09-11T00:01:00Z","actor":"nebula","kind":"note","id":"1","from":"旧版机密A不应注入","to":"新版机密B不应注入"}""".stripMargin,
      createFolders = true
    )
    MemoryHygieneSignal.resetForTest(restartedV = true, compactedV = false)
    val block = ContextRefresher.buildMemoryBlock("Nebula")
    assert(block.contains("[Task]"), block)
    assert(!block.contains("史文件机密正文XYZ不应注入"), "log 正文不得注入")
    assert(!block.contains("旧版机密A不应注入"), "note 变更 old 侧不得注入")
    assert(!block.contains("新版机密B不应注入"), "note 变更 new 侧不得注入")
    assert(!block.contains("Note timeline"), "时间线小节不得注入")
    assert(!block.contains("tasks-v2-history"), "史文件名不得注入")

  test("集成：全闭环 + 生命周期信号 → 摘要行消失"):
    resetTasks()
    seedTask("1", "已完成事项", "completed")
    MemoryHygieneSignal.resetForTest(restartedV = true, compactedV = false)

    val block = ContextRefresher.buildMemoryBlock("Nebula")
    assert(!block.contains("[Task]"), "全闭环后提醒消失")

  test("集成：无账本文件 + 生命周期信号 → 无任务段（空库零渲染）"):
    resetTasks()
    MemoryHygieneSignal.resetForTest(restartedV = true, compactedV = false)
    val block = ContextRefresher.buildMemoryBlock("Nebula")
    assert(!block.contains("[Task]"), block)

  test("门槛：shouldInjectMemory 仅 Nebula——任务提醒不可能出现在其他身份的记忆块"):
    // 提醒挂在 buildMemoryBlock 上，而 buildMemoryBlock 被 shouldInjectMemory 门控
    assert(ContextRefresher.shouldInjectMemory(isWorker = false, agentName = "Nebula"))
    assert(!ContextRefresher.shouldInjectMemory(isWorker = false, agentName = "general"))
    assert(!ContextRefresher.shouldInjectMemory(isWorker = false, agentName = "project-dispatcher"))
    assert(!ContextRefresher.shouldInjectMemory(isWorker = true, agentName = "Nebula"), "worker 无记忆")

end TaskListReminderSpec
