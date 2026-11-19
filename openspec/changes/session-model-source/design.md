# Design

A session's explicit model overrides its role setting until the user restores following. The override
lives in `SharedResources.sessionModelOverrides` (`src/main/scala/nebflow/agent/SharedResources.scala:66`)
and is written and cleared by `src/main/scala/nebflow/gateway/WsConfigHandlers.scala:433-489`, which
emits the `sessionModelSet` frame and re-runs the provider reload with the override map so a role
setting save does not disturb existing session choices. Resolution is
`ActionableModel.effectiveModel` / `slotChainOf` in `src/main/scala/nebflow/core/ModelRoles.scala:293-320`,
where a pinned session keeps its own chain ahead of the slot chain. Both streaming and non-streaming
requests send only the explicit session model when one is present. An explicitly selected model never
falls back to a different model on failure: `src/main/scala/nebflow/llm/registry.scala:518` drops a
stale override instead of silently substituting another provider, and an unavailable selection is
reported with its reason rather than replaced.
