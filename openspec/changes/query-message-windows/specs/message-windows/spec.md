## ADDED Requirements

### Requirement: [MW-REQ-001] Read-only message-window query
The CLI SHALL query retained session UI message windows without writing to the data root, and support window, bound channel, sender, inclusive time range, keyword, human table and JSON filters/output. It SHALL exclude credential-like text and tool payloads.

#### Scenario: [MW-SCN-001] Filter retained window messages
- **WHEN** an agent queries a fixture root with window, channel, sender, time or keyword filters
- **THEN** matching user, AI and agent text records are returned in timestamp order, with their session and binding attribution.

#### Scenario: [MW-SCN-002] Sensitive and invalid inputs
- **WHEN** a message resembles credentials, or the query arguments or input store are invalid
- **THEN** sensitive text is not printed, and invalid inputs are reported as errors rather than empty success.
