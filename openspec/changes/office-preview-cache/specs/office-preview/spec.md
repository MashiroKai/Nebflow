## ADDED Requirements

### Requirement: [OF-REQ-001] Office preview cache follows source content

The office preview cache key SHALL derive from the complete source bytes, source extension, and converter-leg signature, not the source modification time or path. A changed source MUST NOT reuse the previous key even when size and modification time are unchanged; identical bytes, extension and converter signature MUST share a key.

#### Scenario: [OF-SCN-001] Same-size replacement with unchanged timestamp

- **GIVEN** an office source has a cached key
- **WHEN** its bytes are replaced with different bytes of the same length and its timestamp restored
- **THEN** the key differs from the cached key

#### Scenario: [OF-SCN-002] Timestamp-only change

- **GIVEN** unchanged source bytes and converter signature
- **WHEN** the modification timestamp changes
- **THEN** the key remains unchanged

#### Scenario: [OF-SCN-003] Different conversion legs

- **GIVEN** unchanged source bytes
- **WHEN** the converter-leg signature changes
- **THEN** the key changes

### Requirement: [OF-REQ-002] Office documents prefer converted PDF

For a DOCX or XLSX local-file tab, the viewer SHALL request the existing authenticated office conversion service first and display its PDF using the PDF viewer. If conversion is unavailable or fails, the existing format-specific frontend viewer SHALL remain available and visibly indicate the fallback. Blob-only attachments retain their existing viewer.

#### Scenario: [OF-SCN-004] Converted preview available

- **WHEN** office conversion returns a PDF
- **THEN** the tab displays the PDF and does not fetch the original for frontend conversion

#### Scenario: [OF-SCN-005] Conversion fails

- **WHEN** the office conversion request fails
- **THEN** the tab displays the original via its frontend viewer and a visible fallback notice
