## ADDED Requirements

### Requirement: [FRB-REQ-001] Mobile background details omit retired metadata
The mobile background-agent detail SHALL omit plugin and worktree metadata while retaining source, creation time and task details. Desktop detail SHALL remain unchanged.

#### Scenario: [FRB-SCN-001] Mobile detail with legacy metadata
WHEN a delegate record contains plugin and worktree fields and the user opens mobile details THEN neither field is presented, source, creation and task remain readable, and dismissing details works.

#### Scenario: [FRB-SCN-002] Desktop remains unchanged
WHEN the same record opens on desktop THEN the existing desktop detail and controls remain available and no mobile detail shell is installed.
