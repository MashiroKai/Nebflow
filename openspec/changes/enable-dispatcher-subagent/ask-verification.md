# Ask Bash and GitQuery retirement verification

## Scope

The user explicitly requested Bash for Ask and removal of its former Git-related tool. Ask now has an independent eight-tool allowlist: message_record_search, send_message_to_main_chat, Read, Glob, Grep, WebSearch, WebFetch and Bash. Only the two messaging tools are Ask-exclusive. The Ask-only GitQuery implementation, registration and dedicated test suite were removed; negative assertions ensure it is no longer registered.

Ask's initial shell directory is derived from its registered bound file's parent directory using the existing sessionCwd channel. Non-Ask spawn behavior is unchanged. Missing services, missing bindings and invalid bound files fail explicitly. Existing Subagent Bash and dispatcher behavior are retained.

Branch: `feat/dispatcher-subagent`; baseline: `4529e84e0d0bff0633da683361785c2b8296d76c`. Unrelated working edits preserved. No commits, live configuration, deployment or protected-host restart.

## Executed evidence

Scala commands used explicit Temurin JDK 23.0.1, a private HOME/user.home, bounded foreground execution and per-command logs.

- PASS, exit 0: `sbt -batch compile`.
- PASS, exit 0: `sbt -batch 'testOnly nebflow.core.ask.AskToolFaceSpec nebflow.core.ask.AskBashExecutionSpec nebflow.agent.DualModeToolFaceSpec nebflow.agent.AllowedToolSetSpec nebflow.core.tools.BlockingSubagentEndToEndSpec nebflow.core.delegate.KernelChildSpawnGateSpec nebflow.core.tools.DualModeBriefInjectionSpec'` — 109 tests passed, zero failures/errors. Rerun after exact-path formatting.
- PASS: supported scalafmt exact-path invocation over six affected production files and three test files. The formatter changed one production file; the final compile and 109-test run followed it. The new untracked test file was not processed by the git-aware formatter's normal file enumeration.
- FAIL, exit 1: `sbt -batch scalafmtCheckAll` — repository-wide formatting/parser blockers including the existing PerceptionService and PerceptionDaemonLifecycleSpec expressions.
- FAIL, exit 1: `sbt -batch 'scalafix --check'` — repository-wide pre-existing import/parser/linter findings. No baselines weakened.
- NOT COMPLETE, exit 124: `sbt -batch test`, bounded to 120 seconds. Observed failures were PluginRemoveSpec's null seed-resource URL and SeedPluginDeletionMarkerSpec's trust-record idempotency assertion. No complete totals are claimed. Only the owned verification process group was stopped at the deadline.
- PASS: `npm run check:sdd` (links only, no explicit-base diff coverage), pinned OpenSpec 2.0.0 strict validation, prompt single-source check and `git diff --check` are recorded separately from runtime behavior.

The first new-test compile attempt failed on an unavailable os-lib helper and omitted AgentActor depth; both fixture errors were corrected. A later fixture incorrectly used raw actor-system stop and skipped the application's cleanup protocol. It now sends the supported AgentCommand.Stop and observes shell cleanup. Production lifecycle code was not changed to hide the fixture error.

## Observable acceptance

An actual depth-zero Ask actor with a scripted LLM invokes normal Bash to print its working directory and a marker. The test verifies the exact eight-tool model face, successful tool result containing the bound file directory and marker, returned answer text, normal shell creation and release through the supported stop protocol. Additional tests cover invalid/missing bindings, non-Ask cwd preservation, GitQuery absence, shared Bash retention and existing Subagent execution/cleanup.

This is not live provider/UI or deployed-gateway verification. The cwd helper used by the gateway is exercised, but no HTTP/WebSocket server was started. Overall full-repository acceptance remains blocked by the incomplete full suite and global style/lint failures.

## Wording review

Ask context previously listed GitQuery and stated that it had no write/execution tools. The updated tool section advertises normal Bash for shell/file/Git investigation and states its initial bound-file directory. The main-session forwarding tool remains explicit-user-request-only. Its description now calls it the sole messaging channel, not the sole outbound action. Wording remains a source draft pending author review; runtime synchronization is pending.
