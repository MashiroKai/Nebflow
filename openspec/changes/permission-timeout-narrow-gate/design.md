# Design

This change carries only the approval-trigger narrowing; the unbounded-wait debt is closed by the
separately landed permission-landing bound, whose implementation this change adopts instead of
re-implementing a second wait seam. The trigger is keyed on a risk class rather than on a file name
appearing in the command text: `src/main/scala/nebflow/core/tools/CommandRisk.scala`
(`classify` / `isRisky`) judges an opaque command by the operation it performs — write, delete,
network egress or credential access — and
`src/main/scala/nebflow/core/tools/AgentInstructionWriteGate.scala`
(`commandTargetsInstructionFile`) conjoins that risk class with the target mention, so the direct
Write/Edit/MultiEdit file-path gate is unchanged and an opaque command is blocked only when target
and risk hold together. `scripts/check-permission-gate.mjs` (static profile) fails if a document
file-name literal (`.md`) reappears in the classifier, if a read-only command whitelist reappears in
the trigger path, if a function judging an opaque `command: String` does not consult the risk
classifier, or if the permission wait loses its timeout or configurable key. The deadline itself is
`AgentCore.awaitPermissionDecisionBounded` / `awaitMcpPermissionDecisionBounded`, bounded by
`timeoutTo` and configurable through `NEBFLOW_PERMISSION_WAIT_TIMEOUT_SEC`; expiry is fail-closed
(`TimedOut` ⇒ deny, a readable tool result and an exact-card `CloseRequest`). Known limitations: the
gate is not a shell parser — quoted inert text can still produce false positives, variable-concatenated
names and nested `sh -c` can bypass, redirection is a heuristic, and credential locations are a fixed
class list.
