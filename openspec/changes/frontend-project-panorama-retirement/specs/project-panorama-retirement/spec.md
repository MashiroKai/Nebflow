## ADDED Requirements

### Requirement: [FRB-REQ-002] Project panorama entry is retired
The project screen SHALL NOT launch the retired global or project delegate panorama; task-specific delegate details and background lists SHALL remain usable.

#### Scenario: [FRB-SCN-003] Project list panorama entry
WHEN an empty or populated project is displayed THEN neither its header nor project FAB can launch panorama, and project list rendering and unrelated task-specific entry points remain intact.

### Requirement: [FRB-REQ-003] Retire only done-board cleanup reminders
The periodic watcher SHALL NOT issue managed-worktree done-board cleanup reminders. Other completion delivery and orphan/failure monitoring SHALL remain connected; existing records, branches and worktrees SHALL remain untouched.

#### Scenario: [FRB-SCN-004] Periodic watcher after retirement
WHEN the periodic watcher runs THEN it does not invoke the done-board cleanup scan, while completion delivery and orphan scans remain in the chain.
