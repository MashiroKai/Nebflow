## Why

Stand has engineering checks and design records, but they do not form one reproducible connection between intent, implementation, and verification. Some checks can report success after tool failure. Detailed agent instructions duplicate the rulebook and historical records are not a versioned behavior baseline.

## What Changes

- Introduce versioned OpenSpec capability specifications and change records for future work.
- Make the independently versioned Stand dev plugin a progressively loaded engineering rulebook, without changing human-owned AGENTS.md or installed plugins.
- Add a small traceability check and regression tests; distinguish structural completeness from executed verification.
- Share static checks between local development and CI, repair fail-open type-check handling, and lint new tooling.

## Capabilities

### New Capabilities
- `engineering-workflow`: proportional specifications, stable intent, progressive rules, and traceable changes.
- `quality-gates`: reproducible, fail-closed static verification and check-runner behavior.

## Impact

Affected surfaces: `openspec/`, development scripts and tests, Makefile, npm tooling, CI definitions, contributor guidance, and the separate Stand dev source repository. No application runtime behavior changes. No retrospective conversion of old design archives. No deployment, merge, push, or host restart.
