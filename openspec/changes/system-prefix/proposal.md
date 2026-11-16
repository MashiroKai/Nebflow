# Optional System Prefix

## Why
Authors need a single reviewable file for shared system-prompt guidance, including the CLI suite.

## Scope
Read the optional data-root prefix at prompt assembly; prepend a marked nonempty prefix. Preserve the existing cached-prompt lifecycle. Ship a source template only, without deploying or changing live configuration.

## Acceptance
Focused assembly tests cover nonempty, missing, and whitespace-only files and rereading on assembly. The template covers the four CLI families and their safety boundaries.
