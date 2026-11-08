package nebflow.core.delegate

class WorkspaceLedgerSpec extends munit.FunSuite:
  import WorkspaceLedger.*
  private def withLedger(f: WorkspaceLedger => Unit): Unit =
    val dir = os.temp.dir(prefix = "workspace-ledger-")
    try f(new WorkspaceLedger(dir / "ledger.json"))
    finally os.remove.all(dir)
  private def workspace(id: String) = Workspace(id, "project", "repo", s"/tmp/$id", s"delegate/$id", "main", "base", s"owner-$id", "kernel")

  test("busy workspace rejects entire batch; reservation excludes development and persists") {
    withLedger { ledger =>
      ledger.register(workspace("a")); ledger.register(workspace("b"))
      val running = ledger.claimDevelopment("a", "owner-a", 0).toOption.get
      assert(ledger.reserve("request", List("a", "b")).isLeft)
      assertEquals(ledger.snapshot.workspaces.find(_.id == "b").get.lifecycle, "open")
      ledger.releaseDevelopment("a", "owner-a", running.revision, false)
      assert(ledger.reserve("request", List("a", "b")).isRight)
      val reopened = new WorkspaceLedger(ledger.path)
      assert(reopened.claimDevelopment("b", "owner-b", 1).isLeft)
      assertEquals(reopened.reserve("request", List("a", "b")).toOption.get.size, 2)
    }
  }
  test("merger identity is shared across repository aliases") {
    withLedger { ledger =>
      val first = ledger.merger("project", "repo", "merger-1")
      assertEquals(ledger.merger("alias", "repo", "merger-2"), first)
      assertEquals(new WorkspaceLedger(ledger.path).merger("project", "repo", "merger-3"), first)
    }
  }
  test("ownership, stale writes and corrupt state cannot bypass admission") {
    withLedger { ledger =>
      ledger.register(workspace("a"))
      assert(ledger.claimDevelopment("a", "other", 0).isLeft)
      ledger.claimDevelopment("a", "owner-a", 0)
      assert(ledger.releaseDevelopment("a", "owner-a", 0, false).isLeft)
      os.write.over(ledger.path, "broken")
      intercept[IllegalStateException](ledger.reserve("request", List("a")))
    }
  }
