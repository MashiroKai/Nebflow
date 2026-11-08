package nebflow.core.delegate

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*

class WorkspaceLedgerSpec extends munit.FunSuite:
  import WorkspaceLedger.*
  given ExecutionContext = ExecutionContext.global

  private def withLedger(f: WorkspaceLedger => Unit): Unit =
    val dir = os.temp.dir(prefix = "workspace-ledger-")
    try f(new WorkspaceLedger(dir / "ledger.json"))
    finally os.remove.all(dir)

  private def workspace(id: String) =
    Workspace(id, "project", "repo", s"/tmp/$id", s"delegate/$id", "main", "base", s"owner-$id", "kernel")

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

  test("16 concurrent development admissions and merge reservations resolve to exactly one winner with clear losers") {
    withLedger { ledger =>
      ledger.register(workspace("a"))
      ledger.merger("project", "repo", "merger")
      val request =
        MergeRequest("race", "repo", "merger", "user authorized the batch", List(MergeItem("a", "0" * 40, "main")))
      val contenders = 16
      val start = new java.util.concurrent.CountDownLatch(contenders)
      // Dedicated pool: the barrier requires every contender to run CONCURRENTLY —
      // the fork-join global pool would self-deadlock on blocking latch awaits.
      val pool = java.util.concurrent.Executors.newFixedThreadPool(contenders)
      val ec = ExecutionContext.fromExecutor(pool)
      val outcomes =
        (0 until contenders).map { i =>
          Future {
            start.countDown()
            // Maximize contention: every contender fires at the barrier at once.
            assert(start.await(30, java.util.concurrent.TimeUnit.SECONDS), "barrier timeout")
            scala.util.Try {
              val attempt =
                if i % 2 == 0 then ledger.admit("owner-a", false).map(_ => "admit")
                else ledger.requestMerge(request).map(_ => "merge")
              i -> attempt
            }
          }(ec)
        }
      val results = Await.result(Future.sequence(outcomes), 60.seconds)
      pool.shutdown()
      val finalWorkspace = ledger.snapshot.workspaces.find(_.id == "a").get
      // No exceptions may escape the file-locked transactions.
      results.foreach(r => assertEquals(r.isSuccess, true, s"a contender threw: ${r.failed.map(_.getMessage)}"))
      // Final consistency: development won (running, open) XOR merge won (reserved) — never both.
      val developmentWon = finalWorkspace.activity == "running" && finalWorkspace.lifecycle == "open"
      val mergeWon = finalWorkspace.lifecycle == "reserved" && finalWorkspace.reservation.contains("race")
      assert(clue(developmentWon) != clue(mergeWon), s"exactly one side must win, got $finalWorkspace")
      val (admits, merges) = results.map(_.toOption.get).partition(_._1 % 2 == 0)
      if developmentWon then
        merges.foreach { case (_, outcome) =>
          assert(outcome.isLeft, s"a merge reservation must lose once development is running: $outcome")
        }
        admits.foreach { case (_, outcome) =>
          assert(outcome.isRight, s"admission against a running development claim is a legal no-op: $outcome")
        }
      else
        admits.foreach { case (_, outcome) =>
          assert(
            outcome.left.exists(_.contains("reserved")),
            s"admission against a merge-reserved workspace must lose with a clear reason: $outcome"
          )
        }
        merges.foreach { case (_, outcome) =>
          assert(outcome.isRight, s"merge reservations are idempotent for the identical request: $outcome")
        }
      end if
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

  test("legacy ledger gains default baseline without losing ownership") {
    withLedger { ledger =>
      os.write.over(ledger.path, "{\"workspaces\":[],\"mergers\":[]}", createFolders = true)
      assertEquals(ledger.baseline("repo"), "main")
      assert(ledger.setBaseline("repo", "release").isRight)
      assertEquals(new WorkspaceLedger(ledger.path).baseline("repo"), "release")
    }
  }
end WorkspaceLedgerSpec
