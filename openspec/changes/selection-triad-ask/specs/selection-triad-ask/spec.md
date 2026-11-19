## ADDED Requirements

### Requirement: [STA-REQ-001] Selection actions in main chat

A text selection in main chat SHALL be copyable, quotable through the existing composer reference,
or explainable through the existing Ask channel, and the explanation SHALL appear in a closable
panel. When nothing is selected the existing whole-message actions SHALL be unchanged. Friend-chat
and canvas surfaces, and multiple accumulated selections, are NOT implemented in this change and
SHALL NOT be reported as done; no file binding SHALL be fabricated for a surface that has none.

#### Scenario: [STA-SCN-001] Copy, quote and explain a main-chat selection

- **WHEN** text is selected in main chat and a selection action is invoked
- **THEN** exactly the selected text is copied or quoted, or a closable explanation is produced

#### Scenario: [STA-SCN-002] No selection keeps existing actions

- **WHEN** no text is selected in main chat
- **THEN** the existing whole-message actions behave exactly as before
