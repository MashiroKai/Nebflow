## ADDED Requirements

### Requirement: [CRA-REQ-001] Credential-bearing saves persist references, not values

A credential-bearing configuration save SHALL store the value in an owner-only secret file and SHALL
persist a reference in the configuration. Runtime resolution and user reveal SHALL resolve both
legacy inline values and references, while agent-facing projections SHALL NOT return values. A
managed reference read SHALL record the reader and the reference without a value; direct shell or
filesystem reads are outside this mechanism.

#### Scenario: [CRA-SCN-001] A save stores a reference

- **WHEN** a credential-bearing configuration is saved
- **THEN** the value lands in an owner-only (0600) secret file and the configuration carries a reference rather than the value

#### Scenario: [CRA-SCN-002] Agent-facing projections stay value-free

- **WHEN** an agent-facing surface reads the stored credential
- **THEN** it receives the reference or a refusal, never the plaintext value
