## ADDED Requirements

### Requirement: [PLUGIN-SELFSERVE-REQ-001] Discover plugin capabilities without project context
The CLI SHALL allow read-only listing, searching and inspection of mounts without a project context.

#### Scenario: [PLUGIN-SELFSERVE-SCN-001] Query the registry
WHEN an agent invokes a discovery command without a project context THEN the CLI reads the selected data root without modifying it.

### Requirement: [PLUGIN-SELFSERVE-REQ-002] Install and restore local plugin packages
The CLI SHALL validate local package structure before installation, preserve an existing version in a timestamped backup, and support rollback from a backup. It SHALL support a selected data root and a no-write dry run.

#### Scenario: [PLUGIN-SELFSERVE-SCN-002] Install and roll back a valid package
WHEN a valid package replaces an existing installation and rollback is requested THEN the CLI retains a backup and restores the most recent backup.

#### Scenario: [PLUGIN-SELFSERVE-SCN-003] Reject an invalid package
WHEN a package is structurally invalid THEN installation fails with detected errors and does not write the data root.

#### Scenario: [PLUGIN-SELFSERVE-SCN-004] Preview an installation
WHEN an installation is requested with dry-run THEN the CLI validates and reports the operation without writing the data root.

#### Scenario: [PLUGIN-SELFSERVE-SCN-005] Reject unsafe package paths
WHEN the package has an unsafe name or contains a symlink THEN the CLI rejects installation without writing the data root.
