package nebflow.core.tools

import cats.effect.{IO, Ref}
import munit.CatsEffectSuite
import nebflow.actor.{AgentKind, AgentRecord, AgentStatus}
import nebflow.core.AgentRegistryPort
import nebflow.core.delegate.DelegateRegistry
import nebflow.shared.PathUtil

import scala.concurrent.duration.*

/**
 * D12-b spec（delegate reset 零闸治理，作者裁定 2026-10-23）：reset 系命令审计
 * 事件（[[ResetAudit]]）+ delegate brief 警示行（[[DelegateTool.ResetHazardLine]]）。
 *
 * 背景面：auto-all 顶档下危险命令清单是死信（`ToolReversibility` AutoAll ⇒
 * true），就地腿 delegate 与他方共享检出区——本 spec 承重「每次 reset 落审计
 * 事件（谁/何时/命令/受波及 live delegate 及其当时状态/任务）」的写入本体与
 * 「brief 头部警示行（未持久化工作会丢，已落盘工作不受影响）」的装配位形。
 */
class ResetAuditSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 30.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-reset-audit"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot)

  override def afterAll(): Unit = PathUtil.setDataRoot(originalRoot)

  /** 既有审计面（AgentControlTool 同族）：`nebflow.audit` logger 名钉。 */
  test("logger face: the audit lands on the existing nebflow.audit logger (AgentControlTool family)") {
    assertEquals(ResetAudit.LoggerName, "nebflow.audit")
  }

  // ── reset 系命令判别 ─────────────────────────────────────────

  test("isResetCommand: the report-D12 four forms match, with real-world flag variants") {
    val hits = List(
      "git reset --hard",
      "git reset --hard HEAD~1",
      "cd /repo && git reset --hard origin/main",
      "git checkout -- .",
      "git checkout .",
      "git clean -f",
      "git clean -fd",
      "git clean -fdx build/",
      "git branch -D feat/x",
      "git branch -D main"
    )
    hits.foreach(c => assert(ResetAudit.isResetCommand(c), s"must match: $c"))
  }

  test("isResetCommand: safe git commands do not match") {
    val misses = List(
      "git status",
      "git reset",
      "git reset --soft HEAD~1",
      "git checkout main",
      "git checkout -b feat/x",
      "git clean -n",
      "git branch -d merged-branch",
      "git branch -D",
      "ls && git log --oneline -3"
    )
    misses.foreach(c => assert(!ResetAudit.isResetCommand(c), s"must NOT match: $c"))
  }

  test("isResetCommand: the FixedDangerousPatterns git-segment gap is shared, not unilaterally narrowed") {
    // `git -C <path> reset --hard` 在 BashTool 闸面（FixedDangerousPatterns）同样
    // 匹配不到——审计面不单方面收紧这个同隙（扩面另批，两面对账口径一致）。
    // 审计面对闸面的两处已知出入（见 ResetAudit.ResetPatterns 注）：裸 `checkout .`
    // 审计面覆盖（报告点名形态）、`branch -D` 要求带分支名。
    assert(!ResetAudit.isResetCommand("git -C /other/repo reset --hard"))
  }

  // ── 同检出区判定（纯函数） ───────────────────────────────────

  test("sharesCheckout: same dir / nested either way count; disjoint trees do not") {
    assert(ResetAudit.sharesCheckout("/ws", "/ws", None))
    assert(ResetAudit.sharesCheckout("/ws", "/ws/sub/seat", None))
    assert(ResetAudit.sharesCheckout("/ws/sub/seat", "/ws", None))
    assert(!ResetAudit.sharesCheckout("/ws", "/other", None))
    assert(!ResetAudit.sharesCheckout("/ws", "", None))
    assert(!ResetAudit.sharesCheckout("", "/ws", None))
  }

  test("sharesCheckout: a worktree-seated delegate is insulated unless the caller sits inside that worktree") {
    val wt = Some("/ws/.nebflow/worktrees/delegate-x")
    // 主检出区 reset 不进链接 worktree（git 隔离语义）——worktree 座位不在场。
    assert(!ResetAudit.sharesCheckout("/ws", "/ws/.nebflow/worktrees/delegate-x", wt))
    // 调用者坐进同一 worktree ⇒ 波及。
    assert(ResetAudit.sharesCheckout("/ws/.nebflow/worktrees/delegate-x/src", "/ws/.nebflow/worktrees/delegate-x", wt))
    // 反斜杠写法归一后同判（跨设备座位可比）。
    assert(ResetAudit.sharesCheckout("C:\\ws", "C:/ws", None))
  }

  // ── 目标解析（纯函数） ───────────────────────────────────────

  private def seat(id: String, cwd: String, worktree: Option[String] = None, title: String = "t") =
    ResetAudit.DelegateSeat(id, cwd, worktree, title)

  test("affectedTargets: live + co-checkout + not-self, sorted by id; unknown seat resolves none") {
    val delegates = List(
      seat("delegate-b", "/ws"),
      seat("delegate-a", "/ws"),
      seat("delegate-dead", "/ws"), // 不 live
      seat("delegate-self", "/ws"), // 调用者自身
      seat("delegate-wt", "/ws/.nebflow/worktrees/delegate-wt", Some("/ws/.nebflow/worktrees/delegate-wt")),
      seat("delegate-elsewhere", "/other")
    )
    val live = Set("delegate-a", "delegate-b", "delegate-wt", "delegate-elsewhere")
    val got = ResetAudit.affectedTargets(Some("/ws"), "delegate-self", delegates, live)
    assertEquals(got.map(_.id), List("delegate-a", "delegate-b"))
    assertEquals(ResetAudit.affectedTargets(None, "delegate-self", delegates, live), Nil)
  }

  // ── 审计行组装（纯函数） ─────────────────────────────────────

  test("line: carries who/when/command/cwd and the target's id/status/task; no target = target=none") {
    val l1 = ResetAudit.line(
      command = "git reset --hard",
      callerSid = "root-1",
      callerAgent = "Nebula",
      seat = Some("/ws"),
      ts = 1700000000000L,
      target = Some((seat("delegate-a", "/ws", title = "harden the bind flow"), Some(AgentStatus.Processing)))
    )
    assert(l1.startsWith("AUDIT ts=1700000000000 event=git-reset "), s"header fields: $l1")
    assert(l1.contains("caller=root-1"), s"who: $l1")
    assert(l1.contains("callerAgent=\"Nebula\""), s"agent: $l1")
    assert(l1.contains("command=\"git reset --hard\""), s"command: $l1")
    assert(l1.contains("cwd=\"/ws\""), s"cwd: $l1")
    assert(l1.contains("target=delegate-a"), s"target id: $l1")
    assert(l1.contains("targetKind=Delegate"), s"target kind: $l1")
    assert(l1.contains("targetStatus=Processing"), s"target status: $l1")
    assert(l1.contains("targetTask=\"harden the bind flow\""), s"target task: $l1")

    val l2 = ResetAudit.line("git clean -fd", "root-1", "Nebula", None, 1L, target = None)
    assert(l2.contains("target=none"), s"zero-target line keeps the reset itself on record: $l2")
    assert(l2.contains("cwd=\"-\""), s"unknown seat renders as -: $l2")
  }

  test("line: quotes are escaped, values wrapped; multi-line command takes the first line only") {
    val l = ResetAudit.line(
      command = "git reset --hard # \"drop\" it\nsecond line",
      callerSid = "r",
      callerAgent = "A\"B",
      seat = None,
      ts = 1L,
      target = None
    )
    assert(!l.contains("\n"), s"single line: $l")
    // 首行截断契约：第二行不进行（excerpt 只取首行）。
    assert(l.contains("command=\"git reset --hard # \\\"drop\\\" it\""), s"escaped first line: $l")
    assert(!l.contains("second line"), s"second line must not leak into the excerpt: $l")
    assert(l.contains("callerAgent=\"A\\\"B\""), s"agent escaped: $l")
  }

  // ── 审计事件写入（record → emit 本体） ───────────────────────

  private def mkPort(live: (String, AgentKind, AgentStatus)*): AgentRegistryPort =
    val entries: Map[String, AgentRecord] = live.map { case (id, kind, status) =>
      id -> AgentRecord(sessionId = id, ref = null, kind = kind, rootSessionId = "root", status = status)
    }.toMap
    val reg: cats.effect.Ref[IO, Map[String, AgentRecord]] = Ref.unsafe(entries)
    new AgentRegistryPort:
      def processingSessionIds: IO[List[String]] = IO.pure(Nil)
      def agentRegistry = reg

  private def seedDelegate(id: String, cwd: String, worktree: Option[String] = None, title: String = "t"): Unit =
    DelegateRegistry.put(
      DelegateRegistry.Record(
        id = id,
        executor = "nebflow",
        cwd = cwd,
        project = None,
        taskId = None,
        parentSessionId = "nebula-root",
        title = title,
        createdAt = 1L,
        worktree = worktree
      )
    )

  private def auditCtx(sid: String, seatPath: Option[String]): ToolContext =
    // runtime 端口经 record 的显式参（BashTool 调用点传 ctx.sharedResources——
    // AgentRuntimePort <: AgentRegistryPort 子类型代入）传入，ctx 不再重复承载。
    ToolContext(projectRoot = tempRoot.toString, sessionId = Some(sid), sessionCwd = seatPath)

  /** 收集式 emit（spec 注入面；生产缺省 = nebflow.audit logger.info）。 */
  private final class Collector:
    private val ref: cats.effect.Ref[IO, List[String]] = Ref.unsafe(Nil)
    def append: String => IO[Unit] = s => ref.update(_ :+ s)
    def lines: IO[List[String]] = ref.get

  test("record: a reset writes one line per affected live delegate with its status/task (the write itself)") {
    os.remove.all(tempRoot / "delegates") // 每例独立盘面
    seedDelegate("delegate-a", "/ws", title = "bind flow")
    seedDelegate("delegate-wt", "/ws/.nebflow/worktrees/delegate-wt", Some("/ws/.nebflow/worktrees/delegate-wt"))
    seedDelegate("delegate-elsewhere", "/other")

    val port = mkPort(
      ("delegate-a", AgentKind.Delegate, AgentStatus.Processing),
      ("delegate-wt", AgentKind.Delegate, AgentStatus.Idle),
      ("root-1", AgentKind.Root, AgentStatus.Processing) // 非 Delegate kind ⇒ 不入目标
    )
    val out = Collector()
    for _ <- ResetAudit.record("git reset --hard", auditCtx("root-1", Some("/ws")), Some(port), ts = 42L, emit = out.append)
    yield
      val lines = out.lines.unsafeRunSync()(using munitIORuntime)
      assertEquals(lines.length, 1, s"one co-checkout target: $lines")
      val l = lines.head
      assert(l.contains("event=git-reset") && l.contains("caller=root-1"), s"who/what: $l")
      assert(l.contains("ts=42"), s"when: $l")
      assert(l.contains("target=delegate-a"), s"affected id: $l")
      assert(l.contains("targetStatus=Processing"), s"status at reset time: $l")
      assert(l.contains("targetTask=\"bind flow\""), s"running task: $l")
      assert(!l.contains("delegate-wt") && !l.contains("delegate-elsewhere"), s"insulated/distant seats excluded: $l")
  }

  test("record: zero targets still writes one target=none line (the reset itself is never traceless)") {
    os.remove.all(tempRoot / "delegates")
    val port = mkPort()
    val out = Collector()
    for _ <- ResetAudit.record("git clean -fdx", auditCtx("root-1", Some("/ws")), Some(port), ts = 7L, emit = out.append)
    yield
      val lines = out.lines.unsafeRunSync()(using munitIORuntime)
      assertEquals(lines.length, 1)
      assert(lines.head.contains("target=none"), s"traceless is forbidden: $lines")
  }

  test("record: no runtime (REST/harness context) degrades to the plain who/when/command line") {
    val out = Collector()
    for _ <- ResetAudit.record("git branch -D x", auditCtx("solo", None), None, ts = 9L, emit = out.append)
    yield
      val lines = out.lines.unsafeRunSync()(using munitIORuntime)
      assertEquals(lines.length, 1)
      assert(lines.head.contains("caller=solo") && lines.head.contains("target=none"), s"${lines.head}")
  }

  test("record: a failing emit never propagates (command execution is never gated by the audit)") {
    val port = mkPort()
    val boom: String => IO[Unit] = _ => IO.raiseError(new RuntimeException("disk full"))
    // 不 raise = 失败语义落地（测试本体即完成断言）。
    ResetAudit.record("git reset --hard", auditCtx("root-1", Some("/ws")), Some(port), emit = boom)
  }

  // ── 派发面警示行（brief 头部） ───────────────────────────────

  test("brief hazard line: assembled into the delegate brief header, between workspace and report discipline") {
    val brief = DelegateTool.assembleBrief(
      workspaceLine = "[workspace] /tmp/ws",
      bindingLine = None,
      task = "Do the thing.",
      injectedBlock = ""
    )
    assert(brief.contains(DelegateTool.ResetHazardLine), s"hazard line must be in the brief: $brief")
    val wsAt = brief.indexOf("[workspace]")
    val hazardAt = brief.indexOf("[reset hazard]")
    val reportAt = brief.indexOf("[report]")
    assert(wsAt < hazardAt && hazardAt < reportAt, s"header order: $brief")
    // 警示行两要素（作者裁定 b 方案第 2 条）：未持久化工作会丢；已落盘工作不受影响。
    assert(DelegateTool.ResetHazardLine.contains("unpersisted in-progress work"), "loss clause")
    assert(DelegateTool.ResetHazardLine.contains("persisted to disk is unaffected"), "unaffected clause")
    assert(DelegateTool.ResetHazardLine.contains("audit log"), "audited clause")
  }
end ResetAuditSpec
