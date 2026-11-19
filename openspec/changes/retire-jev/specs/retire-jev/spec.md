## ADDED Requirements

### Requirement: [RJV-REQ-001] Plugin allocation uses the shared small-LLM policy

Plugin allocation SHALL use `SchemePolicy.smallLlmModel()` and SHALL emit an auditable decision line
carrying the selected plugins and a concise rationale. An unconfigured small model SHALL follow the
policy's main-model fallback. Task search without an installed reranker SHALL return its original
ordering without an error or a warning, and configuration without a JeV block SHALL load
successfully with no JeV-specific configuration or control exposed.

#### Scenario: [RJV-SCN-001] Allocation and fallback

- **WHEN** plugin allocation runs with and without a configured small model
- **THEN** it allocates through the small-LLM candidate chain, falls back to the main model with a warning when unconfigured, and records exactly one decision line

#### Scenario: [RJV-SCN-002] Degraded and retired surfaces

- **WHEN** task search runs without an installed reranker, or configuration without a JeV block is loaded
- **THEN** search keeps its original ordering without error or warning, and the configuration loads with no JeV surface exposed
