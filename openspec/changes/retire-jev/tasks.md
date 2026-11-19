# Tasks

- [x] Remove JeV configuration, provider integrations and active user interfaces.
- [x] Route plugin allocation through `SchemePolicy.smallLlmModel()` and record an auditable decision line with the selected plugins and a rationale.
- [x] Fall back to the policy's main model with a warning when the small model is unconfigured.
- [x] Return the original task-search ordering, without error or warning, when no reranker is installed.
- [x] Keep archived records readable and load configuration without a JeV block.
- [ ] Post-retirement failing-before / passing-after evidence with mocked providers for all four legs (allocation, search degradation, terminal judgment, configuration) — NOT executed in this batch.
- [ ] Targeted suites and synchronisation with main before delivery — NOT executed in this batch.
