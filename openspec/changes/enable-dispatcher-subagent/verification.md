# Verification evidence

This record describes the initial five-tool dispatcher change before the user's follow-up Bash request. It is historical evidence, not acceptance of the six-tool Subagent. Current Bash verification is recorded in `bash-verification.md`.

## Scope

- Branch: `feat/dispatcher-subagent`.
- Baseline: `4529e84e0d0bff0633da683361785c2b8296d76c`.
- Scope: modern `Delegate(mode="dispatcher")`, three production files and four test files. The legacy `project-dispatcher` is unchanged.
- Existing unrelated working changes were preserved. No commit, merge, deployment or host restart was performed.
- Scala checks explicitly used Temurin JDK 23.0.1 with a private HOME and `-Duser.home`. No gateway was launched. Commands were foreground, logged, heartbeating and timeout-bounded.

## Results

| Check | Outcome |
| --- | --- |
| `sbt -batch compile` | PASS, exit 0. |
| `sbt -batch 'testOnly nebflow.agent.DualModeToolFaceSpec nebflow.agent.DualModeGrayReleaseSpec nebflow.core.tools.DualModeBriefInjectionSpec nebflow.core.tools.BlockingSubagentEndToEndSpec nebflow.core.tools.BlockingSubagentStartGuardSpec nebflow.core.delegate.KernelChildSpawnGateSpec'` | PASS, 39 tests, 0 failures/errors. |
| `sbt -batch test` | NOT COMPLETE, exit 124 at the 300-second limit. At least 1,332 successful test lines and three failure markers were observed; these are partial observations, not final suite totals. |
| `sbt -batch scalafmtCheckAll` | FAIL, exit 1. 508 formatting warnings and parser failures, including `PerceptionService.scala:1211` and `PerceptionDaemonLifecycleSpec.scala`; both parser-failing source expressions were confirmed in the baseline. |
| `sbt -batch 'scalafix --check'` | FAIL, exit 1. Repository-wide import/rule/parser findings; 240 proposed file fixes. The finding on `AgentSessionExecution.scala` concerns unchanged import ordering. No whole-tree cleanup was attempted. |
| `sbt -batch 'scalafmtOnly src/main/scala/nebflow/agent/AgentSessionExecution.scala src/main/scala/nebflow/core/tools/SubagentTool.scala src/main/scala/nebflow/core/tools/DelegateTool.scala' 'Test / scalafmtOnly src/test/scala/nebflow/agent/DualModeToolFaceSpec.scala src/test/scala/nebflow/agent/DualModeGrayReleaseSpec.scala src/test/scala/nebflow/core/tools/DualModeBriefInjectionSpec.scala src/test/scala/nebflow/core/tools/BlockingSubagentEndToEndSpec.scala'` | PASS, exit 0. Formatter processed exactly seven files; before/after SHA-256 values were identical, so focused test evidence remains applicable. |
| `npm run check:sdd` | PASS, exit 0. Link validation only; no explicit-base diff coverage. |
| `npm run check:openspec -- --binary /tmp/stand-openspec-2.0.0/openspec` | PASS, exit 0, OpenSpec 2.0.0 strict validation of seven items. Cached archive SHA-256 matched the pinned toolchain, and executable bytes matched the archive. Initial download attempt timed out; the verified cache avoided a second download. |
| `node scripts/check-prompt-single-source.mjs` | PASS, 18 rules. This gate detects literal copies, not semantic prompt quality. |
| `git diff --check` | PASS. |

The attempted `scalafmtCheckOnly` command is not supported by this build and exited 1. It was replaced with the supported exact-path formatter invocation above; no files changed.

## Full-suite failure observations

- `PluginRemoveSpec`: null resource URL in the seed-plugin reconciliation case.
- `SeedPluginDeletionMarkerSpec`: trust-record idempotency assertion mismatch.
- `NodeMountEnforceSpec`: timed out waiting for its legal-order scenario.

These failures are outside the edited files. Their root causes and baseline reproducibility were not established in this task. The full-suite process group created for verification was stopped at its deadline; protected host processes were not signalled.

## Scenario coverage

- DSA-SCN-001: persisted dispatcher mode exposes Subagent; missing and blank prompts retain normal validation across dispatcher, kernel and ordinary callers.
- DSA-SCN-002: real child actor with scripted LLM returns final text through `SubagentTool.call`; task is completed, child registry entry removed, child actor stops, and parent registry entry remains.
- DSA-SCN-003: the actual child request carries exactly Read/Glob/Grep/WebSearch/WebFetch; kernel spawn restrictions and dispatcher brief separation remain covered.

Overall acceptance is BLOCKED by the incomplete/failing repository-wide gates and pending author review of prompt wording. Focused implementation checks pass. Live provider/UI behavior and deployment were not tested.

## Review follow-up

Focused review found that the shared Subagent description still said "do those yourself" for executable work, contradicting dispatcher discipline. It now says: "Follow your role's execution rules for that work; dispatchers must use chain nodes instead of executing it themselves." The existing F9 regression now checks both the brief and tool description. After this repair, exact-path formatting, compilation and all six focused suites were rerun successfully (39 passed, zero failures/errors). Full-suite and global style/lint results above precede this wording-only repair and remain incomplete/failing evidence, not a final-tree global pass. The integration fixture uses a dummy parent and scripted child LLM; it does not prove model-driven dispatcher tool selection or live UI behavior.

## Prompt wording for author review

Removed:

> Your Subagent tool is gone by design: recon/subtask capacity comes through the chain — a single lookup or subtask is a one-node chain (or a node in the chain you are composing).

Added:

> Reconnaissance: use Subagent for blocking read-only lookups and analysis that inform your orchestration. Give it a self-contained question; it has only Read/Glob/Grep/WebSearch/WebFetch, and its findings return as this tool call's result. It cannot write, execute commands, send messages or spawn children. Use chain nodes for task execution, not Subagent.

The topology sentence no longer mandates a chain node for all read-only analysis; it permits Subagent reconnaissance while retaining chain nodes for writes and execution. The executor override, whole-chain composition, verifier loop and shared-workspace discipline remain. This source wording is a draft, not an adopted or deployed live prompt.
