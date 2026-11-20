# Report block visibility design

## Scope

Only the root session uses report blocks as the user-visible face. The actor stamps source identity using RootAgentIdentity, and downstream consumers must preserve the stamp through live and history paths. Window identity and session IDs are not root predicates. Non-root sessions retain their existing display. Root social output is filtered by the bound emitting session identity; unknown identity must not be guessed. Internal handoffs and final results remain unfiltered.

## User face

Paired `<report>...</report>` blocks yield their trimmed bodies in order separated by blank lines. Empty and absent blocks produce no visible text; process content is folded into the existing tool badge. A turn without report blocks reuses the badge rather than adding a second badge. Quoted text, inline code and fenced code do not define blocks. Nested, stray or unclosed tags fail closed. Old `<output>` tags are ordinary text for root; no alias or full-text kill-switch is proposed.

## Parent face

Child reports are a separate nonterminal channel, independent of root identity. Intermediate report bodies go to the parent; blocking Subagent delivery queues to its next turn boundary, while asynchronous children use their normal nonterminal route. Progress is at-least-once with bounded correlation-ID deduplication and fail-open errors. Final text is delivered unchanged by the existing result channel; its report blocks must be structurally excluded from the progress channel. No terminal accounting changes.

## Open implementation risks

Source identity and report routing must be carried through all live, persistence, and social paths. Stable report IDs require recovery and redispatch coverage and must distinguish consecutive accepted messages. Real actor tests, visual evidence, and channel verification remain required. External executors and chain out-edge recipients need separate applicability analysis.
