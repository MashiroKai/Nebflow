# Subagent Bash verification

This is historical evidence for the Subagent Bash step. The user's subsequent request gives Ask its own Bash capability and removes GitQuery; the older Ask exclusion assertions below are superseded, not current acceptance evidence.

## Scope and result

Branch `feat/dispatcher-subagent`, baseline `4529e84e0d0bff0633da683361785c2b8296d76c`, with unrelated working changes preserved. Normal Bash is now the sixth fixed Subagent tool. Ask retains its independent eight-tool face, without Bash. Kernel still admits only the exact builtin Subagent child. No commit, deployment or host restart was performed; prompt wording remains a draft awaiting author review.

Bash is not a read-only sandbox. The role asks for foreground reconnaissance and complete findings before return; background-job lifecycle behavior was not expanded. The child now inherits `ctx.sessionCwd`, falling back to `ctx.projectRoot`, through the existing spawn parameter. A real shell test first caught the missing cwd inheritance, then passed after this fix.

## Executed checks

All Scala commands used isolated HOME/user.home and explicit Temurin JDK 23.0.1, with bounded foreground execution and logs. No gateway was started.

- PASS: `sbt -batch compile`.
- PASS: `sbt -batch 'testOnly nebflow.agent.DualModeToolFaceSpec nebflow.agent.DualModeGrayReleaseSpec nebflow.core.tools.DualModeBriefInjectionSpec nebflow.core.tools.BlockingSubagentEndToEndSpec nebflow.core.tools.BlockingSubagentStartGuardSpec nebflow.core.delegate.KernelChildSpawnGateSpec nebflow.core.ask.AskToolFaceSpec'` — 47 passed, zero failures/errors.
- PASS: `sbt -batch 'testOnly nebflow.core.tools.DelegateToolSpec -- --tests *subagent*'` — one passed. The two commands above were rerun after exact-path formatting; 48 relevant tests passed on the final source.
- PASS: repository scalafmt applied only to the 12 affected production Scala files and six affected Scala test files. Seven files required formatting. Compile and targeted tests passed afterward.
- PASS: `node scripts/check-prompt-single-source.mjs`, 18 rules. Its Subagent opening-sentence anchor was updated to the new canonical wording; the uniqueness rule is unchanged.
- FAIL: full `DelegateToolSpec` run has two unrelated assertions expecting removed `worktree`/`force` parameters and `AskUserQuestion` instead of current `AskHost`. These remain unchanged; the Subagent case passes. The initial targeted run also exposed a new test-package visibility error, which was fixed without changing production visibility.
- NOT COMPLETE: `sbt -batch test` stopped at its 120-second bound with exit 124; observed failures include `PluginRemoveSpec` (missing resource URL) and `SeedPluginDeletionMarkerSpec` (trust-record idempotency). No final full-suite totals are claimed. Only the owned verification process group was stopped.
- FAIL: `sbt -batch scalafmtCheckAll` has repository-wide formatting/parser failures, including baseline expressions in `PerceptionService.scala:1211` and `PerceptionDaemonLifecycleSpec.scala`.
- FAIL: `sbt -batch 'scalafix --check'` has repository-wide import/parser/linter failures. No baseline was weakened and no unrelated cleanup performed.
- Structural checks: `npm run check:sdd` and the pinned OpenSpec 2.0.0 strict validator are run separately from behavioral tests. SDD without `--base` checks links, not changed-file coverage.

Raw per-step logs and source hash evidence are retained under the private temporary verification directory `stand-dispatcher-verify-m4cmwlq2`. The initial five-tool report in `verification.md` is historical, not evidence for Bash.

## Acceptance evidence and limits

The scripted child calls actual Bash with a safe `printf`, receives the tool output, and returns it through Subagent. Assertions cover the isolated working directory, exactly six advertised child tools, completed task timestamp, and actor/registry/shell cleanup. Ask is checked both as a closed set and through effective tool-face assembly. Kernel executor/custom-child rejection remains tested.

This does not prove live model selection, browser behavior, deployment, explicit-sessionCwd precedence, or background Bash completion. Overall repository acceptance remains blocked by full-suite/style/lint failures and pending author wording review.

## Wording review

Before: Subagent was described as mechanically read-only with five tools and no shell.

After: the canonical role and tool description list `Read / Glob / Grep / WebSearch / WebFetch / Bash`, explain that Bash uses normal execution/permissions rather than a read-only sandbox, and direct foreground evidence gathering before a final answer. Shared delegate wording follows the caller's role rules; only dispatcher-specific guidance requires task execution through chain nodes. No Write/Edit, messaging or recursive agent tools were added.
