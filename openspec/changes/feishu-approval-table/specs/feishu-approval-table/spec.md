## ADDED Requirements

### Requirement: [FAT-REQ-001] Feishu tables render as labeled rows

Ordinary Feishu replies SHALL render GFM tables as narrow-screen readable labeled rows while
preserving cell content, SHALL NOT change Weixin output or local transcripts, and SHALL keep the
original table instead of dropping data when parsing is ambiguous.

#### Scenario: [FAT-SCN-001] Table rendering

- **WHEN** an ordinary reply carries a GFM table, including ambiguous or fenced input
- **THEN** the table becomes labeled rows and an ambiguous or fenced table is left unchanged

### Requirement: [FAT-REQ-002] Permission requests reach the bound conversation exactly once

A pending tool permission request SHALL be delivered to the bound Feishu conversation and SHALL
accept only authenticated, request-bound allow/deny callbacks that reach the original pending
interaction exactly once; expired, unauthorized or mismatched callbacks SHALL NOT approve anything.
Live delivery in an isolated instance is a separate acceptance step and is NOT proved by the
offline specs.

#### Scenario: [FAT-SCN-002] Callback binding

- **WHEN** an allow or a deny callback arrives for a delivered permission request
- **THEN** only an authenticated callback bound to that request approves or denies it, and a stale, foreign or racing callback changes nothing
