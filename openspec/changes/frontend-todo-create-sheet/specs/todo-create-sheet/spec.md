## ADDED Requirements

### Requirement: [FTI-REQ-003] Mobile creation sheet
The actual mobile task entry SHALL expose a bottom creation sheet without dimming the page or changing the underlying list geometry; cancel SHALL not create an item.

#### Scenario: [FTI-SCN-006] Actual entry creation
WHEN the user enters tasks via the mobile chrome and opens create THEN a bottom-anchored sheet supports name and multiline detail, cancel and save; opening and closing SHALL not move existing items or grow the underlying page, including keyboard and reduced-motion states.

### Requirement: [FTI-REQ-004] Confirm creation without losing the draft
The sheet SHALL retain its draft until a correlated server acknowledgement. Offline, rejected, disconnected and unconfirmed writes SHALL provide visible feedback and permit retry without creating duplicate records.

#### Scenario: [FTI-SCN-007] Retry an unconfirmed creation
WHEN a create attempt is rejected, disconnected or unconfirmed THEN the draft remains available and retry uses the same operation identity; only the matching success acknowledgement closes it.

### Requirement: [FTI-REQ-005] Reply to a todo in the primary chat
The mobile todo detail SHALL send a reply addressed to the primary chat without replacing its draft or attachments. Failed sends SHALL leave the reply available and explain the failure.

#### Scenario: [FTI-SCN-008] Reply while another view is active
WHEN a user replies from a todo while another chat view is active THEN the reply targets the primary session exactly once, leaves both composers unchanged, and retains the reply if delivery cannot be attempted.
