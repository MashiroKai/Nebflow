# System Prefix

## ADDED Requirements

### Requirement: Optional shared system prefix
At system-prompt assembly, the engine MUST read `prompt-prefix.md` from the configured data root (by default `~/.stand`) and, when its content is not whitespace-only, prepend it under the `--- SYSTEM PREFIX ---` marker before the agent prompt and conditional blocks. Missing or whitespace-only files MUST produce no prefix or empty marker. The file is read again on each assembly; existing cached session prompts retain their existing lifecycle.

#### Scenario: Prefix exists
- **WHEN** a system prompt is assembled with a nonempty prefix file
- **THEN** the marked file content precedes the agent prompt and conditional blocks

#### Scenario: Prefix absent or empty
- **WHEN** the file is missing or whitespace-only
- **THEN** the assembled prompt has neither prefix nor marker and retains the existing ordering

### Requirement: Reviewable CLI template
The repository MUST provide a non-deployed template describing recall, read-only message queries, plugin discovery/install/preview/rollback, and the three branch-guard checks, with examples. It MUST NOT automatically install the template into the live data root.
