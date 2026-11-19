## ADDED Requirements

### Requirement: [AHR-REQ-001] AskHost request identity survives an interrupted wait

An AskHost request SHALL keep its original correlation identity across an interrupted wait, and an
answer delivered under that same request id SHALL reach the pending interaction exactly once. A
second, conflicting decision for the same request SHALL be refused. Recovery SHALL NOT infer
request identity from question text. The combined end-to-end scenario *real actor + production
startup scan + process restart* is NOT yet proved by an automated test.

#### Scenario: [AHR-SCN-001] Delayed answer to a recovered request

- **WHEN** a crash-interrupted AskHost request is recovered and its original answer arrives late
- **THEN** the recovery accepts it by the original request id and returns it as the original tool result

#### Scenario: [AHR-SCN-002] Duplicate and conflicting replies

- **WHEN** an identical reply is delivered twice, or a different decision is delivered for the same request
- **THEN** the identical repeat is accepted only once and the conflicting decision is refused

#### Scenario: [AHR-SCN-003] Mailbox suppression after consumption

- **WHEN** the durable mailbox has already taken and consumed a request
- **THEN** the request is not re-enqueued by replay
