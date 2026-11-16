## ADDED Requirements

### Requirement: [SLLM-REQ-001] Shared lightweight model resolution
The application SHALL expose an independently configurable model slot and shared accessor for lightweight calls, using existing model-slot parsing and main-chain resolution conventions.

#### Scenario: [SLLM-SCN-001] A lightweight model is configured
- **WHEN** the small-model sidecar has a usable model chain
- **THEN** the accessor returns that chain ahead of the main chain

#### Scenario: [SLLM-SCN-002] Lightweight model is not configured
- **WHEN** the small-model sidecar is absent or its model chain is empty
- **THEN** the accessor returns the resolved main chain and emits a WARN fallback message

### Requirement: [SLLM-REQ-002] Lightweight model settings
Desktop and mobile settings SHALL offer the shared small-model configuration using existing settings-row components.

#### Scenario: [SLLM-SCN-003] Configure and clear the small model
- **WHEN** a user selects a model in the small-model settings row or clears that selection
- **THEN** the small-model slot is saved or cleared independently
- **AND** the unconfigured row indicates fallback to the main model
