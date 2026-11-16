## Design

`executor.models` maps supported executor IDs to optional CLI model IDs. Owner-authenticated writes update one entry under the existing configuration lock. The external runner hot-reads once when admitting a new delegate and writes the selection to the delegate registry; resume reads that record, including the empty/default case. No running process is reconfigured.

Claude Code and Codex receive their verified `--model` argument. ZCode exposes an unsupported explanation rather than claiming support. The popup control edits a global executor default, not a session override. Suggestions are examples from CLI help, not a complete catalogue.

Older records lack the model field and continue using the CLI's default behavior. A default selection does not pin which model the CLI itself considers its default.

## Verification

`ExternalExecutorSpec` covers arguments, persistence and configuration writes. `tests/external-executor-model.spec.mjs` covers popup save and unsupported-state behavior using mocked HTTP. `npm run check:frontend` verifies existing frontend contracts. Full engine tests require the sbt gate.
