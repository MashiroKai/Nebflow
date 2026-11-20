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
