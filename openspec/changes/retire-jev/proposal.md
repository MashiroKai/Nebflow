# Retire JeV decision services

## Intent

Remove JeV configuration, provider integrations and active user interfaces. Plugin allocation uses the shared small-LLM model policy and records each decision with selected plugins and a concise rationale. Existing archived records remain readable.

## Scenarios

- Allocation uses `SchemePolicy.smallLlmModel()` and emits an auditable decision line. An unconfigured small model follows the policy's main-model fallback.
- Task search without an installed reranker returns its original ordering without error or a warning.
- Delegate terminal judgment uses the existing non-JeV model chain and retains its existing failure behavior.
- Configuration without a JeV block loads successfully; JeV-specific configuration and controls are no longer exposed.

## Verification

Use mocked providers, not real network calls. Show failing-before and passing-after evidence for allocation, search degradation, terminal judgment and configuration. Run the applicable targeted suites and synchronize with main before delivery.
