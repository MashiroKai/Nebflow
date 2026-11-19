## ADDED Requirements

### Requirement: [DCM-REQ-001] Decision cards are a read-only projection of scheduled todos

The decision-card surface SHALL read `ScheduledTask(kind="todo")` as a read-only projection and
SHALL decide completion by `completedAt`; it SHALL NOT migrate, dual write, switch or delete the
legacy entry. New cards SHALL be written only as `ScheduledTask` todos marked with project
`__decision_card_mvp__`. `project` is a nullable free-text tag rather than a project id, so
project-matched badges are out of scope. Card replies are not wired and SHALL NOT be reported as
confirmed.

#### Scenario: [DCM-SCN-001] Projection and legacy exclusion

- **WHEN** the decision-card surface lists cards and the legacy todo inbox lists its rows
- **THEN** the cards are projected read-only from todo rows and the legacy inbox excludes the rows whose project is the decision-card marker

#### Scenario: [DCM-SCN-002] Completion and unique count

- **WHEN** entries are counted and a card is completed
- **THEN** the count covers visible, undecided unique ids and the completed card is no longer counted while its row is retained
