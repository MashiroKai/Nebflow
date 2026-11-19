## 1. Intent and artifact foundation

- [x] 1.1 Read the approved proposal and design without overwriting them.
- [x] 1.2 Add current engineering-workflow and quality-gates specifications with stable requirement and scenario IDs.
- [x] 1.3 Add matching change deltas, trace mappings, and focused contributor/query instructions.
- [x] 1.4 Declare the initial mechanical diff scope explicitly; do not require historical whole-repository backfill.

## 2. SDD checker

- [x] 2.1 Implement artifact-only validation and explicit-base diff validation with a stable command interface.
- [x] 2.2 Reject malformed/duplicate IDs, missing scenario links, unsafe paths, missing files, and unreasoned empty tests.
- [x] 2.3 Cover renames/deletions and validate archives without requiring historical implementation files.
- [x] 2.4 Keep link checking separate from actual test execution; never execute trace-provided commands.
- [x] 2.5 Run isolated positive and negative node:test fixtures with temporary-directory cleanup.
- [x] 2.6 Separate proposed deltas from accepted current specifications and associate explicit-base current changes by ID and content.
- [x] 2.7 Validate completed base-active and newly introduced same-diff archives without perpetual bookkeeping changes; reject unrelated coverage, incomplete tasks, missing live links, and removal mismatches.
- [x] 2.8 Support generic stable capability prefixes, scenario-free removals, and an explicit batch-wide tooling scope registry.

## 3. Parallel integration (separate ownership; not completed by artifact validation)

- [x] 3.1 Integrate and locally verify the shared bounded static registry, npm/Make entrypoints, CI configuration, and explicit-base mode. Remote CI execution is not claimed.
- [x] 3.2 Verify fail-closed compiler classification and baseline-preserving type-gate behavior.
- [x] 3.3 Integrate focused tooling lint and execute the complete script-test suite.
- [x] 3.4 Inspect independent Stand dev source guidance and links without installing or editing installed plugins. Author word-for-word approval is still pending.

## 4. Acceptance

- [x] 4.1 Run the SHA-256-verified upstream OpenSpec 2.0.0 binary in isolation: three items passed strict validation. The optional automated download path failed on local network access and is not claimed verified.
- [x] 4.2 Run the shared static profile: 26/37 passed. All 11 failing checks were reproduced on the original baseline; no gate or baseline was weakened.
- [x] 4.3 Confirm human-owned AGENTS.md, the installed plugin, application VERSION and quality baselines remain byte-identical to the recorded starting state. The approved proposal/design are preserved.
- [ ] 4.4 Complete author wording review and remote CI acceptance. Local manual/link review does not replace these steps.

Checked items describe executed local work only. The change remains active while author review, baseline gate repairs, and remote CI acceptance are pending. Local tooling tests passed 53/53; rulebook static tests passed 9/9; the focused seed-manifest suite passed 4/4 with JDK 23. Full backend/browser suites, Node 20 execution, and Windows process-tree cleanup were not run.
