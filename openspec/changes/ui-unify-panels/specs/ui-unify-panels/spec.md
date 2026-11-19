## ADDED Requirements

### Requirement: [UUP-REQ-001] One panel structure and identity alignment across secondary surfaces

Personal and contact identity presentations SHALL align on a shared centered column, secondary
panels SHALL use the existing Canvas control materials and dimensions, and settings SHALL be grouped
into general, models and services, runtime, advanced and about, and SHALL NOT render an account
group, an account card or a settings device slot (the account bar is retired by author order
2026-11-18). Contact save and cancel
actions SHALL exist only for pending changes while per-field persistence, readback and failure
recovery are retained. The static gate SHALL NOT be treated as an inventory of all dynamic controls
nor as evidence for rendered geometry, focus or occlusion.

#### Scenario: [UUP-SCN-001] Static panel contract

- **WHEN** the panel-uniform gate runs over the web sources
- **THEN** the two identity consumers, the contact bottom actions and close guard, and the five settings group identifiers are present in the contracted source markers, and the retired account group, account card and settings device slot are absent from the contracted sources

#### Scenario: [UUP-SCN-002] Pending-change-only contact actions

- **WHEN** a contact form has no pending change, and when it has one
- **THEN** the save and cancel actions are hidden while there is no pending change and shown once a field is edited
