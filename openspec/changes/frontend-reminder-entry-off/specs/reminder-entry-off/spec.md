## ADDED Requirements

### Requirement: [REO-REQ-001] No self-created reminder entry
Desktop and mobile todo surfaces SHALL NOT render or expose an entry to self-create a reminder. Existing reminders and agent-dispatched todos SHALL remain visible and operable. Empty states SHALL NOT invite creation.

#### Scenario: [REO-SCN-001] Browse without creating
WHEN a user browses an empty or populated todo surface, including its menus, blank area and keyboard navigation THEN no creation entry or creation sheet can be opened, while existing items remain visible and editable.
