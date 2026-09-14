package nebflow.agent

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import munit.FunSuite
import nebflow.core.PathUtil
import nebflow.core.project.ProjectStore
import nebflow.core.tools.MemoryQueue
import nebflow.service.{MemoryBudget, MemoryStore}

import java.nio.file.Files
import java.security.MessageDigest

/**
 * 记忆轨暂停标记 spec（#440 ① 引擎面 —— 作者 09-13 裁定「载体缺口」修法**主腿**）。
 *
 * **判据（成对读数，缺一不算落地）**：「标记存在 ⇒ 该轮**零写入** + 事件行存在」。
 *
 *   - **修复臂** = 标记在场时 `MemoryTrack.run` 返回 `Status.Paused`，且
 *     **四份文件 sha256 前后逐位相同**（`User.md` / `agents/Nebula/memory.md` /
 *     项目 `<ws>/.nebflow/memory.md` / `memory/queue.jsonl`）、数据根下**零新增文件**、
 *     `pending` 计数未变（不把 pending 打成终态词）、重试引线不置位。
 *   - **变异臂**（见交付说明的 M1/M2 读数，不在本文件内以开关模拟）：移除入口闸或
 *     让标记不被读取 ⇒ 同一夹具下 `run` **不再短路**（对照臂 ② 即其常驻负控形态：
 *     标记缺席时同一 `run` 走到闸 2 得 `Status.Refused`）⇒ 主判据必红。
 *   - **事件行**（`event=memory-track-skipped detail=reason=paused`）由调用方落
 *     （`AgentActor.handleCompactResponse` 的状态匹配 + `logAgentEvent`，与
 *     `memory-track-{failed,timeout,completed,refused,dry-run}` 同族同处）——本 spec
 *     钉住其**字样来源**：`Result.paused.detail` 以 `reason=paused` 起头。
 *
 * **口径同源（禁两套）**：暂停轮不改任何谓词（`RetryableResults` / 分桶 / 授权集 /
 * 终态词域全不动）⇒ 与 `mempipe-init-plan` §3.1 排空链、§3.4 目标分支安全网、
 * §3.7 定量判据（`P(T)` 定义在队列状态上、与墙钟无关）一致：暂停期间 `P(T)` 的
 * **输入**不变，删标记后由**同一条链**消费。
 *
 * 隔离：`PathUtil.setDataRoot(临时目录)`（`MemoryTrackSpec` 同款），零 `~/.nebflow` 写入。
 */
