## ADDED Requirements

### Requirement: Office preview cache follows source content
The office preview cache key SHALL derive from the complete source bytes, source extension, and converter-leg signature, not the source modification time or path. A changed source MUST NOT reuse the previous key even when size and modification time are unchanged; identical bytes, extension and converter signature MUST share a key.

#### Scenario: Same-size replacement with unchanged timestamp
- **GIVEN** an office source has a cached key
- **WHEN** its bytes are replaced with different bytes of the same length and its timestamp restored
- **THEN** the key differs from the cached key

#### Scenario: Timestamp-only change
- **GIVEN** unchanged source bytes and converter signature
- **WHEN** the modification timestamp changes
- **THEN** the key remains unchanged

#### Scenario: Different conversion legs
- **GIVEN** unchanged source bytes
- **WHEN** the converter-leg signature changes
- **THEN** the key changes
