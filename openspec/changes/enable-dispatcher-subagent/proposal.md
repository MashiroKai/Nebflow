# Enable dispatcher Subagent with Bash reconnaissance

## Why

The user first requested Subagent support for dispatcher, then explicitly requested adding Bash to Subagent. No current accepted OpenSpec requirement covers these capabilities; they are behavior changes, not regression restorations.

## What changes

Allow modern `Delegate(mode="dispatcher")` to call the existing blocking Subagent. Extend the fixed Subagent face to Read, Glob, Grep, WebSearch, WebFetch and the existing Bash tool. Align role prompts and tool descriptions with shell-backed reconnaissance rather than claiming mechanical read-only safety.

## Boundaries

Bash can write files and execute programs; absence of Write/Edit tools does not make the child read-only. Keep the exact builtin child target, no messaging or recursive agent tools, and the dispatcher's task-chain execution discipline. Keep Ask sessions independently enumerated. A subsequent explicit user request adds Bash to Ask and retires its dedicated GitQuery tool. Preserve kernel rejection of other child definitions.

No new shell implementation, Bash schema, background-job lifecycle, legacy project-dispatcher tool grant, installation or deployment is included. Subagent instructions require completing foreground shell reconnaissance before returning. Prompt wording is a source draft pending author review.
