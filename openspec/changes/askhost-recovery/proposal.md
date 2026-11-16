# AskHost recovery: verification boundary

## Scope

Retain the original AskHost request identity across an interrupted wait, reconcile its durable mailbox entry, and handle repeated identical replies without accepting conflicting decisions. Preserve safety gates for unknown tool outcomes and do not infer request identity from question text.

## Verified separately

- An isolated sbt-forked JVM was killed and restarted against the same isolated data root. The ledger recovered the same correlation ID, the mailbox suppressed re-enqueue, and a synthetic handoff returned the decision. Evidence: `/Users/kaiyu/Claude code/Stand/.stand/tmp/askhost-executor-loop/evidence/crash/seed.log`, `recover.log`, `assertions.txt`.
- A real worker actor issued AskHost; the production `recoverPending` function was invoked in the same test process; the result was read back from the session store. Evidence: `/Users/kaiyu/Claude code/Stand/.stand/tmp/askhost-executor-loop/evidence/real-actor-readback.log`.
- Exact duplicate replies through `AskHostReplyTool` are accepted and a different decision is rejected. Evidence: `/Users/kaiyu/Claude code/Stand/.stand/tmp/askhost-executor-loop/evidence/duplicate-reply-red.log`, `duplicate-reply-green.log`, and `real-actor-readback.log`.

## Unverified acceptance boundary

The combined end-to-end scenario **real actor + production startup scan + process restart** has not been proved by an automated test. The separate results above cannot be combined into that claim. In particular, the process-kill fixture uses a synthetic handoff and does not run the production startup scan; the real-actor test invokes the scan without restarting the process. Further manual observation or an integrated automated test is needed to establish the combined behavior.

Other known gaps are not closed by these tests: recovery entry points are not all equivalent; recovery waits and cancellation require further lifecycle verification; legacy answered and otherwise orphaned requests need reconciliation; and the watchdog currently records only one diagnostic tool slot for parallel calls. The existing crash evidence does not prove exactly-once delivery of a user-visible card or durable result across every crash window.
