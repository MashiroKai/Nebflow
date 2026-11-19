## ADDED Requirements

### Requirement: [CBU-REQ-001] Measured button-family dispositions

`.canvas-button` SHALL remain a pure identity hook and the button group's material SHALL live in one
shared glass declaration. Every registered family's state-layer owner SHALL be measured from the CSS
tree, and the family ledger SHALL fail when its recorded `to:` disagrees with the measurement. A
family with no state-layer source SHALL pass only when it is named in `KNOWN_UNCOVERED` with a
checkable reason.

#### Scenario: [CBU-SCN-001] Measured family disposition

- **WHEN** the family ledger is checked against the CSS tree
- **THEN** every family disposition equals its measured value, and a deliberately wrong disposition fails the check

#### Scenario: [CBU-SCN-002] Two-tree computed comparison

- **WHEN** the measurement test compares the pre-consolidation tree with the current tree at 375x812 and 1280x800
- **THEN** the retired second Canvas definition changes only the declared `border-radius` and `box-shadow` readings and the other computed properties stay identical
