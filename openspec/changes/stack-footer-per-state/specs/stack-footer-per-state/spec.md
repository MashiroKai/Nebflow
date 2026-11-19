## ADDED Requirements

### Requirement: [SFP-REQ-001] The media-stack footer follows the expansion state

A collapsed media stack SHALL expose one message footer whose quote references the complete row and
whose download saves every row attachment as one ZIP. An expanded stack SHALL place a footer below
each media card with quote and direct download scoped to that card, and the group controls SHALL be
hidden while expanded. A member with neither path nor inline data SHALL disable the group button
with a visible reason, and a source that fails at runtime SHALL fail the group download.

#### Scenario: [SFP-SCN-001] Collapsed and expanded footer scope

- **WHEN** a media stack is collapsed or expanded
- **THEN** the collapsed quote and download cover the whole row (the ZIP carries every attachment) and the expanded controls cover only the card whose footer hosts them
