## Why

External CLI delegates currently inherit the CLI's own model choice and the subagent conversation cannot configure it.

## What Changes

- Add owner-editable per-executor defaults, with empty meaning CLI default and a clear future-dispatch scope.
- Read settings on spawn and persist the chosen ID for continuations.
- Keep unsupported headless executors visibly unsupported; do not substitute built-in provider models.

## Impact

Executor configuration API, external delegate persistence and launch arguments, and the background conversation popup. Existing records without a model retain CLI-default behavior.
