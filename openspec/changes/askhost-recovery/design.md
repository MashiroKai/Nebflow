# Design

AskHost carries a durable ledger and a durable mailbox so an interrupted wait does not lose the
request identity. `src/main/scala/nebflow/core/tools/AskHostTools.scala` owns the ledger, journal
replay and mailbox; `src/main/scala/nebflow/agent/AgentCore.scala` owns the wait and the commit of
the tool result. Recovery restores the original correlation id, the mailbox suppresses a
re-enqueue it has already consumed, and a reply is matched to the pending interaction by that id
alone — never by question text, because question text is not an identity. The combined end-to-end
scenario *real actor + production startup scan + process restart* is NOT proved by an automated
test: the crash fixture uses a synthetic handoff and does not run the production startup scan,
while the real-actor test invokes the scan without restarting the process. Those two results cannot
be combined into that claim.
