## ADDED Requirements

### Requirement: [RV-REQ-001] Limit the root user-visible face

Root session messages SHALL expose only report block bodies on user-facing surfaces. Non-root session messages SHALL retain their existing presentation. Outside content SHALL be available in the existing turn badge.

#### Scenario: [RV-SCN-001] Extract root report bodies

- **WHEN** a root message contains multiple report blocks and outside text
- **THEN** only trimmed block bodies are selected for user-facing text
- **AND** outside text remains available on the folded process face

### Requirement: [RV-REQ-002] Separate child progress from final results

Child intermediate report blocks SHALL use a nonterminal parent progress channel. Final messages SHALL be delivered in full only by the existing result channel.

#### Scenario: [RV-SCN-002] Exclude final report from progress

- **WHEN** a child message is final
- **THEN** its report block is not separately delivered on the progress channel

### Requirement: [RV-REQ-003] Retire the output block kill switch

The `outputBlock.enabled` switch, its shared reader and its web flag SHALL be removed rather than defaulted. Prompt discipline surfaces SHALL inject unconditionally so that no configuration state can produce a full-text fallback.

#### Scenario: [RV-SCN-003] A retired key cannot change any face

- **WHEN** any configuration value is written under `outputBlock.enabled`
- **THEN** the root prompt, the delegate brief, the node footnote, the subagent wrapper and the channel verdict are byte-identical to the no-configuration case
- **AND** the report discipline line is still present on every one of those faces
