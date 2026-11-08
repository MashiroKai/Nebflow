package nebflow.core.delegate

import nebflow.shared.PathUtil
import cats.effect.unsafe.implicits.global

class ManagedWorkspaceLifecycleSpec extends munit.FunSuite:
  private val previous = PathUtil.dataRoot
  private var root: os.Path = os.pwd

  override def beforeAll(): Unit =
    root = os.temp.dir(prefix = "managed-workspace-")
    PathUtil.setDataRoot(root)

  override def afterAll(): Unit =
    PathUtil.setDataRoot(previous)
    os.remove.all(root)

  test("open worktree survives saved-state TTL and reservation blocks owner admission") {
    val record = DelegateRegistry.Record(
      "owner",
      "nebflow",
      "/unused",
      Some("project"),
      None,
      "parent",
      "task",
      1L,
      worktree = Some("/unused"),
      standbySince = Some(1L)
    )
    DelegateRegistry.put(record)
    val ledger = WorkspaceLedger.default
    val workspace = WorkspaceLedger.Workspace(
      "work",
      "project",
      "repo",
      "/unused",
      "delegate/work",
      "main",
      "base",
      "owner",
      "kernel",
      activity = "running"
    )
    assert(ledger.register(workspace).isRight)
    assert(DelegateRegistry.parkStandby("owner").isRight)
    assertEquals(ledger.owned("owner").get.activity, "idle")
    assert(!DelegateTerminalTtl.verdict(record, None, Long.MaxValue).isExpired)
    assert(ledger.reserve("merge", List("work")).isRight)
    assert(ledger.admit("owner", allowFrozen = true).isLeft)
  }

  test("freeze retains execution state until explicit recovery") {
    val ledger = WorkspaceLedger.default
    val record = DelegateRegistry.Record(
      "frozen-owner",
      "nebflow",
      "/unused-frozen",
      None,
      None,
      "parent",
      "task",
      1L,
      worktree = Some("/unused-frozen")
    )
    DelegateRegistry.put(record)
    ledger.register(
      WorkspaceLedger.Workspace(
        "frozen-work",
        "project",
        "repo",
        "/unused-frozen",
        "delegate/frozen",
        "main",
        "base",
        record.id,
        "kernel",
        activity = "running"
      )
    )
    assert(DelegateRegistry.parkFrozen(record.id, "delegate-frozen:llm-failure", "HTTP 503").isRight)
    assertEquals(ledger.owned(record.id).get.activity, "frozen")
    assert(ledger.admit(record.id, allowFrozen = false).isLeft)
    assert(ledger.admit(record.id, allowFrozen = true).isRight)
  }

  test("reserved workspace rejects NodeEdit and ChainWrite at their mutation entrances") {
    val ledger = WorkspaceLedger.default
    val owner = "delegate-admission-owner"
    ledger.register(
      WorkspaceLedger.Workspace(
        "node-admission",
        "project",
        "repo",
        "/unused-node-admission",
        "delegate/node-admission",
        "main",
        "base",
        owner,
        "dispatcher"
      )
    )
    assert(ledger.reserve("node-reservation", List("node-admission")).isRight)
    val ctx = nebflow.core.tools.ToolContext(projectRoot = root.toString, sessionId = Some(owner))
    assert(nebflow.core.tools.NodeEditTool.call(io.circe.JsonObject.empty, ctx).unsafeRunSync().isLeft)
    assert(nebflow.core.tools.ChainWriteTool.call(io.circe.JsonObject.empty, ctx).unsafeRunSync().isLeft)
  }
end ManagedWorkspaceLifecycleSpec
