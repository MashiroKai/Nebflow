# Build-embedded System Prefix

## Why
Authors need one reviewable, product-wide source for shared system-prompt guidance without a mutable runtime prefix.

## Scope
Replace the optional data-root file read with the packaged `seed/prompt-prefix.md` classpath resource. Prepend it before agent prompts and conditional blocks; retain the cached-prompt lifecycle. The external data-root file is no longer read or installed. Resource updates require a build; deployment and removal of any existing external file are outside this change.

## Acceptance
Focused assembly and wire tests verify the packaged prefix, its ordering, the five CLI families and universality rule, and that an external file cannot override it. The existing change's specification records the semantic replacement.
