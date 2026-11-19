package nebflow.core.delegate

import munit.FunSuite

/**
 * Compat-read spec for the retired managed-workspace ledger: the engine no
 * longer writes `workspaces/ledger.json`, but the recorded history stays on
 * disk and must keep decoding through the read-only face — workspaces, merger
 * identities, merge requests (with items/events), cleanups and bootstrap
 * seeds. A missing file decodes as an empty state.
 */
class WorkspaceLedgerSpec extends FunSuite:

  test("a missing ledger file decodes as the empty state") {
    val dir = os.temp.dir(prefix = "workspace-ledger-missing-")
    try
      val ledger = new WorkspaceLedger(dir / "ledger.json")
      assertEquals(ledger.snapshot, WorkspaceLedger.State())
      assertEquals(WorkspaceLedger.listInFlightRequests(ledger.snapshot), Nil)
    finally os.remove.all(dir)
  }

  test("a recorded legacy ledger decodes every section through the read-only face") {
    val dir = os.temp.dir(prefix = "workspace-ledger-legacy-")
    try
      val legacy =
        """{
          |  "workspaces": [{
          |    "id": "ws-a", "project": "stand", "repository": "repo-key",
          |    "path": "/tmp/ws-a", "branch": "delegate/ws-a", "baseBranch": "main",
          |    "baseCommit": "0000000000000000000000000000000000000000",
          |    "owner": "delegate-nebflow-aaa", "ownerMode": "kernel",
          |    "lifecycle": "reserved", "activity": "idle",
          |    "reservation": "req-1", "revision": 3
          |  }],
          |  "mergers": [{"project": "stand", "repository": "repo-key", "delegateId": "delegate-nebflow-merg"}],
          |  "baselines": {"repo-key": "main"},
          |  "requests": [{
          |    "id": "req-1", "repository": "repo-key", "mergerId": "delegate-nebflow-merg",
          |    "authorization": "user authorized the batch",
          |    "items": [{"workspaceId": "ws-a", "sourceCommit": "0000000000000000000000000000000000000000",
          |                "targetBranch": "main", "phase": "reserved"}],
          |    "status": "claimed", "revision": 1, "cancelRequested": false,
          |    "events": {"worker:req-1:ws-a:merging": "{}"}
          |  }],
          |  "cleanups": {"ws-a": {"token": "tok", "previousLifecycle": "merged-cleanup-pending",
          |                        "removed": false, "error": null}},
          |  "revision": 7,
          |  "mergerBootstraps": {},
          |  "mergerEvents": {},
          |  "completionConfirmations": {
          |    "op-1": {"operationId": "op-1", "workspaceId": "ws-a", "owner": "delegate-nebflow-aaa",
          |             "expectedPath": "/tmp/ws-a", "expectedHead": "0000", "expectedRevision": 2,
          |             "acceptanceEvidence": "evidence", "explicitRootConfirmation": "root",
          |             "actualBranch": "main", "recordedAt": 1, "stage": "completed", "recoverRunning": false}
          |  }
          |}""".stripMargin
      os.write.over(dir / "ledger.json", legacy)
      val snapshot = new WorkspaceLedger(dir / "ledger.json").snapshot
      assertEquals(snapshot.revision, 7L)
      assertEquals(snapshot.workspaces.map(_.id), List("ws-a"))
      assertEquals(snapshot.workspaces.head.lifecycle, "reserved")
      assertEquals(snapshot.mergers.map(_.delegateId), List("delegate-nebflow-merg"))
      assertEquals(snapshot.baselines.get("repo-key"), Some("main"))
      assertEquals(snapshot.requests.map(_.id), List("req-1"))
      assertEquals(snapshot.requests.head.items.map(_.phase), List("reserved"))
      assertEquals(snapshot.requests.head.events.keys.toList, List("worker:req-1:ws-a:merging"))
      assert(snapshot.cleanups.contains("ws-a"))
      assert(snapshot.completionConfirmations.contains("op-1"))
      // Boot listing: a claimed request stays visible as in-flight backlog
      val lines = WorkspaceLedger.listInFlightRequests(snapshot)
      assertEquals(lines.size, 1)
      assert(lines.head.contains("req-1"))
      assert(lines.head.contains("ws-a:reserved"))
      assert(lines.head.contains("status=claimed"))
      // Terminal requests never list
      val terminal = snapshot.copy(requests = snapshot.requests.map(r => r.copy(status = "merged")))
      assertEquals(WorkspaceLedger.listInFlightRequests(terminal), Nil)
    finally os.remove.all(dir)
    end try
  }

end WorkspaceLedgerSpec
