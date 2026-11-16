## ADDED Requirements

### Requirement: Author input resolves todo identifiers and message cards

Console author text containing a leading todo reply `#title·id` or a message card SHALL be resolved before agent delivery without changing the stored author transcript. Todo identity is the eight-hex scheduled-task ID, not a message-card ID. Todo body is `detail` when present, otherwise `content`. Short referenced bodies of at most 4,000 UTF-16 code units SHALL be injected verbatim. Longer bodies SHALL be represented by the first 500 UTF-16 code units and a full-body query handle. Todo handles use `Schedule {action:"get", id:"..."}`; message cards use authenticated `GET /api/sessions/msg-token/<sid8-seq6>`. Structured chat quotes carry a complete client snapshot; a long snapshot is stored as a new message-token mapping before a handle is emitted. This is a snapshot of what the client supplied, not a verified durable source-message identity. Legacy snapshots without the completeness flag are explicitly unresolved rather than silently treated as complete. Quoted attachment names, types, positions and client-declared unverified paths are injected; image bytes are not replayed, which is explicitly disclosed. Handles can fail after deletion. Missing or unresolvable identifiers SHALL produce an explicit marker and a diagnostic warning. Agent-to-agent message-card expansion remains unchanged.

#### Scenario: Short reference
- **WHEN** author input references an existing short body
- **THEN** the title and complete original body are injected to the agent

#### Scenario: Long reference
- **WHEN** an existing body exceeds 4,000 UTF-16 code units
- **THEN** the first 500 units and a query handle are injected instead of the complete body

#### Scenario: Missing identity
- **WHEN** the ID does not resolve
- **THEN** the agent receives an explicit unresolved marker and a diagnostic warning is recorded
