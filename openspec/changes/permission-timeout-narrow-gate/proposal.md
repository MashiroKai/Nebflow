# Risk-based instruction-file gate (approval trigger narrowing)

## Intent

Two debts from 2026-11-18 (author ruling 07:59 + 08:42): the approval trigger keyed on a
**file name in the command text** was too broad (read-only references were blocked), and
the approval wait had **no timeout** (batches/sessions hung 28 and 58 minutes).

This change carries **only the trigger narrowing**; the unbounded-wait debt is closed by
the separately landed permission-landing bound (see "Permission wait" below), whose
implementation this change adopts instead of re-implementing a second wait seam.

1. Replace the file-name-keyed command trigger with a **risk-class** trigger: an opaque
   command is judged by the operation it performs (write / delete / network egress /
   credential access), never by which file name appears in its text.

Preserved: the human-owned instruction file stays a protected **target** — the direct
Write/Edit/MultiEdit file-path gate is unchanged, and an opaque command is blocked only
when target ∧ risk hold together.

## Criteria (old → new)

| # | Old rule (file:line at the 2026-11-18 baseline) | New rule | Basis |
|---|---|---|---|
| 1 | `AgentInstructionWriteGate.commandTargetsAgentsFile`: `lower.contains("agents.md") && !lower.matches("(?:cat\|less\|more\|head\|tail\|grep\|rg\|test\|stat\|readlink)\\s+[^;&\|<>]*")` — a name alone gated, and a whitelist of read-only command names was the only exemption | risk class from `CommandRisk.isRisky`: write verbs / redirection, delete verbs, network egress, credential paths — conjoined with the target mention | `src/main/scala/nebflow/core/tools/CommandRisk.scala`, `AgentInstructionWriteGate.scala` (`commandTargetsInstructionFile`) |
| 2 | read-only exemption = enumeration of command names (`cat`, `grep`, …) | no enumeration at all: a read-only command simply carries no risk class | `CommandRisk.classify` (empty set ⇒ no approval) |

## Permission wait (adopted from permission-landing, not re-implemented)

The deadline is the one already landed by the permission-landing batch; this change does
**not** add a second wait seam. The surviving primitives are
`AgentCore.awaitPermissionDecisionBounded` / `awaitMcpPermissionDecisionBounded`, bounded
by `timeoutTo` and configurable via `NEBFLOW_PERMISSION_WAIT_TIMEOUT_SEC` (invalid or
non-positive values fall back to the safe default). Expiry is fail-closed: `TimedOut` ⇒
deny + a readable tool result + an exact-card `CloseRequest` (see
`PermissionWaitBoundedSpec`, `check-perm-presentation`). A second `AgentCore` wait
implementation (the branch-local `-Dstand.permission.timeout.seconds` / named default) was
**dropped** in the merge — wiring waits twice is the defect this change must not create.

## Invariants gated in `check:*`

`scripts/check-permission-gate.mjs` (static profile) fails if:
- the risk classifier contains a document file-name literal (`.md`), or
- a read-only command whitelist reappears anywhere in the trigger path, or
- a function judging an opaque `command: String` does not consult the risk classifier, or
- a permission wait has no timeout, or the configurable key / named accessor is gone.

## Acceptance

- Read-only git history/diff/pipelines do not trigger; write/delete/egress/credential do
  (regression samples in `CommandRiskSpec`, `AgentInstructionWriteGateSpec`).
- Risk primitives on other files do not trigger (the gate stays target-scoped).
- The bounded wait (permission-landing) denies at the deadline with a distinct error +
  audit, and closes only its own card; a receipt before the deadline resumes the waiter.
- `CommandRisk` look-alikes (`curling`, `ssh-keygen`, `ls rmfile.txt`) are not risk.

## Known gate limitations (not a shell parser)

- Quoted inert text can still produce false positives; variable-concatenated names and
  nested `sh -c` can bypass. TODO shell-aware tokenization.
- Redirection is a heuristic: `a>b` counts, `2>&1` and `> /dev/null` do not.
- Credential locations are a fixed class list; per-user locations outside it are missed.
- `dangerLevel`/`ToolReversibility` (safety-mode ladder) is a separate surface and is
  unchanged by this change.
