## ADDED Requirements

### Requirement: Office documents prefer converted PDF
For a DOCX or XLSX local-file tab, the viewer SHALL request the existing authenticated office conversion service first and display its PDF using the PDF viewer. If conversion is unavailable or fails, the existing format-specific frontend viewer SHALL remain available and visibly indicate the fallback. Blob-only attachments retain their existing viewer.

#### Scenario: Converted preview available
- **WHEN** office conversion returns a PDF
- **THEN** the tab displays the PDF and does not fetch the original for frontend conversion

#### Scenario: Conversion fails
- **WHEN** the office conversion request fails
- **THEN** the tab displays the original via its frontend viewer and a visible fallback notice
