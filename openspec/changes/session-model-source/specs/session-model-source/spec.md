## ADDED Requirements

### Requirement: [SMS-REQ-001] An explicit session model overrides the role setting

A session's explicitly selected model SHALL override its role setting until the user restores
following, and changing a role setting SHALL affect only following sessions. Both streaming and
non-streaming requests SHALL send only the explicit session model when one is present, and an
explicitly selected model SHALL never fall back to a different model on failure.

#### Scenario: [SMS-SCN-001] Explicit model on stream and non-stream requests

- **WHEN** a session with an explicit model sends a streaming or a non-streaming request, and a role setting is saved meanwhile
- **THEN** only the explicit session model is used, and the role-setting save does not replace it

#### Scenario: [SMS-SCN-002] Unavailable selection

- **WHEN** the explicitly selected model is unavailable
- **THEN** the failure is reported with its reason and no other model is selected
