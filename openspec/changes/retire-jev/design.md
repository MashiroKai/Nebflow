# Design

JeV configuration, provider integrations and active user interfaces are removed; what remains is the
plugin allocation path, which now runs on the shared small-LLM policy.
`src/main/scala/nebflow/llm/decision/PluginAllocatorMachine.scala` routes the allocator face to
`src/main/scala/nebflow/llm/decision/LlmAllocator.scala`, whose candidate source is
`SchemePolicy.smallLlmModel()` (read at `LlmAllocator.scala:241`; the policy accessor is
`src/main/scala/nebflow/core/SchemePolicy.scala:106`), with an unconfigured small model falling back
to the main model and logging a warning through `src/main/scala/nebflow/core/ModelRoles.scala:249`.
Each allocation emits one auditable decision line carrying the selected plugins and a concise
rationale. Task search without an installed reranker returns its original ordering without an error
or a warning, and configuration without a JeV block loads successfully with no JeV surface exposed.
The JeV-specific probe surface was deleted with the retirement (the former
`src/main/resources/web/js/sidebar/jevTestButton.js` and its specs no longer exist), which is
recorded as a deletion in `openspec/changes/frontend-forms-repairs/trace.json`.