class MemoryTrackPauseSpec extends FunSuite:

  private def sha(p: os.Path): String =
    MessageDigest.getInstance("SHA-256").digest(os.read.bytes(p)).map("%02x".format(_)).mkString

  /** 数据根下的文件相对路径集（零新增文件的判据面）。 */
  private def tree(root: os.Path): Vector[String] =
    if !os.exists(root) then Vector.empty
    else os.walk(root).filter(os.isFile).map(_.relativeTo(root).toString).toVector.sorted

  private def outcomeLines(queue: os.Path): Int =
    if !os.exists(queue) then 0 else os.read(queue).linesIterator.count(_.contains("\"kind\":\"outcome\""))

  /** 最小 SharedResources 夹具（暂停臂不触资源位；形态沿用 `MemoryTrackSpec.mkResources`）。 */
  private def mkResources(): SharedResources =
    SharedResources(
      llm = null,
      dispatcher = null,
      sessionStore = null,
      projectRoot = os.pwd,
      thinkingConfigRef = Ref.unsafe[IO, nebflow.llm.ThinkingConfig](nebflow.llm.ThinkingConfig()),
      rateLimiter = null,
      fileChangeTracker = null,
      contextWindow = 0,
      agentLibrary = null,
      taskStore = null,
      historyArchiver = null,
      fileLockManager = null,
      sessionModelOverrides = Ref.unsafe[IO, Map[String, nebflow.llm.ModelCandidate]](Map.empty),
      providerRegistry = null,
      healthMonitor = null.asInstanceOf[nebflow.llm.ProviderHealthMonitor],
      actorSystem = null,
      voiceMutedRef = Ref.unsafe[IO, Boolean](false),
      agentRegistry = Ref.unsafe[IO, Map[String, nebflow.agent.AgentRecord]](Map.empty)
    )

  private final case class Fixture(
    root: os.Path,
    userMd: os.Path,
    agentMd: os.Path,
    projectMd: os.Path,
    queue: os.Path,
    notes: Vector[MemoryQueue.Note]
  )

  /** 四份文件（三层记忆 + 队列）的真实夹具：两条 pending（user / project）+ 一条已闭合
    * outcome（使 `queue.jsonl` 的 **outcome 面**非空 ⇒ 判据的 sha 对照面有鉴别力）。 */
  private def withFixture(body: Fixture => Unit): Unit =
    val prev = PathUtil.dataRoot
    val root = os.Path(Files.createTempDirectory("nb-memtrack-pause"))
    try
      PathUtil.setDataRoot(root)
      MemoryStore.invalidateUserCache()
      MemoryStore.invalidateAgentCache("Nebula")
      MemoryTrackSignal.resetForTest()

      val ws = root / "ws"
      val projectMd = ws / ".nebflow" / "memory.md"
      os.write.over(projectMd, "# Project demo\n\n## Lessons\n\n- existing project line\n", createFolders = true)
      os.write.over(
        ProjectStore.projectJsonPath("demo"),
        s"""{"name":"demo","workspace":"${ws.toString}","agentFile":"AGENTS.md","createdAt":0}""",
        createFolders = true)
      os.write.over(MemoryStore.userMemoryPath, "# User\n\n## Dream Extract\n\n- existing user line\n", createFolders = true)
      os.write.over(MemoryStore.agentMemoryPath("Nebula"), "# Nebula memory\n\n- existing agent line\n", createFolders = true)

      val nUser = MemoryQueue
        .enqueue("user", "append", None, None, Some("- new user line"), Some("s"), MemoryQueue.TriggerManual, "Nebula")
        .toOption
        .get
        .id
      val nProj = MemoryQueue
        .enqueue(
          "project:demo",
          "append",
          Some("## Lessons"),
          None,
          Some("- new project line"),
          Some("s"),
          MemoryQueue.TriggerManual,
          "Nebula")
        .toOption
        .get
        .id
      val closed = MemoryQueue
        .enqueue("user", "append", None, None, Some("- already landed line"), Some("s"), MemoryQueue.TriggerManual, "Nebula")
        .toOption
        .get
        .id
      assert(MemoryQueue.recordOutcome(closed, MemoryQueue.ResultApplied, "memory-consolidator", "landed").isRight)

      val pending = MemoryQueue.readState().pending.filter(n => n.id == nUser || n.id == nProj)
      assertEquals(pending.size, 2, "夹具：两条 pending")
      body(Fixture(root, MemoryStore.userMemoryPath, MemoryStore.agentMemoryPath("Nebula"), projectMd, MemoryQueue.queuePath, pending))
    finally
      PathUtil.setDataRoot(prev)
      os.remove.all(root)

  // ===== 标记路径契约 =====

  test("标记路径契约：<dataRoot>/memory/consolidation-paused（`def` 非 `val` ⇒ 换根即换面）"):
    withFixture { fx =>
      assertEquals(MemoryTrack.pauseMarkerPath, fx.root / "memory" / MemoryTrack.PauseMarkerFileName)
      assertEquals(MemoryTrack.pauseMarkerPath, fx.root / "memory" / "consolidation-paused")
      assertEquals(MemoryTrack.pauseMarkerPath, MemoryQueue.queuePath / os.up / "consolidation-paused", "与队列同目录")
      assert(!MemoryTrack.isPaused, "未创建即未暂停")
      os.write.over(MemoryTrack.pauseMarkerPath, "", createFolders = true)
      assert(MemoryTrack.isPaused, "存在位即暂停（不看内容）")
    }

  test("四份文件读数有意义：本轮点名 project:demo ⇒ 轨的触点集确实含该项目记忆文件"):
    withFixture { fx =>
      val targets = MemoryTrack.memoryFilesOf(fx.notes).unsafeRunSync()
      assertEquals(
        targets.map(_._1),
        Vector(MemoryStore.userMemoryPath, MemoryStore.agentMemoryPath("Nebula"), fx.projectMd),
        "三层记忆文件（user / agent / 被点名项目）同在触点集")
    }

  // ===== ① 主判据（修复臂）=====

  test("① 主判据：标记存在 ⇒ run 返回 Paused ∧ 四份文件 sha256 前后逐位相同 ∧ 数据根零新增文件"):
    withFixture { fx =>
      os.write.over(MemoryTrack.pauseMarkerPath, "", createFolders = true)
      assert(MemoryTrack.isPaused)

      val files  = Vector(fx.userMd, fx.agentMd, fx.projectMd, fx.queue)
      val before = files.map(sha)
      val treeBefore  = tree(fx.root)
      val pendingBefore = MemoryQueue.pendingCount()
      val outcomesBefore = outcomeLines(fx.queue)
      assertEquals(pendingBefore, 2, "夹具：两条 pending")
      assertEquals(outcomesBefore, 1, "夹具：outcome 面非空（一条已闭合）")

      val r = MemoryTrack.run(mkResources(), parentSessionId = Some("s"), parentDepth = 0).unsafeRunSync()

      assertEquals(r.status, MemoryTrack.Status.Paused, s"标记存在 ⇒ 跳过本轮: $r")
      assertEquals(r.pendingAtStart, 0, "未读队列的真实读数（不作 pending 计数承诺）")
      assert(r.detail.startsWith("reason=paused"), s"事件行字样来源（detail 起头）: ${r.detail}")
      assert(r.alert.isEmpty, "暂停不是失败 ⇒ 无告警")
      assertEquals(
        files.map(sha),
        before,
        "**四份文件逐位未变**：User.md / agents/Nebula/memory.md / 项目 .nebflow/memory.md / memory/queue.jsonl")
      assertEquals(tree(fx.root), treeBefore, "数据根下零新增文件（无快照目录、无变更史行、无新建记忆文件）")
      assertEquals(MemoryQueue.pendingCount(), pendingBefore, "pending 计数未变（未被打成终态词）")
      assertEquals(outcomeLines(fx.queue), outcomesBefore, "queue.jsonl 的 outcome 面零追加")
      assertEquals(MemoryTrackSignal.peek(), None, "不置位重试引线（暂停不是 infra 失败）")
    }

  // ===== 对照臂（负控：判据确由标记驱动，不是恒真）=====

  test("对照臂：标记缺席 ⇒ 同一夹具下 run 不短路（走到闸 2 得 Refused，非 Paused）"):
    withFixture { fx =>
      assert(!MemoryTrack.isPaused, "夹具未创建标记")
      // 造「再 append 一条必超硬顶」的 User.md ⇒ 闸 2 拒绝（同一形态见 MemoryTrackSpec）
      val hard = MemoryBudget.UserHardBytes
      os.write.over(
        MemoryStore.userMemoryPath,
        "# U\n\n## Dream Extract\n\n- " + ("y" * (hard - 40).toInt) + "\n",
        createFolders = true)
      val before = sha(fx.userMd)

      val r = MemoryTrack.run(mkResources(), parentSessionId = Some("s"), parentDepth = 0).unsafeRunSync()

      assertEquals(r.status, MemoryTrack.Status.Refused, s"标记缺席 ⇒ 不短路、继续执行到前置闸: $r")
      assert(r.status != MemoryTrack.Status.Paused, "暂停态只能由标记产生（判据非恒真）")
      assertEquals(sha(fx.userMd), before, "拒绝路径同样零文件写")
    }

end MemoryTrackPauseSpec
