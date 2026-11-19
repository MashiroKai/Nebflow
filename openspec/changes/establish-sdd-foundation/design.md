## Context

The Stand baseline is main@4529e84e0d0bff0633da683361785c2b8296d76c. The independently maintained Stand dev baseline is main@0c6672e58783857c082bd099fe6aaf82ba5bf499. Both implementations use the branch feat/sdd-engineering-foundation. These identifiers describe the starting point, not a claim of completed verification.

AGENTS.md is human-owned and stays byte-for-byte unchanged. The installed plugin stays unchanged. Existing ignored .nebflow records remain historical evidence; no archive is moved or presented as an original design that was never recorded.

## Goals and Non-Goals

Goals: make future changes traceable to intent and acceptance; provide progressive engineering guidance; close false-green checks; reuse existing tools and expose one static entrypoint.

Non-goals: redesign the actor runtime, migrate all application code, fix every existing CI defect, convert all historical documents, install a running plugin, or add another workflow engine.

## Decisions

1. Use OpenSpec's current-specification and change-delta model. Pin the upstream validator independently of Stand's Node runtime; never run initialization against human instructions. Upstream validation checks specification structure, not implementation correctness.
2. Keep engineering contracts in tracked openspec/. Keep temporary evidence in .nebflow/evidence/. A minimal machine-readable trace file may supplement the Markdown tasks so links can be checked without parsing arbitrary prose. It must not duplicate requirement text or execute arbitrary record-supplied shell commands.
3. Each acceptance mapping identifies a stable requirement/scenario, implementation paths, and test references or a reasoned manual-verification exception. Historical archives are validated structurally without requiring deleted implementation files to reappear. Changed-path coverage requires an explicit Git base and errors on an invalid base; the initial automated coverage scope is declared rather than claimed repository-wide. A new active deletion claim must identify a path removed relative to that base. A historical active deletion may persist only when the path is absent both now and at the base, and the same trace file and requirement/scenario mapping already declared that exact deletion at the base. Editing other links does not invalidate this history; moving a claim to another mapping or change does not inherit it. Historical claims do not exempt unrelated paths from diff coverage. Newly introduced same-diff archives still require deletions in that diff.
4. Reuse the existing check scripts in a small static command registry with explicit argument arrays, bounded subprocesses, propagated exit status, and testable failure behavior. Keep compile, browser, full backend, and runtime checks separate and accurately named.
5. Preserve the current formatter for Scala. Add pinned JavaScript lint only for the new/modified tooling scope. Semantic naming quality and needless abstraction remain review concerns.
6. Refactor the type gate so compiler failures are not converted to a zero-error report. Only a recognized diagnostic failure can enter baseline comparison; configuration errors, unexpected output, spawn failures, signals, and timeouts fail closed.
7. Keep reusable engineering rules in the standalone plugin source, not in a second rulebook inside openspec/. Entry prompts remain short and stable English. Existing authoritative design tokens and component contracts are referenced rather than copied.

## Alternatives

A new custom SDD framework was rejected because OpenSpec already supplies the required artifact model. A repository-wide mandatory historical backfill was rejected because it would interrupt development and invent missing intent. Requiring a full document set for every typo was rejected because ceremony must be proportional to behavior and risk. Making every test suite blocking immediately was rejected for this bounded change: the new gates will block, while existing advisory coverage remains explicitly documented rather than silently reclassified.

## Risks and Mitigations

Plugin source and installed content differ: only source is edited and installation remains a separate authorized step. Existing static checks may already fail: report baseline defects instead of loosening checks. Multiple CI definitions can drift: delegate shared static execution to the same entrypoint. A trace record can be internally complete yet incorrect: do not advertise link checks as semantic verification. Archive paths can disappear: validate historical references against their recorded revision or restrict archive checks to stable structure.

## Verification

Use unit tests for positive and negative trace cases, check-runner failures, and compiler failure classification. Run upstream OpenSpec validation in isolation, plugin link/structure checks, tooling lint, focused static checks, and protected-file digest comparison. Record actual commands and outcomes; mark unexecuted CI and application suites as unverified. No host service is started or restarted.
